package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.app.ActivityManager;
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
    public static void perform(Context ctx, boolean next, int strategy) {
        try {
            switch (strategy) {
                case STRATEGY_VERTICAL:
                    StylusActionDispatcher.injectVerticalSwipe(ctx, next);
                    break;
                case STRATEGY_KEY_UD:
                    StylusActionDispatcher.injectKey(next ? KeyEvent.KEYCODE_PAGE_DOWN : KeyEvent.KEYCODE_PAGE_UP);
                    break;
                case STRATEGY_KEY_LR:
                    StylusActionDispatcher.injectKey(next ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                    break;
                case STRATEGY_HORIZONTAL:
                default:
                    StylusActionDispatcher.injectSwipe(ctx, next);
                    break;
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": perform strategy=" + strategy + ": " + th);
        }
    }

    /** Called from StylusActionDispatcher.pageTurn: resolve strategy, prompt if needed. */
    public static void performWithPrompt(Context ctx, boolean next) {
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
                perform(ctx, next, s);
                return;
            }
        }
        String target = firstNonOverlay(cands);
        if (target == null) {
            // Nothing sensible to key a prompt on. Keep the legacy behaviour and do NOT
            // prompt: a prompt without a real target poisons the map with a bogus key.
            HookUtils.log(TAG + ": no usable foreground candidate, fallback horizontal");
            perform(ctx, next, STRATEGY_HORIZONTAL);
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
                                        if (strategy >= 0) {
                                            setStrategy(ctx, pkg, strategy);
                                            perform(ctx, next, strategy);
                                            HookUtils.log(TAG + ": prompt choice " + pkg + " -> " + strategy);
                                        }
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
                                + "选定后即可在此查看与修改。");
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

    private static void showChangeDialog(final Context ctx, final String pkg) {
        try {
            int cur = getStrategy(ctx, pkg);
            showChoice(ctx, pkg,
                    "翻页触发方式",
                    "为该应用选择翻页模拟方式：",
                    cur, true,
                    new ChoiceListener() {
                        @Override public void onPick(int strategy) {
                            setStrategy(ctx, pkg, strategy);
                            // The write travels to system_server over a broadcast; give it a
                            // moment before re-reading so the reopened list shows the new value.
                            sMain.postDelayed(new Runnable() {
                                @Override public void run() { showConfigDialog(ctx); }
                            }, 250);
                        }
                    },
                    null);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": showChangeDialog: " + th);
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
        try {
            dlg.show();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": dialog.show: " + th);
        }
    }

    // ==================================================================
    // View builders
    // ==================================================================
    private static LinearLayout card(Context ctx, Palette p) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.card);
        bg.setCornerRadius(dp(ctx, 26));
        c.setBackground(bg);
        c.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 8));
        return c;
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

    private static TextView titleView(Context ctx, Palette p, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(p.title);
        return t;
    }

    private static TextView subtitleView(Context ctx, Palette p, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(p.body);
        t.setLineSpacing(dp(ctx, 3), 1f);
        t.setPadding(0, dp(ctx, 6), 0, dp(ctx, 12));
        return t;
    }

    private static View optionRow(Context ctx, Palette p, String name, String desc,
                                  boolean selected, final Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 60));
        row.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        row.setBackground(pressable(ctx, p.press, 16));
        row.setClickable(true);
        row.setFocusable(true);

        View dot = new View(ctx);
        dot.setBackground(radioDot(ctx, selected, p.accent, p.idle));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        dlp.rightMargin = dp(ctx, 14);
        row.addView(dot, dlp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(ctx);
        n.setText(name);
        n.setTextSize(15);
        n.setTextColor(p.title);
        n.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        texts.addView(n);
        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(12);
            d.setTextColor(p.body);
            d.setPadding(0, dp(ctx, 3), 0, 0);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (onClick != null) onClick.run(); }
        });
        return row;
    }

    private static View configRow(Context ctx, Palette p, final String pkg, int strategy,
                                  final Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 58));
        row.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10));
        row.setBackground(pressable(ctx, p.press, 14));
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
        s.setText(strategy < 0 ? "未配置" : label(ctx, strategy));
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

    private static View actionButton(Context ctx, Palette p, String text, final Runnable onClick) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(p.accent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(ctx, 14), 0, dp(ctx, 14));
        b.setBackground(pressable(ctx, p.press, 14));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (onClick != null) onClick.run(); }
        });
        return b;
    }

    private static View divider(Context ctx, Palette p) {
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

    private static Drawable pressable(Context ctx, int pressColor, float radiusDp) {
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressColor);
        pressed.setCornerRadius(dp(ctx, radiusDp));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setCornerRadius(dp(ctx, radiusDp));
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{android.R.attr.state_focused}, pressed);
        sld.addState(new int[0], normal);
        return sld;
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

    private static Drawable appIcon(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable th) {
            return null;
        }
    }

    private static int dp(Context ctx, float v) {
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
    private static final class Palette {
        final int card, title, body, press, divider, accent, idle;

        private Palette(boolean night) {
            if (night) {
                card    = 0xFF2A2A2E;
                title   = 0xFFF2F2F5;
                body    = 0xFF9DA0A8;
                press   = 0x1FFFFFFF;
                divider = 0x1AFFFFFF;
                accent  = 0xFF43D17F;
                idle    = 0x40FFFFFF;
            } else {
                card    = 0xFFFFFFFF;
                title   = 0xFF191A1F;
                body    = 0xFF7C818C;
                press   = 0x0D000000;
                divider = 0x12000000;
                accent  = 0xFF00A863;
                idle    = 0x30000000;
            }
        }

        static Palette of(Context ctx) {
            boolean night = (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return new Palette(night);
        }
    }
}
