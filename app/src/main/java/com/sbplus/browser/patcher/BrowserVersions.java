package com.sbplus.browser.patcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器版本适配表。
 *
 * <p><b>为什么需要它</b>：注入的运行时用 hook 拦截浏览器的方法（类名、方法名、
 * 签名）。这些在浏览器版本之间会变，同一份运行时在不同版本上可能挂不到目标，
 * 或者挂到错的地方。所以「选 APK」之前要先确定目标版本。
 *
 * <p><b>2026-11-19 调整：从「自动识别」改为「手动下拉」</b>
 * <ul>
 *   <li>原实现是选中 APK 后自动比对版本表，命中显示「已验证」，未命中显示
 *       「未验证」但仍允许继续 —— 用户无法主动指定「我要打哪个版本」，
 *       也没法在拿到 APK 之前就知道支持范围。</li>
 *   <li>现在改成：下拉里只列<b>支持打包</b>的版本（{@link #packable}），
 *       用户先选定版本再选 APK。选中的版本决定用哪套 hook 定位参数。</li>
 * </ul>
 *
 * <p><b>「支持打包」的判定</b>：{@code verified == true} —— 即该版本经过真机验证，
 * 注入后能启动且功能正常。未验证的版本不出现在下拉里，
 * 避免用户产出一个装不上 / 一开就崩的包。
 */
public final class BrowserVersions {

    private BrowserVersions() {
    }

    /** 一个已知的浏览器版本。 */
    public static final class Version {
        public final String name;
        public final long versionCode;
        /** 是否经过实测验证（决定是否出现在「支持打包」下拉里）。 */
        public final boolean verified;

        Version(String name, long versionCode, boolean verified) {
            this.name = name;
            this.versionCode = versionCode;
            this.verified = verified;
        }
    }

    /** 正式版包名。 */
    public static final String PKG_STABLE = "com.sec.android.app.sbrowser";
    /** Beta 版包名。 */
    public static final String PKG_BETA = "com.sec.android.app.sbrowser.beta";

    private static final List<Version> STABLE = new ArrayList<Version>();
    private static final List<Version> BETA = new ArrayList<Version>();

    static {
        // 正式版（按版本号降序，新的在前）
        //
        // verified 的语义：**打补丁器这条链路**在该版本上端到端验证过
        // （注入运行时 → 改 manifest → 重打包 → 签名 → 安装 → 启动 → 功能生效）。
        //
        // 2026-11-20 修正：此前把 4 个版本都标成 verified=true，那是从 LSPosed 版的
        // 版本表继承来的 —— 它证明的是「MainHook 的 hook 点在该版本上能定位到」，
        // **不证明**打补丁器能在这个版本上产出可用的包。两者不是一回事：
        //   · LSPosed 版：框架注入 dex，hook 用 libxposed 的 API
        //   · 补丁版：自己注入 dex，hook 用 Pine 的 inline hook
        // Pine 的语义与 libxposed 有差异（入口点、参数回写、异常传播），
        // 同一份 hook 代码在别的版本上可能挂不上或挂错。
        // 所以只有真机跑通过的 31.0.2.39 才标 verified=true。
        STABLE.add(new Version("31.0.2.39", 1310239503L, true));
        // 以下三个版本 hook 点已知可用（LSPosed 版验证过），但补丁器未验证。
        STABLE.add(new Version("31.0.0.64", 1310000064L, false));
        STABLE.add(new Version("30.1.0.67", 1301000067L, false));
        STABLE.add(new Version("30.0.0.67", 1300000067L, false));
        // Beta
        BETA.add(new Version("30.0.0.67", 1300000067L, false));
    }

    /** 取某个包名下的已知版本列表。 */
    public static List<Version> known(String packageName) {
        if (PKG_BETA.equals(packageName)) return BETA;
        return STABLE;
    }

    /**
     * 取某个包名下<b>支持打包</b>的版本（即 verified == true）。
     *
     * <p>UI 的下拉只列这些 —— 未验证版本不出现在下拉里，避免用户选到会产出坏包的版本。
     */
    public static List<Version> packable(String packageName) {
        List<Version> out = new ArrayList<Version>();
        for (Version v : known(packageName)) {
            if (v.verified) out.add(v);
        }
        return out;
    }

    /**
     * 判断一个 APK 的版本是否在支持列表里。
     *
     * @return 命中的 Version；未命中返回 null
     */
    public static Version match(String packageName, String versionName) {
        if (versionName == null) return null;
        for (Version v : known(packageName)) {
            if (v.name.equals(versionName)) return v;
        }
        return null;
    }

    /** 按 versionCode 找版本（用于「下拉选中版本」与「APK 实际版本」的比对）。 */
    public static Version byVersionCode(String packageName, long versionCode) {
        for (Version v : known(packageName)) {
            if (v.versionCode == versionCode) return v;
        }
        return null;
    }

    /** 生成给 UI 显示的「支持版本」摘要。 */
    public static String supportedSummary(String packageName) {
        StringBuilder sb = new StringBuilder();
        for (Version v : packable(packageName)) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(v.name);
        }
        return sb.toString();
    }

    /** 判断包名是否是本模块支持的三星浏览器。 */
    public static boolean isSupportedPackage(String packageName) {
        return PKG_STABLE.equals(packageName) || PKG_BETA.equals(packageName);
    }
}
