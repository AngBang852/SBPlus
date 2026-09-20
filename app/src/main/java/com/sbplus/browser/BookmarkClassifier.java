package com.sbplus.browser;

import java.util.*;
import java.util.regex.*;

/**
 * BookmarkClassifier —— 书签分类的提示词组装与 AI 回包解析（纯逻辑，无 Android 依赖）。
 *
 * <p>不碰数据库、不碰网络，只负责把书签整理成载荷、把回包解析成结果。
 *
 * <h3>两阶段分类（解决跨批类目分裂）</h3>
 * <pre>
 *   阶段一  planCategories()  —— 先发全部书签的「样本」，让 AI 定一套类目体系
 *   阶段二  classifyBatch()   —— 再分批把每一条套进这套类目里
 * </pre>
 * 不分两阶段的话，第 1 批可能分出「前端开发」，第 2 批分出「编程」，
 * 同义类目会分裂成好几个文件夹。
 *
 * <h3>脱敏边界</h3>
 * 发给 AI 的只有<b>域名</b>和标题，完整 URL 不出本机。本地数据库里
 * 的 URL 一个字符都不会被改动 —— 落库只改 PARENT。
 *
 * <h3>一致性校验</h3>
 * {@link Verification} 逐条比对「发出去的」与「回来的」，数量不符、
 * 序号丢失、类目非法都会记录下来，由调用方决定如何处理。
 */
public final class BookmarkClassifier {

    // ==================== 数据模型 ====================

    /** 待分类的一条书签。 */
    public static final class Item {
        public final int index;          // 稳定序号，用于与 AI 回包对齐
        public final String title;
        public final String url;
        public final String host;        // 预解析好的域名（脱敏后）

        public Item(int index, String title, String url) {
            this.index = index;
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.host = hostOf(this.url);
        }

        /**
         * 按开关决定发给 AI 的地址字段。
         *
         * <p>关（默认）：只给域名 —— 查询串和路径不出本机。
         * 开：给完整 URL，分类更准，但分享令牌/提取码会一并离开本机。
         */
        String addr(boolean fullUrl) {
            if (!fullUrl) return host;
            return truncate(url, 120);
        }
    }

    /** 一条分类结果。 */
    public static final class Result {
        public final int index;
        public String category;          // 大类；null 表示未能归类（留原位）
        public String sub;               // 子类；null / 空 = 直接放在大类下
        public String reason;            // AI 给的理由（可选）

        public Result(int index) { this.index = index; }

        /**
         * 用于建文件夹的完整路径；未分类返回 null。
         *
         * <p>「未分类」必须返回 null 而不是字符串 —— 否则落库阶段会建出一个
         * 真的叫「未分类」的文件夹，把判断不出的书签全塞进去。原设计是
         * 让它们留在原位。
         */
        public String pathKey() {
            if (category == null || category.isEmpty()) return null;
            if (UNCLASSIFIED.equals(category)) return null;
            if (sub == null || sub.isEmpty()) return category;
            if (UNCLASSIFIED.equals(sub)) return category;
            return category + "/" + sub;
        }
    }

    /** 两级类目体系里的一项。 */
    public static final class Category {
        public final String name;
        public final List<String> subs = new ArrayList<String>();
        Category(String name) { this.name = name; }
    }

    /** 一致性校验报告。 */
    public static final class Verification {
        public int sent;                 // 发出去的条数
        public int returned;             // 回来的合法条数
        public int missing;              // 发出去但没回来的（保留原状）
        public int extra;                // 回来了但对不上的序号（丢弃）
        public int invalidCategory;      // 类目非法被丢弃的
        /** 体系外的类目（AI 在阶段二新扩的大类）。不是错误，只是要知道。 */
        public int offTaxonomy;
        public final List<Integer> missingIndices = new ArrayList<Integer>();

        /** 是否完全一致。扩体系不算「不一致」—— 样本定不准，扩是正常的。 */
        public boolean isClean() {
            return missing == 0 && extra == 0 && invalidCategory == 0;
        }

        /** 给主人看的一句话说明；干净时返回空串。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (missing > 0) {
                sb.append("有 ").append(missing).append(" 条未能分类，已保留原位");
            }
            if (extra > 0) {
                if (sb.length() > 0) sb.append("；");
                sb.append("有 ").append(extra).append(" 条返回的序号对不上，已忽略");
            }
            if (invalidCategory > 0) {
                if (sb.length() > 0) sb.append("；");
                sb.append("有 ").append(invalidCategory).append(" 条类目异常，已保留原位");
            }
            return sb.toString();
        }
    }

    /** 类目名长度上限 —— 太长说明 AI 在写句子而不是起名。 */
    private static final int MAX_CATEGORY_LEN = 16;

    /** 顶层大类数量区间。 */
    private static final int MIN_CATEGORIES = 4;
    private static final int MAX_CATEGORIES = 12;

    /** 每个大类下的子类数量上限。 */
    private static final int MAX_SUBS = 10;

    /** AI 用它表示「判断不出」；这类书签保留原位，不建文件夹。 */
    public static final String UNCLASSIFIED = "未分类";

    private BookmarkClassifier() {}

    // ==================== 阶段一：定类目体系 ====================

    /**
     * 阶段一载荷：给 AI 看一批样本，让它设计类目体系。
     *
     * @param fullUrl true 时发送完整网址，false 时只发域名
     */
    public static String buildTaxonomyPayload(List<Item> items, int maxSamples, boolean fullUrl) {
        int step = Math.max(1, items.size() / Math.max(1, maxSamples));
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        int n = 0;
        for (int i = 0; i < items.size() && n < maxSamples; i += step, n++) {
            Item it = items.get(i);
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"d\":\"").append(esc(it.addr(fullUrl)))
              .append("\",\"t\":\"").append(esc(truncate(it.title, 50))).append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 阶段一的系统提示词：要一套<b>两级</b>类目体系。
     *
     * <p>为什么要两级：一层的自由分类在几百条书签上会炸出上百个类目
     * （实测 641 条分出 178 个文件夹），那不算整理，只是把乱搬了个地方。
     * 两级把「大类少、子类细」分开约束，顶层好找，子类够准。
     */
    public static String taxonomyPrompt() {
        return "你是书签整理助手。下面是一个 JSON 数组，每项含 d(域名) 和 t(标题)，"
             + "是一批浏览器书签的样本。\n\n"
             + "请设计一套【两级】分类体系。要求：\n"
             + "1. 顶层大类 8-" + MAX_CATEGORIES + " 个，"
             + "用宽泛但明确的领域词（如「学习」「技术」「娱乐」「购物」「资源」「工作」）\n"
             + "2. 每个大类下面给 2-10 个【子类】，子类要具体、有区分度"
             + "（如「技术」下面可以有「前端开发」「后端」「数据库」「运维」）\n"
             + "3. 子类不强求：某大类内容确实单一、或样本里看不出细分时，留空即可\n"
             + "4. 仔细看标题里的具体信息 —— 如「Vue3 响应式原理」应落在"
             + "技术/前端开发，而不是笼统的「技术/编程」\n"
             + "5. 每个名字 2-8 个字，名词短语，不要句子\n"
             + "6. 子类要能覆盖大部分样本 —— 宁可多给几个子类，也不要让样本无家可归\n\n"
             + "只输出 JSON 数组，格式：\n"
             + "[{\"c\":\"学习\",\"sub\":[\"Python\",\"考研\",\"英语\"]},"
             + "{\"c\":\"资源\",\"sub\":[]}]\n"
             + "不要任何解释文字，不要 markdown 代码块。";
    }

    /**
     * 解析阶段一的两级体系。
     *
     * <p><b>关键决定</b>：超过数量上限时<b>截断而不是全丢</b>。
     * 旧实现一旦 AI 给出 37 个类目就整批丢弃，结果退化成无约束的自由分类，
     * 反而炸出 178 个文件夹 —— 比接受 25 个类目糟糕得多。
     *
     * @return 两级体系；空列表表示解析失败（调用方退回自由分类）
     */
    public static List<Category> parseTaxonomy(String raw) {
        List<Category> out = new ArrayList<Category>();
        if (raw == null) return out;
        String json = extractJsonArray(raw);
        if (json == null) return out;

        Matcher m = Pattern.compile(
                "\\{\\s*\"c\"\\s*:\\s*\"([^\"]{1," + MAX_CATEGORY_LEN + "})\""
                + "\\s*(?:,\\s*\"sub\"\\s*:\\s*\\[(.*?)\\])?\\s*\\}",
                Pattern.DOTALL).matcher(json);
        while (m.find()) {
            String name = m.group(1).trim();
            if (name.isEmpty()) continue;
            // 同一大类重复出现时合并子类，而不是新建一个
            Category hit = null;
            for (Category c : out) if (c.name.equals(name)) { hit = c; break; }
            if (hit == null) {
                if (out.size() >= MAX_CATEGORIES) break;   // 截断，不丢全部
                hit = new Category(name);
                out.add(hit);
            }
            String subBlock = m.group(2);
            if (subBlock != null) {
                Matcher sm = Pattern.compile("\"([^\"]{1," + MAX_CATEGORY_LEN + "})\"")
                        .matcher(subBlock);
                while (sm.find()) {
                    String s = sm.group(1).trim();
                    if (s.isEmpty() || s.equals(name)) continue;
                    if (hit.subs.size() >= MAX_SUBS) break;
                    if (!hit.subs.contains(s)) hit.subs.add(s);
                }
            }
        }

        if (out.size() < MIN_CATEGORIES) {
            MainModule.logMsg("[SBPlus] taxonomy too few (" + out.size() + "), ignored");
            return new ArrayList<Category>();
        }
        return out;
    }

    /**
     * 把 AI 给的一条分类收进类目体系里。
     *
     * <p><b>这是防重复的关键一步。</b>实测 AI 会在某几批里返回单层的
     * {@code {c:"影音资源"}}，而在别的批里返回双层的
     * {@code {c:"资源", s:"影音资源"}} —— 同一次整理就建出了两个「影音资源」
     * 文件夹，一个在根下、一个在「资源」下。
     *
     * <h3>为什么不直接把体系外的判为未分类</h3>
     * 那样太狠：阶段一的体系是按<b>样本</b>定的，未必覆盖全部书签。实测
     * 649 条书签只定出 6 个大类，阶段二必然有一批书签落不进去，全判未分类
     * 会有上百条留在原位 —— 比让它扩一个子类糟糕得多。
     *
     * <h3>所以规则是「能归就归，要扩就扩到对的层级」</h3>
     * <ol>
     *   <li>大类在体系里、子类也在该大类下 → 原样保留</li>
     *   <li>大类在体系里、没给子类 → 大类下直接放</li>
     *   <li>名字是某个大类的<b>子类</b> → 归到该大类 + 该子类（防根级重复）</li>
     *   <li>名字和某个大类<b>同义</b>（如「影音」vs「影视资源」）→ 归到那个大类</li>
     *   <li>都不匹配 → 允许<b>新建大类</b>，但会被记录，好让主人知道体系被扩了</li>
     * </ol>
     *
     * @param taxonomy 阶段一定下的体系；为空时不做归一（自由分类模式）
     * @return true 表示这条是新扩出来的（不在原体系里）
     */
    static boolean normalize(Result r, List<Category> taxonomy) {
        if (r == null || r.category == null || r.category.isEmpty()) return false;
        if (taxonomy == null || taxonomy.isEmpty()) return false;

        String c = r.category.trim();
        String s = r.sub == null ? "" : r.sub.trim();

        // ① / ② 大类合法
        Category top = findTop(taxonomy, c);
        if (top != null) {
            if (s.isEmpty()) return false;
            if (top.subs.contains(s)) return false;
            // 子类属于别的大类 -> 改挂到那边
            for (Category cat : taxonomy) {
                if (cat.subs.contains(s)) {
                    r.category = cat.name;
                    r.sub = s;
                    return false;
                }
            }
            r.sub = "";                                   // 陌生子类，退回大类下
            return false;
        }

        // ③ 名字是某个大类的子类 → 提升（这就是防根级重复的那条）
        for (Category cat : taxonomy) {
            if (cat.subs.contains(c)) {
                r.category = cat.name;
                r.sub = c;
                return false;
            }
        }

        // ④ 同义/近义匹配：先找子类，再找大类
        //    顺序很重要 —— 优先塞进子类，符合「大类收紧、子类放开」的原则
        String[] subHit = findSimilarSub(taxonomy, c);
        if (subHit != null) {
            r.category = subHit[0];
            r.sub = subHit[1];
            MainModule.logMsg("[SBPlus] near-miss -> " + subHit[0] + "/" + subHit[1]
                    + " (was '" + c + "')");
            return false;
        }
        Category near = findSimilarTop(taxonomy, c);
        if (near != null) {
            r.category = near.name;
            // 子类也顺手做一次近义匹配
            if (!s.isEmpty()) {
                String sHit = findSimilarSubIn(near, s);
                r.sub = sHit != null ? sHit : "";
            } else {
                r.sub = "";
            }
            MainModule.logMsg("[SBPlus] near-miss top -> " + r.category
                    + " (was '" + c + "')");
            return false;
        }

        // ⑤ 允许扩，但有闸门：一次整理最多新扩 MAX_NEW_TOPS 个大类。
        //    超过之后挂到内容最杂的那个大类下 —— 宁可归类粗糙，也不要
        //    根目录被一堆一次性的新大类撑散。
        if (!newTops.contains(c) && newTops.size() >= MAX_NEW_TOPS) {
            attachToNearestTop(r, taxonomy, c, s);
            return false;
        }
        newTops.add(c);
        MainModule.logMsg("[SBPlus] taxonomy extended with new top: '" + c
                + "' (" + newTops.size() + "/" + MAX_NEW_TOPS + ")");
        return true;
    }

    /**
     * 在全部子类里找名字相近的，返回 {@code [大类, 子类]}。
     *
     * <p>这是「尽量用子类」的落地点：AI 报上来一个体系外的大类，
     * 如果它和某个子类名字接近，说明 AI 只是没按两级结构回答，
     * 内容本身是有归属的。
     */
    private static String[] findSimilarSub(List<Category> cats, String name) {
        if (name.length() < 2) return null;
        for (Category c : cats) {
            String hit = findSimilarSubIn(c, name);
            if (hit != null) return new String[]{ c.name, hit };
        }
        return null;
    }

    /** 在某个大类的子类里找名字相近的。 */
    private static String findSimilarSubIn(Category cat, String name) {
        if (name.length() < 2) return null;
        for (String sub : cat.subs) {
            if (sub.equals(name)) return sub;
            if (sub.length() >= 2 && name.length() >= 2
                    && (sub.contains(name) || name.contains(sub))) {
                return sub;
            }
        }
        return null;
    }

    /**
     * 限制一次整理最多新扩几个大类。
     *
     * <p>提示词说了「最多 1-2 个」，但 AI 不一定听。这里是硬闸门：
     * 超过之后体系外的类目会被挂到最近的大类下，而不是继续新建。
     */
    private static final int MAX_NEW_TOPS = 3;

    /** 本次运行新扩出来的大类名（按出现顺序）。 */
    private static final Set<String> newTops = new LinkedHashSet<String>();

    /** 开始一次新的整理前调用，清空扩体系记录。 */
    public static void resetExtensions() {
        newTops.clear();
    }

    /** 本次运行扩出来的大类。 */
    public static List<String> extendedTops() {
        return new ArrayList<String>(newTops);
    }

    /** 把扩出来的大类挂到最近的大类下（闸门触发时用）。 */
    private static void attachToNearestTop(Result r, List<Category> taxonomy, String c,
                                           String s) {
        // 挑子类最多的那个大类 —— 通常也是内容最杂、最容得下的
        Category best = taxonomy.get(0);
        for (Category cat : taxonomy) {
            if (cat.subs.size() > best.subs.size()) best = cat;
        }
        r.category = best.name;
        String hit = findSimilarSubIn(best, c);
        r.sub = hit != null ? hit : (s.isEmpty() ? "" : s);
        MainModule.logMsg("[SBPlus] new-top quota full, '" + c + "' attached to "
                + best.name + "/" + r.sub);
    }

    /** 精确找大类。 */
    private static Category findTop(List<Category> cats, String name) {
        for (Category c : cats) if (c.name.equals(name)) return c;
        return null;
    }

    /**
     * 找「意思相近」的大类。
     *
     * <p>用字面包含而不是语义 —— 这里只要挡住「影音」/「影视资源」这种
     * 一眼能看出重叠的情况。真正的同义判断交给 AI，此处宁缺毋滥：
     * 判错了会把不相干的类目合并，比多建一个文件夹更糟。
     */
    private static Category findSimilarTop(List<Category> cats, String name) {
        if (name.length() < 2) return null;
        for (Category c : cats) {
            if (c.name.equals(name)) return c;
            // 一个包含另一个，且短的那个至少 2 字
            String a = c.name, b = name;
            if (a.length() >= 2 && b.length() >= 2
                    && (a.contains(b) || b.contains(a))) {
                // 取字数更接近的那个，（「技术」vs「技术文档」不算同义）
                if (Math.abs(a.length() - b.length()) <= 2) return c;
            }
        }
        return null;
    }

    /** 把两级体系压成给阶段二看的文本。 */
    static String taxonomyText(List<Category> cats) {
        StringBuilder sb = new StringBuilder();
        for (Category c : cats) {
            sb.append("- ").append(c.name);
            if (!c.subs.isEmpty()) {
                sb.append(" → ");
                for (int i = 0; i < c.subs.size(); i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(c.subs.get(i));
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 阶段二：套用类目 ====================

    /**
     * 阶段二载荷：这批书签的 i/d/t。
     *
     * @param fullUrl true 时发送完整网址，false 时只发域名
     */
    public static String buildBatchPayload(List<Item> items, List<Integer> indices,
                                           boolean fullUrl) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Integer ix : indices) {
            Item it = find(items, ix);
            if (it == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"i\":").append(it.index)
              .append(",\"d\":\"").append(esc(it.addr(fullUrl)))
              .append("\",\"t\":\"").append(esc(truncate(it.title, 60))).append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 阶段二的系统提示词。
     *
     * @param taxonomy 阶段一定好的两级体系；为空时退化为「自由命名」
     */
    public static String batchPrompt(List<Category> taxonomy) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是书签分类助手。输入是一个 JSON 数组，每项含 i(序号)、d(域名)、t(标题)。\n");
        sb.append("请为每一项分配分类。\n\n");

        if (taxonomy != null && !taxonomy.isEmpty()) {
            sb.append("已有分类体系如下（大类 → 子类）：\n");
            sb.append(taxonomyText(taxonomy));
            sb.append("\n【最重要的一条】优先用这套体系：\n");
            sb.append("· 先看这条书签能不能塞进已有的某个【子类】—— 能就塞，哪怕只有七分像\n");
            sb.append("· 塞不进子类，但能塞进某个【大类】—— 就挂在大类下，s 留空\n");
            sb.append("· 实在都不像，才自创新大类。新大类要克制，"
                    + "一整批里最多出现 1-2 个\n");
            sb.append("· 自创时先自问：这个内容真的不属于上面任何一类吗？"
                    + "往往只是名字不同，实际是同一类\n\n");
            sb.append("注意：宁可子类名略有偏差，也不要自创大类 —— "
                    + "大类多了书签结构就散了。\n\n");
        } else {
            sb.append("分类体系由你根据内容自己决定，采用两级：大类要宽泛（8-12 个），"
                    + "子类要具体（每个大类 2-10 个）。\n\n");
        }

        sb.append("每项输出两个字段：\n");
        sb.append("· c = 大类名\n");
        sb.append("· s = 子类名（可选；某项只适合按大类归类时，s 留空字符串）\n\n");
        sb.append("判断要求：\n");
        sb.append("1. 仔细看标题里的具体信息，标题往往比域名更能说明主题\n");
        sb.append("2. 同一主题的书签必须用完全相同的名字，不要一会儿「前端」一会儿「前端开发」\n");
        sb.append("3. 实在判断不出的，大类写 \"未分类\"\n");
        sb.append("\n只输出 JSON 数组，格式 [{\"i\":0,\"c\":\"技术\",\"s\":\"前端开发\"}]，"
                + "不要任何解释文字，不要 markdown 代码块。");
        return sb.toString();
    }

    /**
     * 解析阶段二回包，写入 results，并产出校验报告。
     *
     * <p>与旧版的区别：这里会<b>逐条核对</b>发出去的序号集合和回来的序号集合，
     * 多出来的、少掉的、类目非法的都记进 {@link Verification}。
     *
     * @param raw      AI 原始回包
     * @param results  全部条目的结果表（按 index）
     * @param sentIdx  本批发出去的序号
     * @param taxonomy 阶段一的体系；非空时对结果做归一，防止跨批结构漂移
     * @return 校验报告
     */
    public static Verification applyBatchResponse(String raw, Map<Integer, Result> results,
                                                  List<Integer> sentIdx,
                                                  List<Category> taxonomy) {
        Verification v = new Verification();
        v.sent = sentIdx.size();

        String json = extractJsonArray(raw);
        if (json == null) {
            // 整批作废：全部算缺失
            v.missing = sentIdx.size();
            v.missingIndices.addAll(sentIdx);
            return v;
        }

        // 解析出 (序号 -> [大类, 子类])，同时记录非法项
        Map<Integer, String[]> parsed = new LinkedHashMap<Integer, String[]>();
        Set<Integer> seen = new HashSet<Integer>();
        // s 字段可选，所以分成两个正则：先匹配带 s 的，再匹配不带 s 的
        Matcher m = Pattern.compile(
                "\\{\\s*\"i\"\\s*:\\s*(\\d+)\\s*,\\s*\"c\"\\s*:\\s*\"([^\"]*)\""
                + "\\s*(?:,\\s*\"s\"\\s*:\\s*\"([^\"]*)\")?\\s*\\}").matcher(json);
        while (m.find()) {
            int i;
            try { i = Integer.parseInt(m.group(1)); } catch (Throwable t) { continue; }
            String c = m.group(2).trim();
            String s = m.group(3) == null ? "" : m.group(3).trim();

            if (c.isEmpty() || c.length() > MAX_CATEGORY_LEN) {
                v.invalidCategory++;
                continue;
            }
            if (s.length() > MAX_CATEGORY_LEN) s = "";   // 子类过长就当没给
            if (!sentIdx.contains(i)) {
                v.extra++;            // 没发过这个序号，或不属于本批
                continue;
            }
            if (seen.contains(i)) {
                v.extra++;            // 重复返回
                continue;
            }
            seen.add(i);
            parsed.put(i, new String[]{ c, s });
        }

        // 写入结果，并找出缺失的
        for (Integer i : sentIdx) {
            String[] cs = parsed.get(i);
            if (cs == null) {
                v.missing++;
                v.missingIndices.add(i);
                continue;
            }
            Result r = results.get(i);
            if (r == null) {
                v.missing++;
                v.missingIndices.add(i);
                continue;
            }
            r.category = cs[0];
            r.sub = cs[1];
            // 归一：防跨批结构漂移（重复文件夹的根因）。
            // 返回值 true 表示体系被扩了 —— 记下来告诉主人，但不阻止。
            if (normalize(r, taxonomy)) v.offTaxonomy++;
        }
        v.returned = parsed.size();
        return v;
    }

    /** 从可能带围栏/解释文字的回包里抠出纯 JSON 数组。 */
    static String extractJsonArray(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int a = s.indexOf('[');
        int b = s.lastIndexOf(']');
        if (a < 0 || b <= a) return null;
        return s.substring(a, b + 1);
    }

    // ==================== 工具 ====================

    /** 解析域名；解析不出来时退化为去掉协议的整个字符串。 */
    static String hostOf(String url) {
        if (url == null) return "";
        try {
            String s = url.trim();
            int i = s.indexOf("://");
            if (i >= 0) s = s.substring(i + 3);
            int j = s.indexOf('/');
            if (j >= 0) s = s.substring(0, j);
            int k = s.indexOf('?');
            if (k >= 0) s = s.substring(0, k);
            int h = s.indexOf('#');
            if (h >= 0) s = s.substring(0, h);
            int at = s.indexOf('@');
            if (at >= 0) s = s.substring(at + 1);
            return s.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    private static Item find(List<Item> items, int index) {
        for (Item it : items) if (it.index == index) return it;
        return null;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
