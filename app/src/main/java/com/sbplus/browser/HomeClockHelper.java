package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * 主页时钟管理: 支持精确到秒, 自定义大小位置.
 *
 * <p>性能说明(2026-09-17 优化):
 * 原实现每个 getter 都写一次
 * {@code ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getXxx(...)}。
 * {@code getSharedPreferences} 本身会被系统按名缓存, 但一次调用仍包含:
 * 名字查表 + 返回对象 + 再次查 XML 解析出的内存 Map, 且**每次都要过一遍
 * Context 的锁**。本类的读取集中在 {@code MainHook.homeClockSignature()},
 * 一次就调 5 个 getter → 5 次 getSharedPreferences + 5 次 Map 查找; 该方法
 * 又在 attachHomeClock / 设置页刷新等路径上被反复调用。
 *
 * 这里把 SharedPreferences 实例缓存为静态字段(与 {@code ThemeColorHelper}
 * 的做法一致), 并把读取收敛到 {@link #prefs(Context)} 一处。
 * 缓存的实例与系统返回的是同一个对象, 因此一旦有写入会立即可见,
 * 不存在陈旧数据问题——{@code SharedPreferences} 本身就是内存共享的。
 */
public class HomeClockHelper {

    public static final String PREFS = "sbplus_home_clock";

    /**
     * 缓存的 SharedPreferences 实例。
     *
     * <p>用 volatile:本类方法可能从主线程与后台线程同时被调用(设置页刷新、
     * 附着流程), 需要保证引用发布的可见性。实际赋值的对象由系统缓存并保证
     * 线程安全, 故此处只保护"引用可见"这一件事。
     */
    private static volatile SharedPreferences sPrefs;

    /** 取得(并缓存)本模块的时钟配置。ctx 为 null 时返回 null, 调用方需兜底。 */
    private static SharedPreferences prefs(Context ctx) {
        SharedPreferences p = sPrefs;
        if (p != null) return p;
        if (ctx == null) return null;
        try {
            p = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sPrefs = p;
            return p;
        } catch (Throwable t) {
            // 取不到就让调用方走默认值,不要抛给 UI 层
            MainModule.logMsg("[SBPlus] HomeClockHelper.prefs error: " + t);
            return null;
        }
    }

    public static boolean isEnabled(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p != null && p.getBoolean("enabled", false);
    }

    public static void setEnabled(Context ctx, boolean en) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("enabled", en).apply();
    }

    /** 精确到秒. */
    public static boolean isSeconds(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null || p.getBoolean("seconds", true);   // 默认开
    }

    public static void setSeconds(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("seconds", on).apply();
    }

    /** 显示日期行(月日+星期). 默认关. */
    public static boolean isShowDate(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p != null && p.getBoolean("show_date", false);
    }

    public static void setShowDate(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("show_date", on).apply();
    }

    // ===== 日期独立条目(与时钟对称的完整配置) =====

    /** 日期条目总开关(独立于时钟的 show_date). 默认关. */
    public static boolean isDateEnabled(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p != null && p.getBoolean("date_enabled", false);
    }

    public static void setDateEnabled(Context ctx, boolean en) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("date_enabled", en).apply();
    }

    /** 日期位置 X 百分比. 默认时钟下方居中(50). */
    public static int getDatePosX(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 50 : p.getInt("date_posx", 50);
    }

    public static void setDatePosX(Context ctx, int v) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("date_posx", v).apply();
    }

    /** 日期位置 Y 百分比. 默认 42(时钟默认 30 的下方). */
    public static int getDatePosY(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 42 : p.getInt("date_posy", 42);
    }

    public static void setDatePosY(Context ctx, int v) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("date_posy", v).apply();
    }

    /** 日期大小百分比(50-200). 默认 100 = 时钟字号的 30%. */
    public static int getDateSizePct(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 100 : p.getInt("date_size_pct", 100);
    }

    public static void setDateSizePct(Context ctx, int v) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("date_size_pct", v).apply();
    }

    /** 日期是否跟随搜索框动画. 默认跟随时钟(独立开关关时). */
    public static boolean isDateFollow(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p != null && p.getBoolean("date_follow", false);
    }

    public static void setDateFollow(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("date_follow", on).apply();
    }

    // ===== 时钟样式: 0=普通文字, 1=翻页卡片 =====

    public static int getClockStyle(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 0 : p.getInt("clock_style", 0);
    }

    public static void setClockStyle(Context ctx, int style) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("clock_style", style).apply();
    }

    /** 位置: X 百分比(0-100, 锚点中心). */
    public static int getPosX(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 50 : p.getInt("posx", 50);
    }

    public static void setPosX(Context ctx, int v) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("posx", v).apply();
    }

    /** 位置: Y 百分比(0-100, 锚点中心). */
    public static int getPosY(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 30 : p.getInt("posy", 30);
    }

    public static void setPosY(Context ctx, int v) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("posy", v).apply();
    }

    /** 大小: 百分比(50-200, 100=默认). */
    public static int getSizePct(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null ? 100 : p.getInt("size", 100);
    }

    public static void setSizePct(Context ctx, int v) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("size", v).apply();
    }

    /** 跟随搜索框动画. */
    public static boolean isFollow(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null || p.getBoolean("follow", true);    // 默认开
    }

    public static void setFollow(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("follow", on).apply();
    }
}
