#!/usr/bin/env python3
"""Package the KernelSU Root module without runtime files from prior installs."""

from __future__ import annotations

import stat
import subprocess
import sys
import zipfile
from pathlib import Path


INCLUDE = (
    "README.md",
    "action.sh",
    "charge-guard.sh",
    "customize.sh",
    "module.prop",
    "panic.sh",
    "post-fs-data.sh",
    "service.sh",
    "guard_note_engine.sh",
    "engine/libSuniaEngine.16.7.2.fixed.so",
    "system/etc/permissions/privapp-permissions-com.aclaniakea.penhidctl.xml",
    "system/priv-app/aclpenhid/PenHidCtl.apk",
    "system/usr/keylayout/Vendor_17ef_Product_622e.kl",
    "uninstall.sh",
)

def find_latest_hook_apk(repo: Path) -> Path:
    candidates = sorted(repo.glob("releases/PenBridge-Hook-tb522fu-v*.apk"))
    if candidates:
        return candidates[-1]
    fallback = repo / "releases" / "PenBridge-Hook-tb522fu-v4.5.4.apk"
    if fallback.is_file():
        return fallback
    raise FileNotFoundError("no PenBridge-Hook APK found in releases/")


def build(module_dir: Path, output: Path) -> None:
    repo = module_dir.parents[0]  # tb522fu-pen-port layout: module/ at repo root
    missing = [name for name in INCLUDE if not (module_dir / name).is_file()]
    hook_apk = find_latest_hook_apk(repo)
    if missing:
        raise FileNotFoundError("missing module files: " + ", ".join(missing))
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in INCLUDE:
            path = module_dir / name
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) | stat.S_IFREG) << 16
            archive.writestr(info, path.read_bytes())
        info = zipfile.ZipInfo("hook/PenBridge-Hook.apk")
        info.compress_type = zipfile.ZIP_STORED
        info.external_attr = (0o644 | stat.S_IFREG) << 16
        archive.writestr(info, hook_apk.read_bytes())
    print(output)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} MODULE_DIR OUTPUT_ZIP")
    build(Path(sys.argv[1]), Path(sys.argv[2]))
