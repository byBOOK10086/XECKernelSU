#!/usr/bin/env bash
# Offline test for target-gen.sh / keybox-health.sh.
#
# The device is not needed: `pm`, `cmd` and the health helper are stubbed, and
# the script runs against a temp directory. This is the gate that keeps the
# generator honest (idempotent rebuild, manual entries preserved, detector gate
# respected, atomic write).
#
# usage: bash feature/tee/test/run.sh
# ShellCheck notes in this harness are the assertion idiom, not defects:
#   `[ cond ] && ok "what" || ko "what"` - ok()/ko() only count and print, they never
#   fail, so the `||` branch really is the else branch (SC2015);
#   some generator calls intentionally pass no arguments to rebuild the default
#   target.txt (SC2119/SC2120).
# shellcheck disable=SC2015,SC2119,SC2120

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
FEATURE_DIR=$(cd "$HERE/.." && pwd)
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

cat > "$STUB/pm" <<'EOF'
#!/usr/bin/env bash
cat <<'LIST'
package:com.example.alpha
package:com.example.beta
package:com.example.gamma
LIST
EOF
chmod +x "$STUB/pm"

cat > "$STUB/cmd" <<'EOF'
#!/usr/bin/env bash
cat <<'LIST'
      com.example.alpha/.MainActivity
      com.vendor.preinstalled/.HomeActivity
LIST
EOF
chmod +x "$STUB/cmd"

# health helper: prints "<ok> <days> <root>"
make_health() {
    local verdict=$1
    cat > "$STUB/health" <<EOF
#!/usr/bin/env bash
echo "$verdict"
EOF
    chmod +x "$STUB/health"
}

export PATH="$STUB:$PATH"

new_case() {
    CASE="$WORK/$1"
    mkdir -p "$CASE/tricky_store"
    export TS_DIR="$CASE/tricky_store"
    export TG_BASELINE="$FEATURE_DIR/target.baseline.txt"
    export TG_DETECTORS="$FEATURE_DIR/detector-pkgs.txt"
    export TG_HEALTH="$STUB/health"
    export TG_LAUNCHER_CMD="cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
    unset TG_SUFFIX TG_MODE TG_TARGET TG_OPTOUT || true  # allowed: unsetting variables that may not be set is not a failure
}

gen() { sh "$FEATURE_DIR/target-gen.sh" "$@" >/dev/null 2>&1; }
genv() { sh "$FEATURE_DIR/target-gen.sh" "$@" 2>&1; }

echo "target-gen.sh"

# 1. fresh run, detector gate ON
new_case gate_on
make_health "1 42 google"
gen
T="$TS_DIR/target.txt"
[ -f "$T" ] && ok "creates target.txt" || ko "creates target.txt"
grep -qxF 'com.example.alpha' "$T" && ok "covers user apps" || ko "covers user apps"
grep -qxF 'com.vendor.preinstalled' "$T" && ok "covers launcher apps" || ko "covers launcher apps"
grep -qxF 'com.google.android.gsf' "$T" && ok "covers baseline" || ko "covers baseline"
grep -qxF 'com.eltavine.duckdetector!' "$T" && ok "detectors forced GENERATE when gate is on" || ko "detectors forced GENERATE when gate is on"
! grep -q 'target.txt.tmp' <<<"$(ls "$TS_DIR")" && ok "atomic write leaves no temp file" || ko "atomic write leaves no temp file"

# 2. idempotent
cp "$T" "$WORK/first.txt"
gen
cmp -s "$T" "$WORK/first.txt" && ok "second run is byte identical" || ko "second run is byte identical"

# 3. manual entries survive a rebuild
printf '\n# my own note\ncom.bank.secure?\ncom.manual.app\n' >> "$T"
gen
grep -qxF '# my own note' "$T" && ok "keeps user comments" || ko "keeps user comments"
grep -qxF 'com.bank.secure?' "$T" && ok "keeps user PATCH entry" || ko "keeps user PATCH entry"
grep -qxF 'com.manual.app' "$T" && ok "keeps user bare entry" || ko "keeps user bare entry"
[ "$(grep -cxF 'com.example.alpha' "$T")" = "1" ] && ok "no duplicate of a generated entry" || ko "no duplicate of a generated entry"

# 4. gate OFF: detectors must be absent, nothing forced
new_case gate_off
make_health "0 -1 expired"
gen
T="$TS_DIR/target.txt"
! grep -q 'duckdetector' "$T" && ok "detectors omitted when gate is off" || ko "detectors omitted when gate is off"
! grep -qE '!$' "$T" && ok "no GENERATE entries when gate is off" || ko "no GENERATE entries when gate is off"
grep -qxF 'com.example.alpha' "$T" && ok "coverage still complete when gate is off" || ko "coverage still complete when gate is off"

# 5. opt-out
new_case optout
make_health "1 42 google"
printf 'com.example.beta\n' > "$TS_DIR/target-optout.txt"
gen
T="$TS_DIR/target.txt"
! grep -qxF 'com.example.beta' "$T" && ok "opt-out removes a package" || ko "opt-out removes a package"

# 6. dry run does not write
new_case dry
make_health "1 42 google"
out=$(genv --dry-run)
[ -f "$TS_DIR/target.txt" ] && ko "dry run writes nothing" || ok "dry run writes nothing"
grep -q 'com.example.alpha' <<<"$out" && ok "dry run prints the result" || ko "dry run prints the result"

# 7. mode=thirdparty ignores launcher-only apps
new_case mode_tp
make_health "1 42 google"
TG_MODE=thirdparty gen
T="$TS_DIR/target.txt"
! grep -qxF 'com.vendor.preinstalled' "$T" && ok "mode=thirdparty skips launcher-only apps" || ko "mode=thirdparty skips launcher-only apps"

echo
echo "keybox-health.sh"

# 8. delegates to the helper
new_case health
make_health "1 42 google"
out=$(TG_HEALTH_CMD="$STUB/health" sh "$FEATURE_DIR/keybox-health.sh" 2>/dev/null)
[ "$out" = "1 42 google" ] && ok "passes the helper verdict through" || ko "passes the helper verdict through"

# 9. no helper at all -> conservative verdict, never a fake pass
mkdir -p "$WORK/nokeybox"
out=$(TG_KEYBOX="$WORK/nokeybox/keybox.xml" PATH=/usr/bin:/bin sh "$FEATURE_DIR/keybox-health.sh" 2>/dev/null)
case "$out" in
    0\ *) ok "no verifier -> conservative verdict ($out)" ;;
    *) ko "no verifier -> conservative verdict (got '$out')" ;;
esac

# 10. malformed keybox is reported, not silently accepted
mkdir -p "$WORK/bad"
printf 'not xml\n' > "$WORK/bad/keybox.xml"
out=$(TG_KEYBOX="$WORK/bad/keybox.xml" PATH=/usr/bin:/bin sh "$FEATURE_DIR/keybox-health.sh" 2>/dev/null)
[ "$out" = "0 -1 malformed" ] && ok "malformed keybox -> malformed" || ko "malformed keybox -> malformed (got '$out')"

rm -rf "$WORK"
echo
printf 'passed %d, failed %d\n' "$pass" "$fail"
[ "$fail" = 0 ]
