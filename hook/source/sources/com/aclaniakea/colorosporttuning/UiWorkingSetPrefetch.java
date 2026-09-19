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
        final String pkg = loadPackageParam.packageName;
        ContractProbe.executeGuarded("pkg_hook_" + pkg,
                new ContractProbe.PrimaryAction<Void>() {
                    @Override public Void execute() {
                        dispatchPackageHook(loadPackageParam, pkg);
                        return null;
                    }
                },
                new ContractProbe.FallbackAction<Void>() {
                    @Override public Void execute() {
                        HookUtils.log("UiWorkingSetPrefetch: Fallback activated for package " + pkg);
                        return null;
                    }
                });
    }

    private void dispatchPackageHook(XC_LoadPackage.LoadPackageParam loadPackageParam, String pkg) {
        switch (pkg) {
            case "com.oplus.healthservice":
            case "com.oplus.exsystemservice":
                if ("com.oplus.exsystemservice".equals(loadPackageParam.packageName)) {
                    ExSystemServiceHooks.install(loadPackageParam);
                }
                HookUtils.log("broadcast/Binder target active: " + loadPackageParam.packageName);
                break;
            case "android":
                SystemStylusHooks.installAsync(loadPackageParam);
                break;
            case "com.oplus.ipemanager":
                IpeManagerHooks.install(loadPackageParam);
                break;
            case "com.oplus.wirelesssettings":
                WirelessSettingsHooks.install(loadPackageParam);
                break;
            case "com.coloros.note":
                EglContractShim.ensureLoaded();
                NoteToolkitHooks.install(loadPackageParam);
                break;
            case "com.oplus.screenshot":
                NoteToolkitHooks.install(loadPackageParam);
                break;
            case "com.heytap.mydevices":
                MyDevicesHooks.install(loadPackageParam);
                break;
            case "com.coloros.translate":
                TranslateRegionHooks.install(loadPackageParam);
                break;
        }
    }
}
