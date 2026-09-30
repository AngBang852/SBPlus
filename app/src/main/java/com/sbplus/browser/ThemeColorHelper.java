package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ThemeColorHelper — 自定义主题色.
 *
 * 结构:
 *  主列表(8 行):
 *    主页图标 / 主页文字 / 设置文字(展开2项:标题/说明) / 设置页大栏目背景色
 *    / 网页文字 / 网页背景 / 开关(展开3项:开启/滑块/关闭)
 *
 * 独立 slot(10 个)存储 0xRRGGBB; -1 = 未启用(保持默认).
 */
public final class ThemeColorHelper {

    // slot 常量
    public static final int S_HOME_ICON = 0;
    public static final int S_HOME_TEXT = 1;
    public static final int S_SETTINGS_TITLE = 2;
    public static final int S_SETTINGS_DESC = 3;
    public static final int S_SETTINGS_BG = 4;
    public static final int S_WEB_TEXT = 5;
    public static final int S_WEB_BG = 6;
    public static final int S_SWITCH_ON = 7;
    public static final int S_SWITCH_THUMB = 8;
    public static final int S_SWITCH_OFF = 9;
    /** 主页时钟文字色(2026-09-19 新增, 主页美化)。 */
    public static final int S_CLOCK = 10;
    /** 主页日期文字色(独立于时钟, 2026-09-19 新增)。 */
    public static final int S_DATE = 11;

    private static final String[] KEYS = {
        "theme_home_icon", "theme_home_text", "theme_settings_title", "theme_settings_desc",
        "theme_settings_bg", "theme_web_text", "theme_web_bg",
        "theme_switch_on", "theme_switch_thumb", "theme_switch_off",
        "theme_clock", "theme_date"
    };

    // 色板
    private static final int[] PRESETS = {
        0xFF69F0AE, 0xFF00C853, 0xFF03A9F4, 0xFF3E91FF, 0xFF7C4DFF,
        0xFFD81B60, 0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B, 0xFF212121,
        0xFF505E81, 0xFF8C6836, 0xFFFFFFFF, 0xFF000000, 0xFF808080
    };

    private ThemeColorHelper() {}

    public static String prefName() { return "sbplus_prefs"; }

    // ============ 读取(带内存缓存) ============
    // 主题着色发生在每个 View 的绑定/绘制路径上,原实现每个 TextView 都要做 10+ 次
    // getSharedPreferences().getInt() —— 每次都要走 SharedPreferences 的同步锁,
    // 在长列表滚动时是明显的 CPU 与发热来源。这里做一次性快照缓存,
    // 由 OnSharedPreferenceChangeListener 在配置变更时失效,保证行为不变。
    private static volatile int[] sSlotCache;
    private static volatile boolean sMasterCache;
    private static volatile boolean sAnySetCache;
    private static volatile boolean sCacheReady;
    private static SharedPreferences.OnSharedPreferenceChangeListener sPrefListener;

    /**
     * 连续读取失败的次数。
     *
     * <p>用途是**退避**而非固化:读失败时我们既不能把「读不到」当成「全部未设置」
     * 固化下来(那会让主题色静默失效且永不恢复),也不能让每次 {@code getSlot}
     * 都重新抛一次异常——本方法在主题着色路径上按帧被调用,
     * 持续故障时每次都要进 synchronized 并走一次失败的 getSharedPreferences,
     * 那会把一个正确性问题换成性能问题。
     *
     * <p>因此:前几次失败立刻重试(覆盖瞬时故障);连续失败达到阈值后暂停重试,
     * 直到 {@link #invalidateCache()} 被调用(配置变更时会走这里)。
     */
    private static volatile int sFailCount;
    private static final int FAIL_BACKOFF_THRESHOLD = 3;

    /** 让缓存失效,下次读取重新快照。 */
    public static void invalidateCache() {
        sCacheReady = false;
        sFailCount = 0;      // 配置变更 -> 允许立刻重试
    }

    private static void ensureCache(Context ctx) {
        if (sCacheReady) return;
        // 连续失败已达阈值:退避,直接返回(不置 sCacheReady,配置变更时由
        // invalidateCache 复位)。调用方读到的仍是上一份有效快照或 null。
        if (sFailCount >= FAIL_BACKOFF_THRESHOLD) return;
        synchronized (ThemeColorHelper.class) {
            if (sCacheReady) return;
            if (sFailCount >= FAIL_BACKOFF_THRESHOLD) return;
            int[] arr = new int[KEYS.length];
            boolean master = false;
            boolean any = false;
            try {
                SharedPreferences sp = ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE);
                for (int i = 0; i < KEYS.length; i++) {
                    arr[i] = sp.getInt(KEYS[i], -1);
                    if (arr[i] != -1) any = true;
                }
                master = sp.getBoolean(MASTER_KEY(), false);
                if (sPrefListener == null) {
                    sPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                        @Override public void onSharedPreferenceChanged(SharedPreferences p, String k) {
                            invalidateCache();
                        }
                    };
                    sp.registerOnSharedPreferenceChangeListener(sPrefListener);
                }
            } catch (Throwable t) {
                // 读取失败必须**保持未就绪**,否则会把「读不到」固化成
                // 「已就绪且全部未设置」——后果是主题色整体静默失效:用户之后
                // 改颜色也不生效(因为 sCacheReady 已为 true,ensureCache 直接返回),
                // 且没有任何日志可查。这是本类最隐蔽的一个缺陷。
                sFailCount++;
                MainModule.logMsg("[SBPlus] ThemeColorHelper cache read failed ("
                        + sFailCount + "/" + FAIL_BACKOFF_THRESHOLD + "), will retry: " + t);
                // 不写任何缓存字段、不置 sCacheReady —— 下次调用重新尝试。
                return;
            }
            sFailCount = 0;
            sSlotCache = arr;
            sMasterCache = master;
            sAnySetCache = any;
            sCacheReady = true;
        }
    }

    public static int getSlot(Context ctx, int slot) {
        try {
            ensureCache(ctx);
            int[] c = sSlotCache;
            if (c != null && slot >= 0 && slot < c.length) return c[slot];
            // 缓存尚未建立(冷启动或刚失效)时的直读兜底,保证读得到真值。
            // 但若已进入失败退避期,就不要再走这条路径:它在主题着色路径上按帧
            // 被调用,持续故障时每次直读都会进一次 synchronized 并抛异常,
            // 把一个正确性问题变成性能问题。此时返回 -1 等价于"未设置"(用默认色),
            // 且 invalidateCache()(配置变更时触发)会复位退避、恢复正常读取。
            if (sFailCount >= FAIL_BACKOFF_THRESHOLD) return -1;
            if (slot < 0 || slot >= KEYS.length) return -1;
            return ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).getInt(KEYS[slot], -1);
        } catch (Throwable t) { return -1; }
    }

    /** 主开关是否开启(缓存)。 */
    public static boolean masterEnabled(Context ctx) {
        try { ensureCache(ctx); return sMasterCache; } catch (Throwable t) { return false; }
    }

    /** 是否至少有一个 slot 被自定义过(缓存,避免逐 slot 轮询)。 */
    public static boolean anySlotSet(Context ctx) {
        try { ensureCache(ctx); return sAnySetCache; } catch (Throwable t) { return false; }
    }

    public static void setSlot(Context ctx, int slot, int color) {
        try { ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).edit().putInt(KEYS[slot], color).apply(); }
        catch (Throwable ignored) {}
        invalidateCache();
    }
    public static void clearSlot(Context ctx, int slot) {
        try { ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).edit().putInt(KEYS[slot], -1).apply(); }
        catch (Throwable ignored) {}
        invalidateCache();
    }
    /** 读 slot, 未设置返回 defaultValue. */
    public static int color(Context ctx, int slot, int def) {
        int c = getSlot(ctx, slot);
        return c == -1 ? def : c;
    }

    private static String MASTER_KEY() { return "theme_color_enabled"; }
    private static boolean isMasterEnabled(Context ctx) {
        try { return ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).getBoolean(MASTER_KEY(), false); }
        catch (Throwable t) { return false; }
    }
    private static void setMasterEnabled(Context ctx, boolean on) {
        try { ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).edit().putBoolean(MASTER_KEY(), on).apply(); }
        catch (Throwable ignored) {}
        invalidateCache();
    }

    // ================= 设置入口条目 =================
    public static Object buildEntry(Context ctx, ClassLoader cl) {
        try {
            Class<?> switchPrefCls = Class.forName(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", false, cl);
            Object pref = switchPrefCls.getConstructor(Context.class).newInstance(ctx);
            setProperty(pref, "setTitle", "自定义主题色");
            setProperty(pref, "setKey", "theme_color_enabled");
            setProperty(pref, "setSummary", "开关启用后，点击配置各项颜色");
            setProperty(pref, "setChecked", Boolean.valueOf(isMasterEnabled(ctx)));
            try { setProperty(pref, "setSelectable", Boolean.TRUE); } catch (Throwable ignored) {}
            try { setProperty(pref, "setDividerVisible", Boolean.TRUE); } catch (Throwable ignored) {}

            // 点击条目 -> 弹配色列表
            bindClick(pref, cl, new Runnable() {
                @Override public void run() { showList(ctx, 0); }
            });

            // 切换开关 -> 保存 enabled
            try {
                Class<?> changeListener = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
                Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{changeListener},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    boolean en = args[1] instanceof Boolean && (Boolean) args[1];
                                    setMasterEnabled(ctx, en);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable ignored) {}
                            return Boolean.FALSE;
                        }
                    });
                java.lang.reflect.Method set = pref.getClass().getMethod("setOnPreferenceChangeListener", changeListener);
                set.invoke(pref, listener);
            } catch (Throwable t) { log("change bind err " + t); }

            return pref;
        } catch (Throwable t) { log("entry: " + t); return null; }
    }

    // 分组定义
    static final int[][] CHILDREN = {
        null, null, null, null,
        new int[]{ S_SETTINGS_TITLE, S_SETTINGS_DESC },   // 设置文字 -> 标题/说明
        null, null, null,
        new int[]{ S_SWITCH_ON, S_SWITCH_THUMB, S_SWITCH_OFF }   // 开关 -> 3 色
    };
    static final String[] ROOT_ZH = { "主页图标","主页文字","主页时钟","主页日期","设置文字","设置页大栏目背景色","网页文字","网页背景","开关" };
    static final String[][] CHILD_ZH = {
        null, null, null, null, { "标题","说明" }, null, null, null,
        { "开启色","滑块色(拇指)","关闭色" }
    };
    static final int[] ROOT_SLOT = { S_HOME_ICON, S_HOME_TEXT, S_CLOCK, S_DATE, -1, S_SETTINGS_BG, S_WEB_TEXT, S_WEB_BG, -1 };

    /** level 0=主列表, 1=子列表(rootIndex 指定父行). */
    static void showList(final Context ctx, int rootIndex) {
        try {
            boolean isChild = rootIndex != 0;
            final int parentRoot = rootIndex;
            int[] rows; String[] labels; final int[] slots;
            String title;
            if (isChild) {
                rows = CHILDREN[parentRoot];
                labels = CHILD_ZH[parentRoot];
                slots = rows;
                // 2026-10-04 修复:原为 labels[0].length() > 0 ? ROOT_ZH[..] : ROOT_ZH[..]
                // —— 两个分支完全相同(死条件),且 labels[0] 为 null 时会直接 NPE
                // (子页标题本可以留空表示"无标题")。直接取 ROOT_ZH。
                title = ROOT_ZH[parentRoot];
            } else {
                rows = new int[]{0,1,2,3,4,5,6,7,8};
                labels = ROOT_ZH;
                slots = ROOT_SLOT;
                title = "自定义主题色";
            }

            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 6);
            ll.setPadding(pad, pad, pad, pad);

            for (int i = 0; i < rows.length; i++) {
                final int slot = slots[i];
                final int rowRoot = isChild ? parentRoot : rows[i];
                String label = labels[i];
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));

                // 色块(仅叶子有)
                View swatch = new View(ctx);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28));
                slp.rightMargin = dp(ctx, 12);
                swatch.setLayoutParams(slp);
                int scolor = slot == -1 ? 0xFFCCCCCC : getSlot(ctx, slot);
                swatch.setBackgroundColor(scolor == -1 ? 0xFFCCCCCC : scolor);
                row.addView(swatch);

                // 名称 + 展开提示
                TextView tv = new TextView(ctx);
                String suffix = (!isChild && CHILDREN[rows[i]] != null) ? "  ›" : "";
                tv.setText(label + suffix);
                tv.setTextSize(15);
                tv.setTextColor(0xFF000000);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv);

                // 状态
                TextView st = new TextView(ctx);
                st.setText(slot == -1 ? "" : (getSlot(ctx, slot) == -1 ? "(默认)" : "#" + hex6(getSlot(ctx, slot))));
                st.setTextSize(12);
                st.setTextColor(0xFF888888);
                row.addView(st);

                // 点击: 父行进子列表, 叶子进颜色选择
                if (slot == -1) {
                    final int childEntry = rows[i];
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { reopenList(ctx, childEntry); }
                    });
                } else {
                    final int fSlot = slot;
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { openPickerAfterDismiss(ctx, fSlot, getSlot(ctx, fSlot)); }
                    });
                    row.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            clearSlot(ctx, fSlot); reopenList(ctx, isChild ? parentRoot : 0);
                            toast(ctx, "已恢复默认"); return true;
                        }
                    });
                }
                ll.addView(row);
            }

            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.addView(ll);
            // "完成"必须给一个**非 null** 监听器(2026-09-17 修正)。
            // 原先传 null:AlertDialog 对 null 监听器的按钮处理在各 ROM 上表现不一,
            // 部分三星固件下点了不关闭对话框,用户以为没生效就反复点——
            // 这正是"点完成要按好几次"的来源。传一个显式关闭的监听器即可,
            // 本页无待提交状态(颜色是点子项时立即写入的)。
            sCurrentDialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle(title + (isChild ? "" : "\n(长按某项恢复默认)"))
                .setView(sv)
                .setPositiveButton("完成", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { d.dismiss(); }
                })
                .create();
            // 2026-10-04 修复(静态 Dialog 泄漏):对话框消失时自动清引用。
            // 持有引用是为消除叠加(见 sCurrentDialog 说明),但原实现只在下次打开时
            // 覆盖,用户直接关闭/返回/点外部时静态字段仍强引用以 Activity 为 context
            // 的 AlertDialog → 连带 Activity 与视图树泄漏。
            final android.app.AlertDialog dlgRef = sCurrentDialog;
            dlgRef.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface d) {
                    if (sCurrentDialog == dlgRef) sCurrentDialog = null;   // 只清自己,不误清新框
                }
            });
            sCurrentDialog.show();
        } catch (Throwable t) { log("list: " + t); }
    }

    /**
     * 当前打开的颜色配置对话框(列表页或取色页)。
     *
     * <p>2026-09-17:用于消除对话框叠加。
     *
     * <p>原有三处入口——点父行进入子列表({@code showList})、点子项进入取色页
     * ({@code showColorPicker})、长按恢复默认后回到列表——都是**直接 show 一个
     * 新对话框,而不关闭旧的**。于是每操作一次就叠一层,用户看到的是"点了没反应",
     * 必须连点好几次穿透上层才能点到底下的目标。长按恢复那条尤其明显:
     * 它在 L312 先 clearSlot 再 showList,叠加的同时配置已经被改了。
     *
     * <p>改为持有引用:新对话框 show 之前先把旧的 dismiss 掉,始终保持只有一层。
     */
    private static volatile android.app.AlertDialog sCurrentDialog;

    /** 关掉当前对话框并重新打开颜色列表(用于长按恢复默认后刷新)。 */
    private static void reopenList(final Context ctx, final int rootIndex) {
        dismissCurrent();
        showList(ctx, rootIndex);
    }

    /** 关掉当前对话框并打开取色页(用于点叶子项)。 */
    private static void openPickerAfterDismiss(final Context ctx, final int slot, final int current) {
        dismissCurrent();
        showColorPicker(ctx, slot, current);
    }

    private static void dismissCurrent() {
        android.app.AlertDialog d = sCurrentDialog;
        sCurrentDialog = null;
        if (d != null && d.isShowing()) {
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
    }

    // ================= 颜色选择 =================
    static void showColorPicker(final Context ctx, final int slot, int current) {
        try {
            final int startColor = current == -1 ? 0xFF505E81 : current;
            final LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 14);
            root.setPadding(pad, pad, pad, pad);

            final View preview = new View(ctx);
            preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44)));
            preview.setBackgroundColor(startColor);
            root.addView(preview);
            root.addView(spacer(ctx, dp(ctx, 8)));

            final EditText hexEt = new EditText(ctx);
            hexEt.setText("#" + hex6(startColor));
            hexEt.setSingleLine(true);
            hexEt.setTextSize(15);
            hexEt.setTextColor(0xFF000000);
            hexEt.setGravity(Gravity.CENTER);
            root.addView(hexEt);
            root.addView(spacer(ctx, dp(ctx, 8)));

            final HsvPicker picker = new HsvPicker(ctx);
            picker.attach(hexEt, preview);
            picker.setColor(startColor);
            picker.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 330)));
            root.addView(picker);
            root.addView(spacer(ctx, dp(ctx, 8)));

            LinearLayout presetRow = new LinearLayout(ctx);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView presetLabel = new TextView(ctx);
            presetLabel.setText("预设:");
            presetLabel.setTextSize(13);
            presetLabel.setTextColor(0xFF666666);
            presetRow.addView(presetLabel);
            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout inner = new LinearLayout(ctx);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            for (final int pc : PRESETS) {
                View sw = new View(ctx);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30));
                slp.leftMargin = dp(ctx, 6);
                sw.setLayoutParams(slp);
                sw.setBackgroundColor(pc);
                sw.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        hexEt.setText("#" + hex6(pc)); picker.setColor(pc); preview.setBackgroundColor(pc);
                    }
                });
                inner.addView(sw);
            }
            hs.addView(inner);
            presetRow.addView(hs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(presetRow);

            sCurrentDialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle(labelFor(slot))
                .setView(root)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        int c = parseHex(hexEt.getText().toString());
                        if (c != -1) { setSlot(ctx, slot, c); toast(ctx, "已保存"); }
                        else toast(ctx, "颜色格式错误");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
            // 2026-10-04 修复(静态 Dialog 泄漏):同上,取色页消失时也自动清引用。
            final android.app.AlertDialog pickerRef = sCurrentDialog;
            pickerRef.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface d) {
                    if (sCurrentDialog == pickerRef) sCurrentDialog = null;
                }
            });
            sCurrentDialog.show();
        } catch (Throwable t) { log("picker: " + t); }
    }

    static String labelFor(int slot) {
        switch (slot) {
            case S_HOME_ICON: return "主页图标";
            case S_HOME_TEXT: return "主页文字";
            case S_SETTINGS_TITLE: return "设置文字 · 标题";
            case S_SETTINGS_DESC: return "设置文字 · 说明";
            case S_SETTINGS_BG: return "设置页大栏目背景色";
            case S_WEB_TEXT: return "网页文字";
            case S_WEB_BG: return "网页背景";
            case S_SWITCH_ON: return "开关 · 开启色";
            case S_SWITCH_THUMB: return "开关 · 滑块色";
            case S_SWITCH_OFF: return "开关 · 关闭色";
            case S_CLOCK: return "主页时钟";
            case S_DATE: return "主页日期";
            default: return "颜色";
        }
    }

    // ============ 工具 ============
    static String hex6(int c) {
        String h = Integer.toHexString(c & 0xFFFFFF);
        while (h.length() < 6) h = "0" + h;
        return h.toUpperCase();
    }
    static int parseHex(String s) {
        try {
            s = s.trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() == 6) return (int)(0xFF000000L | Long.parseLong(s, 16));
        } catch (Throwable ignored) {}
        return -1;
    }
    static int dp(Context ctx, float v) { return (int)(v * ctx.getResources().getDisplayMetrics().density + 0.5f); }
    static View spacer(Context ctx, int h) { View v = new View(ctx); v.setLayoutParams(new LinearLayout.LayoutParams(1, h)); return v; }
    static void toast(Context ctx, String msg) { try { android.widget.Toast.makeText(ctx, msg, 0).show(); } catch (Throwable ignored) {} }
    static void log(String msg) { try { MainModule.logMsg("[SBPlus] themecolor " + msg); } catch (Throwable ignored) {} }
    static void setProperty(Object obj, String method, Object arg) {
        try { MainHook.callMethod(obj, method, arg); } catch (Throwable t) { log("call " + method + " err " + t); }
    }

    static Class<?> listenerParamType(Class<?> cls, String methodName) {
        try {
            for (java.lang.reflect.Method mm : cls.getMethods()) {
                if (mm.getName().equals(methodName)) {
                    Class<?>[] pts = mm.getParameterTypes();
                    if (pts.length == 1) return pts[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    static void bindClick(Object pref, ClassLoader cl, final Runnable r) {
        try {
            java.lang.reflect.Method mm = null;
            for (java.lang.reflect.Method m : pref.getClass().getMethods()) {
                if (m.getName().equals("setOnPreferenceClickListener") && m.getParameterTypes().length == 1) { mm = m; break; }
            }
            if (mm == null) return;
            final Class<?> lc = mm.getParameterTypes()[0];
            Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{lc},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try { if (m.getName().equals("onPreferenceClick")) { r.run(); return Boolean.TRUE; } }
                        catch (Throwable t) {}
                        return Boolean.FALSE;
                    }
                });
            mm.invoke(pref, listener);
        } catch (Throwable t) { log("bind: " + t); }
    }
}
