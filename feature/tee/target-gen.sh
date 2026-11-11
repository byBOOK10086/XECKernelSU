#!/system/bin/sh
# XEC TEE target.txt generator.
#
# The TEESimulator engine is an exhaustive allowlist: a UID that is not listed
# in /data/adb/tricky_store/target.txt is skipped entirely and gets the real TEE
# proof. "Not listed" therefore means "not covered at all". This script makes
# the coverage complete and keeps it complete.
#
# Entry syntax understood by the engine:
#   com.foo.bar            AUTO   - PATCH when the real TEE works, GENERATE when it is broken
#   com.foo.bar!           GENERATE - always forge with the keybox
#   com.foo.bar?           PATCH    - only swap the leaf of the real TEE chain
#   com.foo.bar [b.xml]    use another keybox for this entry
#
# Layout of the managed file: everything OUTSIDE the generated block belongs to
# the user and is preserved byte for byte; everything INSIDE the block is owned
# by this script and rebuilt on every run. That gives idempotent rebuilds
# without ever deleting a manual entry.
#
# usage: target-gen.sh [--rebuild] [--dry-run] [--quiet] [--mode=all|thirdparty|launcher]
# exit : 0 ok, 2 usage, 3 cannot write, 4 nothing to generate

set -u

HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TS_DIR=${TS_DIR:-/data/adb/tricky_store}
TARGET=${TG_TARGET:-$TS_DIR/target.txt}
OPTOUT=${TG_OPTOUT:-$TS_DIR/target-optout.txt}
BASELINE=${TG_BASELINE:-$HERE/target.baseline.txt}
DETECTORS=${TG_DETECTORS:-$HERE/detector-pkgs.txt}
HEALTH=${TG_HEALTH:-$HERE/keybox-health.sh}

# What to add to the generated block. Bare entries resolve to AUTO, which keeps
# the hardware chain whenever the real TEE works and only falls back to a forged
# chain when it does not.
SUFFIX=${TG_SUFFIX:-}
MODE=${TG_MODE:-all}

BEGIN_MARK="# >>> xec:generated (target-gen.sh) >>>"
END_MARK="# <<< xec:generated <<<"

TMP_CMD=${TG_PM_CMD:-}
LAUNCHER_CMD=${TG_LAUNCHER_CMD:-}

DRY=0
QUIET=0
REBUILD=0

log() { [ "$QUIET" = 1 ] || printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit "${2:-1}"; }

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY=1 ;;
        --quiet) QUIET=1 ;;
        --rebuild) REBUILD=1 ;;
        --mode=*) MODE=${arg#--mode=} ;;
        -h|--help)
            sed -n '2,22p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) die "target-gen: unknown argument: $arg" 2 ;;
    esac
done

case "$MODE" in
    all|thirdparty|launcher) ;;
    *) die "target-gen: bad --mode=$MODE" 2 ;;
esac

# ---------------------------------------------------------------------------
# tier collectors
# ---------------------------------------------------------------------------

# Third-party (user installed) packages: `pm list packages -3`.
collect_thirdparty() {
    if [ -n "$TMP_CMD" ]; then
        eval "$TMP_CMD" 2>/dev/null
    else
        pm list packages -3 2>/dev/null
    fi | sed -e 's/^package://' -e 's/[[:space:]]*$//'
}

# Packages that show a launcher icon (preinstalled ones included). Falls back to
# dumpsys when `cmd package` is unavailable, same as the old implementation.
collect_launcher() {
    if [ -n "$LAUNCHER_CMD" ]; then
        eval "$LAUNCHER_CMD" 2>/dev/null
        return 0
    fi
    if command -v cmd >/dev/null 2>&1; then
        cmd package query-activities --brief \
            -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER 2>/dev/null && return 0
    fi
    dumpsys package 2>/dev/null | grep -E '^[[:space:]]+[0-9a-f]+ [A-Za-z0-9_.]+/'
}

# "pkg/activity" lines -> unique package names that look like package names.
launcher_to_pkgs() {
    tr ' ' '\n' 2>/dev/null \
        | grep -E '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+/' \
        | cut -d/ -f1
}

valid_pkg() {
    [ -n "${1:-}" ] || return 1
    printf '%s' "$1" | grep -qE '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$'
}

baseline_list() {
    [ -f "$BASELINE" ] || return 0
    grep -vE '^[[:space:]]*(#|$)' "$BASELINE" 2>/dev/null | sed -e 's/[[:space:]]*$//'
}

# ---------------------------------------------------------------------------
# detector gate (tier D)
# ---------------------------------------------------------------------------

# Only inject into the checker packages when the keybox can prove itself:
# chain reaches Google, leaf has >= 7 days left. Otherwise those packages must
# see the real TEE - handing them a chain we cannot vouch for is what turns
# "unknown certificate / tampered attestation key" into a red item.
detector_route() {
    [ -f "$DETECTORS" ] || { printf 'off no-detector-list'; return 0; }
    [ -x "$HEALTH" ] || [ -f "$HEALTH" ] || { printf 'off no-health-helper'; return 0; }
    _line=$(sh "$HEALTH" 2>/dev/null | head -n 1)
    [ -n "${_line:-}" ] || { printf 'off no-health-output'; return 0; }
    # POSIX sh has no arrays; the line is a whitespace-separated tuple on purpose.
    # shellcheck disable=SC2086
    set -- $_line
    _ok=${1:-0}
    _days=${2:--1}
    _root=${3:-unknown}
    [ "$_ok" = "1" ] || { printf 'off keybox-invalid'; return 0; }
    case "$_root" in
        google|google_rkp) ;;
        *) printf 'off root=%s' "$_root"; return 0 ;;
    esac
    case "$_days" in
        ''|*[!0-9-]*) printf 'off days=unknown'; return 0 ;;
    esac
    if [ "$_days" -lt 7 ]; then
        printf 'off days=%s' "$_days"
        return 0
    fi
    printf 'on days=%s root=%s' "$_days" "$_root"
}

# ---------------------------------------------------------------------------
# build
# ---------------------------------------------------------------------------

gen_tmp=$(mktemp -d 2>/dev/null || printf '%s' "/data/local/tmp/target-gen.$$")
mkdir -p "$gen_tmp" 2>/dev/null || die "target-gen: cannot create a temp dir" 3
trap 'rm -rf "$gen_tmp"' EXIT INT HUP TERM

entries="$gen_tmp/entries"
: > "$entries"

case "$MODE" in
    all)
        collect_thirdparty >> "$entries"
        collect_launcher 2>/dev/null | launcher_to_pkgs >> "$entries"
        ;;
    thirdparty) collect_thirdparty >> "$entries" ;;
    launcher)   collect_launcher 2>/dev/null | launcher_to_pkgs >> "$entries" ;;
esac
baseline_list >> "$entries"

# opt-out list: packages the user does not want covered
if [ -f "$OPTOUT" ]; then
    grep -vE '^[[:space:]]*(#|$)' "$OPTOUT" 2>/dev/null | sed -e 's/[[:space:]]*$//' > "$gen_tmp/optout"
else
    : > "$gen_tmp/optout"
fi

route=$(detector_route)
case "$route" in
    on*)
        grep -vE '^[[:space:]]*(#|$)' "$DETECTORS" 2>/dev/null | sed -e 's/[[:space:]]*$//' >> "$entries"
        det_suffix='!'
        log "target-gen: detector route ON (${route#on }) -> detectors forced GENERATE"
        ;;
    *)
        det_suffix=''
        log "target-gen: detector route OFF (${route#off }) -> detectors left to the real TEE"
        ;;
esac

# normalise: trim, drop invalid, subtract opt-out, dedupe, sort
awk 'NF { print $1 }' "$entries" 2>/dev/null \
    | while read -r pkg; do valid_pkg "$pkg" && printf '%s\n' "$pkg"; done \
    | grep -vxFf "$gen_tmp/optout" - 2>/dev/null \
    | sort -u > "$gen_tmp/pkgs"

n_pkgs=$(wc -l < "$gen_tmp/pkgs" | tr -d ' ')
[ "$n_pkgs" -gt 0 ] || die "target-gen: nothing to generate (no packages collected)" 4

# generated block body. Deliberately deterministic: no timestamps, no random
# identifiers. A block that changes only when the coverage actually changes
# means run number two is a byte-for-byte no-op and the engine never reloads a
# file that says the same thing.
block="$gen_tmp/block"
{
    printf '%s\n' "$BEGIN_MARK"
    printf '# mode=%s route=%s entries=%s\n' "$MODE" "${route%% *}" "$n_pkgs"
    while read -r pkg; do
        if [ -f "$DETECTORS" ] && grep -qxF "$pkg" "$DETECTORS" 2>/dev/null; then
            printf '%s%s\n' "$pkg" "$det_suffix"
        else
            printf '%s%s\n' "$pkg" "$SUFFIX"
        fi
    done < "$gen_tmp/pkgs"
    printf '%s\n' "$END_MARK"
} > "$block"

# ---------------------------------------------------------------------------
# merge with the file the user may have edited
# ---------------------------------------------------------------------------

out="$gen_tmp/out"
: > "$out"

if [ -f "$TARGET" ] && [ "$REBUILD" = 0 ]; then
    # keep everything outside the managed block; drop bare duplicates of entries
    # we are about to write so the file never carries the same package twice
    awk -v b="$BEGIN_MARK" -v e="$END_MARK" '
        $0 == b { skip = 1; next }
        $0 == e { skip = 0; next }
        skip { next }
        { print }
    ' "$TARGET" > "$gen_tmp/keep" 2>/dev/null || : > "$gen_tmp/keep"

    while IFS= read -r line; do
        [ -n "$line" ] || { printf '\n' >> "$out"; continue; }
        case "$line" in
            \#*) printf '%s\n' "$line" >> "$out"; continue ;;
        esac
        # suffixed / keybox-scoped lines are user decisions: always preserved
        case "$line" in
            *'!'|*'?'|*']') printf '%s\n' "$line" >> "$out"; continue ;;
        esac
        pkg=$(printf '%s' "$line" | awk '{ print $1 }')
        if grep -qxF "$pkg" "$gen_tmp/pkgs" 2>/dev/null; then
            continue
        fi
        printf '%s\n' "$line" >> "$out"
    done < "$gen_tmp/keep"

    # make sure exactly one blank line separates user content from the block
    if [ -s "$out" ]; then
        printf '\n' >> "$out"
    fi
fi

cat "$block" >> "$out"

if [ "$DRY" = 1 ]; then
    printf '%s\n' "--- $TARGET (dry run) ---"
    cat "$out"
    printf '%s\n' "--- end ---"
    exit 0
fi

# Nothing to do when the file already says exactly this. Skipping the rename
# keeps the mtime (and therefore the engine's config reload) untouched.
if [ -f "$TARGET" ] && cmp -s "$out" "$TARGET" 2>/dev/null; then
    count=$(grep -cvE '^[[:space:]]*(#|$)' "$TARGET" 2>/dev/null)
    log "target-gen: unchanged TARGET_COUNT=$count route=${route%% *} mode=$MODE"
    exit 0
fi

mkdir -p "$(dirname "$TARGET")" 2>/dev/null
if ! cp -f "$out" "$TARGET.tmp" 2>/dev/null; then
    die "target-gen: cannot write $TARGET.tmp" 3
fi
chmod 644 "$TARGET.tmp" 2>/dev/null
# atomic rename: the engine's ConfigObserver only reacts to MOVED_TO / CLOSE_WRITE
mv -f "$TARGET.tmp" "$TARGET" 2>/dev/null || die "target-gen: cannot replace $TARGET" 3
chmod 644 "$TARGET" 2>/dev/null

count=$(grep -cvE '^[[:space:]]*(#|$)' "$TARGET" 2>/dev/null)
forge=$(grep -cE '![[:space:]]*$' "$TARGET" 2>/dev/null)
patch=$(grep -cE '\?[[:space:]]*$' "$TARGET" 2>/dev/null)
log "target-gen: TARGET_COUNT=$count TARGET_FORGE=$forge TARGET_PATCH=$patch route=${route%% *} mode=$MODE"
exit 0
