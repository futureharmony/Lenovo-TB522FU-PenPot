#!/usr/bin/env python3
"""Build the libSuniaEngine.so fix that gives glew a working init.

Pipeline
--------
1. compile scripts/sunia_stub.c for aarch64 (Homebrew clang, -nostdlib style)
2. place .text at 0xE20000 and .rodata* at 0xE21000 and resolve the (only local)
   relocations by hand -- we cannot use ld.lld, but all 14 of them are simple
   ADR_PREL_PG_HI21 / ADD_ABS_LO12_NC pairs against our own .rodata
3. append the resulting blob as a brand new PT_LOAD segment, reusing the
   program header slot of PT_NOTE (bionic does not need PT_NOTE)
4. redirect the 5-instruction glew dispatch block in EglContext3::eglInit()
   (virtual 0x697cac, file offset 6896812) to `bl 0xE20000` + 4 nops
5. verify: dump the disassembly of the new segment and of the call site

Usage:
    build_sunia_stub.py <libSuniaEngine.so> <out.so>
    build_sunia_stub.py --verify <file.so>
"""

import hashlib
import os
import re
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "sunia_stub.c")
BUILD = os.path.join(HERE, ".build")

LLVM_BIN = "/opt/homebrew/opt/llvm/bin"
CLANG = os.path.join(LLVM_BIN, "clang")
READELF = os.path.join(LLVM_BIN, "llvm-readelf")
OBJCOPY = os.path.join(LLVM_BIN, "llvm-objcopy")
OBJDUMP = os.path.join(LLVM_BIN, "llvm-objdump")

# ---- target build identity -------------------------------------------------
EXPECTED_MD5 = "42fb5fd53e04368ca42d255579075d96"
EXPECTED_SIZE = 14658800

# ---- original layout -------------------------------------------------------
PHDR_OFF = 0x40
PHDR_ENTSIZE = 0x38
PHDR_NOTE_INDEX = 10          # the PT_NOTE slot we repurpose
ORIG_FILE_END = 0xDFA300      # size of the original file

CALL_SITE_VA = 0x697CAC
CALL_SITE_OFF = 0x693CAC      # 6896812

# ---- our new segment -------------------------------------------------------
SEG_OFF = 0xE00000            # must be 0x4000-aligned
SEG_VA = 0xE20000             # must be 0x4000-aligned, above the last segment
SEG_SIZE = 0x2000
TEXT_VA = SEG_VA
RODATA_VA = SEG_VA + 0x1000

PT_LOAD = 1
PF_X, PF_W, PF_R = 1, 2, 4

NOP = 0xD503201F

RELOC_PG_HI21 = 0x113
RELOC_LO12_NC = 0x115


def md5(b):
    return hashlib.md5(b).hexdigest()


def run(*cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit(f"command failed: {' '.join(cmd)}")
    return r.stdout


def enc_bl(va, target):
    off = target - va
    assert off % 4 == 0
    imm26 = (off >> 2) & 0x03FFFFFF
    assert -(1 << 25) <= (off >> 2) < (1 << 25), "bl out of range"
    return 0x94000000 | imm26


def compile_stub():
    os.makedirs(BUILD, exist_ok=True)
    obj = os.path.join(BUILD, "sunia_stub.o")
    run(CLANG, "-target", "aarch64-linux-android", "-O2",
        "-fno-stack-protector", "-fno-exceptions", "-fno-unwind-tables",
        "-fno-asynchronous-unwind-tables", "-fomit-frame-pointer",
        "-mgeneral-regs-only", "-fno-builtin", "-c", SRC, "-o", obj)

    # no undefined symbols allowed
    syms = run(READELF, "-sW", obj)
    und = [l for l in syms.splitlines() if " UND " in l and "NOTYPE  LOCAL" not in l]
    if und:
        raise SystemExit("stub has undefined symbols:\n" + "\n".join(und))

    def section(name, out):
        run(OBJCOPY, "-O", "binary", "--only-section=" + name, obj,
            os.path.join(BUILD, out))
        return open(os.path.join(BUILD, out), "rb").read()

    text = section(".text", "text.bin")
    rodata = section(".rodata", "rodata.bin")
    rostr = section(".rodata.str1.1", "rostr.bin")

    # section placement inside the segment
    rodata_off = RODATA_VA - SEG_VA
    rostr_off = ((RODATA_VA + len(rodata) + 3) // 4) * 4 - SEG_VA
    base = {".rodata": RODATA_VA, ".rodata.str1.1": RODATA_VA + (rostr_off - rodata_off)}

    seg = bytearray(SEG_SIZE)
    seg[0:len(text)] = text
    seg[rodata_off:rodata_off + len(rodata)] = rodata
    seg[rostr_off:rostr_off + len(rostr)] = rostr

    # ---- resolve relocations by hand ----
    rel = run(READELF, "-rW", obj)
    patched = 0
    for line in rel.splitlines():
        m = re.match(r"^([0-9a-f]{16})\s+[0-9a-f]+\s+(R_AARCH64_\S+)\s+[0-9a-f]+\s+(\S+) \+ ([0-9a-f]+)$", line.strip())
        if not m:
            continue
        off = int(m.group(1), 16)
        kind = m.group(2)
        sec = m.group(3)
        add = int(m.group(4), 16)
        if sec not in base:
            raise SystemExit(f"unexpected reloc against {sec}")
        target = base[sec] + add
        pc = TEXT_VA + off
        cur = struct.unpack_from("<I", seg, off)[0]
        rd = cur & 0x1F
        if kind == "R_AARCH64_ADR_PREL_PG_HI21":
            imm = (target & ~0xFFF) - (pc & ~0xFFF)
            imm >>= 12
            assert -(1 << 20) <= imm < (1 << 20), f"adrp out of range for {sec}+{add:x}"
            word = 0x90000000 | ((imm & 3) << 29) | (((imm >> 2) & 0x7FFFF) << 5) | rd
        elif kind == "R_AARCH64_ADD_ABS_LO12_NC":
            imm12 = target & 0xFFF
            rn = (cur >> 5) & 0x1F
            word = 0x91000000 | (imm12 << 10) | (rn << 5) | rd
        else:
            raise SystemExit(f"unhandled reloc {kind}")
        struct.pack_into("<I", seg, off, word)
        patched += 1

    return bytes(seg), patched, len(text), len(rodata), len(rostr)


def build(src, dst):
    data = bytearray(open(src, "rb").read())
    digest = md5(bytes(data))
    if len(data) != EXPECTED_SIZE or digest != EXPECTED_MD5:
        raise SystemExit(f"!! unexpected input: size {len(data)} md5 {digest}")

    seg, nrel, lt, lr, ls = compile_stub()
    print(f"stub: .text={lt}B .rodata={lr}B .rodata.str1.1={ls}B relocs={nrel}")

    site = bytes(data[CALL_SITE_OFF:CALL_SITE_OFF + 20])
    if site == struct.pack("<IIIII",
                           enc_bl(CALL_SITE_VA, SEG_VA), NOP, NOP, NOP, NOP):
        raise SystemExit("already patched")
    if site != bytes.fromhex("882c00f0e0031faa08d541f9080140f900013fd6"):
        raise SystemExit(f"!! unexpected bytes at call site: {site.hex()}")

    # 1. pad the file up to the new segment offset
    if len(data) > SEG_OFF:
        raise SystemExit("file already larger than segment offset")
    data.extend(b"\x00" * (SEG_OFF - len(data)))
    data.extend(seg)
    assert len(data) == SEG_OFF + SEG_SIZE

    # 2. repurpose the PT_NOTE program header as our PT_LOAD
    ph = struct.unpack_from("<IIQQQQQQ", data, PHDR_OFF + PHDR_NOTE_INDEX * PHDR_ENTSIZE)
    print(f"phdr[{PHDR_NOTE_INDEX}] was: type={ph[0]} off={ph[1]:#x} va={ph[2]:#x} "
          f"filesz={ph[4]:#x}")
    struct.pack_into("<IIQQQQQQ", data, PHDR_OFF + PHDR_NOTE_INDEX * PHDR_ENTSIZE,
                     PT_LOAD, PF_R | PF_X, SEG_OFF, SEG_VA, SEG_VA,
                     SEG_SIZE, SEG_SIZE, 0x4000)

    # 3. redirect the glew dispatch block to our stub
    struct.pack_into("<IIIII", data, CALL_SITE_OFF,
                     enc_bl(CALL_SITE_VA, SEG_VA), NOP, NOP, NOP, NOP)

    open(dst, "wb").write(bytes(data))
    print(f"in : {src}  size={EXPECTED_SIZE} md5={digest}")
    print(f"out: {dst}  size={len(data)} md5={md5(bytes(data))}")
    return dst


def disasm(path, start, stop):
    return run(OBJDUMP, "-d", f"--start-address={start:#x}",
               f"--stop-address={stop:#x}", "--no-show-raw-insn", path)


def verify(path):
    data = open(path, "rb").read()
    ph = struct.unpack_from("<IIQQQQQQ", data, PHDR_OFF + PHDR_NOTE_INDEX * PHDR_ENTSIZE)
    site = data[CALL_SITE_OFF:CALL_SITE_OFF + 20]
    print(f"file  : {path}  size={len(data)}  md5={md5(data)}")
    print(f"phdr[{PHDR_NOTE_INDEX}]: type={ph[0]} flags={ph[1]} off={ph[2]:#x} "
          f"va={ph[3]:#x} filesz={ph[5]:#x} memsz={ph[6]:#x} align={ph[7]:#x}")
    if ph[0] != PT_LOAD:
        print("state : ORIGINAL (no extra segment)")
    else:
        print("state : PATCHED")
    print(f"call site 0x{CALL_SITE_VA:x}: {site.hex()}")
    print(disasm(path, CALL_SITE_VA, CALL_SITE_VA + 0x14))
    if ph[0] == PT_LOAD:
        print(disasm(path, SEG_VA, SEG_VA + 0x40))


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 2
    if a[0] == "--verify":
        verify(a[1])
        return 0
    if len(a) != 2:
        print(__doc__)
        return 2
    out = build(a[0], a[1])
    print()
    verify(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
