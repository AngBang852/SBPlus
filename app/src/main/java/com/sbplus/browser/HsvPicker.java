package com.sbplus.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;


/**
 * HsvPicker — 简单 HSV 色盘: 上方 SV(饱和-明度) 面, 下方色相条.
 * 拖动改变颜色, 同步回传 hex 输入框与预览块.
 */
public class HsvPicker extends View {
    private float hue = 220f;          // 0-360
    private float sat = 0.6f;          // 0-1
    private float val = 0.7f;          // 0-1
    private boolean dragging = false;

    private final Paint paint = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private EditText hexEt;
    private View preview;
    private boolean selfUpdate = false;

    private int svH = 200;   // SV 面高度(px), 更大更好操作
    private int hueH = 44;   // 色相条高度(px)
    private int pad = 12;

    // ---- 渐变缓存(2026-09-17 新增) ----
    // onDraw 在拖动时逐帧调用,而原实现每次都要 new 三个 LinearGradient:
    //   - SV 面的饱和度渐变(依赖 hue / val)
    //   - SV 面的明度渐变(依赖 hue)
    //   - 色相条渐变(纯常量)
    // LinearGradient 的构造含数组成员与内部分段表初始化,不是零成本;
    // 逐帧新建会在拖动过程中持续产生垃圾。这里按"影响渐变的输入"做失效缓存。
    private LinearGradient satGradCache;
    private LinearGradient valGradCache;
    private LinearGradient hueGradCache;
    private float satGradHue = Float.NaN, satGradVal = Float.NaN;
    private float valGradHue = Float.NaN;
    private int gradLeft = -1, gradRight = -1, gradTop = -1, gradBottom = -1;

    /** 尺寸变化即让全部渐变失效(旋转/分屏会导致宽度改变)。 */
    private void invalidateGradients() {
        satGradCache = null;
        valGradCache = null;
        hueGradCache = null;
        gradLeft = gradRight = gradTop = gradBottom = -1;
    }

    public HsvPicker(Context c) { super(c); init(); }

    private void init() {
        paint.setAntiAlias(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setColor(0xFF000000);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) invalidateGradients();
    }

    public void attach(EditText hexEt, View preview) {
        this.hexEt = hexEt;
        this.preview = preview;
        if (hexEt != null) {
            hexEt.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (selfUpdate) return;
                    int col = ThemeColorHelper.parseHex(s.toString());
                    if (col != -1) {
                        selfUpdate = true;
                        try { setColor(col); } finally { selfUpdate = false; }
                        if (preview != null) preview.setBackgroundColor(col);
                        invalidate();
                    }
                }
            });
        }
    }

    public void setColor(int argb) {
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        invalidate();
    }

    public int getColor() {
        return Color.HSVToColor(new float[]{hue, sat, val});
    }

    @Override
    protected void onMeasure(int wspec, int hspec) {
        int w = MeasureSpec.getSize(wspec);
        if (w == 0) w = 400;
        setMeasuredDimension(w, svH + hueH + pad * 4 + dp(20));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        // SV 面
        int left = pad, top = pad, right = w - pad, bottom = pad + svH;
        // 几何变化 -> 全部渐变重建(含宽度改变导致的坐标变化)。
        // 注意:判等要在更新 grad* 之前做,否则同一调用内先赋值再比较,永远相等。
        if (left != gradLeft || right != gradRight || top != gradTop || bottom != gradBottom) {
            satGradCache = null;
            valGradCache = null;
            hueGradCache = null;
            gradLeft = left; gradRight = right; gradTop = top; gradBottom = bottom;
        }

        // 水平:饱和(0..sat), 垂直:明度(1..0)
        if (satGradCache == null || hue != satGradHue || val != satGradVal) {
            satGradCache = new LinearGradient(left, 0, right, 0,
                    Color.HSVToColor(new float[]{hue, 0f, val}),
                    Color.HSVToColor(new float[]{hue, 1f, val}), Shader.TileMode.CLAMP);
            satGradHue = hue;
            satGradVal = val;
        }
        paint.setShader(satGradCache);
        canvas.drawRect(left, top, right, bottom, paint);

        if (valGradCache == null || hue != valGradHue) {
            valGradCache = new LinearGradient(0, top, 0, bottom,
                    Color.HSVToColor(new float[]{hue, 1f, 1f}),
                    Color.HSVToColor(new float[]{hue, 1f, 0f}), Shader.TileMode.CLAMP);
            valGradHue = hue;
        }
        paint.setShader(valGradCache);
        canvas.drawRect(left, top, right, bottom, paint);
        paint.setShader(null);
        // 选中点
        float dotX = left + sat * (right - left);
        float dotY = top + (1f - val) * (bottom - top);
        stroke.setColor(0xFFFFFFFF);
        canvas.drawCircle(dotX, dotY, dp(9), stroke);
        stroke.setColor(0xFF000000);
        canvas.drawCircle(dotX, dotY, dp(12), stroke);

        // 色相条
        int hueTop = bottom + pad;
        int hueBottom = hueTop + hueH;
        // 这个渐变是纯常量(颜色固定、只依赖左右边界与纵向位置);几何变化时
        // 上面已把 hueGradCache 置 null,故这里只需判 null 即可重建。
        if (hueGradCache == null) {
            hueGradCache = new LinearGradient(left, 0, right, 0,
                    new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000},
                    null, Shader.TileMode.CLAMP);
        }
        paint.setShader(hueGradCache);
        canvas.drawRect(left, hueTop, right, hueBottom, paint);
        paint.setShader(null);
        // 色相游标
        float hueX = left + (hue / 360f) * (right - left);
        stroke.setColor(0xFFFFFFFF);
        canvas.drawCircle(hueX, (hueTop + hueBottom) / 2f, dp(11), stroke);
        stroke.setColor(0xFF000000);
        canvas.drawCircle(hueX, (hueTop + hueBottom) / 2f, dp(14), stroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // getX/getY 是相对本 View 的坐标;超出左右边界的水平拖动仍需响应
        // (用户手指划出控件边缘是常见操作),故只按纵向区域判定。
        float x = ev.getX(), y = ev.getY();
        int w = getWidth();
        // 命中判定加少量容差,避免边缘像素点不中。
        final float slop = dp(6);
        boolean inSv = (y >= pad - slop && y <= pad + svH + slop);
        boolean inHue = (y >= pad + svH + pad - slop && y <= pad + svH + pad + hueH + slop);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 只有落在色盘/色相条上才接管手势,否则交给父控件(否则会吞掉
                // 设置页的滚动)。
                dragging = inSv || inHue;
                if (!dragging) return false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                break;
            case MotionEvent.ACTION_CANCEL:
                // 手势被父控件(如 ScrollView)抢走时必须复位,否则 dragging
                // 会一直为 true,控件继续跟着手指跑且不再收到 UP。
                dragging = false;
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                return true;
            default:
                return dragging;
        }

        if (dragging) {
            if (inSv) {
                sat = Math.max(0f, Math.min(1f, (x - pad) / (w - pad * 2f)));
                val = Math.max(0f, Math.min(1f, 1f - (y - pad) / svH));
            } else if (inHue) {
                hue = Math.max(0f, Math.min(359f, (x - pad) / (w - pad * 2f) * 360f));
            }
            refresh();
        }
        return dragging;
    }

    private void refresh() {
        int col = getColor();
        if (preview != null) preview.setBackgroundColor(col);
        if (hexEt != null) {
            selfUpdate = true;
            try { hexEt.setText("#" + ThemeColorHelper.hex6(col)); } finally { selfUpdate = false; }
        }
        invalidate();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
