# EGL contract shim (libeglshim.so)

**Purpose:** make this Adreno-830 ColorOS port satisfy the EGL-layer "contracts"
that the bundled GLEW loader inside `com.coloros.note`'s handwriting engine
(`libSuniaEngine.so`) relies on — the same contracts the original OPPO/ColorOS
device satisfied. Fix the boundary, not the engine bytes, so a future HeyTap
update of the Notes app cannot reintroduce the crash.

## What it patches (in-process, scoped to com.coloros.note only)

| EGL entry | Real behavior on this port | Shim behavior |
|---|---|---|
| `eglGetProcAddress(name)` | returns **NULL** for core GL functions (`glGetString`, `glGetStringi`, `glGetIntegerv`, …) because EGL only guarantees *extension* functions | pass-through; when NULL and `name` is a core GL function, fall back to `dlsym(libGLESv2.so, name)` which exports them → GLEW's `glewInit()` gets a valid `glGetString` and proceeds |
| `eglInitialize(dpy, …)` | returns `EGL_FALSE` on a *second* init of an already-initialized display (GLEW's `eglewInit` re-inits) | pass-through; if real call fails but `dpy` is valid and `EGL_VERSION` is queryable, return `EGL_TRUE` (idempotent) so the `__eglew*` dispatch table gets filled |

Blast radius: **only `com.coloros.note`**. Other processes keep the real libEGL.

## How it loads

`EglContractShim.ensureLoaded()` (Java) is called from the `com.coloros.note`
branch of `UiWorkingSetPrefetch.handleLoadPackage`, the earliest hook entry. It
does `System.loadLibrary("eglshim")` once. `JNI_OnLoad` installs the Dobby inline
hooks immediately — before the engine's `glewInit()` runs (that only happens when
a handwritten note is opened). A load failure is caught and logged; it never
takes Notes down. The module's `guard_note_engine.sh` stays as the last-resort
fallback for contracts this shim does not cover.

## Build (Android NDK — verified on NDK r30, 2026-09-18)

```sh
export ANDROID_NDK=$HOME/Library/Android/sdk/ndk/30.0.14904198  # or any r26+
bash hook/source/jni/build_egl_shim.sh
# -> writes hook/source/resources/lib/arm64-v8a/libeglshim.so (stripped, ~140 KB)
```

Then `hook/tools/build_hook_source.py` embeds it into the LSPosed APK
automatically. If the `.so` is absent, the build warns and skips it — the
per-build byte patch / engine guard remain the fallback.

### Build gotchas (already handled, do not "clean up")

- **Dobby is pinned to commit `67d155b`** (vendored under `jni/Dobby`). Master
  HEAD is broken twice: a circular include (`platform.h` <-> `common.h`) that
  leaves `OSMemory` undeclared, and `external/logging/logging.c` including
  `<android/log.h>` inside a function body (NDK >= r26 rejects that — log.h now
  has static inline functions). The vendored copy carries the logging fix; the
  build script re-applies it automatically only on a *fresh* clone.
- `DOBBY_GENERATE_SHARED` must stay `OFF` (set in `CMakeLists.txt`): the APK
  embeds only `libeglshim.so`, so Dobby is linked statically
  (`DobbyHook` must resolve inside the .so — verify with `llvm-nm -D`).
- The shim `dlopen`s `libEGL.so` itself before hooking: at `handleLoadPackage`
  time the app has not run yet and libEGL may not be resident, which would make
  `dlsym(RTLD_DEFAULT, "egl*")` silently return NULL.

## Honest boundaries

- Covers the **specific** ColorOS contracts GLEW uses here. If a future Notes
  version depends on a *different* missing system contract (new EGL extension,
  gralloc, `android.hardware.graphics` version, …), this shim won't cover it —
  that's why `guard_note_engine.sh` still warns instead of failing silently.
- Inline hooks must be installed before the engine initializes. Today the engine
  inits lazily on note-open, which is after app start, so we're safe. If a future
  version inits GLEW at app start (static), move the `ensureLoaded()` call to the
  very first `handleLoadPackage` line.
- Requires the Notes process to be injected by Vector (it is, via scope
  `com.coloros.note`). If the module is disabled, the shim is absent and the old
  behavior returns.
