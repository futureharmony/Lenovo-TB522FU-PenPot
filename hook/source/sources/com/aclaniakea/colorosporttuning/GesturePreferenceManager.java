package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GesturePreferenceManager Module
 * 
 * Deep module responsible for injecting non-stock writing extensions and system shortcuts
 * into the OEM PencilGestureSettingActivity, keeping radio button selection marks in sync,
 * hiding irrelevant wheel UI rows, and persisting bridge keys to Settings.Global and system_server.
 */
public final class GesturePreferenceManager {
    private static final String TAG = "GesturePreferenceManager";

    private static final Map<Object, String> gesturePageTypes = new HashMap<>();

    private GesturePreferenceManager() { }

    public static void install(final XC_LoadPackage.LoadPackageParam lpp) {
        probeGestureRowApi(lpp.classLoader);

        HookUtils.hookAll(lpp.classLoader,
                "android.app.Activity", "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (!StylusGestureConstants.GESTURE_ACTIVITY_CLASS.equals(
                                hook.thisObject.getClass().getName())) {
                            return;
                        }
                        try {
                            String type = ((Activity) hook.thisObject)
                                    .getIntent().getStringExtra("click_type");
                            synchronized (gesturePageTypes) {
                                gesturePageTypes.put(hook.thisObject, type);
                            }
                            HookUtils.log("gesture activity onCreate, click_type=" + type);
                        } catch (Throwable th) {
                            HookUtils.log("gesture page type: " + th);
                        }
                    }
                });

        HookUtils.hookAll(lpp.classLoader,
                "android.app.Activity", "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (!StylusGestureConstants.GESTURE_ACTIVITY_CLASS.equals(
                                hook.thisObject.getClass().getName())) {
                            return;
                        }
                        final Activity activity = (Activity) hook.thisObject;
                        String t0 = null;
                        try {
                            Intent cur = activity.getIntent();
                            if (cur != null) t0 = cur.getStringExtra("click_type");
                        } catch (Throwable ignored) { }
                        synchronized (gesturePageTypes) {
                            gesturePageTypes.put(hook.thisObject, t0);
                        }
                        final String pageType = t0;
                        if ("long_press".equals(pageType)) {
                            activity.setTitle("长按手势");
                        } else if ("squeeze".equals(pageType)) {
                            activity.setTitle("捏握手势");
                        }
                        for (final long delay : new long[]{0L, 300L, 900L, 1600L}) {
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override public void run() {
                                    configurePenGestureActivity(activity);
                                    forceGesturePageTitle(activity, pageType);
                                }
                            }, delay);
                        }
                    }
                });

        final String[] markSources = {"androidx.preference.TwoStatePreference",
                "com.coui.appcompat.preference.COUIMarkPreference"};
        for (final String markSource : markSources) {
            HookUtils.hookAll(lpp.classLoader, markSource, "setChecked",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            try {
                                Object key = hook.thisObject.getClass()
                                        .getMethod("getKey").invoke(hook.thisObject);
                                if (key == null || !Boolean.TRUE.equals(hook.args[0])) return;
                                String ks = key.toString();
                                if (ks.startsWith("item_wb_")) return;
                                Activity host = null;
                                synchronized (gesturePageTypes) {
                                    for (Object o : gesturePageTypes.keySet()) {
                                        if (o instanceof Activity && !((Activity) o).isFinishing()) {
                                            host = (Activity) o;
                                            break;
                                        }
                                    }
                                }
                                if (host == null) return;
                                String type = null;
                                synchronized (gesturePageTypes) {
                                    type = gesturePageTypes.get(host);
                                }
                                if (type == null) return;
                                int sel = Settings.Global.getInt(host.getContentResolver(),
                                        StylusGestureConstants.gestureWbKey(type), -1);
                                if (sel >= 100) {
                                    hook.setResult(null);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
        }

        HookUtils.hookAll(lpp.classLoader,
                "androidx.preference.Preference", "performClick",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        try {
                            Object key = hook.thisObject.getClass()
                                    .getMethod("getKey").invoke(hook.thisObject);
                            if (key == null) return;
                            String ks = key.toString();
                            if (ks.startsWith("item_wb_")) return;
                            Context ctx = (Context) hook.thisObject.getClass()
                                    .getMethod("getContext").invoke(hook.thisObject);
                            Activity host = null;
                            synchronized (gesturePageTypes) {
                                for (Object o : gesturePageTypes.keySet()) {
                                    if (o instanceof Activity && !((Activity) o).isFinishing()) {
                                        host = (Activity) o;
                                        break;
                                    }
                                }
                            }
                            if (host == null && ctx instanceof Activity) host = (Activity) ctx;
                            if (host == null) return;
                            String type = null;
                            synchronized (gesturePageTypes) {
                                type = gesturePageTypes.get(host);
                            }
                            if (type == null) return;
                            persistGestureKey(host, StylusGestureConstants.gestureWbKey(type), -1);
                            ClassLoader loader = host.getClassLoader();
                            Object screen = findScreen(host);
                            if (screen != null) {
                                syncGestureMarks(loader, screen, type, -1);
                            }
                        } catch (Throwable th) {
                            HookUtils.log("performClick stock hook: " + th);
                        }
                    }
                });
    }

    public static void persistGestureKey(Context ctx, String key, int value) {
        try {
            Settings.Global.putInt(ctx.getContentResolver(), key, value);
        } catch (Throwable ignored) { }
        try {
            Intent i = new Intent(PenBridgeConstants.WRITE_GESTURE_KEY);
            i.putExtra("key", key);
            i.putExtra("value", value);
            ctx.sendBroadcast(i);
        } catch (Throwable th) {
            HookUtils.log("gesture key broadcast: " + th);
        }
    }

    private static Object findScreen(Activity activity) {
        try {
            Object fm = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
            List<?> frags = (List<?>) fm.getClass().getMethod("getFragments").invoke(fm);
            if (frags != null) {
                for (Object f : frags) {
                    if (f == null) continue;
                    try {
                        Object scr = f.getClass().getMethod("getPreferenceScreen").invoke(f);
                        if (scr != null) return scr;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void forceGesturePageTitle(Activity activity, String type) {
        if (!"long_press".equals(type) && !"squeeze".equals(type)) return;
        final String want = "long_press".equals(type) ? "长按手势" : "捏握手势";
        try {
            View bar = null;
            for (String idName : new String[]{"toolbar", "appbar_layout"}) {
                int id = activity.getResources().getIdentifier(idName, "id",
                        activity.getPackageName());
                if (id != 0) bar = activity.findViewById(id);
                if (bar != null) break;
            }
            if (bar == null) bar = activity.getWindow().getDecorView();
            TextView tv = biggestTextView(bar);
            if (tv == null) return;
            String now = String.valueOf(tv.getText());
            if (!want.equals(now)) {
                HookUtils.log("gesture page title: " + now + " -> " + want);
                tv.setText(want);
            }
        } catch (Throwable th) {
            HookUtils.log("gesture page title: " + th);
        }
    }

    private static TextView biggestTextView(View root) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.push(root);
        TextView best = null;
        while (!q.isEmpty()) {
            View v = q.pop();
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.push(g.getChildAt(i));
            }
            if (!(v instanceof TextView)) continue;
            TextView tv = (TextView) v;
            String t = String.valueOf(tv.getText());
            if (t.isEmpty() || t.length() > 12) continue;
            if (best == null || tv.getTextSize() > best.getTextSize()) best = tv;
        }
        return best;
    }

    public static void configurePenGestureActivity(Activity activity) {
        try {
            Object fm = activity.getClass()
                    .getMethod("getSupportFragmentManager").invoke(activity);
            List<?> frags = (List<?>) fm.getClass().getMethod("getFragments").invoke(fm);
            if (frags == null) return;
            for (Object f : frags) {
                if (f == null) continue;
                String fn = f.getClass().getName();
                if (fn.contains("Pencil") || fn.contains("Gesture") || fn.contains("Setting")) {
                    configurePenGestureCustomRows(activity, f);
                    return;
                }
            }
            for (Object f : frags) {
                if (f == null) continue;
                try {
                    Object screen = f.getClass().getMethod("getPreferenceScreen").invoke(f);
                    if (screen != null) {
                        configurePenGestureCustomRows(activity, f);
                        return;
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable th) {
            HookUtils.log("configurePenGestureActivity: " + th);
        }
    }

    private static String readGesturePageType(Object fragment) {
        try {
            Class<?> c = fragment.getClass();
            while (c != null && !Object.class.equals(c)) {
                for (Field f : c.getDeclaredFields()) {
                    if (!String.class.equals(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(fragment);
                    if (v instanceof String && ("single_click".equals(v)
                            || "double_click".equals(v) || "long_click_v2".equals(v)
                            || "long_press".equals(v) || "squeeze".equals(v))) {
                        return (String) v;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void configurePenGestureCustomRows(final Activity activity,
            final Object fragment) {
        try {
            String type0 = readGesturePageType(fragment);
            if (type0 == null) {
                synchronized (gesturePageTypes) {
                    type0 = gesturePageTypes.get(activity);
                }
            }
            if (type0 == null) return;
            final String type = type0;
            final Context context = activity;
            Object screen = fragment.getClass()
                    .getMethod("getPreferenceScreen").invoke(fragment);
            if (screen == null) return;
            ClassLoader loader = context.getClassLoader();
            Class<?> pref = Class.forName("androidx.preference.Preference", false, loader);
            Method find = screen.getClass().getMethod("findPreference", CharSequence.class);

            if ("long_press".equals(type) || "squeeze".equals(type)) {
                for (String oemKey : new String[]{"pencil_gesture_pager_header",
                        "pencil_wheel_setting_category", "item_wheel_show_tool_name",
                        "item_global_wheel"}) {
                    try {
                        Object oemRow = find.invoke(screen, oemKey);
                        if (oemRow != null) {
                            pref.getMethod("setVisible", Boolean.TYPE).invoke(oemRow, false);
                        }
                    } catch (Throwable ignored) { }
                }
            }

            Object current = find.invoke(screen, "lenovo_pen_gesture_extra");
            if (current != null) {
                int sel = Settings.Global.getInt(context.getContentResolver(),
                        StylusGestureConstants.gestureWbKey(type), -1);
                syncGestureMarks(loader, screen, type, sel);
                scheduleLateGestureResync(loader, screen, type, sel);
                return;
            }

            Object categoryWriting = makeGestureCategory(context, pref, screen,
                    "lenovo_pen_gesture_extra", "书写扩展", 100);
            Object categorySystem = makeGestureCategory(context, pref, screen,
                    "lenovo_pen_gesture_system", "系统快捷", 200);

            for (int idx : StylusGestureConstants.GROUP_WRITING) {
                makeGestureRow(context, pref, categoryWriting, type, idx,
                        StylusGestureConstants.CUSTOM_CODES[idx]);
            }
            for (int idx : StylusGestureConstants.GROUP_SYSTEM) {
                makeGestureRow(context, pref, categorySystem, type, idx,
                        StylusGestureConstants.CUSTOM_CODES[idx]);
            }

            int sel = Settings.Global.getInt(context.getContentResolver(),
                    StylusGestureConstants.gestureWbKey(type), -1);
            syncGestureMarks(loader, screen, type, sel);
            scheduleLateGestureResync(loader, screen, type, sel);
        } catch (Throwable th) {
            HookUtils.log("configurePenGestureCustomRows: " + th);
        }
    }

    private static Object makeGestureCategory(Context context, Class<?> pref,
            Object screen, String key, String title, int order) {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> catClass = Class.forName("com.coui.appcompat.preference.COUIPreferenceCategory",
                    false, loader);
            Constructor<?> ctor = catClass.getConstructor(Context.class);
            Object cat = ctor.newInstance(context);
            pref.getMethod("setKey", String.class).invoke(cat, key);
            pref.getMethod("setTitle", CharSequence.class).invoke(cat, title);
            pref.getMethod("setOrder", Integer.TYPE).invoke(cat, order);
            screen.getClass().getMethod("addPreference", pref).invoke(screen, cat);
            return cat;
        } catch (Throwable th) {
            try {
                Class<?> catClass = Class.forName("androidx.preference.PreferenceCategory", false,
                        context.getClassLoader());
                Object cat = catClass.getConstructor(Context.class).newInstance(context);
                pref.getMethod("setKey", String.class).invoke(cat, key);
                pref.getMethod("setTitle", CharSequence.class).invoke(cat, title);
                pref.getMethod("setOrder", Integer.TYPE).invoke(cat, order);
                screen.getClass().getMethod("addPreference", pref).invoke(screen, cat);
                return cat;
            } catch (Throwable ignored) {
                return screen;
            }
        }
    }

    private static void makeGestureRow(final Context context, Class<?> pref,
            Object category, final String type, int idx, final int code) {
        try {
            Object row = newGestureRowInstance(context);
            String key = "item_wb_" + code;
            pref.getMethod("setKey", String.class).invoke(row, key);
            pref.getMethod("setTitle", CharSequence.class)
                    .invoke(row, StylusGestureConstants.CUSTOM_LABELS[idx]);
            try {
                pref.getMethod("setSummary", CharSequence.class)
                        .invoke(row, StylusGestureConstants.CUSTOM_SUMMARIES[idx]);
            } catch (Throwable ignored) { }
            try {
                pref.getMethod("setOrder", Integer.TYPE).invoke(row, 100 + idx);
            } catch (Throwable ignored) { }

            Class<?> listenClass = Class.forName(
                    "androidx.preference.Preference$OnPreferenceClickListener",
                    false, context.getClassLoader());
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    context.getClassLoader(),
                    new Class<?>[]{listenClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onPreferenceClick".equals(method.getName())) {
                                onCustomRowClicked(context, type, code);
                                return true;
                            }
                            return null;
                        }
                    });
            pref.getMethod("setOnPreferenceClickListener", listenClass).invoke(row, listener);
            category.getClass().getMethod("addPreference", pref).invoke(category, row);
        } catch (Throwable th) {
            HookUtils.log("makeGestureRow code=" + code + ": " + th);
        }
    }

    private static void onCustomRowClicked(Context context, String type, int code) {
        try {
            persistGestureKey(context, StylusGestureConstants.gestureWbKey(type), code);
            Activity act = (context instanceof Activity) ? (Activity) context : null;
            if (act != null) {
                ClassLoader loader = context.getClassLoader();
                Object screen = findScreen(act);
                if (screen != null) {
                    syncGestureMarks(loader, screen, type, code);
                }
            }
        } catch (Throwable th) {
            HookUtils.log("onCustomRowClicked: " + th);
        }
    }

    private static Object newGestureRowInstance(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        for (String cn : new String[]{
                "com.coui.appcompat.preference.COUIMarkPreference",
                "com.oplus.settings.widget.preference.MarkPreference",
                "androidx.preference.CheckBoxPreference"}) {
            try {
                Class<?> c = Class.forName(cn, false, loader);
                return c.getConstructor(Context.class).newInstance(context);
            } catch (Throwable ignored) { }
        }
        throw new NoSuchMethodException("No mark preference class found");
    }

    private static void probeGestureRowApi(ClassLoader loader) {
        try {
            Class.forName("com.coui.appcompat.preference.COUIMarkPreference", false, loader);
        } catch (Throwable ignored) { }
    }

    private static void syncGestureMarks(ClassLoader loader, Object screen,
            String type, int selectedCode) {
        try {
            for (Object p : collectPreferences(loader, screen)) {
                String key = preferenceKey(p);
                if (key == null) continue;
                if (key.startsWith("item_wb_")) {
                    int code = Integer.parseInt(key.substring("item_wb_".length()));
                    setRowChecked(p, code == selectedCode);
                } else if (selectedCode >= 100) {
                    setRowChecked(p, false);
                }
            }
        } catch (Throwable th) {
            HookUtils.log("syncGestureMarks: " + th);
        }
    }

    private static void scheduleLateGestureResync(final ClassLoader loader,
            final Object screen, final String type, final int selectedCode) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                syncGestureMarks(loader, screen, type, selectedCode);
            }
        }, 400L);
    }

    private static List<Object> collectPreferences(ClassLoader loader, Object group) {
        List<Object> out = new ArrayList<>();
        try {
            Class<?> gClass = Class.forName("androidx.preference.PreferenceGroup", false, loader);
            if (!gClass.isInstance(group)) return out;
            int count = (Integer) gClass.getMethod("getPreferenceCount").invoke(group);
            Method getPref = gClass.getMethod("getPreference", Integer.TYPE);
            for (int i = 0; i < count; i++) {
                Object p = getPref.invoke(group, i);
                if (p == null) continue;
                out.add(p);
                if (gClass.isInstance(p)) {
                    out.addAll(collectPreferences(loader, p));
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String preferenceKey(Object p) {
        try {
            Object k = p.getClass().getMethod("getKey").invoke(p);
            return k == null ? null : k.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setRowChecked(Object p, boolean checked) {
        try {
            Method m = p.getClass().getMethod("setChecked", Boolean.TYPE);
            m.invoke(p, checked);
        } catch (Throwable ignored) { }
    }
}
