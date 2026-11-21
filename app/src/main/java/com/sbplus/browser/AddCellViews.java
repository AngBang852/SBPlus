package com.sbplus.browser;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「添加」格的视图构造（2026-11-21）。
 *
 * <p>做成独立类而不是内联在 {@link AddCellAdapter} 里，是因为
 * {@link AddCellAdapter} 只需要一个 {@code View}，视图长什么样与 adapter 逻辑无关。
 *
 * <p>样式刻意模仿宿主自己的图标格：圆形半透明底 + 主题色「+」+ 下方文字，
 * 这样混在网格里看不出是外来的。
 */
final class AddCellViews {

    private AddCellViews() {}

    /**
     * 造一个「添加」格。
     *
     * @param ctx        上下文（宿主 Activity）
     * @param onAddClick 点击回调
     */
    static View create(Context ctx, final Runnable onAddClick) {
        float density = ctx.getResources().getDisplayMetrics().density;

        // 外层竖排容器：上面圆形图标，下面文字（与宿主图标格同构）
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        root.setPadding(0, (int) (4f * density), 0, 0);
        root.setClickable(true);
        root.setFocusable(true);

        // 圆形底 + 「+」
        TextView icon = new TextView(ctx);
        icon.setText("+");
        icon.setTextSize(24f);
        icon.setTextColor(0xFF4BA6EE);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x22FFFFFF);
        bg.setStroke((int) (1.2f * density), 0x554BA6EE);
        icon.setBackground(bg);
        int iconSize = (int) (36f * density);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(icon, ilp);

        // 文字「添加」
        TextView label = new TextView(ctx);
        label.setText("添加");
        label.setTextSize(11f);
        label.setTextColor(0xFFCCCCCC);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = (int) (2f * density);
        root.addView(label, llp);

        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onAddClick != null) {
                    try {
                        onAddClick.run();
                    } catch (Throwable ignored) {}
                }
            }
        });
        return root;
    }
}
