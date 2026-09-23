package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Dialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Per-app page-turn trigger strategy.
 *
 * <p>When the user triggers "上一页 / 下一页" (actions 109 / 110) inside an app that has no
 * stored strategy yet, we pop a one-time chooser (水平模拟 / 垂直模拟 / KeyEvent上下 /
 * KeyEvent左右), persist the choice keyed by the foreground package, and immediately perform it.
 * Subsequent triggers in the same app skip the chooser. The device-center main panel
 * (see PanelCardExtension) lets the user review / change / clear these per-app choices.</p>
 *
 * <p>Strategies:
 *   0 = horizontal swipe   (good for PDF / gallery / horizontal-paging apps)
 *   1 = vertical swipe     (good for 抖音 / 小红书 / vertical-feed apps)
 *   2 = KEYCODE_PAGE_UP/DOWN (good for apps that consume page keys)
 *   3 = KEYCODE_DPAD_LEFT/RIGHT (good for apps that navigate on arrow keys)</p>
 *
 * <p><b>UI note (2026-09-23):</b> the dialogs are built entirely from custom views instead of
 * {@code AlertDialog.setItems()}. The chooser runs inside {@code system_server}, whose Context
 * resolves the framework's {@code select_dialog_item} layout to a theme whose list-item text
 * colour is invisible against a transparent/oversized dialog window — the symptom was "dialog
 * shows up but has no selectable options". A fully self-drawn card (explicit background, text
 * colours, rounded corners, press states, night/light aware) removes every dependency on the
 * host theme and renders correctly from the system context.</p>
 *
 * <p><b>4.8.7 flow change:</b> picking 模拟滑动 no longer pops the full-screen recorder. The
 * recorder is a deliberate, user-invoked action reachable only from the in-app first-trigger flow
 * (chooser → {@link #showRangePage} → 「自定义滑动范围」), so an ordinary page turn executes
 * immediately and is never interrupted. The device center's per-app page shows the same four
 * strategies for review / change / clear and nothing else (2026-09-23); see
 * {@link #showChangeDialog} for why re-recording must happen inside the target app. Styles follow
 * the ColorOS dialog language: accent-tinted text buttons, 16sp list rows with secondary 12sp
 * descriptions, ripple press feedback, 28dp card radius.</p>
 */
public final class PageTurnConfig {
    private static final String TAG = "PageTurnConfig";

    public static final int STRATEGY_HORIZONTAL = 0;
    public static final int STRATEGY_VERTICAL = 1;
    public static final int STRATEGY_KEY_UD = 2;
    public static final int STRATEGY_KEY_LR = 3;

    /** Settings.Global key holding a JSON object { packageName: strategyInt }. */
    static final String MAP_KEY = "lenovo_pen_pageturn_map";

    /** Sentinel passed to a chooser listener meaning "clear this app's config". */
    private static final int PICK_CLEAR = -1;

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Set<String> sPrompting = new HashSet<>();

    /**
     * True while the second page of the first-trigger chooser (「用默认范围 / 自定义滑动范围」) is
     * on screen. Pen triggers are ignored for its duration: the strategy is already persisted at
     * that point, so a trigger would silently execute the default path while the user is still
     * answering the question.
     */
    private static boolean sRangePageUp;

    private static final String[] OPTION_NAMES = {
            "水平模拟", "垂直模拟", "KeyEvent 上下", "KeyEvent 左右"
    };
    private static final String[] OPTION_DESCS = {
            "左右滑动屏幕 · 适合 PDF / 相册 / 漫画",
            "上下滑动屏幕 · 适合抖音 / 小红书等视频流",
            "发送 PageUp / PageDown 按键",
            "发送方向键 Left / Right"
    };

    private PageTurnConfig() { }

    // ==================================================================
    // Persistence (system uid write path; app-process write is dropped on reboot)
    // ==================================================================
    private static JSONObject readMap(Context ctx) {
        try {
            String s = Settings.Global.getString(ctx.getContentResolver(), MAP_KEY);
            if (s != null && !s.isEmpty()) return new JSONObject(s);
        } catch (Throwable ignored) { }
        return new JSONObject();
    }

    private static void writeMap(Context ctx, JSONObject map) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), MAP_KEY, map.toString());
        } catch (Throwable th) {
            HookUtils.log(TAG + ": writeMap: " + th);
        }
    }

    public static int getStrategy(Context ctx, String pkg) {
        try {
            return readMap(ctx).optInt(pkg, -1);
        } catch (Throwable th) {
            return -1;
        }
    }

    /**
     * Unconditional direct write, no broadcast. Used by the PAGETURN_CONFIG receiver (which
     * always runs under the persisting uid in system_server) so persistence never depends on
     * {@link #isSystemContext} being right and can never re-broadcast — hence never loop.
     */
    static void persist(Context ctx, String pkg, int strategy) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            JSONObject m = readMap(ctx);
            if (strategy < 0) m.remove(pkg);
            else m.put(pkg, strategy);
            writeMap(ctx, m);
            HookUtils.log(TAG + ": persist " + pkg + " -> " + strategy);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": persist: " + th);
        }
    }

    /** Persist a strategy (system_server context) or request a persist (app context). */
    public static void setStrategy(Context ctx, String pkg, int strategy) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            if (isSystemContext(ctx)) {
                persist(ctx, pkg, strategy);
            } else {
                requestSetStrategy(ctx, pkg, strategy);
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": setStrategy: " + th);
        }
    }

    /** Store / clear a strategy when the caller is NOT system_server (pen panel host). */
    public static void requestSetStrategy(Context ctx, String pkg, int strategy) {
        // Mirror the proven gesture-key path (GesturePreferenceManager.persistGestureKey):
        // write directly first — the OEM panel host is a privileged system app on ColorOS and
        // its ContentResolver write is honoured — then announce it so system_server re-applies
        // it under its own uid. Two independent paths; either one landing is enough, and with
        // the direct write the panel re-reads the new value immediately instead of racing a
        // cross-process broadcast.
        try {
            JSONObject m = readMap(ctx);
            if (strategy < 0) m.remove(pkg);
            else m.put(pkg, strategy);
            writeMap(ctx, m);
            HookUtils.log(TAG + ": requestSetStrategy direct " + pkg + " -> " + strategy);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": requestSetStrategy direct: " + th);
        }
        try {
            Intent i = new Intent(PenBridgeConstants.PAGETURN_CONFIG);
            i.putExtra("pkg", pkg);
            i.putExtra("strategy", strategy);
            ctx.sendBroadcast(i);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": requestSetStrategy: " + th);
        }
    }

    public static Map<String, Integer> allConfigured(Context ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            JSONObject m = readMap(ctx);
            Iterator<String> it = m.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, m.optInt(k, -1));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static String label(Context ctx, int strategy) {
        switch (strategy) {
            case STRATEGY_HORIZONTAL: return OPTION_NAMES[0];
            case STRATEGY_VERTICAL: return OPTION_NAMES[1];
            case STRATEGY_KEY_UD: return OPTION_NAMES[2];
            case STRATEGY_KEY_LR: return OPTION_NAMES[3];
            default: return "未配置";
        }
    }

    /** "垂直模拟 · 已自定义范围" — one line of list-row detail, ColorOS style. */
    private static String strategySummary(Context ctx, String pkg, int strategy) {
        if (strategy < 0) return "未配置";
        String base = label(ctx, strategy);
        if (!isSwipeStrategy(strategy)) return base;
        boolean n = hasCalibration(ctx, pkg, true);
        boolean pv = hasCalibration(ctx, pkg, false);
        if (n && pv) return base + " · 已自定义范围";
        if (n || pv) return base + " · 部分自定义";
        return base + " · 默认范围";
    }

    // ==================================================================
    // Swipe trajectory calibration (per app, per direction)
    //
    // Some apps only accept a swipe that starts inside one particular region
    // (the video area above a comment bar, the scrollable column of a split
    // layout, a carousel below a fixed header, ...). The ratio-based default
    // swipe then lands outside the scrollable area and the app ignores it —
    // indistinguishable from "the feature is broken". The user can therefore
    // record one real swipe per direction; the recorded polyline is replayed
    // verbatim, normalised to 0..1 with per-point timing so that both the path
    // AND the velocity profile (which is what a fling detector actually looks
    // at) survive the round trip.
    //
    // Stored in a SEPARATE Settings.Global key so that lenovo_pen_pageturn_map
    // keeps its {pkg: intStrategy} shape and old values stay readable.
    // ==================================================================

    /** Settings.Global key holding {"<pkg>|<dir>": {orient, pts, dur, w, h}}. */
    static final String SWIPE_KEY = "lenovo_pen_pageturn_swipe";

    /** Nominal duration of the ratio-based default path (16 steps x 16 ms). */
    public static final int DEFAULT_DURATION_MS = 256;

    static final String DIR_NEXT = "next";
    static final String DIR_PREV = "prev";

    /** True for the two strategies that inject a motion gesture (0 and 1). */
    public static boolean isSwipeStrategy(int strategy) {
        return strategy == STRATEGY_HORIZONTAL || strategy == STRATEGY_VERTICAL;
    }

    /**
     * {widthPixels, heightPixels} of the display. This is the coordinate space every
     * normalised swipe value is expressed in, and it is deliberately the single source
     * used by BOTH the recorder (SwipeCalibrateOverlay) and the replay (swipeInternal),
     * so a recorded path can never be interpreted in a different space.
     */
    static int[] screenSize(Context ctx) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        if (w <= 0 || h <= 0) {
            w = 3840;
            h = 2560;
        }
        return new int[]{w, h};
    }

    /**
     * The built-in path for a mode/direction as {{x1,y1,0},{x2,y2,DEFAULT_DURATION_MS}},
     * normalised. Same numbers the pre-calibration implementation used, so an app with no
     * recorded trajectory keeps the exact behaviour that was verified on device.
     */
    static float[][] defaultPath(boolean vertical, boolean next) {
        float x1, y1, x2, y2;
        if (vertical) {
            // next (下一页) = swipe up (bottom -> top); prev = swipe down.
            float x = 0.50f;
            x1 = x;
            x2 = x;
            y1 = next ? 0.75f : 0.25f;
            y2 = next ? 0.25f : 0.75f;
        } else {
            // next = swipe left (right -> left); prev = swipe right.
            float y = 0.50f;
            y1 = y;
            y2 = y;
            x1 = next ? 0.75f : 0.25f;
            x2 = next ? 0.25f : 0.75f;
        }
        return new float[][]{{x1, y1, 0f}, {x2, y2, (float) DEFAULT_DURATION_MS}};
    }

    private static String swipeKey(String pkg, boolean next) {
        return pkg + "|" + (next ? DIR_NEXT : DIR_PREV);
    }

    private static JSONObject readSwipeMap(Context ctx) {
        try {
            String s = Settings.Global.getString(ctx.getContentResolver(), SWIPE_KEY);
            if (s != null && !s.isEmpty()) return new JSONObject(s);
        } catch (Throwable ignored) { }
        return new JSONObject();
    }

    private static void writeSwipeMap(Context ctx, JSONObject map) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), SWIPE_KEY,
                    map.length() == 0 ? "" : map.toString());
        } catch (Throwable th) {
            HookUtils.log(TAG + ": writeSwipeMap: " + th);
        }
    }

    /**
     * Recorded trajectory for (pkg, direction) as {{x,y,tMs},...} normalised to
     * {@link #screenSize}, or null when there is none.
     *
     * <p>A trajectory recorded in a different orientation is refused rather than
     * silently reused: the normalised values would point at a different part of the
     * screen after a rotation, which is worse than falling back to the default.</p>
     */
    static float[][] getCalibration(Context ctx, String pkg, boolean next) {
        try {
            if (pkg == null || pkg.isEmpty()) return null;
            JSONObject e = readSwipeMap(ctx).optJSONObject(swipeKey(pkg, next));
            if (e == null) return null;
            int orient = e.optInt("orient", -1);
            int cur = ctx.getResources().getConfiguration().orientation;
            if (orient > 0 && cur > 0 && orient != cur) {
                HookUtils.log(TAG + ": calibration " + pkg + " dir=" + (next ? DIR_NEXT : DIR_PREV)
                        + " ignored, recorded in orientation " + orient + " (now " + cur + ")");
                return null;
            }
            org.json.JSONArray pts = e.optJSONArray("pts");
            if (pts == null || pts.length() < 2) return null;
            float[][] out = new float[pts.length()][];
            for (int i = 0; i < pts.length(); i++) {
                org.json.JSONArray q = pts.optJSONArray(i);
                if (q == null || q.length() < 2) return null;
                out[i] = new float[]{(float) q.optDouble(0, 0.0),
                        (float) q.optDouble(1, 0.0), (float) q.optDouble(2, 0.0)};
            }
            return out;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": getCalibration: " + th);
            return null;
        }
    }

    static boolean hasCalibration(Context ctx, String pkg, boolean next) {
        return getCalibration(ctx, pkg, next) != null;
    }

    /** Human description of a recorded path, e.g. "向上滑动 · 268ms". */
    static String describeCalibration(float[][] pts) {
        if (pts == null || pts.length < 2) return "无";
        float[] a = pts[0];
        float[] b = pts[pts.length - 1];
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        String dir;
        if (Math.abs(dy) >= Math.abs(dx)) dir = dy < 0 ? "向上" : "向下";
        else dir = dx < 0 ? "向左" : "向右";
        return dir + "滑动 · " + Math.round(b[2]) + "ms";
    }

    /** Unconditional direct write of a recorded trajectory (system uid). */
    static void persistCalibration(Context ctx, String pkg, boolean next, float[][] pts, int orient) {
        try {
            if (pkg == null || pkg.isEmpty() || pts == null || pts.length < 2) return;
            org.json.JSONArray arr = new org.json.JSONArray();
            for (float[] pt : pts) {
                org.json.JSONArray q = new org.json.JSONArray();
                q.put(round4(pt[0]));
                q.put(round4(pt[1]));
                q.put(Math.round(pt.length > 2 ? pt[2] : 0f));
                arr.put(q);
            }
            int[] sz = screenSize(ctx);
            JSONObject e = new JSONObject();
            e.put("orient", orient > 0 ? orient : ctx.getResources().getConfiguration().orientation);
            e.put("pts", arr);
            e.put("dur", Math.round(pts[pts.length - 1][2]));
            e.put("w", sz[0]);
            e.put("h", sz[1]);

            JSONObject m = readSwipeMap(ctx);
            m.put(swipeKey(pkg, next), e);
            writeSwipeMap(ctx, m);
            HookUtils.log(TAG + ": persistCalibration " + pkg + " dir=" + (next ? DIR_NEXT : DIR_PREV)
                    + " pts=" + pts.length + " " + describeCalibration(pts)
                    + " space=" + sz[0] + "x" + sz[1]);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": persistCalibration: " + th);
        }
    }

    static void persistClearCalibration(Context ctx, String pkg, boolean next) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            JSONObject m = readSwipeMap(ctx);
            m.remove(swipeKey(pkg, next));
            writeSwipeMap(ctx, m);
            HookUtils.log(TAG + ": clearCalibration " + pkg + " dir=" + (next ? DIR_NEXT : DIR_PREV));
        } catch (Throwable th) {
            HookUtils.log(TAG + ": persistClearCalibration: " + th);
        }
    }

    /**
     * Persist a trajectory. The only caller is the calibration overlay, which is always
     * created by {@code system_server}, so the direct write is the correct (and only) path --
     * no broadcast, hence no loop and no serialisation of the point array.
     */
    static void saveCalibration(Context ctx, String pkg, boolean next, float[][] pts, int orient) {
        persistCalibration(ctx, pkg, next, pts, orient);
    }

    static void clearCalibration(Context ctx, String pkg, boolean next) {
        if (isSystemContext(ctx)) {
            persistClearCalibration(ctx, pkg, next);
        } else {
            requestCalibrateOp(ctx, pkg, next, "clear_calib");
        }
    }

    private static double round4(float v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static void requestCalibrateOp(Context ctx, String pkg, boolean next, String op) {
        // Direct write first (the ColorOS panel host is privileged and its write is honoured),
        // then announce it so system_server re-applies it under its own uid.
        try {
            if ("clear_calib".equals(op)) persistClearCalibration(ctx, pkg, next);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": requestCalibrateOp direct: " + th);
        }
        try {
            Intent i = new Intent(PenBridgeConstants.PAGETURN_CONFIG);
            i.putExtra("op", op);
            i.putExtra("pkg", pkg);
            i.putExtra("dir", next ? DIR_NEXT : DIR_PREV);
            ctx.sendBroadcast(i);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": requestCalibrateOp: " + th);
        }
    }

    /** Ask system_server to pop the calibration overlay for (pkg, direction). */
    public static void requestCalibration(Context ctx, String pkg, boolean next) {
        requestCalibrateOp(ctx, pkg, next, "calibrate");
    }

    /**
     * Bring the target app to the front, then open the recorder on top of it.
     *
     * <p>The recorder lets the calibrated gesture through to whatever is underneath it, so
     * "which app is underneath" is not cosmetic: recording over the device-center panel would
     * capture a trajectory the target app will never receive. That panel is the host of this
     * entry point, not the app being configured, so the app has to be launched first. A launch is
     * not instantaneous, so the foreground is polled rather than slept on, with a bounded give-up
     * so a package without a launcher activity cannot stall the flow.</p>
     */
    static void startCalibration(final Context ctx, final String pkg, final boolean next,
                                final SwipeCalibrateOverlay.Listener onDone) {
        sMain.post(new Runnable() {
            @Override public void run() {
                try {
                    bringToFront(ctx, pkg, next, onDone, AWAIT_TRIES);
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": startCalibration: " + th);
                    showCalibration(ctx, pkg, next, onDone);
                }
            }
        });
    }

    /** ~3 s of patience, in {@link #AWAIT_STEP_MS} steps. */
    private static final int AWAIT_TRIES = 14;
    private static final long AWAIT_STEP_MS = 220L;

    private static void bringToFront(final Context ctx, final String pkg, final boolean next,
                                     final SwipeCalibrateOverlay.Listener onDone, final int tries) {
        String fg = null;
        try {
            fg = getForegroundPackage(ctx);
        } catch (Throwable th) {
            // Best effort: a detector failure must not block the recorder.
        }
        if (pkg.equals(fg)) {
            HookUtils.log(TAG + ": calibrate, app already in front: " + pkg);
            showCalibration(ctx, pkg, next, onDone);
            return;
        }
        if (tries == AWAIT_TRIES) {
            boolean launched = false;
            try {
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    launchContext(ctx).startActivity(i);
                    launched = true;
                } else {
                    HookUtils.log(TAG + ": no launcher activity for " + pkg);
                }
            } catch (Throwable th) {
                HookUtils.log(TAG + ": cannot launch " + pkg + ": " + th);
            }
            if (!launched) {
                showCalibration(ctx, pkg, next, onDone);
                return;
            }
            HookUtils.log(TAG + ": launched " + pkg + " before calibrating");
        }
        if (tries <= 0) {
            HookUtils.log(TAG + ": " + pkg + " never came to the front, calibrating anyway");
            showCalibration(ctx, pkg, next, onDone);
            return;
        }
        sMain.postDelayed(new Runnable() {
            @Override public void run() {
                bringToFront(ctx, pkg, next, onDone, tries - 1);
            }
        }, AWAIT_STEP_MS);
    }

    private static void showCalibration(Context ctx, String pkg, boolean next,
                                       SwipeCalibrateOverlay.Listener onDone) {
        try {
            SwipeCalibrateOverlay.show(ctx, pkg, next, onDone);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": showCalibration: " + th);
        }
    }

    /**
     * The calibration request arrives in system_server, where {@code startActivity} on the plain
     * system context works but logs "Calling a method in the system process without a qualified
     * user" and leans on an implicit current-user fallback. Qualifying the context would need
     * {@code createContextAsUser} / {@code getUser} / {@code UserHandle.of}, none of which are in
     * the public SDK — not worth hidden-API plumbing for a log line, so the call stays as is.
     * Measured: the target app is resumed ~250 ms after the request.
     */
    private static Context launchContext(Context ctx) {
        return ctx;
    }

    // ==================================================================
    // Foreground package (system_server side; no per-app tracker exists)
    //
    // 2026-09-23 fix: the first implementation used
    // queryUsageStats(INTERVAL_DAILY, now-5s, now) and took the entry with the
    // largest lastTimeUsed. That is NOT a foreground detector — the DAILY bucket
    // aggregates EVERY app used today and lastTimeUsed is whatever the aggregation
    // last recorded, so the winner was frequently a system package (SystemUI /
    // launcher / the pen-panel host) rather than the app the user was in.
    // Consequence: the strategy stored on the first trigger was keyed by the wrong
    // package, so the user edited that bogus entry in the pen panel while the real
    // app kept prompting forever.
    //
    // Resolution order (each layer best-effort, failures logged):
    //   1. ActivityManager.getRunningTasks(1)  -> top task's activity package
    //   2. UsageEvents: newest ACTIVITY_RESUMED not yet paused/stopped
    //   3. queryUsageStats (legacy, weakest)
    //
    // Layer 2 is especially valuable here: our own chooser dialog is a
    // system_server overlay window and emits no activity transition, so it cannot
    // shadow the app the user is actually in.
    // ==================================================================

    /** Packages that own overlays / launcher chrome rather than user content. */
    private static final String[] OVERLAY_HOSTS = {
            "android",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.oplus.launcher",
            "com.oplus.ipemanager",
    };

    private static boolean isOverlayHost(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        for (String h : OVERLAY_HOSTS) {
            if (h.equals(pkg)) return true;
        }
        return false;
    }

    /** Ordered foreground guesses, most trustworthy first (deduplicated). */
    public static List<String> foregroundCandidates(Context ctx) {
        List<String> out = new ArrayList<>(3);
        addCandidate(out, topTaskPackage(ctx));
        addCandidate(out, latestResumedPackage(ctx));
        addCandidate(out, usageStatsPackage(ctx));
        return out;
    }

    private static void addCandidate(List<String> out, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (!out.contains(pkg)) out.add(pkg);
    }

    /** Best single guess: first non-overlay candidate, else the first one. */
    public static String getForegroundPackage(Context ctx) {
        List<String> c = foregroundCandidates(ctx);
        for (String p : c) {
            if (!isOverlayHost(p)) return p;
        }
        return c.isEmpty() ? null : c.get(0);
    }

    /** Layer 1: the top task's activity package. */
    private static String topTaskPackage(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ActivityManager.RunningTaskInfo t = tasks.get(0);
            if (t == null || t.topActivity == null) return null;
            return t.topActivity.getPackageName();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": topTaskPackage: " + th);
            return null;
        }
    }

    /** Layer 2: replay resume/pause events, the last still-resumed app wins. */
    private static String latestResumedPackage(Context ctx) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            String fg = scanResumed(usm, now - 30L * 60 * 1000, now);
            if (fg == null) fg = scanResumed(usm, now - 12L * 60 * 60 * 1000, now);
            return fg;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": latestResumedPackage: " + th);
            return null;
        }
    }

    private static String scanResumed(UsageStatsManager usm, long begin, long end) {
        UsageEvents events = usm.queryEvents(begin, end);
        if (events == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String fg = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                fg = e.getPackageName();
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (e.getPackageName() != null && e.getPackageName().equals(fg)) fg = null;
            }
        }
        return fg;
    }

    /** Layer 3 (legacy, weakest): most recently used package of today's bucket. */
    private static String usageStatsPackage(Context ctx) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 5000, now);
            if (stats == null || stats.isEmpty()) return null;
            UsageStats recent = null;
            for (UsageStats s : stats) {
                if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) recent = s;
            }
            return recent != null ? recent.getPackageName() : null;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": usageStatsPackage: " + th);
            return null;
        }
    }

    private static boolean isSystemContext(Context ctx) {
        // system_server context has no Activity ancestor and is the framework context.
        return activityOf(ctx) == null && ctx != null
                && "android".equals(ctx.getPackageName());
    }

    // ==================================================================
    // Execution
    // ==================================================================
    /** @param pkg the app the trigger is attributed to; may be null (no calibration then). */
    public static void perform(Context ctx, String pkg, boolean next, int strategy) {
        try {
            switch (strategy) {
                case STRATEGY_VERTICAL:
                    dispatchSwipe(ctx, pkg, next, true);
                    break;
                case STRATEGY_KEY_UD:
                    StylusActionDispatcher.injectKey(next ? KeyEvent.KEYCODE_PAGE_DOWN : KeyEvent.KEYCODE_PAGE_UP);
                    break;
                case STRATEGY_KEY_LR:
                    StylusActionDispatcher.injectKey(next ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                    break;
                case STRATEGY_HORIZONTAL:
                default:
                    dispatchSwipe(ctx, pkg, next, false);
                    break;
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": perform strategy=" + strategy + ": " + th);
        }
    }

    /**
     * Swipe, using the user's recorded trajectory when one exists for this app and
     * direction, otherwise the built-in ratio path.
     */
    private static void dispatchSwipe(Context ctx, String pkg, boolean next, boolean vertical) {
        float[][] recorded = getCalibration(ctx, pkg, next);
        if (recorded != null) {
            HookUtils.log(TAG + ": replay recorded trajectory pkg=" + pkg
                    + " dir=" + (next ? DIR_NEXT : DIR_PREV) + " " + describeCalibration(recorded));
            StylusActionDispatcher.injectSwipePath(ctx, recorded);
        } else {
            StylusActionDispatcher.injectSwipe(ctx, vertical, next);
        }
    }

    /** Called from StylusActionDispatcher.pageTurn: resolve strategy, prompt if needed. */
    public static void performWithPrompt(Context ctx, boolean next) {
        // A modal calibration is on screen: the user's next gesture belongs to it.
        if (SwipeCalibrateOverlay.isVisible()) {
            HookUtils.log(TAG + ": trigger ignored, calibration overlay is up");
            return;
        }
        // The range question is still open (strategy already persisted, default not chosen):
        // running it now would answer on the user's behalf.
        if (sRangePageUp) {
            HookUtils.log(TAG + ": trigger ignored, range page is up");
            return;
        }
        final List<String> cands = foregroundCandidates(ctx);
        // Only the two real detectors (top task / resumed events) are trusted for the
        // "already configured" lookup. L3 is the legacy daily-bucket heuristic that used to
        // poison the map with system packages; consulting it here would let a stale bogus
        // key hijack the resolution.
        int trusted = Math.min(cands.size(), 2);
        for (int i = 0; i < trusted; i++) {
            String p = cands.get(i);
            int s = getStrategy(ctx, p);
            if (s >= 0) {
                HookUtils.log(TAG + ": perform pkg=" + p + " strategy=" + s);
                perform(ctx, p, next, s);
                return;
            }
        }
        String target = firstNonOverlay(cands);
        if (target == null) {
            // Nothing sensible to key a prompt on. Keep the legacy behaviour and do NOT
            // prompt: a prompt without a real target poisons the map with a bogus key.
            HookUtils.log(TAG + ": no usable foreground candidate, fallback horizontal");
            perform(ctx, null, next, STRATEGY_HORIZONTAL);
            return;
        }
        HookUtils.log(TAG + ": prompt pkg=" + target + " candidates=" + cands);
        showPrompt(ctx, target, next);
    }

    private static String firstNonOverlay(List<String> cands) {
        for (String p : cands) {
            if (!isOverlayHost(p)) return p;
        }
        return null;
    }

    // ==================================================================
    // Runtime chooser (one-time, per app)
    // ==================================================================
    private static void showPrompt(final Context ctx, final String pkg, final boolean next) {
        synchronized (sPrompting) {
            if (!sPrompting.add(pkg)) return; // already prompting for this app
        }
        sMain.post(new Runnable() {
            @Override public void run() {
                try {
                    showChoice(ctx, pkg,
                            "选择翻页触发方式",
                            "首次在该应用触发「上一页 / 下一页」。选择一种模拟方式，之后自动沿用。",
                            -1, false,
                            new ChoiceListener() {
                                @Override public void onPick(int strategy) {
                                    try {
                                        if (strategy < 0) return;
                                        setStrategy(ctx, pkg, strategy);
                                        HookUtils.log(TAG + ": prompt choice " + pkg + " -> " + strategy);
                                        // 4.8.9: picking a 模拟 strategy asks ONE follow-up question
                                        // (「用默认范围」/「自定义滑动范围」) instead of running the
                                        // default silently. Rationale: this chooser is the only
                                        // moment the user is looking at this app's page-turn
                                        // settings, and the default ratio path is exactly what
                                        // fails in feeds whose scrollable column starts elsewhere
                                        // — with no route to the recorder from here, the only way
                                        // to calibrate was to leave the app and dig through the
                                        // device center. The follow-up is still NOT the recorder:
                                        // the full-screen surface opens only on the explicit
                                        // 自定义滑动范围 tap (or from the device center).
                                        //
                                        // KeyEvent strategies never ask: there is no range.
                                        if (isSwipeStrategy(strategy)
                                                && getCalibration(ctx, pkg, next) == null) {
                                            // showChoice already dismissed and ran its onDismiss (which
                                            // released sPrompting); hold the app again so a pen
                                            // trigger cannot slip in behind the second page.
                                            synchronized (sPrompting) { sPrompting.add(pkg); }
                                            showRangePage(ctx, pkg, next, strategy);
                                            return;
                                        }
                                        perform(ctx, pkg, next, strategy);
                                    } catch (Throwable th) {
                                        HookUtils.log(TAG + ": prompt pick: " + th);
                                    }
                                }
                            },
                            new Runnable() {
                                @Override public void run() {
                                    synchronized (sPrompting) { sPrompting.remove(pkg); }
                                }
                            });
                } catch (Throwable th) {
                    synchronized (sPrompting) { sPrompting.remove(pkg); }
                    HookUtils.log(TAG + ": showPrompt: " + th);
                }
            }
        });
    }

    // ==================================================================
    // Device-center management dialog (review / change / clear per app)
    // ==================================================================
    /**
     * Follow-up to the strategy chooser for 模拟滑动: 「用默认范围」 or 「自定义滑动范围」.
     *
     * <p>Deliberately a <b>small card</b>, not {@link SwipeCalibrateOverlay}. 4.8.6 popped the
     * full-screen recorder at this point and it was rejected as an interruption of an ordinary
     * page turn; 4.8.7 over-corrected by removing the follow-up entirely, which left the recorder
     * reachable only from the device center — reported by the user on 2026-09-23 right after
     * picking 垂直模拟 in a fresh app ("不会有自定义的弹窗弹出，也无法设置自定义滚动轨迹"). This
     * page keeps calibration reachable at the moment of choosing while the full-screen surface
     * stays strictly opt-in.</p>
     *
     * <p>Backing out runs the default range, per the original requirement ("确认或直接返回，就按照
     * 默认的滑动范围执行") — the page turn was already requested, this page only asks <i>how</i>.
     * The subtitle states it, so the back key is not a surprise. 取消, by contrast, does nothing.</p>
     */
    private static void showRangePage(final Context ctx, final String pkg, final boolean next,
                                      final int strategy) {
        if (sRangePageUp) return;
        sRangePageUp = true;
        try {
            final Palette p = Palette.of(ctx);
            final Dialog dlg = new Dialog(ctx);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

            final boolean vertical = strategy != STRATEGY_HORIZONTAL;
            final String mode = vertical ? (next ? "上滑" : "下滑") : (next ? "左滑" : "右滑");

            LinearLayout root = card(ctx, p);
            root.addView(headerView(ctx, p, pkg,
                    "「" + (next ? "下一页" : "上一页") + "」的滑动范围",
                    label(ctx, strategy) + " · 起点用内置的，还是自己滑一次？"));

            root.addView(optionRow(ctx, p, "用默认范围",
                    "内置比例路径 · 直接返回也是这个", true,
                    new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            perform(ctx, pkg, next, strategy);
                        }
                    }));

            root.addView(optionRow(ctx, p, "自定义滑动范围",
                    "按你习惯的方式「" + mode + "」一次，之后该应用照此轨迹执行", false,
                    new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            // Hand the job to system_server rather than opening the recorder
                            // right here. The recorder is a system-uid facility: it needs a
                            // gesture monitor (uid must be SYSTEM_UID or SHELL_UID) and it
                            // launches the target app. This panel is com.oplus.ipemanager, a
                            // normal app uid, so a recorder started here could never see the
                            // touch stream and would silently fall back to blocking capture.
                            requestCalibration(ctx, pkg, next);
                        }
                    }));

            root.addView(divider(ctx, p));
            root.addView(actionButton(ctx, p, "取消", new Runnable() {
                @Override public void run() { dlg.dismiss(); }
            }));

            // Back = 用默认范围. Dialog.dispatchKeyEvent consults the OnKeyListener before its own
            // back-to-cancel path, so the two cannot both fire.
            dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override public boolean onKey(DialogInterface d, int code, KeyEvent e) {
                    if (code != KeyEvent.KEYCODE_BACK) return false;
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        dlg.dismiss();
                        perform(ctx, pkg, next, strategy);
                    }
                    return true;
                }
            });
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) {
                    sRangePageUp = false;
                    synchronized (sPrompting) { sPrompting.remove(pkg); }
                    HookUtils.log(TAG + ": range page closed pkg=" + pkg);
                }
            });

            dlg.setContentView(root);
            styleAndShow(ctx, dlg);
            HookUtils.log(TAG + ": range page pkg=" + pkg + " dir=" + (next ? DIR_NEXT : DIR_PREV)
                    + " strategy=" + strategy);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": showRangePage: " + th);
            sRangePageUp = false;
            synchronized (sPrompting) { sPrompting.remove(pkg); }
            perform(ctx, pkg, next, strategy);
        }
    }

    public static void showConfigDialog(final Context ctx) {
        sMain.post(new Runnable() {
            @Override public void run() {
                try {
                    final Map<String, Integer> cfg = allConfigured(ctx);
                    final Palette p = Palette.of(ctx);
                    final Dialog dlg = new Dialog(ctx);
                    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

                    LinearLayout root = card(ctx, p);
                    root.addView(titleView(ctx, p, "翻页触发方式"));
                    root.addView(subtitleView(ctx, p,
                            cfg.isEmpty() ? "尚未配置任何应用"
                                    : "已为 " + cfg.size() + " 个应用单独设置"));

                    if (cfg.isEmpty()) {
                        TextView hint = new TextView(ctx);
                        hint.setText("首次在某个应用中触发「上一页 / 下一页」时会自动弹出选择，"
                                + "选定后即可在此查看与修改。\n\n"
                                + "若模拟滑动在该应用里没反应，说明默认滑动的起点不在它的可滚动区域内 ——"
                                + "在该应用里触发「上一页 / 下一页」、选一种「模拟」时点「自定义滑动范围」，"
                                + "按你的习惯滑一次即可（轨迹必须在该应用内录制，这里只负责选择方式）。");
                        hint.setTextSize(13);
                        hint.setTextColor(p.body);
                        hint.setLineSpacing(dp(ctx, 4), 1f);
                        hint.setPadding(0, dp(ctx, 4), 0, dp(ctx, 10));
                        root.addView(hint);
                    } else {
                        ScrollView sv = new ScrollView(ctx);
                        sv.setFillViewport(true);
                        LinearLayout list = new LinearLayout(ctx);
                        list.setOrientation(LinearLayout.VERTICAL);
                        boolean first = true;
                        for (final Map.Entry<String, Integer> e : cfg.entrySet()) {
                            if (!first) list.addView(divider(ctx, p));
                            first = false;
                            final String pkg = e.getKey();
                            list.addView(configRow(ctx, p, pkg, e.getValue(),
                                    new Runnable() {
                                        @Override public void run() {
                                            dlg.dismiss();
                                            showChangeDialog(ctx, pkg);
                                        }
                                    }));
                        }
                        sv.addView(list, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        slp.topMargin = dp(ctx, 4);
                        if (cfg.size() > 5) {
                            slp.height = dp(ctx, 352);
                        }
                        root.addView(sv, slp);
                    }

                    root.addView(divider(ctx, p));
                    root.addView(actionButton(ctx, p, "关闭", new Runnable() {
                        @Override public void run() { dlg.dismiss(); }
                    }));

                    dlg.setContentView(root);
                    styleAndShow(ctx, dlg);
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": showConfigDialog: " + th);
                }
            }
        });
    }

    private static String calibrationDetail(Context ctx, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (boolean next : new boolean[]{true, false}) {
            float[][] pts = getCalibration(ctx, pkg, next);
            if (pts == null) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append("「").append(next ? "下一页" : "上一页").append("」")
              .append(describeCalibration(pts));
        }
        return sb.toString();
    }

    private static boolean anyCalibration(Context ctx, String pkg) {
        return hasCalibration(ctx, pkg, true) || hasCalibration(ctx, pkg, false);
    }

    /**
     * Per-app editor. Four strategy rows — the same four the first-trigger chooser shows, in the
     * same order with the same descriptions — plus a clear action.
     *
     * <p><b>No calibration rows here (2026-09-23, by request).</b> Re-recording a trajectory is an
     * in-app gesture: it needs the target app underneath (the recorder passes touches through) and
     * the user has to be looking at the thing they are aiming at. Offering it from the device
     * center produced exactly the failure the recorder exists to fix — aiming at a trajectory from
     * inside a different app's panel. So the device center only <i>selects a strategy</i>; the
     * recorder is reached exclusively from the first-trigger flow inside the app
     * ({@link #showRangePage} → 「自定义滑动范围」).</p>
     *
     * <p><b>Locked rows.</b> Once the app owns a custom trajectory, 水平模拟 / 垂直模拟 are
     * disabled: a recorded path was captured on one axis, and switching the axis would replay it
     * with the wrong start point and the wrong direction — the class of failure this whole feature
     * exists to remove. The lock is explicit and states its way out: clear the app's config, then
     * re-trigger inside the app and pick 「自定义滑动范围」. The KeyEvent strategies stay
     * selectable — they inject no gesture, so no trajectory is involved.</p>
     */
    private static void showChangeDialog(final Context ctx, final String pkg) {
        try {
            final int cur = getStrategy(ctx, pkg);
            final boolean locked = anyCalibration(ctx, pkg);
            final Palette p = Palette.of(ctx);
            final Dialog dlg = new Dialog(ctx);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = card(ctx, p);
            root.addView(headerView(ctx, p, pkg, "翻页触发方式",
                    locked ? "当前：" + label(ctx, cur) + " · 已自定义滑动轨迹"
                           : "为该应用选择翻页模拟方式："));
            root.addView(strategyRows(ctx, p, pkg, cur, dlg, locked));

            if (locked) {
                root.addView(divider(ctx, p));
                root.addView(lockedNotice(ctx, p, pkg));
            }

            root.addView(divider(ctx, p));
            root.addView(optionRow(ctx, p, "清除该应用配置",
                    locked ? "清空后回到该应用重新触发，即可重选方式并重录轨迹"
                           : "下次触发时重新询问", false,
                    new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            clearCalibration(ctx, pkg, true);
                            clearCalibration(ctx, pkg, false);
                            setStrategy(ctx, pkg, PICK_CLEAR);
                            reopenConfigSoon(ctx);
                        }
                    }));
            root.addView(actionButton(ctx, p, "取消", new Runnable() {
                @Override public void run() { dlg.dismiss(); }
            }));

            dlg.setContentView(root);
            styleAndShow(ctx, dlg);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": showChangeDialog: " + th);
        }
    }

    /** Explains the lock and gives the exact way out. Body text, no affordance of its own. */
    private static View lockedNotice(Context ctx, Palette p, String pkg) {
        TextView t = new TextView(ctx);
        t.setText("已记录自定义轨迹：" + calibrationDetail(ctx, pkg) + "。\n"
                + "「水平模拟 / 垂直模拟」暂不可选 —— 改轴会让这条轨迹失效。\n"
                + "如需重设：先点下方「清除该应用配置」，再回到该应用触发「上一页 / 下一页」，"
                + "选模拟方式时点「自定义滑动范围」重录一次。");
        t.setTextSize(13);
        t.setTextColor(p.body);
        t.setLineSpacing(dp(ctx, 4), 1f);
        t.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 8));
        return t;
    }

    /** The four strategy rows, reused by both the one-time chooser and the editor. */
    private static View strategyRows(Context ctx, Palette p, final String pkg, int cur,
                                     final Dialog dlg, boolean locked) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < OPTION_NAMES.length; i++) {
            final int idx = i;
            // Locked = a trajectory exists and this row would change the axis it was recorded on.
            boolean enabled = !(locked && isSwipeStrategy(i));
            if (i > 0) box.addView(thinSpace(ctx));
            box.addView(optionRow(ctx, p, OPTION_NAMES[i], OPTION_DESCS[i], i == cur, enabled,
                    new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            setStrategy(ctx, pkg, idx);
                            reopenConfigSoon(ctx);
                        }
                    }));
        }
        return box;
    }

    /**
     * The write may travel to system_server over a broadcast, so give it a moment before
     * re-reading -- otherwise the reopened list would still show the previous value.
     *
     * <p>Guarded against a dead host: reopening a dialog from a {@code PencilPanelActivity} that
     * is already finishing is what produced
     * {@code E WindowManager: android.view.WindowLeaked: Activity …PencilPanelActivity has leaked
     * window … at PageTurnConfig.styleAndShow(PageTurnConfig.java:1016)} (device log, 2026-09-23
     * 17:27:41). The host can go away between the tap and this 250 ms timer — the user closes the
     * panel right after a change — so check before showing, not after.</p>
     */
    private static void reopenConfigSoon(final Context ctx) {
        sMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (hostGone(ctx)) {
                    HookUtils.log(TAG + ": host activity gone, skip reopen");
                    return;
                }
                showConfigDialog(ctx);
            }
        }, 250);
    }

    /** True when the activity we were opened from is finishing or already destroyed. */
    private static boolean hostGone(Context ctx) {
        try {
            Activity a = activityOf(ctx);
            return a != null && (a.isFinishing() || a.isDestroyed());
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * Ties a dialog's lifetime to the OEM activity whose context it was built with.
     *
     * <p>Panel dialogs hang off {@code PencilPanelActivity}, an activity we do not own. Nothing in
     * that activity dismisses our windows on its way out, so if it is destroyed while one of our
     * dialogs is up the window is leaked ({@code WindowLeaked}, seen in the field). When the
     * context is system_server's there is no activity to outlive and this is a no-op.</p>
     */
    private static void bindToHostLifetime(Context ctx, Dialog dlg) {
        try {
            final Activity host = activityOf(ctx);
            if (host == null) return;
            final Application app = host.getApplication();
            if (app == null) return;
            app.registerActivityLifecycleCallbacks(new HostLifetime(app, host, dlg));
        } catch (Throwable th) {
            HookUtils.log(TAG + ": bindToHostLifetime: " + th);
        }
    }

    /** One per panel dialog; drops itself on the host's destroy or the dialog's close. */
    private static final class HostLifetime implements Application.ActivityLifecycleCallbacks {
        private final Application app;
        private final Activity host;
        private final Dialog dlg;
        private boolean released;

        HostLifetime(Application app, Activity host, Dialog dlg) {
            this.app = app;
            this.host = host;
            this.dlg = dlg;
        }

        private void release() {
            if (released) return;
            released = true;
            try {
                app.unregisterActivityLifecycleCallbacks(this);
            } catch (Throwable ignored) { }
        }

        /** The dialog closed on its own: stop watching, nothing left to dismiss. */
        private void reapIfClosed() {
            try {
                if (!dlg.isShowing()) release();
            } catch (Throwable ignored) { }
        }

        @Override public void onActivityCreated(Activity a, Bundle b) { reapIfClosed(); }
        @Override public void onActivityStarted(Activity a) { reapIfClosed(); }
        @Override public void onActivityResumed(Activity a) { reapIfClosed(); }
        @Override public void onActivityPaused(Activity a) { reapIfClosed(); }
        @Override public void onActivityStopped(Activity a) { reapIfClosed(); }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { reapIfClosed(); }

        @Override public void onActivityDestroyed(Activity a) {
            if (a != host) { reapIfClosed(); return; }
            release();
            try {
                if (dlg.isShowing()) dlg.dismiss();
                HookUtils.log(TAG + ": host destroyed, closed our dialog");
            } catch (Throwable ignored) { }
        }
    }

    // ==================================================================
    // Custom chooser dialog (self-drawn; immune to host theme)
    // ==================================================================
    private interface ChoiceListener {
        /** @param strategy 0..3, or -1 to clear this app's config. */
        void onPick(int strategy);
    }

    private static void showChoice(Context ctx, String pkg, String titleText, String subtitleText,
                                   int selected, boolean allowClear,
                                   final ChoiceListener listener, final Runnable onDismiss) {
        final Palette p = Palette.of(ctx);
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = card(ctx, p);

        if (pkg != null) {
            root.addView(headerView(ctx, p, pkg, titleText, subtitleText));
        } else {
            root.addView(titleView(ctx, p, titleText));
            root.addView(subtitleView(ctx, p, subtitleText));
        }

        for (int i = 0; i < OPTION_NAMES.length; i++) {
            final int idx = i;
            if (i > 0) root.addView(thinSpace(ctx));
            root.addView(optionRow(ctx, p, OPTION_NAMES[i], OPTION_DESCS[i], i == selected,
                    new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            if (listener != null) listener.onPick(idx);
                        }
                    }));
        }

        if (allowClear) {
            root.addView(divider(ctx, p));
            root.addView(optionRow(ctx, p, "清除该应用配置", "下次触发时重新询问", false,
                    new Runnable() {
                        @Override public void run() {
                            dlg.dismiss();
                            if (listener != null) listener.onPick(PICK_CLEAR);
                        }
                    }));
        }

        root.addView(divider(ctx, p));
        root.addView(actionButton(ctx, p, "取消", new Runnable() {
            @Override public void run() { dlg.dismiss(); }
        }));

        dlg.setContentView(root);
        if (onDismiss != null) {
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) { onDismiss.run(); }
            });
        }
        styleAndShow(ctx, dlg);
    }

    private static void styleAndShow(Context ctx, Dialog dlg) {
        try {
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                if (activityOf(ctx) == null) {
                    // No Activity token (system_server): must become an overlay window.
                    try {
                        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    } catch (Throwable ignored) { }
                }
                int screen = ctx.getResources().getDisplayMetrics().widthPixels;
                int width = Math.min((int) (screen * 0.90f), dp(ctx, 420));
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                try {
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.dimAmount = 0.55f;
                    w.setAttributes(lp);
                    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": styleAndShow: " + th);
        }
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(true);
        bindToHostLifetime(ctx, dlg);
        try {
            dlg.show();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": dialog.show: " + th);
        }
    }

    // ==================================================================
    // View builders
    // ==================================================================
    static LinearLayout card(Context ctx, Palette p) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.card);
        bg.setCornerRadius(dp(ctx, 28));
        c.setBackground(bg);
        c.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 8));
        return c;
    }

    /**
     * ColorOS bottom-sheet grabber (36x4dp pill). Only visual; the sheet itself is not
     * drag-dismissable because it is an overlay window we own, but the affordance is what
     * makes the card read as a ColorOS sheet rather than a floating box.
     */
    static View sheetHandle(Context ctx, Palette p) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, 0, 0, dp(ctx, 10));
        View bar = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setColor(p.handle);
        g.setCornerRadius(dp(ctx, 2));
        bar.setBackground(g);
        wrap.addView(bar, new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)));
        return wrap;
    }

    private static View headerView(Context ctx, Palette p, String pkg, String title, String sub) {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, 0, 0, dp(ctx, 12));

        ImageView ic = new ImageView(ctx);
        Drawable dr = appIcon(ctx, pkg);
        if (dr != null) ic.setImageDrawable(dr);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42));
        ilp.rightMargin = dp(ctx, 14);
        h.addView(ic, ilp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(titleView(ctx, p, title));
        TextView s = subtitleView(ctx, p, sub);
        s.setPadding(0, dp(ctx, 4), 0, 0);
        texts.addView(s);
        h.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }

    static TextView titleView(Context ctx, Palette p, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(p.title);
        return t;
    }

    static TextView subtitleView(Context ctx, Palette p, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(p.body);
        t.setLineSpacing(dp(ctx, 3), 1f);
        t.setPadding(0, dp(ctx, 6), 0, dp(ctx, 12));
        return t;
    }

    static View optionRow(Context ctx, Palette p, String name, String desc,
                                  boolean selected, final Runnable onClick) {
        return optionRow(ctx, p, name, desc, selected, true, onClick);
    }

    /**
     * Selectable row with an explicit {@code enabled} state.
     *
     * <p>Disabled rows are used by the device-center editor to lock 水平模拟 / 垂直模拟 once a
     * custom trajectory exists for the app: switching the simulation axis would replay a path the
     * user recorded for the other axis. A disabled row keeps its radio indicator (a locked row can
     * still be the <i>current</i> choice, and hiding that would be worse) but loses every tappable
     * affordance — no ripple, no click listener, no focus.</p>
     */
    static View optionRow(Context ctx, Palette p, String name, String desc,
                                  boolean selected, boolean enabled, final Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 62));
        row.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        if (enabled) row.setBackground(pressable(ctx, p.ripple, 16));
        row.setClickable(enabled);
        row.setFocusable(enabled);

        int nameColor = enabled ? p.title : p.muted;
        int descColor = enabled ? p.body : p.muted;

        View dot = new View(ctx);
        dot.setBackground(radioDot(ctx, selected,
                enabled ? p.accent : p.muted,
                enabled ? p.idle : p.muted));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        dlp.rightMargin = dp(ctx, 14);
        row.addView(dot, dlp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(ctx);
        n.setText(name);
        n.setTextSize(16);
        n.setTextColor(nameColor);
        n.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        texts.addView(n);
        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(12);
            d.setTextColor(descColor);
            d.setPadding(0, dp(ctx, 3), 0, 0);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (enabled) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (onClick != null) onClick.run(); }
            });
        }
        return row;
    }

    private static View configRow(Context ctx, Palette p, final String pkg, int strategy,
                                  final Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 58));
        row.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10));
        row.setBackground(pressable(ctx, p.ripple, 14));
        row.setClickable(true);
        row.setFocusable(true);

        ImageView ic = new ImageView(ctx);
        Drawable dr = appIcon(ctx, pkg);
        if (dr != null) ic.setImageDrawable(dr);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34));
        ilp.rightMargin = dp(ctx, 12);
        row.addView(ic, ilp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(ctx);
        n.setText(appLabel(ctx, pkg));
        n.setTextSize(15);
        n.setTextColor(p.title);
        texts.addView(n);
        TextView s = new TextView(ctx);
        s.setText(strategySummary(ctx, pkg, strategy));
        s.setTextSize(12);
        s.setTextColor(p.body);
        s.setPadding(0, dp(ctx, 2), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(ctx);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(p.body);
        row.addView(chevron);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (onClick != null) onClick.run(); }
        });
        return row;
    }

    static View actionButton(Context ctx, Palette p, String text, final Runnable onClick) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(p.accent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(ctx, 15), 0, dp(ctx, 15));
        b.setBackground(pressable(ctx, p.ripple, 14));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (onClick != null) onClick.run(); }
        });
        return b;
    }

    static View divider(Context ctx, Palette p) {
        View v = new View(ctx);
        v.setBackgroundColor(p.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.6f)));
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 6);
        v.setLayoutParams(lp);
        return v;
    }

    private static View thinSpace(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)));
        return v;
    }

    private static Drawable radioDot(Context ctx, boolean selected, int accent, int idleColor) {
        if (selected) {
            GradientDrawable outer = new GradientDrawable();
            outer.setShape(GradientDrawable.OVAL);
            outer.setColor(accent);
            GradientDrawable inner = new GradientDrawable();
            inner.setShape(GradientDrawable.OVAL);
            inner.setColor(Color.WHITE);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{outer, inner});
            int inset = dp(ctx, 6);
            ld.setLayerInset(1, inset, inset, inset, inset);
            return ld;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(ctx, 2), idleColor);
        return g;
    }

    /**
     * ColorOS-style press feedback: a bounded ripple clipped to the row's own rounded
     * rectangle (ColorOS never paints a full square highlight on a list row). RippleDrawable
     * is available on every API this module runs on, but the state-list fallback is kept so a
     * factory failure can never leave a row without any visual response.
     */
    static Drawable pressable(Context ctx, int rippleColor, float radiusDp) {
        float r = dp(ctx, radiusDp);
        GradientDrawable content = new GradientDrawable();
        content.setColor(Color.TRANSPARENT);
        content.setCornerRadius(r);
        try {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(r);
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), content, mask);
        } catch (Throwable th) {
            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(rippleColor);
            pressed.setCornerRadius(r);
            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{android.R.attr.state_focused}, pressed);
            sld.addState(new int[0], content);
            return sld;
        }
    }

    // ==================================================================
    // Misc helpers
    // ==================================================================
    static String appLabel(Context ctx, String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            return cs != null ? cs.toString() : pkg;
        } catch (Throwable th) {
            return pkg;
        }
    }

    static Drawable appIcon(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable th) {
            return null;
        }
    }

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    /** Night / light aware palette, ColorOS-flavoured (green accent). */
    static final class Palette {
        final int card, title, body, ripple, divider, accent, idle, handle, warn, muted;

        private Palette(boolean night) {
            if (night) {
                card    = 0xFF2F3033;
                title   = 0xFFF2F3F5;
                body    = 0xFF9B9FA8;
                ripple  = 0x1FFFFFFF;
                divider = 0x14FFFFFF;
                accent  = 0xFF43D17F;
                idle    = 0x40FFFFFF;
                handle  = 0x33FFFFFF;
                // Reserved for "this is not the app you think it is" (see the recorder's chip
                // and hint): deliberately not the accent, that one reads as a success.
                warn    = 0xFFF08A5D;
                // Locked/disabled rows: still legible, clearly not tappable.
                muted   = 0x4DFFFFFF;
            } else {
                card    = 0xFFFFFFFF;
                title   = 0xFF191A1F;
                body    = 0xFF878B94;
                ripple  = 0x0F000000;
                divider = 0x12000000;
                accent  = 0xFF00A863;
                idle    = 0x30000000;
                handle  = 0x33000000;
                warn    = 0xFFC25A16;
                muted   = 0x42000000;
            }
        }

        static Palette of(Context ctx) {
            boolean night = (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return new Palette(night);
        }
    }
}
