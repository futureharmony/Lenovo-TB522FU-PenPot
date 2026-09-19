package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Put com.coloros.translate back onto its Chinese endpoints.
 *
 * <p>What breaks without this: the app builds its "toolbox" host from a region
 * code produced by this chain
 *
 * <pre>
 *   b2.a.k()  -> SystemProperties "persist.sys.oplus.region" (country code)
 *             -> utils.x.b()      (assets/country_region_mapping.json)
 *             -> b2.a.l()         (switch cn/us/in/eu, DEFAULT = "sg")
 * </pre>
 *
 * On this port the middle step yields nothing usable, so every request lands on
 * the default branch and the host becomes
 * <c>https://aitool-cuiocr-sg.heytapmobi.com/aiendpoints/...</c>. That name no
 * longer resolves anywhere (NXDOMAIN from both the device and a plain Mac DNS
 * query), the photo-translate window never gets its answer and stays black.
 *
 * <p>Re-pointing the dead name at the CN server via hosts/DNS does NOT work:
 * the gateway dispatches on the HTTP Host header, so the same POST answered 404
 * as <c>aitool-cuiocr-sg</c> and 200 as <c>aitool-cuiocr-cn</c>. The hostname
 * itself has to change, hence the hooks below.
 *
 * <p>Two levels, deliberately: the region decision is pinned to "cn" (so the app
 * picks its own CN template) and anything that still goes on the wire with a
 * <c>-sg.heytapmobi.com</c> host gets rewritten, which also covers builder
 * methods an app update may rename.
 *
 * <p>Scoped to com.coloros.translate only. Every target is resolved lazily and
 * failures are swallowed: the app is obfuscated per release, so a missing target
 * must not take the rest of the module down inside that process.
 */
final class TranslateRegionHooks {

    private static final String TAG = "LenovoPenBridge";

    private TranslateRegionHooks() { }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. the region normaliser: everything that is not cn/us/in/eu already
        //    falls through to "sg" in there, so just answer "cn".
        hookArea(lpparam);

        // 2. the aiunit SDK builds its own host from the region string.
        hookDomainBuilder(lpparam);

        // 3. safety net on whatever finally reaches OkHttp.
        hookUrls(lpparam);

        // 4. diagnostics: their own debug log is gated behind h1.c() <= 3, and
        //    on release builds it is higher, so the photo-translate flow is
        //    silent (that is what made the black page so hard to read). With
        //    Settings.Global lenovo_pen_translate_debug=1 we turn it on.
        hookLogLevel(lpparam);

        // 5. bitmap loading fallback: ensure TranslatePhotoResultActivity always
        //    receives a valid bitmap even if URI permission fails.
        hookImageUtils(lpparam);
    }

    private static void hookLogLevel(XC_LoadPackage.LoadPackageParam lpparam) {
        hookOne(lpparam, "com.coloros.translate.utils.h1", "c", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (debugEnabled()) param.setResult(Integer.valueOf(2)); // <= 3 unlocks d()
            }
        });
    }

    /** Settings.Global lenovo_pen_translate_debug, cached for 10s. */
    private static boolean debugEnabled() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (debugChecked != 0 && now - debugChecked < 10000) return debugOn;
        boolean on = false;
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (at instanceof android.content.Context) {
                on = android.provider.Settings.Global.getInt(
                        ((android.content.Context) at).getContentResolver(),
                        "lenovo_pen_translate_debug", 0) == 1;
            }
        } catch (Throwable th) {
            Log.d(TAG, "TranslateRegionHooks: debug flag lookup: " + th);
        }
        debugOn = on;
        debugChecked = now;
        return on;
    }

    private static volatile boolean debugOn;
    private static volatile long debugChecked;

    // ---- targets ---------------------------------------------------------

    private static void hookArea(XC_LoadPackage.LoadPackageParam lpparam) {
        hookOne(lpparam, "b2.a", "l", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Log.d(TAG, "TranslateRegionHooks: region pinned to cn");
                param.setResult("cn");
            }
        }, String.class, boolean.class);
    }

    private static void hookDomainBuilder(XC_LoadPackage.LoadPackageParam lpparam) {
        hookOne(lpparam, "com.oplus.aiunit.translation.utils.DomainBuilder",
                "getToolboxHostNameByRegion", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0) {
                    param.args[0] = "cn";
                }
            }
        }, String.class);
    }

    private static void hookUrls(XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook rewrite = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                if (arg instanceof String) {
                    param.args[0] = cn((String) arg);
                } else if (arg != null && "okhttp3.HttpUrl".equals(arg.getClass().getName())) {
                    String s = arg.toString();
                    String fixed = cn(s);
                    if (!fixed.equals(s)) {
                        Object url = parseHttpUrl(arg, fixed);
                        if (url != null) param.args[0] = url;
                    }
                }
            }
        };
        hookOne(lpparam, "okhttp3.Request$Builder", "url", rewrite, String.class);
        Class<?> httpUrl = tryClass(lpparam, "okhttp3.HttpUrl");
        if (httpUrl != null) {
            hookOne(lpparam, "okhttp3.Request$Builder", "url", rewrite, httpUrl);
        }
    }

    private static void hookImageUtils(XC_LoadPackage.LoadPackageParam lpparam) {
        hookOne(lpparam, "com.coloros.translate.photo.utils.v", "d", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() != null && !param.hasThrowable()) {
                    return;
                }
                Context ctx = param.args != null && param.args.length > 0
                        && param.args[0] instanceof Context ? (Context) param.args[0] : null;
                Uri uri = param.args != null && param.args.length > 1
                        && param.args[1] instanceof Uri ? (Uri) param.args[1] : null;
                Log.w(TAG, "TranslateRegionHooks: ImageUtils#d returned null/failed for " + uri + ", attempting fallback");
                Bitmap fallback = null;
                if (ctx instanceof Activity) {
                    Activity act = (Activity) ctx;
                    Intent intent = act.getIntent();
                    if (intent != null) {
                        ClipData cd = intent.getClipData();
                        if (cd != null && cd.getItemCount() > 0) {
                            Intent inner = cd.getItemAt(0).getIntent();
                            if (inner != null) {
                                Object obj = inner.getParcelableExtra("preview_bitmap");
                                if (obj instanceof Bitmap) {
                                    fallback = (Bitmap) obj;
                                }
                            }
                        }
                    }
                    if (fallback == null) {
                        try {
                            java.lang.reflect.Field f = act.getClass().getDeclaredField("f6996u");
                            f.setAccessible(true);
                            Object obj = f.get(act);
                            if (obj instanceof Bitmap) {
                                fallback = (Bitmap) obj;
                            }
                        } catch (Throwable ignored) { }
                    }
                }
                if (fallback != null) {
                    Log.i(TAG, "TranslateRegionHooks: recovered fallback bitmap "
                            + fallback.getWidth() + "x" + fallback.getHeight());
                    param.setResult(fallback);
                }
            }
        }, Context.class, Uri.class);
    }

    // ---- plumbing --------------------------------------------------------

    /** Rewrite "-sg." to "-cn."; the dead overseas-hosted names are all sg-based. */
    private static String cn(String url) {
        if (url == null || url.indexOf("-sg.") < 0) return url;
        String fixed = url.replace("-sg.", "-cn.");
        Log.d(TAG, "TranslateRegionHooks: url rewrite " + url + " -> " + fixed);
        return fixed;
    }

    private static Object parseHttpUrl(Object sample, String value) {
        try {
            return sample.getClass().getClassLoader()
                    .loadClass("okhttp3.HttpUrl")
                    .getMethod("parse", String.class).invoke(null, value);
        } catch (Throwable th) {
            Log.d(TAG, "TranslateRegionHooks: HttpUrl rewrite skipped: " + th);
            return null;
        }
    }

    private static Class<?> tryClass(XC_LoadPackage.LoadPackageParam lpparam, String name) {
        try {
            return Class.forName(name, false, lpparam.classLoader);
        } catch (Throwable th) {
            return null;
        }
    }

    private static void hookOne(XC_LoadPackage.LoadPackageParam lpparam, String cls,
            String method, XC_MethodHook hook, Class<?>... params) {
        try {
            Class<?> c = Class.forName(cls, false, lpparam.classLoader);
            Method m = c.getDeclaredMethod(method, params);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
            Log.d(TAG, "TranslateRegionHooks: hooked " + cls + "#" + method);
        } catch (Throwable th) {
            Log.d(TAG, "TranslateRegionHooks: skip " + cls + "#" + method + ": " + th);
        }
    }
}
