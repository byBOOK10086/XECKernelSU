#!/system/bin/sh
# On-device acceptance check for the TEE feature.
#
# Every line printed is one requirement from the spec, with a verdict and the
# observed value. Exit code is 0 only when every check passes; a partially
# working setup prints exactly which requirement is not met, which is the whole
# point (the old build could show nine red items without saying why).
#
# usage: selfcheck.sh [--quiet]
#   --quiet  print only failures

set -u

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TS_DIR=${TS_DIR:-/data/adb/tricky_store}
TARGET=${TG_TARGET:-$TS_DIR/target.txt}
HEALTH=${TG_HEALTH:-$HERE/keybox-health.sh}
BASELINE=${TG_BASELINE:-$HERE/target.baseline.txt}
DETECTORS=${TG_DETECTORS:-$HERE/detector-pkgs.txt}

fails=0
pass() { [ "$QUIET" = 1 ] || printf 'PASS %-6s %s\n' "$1" "$2"; }
fail() { fails=$((fails + 1)); printf 'FAIL %-6s %s\n' "$1" "$2"; }
note() { [ "$QUIET" = 1 ] || printf 'info %-6s %s\n' "$1" "$2"; }

# ---------------------------------------------------------------------------
# C: full coverage
# ---------------------------------------------------------------------------

if [ -f "$TARGET" ]; then
    covered=$(grep -cvE '^[[:space:]]*(#|$)' "$TARGET" 2>/dev/null)
    third=$(pm list packages -3 2>/dev/null | wc -l | tr -d ' ')
    base=$(grep -cvE '^[[:space:]]*(#|$)' "$BASELINE" 2>/dev/null)
    if [ -n "$covered" ] && [ "$covered" -ge "$third" ]; then
        pass C-1 "target.txt covers $covered entries (>= $third user apps, baseline $base)"
    else
        fail C-1 "target.txt has ${covered:-0} entries but there are $third user apps"
    fi
    if grep -q '^# >>> xec:generated' "$TARGET" 2>/dev/null; then
        pass C-3 "managed block present (rebuildable, idempotent)"
    else
        fail C-3 "no managed block: this file was not produced by target-gen.sh"
    fi
else
    fail C-1 "no $TARGET"
fi

if [ -f /data/adb/.xudc_secure/target.txt ]; then
    fail C-4 "legacy second copy still exists: /data/adb/.xudc_secure/target.txt"
else
    pass C-4 "single source of truth"
fi

# ---------------------------------------------------------------------------
# T1: keybox can prove itself
# ---------------------------------------------------------------------------

verdict=$(sh "$HEALTH" 2>/dev/null | head -n 1)
ok=$(printf '%s' "${verdict:-}" | awk '{ print $1 }')
days=$(printf '%s' "${verdict:-}" | awk '{ print $2 }')
root=$(printf '%s' "${verdict:-}" | awk '{ print $3 }')

case "$ok" in
    1)
        pass T1-1 "keybox verdict: $verdict"
        case "$days" in
            ''|*[!0-9-]*) fail T1-2 "leaf lifetime unknown ($days)" ;;
            *)
                if [ "$days" -ge 7 ]; then
                    pass T1-2 "leaf has ${days} days left"
                else
                    fail T1-2 "leaf has only ${days} days left"
                fi
                ;;
        esac
        case "$root" in
            google|google_rkp) pass T1-3 "chain anchored at $root" ;;
            *) fail T1-3 "chain anchor is '$root', not Google" ;;
        esac
        ;;
    *)
        fail T1-1 "keybox cannot prove itself: ${verdict:-no output}"
        ;;
esac

# ---------------------------------------------------------------------------
# T2: bootloader spoofed as locked, and self-consistent
#
# Delegated to bootstate.sh --check on purpose: the locked property view is
# declared in exactly one place, so this gate can never disagree with what the
# pass actually writes.
# ---------------------------------------------------------------------------

bs=$(sh "$HERE/bootstate.sh" --check 2>&1)
bs_rc=$?
if [ "$bs_rc" = 0 ]; then
    pass T2 "locked view verified (properties + raw boot parameters)"
else
    printf '%s\n' "$bs" | grep '^FAIL' | sed 's/^FAIL /  - /'
    fail T2 "$(printf '%s\n' "$bs" | grep -c '^FAIL') item(s) still report the unlocked state"
fi

# ---------------------------------------------------------------------------
# D: detector routing must match the keybox verdict
# ---------------------------------------------------------------------------

if [ -f "$TARGET" ] && [ -f "$DETECTORS" ]; then
    forced=$(grep -cE '![[:space:]]*$' "$TARGET" 2>/dev/null)
    if [ "$ok" = "1" ] && [ "$forced" -gt 0 ]; then
        pass D-1 "detectors forced GENERATE ($forced entries) while the keybox is proven"
    elif [ "$ok" != "1" ] && [ "$forced" -eq 0 ]; then
        pass D-1 "detectors left on the real TEE while the keybox is unproven"
    else
        fail D-1 "detector routing does not match the keybox verdict (forced=$forced verdict=$ok)"
    fi
fi

printf '\n%s\n' "$([ "$fails" = 0 ] && echo 'RESULT PASS' || echo "RESULT FAIL ($fails failed)")"
[ "$fails" = 0 ]
