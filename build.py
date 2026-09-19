#!/usr/bin/env python3
"""Unified build script for TB522FU Pen Bridge (futureharmony).

Orchestrates:
  1. Hook APK compilation  (hook/tools/build_hook_source.py)
  2. Root module zip packaging (module/tools/build_root.py)
  3. MD5 checksum generation
  4. Optional --push to /sdcard/Download/ via adb

Usage:
  python3 build.py           # build only
  python3 build.py --push    # build + push to device
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parent
HOOK_BUILD = REPO / "hook" / "tools" / "build_hook_source.py"
ROOT_BUILD = REPO / "module" / "tools" / "build_root.py"
MODULE_DIR = REPO / "module"
RELEASES   = REPO / "releases"
MODULE_ZIP = RELEASES / "tb522fu-pen-bridge-v0.1.0.zip"


def run(cmd: list[str], label: str) -> None:
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")
    result = subprocess.run(cmd, cwd=str(REPO))
    if result.returncode != 0:
        raise SystemExit(f"FAILED: {label} (exit {result.returncode})")


def md5(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_md5(path: Path) -> Path:
    digest = md5(path)
    md5_file = path.with_suffix(path.suffix + ".md5.txt")
    md5_file.write_text(f"{digest}  {path.name}\n", encoding="utf-8")
    print(f"  MD5: {digest}  {path.name}")
    return md5_file


def find_hook_apk() -> Path:
    candidates = sorted(RELEASES.glob("PenBridge-Hook-tb522fu-v*.apk"))
    if not candidates:
        raise FileNotFoundError("No Hook APK found in releases/")
    return candidates[-1]


def main() -> None:
    parser = argparse.ArgumentParser(description="Build TB522FU Pen Bridge")
    parser.add_argument("--push", action="store_true",
                        help="Push artifacts to /sdcard/Download/ via adb")
    parser.add_argument("--skip-hook", action="store_true",
                        help="Skip Hook APK compilation (use existing)")
    args = parser.parse_args()

    # Step 1: Compile Hook APK
    if not args.skip_hook:
        run([sys.executable, str(HOOK_BUILD)], "编译 LSPosed Hook APK")

    hook_apk = find_hook_apk()
    print(f"\n  Hook APK: {hook_apk.name}")

    # Step 2: Package Root module zip
    run([sys.executable, str(ROOT_BUILD), str(MODULE_DIR), str(MODULE_ZIP)],
        "打包 Root 模块 ZIP")

    # Step 3: Generate MD5 checksums
    print(f"\n{'='*60}")
    print("  生成 MD5 校验")
    print(f"{'='*60}")
    md5_hook = write_md5(hook_apk)
    md5_zip  = write_md5(MODULE_ZIP)

    # Step 4: Push to device (optional)
    if args.push:
        adb = "adb"
        dest = "/sdcard/Download/"
        run([adb, "push", str(hook_apk), dest], f"推送 Hook APK → {dest}")
        run([adb, "push", str(MODULE_ZIP), dest], f"推送 Module ZIP → {dest}")
        print(f"\n✅ 已推送到设备 {dest}")

    print(f"\n{'='*60}")
    print("  ✅ 构建完成")
    print(f"{'='*60}")
    print(f"  Hook APK : {hook_apk}")
    print(f"  Module ZIP: {MODULE_ZIP}")
    print(f"  MD5       : {md5_hook}, {md5_zip}")
    if not args.push:
        print(f"\n  提示: 使用 --push 参数可直接推送到设备")
    print()


if __name__ == "__main__":
    main()
