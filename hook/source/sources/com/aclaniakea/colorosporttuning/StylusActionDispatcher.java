package com.aclaniakea.colorosporttuning;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * StylusActionDispatcher Module
 * 
 * Deep module that translates semantic gesture codes into concrete system actions.
 * Encapsulates input key injection, notifications expansion, camera flashlight toggling,
 * QuickNote launching (with handwritten overlay fallback), and lasso OCR/translation.
 * All action paths are protected by ContractProbe and CircuitBreaker.
 */
public final class StylusActionDispatcher {
    private static final String TAG = "StylusActionDispatcher";

    private static volatile boolean sTorchCallbackRegistered = false;
    private static volatile boolean sTorchState = false;

    private StylusActionDispatcher() { }

    private static synchronized void ensureTorchCallback(CameraManager cm) {
        if (sTorchCallbackRegistered || cm == null) return;
        try {
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    sTorchState = enabled;
                    HookUtils.log(TAG + ": torch mode updated from system: " + cameraId + " -> " + enabled);
                }
            }, null);
            sTorchCallbackRegistered = true;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": registerTorchCallback failed: " + th);
        }
    }

    /**
     * Dispatch custom bridge action by code (101 - 114).
     */
    public static void dispatch(final Context context, final int code) {
        if (context == null) return;
        ContractProbe.executeGuarded("action_" + code,
                new ContractProbe.PrimaryAction<Void>() {
                    @Override public Void execute() throws Throwable {
                        executeActionInternal(context, code);
                        return null;
                    }
                },
                new ContractProbe.FallbackAction<Void>() {
                    @Override public Void execute() {
                        HookUtils.log(TAG + ": Action " + code + " failed primary execution; fallback handled");
                        return null;
                    }
                });
    }

    private static void executeActionInternal(Context context, int code) throws Exception {
        switch (code) {
            case 101:
                context.sendBroadcast(new Intent("aclaniakea.penbridge.TAKE_SCREENSHOT"));
                HookUtils.log(TAG + ": screenshot broadcast sent");
                break;
            case 102:
                expandNotifications();
                break;
            case 103:
                injectKey(KeyEvent.KEYCODE_BACK);
                break;
            case 104:
                injectKey(KeyEvent.KEYCODE_HOME);
                break;
            case 105:
                injectKey(KeyEvent.KEYCODE_APP_SWITCH);
                break;
            case 106:
                toggleTorch(context);
                break;
            case 107:
                undo(context);
                break;
            case 108:
                redo(context);
                break;
            case 109:
                pageTurn(context, false);
                break;
            case 110:
                pageTurn(context, true);
                break;
            case 111:
                if (!launchColorOSQuickNote(context)) {
                    HandwrittenNoteOverlay.toggle(context);
                }
                break;
            case 112:
                LassoSelectOverlay.start(context, false);
                break;
            case 113:
                LassoSelectOverlay.start(context, true);
                break;
            case 114:
                LassoSelectOverlay.translateRegion(context,
                        new Rect(1000, 1100, 2492, 1696));
                break;
            default:
                HookUtils.log(TAG + ": unknown action code " + code);
                break;
        }
    }

    public static boolean launchColorOSQuickNote(Context context) {
        try {
            Intent i = new Intent();
            i.setClassName("com.coloros.note",
                    "com.nearme.note.paint.QuickPaintActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
            HookUtils.log(TAG + ": ColorOS QuickPaint launched");
            return true;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": QuickPaint launch failed: " + th);
            return false;
        }
    }

    public static void injectKey(int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                -1, 0, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        Object im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null);
        Method inject = im.getClass().getMethod("injectInputEvent",
                InputEvent.class, Integer.TYPE);
        inject.invoke(im, down, 0);
        Thread.sleep(30);
        long upTime = SystemClock.uptimeMillis();
        KeyEvent up = new KeyEvent(now, upTime, KeyEvent.ACTION_UP, keyCode, 0, 0,
                -1, 0, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        inject.invoke(im, up, 0);
        HookUtils.log(TAG + ": injected key " + keyCode);
    }

    public static void pageTurn(Context context, boolean next) {
        // Per-app strategy: resolve foreground package, prompt once if unconfigured,
        // persist the choice, then perform. See PageTurnConfig.
        PageTurnConfig.performWithPrompt(context, next);
    }

    // ------------------------------------------------------------------
    // Synthetic swipe injection (2026-09-23 rewrite)
    //
    // Measured on device (OPD2409 / ColorOS, 小红书 com.xingin.xhs):
    //   * `adb shell input swipe` switches the video feed reliably.
    //   * the previous implementation here did nothing at all, even though the
    //     dispatcher did deliver the events to the app window (proven with a held
    //     touch + dumpsys input showing touchingPointers=[FINGER] on the app).
    // The three construction differences that made the app ignore our gesture:
    //   1. deviceId 0 (no device) instead of a genuine touchscreen device id;
    //   2. all 12 events injected within ~1 ms while their eventTime spanned
    //      120 ms into the FUTURE (the app sees a gesture whose timestamps do not
    //      correspond to when it was received);
    //   3. injectInputEvent(..., ASYNC) instead of WAIT_FOR_FINISH.
    // This implementation mirrors `input`: real deviceId, real-time pacing (each
    // event's timestamp IS the wall clock at send time), and WAIT_FOR_FINISH.
    // ------------------------------------------------------------------

    /** Wait until the target app has consumed each injected event (what `input` uses). */
    private static final int INJECT_WAIT_FOR_FINISH = 2;

    private static final Object sInjectLock = new Object();
    private static volatile boolean sInjecting = false;
    private static volatile int sTouchDeviceId = Integer.MIN_VALUE;

    /** A real touchscreen device id; 0 means "no device" and gets gestures ignored. */
    private static int touchDeviceId() {
        int cached = sTouchDeviceId;
        if (cached != Integer.MIN_VALUE) return cached;
        int found = 0;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice dev = InputDevice.getDevice(id);
                if (dev == null) continue;
                if ((dev.getSources() & InputDevice.SOURCE_TOUCHSCREEN) != 0) {
                    found = id;
                    break;
                }
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": touchDeviceId: " + th);
        }
        HookUtils.log(TAG + ": touchscreen deviceId=" + found);
        sTouchDeviceId = found;
        return found;
    }

    private static void expectInjected(Object result, String what) {
        if (result instanceof Boolean && !((Boolean) result)) {
            HookUtils.log(TAG + ": injection rejected: " + what);
        }
    }

    private static void injectMotion(Method inject, Object im, int deviceId, long downTime,
                                     int action, float x, float y) throws Exception {
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                x, y, 1.0f, 1.0f, 0, 1.0f, 1.0f, deviceId, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        Object ok = inject.invoke(im, e, INJECT_WAIT_FOR_FINISH);
        e.recycle();
        expectInjected(ok, "action=" + action + " x=" + x + " y=" + y);
    }

    /** Fire a swipe off the main thread (WAIT_FOR_FINISH blocks until the app consumes). */
    private static void swipeAsync(final Context context, final boolean vertical, final boolean next) {
        synchronized (sInjectLock) {
            if (sInjecting) {
                HookUtils.log(TAG + ": swipe skipped, previous gesture still in flight");
                return;
            }
            sInjecting = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    swipeInternal(context, vertical, next);
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": swipe failed: " + th);
                } finally {
                    sInjecting = false;
                }
            }
        }, "pen-swipe");
        t.start();
    }

    private static void swipeInternal(Context context, boolean vertical, boolean next)
            throws Exception {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        if (w <= 0 || h <= 0) {
            w = 3840;
            h = 2560;
        }

        float x1, y1, x2, y2;
        if (vertical) {
            // Next page: swipe up (bottom -> top); Prev page: swipe down (top -> bottom).
            float x = w * 0.50f;
            x1 = x;
            x2 = x;
            y1 = next ? (h * 0.75f) : (h * 0.25f);
            y2 = next ? (h * 0.25f) : (h * 0.75f);
        } else {
            // Next page: swipe right -> left; Prev page: left -> right.
            float y = h * 0.50f;
            y1 = y;
            y2 = y;
            x1 = next ? (w * 0.75f) : (w * 0.25f);
            x2 = next ? (w * 0.25f) : (w * 0.75f);
        }

        int deviceId = touchDeviceId();
        Object im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null);
        Method inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, Integer.TYPE);

        final int steps = 16;
        final long stepMs = 16; // ~256 ms total: a human-paced flick with real timestamps
        long downTime = SystemClock.uptimeMillis();
        injectMotion(inject, im, deviceId, downTime, MotionEvent.ACTION_DOWN, x1, y1);
        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMs);
            float f = (float) i / steps;
            injectMotion(inject, im, deviceId, downTime, MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * f, y1 + (y2 - y1) * f);
        }
        Thread.sleep(stepMs);
        injectMotion(inject, im, deviceId, downTime, MotionEvent.ACTION_UP, x2, y2);

        HookUtils.log(TAG + ": injected " + (vertical ? "vertical" : "horizontal")
                + " swipe next=" + next + " dev=" + deviceId
                + " (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ")");
    }

    public static void injectSwipe(Context context, boolean next) {
        swipeAsync(context, false, next);
    }

    public static void injectVerticalSwipe(Context context, boolean next) {
        swipeAsync(context, true, next);
    }

    public static void undo(Context context) {
        if (HandwrittenNoteOverlay.isVisible()) {
            HandwrittenNoteOverlay.performUndo();
            return;
        }
        // 107 has two consumers and they can never both answer. The full-screen
        // paint canvas replies to no key event at all (NewPaintActivity,
        // NewPaintFragment, CoverPaintView and doodleengine.PaintView declare
        // zero key members), so it is asked directly; every other surface has no
        // live canvas on screen, ignores the command and keeps the injected key.
        // See CanvasPaintHooks for the measurements behind that claim.
        requestCanvasUndo(context, false);
        sendKeyCombination(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z);
    }

    public static void redo(Context context) {
        if (HandwrittenNoteOverlay.isVisible()) {
            HandwrittenNoteOverlay.performRedo();
            return;
        }
        requestCanvasUndo(context, true);
        // Send Ctrl+Shift+Z for canvas/drawing apps
        sendKeyCombination(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z);
        try {
            Thread.sleep(25);
        } catch (InterruptedException ignored) {
        }
        // Send Ctrl+Y for office/text apps
        sendKeyCombination(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Y);
    }

    /**
     * Ask the note process to run the paint canvas's own undo/redo worker
     * ({@code NewPaintEditPresenter.undo()} - the same call the title-bar button
     * makes). Fire-and-forget: the note process decides whether a canvas is
     * actually on screen, so nothing here has to know the foreground activity.
     *
     * <p>Delivery is narrowed two ways: {@code setPackage} restricts it to the
     * note app, and {@code FLAG_RECEIVER_FOREGROUND} keeps the latency inside a
     * gesture's tolerance. Like {@code SystemStylusHooks.sendAll}, this goes
     * through {@code sendBroadcastAsUser(..., UserHandle.ALL, ...)} when the
     * process is permitted to, because a plain {@code sendBroadcast} from
     * {@code system_server} only reaches the sending user.
     */
    private static void requestCanvasUndo(Context context, boolean redo) {
        if (context == null) {
            return;
        }
        dispatchCanvasUndoBroadcast(context, redo, "com.coloros.note");
        dispatchCanvasUndoBroadcast(context, redo, "com.oplus.screenshot");
    }

    private static void dispatchCanvasUndoBroadcast(Context context, boolean redo, String pkg) {
        Intent intent = new Intent(redo ? PenBridgeConstants.CANVAS_REDO
                : PenBridgeConstants.CANVAS_UNDO)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (pkg != null) {
            intent.setPackage(pkg);
        }
        try {
            try {
                Class<?> userHandle = Class.forName("android.os.UserHandle");
                Context.class.getMethod("sendBroadcastAsUser", Intent.class, userHandle, String.class)
                        .invoke(context, intent, userHandle.getField("ALL").get(null), null);
            } catch (Throwable unused) {
                context.sendBroadcast(intent);
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": canvas " + (redo ? "redo" : "undo") + " request failed: " + th);
        }
    }

    public static void sendKeyCombination(int... keyCodes) {
        try {
            long now = SystemClock.uptimeMillis();
            Object im = Class.forName("android.hardware.input.InputManager")
                    .getMethod("getInstance").invoke(null);
            Method inject = im.getClass().getMethod("injectInputEvent",
                    InputEvent.class, Integer.TYPE);

            int metaState = 0;
            for (int code : keyCodes) {
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0,
                        metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_KEYBOARD);
                inject.invoke(im, down, 0);
                metaState |= getModifierMeta(code);
            }

            Thread.sleep(40);
            long upNow = SystemClock.uptimeMillis();

            for (int i = keyCodes.length - 1; i >= 0; i--) {
                int code = keyCodes[i];
                KeyEvent up = new KeyEvent(now, upNow, KeyEvent.ACTION_UP, code, 0,
                        metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_KEYBOARD);
                inject.invoke(im, up, 0);
                metaState &= ~getModifierMeta(code);
            }
            HookUtils.log(TAG + ": injected combo " + Arrays.toString(keyCodes));
        } catch (Throwable th) {
            HookUtils.log(TAG + ": sendKeyCombination failed: " + th);
        }
    }

    private static int getModifierMeta(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return KeyEvent.META_CTRL_RIGHT_ON | KeyEvent.META_CTRL_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return KeyEvent.META_SHIFT_RIGHT_ON | KeyEvent.META_SHIFT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_ALT_ON;
            default:
                return 0;
        }
    }

    public static void expandNotifications() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "statusbar");
        Object svc = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        svc.getClass().getMethod("expandNotificationsPanel").invoke(svc);
        HookUtils.log(TAG + ": notifications expanded");
    }

    public static void toggleTorch(Context context) throws Exception {
        CameraManager cm = (CameraManager) context.getSystemService(CameraManager.class);
        if (cm == null) throw new IllegalStateException("no CameraManager");
        ensureTorchCallback(cm);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                boolean targetState = !sTorchState;
                cm.setTorchMode(id, targetState);
                sTorchState = targetState;
                HookUtils.log(TAG + ": torch " + id + " -> " + targetState);
                return;
            }
        }
        HookUtils.log(TAG + ": no flash unit found");
    }
}
