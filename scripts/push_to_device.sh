#!/usr/bin/env bash
# Push TB522FU pen-bridge release artifacts to the device and emit checksums.
#
# Usage:
#   scripts/push_to_device.sh [SERIAL]
#
# DEST: /sdcard/Download/tb522fu-pen-bridge/
# Also writes <file>.md5.txt next to each artifact for on-device verification.
#
# Note: macOS bsdtar/xattr pitfalls do NOT apply here (adb push copies bytes
# verbatim), so a plain push is safe. Verify with the md5 sidecar on device.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REL="$REPO/releases"
DEST="/sdcard/Download/tb522fu-pen-bridge"

# --- adb resolution -----------------------------------------------------------
# The old default (/opt/homebrew/bin/adb) does not exist on this Mac.
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

# NOTE: every use of SERIAL_ARG below is written as ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"}
# on purpose. macOS ships bash 3.2, where expanding an EMPTY array under
# `set -u` aborts with "SERIAL_ARG[@]: unbound variable" -- i.e. exactly the
# common case of a single connected device with no -s argument. Do not
# "simplify" it back to "${SERIAL_ARG[@]}".
SERIAL_ARG=()
if [ "$#" -ge 1 ] && [ -n "${1:-}" ] && [ "${1#--}" = "$1" ]; then
    SERIAL_ARG=(-s "$1")
fi

"$ADB" ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"} get-state >/dev/null 2>&1 || {
    echo "no device. connect USB and retry: $ADB devices" >&2; exit 1;
}

# --- artifact resolution ------------------------------------------------------
# Resolved by version, never hardcoded. This list used to pin
# PenBridge-Hook-tb522fu-v4.1.3.apk -- the pre-rename package
# (com.aclaniakea.lenovopenbridge). Pushing that and then flashing the module zip
# gives a device where the module is "enabled" but nothing is injected, i.e. all
# custom pen keys (101-113) dead. See TODO.md P1.5.
# `sort -V`, not `sort`: lexicographic order puts v4.1.13 after v4.5.4.
latest_by_version() {  # $1 = glob
    # shellcheck disable=SC2086
    ls -1 $1 2>/dev/null | sort -V | tail -1
}

MODULE_ZIP="$(latest_by_version "$REL/tb522fu-pen-bridge-v*.zip")"
HOOK_APK="$(latest_by_version "$REL/PenBridge-Hook-tb522fu-v*.apk")"
HIDCTL_APK="$(latest_by_version "$REL/PenHidCtl-tb522fu-*.apk")"

ARTIFACTS=()
for f in "$MODULE_ZIP" "$HOOK_APK" "$HIDCTL_APK"; do
    [ -n "$f" ] && [ -f "$f" ] || { echo "artifact not found in $REL" >&2; exit 1; }
    ARTIFACTS+=("$(basename "$f")")
done

echo "adb: $ADB"
for name in "${ARTIFACTS[@]}"; do echo "  -> $name"; done

"$ADB" ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"} shell mkdir -p "$DEST"

for name in "${ARTIFACTS[@]}"; do
    src="$REL/$name"
    if command -v md5 >/dev/null 2>&1; then
        sum=$(md5 -q "$src")
    else
        sum=$(md5sum "$src" | awk '{print $1}')
    fi
    printf '%s  %s\n' "$sum" "$name" > "$REL/$name.md5.txt"
    echo "push $name  (md5 $sum)"
    "$ADB" ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"} push "$src" "$DEST/$name" >/dev/null
    "$ADB" ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"} push "$REL/$name.md5.txt" "$DEST/$name.md5.txt" >/dev/null
done

echo
echo "on-device: $DEST"
"$ADB" ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"} shell ls -l "$DEST"
echo
echo "verify on device:  adb shell 'cd $DEST && md5sum -c *.md5.txt'"
echo "then flash $DEST/${MODULE_ZIP##*/} in KernelSU manager,"
echo "or deploy the whole thing in one shot: scripts/deploy_vector.sh"
