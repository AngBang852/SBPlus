package com.sbplus.browser.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import java.util.Calendar;

/**
 * 翻页时钟（Flip Clock）—— 纯 Canvas 自绘的自定义 View，可在布局 XML 中引用。
 *
 * <p><b>规格来源</b>：参考站点 flipclock.online 的 flipclock.css（FlipClock 0.7.x）。
 * 本类里的每一个比例常量都能在 CSS 里找到对应行，不做主观调整：
 * <pre>
 *   ul              { width:60px; height:90px; margin:5px; border-radius:6px; background:#000 }
 *   ul li a         { perspective:200px }
 *   li a div        { height:50%; overflow:hidden }
 *   li a div.up     { transform-origin:50% 100%; top:0 }
 *   li a div.down   { transform-origin:50% 0;    bottom:0 }
 *   li a div div.inn{ height:200%; font-size:70px; color:#ccc; background-color:#333;
 *                     border-radius:6px; text-shadow:0 1px 2px #000 }
 *   div.up:after    { top:44px; height:3px; background:rgba(0,0,0,.4); z-index:5 }
 *   @keyframes turn2{ rotateX(0deg  -> -90deg) 0.5s linear         (旧值上半翻走) }
 *   @keyframes turn { rotateX(90deg ->    0deg) 0.5s linear 0.5s  (新值下半落回) }
 *   @keyframes asd  { z-index 2 -> 4 (20%)     0.5s linear 0.5s  (新旧层交接)   }
 *   @keyframes show { opacity 0 -> 1           0.5s linear        (暗化遮罩渐显) }
 *   @keyframes hide { opacity 1 -> 0           0.5s linear .3s/.2s(暗化遮罩渐褪) }
 * </pre>
 *
 * <p><b>为什么是 Canvas 自绘而不是 WebView</b>：本组件运行在三星浏览器进程内
 * （LSPosed 模块注入宿主）。WebView 在本机实测会撞 V8 版本冲突（浏览器内核
 * libterrace.so 自带 V8 14.8 vs 系统 WebView snapshot 15.0）→ 渲染进程 abort →
 * 宿主闪退；改走浏览器自带内核则拿不到 compositor 窗口注册、一帧都不画。
 * 纯 Canvas 单 View 自绘不新增任何子 View，对宿主视图树零侵入，也彻底绕开
 * Chromium 的初始化与合成链路。
 *
 * <p><b>实现要点</b>
 * <ul>
 *   <li>3D 翻转用 SkMatrix 的透视分量（{@link Matrix#setValues}）直接实现 CSS 的
 *       {@code rotateX(θ)} + {@code perspective:200px}；绕中缝边旋转对应
 *       {@code transform-origin:50% 100%} / {@code 50% 0}。不使用
 *       {@link android.graphics.Camera}（其相机单位与 z 轴方向都与 CSS 不一致，
 *       会让透视强度与翻转方向变成近似值）。</li>
 *   <li>暗化遮罩用 {@link LinearGradient} 复刻 {@code .shadow} 的渐变方向，透明度由
 *       动画进度驱动。</li>
 *   <li>走时对齐整秒（{@code 1000 - now%1000}）而不是固定 {@code postDelayed(1000)}，
 *       长时间运行不累计漂移。</li>
 *   <li>空闲时零重绘：只有整秒 tick 一次；翻转期间才开 Choreographer 帧循环。</li>
 *   <li>{@link #onDetachedFromWindow()} 停表 + 取消帧回调，不留泄漏。</li>
 * </ul>
 *
 * <p><b>运行环境约束（重要）</b>：本类刻意**不引用**模块的 {@code R} 类与
 * {@code BuildConfig}。宿主进程加载不到模块自己的资源表，任何 {@code R.xxx} 引用
 * 都会在宿主里变成 {@code NoClassDefFoundError}。因此 XML 属性解析走"按属性名
 * 逐条匹配"（{@link #readAttributeSet}），不依赖 {@code R.styleable}。
 * 对应的属性清单见 {@code res/values/attrs.xml}。
 */
public class FlipClockView extends View {

    // ======================================================================
    // 站点比例常量 —— 全部取自 flipclock.css，不做主观调整
    // ======================================================================

    /** 卡高 : 数字字号 = 90 : 70（CSS ul height:90px / .inn font-size:70px）。 */
    private static final float RATIO_CARD_PER_FONT = 90f / 70f;

    /**
     * 站点字体（Helvetica / Arial）的**数字墨迹高 ÷ 字号**。
     *
     * <p>实测（System.Drawing 量 ink bounds）：系统 Arial Bold = <b>0.719</b>，
     * Liberation Sans Bold = 0.698。站点 CSS 里 {@code .inn{font-size:70px}} 在
     * 90px 卡高上量出来的数字墨迹高正是 {@code 0.719 × 70 = 50.3px}，
     * 即<b>占卡高 55.9%</b>（headless Edge 按站点原版 CSS 渲染实测：50/90 = 55.6%）。
     * 本组件以它为观感基准。
     */
    private static final float SITE_DIGIT_INK_RATIO = 0.719f;

    /** 探测字体墨迹比例时用的临时字号（px）；比例与字号无关，够大能量准即可。 */
    private static final float INK_PROBE_PX = 100f;

    /**
     * 数字大小的微调旋钮（{@code 1f} = 与站点完全一致）。
     *
     * <p>默认 {@code 1f}：数字墨迹高 = 卡高 × 55.9%，与 flipclock.online 一致。
     * 想更小往 0.9 调、更大往 1.1 调。它只作用于数字，不影响任何几何。
     */
    private static final float DIGIT_DRAW_SCALE = 1f;
    /** 基准卡高（CSS 里的 90px），其余比例都以它为分母换算成 u。 */
    private static final float BASE_CARD_H = 90f;
    /** 卡宽 : 卡高 = 60 : 90。 */
    private static final float RATIO_CARD_W = 60f / 90f;
    /** 卡外边距 : 卡高 = 5 : 90（ul margin:5px，两卡之间合计 10u）。 */
    private static final float RATIO_MARGIN = 5f / 90f;
    /** 冒号槽宽 : 卡高 = 20 : 90。 */
    private static final float RATIO_COLON = 20f / 90f;
    /** 圆角 : 卡高 = 6 : 90。 */
    private static final float RATIO_RADIUS = 6f / 90f;
    /** 中缝高 : 卡高 = 3 : 90。 */
    private static final float RATIO_SEAM_H = 3f / 90f;
    /** 中缝顶 : 卡高 = 44 : 90（.up:after top:44px）。 */
    private static final float RATIO_SEAM_TOP = 44f / 90f;
    /** perspective : 卡高 = 200 : 90。 */
    private static final float RATIO_PERSPECTIVE = 200f / 90f;
    /** 冒号点直径 : 卡高 = 10 : 90。 */
    private static final float RATIO_DOT = 10f / 90f;
    /** 冒号上点顶 : 卡高 = 30 : 90（.flip-clock-dot.top top:30px）。 */
    private static final float RATIO_DOT_TOP = 30f / 90f;
    /** 冒号下点顶 : 卡高 = 50 : 90（.flip-clock-dot.bottom bottom:30px → 90-30-10）。 */
    private static final float RATIO_DOT_BOTTOM = 50f / 90f;
    /** 暗化遮罩最浅一端的透明度（CSS rgba(0,0,0,.1)）。 */
    private static final int SHADOW_LIGHT = 0x1A000000;

    // ======================================================================
    // 卡片外投影 —— 几何照站点，浓度另行校准
    //
    // 站点原版是 .flip { box-shadow:0 2px 5px rgba(0,0,0,.7) }：偏移 y=2u、模糊 5u、
    // 70% 黑。**几何部分（5u / 2u）照抄站点** —— 2026-10-13 用户明确要求恢复 5u。
    //
    // 但**浓度不能照抄 0.7**：CSS 的 box-shadow 是标准高斯模糊，模糊核有一半落在
    // 卡片底下被遮住，所以卡边处的实际不透明度只有约 0.7 × 0.5 ≈ 0.35，再向外快速衰减。
    // 本组件用「多层逐渐扩大、逐渐变淡的圆角矩形」近似高斯（硬件加速下
    // Paint.setShadowLayer 对**非文本**绘制会被忽略），多层是**叠加**的 —— 若每层都用
    // 0.7，卡边会累加到约 0.77，观感就是"又厚又重的黑边"（2026-10-12 用户反馈）。
    //
    // 所以每层的不透明度系数按「卡边等效 ≈ 0.35」反推：
    //   layers = round(5u)，Σ(1-t) ≈ (layers-1)/2
    //   Σα = 255 × 0.7 × SHADOW_STRENGTH × Σ(1-t)
    //   卡边不透明度 = 1 - exp(-Σα/255) ≈ 0.34   ✓
    // 注意 layers 随卡高的像素值变化，浓度会有 ±0.04 的漂移，可接受。
    // ======================================================================

    /** 卡片外投影的模糊半径（单位 u，u = 卡高/90）—— 站点 box-shadow 的 5px。 */
    private static final float SHADOW_BLUR_U = 5f;
    /** 卡片外投影的下偏移（单位 u）—— 站点 box-shadow 的 2px。 */
    private static final float SHADOW_DY_U = 2f;
    /** 卡片外投影每层的不透明度系数（站点 box-shadow 的 .7 另在公式里保留）。 */
    private static final float SHADOW_STRENGTH = 0.12f;

    /** 新旧层交接时刻（@keyframes asd：0.5s 延迟 + 20% × 0.5s = 0.6s）。 */
    private static final float HANDOVER_T = 0.60f;
    /** turn2 结束时刻（0.5s / 1.0s）。 */
    private static final float PHASE_T = 0.50f;
    /** active .up .shadow 的 hide 延迟（0.3s / 1.0s）。 */
    private static final float HIDE_DELAY_UP = 0.30f;
    /** active .down .shadow 的 hide 延迟（站点 CSS 里是 0.2s）。 */
    private static final float HIDE_DELAY_DOWN = 0.20f;
    /** hide/show 动画时长（0.5s / 1.0s）。 */
    private static final float SHADOW_SPAN = 0.50f;

    private static final String[] DIGITS = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" };
    /** 用于测量数字字形边界的样本（十个数字一起量，取整体外框）。 */
    private static final String ALL_DIGITS = "0123456789";

    /** 最大格位数：HH:MM:SS。 */
    private static final int MAX_CELLS = 8;

    // ======================================================================
    // 可配置项
    // ======================================================================

    /**
     * 尺寸基准（px）—— 对应站点 CSS 里 {@code ul{font-size:80px} / .inn{font-size:70px}}
     * 那一档的"字号"。
     *
     * <p>卡高 = 它 × 90/70；卡宽 / 圆角 / 缝 / 透视距离全部由卡高派生。
     *
     * <p><b>注意：它不等于屏幕上数字的实际绘制字号。</b> 实际字号由
     * {@link #SITE_DIGIT_INK_RATIO} 与当前字体的实测墨迹比例反推
     * （见 {@code recomputeGeometry()}）—— 因为不同字体的「数字墨迹高 ÷ 字号」
     * 能差到 2 倍（打包的 ArialEnUnicodeBold 是 1.438，Arial 是 0.719）。
     * 以它为基准才能保证换任何字体时卡高不变、只有数字按站点观感归一。
     */
    private float mDigitSizePx;
    /** 数字颜色。默认纯白（站点原版是 #ccc，按用户要求改为白色）。 */
    private int mDigitColor = 0xFFFFFFFF;
    /** 数字色块颜色。 */
    private int mCardColor = 0xFF333333;
    /** 卡片底层颜色。 */
    private int mCardBackColor = 0xFF000000;
    /** 中缝颜色。 */
    private int mSeamColor = 0x66000000;
    /** 冒号颜色；{@code mColonColorSet == false} 时跟随数字颜色。 */
    private int mColonColor = 0xFFCCCCCC;
    private boolean mColonColorSet;
    /** 圆角（px）；&lt; 0 表示按比例推导。 */
    private float mCornerRadiusPx = -1f;
    /** 单阶段翻页时长（ms），站点 0.5s。 */
    private int mFlipDurationMs = 500;
    /** 是否显示秒。 */
    private boolean mShowSeconds = true;
    /** 是否 24 小时制。 */
    private boolean mHour24 = true;
    /** 数字是否加粗。 */
    private boolean mDigitBold = true;
    /**
     * 字体族名。
     *
     * <p>默认 {@code sans-serif-condensed}：三星设备上的 {@code sans-serif} 会落到圆润的
     * SamsungOne，而 {@code sans-serif-condensed} 落在 Roboto Condensed —— 几何、方正、
     * 笔画直，配 Bold 更接近站点 Helvetica Neue Bold 那种硬朗观感。
     * 可在设置页换成 sans-serif / sans-serif-black / monospace / serif 等。
     */
    private String mFontFamily = DEFAULT_FONT;

    /**
     * "跟随自定义字体"用的外部字体。
     *
     * <p>字体族名传 {@link #CUSTOM_FONT} 时用它；为 {@code null}（还没加载完 /
     * 用户没导入字体）则回退到 {@link #DEFAULT_FONT}。
     *
     * <p>为什么要外部注入：自定义字体文件在模块的私有目录里，加载要读磁盘，
     * 而本组件刻意不引用模块的 {@code FontHelper}（保持"只依赖 android.*"的可复用性）。
     */
    private Typeface mCustomTypeface;

    /** 字体族名的特殊值：跟随宿主传入的自定义字体（{@link #setCustomTypeface}）。 */
    public static final String CUSTOM_FONT = "custom:";

    /** 默认字体（打包在 APK 的 assets/fonts/ 里，只含 0-9 与冒号）。 */
    public static final String DEFAULT_FONT = "asset:ArialEnUnicodeBold.ttf";

    // ======================================================================
    // 派生几何（recomputeGeometry 里一次算好）
    // ======================================================================

    private float mCardH;
    private float mCardW;
    private float mHalfH;
    private float mGap;
    private float mColonW;
    private float mRadius;
    private float mSeamH;
    private float mSeamTop;
    private float mPersp;
    /** 1u = 站点 CSS 里的 1px（= 卡高/90）。阴影等尺寸都按它换算。 */
    private float mUnit;
    private float mDotD;
    private float mDotTopY;
    private float mDotBottomY;

    // ======================================================================
    // 时间状态
    // ======================================================================

    /** 是否正在走时。 */
    private boolean mRunning;
    /** 是否倒计时模式。 */
    private boolean mCountDown;
    /** 倒计时目标时刻（epoch ms）。 */
    private long mCountDownEndMs;
    /** 倒计时是否已到点（避免重复回调）。 */
    private boolean mCountDownFired;
    /** 固定显示时刻（epoch ms）；&lt; 0 表示跟随系统时间。 */
    private long mFixedMs = -1L;

    private OnCountDownFinishListener mFinishListener;

    /** 倒计时结束回调。 */
    public interface OnCountDownFinishListener {
        void onCountDownFinish();
    }

    // ======================================================================
    // 格位与数字位状态
    // ======================================================================

    /** 单个数字位的翻页状态。 */
    private static class Digit {
        /** 当前（新）数字。 */
        int cur;
        /** 翻页前（旧）数字。 */
        int prev;
        /** 本轮翻页起始时刻（{@link SystemClock#uptimeMillis()}）；&lt; 0 表示空闲。 */
        long animStart = -1L;
        /** 是否已初始化过（首帧直接落值，不播动画）。 */
        boolean valid;
    }

    private final Digit[] mDigits = new Digit[MAX_CELLS / 2 + 2];
    private final boolean[] mCellIsColon = new boolean[MAX_CELLS];
    private int mCellCount;
    private int mDigitCount;

    // ======================================================================
    // 绘制资源（全部复用，onDraw 里零分配）
    // ======================================================================

    private final Matrix mMatrix = new Matrix();
    private final float[] mMatrixValues = new float[9];
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint();
    private final Paint mSeam = new Paint();
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final float[] mRadii = new float[8];
    private final Rect mGlyphBounds = new Rect();
    /** 卡片外投影用的画笔与临时矩形（见 {@link #drawCardShadow}）。 */
    private final Paint mCardShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mShadowRect = new RectF();
    /** 已加载的打包字体缓存（字体名 → Typeface），避免每次重算几何都读一次 assets。 */
    private String mCachedFontKey;
    private Typeface mCachedTypeface;
    /**
     * 数字字形外框中心相对 baseline 的偏移（{@link #ALL_DIGITS} 量得，随字号/字体变化）。
     *
     * <p>用它而不是 {@code FontMetrics.ascent/descent} 来居中：ascent/descent 描述的是
     * 字体的**整体**预留空间（含重音符号的上行区、g/j 这类下沉字母的下行区），
     * 纯数字根本不占那些空间。实测（真机 204px 卡高）用 ascent/descent 居中会让数字
     * 偏下 8px，而按字形外框居中才是视觉上的"上下居中"。
     */
    private float mGlyphCenterOffset;
    private Shader mShadowUp;
    private Shader mShadowDown;

    // ======================================================================
    // 走时 / 帧循环
    // ======================================================================

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Choreographer mChoreographer;
    private boolean mFramePosted;
    private boolean mAttached;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!mRunning || !mAttached) return;
            refreshTime(true);
            // 对齐到下一个整秒：不累计漂移，也不受上一帧耗时影响。
            long now = System.currentTimeMillis();
            long delay = 1000L - (now % 1000L);
            if (delay <= 0L) delay = 1000L;
            mHandler.postDelayed(this, delay);
        }
    };

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            mFramePosted = false;
            if (!mAttached) return;
            invalidate();
            if (hasRunningFlip()) {
                postFrame();
            }
        }
    };

    // ======================================================================
    // 构造
    // ======================================================================

    public FlipClockView(Context context) {
        this(context, null);
    }

    public FlipClockView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlipClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        readAttributeSet(attrs);
        recomputeGeometry();
        initDigits();
    }

    /**
     * 解析 XML 属性 —— **按属性名逐条匹配**，不使用 {@code R.styleable}。
     *
     * <p>原因见类注释：宿主进程加载不到模块自己的资源表，任何 {@code R.xxx}
     * 引用都会变成 {@code NoClassDefFoundError}。按名字匹配在模块自身 Activity
     * 与宿主注入两种环境里都能工作。
     *
     * <p>值只支持字面量（{@code 40dp} / {@code #CCCCCC} / {@code 500} / {@code true}）；
     * {@code @dimen/xxx} 这类资源引用会被静默忽略并保留默认值 —— 宿主环境里
     * 本来也解析不了模块的资源。
     */
    private void readAttributeSet(AttributeSet attrs) {
        if (attrs == null) return;
        try {
            for (int i = 0; i < attrs.getAttributeCount(); i++) {
                final String name = attrs.getAttributeName(i);
                final String value = attrs.getAttributeValue(i);
                if (name == null || value == null) continue;
                try {
                    if ("fcDigitSize".equals(name)) {
                        float px = parseDimension(value);
                        if (!Float.isNaN(px)) mDigitSizePx = px;
                    } else if ("fcDigitColor".equals(name)) {
                        int c = parseColor(value);
                        if (c != -1) mDigitColor = c;
                    } else if ("fcCardColor".equals(name)) {
                        int c = parseColor(value);
                        if (c != -1) mCardColor = c;
                    } else if ("fcCardBackColor".equals(name)) {
                        int c = parseColor(value);
                        if (c != -1) mCardBackColor = c;
                    } else if ("fcSeamColor".equals(name)) {
                        int c = parseColor(value);
                        if (c != -1) mSeamColor = c;
                    } else if ("fcColonColor".equals(name)) {
                        int c = parseColor(value);
                        if (c != -1) { mColonColor = c; mColonColorSet = true; }
                    } else if ("fcCornerRadius".equals(name)) {
                        float px = parseDimension(value);
                        if (!Float.isNaN(px)) mCornerRadiusPx = px;
                    } else if ("fcFlipDuration".equals(name)) {
                        int ms = parseInt(value);
                        if (ms > 0) mFlipDurationMs = ms;
                    } else if ("fcShowSeconds".equals(name)) {
                        mShowSeconds = parseBool(value, mShowSeconds);
                    } else if ("fcHour24".equals(name)) {
                        mHour24 = parseBool(value, mHour24);
                    } else if ("fcDigitBold".equals(name)) {
                        mDigitBold = parseBool(value, mDigitBold);
                    } else if ("fcFontFamily".equals(name)) {
                        String fam = value.trim();
                        if (fam.length() > 0 && fam.charAt(0) != '@' && fam.charAt(0) != '?') {
                            mFontFamily = fam;
                        }
                    } else if ("fcCountDown".equals(name)) {
                        mCountDown = parseBool(value, mCountDown);
                    }
                } catch (Throwable ignoredInner) {
                    // 单个属性解析失败不影响其余属性
                }
            }
        } catch (Throwable ignored) {
            // 宿主环境 / 异常 AttributeSet：整体放弃，保留默认值
        }
    }

    private float parseDimension(String v) {
        if (v == null) return Float.NaN;
        String s = v.trim();
        if (s.length() == 0) return Float.NaN;
        char c0 = s.charAt(0);
        if (c0 == '@' || c0 == '?') return Float.NaN;   // 资源引用：宿主里解析不了
        int i = 0;
        if (c0 == '-' || c0 == '+') i = 1;
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') i++;
            else break;
        }
        if (i == start) return Float.NaN;
        float num;
        try {
            num = Float.parseFloat(s.substring(0, i));
        } catch (Throwable t) {
            return Float.NaN;
        }
        String unit = s.substring(i).trim().toLowerCase(java.util.Locale.US);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (unit.equals("px")) return num;
        if (unit.equals("dp") || unit.equals("dip") || unit.length() == 0) return num * dm.density;
        if (unit.equals("sp")) return num * dm.scaledDensity;
        if (unit.equals("pt")) return num * dm.density * 2.25f;
        if (unit.equals("in")) return num * dm.density * 160f;
        if (unit.equals("mm")) return num * dm.density * 160f / 25.4f;
        return num * dm.density;
    }

    /**
     * 解析颜色字面量：{@code #RGB} / {@code #RRGGBB} / {@code #AARRGGBB}。
     *
     * <p>刻意**不用** {@link android.graphics.Color#parseColor(String)}：本方法要能在
     * 纯 JVM 上被单元测试覆盖（见 {@code tests/flipclock-attr/FlipClockAttrTest.java}），
     * 而 android.jar 里的 Color 是抛 {@code Stub!} 的桩。自实现的三条分支与 Android
     * 的实现语义一致（3 位按位复制成 6 位）。
     *
     * @return ARGB 颜色；无法解析（含 {@code @color/xxx} 资源引用）时返回 -1
     */
    private static int parseColor(String v) {
        if (v == null) return -1;
        final String s = v.trim();
        if (s.length() == 0 || s.charAt(0) != '#') return -1;   // @color/xxx、?attr/xxx 一并挡掉
        final int n = s.length() - 1;
        try {
            if (n == 3) {
                final int r = hex(s.charAt(1));
                final int g = hex(s.charAt(2));
                final int b = hex(s.charAt(3));
                if (r < 0 || g < 0 || b < 0) return -1;
                return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
            if (n == 6) {
                return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
            }
            if (n == 8) {
                return (int) Long.parseLong(s.substring(1), 16);
            }
        } catch (Throwable t) {
            return -1;
        }
        return -1;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean parseBool(String v, boolean fallback) {
        if (v == null) return fallback;
        String s = v.trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return fallback;
    }

    private static int parseInt(String v) {
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ======================================================================
    // 配置 API
    // ======================================================================

    /** 设置数字字号（px）。卡高 = 字号 × 90/70，其余几何全部跟着重算。 */
    public void setDigitSizePx(float px) {
        if (px <= 0f || px == mDigitSizePx) return;
        mDigitSizePx = px;
        recomputeGeometry();
    }

    /** 设置数字字号（sp）。 */
    public void setDigitSizeSp(float sp) {
        setDigitSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics()));
    }

    public float getDigitSizePx() {
        return mDigitSizePx;
    }

    /** 数字颜色。 */
    public void setDigitColor(int color) {
        mDigitColor = color;
        mText.setColor(color);   // 同步到 Paint，否则改色不生效
        invalidate();
    }

    /** 数字色块颜色。 */
    public void setCardColor(int color) {
        mCardColor = color;
        invalidate();
    }

    /** 卡片底层颜色。 */
    public void setCardBackColor(int color) {
        mCardBackColor = color;
        invalidate();
    }

    /** 中缝分隔线颜色。 */
    public void setSeamColor(int color) {
        mSeamColor = color;
        invalidate();
    }

    /** 冒号颜色；传 0 表示恢复"跟随数字颜色"。 */
    public void setColonColor(int color) {
        if (color == 0) {
            mColonColorSet = false;
        } else {
            mColonColor = color;
            mColonColorSet = true;
        }
        invalidate();
    }

    /** 卡片圆角（px）；传负值表示按站点比例自动推导。 */
    public void setCornerRadiusPx(float px) {
        mCornerRadiusPx = px;
        recomputeGeometry();
    }

    /** 单阶段翻页时长（ms）；一轮完整翻页 = 该值 × 2。 */
    public void setFlipDurationMs(int ms) {
        if (ms <= 0) return;
        mFlipDurationMs = ms;
    }

    public int getFlipDurationMs() {
        return mFlipDurationMs;
    }

    /** 是否显示秒（4 位 ↔ 6 位）。 */
    public void setShowSeconds(boolean show) {
        if (mShowSeconds == show) return;
        mShowSeconds = show;
        initDigits();
        recomputeGeometry();
    }

    public boolean isShowSeconds() {
        return mShowSeconds;
    }

    /** 是否 24 小时制。 */
    public void setHour24(boolean hour24) {
        if (mHour24 == hour24) return;
        mHour24 = hour24;
        refreshTime(false);
    }

    public boolean isHour24() {
        return mHour24;
    }

    /** 数字是否加粗。 */
    public void setDigitBold(boolean bold) {
        if (mDigitBold == bold) return;
        mDigitBold = bold;
        recomputeGeometry();
    }

    /**
     * 设置字体族。
     *
     * <p>可用值即 Android 的字体族名：{@code sans-serif}（默认）、{@code sans-serif-light}、
     * {@code sans-serif-condensed}、{@code sans-serif-medium}、{@code serif}、
     * {@code monospace}、{@code cursive}。三星设备上 {@code sans-serif} 落到系统默认字体
     * （SamsungOne），与浏览器渲染站点时的回退结果一致。
     */
    public void setFontFamily(String family) {
        if (family == null || family.length() == 0 || family.equals(mFontFamily)) return;
        mFontFamily = family;
        recomputeGeometry();
    }

    public String getFontFamily() {
        return mFontFamily;
    }

    /**
     * 注入"跟随自定义字体"要用的字体（{@link #CUSTOM_FONT} 时生效）。
     *
     * <p>可随时调用（例如字体在后台线程加载完后才拿到）；传 {@code null} 表示
     * 还没有可用字体，此时回退默认字体。
     */
    public void setCustomTypeface(Typeface tf) {
        if (tf == mCustomTypeface) return;
        mCustomTypeface = tf;
        recomputeGeometry();
    }

    public Typeface getCustomTypeface() {
        return mCustomTypeface;
    }

    /** 倒计时结束回调。 */
    public void setOnCountDownFinishListener(OnCountDownFinishListener l) {
        mFinishListener = l;
    }

    // ======================================================================
    // 时间 API
    // ======================================================================

    /** 启动走时（时钟模式跟随系统时间；倒计时模式跟随目标时刻）。 */
    public void start() {
        if (mRunning) return;
        mRunning = true;
        mCountDownFired = false;
        mHandler.removeCallbacks(mTick);
        refreshTime(false);
        if (mAttached) mHandler.post(mTick);
    }

    /** 停止走时（保留当前显示，翻页动画自然收尾）。 */
    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mTick);
    }

    public boolean isRunning() {
        return mRunning;
    }

    /**
     * 设置指定时刻（时钟模式的显示值）。
     *
     * @param hour   0-23
     * @param minute 0-59
     * @param second 0-59
     */
    public void setTime(int hour, int minute, int second) {
        int h = ((hour % 24) + 24) % 24;
        int m = ((minute % 60) + 60) % 60;
        int s = ((second % 60) + 60) % 60;
        mFixedMs = -1L;
        mCountDown = false;
        applyValues(h, m, s, true);
    }

    /**
     * 直接指定一个 epoch 毫秒时刻（时钟模式按它显示；倒计时模式用它作目标时刻）。
     *
     * @param epochMs 目标时刻
     */
    public void setTimeInMillis(long epochMs) {
        if (mCountDown) {
            mCountDownEndMs = epochMs;
            mCountDownFired = false;
            refreshTime(false);
        } else {
            mFixedMs = epochMs;
            refreshTime(true);
        }
    }

    /** 回到"跟随系统时间"（清除 {@link #setTime} / {@link #setTimeInMillis} 的固定值）。 */
    public void followSystemTime() {
        mFixedMs = -1L;
        mCountDown = false;
        refreshTime(true);
    }

    /** 进入 / 退出倒计时模式。 */
    public void setCountDown(boolean countDown) {
        if (mCountDown == countDown) return;
        mCountDown = countDown;
        mCountDownFired = false;
        if (countDown) mFixedMs = -1L;
        refreshTime(false);
    }

    public boolean isCountDown() {
        return mCountDown;
    }

    /**
     * 设置倒计时剩余时长（毫秒），内部换算成目标时刻。
     *
     * @param millis 剩余毫秒；&lt;= 0 立即到点
     */
    public void setCountDownMillis(long millis) {
        mCountDown = true;
        mCountDownFired = false;
        mFixedMs = -1L;
        mCountDownEndMs = System.currentTimeMillis() + Math.max(0L, millis);
        refreshTime(false);
    }

    /** 设置倒计时目标时刻（epoch ms）。 */
    public void setCountDownTarget(long epochMs) {
        mCountDown = true;
        mCountDownFired = false;
        mFixedMs = -1L;
        mCountDownEndMs = epochMs;
        refreshTime(false);
    }

    /** 当前剩余毫秒（非倒计时返回 0）。 */
    public long getRemainingMillis() {
        if (!mCountDown) return 0L;
        long r = mCountDownEndMs - System.currentTimeMillis();
        return r > 0L ? r : 0L;
    }

    // ======================================================================
    // 几何与格位
    // ======================================================================

    private void recomputeGeometry() {
        if (mDigitSizePx <= 0f) {
            mDigitSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f,
                    getResources().getDisplayMetrics());
        }
        mCardH = mDigitSizePx * RATIO_CARD_PER_FONT;
        float u = mCardH / BASE_CARD_H;         // 1u = CSS 1px 在本组件里的像素当量
        mUnit = u;
        mCardW = mCardH * RATIO_CARD_W;
        mHalfH = mCardH / 2f;
        mGap = mCardH * RATIO_MARGIN;
        mColonW = mCardH * RATIO_COLON;
        mRadius = (mCornerRadiusPx >= 0f) ? mCornerRadiusPx : mCardH * RATIO_RADIUS;
        mSeamH = Math.max(1f, mCardH * RATIO_SEAM_H);
        mSeamTop = mCardH * RATIO_SEAM_TOP;
        mPersp = mCardH * RATIO_PERSPECTIVE;
        mDotD = mCardH * RATIO_DOT;
        mDotTopY = mCardH * RATIO_DOT_TOP;
        mDotBottomY = mCardH * RATIO_DOT_BOTTOM;
        // 数字字号：先探测**当前字体的墨迹比例**，再反推字号。
        //
        // 为什么必须探测、不能写死：不同字体的「数字墨迹高 ÷ 字号」差别极大 ——
        //   系统 Arial Bold ............. 0.719
        //   打包 ArialBold(Liberation).. 0.698
        //   打包 ArialEnUnicodeBold .... 1.4648  ← 字形天生就这么大
        // 最后这个正是本组件此前的默认字体：head.unitsPerEm=1024 且 hhea 896/-128
        // 与之自洽，但十个数字字形的 ink 外框是 y=-27..1473（1500 units = 1.4648 em）
        // —— 同一个字号下它的数字比 Arial 大 **2.06 倍**，墨迹高占到卡高的 112%
        // （溢出卡片）。真机反馈"数字太大、都占满了"就是这个原因，而不是比例没调对
        // （我先前调的是整体缩放，方向错了）。
        // 注：已用 fontTools 逐项比对过 —— 原始 17.5MB 字体与打包子集的
        // unitsPerEm/hhea/OS2/数字 ink 外框**完全相同**，子集化没破坏任何东西。
        //
        // 所以这里不假设任何字体的比例：用 100px 探测量出 inkRatio，再按
        // 「目标墨迹高 = 字号 × SITE_DIGIT_INK_RATIO」反推实际绘制字号。
        // 这样换任何字体（含用户"跟随自定义字体"导入的 ttf/otf）都得到一致观感。
        mText.setTypeface(resolveTypeface(mFontFamily));
        mText.setTextAlign(Paint.Align.CENTER);
        // ⚠️ Paint 不会自己跟着字段变：这里必须显式把颜色刷进 Paint，
        // 否则 mText 一直是 Paint 的默认黑色，数字会"看不见"。
        mText.setColor(mDigitColor);
        mText.setTextSize(INK_PROBE_PX);
        mText.getTextBounds(ALL_DIGITS, 0, ALL_DIGITS.length(), mGlyphBounds);
        final float inkRatio = Math.max(0.01f, mGlyphBounds.height() / INK_PROBE_PX);
        final float digitDrawPx = mDigitSizePx * (SITE_DIGIT_INK_RATIO / inkRatio)
                * DIGIT_DRAW_SCALE;
        mText.setTextSize(digitDrawPx);
        // 站点 text-shadow:0 1px 2px #000 —— 让数字浮在色块上。
        //
        // ⚠️ 阴影量必须按**站点字号当量 mDigitSizePx** 取，不能用校准后的 digitDrawPx。
        //
        // 站点那 2px / 1px 是相对 **font-size** 的，而 mDigitSizePx 才是与站点 font-size
        // 对等的那个量（卡高 = mDigitSizePx × 90/70，站点卡高 = font-size × 90/70）。
        // 若改用 digitDrawPx，阴影会随字体的 ink 比例反着走：
        //   ArialEnUnicode 的 ink 是 1.4648（Arial 的 2 倍）→ digitDrawPx 被压到 0.49 倍
        //   → 阴影也只有站点的一半，数字看着"没有阴影"；
        //   换字体（尤其"跟随自定义字体"）时阴影还会跟着字体变，时有时无。
        // 用 mDigitSizePx 则阴影量恒定，与所选字体无关 —— 换任何字体都保持站点的阴影。
        mText.setShadowLayer(Math.max(1f, mDigitSizePx * (2f / 70f)),
                0f, mDigitSizePx * (1f / 70f), 0xFF000000);
        // 字形外框中心（相对 baseline）—— 居中的唯一依据，字号/字体变了要重新量
        mText.getTextBounds(ALL_DIGITS, 0, ALL_DIGITS.length(), mGlyphBounds);
        mGlyphCenterOffset = (mGlyphBounds.top + mGlyphBounds.bottom) / 2f;
        mSeam.setStyle(Paint.Style.FILL);
        // .shadow 渐变：up 是"上浅下深"，down 是"上深下浅"（rgba(0,0,0,.1) ↔ black）
        mShadowUp = new LinearGradient(0f, 0f, 0f, mHalfH, SHADOW_LIGHT, 0xFF000000,
                Shader.TileMode.CLAMP);
        mShadowDown = new LinearGradient(0f, 0f, 0f, mHalfH, 0xFF000000, SHADOW_LIGHT,
                Shader.TileMode.CLAMP);
        requestLayout();
        invalidate();
    }

    /** 按当前"是否显示秒"重建格位表与数字位。 */
    private void initDigits() {
        mDigitCount = mShowSeconds ? 6 : 4;
        mCellCount = mShowSeconds ? 8 : 5;      // HH:MM 或 HH:MM:SS
        int idx = 0;
        mCellIsColon[idx++] = false;            // H
        mCellIsColon[idx++] = false;            // H
        mCellIsColon[idx++] = true;             // :
        mCellIsColon[idx++] = false;            // M
        mCellIsColon[idx++] = false;            // M
        if (mShowSeconds) {
            mCellIsColon[idx++] = true;         // :
            mCellIsColon[idx++] = false;        // S
            mCellIsColon[idx++] = false;        // S
        }
        for (int i = 0; i < mDigits.length; i++) {
            if (mDigits[i] == null) mDigits[i] = new Digit();
            mDigits[i].valid = false;
            mDigits[i].animStart = -1L;
        }
        refreshTime(false);
    }

    /** 内容自然宽度（含两侧外边距）。 */
    private float contentWidth() {
        return mDigitCount * (mCardW + mGap * 2f) + (mCellCount - mDigitCount) * mColonW;
    }

    // ======================================================================
    // View 生命周期
    // ======================================================================

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        mChoreographer = Choreographer.getInstance();
        if (mRunning) {
            mHandler.removeCallbacks(mTick);
            mHandler.post(mTick);
        }
    }

    @Override protected void onDetachedFromWindow() {
        mAttached = false;
        mHandler.removeCallbacks(mTick);
        mHandler.removeCallbacksAndMessages(null);
        cancelFrame();
        // 不停止 mRunning：重新 attach（主页重建）后 onAttachedToWindow 会自动续跑，
        // 这正是"从网页返回主页时钟继续走"需要的行为。
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            cancelFrame();
        } else if (hasRunningFlip()) {
            postFrame();
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 两侧各留出卡片外投影的空间（见 SHADOW_BLUR_U / SHADOW_DY_U），否则阴影会被 View 边界裁掉
        final float margin = shadowMargin();
        int wantW = Math.max(1, Math.round(contentWidth() + margin * 2f));
        int wantH = Math.max(1, Math.round(mCardH + margin * 2f));
        setMeasuredDimension(resolveSize(wantW, widthMeasureSpec),
                resolveSize(wantH, heightMeasureSpec));
    }

    /** 卡片外投影需要的留白：模糊半径 + 下偏移（两者都以 u 为单位，见 SHADOW_* 常量）。 */
    private float shadowMargin() {
        return (SHADOW_BLUR_U + SHADOW_DY_U) * mUnit;
    }

    // ======================================================================
    // 时间推进
    // ======================================================================

    /**
     * 按当前模式刷新显示值。
     *
     * @param animate 数字变化时是否播翻页动画（首帧 / 重建时传 false）
     */
    private void refreshTime(boolean animate) {
        long nowMs = System.currentTimeMillis();
        int h;
        int m;
        int s;
        if (mCountDown) {
            long remain = mCountDownEndMs - nowMs;
            if (remain < 0L) remain = 0L;
            long totalSec = remain / 1000L;
            long hh = totalSec / 3600L;
            if (hh > 99L) hh = 99L;
            h = (int) hh;
            m = (int) ((totalSec % 3600L) / 60L);
            s = (int) (totalSec % 60L);
            if (remain <= 0L && !mCountDownFired) {
                mCountDownFired = true;
                OnCountDownFinishListener l = mFinishListener;
                if (l != null) {
                    try { l.onCountDownFinish(); } catch (Throwable ignored) {}
                }
            }
        } else {
            long base = (mFixedMs >= 0L) ? mFixedMs : nowMs;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(base);
            h = cal.get(Calendar.HOUR_OF_DAY);
            m = cal.get(Calendar.MINUTE);
            s = cal.get(Calendar.SECOND);
        }
        applyValues(h, m, s, animate);
    }

    private void applyValues(int h, int m, int s, boolean animate) {
        if (!mHour24 && !mCountDown) {
            h = h % 12;
            if (h == 0) h = 12;
        }
        final int[] vals = mShowSeconds
                ? new int[]{ h / 10, h % 10, m / 10, m % 10, s / 10, s % 10 }
                : new int[]{ h / 10, h % 10, m / 10, m % 10 };
        final long nowUp = SystemClock.uptimeMillis();
        for (int i = 0; i < vals.length && i < mDigits.length; i++) {
            Digit d = mDigits[i];
            if (d == null) continue;
            if (!d.valid) {
                d.cur = vals[i];
                d.prev = vals[i];
                d.valid = true;
                d.animStart = -1L;
            } else if (d.cur != vals[i]) {
                d.prev = d.cur;
                d.cur = vals[i];
                d.animStart = animate ? nowUp : -1L;
            }
        }
        if (hasRunningFlip()) postFrame();
        else invalidate();
    }

    private boolean hasRunningFlip() {
        long now = SystemClock.uptimeMillis();
        long span = (long) mFlipDurationMs * 2L;
        for (int i = 0; i < mDigits.length; i++) {
            Digit d = mDigits[i];
            if (d != null && d.animStart >= 0L && (now - d.animStart) < span) return true;
        }
        return false;
    }

    private void postFrame() {
        if (mFramePosted || !mAttached) return;
        if (mChoreographer == null) mChoreographer = Choreographer.getInstance();
        if (mChoreographer == null) return;
        mFramePosted = true;
        mChoreographer.postFrameCallback(mFrameCallback);
    }

    private void cancelFrame() {
        if (mChoreographer != null && mFramePosted) {
            mChoreographer.removeFrameCallback(mFrameCallback);
        }
        mFramePosted = false;
    }

    /**
     * 该位当前的翻页进度（0..1）；返回负值表示静止。
     *
     * <p>时间轴（与 CSS 完全对齐，以单阶段时长 D 为单位，整轮 2D）：
     * <pre>
     *   0 .. D      before.up   rotateX(0 → -90deg)     + shadow show(0→1)
     *   0 .. D      before.down 静止                     + shadow show(0→1)
     *   0 .. D      active.up   静止                     + shadow hide 延迟 0.3D
     *   0 .. D      active.down 静止(90°)                + shadow hide 延迟 0.2D
     *   D .. 2D     active.down rotateX(90 → 0deg)
     *   0.6×2D 处   新旧层交接（asd：0.5s 延迟 + 20%）
     * </pre>
     */
    private float flipProgress(Digit d) {
        if (d == null || d.animStart < 0L) return -1f;
        long span = (long) mFlipDurationMs * 2L;
        long elapsed = SystemClock.uptimeMillis() - d.animStart;
        if (elapsed >= span) {
            d.animStart = -1L;
            return -1f;
        }
        if (elapsed < 0L) return 0f;
        return elapsed / (float) span;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mDigitSizePx <= 0f || mCellCount == 0) return;
        final float margin = shadowMargin();
        final float top = Math.max(margin, (getHeight() - mCardH) / 2f);
        float x = margin;
        int di = 0;
        for (int i = 0; i < mCellCount; i++) {
            if (mCellIsColon[i]) {
                drawColon(canvas, x, top);
                x += mColonW;
            } else {
                Digit d = (di < mDigits.length) ? mDigits[di] : null;
                di++;
                final float left = x + mGap;
                if (d != null) {
                    float t = flipProgress(d);
                    if (t < 0f) drawStaticCard(canvas, d.cur, left, top);
                    else drawFlipCard(canvas, d, left, top, t);
                }
                x += mGap + mCardW + mGap;
            }
        }
    }

    /** 静止状态：一整张卡（底层 + 上半 + 下半 + 中缝）。 */
    private void drawStaticCard(Canvas canvas, int value, float left, float top) {
        drawCardShadow(canvas, left, top);
        canvas.save();
        canvas.translate(left, top);
        for (int i = 0; i < 8; i++) mRadii[i] = mRadius;
        mRect.set(0f, 0f, mCardW, mCardH);
        mPath.reset();
        mPath.addRoundRect(mRect, mRadii, Path.Direction.CW);
        mFill.setShader(null);
        mFill.setColor(mCardBackColor);
        canvas.drawPath(mPath, mFill);
        canvas.restore();
        drawHalf(canvas, left, top, true, value, 0f, 0f);
        drawHalf(canvas, left, top, false, value, 0f, 0f);
        drawSeam(canvas, left, top);
    }

    /**
     * 翻转中：两层完整数字叠加（站点 DOM 是 {@code li.before} + {@code li.active}，
     * 每层各自带上下两块与暗化遮罩），按 asd 交接时刻决定谁在上。
     */
    private void drawFlipCard(Canvas canvas, Digit d, float left, float top, float t) {
        drawCardShadow(canvas, left, top);
        final int prev = d.prev;
        final int cur = d.cur;
        final boolean beforeOnTop = t < HANDOVER_T;
        if (beforeOnTop) {
            drawFlipLayer(canvas, left, top, cur, t, false);   // 新值层（在下）
            drawFlipLayer(canvas, left, top, prev, t, true);   // 旧值层（在上）
        } else {
            drawFlipLayer(canvas, left, top, prev, t, true);   // 旧值层（在下）
            drawFlipLayer(canvas, left, top, cur, t, false);   // 新值层（在上）
        }
        drawSeam(canvas, left, top);
    }

    /**
     * 画一层（旧值层 / 新值层）。
     *
     * @param isBefore true = 旧值层（上半翻走、下半静止）；false = 新值层（上半静止、下半落回）
     */
    private void drawFlipLayer(Canvas canvas, float left, float top, int value, float t,
                               boolean isBefore) {
        final float upDeg;
        final float downDeg;
        final float upShadow;
        final float downShadow;
        if (isBefore) {
            // turn2: rotateX(0 → -90deg)，0 → D
            upDeg = -90f * clamp01(t / PHASE_T);
            downDeg = 0f;
            // show: opacity 0 → 1，无延迟（0 → D）
            upShadow = clamp01(t / SHADOW_SPAN);
            downShadow = clamp01(t / SHADOW_SPAN);
        } else {
            upDeg = 0f;
            // turn: rotateX(90 → 0deg)，D → 2D
            downDeg = 90f - 90f * clamp01((t - PHASE_T) / PHASE_T);
            // hide: opacity 1 → 0，延迟 0.3D（上半）/ 0.2D（下半）
            upShadow = 1f - clamp01((t - HIDE_DELAY_UP) / SHADOW_SPAN);
            downShadow = 1f - clamp01((t - HIDE_DELAY_DOWN) / SHADOW_SPAN);
        }
        // backface-visibility:hidden —— 转到侧面及背面时不画
        if (Math.cos(Math.toRadians(upDeg)) > 0.0001f) {
            drawHalf(canvas, left, top, true, value, upDeg, upShadow);
        }
        if (Math.cos(Math.toRadians(downDeg)) > 0.0001f) {
            drawHalf(canvas, left, top, false, value, downDeg, downShadow);
        }
    }

    /**
     * 按 <b>CSS 约定</b>构造"绕水平轴旋转 + perspective 投影"的矩阵。
     *
     * <p>为什么不用 {@link android.graphics.Camera}：Camera 走的是 Sk3DView，
     * 它的相机位置单位是**英寸**（内部 ×72 转 pt）、z 轴正方向与 CSS 相反，
     * 两点都会让"透视强度"和"翻转方向"变成靠猜的近似值。这里直接把 CSS 的
     * 投影公式写进 SkMatrix 的透视分量，方向与强度都精确可控。
     *
     * <p>CSS 的数学（z 轴朝向观察者为正，perspective 距离 d）：
     * <pre>
     *   点 (x, y, 0) --rotateX(θ)--> (x, y·cosθ, y·sinθ)
     *   投影到屏幕:  X = x / w,   Y = y·cosθ / w,   其中 w = 1 - y·sinθ / d
     * </pre>
     * SkMatrix 的齐次形式（{@code m6/m7/m8} 即透视分量，绘制时做除法）：
     * <pre>
     *   [ 1,    0,      0 ]
     *   [ 0,   cosθ,    0 ]
     *   [ 0, -sinθ/d,   1 ]
     * </pre>
     * 再按 {@code transform-origin} 把旋转中心搬到 pivot：{@code M = T(pivot)·R·T(-pivot)}。
     *
     * <p>这与站点 CSS 的 {@code perspective:200px} + {@code transform-origin} 完全等价，
     * 因此 {@code deg} 直接传 CSS 里的角度值（旧值上半 {@code 0 → -90deg}、
     * 新值下半 {@code 90 → 0deg}），不需要任何符号换算。
     *
     * @param degCss CSS 语义的 rotateX 角度（度）
     * @param pivotX 旋转中心 X（画布坐标）
     * @param pivotY 旋转中心 Y（画布坐标）
     */
    private void buildCssRotateX(float degCss, float pivotX, float pivotY) {
        final double rad = Math.toRadians(degCss);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        final float d = (mPersp > 1f) ? mPersp : 1f;
        mMatrixValues[0] = 1f;
        mMatrixValues[1] = 0f;
        mMatrixValues[2] = 0f;
        mMatrixValues[3] = 0f;
        mMatrixValues[4] = cos;
        mMatrixValues[5] = 0f;
        mMatrixValues[6] = 0f;
        mMatrixValues[7] = -sin / d;
        mMatrixValues[8] = 1f;
        mMatrix.setValues(mMatrixValues);
        mMatrix.preTranslate(-pivotX, -pivotY);
        mMatrix.postTranslate(pivotX, pivotY);
    }

    /**
     * 画半块（上 / 下）。
     *
     * <p>数字按"整卡垂直居中"定位，再由半块矩形裁出该露的一半 —— 这对应 CSS 里
     * {@code .inn{height:200%}} 加上 {@code .up>.inn{top:0}} / {@code .down>.inn{bottom:0}}：
     * 同一个数字在上下两块里必然对齐，跨中缝连续。
     *
     * @param deg         绕中缝边的旋转角（0 = 不旋转）
     * @param shadowAlpha 暗化遮罩透明度（0 = 不画）
     */
    private void drawHalf(Canvas canvas, float left, float top, boolean upper, int value,
                          float deg, float shadowAlpha) {
        final float x = left;
        final float y = upper ? top : (top + mHalfH);
        final float w = mCardW;
        final float h = mHalfH;
        canvas.save();
        if (deg != 0f) {
            // transform-origin：上半绕底边中点(50% 100%)，下半绕顶边中点(50% 0)
            final float pivotX = x + w / 2f;
            final float pivotY = upper ? (y + h) : y;
            buildCssRotateX(deg, pivotX, pivotY);
            canvas.concat(mMatrix);
        }
        canvas.translate(x, y);
        // 圆角：上半圆上面两角，下半圆下面两角，贴中缝的两角是直角
        final float r = mRadius;
        mRadii[0] = upper ? r : 0f;
        mRadii[1] = upper ? r : 0f;
        mRadii[2] = upper ? r : 0f;
        mRadii[3] = upper ? r : 0f;
        mRadii[4] = upper ? 0f : r;
        mRadii[5] = upper ? 0f : r;
        mRadii[6] = upper ? 0f : r;
        mRadii[7] = upper ? 0f : r;
        mRect.set(0f, 0f, w, h);
        mPath.reset();
        mPath.addRoundRect(mRect, mRadii, Path.Direction.CW);
        canvas.clipPath(mPath);
        // 色块（.inn background-color:#333）
        mFill.setShader(null);
        mFill.setColor(mCardColor);
        canvas.drawRect(mRect, mFill);
        // 数字（.inn color:#ccc + text-shadow）
        //
        // 数字的中心必须落在**整卡中心**（也就是中缝）上，而不是半块中心：
        // CSS 里 .inn 高 200%（= 整卡高）且 .up>.inn{top:0} / .down>.inn{bottom:0}，
        // 同一串数字在上下两块里共用一套坐标，只有把中心放在中缝处，两块裁出来的
        // 半个数字才能拼成一个完整的数字。
        //   上半块：局部 (0,0) = 卡顶，整卡占 y∈[0, 2h] → 中心 y = h
        //   下半块：局部 (0,0) = 中缝，整卡占 y∈[-h, h] → 中心 y = 0
        // 垂直定位以**字形外框中心**为准（mGlyphCenterOffset），不用 FontMetrics：
        // 后者描述字体的整体预留空间（重音上行区、g/j 下行区），纯数字不占那些空间，
        // 真机实测（204px 卡高）按它居中会偏下 8px。
        // 数字严格居中于整卡（中心 = 中缝）。
        // 站点 CSS 因 ul{line-height:87px} 会让数字比卡中心高 1.5u，但真机反馈"没有上下
        // 居中"，故以**视觉居中**为准，不再照搬那 1.5u 的偏移。
        final float centerY = upper ? h : 0f;
        final float baseline = centerY - mGlyphCenterOffset;
        canvas.drawText(DIGITS[((value % 10) + 10) % 10], w / 2f, baseline, mText);
        // 暗化遮罩（div.shadow）
        if (shadowAlpha > 0.004f) {
            mShadow.setShader(upper ? mShadowUp : mShadowDown);
            mShadow.setAlpha((int) (255f * clamp01(shadowAlpha)));
            canvas.drawRect(mRect, mShadow);
        }
        canvas.restore();
    }

    /**
     * 解析字体：{@code asset:xxx.ttf} 走**打包字体**，其它按 Android 字体族名处理。
     *
     * <p>为什么要打包字体：站点用的字体栈是 {@code "Helvetica Neue",Helvetica,sans-serif}
     * 且**没有任何 webfont**，所以在电脑上落到 Arial/Helvetica（方正、有棱角），
     * 而手机系统的 {@code sans-serif} 是圆润的 SamsungOne —— 同一份 CSS 在两端观感完全不同。
     * 因此把一份 Arial 血统的字体（{@link #DEFAULT_FONT}，已用 fontTools 子集化到只含
     * 0-9 与冒号）打进 APK，"固定一份"、跨设备一致。
     *
     * <p>2026-10-13：assets 里原本还有 6 款候选字体（ArialBold / DroidSans / OpenSans /
     * Barlow / Oswald / Archivo）。设置页的字体下拉收窄为「默认 / 跟随自定义字体」两项后
     * 它们已无任何入口，故全部移除 —— 只留 {@link #DEFAULT_FONT}。
     * 兜底一律走**系统字体**（见 {@code resolveTypeface}），不再依赖打包资源。
     *
     * <p>⚠️ 本组件运行在**宿主进程**（三星浏览器）里，{@code getContext()} 是宿主 Context，
     * 它的 {@code getAssets()} 读不到模块的 assets —— 必须先用 {@code createPackageContext}
     * 拿到模块自己的包 Context。任一步失败都回退到系统字体，绝不抛异常。
     */
    private Typeface resolveTypeface(String family) {
        if (family == null || family.length() == 0) family = DEFAULT_FONT;
        // "跟随自定义字体"：用宿主注入的 Typeface（FontHelper 里用户选的字体文件）。
        // 还没加载完 / 用户没导入字体时回退默认字体，绝不让时钟空着。
        if (CUSTOM_FONT.equals(family)) {
            if (mCustomTypeface != null) return mCustomTypeface;
            return resolveTypeface(DEFAULT_FONT);
        }
        if (!family.startsWith("asset:")) {
            final int style = mDigitBold ? Typeface.BOLD : Typeface.NORMAL;
            final Typeface tf = Typeface.create(family, style);
            // 系统里没有这个字体族时 Typeface.create 会**静默返回默认字体** —— 检测出来并回退。
            //
            // 2026-10-13：兜底由「打包的 ArialBold」改为**系统字体**。
            // 理由：兜底这一层存在的意义就是"不依赖任何可能缺失的外部资源"，而打包字体
            // 同样可能缺失（assets 被裁、createPackageContext 失败、APK 拆分），用它兜底
            // 只是把风险换了个位置。系统字体是宿主环境必然具备的。
            // 也正因如此，assets 里的 ArialBold.ttf 已随本次清理一并移除。
            if (tf == null || tf.equals(Typeface.create("sans-serif", style))) {
                return Typeface.create("sans-serif-condensed", style);
            }
            return tf;
        }
        if (family.equals(mCachedFontKey) && mCachedTypeface != null) return mCachedTypeface;
        Typeface tf = null;
        try {
            android.content.Context modCtx = getContext().createPackageContext(
                    "com.sbplus.browser", android.content.Context.CONTEXT_IGNORE_SECURITY);
            tf = Typeface.createFromAsset(modCtx.getAssets(),
                    "fonts/" + family.substring("asset:".length()));
        } catch (Throwable t) {
            tf = null;
        }
        if (tf == null) {
            // 拿不到模块 assets（宿主环境限制）：回退系统字体，不让时钟消失
            tf = Typeface.create("sans-serif-condensed",
                    mDigitBold ? Typeface.BOLD : Typeface.NORMAL);
        }
        mCachedFontKey = family;
        mCachedTypeface = tf;
        return tf;
    }

    /**
     * 卡片外投影 —— 站点 {@code .flip { box-shadow:0 2px 5px rgba(0,0,0,.7) }}。
     *
     * <p>硬件加速下 {@code Paint.setShadowLayer} 对**非文本**绘制会被直接忽略（Android
     * 的已知限制），所以这里用「多层逐渐扩大、逐渐变淡的圆角矩形」近似高斯模糊：
     * 由外到内透明度递增，叠出来就是一圈柔和暗边 —— 卡片因此"浮"在壁纸上。
     *
     * <p>所需留白由 {@link #shadowMargin()} 提供，{@code onMeasure} 已把它算进 View 尺寸，
     * 否则阴影会被 View 边界裁掉。
     */
    private void drawCardShadow(Canvas canvas, float left, float top) {
        final float dy = SHADOW_DY_U * mUnit;
        final float blur = SHADOW_BLUR_U * mUnit;
        final int layers = Math.max(3, Math.round(blur));
        for (int i = layers; i >= 1; i--) {
            final float t = i / (float) layers;              // 1 = 最外圈
            final float expand = blur * t;
            final int alpha = (int) (255f * 0.7f * (1f - t) * SHADOW_STRENGTH);
            if (alpha <= 0) continue;
            mCardShadow.setColor(alpha << 24);               // 纯黑 + alpha
            mShadowRect.set(left - expand, top + dy - expand,
                    left + mCardW + expand, top + mCardH + dy + expand);
            canvas.drawRoundRect(mShadowRect, mRadius + expand, mRadius + expand, mCardShadow);
        }
    }

    /**
     * 中缝（{@code div.up:after}）：{@code top:44px; height:3px; background:rgba(0,0,0,.4)}。
     *
     * <p>注意 CSS 里它挂在 {@code .up} 上，而 {@code .up} 高 50%（45u）且
     * {@code overflow:hidden} —— 3u 高的伪元素只有 44→45u 那一段露得出来，
     * 所以站点上看到的中缝其实是 <b>1u</b> 细线。这里按同样的裁剪语义画。
     */
    private void drawSeam(Canvas canvas, float left, float top) {
        final float y0 = top + mSeamTop;
        final float y1 = Math.min(top + mSeamTop + mSeamH, top + mHalfH);
        if (y1 <= y0) return;
        mSeam.setColor(mSeamColor);
        canvas.drawRect(left, y0, left + mCardW, y1, mSeam);
    }

    /** 冒号：两个圆点（.flip-clock-dot.top top:30px / .bottom bottom:30px）。 */
    private void drawColon(Canvas canvas, float left, float top) {
        mDot.setColor(mColonColorSet ? mColonColor : mDigitColor);
        final float cx = left + mColonW / 2f;
        final float r = mDotD / 2f;
        canvas.drawCircle(cx, top + mDotTopY + r, r, mDot);
        canvas.drawCircle(cx, top + mDotBottomY + r, r, mDot);
    }
}
