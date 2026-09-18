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

ADB="${ADB:-/opt/homebrew/bin/adb}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
REL="$REPO/releases"
DEST="/sdcard/Download/tb522fu-pen-bridge"

SERIAL_ARG=()
if [ "$#" -ge 1 ] && [ -n "${1:-}" ]; then
    SERIAL_ARG=(-s "$1")
fi

command -v "$ADB" >/dev/null 2>&1 || { echo "adb not found at $ADB" >&2; exit 1; }
"$ADB" "${SERIAL_ARG[@]}" get-state >/dev/null 2>&1 || {
    echo "no device. connect USB and retry: $ADB devices" >&2; exit 1;
}

ARTIFACTS=(
    "tb522fu-pen-bridge-v0.1.0.zip"
    "PenBridge-Hook-tb522fu-v4.1.3.apk"
    "PenHidCtl-tb522fu-1.1.0.apk"
)

"$ADB" "${SERIAL_ARG[@]}" shell mkdir -p "$DEST"

for name in "${ARTIFACTS[@]}"; do
    src="$REL/$name"
    [ -f "$src" ] || { echo "missing: $src" >&2; exit 1; }
    if command -v md5 >/dev/null 2>&1; then
        sum=$(md5 -q "$src")
    else
        sum=$(md5sum "$src" | awk '{print $1}')
    fi
    printf '%s  %s\n' "$sum" "$name" > "$REL/$name.md5.txt"
    echo "push $name  (md5 $sum)"
    "$ADB" "${SERIAL_ARG[@]}" push "$src" "$DEST/$name" >/dev/null
    "$ADB" "${SERIAL_ARG[@]}" push "$REL/$name.md5.txt" "$DEST/$name.md5.txt" >/dev/null
done

echo
echo "on-device: $DEST"
"$ADB" "${SERIAL_ARG[@]}" shell ls -l "$DEST"
echo
echo "verify on device:  adb shell 'cd $DEST && md5sum -c *.md5.txt'"
echo "then flash $DEST/tb522fu-pen-bridge-v0.1.0.zip in KernelSU manager."
