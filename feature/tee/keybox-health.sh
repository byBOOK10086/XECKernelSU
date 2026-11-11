#!/system/bin/sh
# Keybox self-proof (T1 gate).
#
# Prints one line, machine readable, first field is the verdict:
#   1 <days_left> <root>    the keybox can prove itself
#   0 <days_left> <root>    it cannot (expired / wrong chain / mismatched key / unknown)
#
# <root> is `google`, `google_rkp`, or whatever the verifier names the trust
# anchor it ended on (`unknown` when nothing could be checked).
#
# The real X.509 work lives in a helper binary: parsing a chain, comparing a
# PKCS#8 private key against a leaf public key and checking a revocation list is
# not something a shell script should fake. Candidates are tried in order and
# the first one that answers wins. When none answers we return a conservative
# verdict (`0 -1 unknown`) so the caller falls back to "leave the checkers on
# the real TEE" instead of injecting an unverified chain.
#
# usage: keybox-health.sh [--verbose]
# exit : 0 whenever a line was printed (the verdict is in the line, not in $?)

set -u

VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

KEYBOX=${TG_KEYBOX:-/data/adb/tricky_store/keybox.xml}

note() { [ "$VERBOSE" = 1 ] && printf '%s\n' "$*" >&2; }

# Candidates: our own ksud first (the long term home of the verifier), then the
# legacy helper names so an existing installation still gates correctly.
candidates=${TG_HEALTH_CMD:-}
for c in \
    "$candidates" \
    "ksud keybox health" \
    "/data/adb/ksud/ksud keybox health" \
    "/data/adb/modules/tricky_store/ksud keybox health" \
    "/data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced keybox health" \
    "/data/adb/ksud/builtin/TA_enhanced/bin/ta-enhanced keybox health" \
    "/data/adb/modules/TA_enhanced/bin/ta-enhanced keybox health"
do
    [ -n "$c" ] || continue
    set -- $c
    bin=$1
    shift
    case "$bin" in
        /*) [ -x "$bin" ] || { note "skip (not executable): $bin"; continue; } ;;
        *)  command -v "$bin" >/dev/null 2>&1 || { note "skip (not in PATH): $bin"; continue; } ;;
    esac
    line=$("$bin" "$@" 2>/dev/null | head -n 1)
    [ -n "${line:-}" ] || { note "skip (no output): $c"; continue; }
    # contract: <0|1> <days> <root>
    v=$(printf '%s' "$line" | awk '{ print $1 }')
    case "$v" in
        0|1)
            printf '%s\n' "$line"
            note "verdict from: $c"
            exit 0
            ;;
    esac
    note "skip (unexpected output): $c -> $line"
done

# No verifier available. Still say something useful about the file so the reason
# is not just "unknown".
if [ ! -f "$KEYBOX" ]; then
    note "no verifier and no keybox at $KEYBOX"
    printf '0 -1 absent\n'
    exit 0
fi
if [ ! -s "$KEYBOX" ]; then
    note "keybox is empty: $KEYBOX"
    printf '0 -1 empty\n'
    exit 0
fi
if ! grep -q '<Key' "$KEYBOX" 2>/dev/null; then
    note "keybox has no <Key> element: $KEYBOX"
    printf '0 -1 malformed\n'
    exit 0
fi
note "no verifier binary; refusing to vouch for $KEYBOX"
printf '0 -1 unknown\n'
