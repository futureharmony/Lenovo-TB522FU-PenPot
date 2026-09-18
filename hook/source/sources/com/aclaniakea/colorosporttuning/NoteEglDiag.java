package com.aclaniakea.colorosporttuning;

import android.content.ContentResolver;
import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;

/**
 * One-shot, opt-in in-process EGL diagnostic for the Notes handwriting crash.
 *
 * WHY THIS EXISTS
 * ---------------
 * com.coloros.note dies while opening a handwriting note. Nine tombstones agree
 * on one signature: pc=0 from a `blr x8` inside
 *   EglContext3::eglInit()+76  <- EglContext3(bool) <- ToolFactory()
 *   <- GroupFactoryManager::createFactory  <- CanvasManager::CanvasThreadHandlerFunc
 * The x8 that is null is the GLEW EGL entry `__eglewGetDisplay`. GLEW leaves it
 * null because `eglewContextInit()` never ran: `glewInit()` bails out with
 * GLEW_ERROR_NO_GL_VERSION ("Missing GL version") whenever the calling thread
 * has no *current* GL context, and `eglewInit(dpy)` in turn returns early when
 * `eglInitialize(dpy)` fails (i.e. dpy == EGL_NO_DISPLAY). The engine's own
 * `EglInit::makeTempEglContext()` discards the return values of
 * `eglCreateContext` / `eglMakeCurrent`, so a failure there is silent and the
 * very next call dereferences the null entry point.
 *
 * The same call sequence succeeds in a plain `app_process` shell on this exact
 * device (surfaceless EGL 1.5, Adreno 830), which proves the *hardware* path is
 * fine and the failure is process-local. This probe re-runs that sequence
 * inside the real Notes process so the two hypotheses can be separated:
 *
 *   probe succeeds in-process  -> the engine's own ordering/threading is at
 *                                 fault (swap app or patch the .so)
 *   probe fails in-process     -> something in this process breaks EGL (bundled
 *                                 libEGL shadowing the system one, a stuck
 *                                 current context, ...) and we keep digging
 *
 * The probe is deliberately tiny but records the environment as well: which
 * libEGL / libGLESv2 / GLEW libraries are actually mapped, and what lives in the
 * app's own nativeLibraryDir. A bundled libEGL.so is the classic smoking gun.
 *
 * SWITCH (off by default; nothing runs unless it is turned on)
 * ----------------------------------------------------------
 *   su -c 'settings put global lenovo_penbridge_egl_diag 1'
 *   ... launch Notes, open a handwritten note ...
 *   su -c 'cat /data/local/tmp/note_egl_diag.txt'      (primary)
 *   su -c 'cat /data/data/com.coloros.note/files/note_egl_diag.txt'  (mirror)
 *   su -c 'settings put global lenovo_penbridge_egl_diag 0'   # turn it back off
 *
 * `persist.lenovo.penbridge.egl_diag=1` is accepted as an equivalent switch, for
 * setups where the settings provider is awkward.
 *
 * The probe never blocks the UI for long (EGL bring-up is a few ms), never calls
 * eglTerminate, and runs each phase only once per process.
 */
final class NoteEglDiag {
    private static final String TAG = "NoteEglDiag";

    /** Primary sink requested by the operator; only writable if pre-created 0666. */
    private static final String TMP_PATH = "/data/local/tmp/note_egl_diag.txt";
    /** Always-writable mirror inside the app sandbox (pull it with `su -c cat`). */
    private static final String MIRROR_NAME = "note_egl_diag.txt";

    private static final String SETTINGS_KEY = "lenovo_penbridge_egl_diag";
    private static final String PROP_KEY = "persist.lenovo.penbridge.egl_diag";

    /** The only package we care about; avoids perturbing com.oplus.screenshot. */
    private static final String TARGET_PKG = "com.coloros.note";

    /** /proc/self/maps lines worth keeping (lowercased substring match). */
    private static final String[] MAP_KEYS = {
            "libegl", "libglesv2", "libglesv1_cm", "libglew", "libsuniaengine",
            "libnativewindow", "libui.so", "libadreno", "libvulkan",
    };

    private static final HashSet<String> DONE = new HashSet<>();

    private static volatile int switchState = -1; // -1 unknown, 0 off, 1 on

    private NoteEglDiag() {
    }

    /**
     * Runs the probe once per (process, phase). Safe to call from any hook; it
     * returns immediately when the switch is off.
     */
    static void runOnce(Context context, String phase) {
        if (context == null || phase == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        if (!enabled(app)) {
            return;
        }
        synchronized (DONE) {
            if (!DONE.add(phase)) {
                return;
            }
        }
        try {
            String body = collect(app, phase);
            write(app, body);
        } catch (Throwable th) {
            // Never let the diagnostic take the app down; log and move on.
            HookUtils.log("egl diag failed: " + th);
        }
    }

    /** Cheap, cached switch read. Off unless explicitly enabled. */
    private static boolean enabled(Context app) {
        int cached = switchState;
        if (cached >= 0) {
            return cached == 1;
        }
        int state = 0;
        try {
            String pkg = app.getPackageName();
            if (!TARGET_PKG.equals(pkg)) {
                switchState = 0;
                return false;
            }
            ContentResolver cr = app.getContentResolver();
            if (cr != null && Settings.Global.getInt(cr, SETTINGS_KEY, 0) == 1) {
                state = 1;
            }
            if (state == 0) {
                state = "1".equals(systemProperty(PROP_KEY, "0")) ? 1 : 0;
            }
        } catch (Throwable th) {
            HookUtils.log("egl diag switch: " + th);
            state = 0;
        }
        switchState = state;
        return state == 1;
    }

    /** Reads a build property without depending on the hidden API being public. */
    private static String systemProperty(String key, String def) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class, String.class);
            Object out = get.invoke(null, key, def);
            return out == null ? def : out.toString();
        } catch (Throwable th) {
            return def;
        }
    }

    // ---------------------------------------------------------------- collect

    private static String collect(Context app, String phase) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("==================== note_egl_diag ====================\n");
        sb.append("phase      = ").append(phase).append('\n');
        sb.append("time       = ").append(nowIso()).append('\n');
        sb.append("pid/uid/tid= ").append(Process.myPid()).append('/')
                .append(Process.myUid()).append('/').append(Process.myTid()).append('\n');
        sb.append("pkg        = ").append(app.getPackageName()).append('\n');
        sb.append("proc name  = ").append(procName()).append('\n');
        sb.append("sdk/rel    = ").append(Build.VERSION.SDK_INT).append(' ')
                .append(Build.VERSION.RELEASE).append('\n');
        sb.append("fingerprint= ").append(Build.FINGERPRINT).append('\n');
        sb.append("abi/arch   = ").append(Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?")
                .append(" / ").append(System.getProperty("os.arch")).append('\n');
        try {
            sb.append("dataDir    = ").append(app.getApplicationInfo().dataDir).append('\n');
            sb.append("nativeLib  = ").append(app.getApplicationInfo().nativeLibraryDir).append('\n');
        } catch (Throwable th) {
            sb.append("appInfo    = <").append(th).append(">\n");
        }
        sb.append("java.library.path = ").append(System.getProperty("java.library.path")).append('\n');

        sb.append("--- already-current EGL (before probe) ---\n");
        try {
            sb.append("eglGetCurrentDisplay -> ")
                    .append(EGL14.eglGetCurrentDisplay() == EGL14.EGL_NO_DISPLAY
                            ? "EGL_NO_DISPLAY" : "ok").append('\n');
            sb.append("eglGetCurrentContext -> ")
                    .append(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT
                            ? "EGL_NO_CONTEXT" : "ok").append('\n');
            sb.append("eglGetCurrentSurface(DRAW) -> ")
                    .append(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == EGL14.EGL_NO_SURFACE
                            ? "EGL_NO_SURFACE" : "ok").append('\n');
            sb.append("eglGetError -> ").append(eglErr()).append('\n');
        } catch (Throwable th) {
            sb.append("current-egl query failed: ").append(th).append('\n');
        }

        sb.append("--- nativeLibraryDir listing ---\n");
        try {
            String dir = app.getApplicationInfo().nativeLibraryDir;
            File[] fs = dir == null ? null : new File(dir).listFiles();
            if (fs == null) {
                sb.append("(unreadable or empty)\n");
            } else {
                for (File f : fs) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("egl") || n.contains("gles") || n.contains("glew")
                            || n.contains("sunia") || n.contains("vulkan")) {
                        sb.append("  ").append(f.getName()).append(' ')
                                .append(f.length()).append(" bytes\n");
                    }
                }
                sb.append("  (total entries: ").append(fs.length).append(")\n");
            }
        } catch (Throwable th) {
            sb.append("listing failed: ").append(th).append('\n');
        }

        sb.append("--- /proc/self/maps (graphics libs) ---\n");
        sb.append(scanMaps());

        // Run the sequence twice: on the hook's own thread (typically main) and
        // on a throwaway worker. The crash happens on a non-main dispatcher
        // thread, so a main-only pass would not answer the question.
        sb.append("--- probe: caller thread ---\n");
        sb.append(probe("caller"));

        sb.append("--- probe: spawned worker thread ---\n");
        sb.append(probeOnWorker());

        sb.append("==================== end ====================\n\n");
        return sb.toString();
    }

    private static String probeOnWorker() {
        final String[] out = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                out[0] = probe("worker");
            }
        }, "EglDiag-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join(5000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return out[0] == null ? "worker: <timed out / did not finish>\n" : out[0];
    }

    /**
     * Replicates the engine's bring-up order: get display, initialise, choose a
     * config, create an ES3 context, make it current with no surface, then read
     * GL_VERSION -- the very call glewContextInit() makes and bails out on.
     */
    private static String probe(String tag) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("thread=").append(tag)
                .append(" tid=").append(Process.myTid())
                .append(" name=").append(Thread.currentThread().getName()).append('\n');

        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            boolean haveDpy = dpy != EGL14.EGL_NO_DISPLAY && dpy != null;
            sb.append("  eglGetDisplay(DEFAULT)      -> ")
                    .append(haveDpy ? "ok" : "EGL_NO_DISPLAY")
                    .append("   err=").append(eglErr()).append('\n');
            if (!haveDpy) {
                sb.append("  ABORT: no display\n");
                return sb.toString();
            }

            int[] maj = new int[1];
            int[] min = new int[1];
            boolean init = EGL14.eglInitialize(dpy, maj, 0, min, 0);
            sb.append("  eglInitialize               -> ").append(init)
                    .append(" v").append(maj[0]).append('.').append(min[0])
                    .append("   err=").append(eglErr()).append('\n');
            sb.append("  eglQueryString(VENDOR)      -> ")
                    .append(EGL14.eglQueryString(dpy, EGL14.EGL_VENDOR)).append('\n');
            sb.append("  eglQueryString(VERSION)     -> ")
                    .append(EGL14.eglQueryString(dpy, EGL14.EGL_VERSION)).append('\n');
            String ext = EGL14.eglQueryString(dpy, EGL14.EGL_EXTENSIONS);
            sb.append("  EGL_KHR_surfaceless_context -> ")
                    .append(ext != null && ext.contains("EGL_KHR_surfaceless_context")).append('\n');
            if (!init) {
                sb.append("  ABORT: eglInitialize failed (this is the eglewInit guard)\n");
                return sb.toString();
            }

            int[] cfgAttr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
            };
            EGLConfig[] cfgs = new EGLConfig[4];
            int[] ncfg = new int[1];
            boolean chosen = EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, cfgs.length, ncfg, 0);
            sb.append("  eglChooseConfig             -> ").append(chosen)
                    .append(" n=").append(ncfg[0])
                    .append("   err=").append(eglErr()).append('\n');
            if (!chosen || ncfg[0] < 1) {
                sb.append("  ABORT: no config\n");
                return sb.toString();
            }

            int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            boolean haveCtx = ctx != null && ctx != EGL14.EGL_NO_CONTEXT;
            sb.append("  eglCreateContext(ES3)       -> ")
                    .append(haveCtx ? "ok" : "EGL_NO_CONTEXT")
                    .append("   err=").append(eglErr()).append('\n');
            if (!haveCtx) {
                ctxAttr[1] = 2;
                ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                haveCtx = ctx != null && ctx != EGL14.EGL_NO_CONTEXT;
                sb.append("  eglCreateContext(ES2)       -> ")
                        .append(haveCtx ? "ok" : "EGL_NO_CONTEXT")
                        .append("   err=").append(eglErr()).append('\n');
                if (!haveCtx) {
                    sb.append("  ABORT: no context\n");
                    return sb.toString();
                }
            }

            boolean made = EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, ctx);
            sb.append("  eglMakeCurrent(surfaceless) -> ").append(made)
                    .append("   err=").append(eglErr()).append('\n');
            if (!made) {
                sb.append("  ABORT: makeCurrent failed (engine discards this result)\n");
                return sb.toString();
            }

            sb.append("  glGetString(GL_VERSION)     -> ").append(GLES20.glGetString(GLES20.GL_VERSION)).append('\n');
            sb.append("  glGetString(GL_VENDOR)      -> ").append(GLES20.glGetString(GLES20.GL_VENDOR)).append('\n');
            sb.append("  glGetString(GL_RENDERER)    -> ").append(GLES20.glGetString(GLES20.GL_RENDERER)).append('\n');
            sb.append("  glGetError                  -> 0x")
                    .append(Integer.toHexString(GLES20.glGetError())).append('\n');
            sb.append("  RESULT: EGL OK on this thread -- a failure elsewhere is not environmental\n");

            // Tear down only what we created; leave the display alive for the app.
            try {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroyContext(dpy, ctx);
            } catch (Throwable ignored) {
            }
        } catch (Throwable th) {
            // An UnsatisfiedLinkError here would itself be the answer.
            sb.append("  EXCEPTION: ").append(th).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ sinks

    private static void write(Context app, String body) {
        boolean mirrorOk = false;
        try {
            File mirror = new File(app.getFilesDir(), MIRROR_NAME);
            appendTo(mirror, body);
            mirrorOk = true;
        } catch (Throwable th) {
            HookUtils.log("egl diag mirror write failed: " + th);
        }

        boolean tmpOk = false;
        String tmpErr = null;
        try {
            appendTo(new File(TMP_PATH), body);
            tmpOk = true;
        } catch (Throwable th) {
            tmpErr = String.valueOf(th);
        }

        HookUtils.log("egl diag phase written: tmp=" + tmpOk + " mirror=" + mirrorOk
                + (tmpErr == null ? "" : (" tmpErr=" + tmpErr)));

        if (!tmpOk && mirrorOk) {
            // Record why the requested path is unavailable, next to the data.
            try {
                appendTo(new File(app.getFilesDir(), MIRROR_NAME),
                        "[note] " + TMP_PATH + " not writable: " + tmpErr
                                + " (pre-create it 0666 from root, or read this mirror)\n");
            } catch (Throwable ignored) {
            }
        }
    }

    private static void appendTo(File f, String body) throws Exception {
        FileWriter w = null;
        try {
            w = new FileWriter(f, true);
            w.write(body);
            w.flush();
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String scanMaps() {
        BufferedReader r = null;
        StringBuilder sb = new StringBuilder(512);
        try {
            r = new BufferedReader(new FileReader("/proc/self/maps"), 8192);
            String line;
            int shown = 0;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase(Locale.ROOT);
                for (String key : MAP_KEYS) {
                    if (low.contains(key)) {
                        sb.append("  ").append(line).append('\n');
                        shown++;
                        break;
                    }
                }
                if (shown > 60) {
                    sb.append("  ... (truncated)\n");
                    break;
                }
            }
            if (shown == 0) {
                sb.append("  (no graphics libraries mapped yet)\n");
            }
        } catch (Throwable th) {
            sb.append("  maps read failed: ").append(th).append('\n');
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    private static String procName() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/comm"));
            String s = r.readLine();
            return s == null ? "?" : s.trim();
        } catch (Throwable th) {
            return "?";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String eglErr() {
        try {
            int e = EGL14.eglGetError();
            return "0x" + Integer.toHexString(e) + "(EGL_SUCCESS=" + (e == EGL14.EGL_SUCCESS) + ")";
        } catch (Throwable th) {
            return "n/a";
        }
    }

    private static String nowIso() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new java.util.Date());
    }
}
