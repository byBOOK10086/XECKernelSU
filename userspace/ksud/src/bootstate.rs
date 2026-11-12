//! Boot-state consistency pass (runs without any module installed).
//!
//! The device really is unlocked: `ro.boot.verifiedbootstate=orange`,
//! `ro.boot.flash.locked=0`, `ro.boot.vbmeta.device_state=unlocked` and at least
//! one `partition.*.verified` disabled. Property-based integrity checks read
//! exactly those names *and* the coherence rules that tie them together — a green
//! colour with an unlocked flag, or a green colour with a disabled partition, is
//! reported as a contradiction of its own — so this pass rewrites the property
//! view to what a locked user build reports and keeps every rule of that set
//! self-consistent.
//!
//! Mechanism: `resetprop` with `skip_svc`, i.e. the write goes straight into the
//! bionic property area that every read path resolves through, so the four read
//! paths of a property (reflection, `getprop`, native libc, `System.getProperty`)
//! stay in agreement. Hooking a single read path is what creates a visible
//! contradiction, so nothing here hooks anything.
//!
//! The pass runs on every boot before zygote starts, so `android.os.Build` and
//! the properties never disagree, and it is idempotent. The second half,
//! [`apply_bootparam_overlay`], stages a rewritten copy of `/proc/cmdline` and
//! `/proc/bootconfig` in RAM and binds it over the originals, so the raw boot
//! parameters carry the same values as the properties they are compared with;
//! [`harden_readability`] additionally removes an untrusted-app read grant for the
//! cmdline where the vendor policy has one.

use crate::defs;
use log::{info, warn};
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use std::ffi::CString;
use std::fs;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};

/// The property view of a locked user build.
const LOCKED_PROPS: &[(&str, &str)] = &[
    ("ro.boot.verifiedbootstate", "green"),
    ("ro.boot.flash.locked", "1"),
    ("ro.boot.vbmeta.device_state", "locked"),
    ("ro.boot.veritymode", "enforcing"),
    ("ro.boot.secureboot", "1"),
    ("ro.boot.vbmeta.invalidate_on_error", "yes"),
    ("partition.system.verified", "1"),
    ("partition.vendor.verified", "1"),
    ("partition.product.verified", "1"),
    ("partition.system_ext.verified", "1"),
    ("partition.odm.verified", "1"),
    ("ro.oem_unlock_supported", "0"),
    // ADB/debug properties belong to the configured ADB policy, not boot state.
];

/// Android 16 (API 36) stopped publishing this property, so any value found there
/// is reported as written by something other than AOSP; on older releases the
/// check only looks at `1`/`true`, which is why removing it is safe on both: an
/// absent property leaves the rule unevaluated instead of firing.
const OEM_UNLOCK_PROP: &str = "sys.oem_unlock_allowed";

/// Best effort: `deny` removes an existing allow rule, so a policy that never
/// granted untrusted apps access to `/proc/cmdline` simply has nothing to remove.
const CMDLINE_DENY: &[&str] = &[
    "deny appdomain proc_cmdline file read",
    "deny untrusted_app proc_cmdline file read",
    "deny untrusted_app_all proc_cmdline file read",
];

const fn resetprop() -> ResetProp {
    ResetProp {
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    }
}

/// Enabled by default; an explicit false value in the flag file disables it.
/// This changes the property/boot-parameter view only, not hardware attestation.
fn enabled() -> bool {
    fs::read_to_string(defs::BOOTSTATE_FLAG_PATH).map_or(true, |value| {
        !matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "0" | "off" | "false" | "no" | "disable" | "disabled"
        )
    })
}

/// Rewrite the boot-state properties to the locked view. Never fails the boot:
/// every step logs and continues, because a partially applied pass is still
/// better than none and the pass runs again on the next boot.
pub fn apply() {
    if !enabled() {
        info!("bootstate: disabled by {}", defs::BOOTSTATE_FLAG_PATH);
        return;
    }

    if let Err(e) = sys_prop::init() {
        warn!("bootstate: property API init failed: {e:#}");
        return;
    }

    let rp = resetprop();

    let mut applied = 0;
    for (name, value) in LOCKED_PROPS {
        if sys_prop::find(name).is_none() {
            continue;
        }
        match rp.set(name, value) {
            Ok(_) => applied += 1,
            Err(e) => warn!("bootstate: set {name}={value} failed: {e:#}"),
        }
    }

    match rp.delete(OEM_UNLOCK_PROP) {
        Ok(true) => info!("bootstate: removed {OEM_UNLOCK_PROP}"),
        Ok(false) => {}
        Err(e) => warn!("bootstate: delete {OEM_UNLOCK_PROP} failed: {e:#}"),
    }

    info!("bootstate: locked-view properties applied ({applied} properties)");
}

/// Remove the untrusted-app read access to the raw boot parameters when the
/// loaded policy has one. The property pass above and this rule are two halves of
/// the same statement: the runtime value is rewritten, so the raw value must not
/// stay readable for a comparison.
pub fn harden_readability() {
    if !enabled() {
        return;
    }

    for rule in CMDLINE_DENY {
        match crate::sepolicy::live_patch(rule) {
            Ok(()) => info!("bootstate: applied `{rule}`"),
            Err(e) => warn!("bootstate: `{rule}` not applied: {e:#}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Raw boot parameters
// ---------------------------------------------------------------------------

/// RAM-only staging directory for the rewritten boot parameters. It must not
/// live under `/data/adb`: a mount whose source path contains that string is
/// itself a signal for mount inspectors.
const OVERLAY_DIR: &str = "/dev/.ksud_hidden/bootparams";
const CMDLINE_PATH: &str = "/proc/cmdline";
const BOOTCONFIG_PATH: &str = "/proc/bootconfig";

/// `androidboot.*` keys whose runtime property counterpart [`apply`] rewrites.
/// Both sources have to agree: a reader that compares `ro.boot.*` with the raw
/// file reports a contradiction otherwise, and a reader that greps the raw file
/// for `verifiedbootstate=orange` or `vbmeta.device_state=unlocked` reads the
/// unlocked state straight out of it. Every other token is preserved verbatim, so
/// nothing else that parses these files changes behaviour.
#[path = "bootstate/params.rs"]
mod params;
use params::{rewrite_bootconfig, rewrite_cmdline};

/// Write the rewritten copy of `source` into the RAM staging directory.
fn stage(rewritten: &str, name: &str) -> Option<PathBuf> {
    if let Err(e) = fs::create_dir_all(OVERLAY_DIR) {
        warn!("bootstate: create {OVERLAY_DIR} failed: {e}");
        return None;
    }

    let path = Path::new(OVERLAY_DIR).join(name);
    if let Err(e) = fs::write(&path, rewritten) {
        warn!("bootstate: write {} failed: {e}", path.display());
        return None;
    }
    if let Err(e) = fs::set_permissions(&path, fs::Permissions::from_mode(0o644)) {
        warn!("bootstate: chmod {} failed: {e}", path.display());
    }
    Some(path)
}

fn bind_over(staged: &Path, target: &str) -> bool {
    let (Ok(source), Ok(destination)) = (
        CString::new(staged.as_os_str().as_encoded_bytes()),
        CString::new(target),
    ) else {
        warn!("bootstate: unusable path for {target}");
        return false;
    };

    // SAFETY: both paths are NUL-terminated C strings that outlive the call,
    // `fstype`/`data` are a literal and NULL as `MS_BIND` requires, and the
    // syscall only reads them.
    let rc = unsafe {
        libc::mount(
            source.as_ptr(),
            destination.as_ptr(),
            c"none".as_ptr(),
            libc::MS_BIND,
            std::ptr::null(),
        )
    };

    if rc == 0 {
        info!("bootstate: {target} now serves the staged copy");
    } else {
        warn!(
            "bootstate: bind {target} failed: {}",
            std::io::Error::last_os_error()
        );
    }
    rc == 0
}

fn unmount_owned(target: &str, name: &str) -> bool {
    let staged = Path::new(OVERLAY_DIR).join(name);
    // A bind mount exposes the backing file's device and inode. Only detach
    // our staged file; a mount installed by anyone else must remain untouched.
    let owned = fs::metadata(&staged)
        .and_then(|source| {
            fs::metadata(target).map(|destination| {
                source.dev() == destination.dev() && source.ino() == destination.ino()
            })
        })
        .unwrap_or(false);
    if !owned {
        // Fixed proc paths contain no mountinfo escapes. Refuse to cover another
        // component's mount, and fail closed if mount ownership cannot be read.
        return match fs::read_to_string("/proc/self/mountinfo") {
            Ok(mounts) => {
                let foreign = mounts
                    .lines()
                    .any(|line| line.split_whitespace().nth(4) == Some(target));
                if foreign {
                    warn!("bootstate: skip foreign mount at {target}");
                }
                !foreign
            }
            Err(error) => {
                warn!("bootstate: cannot inspect mounts for {target}: {error}");
                false
            }
        };
    }
    let Ok(destination) = CString::new(target) else {
        return false;
    };
    // SAFETY: destination is a NUL-terminated string valid for this call.
    if unsafe { libc::umount2(destination.as_ptr(), libc::MNT_DETACH) } == 0 {
        return true;
    }
    let error = std::io::Error::last_os_error();
    if matches!(error.raw_os_error(), Some(libc::EINVAL | libc::ENOENT)) {
        return true;
    }
    warn!("bootstate: unmount {target} failed: {error}");
    false
}

/// Stage and bind the rewritten boot parameters over `/proc/cmdline` and
/// `/proc/bootconfig`. Every step is best effort: a missing source, an unwritable
/// staging dir or a denied bind only costs that one file, and the property half
/// of the pass still applies.
pub fn apply_bootparam_overlay() {
    if !enabled() {
        unmount_owned(CMDLINE_PATH, "cmdline");
        unmount_owned(BOOTCONFIG_PATH, "bootconfig");
        return;
    }

    let mut applied = 0_u32;
    for (target, name, rewrite) in [
        (
            CMDLINE_PATH,
            "cmdline",
            rewrite_cmdline as fn(&str) -> String,
        ),
        (BOOTCONFIG_PATH, "bootconfig", rewrite_bootconfig),
    ] {
        // Detach only our previous copy before reading or overwriting its backing
        // file. Never stack a new bind if detaching that owned copy failed.
        if !unmount_owned(target, name) {
            continue;
        }
        let Ok(original) = fs::read_to_string(target) else {
            continue;
        };
        let rewritten = rewrite(&original);
        if rewritten != original
            && let Some(staged) = stage(&rewritten, name)
            && bind_over(&staged, target)
        {
            applied += 1;
        }
    }

    info!("bootstate: boot parameter overlay staged for {applied} file(s)");
}
