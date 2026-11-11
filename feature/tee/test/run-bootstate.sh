#!/usr/bin/env bash
# Offline test for bootstate.sh (T2: locked-view properties + raw boot params).
#
# No device needed: `resetprop`, `mount` and `ksud` are stubbed and the "proc"
# tree is a temp directory. What is verified here is the part that has real
# logic - which properties get written, skip-if-already-correct, the exact
# cmdline/bootconfig rewrite, and that the pass never claims success when a
# write did not land.
#
# usage: bash feature/tee/test/run-bootstate.sh
# ShellCheck notes in this harness are the assertion idiom, not defects:
#   `[ cond ] && ok "what" || ko "what"` - ok()/ko() only count and print, they never
#   fail, so the `||` branch really is the else branch (SC2015);
#   some generator calls intentionally pass no arguments to rebuild the default
#   target.txt (SC2119/SC2120).
# shellcheck disable=SC2015,SC2119,SC2120

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
FEATURE_DIR=$(cd "$HERE/.." && pwd)
SCRIPT="$FEATURE_DIR/bootstate.sh"
WORK=$(mktemp -d)

pass=0
fail=0
ok() { pass=$((pass + 1)); printf '  ok   %s\n' "$1"; }
ko() { fail=$((fail + 1)); printf '  FAIL %s\n' "$1"; }

# ---------------------------------------------------------------------------
# stubs
# ---------------------------------------------------------------------------
STUB="$WORK/stub"
mkdir -p "$STUB"

# resetprop stub: keeps a key=value store in $RP_STORE, records every call in
# $RP_LOG. Supports `name`, `name value` and `-d name`, like the real CLI.
cat > "$STUB/resetprop" <<'EOF'
#!/usr/bin/env bash
store=${RP_STORE:?}
log=${RP_LOG:?}
touch "$store" "$log"
case "${1:-}" in
    -d) printf 'del %s\n' "$2" >> "$log"; grep -v "^$2=" "$store" > "$store.tmp" || true; mv "$store.tmp" "$store"; exit 0 ;;  # allowed: grep exits 1 when the removed line was the last one; the file is still written
esac
if [ $# -eq 1 ]; then
    v=$(grep -m1 "^$1=" "$store" | cut -d= -f2-)
    [ -n "$v" ] || exit 1
    printf '%s\n' "$v"
    exit 0
fi
printf 'set %s=%s\n' "$1" "$2" >> "$log"
grep -v "^$1=" "$store" > "$store.tmp" 2>/dev/null || true  # allowed: same: an empty property store is a valid state
printf '%s=%s\n' "$1" "$2" >> "$store.tmp"
mv "$store.tmp" "$store"
EOF
chmod +x "$STUB/resetprop"

# mount stub: records the bind, does not actually mount
cat > "$STUB/mount" <<'EOF'
#!/usr/bin/env bash
printf 'mount %s\n' "$*" >> "${MOUNT_LOG:?}"
exit 0
EOF
chmod +x "$STUB/mount"

cat > "$STUB/umount" <<'EOF'
#!/usr/bin/env bash
printf 'umount %s\n' "$*" >> "${MOUNT_LOG:?}"
exit 0
EOF
chmod +x "$STUB/umount"

# ksud stub: records the sepolicy rules it was asked to apply
cat > "$STUB/ksud" <<'EOF'
#!/usr/bin/env bash
printf 'ksud %s\n' "$*" >> "${KSUD_LOG:?}"
exit 0
EOF
chmod +x "$STUB/ksud"

new_case() {
    CASE="$WORK/$1"
    mkdir -p "$CASE/proc" "$CASE/stage"
    export BS_PROCFS="$CASE/proc"
    export BS_STAGE_DIR="$CASE/stage"
    export BS_FLAG="$CASE/bootstate.off"
    export BS_RESETPROP="$STUB/resetprop"
    export BS_MOUNT="$STUB/mount"
    export BS_UMOUNT="$STUB/umount"
    export BS_KSUD="$STUB/ksud"
    export RP_STORE="$CASE/props"
    export RP_LOG="$CASE/rp.log"
    export MOUNT_LOG="$CASE/mount.log"
    export KSUD_LOG="$CASE/ksud.log"
    export BS_TMP="$CASE"
    : > "$RP_STORE"
    : > "$RP_LOG"
    : > "$MOUNT_LOG"
    : > "$KSUD_LOG"
    printf 'console=ttyMSM0 androidboot.verifiedbootstate=orange androidboot.vbmeta.device_state=unlocked androidboot.selinux=permissive androidboot.hardware=qcom\n' > "$CASE/proc/cmdline"
    cat > "$CASE/proc/bootconfig" <<'EOF'
androidboot.verifiedbootstate = orange
androidboot.vbmeta.device_state = unlocked
androidboot.selinux = permissive
EOF
}

run() { sh "$SCRIPT" "$@" >/dev/null 2>&1; }
runv() { sh "$SCRIPT" "$@" 2>&1; }

echo "bootstate.sh"

# 1. apply writes the whole locked view and deletes the OEM unlock property
new_case apply
printf 'sys.oem_unlock_allowed=1\n' >> "$RP_STORE"
run
want=16
got=$(grep -c '^set ' "$RP_LOG")
[ "$got" = "$want" ] && ok "writes all $want locked-view properties" || ko "writes all $want properties (got $got)"
grep -qx 'set ro.boot.verifiedbootstate=green' "$RP_LOG" && ok "verifiedbootstate=green" || ko "verifiedbootstate=green"
grep -qx 'set ro.boot.flash.locked=1' "$RP_LOG" && ok "flash.locked=1" || ko "flash.locked=1"
grep -qx 'set ro.boot.vbmeta.device_state=locked' "$RP_LOG" && ok "device_state=locked" || ko "device_state=locked"
grep -qx 'set partition.vendor.verified=1' "$RP_LOG" && ok "partition verified flags set to 1" || ko "partition verified flags set to 1"
grep -qx 'del sys.oem_unlock_allowed' "$RP_LOG" && ok "removes sys.oem_unlock_allowed" || ko "removes sys.oem_unlock_allowed"

# 2. second run is a no-op (idempotent, no churn)
: > "$RP_LOG"
run
got=$(grep -c '^set ' "$RP_LOG" 2>/dev/null || true)  # allowed: counting matches; grep exits 1 when there are zero
[ "${got:-0}" = "0" ] && ok "second run writes nothing" || ko "second run writes nothing (got ${got:-0})"

# 3. cmdline rewritten in place, unknown tokens preserved
cmd=$(cat "$CASE/stage/cmdline")
case "$cmd" in
    *androidboot.verifiedbootstate=green*) ok "cmdline: verifiedbootstate -> green" ;;
    *) ko "cmdline: verifiedbootstate -> green ($cmd)" ;;
esac
case "$cmd" in
    *androidboot.vbmeta.device_state=locked*) ok "cmdline: device_state -> locked" ;;
    *) ko "cmdline: device_state -> locked" ;;
esac
case "$cmd" in
    *androidboot.selinux=permissive*) ok "cmdline: unrelated token preserved" ;;
    *) ko "cmdline: unrelated token preserved" ;;
esac
case "$cmd" in
    *androidboot.flash.locked=1*) ok "cmdline: missing key appended" ;;
    *) ko "cmdline: missing key appended" ;;
esac
! grep -q 'verifiedbootstate=orange' "$CASE/stage/cmdline" && ok "cmdline: orange gone" || ko "cmdline: orange gone"
grep -q 'mount -o bind .*cmdline /.*/proc/cmdline' "$MOUNT_LOG" && ok "cmdline bound over the original" || ko "cmdline bound over the original"

# 4. bootconfig rewritten in place
bc=$(cat "$CASE/stage/bootconfig")
grep -q 'verifiedbootstate = green' <<<"$bc" && ok "bootconfig: verifiedbootstate -> green" || ko "bootconfig: verifiedbootstate -> green"
grep -q 'androidboot.selinux = permissive' <<<"$bc" && ok "bootconfig: unrelated line preserved" || ko "bootconfig: unrelated line preserved"
grep -q 'mount -o bind .*bootconfig /.*/proc/bootconfig' "$MOUNT_LOG" && ok "bootconfig bound over the original" || ko "bootconfig bound over the original"

# 5. readability rules applied through ksud
grep -q 'deny untrusted_app proc_cmdline file read' "$KSUD_LOG" && ok "sepolicy deny rules applied" || ko "sepolicy deny rules applied"

# 6. --check passes on a fully applied device. The mount stub cannot really
#    rebind /proc, so emulate what a successful bind produces: the target now
#    serves the staged copy.
cp "$CASE/stage/cmdline" "$CASE/proc/cmdline"
cp "$CASE/stage/bootconfig" "$CASE/proc/bootconfig"
run --check && ok "--check passes after apply" || ko "--check passes after apply"

# 7. --check fails when one property is wrong (never a fake green)
new_case check_bad
printf 'ro.boot.flash.locked=0\n' >> "$RP_STORE"
if run --check; then ko "--check fails on a wrong property"; else ok "--check fails on a wrong property"; fi
out=$(runv --check)
grep -q 'FAIL ro.boot.flash.locked=0' <<<"$out" && ok "--check names the wrong property" || ko "--check names the wrong property"

# 8. a write that does not land is reported, not swallowed
new_case write_fails
cat > "$STUB/resetprop_fail" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
    -d) exit 0 ;;
esac
exit 1
EOF
chmod +x "$STUB/resetprop_fail"
BS_RESETPROP="$STUB/resetprop_fail" out=$(sh "$SCRIPT" --no-overlay --no-harden 2>&1)
grep -q 'could not be set' <<<"$out" && ok "failed write is reported" || ko "failed write is reported"

# 9. the off flag disables the whole pass
new_case off_flag
printf 'off\n' > "$CASE/bootstate.off"
run
[ ! -s "$RP_LOG" ] && ok "off flag: no property writes" || ko "off flag: no property writes"

# 10. dry run writes nothing
new_case dry
out=$(runv --dry-run)
[ ! -s "$RP_LOG" ] && ok "dry run: no property writes" || ko "dry run: no property writes"
grep -q 'set ro.boot.verifiedbootstate=green' <<<"$out" && ok "dry run: lists the planned writes" || ko "dry run: lists the planned writes"
grep -q 'cmdline: .*androidboot.verifiedbootstate=green' <<<"$out" && ok "dry run: prints the rewritten cmdline" || ko "dry run: prints the rewritten cmdline"

rm -rf "$WORK"
echo
printf 'passed %d, failed %d\n' "$pass" "$fail"
[ "$fail" = 0 ]
