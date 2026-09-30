package com.sbplus.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ModelSettingsUI —— 「模型」设置界面：分组管理 + 全局唯一选中。
 *
 * <p>界面结构：
 * <pre>
 *   模型
 *     ▼ 组名(可改)              [删组]
 *        api.xxx/v1
 *        ● 模型A                ← 点选切换（全局唯一）
 *        ○ 模型B
 *        [+ 添加模型] [重新拉取]
 *     ▼ 组名B ...
 *     [+ 添加]
 * </pre>
 */
public final class ModelSettingsUI {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 当前已弹出的模型对话框。2026-09-20 修复:show() 此前被当作「刷新重绘」
     * 反复调用(单选/增删/改名后),每次都 new 一个 AlertDialog 且从不关旧的,
     * 对话框无限叠加(点 5 个模型叠 5 层)。重入时先关旧的。
     */
    private static android.app.Dialog sModelsDialog;

    private ModelSettingsUI() {}

    // ==================== 主界面 ====================

    public static void show(final Activity act) {
        if (act == null || act.isFinishing()) return;
        if (sModelsDialog != null) {
            try { sModelsDialog.dismiss(); } catch (Throwable ignored) {}
            sModelsDialog = null;
        }
        try {
            final LinearLayout body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 16);
            body.setPadding(pad, pad, pad, pad);

            final List<ModelStore.Group> groups = ModelStore.load(act);
            final String current = ModelStore.getCurrent(act);

            // ---- 无分组时的空状态 ----
            if (groups.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText(MainHook.T(
                        "还没有配置模型。\n\n添加一个分组，填入接口地址和 API Key，"
                        + "再从服务端拉取可用模型即可。",
                        "No models configured yet.\n\nAdd a group with a base URL and "
                        + "API key, then fetch the available models."));
                empty.setPadding(0, dp(act, 12), 0, dp(act, 12));
                body.addView(empty);
            }

            // ---- 每个分组 ----
            for (int gi = 0; gi < groups.size(); gi++) {
                final int groupIndex = gi;
                final ModelStore.Group g = groups.get(gi);

                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));

                // 组标题行: 组名 + 删组
                LinearLayout head = new LinearLayout(act);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);

                TextView nameTv = new TextView(act);
                nameTv.setText(g.name.isEmpty() ? hostOf(g.base) : g.name);
                nameTv.setTextSize(16f);
                nameTv.setPadding(0, 0, dp(act, 8), 0);
                head.addView(nameTv, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button renameBtn = new Button(act);
                renameBtn.setText(MainHook.T("改名", "Rename"));
                renameBtn.setTextSize(12f);
                head.addView(renameBtn);

                Button delBtn = new Button(act);
                delBtn.setText(MainHook.T("删组", "Delete"));
                delBtn.setTextSize(12f);
                head.addView(delBtn);
                body.addView(head);

                // 地址副标题
                TextView hostTv = new TextView(act);
                hostTv.setText(g.base);
                hostTv.setTextSize(12f);
                hostTv.setPadding(0, 0, 0, dp(act, 6));
                body.addView(hostTv);

                // 模型列表（单选）
                for (final String m : g.models) {
                    RadioButton rb = new RadioButton(act);
                    rb.setText(m);
                    rb.setChecked(m.equals(current));
                    rb.setPadding(dp(act, 8), dp(act, 4), 0, dp(act, 4));
                    rb.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            ModelStore.setCurrent(act, m);
                            show(act);   // 重绘，保证全局唯一
                        }
                    });
                    body.addView(rb);
                }
                if (g.models.isEmpty()) {
                    TextView none = new TextView(act);
                    none.setText(MainHook.T("（还没有模型）", "(no models yet)"));
                    none.setTextSize(12f);
                    none.setPadding(dp(act, 8), 0, 0, dp(act, 4));
                    body.addView(none);
                }

                // 操作行: 添加模型 / 重新拉取
                LinearLayout ops = new LinearLayout(act);
                ops.setOrientation(LinearLayout.HORIZONTAL);
                ops.setPadding(0, dp(act, 6), 0, 0);

                Button addBtn = new Button(act);
                addBtn.setText(MainHook.T("添加模型", "Add model"));
                addBtn.setTextSize(12f);
                ops.addView(addBtn, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button refetchBtn = new Button(act);
                refetchBtn.setText(MainHook.T("重新拉取", "Refetch"));
                refetchBtn.setTextSize(12f);
                ops.addView(refetchBtn, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                body.addView(ops);

                // 分隔线
                View div = new View(act);
                div.setBackgroundColor(Color.parseColor("#33000000"));
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(act, 1)));
                divLp.topMargin = dp(act, 12);
                divLp.bottomMargin = dp(act, 12);
                body.addView(div, divLp);

                // ---- 事件 ----
                delBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmDeleteGroup(act, groupIndex); }
                });
                renameBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { renameGroup(act, groupIndex); }
                });
                addBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { addModel(act, groupIndex); }
                });
                refetchBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { addModel(act, groupIndex); }
                });
            }
            ScrollView sv = new ScrollView(act);
            sv.addView(body);

            sModelsDialog = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("模型", "Models"))
                    .setView(sv)
                    .setPositiveButton(MainHook.T("添加", "Add"),
                            new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { newGroup(act); }
                    })
                    .setNegativeButton(MainHook.T("关闭", "Close"), null)
                    .show();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] ModelSettingsUI.show error: " + t);
            toast(act, MainHook.T("打开失败", "Failed to open"));
        }
    }

    // ==================== 添加分组 ====================

    static void newGroup(final Activity act) {
        try {
            final LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(act, 18);
            box.setPadding(pad, pad, pad, pad);

            box.addView(label(act, MainHook.T("分组名称", "Group name")));
            final EditText etName = new EditText(act);
            etName.setHint(MainHook.T("例如：DeepSeek 官方", "e.g. DeepSeek official"));
            box.addView(etName);

            box.addView(label(act, MainHook.T("接口地址", "Base URL")));
            final EditText etBase = new EditText(act);
            etBase.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            etBase.setHint("https://api.deepseek.com/v1");
            box.addView(etBase);

            box.addView(label(act, MainHook.T("API Key", "API key")));
            final EditText etKey = new EditText(act);
            etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            etKey.setHint("sk-...");
            box.addView(etKey);

            TextView tip = new TextView(act);
            // 2026-10-04(S7):如实告知 Key 的存放方式。
            // 本模块跑在宿主浏览器进程内,没有自己的 Application 初始化时机,
            // 也拿不到 androidx.security 的 EncryptedSharedPreferences(项目未引入该依赖),
            // 因此 Key 只能以明文存在宿主进程的私有 SharedPreferences(sbplus_config)里。
            // 不假装做了加密 —— 直接把事实说清楚,由用户决定填什么 Key。
            tip.setText(MainHook.T(
                    "同一地址只需填一次凭据。\n"
                    + "说明:API Key 以明文保存在浏览器应用的私有配置中"
                    + "(仅本应用可读,但已 root 的设备或同进程内的其它 Xposed 模块可读),"
                    + "建议使用限额 Key。",
                    "Credentials are entered once per base URL.\n"
                    + "Note: the API key is stored in cleartext in the browser app's private "
                    + "preferences (readable by this app only, but also by root or other Xposed "
                    + "modules in the same process). A rate-limited key is recommended."));
            tip.setTextSize(12f);
            tip.setPadding(0, dp(act, 8), 0, 0);
            box.addView(tip);

            ScrollView sv = new ScrollView(act);
            sv.addView(box);

            final AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("添加", "Add"))
                    .setView(sv)
                    .setPositiveButton(MainHook.T("创建", "Create"), null)
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .create();
            dlg.show();

            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String base = etBase.getText().toString().trim();
                    String key = etKey.getText().toString().trim();
                    if (base.isEmpty()) {
                        toast(act, MainHook.T("请填写接口地址", "Enter the base URL"));
                        return;
                    }
                    if (!base.startsWith("http")) {
                        toast(act, MainHook.T("接口地址应以 http 开头",
                                "The base URL should start with http"));
                        return;
                    }
                    // Key 必填校验放在 http 警告之前:否则用户填了 http 又漏填 Key 时,
                    // 会先被 https 警告拦一次、确认后才被告知"请填写 API Key"。
                    if (key.isEmpty()) {
                        toast(act, MainHook.T("请填写 API Key", "Enter the API key"));
                        return;
                    }
                    // 2026-10-04 修复(审查 S7):明文 http 传输会把 Bearer Key 暴露在链路上。
                    // 这里**不直接拒绝** —— 局域网自建服务(如本机 LM Studio/Ollama)是常见且
                    // 合理的用法,一律禁用会废掉该场景。改为对非 https 给出明确警告并要求确认,
                    // 让用户知道自己正在把密钥明文发出去。
                    if (!base.startsWith("https://")) {
                        final String fBase = base, fKey = key;
                        new android.app.AlertDialog.Builder(act)
                            .setTitle(MainHook.T("该地址不是 HTTPS", "Not HTTPS"))
                            .setMessage(MainHook.T(
                                    "接口地址是明文 http,API Key 会在网络上以明文传输,"
                                    + "可能被同一网络中的其他人截获。\n\n"
                                    + "如果是本机或局域网自建服务(如 Ollama / LM Studio),可以继续;\n"
                                    + "如果是公网服务,请改用 https。",
                                    "This endpoint uses plain http, so your API key will be sent "
                                    + "in cleartext and can be intercepted on the same network.\n\n"
                                    + "Fine for a local/LAN service (e.g. Ollama, LM Studio);\n"
                                    + "use https for any public service."))
                            .setPositiveButton(MainHook.T("继续使用", "Use anyway"),
                                    new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d2, int w2) {
                                            addGroupAndSave(act, fBase, fKey, etName, dlg);
                                        }
                                    })
                            .setNegativeButton(MainHook.T("返回修改", "Go back"), null)
                            .show();
                        return;
                    }
                    addGroupAndSave(act, base, key, etName, dlg);
                }
            });
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] newGroup error: " + t);
        }
    }

    /**
     * 保存新分组(由「添加分组」对话框调用,含 http 警告确认后的路径)。
     *
     * <p>抽成独立方法的原因:非 https 地址需要先弹警告、用户确认后再走同一段保存逻辑,
     * 避免把保存代码复制两份(复制品最容易各自漂移)。
     */
    private static void addGroupAndSave(final Activity act, String base, final String key,
                                        final android.widget.EditText etName,
                                        final android.app.Dialog dlg) {
        try {
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            List<ModelStore.Group> groups = ModelStore.load(act);
            ModelStore.Group g = new ModelStore.Group();
            g.name = etName.getText().toString().trim();
            if (g.name.isEmpty()) g.name = hostOf(base);
            g.base = base;
            g.key = key;
            groups.add(g);
            ModelStore.save(act, groups);
            try { dlg.dismiss(); } catch (Throwable ignored) {}
            show(act);            // 回到主界面
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] addGroupAndSave error: " + t);
        }
    }

    // ==================== 添加模型（单个 / 批量） ====================
    /**
     * 添加模型：先问「从列表选择」还是「手动输入」。
     *
     * <p>「从列表选择」会拉取服务端模型列表并支持<b>多选</b> —— 一次把要用的
     * 都加进来，这是「同一地址加多个模型」的主要路径。
     */
    static void addModel(final Activity act, final int groupIndex) {
        try {
            List<ModelStore.Group> groups = ModelStore.load(act);
            if (groupIndex < 0 || groupIndex >= groups.size()) return;
            final ModelStore.Group g = groups.get(groupIndex);

            new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("添加模型", "Add model"))
                    .setItems(new String[]{
                            MainHook.T("从列表选择（可多选）", "Choose from list (multi-select)"),
                            MainHook.T("手动输入单个", "Enter one manually") },
                            new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            if (which == 0) fetchAndPick(act, groupIndex);
                            else manualInput(act, groupIndex);
                        }
                    })
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .show();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] addModel error: " + t);
        }
    }

    /** 拉取服务端模型列表并多选添加。已存在的模型自动跳过。 */
    static void fetchAndPick(final Activity act, final int groupIndex) {
        final List<ModelStore.Group> groups = ModelStore.load(act);
        if (groupIndex < 0 || groupIndex >= groups.size()) return;
        final ModelStore.Group g = groups.get(groupIndex);

        final AlertDialog progress = new AlertDialog.Builder(act)
                .setTitle(MainHook.T("正在拉取模型列表...", "Fetching models..."))
                .setMessage(g.base)
                .setCancelable(false)
                .create();
        progress.show();

        MainHook.SbExecutorsBg(new Runnable() {
            @Override public void run() {
                final ModelListFetcher.Result res = new ModelListFetcher(g.base, g.key).fetch();
                MAIN.post(new Runnable() { @Override public void run() {
                    try { progress.dismiss(); } catch (Throwable ignored) {}
                    try {
                        if (!res.isOk()) {
                            new AlertDialog.Builder(act)
                                    .setTitle(MainHook.T("拉取失败", "Fetch failed"))
                                    .setMessage(res.error)
                                    .setPositiveButton(MainHook.T("好", "OK"), null)
                                    .show();
                            return;
                        }
                        // 过滤掉已添加过的
                        final List<String> fresh = new ArrayList<String>();
                        for (ModelListFetcher.Model m : res.models) {
                            if (!g.models.contains(m.id)) fresh.add(m.id);
                        }
                        if (fresh.isEmpty()) {
                            toast(act, MainHook.T("没有新模型（全部已添加）",
                                    "No new models (all already added)"));
                            return;
                        }
                        final String[] names = fresh.toArray(new String[0]);
                        final boolean[] checked = new boolean[names.length];
                        // 默认全不选 —— 列表可能很长，全选容易误加
                        new AlertDialog.Builder(act)
                                .setTitle(MainHook.T("选择要添加的模型（可多选）",
                                        "Select models to add (multi-select)"))
                                .setMultiChoiceItems(names, checked,
                                        new DialogInterface.OnMultiChoiceClickListener() {
                                    @Override public void onClick(DialogInterface d, int i, boolean isChecked) {
                                        checked[i] = isChecked;
                                    }
                                })
                                .setPositiveButton(MainHook.T("添加", "Add"),
                                        new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        List<ModelStore.Group> cur = ModelStore.load(act);
                                        if (groupIndex >= cur.size()) return;
                                        ModelStore.Group target = cur.get(groupIndex);
                                        int added = 0;
                                        for (int i = 0; i < names.length; i++) {
                                            if (checked[i] && !target.models.contains(names[i])) {
                                                target.models.add(names[i]);
                                                added++;
                                            }
                                        }
                                        ModelStore.save(act, cur);
                                        // 首次添加时自动选为当前
                                        if (ModelStore.getCurrent(act).isEmpty() && added > 0) {
                                            ModelStore.setCurrent(act, target.models.get(0));
                                        }
                                        toast(act, MainHook.T("已添加 ", "Added ") + added
                                                + MainHook.T(" 个模型", " models"));
                                        show(act);
                                    }
                                })
                                .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                                .show();
                    } catch (Throwable t) {
                        MainModule.logMsg("[SBPlus] fetchAndPick dialog error: " + t);
                    }
                }});
            }
        });
    }

    /** 手动输入单个模型名。 */
    static void manualInput(final Activity act, final int groupIndex) {
        try {
            final EditText et = new EditText(act);
            et.setHint("deepseek-chat");
            et.setPadding(dp(act, 18), dp(act, 12), dp(act, 18), dp(act, 12));

            final AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("输入模型名", "Enter model name"))
                    .setView(et)
                    .setPositiveButton(MainHook.T("添加", "Add"), null)
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .create();
            dlg.show();

            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(act, MainHook.T("请输入模型名", "Enter a model name"));
                        return;
                    }
                    List<ModelStore.Group> cur = ModelStore.load(act);
                    if (groupIndex >= cur.size()) return;
                    ModelStore.Group target = cur.get(groupIndex);
                    if (target.models.contains(name)) {
                        toast(act, MainHook.T("该模型已存在", "Model already exists"));
                        return;
                    }
                    target.models.add(name);
                    ModelStore.save(act, cur);
                    if (ModelStore.getCurrent(act).isEmpty()) ModelStore.setCurrent(act, name);
                    dlg.dismiss();
                    toast(act, MainHook.T("已添加", "Added"));
                    show(act);
                }
            });
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] manualInput error: " + t);
        }
    }

    // ==================== 删组 / 改名 ====================

    static void confirmDeleteGroup(final Activity act, final int groupIndex) {
        try {
            final List<ModelStore.Group> groups = ModelStore.load(act);
            if (groupIndex < 0 || groupIndex >= groups.size()) return;
            final ModelStore.Group g = groups.get(groupIndex);
            String name = g.name.isEmpty() ? hostOf(g.base) : g.name;

            new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("删除分组", "Delete group"))
                    .setMessage(MainHook.T("将删除「", "Delete \"") + name
                            + MainHook.T("」及其下的 ", "\" and its ")
                            + g.models.size() + MainHook.T(" 个模型。\n\n此操作不可撤销。",
                                    " models.\n\nThis cannot be undone."))
                    .setPositiveButton(MainHook.T("删除", "Delete"),
                            new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            List<ModelStore.Group> cur = ModelStore.load(act);
                            if (groupIndex < cur.size()) {
                                ModelStore.Group removed = cur.remove(groupIndex);
                                // 若当前选中的模型属于被删的组, 清空选中
                                String c = ModelStore.getCurrent(act);
                                if (removed.models.contains(c)) ModelStore.setCurrent(act, "");
                                ModelStore.save(act, cur);
                            }
                            toast(act, MainHook.T("已删除", "Deleted"));
                            show(act);
                        }
                    })
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .show();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] confirmDeleteGroup error: " + t);
        }
    }

    static void renameGroup(final Activity act, final int groupIndex) {
        try {
            final List<ModelStore.Group> groups = ModelStore.load(act);
            if (groupIndex < 0 || groupIndex >= groups.size()) return;
            final ModelStore.Group g = groups.get(groupIndex);

            final EditText et = new EditText(act);
            et.setText(g.name.isEmpty() ? hostOf(g.base) : g.name);
            et.setPadding(dp(act, 18), dp(act, 12), dp(act, 18), dp(act, 12));

            final AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(MainHook.T("分组改名", "Rename group"))
                    .setView(et)
                    .setPositiveButton(MainHook.T("保存", "Save"), null)
                    .setNegativeButton(MainHook.T("取消", "Cancel"), null)
                    .create();
            dlg.show();

            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) n = hostOf(g.base);
                    List<ModelStore.Group> cur = ModelStore.load(act);
                    if (groupIndex < cur.size()) {
                        cur.get(groupIndex).name = n;
                        ModelStore.save(act, cur);
                    }
                    dlg.dismiss();
                    show(act);
                }
            });
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] renameGroup error: " + t);
        }
    }

    // ==================== 小工具 ====================

    static String hostOf(String url) {
        try {
            if (url == null) return "";
            String s = url;
            int i = s.indexOf("://");
            if (i >= 0) s = s.substring(i + 3);
            int j = s.indexOf('/');
            if (j >= 0) s = s.substring(0, j);
            return s;
        } catch (Throwable t) {
            return url == null ? "" : url;
        }
    }

    private static TextView label(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setPadding(0, dp(c, 10), 0, dp(c, 2));
        return tv;
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
