package com.sbplus.browser.patcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 注入到浏览器的全部功能清单（只读展示用）。
 *
 * <p><b>为什么这份清单要单独存在</b>：补丁版是把整个 SBPlus 运行时注入 APK，
 * 功能集合与 LSPosed 版<b>完全一致</b>（同一份 {@code MainHook} 代码）。
 * 用户在打补丁前需要知道「打完之后会多出什么」，而这份清单就是这个答案 ——
 * 它必须与 {@code MainHook.doHooks} 里注册的功能严格对应，不能凭印象写。
 *
 * <p><b>与 LSPosed 版的唯一差别</b>：补丁版没有「模块作用域」「热重载」
 * 「远程 SharedPreferences」这三项框架能力，它们由
 * {@code com.sbplus.browser.rt.PineXposedImpl} 用本地实现替代 ——
 * 对功能本身没有影响，只是设置项存在浏览器自己的 prefs 里。
 *
 * <p><b>维护约定</b>：{@code MainHook} 里新增一个 {@code safeFeature("xxx", ...)}
 * 就在这里加一行。{@link #ID_COUNT_EXPECTED} 会在构建期自检时提醒。
 */
final class FeatureCatalog {

    private FeatureCatalog() {
    }

    /** {@code MainHook} 里 {@code safeFeature} 的调用总数（自检基准）。 */
    static final int ID_COUNT_EXPECTED = 22;

    /** 一个功能条目。 */
    static final class Feature {
        /** 对应 {@code safeFeature} 的标识。 */
        final String id;
        /** 中文名。 */
        final String name;
        /** 一句话说明（做什么 / 有什么副作用）。 */
        final String description;
        /** 分组名。 */
        final String group;

        Feature(String id, String name, String description, String group) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.group = group;
        }
    }

    private static final List<Feature> ALL = new ArrayList<Feature>();

    static {
        // ── 下载 ──
        ALL.add(new Feature("download-pre-request", "下载接管",
                "拦截浏览器的下载请求，把 URL / Cookie / UA / 文件名交给第三方下载器"
                        + "（IDM、ADM 等）。也可改用内置下载引擎。", "下载"));
        ALL.add(new Feature("download-started", "下载事件监听",
                "在下载真正开始时补齐元数据（Referer、Content-Disposition），"
                        + "让第三方下载器能正确续传与命名。", "下载"));

        // ── 界面 ──
        ALL.add(new Feature("settings-menu", "设置页 SBPlus 入口",
                "在三星浏览器设置页里加一个「SBPlus 设置」条目，点进去是完整的模块设置界面。", "界面"));
        ALL.add(new Feature("fragment-load", "页面框架接管",
                "接管 Fragment 加载流程，为自定义主页组件（时钟、Logo、视频背景）提供挂载点。", "界面"));
        ALL.add(new Feature("inline-edit", "行内编辑",
                "让设置页里的数值项支持就地编辑，不必弹对话框。", "界面"));
        ALL.add(new Feature("back-press", "返回键增强",
                "在部分页面（如下载列表、书签编辑）把返回键改成「先收键盘 / 先退出编辑」。", "界面"));
        ALL.add(new Feature("navigate-up", "向上导航增强",
                "修正部分子页面的向上按钮行为，避免直接跳回主页。", "界面"));
        ALL.add(new Feature("radio-group", "单选组样式",
                "统一设置页单选组的间距与选中态样式。", "界面"));
        ALL.add(new Feature("more-menu-grid", "网格菜单",
                "把「更多」菜单从单列列表改成多列网格，一屏放更多项。", "界面"));
        ALL.add(new Feature("theme", "主题着色",
                "让浏览器界面跟随系统 / 自定义主题色，包括工具栏、卡片、对话框。", "界面"));
        ALL.add(new Feature("global-font", "全局字体",
                "替换浏览器界面与网页的默认字体，支持导入字体文件。", "界面"));
        ALL.add(new Feature("video-background", "视频背景",
                "在主页用视频当背景，可调节播放速度与透明度。", "界面"));
        ALL.add(new Feature("debug-localize", "调试页汉化",
                "把「调试设置」及其子页的英文标题翻译成中文。", "界面"));

        // ── 网页 ──
        ALL.add(new Feature("ua-override", "桌面版 UA",
                "把默认 User-Agent 改成桌面 Chrome，让网站返回 PC 版页面。"
                        + "副作用：部分站点会跳 PC 布局。", "网页"));
        ALL.add(new Feature("ua-per-site", "按站点 UA",
                "为不同域名分别指定 UA（桌面 / 移动 / 自定义），互不干扰。", "网页"));
        ALL.add(new Feature("userscript", "油猴脚本",
                "支持 Tampermonkey 风格的 @require / @resource / GM_* API，"
                        + "可在设置里添加脚本源与单独启停。", "网页"));
        ALL.add(new Feature("userscript-toolbar", "油猴工具栏图标",
                "在页面上显示当前生效的脚本数，点开可查看 / 授权 / 停用。", "网页"));
        ALL.add(new Feature("network-sniff", "媒体嗅探",
                "抓取页面加载的 m3u8 / mp4 / mp3 地址，可按类型分组并直接下载。", "网页"));

        // ── 精简 ──
        ALL.add(new Feature("clean-settings", "精简设置页",
                "隐藏设置页里的「更新」「隐私声明」等用不到的条目。", "精简"));
        ALL.add(new Feature("block-update", "屏蔽更新提示",
                "移除设置页的更新卡片与更新小红点，避免被引导去应用商店升级。", "精简"));
        ALL.add(new Feature("region-lock", "改区锁定",
                "锁定浏览器的区域判定，避免被识别成其它地区而限制功能。", "精简"));
        ALL.add(new Feature("region-touch-scroll", "区域触摸滚动",
                "配合改区锁定，修正锁定后触摸滚动行为异常的问题。", "精简"));
    }

    /** 全部功能（顺序即 UI 顺序）。 */
    static List<Feature> all() {
        return ALL;
    }

    /** 某个分组下的功能。 */
    static List<Feature> byGroup(String group) {
        List<Feature> out = new ArrayList<Feature>();
        for (Feature f : ALL) {
            if (f.group.equals(group)) out.add(f);
        }
        return out;
    }

    /** 全部组名（按首次出现顺序）。 */
    static List<String> groupNames() {
        List<String> order = new ArrayList<String>();
        for (Feature f : ALL) {
            if (!order.contains(f.group)) order.add(f.group);
        }
        return order;
    }

    /** 功能总数。 */
    static int count() {
        return ALL.size();
    }
}
