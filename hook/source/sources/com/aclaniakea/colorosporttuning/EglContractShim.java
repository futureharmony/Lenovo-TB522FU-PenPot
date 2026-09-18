package com.aclaniakea.colorosporttuning;

/**
 * Loads the native EGL contract shim (libeglshim.so) exactly once, in-process,
 * inside com.coloros.note.
 *
 * The shim installs inline hooks on eglGetProcAddress / eglInitialize that make
 * this Adreno-830 port satisfy the EGL-layer contracts the bundled GLEW loader
 * (libSuniaEngine.so) relies on -- the same contracts the original OPPO/ColorOS
 * device satisfied. Because it fixes the boundary, not the engine's bytes, it
 * survives future HeyTap updates of the Notes app (16.8 / 17.x) instead of
 * needing a fresh per-build byte patch every time.
 *
 * Loaded from the earliest com.coloros.note hook entry so the contract is in
 * place before the handwriting engine calls glewInit() (which only happens when
 * a handwritten note is opened). A failure to load must never take Notes down:
 * we catch everything and let the app fall back to its stock (broken) behavior,
 * which the module's guard_note_engine.sh still covers as a last resort.
 */
final class EglContractShim {
    private static volatile boolean loaded = false;
    private static volatile boolean tried = false;

    private EglContractShim() {
    }

    static void ensureLoaded() {
        if (loaded || tried) {
            return;
        }
        synchronized (EglContractShim.class) {
            if (loaded || tried) {
                return;
            }
            tried = true;
            try {
                System.loadLibrary("eglshim");
                loaded = true;
                HookUtils.log("egl shim: libeglshim.so loaded");
            } catch (Throwable th) {
                // Native lib missing / hook install failed: degrading silently
                // is acceptable -- the per-build byte patch / engine guard
                // remains the fallback. Never throw into the Xposed callback.
                HookUtils.log("egl shim: load failed: " + th);
            }
        }
    }

    /** Test hook: reports whether the shim is currently resident. */
    static boolean isLoaded() {
        return loaded;
    }
}
