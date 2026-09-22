#!/usr/bin/env python3
"""Package the KernelSU Root module without runtime files from prior installs."""

from __future__ import annotations

import stat
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

from pen_release import latest_hook_apk  # noqa: E402


INCLUDE = (
    "README.md",
    "action.sh",
    "bin/penlog.sh",
    "customize.sh",
    "module.prop",
    "panic.sh",
    "post-fs-data.sh",
    "service.sh",
    "guard_note_engine.sh",
    # 目录条目：构建时自动展开为目录下所有 *.sh（按文件名排序）。
    # service.sh 通过 `. "$MODDIR/lib/x.sh"` 加载这些文件；把它们做成目录
    # 而不是逐个列举，是因为「新增 lib 文件却忘了加白名单」是一个不会报错
    # 的失效 —— 手工 cp 到设备时一切正常，但 zip 里永久缺该文件，直到下次
    # 重装模块才以 command not found 的形式暴露。
    "lib",
    "engine/libSuniaEngine.16.7.2.fixed.so",
    "system/usr/keylayout/Vendor_17ef_Product_622e.kl",
    "uninstall.sh",
)


def expand_includes(module_dir: Path) -> list[str]:
    """把 INCLUDE 里的目录条目展开成具体的文件清单。

    目录只收直系 *.sh（跳过隐藏文件与子目录），排序保证 zip 条目顺序稳定 ——
    否则同样的源码在不同文件系统上会产出内容相同但顺序不同的 zip。
    """
    names: list[str] = []
    for name in INCLUDE:
        path = module_dir / name
        if path.is_dir():
            names.extend(
                f"{name}/{child.name}"
                for child in sorted(path.iterdir(), key=lambda p: p.name)
                if child.is_file() and child.suffix == ".sh" and not child.name.startswith(".")
            )
        else:
            names.append(name)
    return names

def find_latest_hook_apk(repo: Path) -> Path:
    """Newest Hook APK by VERSION order.

    Was `sorted(glob(...))[-1]`, a lexicographic max: it ranks v4.5.4 above
    v4.5.10, and it will happily embed a pre-rename (com.aclaniakea.*) APK into
    the module zip, leaving the device with a "deployed" module that is never
    injected. Kept as a thin wrapper so the ordering rule lives in exactly one
    place (scripts/pen_release.py).
    """
    return latest_hook_apk(repo / "releases")


def build(module_dir: Path, output: Path) -> None:
    repo = module_dir.parents[0]  # tb522fu-pen-port layout: module/ at repo root
    includes = expand_includes(module_dir)
    missing = [name for name in includes if not (module_dir / name).is_file()]
    hook_apk = find_latest_hook_apk(repo)
    if missing:
        raise FileNotFoundError("missing module files: " + ", ".join(missing))
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in includes:
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
