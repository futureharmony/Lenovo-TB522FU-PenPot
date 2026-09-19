package com.aclaniakea.colorosporttuning;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bridge action 111: full-screen handwriting scratchpad overlay.
 *
 * <p>Runs in system_server (like SystemUI), so we may add our own
 * TYPE_APPLICATION_OVERLAY window without any user permission.  The window
 * is a plain android.widget stack -- a toolbar (close / undo / eraser /
 * clear / colour / save) over a freehand draw view that records strokes.
 * Saving writes a PNG into Pictures/PenBridge via MediaStore.
 *
 * <p>The toggle action reuses the same gesture: while the pad is open the
 * gesture closes it, so the pen never gets stuck in the pad. */
final class HandwrittenNoteOverlay {

    private static FrameLayout root;
    private static DrawView pad;

    private HandwrittenNoteOverlay() { }

    /** Called from runCustomAction on the main looper. */
    static synchronized void toggle(final Context ctx) {
        if (root != null) {
            hide();
            return;
        }
        try {
            show(ctx);
        } catch (Throwable th) {
            HookUtils.log("note pad show: " + th);
        }
    }

    private static void show(Context ctx) {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xF2FFFFFF);
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- toolbar -----------------------------------------------------
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFEDEDED);
        bar.setPadding(24, 20, 24, 20);
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = barItem(ctx, "关闭");
        TextView undo = barItem(ctx, "撤销");
        TextView eraser = barItem(ctx, "橡皮");
        TextView clear = barItem(ctx, "清空");
        TextView save = barItem(ctx, "保存");
        bar.addView(close);
        TextView sep = barItem(ctx, "|");
        bar.addView(sep);
        bar.addView(undo);
        bar.addView(eraser);
        bar.addView(clear);
        bar.addView(save);

        // Colour dots.
        final int[] colors = {0xFF1A1A1A, 0xFFE53935, 0xFF1E88E5, 0xFFFDD835};
        final TextView[] dots = new TextView[colors.length];
        for (int i = 0; i < colors.length; i++) {
            final TextView dot = new TextView(ctx);
            dot.setText("●");
            dot.setTextSize(22);
            dot.setTextColor(colors[i]);
            dot.setPadding(22, 0, 22, 0);
            final int sel = i;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pad.setEraser(false);
                    pad.setPenColor(colors[sel]);
                    markDot(dots, sel);
                }
            });
            dots[i] = dot;
            bar.addView(dot);
        }

        final DrawView draw = new DrawView(ctx);
        pad = draw;
        column.addView(draw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        markDot(dots, 0);

        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hide();
            }
        });
        undo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                draw.undo();
            }
        });
        eraser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                draw.setEraser(true);
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                draw.clear();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(ctx, draw);
            }
        });

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.FILL;
        wm.addView(root, lp);
        HookUtils.log("note pad shown");
    }

    private static TextView barItem(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(0xFF333333);
        tv.setPadding(28, 8, 28, 8);
        return tv;
    }

    private static void markDot(TextView[] dots, int sel) {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setAlpha(i == sel ? 1f : 0.35f);
        }
    }

    private static synchronized void hide() {
        try {
            if (root != null) {
                Context ctx = root.getContext();
                WindowManager wm = (WindowManager)
                        ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(root);
            }
        } catch (Throwable th) {
            HookUtils.log("note pad hide: " + th);
        }
        root = null;
        pad = null;
        HookUtils.log("note pad hidden");
    }

    private static void save(Context ctx, DrawView draw) {
        try {
            Bitmap bmp = Bitmap.createBitmap(draw.getWidth(), draw.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.WHITE);
            draw.draw(c);
            String name = "pen_note_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date()) + ".png";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PenBridge");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            cv.clear();
            cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, cv, null, null);
            Toast.makeText(ctx, "手写便签已保存到 Pictures/PenBridge/" + name,
                    Toast.LENGTH_SHORT).show();
            HookUtils.log("note pad saved " + name);
        } catch (Throwable th) {
            HookUtils.log("note pad save: " + th);
            Toast.makeText(ctx, "保存失败: " + th, Toast.LENGTH_SHORT).show();
        }
    }

    /** Freehand stroke layer.  Stylus and finger both draw; palm rejection is
     * left to the framework (pen input wins over touch on this panel). */
    private static final class DrawView extends View {
        private static final class Stroke {
            final Path path = new Path();
            final Paint paint = new Paint();
        }

        private final List<Stroke> strokes = new ArrayList<Stroke>();
        private Stroke current;
        private boolean eraser;
        private int penColor = 0xFF1A1A1A;
        private final PathMeasureScratch scratch = new PathMeasureScratch();

        DrawView(Context ctx) {
            super(ctx);
        }

        void setPenColor(int color) {
            eraser = false;
            penColor = color;
        }

        void setEraser(boolean on) {
            eraser = on;
            if (on) current = null;
        }

        void undo() {
            if (!strokes.isEmpty()) {
                strokes.remove(strokes.size() - 1);
                invalidate();
            }
        }

        void clear() {
            strokes.clear();
            current = null;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            for (Stroke s : strokes) {
                canvas.drawPath(s.path, s.paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (eraser) return true;
                    current = newStroke();
                    current.path.moveTo(x, y);
                    applyWidth(current, event);
                    strokes.add(current);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (eraser) {
                        eraseAt(x, y, event);
                        return true;
                    }
                    if (current != null) {
                        int hist = event.getHistorySize();
                        for (int i = 0; i < hist; i++) {
                            current.path.lineTo(event.getHistoricalX(i),
                                    event.getHistoricalY(i));
                        }
                        current.path.lineTo(x, y);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!eraser && current != null) {
                        current.path.lineTo(x, y);
                    }
                    current = null;
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        private Stroke newStroke() {
            Stroke s = new Stroke();
            s.paint.setAntiAlias(true);
            s.paint.setStyle(Paint.Style.STROKE);
            s.paint.setStrokeCap(Paint.Cap.ROUND);
            s.paint.setStrokeJoin(Paint.Join.ROUND);
            s.paint.setColor(penColor);
            s.paint.setStrokeWidth(6f);
            return s;
        }

        /** Eraser: drop every stroke whose control points come near the touch. */
        private void eraseAt(float x, float y, MotionEvent event) {
            float radius = 28f;
            boolean changed = false;
            for (int i = strokes.size() - 1; i >= 0; i--) {
                Stroke s = strokes.get(i);
                RectF bounds = new RectF();
                s.path.computeBounds(bounds, true);
                bounds.inset(-radius, -radius);
                if (bounds.contains(x, y) && scratch.near(s.path, x, y, radius)) {
                    strokes.remove(i);
                    changed = true;
                }
            }
            if (changed) invalidate();
        }

        private void applyWidth(Stroke s, MotionEvent event) {
            float pressure = event.getPressure();
            if (pressure <= 0f) pressure = 0.5f;
            s.paint.setStrokeWidth(3f + 9f * pressure);
        }

        /** Segments samples of a path so a near-test can run without the
         * (hidden) PathMeasure API complexity. */
        private static final class PathMeasureScratch {
            boolean near(Path path, float x, float y, float radius) {
                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(path, false);
                float[] pos = new float[2];
                float step = 8f;
                for (float d = 0; d <= pm.getLength(); d += step) {
                    pm.getPosTan(d, pos, null);
                    float dx = pos[0] - x, dy = pos[1] - y;
                    if (dx * dx + dy * dy <= radius * radius) return true;
                }
                return false;
            }
        }
    }
}
