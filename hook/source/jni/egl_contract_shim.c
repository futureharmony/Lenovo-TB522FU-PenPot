/*
 * egl_contract_shim.c  --  "make the low-level satisfy ColorOS's EGL contract"
 * ----------------------------------------------------------------------------
 * Problem: com.coloros.note's handwriting engine (libSuniaEngine.so) bundles a
 * desktop GLEW loader. On the original OPPO/ColorOS device two EGL-layer
 * contracts hold that DON'T hold on this Adreno-830 port:
 *
 *   (A) glewInit() probes GL via eglGetProcAddress("glGetString") and treats a
 *       NULL return as "no GL version" -> bails out (GLEW_ERROR_NO_GL_VERSION).
 *       On Android/Adreno, eglGetProcAddress only guarantees *extension*
 *       functions; core GL 1.x/3.x functions are not exposed through it and
 *       return NULL. They DO exist as real symbols inside libGLESv2.so.
 *
 *   (B) eglewInit() calls eglInitialize(dpy) a *second* time on an already
 *       initialized display. Some drivers return EGL_FALSE for a re-init, so
 *       the __eglew* dispatch table is never filled -> NULL deref crash.
 *
 * Fix (contract shim, NOT a per-build byte patch): install tiny inline hooks on
 * the two EGL entry points, in-process, scoped to com.coloros.note only.
 *
 *   * my_eglGetProcAddress(): pass through to the real one; when it returns
 *     NULL for what is clearly a core GL function, fall back to dlsym() against
 *     libGLESv2.so (which exports glGetString/glGetStringi/glGetIntegerv/...).
 *     This restores the "ColorOS contract" where the engine gets a valid
 *     glGetString and glewInit() proceeds.
 *
 *   * my_eglInitialize(): if the real call succeeds, return EGL_TRUE. If it
 *     fails but the display is valid and EGL_VERSION is queryable, treat it as
 *     already-initialized and return EGL_TRUE (idempotent init).
 *
 * Because we fix the *boundary* (libEGL.so symbols) rather than the engine's
 * own bytes, any future libSuniaEngine version (16.8 / 17.x) that routes
 * through these two calls is covered -- it no longer depends on which so HeyTap
 * pushed. Blast radius is limited to the one hooked process (com.coloros.note).
 *
 * Build: see CMakeLists.txt / build_egl_shim.sh (needs Android NDK). The
 * produced lib/arm64-v8a/libeglshim.so is embedded into the LSPosed APK by
 * hook/tools/build_hook_source.py and loaded from EglContractShim.java.
 */

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <string.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "dobby.h"

#define TAG "EglShim"
#define LOGV(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- real (trampoline) function pointers, filled by DobbyHook ---- */
static void *(*real_eglGetProcAddress)(const char *procname) = NULL;
static EGLBoolean (*real_eglInitialize)(EGLDisplay dpy, EGLint *major,
                                        EGLint *minor) = NULL;
static const char *(*real_eglQueryString)(EGLDisplay dpy, EGLint name) = NULL;

/* Cache the libGLESv2 handle once; core GL functions live there on Android. */
static void *g_glesv2 = NULL;
/* Handle for libEGL, dlopen'ed by us. At Xposed handleLoadPackage time the app
 * has not run any of its own code yet, so libEGL.so may not be mapped into the
 * process and dlsym(RTLD_DEFAULT, "egl*") would silently return NULL. Loading
 * it ourselves is harmless (it is a public lib the app will use anyway) and
 * makes hook installation deterministic regardless of app init order. */
static void *g_egl = NULL;
static int g_installed = 0;

static int is_core_gl_name(const char *name) {
    /* Core GL functions never start with "egl"/"EGL"/"AEGL" or "vk". */
    if (name == NULL)
        return 0;
    if (name[0] == 'e' && name[1] == 'g' && name[2] == 'l')
        return 0; /* egl* / EGL* */
    if (name[0] == 'A' && name[1] == 'E' && name[2] == 'G' && name[3] == 'L')
        return 0; /* AEGL* */
    if (name[0] == 'v' && name[1] == 'k')
        return 0; /* vk* */
    return 1;     /* everything else is a GL function */
}

static void *my_eglGetProcAddress(const char *procname) {
    void *p = real_eglGetProcAddress(procname);
    if (p != NULL)
        return p;
    /* Real call returned NULL. On Android that is normal for core GL
     * functions exposed only as static libGLESv2 symbols. Fall back. */
    if (is_core_gl_name(procname) && g_glesv2 != NULL) {
        void *g = dlsym(g_glesv2, procname);
        if (g != NULL) {
            LOGV("contract: eglGetProcAddress(\"%s\") -> GLESv2 fallback %p",
                 procname, g);
            return g;
        }
    }
    return NULL;
}

static EGLBoolean my_eglInitialize(EGLDisplay dpy, EGLint *major,
                                   EGLint *minor) {
    EGLBoolean r = real_eglInitialize(dpy, major, minor);
    if (r == EGL_TRUE)
        return EGL_TRUE;
    /* eglewInit re-initializes an already-initialized display. If the display
     * is valid and reports a VERSION, the driver is up -> report success so
     * GLEW's dispatch table actually gets filled. */
    if (dpy != EGL_NO_DISPLAY && real_eglQueryString != NULL) {
        const char *ver = real_eglQueryString(dpy, EGL_VERSION);
        if (ver != NULL) {
            if (major)
                *major = 1;
            if (minor)
                *minor = 5; /* surfaceless EGL 1.5 observed on this port */
            LOGV("contract: eglInitialize re-init on live dpy -> EGL_TRUE");
            return EGL_TRUE;
        }
    }
    return r;
}

/* Resolve a symbol preferring our own dlopen'ed libEGL handle, falling back to
 * the default namespace (covers builds where libEGL is already resident). */
static void *egl_sym(const char *name) {
    void *p = NULL;
    if (g_egl != NULL)
        p = dlsym(g_egl, name);
    if (p == NULL)
        p = dlsym(RTLD_DEFAULT, name);
    return p;
}

static void install_hooks(void) {
    void *addr;

    if (g_installed)
        return;
    g_installed = 1;

    /* Ensure libEGL is resident before resolving/hooking. Without this, a
     * call from the very first handleLoadPackage (before the app has touched
     * any GL) would find no eglGetProcAddress at all and install nothing. */
    g_egl = dlopen("libEGL.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_egl == NULL)
        LOGE("contract: dlopen libEGL failed: %s", dlerror());
    else
        LOGV("contract: libEGL handle %p", g_egl);

    g_glesv2 = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    if (g_glesv2 == NULL)
        LOGE("contract: dlopen libGLESv2 failed: %s", dlerror());
    else
        LOGV("contract: libGLESv2 handle %p", g_glesv2);

    addr = egl_sym("eglGetProcAddress");
    if (addr && DobbyHook(addr, (void *)my_eglGetProcAddress,
                          (void **)&real_eglGetProcAddress) == 0)
        LOGV("contract: hooked eglGetProcAddress @ %p", addr);
    else
        LOGE("contract: hook eglGetProcAddress failed @ %p", addr);

    addr = egl_sym("eglInitialize");
    if (addr && DobbyHook(addr, (void *)my_eglInitialize,
                          (void **)&real_eglInitialize) == 0)
        LOGV("contract: hooked eglInitialize @ %p", addr);
    else
        LOGE("contract: hook eglInitialize failed @ %p", addr);

    /* eglQueryString is only called by our wrapper; resolve the real one. */
    real_eglQueryString =
        (const char *(*)(EGLDisplay, EGLint))egl_sym("eglQueryString");
}

/* JNI_OnLoad runs once, inside com.coloros.note, when EglContractShim.java
 * calls System.loadLibrary("eglshim"). It executes before the engine's
 * glewInit() (which only runs when a handwritten note is opened), so the
 * contract is in place in time. */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    LOGV("contract: loading EGL contract shim (Dobby)");
    install_hooks();
    return JNI_VERSION_1_6;
}
