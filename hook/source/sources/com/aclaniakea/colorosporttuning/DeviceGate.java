package com.aclaniakea.colorosporttuning;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/* loaded from: classes.dex */
final class DeviceGate {
    private static Boolean sCachedResult = null;

    private DeviceGate() {
    }

    static boolean isModuleDisabled() {
        try {
            String dis = property("persist.lenovo.penbridge.disabled");
            if ("1".equals(dis) || "true".equalsIgnoreCase(dis)) {
                return true;
            }
            if (new File("/data/adb/modules/tb522fu_pen_bridge/disable").exists()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean supported() {
        if (isModuleDisabled()) {
            return false;
        }
        if (sCachedResult != null) {
            return sCachedResult.booleanValue();
        }

        // 1. Explicit user / developer override flag
        String force = property("persist.lenovo.penbridge.force_enable");
        if ("1".equals(force) || "true".equalsIgnoreCase(force)) {
            sCachedResult = Boolean.TRUE;
            return true;
        }
        String forceLegacy = property("persist.penbridge.enable");
        if ("1".equals(forceLegacy) || "true".equalsIgnoreCase(forceLegacy)) {
            sCachedResult = Boolean.TRUE;
            return true;
        }

        // 2. Hardware / SoC platforms known for ported tablets:
        //    - SM8750 / SM8750P (sun) -> TB522FU, Y700 Gen 3
        //    - SM8650 / SM8650Q (pineapple) -> TB710FU, Pad Pro 12.7 2024/2025
        //    - SM8550 (kalama) -> Pad Pro 12.7 2023, Y700 Gen 2
        //    - SM8475 / SM8450 (cape/taro) -> Y700 Gen 1, Pad Pro 2022
        //    - MT6989 / MT6985 / MT6895 (Dimensity tablet series)
        String soc = property("ro.soc.model").toUpperCase();
        String platform = property("ro.board.platform").toUpperCase();

        boolean knownSoC = soc.contains("SM8750") || soc.contains("SM8650")
                || soc.contains("SM8550") || soc.contains("SM8475") || soc.contains("SM8450")
                || soc.contains("MT6989") || soc.contains("MT6985")
                || platform.contains("SUN") || platform.contains("PINEAPPLE")
                || platform.contains("KALAMA") || platform.contains("TARO") || platform.contains("CAPE");

        // 3. Lenovo vendor heritage detection on ported system:
        //    Ported ColorOS retains Lenovo vendor/boot/odm properties
        boolean isLenovoPort = !property("ro.boot.lenovo.gsn").isEmpty()
                || !property("ro.boot.lenovo.psn").isEmpty()
                || !property("ro.odm.lenovo.psn").isEmpty()
                || !property("ro.vendor.lenovo.backcolor").isEmpty()
                || property("ro.product.model_for_attestation").toUpperCase().startsWith("TB")
                || property("ro.vendor.config.lgsi.ota.model").toUpperCase().startsWith("TB")
                || property("ro.product.model").toUpperCase().startsWith("TB")
                || property("ro.product.device").toUpperCase().startsWith("TB")
                || property("ro.product.brand").equalsIgnoreCase("lenovo")
                || property("ro.product.manufacturer").equalsIgnoreCase("lenovo");

        // 4. Input device check: presence of Lenovo stylus device or event node
        boolean hasLenovoPenNode = hasPenInputDevice();

        boolean result = (knownSoC && isLenovoPort) || hasLenovoPenNode || isLenovoPort;
        sCachedResult = Boolean.valueOf(result);
        return result;
    }

    private static boolean hasPenInputDevice() {
        try {
            File inputDir = new File("/sys/class/input");
            if (inputDir.exists() && inputDir.isDirectory()) {
                File[] list = inputDir.listFiles();
                if (list != null) {
                    for (File f : list) {
                        File nameFile = new File(f, "name");
                        if (nameFile.exists()) {
                            String name = readFirstLine(nameFile);
                            if (name != null) {
                                String lower = name.toLowerCase();
                                if (lower.contains("lenovo") && lower.contains("pen")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String readFirstLine(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String property(String str) {
        try {
            return String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, str, ""));
        } catch (Throwable unused) {
            return "";
        }
    }
}
