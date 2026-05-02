#!/usr/bin/env bash
# bike-radar-test.sh — live-testing helper for the bike radar overlay app.
#
# Single tool that gets you from "code change" to "ready to test step N"
# without manual ADB chasing. See `--help` for the full subcommand list.
#
# Conventions:
#   - `set -euo pipefail` and a tab/newline-only IFS at the top
#   - Every subcommand accepts `--help` for self-documenting usage
#   - `--self-test` runs help + arg parsing only, no phone interaction
#   - Errors prefixed [ERR] (red), success [OK] (green), info [..] (cyan)
#   - Colours auto-disabled when NO_COLOR=1 or stdout is not a tty
#   - ADB target via $ANDROID_SERIAL or default device; never hardcoded
#   - All phone-touching subcommands fail loud if `adb devices` is empty

set -euo pipefail
IFS=$'\n\t'

PKG="es.jjrh.bikeradar"
SERVICE_FQN="${PKG}/.BikeRadarService"
DEFAULT_APK="app/build/outputs/apk/debug/app-debug.apk"
BUILDER_IMAGE="bike-radar-builder"

# Top-level flags consumed by main(); subcommands may also opt in.
TOP_KEEP_SCREENCAPS=0
TOP_STAYON=0
TOP_NO_BUILD=0

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------

if [[ -t 1 ]] && [[ -z "${NO_COLOR:-}" ]]; then
    C_RED=$'\033[31m'
    C_GREEN=$'\033[32m'
    C_CYAN=$'\033[36m'
    C_RESET=$'\033[0m'
else
    C_RED=""
    C_GREEN=""
    C_CYAN=""
    C_RESET=""
fi

err()  { printf '%s[ERR]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
ok()   { printf '%s[OK]%s %s\n'  "$C_GREEN" "$C_RESET" "$*"; }
info() { printf '%s[..]%s %s\n'  "$C_CYAN" "$C_RESET" "$*"; }

# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

adb_cmd() {
    # Wrap adb so $ANDROID_SERIAL is honoured automatically by adb itself.
    adb "$@"
}

# Print which adb device we're targeting. Called once per subcommand from
# require_device so the user always knows where commands are going.
_announced_serial=0
announce_serial() {
    [[ "$_announced_serial" -eq 1 ]] && return 0
    _announced_serial=1
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        info "ANDROID_SERIAL=${ANDROID_SERIAL}"
    fi
}

require_device() {
    if ! command -v adb >/dev/null 2>&1; then
        err "adb not found in PATH"
        exit 2
    fi
    local count
    count=$(adb_cmd devices | awk 'NR>1 && $2=="device" {n++} END{print n+0}')
    if [[ "$count" -eq 0 ]]; then
        err "no adb devices attached (set ANDROID_SERIAL or run 'adb connect <ip>:5555')"
        exit 2
    fi
    announce_serial
}

# ---------------------------------------------------------------------------
# Top-level usage
# ---------------------------------------------------------------------------

top_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh [--keep-screencaps] [--stayon] [--no-build] <subcommand> [options]

Subcommands:
  wait-for-radar   Poll logcat for handshake + first V2 frame
  tap-row          Tap a settings row by visible text (contains, case-insensitive)
  tap-button       Tap a clickable node by exact text match
  clean-install    Build (Docker), install APK, relaunch, wait for radar
  bt-cycle         Toggle Bluetooth off/on to reset Bluedroid
  screencap-named  Pull a screenshot to /tmp (auto-deleted on exit)
  walkaway-test    End-to-end walk-away alert verification
  stayon           Toggle screen-stays-on while charging

Top-level flags (apply to all subcommands):
  --keep-screencaps  Don't auto-delete /tmp/bike-radar-*.png on exit
  --stayon           Run `stayon on` before, restore on exit (clean-install,
                     walkaway-test). No-op for other subcommands.
  --no-build         Skip the gradle build inside clean-install.

Other flags:
  -h, --help       Show this help
  --self-test      Run help / arg parsing only; no phone interaction

Environment:
  ANDROID_SERIAL    Pin a specific adb device (e.g. PIXEL_SERIAL or HOST:PORT)
  NO_COLOR=1        Disable ANSI colours
  KEEP_SCREENCAPS=1 Equivalent to --keep-screencaps
EOF
}

# ---------------------------------------------------------------------------
# Subcommand: wait-for-radar
# ---------------------------------------------------------------------------

cmd_wait_for_radar_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh wait-for-radar [--timeout SECONDS] [--no-clear]

Clears logcat, then polls `adb logcat -d` until both
  - "BikeRadar.Radar: handshake complete"
  - "first V2 frame"
have appeared, in that order. Default timeout 60s.

The logcat is cleared at start to avoid false negatives where the markers
have already rolled out of the ring buffer. Pass --no-clear to skip the
clear (used by clean-install where the install already reset things).

Exit codes:
  0  both markers seen
  1  timeout; last 50 lines of BikeRadar logcat dumped to stderr
  2  no adb device
EOF
}

cmd_wait_for_radar() {
    local timeout=60
    local clear_log=1
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) cmd_wait_for_radar_usage; return 0 ;;
            --timeout) timeout="${2:-}"; shift 2 ;;
            --timeout=*) timeout="${1#--timeout=}"; shift ;;
            --no-clear) clear_log=0; shift ;;
            *) err "unknown arg: $1"; cmd_wait_for_radar_usage >&2; return 64 ;;
        esac
    done
    if ! [[ "$timeout" =~ ^[0-9]+$ ]]; then
        err "--timeout must be a positive integer (got: $timeout)"
        return 64
    fi
    require_device

    if [[ "$clear_log" -eq 1 ]]; then
        info "clearing logcat ring buffer"
        adb_cmd logcat -c >/dev/null 2>&1 || true
    fi

    info "waiting up to ${timeout}s for handshake + first V2 frame"
    local deadline
    deadline=$(( $(date +%s) + timeout ))
    local saw_handshake=0 saw_v2=0
    local logbuf
    while [[ $(date +%s) -lt $deadline ]]; do
        logbuf=$(adb_cmd logcat -d -s BikeRadar.Radar:V BikeRadar:V 2>/dev/null || true)
        if [[ "$saw_handshake" -eq 0 ]] && grep -q 'BikeRadar.Radar: handshake complete' <<<"$logbuf"; then
            saw_handshake=1
            info "handshake complete seen"
        fi
        if [[ "$saw_handshake" -eq 1 ]] && grep -q 'first V2 frame' <<<"$logbuf"; then
            saw_v2=1
            break
        fi
        sleep 1
    done

    if [[ "$saw_handshake" -eq 1 && "$saw_v2" -eq 1 ]]; then
        ok "radar ready (handshake + V2 frame)"
        return 0
    fi

    err "radar not ready within ${timeout}s (handshake=$saw_handshake v2=$saw_v2)"
    {
        echo "--- last 50 lines of BikeRadar logcat ---"
        adb_cmd logcat -d -s BikeRadar:V BikeRadar.Radar:V 2>/dev/null | tail -n 50 || true
    } >&2
    return 1
}

# ---------------------------------------------------------------------------
# UI dump helpers
# ---------------------------------------------------------------------------

# Dump current UI hierarchy to stdout. Robust against Android variants:
# `uiautomator dump` writes a file path to stderr; pull from /sdcard.
dump_ui() {
    local tmp_xml="/sdcard/window_dump.xml"
    adb_cmd shell uiautomator dump "$tmp_xml" >/dev/null 2>&1 || {
        err "uiautomator dump failed"
        return 1
    }
    adb_cmd exec-out cat "$tmp_xml"
}

# Parse `<node ...>` lines from a uiautomator dump, emit one record per line:
#   text<TAB>clickable<TAB>x1<TAB>y1<TAB>x2<TAB>y2
#
# When `xmllint` is on PATH we use XPath (handles HTML entities like
# `&amp;apos;` correctly). Otherwise fall back to a sed-regex parser that
# does not decode entities — fine for our app's text but not generally
# correct. Detect once per process.
_parse_engine=""
_select_parse_engine() {
    [[ -n "$_parse_engine" ]] && return 0
    if command -v xmllint >/dev/null 2>&1; then
        _parse_engine="xmllint"
    else
        _parse_engine="sed"
    fi
}

parse_nodes() {
    _select_parse_engine
    if [[ "$_parse_engine" == "xmllint" ]]; then
        _parse_nodes_xmllint
    else
        _parse_nodes_sed
    fi
}

# xmllint-based parser. Walks every <node ...> with a non-empty bounds and
# prints text<TAB>clickable<TAB>x1<TAB>y1<TAB>x2<TAB>y2.
_parse_nodes_xmllint() {
    # Slurp stdin, then run xmllint with the buffer as input.
    local xml
    xml=$(cat)
    # `xmllint --xpath "string()"` collapses to a single value, so use --shell
    # with `cat //node` and post-process — but that's awkward. Instead run a
    # small XSLT-free extraction: print attrs with one "node" record per line.
    # We use --xpath in a loop over indices.
    local count
    count=$(printf '%s' "$xml" | xmllint --xpath 'count(//node[@bounds])' - 2>/dev/null || echo 0)
    if ! [[ "$count" =~ ^[0-9]+$ ]] || [[ "$count" -eq 0 ]]; then
        return 0
    fi
    local i text bounds clickable x1 y1 x2 y2
    for (( i=1; i<=count; i++ )); do
        text=$(printf '%s' "$xml" | xmllint --xpath "string(//node[@bounds][$i]/@text)" - 2>/dev/null || echo "")
        bounds=$(printf '%s' "$xml" | xmllint --xpath "string(//node[@bounds][$i]/@bounds)" - 2>/dev/null || echo "")
        clickable=$(printf '%s' "$xml" | xmllint --xpath "string(//node[@bounds][$i]/@clickable)" - 2>/dev/null || echo "")
        [[ -z "$bounds" ]] && continue
        if [[ "$bounds" =~ \[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
            x1="${BASH_REMATCH[1]}"; y1="${BASH_REMATCH[2]}"
            x2="${BASH_REMATCH[3]}"; y2="${BASH_REMATCH[4]}"
        else
            continue
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$text" "${clickable:-false}" "$x1" "$y1" "$x2" "$y2"
    done
}

# Pure bash + sed/awk fallback. Does NOT decode HTML entities; OK for our
# app's text which doesn't use them. Kept for portability.
_parse_nodes_sed() {
    # Each <node ... /> may be on one line or split; normalise first.
    local attrs text bounds clickable x1 y1 x2 y2
    tr '>' '\n' | sed -n 's/.*<node \(.*\)/\1/p' | while IFS= read -r attrs; do
        [[ -z "$attrs" ]] && continue
        text=$(printf '%s' "$attrs" | sed -n 's/.*text="\([^"]*\)".*/\1/p')
        bounds=$(printf '%s' "$attrs" | sed -n 's/.*bounds="\([^"]*\)".*/\1/p')
        clickable=$(printf '%s' "$attrs" | sed -n 's/.*clickable="\([^"]*\)".*/\1/p')
        [[ -z "$bounds" ]] && continue
        # bounds format: [x1,y1][x2,y2]
        if [[ "$bounds" =~ \[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
            x1="${BASH_REMATCH[1]}"; y1="${BASH_REMATCH[2]}"
            x2="${BASH_REMATCH[3]}"; y2="${BASH_REMATCH[4]}"
        else
            continue
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$text" "${clickable:-false}" "$x1" "$y1" "$x2" "$y2"
    done
}

# Tap the centre of a bounds rect.
tap_centre() {
    local x1=$1 y1=$2 x2=$3 y2=$4
    local cx=$(( (x1 + x2) / 2 ))
    local cy=$(( (y1 + y2) / 2 ))
    adb_cmd shell input tap "$cx" "$cy"
}

# ---------------------------------------------------------------------------
# Subcommand: tap-row
# ---------------------------------------------------------------------------

cmd_tap_row_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh tap-row NAME

Resolve NAME to a settings-row coordinate via `uiautomator dump`,
then `input tap` the centre of that row. Match is case-insensitive
substring (literal, not regex) against the node's `text` attribute.

Aliases (NAME may use any of):
  permissions  -> permissions / permission
  ha           -> home assistant / ha
  pairing      -> pair / pairing
  finish       -> finish / done / continue
  dashcam      -> dashcam
  radar        -> radar
  about        -> about

Falls back to listing visible texts on no match.
EOF
}

# Map a NAME to one or more substrings to try. Echoes substrings, one per line.
row_aliases() {
    local name
    name=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    case "$name" in
        permissions) printf '%s\n' "permissions" "permission" ;;
        ha)          printf '%s\n' "home assistant" "ha" ;;
        pairing)     printf '%s\n' "pairing" "pair" ;;
        finish)      printf '%s\n' "finish" "done" "continue" ;;
        dashcam)     printf '%s\n' "dashcam" ;;
        radar)       printf '%s\n' "radar" ;;
        about)       printf '%s\n' "about" ;;
        *)           printf '%s\n' "$name" ;;
    esac
}

cmd_tap_row() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        cmd_tap_row_usage; return 0
    fi
    if [[ $# -ne 1 ]]; then
        err "tap-row takes exactly one NAME"
        cmd_tap_row_usage >&2
        return 64
    fi
    require_device

    local name="$1"
    local nodes
    nodes=$(dump_ui | parse_nodes) || return 1

    # Try each alias substring in order. Use awk's index() (literal substring)
    # not the regex `~` operator — NAME may contain regex metachars (e.g.
    # `c++`, `[settings]`) that would otherwise mis-match or syntax-error.
    local sub
    while IFS= read -r sub; do
        [[ -z "$sub" ]] && continue
        local sub_lc
        sub_lc=$(printf '%s' "$sub" | tr '[:upper:]' '[:lower:]')
        local hit
        hit=$(printf '%s\n' "$nodes" | awk -F'\t' -v s="$sub_lc" '
            { tlc=tolower($1); if ($1 != "" && index(tlc, s) > 0) { print; exit } }
        ')
        if [[ -n "$hit" ]]; then
            local text x1 y1 x2 y2
            IFS=$'\t' read -r text _ x1 y1 x2 y2 <<<"$hit"
            info "tap-row '$name' -> '$text' @ ($x1,$y1)-($x2,$y2)"
            tap_centre "$x1" "$y1" "$x2" "$y2"
            ok "tapped"
            return 0
        fi
    done < <(row_aliases "$name")

    err "no row matching '$name' found. Visible texts:"
    printf '%s\n' "$nodes" | awk -F'\t' '$1!="" {print "  - " $1}' >&2
    return 1
}

# ---------------------------------------------------------------------------
# Subcommand: tap-button
# ---------------------------------------------------------------------------

cmd_tap_button_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh tap-button TEXT

Find a clickable node whose `text` exactly equals TEXT (case-sensitive),
then tap its centre. Falls back to listing visible clickable texts.
EOF
}

cmd_tap_button() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        cmd_tap_button_usage; return 0
    fi
    if [[ $# -ne 1 ]]; then
        err "tap-button takes exactly one TEXT"
        cmd_tap_button_usage >&2
        return 64
    fi
    require_device

    local target="$1"
    local nodes
    nodes=$(dump_ui | parse_nodes) || return 1

    local hit
    hit=$(printf '%s\n' "$nodes" | awk -F'\t' -v t="$target" '
        $1==t && $2=="true" { print; exit }
    ')
    if [[ -n "$hit" ]]; then
        local text x1 y1 x2 y2
        IFS=$'\t' read -r text _ x1 y1 x2 y2 <<<"$hit"
        info "tap-button '$text' @ ($x1,$y1)-($x2,$y2)"
        tap_centre "$x1" "$y1" "$x2" "$y2"
        ok "tapped"
        return 0
    fi

    err "no clickable node with exact text '$target'. Visible clickable texts:"
    printf '%s\n' "$nodes" | awk -F'\t' '$2=="true" && $1!="" {print "  - " $1}' >&2
    return 1
}

# ---------------------------------------------------------------------------
# Subcommand: clean-install
# ---------------------------------------------------------------------------

cmd_clean_install_usage() {
    cat <<EOF
Usage: bike-radar-test.sh clean-install [--no-build] [APK]

By default: build :app:assembleDebug via the '$BUILDER_IMAGE' Docker image,
then stop the foreground service so onDestroy can clear the radar GATT,
install the APK, force-stop, relaunch, then wait-for-radar.

Pass --no-build (or set the top-level --no-build flag) to skip the build
step and use a pre-built APK as-is.

APK defaults to: $DEFAULT_APK
EOF
}

# Run the gradle build via the Docker builder image. Mirrors AGENTS.md's
# documented command. Falls back to a clear error if the image is missing.
build_apk_in_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        err "docker not found in PATH; build with 'gradle :app:assembleDebug' manually or pass --no-build"
        return 1
    fi
    if ! docker image inspect "$BUILDER_IMAGE" >/dev/null 2>&1; then
        err "builder image '$BUILDER_IMAGE' missing; build it with: docker build -t $BUILDER_IMAGE ."
        err "or re-run with --no-build to skip"
        return 1
    fi
    info "running gradle :app:assembleDebug in $BUILDER_IMAGE"
    local uid gid cache
    uid=$(id -u); gid=$(id -g)
    cache="${HOME}/.cache/bike-radar-gradle"
    mkdir -p "$cache"
    docker run --rm \
        -v "$PWD:/workspace" \
        -u "${uid}:${gid}" \
        -v "${cache}:/gradle-cache" \
        -e GRADLE_USER_HOME=/gradle-cache \
        -w /workspace "$BUILDER_IMAGE" \
        gradle :app:assembleDebug --console=plain --no-daemon
}

cmd_clean_install() {
    local no_build="$TOP_NO_BUILD"
    local apk=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) cmd_clean_install_usage; return 0 ;;
            --no-build) no_build=1; shift ;;
            --) shift; break ;;
            -*) err "unknown arg: $1"; cmd_clean_install_usage >&2; return 64 ;;
            *) if [[ -z "$apk" ]]; then apk="$1"; shift; else err "clean-install takes at most one APK arg"; return 64; fi ;;
        esac
    done
    [[ -z "$apk" ]] && apk="$DEFAULT_APK"

    if [[ "$no_build" -eq 0 ]]; then
        build_apk_in_docker || return 1
    else
        info "--no-build: skipping gradle build"
    fi

    if [[ ! -f "$apk" ]]; then
        err "APK not found: $apk"
        return 1
    fi
    require_device

    if [[ "$TOP_STAYON" -eq 1 ]]; then
        info "--stayon: enabling screen stayon for the duration of clean-install"
        adb_cmd shell svc power stayon true || true
        # shellcheck disable=SC2064  # captured at registration time on purpose
        trap 'adb shell svc power stayon false >/dev/null 2>&1 || true' EXIT INT TERM
    fi

    info "stopping service $SERVICE_FQN"
    adb_cmd shell am stopservice "$SERVICE_FQN" || true
    sleep 1
    info "installing $apk"
    adb_cmd install -r "$apk"
    info "force-stopping $PKG"
    adb_cmd shell am force-stop "$PKG"
    info "relaunching via monkey"
    adb_cmd shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
    # The install resets things; --no-clear avoids racing the new logcat.
    cmd_wait_for_radar --no-clear
}

# ---------------------------------------------------------------------------
# Subcommand: bt-cycle
# ---------------------------------------------------------------------------

cmd_bt_cycle_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh bt-cycle

Disable Bluetooth, wait 2s, re-enable, wait 3s. Use when Bluedroid is
stuck after a half-open GATT.
EOF
}

cmd_bt_cycle() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        cmd_bt_cycle_usage; return 0
    fi
    if [[ $# -ne 0 ]]; then
        err "bt-cycle takes no args"
        cmd_bt_cycle_usage >&2
        return 64
    fi
    require_device
    info "bluetooth off"
    adb_cmd shell svc bluetooth disable
    sleep 2
    info "bluetooth on"
    adb_cmd shell svc bluetooth enable
    sleep 3
    ok "bluetooth cycled"
}

# ---------------------------------------------------------------------------
# Subcommand: screencap-named
# ---------------------------------------------------------------------------

cmd_screencap_named_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh screencap-named [--keep] NAME

Pull a screenshot via `adb exec-out screencap -p` to
  /tmp/bike-radar-${NAME}-<unix-ts>.png

By default ALL /tmp/bike-radar-*.png are deleted on script exit
(phone screenshots may leak home address). Pass --keep, or set
KEEP_SCREENCAPS=1, or use the top-level --keep-screencaps flag,
to preserve them.
EOF
}

# Trap registered the first time screencap-named is called.
_screencap_trap_registered=0
register_screencap_trap() {
    [[ "$_screencap_trap_registered" -eq 1 ]] && return 0
    _screencap_trap_registered=1
    # SIGINT alone fires EXIT through bash's default behaviour, but SIGTERM
    # exits *without* firing EXIT. Trap all three to guarantee cleanup.
    trap '_cleanup_screencaps' EXIT INT TERM
}
_cleanup_screencaps() {
    if [[ "$TOP_KEEP_SCREENCAPS" -eq 1 || -n "${KEEP_SCREENCAPS:-}" ]]; then
        info "keep-screencaps active; leaving /tmp/bike-radar-*.png in place"
        return 0
    fi
    # Use a glob-safe loop; suppress no-match noise.
    shopt -s nullglob
    local f deleted=0
    for f in /tmp/bike-radar-*.png; do
        rm -f -- "$f" && deleted=$(( deleted + 1 ))
    done
    shopt -u nullglob
    if [[ "$deleted" -gt 0 ]]; then
        info "deleted $deleted /tmp/bike-radar-*.png screenshot(s)"
    fi
}

cmd_screencap_named() {
    local name=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) cmd_screencap_named_usage; return 0 ;;
            --keep) TOP_KEEP_SCREENCAPS=1; shift ;;
            --) shift; break ;;
            -*) err "unknown arg: $1"; cmd_screencap_named_usage >&2; return 64 ;;
            *) if [[ -z "$name" ]]; then name="$1"; shift; else err "screencap-named takes exactly one NAME"; return 64; fi ;;
        esac
    done
    if [[ -z "$name" ]]; then
        err "screencap-named takes exactly one NAME"
        cmd_screencap_named_usage >&2
        return 64
    fi
    require_device
    # Sanitise NAME: keep alnum, dash, underscore.
    if ! [[ "$name" =~ ^[A-Za-z0-9_-]+$ ]]; then
        err "NAME must match [A-Za-z0-9_-]+ (got: $name)"
        return 64
    fi
    register_screencap_trap
    local out
    out="/tmp/bike-radar-${name}-$(date +%s).png"
    adb_cmd exec-out screencap -p > "$out"
    if [[ ! -s "$out" ]]; then
        err "screencap produced empty file: $out"
        rm -f -- "$out"
        return 1
    fi
    printf '%s\n' "$out"
    ok "screencap saved"
    if [[ "$TOP_KEEP_SCREENCAPS" -eq 0 && -z "${KEEP_SCREENCAPS:-}" ]]; then
        info "auto-deletes on exit; pass --keep or set KEEP_SCREENCAPS=1 to preserve"
    fi
}

# ---------------------------------------------------------------------------
# Subcommand: walkaway-test
# ---------------------------------------------------------------------------

cmd_walkaway_test_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh walkaway-test

End-to-end walk-away verification:
  1. wait-for-radar
  2. prompt user to power the radar OFF
  3. watch logcat for "walk-away FIRE" up to 60s
  4. PASS / FAIL with diagnostic dump on FAIL

Press Ctrl-C at the prompt to abort cleanly.
EOF
}

cmd_walkaway_test() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        cmd_walkaway_test_usage; return 0
    fi
    if [[ $# -ne 0 ]]; then
        err "walkaway-test takes no args"
        cmd_walkaway_test_usage >&2
        return 64
    fi
    require_device

    if [[ "$TOP_STAYON" -eq 1 ]]; then
        info "--stayon: enabling screen stayon for the duration of walkaway-test"
        adb_cmd shell svc power stayon true || true
        # shellcheck disable=SC2064
        trap 'adb shell svc power stayon false >/dev/null 2>&1 || true' EXIT INT TERM
    fi

    cmd_wait_for_radar || return 1

    # Distinguish abort (Ctrl-C, EOF) from a normal Enter. Without an explicit
    # INT trap here, `read || true` swallows Ctrl-C and the user can't abort.
    local prompt_aborted=0
    trap 'prompt_aborted=1' INT
    local _reply
    if ! read -r -p "Turn the radar OFF now, then press Enter: " _reply; then
        prompt_aborted=1
    fi
    trap - INT
    if [[ "$prompt_aborted" -eq 1 ]]; then
        echo  # newline after ^C
        err "walkaway-test aborted at prompt"
        return 130
    fi

    info "watching for 'walk-away FIRE' for up to 60s"
    local deadline
    deadline=$(( $(date +%s) + 60 ))
    while [[ $(date +%s) -lt $deadline ]]; do
        if adb_cmd logcat -d -s BikeRadar:V BikeRadar.Radar:V 2>/dev/null \
              | grep -q 'walk-away FIRE'; then
            ok "PASS: walk-away FIRE seen"
            return 0
        fi
        sleep 2
    done

    err "FAIL: no walk-away FIRE within 60s"
    {
        echo "--- last 50 lines of BikeRadar logcat ---"
        adb_cmd logcat -d -s BikeRadar:V BikeRadar.Radar:V 2>/dev/null | tail -n 50 || true
    } >&2
    return 1
}

# ---------------------------------------------------------------------------
# Subcommand: stayon
# ---------------------------------------------------------------------------

cmd_stayon_usage() {
    cat <<'EOF'
Usage: bike-radar-test.sh stayon on|off

Toggle `svc power stayon`. Without this the lock timer fires mid-flow
when driving the app via `input` / `screencap`. Restore to off when done.
EOF
}

cmd_stayon() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        cmd_stayon_usage; return 0
    fi
    if [[ $# -ne 1 ]]; then
        err "stayon takes exactly one arg: on|off"
        cmd_stayon_usage >&2
        return 64
    fi
    case "$1" in
        on)  require_device; adb_cmd shell svc power stayon true;  ok "stayon=on" ;;
        off) require_device; adb_cmd shell svc power stayon false; ok "stayon=off" ;;
        *)   err "stayon arg must be on|off (got: $1)"; return 64 ;;
    esac
}

# ---------------------------------------------------------------------------
# --self-test: exercise help + arg parsing without touching the phone
# ---------------------------------------------------------------------------

self_test() {
    info "self-test: exercising --help on every subcommand"
    local sub
    local subs=(wait-for-radar tap-row tap-button clean-install bt-cycle \
                screencap-named walkaway-test stayon)
    for sub in "${subs[@]}"; do
        info "  -- $sub --help"
        # Run in a subshell so a buggy sub can't poison the parent.
        ( dispatch "$sub" --help ) >/dev/null
    done

    info "self-test: top-level --help"
    top_usage >/dev/null

    info "self-test: error paths (no device required, args validated first)"
    # These should fail with exit 64 (bad usage) without ever calling adb.
    if ( dispatch tap-row ) 2>/dev/null; then
        err "tap-row with no args should have failed"; return 1
    fi
    if ( dispatch tap-button foo bar ) 2>/dev/null; then
        err "tap-button with extra args should have failed"; return 1
    fi
    if ( dispatch stayon maybe ) 2>/dev/null; then
        err "stayon with bad arg should have failed"; return 1
    fi
    if ( dispatch wait-for-radar --timeout abc ) 2>/dev/null; then
        err "wait-for-radar with non-numeric timeout should have failed"; return 1
    fi
    if ( dispatch screencap-named --bogus ) 2>/dev/null; then
        err "screencap-named with unknown flag should have failed"; return 1
    fi

    ok "self-test passed"
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

dispatch() {
    local sub="${1:-}"
    [[ $# -gt 0 ]] && shift || true
    case "$sub" in
        wait-for-radar)  cmd_wait_for_radar  "$@" ;;
        tap-row)         cmd_tap_row         "$@" ;;
        tap-button)      cmd_tap_button      "$@" ;;
        clean-install)   cmd_clean_install   "$@" ;;
        bt-cycle)        cmd_bt_cycle        "$@" ;;
        screencap-named) cmd_screencap_named "$@" ;;
        walkaway-test)   cmd_walkaway_test   "$@" ;;
        stayon)          cmd_stayon          "$@" ;;
        ""|-h|--help)    top_usage ;;
        *) err "unknown subcommand: $sub"; top_usage >&2; return 64 ;;
    esac
}

# Pull top-level flags off $@ before dispatching to a subcommand.
parse_top_flags() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --keep-screencaps) TOP_KEEP_SCREENCAPS=1; shift ;;
            --stayon)          TOP_STAYON=1; shift ;;
            --no-build)        TOP_NO_BUILD=1; shift ;;
            --) shift; break ;;
            *) break ;;
        esac
    done
    REMAINING_ARGS=("$@")
}

main() {
    if [[ "${1:-}" == "--self-test" ]]; then
        self_test
        exit $?
    fi
    REMAINING_ARGS=()
    parse_top_flags "$@"
    # Honour env-var equivalent at startup.
    [[ -n "${KEEP_SCREENCAPS:-}" ]] && TOP_KEEP_SCREENCAPS=1
    dispatch "${REMAINING_ARGS[@]}"
}

main "$@"
