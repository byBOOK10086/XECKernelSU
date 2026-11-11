#!/system/bin/sh
# T2: present the device as a locked user build.
#
# Two halves that must agree, because the property-based integrity checks read
# both and treat any disagreement between them as a contradiction of its own:
#
#   1. the ro.boot.* / partition.* property view is rewritten to what a locked
#      build reports, and
#   2. /proc/cmdline and /proc/bootconfig are rewritten and bind-mounted over the
#      originals, so a reader that greps the raw boot parameters sees the same
#      locked values.
#
# Why a direct property write and not a hook: hooking one read path (reflection,
# getprop, libc, System.getProperty are four different paths) is exactly what
# creates a visible contradiction. `resetprop` writes the property area itself,
# so all four readers agree without any hook.
#
# Nothing here may abort the boot: every step logs and continues, and the pass
# runs again on the next boot.
#
# usage: bootstate.sh [--check] [--dry-run] [--quiet] [--no-overlay] [--no-harden]
#   --check      verify only (prints one line per item, exit 1 on any mismatch)
#   --dry-run    print what would change, write nothing
#   --no-overlay skip the /proc/cmdline + /proc/bootconfig half
#   --no-harden  skip the sepolicy readability rules
# exit: 0 ok / 1 verification failed / 2 usage

set -u

# ---------------------------------------------------------------------------
# configuration
# ---------------------------------------------------------------------------

FLAG=${BS_FLAG:-/data/adb/tricky_store/bootstate.off}
PROCFS=${BS_PROCFS:-/proc}
STAGE_DIR=${BS_STAGE_DIR:-/dev/.xec/bootparams}
RESETPROP=${BS_RESETPROP:-}
KSUD=${BS_KSUD:-}
MOUNT=${BS_MOUNT:-mount}
UMOUNT=${BS_UMOUNT:-umount}

MODE=apply
QUIET=0
DO_OVERLAY=1
DO_HARDEN=1

for arg in "$@"; do
    case "$arg" in
        --check) MODE=check ;;
        --dry-run) MODE=dry ;;
        --quiet) QUIET=1 ;;
        --no-overlay) DO_OVERLAY=0 ;;
        --no-harden) DO_HARDEN=0 ;;
        -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) printf 'bootstate: unknown argument: %s\n' "$arg" >&2; exit 2 ;;
    esac
done

say() { [ "$QUIET" = 1 ] || printf '%s\n' "$*"; }
warn() { printf 'bootstate: %s\n' "$*" >&2; }

fails=0
pass() { [ "$MODE" = check ] || [ "$QUIET" = 1 ] || printf 'PASS %s\n' "$1"; }
bad() { fails=$((fails + 1)); printf 'FAIL %s\n' "$1"; }

# The property view of a locked user build.
PROPS="
ro.boot.verifiedbootstate=green
ro.boot.flash.locked=1
ro.boot.vbmeta.device_state=locked
ro.boot.veritymode=enforcing
ro.boot.secureboot=1
ro.boot.vbmeta.invalidate_on_error=yes
partition.system.verified=1
partition.vendor.verified=1
partition.product.verified=1
partition.system_ext.verified=1
partition.odm.verified=1
ro.oem_unlock_supported=0
service.adb.root=0
ro.debuggable=0
ro.secure=1
ro.adb.secure=1
"

# Android 16 stopped publishing this one; any value found belongs to something
# other than AOSP, and an absent property leaves the check unevaluated instead
# of firing.
DELETE_PROP=sys.oem_unlock_allowed

# `androidboot.*` counterparts of the properties above. Keep the two lists in
# sync: they are the same statement read through two different interfaces.
OVERRIDES="
androidboot.verifiedbootstate=green
androidboot.vbmeta.device_state=locked
androidboot.flash.locked=1
androidboot.veritymode=enforcing
androidboot.secureboot=1
androidboot.vbmeta.invalidate_on_error=yes
"

# Best effort: `deny` removes an existing allow rule, so a policy that never
# granted untrusted apps access to /proc/cmdline simply has nothing to remove.
CMDLINE_DENY="
deny appdomain proc_cmdline file read
deny untrusted_app proc_cmdline file read
deny untrusted_app_all proc_cmdline file read
"

enabled() {
    [ -f "$FLAG" ] || return 0
    case "$(tr '[:upper:]' '[:lower:]' < "$FLAG" 2>/dev/null | tr -d '[:space:]')" in
        0|off|false|no|disable|disabled) return 1 ;;
    esac
    return 0
}

# ---------------------------------------------------------------------------
# property half
# ---------------------------------------------------------------------------

resolve_resetprop() {
    [ -n "$RESETPROP" ] && return 0
    if command -v resetprop >/dev/null 2>&1; then
        RESETPROP=resetprop
        return 0
    fi
    for candidate in \
        /data/adb/ksu/bin/resetprop \
        /data/adb/modules/tricky_store/bin/resetprop \
        /data/adb/modules/TA_enhanced/resetprop
    do
        [ -x "$candidate" ] && { RESETPROP=$candidate; return 0; }
    done
    return 1
}

prop_get() {
    [ -n "$RESETPROP" ] || return 1
    "$RESETPROP" "$1" 2>/dev/null
}

prop_set() {
    [ -n "$RESETPROP" ] || return 1
    "$RESETPROP" "$1" "$2" >/dev/null 2>&1
}

prop_del() {
    [ -n "$RESETPROP" ] || return 1
    "$RESETPROP" -d "$1" >/dev/null 2>&1
}

apply_props() {
    if ! resolve_resetprop; then
        warn "no resetprop binary found; property half skipped"
        fails=$((fails + 1))
        return
    fi
    say "bootstate: using $RESETPROP"

    set_changed=0
    set_total=0
    while IFS='=' read -r name value; do
        [ -n "${name:-}" ] || continue
        set_total=$((set_total + 1))
        current=$(prop_get "$name")
        if [ "$current" = "$value" ]; then
            [ "$MODE" = check ] && pass "$name=$value"
            continue
        fi
        if [ "$MODE" = check ]; then
            bad "$name=${current:-<unset>} (want $value)"
            continue
        fi
        if prop_set "$name" "$value"; then
            set_changed=$((set_changed + 1))
            after=$(prop_get "$name")
            if [ "$after" = "$value" ]; then
                pass "$name=$value"
            else
                bad "$name set but reads back ${after:-<unset>}"
            fi
        else
            bad "$name could not be set"
        fi
    done <<EOF
$PROPS
EOF

    if [ -n "$(prop_get "$DELETE_PROP")" ]; then
        if [ "$MODE" = check ]; then
            bad "$DELETE_PROP still set ($(prop_get "$DELETE_PROP"))"
        else
            prop_del "$DELETE_PROP"
            if [ -n "$(prop_get "$DELETE_PROP")" ]; then
                bad "$DELETE_PROP could not be removed"
            else
                pass "$DELETE_PROP removed"
            fi
        fi
    fi
    say "bootstate: properties checked=$set_total changed=$set_changed"
}

# ---------------------------------------------------------------------------
# raw boot parameters (cmdline / bootconfig)
# ---------------------------------------------------------------------------
# Both rewrite passes read the override table from this file so the shell only
# has to write it once; passing a multi-line table through `awk -v` depends on
# the awk flavour (toybox included), a file does not.
write_overrides() {
    printf '%s\n' "$OVERRIDES" | awk 'NF' > "$1"
}

# /proc/cmdline is one space separated token list. Tokens are preserved in
# order, overridden keys are replaced in place, missing keys are appended, and
# everything else stays byte identical.
rewrite_cmdline() {
    write_overrides "$3"
    awk -v ofile="$3" '
        BEGIN {
            while ((getline line < ofile) > 0) {
                if (line == "") continue
                split(line, p, "=")
                n++
                keys[n] = p[1]
                want[p[1]] = substr(line, length(p[1]) + 2)
            }
            close(ofile)
        }
        {
            out = ""
            for (i = 1; i <= NF; i++) {
                tok = $i
                if (index(tok, "=") > 0) {
                    key = substr(tok, 1, index(tok, "=") - 1)
                    if (key in want) { tok = key "=" want[key]; seen[key] = 1 }
                }
                out = out (out == "" ? "" : " ") tok
            }
            for (i = 1; i <= n; i++) {
                k = keys[i]
                if (!(k in seen)) { out = out (out == "" ? "" : " ") k "=" want[k] }
            }
            print out
        }
    ' "$1" > "$2"
}

# /proc/bootconfig is one `key = value` line per parameter.
rewrite_bootconfig() {
    write_overrides "$3"
    awk -v ofile="$3" '
        BEGIN {
            while ((getline line < ofile) > 0) {
                if (line == "") continue
                split(line, p, "=")
                n++
                keys[n] = p[1]
                want[p[1]] = substr(line, length(p[1]) + 2)
            }
            close(ofile)
        }
        {
            key = $0
            sub(/[ \t]*=.*/, "", key)
            if (key in want) { print key " = " want[key]; seen[key] = 1 }
            else print
        }
        END {
            for (i = 1; i <= n; i++) {
                k = keys[i]
                if (!(k in seen)) print k " = " want[k]
            }
        }
    ' "$1" > "$2"
}

stage() {
    mkdir -p "$STAGE_DIR" 2>/dev/null || { warn "cannot create $STAGE_DIR"; return 1; }
    chmod 755 "$STAGE_DIR" 2>/dev/null
}

bind_over() {
    staged=$1
    target=$2
    if "$MOUNT" -o bind "$staged" "$target" 2>/dev/null; then
        say "bootstate: $target serves the staged copy"
        return 0
    fi
    warn "bind $target failed"
    return 1
}

apply_overlay() {
    stage || { fails=$((fails + 1)); return; }

    if [ -r "$PROCFS/cmdline" ]; then
        rewrite_cmdline "$PROCFS/cmdline" "$STAGE_DIR/cmdline.inc" "$STAGE_DIR/overrides"
        # the staging file must live in RAM; /dev/.xec is a tmpfs in every
        # Android release, and a source path under /data/adb would itself be a
        # signal for mount inspectors.
        mv -f "$STAGE_DIR/cmdline.inc" "$STAGE_DIR/cmdline" 2>/dev/null
        chmod 644 "$STAGE_DIR/cmdline" 2>/dev/null
        if [ "$MODE" = check ]; then
            if grep -q 'androidboot.verifiedbootstate=green' "$PROCFS/cmdline" 2>/dev/null; then
                pass "/proc/cmdline locked view"
            else
                bad "/proc/cmdline still shows the real boot state"
            fi
        elif ! grep -q 'androidboot.verifiedbootstate=green' "$PROCFS/cmdline" 2>/dev/null; then
            bind_over "$STAGE_DIR/cmdline" "$PROCFS/cmdline" && pass "/proc/cmdline overlay"
        else
            pass "/proc/cmdline already locked"
        fi
    else
        warn "$PROCFS/cmdline not readable"
    fi

    if [ -r "$PROCFS/bootconfig" ]; then
        rewrite_bootconfig "$PROCFS/bootconfig" "$STAGE_DIR/bootconfig" "$STAGE_DIR/overrides"
        chmod 644 "$STAGE_DIR/bootconfig" 2>/dev/null
        if [ "$MODE" = check ]; then
            if grep -q 'verifiedbootstate[[:space:]]*=[[:space:]]*green' "$PROCFS/bootconfig" 2>/dev/null; then
                pass "/proc/bootconfig locked view"
            else
                bad "/proc/bootconfig still shows the real boot state"
            fi
        elif ! grep -q 'verifiedbootstate[[:space:]]*=[[:space:]]*green' "$PROCFS/bootconfig" 2>/dev/null; then
            bind_over "$STAGE_DIR/bootconfig" "$PROCFS/bootconfig" && pass "/proc/bootconfig overlay"
        else
            pass "/proc/bootconfig already locked"
        fi
    else
        say "bootstate: $PROCFS/bootconfig not readable (normal for apps)"
    fi
}

# ---------------------------------------------------------------------------
# readability half
# ---------------------------------------------------------------------------

resolve_ksud() {
    [ -n "$KSUD" ] && return 0
    if command -v ksud >/dev/null 2>&1; then
        KSUD=ksud
        return 0
    fi
    for candidate in /data/adb/ksud/ksud /data/adb/ksu/bin/ksud; do
        [ -x "$candidate" ] && { KSUD=$candidate; return 0; }
    done
    return 1
}

apply_harden() {
    if ! resolve_ksud; then
        say "bootstate: no ksud, readability rules skipped"
        return
    fi
    printf '%s\n' "$CMDLINE_DENY" | while IFS= read -r rule; do
        [ -n "${rule:-}" ] || continue
        if [ "$MODE" = check ]; then
            continue
        fi
        if "$KSUD" sepolicy patch "$rule" >/dev/null 2>&1; then
            say "bootstate: applied \`$rule\`"
        else
            say "bootstate: \`$rule\` not applied (policy had nothing to remove)"
        fi
    done
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

if ! enabled; then
    say "bootstate: disabled by $FLAG"
    if [ "$DO_OVERLAY" = 1 ] && [ "$MODE" != check ]; then
        "$UMOUNT" "$PROCFS/cmdline" 2>/dev/null
        "$UMOUNT" "$PROCFS/bootconfig" 2>/dev/null
    fi
    exit 0
fi

if [ "$MODE" = dry ]; then
    printf 'bootstate: would set %d properties and delete %s\n' \
        "$(printf '%s\n' "$PROPS" | awk 'NF' | wc -l | tr -d ' ')" "$DELETE_PROP"
    printf '%s\n' "$PROPS" | awk 'NF { printf "  set %s\n", $0 }'
    printf '  del %s\n' "$DELETE_PROP"
    if [ "$DO_OVERLAY" = 1 ]; then
        drydir=${BS_TMP:-${TMPDIR:-/data/local/tmp}}
        mkdir -p "$drydir" 2>/dev/null
        if [ -r "$PROCFS/cmdline" ]; then
            rewrite_cmdline "$PROCFS/cmdline" "$drydir/bootstate-dry.$$" "$drydir/bootstate-ov.$$"
            printf '  cmdline: %s\n' "$(cat "$drydir/bootstate-dry.$$" 2>/dev/null)"
            rm -f "$drydir/bootstate-dry.$$" "$drydir/bootstate-ov.$$"
        fi
        if [ -r "$PROCFS/bootconfig" ]; then
            rewrite_bootconfig "$PROCFS/bootconfig" "$drydir/bootstate-dry.$$" "$drydir/bootstate-ov.$$"
            printf '  bootconfig: %s\n' "$(cat "$drydir/bootstate-dry.$$" 2>/dev/null)"
            rm -f "$drydir/bootstate-dry.$$" "$drydir/bootstate-ov.$$"
        fi
    fi
    if [ "$DO_HARDEN" = 1 ]; then
        printf '%s\n' "$CMDLINE_DENY" | awk 'NF { printf "  sepolicy %s\n", $0 }'
    fi
    exit 0
fi

apply_props
if [ "$DO_OVERLAY" = 1 ]; then
    apply_overlay
fi
if [ "$DO_HARDEN" = 1 ] && [ "$MODE" != check ]; then
    apply_harden
fi

if [ "$MODE" = check ]; then
    printf '\n%s\n' "$([ "$fails" = 0 ] && echo 'bootstate RESULT PASS' || echo "bootstate RESULT FAIL ($fails)")"
    [ "$fails" = 0 ]
    exit $?
fi

say "bootstate: done"
exit 0
