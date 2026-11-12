use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::utils::is_safe_mode;
use crate::{
    assets, defs, ksucalls, metamodule, restorecon,
    utils::{self},
};
use anyhow::{Context, Result};
use log::{error, info, warn};
use std::{path::Path, time::Instant};

pub fn on_post_fs_data() -> Result<()> {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_post_fs_data");
        return Ok(());
    }

    ksucalls::report_post_fs_data();

    utils::umask(0);

    // Locked-view properties first: the module scripts below (and every integrity
    // check that follows) read them, and zygote snapshots Build.* from the same
    // property area once post-fs-data returns.
    crate::bootstate::apply();
    crate::bootstate::harden_readability();
    crate::bootstate::apply_bootparam_overlay();

    // Clear all temporary module configs early
    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    let _ = catch_bootlog("logcat", &["logcat", "-b", "all"]);
    let _ = catch_bootlog("dmesg", &["dmesg", "-w", "-r"]);

    if utils::has_magisk() {
        warn!("Magisk detected, skip post-fs-data!");
        return Ok(());
    }

    let safe_mode = crate::utils::is_safe_mode();
    let wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);

    if safe_mode {
        // we should still ensure module directory exists in safe mode
        // because we may need to operate the module dir in safe mode
        warn!("safe mode, skip common post-fs-data.d scripts");
    } else {
        // Then exec common post-fs-data scripts
        if let Err(e) = crate::module::exec_common_scripts("post-fs-data.d", wait) {
            warn!("exec common post-fs-data scripts failed: {e}");
        }
    }

    let module_dir = defs::MODULE_DIR;

    assets::ensure_binaries(true).with_context(|| "Failed to extract bin assets")?;

    crate::hot_install::clean_trash();

    // if we are in safe mode, we should disable all modules
    if safe_mode {
        warn!("safe mode, skip post-fs-data scripts and disable all modules!");
        if let Err(e) = crate::module::disable_all_modules() {
            warn!("disable all modules failed: {e}");
        }
        return Ok(());
    }

    if let Err(e) = handle_updated_modules() {
        warn!("handle updated modules failed: {e}");
    }

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    // Materialise the module trees bundled into this ksud binary (the TEE engine and the
    // TA-enhancement tree) before the module set is enumerated below, so sepolicy, preinit
    // and the post-fs-data scripts all see them on the very boot they were installed. A
    // failure is logged and must never abort the boot.
    match crate::builtin::install_builtin_modules() {
        Ok(()) => info!("built-in modules ready"),
        Err(e) => warn!("install built-in modules failed: {e:#}"),
    }

    // Refresh /metadata/watchdog/ksu/{modules.rc,preinit/} so the next boot's kernel hook sees the
    // current module set. Acts as a safety net when state was changed outside
    // of ksud's normal mutation commands.
    if let Err(e) = crate::module::regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // load sepolicy.rule
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::builtin::load_sepolicy_rules() {
        warn!("load built-in sepolicy rules failed: {e}");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    crate::feature::init_forced_features();

    // SUSFS is a second, independent loadable module. Most devices flash a boot image that
    // already carries kernelsu.ko, so nobody ever calls `late-load`: this is the only place
    // that brings SUSFS up on the normal boot path. It returns immediately when SUSFS is
    // already present (builtin kernel, or an earlier load) and never fails the boot.
    crate::late_load::load_susfs_lkm_for_current_kmi();

    // With SUSFS up (in-kernel or the loadable module), apply the hiding baseline so
    // the device actually hides something: /data/adb, the ksu working dir, the modules
    // dir and the daemon from non-su processes, plus sus mounts. On a kernel without
    // SUSFS this is a single log line.
    #[cfg(all(target_arch = "aarch64", target_os = "android"))]
    crate::susfs::provision_baseline();

    // load feature config
    if is_safe_mode() {
        warn!("safe mode, skip load feature config");
    } else if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // execute metamodule post-fs-data script first (priority)
    if let Err(e) = metamodule::exec_stage_script("post-fs-data", wait) {
        warn!("exec metamodule post-fs-data script failed: {e}");
    }

    // exec modules post-fs-data scripts
    if let Err(e) = crate::module::exec_stage_script("post-fs-data", wait) {
        warn!("exec post-fs-data scripts failed: {e}");
    }

    // The bundled trees are not modules any more: their stage scripts run from
    // /data/adb/ksu/builtin after the user modules, which is where they used to run.
    if let Err(e) = crate::builtin::exec_stage_script("post-fs-data", wait) {
        warn!("exec built-in post-fs-data scripts failed: {e}");
    }

    // load system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // execute metamodule mount script
    if let Err(e) = metamodule::exec_mount_script(module_dir) {
        warn!("execute metamodule mount failed: {e}");
    }

    run_stage("post-mount", wait);

    std::env::set_current_dir("/").with_context(|| "failed to chdir to /")?;

    Ok(())
}

pub fn run_stage(stage: &str, wait: ScriptWait) {
    utils::umask(0);

    if utils::has_magisk() {
        warn!("Magisk detected, skip {stage}");
        return;
    }

    if crate::utils::is_safe_mode() {
        warn!("safe mode, skip {stage} scripts");
        return;
    }

    if let Err(e) = crate::module::exec_common_scripts(&format!("{stage}.d"), wait) {
        warn!("Failed to exec common {stage} scripts: {e}");
    }

    // execute metamodule stage script first (priority)
    if let Err(e) = metamodule::exec_stage_script(stage, wait) {
        warn!("Failed to exec metamodule {stage} script: {e}");
    }

    // execute regular modules stage scripts
    if let Err(e) = crate::module::exec_stage_script(stage, wait) {
        warn!("Failed to exec {stage} scripts: {e}");
    }

    if let Err(e) = crate::builtin::exec_stage_script(stage, wait) {
        warn!("Failed to exec built-in {stage} scripts: {e}");
    }
}

pub fn on_services() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_services");
        return;
    }

    match ksucalls::report_services() {
        Ok(true) => {}
        Ok(false) => {
            info!("services already started, skipping");
            return;
        }
        Err(e) => {
            error!("Failed to report services: {e:#}");
            return;
        }
    }

    info!("on_services triggered!");
    run_stage("service", ScriptWait::NoWait);
}

pub fn on_boot_completed() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_boot_completed");
        return;
    }

    ksucalls::report_boot_complete();
    info!("on_boot_completed triggered!");

    crate::preinit::on_boot_completed();

    run_stage("boot-completed", ScriptWait::NoWait);
}

fn catch_bootlog(logname: &str, command: &[&str]) -> Result<()> {
    use std::os::unix::process::CommandExt;
    use std::process::Stdio;

    let logdir = Path::new(defs::LOG_DIR);
    utils::ensure_dir_exists(logdir)?;
    let bootlog = logdir.join(format!("{logname}.log"));
    let oldbootlog = logdir.join(format!("{logname}.old.log"));

    if bootlog.exists() {
        std::fs::rename(&bootlog, oldbootlog)?;
    }

    let bootlog = std::fs::File::create(bootlog)?;

    let mut args = vec!["-s", "9", defs::BOOTLOG_TIMEOUT];
    args.extend_from_slice(command);
    // timeout -s 9 30s logcat > boot.log
    let result = unsafe {
        std::process::Command::new("timeout")
            .process_group(0)
            .pre_exec(|| {
                utils::switch_cgroups();
                Ok(())
            })
            .args(args)
            .stdout(Stdio::from(bootlog))
            .spawn()
    };

    if let Err(e) = result {
        warn!("Failed to start logcat: {e:#}");
    }

    Ok(())
}
