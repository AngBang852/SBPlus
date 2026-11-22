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

    /** 已装过 add-item hook 的 adapter 实例（2026-11-21，按 identityHashCode）。 */
    private static final java.util.Set<Integer> sAddItemHookedIds =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());

    private static volatile int sIconCount = -1;

    /** getItemCount 诊断计数（2026-11-21）。 */
    private static int sCountLogged;

    /** 「添加」格子的 MenuItem id（2026-11-21）。 */
    private static final int ADD_ITEM_ID = 0x5B9A0001;
    private static volatile int sAddMenuId;

    /** 注入重入保护（2026-11-21）。 */
    private static volatile boolean sInjecting;
    // 2026-10-04:sPadCount 已删除 —— 它恒为 0(唯一赋值处就是置 0),只被拼进两条
    // 日志,是分页功能关闭后遗留的死变量。连同日志里的 "pad=" 字段一并清理。
    /** Hook adapter getItemCount/getItem/onBindViewHolder to render a trailing "+" cell. */
    /** 读对象的 List 字段（沿继承链）；读不到返回 null。 */
    private static java.util.List<Object> readListField(Object obj, String name) {
        if (obj == null) return null;
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> l = (java.util.List<Object>) v;
                    return l;
                }
                return null;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
    public static void installGridAddItem(Object adapter, ClassLoader cl) {
        MainModule.logMsg("[SBPlus] addItem ENTER adapter="
                + (adapter == null ? "null" : adapter.getClass().getSimpleName())
                + " hooked=" + sAddItemHookedIds.size());
        if (adapter == null) return;
        // 2026-11-21：原来只要装过一次就永久跳过，而第一次是在
        // updateContentView 里用**空 recycler 的 adapter** 装的 ——
        // 那个 adapter 不是真正显示的，hook 挂上去也收不到调用。
        // 改为按「adapter 类名」判断，不同实例可以各装一次。
        // 2026-11-21：不能「装过一次就跳过」。
        //
        // 宿主每次打开菜单都会**重建** adapter 的 mPrimaryItems（从菜单模型重新填充），
        // 我们注入的「添加」项会被冲掉。所以每次 install 都要检查列表里还有没有，
        // 没有就重新注入。hook 本身重复注册是幂等的（同 id 会原子替换），
        // 真正需要防重复的是**列表项**，不是 hook。
        int aid = System.identityHashCode(adapter);
        MainModule.logMsg("[SBPlus] addItem ENTER aid=" + aid);
        try {
            // 2026-11-21 修复「网格菜单没有添加按钮」：
            //
            // 原来硬编码 "MoreMenuRecyclerAdapter" 类名去找方法。但实测
            // getItemCount 的 hook 装上了却**从不被调用** —— 因为该方法定义在
            // 父类（RecyclerView.Adapter）里，getDeclaredMethods 查不到，
            // findAndHookMethod 静默失败。
            //
            // 改用传入的真实 adapter 实例的类（那是 RecyclerView 实际调用的
            // 那个对象），并沿继承链找方法。
            final Class<?> adapterCls = adapter.getClass();
            MainModule.logMsg("[SBPlus] add-item adapter class: " + adapterCls.getName());

            // 2026-11-21 换实现路线：**不再 hook getItemCount**。
            //
            // <p>实测 Pine 的 inline hook 挂不上这个短方法：
            // 日志打印了 `getItemCount declared in MoreMenuRecyclerAdapter`
            // （方法确实在该类上），但 `getItemCount hook installed on` 从不打印，
            // 说明 findAndHookMethod 内部静默失败。
            //
            // <p>新路线：**直接改 adapter 的数据源**。
            // 已通过反射确认 adapter 有字段：
            //   mPrimaryItems:List   ← 主菜单图标列表
            //   mSecondaryItems:List
            //   mMenu:Menu
            // 只要往 mPrimaryItems 末尾塞一个「添加」占位项，getItemCount 自然变成 28，
            // RecyclerView 就会为它创建 ViewHolder —— 不需要 hook 任何方法。
            //
            // <p>占位项用 Menu.add(...) 造一个真 MenuItem（带 id/icon/title），
            // 这样 onBindViewHolder 里取 mIcon/mText 都不会空。
            final java.util.List<Object> primaryItems = readListField(adapter, "mPrimaryItems");
            if (primaryItems != null) {
                MainModule.logMsg("[SBPlus] mPrimaryItems size=" + primaryItems.size());
                // 先记下「注入前的图标数」—— onBindViewHolder 用它判断「哪一格是添加格」。
                sIconCount = primaryItems.size();
                try {
                    android.view.Menu menu = (android.view.Menu)
                            MainHook.getObjectField(adapter, "mMenu");

                    // 2026-11-21 修掉 SIGSEGV 的真正根因：
                    //
                    // <p><b>原来为什么崩</b>：menu.add() 会触发宿主的 menu-changed
                    // 回调 → notifyDataSetChanged → 重进 onBindViewHolder → 重进本 hook。
                    // 而防重入标志 sAddMenuId 是在 menu.add() **之后**才赋值的，
                    // 重入时它还是 0 → 又 add 一次 → 无限递归（实测 47 层栈帧）→ SIGSEGV。
                    //
                    // <p><b>修法</b>：① 用独立的 sInjecting 标志，在 add **之前**置位；
                    // ② 判重改成查「列表里有没有 ADD_ITEM_ID」，而不是查全局标志
                    //    （宿主重建 adapter 后列表被重新填充，全局标志会误判"已注入"）。
                    boolean alreadyInjected = false;
                    for (Object o : primaryItems) {
                        if (o instanceof MenuItem
                                && ((MenuItem) o).getItemId() == ADD_ITEM_ID) {
                            alreadyInjected = true;
                            break;
                        }
                    }
                    // 2026-11-21 停用：改用 AddCellAdapter 包装方案（见 MainHook.wrapAdapterWithAddCell）。
                    // 注入外来 MenuItem 会让宿主 onBindViewHolder 解引用野指针 → SIGSEGV。
                    if (false && menu != null && !alreadyInjected && !sInjecting) {
                        sInjecting = true;
                        try {
                            MenuItem addItem = menu.add(0, ADD_ITEM_ID, 0, "添加");
                            addItem.setIcon(android.R.drawable.ic_input_add);
                            addItem.setCheckable(false);
                            // 2026-11-21 用户要求：和 LSPosed 版一致 ——
                            // 放在**左右滑动列表的最后一位**。
                            primaryItems.add(addItem);
                            int newPos = primaryItems.size() - 1;
                            MainModule.logMsg("[SBPlus] add-cell injected at LAST pos "
                                    + newPos + ", size=" + primaryItems.size());
                            try {
                                MainHook.callMethod(adapter, "notifyItemInserted", newPos);
                            } catch (Throwable t) {
                                MainModule.logMsg("[SBPlus] notifyItemInserted failed: " + t);
                            }
                        } finally {
                            sInjecting = false;
                        }
                    }
                } catch (Throwable t) {
                    MainModule.logMsg("[SBPlus] inject add-cell failed: " + t);
                }
            }

            // onCreateViewHolder(ViewGroup,I) after -> set a precise column width BEFORE the
            // LayoutManager measures the item, so match_parent never resolves to the content
            // width (which breaks 5-columns-per-page alignment).
            try {
                MainHook.findAndHookMethodInHierarchy(adapterCls, "onCreateViewHolder",
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
            // 2026-11-22：31.0.2.39 的 adapter 没有 getItem(int)（方法被混淆/移除），
            // 找不到就跳过这一步，不能让它中断后面的 onBindViewHolder/样式 hook。
            try {
                MainHook.findAndHookMethodInHierarchy(adapterCls, "getItem", int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            int pos = (Integer) p.args[0];
                            if (sIconCount >= 0 && pos >= sIconCount) {
                                p.setResult(null); // "+" or filler -> no backing MenuItem
                            }
                        }
                    });
            } catch (Throwable ignoredGetItem) {
                MainModule.logMsg("[SBPlus] getItem hook skipped (method missing): " + ignoredGetItem);
            }

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
            MainHook.findAndHookMethodInHierarchy(adapterCls, "onBindViewHolder",
                    vhCls,
                    int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            int pos = (Integer) p.args[1];
                            Object holder = p.args[0];
                            int icons = sIconCount;

                            // 2026-11-21：不再注入外来 MenuItem（会让宿主 SIGSEGV），
                            // 所以「添加」格的位置判断必须回到「末尾之后」（pos == icons）。
                            // 注意此时 getItemCount 仍是 27，pos 永远到不了 icons，
                            // 这个分支不会执行 —— 保留它只为将来找到安全注入方式后启用。
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

            sAddItemHookedIds.add(System.identityHashCode(adapter));
            MainModule.logMsg("[SBPlus] grid add-item hooks installed");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] installGridAddItem error: " + t);
        }
    }
}
