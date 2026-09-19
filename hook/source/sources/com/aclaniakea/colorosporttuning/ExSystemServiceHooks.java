package com.aclaniakea.colorosporttuning;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;

/**
 * Screenshot bridge for the pen gesture custom actions.
 *
 * <p>The OEM full-screen screenshot is not exported: it can only be driven
 * through {@code OplusLongshotUtils.getScreenshotManager(ctx).takeScreenshot(
 * bundle)} (decompiled from com.oplus.exsystemservice z7/a.java, the OEM pen
 * double-click path).  That class lives with the screenshot app's classes, so
 * we register a small receiver INSIDE the com.oplus.exsystemservice process
 * (already in the module scope) and system_server's gesture dispatch asks it
 * to take the shot via a broadcast.
 */
final class ExSystemServiceHooks {
    private static final String ACTION_TAKE_SCREENSHOT = "aclaniakea.penbridge.TAKE_SCREENSHOT";
    private static volatile boolean receiverInstalled = false;

    private ExSystemServiceHooks() { }

    static void install(final XC_LoadPackage.LoadPackageParam lpp) {
        HookUtils.hookAll(lpp.classLoader, "android.app.Application", "onCreate",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (receiverInstalled) return;
                        if (!(hook.thisObject instanceof Context)) return;
                        receiverInstalled = true;
                        installScreenshotReceiver((Context) hook.thisObject, lpp.classLoader);
                    }
                });
    }

    private static void installScreenshotReceiver(final Context context, final ClassLoader loader) {
        try {
            IntentFilter filter = new IntentFilter(ACTION_TAKE_SCREENSHOT);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    try {
                        Class<?> util = Class.forName(
                                "com.oplus.screenshot.OplusLongshotUtils", false, loader);
                        Object manager = util.getMethod("getScreenshotManager", Context.class)
                                .invoke(null, ctx);
                        Bundle bundle = new Bundle();
                        // Same shape the OEM pen double-click path passes.
                        bundle.putString("screenshot_source", "LenovoPenBridge");
                        manager.getClass()
                                .getMethod("takeScreenshot", Bundle.class)
                                .invoke(manager, bundle);
                        HookUtils.log("OEM screenshot triggered from bridge");
                    } catch (Throwable th) {
                        HookUtils.log("OEM screenshot: " + th);
                    }
                }
            }, filter, 2 /* Context.RECEIVER_EXPORTED; sender is system_server */);
            HookUtils.log("screenshot receiver installed in exsystemservice");
        } catch (Throwable th) {
            HookUtils.log("screenshot receiver install: " + th);
        }
    }
}
