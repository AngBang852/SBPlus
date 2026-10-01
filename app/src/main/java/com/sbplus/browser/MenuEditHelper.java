package com.sbplus.browser;

import android.view.MenuItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.sbplus.browser.XC_MethodHook;

/**
 * Show/hide (add/remove) icons in Samsung Internet's "More" (⋮) grid menu.
 *
 * Data model (reverse-engineered):
 *  - CustomizeMenuModel.getAllMenus()  -> the full icon library (every possible item).
 *  - getAllMenus() minus the currently-available list = the "addable" (hidden) icons.
 *  - Adding  = MenuReorderHelper.addItem(item)     (checked=true, appended, saved)
 *  - Removing = MenuReorderHelper.removeItem(item)  (checked=false, dropped, saved)
 *
 * Persistence reuses Samsung's CustomizeToolbarManager.saveToolsMenu(list).
 */
public final class MenuEditHelper {

    private MenuEditHelper() {}

    private static ClassLoader sCl;

    public static void setClassLoader(ClassLoader cl) { sCl = cl; }

    private static Object currentModel() {
        try {
            Class<?> mgrCls = MainHook.loadClassSafely(
                    "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", sCl);
            Object mgr = MainHook.callStaticMethod(mgrCls, "getInstance");
            return MainHook.callMethod(mgr, "getCurrentInstanceModel");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] currentModel error: " + t);
            return null;
        }
    }

    private static List<MenuItem> allMenus() {
        Object model = currentModel();
        if (model == null) return new ArrayList<>();
        Object r = MainHook.callMethod(model, "getAllMenus");
        return r == null ? new ArrayList<MenuItem>() : (List<MenuItem>) r;
    }

    private static List<MenuItem> availableMenus() {
        Object model = currentModel();
        if (model == null) return new ArrayList<>();
        Object r = MainHook.callMethod(model, "getToolsAvailableMenus");
        return r == null ? new ArrayList<MenuItem>() : (List<MenuItem>) r;
    }

    /** Icons currently not shown (never added, or added but hidden). Sorted by title. */
    public static List<MenuItem> getAddableMenus() {
        try {
            List<MenuItem> all = allMenus();
            List<MenuItem> avail = availableMenus();
            HashSet<Integer> availIds = new HashSet<>();
            if (avail != null) for (MenuItem m : avail) availIds.add(m.getItemId());
            ArrayList<MenuItem> result = new ArrayList<>();
            if (all != null) {
                for (MenuItem m : all) {
                    if (m == null) continue;
                    boolean present = availIds.contains(m.getItemId());
                    if (!present) result.add(m);      // never added -> addable
                    else if (!m.isChecked()) result.add(m); // added but hidden -> addable
                }
            }
            return result;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] getAddableMenus error: " + t);
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------------------------
    // Grid "+" add-item (appended as the last grid cell, not draggable/removable).
    // ---------------------------------------------------------------------------------

    private static volatile boolean sAddItemHooked = false;
    private static volatile ClassLoader sAddItemCl = null;
    private static volatile int sIconCount = -1;
    // 2026-10-04:sPadCount 已删除 —— 它恒为 0(唯一赋值处就是置 0),只被拼进两条
    // 日志,是分页功能关闭后遗留的死变量。连同日志里的 "pad=" 字段一并清理。
    /** Hook adapter getItemCount/getItem/onBindViewHolder to render a trailing "+" cell. */
    public static void installGridAddItem(Object adapter, ClassLoader cl) {
        if (adapter == null) return;
        if (sAddItemHooked && sAddItemCl == cl) return;
        try {
            final Class<?> adapterCls = MainHook.loadClassSafely(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuRecyclerAdapter", cl);

            // getItemCount() -> icons + "+". No trailing blank fillers (paging disabled).
            MainHook.findAndHookMethod(adapterCls, "getItemCount",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        int icons = (Integer) p.getResult();
                        sIconCount = icons;
                        p.setResult(icons + 1); // icons + the "+" cell
                    }
                });

            // onCreateViewHolder(ViewGroup,I) after -> set a precise column width BEFORE the
            // LayoutManager measures the item, so match_parent never resolves to the content
            // width (which breaks 5-columns-per-page alignment).
            try {
                MainHook.findAndHookMethod(adapterCls, "onCreateViewHolder",
                        MainHook.loadClassSafely("android.view.ViewGroup", cl), int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            Object holder = p.getResult();
                            if (holder == null) return;
                            android.view.View iv = (android.view.View) MainHook.getObjectField(holder, "itemView");
                            if (iv == null) return;
                            try {
                                // 2026-10-04 修复:列数不再硬编码 5。
                                // 原实现 itemW = screenW / 5 —— 横屏、分屏、平板或宿主
                                // 改了网格列数时,添加格子的宽度会与真实网格错位(要么
                                // 撑出屏幕、要么明显偏窄)。这里改为:
                                //   ① 若 RecyclerView 的 LayoutManager 暴露 spanCount,用它;
                                //   ② 否则用"容器可用宽度 ÷ 已有子项宽度"推算列数;
                                //   ③ 都拿不到才退回 5(与改动前行为一致)。
                                int screenW = iv.getResources().getDisplayMetrics().widthPixels;
                                int cols = 0;
                                try {
                                    android.view.View anchor = MenuReorderHelper.menuAnchorView();
                                    if (anchor != null) {
                                        int w = anchor.getWidth();
                                        if (w > 0) {
                                            // 用已有子项的实测宽度推算列数(最可靠:直接反映当前网格)
                                            if (anchor instanceof android.view.ViewGroup) {
                                                android.view.ViewGroup vg = (android.view.ViewGroup) anchor;
                                                for (int ci = 0; ci < vg.getChildCount(); ci++) {
                                                    android.view.View ch = vg.getChildAt(ci);
                                                    if (ch != null && ch.getWidth() > 0) {
                                                        int n = Math.round((float) w / ch.getWidth());
                                                        if (n >= 1 && n <= 12) { cols = n; }
                                                        break;
                                                    }
                                                }
                                            }
                                            if (cols == 0) cols = 5;   // 拿不到子项宽度 → 沿用旧假设
                                            screenW = w;               // 用真实容器宽而非屏幕宽
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                if (cols <= 0) cols = 5;
                                int itemW = Math.max(1, screenW / cols);
                                android.view.ViewGroup.LayoutParams lp = iv.getLayoutParams();
                                if (lp == null) {
                                    lp = new android.view.ViewGroup.LayoutParams(itemW,
                                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                                } else {
                                    lp.width = itemW;
                                }
                                iv.setLayoutParams(lp);
                            } catch (Throwable ignore) {}
                        }
                    });
            } catch (Throwable t) {
                MainModule.logMsg("[SBPlus] onCreateViewHolder width hook failed: " + t);
            }

            // getItem(I) -> return null for the "+" cell and blank fillers (no backing MenuItem).
            MainHook.findAndHookMethod(adapterCls, "getItem", int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        int pos = (Integer) p.args[0];
                        if (sIconCount >= 0 && pos >= sIconCount) {
                            p.setResult(null); // "+" or filler -> no backing MenuItem
                        }
                    }
                });

            // onBindViewHolder(ViewHolder, I) after -> style the add cell, blank the fillers.
            // ViewHolder 类名被 R8 混淆(旧版 g1),通过 onBindViewHolder 方法参数类型反推
            Class<?> vhCls = null;
            for (java.lang.reflect.Method m : adapterCls.getDeclaredMethods()) {
                if ("onBindViewHolder".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[1] == int.class) {
                    vhCls = m.getParameterTypes()[0];
                    break;
                }
            }
            if (vhCls == null) throw new ClassNotFoundException("ViewHolder for onBindViewHolder");
            MainHook.findAndHookMethod(adapterCls, "onBindViewHolder",
                    vhCls,
                    int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            int pos = (Integer) p.args[1];
                            Object holder = p.args[0];
                            int icons = sIconCount;

                            // The "+" cell sits immediately after the last icon.
                            if (pos == icons) {
                                android.widget.ImageView icon =
                                        (android.widget.ImageView) MainHook.getObjectField(holder, "mIcon");
                                android.widget.TextView text =
                                        (android.widget.TextView) MainHook.getObjectField(holder, "mText");
                                if (icon != null) {
                                    icon.setVisibility(android.view.View.VISIBLE);
                                    icon.setImageResource(android.R.drawable.ic_input_add);
                                    icon.setColorFilter(android.graphics.Color.rgb(0xDD, 0xDD, 0xDD));
                                }
                                if (text != null) {
                                    text.setVisibility(android.view.View.VISIBLE);
                                    text.setText("添加");
                                }
                                android.view.View badge = (android.view.View) MainHook.getObjectField(holder, "mBadge");
                                if (badge != null) badge.setVisibility(android.view.View.GONE);
                                android.view.View div = (android.view.View) MainHook.getObjectField(holder, "mDivider");
                                if (div != null) div.setVisibility(android.view.View.GONE);
                                final android.view.View itemView = (android.view.View) MainHook.getObjectField(holder, "itemView");
                                if (itemView != null) {
                                    itemView.setClickable(true);
                                    itemView.setOnClickListener(new android.view.View.OnClickListener() {
                                        @Override public void onClick(android.view.View v) {
                                            MainModule.logMsg("[SBPlus] add cell CLICKED");
                                            android.content.Context ctx = itemView.getContext();
                                            MenuAddButtonHelper.showAddDialog(ctx);
                                        }
                                    });
                                }
                                MainModule.logMsg("[SBPlus] add cell rendered at pos " + pos
                                        + " (icons=" + icons + ")");
                                return;
                            }

                            // Blank filler cells AFTER the "+" (pad the tail to a full page).
                            if (icons >= 0 && pos > icons) {
                                MainModule.logMsg("[SBPlus] blank filler at pos " + pos
                                        + " (icons=" + icons + ")");
                                android.widget.ImageView icon =
                                        (android.widget.ImageView) MainHook.getObjectField(holder, "mIcon");
                                android.widget.TextView text =
                                        (android.widget.TextView) MainHook.getObjectField(holder, "mText");
                                if (icon != null) icon.setVisibility(android.view.View.INVISIBLE);
                                if (text != null) text.setVisibility(android.view.View.INVISIBLE);
                                android.view.View badge = (android.view.View) MainHook.getObjectField(holder, "mBadge");
                                if (badge != null) badge.setVisibility(android.view.View.GONE);
                                android.view.View div = (android.view.View) MainHook.getObjectField(holder, "mDivider");
                                if (div != null) div.setVisibility(android.view.View.GONE);
                                return;
                            }

                            // Real icon cell: restore icons hidden by a recycled blank-filler
                            // holder, then paint the ✕ mark in edit mode.
                            android.widget.ImageView ric =
                                    (android.widget.ImageView) MainHook.getObjectField(holder, "mIcon");
                            android.widget.TextView rtx =
                                    (android.widget.TextView) MainHook.getObjectField(holder, "mText");
                            if (ric != null) ric.setVisibility(android.view.View.VISIBLE);
                            if (rtx != null) rtx.setVisibility(android.view.View.VISIBLE);
                            android.view.View rbd = (android.view.View) MainHook.getObjectField(holder, "mBadge");
                            if (rbd != null) rbd.setVisibility(android.view.View.VISIBLE);
                            MenuReorderHelper.decorateBoundItem(holder, pos);
                        } catch (Throwable t) {
                            MainModule.logMsg("[SBPlus] add cell bind error: " + t);
                        }
                    }
                });

            sAddItemHooked = true;
            sAddItemCl = cl;
            MainModule.logMsg("[SBPlus] grid add-item hooks installed");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] installGridAddItem error: " + t);
        }
    }
}
