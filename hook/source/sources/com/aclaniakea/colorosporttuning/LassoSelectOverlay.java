package com.aclaniakea.colorosporttuning;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bridge actions 112 (圈选识别) / 113 (圈选翻译).
 *
 * <p>Flow: a full-screen transparent overlay captures a pen (or finger)
 * lasso; on release the stroke's bounding rect is cropped out of the
 * display via android.window.ScreenCapture (we are in system_server, so
 * hidden system APIs are reachable through reflection).  The crop is saved
 * to Pictures/PenBridge and put on the clipboard; the translate variant
 * additionally opens the share sheet targeted at translation.  An on-device
 * OCR engine hook goes through {@link #tryRecognize} once a usable service
 * is bound (DeepThinker probe pending -- see TODO P0.12).
 */
final class LassoSelectOverlay {

    private static final Object LOCK = new Object();
    private static boolean active;
    private static ViewGroup root;

    private LassoSelectOverlay() { }

    static void start(final Context ctx, final boolean translate) {
        synchronized (LOCK) {
            if (active) {
                HookUtils.log("lasso: already active, ignore");
                return;
            }
            active = true;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    show(ctx, translate);
                } catch (Throwable th) {
                    synchronized (LOCK) { active = false; }
                    HookUtils.log("lasso show: " + th);
                }
            }
        });
    }

    private static void show(Context ctx, boolean translate) {
        FrameLayout container = new FrameLayout(ctx);
        LassoView lasso = new LassoView(ctx, translate);
        container.addView(lasso, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.FILL;
        root = container;
        wm.addView(container, lp);
        Toast.makeText(ctx, translate
                        ? "用笔圈选要翻译的区域" : "用笔圈选要识别的区域",
                Toast.LENGTH_SHORT).show();
        HookUtils.log("lasso overlay shown translate=" + translate);
    }

    private static void finish(final Context ctx, final Rect crop,
            final boolean translate) {
        hide();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Let the window manager drop our overlay for a couple of
                    // frames first, otherwise the capture contains the dim
                    // layer and the selection rectangle.
                    Thread.sleep(250);
                    Bitmap region = captureRegion(ctx, crop);
                    if (region == null) {
                        toast(ctx, "区域截图失败（ScreenCapture 不可用）");
                        return;
                    }
                    Uri uri = savePng(ctx, region, translate ? "pen_translate" : "pen_ocr");
                    if (uri == null) {
                        // Plain-file fallback already placed the PNG in
                        // Pictures/PenBridge; only the shareable content Uri
                        // is missing.
                        toast(ctx, "圈选区域已保存到 Pictures/PenBridge"
                                + (translate ? "（分享通道不可用）" : ""));
                        return;
                    }
                    String text = tryRecognize(ctx, region);
                    if (text != null && !text.isEmpty()) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) ctx.getSystemService(
                                        Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("PenBridge", text));
                        if (translate) {
                            openTranslate(ctx, text);
                        } else {
                            toast(ctx, "识别结果已复制：" + summarize(text));
                        }
                        return;
                    }
                    // No OCR engine wired yet: image goes to the clipboard and
                    // (for translate) the share sheet so the user can pick any
                    // translate app.  This keeps the pipeline usable today.
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) ctx.getSystemService(
                                    Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newUri(ctx.getContentResolver(),
                            "PenBridge 圈选", uri);
                    cm.setPrimaryClip(clip);
                    if (translate) {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("image/png");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(Intent.createChooser(share, "圈选翻译"));
                    } else {
                        toast(ctx, "圈选区域已保存并复制到剪贴板（OCR 引擎未接入）");
                    }
                } catch (Throwable th) {
                    HookUtils.log("lasso finish: " + th);
                    toast(ctx, "圈选失败: " + th);
                }
            }
        }, "penbridge-lasso").start();
    }

    private static synchronized void hide() {
        try {
            if (root != null) {
                Context ctx = root.getContext();
                WindowManager wm = (WindowManager)
                        ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(root);
            }
        } catch (Throwable ignored) { }
        root = null;
        synchronized (LOCK) { active = false; }
        HookUtils.log("lasso overlay hidden");
    }

    // ---- display capture ------------------------------------------------

    /** Crop the display.  Reflection chain because ScreenCapture /
     * DisplayCaptureArgs are @hide system APIs not in the public jar. */
    private static Bitmap captureRegion(Context ctx, Rect crop) throws Exception {
        // /system/bin/screencap is NOT usable here: system_server is denied the
        // exec (SELinux error=13, logged 2026-09-19), and the @hide
        // SurfaceControl token statics were removed on Android 16
        // (NoSuchMethodException).  What DOES work on this ROM is the
        // ScreenCapture API -- but the display args class is NESTED:
        // android.window.ScreenCapture$DisplayCaptureArgs (there is no
        // top-level android.window.DisplayCaptureArgs on Android 15+, which is
        // exactly what broke the previous attempt with ClassNotFoundException).
        // Token comes from the @hide Display.getAddress().
        Object token = displayToken(ctx);
        if (token == null) {
            throw new IllegalStateException("no display token from any source");
        }
        HookUtils.log("lasso capture token=" + token.getClass().getName()
                + " crop=" + crop);
        return captureWithArgs(token, crop);
    }

    private static Bitmap captureWithArgs(Object token, Rect crop) throws Exception {
        Class<?> builderCls = Class.forName(
                "android.window.ScreenCapture$DisplayCaptureArgs$Builder");
        Object builder = builderCls.getConstructor(android.os.IBinder.class)
                .newInstance(token);
        try {
            builderCls.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
        } catch (Throwable nosrc) {
            HookUtils.log("lasso: setSourceCrop missing, capturing full screen");
        }
        Object args = builderCls.getMethod("build").invoke(builder);
        Class<?> capCls = Class.forName("android.window.ScreenCapture");
        Class<?> argsCls = Class.forName(
                "android.window.ScreenCapture$DisplayCaptureArgs");
        Object buf = capCls.getMethod("captureDisplay", argsCls).invoke(null, args);
        if (buf == null) {
            throw new IllegalStateException("captureDisplay returned null");
        }
        HookUtils.log("lasso: captured via ScreenCapture, crop=" + crop);
        try {
            Object b = buf.getClass().getMethod("asBitmap").invoke(buf);
            if (b instanceof Bitmap) return softwareShrink((Bitmap) b, crop);
            HookUtils.log("lasso: asBitmap returned " + b);
        } catch (Throwable th) {
            HookUtils.log("lasso: asBitmap unavailable, using buffer: " + th);
        }
        return bitmapFromCapture(buf, crop);
    }

    /** setSourceCrop is honoured by the native capture, but guard against a
     * ROM that ignores it: the returned bitmap is then display-sized and has
     * to be cropped here.  Also force a SOFTWARE copy -- a HARDWARE bitmap
     * cannot be compressed into a PNG. */
    private static Bitmap softwareShrink(Bitmap b, Rect crop) {
        Bitmap out = b;
        if (b.getWidth() != crop.width() && b.getWidth() >= crop.right
                && b.getHeight() >= crop.bottom) {
            int w = Math.min(crop.width(), b.getWidth() - crop.left);
            int h = Math.min(crop.height(), b.getHeight() - crop.top);
            out = Bitmap.createBitmap(b, crop.left, crop.top, w, h);
            HookUtils.log("lasso: crop applied locally "
                    + b.getWidth() + "x" + b.getHeight() + " -> " + w + "x" + h);
        }
        if (out.getConfig() == Bitmap.Config.HARDWARE) {
            out = out.copy(Bitmap.Config.ARGB_8888, false);
        }
        return out;
    }

    private static Bitmap bitmapFromCapture(Object buf, Rect crop) throws Exception {
        HardwareBuffer hb = (HardwareBuffer) buf.getClass()
                .getMethod("getHardwareBuffer").invoke(buf);
        Object cs = buf.getClass().getMethod("getColorSpace").invoke(buf);
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb,
                cs instanceof android.graphics.ColorSpace
                        ? (android.graphics.ColorSpace) cs : null);
        if (hw == null) throw new IllegalStateException("wrapHardwareBuffer null");
        int w = Math.min(crop.width(), hw.getWidth());
        int h = Math.min(crop.height(), hw.getHeight());
        Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap out = Bitmap.createBitmap(soft,
                Math.min(crop.left, hw.getWidth() - 1),
                Math.min(crop.top, hw.getHeight() - 1), w, h);
        hb.close();
        return out;
    }

    /** Token sources, most reliable first; logs which ones fail so the next
     * iteration can drop dead entries. */
    private static Object displayToken(Context ctx) {
        // 1) The classic SurfaceControl statics.
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object t = sc.getMethod("getInternalDisplayToken").invoke(null);
            if (t != null) return t;
            HookUtils.log("lasso token: getInternalDisplayToken=null");
        } catch (Throwable th) {
            HookUtils.log("lasso token: getInternalDisplayToken: " + th);
        }
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
            if (ids != null && ids.length > 0) {
                Object t = sc.getMethod("getPhysicalDisplayToken", Long.TYPE)
                        .invoke(null, ids[0]);
                if (t != null) return t;
                HookUtils.log("lasso token: getPhysicalDisplayToken=null");
            }
        } catch (Throwable th) {
            HookUtils.log("lasso token: getPhysicalDisplayToken: " + th);
        }
        // 2) @hide Display#getAddress() -> DisplayAddress$Physical.  That is
        // NOT an IBinder (on-device 2026-09-19: "argument 1 has type
        // android.os.IBinder, got android.view.DisplayAddress$Physical"), so
        // read its physical display id and ask OPPO's display service for the
        // token -- this ROM's SurfaceControl exposes NO token getter at all
        // (getInternalDisplayToken / getPhysicalDisplayIds / getPhysicalDisplayToken
        // are all absent from the framework dex) and android.view.DisplayControl
        // does not exist either.
        try {
            Object wm = ctx.getSystemService(Context.WINDOW_SERVICE);
            Object disp = wm.getClass().getMethod("getDefaultDisplay").invoke(wm);
            Object addr = disp.getClass().getMethod("getAddress").invoke(disp);
            if (addr instanceof android.os.IBinder) return addr;
            if (addr != null) {
                Object idv = addr.getClass()
                        .getMethod("getPhysicalDisplayId").invoke(addr);
                long id = ((Number) idv).longValue();
                HookUtils.log("lasso token: physical display id=" + id);
                Object t = oplusDisplayToken(id);
                if (t != null) return t;
                HookUtils.log("lasso token: OplusDisplayManager token=null");
            } else {
                HookUtils.log("lasso token: Display.getAddress=null");
            }
        } catch (Throwable th) {
            HookUtils.log("lasso token: Display.getAddress: " + th);
        }
        // 3) Brute force the OPPO display service with the usual ids.
        for (int i = 0; i < 4; i++) {
            Object t = oplusDisplayToken(i);
            if (t != null) {
                HookUtils.log("lasso token: OplusDisplayManager id=" + i + " ok");
                return t;
            }
        }
        return null;
    }

    /** Display token via OPPO's own display service:
     *  OplusDisplayManager.getInstance().getPhysicalDisplayToken(id). */
    private static Object oplusDisplayToken(long displayId) {
        try {
            Class<?> om = Class.forName("android.hardware.display.OplusDisplayManager");
            Object inst = om.getMethod("getInstance").invoke(null);
            Object t = om.getMethod("getPhysicalDisplayToken", Long.TYPE)
                    .invoke(inst, displayId);
            return t instanceof android.os.IBinder ? t : null;
        } catch (Throwable th) {
            HookUtils.log("lasso token: OplusDisplayManager(" + displayId + "): " + th);
            return null;
        }
    }

    /** On-device OCR hook point.  Returns extracted text or null when no
     * engine is available (the image fallback path then runs). */
    private static String tryRecognize(Context ctx, Bitmap region) {
        // TODO(P0.12): bind com.oplus.deepthinker OCR (probe pending -- adb
        // dropped before the package scan).  Keep this null for now.
        return null;
    }

    /** Persist the capture (see HookUtils.savePngToGallery for the destination
     * chain).  Returns the content Uri, or null when only a plain file in
     * Pictures/PenBridge could be produced. */
    private static Uri savePng(Context ctx, Bitmap bmp, String prefix) throws Exception {
        String name = prefix + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png";
        return HookUtils.savePngToGallery(ctx, bmp, name);
    }

    private static void openTranslate(Context ctx, String text) throws Exception {
        Intent t = new Intent(Intent.ACTION_TRANSLATE);
        t.putExtra(Intent.EXTRA_TEXT, text);
        t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (t.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(t);
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(Intent.createChooser(share, "圈选翻译"));
    }

    private static String summarize(String text) {
        String one = text.replace('\n', ' ').trim();
        return one.length() > 24 ? one.substring(0, 24) + "…" : one;
    }

    private static void toast(final Context ctx, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Transparent view that records the lasso and mirrors the stroke while
     * dragging.  Release (or the view being cancelled) commits the region. */
    private static final class LassoView extends View {
        private final boolean translate;
        private final Paint strokePaint = new Paint();
        private final Paint maskPaint = new Paint();
        private final Path path = new Path();
        private boolean dragging;

        LassoView(Context ctx, boolean translate) {
            super(ctx);
            this.translate = translate;
            strokePaint.setAntiAlias(true);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(4f);
            strokePaint.setColor(translate ? 0xFF1E88E5 : 0xFF43A047);
            maskPaint.setStyle(Paint.Style.FILL);
            maskPaint.setColor(0x14000000);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawPaint(maskPaint);
            canvas.drawPath(path, strokePaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    path.reset();
                    path.moveTo(x, y);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        int hist = event.getHistorySize();
                        for (int i = 0; i < hist; i++) {
                            path.lineTo(event.getHistoricalX(i),
                                    event.getHistoricalY(i));
                        }
                        path.lineTo(x, y);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        RectF b = new RectF();
                        path.computeBounds(b, true);
                        path.reset();
                        Rect crop = new Rect((int) b.left, (int) b.top,
                                (int) b.right, (int) b.bottom);
                        if (crop.width() < 40 || crop.height() < 40) {
                            // Treat a tiny tap as "cancel".
                            toast(getContext(), "圈选区域太小，已取消");
                            hide();
                            return true;
                        }
                        // Clamp to display.
                        int[] loc = new int[2];
                        getLocationOnScreen(loc);
                        crop.offset(loc[0], loc[1]);
                        finish(getContext(), crop, translate);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
