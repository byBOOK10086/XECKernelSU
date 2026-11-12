# Tricky Addon Enhanced (vendored)

The `ta-enhanced` daemon and the `resetprop-rs` CLI behind the built-in
`TA_enhanced` module.

## Pin

| | |
|---|---|
| upstream | <https://github.com/Enginex0/tricky-addon-enhanced> |
| version | tag `v5.53.1` (versionCode `50058`) |
| tag object | `baa7528b25566978380215bc8aeef3f348ede2e1` |
| commit | `5c5e49751dd844ea446c7250f09082dc61489f81` |
| update endpoint | <https://raw.githubusercontent.com/Enginex0/tricky-addon-enhanced/main/update.json> |
| license | GPL-3.0 - see `LICENSE` in this directory |
| author | Enginex0 |

Checked 2026-11-11: upstream `update.json` reports `v5.53.1` / versionCode
50058, i.e. this tree is current. When moving up, bump the table above **and**
`version` / `versionCode` in `userspace/ksud/builtin/TA_enhanced/module.prop`.

## What is vendored

Only the source the daemon build needs:

- `rust/` - the `ta-enhanced` workspace.
- `external/resetprop-rs/` - the `resetprop-cli` crate, a plain-file snapshot of
  <https://github.com/Enginex0/resetprop-rs> (**MIT**, commit
  `4646d28a2de49c139025abc5f6d0357cd863320d`, 2026-03-10), which our build turns
  into the standalone `bin/arm64-v8a/resetprop-rs`. Upstream dropped this
  submodule from `v5.53.1`; we keep it because the shipping module still stages
  a standalone `resetprop-rs`.

Not vendored on purpose:

- The module scripts, WebUI assets, `bin/*/aapt` and prebuilt binaries. The
  on-device copies live in `userspace/ksud/builtin/TA_enhanced/` (upstream's
  `v5.53.1` snapshot with the fixes listed in that directory's `NOTICE.md`).
- `rust/.cargo/config.toml`. Upstream pins the linker to an
  `aarch64-linux-android26-clang` wrapper there; this repository's CI picks the
  wrapper the installed NDK actually ships and overrides it via
  `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` (see
  `.github/scripts/build-builtin-modules.sh`).

## Local patches on top of `v5.53.1` (GPL-3.0 §5(a))

The daemon is patched to work as an **embedded** module (it ships inside `ksud`
at `/data/adb/ksu/builtin/`, not as a Magisk/KernelSU module directory) and to
answer the detection surfaces this fork cares about. Grouped by area:

| area | files | what changed | why |
|---|---|---|---|
| keybox validation | `rust/src/keybox/validate.rs`, `keybox/mod.rs`, `keybox/sources.rs` | Google RKP EC P-384 trust anchor, SEC1 (as well as PKCS#8) key parsing, validity check for every certificate in the chain, cached parse, `KeyboxHealth` (root name, days left), extra upstream mirror | a legal keybox was being rejected as `root certificate not recognized` and an expired chain was being accepted |
| builtin engine discovery | `rust/src/health/mod.rs` | `engine_dirs()` = builtin first, then `/data/adb/modules/tricky_store`, then the hidden path; engine presence, nice-name and self-heal restart all go through it; restart skips a tree marked `disable` | upstream only knew `/data/adb/modules/tricky_store`, so with the engine installed as builtin `is_engine_enabled()` was always false and the engine was never auto-restarted |
| props and security patch | `rust/src/platform/props.rs`, `rust/src/security_patch/mod.rs` | `getprop`/`set` helpers, `RP_PATH` pointing at the staged `resetprop-rs`, `variant_module_prop()` picks the first non-disabled `module.prop` (external -> builtin -> hidden) | the variant was stuck on `Legacy`, so `security-patch set/update` wrote the property instead of `security_patch.txt`; writes must not bypass the user's own toggle |
| package enumeration | `rust/src/platform/packages.rs` (new), used by `automation/watcher.rs`, `cli/applist.rs`, `status/mod.rs` | one place that lists third-party / all packages, with the `.automation/auto_added.txt` bookkeeping and `exclude_patterns.txt` | three copies of `pm list packages` parsing disagreed, and auto-added targets were never cleaned up |
| region | `rust/src/region/mod.rs` (new), `rust/src/lib.rs` | region detection used by the daemon and the WebUI status | Chinese ROMs need the region-specific target set |
| conflict detection | `rust/src/conflict/mod.rs` | recognise `magisk.db` / `/data/adb/magisk` and `/data/adb/xudc` as the other root manager | this fork's daemon lives at `/data/adb/xudc`, which upstream did not know |
| daemon, status, scheduler | `rust/src/daemon/{mod,scheduler,tasks}.rs`, `rust/src/status/mod.rs`, `rust/src/vbhash/mod.rs` | status task with inotify wake-up and `run_status_now`, boot-hash from `ro.boot.vbmeta.digest`, installed-package filtering for the status report | the WebUI showed a stale status until the next scheduled pass |
| CLI and WebUI bootstrap | `rust/src/cli/{mod,handlers,webui_init}.rs`, `rust/src/config/mod.rs`, `rust/src/module/mod.rs` | `health` and `props-custom` subcommands, richer `webui_init` JSON (`is_aosp_device`, `root_type`, keybox check), `keybox.source` legacy values migrated to `yurikey` | the WebUI needs those fields, and a legacy config value silently disabled the keybox source |
| automation | `rust/src/automation/target.rs`, `automation/watcher.rs` | auto-added target bookkeeping, exclude-pattern file, package-list diffing instead of `pm` polling per app | |
| build inputs | `rust/Cargo.toml`, `rust/Cargo.lock` | workspace version `5.53.1`; `quick-xml =0.37.2`, `x509-parser =0.18.1`, `p256 =0.13.2` pinned | the keybox validator needs these exact versions |
| removed | `rust/src/props/mod.rs` | deleted | upstream moved property handling into `platform/props.rs`; keeping both produced duplicate item definitions |

`userspace/ksud/builtin/TA_enhanced/` additionally carries the script-level
fixes (staged `resetprop-rs` mirrored to the path the Rust side hardcodes); they
are listed in `userspace/ksud/builtin/TA_enhanced/NOTICE.md`.

## How this repository builds it

`.github/workflows/ksud.yml` -> `.github/scripts/build-builtin-modules.sh`
(aarch64 target only) produces:

| artifact | source |
|---|---|
| `builtin/TA_enhanced/bin/arm64-v8a/ta-enhanced` | `rust/` (cargo, `aarch64-linux-android`) |
| `builtin/TA_enhanced/bin/arm64-v8a/resetprop-rs` | `external/resetprop-rs/crates/resetprop-cli` |
