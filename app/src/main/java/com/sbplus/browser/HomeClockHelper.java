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

    /**
     * 精确到秒。
     *
     * <p>2026-10-04:保留"默认开"的语义(prefs 不可用时返回 true),但补注释说明
     * 它与本类其它 getter 的默认方向**不同** —— 其它 getter 读不到时返回 false。
     * 这不是笔误:秒针属于"默认展示更完整"的选项,而"是否跟随系统""是否显示"
     * 这类开关默认关更符合预期。统一方向会改变用户可见的默认表现,故保持原样,
     * 只把差异写清楚以免后人误"修正"。
     */
    public static boolean isSeconds(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null || p.getBoolean("seconds", true);   // 默认开(与其它 getter 相反,见上)
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

    // ===== 12/24 小时制 =====

    /**
     * 是否 24 小时制。
     *
     * <p>2026-10-09 新增：翻页时钟重写时补上（旧实现只有 24 小时制）。
     * 默认**开**（读不到 prefs 时返回 true）—— 与 {@link #isSeconds} 同属
     * "默认展示更完整"的选项，而与 {@link #isShowDate} 这类"默认关"的开关方向不同。
     */
    public static boolean is24Hour(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p == null || p.getBoolean("hour24", true);
    }

    public static void set24Hour(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putBoolean("hour24", on).apply();
    }

    // ===== 翻页时钟数字字体 =====

    /**
     * 翻页时钟的数字字体（仅翻页样式生效）。
     *
     * <p>2026-10-09：从"系统字体族名"改为**打包字体**。原因 —— 站点用的字体栈
     * {@code "Helvetica Neue",Helvetica,sans-serif} 且**没有任何 webfont**，
     * 所以在电脑上落到 Arial/Helvetica（方正、有棱角），而手机系统的 {@code sans-serif}
     * 是圆润的 SamsungOne：同一份 CSS 两端观感完全不同。把 Arial 血统的开源字体打进
     * APK（{@code assets/fonts/}，已子集化到只含 0-9 与冒号）才能跨设备固定一致。
     *
     * <p>2026-10-12：本常量与 {@link #CUSTOM_FONT} 现在是<b>仅有的两个合法取值</b>
     * （见 {@link #isAllowedFont}）。历史实现还接受 {@code sans-serif} /
     * {@code monospace} 这类 Android 字体族名，以及另外几款打包字体，但那些取值
     * 已从设置页下架 —— {@code FlipClockView} 内部仍能解析它们（组件本身保持通用），
     * 只是模块不再产生这类值。
     */
    public static final String DEFAULT_FONT = "asset:ArialEnUnicodeBold.ttf";

    /**
     * "跟随自定义字体"（{@link FontHelper} 里用户导入并选中的字体文件）。
     *
     * <p>2026-10-09 新增：翻页时钟的数字直接用用户自己的字体，与浏览器全局自定义
     * 字体同源。它是个**特殊值**而不是系统字体族名 —— {@code FlipClockView} 认这个
     * 前缀并改用宿主注入的 Typeface；没导入字体时回退 {@link #DEFAULT_FONT}。
     */
    public static final String CUSTOM_FONT = "custom:";

    /**
     * 字体方案版本：每次换默认字体 +1，用一次性升级把旧值顶掉。
     *
     * <p>2026-10-12：8 → 9。这次不是换默认字体，而是**收窄可选范围** ——
     * 设置页的数字字体下拉从 12 项砍到 2 项（默认 / 跟随自定义字体）。
     * 已装用户 prefs 里存的很可能是被删掉的那 10 项之一（系统字体族名或其它
     * 打包字体），+1 让 {@link #getFontFamily} 一次性把它们全部顶回默认，
     * 避免"设置页显示默认、实际生效的是别的字体"这种错位。
     */
    private static final int FONT_SCHEME = 9;

    /**
     * 字体取值白名单（2026-10-12 新增）。
     *
     * <p>只允许 {@link #DEFAULT_FONT} 与 {@link #CUSTOM_FONT}。
     *
     * <p>为什么除了 FONT_SCHEME 还要这一道：FONT_SCHEME 是**一次性**闸门，
     * 它只能清掉"升级那一刻"的旧值；而 prefs 还可能通过备份还原、adb 直接改
     * 等路径重新出现旧值。白名单是**永久**约束 —— 设置页既然只提供两个选项，
     * 生效值就不该出现第三种，否则 UI 与实际行为脱节。
     *
     * <p>将来若要在设置页加回某个字体，**必须同步改这里**，否则选了也不生效。
     */
    private static boolean isAllowedFont(String s) {
        return DEFAULT_FONT.equals(s) || CUSTOM_FONT.equals(s);
    }

    public static String getFontFamily(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p == null) return DEFAULT_FONT;
        if (p.getInt("font_scheme", 1) < FONT_SCHEME) {
            // 旧方案存的是系统字体族名（且多为默认值而非用户主动选择），整体升级一次
            p.edit().putInt("font_scheme", FONT_SCHEME)
                    .putString("font_family", DEFAULT_FONT).apply();
            return DEFAULT_FONT;
        }
        String s = p.getString("font_family", DEFAULT_FONT);
        if (s == null || s.length() == 0) return DEFAULT_FONT;
        if (!isAllowedFont(s)) {
            // 白名单外的旧值（旧版本存的系统字体族名 / 已下架的打包字体）：
            // 归一到默认，并**顺手写回** —— 只返回默认而不写回的话，设置页每次
            // 打开都要重新归一，且写回路径（setFontFamily）之外的读取者看到的
            // 仍是不一致的值。
            try {
                p.edit().putString("font_family", DEFAULT_FONT).apply();
            } catch (Throwable ignored) {
                // 写失败不影响本次返回：本次仍如实返回默认值。
            }
            return DEFAULT_FONT;
        }
        return s;
    }

    public static void setFontFamily(Context ctx, String family) {
        SharedPreferences p = prefs(ctx);
        if (p != null) p.edit().putInt("font_scheme", FONT_SCHEME).putString("font_family",
                (family == null || family.length() == 0) ? DEFAULT_FONT : family).apply();
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
