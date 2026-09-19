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
                    Bitmap region = captureRegion(ctx, crop);
                    if (region == null) {
                        toast(ctx, "区域截图失败（ScreenCapture 不可用）");
                        return;
                    }
                    Uri uri = savePng(ctx, region, translate ? "pen_translate" : "pen_ocr");
                    if (uri == null) {
                        toast(ctx, "圈选结果保存失败");
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
        // Token chain: on this ROM (Android 16) SurfaceControl's static token
        // getters return null even inside system_server (user report
        // 2026-09-19 "no display token"), so try the @hide alternatives
        // before giving up.  Every failure is logged for the next probe.
        Object token = displayToken(ctx);
        if (token != null) {
            try {
                return captureWithArgs(token, crop);
            } catch (Throwable th) {
                HookUtils.log("lasso capture token path failed: " + th);
            }
        } else {
            HookUtils.log("lasso: all display token sources failed");
        }
        // Last resort: captureDisplayEx(displayId, args) resolves the token
        // inside SurfaceFlinger, so the caller never needs one.  Builder
        // formally takes a token; pass null and hope the ctor tolerates it
        // (a no-arg ctor would be preferred but does not exist in AOSP).
        try {
            Class<?> argsCls = Class.forName("android.window.DisplayCaptureArgs");
            Class<?> builderCls = Class.forName("android.window.DisplayCaptureArgs$Builder");
            Object builder;
            try {
                builder = builderCls.getConstructor(android.os.IBinder.class)
                        .newInstance(new Object[]{null});
            } catch (Throwable nullTok) {
                builder = builderCls.getDeclaredConstructor().newInstance();
            }
            try {
                builderCls.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
            } catch (Throwable nosrc) {
                HookUtils.log("lasso: setSourceCrop missing, full-screen capture");
            }
            Object args = builderCls.getMethod("build").invoke(builder);
            Class<?> capCls = Class.forName("android.window.ScreenCapture");
            Object buf = capCls.getMethod("captureDisplayEx", Integer.TYPE, argsCls)
                    .invoke(null, android.view.Display.DEFAULT_DISPLAY, args);
            return bitmapFromCapture(buf, crop);
        } catch (Throwable th) {
            throw new IllegalStateException(
                    "display capture unavailable (token=" + token + "): " + th);
        }
    }

    private static Bitmap captureWithArgs(Object token, Rect crop) throws Exception {
        Class<?> argsCls = Class.forName("android.window.DisplayCaptureArgs");
        Class<?> builderCls = Class.forName("android.window.DisplayCaptureArgs$Builder");
        Object builder = builderCls.getConstructor(android.os.IBinder.class)
                .newInstance(token);
        try {
            builderCls.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
        } catch (Throwable nosrc) {
            HookUtils.log("lasso: setSourceCrop missing, full-screen capture");
        }
        try {
            builderCls.getMethod("setCaptureSecureLayers", Boolean.TYPE)
                    .invoke(builder, true);
        } catch (Throwable ignored) { }
        Object args = builderCls.getMethod("build").invoke(builder);
        Class<?> capCls = Class.forName("android.window.ScreenCapture");
        Object buf = capCls.getMethod("captureDisplay", argsCls).invoke(null, args);
        return bitmapFromCapture(buf, crop);
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
        // 2) @hide Display#getAddress() -- the token of the default Display.
        try {
            Object wm = ctx.getSystemService(Context.WINDOW_SERVICE);
            Object disp = wm.getClass().getMethod("getDefaultDisplay").invoke(wm);
            Object t = disp.getClass().getMethod("getAddress").invoke(disp);
            if (t != null) return t;
            HookUtils.log("lasso token: Display.getAddress=null");
        } catch (Throwable th) {
            HookUtils.log("lasso token: Display.getAddress: " + th);
        }
        // 3) @hide DisplayControl (system_server-only helper used by SystemUI).
        try {
            Class<?> dc = Class.forName("android.view.DisplayControl");
            long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
            if (ids != null && ids.length > 0) {
                Object t = dc.getMethod("getPhysicalDisplayToken", Long.TYPE)
                        .invoke(null, ids[0]);
                if (t != null) return t;
                HookUtils.log("lasso token: DisplayControl token=null");
            }
        } catch (Throwable th) {
            HookUtils.log("lasso token: DisplayControl: " + th);
        }
        return null;
    }

    /** On-device OCR hook point.  Returns extracted text or null when no
     * engine is available (the image fallback path then runs). */
    private static String tryRecognize(Context ctx, Bitmap region) {
        // TODO(P0.12): bind com.oplus.deepthinker OCR (probe pending -- adb
        // dropped before the package scan).  Keep this null for now.
        return null;
    }

    private static Uri savePng(Context ctx, Bitmap bmp, String prefix) throws Exception {
        String name = prefix + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PenBridge");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return null;
        OutputStream os = ctx.getContentResolver().openOutputStream(uri);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, cv, null, null);
        HookUtils.log("lasso saved " + name);
        return uri;
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
