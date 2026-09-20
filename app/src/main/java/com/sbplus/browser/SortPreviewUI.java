package com.sbplus.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SortPreviewUI —— 分类预览界面。
 *
 * <p>这是「分类」与「落库」之间的那道闸：分类结果先在这里给主人看，
 * 不满意可以点「重新分类」退回重来（分类阶段不写库，所以重来没有代价），
 * 满意才点「确认整理」真正动书签。
 *
 * <p>界面分三块：
 * <pre>
 *   ① 概览    —— 共 N 条，已分类 X，保留原位 Y
 *   ② 类目分布 —— 每个类目多少条（点击可展开看具体书签）
 *   ③ 提示    —— 校验异常 / 失败批次
 * </pre>
 */
public final class SortPreviewUI {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SortPreviewUI() {}

    /**
     * 跑一次分类（带进度框），完成后弹预览。
     *
     * @param onRetry 主人点「重新分类」时调用（回到这一步重跑）
     */
    static void runClassifyAndPreview(final Activity act, final List<Long> ids,
                                      final Runnable onRetry) {
        if (act == null || act.isFinishing()) return;

        // ---- 进度框 ----
        final AlertDialog[] pd = new AlertDialog[1];
        final ProgressBar[] bar = new ProgressBar[1];
        final TextView[] titleTv = new TextView[1];
        final TextView[] detailTv = new TextView[1];
        final Button[] cancelBtn = new Button[1];
        final boolean[] cancelled = new boolean[]{ false };

        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 24);
            box.setPadding(pad, pad, pad, pad);

            titleTv[0] = new TextView(act);
            titleTv[0].setText(MainHook.T("正在准备...", "Preparing..."));
            box.addView(titleTv[0]);

            detailTv[0] = new TextView(act);
            detailTv[0].setTextSize(12f);
            detailTv[0].setPadding(0, dp(act, 6), 0, 0);
            box.addView(detailTv[0]);

            bar[0] = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            bar[0].setMax(100);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(act, 12);
            box.addView(bar[0], blp);

            cancelBtn[0] = new Button(act);
            cancelBtn[0].setText(MainHook.T("取消", "Cancel"));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(act, 12);
            clp.gravity = Gravity.END;
            box.addView(cancelBtn[0], clp);

            pd[0] = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("智能分类", "Smart sort"))
                    .setView(box)
                    .setCancelable(false)
                    .create();
            pd[0].show();
            // 美化关闭但保留取消语义：点外部不关，只能点取消按钮
            cancelBtn[0].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cancelled[0] = true;
                    try { pd[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}

        MainHook.SbExecutorsBg(new Runnable() {
            @Override public void run() {
                BookmarkSmartSort.Progress pg = new BookmarkSmartSort.Progress() {
                    @Override public void onProgress(final String title,
                                                     final String detail, final int pct) {
                        if (cancelled[0]) return;
                        MAIN.post(new Runnable() { @Override public void run() {
                            try {
                                if (titleTv[0] != null) titleTv[0].setText(title);
                                if (detailTv[0] != null) detailTv[0].setText(detail);
                                if (bar[0] != null) bar[0].setProgress(pct);
                            } catch (Throwable ignored) {}
                        }});
                    }
                };

                final BookmarkSmartSort.Plan plan =
                        BookmarkSmartSort.classify(act.getApplicationContext(), ids, pg);

                MAIN.post(new Runnable() { @Override public void run() {
                    // 2026-09-20 S5 修复:classify 可能耗时数分钟,期间 Activity 可能已被
                    // 系统回收/用户关闭;此前弹窗前未重查状态也无整体 try/catch,
                    // BadTokenException 会直接崩掉宿主浏览器进程。
                    try {
                        try { if (pd[0] != null && pd[0].isShowing()) pd[0].dismiss(); }
                        catch (Throwable ignored) {}
                        if (cancelled[0]) return;
                        if (act == null || act.isFinishing() || act.isDestroyed()) return;

                        // 未配置模型
                        if (plan.warnings.contains("__NO_MODEL__")) {
                            new AlertDialog.Builder(act)
                                    .setTitle(MainHook.T("还没有配置模型", "No model configured"))
                                    .setMessage(MainHook.T(
                                            "请先到「设置 → 模型」添加一个分组并选择模型，"
                                            + "然后再来分类。",
                                            "Go to Settings → Models to add one first."))
                                    .setPositiveButton(MainHook.T("好", "OK"), null)
                                    .show();
                            return;
                        }

                        show(act, plan, ids, onRetry);
                    } catch (Throwable t) {
                        MainModule.logMsg("[SBPlus] classify result UI error: " + t);
                    }
                }});
            }
        });
    }

    /** 展示预览。 */
    static void show(final Activity act, final BookmarkSmartSort.Plan plan,
                     final List<Long> ids, final Runnable onRetry) {
        if (act == null || act.isFinishing()) return;
        try {
            LinearLayout body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 16);
            body.setPadding(pad, pad, pad, pad);

            // ---- ① 概览 ----
            TextView head = new TextView(act);
            head.setTextSize(15f);
            head.setText(MainHook.T("共 ", "Total ") + plan.entries.size()
                    + MainHook.T(" 条 · 已分类 ", " · classified ")
                    + plan.classified
                    + MainHook.T(" · 保留原位 ", " · left in place ")
                    + plan.unclassified);
            body.addView(head);

            Map<String, Integer> counts = plan.counts();
            if (!counts.isEmpty()) {
                TextView cats = new TextView(act);
                cats.setTextSize(12f);
                cats.setPadding(0, dp(act, 4), 0, 0);
                cats.setText(MainHook.T("分为 ", "Into ") + plan.topCounts().size()
                        + MainHook.T(" 个大类", " groups") + "、" + counts.size()
                        + MainHook.T(" 个子类", " sub-groups"));
                body.addView(cats);
            }

            // ---- ② 两级结构预览（大类 → 子类，点击展开） ----
            TextView hint = new TextView(act);
            hint.setTextSize(12f);
            hint.setPadding(0, dp(act, 10), 0, dp(act, 4));
            hint.setText(MainHook.T("点任意一行可查看具体书签：",
                    "Tap any row to see its bookmarks:"));
            body.addView(hint);

            // 大类 → 子类 → 书签标题。子类键为 "" 表示直接放在大类下。
            // 注意 plan.subs 的键是「书签序号」，不是 entries 的下标。
            final Map<String, Map<String, List<String>>> tree =
                    new java.util.LinkedHashMap<String, Map<String, List<String>>>();
            for (int i = 0; i < plan.entries.size(); i++) {
                BookmarkOrganizer.Entry e = plan.entries.get(i);
                String c = plan.categories.get(e.index);
                if (c == null || c.isEmpty()) continue;
                String s = plan.subs.get(e.index);
                if (s == null) s = "";

                Map<String, List<String>> subs = tree.get(c);
                if (subs == null) {
                    subs = new java.util.LinkedHashMap<String, List<String>>();
                    tree.put(c, subs);
                }
                List<String> l = subs.get(s);
                if (l == null) { l = new ArrayList<String>(); subs.put(s, l); }
                l.add(e.title == null || e.title.isEmpty() ? e.url : e.title);
            }

            for (final Map.Entry<String, Map<String, List<String>>> top : tree.entrySet()) {
                // 大类总条数
                int topTotal = 0;
                for (List<String> l : top.getValue().values()) topTotal += l.size();

                TextView topTv = new TextView(act);
                topTv.setText(top.getKey());
                topTv.setTextSize(15f);
                topTv.setPadding(0, dp(act, 8), 0, dp(act, 2));
                body.addView(topTv);

                for (final Map.Entry<String, List<String>> sub : top.getValue().entrySet()) {
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(act, 16), dp(act, 4), 0, dp(act, 4));

                    TextView nameTv = new TextView(act);
                    // 子类为空 = 直接放在大类下
                    nameTv.setText(sub.getKey().isEmpty()
                            ? MainHook.T("（直接放在此大类下）", "(directly under this group)")
                            : sub.getKey());
                    nameTv.setTextSize(13f);
                    row.addView(nameTv, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    TextView cntTv = new TextView(act);
                    cntTv.setText(String.valueOf(sub.getValue().size()));
                    cntTv.setTextSize(13f);
                    cntTv.setPadding(dp(act, 8), 0, 0, 0);
                    row.addView(cntTv);

                    final String fullPath = sub.getKey().isEmpty()
                            ? top.getKey() : top.getKey() + "/" + sub.getKey();
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showCategoryContents(act, fullPath, sub.getValue());
                        }
                    });
                    body.addView(row);
                }
            }

            // 保留原位的条目也要能看到
            if (plan.unclassified > 0) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(act, 6), 0, dp(act, 6));

                TextView nameTv = new TextView(act);
                nameTv.setText(MainHook.T("（保留原位）", "(left in place)"));
                nameTv.setTextSize(14f);
                row.addView(nameTv, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView cntTv = new TextView(act);
                cntTv.setText(String.valueOf(plan.unclassified));
                cntTv.setTextSize(14f);
                cntTv.setPadding(dp(act, 8), 0, 0, 0);
                row.addView(cntTv);

                final List<String> kept = new ArrayList<String>();
                for (int i = 0; i < plan.entries.size(); i++) {
                    String c = plan.categories.get(i);
                    if (c == null || c.isEmpty()) {
                        BookmarkOrganizer.Entry e = plan.entries.get(i);
                        kept.add(e.title == null || e.title.isEmpty() ? e.url : e.title);
                    }
                }
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showCategoryContents(act, MainHook.T("（保留原位）", "(left in place)"), kept);
                    }
                });
                body.addView(row);
            }

            // ---- ③ 提示 ----
            if (!plan.warnings.isEmpty()) {
                View div = new View(act);
                div.setBackgroundColor(Color.parseColor("#33000000"));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(act, 1)));
                dlp.topMargin = dp(act, 10);
                dlp.bottomMargin = dp(act, 10);
                body.addView(div, dlp);

                for (String w : plan.warnings) {
                    if (w == null || w.isEmpty() || "__NO_MODEL__".equals(w)) continue;
                    TextView wt = new TextView(act);
                    wt.setText("· " + w);
                    wt.setTextSize(12f);
                    body.addView(wt);
                }
            }

            ScrollView sv = new ScrollView(act);
            sv.addView(body);

            final AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("分类预览", "Preview"))
                    .setView(sv)
                    .setPositiveButton(MainHook.T("确认整理", "Apply"), null)
                    .setNeutralButton(MainHook.T("重新分类", "Redo"), null)
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .create();
            dlg.show();

            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    BookmarkSmartSort.apply(act, plan, null);
                }
            });
            dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    if (onRetry != null) onRetry.run();
                }
            });
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] SortPreviewUI.show error: " + t);
        }
    }

    /** 展开某个类目里的书签标题列表。 */
    private static void showCategoryContents(Activity act, String category, List<String> titles) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 16);
            box.setPadding(pad, pad, pad, pad);

            for (String t : titles) {
                TextView tv = new TextView(act);
                tv.setText("· " + t);
                tv.setTextSize(13f);
                tv.setPadding(0, dp(act, 3), 0, dp(act, 3));
                box.addView(tv);
            }

            ScrollView sv = new ScrollView(act);
            sv.addView(box);

            new AlertDialog.Builder(act)
                    .setTitle(category + "  (" + titles.size() + ")")
                    .setView(sv)
                    .setPositiveButton(MainHook.T("关闭", "Close"), null)
                    .show();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] showCategoryContents error: " + t);
        }
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static void toast(Context c, String msg) {
        try {
            if (c != null) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
