package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * FloatingQuickMenuFallback (Option A Fallback)
 * 
 * Activated when OEM OTA updates disrupt the bottom-sheet layout or when in-place card
 * injection fails. Displays a clean, translucent stylus gesture action sheet so users
 * retain 100% control over all 5 gestures.
 */
public final class FloatingQuickMenuFallback {
    private static final String TAG = "FloatingQuickMenuFallback";

    private FloatingQuickMenuFallback() { }

    public static void show(final Context ctx) {
        if (ctx == null) return;
        try {
            final AlertDialog.Builder b = new AlertDialog.Builder(ctx);
            final float density = ctx.getResources().getDisplayMetrics().density;

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xF0202020);
            int pad = (int) (20 * density);
            root.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText("手写笔手势快捷菜单 (安全降级模式)");
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            root.addView(title);

            TextView desc = new TextView(ctx);
            desc.setText("检测到系统底栏协议变更，已自动启用独立保底菜单");
            desc.setTextColor(0x8AFFFFFF);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            desc.setPadding(0, (int) (4 * density), 0, (int) (14 * density));
            root.addView(desc);

            ScrollView sv = new ScrollView(ctx);
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);

            final String[][] gestures = {
                    {"下滑触控条", "single_click"},
                    {"双击触控条", "double_click"},
                    {"上滑触控条", "long_click_v2"},
                    {"长按手势", "long_press"},
                    {"捏握手势", "squeeze"}
            };

            for (final String[] g : gestures) {
                final String label = g[0];
                final String type = g[1];

                LinearLayout item = new LinearLayout(ctx);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(0, (int) (12 * density), 0, (int) (12 * density));

                TextView tvName = new TextView(ctx);
                tvName.setText(label);
                tvName.setTextColor(0xDEFFFFFF);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                item.addView(tvName, lpName);

                int val = Settings.Global.getInt(ctx.getContentResolver(),
                        StylusGestureConstants.gestureWbKey(type), -1);
                String valStr = StylusGestureConstants.getCustomLabel(val);
                if (valStr == null) valStr = "系统默认";

                TextView tvVal = new TextView(ctx);
                tvVal.setText(valStr);
                tvVal.setTextColor(0x8AFFFFFF);
                tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                item.addView(tvVal);

                item.setClickable(true);
                item.setFocusable(true);
                item.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            Intent i = new Intent();
                            i.setClassName("com.oplus.ipemanager",
                                    StylusGestureConstants.GESTURE_ACTIVITY_CLASS);
                            i.putExtra("click_type", type);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(i);
                        } catch (Throwable th) {
                            HookUtils.log(TAG + ": launch error: " + th);
                        }
                    }
                });

                list.addView(item);

                View div = new View(ctx);
                div.setBackgroundColor(0x33FFFFFF);
                list.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));
            }

            sv.addView(list);
            root.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (260 * density)));

            b.setView(root);
            final AlertDialog d = b.create();
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            d.show();
            HookUtils.log(TAG + ": Option A floating fallback menu presented successfully");
        } catch (Throwable th) {
            HookUtils.log(TAG + ": show failed: " + th);
        }
    }
}
