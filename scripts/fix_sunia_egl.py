#!/usr/bin/env python3
"""
fix_sunia_egl.py — 修补 com.coloros.note 的 libSuniaEngine.so (16.7.2) 的 EGL/glew 初始化崩溃

背景
----
16.7.2 的 libSuniaEngine.so 在 EglContext3::eglInit() 里走：
    makeTempEglContext() -> glewInit() -> clearTempEglContext()
    -> __eglewGetDisplay (通过 GOT 表 dispatch)
makeTempEglContext 已经 eglGetDisplay+eglInitialize+eglChooseConfig+eglCreateContext+eglMakeCurrent
成功（display 已初始化、GL 上下文已 current）。但 glew 是桌面 GL 加载器，它的 GL 版本探测
用 eglGetProcAddress("glGetString") —— 在 Android/Adreno 上核心 GL 函数不通过该接口暴露，
返回 NULL -> _glewInit 报 GLEW_ERROR_NO_GL_VERSION -> glewInit 提前 return -> eglewInit 不执行
-> 整张 __eglew* EGL 分发表保持 NULL -> 第一个 EGL 调用 (blr x8, x8=NULL) 直接 SIGSEGV。

与此同时 eglewInit 内部又会二次调用 eglInitialize(dpy)，本机 Adreno 对“已初始化 display 的二次
eglInitialize”返回 !=1（实测确认），所以即便强制 glewInit 走到 eglewInit，表仍填不上。
因此必须两点同时改。

补丁（对 16.7.2 原版 so，md5 42fb5fd53e04368ca42d255579075d96）
----
PATCH 1 @ file off 0x824bd8 (va 0x828bd8, glewInit 内的 cbz w0,0x828be4):
    原: 60 00 00 34   (cbz w0, #0xc  -> 早退 ret)
    新: 03 00 00 14   (b   #0xc      -> 无条件进 eglewInit)
PATCH 2 @ file off 0x8197b0 (va 0x81d7b0, eglewInit 内的 b.ne 0x81d7f4):
    原: 21 02 00 54   (b.ne 错误返回)
    新: 01 00 00 14   (b    #0x4      -> 忽略冗余 eglInitialize 失败，继续填 EGL 表)

效果：glewInit 始终调用 eglewInit；eglewInit 忽略二次 eglInitialize 的 !=1，把整张 __eglew*
EGL 分发表填满。引擎用 GLES（libGLESv2）直接渲染，桌面 GL 表(__glew*)空不影响。
保持 16.7.2 引擎 <-> 16.7.2 app 的匹配接口（避免 16.6.14 引擎换入后笔迹数据结构不匹配、
笔画不渲染的问题）。

用法
----
    python3 fix_sunia_egl.py <input.so> <output.so>
校验原始字节，命中才打补丁；输出打补丁后 md5。再用 apply 脚本/adb 推到设备替换。
"""
import sys, hashlib

PATCHES = [
    # (file_offset, original_bytes_hex, new_bytes_hex, label)
    (0x824BD8, "60000034", "03000014", "glewInit: cbz->b (force eglewInit)"),
    (0x8197B0, "21020054", "01000014", "eglewInit: b.ne->b (ignore re-init)"),
]

EXPECTED_MD5 = "42fb5fd53e04368ca42d255579075d96"  # 16.7.2 原版


def md5(b: bytes) -> str:
    return hashlib.md5(b).hexdigest()


def main():
    if len(sys.argv) != 3:
        print("usage: python3 fix_sunia_egl.py <input.so> <output.so>")
        sys.exit(2)
    inp, outp = sys.argv[1], sys.argv[2]
    data = bytearray(open(inp, "rb").read())
    print(f"[*] input  = {inp}  ({len(data)} bytes, md5={md5(data)})")
    if md5(data) != EXPECTED_MD5:
        print(f"[!] 警告：输入 so 的 md5 ({md5(data)}) 不是预期的 16.7.2 原版 "
              f"({EXPECTED_MD5})。补丁偏移是针对 16.7.2 原版，打在其他版本上可能错位。")
        ans = input("     仍要继续? [y/N] ").strip().lower()
        if ans != "y":
            print("[x] 已取消")
            sys.exit(1)

    for off, orig_hex, new_hex, label in PATCHES:
        orig = bytes.fromhex(orig_hex)
        new = bytes.fromhex(new_hex)
        cur = bytes(data[off:off + len(orig)])
        if cur != orig:
            print(f"[x] 补丁点未命中 @0x{off:x} ({label})：期望 {orig_hex}，实际 "
                  f"{cur.hex()}。so 版本/基址不匹配，停止。")
            sys.exit(1)
        data[off:off + len(new)] = new
        print(f"[+] 已打补丁 @0x{off:x} ({label})")

    open(outp, "wb").write(data)
    print(f"[*] output = {outp}  ({len(data)} bytes, md5={md5(data)})")
    print("[*] 完成。推到设备替换 libSuniaEngine.so 并保持 owner/system:system + "
          "apk_data_file 上下文，重启便签验证涂鸦。")


if __name__ == "__main__":
    main()
