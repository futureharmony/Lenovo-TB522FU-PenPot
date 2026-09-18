package com.aclaniakea.colorosporttuning;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
public final class UiWorkingSetPrefetch implements IXposedHookLoadPackage {
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.log("handleLoadPackage pkg=" + (loadPackageParam == null ? "<null>"
                : loadPackageParam.packageName) + " proc=" + (loadPackageParam == null ? "<null>"
                : loadPackageParam.processName));
        if (!DeviceGate.supported() || loadPackageParam == null || loadPackageParam.packageName == null) {
            // Diagnostic: the entry point runs but bails out. Silent before, which
            // made a wrong DeviceGate (or a missing scope) look like "no hook".
            // Also confirms which packages the framework actually injects into.
            HookUtils.log("skip " + (loadPackageParam == null ? "<null>" : loadPackageParam.packageName)
                    + " (gate=" + DeviceGate.supported() + ")");
            return;
        }
        String str = loadPackageParam.packageName;
        str.hashCode();
        switch (str) {
            case "com.oplus.healthservice":
            case "com.oplus.exsystemservice":
                HookUtils.log("broadcast/Binder target active: " + loadPackageParam.packageName);
                break;
            case "android":
                // Deferred/async: installing hooks makes the framework
                // deoptimise the targets (ART SuspendAll). Doing that from this
                // callback can deadlock against system_server's own binder JNI
                // calls and hang the boot, so it is handed to a worker thread
                // with a short delay. See SystemStylusHooks.installAsync.
                SystemStylusHooks.installAsync(loadPackageParam);
                break;
            case "com.oplus.ipemanager":
                IpeManagerHooks.install(loadPackageParam);
                break;
            case "com.oplus.wirelesssettings":
                WirelessSettingsHooks.install(loadPackageParam);
                break;
            case "com.coloros.note":
                // Restore the EGL-layer "ColorOS contract" the bundled GLEW
                // loader expects (eglGetProcAddress must hand back core GL
                // functions; eglInitialize must be idempotent). Loaded once,
                // in-process, before the handwriting engine's glewInit() runs.
                // See hook/source/jni/egl_contract_shim.c. Scoped to this
                // package only -- zero blast radius to the rest of the system.
                EglContractShim.ensureLoaded();
                NoteToolkitHooks.install(loadPackageParam);
                break;
            case "com.oplus.screenshot":
                NoteToolkitHooks.install(loadPackageParam);
                break;
            case "com.heytap.mydevices":
                MyDevicesHooks.install(loadPackageParam);
                break;
        }
    }
}
