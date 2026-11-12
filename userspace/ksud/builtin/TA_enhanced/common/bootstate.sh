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

# configuration

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

# Only existing boot-state properties; configured ADB properties are untouched.
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

# property half

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
        current=$(prop_get "$name")
        [ -n "$current" ] || continue
        set_total=$((set_total + 1))
        if [ "$current" = "$value" ]; then
            [ "$MODE" = check ] && pass "$name=$value"
            continue
        fi
        if [ "$MODE" = check ]; then
            bad "$name=${current:-<unset>} (want $value)"
            continue
        fi
        if [ "$MODE" = dry ]; then
            say "  set $name=$value"
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
        elif [ "$MODE" = dry ]; then
            say "  del $DELETE_PROP"
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

# raw boot parameters (cmdline / bootconfig)
# Both rewrite passes read the override table from this file so the shell only
# has to write it once; passing a multi-line table through `awk -v` depends on
# the awk flavour (toybox included), a file does not.
write_overrides() {
    printf '%s\n' "$OVERRIDES" | awk 'NF' > "$1"
}

# /proc/cmdline is one space separated token list. Tokens are preserved in
# order; only existing keys are replaced. Missing keys are never appended.
rewrite_cmdline() {
    write_overrides "$3"
    awk -v ofile="$3" '
        BEGIN {
            while ((getline line < ofile) > 0) {
                if (line == "") continue
                split(line, p, "=")
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
                    if (key in want) tok = key "=" want[key]
                }
                out = out (out == "" ? "" : " ") tok
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
                want[p[1]] = substr(line, length(p[1]) + 2)
            }
            close(ofile)
        }
        {
            key = $0
            sub(/^[ \t]*/, "", key)
            sub(/[ \t]*=.*/, "", key)
            if (key in want) print key " = \"" want[key] "\""
            else print
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

# A proc target belongs to us only while it is a mountpoint exposing the
# exact staged inode. Never detach another module's mount, or stack over it.
mounted() {
    awk -v target="$1" '$5 == target { found=1 } END { exit !found }' "$PROCFS/self/mountinfo" 2>/dev/null
}
owned() { [ -f "$STAGE_DIR/$1" ] && [ "$STAGE_DIR/$1" -ef "$PROCFS/$1" ] && mounted "$PROCFS/$1"; }
detach_owned() {
    owned "$1" || return 0
    "$UMOUNT" "$PROCFS/$1" 2>/dev/null
}

overlay_file() {
    file=$1
    target=$PROCFS/$file
    staged=$STAGE_DIR/$file
    [ -r "$target" ] || return 0
    [ -r "$PROCFS/self/mountinfo" ] || { bad "mount ownership unavailable"; return; }
    "rewrite_$file" "$target" "$staged.inc" "$STAGE_DIR/overrides" || {
        bad "$target rewrite failed"; return;
    }
    if cmp -s "$target" "$staged.inc"; then
        pass "$target already locked"
        rm -f "$staged.inc"
        return
    fi
    if [ "$MODE" = check ]; then
        bad "$target still shows the real boot state"
        rm -f "$staged.inc"
        return
    fi
    if mounted "$target"; then
        if ! owned "$file"; then
            warn "$target has a foreign mount; overlay skipped"
            rm -f "$staged.inc"
            return
        fi
        if ! detach_owned "$file"; then
            bad "$target owned overlay could not be detached"
            rm -f "$staged.inc"
            return
        fi
    fi
    # Replace the staging inode only after detaching our old bind.
    if mv -f "$staged.inc" "$staged" && chmod 644 "$staged"; then
        bind_over "$staged" "$target" || bad "$target overlay failed"
    else
        bad "$target staging failed"
    fi
}

apply_overlay() {
    stage || { fails=$((fails + 1)); return; }
    # Serialize mount inspection and replacement to prevent concurrent stacking.
    mkdir "$STAGE_DIR/.lock" 2>/dev/null || { bad "overlay busy"; return; }
    trap 'rmdir "$STAGE_DIR/.lock" 2>/dev/null' 0
    if ! enabled; then
        detach_owned cmdline
        detach_owned bootconfig
    else
        overlay_file cmdline
        overlay_file bootconfig
    fi
    rmdir "$STAGE_DIR/.lock" 2>/dev/null
    trap - 0
}

# readability half

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

# main

if ! enabled; then
    say "bootstate: disabled by $FLAG"
    if [ "$DO_OVERLAY" = 1 ] && [ "$MODE" != check ]; then
        [ "$MODE" != dry ] && apply_overlay
    fi
    exit 0
fi

if [ "$MODE" = dry ]; then
    apply_props
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
