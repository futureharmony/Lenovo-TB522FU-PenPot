package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CanvasPaintHooks - undo/redo bridge for the full-screen paint canvas
 * ({@code com.nearme.note.paint.NewPaintActivity} / {@code QuickPaintActivity}).
 *
 * <h2>The problem this solves</h2>
 * Bridge action 107 (undo) is implemented as an injected {@code Ctrl+Z}
 * ({@link StylusActionDispatcher#undo}). The note app's WebView editor does
 * handle that: {@code WVJBWebView.onKeyPreIme} -> {@code WVNoteViewEditFragment}
 * -> {@code undoEvent()}. The full-screen canvas does not, and cannot - measured
 * 2026-09-19 against the shipping ROM (fw 2.0.12.015, note app 15.29.5) by
 * decompiling every layer of that surface:
 *
 * <table>
 *   <caption>key-event handling on the canvas surface</caption>
 *   <tr><th>class</th><th>members</th><th>key-event members</th></tr>
 *   <tr><td>{@code NewPaintActivity}</td><td>16</td><td>0</td></tr>
 *   <tr><td>{@code NewPaintFragment}</td><td>480</td><td>0 (only {@code KEY_*}
 *       Intent-extra constants)</td></tr>
 *   <tr><td>{@code CoverPaintView}</td><td>188</td><td>0</td></tr>
 *   <tr><td>{@code doodleengine.PaintView}</td><td>370</td><td>0</td></tr>
 * </table>
 *
 * {@code PaintView} extends {@code FrameLayout} and overrides no key callback,
 * so an injected {@code Ctrl+Z} is delivered to the focused window and then
 * dropped. Injecting harder cannot fix a surface that has no receiver.
 *
 * <h2>The contract</h2>
 * The canvas exposes a clean, public, zero-argument worker - it is literally
 * what the title-bar undo button runs
 * ({@code NewPaintFragment.initTitleBar$lambda$63} -> {@code PaintView.undo()}):
 * <pre>
 *   com.nearme.note.paint.NewPaintEditPresenter   (PUBLIC FINAL)
 *     private final CoverPaintView paintView;
 *     public void    undo()     { paintView.undo(); }   // ()V
 *     public void    redo()     { paintView.redo(); }   // ()V
 *     public boolean canUndo();                          // ()Z
 *     public boolean canRedo();                          // ()Z
 * </pre>
 * These are invoked reflectively; the hook never links against the OEM class,
 * so an OEM rename degrades to "logged no-op", never to a {@code NoClassDefFoundError}.
 *
 * <h2>Why a broadcast, and why it cannot double-fire</h2>
 * The trigger (the touch-strip squeeze) is only visible in {@code system_server},
 * while the worker lives in the note process - the same seam
 * {@link NoteToolkitHooks} already bridges with a receiver. So
 * {@link StylusActionDispatcher} sends a targeted broadcast and
 * <em>keeps</em> injecting {@code Ctrl+Z} as well:
 * <ul>
 *   <li>canvas on screen - the command drives the canvas and the injected key is
 *       inert (the table above); and</li>
 *   <li>any other surface - no canvas is registered, so the command is a logged
 *       no-op and the injected key does what it always did.</li>
 * </ul>
 * Exactly one of the two can act, which is why neither side needs to know the
 * foreground activity. The gate that makes this true is {@link #dispatch}: a
 * candidate only counts while its fragment reports {@code isAdded() &&
 * isResumed()}. That is precise because {@code NewPaintFragment} is referenced
 * exclusively from {@code com.nearme.note.paint.*} - never from
 * {@code MainActivity}, {@code NoteDetailFragment} or
 * {@code NoteDetailPaintManager} - so it is resumed only while a full-screen
 * canvas is actually on screen.
 *
 * <h2>Failure modes</h2>
 * Every reflective step is individually guarded and the receiver body runs under
 * {@link ContractProbe#executeGuarded}, so repeated failure trips the circuit
 * breaker and silently degrades to "do nothing" instead of throwing inside the
 * note app's main thread. Registration is idempotent and held by weak
 * references, so a leaked canvas can never pin the activity.
 */
final class CanvasPaintHooks {
    private static final String TAG = "CanvasPaintHooks";

    private static final String FRAGMENT = "com.nearme.note.paint.NewPaintFragment";
    private static final String PRESENTER = "com.nearme.note.paint.NewPaintEditPresenter";

    /** Declared private field on {@code NewPaintFragment} holding the presenter. */
    private static final String PRESENTER_FIELD = "newPaintPresenter";

    /** The canvas is a single-window surface; two commands inside this window
     * are one physical gesture arriving twice (our receiver and the OEM's own
     * listener both see some gestures), not two deliberate presses. */
    private static final long DEDUP_MS = 250L;

    /** Cancels the duplicates the OEM gesture pipeline is known to deliver. */
    private static long lastDispatchAt;
    private static boolean lastDispatchRedo;

    private static final ArrayList<WeakReference<Object>> canvases = new ArrayList<>();
    private static boolean receiverRegistered;
    private static WeakReference<Activity> sCurrentActivity = new WeakReference<>(null);

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null || loadPackageParam.classLoader == null) {
            return;
        }
        final ClassLoader loader = loadPackageParam.classLoader;

        // Eagerly install receiver if Application is already running
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentAppMethod = activityThreadClass.getMethod("currentApplication");
            Application app = (Application) currentAppMethod.invoke(null);
            if (app != null) {
                ensureReceiver(app);
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": currentApplication probe failed: " + th);
        }

        // Track foreground activity in the note process to access on-screen toolbar actions
        HookUtils.hookAll(loader, "android.app.Activity", "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) {
                    sCurrentActivity = new WeakReference<>((Activity) param.thisObject);
                    ensureReceiver((Context) param.thisObject);
                }
            }
        });
        HookUtils.hookAll(loader, "android.app.Activity", "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject == sCurrentActivity.get()) {
                    sCurrentActivity = new WeakReference<>(null);
                }
            }
        });

        // Lifecycle seam. onStart/onStop are declared by NewPaintFragment itself
        // (it does NOT override onResume), so HookUtils.hookAll can reach them;
        // between them the presenter is guaranteed to exist, because the
        // fragment builds it in onCreateView.
        HookUtils.hookAll(loader, FRAGMENT, "onStart", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                remember(param.thisObject);
                ensureReceiver(fragmentContext(param.thisObject));
            }
        });
        XC_MethodHook forget = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                forget(param.thisObject);
            }
        };
        HookUtils.hookAll(loader, FRAGMENT, "onStop", forget);
        HookUtils.hookAll(loader, FRAGMENT, "onDestroyView", forget);

        // The note process outlives any single canvas, and makeText/toast paths
        // can open a canvas before any fragment callback has run in this
        // process - so latch an application context as an independent second
        // chance to install the receiver.
        HookUtils.hookAll(loader, "android.app.Application", "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ensureReceiver(HookUtils.context(param.thisObject));
            }
        });
        // Hook Service.onCreate for overlay services such as com.oplus.screenshot.LongshotService
        HookUtils.hookAll(loader, "android.app.Service", "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Context) {
                    ensureReceiver((Context) param.thisObject);
                }
            }
        });

        HookUtils.log(TAG + ": canvas undo/redo bridge installed in "
                + loadPackageParam.packageName);
    }

    private static Activity getForegroundActivity() {
        Activity act = sCurrentActivity.get();
        if (act != null && !act.isFinishing() && !act.isDestroyed() && act.hasWindowFocus()) {
            return act;
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method m = activityThreadClass.getMethod("currentActivityThread");
            Object activityThread = m.invoke(null);
            if (activityThread != null) {
                Field f = activityThreadClass.getDeclaredField("mActivities");
                f.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) f.get(activityThread);
                if (map != null) {
                    for (Object record : map.values()) {
                        Field actF = record.getClass().getDeclaredField("activity");
                        actF.setAccessible(true);
                        Activity candidate = (Activity) actF.get(record);
                        if (candidate != null && !candidate.isFinishing() && !candidate.isDestroyed()) {
                            boolean paused = false;
                            try {
                                Field pausedF = record.getClass().getDeclaredField("paused");
                                pausedF.setAccessible(true);
                                paused = pausedF.getBoolean(record);
                            } catch (Throwable ignored) {}
                            if (!paused || candidate.hasWindowFocus()) {
                                sCurrentActivity = new WeakReference<>(candidate);
                                return candidate;
                            }
                            if (act == null) {
                                act = candidate;
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": getForegroundActivity failed: " + th);
        }
        if (act != null) {
            sCurrentActivity = new WeakReference<>(act);
        }
        return act;
    }

    private static final String[] UNDO_NAMES = {"undo", "menu_undo", "undo_button", "undo_btn", "btn_undo"};
    private static final String[] REDO_NAMES = {"redo", "menu_redo", "redo_button", "redo_btn", "btn_redo"};

    private static View findUndoRedoButton(View root, boolean redo) {
        if (root == null) return null;
        String[] targetNames = redo ? REDO_NAMES : UNDO_NAMES;
        try {
            if (root.getContext() != null) {
                for (String name : targetNames) {
                    int resId = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
                    if (resId != 0) {
                        View found = root.findViewById(resId);
                        if (found != null && found.getVisibility() == View.VISIBLE && (found.getWidth() > 0 || found.isAttachedToWindow())) {
                            return found;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return searchViewRecursive(root, redo);
    }

    private static View searchViewRecursive(View root, boolean redo) {
        if (root == null) return null;
        String[] targetIds = redo ? REDO_NAMES : UNDO_NAMES;

        int id = root.getId();
        if (id != View.NO_ID && root.getResources() != null) {
            try {
                String entry = root.getResources().getResourceEntryName(id);
                for (String targetId : targetIds) {
                    if (targetId.equals(entry) || (redo ? "redo" : "undo").equalsIgnoreCase(entry)) {
                        // Prioritize visible and laid-out views
                        if (root.getVisibility() == View.VISIBLE) {
                            return root;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        CharSequence desc = root.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if (redo ? (d.contains("恢复") || d.contains("重做") || d.contains("redo"))
                     : (d.contains("撤销") || d.contains("undo"))) {
                if (root.getVisibility() == View.VISIBLE) {
                    return root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = searchViewRecursive(group.getChildAt(i), redo);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<View> getAllRootViews() {
        List<View> result = new ArrayList<>();
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmgClass.getMethod("getInstance");
            Object wmg = getInstance.invoke(null);
            if (wmg != null) {
                Field mViewsField = wmgClass.getDeclaredField("mViews");
                mViewsField.setAccessible(true);
                List<?> views = (List<?>) mViewsField.get(wmg);
                if (views != null) {
                    synchronized (wmg) {
                        for (Object v : views) {
                            if (v instanceof View) {
                                result.add((View) v);
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": getAllRootViews failed: " + th);
        }
        return result;
    }

    private static boolean tryClickInRoot(final View root, final boolean redo, final String sourceName) {
        if (root == null) return false;
        final boolean[] handled = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable clickTask = new Runnable() {
            @Override
            public void run() {
                try {
                    View btn = findUndoRedoButton(root, redo);
                    if (btn != null) {
                        if (btn.isEnabled()) {
                            long downTime = SystemClock.uptimeMillis();
                            float x = btn.getWidth() > 0 ? btn.getWidth() / 2f : 10f;
                            float y = btn.getHeight() > 0 ? btn.getHeight() / 2f : 10f;
                            try {
                                MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
                                MotionEvent up = MotionEvent.obtain(downTime, downTime + 10, MotionEvent.ACTION_UP, x, y, 0);
                                btn.dispatchTouchEvent(down);
                                btn.dispatchTouchEvent(up);
                                down.recycle();
                                up.recycle();
                            } catch (Throwable th) {
                                HookUtils.log(TAG + ": dispatchTouchEvent on " + btn.getClass().getSimpleName() + " failed: " + th);
                            }
                            boolean res = btn.performClick();
                            handled[0] = true;
                            HookUtils.log(TAG + ": clicked " + (redo ? "redo" : "undo")
                                    + " in " + sourceName + " (" + btn.getClass().getSimpleName()
                                    + ") performClick=" + res);
                        } else {
                            HookUtils.log(TAG + ": button " + (redo ? "redo" : "undo")
                                    + " is disabled in " + sourceName + " (nothing to " + name(redo) + ")");
                            handled[0] = true;
                        }
                    }
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": UI click failed in " + sourceName + ": " + th);
                } finally {
                    latch.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clickTask.run();
        } else {
            new Handler(Looper.getMainLooper()).post(clickTask);
            try {
                latch.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
        }
        return handled[0];
    }

    /**
     * Runs the canvas's own undo/redo worker. Called from the receiver in the
     * note process; never from {@code system_server}, where no canvas exists.
     *
     * @return true when a resumed canvas took the command.
     */
    private static boolean dispatch(final boolean redo) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDispatchAt < DEDUP_MS && lastDispatchRedo == redo) {
            HookUtils.log(TAG + ": duplicate " + name(redo) + " dropped (+"
                    + (now - lastDispatchAt) + "ms)");
            return true;
        }

        // 1. First priority: Check foreground Resumed Activity toolbar menu_undo / menu_redo button
        final Activity act = getForegroundActivity();
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (tryClickInRoot(decor, redo, act.getClass().getSimpleName())) {
                lastDispatchAt = now;
                lastDispatchRedo = redo;
                return true;
            }
        }

        // 2. Second priority: Check WindowManager root views (e.g. LongshotMark in com.oplus.screenshot or dialogs)
        List<View> roots = getAllRootViews();
        for (int i = roots.size() - 1; i >= 0; i--) {
            View root = roots.get(i);
            if (root != null && root.isAttachedToWindow() && root.getVisibility() == View.VISIBLE) {
                if (tryClickInRoot(root, redo, "WindowOverlay")) {
                    lastDispatchAt = now;
                    lastDispatchRedo = redo;
                    return true;
                }
            }
        }

        Object presenter = null;
        synchronized (CanvasPaintHooks.class) {
            Iterator<WeakReference<Object>> it = canvases.iterator();
            while (it.hasNext()) {
                Object fragment = it.next().get();
                if (fragment == null) {
                    it.remove();
                    continue;
                }
                if (!onScreen(fragment)) {
                    continue;
                }
                presenter = presenterOf(fragment);
                if (presenter != null) {
                    break;
                }
            }
        }
        if (presenter == null) {
            // Not an error: 107/108 must stay usable on every keyboard-driven
            // surface, and on those there is simply no canvas to talk to.
            HookUtils.log(TAG + ": no resumed canvas on screen, " + name(redo) + " left to the key path");
            return false;
        }

        lastDispatchAt = now;
        lastDispatchRedo = redo;
        boolean invoked = invoke(presenter, redo ? "redo" : "undo");
        // canUndo/canRedo are read only for the log: the button does not consult
        // them either, and they are cached flags that can lag the real stack.
        HookUtils.log(TAG + ": canvas " + name(redo) + " invoked=" + invoked
                + " canUndo=" + HookUtils.call(presenter, "canUndo")
                + " canRedo=" + HookUtils.call(presenter, "canRedo"));
        return invoked;
    }

    /** A candidate counts only while it is genuinely the surface on screen. */
    private static boolean onScreen(Object fragment) {
        return Boolean.TRUE.equals(HookUtils.call(fragment, "isAdded"))
                && Boolean.TRUE.equals(HookUtils.call(fragment, "isResumed"));
    }

    /** Read {@code NewPaintFragment.newPaintPresenter}, tolerating a renamed field. */
    private static Object presenterOf(Object fragment) {
        try {
            Field field;
            try {
                field = fragment.getClass().getDeclaredField(PRESENTER_FIELD);
            } catch (NoSuchFieldException missing) {
                // Private fields are renameable by R8 between app updates. The
                // declared type is not, so fall back to "the field that holds a
                // NewPaintEditPresenter" before giving up.
                field = presenterFieldByType(fragment.getClass());
                if (field == null) {
                    HookUtils.log(TAG + ": no presenter field on " + fragment.getClass().getName());
                    return null;
                }
                HookUtils.log(TAG + ": presenter field renamed to '" + field.getName() + "'");
            }
            field.setAccessible(true);
            return field.get(fragment);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": presenter lookup failed: " + th);
            return null;
        }
    }

    private static Field presenterFieldByType(Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            if (PRESENTER.equals(field.getType().getName())) {
                return field;
            }
        }
        return null;
    }

    /** Invoke a public no-arg method, reporting success separately from a void
     * return (which {@link HookUtils#call} cannot distinguish from failure). */
    private static boolean invoke(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            m.invoke(target);
            return true;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": " + method + " failed: " + th);
            return false;
        }
    }

    private static Context fragmentContext(Object fragment) {
        Object viaFragment = HookUtils.call(fragment, "getContext");
        if (viaFragment instanceof Context) {
            return (Context) viaFragment;
        }
        Object viaActivity = HookUtils.call(fragment, "getActivity");
        if (viaActivity instanceof Context) {
            return (Context) viaActivity;
        }
        return HookUtils.context(fragment);
    }

    /**
     * Install the command receiver once per note process.
     *
     * <p>EXPORTED (not NOT_EXPORTED) is required and deliberate: the sender runs
     * in {@code system_server}, a different uid, so a not-exported receiver
     * would never see the command. Reachability is narrowed instead by
     * {@code setPackage("com.coloros.note")} on the sender and by the
     * module-private action names.
     */
    static synchronized void ensureReceiver(Context context) {
        if (receiverRegistered || context == null) {
            return;
        }
        IntentFilter filter = new IntentFilter(PenBridgeConstants.CANVAS_UNDO);
        filter.addAction(PenBridgeConstants.CANVAS_REDO);
        filter.addAction("com.aclaniakea.colorosporttuning.action.CANVAS_UNDO");
        filter.addAction("com.aclaniakea.colorosporttuning.action.CANVAS_REDO");
        filter.addAction("com.aclaniakea.lenovopenbridge.action.CANVAS_UNDO");
        filter.addAction("com.aclaniakea.lenovopenbridge.action.CANVAS_REDO");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                final String action = intent == null ? null : intent.getAction();
                if (action == null) {
                    return;
                }
                final boolean redo = PenBridgeConstants.CANVAS_REDO.equals(action)
                        || "com.aclaniakea.colorosporttuning.action.CANVAS_REDO".equals(action)
                        || "com.aclaniakea.lenovopenbridge.action.CANVAS_REDO".equals(action);
                ContractProbe.executeGuarded("canvas_" + name(redo),
                        new ContractProbe.PrimaryAction<Void>() {
                            @Override
                            public Void execute() {
                                dispatch(redo);
                                return null;
                            }
                        },
                        new ContractProbe.FallbackAction<Void>() {
                            @Override
                            public Void execute() {
                                HookUtils.log(TAG + ": canvas " + name(redo)
                                        + " fallback (circuit open)");
                                return null;
                            }
                        });
            }
        };
        try {
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            if (app instanceof Application) {
                try {
                    ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
                        @Override
                        public void onActivityStarted(Activity activity) {}
                        @Override
                        public void onActivityResumed(Activity activity) {
                            sCurrentActivity = new WeakReference<>(activity);
                        }
                        @Override
                        public void onActivityPaused(Activity activity) {
                            if (sCurrentActivity.get() == activity) {
                                sCurrentActivity = new WeakReference<>(null);
                            }
                        }
                        @Override
                        public void onActivityStopped(Activity activity) {}
                        @Override
                        public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
                        @Override
                        public void onActivityDestroyed(Activity activity) {}
                    });
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": registerActivityLifecycleCallbacks failed: " + th);
                }
            }
            receiverRegistered = true;
            HookUtils.log(TAG + ": undo/redo receiver registered");
        } catch (Throwable th) {
            HookUtils.log(TAG + ": receiver registration failed: " + th);
        }
    }

    private static synchronized void remember(Object fragment) {
        forget(fragment);
        if (fragment != null) {
            canvases.add(new WeakReference<>(fragment));
        }
    }

    private static synchronized void forget(Object fragment) {
        Iterator<WeakReference<Object>> it = canvases.iterator();
        while (it.hasNext()) {
            Object candidate = it.next().get();
            if (candidate == null || candidate == fragment) {
                it.remove();
            }
        }
    }

    private static String name(boolean redo) {
        return redo ? "redo" : "undo";
    }

    private CanvasPaintHooks() {
    }
}
