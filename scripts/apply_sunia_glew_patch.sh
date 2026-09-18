#!/system/bin/sh
# apply_sunia_glew_patch.sh -- force the glew EGL dispatch table to be
# initialised inside com.coloros.note's libSuniaEngine.so.
#
# Run as root ON THE DEVICE:
#     su -c 'sh /data/local/tmp/apply_sunia_glew_patch.sh status'
#     su -c 'sh /data/local/tmp/apply_sunia_glew_patch.sh apply'
#     su -c 'sh /data/local/tmp/apply_sunia_glew_patch.sh restore'
#
# Why: EglContext3::eglInit() calls glewInit() to fill the `__eglew*` table.
# glewInit() early-returns when its GL probe fails (the temporary EGL context
# is unusable on this port), so eglewInit() never runs and __eglewGetDisplay
# stays NULL -> SIGSEGV at EglContext3::eglInit()+76 when any doodle canvas is
# opened. See docs/sunia_glew_egl_crash_20260918.md.
#
# Patch = 20 bytes at file offset 6896812 (virtual 0x697cac), applied with dd
# in place so inode, ownership and SELinux context are preserved.

PKG=com.coloros.note
OFF=6896812
PATCH_BIN=/data/local/tmp/sunia_glew_patch.bin
ORIG_BIN=/data/local/tmp/sunia_glew_orig.bin

# md5 of the unpatched and patched libSuniaEngine.so (16.7.2 / this ROM)
MD5_ORIG=42fb5fd53e04368ca42d255579075d96
MD5_PATCHED=b722fea2603c86e867d98f8852c35e02

MODE="${1:-status}"

hexdump20() {
    dd if="$1" bs=1 skip=$OFF count=20 2>/dev/null | od -A n -t x1 -v | tr -d ' \n'
}

PATCHHEX=$(od -A n -t x1 -v < "$PATCH_BIN" 2>/dev/null | tr -d ' \n')
ORIGHEX=$(od -A n -t x1 -v < "$ORIG_BIN" 2>/dev/null | tr -d ' \n')
if [ -z "$PATCHHEX" ] || [ -z "$ORIGHEX" ]; then
    echo "!! $PATCH_BIN / $ORIG_BIN missing"
    exit 1
fi

found=0
rc=0
for LIB in /data/app/*/$PKG-*/lib/arm64/libSuniaEngine.so; do
    [ -f "$LIB" ] || continue
    found=1
    full=$(md5sum "$LIB" | awk '{print $1}')
    site=$(hexdump20 "$LIB")

    state=UNKNOWN
    [ "$site" = "$PATCHHEX" ] && state=PATCHED
    [ "$site" = "$ORIGHEX" ] && state=ORIGINAL

    echo "$LIB"
    echo "  md5   = $full"
    echo "  site  = $site"
    echo "  state = $state"

    case "$MODE" in
    status)
        ;;
    apply)
        if [ "$state" = PATCHED ]; then
            echo "  -> already patched, nothing to do"
            continue
        fi
        if [ "$full" != "$MD5_ORIG" ]; then
            echo "  -> !! refusing: unexpected build (want md5 $MD5_ORIG)"
            rc=1
            continue
        fi
        if pidof $PKG >/dev/null 2>&1; then
            echo "  -> stopping $PKG"
            am force-stop $PKG
            sleep 1
        fi
        dd if="$PATCH_BIN" of="$LIB" bs=1 seek=$OFF conv=notrunc 2>/dev/null
        sync
        new=$(hexdump20 "$LIB")
        echo "  -> after: $new"
        if [ "$new" = "$PATCHHEX" ]; then
            echo "  -> OK patched"
        else
            echo "  -> !! verify FAILED"
            rc=1
        fi
        ;;
    restore)
        if [ "$state" = ORIGINAL ]; then
            echo "  -> already original, nothing to do"
            continue
        fi
        pidof $PKG >/dev/null 2>&1 && { am force-stop $PKG; sleep 1; }
        dd if="$ORIG_BIN" of="$LIB" bs=1 seek=$OFF conv=notrunc 2>/dev/null
        sync
        echo "  -> after: $(hexdump20 "$LIB")"
        ;;
    *)
        echo "usage: $0 [status|apply|restore]"
        exit 2
        ;;
    esac
done

if [ "$found" = 0 ]; then
    echo "no libSuniaEngine.so found for $PKG under /data/app"
    exit 1
fi
exit $rc
