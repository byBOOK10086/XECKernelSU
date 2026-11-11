use anyhow::{Context, Result};
use log::{info, warn};
use rustix::cstr;
use std::os::unix::fs::PermissionsExt;
use std::{process::Command, time::Instant};

use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::{assets, defs, init_event, metamodule, restorecon, utils};

fn dump_process_info(label: &str) {
    use rustix::process::{getgid, getgroups, getpid, getuid};

    let pid = getpid().as_raw_nonzero();
    let uid = getuid().as_raw();
    let gid = getgid().as_raw();
    let groups: Vec<String> = getgroups()
        .unwrap_or_default()
        .iter()
        .map(|g| g.as_raw().to_string())
        .collect();
    let selinux = std::fs::read_to_string("/proc/self/attr/current")
        .unwrap_or_else(|_| "unknown".to_string());
    let seccomp = std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("Seccomp:"))
                .map(|l| l.trim().to_string())
        })
        .unwrap_or_else(|| "unknown".to_string());

    info!(
        "[{label}] pid={pid}, uid={uid}, gid={gid}, groups=[{}], selinux={}, {seccomp}",
        groups.join(","),
        selinux.trim(),
    );
}

pub fn run(package_name: &String, kmi: Option<String>, allow_shell: bool) -> Result<()> {
    utils::daemonize(false)?;
    info!("late-load command triggered!");
    dump_process_info("late-load start");

    // 1. Check if KernelSU is already loaded
    if ksuinit::has_kernelsu() {
        info!("KernelSU already loaded, skip loading ko");
    } else {
        // 2. Detect current KMI version
        let kmi = kmi.map_or_else(
            || crate::boot_patch::get_current_kmi().context("Failed to detect current KMI version"),
            Ok,
        )?;
        info!("Detected KMI: {kmi}");

        // 3. Get kernelsu.ko from embedded assets
        let ko_name = format!("{kmi}_kernelsu.ko");
        let ko_data = assets::get_asset_data(&ko_name)
            .with_context(|| format!("Failed to get {ko_name} from assets"))?;

        // 4. Load kernelsu.ko from memory with manual relocation
        info!("Loading kernelsu.ko for KMI {kmi}...");
        // bundled flag is meaningless in jailbreak mode since we can't flash boot to update it.
        let params = if allow_shell {
            cstr!("allow_shell=1")
        } else {
            cstr!("")
        };
        ksuinit::load_module(&ko_data, params).context("Failed to load kernelsu.ko")?;
        info!("kernelsu.ko loaded successfully!");
        dump_process_info("after load_module");
    }

    // SUSFS is a second, independent loadable module. It has to come after kernelsu.ko: its
    // susfs_hide_syms filters /proc/kallsyms and /proc/modules, and our symbol resolution
    // happens at module init time. Any failure here degrades to "no SUSFS", never to "no root".
    load_susfs_lkm_for_current_kmi();

    // We need to reset stdin/stdout/stderr; otherwise, sending file descriptors via cmd transactions
    // will be blocked by SELinux because its fsec->sid is still u:r:su:s0 instead of u:r:ksu:s0.
    utils::reset_std()?;

    utils::umask(0);

    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    utils::install(None, None).context("Failed to install ksud")?;

    crate::hot_install::clean_trash();

    // 5. Handle module updates
    if let Err(e) = handle_updated_modules() {
        warn!("handle updated modules failed: {e}");
    }

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // 6. Load SELinux rules
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    // 7. Initialize features
    crate::feature::init_forced_features();
    if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // 8. Execute late-load stage scripts with a shared boot deadline
    let wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);
    init_event::run_stage("late-load", wait);

    // 9. Load system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // 10. Execute metamodule mount script (OverlayFS)
    if let Err(e) = metamodule::exec_mount_script(defs::MODULE_DIR) {
        warn!("execute metamodule mount failed: {e}");
    }

    // 11. Execute post-mount stage scripts using the same deadline
    init_event::run_stage("post-mount", wait);

    // 12. Execute service stage scripts (non-blocking)
    init_event::run_stage("service", ScriptWait::NoWait);

    // 13. Execute boot-completed stage scripts (non-blocking)
    init_event::run_stage("boot-completed", ScriptWait::NoWait);

    // 14. Restart Manager so it gets a fresh ksu fd from the newly loaded kernel module
    info!("Restarting KernelSU Manager {package_name}...");
    let _ = Command::new("am")
        .args(["force-stop", package_name])
        .status();
    let _ = Command::new("am")
        .args([
            "start",
            "-n",
            &format!("{package_name}/me.weishu.kernelsu.ui.MainActivity"),
        ])
        .status();

    Ok(())
}

/// Bring SUSFS up on the boot path (`init_event` post-fs-data) as well as from `run` above.
///
/// `late-load` is only the "load the kernel module after boot" route; most devices flash a
/// boot image that already carries `kernelsu.ko`, in which case nobody calls late-load and the
/// post-fs-data hook is the only chance to load SUSFS.
///
/// Repeat calls are safe: `/proc/susfs_path` only exists once the module is loaded.
pub fn load_susfs_lkm_for_current_kmi() {
    if std::path::Path::new("/proc/susfs_path").exists() {
        info!("SUSFS already present, skip the boot-path load");
        return;
    }
    match crate::boot_patch::get_current_kmi() {
        Ok(kmi) => load_susfs_lkm(&kmi),
        Err(error) => warn!("cannot detect KMI for the SUSFS module: {error:#}"),
    }
}

/// Load the SUSFS loadable module built from `third_party/susfs4ksu-lkm`, if this ABI ships
/// one. It is deliberately not installed as a KernelSU/Magisk module: the `.ko` is embedded in
/// ksud and loaded in place, so nothing shows up in the user's module list.
fn load_susfs_lkm(kmi: &str) {
    if std::path::Path::new("/proc/susfs_path").exists() {
        info!("SUSFS already present, skip loading the bundled module");
        return;
    }

    let name = format!("{kmi}_susfs_guard_lkm.ko");
    let Ok(data) = assets::get_asset_data(&name) else {
        // x86_64 builds carry no SUSFS payload; that is expected, not an error.
        info!("no bundled {name}, skipping SUSFS");
        return;
    };

    info!("Loading {name}...");
    // expose_proc=1 is what creates the /proc/susfs_* control nodes ksud talks to.
    // avc_su_ctx has to be our own superuser domain: the upstream default belongs to another
    // variant, and a wrong value makes the avc spoof fail with -EOPNOTSUPP because the module
    // resolves the string through security_secctx_to_secid.
    let params = [
        "expose_proc=1".to_string(),
        "avc_su_ctx=u:r:su:s0".to_string(),
    ];

    // Primary path: write the .ko out and insmod it through ksud, whose loader resolves the
    // module's undefined symbols (KSU_IOCTL_LOOKUP_SYMBOLS, falling back to /proc/kallsyms).
    // SUSFS references many unexported kernel symbols; a pure in-memory loader that skips them
    // ends in "Unknown symbol".
    let path = std::path::Path::new(defs::BINARY_DIR).join(&name);
    let written = (|| -> Result<()> {
        std::fs::create_dir_all(defs::BINARY_DIR)?;
        std::fs::write(&path, data.as_ref())?;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
        Ok(())
    })();

    if let Err(error) = &written {
        warn!("cannot stage {name} to {}: {error:#}", path.display());
    }

    if written.is_ok() && crate::debug::insmod(&path, &params).is_ok() {
        info!("{name} loaded successfully (insmod)");
        return;
    }

    // Fallback: in-memory load. On the late-load route the driver may have just come up and
    // ksucalls has no fd yet, which fails insmod but not the in-memory loader.
    match ksuinit::load_module(&data, cstr!("expose_proc=1 avc_su_ctx=u:r:su:s0")) {
        Ok(()) => info!("{name} loaded successfully (in-memory)"),
        Err(error) => warn!("failed to load {name}: {error:#}"),
    }
}
