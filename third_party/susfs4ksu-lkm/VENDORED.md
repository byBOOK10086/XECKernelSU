# susfs4ksu-for-lkm (vendored)

- upstream: https://github.com/inforcqb/susfs4ksu-for-lkm
- pinned commit: `f95ae4c4c7df7050b2e13bc5d26403e5e0786d00` (2026-11-10)
- license: GPL-2.0 (see LICENSE in this directory)
- vendored: kernel/, module/ templates, tools/, docs. Upstream `.github/` is NOT vendored;
  this repository builds it with .github/workflows/build-susfs-lkm.yml instead.
- why: SUSFS as a loadable module means the feature no longer requires flashing a rebuilt
  GKI image. The previous tree vendored an older revision of the same project (58 kernel
  files, kernel/ only); this is the current release tree (8 KMIs incl. android17-6.18)
  plus the module packer and the no-ksud loader.
