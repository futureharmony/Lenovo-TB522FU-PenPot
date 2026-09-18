#!/usr/bin/env bash
# Deploy the TB522FU pen-bridge (Hook APK + KernelSU module) to the device.
#
# Framework is Vector (zygisk_vector), not LSPosed.
#
# Usage:
#   scripts/deploy_vector.sh [SERIAL] [--no-module] [--no-reboot]
#
# Notes baked in from real failures:
#   * `adb install` is rejected by ColorOS (Failure [-99]) -> use root `pm install`.
#   * Changing the signing key requires uninstalling the old APK first
#     (INSTALL_FAILED_UPDATE_INCOMPATIBLE otherwise). This script always
#     uninstalls + reinstalls, which is harmless when the key is unchanged.
#   * Vector CLI is fully scriptable; no manual tapping in the manager.
#   * Boot guard only covers the KSU module. If boot hangs, disable the Hook
#     module too:  vector-cli modules disable com.aclaniakea.lenovopenbridge
set -euo pipefail

ADB="${ADB:-/opt/homebrew/bin/adb}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
HOOK_APK="$REPO/releases/PenBridge-Hook-tb522fu-v4.1.3.apk"
MODULE_ZIP="$REPO/releases/tb522fu-pen-bridge-v0.1.0.zip"
PKG=com.aclaniakea.lenovopenbridge
CLI=/data/adb/modules/zygisk_vector/cli
SCOPE="android/0 com.coloros.note/0 com.oplus.exsystemservice/0 com.oplus.healthservice/0 com.heytap.mydevices/0 com.oplus.ipemanager/0 com.oplus.wirelesssettings/0 com.oplus.screenshot/0"

SERIAL=""
DO_MODULE=1
DO_REBOOT=1
for a in "$@"; do
    case "$a" in
        --no-module) DO_MODULE=0 ;;
        --no-reboot) DO_REBOOT=0 ;;
        -*) echo "unknown flag: $a" >&2; exit 2 ;;
        *) SERIAL="$a" ;;
    esac
done
A=("$ADB")
[ -n "$SERIAL" ] && A+=(-s "$SERIAL")

"${A[@]}" get-state >/dev/null 2>&1 || { echo "no device; adb connect first" >&2; exit 1; }
[ -f "$HOOK_APK" ] || { echo "missing $HOOK_APK" >&2; exit 1; }

echo "== 1. Hook APK =="
"${A[@]}" push "$HOOK_APK" /data/local/tmp/PenBridge-Hook.apk >/dev/null
"${A[@]}" shell "su -c '
  $CLI modules disable $PKG >/dev/null 2>&1 || true
  pm uninstall $PKG >/dev/null 2>&1 || true
  pm install -r -d /data/local/tmp/PenBridge-Hook.apk
'"

echo "== 2. Vector: enable + scope =="
"${A[@]}" shell "su -c '$CLI modules enable $PKG && $CLI scope set $PKG $SCOPE'"

if [ "$DO_MODULE" = "1" ]; then
    [ -f "$MODULE_ZIP" ] || { echo "missing $MODULE_ZIP" >&2; exit 1; }
    echo "== 3. KernelSU module =="
    "${A[@]}" push "$MODULE_ZIP" /data/local/tmp/pen-bridge.zip >/dev/null
    "${A[@]}" shell "su -c 'ksud module install /data/local/tmp/pen-bridge.zip && rm -f /data/adb/tb522fu_pen_bridge.bootfail'"
fi

echo "== 4. verify =="
"${A[@]}" shell "su -c '
  $CLI modules ls | grep -i lenovopenbridge
  $CLI scope ls $PKG | tail -n +3
  pm path $PKG
'"

if [ "$DO_REBOOT" = "1" ]; then
    echo "== 5. reboot =="
    "${A[@]}" shell "su -c reboot"
    echo "rebooting; watch boot with:"
    echo "  $ADB wait-for-device shell getprop sys.boot_completed"
fi
