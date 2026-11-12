#![allow(clippy::redundant_pub_crate)]

// These modules have no Android or third-party dependencies, so their regressions
// can also run on a host with `rustc --test tests/audit_pure.rs`.
#[path = "../src/bootstate/params.rs"]
mod params;
#[path = "../src/boot_patch/version.rs"]
mod version;
