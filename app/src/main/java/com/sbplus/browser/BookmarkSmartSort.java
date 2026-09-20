package com.sbplus.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BookmarkSmartSort —— 书签智能整理的流程编排。
 *
 * <p>流程（分成「分类」与「落库」两段，中间夹一次预览确认）：
 * <pre>
 *   书签管理 → 智能分类
 *        ↓
 *   选择书签（默认全选）
 *        ↓
 *   二次确认（倒计时）
 *        ↓
 *   阶段一：AI 定类目体系        ┐
 *   阶段二：分批套用类目         ├─ 只算不写，可反复重来
 *        ↓                      ┘
 *   ★ 预览（哪条去哪 + 校验报告）
 *        ↓  不满意 → 「重新分类」退回分类阶段
 *   确认 → 备份 → 落库
 *        ↓
 *   结果
 * </pre>
 *
 * <p><b>关键设计</b>：分类阶段<b>完全不碰数据库</b>。所有写操作都发生在预览确认
 * 之后，且写之前必有备份。这样主人可以反复重试分类，而书签不会被中途改动。
 */
public final class BookmarkSmartSort {

    /** 二次确认的倒计时秒数。防手滑，不必太长。 */
    private static final int CONFIRM_SECONDS = 3;

    /**
     * 阶段二每批的条数。
     *
     * <p>串行之后靠「大批」抢回速度：649 条书签，100 条/批只要 7 轮，
     * 而 60 条/批要 11 轮。轮次少了，总耗时和出错概率都降。
     */
    static final int BATCH_SIZE = 100;

    /**
     * 并发批数。
     *
     * <p>设为 1（串行）。实测并发会触发大面积 {@code SSLHandshakeException:
     * connection closed} —— 服务端或中间的 CDN 在压力下直接掐连接，
     * 649 条书签时 11 批全挂，重试也救不回来。串行慢一点，但能跑完。
     *
     * <p>要提速应该调大批大小，而不是开并发：大批是「一次多干点」，
     * 并发是「同时打多个请求」，后者在免费服务上基本等于自找限流。
     */
    private static final int PARALLEL = 1;

    /** 阶段一采样的条数上限。 */
    private static final int TAXONOMY_SAMPLES = 60;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BookmarkSmartSort() {}

    // ==================== 设置读写 ====================

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("sbplus_config", Context.MODE_PRIVATE);
    }

    // ==================== ① 入口 ====================

    public static void start(final Activity act) {
        try {
            MainHook.showBookmarkPickForSort(act);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] BookmarkSmartSort.start error: " + t);
            toast(act, MainHook.T("打开失败", "Failed to open"));
        }
    }

    // ==================== ② 二次确认（倒计时） ====================

    /**
     * 二次确认（倒计时）+ 完整地址选项。
     *
     * <p>完整地址开关放在这里而不是「模型」设置里 —— 它是「这一次分类发什么」
     * 的决定，每次分类都可能不一样，跟模型凭据不是一回事。
     *
     * <p>勾选它会弹风险提示；风险提示取消了，就按没勾处理。
     */
    static void confirmWithCountdown(final Activity act, final int count,
                                     final Runnable onConfirmed) {
        if (act == null || act.isFinishing()) return;
        try {
            final LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 20);
            box.setPadding(pad, pad, pad, pad);

            TextView msg = new TextView(act);
            StringBuilder sb = new StringBuilder();
            sb.append(MainHook.T("即将对 ", "About to classify ")).append(count)
              .append(MainHook.T(" 个书签进行分类", " bookmarks")).append("\n\n");
            sb.append(MainHook.T("· 这一步只做分类，不会改动书签",
                    "· This step only classifies; nothing is changed yet")).append("\n");
            sb.append(MainHook.T("· 分类完成后会先给你看结果，确认后才真正整理",
                    "· You will review the result before anything is applied"));
            msg.setText(sb.toString());
            box.addView(msg);

            // ---- 完整地址选项 ----
            final boolean[] fullUrl = new boolean[]{ ModelStore.isFullUrl(act) };
            final android.widget.CheckBox cb = new android.widget.CheckBox(act);
            cb.setText(MainHook.T("发送完整网址（更准确）", "Send full URLs (more accurate)"));
            cb.setChecked(fullUrl[0]);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(act, 12);
            box.addView(cb, clp);

            final TextView tip = new TextView(act);
            tip.setTextSize(12f);
            tip.setPadding(dp(act, 8), 0, 0, 0);
            updateFullUrlTip(tip, fullUrl[0]);
            box.addView(tip);

            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b,
                                                        boolean checked) {
                    if (checked) {
                        // 开启前告知风险；取消则回弹
                        new AlertDialog.Builder(act)
                                .setTitle(MainHook.T("确认开启？", "Turn this on?"))
                                .setMessage(MainHook.T(
                                        "开启后，书签的完整网址会发送给模型服务商。\n\n"
                                        + "网址里可能含有：\n"
                                        + "· 分享令牌（别人拿到就能访问）\n"
                                        + "· 网盘提取码\n"
                                        + "· 内网地址、带参数的视频链接\n\n"
                                        + "只在你信任该服务商时开启。",
                                        "Full URLs will be sent to the model provider.\n\n"
                                        + "They may contain:\n"
                                        + "· share tokens (anyone with them can access)\n"
                                        + "· cloud-drive extraction codes\n"
                                        + "· intranet addresses and signed video links\n\n"
                                        + "Only enable if you trust the provider."))
                                .setPositiveButton(MainHook.T("仍要开启", "Enable"), null)
                                .setNegativeButton(MainHook.T("取消", "Cancel"),
                                        new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        cb.setChecked(false);
                                    }
                                })
                                .show();
                    }
                    fullUrl[0] = checked;
                    updateFullUrlTip(tip, checked);
                }
            });

            ScrollView sv = new ScrollView(act);
            sv.addView(box);

            final AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("确认智能分类", "Confirm smart sort"))
                    .setView(sv)
                    .setPositiveButton(MainHook.T("确认", "Confirm"), null)
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .create();
            dlg.show();

            final Button ok = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
            ok.setEnabled(false);
            final int[] left = new int[]{ CONFIRM_SECONDS };
            ok.setText(MainHook.T("确认 (", "Confirm (") + left[0] + ")");

            final Runnable tick = new Runnable() {
                @Override public void run() {
                    if (dlg.isShowing()) {
                        left[0]--;
                        if (left[0] > 0) {
                            ok.setText(MainHook.T("确认 (", "Confirm (") + left[0] + ")");
                            MAIN.postDelayed(this, 1000);
                        } else {
                            ok.setEnabled(true);
                            ok.setText(MainHook.T("确认", "Confirm"));
                        }
                    }
                }
            };
            MAIN.postDelayed(tick, 1000);

            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 记住主人的选择，下次打开沿用
                    ModelStore.setFullUrl(act, fullUrl[0]);
                    dlg.dismiss();
                    onConfirmed.run();
                }
            });
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] confirmWithCountdown error: " + t);
        }
    }

    /** 完整地址开关下面的说明文字，随勾选状态变化。 */
    private static void updateFullUrlTip(TextView tip, boolean on) {
        try {
            tip.setText(on
                    ? MainHook.T("将发送完整网址，分类更准确。网址中的分享令牌、"
                            + "提取码等也会一并发送。",
                            "Full URLs will be sent for better accuracy. Share tokens and "
                            + "extraction codes go with them.")
                    : MainHook.T("只发送域名，网址的路径和参数留在本机。",
                            "Only domains are sent; paths and parameters stay on this device."));
        } catch (Throwable ignored) {}
    }

    // ==================== ③ 分类（只算不写） ====================

    /** 一次分类任务的产物。 */
    static final class Plan {
        List<BookmarkOrganizer.Entry> entries = new ArrayList<BookmarkOrganizer.Entry>();
        /** 序号 → 大类。null / 空 = 未分类（留原位）。 */
        Map<Integer, String> categories = new LinkedHashMap<Integer, String>();
        /** 序号 → 子类。null / 空 = 直接放在大类下。 */
        Map<Integer, String> subs = new LinkedHashMap<Integer, String>();
        final List<String> warnings = new ArrayList<String>();
        /** 阶段一定下的两级体系；空表示退化为自由分类。 */
        List<BookmarkClassifier.Category> taxonomy = new ArrayList<BookmarkClassifier.Category>();
        int classified;      // 成功分到类的条数
        int unclassified;    // 保留原位的条数

        /** 某条的完整路径（"大类/子类" 或 "大类"）；未分类返回 null。 */
        String pathOf(int index) {
            String c = categories.get(index);
            if (c == null || c.isEmpty()) return null;
            String s = subs.get(index);
            if (s == null || s.isEmpty()) return c;
            return c + "/" + s;
        }

        /** 按「大类/子类」统计条数。 */
        Map<String, Integer> counts() {
            Map<String, Integer> m = new LinkedHashMap<String, Integer>();
            for (Integer i : categories.keySet()) {
                String p = pathOf(i);
                if (p == null) continue;
                Integer n = m.get(p);
                m.put(p, n == null ? 1 : n + 1);
            }
            return m;
        }

        /** 顶层大类 → 条数（用于预览界面的折叠分组）。 */
        Map<String, Integer> topCounts() {
            Map<String, Integer> m = new LinkedHashMap<String, Integer>();
            for (Integer i : categories.keySet()) {
                String c = categories.get(i);
                if (c == null || c.isEmpty()) continue;
                Integer n = m.get(c);
                m.put(c, n == null ? 1 : n + 1);
            }
            return m;
        }
    }

    /** 进度回调。pct 为 0-100。 */
    interface Progress {
        void onProgress(String title, String detail, int pct);
    }

    /**
     * 执行分类（后台线程调用），产出 {@link Plan}。只读数据库，不写任何东西。
     */
    static Plan classify(Context app, List<Long> ids, Progress pg) {
        Plan plan = new Plan();

        pg.onProgress(MainHook.T("正在读取书签...", "Reading bookmarks..."), "", 2);
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(MainHook.bookmarkDbPathPublic(), null,
                    SQLiteDatabase.OPEN_READONLY);
            plan.entries = readEntriesById(db, ids);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] classify read error: " + t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }

        if (plan.entries.isEmpty()) {
            plan.warnings.add(MainHook.T("没有读到可整理的书签", "No bookmarks found"));
            return plan;
        }

        List<BookmarkClassifier.Item> items = new ArrayList<BookmarkClassifier.Item>();
        for (int i = 0; i < plan.entries.size(); i++) {
            BookmarkOrganizer.Entry e = plan.entries.get(i);
            e.index = i;        // 记下序号，落库时靠它回查分类结果
            items.add(new BookmarkClassifier.Item(i, e.title, e.url));
        }

        Map<Integer, BookmarkClassifier.Result> results =
                new LinkedHashMap<Integer, BookmarkClassifier.Result>();
        for (BookmarkClassifier.Item it : items) {
            results.put(it.index, new BookmarkClassifier.Result(it.index));
        }

        final String[] triple = ModelStore.currentTriple(app);
        if (triple == null) {
            plan.warnings.add("__NO_MODEL__");
            return plan;
        }
        BookmarkAiClient client = new BookmarkAiClient(triple[0], triple[1], triple[2]);
        final boolean fullUrl = ModelStore.isFullUrl(app);

        // ---- 阶段一：定类目体系 ----
        // 只有一批书签时不值得多跑一轮：单批内部的类目本来就自洽。
        if (items.size() > BATCH_SIZE) {
            pg.onProgress(MainHook.T("正在分析内容，设计分类体系...", "Designing categories..."),
                    MainHook.T("样本 ", "Sample ") + Math.min(TAXONOMY_SAMPLES, items.size())
                    + MainHook.T(" 条", " items"), 8);
            try {
                String txPayload = BookmarkClassifier.buildTaxonomyPayload(items, TAXONOMY_SAMPLES, fullUrl);
                String txRaw = client.callWith(BookmarkClassifier.taxonomyPrompt(), txPayload);
                plan.taxonomy = BookmarkClassifier.parseTaxonomy(txRaw);
                // 把体系完整打出来 —— 排查「不在既定体系内」必须能看到它
                StringBuilder dump = new StringBuilder();
                for (BookmarkClassifier.Category c : plan.taxonomy) {
                    dump.append("[").append(c.name);
                    for (String s : c.subs) dump.append("|").append(s);
                    dump.append("] ");
                }
                MainModule.logMsg("[SBPlus] taxonomy(" + plan.taxonomy.size() + "): " + dump);
                if (plan.taxonomy.isEmpty()) {
                    MainModule.logMsg("[SBPlus] TAXONOMY EMPTY -> 归一失效, 会自由分类");
                    MainModule.logMsg("[SBPlus] raw taxonomy reply: "
                            + (txRaw == null ? "null" : txRaw.substring(0,
                                    Math.min(700, txRaw.length()))));
                }
            } catch (Throwable t) {
                MainModule.logMsg("[SBPlus] taxonomy stage failed: " + t);
                plan.warnings.add(MainHook.T("分类体系设计失败，改用逐条自由分类",
                        "Category design failed; using free-form classification"));
            }
        } else {
            MainModule.logMsg("[SBPlus] skipped taxonomy stage (only " + items.size() + " items)");
        }

        // 清空上一轮扩体系的记录，否则第二次整理会被上一轮的额度占满
        BookmarkClassifier.resetExtensions();

        // ---- 阶段二：分批套用（并发 PARALLEL 路）----
        List<Integer> allIdx = new ArrayList<Integer>();
        for (BookmarkClassifier.Item it : items) allIdx.add(it.index);
        int batches = (allIdx.size() + BATCH_SIZE - 1) / BATCH_SIZE;

        // 先切好所有批次
        List<List<Integer>> slices = new ArrayList<List<Integer>>();
        for (int b = 0; b < batches; b++) {
            int from = b * BATCH_SIZE;
            int to = Math.min(from + BATCH_SIZE, allIdx.size());
            slices.add(new ArrayList<Integer>(allIdx.subList(from, to)));
        }

        final String batchSystem = BookmarkClassifier.batchPrompt(plan.taxonomy);
        final java.util.concurrent.atomic.AtomicInteger doneCnt =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger batchNo =
                new java.util.concurrent.atomic.AtomicInteger(0);
        // 记录每批的序号，供失败时提示
        final List<String> errs = java.util.Collections.synchronizedList(new ArrayList<String>());

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(PARALLEL, Math.max(1, slices.size())));
        List<java.util.concurrent.Future<?>> futures = new ArrayList<java.util.concurrent.Future<?>>();

        for (final List<Integer> slice : slices) {
            futures.add(pool.submit(new Runnable() {
                @Override public void run() {
                    int myNo = batchNo.incrementAndGet();
                    try {
                        String payload = BookmarkClassifier.buildBatchPayload(items, slice, fullUrl);
                        String raw = client.callWith(batchSystem, payload);
                        BookmarkClassifier.Verification v =
                                BookmarkClassifier.applyBatchResponse(raw, results, slice, plan.taxonomy);
                        int d = doneCnt.addAndGet(slice.size() - v.missing);
                        if (!v.isClean()) {
                            String s = v.summary();
                            if (!s.isEmpty()) {
                                synchronized (plan.warnings) {
                                    if (!plan.warnings.contains(s)) plan.warnings.add(s);
                                }
                            }
                            MainModule.logMsg("[SBPlus] batch " + myNo + " verify: missing="
                                    + v.missing + " extra=" + v.extra
                                    + " invalid=" + v.invalidCategory);
                        }
                        pg.onProgress(MainHook.T("正在分类...", "Classifying..."),
                                MainHook.T("已处理 ", "Processed ") + d + "/" + allIdx.size()
                                        + MainHook.T(" · ", " · ") + batchNo.get() + "/"
                                        + slices.size() + MainHook.T(" 批", " batches"),
                                10 + (int) (85.0 * d / Math.max(1, allIdx.size())));
                    } catch (Throwable t) {
                        MainModule.logMsg("[SBPlus] batch " + myNo + " failed: " + t);
                        errs.add(MainHook.T("第 ", "Batch ") + myNo + MainHook.T(" 批请求失败，"
                                + "该批已保留原位",
                                " failed; those bookmarks kept their original position"));
                    }
                }
            }));
        }

        pool.shutdown();
        try {
            // 等全部批次收工；给足超时，避免某批卡住永远不返回
            pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        for (String e : errs) plan.warnings.add(e);

        // ---- 汇总 ----
        pg.onProgress(MainHook.T("正在整理结果...", "Finalizing..."), "", 97);
        int classified = 0, unclassified = 0;
        for (BookmarkClassifier.Item it : items) {
            BookmarkClassifier.Result r = results.get(it.index);
            String c = r == null ? null : r.category;
            if (c == null || c.isEmpty() || "未分类".equals(c)) {
                plan.categories.put(it.index, null);
                plan.subs.put(it.index, null);
                unclassified++;
            } else {
                plan.categories.put(it.index, c);
                plan.subs.put(it.index, r.sub);
                classified++;
            }
        }
        plan.classified = classified;
        plan.unclassified = unclassified;
        pg.onProgress(MainHook.T("分类完成", "Done"), "", 100);
        java.util.List<String> ext = BookmarkClassifier.extendedTops();
        if (!ext.isEmpty()) {
            plan.warnings.add(MainHook.T("AI 新增了 ", "AI added ")
                    + ext.size() + MainHook.T(" 个大类：", " new groups: ")
                    + join(ext));
        }
        MainModule.logMsg("[SBPlus] classify done: classified=" + classified
                + " unclassified=" + unclassified
                + " top=" + plan.topCounts().size()
                + " paths=" + plan.counts().size()
                + " extended=" + ext);
        return plan;
    }

    /** 列表拼成 "a、b、c"。 */
    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    /**
     * 按 ID 列表读出要处理的书签。
     *
     * <p>一次查完再过滤，而不是每条一个 SELECT —— 300 条书签就是 300 次查询，
     * 在手机上这部分能占到几秒。
     */
    private static List<BookmarkOrganizer.Entry> readEntriesById(SQLiteDatabase db, List<Long> ids) {
        List<BookmarkOrganizer.Entry> out = new ArrayList<BookmarkOrganizer.Entry>();
        java.util.Set<Long> want = new java.util.HashSet<Long>(ids);
        android.database.Cursor c = null;
        try {
            c = db.rawQuery("SELECT _ID, TITLE, URL, PARENT FROM BOOKMARKS WHERE FOLDER=0", null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (!want.contains(id)) continue;
                BookmarkOrganizer.Entry e = new BookmarkOrganizer.Entry();
                e.id = id;
                e.title = c.isNull(1) ? "" : c.getString(1);
                e.url = c.isNull(2) ? "" : c.getString(2);
                e.parent = c.getLong(3);
                out.add(e);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] readEntriesById error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        // 按传入顺序排回来（AI 序号要和勾选顺序对齐）
        Map<Long, Integer> order = new HashMap<Long, Integer>();
        for (int i = 0; i < ids.size(); i++) order.put(ids.get(i), i);
        java.util.Collections.sort(out, new java.util.Comparator<BookmarkOrganizer.Entry>() {
            @Override public int compare(BookmarkOrganizer.Entry a, BookmarkOrganizer.Entry b) {
                Integer ia = order.get(a.id), ib = order.get(b.id);
                return (ia == null ? 0 : ia) - (ib == null ? 0 : ib);
            }
        });
        return out;
    }

    // ==================== ④ 落库（确认后） ====================

    /**
     * 应用分类结果。**这是唯一会改动书签的地方**，调用前必有备份。
     */
    static void apply(final Activity act, final Plan plan, final Runnable onFinished) {
        // 注意：plan.categories / plan.subs 是以「书签序号」为键的，
        // 不是 entries 的下标 —— 写错过一次，会导致分类结果整体错位。
        for (BookmarkOrganizer.Entry e : plan.entries) {
            e.category = plan.pathOf(e.index);
        }

        final AlertDialog[] pd = new AlertDialog[1];
        final ProgressBar[] bar = new ProgressBar[1];
        final TextView[] tv = new TextView[1];
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 24);
            box.setPadding(pad, pad, pad, pad);
            tv[0] = new TextView(act);
            tv[0].setText(MainHook.T("正在备份...", "Backing up..."));
            box.addView(tv[0]);
            bar[0] = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            bar[0].setMax(100);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(act, 12);
            box.addView(bar[0], blp);
            pd[0] = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("正在整理书签", "Applying"))
                    .setView(box)
                    .setCancelable(false)
                    .create();
            pd[0].show();
        } catch (Throwable ignored) {}

        MainHook.SbExecutorsBg(new Runnable() {
            @Override public void run() {
                final StringBuilder msg = new StringBuilder();
                boolean ok = false;
                try {
                    final String backup = MainHook.backupBookmarkDbPublic();
                    if (backup == null) {
                        msg.append(MainHook.T("备份失败，已中止（避免损坏书签库）",
                                "Backup failed; aborted to protect your bookmarks"));
                    } else {
                        setStep(tv, bar, MainHook.T("正在整理书签...", "Applying..."), 30);
                        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                                MainHook.bookmarkDbPathPublic(), null,
                                SQLiteDatabase.OPEN_READWRITE);
                        try {
                            long root = BookmarkOrganizer.detectRootParent(db);
                            db.beginTransaction();
                            BookmarkOrganizer.Stats st;
                            try {
                                st = BookmarkOrganizer.organize(db, plan.entries, root);
                                db.setTransactionSuccessful();
                            } finally {
                                try { if (db.inTransaction()) db.endTransaction(); }
                                catch (Throwable ignored) {}
                            }
                            setStep(tv, bar, MainHook.T("完成", "Done"), 100);

                            msg.append(MainHook.T("已整理 ", "Sorted ")).append(st.moved)
                               .append(MainHook.T(" 个书签", " bookmarks"));
                            Map<String, Integer> counts = st.perCategory;
                            if (!counts.isEmpty()) {
                                msg.append(MainHook.T("，分为 ", " into ")).append(counts.size())
                                   .append(MainHook.T(" 类：\n", " categories:\n"));
                                for (Map.Entry<String, Integer> en : counts.entrySet()) {
                                    msg.append("  ").append(en.getKey()).append("  ")
                                       .append(en.getValue()).append("\n");
                                }
                            }
                            if (st.keptInPlace > 0) {
                                msg.append("\n").append(MainHook.T("保留原位 ", "Left in place "))
                                   .append(st.keptInPlace).append(MainHook.T(" 个", " bookmarks"));
                            }
                            if (st.failed > 0) {
                                msg.append("\n").append(MainHook.T("写入失败 ", "Failed "))
                                   .append(st.failed);
                            }
                            msg.append("\n\n").append(MainHook.T("已备份到：", "Backup at:"))
                               .append("\n").append(backup);
                            msg.append("\n").append(MainHook.T("重启浏览器后生效",
                                    "Restart the browser to apply"));
                            ok = true;
                            MainModule.logMsg("[SBPlus] apply done moved=" + st.moved
                                    + " kept=" + st.keptInPlace + " created=" + st.foldersCreated
                                    + " reused=" + st.foldersReused + " failed=" + st.failed);
                        } finally {
                            try { db.close(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    MainModule.logMsg("[SBPlus] apply error: " + t);
                    msg.append(MainHook.T("整理出错：", "Error: ")).append(t.getMessage());
                }

                final boolean okf = ok;
                final String text = msg.toString();
                MAIN.post(new Runnable() { @Override public void run() {
                    try { if (pd[0] != null && pd[0].isShowing()) pd[0].dismiss(); }
                    catch (Throwable ignored) {}
                    try {
                        new AlertDialog.Builder(act)
                                .setTitle(okf ? MainHook.T("整理完成", "Done")
                                              : MainHook.T("整理失败", "Failed"))
                                .setMessage(text)
                                .setPositiveButton(MainHook.T("好", "OK"),
                                        new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        if (onFinished != null) onFinished.run();
                                    }
                                })
                                .show();
                    } catch (Throwable t) {
                        MainModule.logMsg("[SBPlus] result dialog error: " + t);
                    }
                }});
            }
        });
    }

    private static void setStep(final TextView[] tv, final ProgressBar[] bar,
                                final String text, final int pct) {
        MAIN.post(new Runnable() { @Override public void run() {
            try {
                if (tv[0] != null) tv[0].setText(text);
                if (bar[0] != null) bar[0].setProgress(pct);
            } catch (Throwable ignored) {}
        }});
    }

    // ==================== 模型设置转发 ====================

    public static boolean isAiConfigured(Context c) {
        return ModelStore.isConfigured(c);
    }

    public static void showAiSettings(final Activity act) {
        ModelSettingsUI.show(act);
    }

    // ==================== 小工具 ====================

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static void toast(Context c, String msg) {
        try {
            if (c == null) return;
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }
}
