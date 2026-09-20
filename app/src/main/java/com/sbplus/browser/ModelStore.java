package com.sbplus.browser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ModelStore —— 「模型」设置的数据层：分组 + 全局唯一选中。
 *
 * <p>数据结构（按主人的要求）：
 * <pre>
 *   一组 = { 名称(可改) + 接口地址 + API Key + 模型名列表 }
 *          ↑ 凭据只填一次，模型可单个加、也可拉列表批量加
 *
 *   全局唯一选中：同时只有一个「当前用的模型」
 * </pre>
 *
 * <p>两个设计决定：
 * <ul>
 *   <li><b>删除以组为单位</b> —— 删组即删该组全部模型（主人明确要求）。</li>
 *   <li><b>模型名不可改</b> —— 保持 API 原始名，避免主人改乱了分不清实际调的是哪个；
 *       辨识度交给组名。</li>
 * </ul>
 */
public final class ModelStore {

    /** 一组凭据 + 它下面的模型。 */
    public static final class Group {
        public String name = "";      // 组名，可改
        public String base = "";      // 接口地址
        public String key = "";       // API Key
        public final List<String> models = new ArrayList<String>();

        public boolean isValid() { return !base.isEmpty() && !key.isEmpty(); }
        public boolean hasModels() { return !models.isEmpty(); }
    }

    private static final String K_GROUPS = "sbplus_model_groups";
    private static final String K_CURRENT = "sbplus_model_current";
    private static final String K_FULL_URL = "sbplus_sort_full_url";

    private ModelStore() {}

    /**
     * 是否发送完整网址。
     *
     * <p>默认<b>关闭</b>：关着的时候只发域名，查询串和路径都留在本机 ——
     * 分享令牌、网盘提取码、内网地址都藏在那些位置。
     *
     * <p>打开后分类会更准（AI 能看到具体页面路径），代价是这些信息会离开本机。
     * 是否打开由主人在「模型」界面里自己决定，那里有风险提示。
     */
    public static boolean isFullUrl(Context c) {
        try {
            return BookmarkSmartSort.prefs(c).getBoolean(K_FULL_URL, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setFullUrl(Context c, boolean on) {
        try {
            BookmarkSmartSort.prefs(c).edit().putBoolean(K_FULL_URL, on).apply();
        } catch (Throwable ignored) {}
    }

    // ==================== 读写 ====================

    /** 读全部组。数据损坏时返回空表而不是抛异常 —— 设置界面不该因此打不开。 */
    public static List<Group> load(Context c) {
        List<Group> out = new ArrayList<Group>();
        try {
            String raw = BookmarkSmartSort.prefs(c).getString(K_GROUPS, "");
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Group g = new Group();
                g.name = o.optString("name", "");
                g.base = o.optString("base", "");
                g.key = o.optString("key", "");
                JSONArray ms = o.optJSONArray("models");
                if (ms != null) {
                    for (int j = 0; j < ms.length(); j++) {
                        String m = ms.optString(j, "");
                        if (!m.isEmpty() && !g.models.contains(m)) g.models.add(m);
                    }
                }
                if (!g.base.isEmpty()) out.add(g);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] ModelStore.load error: " + t);
        }
        return out;
    }

    /** 保存全部组。 */
    public static void save(Context c, List<Group> groups) {
        try {
            JSONArray arr = new JSONArray();
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("name", g.name);
                o.put("base", g.base);
                o.put("key", g.key);
                JSONArray ms = new JSONArray();
                for (String m : g.models) ms.put(m);
                o.put("models", ms);
                arr.put(o);
            }
            BookmarkSmartSort.prefs(c).edit().putString(K_GROUPS, arr.toString()).apply();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] ModelStore.save error: " + t);
        }
    }

    /** 当前选中的模型名（全局唯一）。 */
    public static String getCurrent(Context c) {
        try {
            return BookmarkSmartSort.prefs(c).getString(K_CURRENT, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setCurrent(Context c, String model) {
        try {
            BookmarkSmartSort.prefs(c).edit().putString(K_CURRENT, model == null ? "" : model).apply();
        } catch (Throwable ignored) {}
    }

    // ==================== 查询 ====================

    /** 找到某个模型所属的组（用于取它的 base/key）。 */
    public static Group groupOf(Context c, String model) {
        if (model == null || model.isEmpty()) return null;
        for (Group g : load(c)) {
            if (g.models.contains(model)) return g;
        }
        return null;
    }

    /** 是否配置齐全（有组、有选中、且选中项确实存在于某个组里）。 */
    public static boolean isConfigured(Context c) {
        String cur = getCurrent(c);
        if (cur.isEmpty()) return false;
        Group g = groupOf(c, cur);
        return g != null && g.isValid();
    }

    /** 当前生效的 base / key / model 三元组。未配置时返回 null。 */
    public static String[] currentTriple(Context c) {
        String cur = getCurrent(c);
        Group g = groupOf(c, cur);
        if (g == null || !g.isValid() || cur.isEmpty()) return null;
        return new String[]{ g.base, g.key, cur };
    }

    /** 全部模型总数（用于界面显示）。 */
    public static int totalModels(Context c) {
        int n = 0;
        for (Group g : load(c)) n += g.models.size();
        return n;
    }
}
