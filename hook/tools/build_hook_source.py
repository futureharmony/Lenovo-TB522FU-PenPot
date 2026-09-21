#!/usr/bin/env python3
"""Build the pen-bridge LSPosed Hook APK from Java source (ACLaniakea 1.1.27).

The hook now compiles directly from pen-bridge/hook/source/sources so every
behavior change lives in source:
  javac (android.jar + Xposed stubs) -> d8 -> classes.dex
  aapt2 compile+link (source res + manifest) -> resources.arsc
  assemble (dex + native lib + xposed_init + scope.list) -> zipalign -> apksigner
"""

from __future__ import annotations

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "source" / "sources"
COMPILE_STUBS = ROOT / "source" / "stubs"
XPOSED_STUB_PATCHES = ROOT / "source" / "stub-patches"
RES = ROOT / "source" / "resources" / "res"
MANIFEST = ROOT / "source" / "resources" / "AndroidManifest.xml"
XPOSED_INIT = ROOT / "source" / "resources" / "assets" / "xposed_init"
SCOPE_LIST = ROOT / "source" / "resources" / "META-INF" / "xposed" / "scope.list"
PEN_SO = ROOT / "source" / "resources" / "lib" / "arm64-v8a" / "libpeninput.so"
# EGL contract shim (Dobby inline hook on eglGetProcAddress / eglInitialize).
# Restores the "ColorOS contract" the bundled GLEW loader expects, in-process,
# scoped to com.coloros.note. Optional: if the NDK was never run, the .so is
# absent and we simply skip embedding it (the per-build byte patch remains the
# fallback). See hook/source/jni/README.
SHIM_SO = ROOT / "source" / "resources" / "lib" / "arm64-v8a" / "libeglshim.so"

SDK = Path(os.environ.get("ANDROID_SDK") or os.environ.get("ANDROID_HOME") or "/tmp/android-sdk")


def _find_build_tools(sdk: Path) -> Path:
    env_bt = os.environ.get("ANDROID_BUILD_TOOLS")
    if env_bt and Path(env_bt).is_dir():
        return Path(env_bt)
    for cand_name in ("android-15", "android-14"):
        cand = sdk / "build-tools" / cand_name
        if cand.is_dir():
            return cand
    bt_dir = sdk / "build-tools"
    if bt_dir.is_dir():
        dirs = [d for d in bt_dir.iterdir() if d.is_dir()]
        if dirs:
            return sorted(dirs)[-1]
    return sdk / "build-tools" / "android-15"


BT = _find_build_tools(SDK)
AAPT2 = BT / "aapt2"
D8 = BT / "d8"
R8_JAR = Path(os.environ.get("ACL_R8_JAR", "/run/media/ACLaniakea/IXUNICS/pad/tools/dex/r8.jar"))
ZIPALIGN = BT / "zipalign"
APKSIGNER = BT / "apksigner"


def _android_jar(sdk: Path) -> Path:
    for cand in (sdk / "platforms" / "android-35" / "android.jar",
                 sdk / "platforms" / "android-35" / "android-35" / "android.jar"):
        if cand.is_file():
            return cand
    platforms = sdk / "platforms"
    if platforms.is_dir():
        cands = sorted(platforms.glob("android-*/android.jar"))
        if cands:
            return cands[-1]
    return sdk / "platforms" / "android-35" / "android.jar"


ANDROID_JAR = _android_jar(SDK)
_custom_stubs = os.environ.get("XPOSED_STUBS")
if _custom_stubs and Path(_custom_stubs).is_dir():
    STUBS = Path(_custom_stubs)
elif Path("/tmp/acdb/stubs").is_dir():
    STUBS = Path("/tmp/acdb/stubs")
else:
    STUBS = ROOT / "source" / "stubs"

# Repo-local defaults: the signing material lives in <repo>/keys (gitignored).
# The store password is read from <repo>/keys/tb522fu.pass so rebuilds do not
# depend on remembering it. Env vars still win when set.
REPO = ROOT.parents[0]  # hook/ -> repo root
KEYSTORE = Path(os.environ.get("ACL_KS", str(REPO / "keys" / "tb522fu.jks")))
ALIAS = os.environ.get("ACL_ALIAS", "tb522fu")


def _ks_pass() -> str:
    env = os.environ.get("ACL_KS_PASS")
    if env:
        return env
    pw_file = KEYSTORE.parent / "tb522fu.pass"
    if pw_file.is_file():
        return pw_file.read_text(encoding="utf-8").strip()
    return "changeit"


KS_PASS = _ks_pass()

OUT_DIR = Path(os.environ.get("ACL_OUT", str(REPO / "releases")))

# Single bump point. The APK file name, the aapt2 version-name and version-code
# all derive from this, so a release can never be half-renamed. Override with
# ACL_VERSION for a one-off build.
#   4.6.0 -> "PenBridge-Hook-tb522fu-v4.6.0.apk", version-name 4.6.0, code 460000
# 4.6.0: 107/108 reach the full-screen paint canvas (which answers no key event)
#        through the CanvasPaintHooks command bridge.
APK_VERSION = os.environ.get("ACL_VERSION", "4.7.0")
_MAJOR, _MINOR, _PATCH = (int(p) for p in APK_VERSION.split("."))
VERSION_CODE = f"{_MAJOR}{_MINOR}{_PATCH:04d}"
OUT_APK = OUT_DIR / f"PenBridge-Hook-tb522fu-v{APK_VERSION}.apk"


def run(cmd: list[str]) -> None:
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True)


def write_aligned_stored(dst: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    header_size = 30 + len(info.filename.encode("utf-8"))
    padding = (-(dst.fp.tell() + header_size)) % 4
    if padding:
        extra_size = padding + 4
        info.extra = struct.pack("<HH", 0xFFFF, extra_size - 4) + b"\0" * (extra_size - 4)
    dst.writestr(info, data)


def verify_version(apk: Path) -> None:
    """Assert the freshly signed APK actually carries APK_VERSION.

    A versionCode/versionName left in AndroidManifest.xml silently overrides the
    aapt2 flags, so a "bumped" build can ship with the previous version baked in
    (happened 2026-09-19: v4.5.5 file, versionName 4.5.4 inside). Never trust the
    file name.
    """
    proc = subprocess.run([str(AAPT2), "dump", "badging", str(apk)],
                          capture_output=True, text=True)
    first = (proc.stdout or "").splitlines()[0] if proc.stdout else ""
    if f"versionCode='{VERSION_CODE}'" not in first or f"versionName='{APK_VERSION}'" not in first:
        raise SystemExit(
            f"built APK reports the wrong version:\n  {first}\n"
            f"  expected versionCode={VERSION_CODE} versionName={APK_VERSION}\n"
            f"  -> AndroidManifest.xml must not declare android:versionCode/versionName"
        )
    print(f"version OK: versionName={APK_VERSION} versionCode={VERSION_CODE}")


def main() -> None:
    if not all(p.exists() for p in (AAPT2, D8, ZIPALIGN, APKSIGNER, ANDROID_JAR, KEYSTORE)):
        raise SystemExit(f"missing toolchain; checked {AAPT2}, {ANDROID_JAR}, {KEYSTORE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="penhook-src-") as td:
        tmp = Path(td)
        run([AAPT2, "compile", "--dir", RES, "-o", tmp / "res.zip"])
        run([AAPT2, "link", "-o", tmp / "base.apk", "-I", ANDROID_JAR,
             "--auto-add-overlay", "--manifest", MANIFEST, "-R", tmp / "res.zip",
             "--java", tmp / "gen", "--min-sdk-version", "31",
             "--target-sdk-version", "35",
             "--version-code", VERSION_CODE, "--version-name", APK_VERSION])
        (tmp / "classes").mkdir(parents=True, exist_ok=True)
        (tmp / "stub-classes").mkdir(parents=True, exist_ok=True)
        (tmp / "dex").mkdir(parents=True, exist_ok=True)
        # The historical pen build used a minimal Xposed API jar that lacks
        # MethodHookParam.method/getResult and XposedBridge.hookMethod.  These
        # declarations are compile-only and are filtered from classes.dex;
        # LSPosed remains the sole runtime implementation.
        run(["javac", "--release", "17", "-classpath", STUBS,
             "-d", tmp / "stub-classes"] +
            [str(p) for p in sorted(XPOSED_STUB_PATCHES.rglob("*.java"))])
        run(["javac", "--release", "17",
             "-classpath", f"{ANDROID_JAR}:{tmp / 'stub-classes'}:{STUBS}:{tmp / 'gen'}",
             "-d", tmp / "classes"] +
            [str(p) for p in sorted(SOURCES.rglob("*.java"))] +
            [str(p) for p in sorted(COMPILE_STUBS.rglob("*.java"))])
        d8_cmd = ([D8] if not R8_JAR.is_file()
                  else ["java", "-cp", R8_JAR, "com.android.tools.r8.D8"])
        # Xposed API and the UEventObserver stub are provided at runtime by
        # LSPosed / the framework. They must never be baked into classes.dex,
        # otherwise LSPosed refuses to load the module ("The Xposed API
        # classes are compiled into the module's APK").
        _provided_prefixes = ("android/os/UEventObserver", "de/robv/android/xposed/")
        _class_files = []
        for p in sorted((tmp / "classes").rglob("*.class")):
            rel = str(p.relative_to(tmp / "classes"))
            if any(rel.startswith(prefix) for prefix in _provided_prefixes):
                continue
            _class_files.append(str(p))
        run(d8_cmd + ["--lib", ANDROID_JAR, "--min-api", "31", "--output", tmp / "dex"] + _class_files)
        dex = tmp / "dex" / "classes.dex"
        unsigned = tmp / "unsigned.apk"
        with zipfile.ZipFile(tmp / "base.apk", "r") as src, \
             zipfile.ZipFile(unsigned, "w") as dst:
            for info in src.infolist():
                dst.writestr(info, src.read(info))
            write_aligned_stored(dst, "classes.dex", dex.read_bytes())
            if PEN_SO.is_file():
                write_aligned_stored(dst, "lib/arm64-v8a/libpeninput.so", PEN_SO.read_bytes())
            if SHIM_SO.is_file():
                write_aligned_stored(dst, "lib/arm64-v8a/libeglshim.so", SHIM_SO.read_bytes())
            else:
                print("[warn] libeglshim.so not found -- EGL contract shim NOT embedded. "
                      "Run hook/source/jni/build_egl_shim.sh (needs NDK). "
                      "The per-build byte patch / engine guard remain the fallback.")
            dst.writestr("assets/xposed_init", XPOSED_INIT.read_bytes(),
                         compress_type=zipfile.ZIP_DEFLATED)
            dst.writestr("META-INF/xposed/scope.list", SCOPE_LIST.read_bytes(),
                         compress_type=zipfile.ZIP_DEFLATED)
        aligned = tmp / "aligned.apk"
        run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])
        # apksigner 默认会额外产出 .idsig（APK Signature Scheme v4）。它只服务于
        # `adb install --incremental`，普通分发用不到，却会跟着产物目录被误传到 Release
        # （4.0.2 就误传过一个）。内容是哈希/证书/公钥/签名值，不含私钥，但没有用处
        # 就不该出现在发布页上，故显式关闭。
        run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-key-alias", ALIAS, "--v4-signing-enabled", "false",
             "--ks-pass", f"pass:{KS_PASS}", "--key-pass", f"pass:{KS_PASS}",
             "--out", OUT_APK, aligned])
    verify_version(OUT_APK)
    print(OUT_APK)


if __name__ == "__main__":
    main()
