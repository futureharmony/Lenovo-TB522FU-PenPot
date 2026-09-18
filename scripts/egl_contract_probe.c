/*
 * egl_contract_probe.c — 验证 GLEW 崩溃的"契约假设"在移植 ROM 上是否成立
 *
 * 目的（对应 sunia_egl_compat_shim_design_20260918.md §5）：
 *   假设 A: eglGetProcAddress("glGetString") 应返回有效核心 GL 函数指针
 *           （ColorOS 原厂满足；本移植 Adreno 据测返回 NULL → GLEW 探测失败早退）
 *   假设 B: 已 eglInitialize 后再次 eglInitialize(dpy) 应返回 EGL_TRUE（幂等）
 *           （本机据测返回 !=1 → eglewInit 表填不上）
 *
 * 编译（需 NDK / 设备上 aarch64 工具链）：
 *   aarch64-linux-android21-clang egl_contract_probe.c -lEGL -lGLESv2 -o egl_contract_probe
 * 运行（device, adb push 后）：
 *   adb shell su -c '/data/local/tmp/egl_contract_probe'
 *
 * 判读：
 *   - A_OK=1 且 B_OK=1  → 移植 ROM 已满足 GLEW 契约（崩溃另有原因，需重查）
 *   - A_OK=0 或 B_OK=0  → 证实假设 A/B 被打破 → 路线 1 的 shim 方向正确
 */
#include <EGL/egl.h>
#include <GLES/gl.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    int A_ok = 0, B_ok = 0;
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj = 0, min = 0;
    EGLBoolean first = eglInitialize(dpy, &maj, &min);

    printf("[probe] eglGetDisplay   = %p\n", (void*)dpy);
    printf("[probe] eglInitialize#1 = %s (v%d.%d)\n",
           first == EGL_TRUE ? "OK" : "FAIL", (int)maj, (int)min);

    /* 假设 A：核心 GL 函数经 eglGetProcAddress 是否可取 */
    void *p_glGetString = (void*)eglGetProcAddress("glGetString");
    void *p_glGetError  = (void*)eglGetProcAddress("glGetError");
    printf("[probe] eglGetProcAddress(\"glGetString\") = %p\n", p_glGetString);
    printf("[probe] eglGetProcAddress(\"glGetError\")  = %p\n", p_glGetError);
    A_ok = (p_glGetString != NULL) ? 1 : 0;

    /* 对照：直接 dlsym 真实 libGLESv2 的核心函数（shim 应返回的源） */
    /* 注：设备上可用 dlopen("libGLESv2.so", RTLD_NOW) 验证，这里仅记录思路 */

    /* 假设 B：重复 eglInitialize 是否幂等 */
    EGLint maj2 = 0, min2 = 0;
    EGLBoolean second = eglInitialize(dpy, &maj2, &min2);
    printf("[probe] eglInitialize#2 = %s (v%d.%d)\n",
           second == EGL_TRUE ? "OK" : "FAIL", (int)maj2, (int)min2);
    B_ok = (second == EGL_TRUE) ? 1 : 0;

    /* 若 A 成立，GLEW 能拿到 glGetString 并探到版本 */
    if (A_ok && p_glGetString) {
        /* 需先 make current 一个 surface 才能调 GL；此处仅确认指针非空即可 */
        printf("[probe] glGetString ptr resolvable -> GLEW probe would SUCCEED\n");
    } else {
        printf("[probe] glGetString ptr NULL -> GLEW probe FAILS (crash root)\n");
    }

    eglTerminate(dpy);
    printf("[probe] RESULT A_OK=%d B_OK=%d  (both 1 == ColorOS-compatible)\n", A_ok, B_ok);
    return 0;
}
