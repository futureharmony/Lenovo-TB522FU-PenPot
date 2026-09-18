#!/usr/bin/env python3
"""Surgically patch libSuniaEngine.so (com.coloros.note) so that the glew EGL
dispatch table is guaranteed to be populated.

Background (see docs/sunia_glew_egl_crash_20260918.md):

    EglContext3::eglInit():                     @0x697c70
        bl  EglInit::makeTempEglContext()       @0x697c90
        strb w9, [__X]                          @0x697ca0
        bl  glewInit@plt                        @0x697ca4
        bl  EglInit::clearTempEglContext()      @0x697ca8
        adrp/ldr x8, [GOT __eglewGetDisplay]    @0x697cac..0x697cb4
        blr x8                                  @0x697cbc  *** NULL -> SIGSEGV ***

    glewInit():                                 @0x828bcc
        bl  _glewInit()                         @0x83962c  (GL version probe)
        cbz w0, +0x18                           ; err == 0 -> continue
        ret                                     ; err != 0 -> EARLY RETURN
        ... p = eglGetProcAddress("eglGetCurrentDisplay"); dpy = p();
        b   eglewInit@plt                       @0xb97580

On this device the temporary EGL/GL context is not usable, so _glewInit()
returns GLEW_ERROR_NO_GL_VERSION(1), glewInit() returns early, eglewInit()
never runs, and the whole `__eglew*` table (149 cells) stays NULL -> crash the
first time the engine calls through it.

The patch replaces the 5 instructions at 0x697cac..0x697cbc (20 bytes, exactly
the same size, so no code cave / no segment surgery is needed) with:

        adrp x8, 0xe0a000
        ldr  x0, [x8, #0xdb0]      ; x0 = EglInit::s_display  (valid, obtained
                                   ;      by makeTempEglContext; it threw if NULL)
        bl   eglewInit@plt         ; 0xb97580  -> fills __eglew* + capability flags
        mov  x0, xzr               ; EGL_DEFAULT_DISPLAY
        bl   eglGetDisplay@plt     ; 0xb90c20  == what __eglewGetDisplay would do

x0 afterwards holds the display handle that the following instruction
(`str x0, [x19, #0xd48]`) stores, so the rest of eglInit() is unaffected.

Usage:
    patch_sunia_glew.py <input.so> <output.so>
    patch_sunia_glew.py --verify <file.so>          # report patched/unpatched
"""

import hashlib
import struct
import sys

# virtual address of the 5-instruction block inside libSuniaEngine.so
PATCH_VA = 0x697CAC
# vaddr -> file offset for the executable PT_LOAD (p_vaddr 0x4dc840 = p_offset 0x4d8840)
TEXT_VADDR = 0x4DC840
TEXT_OFF = 0x4D8840
PATCH_FILE_OFF = TEXT_OFF + (PATCH_VA - TEXT_VADDR)  # 0x693cac

# ---------------------------------------------------------------- encoders ----
def enc_adrp(va, imm_target, rd):
    """adrp rd, imm_target  (page-relative)"""
    page = va & ~0xFFF
    imm = (imm_target - page) >> 12
    assert -(1 << 20) <= imm < (1 << 20), "adrp out of range"
    immlo = imm & 3
    immhi = (imm >> 2) & 0x7FFFF
    return 0x90000000 | (immlo << 29) | (immhi << 5) | rd


def enc_ldr_imm64(rt, rn, off):
    """ldr rt, [rn, #off]   (64-bit, unsigned offset, off%8==0)"""
    assert off % 8 == 0 and off // 8 < 0x1000
    return 0xF9400000 | ((off // 8) << 10) | (rn << 5) | rt


def enc_bl(va, target):
    off = target - va
    assert off % 4 == 0, "bl target not 4-byte aligned"
    imm26 = (off >> 2) & 0x03FFFFFF
    assert -(1 << 25) <= (off >> 2) < (1 << 25), "bl out of +/-128MB range"
    return 0x94000000 | imm26


def enc_mov_x0_xzr():
    # the compiler emits the `orr x0, xzr, xzr` alias; keep byte-identical style
    return 0xAA1F03E0  # mov x0, xzr


# addresses inside libSuniaEngine.so (this exact build)
EGLEWINIT_PLT = 0xB97580
EGLGETDISPLAY_PLT = 0xB90C20
EglInit_s_display = 0xE0ADB0  # EglInit::s_display (written by makeTempEglContext)
GOT_eglewGetDisplay = 0xC2A3A8

# the *.so build this patch is valid for
EXPECTED_MD5 = "42fb5fd53e04368ca42d255579075d96"
EXPECTED_SIZE = 14658800


def build_patch():
    words = [
        enc_adrp(PATCH_VA + 0x0, EglInit_s_display, 8),        # adrp x8, 0xe0a000
        enc_ldr_imm64(0, 8, EglInit_s_display & 0xFFF),        # ldr  x0, [x8, #0xdb0]
        enc_bl(PATCH_VA + 0x8, EGLEWINIT_PLT),                 # bl   eglewInit@plt
        enc_mov_x0_xzr(),                                      # mov  x0, xzr
        enc_bl(PATCH_VA + 0x10, EGLGETDISPLAY_PLT),            # bl   eglGetDisplay@plt
    ]
    return b"".join(struct.pack("<I", w) for w in words)


PATCH_BYTES = build_patch()

# the original instructions (for verification / un-patching)
ORIG_BYTES = struct.pack(
    "<IIIII",
    enc_adrp(PATCH_VA, 0xC2A000, 8),                 # adrp x8, 0xc2a000
    enc_mov_x0_xzr(),                                # mov  x0, xzr
    enc_ldr_imm64(8, 8, GOT_eglewGetDisplay & 0xFFF),  # ldr  x8, [x8, #0x3a8]
    0xF9400108,                                      # ldr  x8, [x8]
    0xD63F0100,                                      # blr  x8
)


def md5(b):
    return hashlib.md5(b).hexdigest()


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2

    if args[0] == "--verify":
        data = open(args[1], "rb").read()
        cur = data[PATCH_FILE_OFF:PATCH_FILE_OFF + 20]
        print(f"file      : {args[1]}  size={len(data)}  md5={md5(data)}")
        print(f"@0x{PATCH_VA:x} (off 0x{PATCH_FILE_OFF:x}) = {cur.hex()}")
        if cur == PATCH_BYTES:
            print("state     : PATCHED (glew EGL table will be force-initialised)")
        elif cur == ORIG_BYTES:
            print("state     : ORIGINAL (unpatched)")
        else:
            print("state     : UNKNOWN -- neither original nor patched bytes!")
            return 3
        return 0

    if len(args) != 2:
        print(__doc__)
        return 2

    src, dst = args
    data = bytearray(open(src, "rb").read())
    digest = md5(bytes(data))
    if len(data) != EXPECTED_SIZE or digest != EXPECTED_MD5:
        print(f"!! refusing to patch: unexpected input\n"
              f"   size {len(data)} (expected {EXPECTED_SIZE})\n"
              f"   md5  {digest} (expected {EXPECTED_MD5})")
        return 4

    cur = bytes(data[PATCH_FILE_OFF:PATCH_FILE_OFF + 20])
    if cur == PATCH_BYTES:
        print("already patched; nothing to do")
        return 0
    if cur != ORIG_BYTES:
        print(f"!! unexpected bytes at patch site: {cur.hex()}")
        return 4

    data[PATCH_FILE_OFF:PATCH_FILE_OFF + 20] = PATCH_BYTES
    open(dst, "wb").write(data)
    print(f"in : {src}  md5={digest}")
    print(f"out: {dst}  md5={md5(bytes(data))}")
    print(f"patch site 0x{PATCH_FILE_OFF:x} ({PATCH_VA:#x}):")
    print(f"  was {ORIG_BYTES.hex()}")
    print(f"  now {PATCH_BYTES.hex()}")
    print("\non-device apply (in place, keeps inode/selinux context):")
    print(f"  su -c 'dd if=/data/local/tmp/sunia_patch.bin "
          f"of=<app lib>/libSuniaEngine.so bs=1 seek={PATCH_FILE_OFF} conv=notrunc'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
