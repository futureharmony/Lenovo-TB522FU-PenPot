package com.aclaniakea.colorosporttuning;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Swipe-range recorder for the 模拟滑动 page-turn strategies.
 *
 * <p>Why this exists: the built-in swipe starts at a fixed ratio of the screen. In several apps
 * that point is not inside the scrollable content at all (the video area above a comment bar, the
 * scrollable column of a split layout, a carousel under a fixed header), so the app just ignores
 * the gesture — which is indistinguishable from "the feature does not work". The fix cannot be
 * guessed from outside, so the user records one real swipe per direction.</p>
 *
 * <p><b>Never popped implicitly.</b> This surface is opened only by an explicit 「自定义范围」
 * action — either the second page of the first-trigger chooser, or device center → 翻页功能触发方式
 * → app → 自定义「下一页 / 上一页」滑动范围. Picking a strategy runs the stored/default range
 * straight away, so an ordinary page turn is never interrupted by a full-screen window.</p>
 *
 * <p><b>Touch pass-through (2026-09-23, the point of this revision).</b> While recording, the
 * full-screen window carries {@code FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE |
 * FLAG_NOT_TOUCH_MODAL} and no dim: the gesture travels to the app underneath exactly as it would
 * have without us, so the user calibrates against the app's <em>real</em> reaction instead of
 * aiming blind at a frozen screen. The trajectory is taken from a read-only copy of the event
 * stream ({@link TouchSpy}, a gesture monitor) rather than from a consumed gesture. Two
 * consequences worth knowing:</p>
 * <ul>
 *   <li>the recorded swipe has already acted on the app, so finishing the recording must
 *       <b>not</b> replay it — {@link Listener#onDone()} is deliberately not wired to a page turn
 *       on this path;</li>
 *   <li>if no monitor channel can be acquired, the surface falls back to the old blocking capture
 *       (touchable window, app frozen underneath) instead of becoming unusable, and says so in
 *       the on-canvas hint.</li>
 * </ul>
 *
 * <p><b>Foreground identity is on screen, always.</b> A separate small window in the top-left
 * corner shows which app is actually underneath (icon, label, package) and turns into a warning
 * when it is not the app being calibrated — because a trajectory recorded over the wrong app is
 * worse than no trajectory at all. Tapping that chip cancels the recording. It has to be a second
 * window: a window cannot be partly transparent to touch and partly interactive by flags alone,
 * and the chip must stay tappable while everything else passes through.</p>
 *
 * <p>Window notes: added through {@code WindowManager} as
 * {@code TYPE_APPLICATION_OVERLAY} from {@code system_server} (same technique as
 * {@link HandwrittenNoteOverlay} / {@link LassoSelectOverlay}) so it covers any app without a
 * {@code SYSTEM_ALERT_WINDOW} grant. Coordinates are captured in display space
 * ({@code FLAG_LAYOUT_IN_SCREEN} puts the view origin at the display origin) and normalised with
 * {@link PageTurnConfig#screenSize}, the exact space the replay path multiplies back out, so a
 * recorded trajectory can never be interpreted in a different coordinate system.</p>
 *
 * <p><b>Why it lives in system_server, and what that costs.</b> Moving it into the module's own
 * process would buy crash isolation only, at the price of a new component, a user-granted overlay
 * app-op the ROM may revoke, and IPC for a trajectory system_server has to persist anyway — while
 * input injection itself must stay in system_server regardless. The price of staying here is
 * explicit: {@code ViewRootImpl} runs on whichever looper calls {@code addView}, which is
 * system_server's main thread, so traversal, {@code onDraw}, touch dispatch and every click
 * listener of this surface execute there too. Containment, all mandatory:</p>
 * <ul>
 *   <li>every interactive entry point is a try/catch firewall ({@code dispatchTouchEvent},
 *       {@code dispatchKeyEvent}, {@code onDraw}, the frame loop, the heartbeat, the listener
 *       callbacks) — an escaped throwable is not "the overlay broke", it is the framework
 *       restarting;</li>
 *   <li>the frame loop is bounded ({@link #MAX_ANIM_CYCLES}) and live repaints are throttled, so
 *       an open surface does not sit on the main looper at 60 Hz;</li>
 *   <li>{@link #WATCHDOG_MS} plus screen-off detection guarantees the window is removed even if
 *       every user-facing exit is missed — without it a stuck surface swallows, or at minimum
 *       covers, the device;</li>
 *   <li>the spy channel is released with the window: an armed monitor that outlives the recorder
 *       keeps a dead client registered in the dispatcher.</li>
 * </ul>
 */
public final class SwipeCalibrateOverlay {

    private static final String TAG = "PenSwipeCalib";

    /** Called after the overlay is gone, so the caller can run the page turn it owed. */
    public interface Listener {
        void onDone();
    }

    private static final int PHASE_RECORD = 0;
    private static final int PHASE_RESULT = 1;

    private static final int MAX_POINTS = 32;
    private static final int MAX_RAW_POINTS = 400;
    private static final long MIN_DURATION_MS = 60L;
    private static final long MAX_DURATION_MS = 4000L;
    private static final float MIN_TRAVEL = 0.12f;
    private static final float MIN_STEP_FRAC = 0.004f;
    private static final long MIN_STEP_MS = 8L;
    private static final long FRAME_MS = 24L;
    private static final long PREVIEW_HOLD_MS = 650L;
    private static final long PREVIEW_GAP_MS = 220L;

    private static final String WAITING = "等待滑动…";

    /**
     * The result preview is a hint, not an ambient animation. After this many loops the frame
     * loop parks on the finished state, so a card left open stops consuming frames on the
     * looper it lives on (system_server's main thread — see the class comment).
     */
    private static final int MAX_ANIM_CYCLES = 4;

    /** Minimum spacing between live-trail repaints while recording (≈40 fps). */
    private static final long TRAIL_MIN_FRAME_MS = 24L;

    /** Housekeeping period: screen-off detection + idle watchdog + foreground re-check. */
    private static final long HEARTBEAT_MS = 1000L;

    /** How often the app chip re-reads the foreground package. */
    private static final long CHIP_POLL_MS = 2000L;

    /**
     * A full-screen overlay owned by system_server outlives everything else: the home key does
     * not remove it, the screen going off does not remove it, the app underneath dying does not
     * remove it. If it were ever left attached every touch on the device would land on it and the
     * tablet would look frozen. The watchdog is the guaranteed way out.
     */
    private static final long WATCHDOG_MS = 90_000L;

    private static SwipeCalibrateOverlay sCurrent;

    /** True while a calibration surface is on screen; the dispatch path must not fire. */
    public static boolean isVisible() {
        return sCurrent != null;
    }

    public static void show(Context ctx, String pkg, boolean next, Listener onDone) {
        if (sCurrent != null) {
            HookUtils.log(TAG + ": already showing, ignoring request for " + pkg);
            return;
        }
        SwipeCalibrateOverlay o = new SwipeCalibrateOverlay(ctx, pkg, next, onDone);
        sCurrent = o;
        try {
            o.attach();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": attach: " + th);
            // A half-attached surface must not stay registered: isVisible() gates the whole
            // dispatch path, so a stale sCurrent would silently disable page turning.
            o.detach();
            sCurrent = null;
            if (onDone != null) onDone.onDone();
        }
    }

    // ------------------------------------------------------------------

    private final String pkg;
    private final boolean next;
    private final Listener listener;
    /** Application context: this surface must not hold an Activity that may already be gone. */
    private final Context ui;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final boolean vertical;
    private final int sw;
    private final int sh;
    private final PageTurnConfig.Palette pal;

    private WindowManager wm;
    private PowerManager pm;
    private WindowManager.LayoutParams lp;
    private Surface root;
    private LinearLayout chip;
    private TrailView trail;
    private LinearLayout card;
    private TouchSpy spy;
    private boolean passThrough;
    private String chipPkg;
    private long chipPollRt;
    private boolean sizeLogged;

    private int phase = PHASE_RECORD;
    private float[][] shown;
    private boolean shownIsCustom;
    private long previewStart;
    private int animCycles;
    private volatile long lastInputRt;
    private long lastTrailRt;
    private String feedbackText;
    private boolean dismissed;

    /** Guards {@link #rec} and {@link #downTimeMs}: written by the spy thread, read by main. */
    private final Object recLock = new Object();
    private final List<float[]> rec = new ArrayList<>();
    private long downTimeMs;
    /** Spy-thread gesture state (only meaningful in pass-through mode). */
    private boolean gestureActive;

    private SwipeCalibrateOverlay(Context ctx, String pkg, boolean next, Listener listener) {
        this.pkg = pkg;
        this.next = next;
        this.listener = listener;
        this.ui = appContext(ctx);
        // Only an explicit horizontal strategy means horizontal; anything else (including a
        // stale/unknown value) defaults to vertical, which is what the video feeds need.
        this.vertical = PageTurnConfig.getStrategy(ctx, pkg) != PageTurnConfig.STRATEGY_HORIZONTAL;
        int[] sz = PageTurnConfig.screenSize(ctx);
        this.sw = sz[0];
        this.sh = sz[1];
        this.pal = PageTurnConfig.Palette.of(ctx);
        reloadShown();
    }

    private static Context appContext(Context c) {
        try {
            Context app = c.getApplicationContext();
            return app != null ? app : c;
        } catch (Throwable th) {
            return c;
        }
    }

    // ==================================================================
    // Window plumbing
    // ==================================================================
    private void attach() {
        wm = (WindowManager) ui.getSystemService(Context.WINDOW_SERVICE);
        pm = (PowerManager) ui.getSystemService(Context.POWER_SERVICE);

        root = new Surface(ui);
        trail = new TrailView(ui);
        root.addView(trail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = buildCard();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        int m = PageTurnConfig.dp(ui, 18);
        clp.setMargins(m, m, m, m);
        root.addView(card, clp);

        // A gesture monitor is the only way to keep observing the touch stream after the window
        // stops consuming it. Null means this build will not hand one out; the recorder then runs
        // in its old blocking mode rather than not at all.
        spy = TouchSpy.start(ui, spyListener);
        passThrough = spy != null;

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        applyRecordWindowStyle();

        wm.addView(root, lp);
        startRecord();
        armHeartbeat();
        HookUtils.log(TAG + ": shown pkg=" + pkg + " dir=" + pageWord()
                + " vertical=" + vertical + " space=" + sw + "x" + sh
                + " passthrough=" + passThrough + " foreground=" + foregroundNow());
    }

    private void detach() {
        h.removeCallbacks(previewTick);
        h.removeCallbacks(heartbeat);
        stopSpy();
        removeChip();
        try {
            if (wm != null && root != null) wm.removeView(root);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": removeView: " + th);
        }
        root = null;
        if (sCurrent == this) sCurrent = null;
    }

    /**
     * Recording style. Pass-through mode hands the whole display to the app: no touch flag, no
     * focus flag, no dim — the app must look and behave exactly as it does without us. Blocking
     * mode keeps the old behaviour (dim so the frozen app is still readable, focusable so back
     * works).
     */
    private void applyRecordWindowStyle() {
        if (passThrough) {
            lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            lp.dimAmount = 0f;
        } else {
            lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            lp.dimAmount = 0.30f;
        }
    }

    /** Result style: interactive card, dimmed backdrop, exactly as the previous revision. */
    private void applyResultWindowStyle() {
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        lp.dimAmount = 0.30f;
    }

    private void updateWindow() {
        try {
            if (wm != null && root != null) wm.updateViewLayout(root, lp);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": updateViewLayout: " + th);
        }
    }

    private void stopSpy() {
        TouchSpy s = spy;
        spy = null;
        if (s == null) return;
        try {
            s.dispose();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": spy.dispose: " + th);
        }
    }

    // ==================================================================
    // App chip (top-left): which app is actually underneath
    // ==================================================================
    private void addChip() {
        if (!passThrough || chip != null || wm == null) return;
        try {
            chip = buildChip();
            WindowManager.LayoutParams c = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            c.gravity = Gravity.TOP | Gravity.START;
            int m = PageTurnConfig.dp(ui, 14);
            c.x = m;
            c.y = m;
            wm.addView(chip, c);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": chip addView: " + th);
            chip = null;
        }
    }

    private void removeChip() {
        if (chip == null) return;
        try {
            if (wm != null) wm.removeView(chip);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": chip removeView: " + th);
        }
        chip = null;
    }

    private LinearLayout buildChip() {
        LinearLayout box = new LinearLayout(ui);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int radius = PageTurnConfig.dp(ui, 22);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.card);
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, PageTurnConfig.dp(ui, 1)), pal.divider);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                bg, PageTurnConfig.pressable(ui, pal.ripple, 22)});
        box.setBackground(layers);
        box.setPadding(PageTurnConfig.dp(ui, 12), PageTurnConfig.dp(ui, 9),
                PageTurnConfig.dp(ui, 10), PageTurnConfig.dp(ui, 9));
        box.setClickable(true);
        box.setFocusable(false);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                HookUtils.log(TAG + ": cancelled from chip");
                finish();
            }
        });
        return box;
    }

    /**
     * Rebuild the chip. Shows the app that is <em>currently</em> in front (not the one being
     * configured): during recording the two can differ, and that difference has to be visible.
     */
    private void refreshChip(boolean force) {
        if (chip == null) return;
        String fg = foregroundNow();
        if (fg == null) fg = chipPkg;
        if (!force && fg != null && fg.equals(chipPkg)) return;
        chipPkg = fg;
        try {
            chip.removeAllViews();
            String shownPkg = fg != null ? fg : pkg;
            boolean away = fg != null && !fg.equals(pkg);

            ImageView icon = new ImageView(ui);
            Drawable d = PageTurnConfig.appIcon(ui, shownPkg);
            if (d != null) icon.setImageDrawable(d);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int is = PageTurnConfig.dp(ui, 30);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(is, is);
            ilp.rightMargin = PageTurnConfig.dp(ui, 10);
            chip.addView(icon, ilp);

            LinearLayout texts = new LinearLayout(ui);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(ui);
            name.setText(PageTurnConfig.appLabel(ui, shownPkg));
            name.setTextSize(14);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(away ? pal.warn : pal.title);
            name.setMaxLines(1);
            name.setMaxWidth(PageTurnConfig.dp(ui, 170));
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);

            TextView sub = new TextView(ui);
            sub.setText(fg != null ? fg : "前台未知");
            sub.setTextSize(10);
            sub.setTextColor(pal.body);
            sub.setMaxLines(1);
            sub.setMaxWidth(PageTurnConfig.dp(ui, 170));
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub);

            if (away) {
                TextView warn = new TextView(ui);
                warn.setText("≠ 目标「" + PageTurnConfig.appLabel(ui, pkg) + "」");
                warn.setTextSize(11);
                warn.setTypeface(null, Typeface.BOLD);
                warn.setTextColor(pal.warn);
                warn.setMaxLines(1);
                warn.setMaxWidth(PageTurnConfig.dp(ui, 170));
                warn.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(warn);
            }
            chip.addView(texts);

            TextView x = new TextView(ui);
            x.setText("✕");
            x.setTextSize(15);
            x.setTextColor(pal.body);
            x.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
                    PageTurnConfig.dp(ui, 30), PageTurnConfig.dp(ui, 30));
            xlp.leftMargin = PageTurnConfig.dp(ui, 10);
            chip.addView(x, xlp);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": refreshChip: " + th);
        }
    }

    // ==================================================================
    // Liveness: the surface must never be able to outlive its welcome
    // ==================================================================
    private void armHeartbeat() {
        lastInputRt = SystemClock.elapsedRealtime();
        h.removeCallbacks(heartbeat);
        h.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    /**
     * One message per second, three jobs: notice that the screen went off (a recorder over a
     * sleeping display, waking back into it, is a stuck-looking device), enforce
     * {@link #WATCHDOG_MS} of continuous idleness, and keep the app chip honest. All three paths
     * are idempotent or cheap, so a double fire is harmless.
     */
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (dismissed || root == null) return;
            try {
                if (pm != null && !pm.isInteractive()) {
                    autoClose("screen off");
                    return;
                }
                if (SystemClock.elapsedRealtime() - lastInputRt >= WATCHDOG_MS) {
                    autoClose("idle " + WATCHDOG_MS + "ms");
                    return;
                }
                if (phase == PHASE_RECORD && passThrough) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - chipPollRt >= CHIP_POLL_MS) {
                        chipPollRt = now;
                        refreshChip(false);
                        applyRecordHint();
                    }
                }
            } catch (Throwable th) {
                HookUtils.log(TAG + ": heartbeat: " + th);
            }
            h.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ==================================================================
    // Wording
    // ==================================================================
    private String pageWord() {
        return next ? "下一页" : "上一页";
    }

    private String modeWord() {
        if (vertical) return next ? "上滑" : "下滑";
        return next ? "左滑" : "右滑";
    }

    private void reloadShown() {
        float[][] stored = PageTurnConfig.getCalibration(ui, pkg, next);
        if (stored != null) {
            shown = stored;
            shownIsCustom = true;
        } else {
            shown = PageTurnConfig.defaultPath(vertical, next);
            shownIsCustom = false;
        }
    }

    /** Best-effort foreground package; null when the detector itself fails. */
    private String foregroundNow() {
        try {
            return PageTurnConfig.getForegroundPackage(ui);
        } catch (Throwable th) {
            return null;
        }
    }

    private String subtitleText() {
        String app = PageTurnConfig.appLabel(ui, pkg);
        if (phase == PHASE_RECORD) {
            return app + " · 在「" + app + "」上按你习惯的方式" + modeWord() + "一次，"
                    + "轨迹与速度都会被记录。";
        }
        String current = shownIsCustom
                ? ("自定义 · " + PageTurnConfig.describeCalibration(shown))
                : "默认范围";
        return app + " · 当前：" + current;
    }

    private String titleText() {
        return phase == PHASE_RECORD
                ? ("自定义" + modeWord() + "范围")
                : "已保存滑动范围";
    }

    /**
     * The two on-canvas lines shown while recording. With pass-through the gesture really acts on
     * the app, so the wording must say so — and when the app underneath is not the one being
     * configured, that is the single most useful thing to put on screen.
     *
     * @return {@code {main, sub, "1" when the foreground is the wrong app}}
     */
    private String[] recordHint() {
        String target = PageTurnConfig.appLabel(ui, pkg);
        if (!passThrough) {
            return new String[] {
                    "在屏幕上按你习惯的方式" + modeWord() + "一次",
                    "本机未提供手势旁路，录制期间应用不会响应 · 点左上角卡片可取消",
                    "0"};
        }
        String fg = chipPkg != null ? chipPkg : foregroundNow();
        boolean away = fg != null && !fg.equals(pkg);
        if (away) {
            return new String[] {
                    "当前前台是「" + PageTurnConfig.appLabel(ui, fg) + "」",
                    "请切回「" + target + "」再" + modeWord() + " —— 轨迹记给该应用的「"
                            + pageWord() + "」· 点左上角卡片可取消",
                    "1"};
        }
        return new String[] {
                "在「" + target + "」上按你习惯的方式" + modeWord() + "一次",
                "应用会照常响应，这一整条轨迹与速度都会被记录 · 点左上角卡片可取消",
                "0"};
    }

    private void applyRecordHint() {
        if (phase != PHASE_RECORD || trail == null) return;
        try {
            String[] hint = recordHint();
            boolean warn = "1".equals(hint[2]);
            String main = hint[0];
            if (feedbackText != null && !WAITING.equals(feedbackText)) {
                main = feedbackText;
                warn = true;
            }
            trail.setHint(main, hint[1], warn);
            trail.invalidate();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": applyRecordHint: " + th);
        }
    }

    // ==================================================================
    // Card (rebuilt on every phase / state change)
    // ==================================================================
    private LinearLayout buildCard() {
        LinearLayout c = PageTurnConfig.card(ui, pal);
        // Swallow touches that land on the card body: without this, a tap on the card would
        // reach the root view's own handler and close the surface.
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        return c;
    }

    private void refreshCard() {
        card.removeAllViews();
        card.addView(PageTurnConfig.sheetHandle(ui, pal));
        card.addView(PageTurnConfig.titleView(ui, pal, titleText()));

        TextView sub = PageTurnConfig.subtitleView(ui, pal, subtitleText());
        sub.setPadding(0, PageTurnConfig.dp(ui, 4), 0, PageTurnConfig.dp(ui, 10));
        card.addView(sub);

        if (feedbackText != null && !feedbackText.isEmpty()) {
            TextView fb = new TextView(ui);
            fb.setText(feedbackText);
            fb.setTextSize(13);
            fb.setTextColor(pal.accent);
            fb.setTypeface(null, Typeface.BOLD);
            fb.setPadding(PageTurnConfig.dp(ui, 2), 0, PageTurnConfig.dp(ui, 2),
                    PageTurnConfig.dp(ui, 8));
            card.addView(fb);
        }

        if (phase == PHASE_RECORD) {
            card.addView(PageTurnConfig.divider(ui, pal));
            card.addView(PageTurnConfig.actionButton(ui, pal, "取消", new Runnable() {
                @Override public void run() { finish(); }
            }));
            return;
        }

        card.addView(PageTurnConfig.optionRow(ui, pal, "重新记录",
                "再滑一次，覆盖当前记录", false,
                new Runnable() {
                    @Override public void run() { startRecord(); }
                }));

        if (shownIsCustom) {
            card.addView(PageTurnConfig.optionRow(ui, pal, "恢复默认范围",
                    "清除本应用该方向的自定义轨迹", false,
                    new Runnable() {
                        @Override public void run() { clearCustom(); }
                    }));
        }

        card.addView(PageTurnConfig.divider(ui, pal));
        card.addView(PageTurnConfig.actionButton(ui, pal, "完成", new Runnable() {
            @Override public void run() { finish(); }
        }));
    }

    private void setFeedback(String text) {
        feedbackText = text;
        if (phase == PHASE_RESULT) refreshCard();
        else applyRecordHint();
    }

    // ==================================================================
    // Phase transitions
    // ==================================================================
    /** Result phase: loop the animation of the saved trajectory. */
    private void startResultAnim() {
        previewStart = SystemClock.uptimeMillis();
        animCycles = 0;
        h.removeCallbacks(previewTick);
        h.post(previewTick);
    }

    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            if (dismissed || root == null || phase != PHASE_RESULT) return;
            try {
                if (shown == null || shown.length < 2) return;
                long dur = Math.max(140L, Math.round(shown[shown.length - 1][2]));
                long cycle = dur + PREVIEW_HOLD_MS + PREVIEW_GAP_MS;
                long el = SystemClock.uptimeMillis() - previewStart;
                if (el >= cycle) {
                    animCycles++;
                    if (animCycles >= MAX_ANIM_CYCLES) {
                        // Park on the finished state and stop posting: from here on the open
                        // card costs nothing but the heartbeat's one message per second.
                        trail.setProgress(1f);
                        trail.invalidate();
                        return;
                    }
                    previewStart = SystemClock.uptimeMillis();
                    el = 0L;
                }
                float p;
                if (el <= dur) p = (float) el / (float) dur;
                else if (el <= dur + PREVIEW_HOLD_MS) p = 1f;
                else p = 0f;
                trail.setProgress(p);
                trail.invalidate();
                h.postDelayed(this, FRAME_MS);
            } catch (Throwable th) {
                // A draw-loop bug must not escape onto this looper (system_server's main one).
                HookUtils.log(TAG + ": previewTick: " + th);
            }
        }
    };

    /**
     * Repaint the live trail, throttled. A swipe arrives at up to 120 Hz and every
     * {@code invalidate()} schedules a traversal on this window's looper; the recorded samples are
     * unaffected by the throttle (they are taken per event, on the spy thread), only the painted
     * frame rate is (~40 fps).
     */
    private final Runnable repaint = new Runnable() {
        @Override public void run() {
            try {
                if (dismissed || root == null || trail == null) return;
                trail.setRecording(true, snapshot());
                trail.invalidate();
            } catch (Throwable th) {
                HookUtils.log(TAG + ": repaint: " + th);
            }
        }
    };

    private void invalidateTrail() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTrailRt < TRAIL_MIN_FRAME_MS) return;
        lastTrailRt = now;
        trail.invalidate();
    }

    /** Arm the recorder. This is the initial state of the surface. */
    private void startRecord() {
        h.removeCallbacks(previewTick);
        synchronized (recLock) {
            rec.clear();
        }
        gestureActive = false;
        phase = PHASE_RECORD;
        feedbackText = WAITING;
        chipPollRt = 0L;
        if (card != null) card.setVisibility(View.GONE);
        applyRecordWindowStyle();
        updateWindow();
        if (passThrough) {
            addChip();
            refreshChip(true);
        }
        trail.setRecording(true, null);
        applyRecordHint();
        trail.invalidate();
        HookUtils.log(TAG + ": recording started for " + pkg + " dir=" + pageWord()
                + " passthrough=" + passThrough);
    }

    /** Recording is over: hand the display back to the app and show the result card. */
    private void enterResult() {
        phase = PHASE_RESULT;
        gestureActive = false;
        stopSpy();
        removeChip();
        applyResultWindowStyle();
        updateWindow();
        trail.setRecording(false, null);
        trail.setHint(null, null, false);
        if (card != null) card.setVisibility(View.VISIBLE);
        refreshCard();
        startResultAnim();
        HookUtils.log(TAG + ": result pkg=" + pkg + " dir=" + pageWord()
                + " custom=" + shownIsCustom);
    }

    private void clearCustom() {
        PageTurnConfig.clearCalibration(ui, pkg, next);
        reloadShown();
        feedbackText = "已恢复默认范围";
        phase = PHASE_RESULT;
        refreshCard();
        startResultAnim();
    }

    /** User-initiated close: 完成 / 取消 / 返回键 / 左上角卡片. */
    private void finish() {
        close(true, "user");
    }

    /**
     * Close without honouring the owed page turn. Used by both safety nets — the screen went off,
     * or nothing happened for {@link #WATCHDOG_MS}. Those fire while the user is elsewhere, and
     * injecting a swipe then would be an action nobody asked for.
     */
    private void autoClose(String reason) {
        close(false, reason);
    }

    private void close(boolean mayRunListener, String why) {
        if (dismissed) return;
        dismissed = true;
        HookUtils.log(TAG + ": closing pkg=" + pkg + " dir=" + pageWord()
                + " custom=" + shownIsCustom + " phase=" + phase + " why=" + why);
        detach();
        // The listener exists to run the page turn the caller owed. In pass-through mode there is
        // no debt: the gesture already reached the app and the app already acted on it, so
        // replaying it would turn two pages. In blocking mode (no spy channel) the app never saw
        // the gesture, and the caller's turn is the only feedback the user gets.
        if (mayRunListener && listener != null && !passThrough) {
            // Give the window manager a moment to hand focus back to the app before anything is
            // injected, otherwise the first DOWN can land on a window that is still losing focus.
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        listener.onDone();
                    } catch (Throwable th) {
                        HookUtils.log(TAG + ": listener: " + th);
                    }
                }
            }, 90L);
        }
    }

    /** Back closes the surface; what the recorder already saved stays saved. */
    private void onBack() {
        finish();
    }

    // ==================================================================
    // Recording — pass-through (spy thread) and blocking (main thread) paths
    // ==================================================================
    private final TouchSpy.Listener spyListener = new TouchSpy.Listener() {
        @Override public void onSpyTouch(int action, float x, float y, long eventTime) {
            onSpyEvent(action, x, y, eventTime);
        }
        @Override public void onSpyLost() {
            HookUtils.log(TAG + ": spy channel lost while recording");
        }
    };

    /**
     * Runs on the spy thread. Touches only {@link #rec} (under its lock), the gesture state and
     * volatile fields; every UI or Settings write is posted to the main looper.
     */
    private void onSpyEvent(int action, float x, float y, long eventTime) {
        try {
            if (dismissed || phase != PHASE_RECORD) return;
            lastInputRt = SystemClock.elapsedRealtime();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    float nx = x / (float) sw;
                    float ny = y / (float) sh;
                    synchronized (recLock) {
                        rec.clear();
                        downTimeMs = eventTime;
                        rec.add(new float[]{nx, ny, 0f});
                    }
                    gestureActive = true;
                    lastTrailRt = 0L;
                    h.post(repaint);
                    return;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!gestureActive) return;
                    long t = eventTime - downTimeMs;
                    float nx = x / (float) sw;
                    float ny = y / (float) sh;
                    synchronized (recLock) {
                        if (rec.size() < MAX_RAW_POINTS && shouldSample(nx, ny, t)) {
                            rec.add(new float[]{nx, ny, (float) t});
                        }
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now - lastTrailRt >= TRAIL_MIN_FRAME_MS) {
                        lastTrailRt = now;
                        h.post(repaint);
                    }
                    return;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!gestureActive) return;
                    gestureActive = false;
                    final int a = action;
                    float nx = x / (float) sw;
                    float ny = y / (float) sh;
                    float t = (float) (eventTime - downTimeMs);
                    synchronized (recLock) {
                        rec.add(new float[]{nx, ny, t});
                    }
                    h.post(new Runnable() {
                        @Override public void run() {
                            try {
                                if (dismissed || phase != PHASE_RECORD) return;
                                trail.setRecording(true, snapshot());
                                trail.invalidate();
                                if (a == MotionEvent.ACTION_UP) commitRecord();
                                else setFeedback("滑动被中断，请再试一次");
                            } catch (Throwable th) {
                                HookUtils.log(TAG + ": gesture end: " + th);
                            }
                        }
                    });
                    return;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    // A second finger makes the gesture ambiguous for a fling detector.
                    gestureActive = false;
                    h.post(new Runnable() {
                        @Override public void run() { setFeedback("请用单指滑动"); }
                    });
                    return;
                }
                default:
                    return;
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": onSpyEvent: " + th);
        }
    }

    /** Blocking path (no spy available): the surface consumes the gesture itself. */
    private boolean handleRecord(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float[] p = norm(e.getX(), e.getY(), 0L);
                synchronized (recLock) {
                    rec.clear();
                    downTimeMs = e.getEventTime();
                    rec.add(p);
                }
                lastTrailRt = 0L;
                trail.setRecording(true, snapshot());
                trail.invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                long t = e.getEventTime() - downTimeMs;
                float nx = e.getX() / (float) sw;
                float ny = e.getY() / (float) sh;
                synchronized (recLock) {
                    if (rec.size() < MAX_RAW_POINTS && shouldSample(nx, ny, t)) {
                        rec.add(new float[]{nx, ny, (float) t});
                    }
                }
                trail.setRecording(true, snapshot());
                invalidateTrail();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float[] p = norm(e.getX(), e.getY(), e.getEventTime() - downTimeMs);
                synchronized (recLock) {
                    rec.add(p);
                }
                trail.setRecording(true, snapshot());
                trail.invalidate();
                // commitRecord() decides; it keeps the live trail on screen when the gesture is
                // rejected, so the user can see what went wrong.
                commitRecord();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                // The gesture was taken away from us (another window grabbed the pointer).
                // Stay armed instead of tearing the surface down: the user simply retries.
                synchronized (recLock) {
                    rec.clear();
                }
                trail.setRecording(true, null);
                trail.invalidate();
                setFeedback("滑动被中断，请再试一次");
                return true;
            }
            default:
                return true;
        }
    }

    private float[] norm(float x, float y, long t) {
        return new float[]{x / (float) sw, y / (float) sh, (float) t};
    }

    /** Copy of the live samples; the arrays themselves are never mutated after creation. */
    private float[][] snapshot() {
        synchronized (recLock) {
            int n = rec.size();
            float[][] out = new float[n][];
            for (int i = 0; i < n; i++) out[i] = rec.get(i);
            return out;
        }
    }

    /**
     * Sampling filter over the raw stream: keep a point when the pointer moved far enough or
     * enough time passed since the previous one, so a stationary finger does not fill the buffer
     * while a fast flick is not thinned away.
     *
     * <p>Callers hold {@link #recLock} — this reads the last sample directly on purpose, a
     * nested acquire per move event would be pointless churn on a 120 Hz path.</p>
     */
    private boolean shouldSample(float nx, float ny, long t) {
        if (rec.isEmpty()) return true;
        float[] last = rec.get(rec.size() - 1);
        float dx = nx - last[0];
        float dy = ny - last[1];
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist >= MIN_STEP_FRAC || (t - Math.round(last[2])) >= 40L;
    }

    private void commitRecord() {
        float[][] raw = snapshot();
        if (raw.length < 2) {
            setFeedback("没有捕捉到滑动，请再试一次");
            return;
        }
        float[] a = raw[0];
        float[] b = raw[raw.length - 1];
        long dur = Math.round(b[2]);
        float travel = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));

        if (dur < MIN_DURATION_MS) {
            setFeedback("滑动太快了（<60ms），请用正常速度再试一次");
            return;
        }
        if (dur > MAX_DURATION_MS) {
            setFeedback("滑动太慢（>4 秒），请再试一次");
            return;
        }
        if (travel < MIN_TRAVEL) {
            setFeedback("滑动距离太短，请滑得长一些");
            return;
        }

        float[][] pts = resample(raw);
        if (pts == null) {
            setFeedback("轨迹无效，请再试一次");
            return;
        }
        String fg = foregroundNow();
        boolean away = fg != null && !fg.equals(pkg);
        PageTurnConfig.saveCalibration(ui, pkg, next, pts,
                ui.getResources().getConfiguration().orientation);
        reloadShown();
        feedbackText = "已保存：" + PageTurnConfig.describeCalibration(pts) + oppositeHint(pts)
                + (away ? "　（记录时前台是「" + PageTurnConfig.appLabel(ui, fg)
                        + "」，轨迹可能不适用）" : "");
        enterResult();
    }

    /** Say so when the recorded direction is the opposite of the mode's default. */
    private String oppositeHint(float[][] pts) {
        float[] a = pts[0];
        float[] b = pts[pts.length - 1];
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        boolean opposite;
        if (vertical) opposite = (dy < 0) != next;
        else opposite = (dx < 0) != next;
        if (!opposite) return "";
        return "（注：方向与「" + pageWord() + "」的默认相反）";
    }

    /** Thin the raw samples down to {@link #MAX_POINTS} while keeping first / last / timing. */
    private static float[][] resample(float[][] src) {
        int n = src.length;
        if (n < 2) return null;
        int out = Math.min(n, MAX_POINTS);
        float[][] r = new float[out][];
        if (n <= MAX_POINTS) {
            for (int i = 0; i < n; i++) r[i] = src[i].clone();
        } else {
            for (int i = 0; i < out; i++) {
                int idx = (int) Math.round((double) i * (n - 1) / (out - 1));
                r[i] = src[Math.max(0, Math.min(n - 1, idx))].clone();
            }
        }
        // Timing must be non-decreasing, otherwise the replay would sleep negative values.
        for (int i = 1; i < out; i++) {
            if (r[i][2] < r[i - 1][2]) r[i][2] = r[i - 1][2];
        }
        return r;
    }

    // ==================================================================
    // Root view: key handling + touch routing
    // ==================================================================
    private final class Surface extends FrameLayout {
        Surface(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (!sizeLogged) {
                sizeLogged = true;
                HookUtils.log(TAG + ": surface=" + w + "x" + h + " space=" + sw + "x" + sh
                        + (w == sw && h == sh ? " (match)" : " (MISMATCH)"));
            }
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            lastInputRt = SystemClock.elapsedRealtime();
            try {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) onBack();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            } catch (Throwable th) {
                HookUtils.log(TAG + ": dispatchKeyEvent: " + th);
                return true;
            }
        }

        /**
         * Single funnel for every interactive event of this surface. Two jobs.
         *
         * <p>(1) It is the only place the idle watchdog can be rearmed for taps that a child row
         * consumes before {@link #onTouchEvent} ever sees them. In pass-through mode the window is
         * not touchable at all, so the spy's events rearm the timer instead.</p>
         *
         * <p>(2) It is the exception firewall. Row click listeners run inside this call, so an
         * escaped throwable would unwind into the looper that owns this window — and that looper
         * is {@code system_server}'s main thread, where an uncaught exception means the framework
         * restarts. A broken overlay must degrade to "this tap did nothing", never to a reboot.</p>
         */
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            lastInputRt = SystemClock.elapsedRealtime();
            try {
                return super.dispatchTouchEvent(e);
            } catch (Throwable th) {
                HookUtils.log(TAG + ": dispatchTouchEvent: " + th);
                return true;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                if (phase == PHASE_RECORD) return handleRecord(e);
                // Result: the card's own rows handle their taps; a tap anywhere else closes the
                // surface, exactly like a ColorOS sheet. Nothing is executed here — this surface
                // is a setting, not an action.
                if (e.getActionMasked() == MotionEvent.ACTION_UP) finish();
                return true;
            } catch (Throwable th) {
                HookUtils.log(TAG + ": onTouchEvent: " + th);
                return true;
            }
        }
    }

    // ==================================================================
    // Drawing
    // ==================================================================
    private final class TrailView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bandFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bandEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hollow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintSub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private float progress = 1f;
        private boolean recording;
        private float[][] live;
        private String hintMain;
        private String hintText;
        private boolean hintWarn;

        TrailView(Context c) {
            super(c);
            int accent = pal.accent;
            int rgb = accent & 0x00FFFFFF;

            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(PageTurnConfig.dp(ui, 6));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(accent);

            bandFill.setStyle(Paint.Style.FILL);
            bandFill.setColor(rgb | 0x22000000);
            bandEdge.setStyle(Paint.Style.STROKE);
            bandEdge.setStrokeWidth(Math.max(1f, PageTurnConfig.dp(ui, 1.5f)));
            bandEdge.setColor(rgb | 0x66000000);

            solid.setStyle(Paint.Style.FILL);
            solid.setColor(accent);
            halo.setStyle(Paint.Style.FILL);
            halo.setColor(rgb | 0x33000000);

            hollow.setStyle(Paint.Style.STROKE);
            hollow.setStrokeWidth(PageTurnConfig.dp(ui, 3));
            hollow.setColor(accent);

            text.setColor(accent);
            text.setTextSize(PageTurnConfig.dp(ui, 13));
            text.setTypeface(Typeface.DEFAULT_BOLD);

            hint.setColor(pal.title);
            hint.setTextSize(PageTurnConfig.dp(ui, 17));
            hint.setTextAlign(Paint.Align.CENTER);
            hint.setTypeface(Typeface.DEFAULT_BOLD);

            // The hint is drawn over whatever the app happens to show, so it needs its own
            // contrast: a soft shadow instead of a background band, which would hide the app.
            hint.setShadowLayer(PageTurnConfig.dp(ui, 6), 0f, 0f, 0xCCFFFFFF);
            hintSub.setColor(pal.title);
            hintSub.setTextSize(PageTurnConfig.dp(ui, 13));
            hintSub.setTextAlign(Paint.Align.CENTER);
            hintSub.setShadowLayer(PageTurnConfig.dp(ui, 6), 0f, 0f, 0xCCFFFFFF);

            // Reference (the range currently in effect) shown behind the live recording.
            refLine.setStyle(Paint.Style.STROKE);
            refLine.setStrokeWidth(Math.max(1f, PageTurnConfig.dp(ui, 2)));
            refLine.setStrokeCap(Paint.Cap.ROUND);
            refLine.setColor(rgb | 0x55000000);
            refLine.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{PageTurnConfig.dp(ui, 9), PageTurnConfig.dp(ui, 7)}, 0));
            refDot.setStyle(Paint.Style.STROKE);
            refDot.setStrokeWidth(Math.max(1f, PageTurnConfig.dp(ui, 2)));
            refDot.setColor(rgb | 0x55000000);
            refText.setColor(pal.body);
            refText.setTextSize(PageTurnConfig.dp(ui, 12));
            refText.setShadowLayer(PageTurnConfig.dp(ui, 5), 0f, 0f, 0xCCFFFFFF);
        }

        /** Faint dashed outline of {@link #shown} — what the app uses right now. */
        private void drawReference(Canvas cv) {
            float[][] r = shown;
            if (r == null || r.length < 2) return;
            path.reset();
            path.moveTo(r[0][0] * sw, r[0][1] * sh);
            for (int i = 1; i < r.length; i++) path.lineTo(r[i][0] * sw, r[i][1] * sh);
            cv.drawPath(path, refLine);
            cv.drawCircle(r[0][0] * sw, r[0][1] * sh, PageTurnConfig.dp(ui, 18), refDot);
            cv.drawCircle(r[r.length - 1][0] * sw, r[r.length - 1][1] * sh,
                    PageTurnConfig.dp(ui, 18), refDot);
            cv.drawText("当前范围", r[0][0] * sw + PageTurnConfig.dp(ui, 26),
                    r[0][1] * sh - PageTurnConfig.dp(ui, 22), refText);
        }

        void setProgress(float p) {
            progress = p;
        }

        void setRecording(boolean on, float[][] points) {
            recording = on;
            live = points;
        }

        void setHint(String main, String sub, boolean warn) {
            hintMain = main;
            hintText = sub;
            hintWarn = warn;
        }

        private float[][] current() {
            if (recording) {
                if (live == null || live.length < 2) return null;
                return live;
            }
            return shown;
        }

        @Override protected void onDraw(Canvas cv) {
            // Contained on purpose: the framework's software-draw path logs and continues, but
            // this is still the system main thread and this class must not be the reason a frame
            // blows up there. A failed frame simply draws nothing.
            try {
                drawTrail(cv);
            } catch (Throwable th) {
                HookUtils.log(TAG + ": onDraw: " + th);
            }
        }

        private void drawTrail(Canvas cv) {
            // While recording, the range currently in effect stays visible as a faint dashed
            // reference so the user can place the new gesture relative to it.
            if (recording) drawReference(cv);

            float[][] pts = current();
            if (pts == null || pts.length < 2) {
                if (recording) drawHint(cv);
                return;
            }

            int n = pts.length;
            float[] xs = new float[n];
            float[] ys = new float[n];
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                xs[i] = pts[i][0] * sw;
                ys[i] = pts[i][1] * sh;
                minX = Math.min(minX, xs[i]);
                maxX = Math.max(maxX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxY = Math.max(maxY, ys[i]);
            }

            // -------- range band --------
            float padX = Math.max((maxX - minX) * 0.10f, sw * 0.055f);
            float padY = Math.max((maxY - minY) * 0.10f, sh * 0.045f);
            float cr = PageTurnConfig.dp(ui, 24);
            RectF r = new RectF(minX - padX, minY - padY, maxX + padX, maxY + padY);
            cv.drawRoundRect(r, cr, cr, bandFill);
            cv.drawRoundRect(r, cr, cr, bandEdge);

            // -------- trajectory --------
            float total = 0f;
            for (int i = 1; i < n; i++) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i]);
            float shownLen = recording ? total : total * progress;

            path.reset();
            path.moveTo(xs[0], ys[0]);
            float acc = 0f;
            float tipX = xs[0], tipY = ys[0];
            boolean tipSet = false;
            for (int i = 1; i < n; i++) {
                float seg = dist(xs[i - 1], ys[i - 1], xs[i], ys[i]);
                if (acc + seg <= shownLen) {
                    path.lineTo(xs[i], ys[i]);
                    acc += seg;
                    tipX = xs[i];
                    tipY = ys[i];
                    tipSet = true;
                } else {
                    float f = seg <= 0f ? 0f : (shownLen - acc) / seg;
                    tipX = xs[i - 1] + (xs[i] - xs[i - 1]) * f;
                    tipY = ys[i - 1] + (ys[i] - ys[i - 1]) * f;
                    path.lineTo(tipX, tipY);
                    tipSet = true;
                    break;
                }
            }
            if (shownLen > 1f) cv.drawPath(path, line);

            // -------- handles --------
            float rs = PageTurnConfig.dp(ui, 13);
            cv.drawCircle(xs[0], ys[0], rs, hollow);
            cv.drawCircle(xs[n - 1], ys[n - 1], PageTurnConfig.dp(ui, 8), solid);
            label(cv, "起点", xs[0] + PageTurnConfig.dp(ui, 20), ys[0] - PageTurnConfig.dp(ui, 14));
            label(cv, "终点", xs[n - 1] + PageTurnConfig.dp(ui, 20),
                    ys[n - 1] + PageTurnConfig.dp(ui, 26));
            arrow(cv, xs[n - 2], ys[n - 2], xs[n - 1], ys[n - 1]);

            // -------- travelling dot --------
            if (!recording && tipSet && shownLen > 1f) {
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ui, 22), halo);
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ui, 11), solid);
            } else if (recording) {
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ui, 14), halo);
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ui, 8), solid);
            }
        }

        /**
         * Recording instructions, centred over the app. Positioned in the upper-middle band: the
         * bottom of the screen is where the user's own gesture and the app's response are.
         */
        private void drawHint(Canvas cv) {
            int warnColor = pal.warn;
            hint.setColor(hintWarn ? warnColor : pal.title);
            cv.drawText(hintMain == null ? "" : hintMain, sw * 0.5f, sh * 0.40f, hint);
            if (hintText != null) {
                cv.drawText(hintText, sw * 0.5f, sh * 0.40f + PageTurnConfig.dp(ui, 26), hintSub);
            }
        }

        private void label(Canvas cv, String s, float x, float y) {
            float w = text.measureText(s);
            float px = Math.max(PageTurnConfig.dp(ui, 8),
                    Math.min(x, sw - w - PageTurnConfig.dp(ui, 8)));
            float py = Math.max(PageTurnConfig.dp(ui, 24),
                    Math.min(y, sh - PageTurnConfig.dp(ui, 12)));
            cv.drawText(s, px, py, text);
        }

        /** Arrow head at the end of the last segment. */
        private void arrow(Canvas cv, float x0, float y0, float x1, float y1) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) return;
            float ux = dx / len;
            float uy = dy / len;
            float size = PageTurnConfig.dp(ui, 22);
            float half = size * 0.55f;
            // base of the head, just behind the tip
            float bx = x1 - ux * size;
            float by = y1 - uy * size;
            Path a = new Path();
            a.moveTo(x1, y1);
            a.lineTo(bx - uy * half, by + ux * half);
            a.lineTo(bx + uy * half, by - ux * half);
            a.close();
            cv.drawPath(a, solid);
        }

        private float dist(float x0, float y0, float x1, float y1) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }
}
