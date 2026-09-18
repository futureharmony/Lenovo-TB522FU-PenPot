package com.aclaniakea.colorosporttuning;

/* loaded from: classes.dex */
final class DeviceGate {
    private DeviceGate() {
    }

    static boolean supported() {
        // TB522FU port: sun / SM8750P (Snapdragon 8 Elite). Original gate was
        // SM8650Q / pineapple (TB710FU). Keep both during transition if needed.
        return property("ro.soc.model").toUpperCase().contains("SM8750P") && property("ro.board.platform").toUpperCase().contains("SUN");
    }

    private static String property(String str) {
        try {
            return String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, str, ""));
        } catch (Throwable unused) {
            return "";
        }
    }
}
