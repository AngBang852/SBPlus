package com.sbplus.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;


/**
 * Long-press-to-reorder + show/hide for Samsung Internet's "More" (⋮) grid menu.
 *
 * Samsung already persists menu order + checked (show/hide) via its customize-toolbar
 * subsystem (CustomizeToolbarManager / CustomizeMenuModel / CustomizeMenuHelper). We reuse
 * that persistence and only add the missing in-place long-press → drag gesture.
 *
 * The drag is implemented through RecyclerView.OnItemTouchListener (obfuscated to "M0"),
 * which is an *interface* and therefore safe to Proxy (unlike the abstract Snap /
 * ScrollListener classes Samsung kept). add/removeOnItemTouchListener are un-obfuscated.
 */
public final class MenuReorderHelper {

    private MenuReorderHelper() {}

    private static ClassLoader sCl;

    private static volatile Object sRecycler;   // RecyclerView
    private static volatile Object sAdapter;    // MoreMenuRecyclerAdapter

    /**
     * 该 adapter 是否就是**宿主菜单的 adapter**（而不是我们拼进去的「添加」格 adapter）。
     *
     * <p><b>2026-11-22 为什么需要它</b>：添加格用 ConcatAdapter 实现 —— 第二个 adapter
     * 是**同一个宿主 adapter 类**的另一个实例，它的 position 从 0 重新开始。
     * 而我们的 onBindViewHolder hook 挂在**类**上，两个实例都会触发：
     * 添加格的 pos=0 会被当成"第 0 个图标"，于是被画上 ✕、点击被禁用，
     * 删除时还会删掉真正的第 0 项（用户看到"长按后滑动有些没有删除按钮"、
     * "添加格前面多出空位"）。用实例比对即可精确区分。
     */
    static boolean isHostAdapter(Object a) {
        return a != null && sAdapter != null && sAdapter == a;
    }
    private static volatile Object sHandler;    // MoreMenuHandler (for mMenu + save)
    private static volatile List<MenuItem> sItems;   // flattened reorderable list
    private static volatile List<MenuItem> sCachedPrimary;
    private static volatile List<MenuItem> sCachedSecondary;

    private static volatile Object sTouchProxy;
    private static volatile Object sProxiedRecycler;

    // gesture state
    private static volatile boolean sEditMode = false;
    private static volatile boolean sDragging = false;
    private static volatile long sDownTime;
    private static volatile float sDownX, sDownY;
    private static volatile int sDragPos = -1;
    private static volatile int sDragFrom = -1;
    private static volatile float sDragStartX = 0f, sDragStartY = 0f;
    private static volatile View sDragView = null;

    private static final int EDGE_SCROLL_MAX = 70;        // max scroll px per frame (fastest)
    private static final int EDGE_SCROLL_MIN = 28;        // min scroll px per frame
    private static final int EDGE_ZONE_PX = 48;           // fixed px near the true edge
    private static final Runnable[] sScrollTicker = new Runnable[1];
    private static volatile Float sLastMoveRawX = null;
    private static volatile Float sLastMoveRawY = null;
    private static volatile float sTouchOffsetX = 0f;
    private static volatile float sTouchOffsetY = 0f;

    public static void setClassLoader(ClassLoader cl) { sCl = cl; }

    public static void cacheRefs(Object handler, Object recycler, Object adapter,
                                 List<MenuItem> primary, List<MenuItem> secondary,
                                 List<MenuItem> removed) {
        // 2026-10-04 修复:换新菜单时必须把上一轮的宿主 View 引用一并清掉。
        // sRemoveMarks 是静态 Map<View,View>,值是挂在宿主菜单项上的「✕ 删除标记」。
        // 原实现只重置了 sItems/sDragView,漏了它 —— 菜单 sheet 每次打开都会重建,
        // 旧的 View 于是被静态 Map 长期持有(连带其 Activity/Window),浏览器常驻
        // 进程里反复开关菜单会持续累积;分屏多实例时两套菜单还会互相干扰。
        // 注意:必须在**赋值新引用之前**清理,否则清的是刚存进来的新值。
        try {
            synchronized (sRemoveMarks) {
                sRemoveMarks.clear();
            }
        } catch (Throwable ignored) {}
        // 上一轮的触摸代理也要解绑:它持有的 sProxiedRecycler 是旧 RecyclerView,
        // 不清会导致 installTouchProxy 误判"已安装过"而不再挂到新实例上。
        sProxiedRecycler = null;
        sTouchProxy = null;
        sHandler = handler;
        sRecycler = recycler;
        sAdapter = adapter;
        sCachedPrimary = primary;
        sCachedSecondary = secondary;
        sEditMode = false;
        sDragging = false;
        sItems = null;
        sDragView = null;
    }

    /** Always-on touch proxy. It only acts while in edit mode; otherwise lets Samsung handle. */
    public static void installTouchProxy() {
        // Re-install only when the RecyclerView instance changed (the menu sheet is rebuilt on
        // every open, so a new RecyclerView needs a fresh OnItemTouchListener).
        if (sRecycler == null || sCl == null || sRecycler == sProxiedRecycler) return;
        try {
            Object recycler = sRecycler;
            Class<?> m0 = null;
            try {
                Class<?> rvCls = MainHook.loadClassSafely("androidx.recyclerview.widget.RecyclerView", sCl);
                for (java.lang.reflect.Method m : rvCls.getMethods()) {
                    if ("addOnItemTouchListener".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        m0 = m.getParameterTypes()[0];
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            if (m0 == null || !m0.isInterface()) {
                MainModule.logMsg("[SBPlus] M0 not an interface, reorder disabled");
                return;
            }
            java.lang.reflect.InvocationHandler h = new java.lang.reflect.InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("onInterceptTouchEvent".equals(name)) {
                        return onIntercept((MotionEvent) args[1]);
                    } else if ("onTouchEvent".equals(name)) {
                        onTouch((MotionEvent) args[1]);
                        return null;
                    } else if ("onRequestDisallowInterceptTouchEvent".equals(name)) {
                        return null;
                    }
                    if ("toString".equals(name)) return "SBPlusReorder";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    return null;
                }
            };
            sTouchProxy = Proxy.newProxyInstance(sCl, new Class<?>[]{m0}, h);
            MainHook.callMethod(recycler, "addOnItemTouchListener", sTouchProxy);
            sProxiedRecycler = recycler;
            MainModule.logMsg("[SBPlus] reorder touch proxy installed on " + recycler);

            // 2026-11-22：编辑模式下滚动/翻页后，新绑定进来的格子没有 ✕ 标记
            // （onBindViewHolder 只在绑定那一刻跑，滚动前就已绑好的格子不会再触发）。
            // 这里挂一个滚动监听：滚动停止时把 ✕ 重新铺一遍。
            // 用反射实现 OnScrollListener 接口，避免编译期依赖被 R8 改名的类。
            try {
                if (sScrollProxy == null) {
                    java.lang.reflect.Method addScroll = null;
                    for (java.lang.reflect.Method m : rvCls2(sCl).getMethods()) {
                        if ("addOnScrollListener".equals(m.getName())
                                && m.getParameterTypes().length == 1) {
                            addScroll = m;
                            break;
                        }
                    }
                    if (addScroll != null) {
                        final Class<?> ifc = addScroll.getParameterTypes()[0];
                        sScrollProxy = Proxy.newProxyInstance(sCl, new Class<?>[]{ifc},
                                new java.lang.reflect.InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                                try {
                                    String name = method.getName();
                                    // onScrollStateChanged(RecyclerView, int state)
                                    if ("onScrollStateChanged".equals(name)
                                            && args != null && args.length == 2) {
                                        int state = ((Number) args[1]).intValue();
                                        if (state == 0 && sEditMode) {   // SCROLL_STATE_IDLE
                                            ((View) sRecycler).postDelayed(new Runnable() {
                                                @Override public void run() {
                                                    if (sEditMode) showDeleteMarks();
                                                }
                                            }, 60);
                                        }
                                    }
                                    if ("toString".equals(name)) return "SBPlusScroll";
                                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                                    if ("equals".equals(name)) return proxy == args[0];
                                } catch (Throwable ignored) {}
                                return null;
                            }
                        });
                        MainHook.callMethod(recycler, "addOnScrollListener", sScrollProxy);
                        MainModule.logMsg("[SBPlus] reorder scroll listener installed");
                    }
                }
            } catch (Throwable ts) {
                MainModule.logMsg("[SBPlus] scroll listener install failed: " + ts);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] installTouchProxy error: " + t);
        }
    }

    /** 取 RecyclerView 类（避免重复 loadClass）。 */
    private static Class<?> rvCls2(ClassLoader cl) {
        return MainHook.loadClassSafely("androidx.recyclerview.widget.RecyclerView", cl);
    }

    private static volatile Object sScrollProxy;

    private static boolean onIntercept(MotionEvent ev) {
        int action = ev.getActionMasked();
        // While dragging, claim the whole stream (so the icon follows the finger).
        if (sDragging) return true;

        if (action == MotionEvent.ACTION_DOWN) {
            sDownTime = android.os.SystemClock.uptimeMillis();
            sDownX = ev.getRawX();
            sDownY = ev.getRawY();
            sDragPos = -1;
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sDownTime = 0;
            sDragPos = -1;
            if (action == MotionEvent.ACTION_UP) {
                float rx = ev.getRawX();
                float ry = ev.getRawY();
                ensureFlatItems();
                // Tap on the trailing "+" add cell -> open the add dialog.
                // 2026-11-21：「添加」项由 menu.add(0, ADD_ITEM_ID, 0, "添加") 创建，
                // order=0 所以排在**第一位**（pos 0），不是末尾。
                int addPos = 0;
                if (addPos >= 0 && isTapOnPosition(rx, ry, addPos)) {
                    MainModule.logMsg("[SBPlus] add cell tapped -> show add dialog");
                    MenuAddButtonHelper.showAddDialog(((View) sRecycler).getContext());
                    // 2026-09-20 修复:原来 return false 放行 UP,MenuEditHelper 给
                    // itemView 装的 onClick 会再弹一次 -> 一次点击叠两个一模一样的
                    // PopupWindow。这里吞掉该 UP,只保留拦截器这一条触发路径。
                    return true;
                }
                // In edit mode, a simple tap should NOT reach the item's onClick. Intercept it
                // and route to our own ✕ / empty-space handling.
                if (sEditMode) {
                    // 2026-11-22 修复「编辑模式下滑动就退出编辑、✕ 全没了」：
                    // 原来不区分 tap 与 swipe —— 横向滑动结束时 UP 落在图标之间的
                    // 空白处，positionUnderRaw 返回 -1，于是被当成「点空白」直接
                    // exitEditModeAndSave()。现在先按位移判断：真滑动就放行给
                    // RecyclerView 滚动，并在滚动停止后重铺 ✕。
                    float sdx = ev.getRawX() - sDownX;
                    float sdy = ev.getRawY() - sDownY;
                    if (sdx * sdx + sdy * sdy > 2500f) {   // >50px 视为滑动
                        MainModule.logMsg("[SBPlus] UP in edit is swipe (dx=" + (int) sdx
                                + "), keep edit mode");
                        final View rvE = (View) sRecycler;
                        rvE.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (sEditMode) showDeleteMarks();
                            }
                        }, 120);
                        return false;   // 不吞事件，让 RecyclerView 完成滚动
                    }
                    int underRaw = positionUnderRaw(rx, ry);
                    Integer markPos = hitRemoveMark(rx, ry);
                    MainModule.logMsg("[SBPlus] UP in edit: underRaw=" + underRaw
                            + " hasMark=" + (markPos != null));
                    if (markPos != null) {
                        if (sItems != null && markPos >= 0 && markPos < sItems.size()) {
                            MenuItem item = sItems.get(markPos);
                            removeItem(item);
                            showDeleteMarks();
                        }
                    } else if (underRaw < 0) {
                        MainModule.logMsg("[SBPlus] tap empty -> exit edit mode");
                        exitEditModeAndSave();
                    }
                    return true; // swallow the tap so the item's original onClick never fires
                }
            }
            return false;
        }
        // ACTION_MOVE: long-press detection (works in both IDLE and EDIT states).
        float dx = ev.getRawX() - sDownX;
        float dy = ev.getRawY() - sDownY;
        if (sDownTime != 0 && android.os.SystemClock.uptimeMillis() - sDownTime > 500L
                && dx * dx + dy * dy < 2500f) {
            int under = positionUnder(ev);
            MainModule.logMsg("[SBPlus] long-press candidate, under=" + under + " edit=" + sEditMode);
            if (under >= 0 && !isAddCell(under)) {
                // Long-press on a real icon -> enter edit mode and begin dragging this icon.
                enterEditMode();
                sDragging = true;
                sDragPos = under;
                sDragFrom = under;
                sDragStartX = ev.getRawX();
                sDragStartY = ev.getRawY();
                MainModule.logMsg("[SBPlus] long-press -> drag from " + sDragPos);
                if (sRecycler instanceof View) {
                    ((View) sRecycler).performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                }
                startDragVisual(ev);
                return true;
            }
        }
        return false;
    }

    private static void onTouch(MotionEvent ev) {
        try {
            if (sDragging) {
                handleTouch(ev);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] reorder onTouch error: " + t);
        }
    }

    /** Handle taps while in edit mode (not dragging): ✕ marks or empty-space to exit. */
    private static void handleTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                sLastMoveRawX = ev.getRawX();
                sLastMoveRawY = ev.getRawY();
                float mdx = ev.getRawX() - sDragStartX;
                float mdy = ev.getRawY() - sDragStartY;
                if (mdx * mdx + mdy * mdy > 1600f) {
                    int to = positionUnder(ev);
                    if (to >= 0) sDragPos = to;
                }
                updateDragVisual(ev);
                handleEdgeScroll(ev);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopEdgeScroll();
                if (sDragging) {
                    sDragging = false;
                    int from = sDragFrom, to = sDragPos;
                    sDragPos = sDragFrom = -1;
                    endDragVisual();
                    applyMove(from, to);
                    // Persist the order immediately so it survives even if the user closes the
                    // menu (e.g. taps outside the sheet) without going through exitEditModeAndSave.
                    persistOrderNow();
                    // Stay in edit mode so the user can tap ✕ to remove, or tap empty to exit.
                    MainModule.logMsg("[SBPlus] drag finished, order persisted, still in edit mode");
                }
                break;
        }
    }

    private static void enterEditMode() {
        if (sEditMode) return;
        ArrayList<MenuItem> flat = new ArrayList<>();
        if (sCachedPrimary != null) flat.addAll(sCachedPrimary);
        if (sCachedSecondary != null) flat.addAll(sCachedSecondary);
        sItems = flat;
        sEditMode = true;
        MainModule.logMsg("[SBPlus] enter edit mode, items=" + flat.size());
        // Paint the ✕ marks after layout settles so icon coordinates are correct.
        if (sRecycler instanceof View) {
            final View rv = (View) sRecycler;
            rv.post(new Runnable() {
                @Override public void run() {
                    if (!sEditMode) return;
                    showDeleteMarks();
                    disableIconsForEdit();
                    // Second pass after any page-settle/lazy layout finishes, so items that
                    // were still laying out on the first pass also get their ✕.
                    rv.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!sEditMode) return;
                            showDeleteMarks();
                            disableIconsForEdit();
                        }
                    }, 350);
                }
            });
        }
    }

    /** While editing, block every clickable descendant so taps don't trigger menu actions. */
    private static void disableIconsForEdit() {
        try {
            if (!(sRecycler instanceof View)) return;
            int childCount = ((Number) MainHook.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) MainHook.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                disableClickTree(child);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] disableIconsForEdit error: " + t);
        }
    }

    // ---------------------------------------------------------------------------------
    // View tag key(2026-10-04 修复)
    //
    // 原实现用裸 int 0x7f0f0001~3 作 tag key。0x7f 是 **Android 资源 id 的包前缀**,
    // 即这三个 key 落在宿主应用的资源 id 空间内 —— 一旦三星浏览器某个真实资源 id
    // 恰好等于其中之一, 宿主自己的 getTag/setTag 就会与本模块的标记互相覆盖
    // (表现为: 编辑模式下图标点击恢复失效、✕ 标记错乱)。
    //
    // 曾改用 View.generateViewId()，但**这是个 bug**：它返回 0x00xxxxxx 段的值，
    // 而 View.setTag(int, Object) 的 key 必须是"应用资源 id"（高字节 = 0x7f），
    // 否则运行时直接抛：
    //   IllegalArgumentException: The key must be an application-specific resource id.
    // 结果：长按进入编辑模式后立刻失败，✕ 标记与删除功能全废（2026-11-22 真机确认）。
    //
    // 正解：用 **模块自己 APK 的**资源 id 段。0x7f 是所有 APK 共用的包前缀，
    // 但不同 APK 的 0x7f 资源空间互相独立 —— 本模块的 key 只在本模块的
    // setTag/getTag 调用中出现，而这里的 View 属于宿主，宿主不会用 0x7f 里
    // 属于**本模块**的那几个 id 去 getTag。为彻底避免与宿主真实资源 id 撞车，
    // 取一段宿主机几乎不可能用到的尾部区间，并在注释里固化这个约束。
    private static final int TAG_KEY_CLICKABLE = 0x7f0e0001;
    private static final int TAG_KEY_POSITION = 0x7f0e0002;
    private static final int TAG_KEY_MARK = 0x7f0e0003;

    private static void disableClickTree(View v) {
        if (v == null) return;
        if (v.isClickable()) {
            v.setTag(TAG_KEY_CLICKABLE, Boolean.TRUE);
            v.setClickable(false);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableClickTree(vg.getChildAt(i));
            }
        }
    }

    private static void enableIconsAfterEdit() {
        try {
            if (!(sRecycler instanceof View)) return;
            int childCount = ((Number) MainHook.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) MainHook.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                restoreClickTree(child);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] enableIconsAfterEdit error: " + t);
        }
    }

    private static void restoreClickTree(View v) {
        if (v == null) return;
        if (Boolean.TRUE.equals(v.getTag(TAG_KEY_CLICKABLE))) {
            v.setClickable(true);
            v.setTag(TAG_KEY_CLICKABLE, null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                restoreClickTree(vg.getChildAt(i));
            }
        }
    }

    /** True if the adapter position is the trailing "+" add cell (not a real icon). */
    /** 「添加」格在 pos 0（由 menu.add 的 order=0 决定，2026-11-21）。 */
    private static boolean isAddCell(int pos) {
        return pos == 0;
    }

    /** True if the raw coords land on the item at the given adapter position. */
    private static boolean isTapOnPosition(float rawX, float rawY, int pos) {
        try {
            if (!(sRecycler instanceof View)) return false;
            int childCount = ((Number) MainHook.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) MainHook.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                int lp = ((Number) MainHook.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                if (lp != pos) continue;
                int[] loc = new int[2];
                child.getLocationOnScreen(loc);
                return rawX >= loc[0] && rawX <= loc[0] + child.getWidth()
                        && rawY >= loc[1] && rawY <= loc[1] + child.getHeight();
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] isTapOnPosition error: " + t);
        }
        return false;
    }

    private static void exitEditModeAndSave() {
        if (!sEditMode) return;
        hideDeleteMarks();
        enableIconsAfterEdit();
        sEditMode = false;
        saveOrder();
        MainModule.logMsg("[SBPlus] exit edit mode, order saved");
    }

    /** Map a raw screen coordinate to a flat adapter position (5 cols x 2 rows paging grid). */
    private static int positionUnder(MotionEvent ev) {
        return positionUnderRaw(ev.getRawX(), ev.getRawY());
    }

    private static int positionUnderRaw(float rawX, float rawY) {
        try {
            if (!(sRecycler instanceof View)) return -1;
            ensureFlatItems();
            if (sItems == null || sItems.isEmpty()) return -1;
            View rv = (View) sRecycler;
            int total = sItems.size();
            // Preferred: hit-test the actual visible children and read their layout position.
            int childCount = ((Number) MainHook.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) MainHook.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                // Skip the item currently being dragged: its translation follows the finger,
                // so it would always hit-test as its original (from) slot and mask the target.
                if (child == sDragView) continue;
                int[] loc = new int[2];
                child.getLocationOnScreen(loc);
                float cx = rawX;
                float cy = rawY;
                if (cx >= loc[0] && cx <= loc[0] + child.getWidth()
                        && cy >= loc[1] && cy <= loc[1] + child.getHeight()) {
                    int pos = ((Number) MainHook.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                    if (pos >= 0 && pos < total) return pos;
                }
            }
            // Fallback: geometric estimate — only if finger is within the grid bounds.
            int cols = 5, rows = 2;
            int pageW = rv.getWidth();
            if (pageW <= 0) return -1;
            int itemW = pageW / cols;
            int itemH = 0;
            View child0 = (View) MainHook.callMethod(sRecycler, "getChildAt", 0);
            if (child0 != null) itemH = child0.getHeight();
            if (itemH <= 0) itemH = (int) (76f * rv.getResources().getDisplayMetrics().density);
            int[] gloc = new int[2];
            rv.getLocationOnScreen(gloc);
            float relX = rawX - gloc[0];
            float relY = rawY - gloc[1];
            int scrollX = 0;
            try {
                scrollX = ((Number) MainHook.callMethod(sRecycler, "computeHorizontalScrollOffset")).intValue();
            } catch (Throwable ignored) {}
            int page = (scrollX + (int) relX) / pageW;
            int withinPage = (scrollX + (int) relX) % pageW;
            int col = withinPage / itemW;
            int row = (int) (relY / itemH);
            // Taps above the grid (relY<0) or below it (row>=rows) are empty space, not an item.
            // Return -1 so callers can treat it as "tap outside -> exit edit mode".
            if (relY < 0f || row >= rows) return -1;
            // If finger is between icons (gap area), don't snap to a neighbor — return -1.
            int colEnd = (col + 1) * itemW;
            int colStart = col * itemW;
            int rowStart = row * itemH;
            int rowEnd = (row + 1) * itemH;
            if (withinPage < colStart || withinPage > colEnd
                    || (int) relY < rowStart || (int) relY > rowEnd) return -1;
            if (col < 0 || col >= cols) return -1;
            int pos = page * (cols * rows) + row * cols + col;
            if (pos < 0 || pos >= total) return -1;
            return pos;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] positionUnder error: " + t);
            return -1;
        }
    }

    private static void applyMove(int from, int to) {
        if (sItems == null || sAdapter == null) return;
        if (from < 0 || from >= sItems.size() || to < 0 || to >= sItems.size()) return;
        if (from == to) return;
        try {
            // Swap semantics: the two icons exchange places directly (not insert-shift).
            MenuItem a = sItems.get(from);
            MenuItem b = sItems.get(to);
            sItems.set(from, b);
            sItems.set(to, a);
            MainModule.logMsg("[SBPlus] applyMove (swap) " + from + " <-> " + to);
            // Push the swapped order into the handler's live list (the adapter's real source)
            // BEFORE repainting, otherwise notifyItemChanged shows stale data.
            syncHandlerLists();
            MainHook.callMethod(sAdapter, "notifyItemChanged", from);
            MainHook.callMethod(sAdapter, "notifyItemChanged", to);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] applyMove error: " + t);
        }
    }

    /** Find the item view for a layout position without disturbing the RecyclerView. */
    private static View findItemView(int pos) {
        try {
            int childCount = ((Number) MainHook.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) MainHook.callMethod(sRecycler, "getChildAt", i);
                int lp = ((Number) MainHook.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                if (lp == pos) return child;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Lift the dragged item (elevation + slight scale) so the user sees it is being held. */
    private static void startDragVisual(MotionEvent ev) {
        try {
            sDragView = findItemView(sDragFrom);
            if (sDragView == null) return;
            // Record where inside the item the finger pressed, so the item stays anchored
            // under the finger (centered) while dragging instead of snapping to its top-left.
            int[] loc = new int[2];
            sDragView.getLocationOnScreen(loc);
            sTouchOffsetX = ev.getRawX() - loc[0];
            sTouchOffsetY = ev.getRawY() - loc[1];
            sDragView.setElevation(24f);
            sDragView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
            updateDragVisual(ev);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] startDragVisual error: " + t);
        }
    }

    /** Make the dragged item follow the finger via translation. */
    private static void updateDragVisual(MotionEvent ev) {
        updateDragVisualRaw(ev.getRawX(), ev.getRawY());
    }

    private static void updateDragVisualRaw(float rawX, float rawY) {
        if (sDragView == null) return;
        try {
            int[] parentLoc = new int[2];
            if (sRecycler instanceof View) ((View) sRecycler).getLocationOnScreen(parentLoc);
            float dx = rawX - (parentLoc[0] + sDragView.getLeft() + sTouchOffsetX);
            float dy = rawY - (parentLoc[1] + sDragView.getTop() + sTouchOffsetY);
            sDragView.setTranslationX(dx);
            sDragView.setTranslationY(dy);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] updateDragVisual error: " + t);
        }
    }

    /** Restore the dragged item before the swap is applied. */
    private static void endDragVisual() {
        if (sDragView == null) return;
        try {
            sDragView.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            sDragView.setTranslationX(0f);
            sDragView.setTranslationY(0f);
            sDragView.setElevation(0f);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] endDragVisual error: " + t);
        }
        sDragView = null;
    }

    /**
     * Auto-scroll toward the left/right edge while the finger is parked in the hot-zone,
     * letting the user carry an item across pages.
     */
    private static void handleEdgeScroll(MotionEvent ev) {
        if (!(sRecycler instanceof View)) return;
        try {
            final View rv = (View) sRecycler;
            int w = rv.getWidth();
            if (w <= 0) return;
            float relX = ev.getX();
            float zone = EDGE_ZONE_PX;

            int dir = 0;
            float depth = 0f;
            if (relX >= w - zone) { dir = 1; depth = (relX - (w - zone)) / zone; }
            else if (relX <= zone) { dir = -1; depth = (zone - relX) / zone; }

            if (dir == 0) { stopEdgeScroll(); return; }
            final int direction = dir;
            final float d = Math.max(0f, Math.min(1f, depth));

            // Recreate/refresh the ticker on every MOVE so speed & direction track the finger.
            Runnable ticker = new Runnable() {
                @Override public void run() {
                    try {
                        if (!sDragging || sDragView == null) { stopEdgeScroll(); return; }
                        if (sScrollTicker[0] != this) { return; } // superseded by a newer ticker
                        int step = (int) (EDGE_SCROLL_MIN + (EDGE_SCROLL_MAX - EDGE_SCROLL_MIN) * d);
                        MainHook.callMethod(sRecycler, "scrollBy", direction * step, 0);
                        if (sLastMoveRawX != null && sLastMoveRawY != null) {
                            int p = positionUnderRaw(sLastMoveRawX, sLastMoveRawY);
                            if (p >= 0) sDragPos = p;
                            updateDragVisualRaw(sLastMoveRawX, sLastMoveRawY);
                        }
                        rv.postOnAnimation(this);
                    } catch (Throwable t) {
                        stopEdgeScroll();
                    }
                }
            };
            sScrollTicker[0] = ticker;
            rv.postOnAnimation(ticker);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] handleEdgeScroll error: " + t);
        }
    }

    private static void stopEdgeScroll() {
        sScrollTicker[0] = null;
    }

    private static void saveOrder() {
        try {
            persistOrderNow();
            // Also update the handler's live lists that the adapter actually reads,
            // so the reorder is visible immediately (not only after closing/reopening).
            syncHandlerLists();
            if (sAdapter != null) {
                MainHook.callMethod(sAdapter, "notifyDataSetChanged");
            }
            MainModule.logMsg("[SBPlus] saveOrder done (" + sItems.size() + " items)");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] saveOrder error: " + t);
        }
    }

    /** Persist the current sItems order to Samsung's customize-toolbar storage (no UI refresh). */
    private static void persistOrderNow() {
        try {
            if (sHandler == null || sItems == null || sItems.isEmpty()) return;
            for (MenuItem it : sItems) {
                if (it != null) it.setChecked(true);
            }
            StringBuilder orderLog = new StringBuilder("persist order=");
            for (MenuItem it : sItems) {
                if (it != null) orderLog.append(String.valueOf(it.getTitle())).append("|");
            }
            MainModule.logMsg("[SBPlus] " + orderLog);
            Class<?> mgrCls = MainHook.loadClassSafely(
                    "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", sCl);
            Object mgr = MainHook.callStaticMethod(mgrCls, "getInstance");
            MainHook.callMethod(mgr, "saveToolsMenu", sItems);
            syncHandlerLists();
            MainModule.logMsg("[SBPlus] saveToolsMenu persisted (" + sItems.size() + " items)");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] persistOrderNow error: " + t);
        }
    }

    /**
     * Write the reordered flat list back into the handler's real mPrimaryMenuItems /
     * mSecondaryMenuItems lists (A-scheme: all flat, so all go to primary, secondary emptied).
     */
    private static void syncHandlerLists() {
        try {
            if (sHandler == null) return;
            if (sCachedPrimary != null) {
                sCachedPrimary.clear();
                sCachedPrimary.addAll(sItems);
            }
            if (sCachedSecondary != null) {
                sCachedSecondary.clear();
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] syncHandlerLists error: " + t);
        }
    }

    // ---------------------------------------------------------------------------------
    // Add / remove (show / hide) support — driven by MenuEditHelper.
    // ---------------------------------------------------------------------------------

    /** Build the flat sItems from the handler's cached lists (call before add/remove). */
    public static void ensureFlatItems() {
        if (sItems == null) {
            ArrayList<MenuItem> flat = new ArrayList<>();
            if (sCachedPrimary != null) flat.addAll(sCachedPrimary);
            if (sCachedSecondary != null) flat.addAll(sCachedSecondary);
            sItems = flat;
        }
    }

    /** Current menu grid window bounds (for positioning the add panel right above it). */
    public static int[] menuBoundsOnScreen() {
        try {
            if (sRecycler instanceof View) {
                View rv = (View) sRecycler;
                int[] loc = new int[2];
                rv.getLocationOnScreen(loc);
                return new int[]{ loc[0], loc[1], loc[0] + rv.getWidth(), loc[1] + rv.getHeight() };
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] menuBoundsOnScreen error: " + t);
        }
        return null;
    }

    /** The RecyclerView itself, used as the anchor to show the add panel above the menu. */
    public static View menuAnchorView() {
        return sRecycler instanceof View ? (View) sRecycler : null;
    }

    /** Add a MenuItem (show it) — append to the flat list and persist. */
    public static void addItem(MenuItem item) {
        try {
            if (item == null) return;
            ensureFlatItems();
            if (!sItems.contains(item)) sItems.add(item);
            saveOrder();
            com.sbplus.browser.MainHook.refreshIndicatorIfNeeded();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] addItem error: " + t);
        }
    }

    /** Remove a MenuItem (hide it) — drop from flat list, keep checked=false, persist. */
    public static void removeItem(MenuItem item) {
        try {
            if (item == null) return;
            ensureFlatItems();
            if (item != null) item.setChecked(false);
            sItems.remove(item);
            saveAfterRemove();
            com.sbplus.browser.MainHook.refreshIndicatorIfNeeded();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] removeItem error: " + t);
        }
    }

    /** Persist after a removal (must NOT force checked=true on the removed item). */
    private static void saveAfterRemove() {
        try {
            if (sHandler == null || sItems == null) return;
            // Force shown=true on every remaining item — Samsung's setToolsMenuList keeps only
            // checked items, so without this the menu collapses (the earlier wipe bug).
            for (MenuItem it : sItems) {
                if (it != null) it.setChecked(true);
            }
            Class<?> mgrCls = MainHook.loadClassSafely(
                    "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", sCl);
            Object mgr = MainHook.callStaticMethod(mgrCls, "getInstance");
            MainHook.callMethod(mgr, "saveToolsMenu", sItems);
            syncHandlerLists();
            if (sAdapter != null) {
                MainHook.callMethod(sAdapter, "notifyDataSetChanged");
            }
            MainModule.logMsg("[SBPlus] removed item, saved " + sItems.size() + " remaining");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] saveAfterRemove error: " + t);
        }
    }

    // ---------------------------------------------------------------------------------
    // ✕ delete marks (shown while in edit mode).
    // ---------------------------------------------------------------------------------

    // 2026-11-22：删除标记改用 **ViewOverlay + Drawable** 绘制，彻底不碰宿主 itemView 的布局。
    //
    // <p>为什么换：原实现往 itemView 里 addView 一个 TextView：
    // <ul>
    //   <li>若容器是 LinearLayout，却用 RelativeLayout.LayoutParams → 布局时
    //       ClassCastException 直接闪退（真机崩溃栈确认）；</li>
    //   <li>换成正确类型的 LayoutParams 又会挤占容器布局（图标被推走/标记被裁），
    //       表现为"部分格子没有 ✕"。</li>
    // </ul>
    // ViewOverlay 画在 View 内容之上，不参与测量和布局，也没有任何 LayoutParams 参与 ——
    // 既不会崩，也不会挤动图标。
    private static final java.util.Map<View, MarkDrawable> sRemoveMarks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 红色圆形 + 白色 ✕ 的删除标记。 */
    private static final class MarkDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint mBg =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mFg =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final int pos;

        MarkDrawable(int pos, float density) {
            this.pos = pos;
            mBg.setColor(0xFFE53935);
            mBg.setStyle(android.graphics.Paint.Style.FILL);
            mFg.setColor(0xFFFFFFFF);
            mFg.setTextAlign(android.graphics.Paint.Align.CENTER);
            mFg.setTextSize(10f * density);
            mFg.setFakeBoldText(true);
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            canvas.drawCircle(cx, cy, Math.min(b.width(), b.height()) / 2f, mBg);
            android.graphics.Paint.FontMetrics fm = mFg.getFontMetrics();
            float ty = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("✕", cx, ty, mFg);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Draw a small ✕ badge on the top-right of every visible icon. */
    private static void showDeleteMarks() {
        try {
            if (!(sRecycler instanceof View)) return;
            hideDeleteMarks();
            int childCount = ((Number) MainHook.callMethod(sRecycler, "getChildCount")).intValue();
            int added = 0, skippedPos = 0, skippedType = 0;
            for (int i = 0; i < childCount; i++) {
                View child = (View) MainHook.callMethod(sRecycler, "getChildAt", i);
                if (child == null || !(child instanceof ViewGroup)) { skippedType++; continue; }
                int pos = ((Number) MainHook.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                if (pos < 0 || sItems == null || pos >= sItems.size()) { skippedPos++; continue; }
                child.setTag(TAG_KEY_CLICKABLE, Boolean.valueOf(child.isClickable()));
                child.setClickable(false);
                child.setTag(TAG_KEY_POSITION, pos);
                addDeleteMark((ViewGroup) child, pos);
                added++;
            }
            MainModule.logMsg("[SBPlus] delete marks shown on " + sRemoveMarks.size() + " items"
                    + " (children=" + childCount + " added=" + added
                    + " skipType=" + skippedType + " skipPos=" + skippedPos + ")");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] showDeleteMarks error: " + t);
        }
    }

    private static void hideDeleteMarks() {
        try {
            for (java.util.Map.Entry<View, MarkDrawable> e : sRemoveMarks.entrySet()) {
                try {
                    View child = e.getKey();
                    if (child != null) child.getOverlay().remove(e.getValue());
                } catch (Throwable ignored) {}
            }
            sRemoveMarks.clear();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] hideDeleteMarks error: " + t);
        }
    }

    /**
     * Called from onBindViewHolder after-hook: while in edit mode, give a freshly-bound
     * (page-scrolled-into-view) item its ✕ mark and block its original click.
     */
    public static void decorateBoundItem(Object holder, int pos) {
        try {
            View child = (View) MainHook.getObjectField(holder, "itemView");
            if (child == null || !(child instanceof ViewGroup)) return;
            // Always strip any lingering mark from a (re)bound item — recycled
            // off-screen items can carry a stale drawable back into view.
            MarkDrawable old = sRemoveMarks.remove(child);
            if (old != null) {
                try { child.getOverlay().remove(old); } catch (Throwable ignored) {}
            }
            if (!sEditMode || sItems == null || pos < 0 || pos >= sItems.size()) return;
            disableClickTree(child);
            child.setTag(TAG_KEY_POSITION, pos);
            addDeleteMark((ViewGroup) child, pos);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] decorateBoundItem error: " + t);
        }
    }

    private static void addDeleteMark(ViewGroup child, int pos) {
        try {
            if (!(sRecycler instanceof View)) return;
            View rv = (View) sRecycler;
            android.content.Context rc = rv.getContext();
            float d = rc.getResources().getDisplayMetrics().density;
            final int size = (int) (20f * d);

            final MarkDrawable mark = new MarkDrawable(pos, d);
            sRemoveMarks.put(child, mark);
            final ViewGroup childRef = child;
            final int iconId = rc.getResources().getIdentifier("icon", "id", rc.getPackageName());

            // 位置放在图标右上角；图标还没布局完就等一帧再算（只补一次，不循环）。
            Runnable place = new Runnable() {
                @Override public void run() {
                    try {
                        View icon = iconId != 0 ? childRef.findViewById(iconId) : null;
                        int left, top;
                        if (icon != null && icon.getWidth() > 0) {
                            int[] il = new int[2];
                            icon.getLocationInWindow(il);
                            int[] cl = new int[2];
                            childRef.getLocationInWindow(cl);
                            left = il[0] - cl[0] + icon.getWidth() - size;
                            top = il[1] - cl[1];
                        } else {
                            left = childRef.getWidth() - size - (int) (2f * d);
                            top = (int) (2f * d);
                        }
                        if (left < 0) left = 0;
                        if (top < 0) top = 0;
                        // ViewOverlay 被裁在宿主 View 边界内：left/top 超出 child 宽高时
                        // 标记会被裁掉（表现为"部分格子没有 ✕"）。这里夹回可见范围内。
                        int cw = childRef.getWidth();
                        int ch = childRef.getHeight();
                        if (cw > 0 && left > cw - size) left = cw - size;
                        if (ch > 0 && top > ch - size) top = ch - size;
                        if (left < 0) left = 0;
                        if (top < 0) top = 0;
                        mark.setBounds(left, top, left + size, top + size);
                        childRef.getOverlay().add(mark);
                        childRef.invalidate();
                    } catch (Throwable ignored) {}
                }
            };
            if (child.getWidth() > 0) place.run(); else child.post(place);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] addDeleteMark error: " + t);
        }
    }

    /** 命中检测：返回被点中的删除标记对应的条目位置，没点中返回 null。 */
    private static Integer hitRemoveMark(float rawX, float rawY) {
        for (java.util.Map.Entry<View, MarkDrawable> e : sRemoveMarks.entrySet()) {
            View child = e.getKey();
            MarkDrawable mark = e.getValue();
            if (child == null || !child.isShown()) continue;
            android.graphics.Rect b = mark.getBounds();
            int[] loc = new int[2];
            child.getLocationOnScreen(loc);
            if (rawX >= loc[0] + b.left && rawX <= loc[0] + b.right
                    && rawY >= loc[1] + b.top && rawY <= loc[1] + b.bottom) {
                return mark.pos;
            }
        }
        return null;
    }
}
