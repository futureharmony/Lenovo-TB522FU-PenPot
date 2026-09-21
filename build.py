#!/usr/bin/env python3
"""Unified build script for TB522FU Pen Bridge (futureharmony).

Orchestrates:
  1. Hook APK compilation  (hook/tools/build_hook_source.py)
  2. Root module zip packaging (module/tools/build_root.py)
  3. MD5 checksum generation
  4. Vector injection-scope self-check / idempotent repair (needs a device)
  5. Optional --push: delegate to scripts/push_to_device.sh (single push path,
     owns the /sdcard/Download/tb522fu-pen-bridge/ destination + md5 sidecars)

Step 4 exists because the scope is device-side state that nothing in the build
owns: renaming the package creates a *new* Vector module id with an empty scope,
and `vector-cli scope set` overwrites the whole table. Twice now the table ended
up missing the `system/0` pseudo-package -- system_server was never injected, all
custom pen keys (101-113) went dead, while every app-scoped hook kept working, so
it read as a code bug. See TODO.md P1.5.

Usage:
  python3 build.py                  # build + scope self-check (if device present)
  python3 build.py --push           # ... and push artifacts to device Download/
  python3 build.py --skip-hook      # reuse the newest existing Hook APK
  python3 build.py --no-device      # never touch a device
  python3 build.py --no-scope-fix   # report scope drift but do not repair it
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent
sys.path.insert(0, str(REPO / "scripts"))

from pen_release import (  # noqa: E402
    SCOPE_LIST,
    latest_hook_apk,
    resolve_adb,
    scope_args,
    version_key,
)

HOOK_BUILD = REPO / "hook" / "tools" / "build_hook_source.py"
ROOT_BUILD = REPO / "module" / "tools" / "build_root.py"
MODULE_DIR = REPO / "module"
RELEASES = REPO / "releases"


def module_version() -> str:
    """Module version read from module/module.prop (single source of truth)."""
    for line in (MODULE_DIR / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("module/module.prop has no version= line")


def sync_module_version(new_ver: str) -> str:
    """Sync version in module/module.prop to match new_ver (e.g. from tag 'v0.1.2' -> '0.1.2')."""
    clean_ver = new_ver.lstrip("v").strip()
    if not clean_ver:
        return module_version()
    prop_file = MODULE_DIR / "module.prop"
    lines = prop_file.read_text(encoding="utf-8").splitlines()
    new_lines = []
    old_code = 0
    for line in lines:
        if line.startswith("versionCode="):
            try:
                old_code = int(line.split("=", 1)[1].strip())
            except ValueError:
                pass

    # Calculate candidate versionCode from semver: X.Y.Z -> X*10000 + Y*100 + Z
    new_code = old_code + 1
    parts = clean_ver.split(".")
    if len(parts) >= 2 and all(p.isdigit() for p in parts[:2]):
        patch = int(parts[2]) if len(parts) >= 3 and parts[2].isdigit() else 0
        calc_code = int(parts[0]) * 10000 + int(parts[1]) * 100 + patch
        new_code = max(old_code + 1, calc_code)

    for line in lines:
        if line.startswith("version="):
            new_lines.append(f"version={clean_ver}")
        elif line.startswith("versionCode="):
            new_lines.append(f"versionCode={new_code}")
        else:
            new_lines.append(line)

    prop_file.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
    print(f"  [module.prop] 同步版本: version={clean_ver} versionCode={new_code}")
    return clean_ver


def module_zip_path(ver: str | None = None) -> Path:
    return RELEASES / f"tb522fu-pen-bridge-v{ver or module_version()}.zip"


PKG = "com.futureharmony.lenovopenbridge"
VECTOR_CLI = "/data/adb/modules/zygisk_vector/cli"


def run(cmd: list[str], label: str, cwd: Path = REPO) -> None:
    print(f"\n{'=' * 60}")
    print(f"  {label}")
    print(f"{'=' * 60}")
    result = subprocess.run(cmd, cwd=str(cwd))
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


# --- device side ---------------------------------------------------------------


def adb_su(adb: str, remote_cmd: str) -> subprocess.CompletedProcess:
    """Run a command as root on the device. `exec-out` avoids CR mangling."""
    return subprocess.run(
        [adb, "exec-out", "su", "-c", remote_cmd],
        capture_output=True, text=True,
    )


def device_state(adb: str) -> str:
    """One of 'device' | 'multiple' | 'none'.

    'multiple' is the case that used to masquerade as 'none': with USB and WiFi
    both attached, a bare `adb get-state` fails with "more than one
    device/emulator" and the step-4 self-check was skipped without saying why.
    """
    try:
        proc = subprocess.run([adb, "get-state"], capture_output=True, text=True)
    except OSError:
        return "none"
    if proc.returncode == 0 and proc.stdout.strip() == "device":
        return "device"
    err = (proc.stderr or "") + (proc.stdout or "")
    if "more than one device" in err:
        return "multiple"
    return "none"


def device_online(adb: str) -> bool:
    return device_state(adb) == "device"


def read_runtime_scope(adb: str) -> set[str] | None:
    """Current Vector scope entries as `pkg/user`, or None if unreadable."""
    proc = adb_su(adb, f"{VECTOR_CLI} --json scope ls {PKG}")
    if proc.returncode != 0:
        return None
    start = proc.stdout.find("{")
    if start < 0:
        return None
    try:
        data = json.loads(proc.stdout[start:])
    except json.JSONDecodeError:
        return None
    return {f"{row['APP_PACKAGE']}/{row['USER_ID']}" for row in data.get("data", [])}


def module_status(adb: str) -> str | None:
    proc = adb_su(adb, f"{VECTOR_CLI} modules ls")
    if proc.returncode != 0:
        return None
    for raw in proc.stdout.splitlines():
        fields = raw.split()
        if len(fields) >= 3 and fields[0] == PKG:
            return fields[2]
    return None


def check_vector_scope(adb: str, repair: bool) -> bool:
    """Ensure the device scope equals the scope the APK is built with.

    Idempotent: uses surgical `scope add` / `scope rm` (not a whole-table
    `scope set`), so an unrelated entry can never be wiped by accident.

    Returns True when the device is in the desired state (after any repair).
    """
    want = set(scope_args())
    print(f"\n{'=' * 60}")
    print("  Vector 作用域自检")
    print(f"{'=' * 60}")
    print(f"  期望({len(want)}): {' '.join(sorted(want))}")

    have = read_runtime_scope(adb)
    if have is None:
        print("  [warn] 读不到运行时作用域（vector-cli 不可达或模块未安装）——跳过")
        return False

    missing = sorted(want - have)
    extra = sorted(have - want)
    print(f"  实际({len(have)}): {' '.join(sorted(have))}")

    if not missing and not extra:
        print("  ✓ 作用域与 scope.list 一致")
        return True

    for entry in missing:
        tag = "  ← system_server 永远不会被注入" if entry.startswith("system/") else ""
        print(f"  [缺] {entry}{tag}")
    for entry in extra:
        print(f"  [多] {entry}  (已废弃/历史残留)")

    if not repair:
        print("  [--no-scope-fix] 只报告不修复")
        return False

    if missing:
        cmd = f"{VECTOR_CLI} scope add {PKG} " + " ".join(missing)
        print(f"  -> {cmd}")
        proc = adb_su(adb, cmd)
        if proc.returncode != 0:
            print(f"  [error] scope add 失败: {proc.stderr.strip()}", file=sys.stderr)
            return False
    if extra:
        cmd = f"{VECTOR_CLI} scope rm {PKG} " + " ".join(extra)
        print(f"  -> {cmd}")
        adb_su(adb, cmd)  # best effort; extras are harmless, missing ones are not

    final = read_runtime_scope(adb)
    if final is None or not want.issubset(final):
        print("  [error] 修复后仍缺项，需人工检查 Vector", file=sys.stderr)
        print(f"          (源: {SCOPE_LIST})", file=sys.stderr)
        return False
    print("  ✓ 已补齐——注意：注入作用域只在进程启动时生效，需重启")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description="Build TB522FU Pen Bridge")
    parser.add_argument("--push", action="store_true",
                        help="Push artifacts to the device Download/ dir via scripts/push_to_device.sh")
    parser.add_argument("--skip-hook", action="store_true",
                        help="Skip Hook APK compilation (use existing)")
    parser.add_argument("--no-device", action="store_true",
                        help="Never touch a device (skip scope check + push)")
    parser.add_argument("--no-scope-fix", action="store_true",
                        help="Report Vector scope drift but do not repair it")
    parser.add_argument("--require-device", action="store_true",
                        help="Fail instead of skipping when no device is connected")
    parser.add_argument("--set-version", type=str, default=None,
                        help="Sync version in module/module.prop before packaging (e.g. 0.1.2 or v0.1.2)")
    args = parser.parse_args()

    # Step 0: Sync module version if requested or via env var
    target_ver = args.set_version or os.environ.get("MODULE_VERSION")
    if target_ver:
        sync_module_version(target_ver)

    cur_ver = module_version()
    module_zip = module_zip_path(cur_ver)

    # Step 1: Compile Hook APK
    if not args.skip_hook:
        run([sys.executable, str(HOOK_BUILD)], "编译 Hook APK")

    hook_apk = latest_hook_apk()
    print(f"\n  Hook APK: {hook_apk.name}  (v{'.'.join(map(str, version_key(hook_apk)))})")

    # Step 2: Package Root module zip
    run([sys.executable, str(ROOT_BUILD), str(MODULE_DIR), str(module_zip)],
        f"打包 Root 模块 ZIP (v{cur_ver})")

    # Step 3: Generate MD5 checksums
    print(f"\n{'=' * 60}")
    print("  生成 MD5 校验")
    print(f"{'=' * 60}")
    md5_hook = md5(hook_apk)
    md5_zip = md5(module_zip)
    write_md5(hook_apk)
    write_md5(module_zip)

    # The module zip embeds a copy of the Hook APK; make sure it is the fresh one
    # and not a pre-rename leftover picked by lexicographic sort.
    with zipfile.ZipFile(module_zip) as zf:
        embedded = hashlib.md5(zf.read("hook/PenBridge-Hook.apk")).hexdigest()
    if embedded != md5(hook_apk):
        raise SystemExit(f"{module_zip.name} embeds a DIFFERENT Hook APK "
                         f"({embedded} != {md5(hook_apk)})")
    print(f"  module zip 内嵌 Hook APK 与 {hook_apk.name} 一致 ✓")

    # Steps 4/5 need a device
    adb: str | None = None
    online = False
    if not args.no_device:
        try:
            adb = resolve_adb()
            online = device_online(adb)
        except SystemExit as exc:
            print(f"\n  [warn] {exc}")
        if not online:
            state = device_state(adb) if adb else "none"
            if state == "multiple":
                note = "多个设备同时在线（USB + WiFi）"
                print(f"\n  [warn] {note} —— 裸 adb 命令报 more than one device，"
                      f"已跳过 scope 自检与推送")
                print("         定向到 WiFi： eval \"$(scripts/adb_wifi.sh env)\"")
                print("         或显式指定：  export ANDROID_SERIAL=<ip>:5555")
            else:
                note = "no device connected"
                print(f"\n  [skip] {note} —— 跳过 scope 自检与推送")
            if args.require_device:
                raise SystemExit(f"{note}; --require-device was set")

    # Step 4: Vector scope self-check
    scope_ok = False
    if online and adb:
        status = module_status(adb)
        print(f"\n  Vector 模块状态: {status or 'unknown'}")
        if status != "enabled":
            print(f"  [warn] {PKG} 未启用 —— scope 修了也不会注入 "
                  f"({VECTOR_CLI} modules enable {PKG})")
        scope_ok = check_vector_scope(adb, repair=not args.no_scope_fix)

    # Step 5: Push to device (optional)
    # Delegate to scripts/push_to_device.sh instead of pushing here: that script
    # owns the destination (/sdcard/Download/tb522fu-pen-bridge/), pushes the
    # per-file .md5.txt sidecars and the PenHidCtl APK too. Keeping a second
    # push implementation here meant two destinations and no checksums.
    if args.push:
        if not (online and adb):
            print("\n  [skip] 无设备，无法推送")
        else:
            run(["bash", str(REPO / "scripts" / "push_to_device.sh")],
                "推送产物到设备 Download 目录")

    print(f"\n{'=' * 60}")
    print("  ✅ 构建完成")
    print(f"{'=' * 60}")
    print(f"  Hook APK  : {hook_apk}")
    print(f"  Module ZIP: {module_zip}")
    print(f"  MD5       : {md5_hook}, {md5_zip}")
    if online:
        print(f"  Vector scope: {'OK' if scope_ok else 'NEEDS ATTENTION'}")
        print("  部署: scripts/deploy_vector.sh   (装 APK + 重设 scope + 装模块 + 重启)")
    elif not args.push:
        print("\n  提示: --push 推送到设备；--require-device 让无设备时直接失败")


if __name__ == "__main__":
    main()
