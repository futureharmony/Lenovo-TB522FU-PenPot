#!/usr/bin/env python3
"""判定某个应用能否响应手写笔自定义动作 107-110（撤销/重做/上翻/下翻）。

背景（代码实证，hook/.../StylusActionDispatcher.java）：

    107 撤销   -> HandwrittenNoteOverlay 可见则走自有浮窗；否则注入 Ctrl+Z
    108 重做   -> 同上；否则注入 Ctrl+Shift+Z，25ms 后再 Ctrl+Y
    109 上翻   -> 注入触摸滑动 + KEYCODE_PAGE_UP
    110 下翻   -> 注入触摸滑动 + KEYCODE_PAGE_DOWN

    101-106 / 111-114 不走键盘，是本模块自己实现（广播 / 注入 BACK·HOME·APP_SWITCH /
    反射状态栏 / 自有浮窗），不在本工具的判定范围内。

由于 107-110 在应用侧只会表现为「一串合成按键/触摸事件」，本工具回答两个问题：

    Q1 这个应用会不会消费 Ctrl+Z / PAGE_UP·DOWN 这类快捷键？
    Q2 它的按键处理器挂在哪、由谁调用、什么条件下才会被触达？

三层扫描，逐层更硬：
    L1  字符串/方法引用扫描（默认）—— 快，只能证伪「完全没有接键代码」。
    L2  键码级反汇编（--deep，需 dexdump）—— 直接读出方法体里比较的键码字面量与
        修饰键判定，能给出「认不认 Ctrl+Z」的确定答案，并列出调用者与父类。
    L3  原厂笔通道探测（同时进行）—— 命中说明该应用另有原生笔语义，但**不承载
        107-110**（代码里 1-4 才走那条广播）。

用法：
    scripts/action_support_triage.py com.coloros.note
    scripts/action_support_triage.py --deep com.coloros.note com.android.chrome
    scripts/action_support_triage.py --local /tmp/foo.apk --deep
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pen_release import resolve_adb  # noqa: E402


# ---------------------------------------------------------------------------
# 标记集合
# ---------------------------------------------------------------------------
# 关键前提：KeyEvent.KEYCODE_Z 是 `static final int = 54`，编译期就被内联成字面量，
# dex 字符串表里**不会**留下 "KEYCODE_Z" 这个名字。所以按 KEYCODE_* 计数是无效证据。
# 可靠的是方法名/类描述符引用 —— 它们在 dex 里保留原文。
KB_MARKERS = {
    "onKeyDown": "View/Activity.onKeyDown 重写",
    "onKeyUp": "View/Activity.onKeyUp 重写",
    "onKeyLongPress": "长按键处理",
    "dispatchKeyEvent": "按键总入口重写",
    "dispatchKeyShortcutEvent": "快捷键入口重写",
    "onKeyShortcut": "菜单快捷键",
    "onProvideKeyboardShortcuts": "声明系统快捷键（帮助面板）",
    "isCtrlPressed": "显式判 Ctrl",
    "isShiftPressed": "显式判 Shift",
    "getMetaState": "读修饰键状态",
    "KeyCharacterMap": "键位映射表",
    "Landroid/view/KeyEvent;": "引用 KeyEvent 类型",
}

OEM_MARKERS = {
    "PENCIL_SINGLE_CLICK": "原厂笔点击广播 action",
    "triggerStylusClick": "原厂 Toolkit 笔点击入口",
    "oplus/ipemanager": "引用 IPeManager",
    "oplus/healthservice": "引用 healthservice（笔轮盘）",
}

FRAMEWORK_MARKERS = {
    "android/webkit/WebView": "WebView（编辑器可能在 Chromium 里）",
    "androidx/compose/": "Jetpack Compose 画布",
    "io/flutter/": "Flutter 引擎",
    "com/unity3d/": "Unity 引擎",
    "org/chromium/": "内嵌 Chromium",
    "android/view/inputmethod": "输入法框架",
}

# 深扫关注的方法名
KEY_METHODS = {"dispatchKeyEvent", "onKeyDown", "onKeyUp", "onKeyLongPress",
               "onKeyShortcut", "dispatchKeyShortcutEvent", "onProvideKeyboardShortcuts"}

# 关注的键码（用于过滤噪声常量）
WATCH_KEYS = {3, 4, 19, 20, 21, 22, 23, 53, 54, 57, 58, 59, 60, 66, 82, 92, 93,
              111, 112, 113, 114, 187}

KEYCODE_NAMES = {
    3: "HOME", 4: "BACK", 19: "DPAD_UP", 20: "DPAD_DOWN", 21: "DPAD_LEFT", 22: "DPAD_RIGHT",
    23: "DPAD_CENTER", 53: "Y", 54: "Z", 57: "ALT_LEFT", 58: "ALT_RIGHT",
    59: "SHIFT_LEFT", 60: "SHIFT_RIGHT", 66: "ENTER", 82: "MENU",
    92: "PAGE_UP", 93: "PAGE_DOWN", 111: "ESCAPE", 112: "FORWARD_DEL",
    113: "CTRL_LEFT", 114: "CTRL_RIGHT", 187: "APP_SWITCH",
}


def kc_name(code: int) -> str:
    if 29 <= code <= 54:
        return f"{chr(ord('A') + code - 29)}({code})"
    if 7 <= code <= 16:
        return f"{code - 7}({code})"
    return f"{KEYCODE_NAMES.get(code, 'KEYCODE_' + str(code))}({code})"


def short(x: str) -> str:
    """Lcom/foo/Bar; -> com.foo.Bar"""
    return x.replace("/", ".")[1:-1] if len(x) > 2 else x


def short_caller(ref: str) -> str:
    """Lcom/foo/Bar;.meth -> com.foo.Bar.meth（不能直接替换 . 再切，类名里也有点）"""
    i = ref.find(";.")
    return f"{short(ref[:i + 1])}.{ref[i + 2:]}" if i > 0 else ref


# 框架/androidx 自带的处理器多是「列表滚动 / 焦点移动」这类通用行为，
# 与应用自己的语义无关 —— 必须与「应用自有类」区分开，否则会被噪声淹没。
FW_OWNER_PREFIXES = ("Landroid/", "Landroidx/", "Lcom/google/android/", "Lkotlin/",
                     "Ljava/", "Ljavax/", "Ldalvik/", "Lcom/google/common/")


def is_framework_owner(owner: str) -> bool:
    return owner.startswith(FW_OWNER_PREFIXES)


# ---------------------------------------------------------------------------
# 设备交互
# ---------------------------------------------------------------------------
def sh(adb: str, serial: str | None, cmd: str, timeout: int = 120) -> tuple[int, bytes]:
    argv = [adb] + (["-s", serial] if serial else []) + ["shell", cmd]
    p = subprocess.run(argv, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout


def exec_out(adb: str, serial: str | None, cmd: str, timeout: int = 300) -> tuple[int, bytes]:
    """二进制安全通道：抽 dex 必须用它，`adb shell` 有 CRLF 翻译的历史包袱。"""
    argv = [adb] + (["-s", serial] if serial else []) + ["exec-out", cmd]
    p = subprocess.run(argv, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout


def apks_of(adb: str, serial: str | None, pkg: str) -> list[str]:
    _, out = sh(adb, serial, f"pm path {pkg}")
    return [ln.strip()[len("package:"):] for ln in out.decode("utf-8", "replace").splitlines()
            if ln.strip().startswith("package:")]


def collect_dex_remote(adb: str, serial: str | None, apk: str) -> list[tuple[str, bytes]]:
    """从设备 APK 里流式抽 classes*.dex，避免把几百 MB 的 APK 拉回本地。

    这台设备（ColorOS / Android 16）自带的是**精简版 unzip**：
        unzip [-d DIR] [-lnopqv] ZIP [FILE...] [-x FILE...]
    没有 `-Z`（列目录靠 `-l`），所以流程是「-l 解析成员名 → 逐个 -p 抽取」。
    """
    rc, out = sh(adb, serial, f"unzip -l '{apk}'")
    if rc != 0 or b"Length" not in out:
        rc, out = sh(adb, serial, f"su -c \"unzip -l '{apk}'\"")
    names = []
    for line in out.splitlines():
        m = re.match(rb"\s*\d+\s+\S+\s+\S+\s+(\S+)\s*$", line)
        if m and re.fullmatch(rb"classes\d*\.dex", m.group(1)):
            names.append(m.group(1).decode())
    if not names:
        return []  # 纯资源 split（如 split_config.zh.apk）本来就没有 dex

    chunks = []
    for name in sorted(names, key=lambda n: (len(n), n)):
        rc, data = exec_out(adb, serial, f"unzip -p '{apk}' {name}")
        if rc != 0 or not data.startswith(b"dex\n"):
            rc, data = exec_out(adb, serial, f"su -c \"unzip -p '{apk}' {name}\"")
        if not data or not data.startswith(b"dex\n"):
            raise RuntimeError(f"{apk}!{name}: 不是有效 dex（rc={rc}, {len(data)}B）")
        chunks.append((name, data))
    return chunks


def collect_dex_local(apk: Path) -> list[tuple[str, bytes]]:
    with zipfile.ZipFile(apk) as zf:
        names = sorted(n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        return [(n, zf.read(n)) for n in names]


def count_markers(blob: bytes, markers: dict[str, str]) -> dict[str, int]:
    return {name: blob.count(name.encode()) for name in markers}


# ---------------------------------------------------------------------------
# L2 · 键码级反汇编
# ---------------------------------------------------------------------------
def find_dexdump() -> str | None:
    if os.environ.get("DEXDUMP"):
        return os.environ["DEXDUMP"]
    cand: list[Path] = []
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 str(Path.home() / "Library/Android/sdk"), "/tmp/android-sdk"):
        if root:
            cand += list(Path(root).glob("build-tools/*/dexdump"))
    hit = [c for c in cand if c.is_file() and os.access(c, os.X_OK)]
    return str(sorted(hit, key=lambda p: p.parent.name)[-1]) if hit else None


CLS_RE = re.compile(r"Class descriptor\s*: '([^']+)'")
SUP_RE = re.compile(r"Superclass\s*: '([^']+)'")
NAME_RE = re.compile(r"      name          : '(\w+)'")
INVOKE_RE = re.compile(r"invoke-\w+(?:/range)?\s+\{[^}]*\},\s*(?:\[[0-9a-f]+\]\s*)?(L[^;]+;)\.(\w+):")
CONST_RE = re.compile(r"const(?:/16|/4|/high16)?\s+v\d+, #int (-?\d+)")


class Handler:
    def __init__(self, owner: str, sup: str, meth: str) -> None:
        self.owner, self.sup, self.meth = owner, sup, meth
        self.keys: set[int] = set()
        self.mods: set[str] = set()
        self.callers: list[str] = []

    @property
    def desc(self) -> str:
        return f"{self.owner}.{self.meth}"


def deep_scan(dexdump: str, dex_files: list[Path]) -> list[Handler]:
    """流式解析 dexdump -d 输出（不落盘，输出可达数百 MB）。"""
    handlers: list[Handler] = []
    callers: dict[str, list[str]] = {}
    for dex in dex_files:
        proc = subprocess.Popen([dexdump, "-d", str(dex)], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, errors="replace")
        assert proc.stdout is not None
        cls = sup = meth = None
        cur: Handler | None = None
        for line in proc.stdout:
            c = CLS_RE.search(line)
            if c:
                cls, sup, meth, cur = c.group(1), "?", None, None
                continue
            s = SUP_RE.search(line)
            if s and cls:
                sup = s.group(1)
                continue
            m = NAME_RE.match(line)
            if m:
                meth = m.group(1)
                if cls and meth in KEY_METHODS:
                    cur = Handler(cls, sup, meth)
                    handlers.append(cur)
                else:
                    cur = None
                continue
            if cur is not None:
                for k in CONST_RE.findall(line):
                    v = int(k)
                    if v in WATCH_KEYS:
                        cur.keys.add(v)
                if "isCtrlPressed" in line:
                    cur.mods.add("Ctrl")
                if "isShiftPressed" in line:
                    cur.mods.add("Shift")
                if "isAltPressed" in line:
                    cur.mods.add("Alt")
            inv = INVOKE_RE.search(line)
            if inv and inv.group(2) in KEY_METHODS and cls and meth:
                callers.setdefault(f"{inv.group(1)}.{inv.group(2)}", []).append(f"{cls}.{meth}")
        proc.stdout.close()
        proc.wait()

    for h in handlers:
        h.callers = sorted(set(callers.get(h.desc, [])))
    return handlers


def render_deep(handlers: list[Handler]) -> tuple[str, list[Handler] | None]:
    """只保留真正"对手写笔动作可能有反应"的处理器。"""
    relevant = [h for h in handlers if (h.keys & {54, 53, 92, 93}) or h.mods]

    L = [f"\n  [L2 键码级深扫] 共 {len(handlers)} 个按键处理方法，"
         f"其中 {len(relevant)} 个涉及 Z/Y/PAGE 键或修饰键判定："]
    if not relevant:
        L.append("      （无一比较过 Z / Y / PAGE_UP / PAGE_DOWN，也无 Ctrl/Shift 判定）")
    for h in relevant:
        keys = ", ".join(kc_name(k) for k in sorted(h.keys)) or "—"
        L.append(f"\n      {short(h.owner)}")
        L.append(f"          继承     : {short(h.sup)}")
        L.append(f"          方法     : {h.meth}()")
        L.append(f"          比较键码 : {keys}")
        L.append(f"          修饰键   : {', '.join(sorted(h.mods)) or '—'}")
        L.append(f"          调用者   : {', '.join(short_caller(c) for c in h.callers)}"
                 if h.callers else "          调用者   : （全 dex 无直接调用点）")
    return "\n".join(L), relevant


def render(pkg: str, blob: bytes, deep_out: str | None, relevant: list[Handler] | None) -> str:
    kb = count_markers(blob, KB_MARKERS)
    oem = count_markers(blob, OEM_MARKERS)
    fw = count_markers(blob, FRAMEWORK_MARKERS)

    kb_total, fw_total = sum(kb.values()), sum(fw.values())
    oem_hits = [k for k, v in oem.items() if v]
    if kb_total == 0 and fw_total == 0:
        label = "C 类 · 静态即可判定为「不会响应」"
        why = "dex 里没有任何按键处理方法引用，也没有托管编辑面 —— 没有代码能接住这串按键。"
    elif kb_total == 0 and fw_total > 0:
        label = "不可判 · 编辑面在框架/引擎里"
        why = ("应用自身不处理按键，但存在 WebView/Compose/Flutter/Unity 等托管面；"
               "键盘语义由框架原生代码实现，字符串扫描看不到 —— 必须做 L2 深扫或实机实测。")
    else:
        label = "待确认 · 有键盘处理面，但未必认 Ctrl+Z"
        why = (f"存在 {kb_total} 处按键处理引用，具备接住按键的代码；是否真认 Ctrl+Z/PAGE 键"
               "必须看 L2 深扫（字符串扫描看不到被内联的键码）。")
        if oem_hits:
            why += f"  另有原厂笔通道（{', '.join(oem_hits)}），但它只承载代码 1-4，不承载 107-110。"

    L = [f"\n{'=' * 78}",
         f"  {pkg}   （dex 合计 {len(blob) / 1048576:.1f} MB）",
         f"{'=' * 78}",
         f"  结论：{label}",
         f"  依据：{why}"]

    def section(title: str, hits: dict[str, int], meta: dict[str, str], tag: str) -> None:
        L.append(f"\n  [{tag}] {title}")
        if not hits:
            L.append("      （全部为 0）")
            return
        for k, v in sorted(hits.items(), key=lambda kv: -kv[1]):
            L.append(f"      {v:>6}  {k:<30} {meta[k]}")

    section("键盘处理面  —— 命中 = 有接住按键的代码", kb, KB_MARKERS, "L1 KB")
    section("原厂笔通道  —— 命中 = 另有原生笔语义（但不承载 107-110）", oem, OEM_MARKERS, "L1 OEM")
    section("托管编辑面  —— 命中 = 字符串扫描失效，须深扫/实测", fw, FRAMEWORK_MARKERS, "L1 FW")

    if deep_out:
        L.append(deep_out)

    if relevant is not None:
        L.append("\n  [最终裁决]")
        if not relevant:
            if fw_total > 0:
                L.append("      ⚠ dex 里没有任何处理器比较过 Z / Y / PAGE_UP / PAGE_DOWN ——")
                L.append(f"        但检测到托管编辑面（{fw_total} 处）。键码很可能由框架/引擎的原生代码")
                L.append("        处理（Blink / Compose runtime / Flutter engine），dex 扫描看不到")
                L.append("        ⇒ 静态不可判，必须实机实测。")
            else:
                L.append("      ✘ 没有任何按键处理器比较过 Z / Y / PAGE_UP / PAGE_DOWN，也没有 Ctrl/Shift")
                L.append("        判定，且无托管编辑面 ⇒ 107-110 在这个应用里必然无效。")
        else:
            app_h = [h for h in relevant if not is_framework_owner(h.owner)]
            fwk_h = [h for h in relevant if is_framework_owner(h.owner)]
            if not app_h:
                L.append(f"      ⚠ 命中 {len(fwk_h)} 个处理器，但**全部属于框架/androidx（非应用自有）**。")
                L.append("        这类通常是列表滚动 / 焦点移动等通用行为，不代表应用语义。")
                if fw_total > 0:
                    L.append(f"        同时存在托管编辑面（{fw_total} 处）⇒ 静态不可判，必须实机实测。")
            for h in app_h + fwk_h:
                tag = "" if not is_framework_owner(h.owner) else "  [框架类·可能是通用行为]"
                covers = []
                if 54 in h.keys:
                    covers.append("Ctrl+Z → 107 撤销")
                if 53 in h.keys:
                    covers.append("Ctrl+Y → 108 重做（Office 风格）")
                if 54 in h.keys and "Shift" in h.mods:
                    covers.append("Ctrl+Shift+Z → 108 重做（画布风格）")
                if 92 in h.keys:
                    covers.append("PAGE_UP → 109 上翻")
                if 93 in h.keys:
                    covers.append("PAGE_DOWN → 110 下翻")
                L.append(f"\n      ✔ {short(h.owner)}.{h.meth}(){tag}")
                L.append(f"          可覆盖：{', '.join(covers) if covers else '（只判修饰键，键码未落在 107-110 需要的键上）'}")
                if "Fragment" in h.sup:
                    L.append("          ⚠ 宿主是 Fragment —— 框架不会自动派发按键，必须由某个")
                    L.append("            Activity/View 手工转调，且只在该宿主界面内有效。")
                if not h.callers:
                    L.append("          ⚠ 全 dex 找不到调用点 —— 极可能是死代码 / 只由框架回调。")
                else:
                    L.append(f"          触达条件：仅当 {short_caller(h.callers[0])} 执行到转调分支时。")

    L.append("\n  [下一步] 实机验证（把同一串按键直接打进目标应用：先进可编辑界面）")
    for action, cmd, note in [("107 撤销", "keycombination 113 54", "Ctrl+Z"),
                              ("108 重做", "keycombination 113 59 54", "Ctrl+Shift+Z"),
                              ("108 重做", "keycombination 113 53", "Ctrl+Y"),
                              ("109 上翻", "keyevent 92", "PAGE_UP"),
                              ("110 下翻", "keyevent 93", "PAGE_DOWN")]:
        L.append(f"      {action:<9} adb shell input {cmd:<28} # {note}")
    L.append("      # 注意：本机 input 无 keydown/keyup 子命令；109/110 还额外做触摸滑动，")
    L.append("      #       input 无法复现那一半，只能靠手势触发。")
    return "\n".join(L)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pkgs", nargs="*", help="要判定的包名")
    ap.add_argument("--local", action="append", default=[], metavar="APK",
                    help="改为分析本地 APK（可重复）")
    ap.add_argument("-s", "--serial", help="adb 序列号（多设备时必填）")
    ap.add_argument("--adb", help="adb 可执行文件路径")
    ap.add_argument("--deep", action="store_true", help="启用 L2 键码级反汇编（需 dexdump）")
    ap.add_argument("--dexdump", help="dexdump 可执行文件路径")
    ap.add_argument("--keep-dex", action="store_true", help="保留抽取出的 dex（调试用）")
    args = ap.parse_args()

    if not args.pkgs and not args.local:
        ap.error("至少给一个包名，或用 --local 指定 APK")

    print("判定应用对 107-110（撤销/重做/上翻/下翻）的响应能力")

    dexdump = None
    if args.deep:
        dexdump = args.dexdump or find_dexdump()
        if not dexdump:
            print("!! --deep 需要 dexdump：用 --dexdump 指定，或设 $DEXDUMP，"
                  "或放在 $ANDROID_HOME/build-tools/*/dexdump")
            return 2
        print(f"L2 深扫使用 dexdump: {dexdump}")

    workdir = Path(tempfile.mkdtemp(prefix="pen-triage-"))

    def analyze(name: str, chunks: list[tuple[str, bytes]]) -> None:
        if not chunks:
            print(f"\n!! {name}: 没有 dex（纯资源包？）")
            return
        blob = b"".join(d for _, d in chunks)
        deep_out = None
        relevant = None
        if args.deep and dexdump:
            dex_files = []
            for i, (dexname, data) in enumerate(chunks):
                p = workdir / f"{i}_{dexname}"
                p.write_bytes(data)
                dex_files.append(p)
            relevant = deep_scan(dexdump, dex_files)
            deep_out, relevant = render_deep(relevant)
        print(render(name, blob, deep_out, relevant))

    try:
        for path in args.local:
            p = Path(path)
            try:
                analyze(p.name, collect_dex_local(p))
            except Exception as e:  # noqa: BLE001
                print(f"\n!! {p}: {e}")

        if args.pkgs:
            adb = resolve_adb(args.adb)
            for pkg in args.pkgs:
                try:
                    apks = apks_of(adb, args.serial, pkg)
                    if not apks:
                        print(f"\n!! {pkg}: 未安装")
                        continue
                    chunks: list[tuple[str, bytes]] = []
                    for a in apks:
                        chunks += collect_dex_remote(adb, args.serial, a)
                    analyze(pkg, chunks)
                except Exception as e:  # noqa: BLE001
                    print(f"\n!! {pkg}: {e}")
    finally:
        if not args.keep_dex:
            shutil.rmtree(workdir, ignore_errors=True)

    print(f"\n{'=' * 78}")
    print("  判定注意事项：")
    print("  1. 按 KEYCODE_* 字符串计数无效 —— 常量编译期内联，dex 里没有该名字。")
    print("     要拿真实键码必须走 L2（dexdump 反汇编），不能用 strings/grep 猜。")
    print("  2. 「有处理器」≠「会生效」：Handler 挂在 Fragment 上时框架不会自动派发，")
    print("     必须由某个 Activity/View 手工转调，且只在该宿主界面里有效。")
    print("  3. 「无处理器」≠「一定无效」：编辑面在 WebView/Compose/Flutter/Unity 时，")
    print("     键码由框架原生代码处理，dex 里永远是 0 命中（看 L1 FW 段）。")
    print("  4. 最硬的判据仍是进程内探针：打印事件是否送达 + dispatchKeyEvent 返回值。")
    print(f"{'=' * 78}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
