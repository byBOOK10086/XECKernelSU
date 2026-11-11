# Third-party notices

This repository bundles third-party components inside the trees below. Each of them keeps
its own LICENSE file next to the code; the list here is the index, not a replacement.

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

The module trees bundled inside `ksud` (TA_enhancement, tricky_store) carry their own
`module.prop` and NOTICE files inside each tree. They are materialised to
`/data/adb/modules` at boot; their licences are stated in those files.
