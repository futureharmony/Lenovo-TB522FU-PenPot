package com.aclaniakea.colorosporttuning;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: classes.dex */
final class NoteToolkitHooks {
    private static final String TOOLKIT = "com.oplusos.vfxsdk.doodleengine.toolkit.Toolkit";
    private static final ArrayList<WeakReference<Object>> active = new ArrayList<>();
    private static boolean receiver;

    /**
     * Re-entrancy guard for the public OEM entry points
     * (receiverSingleClick / receiverDoubleClick). Those two are themselves
     * hooked by install() above, so invoking them from trigger() would recurse
     * forever: trigger -> receiverSingleClick -> hook -> dispatch -> trigger.
     * Only the last-resort strategy below uses them, and only outside a
     * dispatch already in progress on this thread.
     */
    private static final ThreadLocal<Boolean> IN_DISPATCH = new ThreadLocal<Boolean>();

    /**
     * Same-mode de-duplication window.
     *
     * <p>A single strip gesture can legitimately reach the engine twice: our
     * own receiver registered in register() dispatches the configured mode, and
     * the doodle engine's own receiver calls
     * {@code Toolkit.receiverSingleClick(Integer)}, which install()'s hook also
     * intercepts and dispatches. Both land on the same mode within a few
     * milliseconds, and every mode is a TOGGLE -- so running it twice opens the
     * palette and immediately closes it again, which is indistinguishable from
     * "the gesture did nothing". Collapsing the duplicate keeps one action.
     */
    private static long lastDispatchAt;
    private static int lastDispatchMode;

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.1
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    NoteToolkitHooks.register(context);
                    // Opt-in one-shot EGL diagnostic. No-ops unless the switch
                    // (Settings.Global lenovo_penbridge_egl_diag=1) is on.
                    NoteEglDiag.runOnce(context, NoteToolkitHooks.phaseOf(methodHookParam, "app-oncreate"));
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "android.app.Application", "onCreate", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.nearme.note.MyApplication", "onCreate", xC_MethodHook);
        XC_MethodHook xC_MethodHook2 = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.2
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                NoteToolkitHooks.remember(methodHookParam.thisObject);
                Context context = methodHookParam.thisObject instanceof View ? ((View) methodHookParam.thisObject).getContext() : HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    NoteToolkitHooks.register(context);
                    // Second phase: the engine has loaded its toolkit by now, so
                    // comparing this against the app-oncreate phase tells us
                    // whether the engine's own init is what breaks EGL.
                    NoteEglDiag.runOnce(context, NoteToolkitHooks.phaseOf(methodHookParam, "toolkit"));
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, "onAttachedToWindow", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, "onResume$paint_intermediate_release", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, "onDetachedFromWindow", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.3
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                NoteToolkitHooks.forget(methodHookParam.thisObject);
            }
        });
        String[] strArr = {"receiverSingleClick", "receiverDoubleClick"};
        for (int i = 0; i < 2; i++) {
            final String str = strArr[i];
            HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, str, new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.4
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    Context context = methodHookParam.thisObject instanceof View ? ((View) methodHookParam.thisObject).getContext() : null;
                    int iMode = NoteToolkitHooks.mode(context, str.contains("Double"), methodHookParam.args);
                    if (iMode < 1 || iMode > 3 || !NoteToolkitHooks.dispatch(iMode)) {
                        return;
                    }
                    methodHookParam.setResult((Object) null);
                    NoteToolkitHooks.haptic(context);
                }
            });
        }
        HookUtils.log("Note/Screenshot hooks installed in " + loadPackageParam.packageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void register(Context context) {
        if (receiver) {
            return;
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK");
        intentFilter.addAction("com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK");
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (NoteToolkitHooks.dispatch(NoteToolkitHooks.mode(context2, "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK".equals(intent.getAction()), intent.getExtras() == null ? null : new Object[]{Integer.valueOf(intent.getIntExtra("action", 0))}))) {
                    NoteToolkitHooks.haptic(context2);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.getApplicationContext().registerReceiver(broadcastReceiver, intentFilter, 2);
            } else {
                context.getApplicationContext().registerReceiver(broadcastReceiver, intentFilter);
            }
            receiver = true;
        } catch (Throwable th) {
            HookUtils.log("toolkit receiver: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int mode(Context context, boolean z, Object[] objArr) {
        int iIntValue = 0;
        if (objArr != null && objArr.length > 0) {
            Object obj = objArr[0];
            if (obj instanceof Number) {
                iIntValue = ((Number) obj).intValue();
            }
        }
        if (iIntValue >= 1 && iIntValue <= 3) {
            return iIntValue;
        }
        if (context == null) {
            return z ? 2 : 1;
        }
        return Settings.Global.getInt(context.getContentResolver(), z ? "ipe_pencil_double_click" : "ipe_pencil_single_click", z ? 2 : 1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void remember(Object obj) {
        forget(obj);
        active.add(new WeakReference<>(obj));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void forget(Object obj) {
        Iterator<WeakReference<Object>> it = active.iterator();
        while (it.hasNext()) {
            Object obj2 = it.next().get();
            if (obj2 == null || obj2 == obj) {
                it.remove();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized boolean dispatch(int i) {
        if (active.isEmpty()) {
            // Diagnostics for the "gesture does nothing" class of report: the
            // system side (PhoneWindowManager -> click -> broadcast) can be
            // perfect while this list is empty, e.g. because the note editor
            // was never opened and no Toolkit was ever attached.
            HookUtils.log("toolkit dispatch(mode=" + i + "): no Toolkit attached");
            return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (i == lastDispatchMode && now - lastDispatchAt < 150L) {
            HookUtils.log("toolkit dispatch(mode=" + i + ") dropped as duplicate (+"
                    + (now - lastDispatchAt) + "ms)");
            return true;
        }
        lastDispatchAt = now;
        lastDispatchMode = i;
        boolean zTrigger = false;
        Iterator<WeakReference<Object>> it = active.iterator();
        while (it.hasNext()) {
            Object obj = it.next().get();
            if (obj == null) {
                it.remove();
            } else {
                zTrigger |= trigger(obj, i);
            }
        }
        HookUtils.log("toolkit dispatch(mode=" + i + ") -> " + zTrigger);
        return zTrigger;
    }

    /**
     * Drive the doodle engine's own pen-mode switch.
     *
     * <p>MODE SEMANTICS (decompiled from
     * com.oplusos.vfxsdk.doodleengine.toolkit.Toolkit):
     * {@code private void triggerStylusClick(int mode, boolean vibrate)} is the
     * single real worker --
     * <pre>
     *   1 -> toggleWithEraserView()                     (switch_eraser)
     *   2 -> toggleWithLastPenView()                    (switch_recent)
     *   3 -> toolkitWrapperManager.showColorIfNeed {}   (show_color)
     * </pre>
     * so the integer is exactly Settings.Global
     * {@code ipe_pencil_single_click} / {@code ipe_pencil_double_click}.
     *
     * <p>WHY THE OLD CODE NEVER WORKED (this is the fix): the previous revision
     * scanned {@code {"triggerStylusClick", "onStylusClick", "switchPenMode"}}
     * with {@code Class.getMethods()}, which returns PUBLIC members only. The
     * real method is declared PRIVATE and takes TWO arguments
     * ({@code triggerStylusClick(IZ)V}); the other two names do not exist on
     * this class at all. The loop therefore always fell through to
     * {@code return false}, so every gesture was a silent no-op -- exactly the
     * reported symptom: PhoneWindowManager logs the strip gesture, click()
     * sends the broadcast, the receiver in com.coloros.note fires, and nothing
     * visible happens.
     *
     * <p>STRATEGY ORDER (first that resolves wins):
     * <ol>
     *   <li>declared (private) {@code triggerStylusClick(int, boolean)}</li>
     *   <li>Kotlin default-arg bridge {@code triggerStylusClick$default}</li>
     *   <li>synthetic accessor {@code access$triggerStylusClick}</li>
     *   <li>public {@code receiverSingleClick/receiverDoubleClick(Integer)}
     *       -- recursion-guarded, because install() hooks those two</li>
     *   <li>legacy public-name scan, for other ROM revisions</li>
     * </ol>
     */
    private static boolean trigger(Object obj, int i) throws SecurityException {
        if (obj == null) {
            return false;
        }
        Class<?> cls = obj.getClass();

        // --- 1. the real, private worker --------------------------------
        try {
            Method m = cls.getDeclaredMethod("triggerStylusClick", int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(obj, Integer.valueOf(i), Boolean.FALSE);
            HookUtils.log("toolkit triggerStylusClick(mode=" + i + ") ok foreGround="
                    + boolField(obj, "foreGroundRunning"));
            return true;
        } catch (Throwable th) {
            HookUtils.log("toolkit triggerStylusClick(private) unavailable: " + th);
        }

        // --- 2. Kotlin $default bridge (public static synthetic) --------
        try {
            Method m = cls.getDeclaredMethod("triggerStylusClick$default", cls, int.class,
                    boolean.class, int.class, Object.class);
            m.setAccessible(true);
            m.invoke(null, obj, Integer.valueOf(i), Boolean.FALSE, Integer.valueOf(2), null);
            HookUtils.log("toolkit triggerStylusClick$default(mode=" + i + ") ok");
            return true;
        } catch (Throwable unused) {
        }

        // --- 3. synthetic accessor (public static) ----------------------
        try {
            Method m = cls.getDeclaredMethod("access$triggerStylusClick", cls, int.class,
                    boolean.class);
            m.setAccessible(true);
            m.invoke(null, obj, Integer.valueOf(i), Boolean.FALSE);
            HookUtils.log("toolkit access$triggerStylusClick(mode=" + i + ") ok");
            return true;
        } catch (Throwable unused) {
        }

        // --- 4. public OEM entry points (recursion-guarded) -------------
        // install() hooks receiverSingleClick/receiverDoubleClick, and that
        // hook calls dispatch() -> trigger() again, so entering them from here
        // recurses unless the re-entry is cut off.
        if (!Boolean.TRUE.equals(IN_DISPATCH.get())) {
            Method m = null;
            try {
                m = cls.getMethod(i == 2 ? "receiverDoubleClick" : "receiverSingleClick",
                        Integer.class);
            } catch (Throwable unused) {
            }
            if (m != null) {
                try {
                    m.setAccessible(true);
                    IN_DISPATCH.set(Boolean.TRUE);
                    try {
                        m.invoke(obj, Integer.valueOf(i));
                    } finally {
                        IN_DISPATCH.remove();
                    }
                    HookUtils.log("toolkit " + m.getName() + "(mode=" + i + ") ok");
                    return true;
                } catch (Throwable unused) {
                }
            }
        }

        // --- 5. legacy public-name scan ---------------------------------
        String[] strArr = {"onStylusClick", "switchPenMode", "triggerStylusClick"};
        for (int i2 = 0; i2 < 3; i2++) {
            String str = strArr[i2];
            for (Method method : cls.getMethods()) {
                if (method.getName().equals(str)) {
                    try {
                        method.setAccessible(true);
                        Class<?>[] params = method.getParameterTypes();
                        if (params.length == 2 && params[0] == int.class
                                && params[1] == boolean.class) {
                            method.invoke(obj, Integer.valueOf(i), Boolean.FALSE);
                        } else if (params.length == 1) {
                            method.invoke(obj, HookUtils.adapt(params[0], Integer.valueOf(i)));
                        } else if (params.length == 0) {
                            method.invoke(obj, new Object[0]);
                        }
                        HookUtils.log("toolkit legacy " + str + "(mode=" + i + ") ok");
                        return true;
                    } catch (Throwable unused) {
                    }
                }
            }
        }
        HookUtils.log("toolkit trigger(mode=" + i + ") found no callable on " + cls.getName());
        return false;
    }

    /** Best-effort read of a declared boolean field; diagnostics only. */
    private static String boolField(Object obj, String str) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(str);
            f.setAccessible(true);
            return String.valueOf(f.getBoolean(obj));
        } catch (Throwable th) {
            return "?";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void haptic(Context context) {
        if (context == null) {
            return;
        }
        PenHapticGatt.brush(context, HookUtils.state(context).address);
    }

    /**
     * Names a diagnostic phase after the hooked method so the EGL probe reports
     * exactly where in the app's lifecycle it fired. Deliberately tolerant: a
     * missing Member must not break the hook.
     */
    /* JADX INFO: Access modifiers changed from: private */
    public static String phaseOf(XC_MethodHook.MethodHookParam methodHookParam, String str) {
        try {
            java.lang.reflect.Member member = methodHookParam.method;
            if (member == null) {
                return str;
            }
            String name = member.getName();
            return name == null ? str : str + ":" + name;
        } catch (Throwable th) {
            return str;
        }
    }

    private NoteToolkitHooks() {
    }
}
