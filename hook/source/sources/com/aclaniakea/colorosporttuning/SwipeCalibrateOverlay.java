package com.aclaniakea.colorosporttuning;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen transparent calibration surface for the "模拟滑动" page-turn strategies.
 *
 * <p>Why this exists: the built-in swipe starts at a fixed ratio of the screen. In several
 * apps that point is not inside the scrollable content at all (the video area above a comment
 * bar, the scrollable column of a split layout, a carousel under a fixed header), so the app
 * just ignores the gesture — which is indistinguishable from "the feature does not work".
 * The fix cannot be guessed from outside, so the user records one real swipe per direction.</p>
 *
 * <p><b>This surface is never popped implicitly (4.8.7).</b> It is opened only by an explicit
 * 「自定义范围」 action — device center → 翻页功能触发方式 → app → 自定义「下一页 / 上一页」滑动范围
 * (see {@link PageTurnConfig#showChangeDialog}). Picking 模拟滑动 in the one-time chooser just
 * runs the stored/default range, so a page turn is never interrupted by a full-screen window.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li><b>Recording</b> (initial phase) — the previous range (default or a stored trajectory)
 *       is drawn as a faint reference and the next real swipe is captured: path <i>and</i>
 *       per-point timing, because velocity is what a fling detector actually reads.</li>
 *   <li><b>Result</b> — the saved trajectory is drawn and animated so the user can see exactly
 *       what will be replayed; 「重新记录」 goes back to capture, 「恢复默认范围」 drops it,
 *       「完成」 closes. Nothing is executed on close: this surface is a setting, not an action.</li>
 * </ol>
 *
 * <p>Window notes: added straight through {@code WindowManager} as a
 * {@code TYPE_APPLICATION_OVERLAY} window from {@code system_server} (same technique as
 * {@link HandwrittenNoteOverlay} / {@link LassoSelectOverlay}), deliberately <b>not</b>
 * {@code FLAG_NOT_FOCUSABLE} so the back key reaches us, and with {@code FLAG_ALT_FOCUSABLE_IM}
 * so the app underneath keeps its IME state untouched. Coordinates are captured in display space
 * ({@code FLAG_LAYOUT_IN_SCREEN} puts the view origin at the display origin) and normalised with
 * {@link PageTurnConfig#screenSize}, the exact space the replay path multiplies back out — a
 * recorded trajectory can therefore never be interpreted in a different coordinate system.</p>
 *
 * <p>The dim is kept deliberately light (0.30): calibration is usually launched from the device
 * center, and being able to see the app or panel behind the band is what lets the user place the
 * start point inside the app's scrollable region.</p>
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
    private static final long FRAME_MS = 16L;
    private static final long PREVIEW_HOLD_MS = 650L;
    private static final long PREVIEW_GAP_MS = 220L;

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
            sCurrent = null;
            if (onDone != null) onDone.onDone();
        }
    }

    // ------------------------------------------------------------------

    private final Context ctx;
    private final String pkg;
    private final boolean next;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final boolean vertical;
    private final int sw;
    private final int sh;
    private final PageTurnConfig.Palette pal;

    private WindowManager wm;
    private Surface root;
    private TrailView trail;
    private LinearLayout card;
    private boolean sizeLogged;

    private int phase = PHASE_RECORD;
    private float[][] shown;
    private boolean shownIsCustom;
    private long previewStart;
    private String feedbackText;
    private boolean dismissed;

    private final List<float[]> rec = new ArrayList<>();
    private long downTimeMs;

    private SwipeCalibrateOverlay(Context ctx, String pkg, boolean next, Listener listener) {
        this.ctx = ctx;
        this.pkg = pkg;
        this.next = next;
        this.listener = listener;
        // Only an explicit horizontal strategy means horizontal; anything else (including a
        // stale/unknown value) defaults to vertical, which is what the video feeds need.
        this.vertical = PageTurnConfig.getStrategy(ctx, pkg) != PageTurnConfig.STRATEGY_HORIZONTAL;
        int[] sz = PageTurnConfig.screenSize(ctx);
        this.sw = sz[0];
        this.sh = sz[1];
        this.pal = PageTurnConfig.Palette.of(ctx);
        reloadShown();
    }

    // ==================================================================
    // Window plumbing
    // ==================================================================
    private void attach() {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        root = new Surface(ctx);
        trail = new TrailView(ctx);
        root.addView(trail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = buildCard();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        int m = PageTurnConfig.dp(ctx, 18);
        clp.setMargins(m, m, m, m);
        root.addView(card, clp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Light dim on purpose: the app / panel behind stays visible so the user can aim the
        // start point at the app's real scrollable area.
        lp.dimAmount = 0.30f;

        wm.addView(root, lp);
        refreshCard();
        startRecord();
        HookUtils.log(TAG + ": shown pkg=" + pkg + " dir=" + pageWord()
                + " vertical=" + vertical + " space=" + sw + "x" + sh
                + " foreground=" + foregroundNow());
    }

    private void detach() {
        h.removeCallbacks(previewTick);
        try {
            if (wm != null && root != null) wm.removeView(root);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": removeView: " + th);
        }
        root = null;
        if (sCurrent == this) sCurrent = null;
    }

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
        float[][] stored = PageTurnConfig.getCalibration(ctx, pkg, next);
        if (stored != null) {
            shown = stored;
            shownIsCustom = true;
        } else {
            shown = PageTurnConfig.defaultPath(vertical, next);
            shownIsCustom = false;
        }
    }

    /** Best-effort foreground package, only used to word the "you are not in that app" hint. */
    private String foregroundNow() {
        try {
            return PageTurnConfig.getForegroundPackage(ctx);
        } catch (Throwable th) {
            return null;
        }
    }

    private String subtitleText() {
        String app = PageTurnConfig.appLabel(ctx, pkg);
        if (phase == PHASE_RECORD) {
            return app + " · 在屏幕上按你习惯的方式" + modeWord() + "一次，"
                    + "整条轨迹和速度都会被记录，之后该应用的「" + pageWord() + "」照此执行。";
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

    /** Null when the calibration is happening while the target app is actually in front. */
    private String awayHint() {
        if (phase != PHASE_RECORD) return null;
        String fg = foregroundNow();
        if (fg == null || fg.equals(pkg)) return null;
        String[] hosts = {"com.oplus.ipemanager", "com.android.systemui", "com.android.launcher",
                "com.android.launcher3", "com.oplus.launcher", "android"};
        for (String h : hosts) {
            if (h.equals(fg)) {
                return "提示：当前不在「" + PageTurnConfig.appLabel(ctx, pkg)
                        + "」内，坐标按屏幕比例记录 —— 请按该应用中可滚动内容的实际位置滑动。";
            }
        }
        return null;
    }

    // ==================================================================
    // Card (rebuilt on every phase / state change)
    // ==================================================================
    private LinearLayout buildCard() {
        LinearLayout c = PageTurnConfig.card(ctx, pal);
        // Swallow touches that land on the card body: without this, a tap on the card while
        // recording would be captured as part of the trajectory. Child rows / buttons still
        // receive their own clicks first.
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        return c;
    }

    private void refreshCard() {
        card.removeAllViews();
        card.addView(PageTurnConfig.sheetHandle(ctx, pal));
        card.addView(PageTurnConfig.titleView(ctx, pal, titleText()));

        TextView sub = PageTurnConfig.subtitleView(ctx, pal, subtitleText());
        sub.setPadding(0, PageTurnConfig.dp(ctx, 4), 0, PageTurnConfig.dp(ctx, 10));
        card.addView(sub);

        if (feedbackText != null && !feedbackText.isEmpty()) {
            TextView fb = new TextView(ctx);
            fb.setText(feedbackText);
            fb.setTextSize(13);
            fb.setTextColor(pal.accent);
            fb.setTypeface(null, Typeface.BOLD);
            fb.setPadding(PageTurnConfig.dp(ctx, 2), 0, PageTurnConfig.dp(ctx, 2),
                    PageTurnConfig.dp(ctx, 8));
            card.addView(fb);
        }

        if (phase == PHASE_RECORD) {
            String away = awayHint();
            if (away != null) card.addView(hintView(away));
            card.addView(PageTurnConfig.divider(ctx, pal));
            card.addView(PageTurnConfig.actionButton(ctx, pal, "取消", new Runnable() {
                @Override public void run() { finish(); }
            }));
            return;
        }

        card.addView(PageTurnConfig.optionRow(ctx, pal, "重新记录",
                "再滑一次，覆盖当前记录", false,
                new Runnable() {
                    @Override public void run() { startRecord(); }
                }));

        if (shownIsCustom) {
            card.addView(PageTurnConfig.optionRow(ctx, pal, "恢复默认范围",
                    "清除本应用该方向的自定义轨迹", false,
                    new Runnable() {
                        @Override public void run() { clearCustom(); }
                    }));
        }

        card.addView(PageTurnConfig.divider(ctx, pal));
        card.addView(PageTurnConfig.actionButton(ctx, pal, "完成", new Runnable() {
            @Override public void run() { finish(); }
        }));
    }

    /** Secondary-coloured note (the "you are not inside that app" hint). */
    private TextView hintView(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(pal.body);
        t.setLineSpacing(PageTurnConfig.dp(ctx, 3), 1f);
        t.setPadding(PageTurnConfig.dp(ctx, 2), 0, PageTurnConfig.dp(ctx, 2),
                PageTurnConfig.dp(ctx, 10));
        return t;
    }

    private void setFeedback(String text) {
        feedbackText = text;
        refreshCard();
    }

    // ==================================================================
    // Phase transitions
    // ==================================================================
    /** Result phase: loop the animation of the saved trajectory. */
    private void startResultAnim() {
        previewStart = SystemClock.uptimeMillis();
        h.removeCallbacks(previewTick);
        h.post(previewTick);
    }

    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            if (root == null || phase != PHASE_RESULT) return;
            long dur = Math.max(140L, Math.round(shown[shown.length - 1][2]));
            long cycle = dur + PREVIEW_HOLD_MS + PREVIEW_GAP_MS;
            long el = SystemClock.uptimeMillis() - previewStart;
            if (el >= cycle) {
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
        }
    };

    /** Arm the recorder. This is the initial state of the surface. */
    private void startRecord() {
        h.removeCallbacks(previewTick);
        rec.clear();
        phase = PHASE_RECORD;
        feedbackText = "等待滑动…";
        refreshCard();
        trail.setRecording(true, rec);
        trail.invalidate();
        HookUtils.log(TAG + ": recording started for " + pkg + " dir=" + pageWord());
    }

    private void clearCustom() {
        PageTurnConfig.clearCalibration(ctx, pkg, next);
        reloadShown();
        feedbackText = "已恢复默认范围";
        phase = PHASE_RESULT;
        refreshCard();
        startResultAnim();
    }

    private void finish() {
        if (dismissed) return;
        dismissed = true;
        HookUtils.log(TAG + ": finished pkg=" + pkg + " dir=" + pageWord()
                + " custom=" + shownIsCustom);
        detach();
        if (listener != null) {
            // Give the window manager a moment to hand focus back to the app before the
            // gesture is injected, otherwise the first DOWN can land on a window that is
            // still losing focus.
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
    // Recording
    // ==================================================================
    private boolean handleRecord(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                rec.clear();
                downTimeMs = e.getEventTime();
                addPoint(e.getX(), e.getY(), 0L);
                trail.setRecording(true, rec);
                trail.invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                long t = e.getEventTime() - downTimeMs;
                float nx = e.getX() / (float) sw;
                float ny = e.getY() / (float) sh;
                if (rec.size() < MAX_RAW_POINTS && shouldSample(nx, ny, t)) addPoint(e.getX(), e.getY(), t);
                trail.setRecording(true, rec);
                trail.invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
                addPoint(e.getX(), e.getY(), e.getEventTime() - downTimeMs);
                // commitRecord() decides; it keeps the live trail on screen when the
                // gesture is rejected, so the user can see what went wrong.
                commitRecord();
                return true;

            case MotionEvent.ACTION_CANCEL:
                // The gesture was taken away from us (another window grabbed the pointer).
                // Stay armed instead of tearing the surface down: the user simply retries.
                rec.clear();
                trail.setRecording(true, rec);
                trail.invalidate();
                setFeedback("滑动被中断，请再试一次");
                return true;

            default:
                return true;
        }
    }

    private boolean shouldSample(float nx, float ny, long t) {
        if (rec.isEmpty()) return true;
        float[] last = rec.get(rec.size() - 1);
        float dx = nx - last[0];
        float dy = ny - last[1];
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist >= MIN_STEP_FRAC || (t - Math.round(last[2])) >= 40L;
    }

    private void addPoint(float x, float y, long t) {
        rec.add(new float[]{x / (float) sw, y / (float) sh, (float) t});
    }

    private void commitRecord() {
        if (rec.size() < 2) {
            setFeedback("没有捕捉到滑动，请再试一次");
            return;
        }
        float[] a = rec.get(0);
        float[] b = rec.get(rec.size() - 1);
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

        float[][] pts = resample(rec);
        if (pts == null) {
            setFeedback("轨迹无效，请再试一次");
            return;
        }
        PageTurnConfig.saveCalibration(ctx, pkg, next, pts,
                ctx.getResources().getConfiguration().orientation);
        reloadShown();
        phase = PHASE_RESULT;
        rec.clear();
        trail.setRecording(false, null);
        trail.invalidate();
        setFeedback("已保存：" + PageTurnConfig.describeCalibration(pts) + oppositeHint(pts));
        startResultAnim();
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
    private static float[][] resample(List<float[]> src) {
        int n = src.size();
        if (n < 2) return null;
        int out = Math.min(n, MAX_POINTS);
        float[][] r = new float[out][];
        if (n <= MAX_POINTS) {
            for (int i = 0; i < n; i++) r[i] = src.get(i).clone();
        } else {
            for (int i = 0; i < out; i++) {
                int idx = (int) Math.round((double) i * (n - 1) / (out - 1));
                r[i] = src.get(Math.max(0, Math.min(n - 1, idx))).clone();
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
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) onBack();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (phase == PHASE_RECORD) return handleRecord(e);
            // Result: the card's own rows handle their taps; a tap anywhere else closes the
            // surface, exactly like a ColorOS sheet. Nothing is executed here — this surface
            // is a setting, not an action.
            if (e.getActionMasked() == MotionEvent.ACTION_UP) finish();
            return true;
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
        private final Paint refLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private float progress = 1f;
        private boolean recording;
        private List<float[]> live;

        TrailView(Context c) {
            super(c);
            int accent = pal.accent;
            int rgb = accent & 0x00FFFFFF;

            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(PageTurnConfig.dp(ctx, 6));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(accent);

            bandFill.setStyle(Paint.Style.FILL);
            bandFill.setColor(rgb | 0x22000000);
            bandEdge.setStyle(Paint.Style.STROKE);
            bandEdge.setStrokeWidth(Math.max(1f, PageTurnConfig.dp(ctx, 1.5f)));
            bandEdge.setColor(rgb | 0x66000000);

            solid.setStyle(Paint.Style.FILL);
            solid.setColor(accent);
            halo.setStyle(Paint.Style.FILL);
            halo.setColor(rgb | 0x33000000);

            hollow.setStyle(Paint.Style.STROKE);
            hollow.setStrokeWidth(PageTurnConfig.dp(ctx, 3));
            hollow.setColor(accent);

            text.setColor(accent);
            text.setTextSize(PageTurnConfig.dp(ctx, 13));
            text.setTypeface(Typeface.DEFAULT_BOLD);

            hint.setColor(pal.body);
            hint.setTextSize(PageTurnConfig.dp(ctx, 15));
            hint.setTextAlign(Paint.Align.CENTER);

            // Reference (the range currently in effect) shown behind the live recording.
            refLine.setStyle(Paint.Style.STROKE);
            refLine.setStrokeWidth(Math.max(1f, PageTurnConfig.dp(ctx, 2)));
            refLine.setStrokeCap(Paint.Cap.ROUND);
            refLine.setColor(rgb | 0x55000000);
            refLine.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{PageTurnConfig.dp(ctx, 9), PageTurnConfig.dp(ctx, 7)}, 0));
            refDot.setStyle(Paint.Style.STROKE);
            refDot.setStrokeWidth(Math.max(1f, PageTurnConfig.dp(ctx, 2)));
            refDot.setColor(rgb | 0x55000000);
            refText.setColor(pal.body);
            refText.setTextSize(PageTurnConfig.dp(ctx, 12));
        }

        /** Faint dashed outline of {@link #shown} — what the app uses right now. */
        private void drawReference(Canvas cv) {
            float[][] r = shown;
            if (r == null || r.length < 2) return;
            path.reset();
            path.moveTo(r[0][0] * sw, r[0][1] * sh);
            for (int i = 1; i < r.length; i++) path.lineTo(r[i][0] * sw, r[i][1] * sh);
            cv.drawPath(path, refLine);
            cv.drawCircle(r[0][0] * sw, r[0][1] * sh, PageTurnConfig.dp(ctx, 18), refDot);
            cv.drawCircle(r[r.length - 1][0] * sw, r[r.length - 1][1] * sh,
                    PageTurnConfig.dp(ctx, 18), refDot);
            cv.drawText("当前范围", r[0][0] * sw + PageTurnConfig.dp(ctx, 26),
                    r[0][1] * sh - PageTurnConfig.dp(ctx, 22), refText);
        }

        void setProgress(float p) {
            progress = p;
        }

        void setRecording(boolean on, List<float[]> points) {
            recording = on;
            live = points;
        }

        private float[][] current() {
            if (recording) {
                if (live == null || live.size() < 2) return null;
                float[][] a = new float[live.size()][];
                for (int i = 0; i < live.size(); i++) a[i] = live.get(i);
                return a;
            }
            return shown;
        }

        @Override protected void onDraw(Canvas cv) {
            // While recording, the range currently in effect stays visible as a faint dashed
            // reference so the user can place the new gesture relative to it.
            if (recording) drawReference(cv);

            float[][] pts = current();
            if (pts == null || pts.length < 2) {
                String msg = recording ? "在此区域按你的习惯滑动" : null;
                if (msg != null) cv.drawText(msg, sw * 0.5f, sh * 0.45f, hint);
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
            float cr = PageTurnConfig.dp(ctx, 24);
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
            float rs = PageTurnConfig.dp(ctx, 13);
            cv.drawCircle(xs[0], ys[0], rs, hollow);
            cv.drawCircle(xs[n - 1], ys[n - 1], PageTurnConfig.dp(ctx, 8), solid);
            label(cv, "起点", xs[0] + PageTurnConfig.dp(ctx, 20), ys[0] - PageTurnConfig.dp(ctx, 14));
            label(cv, "终点", xs[n - 1] + PageTurnConfig.dp(ctx, 20),
                    ys[n - 1] + PageTurnConfig.dp(ctx, 26));
            arrow(cv, xs[n - 2], ys[n - 2], xs[n - 1], ys[n - 1]);

            // -------- travelling dot --------
            if (!recording && tipSet && shownLen > 1f) {
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ctx, 22), halo);
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ctx, 11), solid);
            } else if (recording) {
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ctx, 14), halo);
                cv.drawCircle(tipX, tipY, PageTurnConfig.dp(ctx, 8), solid);
            }
        }

        private void label(Canvas cv, String s, float x, float y) {
            float w = text.measureText(s);
            float px = Math.max(PageTurnConfig.dp(ctx, 8), Math.min(x, sw - w - PageTurnConfig.dp(ctx, 8)));
            float py = Math.max(PageTurnConfig.dp(ctx, 24), Math.min(y, sh - PageTurnConfig.dp(ctx, 12)));
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
            float size = PageTurnConfig.dp(ctx, 22);
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
