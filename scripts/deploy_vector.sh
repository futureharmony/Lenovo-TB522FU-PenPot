#!/usr/bin/env bash
# Deploy the TB522FU pen-bridge (Hook APK + KernelSU module) to the device.
#
# Framework is Vector (zygisk_vector), not LSPosed.
#
# Usage:
#   scripts/deploy_vector.sh [SERIAL] [--no-module] [--no-scope] [--no-reboot]
#
# Notes baked in from real failures:
#   * `adb install` is rejected by ColorOS (Failure [-99]) -> use root `pm install`.
#   * Changing the signing key requires uninstalling the old APK first
#     (INSTALL_FAILED_UPDATE_INCOMPATIBLE otherwise). This script always
#     uninstalls + reinstalls, which is harmless when the key is unchanged.
#   * Vector CLI is fully scriptable; no manual tapping in the manager.
#   * Boot guard only covers the KSU module. If boot hangs, disable the Hook
#     module too:  vector-cli modules disable com.futureharmony.lenovopenbridge
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.futureharmony.lenovopenbridge
CLI=/data/adb/modules/zygisk_vector/cli
# Refuse anything from the pre-rename package (com.aclaniakea.lenovopenbridge).
MIN_HOOK_VERSION=4.5.0

# --- adb resolution -----------------------------------------------------------
# This used to be hardcoded to /opt/homebrew/bin/adb, which does not exist on
# this Mac (platform-tools live under ~/Library/Android/sdk). Prefer $ADB, then
# PATH, then the usual install locations.
if [ -z "${ADB:-}" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    else
        for c in "$HOME/Library/Android/sdk/platform-tools/adb" \
                 /opt/homebrew/bin/adb /usr/local/bin/adb; do
            [ -x "$c" ] && ADB="$c" && break
        done
    fi
fi
[ -n "${ADB:-}" ] && [ -x "$ADB" ] || {
    echo "adb not found; install platform-tools or set ADB=/path/to/adb" >&2; exit 1;
}

# --- artifact resolution ------------------------------------------------------
# NEVER hardcode a version here. This default used to be v4.1.13, i.e. the OLD
# package name: deploying it installed com.aclaniakea.lenovopenbridge while the
# script went on to `enable`/`scope set` com.futureharmony.lenovopenbridge, so
# the module was enabled but nothing was injected -- every custom pen key dead
# (101-113), with all app-side hooks looking perfectly healthy. See TODO.md P1.5.
# `sort -V` is version order; plain `sort`/glob would pick v4.1.13 over v4.5.4.
latest_by_version() {  # $1 = glob, prints newest match or nothing
    # shellcheck disable=SC2086
    ls -1 $1 2>/dev/null | sort -V | tail -1
}
HOOK_APK="${HOOK_APK:-$(latest_by_version "$REPO/releases/PenBridge-Hook-tb522fu-v*.apk")}"
MODULE_ZIP="${MODULE_ZIP:-$(latest_by_version "$REPO/releases/tb522fu-pen-bridge-v*.zip")}"

# --- scope resolution ---------------------------------------------------------
# Derived from the Xposed manifest the APK is built from, so the runtime table
# can never drift from the declared one. `vector-cli scope set` OVERWRITES the
# whole scope, so a retyped/partial list silently deletes whatever it omits.
#
# NOTE: the first entry is the system_server pseudo-package `system/0`, NOT
# `android/0`. Verified the hard way twice (2026-09-18 and again 2026-09-19):
# with only `android/0` the module is NOT injected into system_server at all --
# no `handleLoadPackage pkg=android`, no PhoneWindowManager
# .interceptKeyBeforeQueueing hook, no `system_server stylus hooks installed` --
# while every app-scoped hook keeps working, so it looks exactly like a code bug.
SCOPE_LIST="$REPO/hook/source/resources/META-INF/xposed/scope.list"
[ -f "$SCOPE_LIST" ] || { echo "missing $SCOPE_LIST" >&2; exit 1; }
SCOPE="$(sed -e 's/#.*//' -e 's/[[:space:]]*$//' -e '/^$/d' "$SCOPE_LIST" \
         | awk '{print $1"/0"}' | awk '!seen[$0]++' | tr '\n' ' ')"
SCOPE="${SCOPE% }"
SYSTEM_ENTRY="system/0"
case " $SCOPE " in
    *" $SYSTEM_ENTRY "*) ;;
    *) echo "$SCOPE_LIST has no 'system' entry -- system_server would never be injected" >&2
       exit 1 ;;
esac

SERIAL=""
DO_MODULE=1
DO_SCOPE=1
DO_REBOOT=1
for a in "$@"; do
    case "$a" in
        --no-module) DO_MODULE=0 ;;
        --no-scope)  DO_SCOPE=0 ;;
        --no-reboot) DO_REBOOT=0 ;;
        -*) echo "unknown flag: $a" >&2; exit 2 ;;
        *) SERIAL="$a" ;;
    esac
done
A=("$ADB")
[ -n "$SERIAL" ] && A+=(-s "$SERIAL")

"${A[@]}" get-state >/dev/null 2>&1 || { echo "no device; adb connect first" >&2; exit 1; }
[ -f "$HOOK_APK" ] || { echo "missing $HOOK_APK" >&2; exit 1; }

echo "== 0. preflight =="
echo "  adb      : $ADB"
echo "  Hook APK : ${HOOK_APK#$REPO/}"
echo "  module   : ${MODULE_ZIP#$REPO/}"
echo "  scope    : $SCOPE"

# Version floor: a pre-rename APK installs under a package name we never enable.
HOOK_VER="$(basename "$HOOK_APK" | sed -n 's/.*-v\([0-9][0-9.]*\)\.apk$/\1/p')"
[ -n "$HOOK_VER" ] || { echo "cannot parse version from $(basename "$HOOK_APK")" >&2; exit 1; }
NEWEST="$(printf '%s\n%s\n' "$MIN_HOOK_VERSION" "$HOOK_VER" | sort -V | tail -1)"
[ "$NEWEST" = "$HOOK_VER" ] || {
    echo "refusing stale Hook APK v$HOOK_VER (< v$MIN_HOOK_VERSION, pre-rename package)" >&2; exit 1; }

# The APK declares its own scope; it must agree with the runtime table we write.
APK_SCOPE="$(unzip -p "$HOOK_APK" META-INF/xposed/scope.list 2>/dev/null | tr -d '\r' || true)"
if [ -z "$APK_SCOPE" ]; then
    echo "  [warn] $HOOK_APK declares no META-INF/xposed/scope.list" >&2
elif ! printf '%s\n' "$APK_SCOPE" | grep -qx "system"; then
    echo "refusing $HOOK_APK: declared scope has no 'system' (system_server would never be injected)" >&2
    exit 1
else
    echo "  declared scope OK (system + $(($(printf '%s\n' "$APK_SCOPE" | grep -c .) - 1)) apps)"
fi

echo "== 1. Hook APK =="
"${A[@]}" push "$HOOK_APK" /data/local/tmp/PenBridge-Hook.apk >/dev/null
"${A[@]}" shell "su -c '
  $CLI modules disable $PKG >/dev/null 2>&1 || true
  pm uninstall $PKG >/dev/null 2>&1 || true
  pm install -r -d /data/local/tmp/PenBridge-Hook.apk
'"

if [ "$DO_SCOPE" = "1" ]; then
    echo "== 2. Vector: enable + scope =="
    "${A[@]}" shell "su -c '$CLI modules enable $PKG && $CLI scope set $PKG $SCOPE'"
else
    echo "== 2. Vector: enable (scope skipped) =="
    "${A[@]}" shell "su -c '$CLI modules enable $PKG'"
fi

if [ "$DO_MODULE" = "1" ]; then
    [ -f "$MODULE_ZIP" ] || { echo "missing $MODULE_ZIP" >&2; exit 1; }
    echo "== 3. KernelSU module =="
    "${A[@]}" push "$MODULE_ZIP" /data/local/tmp/pen-bridge.zip >/dev/null
    "${A[@]}" shell "su -c 'ksud module install /data/local/tmp/pen-bridge.zip && rm -f /data/adb/tb522fu_pen_bridge.bootfail'"
fi

echo "== 4. verify =="
"${A[@]}" shell "su -c '
  $CLI modules ls | grep -i lenovopenbridge
  pm path $PKG
'"

# Read the scope back. `scope set` is a whole-table overwrite; trusting its exit
# code alone is what let the `android/0` regression ride through two reboots.
if [ "$DO_SCOPE" = "1" ]; then
    ACTUAL="$("${A[@]}" shell "su -c '$CLI scope ls $PKG'" | tr -d '\r' | tail -n +3 \
             | awk 'NF>=2 {print $1"/"$2}' | sort)"
    if printf '%s\n' "$ACTUAL" | grep -qx "$SYSTEM_ENTRY"; then
        echo "  scope verify: $SYSTEM_ENTRY present ✓"
    else
        echo "  scope verify: FAILED -- $SYSTEM_ENTRY missing after scope set." >&2
        echo "    system_server will NOT be injected; custom pen keys stay dead." >&2
        printf '%s\n' "$ACTUAL" | sed 's/^/    got: /' >&2
        exit 1
    fi
    EXPECTED="$(printf '%s\n' $SCOPE | sort)"
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "  [warn] runtime scope != $SCOPE_LIST:" >&2
        diff <(printf '%s\n' "$EXPECTED") <(printf '%s\n' "$ACTUAL") | sed 's/^/    /' >&2 || true
    fi
fi

if [ "$DO_REBOOT" = "1" ]; then
    echo "== 5. reboot =="
    "${A[@]}" shell "su -c reboot"
    echo "rebooting; watch boot with:"
    echo "  $ADB wait-for-device shell getprop sys.boot_completed"
    echo "then confirm injection (3s after boot_completed):"
    echo "  $ADB logcat -d -s LenovoPenBridge | grep -E 'handleLoadPackage pkg=android|stylus hooks installed'"
fi
