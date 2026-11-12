#!/usr/bin/env bash
# Offline regression tests: existing values only, configured ADB, owned overlays.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/../bootstate.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
pass=0 fail=0
ok() { pass=$((pass + 1)); printf '  ok   %s\n' "$1"; }
ko() { fail=$((fail + 1)); printf '  FAIL %s\n' "$1"; }
assert() { if "$@"; then ok "$*"; else ko "$*"; fi; }
STUB="$WORK/stub"
mkdir -p "$STUB"
cat > "$STUB/resetprop" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = -d ]; then
    printf 'del %s\n' "$2" >> "$RP_LOG"
    grep -v "^$2=" "$RP_STORE" > "$RP_STORE.tmp" || true # allowed: grep returns 1 when deleting the final property
elif [ $# = 1 ]; then
    grep -m1 "^$1=" "$RP_STORE" | cut -d= -f2-
    exit
else
    printf 'set %s=%s\n' "$1" "$2" >> "$RP_LOG"
    [ "${RP_FAIL:-0}" = 0 ] || exit 1
    grep -v "^$1=" "$RP_STORE" > "$RP_STORE.tmp" || true # allowed: grep returns 1 when deleting the final property
    printf '%s=%s\n' "$1" "$2" >> "$RP_STORE.tmp"
fi
mv "$RP_STORE.tmp" "$RP_STORE"
EOF
# Hard links model a bind exposing the same staged inode without device access.
cat > "$STUB/mount" <<'EOF'
#!/usr/bin/env bash
printf 'mount %s\n' "$*" >> "$MOUNT_LOG"
[ "${MOUNT_FAIL:-0}" = 0 ] || exit 1
cp "$4" "$4.original"
rm "$4"
ln "$3" "$4"
printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$4" >> "$BS_PROCFS/self/mountinfo"
EOF
cat > "$STUB/umount" <<'EOF'
#!/usr/bin/env bash
printf 'umount %s\n' "$*" >> "$MOUNT_LOG"
[ "${UMOUNT_FAIL:-0}" = 0 ] || exit 1
mv -f "$1.original" "$1"
awk -v target="$1" '$5 != target' "$BS_PROCFS/self/mountinfo" > "$BS_PROCFS/self/mountinfo.tmp"
mv "$BS_PROCFS/self/mountinfo.tmp" "$BS_PROCFS/self/mountinfo"
EOF
cat > "$STUB/ksud" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$KSUD_LOG"
EOF
chmod +x "$STUB/"*
new_case() {
    CASE="$WORK/$1"
    mkdir -p "$CASE/proc/self" "$CASE/stage"
    export BS_PROCFS="$CASE/proc" BS_STAGE_DIR="$CASE/stage" BS_FLAG="$CASE/off"
    export BS_RESETPROP="$STUB/resetprop" BS_MOUNT="$STUB/mount"
    export BS_UMOUNT="$STUB/umount" BS_KSUD="$STUB/ksud" BS_TMP="$CASE"
    export RP_STORE="$CASE/props" RP_LOG="$CASE/rp.log"
    export MOUNT_LOG="$CASE/mount.log" KSUD_LOG="$CASE/ksud.log"
    : > "$RP_STORE"; : > "$RP_LOG"; : > "$MOUNT_LOG"; : > "$KSUD_LOG"
    : > "$CASE/proc/self/mountinfo"
    printf 'console=ttyMSM0 androidboot.verifiedbootstate=orange androidboot.vbmeta.device_state=unlocked androidboot.selinux=permissive\n' > "$CASE/proc/cmdline"
    printf 'androidboot.verifiedbootstate = "orange"\n  androidboot.vbmeta.device_state = "unlocked"\nandroidboot.selinux = "permissive"\n' > "$CASE/proc/bootconfig"
}
run() { sh "$SCRIPT" "$@" > "$CASE/out" 2>&1; }
seed() {
    printf 'ro.boot.verifiedbootstate=orange\nro.boot.flash.locked=0\npartition.vendor.verified=0\nsys.oem_unlock_allowed=1\nservice.adb.root=1\nro.debuggable=1\nro.secure=0\nro.adb.secure=0\n' > "$RP_STORE"
}
echo bootstate.sh
new_case apply
seed
assert run
assert grep -qx 'set ro.boot.verifiedbootstate=green' "$RP_LOG"
assert grep -qx 'set ro.boot.flash.locked=1' "$RP_LOG"
assert grep -qx 'set partition.vendor.verified=1' "$RP_LOG"
assert grep -qx 'del sys.oem_unlock_allowed' "$RP_LOG"
assert test "$(grep -c '^set ' "$RP_LOG")" = 3
assert grep -qx 'service.adb.root=1' "$RP_STORE"
assert grep -qx 'ro.debuggable=1' "$RP_STORE"
assert grep -qx 'ro.secure=0' "$RP_STORE"
assert grep -qx 'ro.adb.secure=0' "$RP_STORE"
assert test "$(wc -l < "$RP_STORE" | tr -d ' ')" = 7
assert grep -q 'androidboot.verifiedbootstate=green' "$CASE/proc/cmdline"
assert grep -q 'androidboot.vbmeta.device_state=locked' "$CASE/proc/cmdline"
assert grep -q 'androidboot.selinux=permissive' "$CASE/proc/cmdline"
assert test "$(grep -o 'androidboot\.' "$CASE/proc/cmdline" | wc -l | tr -d ' ')" = 3
assert grep -qx 'androidboot.verifiedbootstate = "green"' "$CASE/proc/bootconfig"
assert grep -qx 'androidboot.vbmeta.device_state = "locked"' "$CASE/proc/bootconfig"
assert grep -qx 'androidboot.selinux = "permissive"' "$CASE/proc/bootconfig"
assert test "$(wc -l < "$CASE/proc/bootconfig" | tr -d ' ')" = 3
assert grep -q 'deny untrusted_app proc_cmdline file read' "$KSUD_LOG"
assert test "$(grep -c '^mount ' "$MOUNT_LOG")" = 2
: > "$RP_LOG"; : > "$MOUNT_LOG"
assert run
assert test ! -s "$RP_LOG"
assert test ! -s "$MOUNT_LOG"
assert run --check
# OFF must detach only both owned binds; repeated OFF cannot detach again.
printf off > "$BS_FLAG"
assert run
assert test "$(grep -c '^umount ' "$MOUNT_LOG")" = 2
assert grep -q 'verifiedbootstate=orange' "$CASE/proc/cmdline"
: > "$MOUNT_LOG"
assert run
assert test ! -s "$MOUNT_LOG"
# Foreign mount with equal contents is not ours and must never be detached.
new_case foreign
cp "$CASE/proc/cmdline" "$CASE/stage/cmdline"
printf '1 0 0:1 / %s rw - tmpfs foreign rw\n' "$CASE/proc/cmdline" > "$CASE/proc/self/mountinfo"
assert run
assert test "$(grep -c '^mount ' "$MOUNT_LOG")" = 1
assert grep -q 'foreign mount' "$CASE/out"
: > "$MOUNT_LOG"; printf off > "$BS_FLAG"
assert run
assert test "$(grep -c '^umount ' "$MOUNT_LOG")" = 1
assert grep -q 'verifiedbootstate=orange' "$CASE/proc/cmdline"
# A stale own overlay is detached before replacement, with no stacking.
new_case refresh
assert run
printf 'androidboot.verifiedbootstate=orange\n' > "$CASE/stage/cmdline"
: > "$MOUNT_LOG"
assert run
assert test "$(grep -c '^umount ' "$MOUNT_LOG")" = 1
assert test "$(grep -c '^mount ' "$MOUNT_LOG")" = 1
assert grep -q 'verifiedbootstate=green' "$CASE/proc/cmdline"
# Failure to detach our inode must not replace it or add a mount.
printf 'androidboot.verifiedbootstate=orange\n' > "$CASE/stage/cmdline"
: > "$MOUNT_LOG"
UMOUNT_FAIL=1 run
assert grep -q 'could not be detached' "$CASE/out"
assert test "$(grep -c '^mount ' "$MOUNT_LOG")" = 0
assert grep -q 'verifiedbootstate=orange' "$CASE/stage/cmdline"
new_case missing
printf 'console=ttyMSM0\n' > "$CASE/proc/cmdline"
printf 'unrelated = "value"\n' > "$CASE/proc/bootconfig"
assert run
assert test ! -s "$RP_STORE"
assert test ! -s "$RP_LOG"
assert test ! -s "$MOUNT_LOG"
assert run --check
new_case check_bad
seed
if run --check; then ko 'check rejects mismatch'; else ok 'check rejects mismatch'; fi
assert grep -q 'FAIL ro.boot.flash.locked=0' "$CASE/out"
assert test ! -s "$RP_LOG"
assert test ! -s "$MOUNT_LOG"
new_case write_fail
seed
RP_FAIL=1 run --no-overlay --no-harden
assert grep -q 'could not be set' "$CASE/out"
new_case dry
seed
cp "$RP_STORE" "$CASE/before"
assert run --dry-run
assert cmp -s "$RP_STORE" "$CASE/before"
assert test ! -s "$RP_LOG"
assert test ! -s "$MOUNT_LOG"
assert test ! -s "$KSUD_LOG"
assert grep -q 'set ro.boot.verifiedbootstate=green' "$CASE/out"
assert grep -q 'cmdline: .*androidboot.verifiedbootstate=green' "$CASE/out"
new_case mount_fail
MOUNT_FAIL=1 run
assert grep -q 'overlay failed' "$CASE/out"
new_case no_mountinfo
rm "$CASE/proc/self/mountinfo"
assert run
assert test ! -s "$MOUNT_LOG"
# Green verifiedbootstate must not hide another existing mismatched token.
new_case mixed
printf 'androidboot.verifiedbootstate=green androidboot.flash.locked=0\n' > "$CASE/proc/cmdline"
printf 'androidboot.verifiedbootstate = green\nandroidboot.flash.locked = 0\n' > "$CASE/proc/bootconfig"
if run --check; then ko 'check rejects mixed tokens'; else ok 'check rejects mixed tokens'; fi
assert run
assert grep -q 'androidboot.flash.locked=1' "$CASE/proc/cmdline"
assert grep -qx 'androidboot.flash.locked = "1"' "$CASE/proc/bootconfig"
assert run --check
new_case busy
mkdir "$CASE/stage/.lock"
assert run
assert test ! -s "$MOUNT_LOG"
# Same inode without a recorded mount is insufficient ownership for OFF.
new_case not_mounted
ln "$CASE/proc/cmdline" "$CASE/stage/cmdline"
printf off > "$BS_FLAG"
assert run --dry-run
assert test ! -s "$MOUNT_LOG"
assert run
assert test ! -s "$MOUNT_LOG"
printf '\npassed %d, failed %d\n' "$pass" "$fail"
[ "$fail" = 0 ]
