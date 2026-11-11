# feature/tee - keybox trust + bootloader spoof, nothing else

Scope: after the user flashes the LKM, two things must hold.

| Goal | Requirement |
|---|---|
| **T1** keys trusted | keybox proves itself: chain anchored at a Google root, leaf has >= 7 days left, private key matches the leaf, not revoked |
| **T2** bootloader spoofed as locked | `ro.boot.verifiedbootstate=green`, `ro.boot.flash.locked=1`, `ro.boot.vbmeta.device_state=locked`, partition verified flags enabled, all self-consistent, `/proc/bootconfig` agreeing |

Explicitly **not** in scope: repairing a broken engine, diagnosing it, reinstalling
it, or managing its lifecycle. The engine is an ordinary module. This directory
only makes the coverage complete and checks that the result is real.

## Why target.txt must be complete

The engine is an exhaustive allowlist: a UID absent from
`/data/adb/tricky_store/target.txt` is skipped entirely and receives the **real**
TEE proof, i.e. "not listed" means "not covered at all". The previous build
seeded eight entries and left the rest uncovered.

Entry syntax understood by the engine:

| line | meaning |
|---|---|
| `com.foo.bar` | AUTO - PATCH when the real TEE works, GENERATE when it is broken |
| `com.foo.bar!` | force GENERATE (forge with the keybox) |
| `com.foo.bar?` | force PATCH (swap the leaf of the real chain only) |
| `com.foo.bar [b.xml]` | use another keybox for this entry |

`target-gen.sh` writes bare (AUTO) entries for everything, so coverage is 100 %
while the hardware chain is kept whenever the real TEE still works.

## Files

| file | role |
|---|---|
| `target-gen.sh` | the only writer of `target.txt`: tiers A (user apps) + B (launcher apps) + C (baseline) + D (checker packages, gated) |
| `target.baseline.txt` | tier C - packages that read the attestation verdict |
| `detector-pkgs.txt` | tier D - checker packages, only written when the keybox is proven |
| `keybox-health.sh` | T1 verdict, `<0|1> <days> <root>`, delegates to a verifier binary |
| `bootstate.sh` | T2: rewrites the `ro.boot.*` / `partition.*` locked view, overlays `/proc/cmdline` + `/proc/bootconfig`, applies the readability rules; `--check` verifies |
| `selfcheck.sh` | on-device acceptance: prints one line per requirement, exit 0 only when all pass |
| `test/run.sh` | offline test for the coverage + keybox scripts (stubbed `pm`/`cmd`/health) |
| `test/run-bootstate.sh` | offline test for T2 (stubbed `resetprop`/`mount`/`ksud`, fake `/proc`) |

## T2: how the locked view is produced

`bootstate.sh` writes the property view straight into the property area with
`resetprop` (`name value` / `-d name`) instead of hooking a read path. Four
readers resolve through that area - reflection, `getprop`, libc and
`System.getProperty` - so all four agree without any hook; hooking a single path
is exactly what creates the contradiction detectors report.

The raw boot parameters are the second half of the same statement: a reader that
compares `ro.boot.*` against `/proc/cmdline`, or greps that file for
`verifiedbootstate=orange`, must not see the unlocked state there either. Both
files are rewritten (every unrelated token preserved verbatim) into a tmpfs
staging dir (`/dev/.xec/bootparams`, deliberately not under `/data/adb` - a
mount source under that path is itself a signal) and bind-mounted over the
originals.

The pass is idempotent: a second run writes nothing, which matters because it
runs on every boot and the values already hold.

Disable it by creating the flag file (default `/data/adb/tricky_store/bootstate.off`,
content `0`/`off`/`false`/`no`/`disable`); the overlay is then unmounted so the
real state becomes visible again.


## Layout of target.txt

Everything outside the managed block belongs to the user and is preserved
byte for byte; the block itself is rebuilt. That makes rebuilds idempotent
without ever deleting a manual entry.

```
# user entries survive
com.bank.secure?
# >>> xec:generated (target-gen.sh) >>>
# mode=all route=on entries=217
com.example.app
com.eltavine.duckdetector!
# <<< xec:generated <<<
```

The block is deterministic (no timestamps), and the script skips the rename when
the content is unchanged, so an idle reconcile loop never touches the file the
engine is watching.

## Integration

The TEE module's `service.sh` should do this before starting the engine:

```sh
# feature scripts ship next to the module
sh "$MODPATH/feature/tee/target-gen.sh" --rebuild || log "WARN: target-gen failed"

# keep it complete while the device is up (no-op when nothing changed)
( while :; do sleep 60; sh "$MODPATH/feature/tee/target-gen.sh" --quiet; done ) &
```

The package-install trigger lives in the manager (a broadcast receiver that
simply runs `target-gen.sh`); a new app is therefore covered within a minute of
being installed without waiting for a reboot.

The T2 pass runs **before zygote starts** (a `post-fs-data` stage script), so
`android.os.Build` and the properties never disagree:

```sh
sh "$MODPATH/feature/tee/bootstate.sh" --quiet || true
```

It needs a `resetprop` binary (this is the one runtime dependency; the script
locates it in `PATH`, `/data/adb/ksu/bin`, or the module's `bin/`). `ksud` is
optional: without it only the readability rules are skipped, the property half
and the overlay still apply.

## Acceptance

Run `sh selfcheck.sh` on the device. It covers T1-1..3, T2 (delegated to
`bootstate.sh --check`), C-1/C-3/C-4 and the detector-routing consistency check.
Until `ksud keybox health` exists (stage 3) `keybox-health.sh` returns a
conservative `0 -1 unknown`, so T1 and D-1 report FAIL on purpose rather than
claiming success.

## Offline test

```sh
bash feature/tee/test/run.sh            # coverage + keybox
bash feature/tee/test/run-bootstate.sh  # T2
```

Coverage/keybox: all tiers, idempotent second run, manual entries preserved, no
duplicates, detector gate on/off, opt-out, dry run, `--mode=thirdparty`, atomic
write, conservative verdict without a verifier.

T2: all 16 properties written, OEM-unlock property deleted, second run a no-op,
cmdline/bootconfig rewritten with unrelated tokens preserved and missing keys
appended, bind issued for both files, readability rules applied, `--check` green
after apply and red on a wrong property, failed write reported instead of
swallowed, off flag honoured, dry run writes nothing.

## Known follow-ups

1. `ksud keybox health` (Rust): the only missing piece for a real T1 verdict -
   X.509 chain parsing, private/public match, revocation list, days left.
2. Multi-keybox split (checker/banking vs everything else) to keep the
   "one keybox for every app" mass-abuse signal low. `[b.xml]` already works at
   the engine level; the generator needs a tier -> keybox mapping.

