# Third-party notices

This repository bundles third-party components inside the trees below. Each of them keeps
its own LICENSE file next to the code; the list here is the index, not a replacement.
Every vendored tree also carries an `UPSTREAM.md` (or `VENDORED.md`) that records the exact
pin, the files that differ from that pin, and how CI rebuilds it.

## Version tracking

The three upstream projects all publish a machine-readable version. These are the endpoints
to check before bumping a pin:

| tree | upstream version endpoint |
|---|---|
| `third_party/TEESimulator-RS` | <https://raw.githubusercontent.com/Enginex0/TEESimulator-RS/main/module/update.json> |
| `third_party/tricky-addon-enhanced` | <https://raw.githubusercontent.com/Enginex0/tricky-addon-enhanced/main/update.json> |
| `third_party/susfs4ksu-lkm` | <https://api.github.com/repos/inforcqb/susfs4ksu-for-lkm/releases/latest> |

## third_party/TEESimulator-RS

- Project: TEESimulator-RS - software attestation-key simulator, the engine behind the
  built-in `tricky_store` module
- Source: https://github.com/Enginex0/TEESimulator-RS
- Pinned: tag `v6.0.1-307` (versionCode 307), commit
  `6d241e56d6e8146cd67ed4ac3dadc9c632969549`
- License: **GPL-3.0** (see `third_party/TEESimulator-RS/LICENSE`)
- Copyright: Enginex0, JingMatrix; technical lineage 5ec1cff/TrickyStore.
- Vendored third-party code inside it:
  - `app/src/main/cpp/external/LSPlt/` - JingMatrix/LSPlt, **LGPL-3.0**, pinned
    `3e29437f037cb7d2b9fbb459dcf162f6b8d1d926` (upstream's submodule pointer at the tag;
    committed as plain files so CI needs no `submodules: recursive`)
  - `app/src/main/cpp/external/AOSP/` - AOSP libbinder/libutils headers, Apache-2.0
  - `app/src/main/cpp/external/linux-kernel/` - Linux UAPI headers, GPL-2.0
- What we changed: 11 files carry local patches (keystore error replies, key forgery
  fallback, 春秋 coverage, TEE-response cache invalidation, engine staging on upgrade,
  gradle wrapper timeout). The full list with per-file reasons is in
  `third_party/TEESimulator-RS/UPSTREAM.md`.
- No prebuilt engine binary is committed; `.github/scripts/build-builtin-modules.sh`
  compiles it in the same CI run that builds `ksud`.

## third_party/tricky-addon-enhanced

- Project: Tricky Addon Enhanced - the `ta-enhanced` daemon and `resetprop-rs` CLI behind
  the built-in `TA_enhanced` module
- Source: https://github.com/Enginex0/tricky-addon-enhanced
- Pinned: tag `v5.53.1` (versionCode 50058), commit
  `5c5e49751dd844ea446c7250f09082dc61489f81`
- License: **GPL-3.0** (see `third_party/tricky-addon-enhanced/LICENSE`)
- Copyright: Enginex0.
- Vendored third-party code inside it:
  - `external/resetprop-rs/` - Enginex0/resetprop-rs, **MIT**, pinned
    `4646d28a2de49c139025abc5f6d0357cd863320d`. Upstream dropped this submodule by
    `v5.53.1`; we keep the snapshot because the shipping module still stages a standalone
    `resetprop-rs`.
- What we changed: the daemon is patched to run as an embedded module (builtin engine
  discovery, keybox validation with the Google RKP EC root, props/security-patch variant
  handling, package enumeration, region, conflict detection, status task). The per-area
  table is in `third_party/tricky-addon-enhanced/UPSTREAM.md`.
- Only `rust/` and `external/resetprop-rs/` are vendored. The module scripts, WebUI assets
  and prebuilt binaries live in `userspace/ksud/builtin/TA_enhanced/` and are covered by
  the notices inside that directory.

## third_party/susfs4ksu-lkm

- Project: susfs4ksu-for-lkm - SUSFS ported to a loadable kernel module
- Source: https://github.com/inforcqb/susfs4ksu-for-lkm
- Pinned commit: `f95ae4c4c7df7050b2e13bc5d26403e5e0786d00` (2026-11-10)
- License: **GPL-2.0** (see `third_party/susfs4ksu-lkm/LICENSE`)
- Copyright: the susfs4ksu-for-lkm authors.
- Upstream credits, as stated by the project itself:
  - susfs4ksu (https://gitlab.com/simonpunk/susfs4ksu) for the feature logic and hook points
  - SukiSU-Ultra (https://github.com/SukiSU-Ultra/SukiSU-Ultra) for `patch_memory`,
    `lsm_hook` and `symbol_resolver`
  - KallRecon (https://github.com/Dere3046/KallRecon)
- What we changed: nothing inside the vendored tree. Upstream's `.github/` directory is not
  vendored; this repository builds the module with `.github/workflows/build-susfs-lkm.yml`
  against the same digest-pinned DDK images upstream uses.

## userspace/ksud/builtin

The module trees bundled inside `ksud` (TA_enhanced, tricky_store) carry their own
`module.prop` and NOTICE files inside each tree. They are materialised to
`/data/adb/modules` at boot; their licences are stated in those files.
