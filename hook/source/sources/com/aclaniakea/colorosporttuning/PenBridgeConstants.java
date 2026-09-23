package com.aclaniakea.colorosporttuning;

/* loaded from: classes.dex */
final class PenBridgeConstants {
    static final String BATTERY = "com.oplus.ipemanager.action.BATTERY_NOTIFY";
    static final String BONDED = "com.oplus.ipemanager.action.PENCIL_BONDED_WHEN_BOOT";
    static final String BUTTON = "com.oplus.ipemanager.action.STYLUS_BUTTON_STATE_CHANGED";
    /* Full-screen paint canvas undo/redo. system_server -> com.coloros.note;
       see CanvasPaintHooks for why the key-injection path cannot serve the
       canvas and why these two never race with it. No _LEGACY twin: sender and
       receiver ship in the same APK, so there is no mixed-version window. */
    static final String CANVAS_UNDO = "com.futureharmony.lenovopenbridge.action.CANVAS_UNDO";
    static final String CANVAS_REDO = "com.futureharmony.lenovopenbridge.action.CANVAS_REDO";
    static final String COLOROS_HANDOFF = "com.futureharmony.lenovopenbridge.action.COLOROS_PEN_STATE";
    static final String COLOROS_HANDOFF_LEGACY = "com.aclaniakea.lenovopenbridge.action.COLOROS_PEN_STATE";
    static final String DISCONNECT = "com.oplus.ipemanager.action.DISCONNECT_PENCIL";
    static final String DISCONNECT_REQUESTED = "lenovo_pen_disconnect_requested";
    static final String USER_DISCONNECT_REQUESTED = "lenovo_pen_user_disconnect_requested";
    static final String DOUBLE = "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK";
    static final String HAPTIC_COMMAND = "com.futureharmony.lenovopenbridge.haptic.COMMAND";
    static final String HAPTIC_COMMAND_LEGACY = "com.aclaniakea.lenovopenbridge.haptic.COMMAND";
    static final String HAPTIC_TOUCHSCREEN = "com.futureharmony.lenovopenbridge.haptic.TOUCHSCREEN";
    static final String HAPTIC_TOUCHSCREEN_LEGACY = "com.aclaniakea.lenovopenbridge.haptic.TOUCHSCREEN";
    static final String HARDWARE_BATTERY_LAST_AT = "lenovo_pen_hardware_battery_last_at";
    static final String HARDWARE_BATTERY_VALID = "lenovo_pen_hardware_battery_valid";
    static final String LAST_VALID_BATTERY = "lenovo_pen_last_valid_battery";
    static final int LENOVO_VENDOR = 6127;
    static final String LINK_CONNECTED = "lenovo_pen_link_connected";
    static final String OEM_CHARGE_RAW = "lenovo_pen_oem_charge_raw";
    static final String OEM_CHARGE_STATE = "lenovo_pen_oem_charge_state";
    static final String OEM_CHARGE_VALID = "lenovo_pen_oem_charge_valid";
    static final String OEM_CONTROL_READY = "lenovo_pen_oem_control_ready";
    static final String OEM_PEN_CONTROL = "com.futureharmony.lenovopenbridge.action.OEM_PEN_CONTROL";
    static final String OEM_PEN_CONTROL_LEGACY = "com.aclaniakea.lenovopenbridge.action.OEM_PEN_CONTROL";
    static final String OPPO_BOOT_RECOVERY_DEVICE_TYPE = "pencil_boot_recovery";
    static final String OPPO_OAF_DEVICE_FOUND = "com.oplus.ipemanager.ACTION.BROADCAST.OAF_DEVICE_FOUND";
    static final String PHYSICAL_DOCKED = "lenovo_pen_physical_docked";
    static final String RECONNECT = "com.futureharmony.lenovopenbridge.action.RECONNECT_PEN";
    static final String RECONNECT_LEGACY = "com.aclaniakea.lenovopenbridge.action.RECONNECT_PEN";
    static final String REFRESH_ACTIVE = "lenovo_pen_refresh_active";
    static final String SHOW_CAPSULE = "com.futureharmony.lenovopenbridge.action.SHOW_PENCIL_CAPSULE";
    static final String SHOW_CAPSULE_LEGACY = "com.aclaniakea.lenovopenbridge.action.SHOW_PENCIL_CAPSULE";
    static final String DISMISS_CAPSULE = "com.futureharmony.lenovopenbridge.action.DISMISS_PENCIL_CAPSULE";
    static final String DISMISS_CAPSULE_LEGACY = "com.aclaniakea.lenovopenbridge.action.DISMISS_PENCIL_CAPSULE";
    static final String WRITE_GESTURE_KEY = "com.futureharmony.lenovopenbridge.WRITE_GESTURE_KEY";
    static final String WRITE_GESTURE_KEY_LEGACY = "com.aclaniakea.lenovopenbridge.WRITE_GESTURE_KEY";
    /* Per-app page-turn trigger strategy. ipemanager (device-center panel) sends this
       to ask system_server to persist a choice (system uid write survives reboot; an
       app-process Settings.Global write does not, see TODO 1018). system_server also
       writes directly when the runtime prompt resolves.

       Extras:
         "pkg"      (String)  target package -- required.
         "strategy" (int)     <0 = clear; used when "op" is absent.
         "op"       (String)  optional action:
                                "calibrate"   -> pop the full-screen swipe-calibration
                                                 overlay (overlay windows need the system
                                                 uid, so the panel cannot do it itself);
                                "clear_calib" -> drop the recorded trajectory.
         "dir"      (String)  "next" / "prev" for the calibration ops. */
    static final String PAGETURN_CONFIG = "com.futureharmony.lenovopenbridge.PAGETURN_CONFIG";
    static final String RUN_ACTION = "com.futureharmony.lenovopenbridge.RUN_ACTION";
    static final String RUN_ACTION_LEGACY = "com.aclaniakea.lenovopenbridge.RUN_ACTION";
    static final String HAPTIC_TRANSPORT = "com.futureharmony.lenovopenbridge.action.HAPTIC_TRANSPORT";
    static final String HAPTIC_TRANSPORT_LEGACY = "com.aclaniakea.lenovopenbridge.action.HAPTIC_TRANSPORT";
    static final String SINGLE = "com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK";
    static final String STATUS = "com.oplus.ipemanager.action.PENCIL_STATUS_CHANGE";
    static final String WRITING_HAPTIC_ENABLED = "lenovo_pen_global_writing_haptic";
    static final String[] CONNECT_KEYS = {"PENCIL_CONNECT_STATE", "ipe_pencil_connect_state", "ipe_pencil_connection_state", "pencil_connect_state"};
    static final String[] LENOVO_NAMES = {"lenovo precision pen", "lenovo tab pen", "lenovo digital pen", "xiaoxin stylus", "yoga pen", "picasso"};
    static final int[] LENOVO_PRODUCTS = {24959, 24993, 25134};

    private PenBridgeConstants() {
    }
}
