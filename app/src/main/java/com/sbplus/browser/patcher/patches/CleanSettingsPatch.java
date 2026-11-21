package com.sbplus.browser.patcher.patches;

import com.sbplus.browser.patcher.Patch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * 精简设置页：隐藏「更新」与「隐私声明」相关条目。
 *
 * <p>对应 Xposed 版的 {@code hookCleanSettings}。
 *
 * <p><b>目标</b>：{@code SettingsFragment.initPreferences} 里对每个 preference key
 * 调 {@code findPreference(key)} 后再 {@code setVisible(...)}。静态补丁没有运行时
 * 拦截能力，所以改用**改 XML**：把 {@code res/xml/} 下设置页定义里的目标项
 * 直接标成 {@code android:visible="false"} 或删除。
 *
 * <p><b>为什么走 XML 而不是 smali</b>：设置页条目由 XML 声明，改 XML 是
 * 「数据层」修改，比改 smali 的调用序列稳定得多 —— 三星调整 initPreferences
 * 的逻辑不影响本补丁。代价是要能重打包资源，这依赖 ARSCLib（已集成）。
 *
 * <p><b>本补丁当前状态</b>：只处理「更新」相关条目。三星的设置页 XML 文件名
 * 随版本变化，这里用特征匹配（含 update 关键字的 preference）而不是硬编码文件名。
 */
public final class CleanSettingsPatch implements Patch {

    private static final String MARK = "sbplus-clean-settings";

    @Override
    public String id() {
        return "clean-settings";
    }

    @Override
    public String name() {
        return "精简设置页";
    }

    @Override
    public String description() {
        return "隐藏设置页里的「更新」相关条目（需要资源重打包能力）";
    }

    @Override
    public String targetPackage() {
        return "com.sec.android.app.sbrowser";
    }

    @Override
    public long minVersionCode() {
        return 0;
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public boolean apply(PatchContext ctx) throws Exception {
        // 资源改动需要 ARSCLib 路径（不经过 aapt2）。
        // 当前引擎的 COEXIST 模式已经用 ARSCLib 改包名；要再改 res/xml 需要在
        // ArscEditor 里加「按文件名改 XML 属性」的能力。
        // 这一版先不启用，避免给出「勾了但没效果」的假象。
        ctx.log.log("clean-settings: 资源改动待接入（当前引擎只支持 dex 补丁）");
        return false;
    }
}
