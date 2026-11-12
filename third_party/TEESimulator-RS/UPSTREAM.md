# TEESimulator-RS (vendored)

The attestation-key simulator behind the built-in `tricky_store` module.

## Pin

| | |
|---|---|
| upstream | <https://github.com/Enginex0/TEESimulator-RS> |
| version | tag `v6.0.1-307` (versionCode `307`) |
| commit | `6d241e56d6e8146cd67ed4ac3dadc9c632969549` |
| update endpoint | <https://raw.githubusercontent.com/Enginex0/TEESimulator-RS/main/module/update.json> |
| license | GPL-3.0 - see `LICENSE` in this directory |
| authors | Enginex0, JingMatrix; technical lineage 5ec1cff/TrickyStore (GPL-3.0) |

Checked 2026-11-11: upstream `module/update.json` still reports `v6.0.1-307` /
versionCode 307, so this tree is the current release. When moving up, bump the
table above **and** `version` / `versionCode` in
`userspace/ksud/builtin/tricky_store/module.prop` in the same commit.

## What is vendored

Everything the Gradle + CMake + cargo build needs: `app/` (Kotlin sources plus
the C++ interceptor/inject/supervisor code) and `native-certgen/`, plus
`gradle/wrapper/` and `settings.gradle.kts`. Upstream `.git/` is not vendored and
upstream's own `.github/` workflows are not used - this repository builds the
artifacts itself (see "How this repository builds it" below).

Upstream's `module/` directory (`module.prop`, `customize.sh`, `service.sh`,
`daemon`, ...) is kept as **reference only**. The module that actually lands on
the device is `userspace/ksud/builtin/tricky_store/`, whose scripts are written
against this fork's builtin layout.

## Third-party code inside this tree

| path | project | license | pin |
|---|---|---|---|
| `app/src/main/cpp/external/LSPlt/` | JingMatrix/LSPlt (plt hook) | LGPL-3.0 | `3e29437f037cb7d2b9fbb459dcf162f6b8d1d926` - upstream's submodule pointer at `v6.0.1-307`; committed as plain files so CI needs no `submodules: recursive` |
| `app/src/main/cpp/external/AOSP/` | AOSP libbinder / libutils headers | Apache-2.0 | see the headers |
| `app/src/main/cpp/external/linux-kernel/` | Linux UAPI headers | GPL-2.0 | see the headers |

LGPL-3.0 LSPlt code is linked into `libTEESimulator.so`; its full source and
build recipe stay in this directory, as LGPL-3.0 requires.

## Local patches on top of `v6.0.1-307` (GPL-3.0 §5(a))

11 files differ from the pristine tag. Most are patches that make the simulator
answer the way a stock `keystore2` does instead of over-claiming; two are a
cache-invalidation fix that was still in the working tree when this tree was
vendored. Every hunk is local to this project.

| file | what changed | why |
|---|---|---|
| `interception/keystore/InterceptorUtils.kt` | error replies rebuilt in the daemon's field order; error names the daemon actually prints; forge only when the KeyMint cannot; `unique_id` kept refused | a mismatched Binder reply or an unknown error name makes `keystore2` log/deny instead of accepting the answer |
| `interception/keystore/shim/KeyMintSecurityLevelInterceptor.kt` | forge a key when the HAL refuses `generateKey`; keep the old key when the fallback cannot build a replacement; cover the 春秋 (Chunqiu) checker; stop contradicting ourselves inside the chain; `invalidateTeeResponses()` cache invalidation | the spoof has to answer every probe surface consistently, and must not destroy a working key on a failed fallback |
| `interception/keystore/shim/AuthorizeCreate.kt` | stop over-rejecting legal operations, stop stamping template values | the previous checks rejected legitimate creates and wrote values the platform never writes |
| `interception/keystore/shim/SoftwareOperation.kt` | stop writing made-up values into the attestation chain | detection compares the chain against what the real TEE would report |
| `attestation/AttestationBuilder.kt` | the same fix on the certificate-extension path, plus 春秋 coverage | |
| `util/AndroidDeviceUtils.kt` | device-property lookups de-duplicated and cached | building the chain twice per request cost systime that SELinux-timing probes can notice |
| `App.kt` | stage the new engine on the boot that notices it; load `libcertgen.so` from where it is actually installed | an in-place upgrade of a mapped `.so` fails on Android, so the old engine kept running until reboot |
| `config/ConfigurationManager.kt` | call `invalidateTeeResponses()` whenever a config file changes | spoofed TEE responses cached across a config edit leak the previous configuration |
| `native-certgen/src/attestation.rs` | 春秋 coverage on the native certificate path | |
| `native-certgen/src/certbuilder.rs` | legal-operation fixes mirrored from `AuthorizeCreate.kt` | |
| `gradle/wrapper/gradle-wrapper.properties` | wrapper network timeout raised, retries enabled | the runner gets HTTP 504 from `services.gradle.org`; `.github/workflows/ksud.yml` normally sidesteps the wrapper entirely with a verified mirror download |

## How this repository builds it

`.github/workflows/ksud.yml` runs `.github/scripts/build-builtin-modules.sh`
before the `cargo build` that packs `userspace/ksud/builtin/` into `ksud`. That
script builds this tree with `./gradlew zipRelease` (JDK 21, NDK 27.3,
CMake 3.22.1) and installs:

| artifact | source |
|---|---|
| `builtin/tricky_store/classes.dex` | Gradle `zipRelease` (R8-minified Kotlin) |
| `builtin/tricky_store/libTEESimulator.so` | `app/src/main/cpp/binder_interceptor.cpp` |
| `builtin/tricky_store/libcertgen.so` | `native-certgen/` (cargo-ndk, arm64-v8a) |
| `builtin/tricky_store/inject` | `app/src/main/cpp/inject/` |
| `builtin/tricky_store/supervisor` | `app/src/main/cpp/supervisor.cpp` |

No prebuilt engine binary is committed: the GPL-3.0 artifacts are always
compiled from this source tree in the same CI run that builds `ksud`.
