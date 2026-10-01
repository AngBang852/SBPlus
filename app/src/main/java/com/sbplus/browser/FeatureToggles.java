package com.sbplus.browser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 功能名 ↔ 用户开关的映射（2026-10-04 新增）。
 *
 * <p><b>为什么需要它</b>：{@code safeFeature} 用的是<b>功能标识</b>（如 {@code "region-lock"}），
 * 而设置界面的开关保存方法用的是另一套命名（如 {@code saveRegionLockEnabled}）。
 * 两者要对应起来，才能在用户关闭开关时调用
 * {@link MainHook#disableFeatureRuntime} 把该功能已注册的 hook 撤销掉
 * —— 实现"关功能无需重启浏览器"。
 *
 * <p><b>为什么是显式映射表而不是命名推导</b>：两套命名的对应关系并非机械转换
 * （{@code "region-lock"} ↔ {@code saveRegionLockEnabled} 就不是简单拼接），
 * 而且只有<b>部分</b>功能有用户开关 —— 22 个功能里大多数是常驻 hook
 * （如 {@code download-started} / {@code navigate-up}），它们没有开关，
 * 也就没有"关掉"这一说。用显式表能一眼看清"哪些功能可被用户关闭"，
 * 比推导更可靠、也更好维护。
 *
 * <p><b>新增可关闭功能时</b>：在 {@link #FEATURE_BY_TOGGLE} 里加一行即可，
 * 界面侧无需改动（开关回调统一走 {@link #onToggleChanged}）。
 */
final class FeatureToggles {

    private FeatureToggles() {}

    /**
     * 开关标识 → 功能名。
     *
     * <p>key 用"开关的语义名"（与设置界面里那几处回调一一对应），
     * value 是 {@code safeFeature} 注册时用的功能名。
     */
    private static final Map<String, String> FEATURE_BY_TOGGLE;
    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        // UA 伪装：总开关关掉后，UA 相关的 hook 就没必要继续挂着
        m.put("ua-override", "ua-override");
        // 按站点 UA（独立开关）
        m.put("ua-per-site", "ua-per-site");
        // 改区锁定
        m.put("region-lock", "region-lock");
        // 媒体嗅探
        m.put("network-sniff", "network-sniff");
        // 油猴脚本
        m.put("userscript", "userscript");
        // 油猴工具栏图标（跟随油猴开关，但独立注册）
        m.put("userscript-toolbar", "userscript-toolbar");
        // 精简设置页
        m.put("clean-settings", "clean-settings");
        // 屏蔽更新提示
        m.put("block-update", "block-update");
        // 网格菜单
        m.put("more-menu-grid", "more-menu-grid");
        // 视频背景
        m.put("video-background", "video-background");
        FEATURE_BY_TOGGLE = Collections.unmodifiableMap(m);
    }

    /** 该开关对应的功能名；无对应关系返回 null（表示这是无 hook 的纯配置项）。 */
    static String featureOf(String toggleKey) {
        if (toggleKey == null) return null;
        return FEATURE_BY_TOGGLE.get(toggleKey);
    }

    /** 全部"可被用户关闭的功能"标识（诊断/自检用）。 */
    static java.util.Set<String> allToggleableFeatures() {
        return FEATURE_BY_TOGGLE.keySet();
    }

    /**
     * 开关变化时的统一处理。
     *
     * <p><b>关闭</b>：撤销该功能已注册的 hook —— **立即生效，无需重启**。
     *
     * <p><b>打开</b>：清除"已撤销"标记（让下次启动能正常注册），但**本进程内无法恢复** ——
     * libxposed 没有"重新挂上已撤销 handle"的接口，被 unhook 的方法不能重新 hook。
     * 因此必须提示用户重启。若此前未被撤销过（正常开着的功能被再次打开），
     * 则什么都不用做、也不必提示。
     *
     * @param toggleKey 开关标识
     * @param enabled 用户设置的新值
     * @return 关闭时返回实际撤销的 hook 数；其余情况返回 -1（无需向用户报告）
     */
    static int onToggleChanged(String toggleKey, boolean enabled) {
        String feature = featureOf(toggleKey);
        if (feature == null) return -1;
        if (enabled) {
            // 打开方向分两步：
            // ① 清除"已撤销"标记 —— 让下次启动能正常注册；
            boolean wasRevoked = HookRegistry.clearRevoked(feature);
            // ② 恢复状态标记 —— 否则菜单会一直错误地显示"(失效)"。
            //    这正是主人报的"关掉再打开显示失效，重启浏览器后才正常"的根因：
            //    disableFeatureRuntime 把 sFeatureStatus 置为 false 供 featureDown 判断，
            //    而打开时原先只清了 REVOKED、没恢复它。
            MainHook.markFeatureReEnabled(feature);
            if (wasRevoked) {
                // 被 unhook 的方法在本进程内无法重新 hook（libxposed 无此接口），
                // 故必须提示重启。
                MainHook.toastOnMainPublic(MainHook.T("该功能需要重启浏览器后生效",
                        "This feature needs a browser restart to take effect"));
            }
            return -1;
        }
        int n = MainHook.disableFeatureRuntime(feature);
        if (n > 0) {
            MainHook.toastOnMainPublic(MainHook.T("已关闭，立即生效",
                    "Disabled, effective immediately"));
        }
        return n;
    }
}