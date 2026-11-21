package com.sbplus.browser.patcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 「将注入的功能」子页。
 *
 * <p><b>只读</b>：补丁版是把整个运行时注入，功能集合固定，没有「勾选 / 不勾选」。
 * 所以这里只做展示 —— 按分组列出全部功能、名字与一句话说明。
 *
 * <p>为什么不用勾选框：勾选框会让用户以为「关掉某个功能 = 少注入一段代码」，
 * 实际并不会（注入的是同一份运行时，功能开关在浏览器设置里）。
 * 给出不可点的清单，比给一堆点了没用的勾选框诚实。
 */
public class PatchFeatureActivity extends Activity {

    private static final int C_BG = 0xFF121417;
    private static final int C_CARD = 0xFF1C1F24;
    private static final int C_TEXT = 0xFFE8EAED;
    private static final int C_TEXT2 = 0xFF9AA0A6;
    private static final int C_TEXT3 = 0xFF6B7076;
    private static final int C_BRAND = 0xFF4BA6EE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("将注入的功能", 24, C_TEXT, true));
        TextView sub = text("共 " + FeatureCatalog.count()
                + " 项，全部一次性注入，不可单独关闭", 12, C_TEXT2, false);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        for (String group : FeatureCatalog.groupNames()) {
            List<FeatureCatalog.Feature> items = FeatureCatalog.byGroup(group);

            LinearLayout card = card();
            TextView title = text(group, 12, C_BRAND, true);
            title.setLetterSpacing(0.06f);
            card.addView(title);

            for (FeatureCatalog.Feature f : items) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(12);

                TextView name = text(f.name, 14, C_TEXT, false);
                row.addView(name);

                TextView desc = text(f.description, 11, C_TEXT3, false);
                desc.setLineSpacing(dp(2), 1f);
                desc.setPadding(0, dp(3), 0, 0);
                row.addView(desc);

                card.addView(row, lp);
            }
            root.addView(card);
        }

        // 与 LSPosed 版的差异说明
        LinearLayout diffCard = card();
        diffCard.addView(text("与 LSPosed 版的差别", 12, C_BRAND, true));
        TextView diff = text(
                "功能完全一致（同一份代码）。区别只在框架能力：\n"
                        + "· 补丁版没有「模块作用域」—— 它只作用于这一个 APK\n"
                        + "· 补丁版没有「热重载」—— 换功能要重新打补丁\n"
                        + "· 补丁版的设置存在浏览器自己的目录里，不是模块的远程 prefs\n"
                        + "功能开关在浏览器的「设置 → SBPlus 设置」里，与 LSPosed 版一样。",
                12, C_TEXT2, false);
        diff.setLineSpacing(dp(3), 1f);
        diff.setPadding(0, dp(8), 0, 0);
        diffCard.addView(diff);
        root.addView(diffCard);

        return sv;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        l.setBackground(bg);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
