package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * PanelCardExtension Module
 * 
 * Responsible for non-destructive pre-draw augmentation of the OEM stylus bottom sheet
 * dialog (COUIBottomSheetDialog). Injects "长按" and "捏握" slots with independent press
 * feedback and stock-matching typography before the first frame renders (zero flicker).
 * 
 * Includes Option A graceful fallback: if dialog DOM layout changes prevent in-place
 * card injection, triggers an overlay quick-menu fallback so gesture features remain usable.
 */
public final class PanelCardExtension {
    private static final String TAG = "PanelCardExtension";

    private PanelCardExtension() { }

    public static void install(final de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpp) {
        HookUtils.hookAll(lpp.classLoader, "android.app.Activity", "onResume",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        try {
                            String cls = hook.thisObject.getClass().getName();
                            if (!"com.oplus.ipemanager.btadsorb.pencilPanel.activity.PencilPanelActivity".equals(cls)
                                    && !"com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity".equals(cls)) {
                                return;
                            }
                            final Activity activity = (Activity) hook.thisObject;
                            schedulePanelPass(activity.getWindow().getDecorView(), activity,
                                    "com.oplus.ipemanager.btadsorb.pencilPanel.activity.PencilPanelActivity".equals(cls));
                        } catch (Throwable ignored) { }
                    }
                });

        HookUtils.hookAll(lpp.classLoader, "android.app.Dialog", "show",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        try {
                            android.view.Window w = (android.view.Window) hook.thisObject
                                    .getClass().getMethod("getWindow").invoke(hook.thisObject);
                            if (w == null || w.getDecorView() == null) return;
                            schedulePanelPass(w.getDecorView(), w.getContext());
                        } catch (Throwable ignored) { }
                    }
                });
        HookUtils.log(TAG + ": Panel card extension hooks installed");
    }

    public static void schedulePanelPass(final View root, final Context ctx) {
        schedulePanelPass(root, ctx, true);
    }

    public static void schedulePanelPass(final View root, final Context ctx,
            final boolean allowExtraRows) {
        if (root == null) return;

        ContractProbe.executeGuarded("panel_card_extension",
                new ContractProbe.PrimaryAction<Void>() {
                    @Override public Void execute() {
                        installPreDrawInterceptor(root, ctx, allowExtraRows);
                        return null;
                    }
                },
                new ContractProbe.FallbackAction<Void>() {
                    @Override public Void execute() {
                        HookUtils.log(TAG + ": Primary pre-draw failed, activating fallback passes");
                        fallbackDelayedPass(root, ctx, allowExtraRows);
                        return null;
                    }
                });
    }

    private static void installPreDrawInterceptor(final View root, final Context ctx,
            final boolean allowExtraRows) {
        final ViewTreeObserver vto = root.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                private boolean handled = false;
                private int frames = 0;

                @Override public boolean onPreDraw() {
                    if (handled) return true;
                    frames++;
                    if (subtreeHasGestureTitle(root)) {
                        handled = true;
                        removeSelf();
                        boolean injected = fixPanelAssignments(root, ctx, allowExtraRows);
                        if (!injected && allowExtraRows) {
                            HookUtils.log(TAG + ": in-place injection missed card, trigger fallback check");
                        }
                        // Cancel current draw pass so newly added views are measured and laid out
                        return false;
                    }
                    if (frames > 15) {
                        handled = true;
                        removeSelf();
                    }
                    return true;
                }

                private void removeSelf() {
                    try {
                        ViewTreeObserver obs = root.getViewTreeObserver();
                        if (obs != null && obs.isAlive()) {
                            obs.removeOnPreDrawListener(this);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }

        // Safety passes
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (subtreeHasGestureTitle(root)) {
                    fixPanelAssignments(root, ctx, allowExtraRows);
                }
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                fixPanelAssignments(root, ctx, allowExtraRows);
            }
        }, 400L);

        // Everything this pass renders is a function of time, not a one-shot read:
        //   - the OEM settles its own enabled state asynchronously (it waits on the BT stack);
        //   - the pen can drop while the panel is open;
        //   - the 翻页 summary counts Settings entries that the user edits *in a dialog above this
        //     very panel*, so nothing in the panel's own lifecycle re-reads them.
        // A fixed 12 s window was wrong twice (user report 2026-09-23: cleared every app, the row
        // still said 「已配置 3 个应用」 until the panel was reopened). So keep ticking for as long
        // as the surface is attached, at a rate that costs nothing (one tree walk per second).
        final Handler ticker = new Handler(Looper.getMainLooper());
        ticker.postDelayed(new Runnable() {
            private int ticks = 0;

            @Override public void run() {
                ticks++;
                if (ticks > TICK_LIMIT || !root.isAttachedToWindow()) return;
                // Hidden (sheet collapsed, activity stopped) — nothing to refresh, and while it
                // stays hidden the tick costs one isShown() call.
                if (!root.isShown()) {
                    ticker.postDelayed(this, TICK_MS);
                    return;
                }
                try {
                    fixPanelAssignments(root, ctx, allowExtraRows, false);
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": refresh tick error: " + th);
                    return;
                }
                ticker.postDelayed(this, TICK_MS);
            }
        }, TICK_MS);
    }

    /** Refresh cadence while a panel is on screen. Cheap, and the panel is a transient surface. */
    private static final long TICK_MS = 1000L;
    /** Hard stop so a surface that somehow never detaches cannot tick forever. */
    private static final int TICK_LIMIT = 900;

    private static void fallbackDelayedPass(final View root, final Context ctx,
            final boolean allowExtraRows) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    fixPanelAssignments(root, ctx, allowExtraRows);
                } catch (Throwable th) {
                    HookUtils.log(TAG + ": fallback pass error: " + th);
                }
            }
        }, 500L);
    }

    /**
     * Walk the decor view tree, update custom action assignment labels, and inject extra rows.
     * @return true if extra rows were found or successfully injected
     */
    public static boolean fixPanelAssignments(View root, Context ctx, boolean allowExtraRows) {
        return fixPanelAssignments(root, ctx, allowExtraRows, true);
    }

    private static boolean fixPanelAssignments(View root, Context ctx, boolean allowExtraRows,
            boolean verbose) {
        try {
            List<ViewGroup> rows = new ArrayList<>();
            List<TextView> titles = new ArrayList<>();
            List<TextView> assigns = new ArrayList<>();
            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(root);

            while (!stack.isEmpty()) {
                View v = stack.pop();
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                }
                if (!(v instanceof TextView)) continue;
                TextView tv = (TextView) v;
                String type = StylusGestureConstants.titleToGestureType(String.valueOf(tv.getText()));
                if (type != null) {
                    titles.add(tv);
                    if (tv.getParent() instanceof ViewGroup) {
                        rows.add((ViewGroup) tv.getParent());
                    }
                } else if ("assignment".equals(idName(tv))) {
                    assigns.add(tv);
                }
            }

            if (verbose) {
                HookUtils.log(TAG + ": assignments pass: titles=" + titles.size()
                        + " assigns=" + assigns.size());
            }

            for (TextView a : assigns) {
                int best = -1, bestDist = Integer.MAX_VALUE;
                for (int i = 0; i < titles.size(); i++) {
                    int d = Math.abs(centerY(a) - centerY(titles.get(i)));
                    if (d < bestDist) { bestDist = d; best = i; }
                }
                if (best < 0 || bestDist > 80) continue;
                String type = StylusGestureConstants.titleToGestureType(
                        String.valueOf(titles.get(best).getText()));
                if (type == null) continue;
                int bridge = Settings.Global.getInt(ctx.getContentResolver(),
                        StylusGestureConstants.gestureWbKey(type), -1);
                if (bridge < 100) continue;
                String want = StylusGestureConstants.getCustomLabel(bridge);
                if (want != null && !want.equals(String.valueOf(a.getText()))) {
                    a.setText(want);
                    HookUtils.log(TAG + ": assignment " + type + " -> " + want);
                }
            }

            if (allowExtraRows && !rows.isEmpty()) {
                int lastIdx = 0;
                for (int i = 1; i < rows.size(); i++) {
                    if (centerY(rows.get(i)) > centerY(rows.get(lastIdx))) lastIdx = i;
                }
                boolean usable = panelUsable(ctx, rows);
                float dim = stockDimAlpha(rows);
                if (verbose || usable != sLastPanelUsable) {
                    HookUtils.log(TAG + ": panel usability stockRows=" + rows.size()
                            + " verdict=" + (usable ? "enabled" : "DISABLED")
                            + " dim=" + dim + " stockEnabled=" + describeStockRows(rows)
                            + " hidHost=" + hidHostConnected(ctx));
                }
                sLastPanelUsable = usable;
                addExtraPanelRows(ctx, rows.get(lastIdx), usable, dim);
            }
            return true;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": assignments walk error: " + th);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Availability mirror (2026-09-23, by request)
    //
    // The OEM rows (下滑 / 双击 / 上滑触控条) are owned by the panel and grey out once the pen
    // link drops. The rows injected here are ours — nothing on the OEM side knows about them — so
    // they stayed tappable with the pen disconnected. The fix is deliberately NOT a new notion of
    // "is the pen connected": we mirror what the OEM rows are already saying, and take the HID
    // Host profile state as a second opinion.
    //
    // Verdict rules:
    //   OEM rows grey  -> disabled (this is the whole point: mirror, do not re-derive)
    //   OEM rows live  -> enabled, no matter what the BT stack says
    //   no OEM rows    -> fall back to the HID Host profile; unknown means enabled
    // A row that is greyed out while the pen actually works is a worse bug than the
    // inconsistency being fixed here, so every rule errs towards "enabled".
    // ------------------------------------------------------------------

    /** Flipped from false to true once a panel has been seen with a verdict. */
    private static boolean sLastPanelUsable = true;

    /** {@code BluetoothProfile.HID_HOST_PROFILE} — hidden from the public SDK (HookUtils has it too). */
    private static final int HID_HOST_PROFILE = 4;

    private static boolean panelUsable(Context ctx, List<ViewGroup> stockRows) {
        Boolean stock = stockRowsUsable(stockRows);
        if (stock != null && !stock) return false;      // OEM rows are grey -> we are grey
        if (stock != null) return true;                 // OEM rows are live -> stay live, whatever
                                                        // the BT stack says (a misread profile
                                                        // must never grey out a working row)
        Boolean link = hidHostConnected(ctx);           // no OEM rows to mirror: best effort
        return link == null || link;
    }

    /** False only when every OEM gesture row looks disabled — never true-because-of-a-guess. */
    private static Boolean stockRowsUsable(List<ViewGroup> stockRows) {
        if (stockRows == null || stockRows.isEmpty()) return null;
        for (ViewGroup r : stockRows) {
            if (r == null) continue;
            if (r.isEnabled() && r.getAlpha() > 0.95f) return true;
        }
        return false;
    }

    /**
     * HID Host profile state, or null when it cannot be read.
     *
     * <p>Deliberately stricter than {@code HookUtils.bluetoothConnected}: that one reports
     * "connected" whenever the HID profile and the live uhid link disagree, which is the right
     * call for "should we let the user use the pen" and the wrong one for "should this row look
     * grey". No reconciliation here — unknown stays unknown.</p>
     */
    private static Boolean hidHostConnected(Context ctx) {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null) return null;
            if (!a.isEnabled()) return false;
            return a.getProfileConnectionState(HID_HOST_PROFILE)
                    == android.bluetooth.BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable th) {
            return null;
        }
    }

    /** Alpha the OEM uses for its own greyed-out rows, so ours dims by the same amount. */
    private static float stockDimAlpha(List<ViewGroup> stockRows) {
        float min = 1f;
        for (ViewGroup r : stockRows) {
            if (r != null && r.getAlpha() < min) min = r.getAlpha();
        }
        return min < 0.95f ? min : 0.4f;
    }

    private static String describeStockRows(List<ViewGroup> stockRows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < stockRows.size(); i++) {
            ViewGroup r = stockRows.get(i);
            if (i > 0) sb.append(',');
            sb.append(r == null ? "null"
                    : (r.isEnabled() ? "on" : "off") + "@" + r.getAlpha());
        }
        return sb.append(']').toString();
    }

    private static boolean addExtraPanelRows(Context ctx, ViewGroup refInner, boolean usable,
            float dim) {
        try {
            ViewGroup card = refInner;
            while (true) {
                ViewParent p = card.getParent();
                if (!(p instanceof ViewGroup)) return false;
                ViewGroup parent = (ViewGroup) p;
                boolean siblingHasTitle = false;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View c = parent.getChildAt(i);
                    if (c != card && subtreeHasGestureTitle(c)) {
                        siblingHasTitle = true;
                        break;
                    }
                }
                if (siblingHasTitle) break;
                card = parent;
            }

            final boolean wantPageturn = shouldAddPageTurnRow(ctx);
            View extra = card.findViewWithTag("lenovo_panel_extra");
            if (extra != null) {
                for (String t : extraRowTypes(wantPageturn)) {
                    View vt = card.findViewWithTag("lenovo_extra_value_" + t);
                    if (vt instanceof TextView) {
                        // Write only on change: this runs once a second for as long as the panel is
                        // up, and an unconditional setText would re-request layout every tick.
                        CharSequence want = "pageturn".equals(t)
                                ? pageturnSummary(ctx) : extraGestureLabel(ctx, t);
                        TextView tv = (TextView) vt;
                        if (!want.toString().contentEquals(tv.getText())) {
                            tv.setText(want);
                        }
                    }
                    View row = card.findViewWithTag("lenovo_extra_row_" + t);
                    if (row != null) applyRowUsable(row, usable, dim);
                }
                return true;
            }

            if (!(card instanceof LinearLayout)) return false;
            LinearLayout rowCard = (LinearLayout) card;
            if (rowCard.getOrientation() != LinearLayout.HORIZONTAL) return false;

            View stockContent = null;
            for (int i = 0; i < rowCard.getChildCount(); i++) {
                View c = rowCard.getChildAt(i);
                if (subtreeHasGestureTitle(c)) {
                    stockContent = c;
                    break;
                }
            }
            if (stockContent == null) return false;

            if (rowCard.getBackground() == null) {
                Drawable sibBg = stockContent.getBackground();
                try {
                    ViewGroup list = (ViewGroup) rowCard.getParent();
                    for (int i = 0; i < list.getChildCount() && sibBg == null; i++) {
                        View s = list.getChildAt(i);
                        if (s != rowCard) sibBg = s.getBackground();
                    }
                } catch (Throwable ignored) { }
                if (sibBg != null) rowCard.setBackground(sibBg);
            }

            int insetL = 0, insetR = 0;
            ViewGroup.LayoutParams srcLP = stockContent.getLayoutParams();
            if (srcLP instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mp = (ViewGroup.MarginLayoutParams) srcLP;
                insetL = mp.leftMargin;
                insetR = mp.rightMargin;
            }

            int rowH = rowCard.getHeight() > 0 ? rowCard.getHeight() : rowCard.getMeasuredHeight();
            try {
                ViewGroup shelf = rowCard;
                while (shelf.getParent() instanceof ViewGroup
                        && !shelf.getClass().getName().contains("RecyclerView")) {
                    shelf = (ViewGroup) shelf.getParent();
                }
                for (int i = 0; i < shelf.getChildCount(); i++) {
                    View s = shelf.getChildAt(i);
                    int sh = s.getHeight() > 0 ? s.getHeight() : s.getMeasuredHeight();
                    if (s != rowCard && sh > 0 && subtreeHasGestureTitle(s)
                            && (rowH <= 0 || sh < rowH)) {
                        rowH = sh;
                    }
                }
            } catch (Throwable th) {
                HookUtils.log(TAG + ": sibling scan: " + th);
            }

            int divColor = 0xFF5D5D5D, divH = 1;
            for (int i = 0; i < rowCard.getChildCount(); i++) {
                View c = rowCard.getChildAt(i);
                if (c == stockContent || c.getHeight() <= 0 || c.getHeight() > 6) continue;
                if (c.getBackground() instanceof ColorDrawable) {
                    divColor = ((ColorDrawable) c.getBackground()).getColor();
                    divH = c.getHeight();
                    break;
                }
            }

            Context tctx = rowCard.getContext();
            TextView srcTitle = null, srcAssign = null;
            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(stockContent);
            while (!stack.isEmpty()) {
                View v = stack.pop();
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                }
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    if ("assignment".equals(idName(tv))) {
                        if (srcAssign == null) srcAssign = tv;
                    } else if (srcTitle == null && StylusGestureConstants.titleToGestureType(
                            String.valueOf(tv.getText())) != null) {
                        srcTitle = tv;
                    }
                }
            }
            if (srcTitle == null) return false;

            View srcArrow = findStockArrow(rowCard, stockContent);

            int padL = rowCard.getPaddingLeft();
            int padR = rowCard.getPaddingRight();
            int padT = rowCard.getPaddingTop();
            int padB = rowCard.getPaddingBottom();
            Drawable itemBg = rowCard.getBackground();

            if (itemBg instanceof LayerDrawable) {
                LayerDrawable ld = (LayerDrawable) itemBg;
                if (ld.getNumberOfLayers() > 1) {
                    for (int l = 1; l < ld.getNumberOfLayers(); l++) {
                        Drawable maskLayer = ld.getDrawable(l);
                        if (maskLayer != null) {
                            maskLayer.setAlpha(0);
                            maskLayer.setVisible(false, false);
                        }
                    }
                }
            }
            rowCard.setPadding(0, 0, 0, 0);

            final View.OnClickListener stockRowClick = getOnClickListener(rowCard);
            LinearLayout row0 = new LinearLayout(tctx);
            row0.setOrientation(LinearLayout.HORIZONTAL);
            row0.setGravity(Gravity.CENTER_VERTICAL);
            row0.setPadding(padL, padT, padR, 0);
            if (rowH > 0) row0.setMinimumHeight(rowH);
            row0.setClickable(true);
            row0.setFocusable(true);
            row0.setBackground(createRowPressDrawable(0x33FFFFFF));

            if (stockRowClick != null) {
                row0.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        stockRowClick.onClick(rowCard);
                    }
                });
            }

            rowCard.setOrientation(LinearLayout.VERTICAL);
            List<View> orig = new ArrayList<>();
            for (int i = 0; i < rowCard.getChildCount(); i++) orig.add(rowCard.getChildAt(i));
            for (View v : orig) {
                rowCard.removeView(v);
                row0.addView(v);
            }
            rowCard.addView(row0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final String[] extraTypes = extraRowTypes(wantPageturn);
            HookUtils.log(TAG + ": extra rows: ctx=" + ctxClassName(ctx) + " host="
                    + hostActivityName(ctx) + " pageturnRow=" + wantPageturn);
            for (int ei = 0; ei < extraTypes.length; ei++) {
                final String gestureType = extraTypes[ei];
                final boolean lastRow = (ei == extraTypes.length - 1);
                final int contentInL = padL + insetL;
                final int contentInR = padR + insetR;

                View divider = new View(tctx);
                divider.setBackgroundColor(divColor);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, divH);
                dlp.setMargins(contentInL, 0, contentInR, 0);
                rowCard.addView(divider, dlp);

                LinearLayout row = new LinearLayout(tctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                row.setLayoutParams(rlp);
                row.setPadding(contentInL, 0, contentInR, lastRow ? padB : 0);
                if (rowH > 0) row.setMinimumHeight(rowH);
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackground(createRowPressDrawable(0x33FFFFFF));

                TextView title = new TextView(tctx);
                copyTextStyle(title, srcTitle);
                title.setText(rowTitle(gestureType));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                row.addView(title, tlp);

                final TextView value = new TextView(tctx);
                if (srcAssign != null) {
                    copyTextStyle(value, srcAssign);
                } else {
                    copyTextStyle(value, srcTitle);
                    value.setTextColor(0x8AFFFFFF);
                }
                value.setTag("lenovo_extra_value_" + gestureType);
                value.setText("pageturn".equals(gestureType)
                        ? pageturnSummary(ctx) : extraGestureLabel(ctx, gestureType));
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vlp.setMargins(0, 0, (int) (6 * tctx.getResources().getDisplayMetrics().density), 0);
                row.addView(value, vlp);

                View arrow = cloneStockArrow(tctx, srcArrow);
                row.addView(arrow);

                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!v.isEnabled()) return;   // belt and braces; clickable is already off
                        if ("pageturn".equals(gestureType)) {
                            PageTurnConfig.showConfigDialog(ctx);
                        } else {
                            showExtraGestureDialog(ctx, gestureType, value);
                        }
                    }
                });

                row.setTag("lenovo_extra_row_" + gestureType);
                applyRowUsable(row, usable, dim);
                rowCard.addView(row);
            }

            rowCard.setTag("lenovo_panel_extra");
            HookUtils.log(TAG + ": 长按/捏握 appended under card with independent press and continuous card background");
            return true;
        } catch (Throwable th) {
            HookUtils.log(TAG + ": addExtraPanelRows error: " + th);
            return false;
        }
    }

    /**
     * Grey out (or restore) one injected row, the way the OEM greys its own.
     *
     * <p>Alpha on the row carries down to the title, the value text and the chevron, so a single
     * call dims the whole thing; {@code clickable=false} also disarms the press StateListDrawable
     * that would otherwise still flash on touch.</p>
     */
    private static void applyRowUsable(View row, boolean usable, float dim) {
        if (row == null) return;
        float wantAlpha = usable ? 1f : dim;
        if (row.isEnabled() != usable) {
            row.setEnabled(usable);
            row.setClickable(usable);
            row.setFocusable(usable);
        }
        // Idempotent on purpose: this is called on every refresh tick.
        if (Math.abs(row.getAlpha() - wantAlpha) > 0.001f) row.setAlpha(wantAlpha);
    }

    private static Drawable createRowPressDrawable(int pressColor) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(pressColor));
        sld.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return sld;
    }

    private static View.OnClickListener getOnClickListener(View view) {
        if (view == null) return null;
        try {
            Field liField = View.class.getDeclaredField("mListenerInfo");
            liField.setAccessible(true);
            Object li = liField.get(view);
            if (li != null) {
                Field clickField = li.getClass().getDeclaredField("mOnClickListener");
                clickField.setAccessible(true);
                return (View.OnClickListener) clickField.get(li);
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": getOnClickListener: " + th);
        }
        return null;
    }

    private static View findStockArrow(ViewGroup card, View content) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.push(content);
        if (card != null && card != content) q.push(card);
        while (!q.isEmpty()) {
            View v = q.pop();
            String id = idName(v);
            if (id != null && (id.contains("arrow") || id.contains("more")
                    || id.contains("chevron") || id.contains("tail"))) {
                return v;
            }
            if (v instanceof ImageView && v != content) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.push(g.getChildAt(i));
            }
        }
        return null;
    }

    private static View cloneStockArrow(Context ctx, View src) {
        ImageView iv = new ImageView(ctx);
        int w = src != null && src.getWidth() > 0 ? src.getWidth() : (int) (8 * ctx.getResources().getDisplayMetrics().density);
        int h = src != null && src.getHeight() > 0 ? src.getHeight() : (int) (14 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        iv.setLayoutParams(lp);
        if (src instanceof ImageView) {
            Drawable d = ((ImageView) src).getDrawable();
            if (d != null && d.getConstantState() != null) {
                iv.setImageDrawable(d.getConstantState().newDrawable(ctx.getResources()));
                return iv;
            }
        }
        iv.setImageDrawable(createChevronDrawable(ctx));
        return iv;
    }

    private static Drawable createChevronDrawable(Context ctx) {
        final float density = ctx.getResources().getDisplayMetrics().density;
        return new Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) {{
                setColor(0x8AFFFFFF);
                setStyle(android.graphics.Paint.Style.STROKE);
                setStrokeWidth(1.5f * density);
                setStrokeCap(android.graphics.Paint.Cap.ROUND);
                setStrokeJoin(android.graphics.Paint.Join.ROUND);
            }};

            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect bounds = getBounds();
                if (bounds.width() <= 0 || bounds.height() <= 0) return;
                float midY = bounds.exactCenterY();
                float tipX = bounds.right - 2 * density;
                float baseLeft = bounds.left + 2 * density;
                float halfSpan = Math.min(bounds.height() / 3f, 5f * density);

                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(baseLeft, midY - halfSpan);
                path.lineTo(tipX, midY);
                path.lineTo(baseLeft, midY + halfSpan);
                canvas.drawPath(path, paint);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private static void copyTextStyle(TextView dst, TextView src) {
        dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
        dst.setTextColor(src.getCurrentTextColor());
        dst.setTypeface(src.getTypeface());
        dst.setPadding(src.getPaddingLeft(), src.getPaddingTop(),
                src.getPaddingRight(), src.getPaddingBottom());
    }

    public static boolean subtreeHasGestureTitle(View v) {
        if (v instanceof TextView) {
            return StylusGestureConstants.titleToGestureType(
                    String.valueOf(((TextView) v).getText())) != null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (subtreeHasGestureTitle(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static int centerY(View v) {
        int[] out = new int[2];
        v.getLocationOnScreen(out);
        int h = v.getHeight() > 0 ? v.getHeight() : v.getMeasuredHeight();
        return out[1] + h / 2;
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return null;
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable th) {
            return null;
        }
    }

    private static String extraGestureLabel(Context ctx, String type) {
        int v = Settings.Global.getInt(ctx.getContentResolver(),
                StylusGestureConstants.gestureWbKey(type), -1);
        if (v >= 100) return StylusGestureConstants.getCustomLabel(v);
        return "无";
    }

    private static void showExtraGestureDialog(Context ctx, final String type,
            final TextView valueView) {
        try {
            Intent i = new Intent();
            i.setClassName("com.oplus.ipemanager", StylusGestureConstants.GESTURE_ACTIVITY_CLASS);
            i.putExtra("click_type", type);
            Activity act = activityOf(ctx);
            if (act != null) {
                act.startActivity(i);
            } else {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
            HookUtils.log(TAG + ": extra " + type + " -> native gesture page");
        } catch (Throwable th) {
            HookUtils.log(TAG + ": extra launch error: " + th);
        }
    }

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Per-app page-turn trigger row (device-center pen panel)
    //
    // History: the row used to be injected through a SEPARATE path that required ctx to be an
    // Activity of class PencilPanelActivity. But the visible stylus panel is a
    // COUIBottomSheetDialog (a Dialog) whose Context is a ContextThemeWrapper, not the Activity,
    // and the Activity's own decor view contains no panel content at all — so the old gate could
    // never match the real panel and the row was silently skipped (logcat:
    // "pageturn: gesture card not found"). Meanwhile 长按/捏握 injected fine because they ride the
    // in-card path below.
    //
    // Fix: append 翻页 through that SAME in-card path, so wherever 长按/捏握 render, the page-turn
    // row renders too — no dependency on host-Activity guessing.
    // ------------------------------------------------------------------
    private static boolean shouldAddPageTurnRow(Context ctx) {
        return true;
    }

    private static String ctxClassName(Context ctx) {
        return ctx == null ? "null" : ctx.getClass().getName();
    }

    private static String hostActivityName(Context ctx) {
        Activity a = activityOf(ctx);
        return a == null ? "none" : a.getClass().getName();
    }

    /** Row types appended under the gesture card: 长按 / 捏握, plus 翻页 on the pen panel only. */
    private static String[] extraRowTypes(boolean wantPageturn) {
        return wantPageturn
                ? new String[]{"long_press", "squeeze", "pageturn"}
                : new String[]{"long_press", "squeeze"};
    }

    private static String rowTitle(String rowType) {
        if ("pageturn".equals(rowType)) return "翻页功能触发方式";
        return "long_press".equals(rowType) ? "长按" : "捏握";
    }

    private static String pageturnSummary(Context ctx) {
        int n = PageTurnConfig.allConfigured(ctx).size();
        return n == 0 ? "点击配置" : ("已配置 " + n + " 个应用");
    }
}
