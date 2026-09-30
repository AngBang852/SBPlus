package com.sbplus.browser;

/**
 * StrUtils.isNewerVersion 的版本比较测试(2026-10-04 修复)。
 *
 * 修复前的行为(RED):
 *   - 无法按数字段解析时回退为 {@code !r.equals(l)}(字符串不等即"有更新"),
 *     于是本地 "2.6.0-beta" 对上远端 "2.2" 会**误报更新** —— 用户被引向更旧版本
 *
 * 修复后(GREEN): 解析失败保守返回 false(宁可漏报不误报); 并支持预发布段比较
 *   (数字部分相同时, 无后缀 > 有后缀, 符合语义版本规范)。
 *
 * 说明: 该逻辑原在 UpdateChecker 里, 因该类依赖 android.content/os 无法脱离
 * Android SDK 编译而长期无法测试, 现已迁到 StrUtils(零 Android 依赖),
 * 故本测试可直接 javac 编译运行。
 */
public class VersionT {

    static int failures = 0;

    static void check(String remote, String local, boolean want, String desc) {
        boolean got;
        try {
            got = StrUtils.isNewerVersion(remote, local);
        } catch (Throwable t) {
            System.out.println("FAIL [threw] " + desc + " -> " + t);
            failures++;
            return;
        }
        boolean ok = (got == want);
        System.out.println((ok ? "pass" : "FAIL") + " " + desc
                + " remote=" + remote + " local=" + local
                + " got=" + got + (ok ? "" : " want=" + want));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // ---- 基本数字点分比较 ----
        check("2.7.0", "2.6.0", true, "minor bump -> newer");
        check("2.6.0", "2.6.0", false, "same version -> not newer");
        check("2.5.0", "2.6.0", false, "older remote -> not newer");
        check("2.6.1", "2.6.0", true, "patch bump -> newer");
        check("3.0", "2.6.0", true, "different segment count -> newer");
        check("2.6", "2.6.0", false, "shorter but equal -> not newer");

        // ---- 前缀 v ----
        check("v2.7.0", "2.6.0", true, "v-prefixed remote");
        check("2.7.0", "v2.6.0", true, "v-prefixed local");
        check("v2.6.0", "v2.6.0", false, "both v-prefixed, equal");

        // ---- 空值 ----
        check("", "2.6.0", false, "empty remote -> not newer");
        check("2.7.0", "", true, "empty local -> newer");
        check(null, "2.6.0", false, "null remote -> not newer");
        check("2.7.0", null, true, "null local -> newer");

        // ---- 预发布段(本次修复的核心) ----
        check("2.6.0", "2.6.0-beta", true, "release > prerelease (same numeric)");
        check("2.6.0-beta", "2.6.0", false, "prerelease < release (same numeric)");
        check("2.6.1-beta", "2.6.0", true, "higher numeric wins regardless of suffix");
        check("2.6.0-rc1", "2.6.0-beta", true, "prerelease ordering by string");

        // ---- 不可解析 → 保守返回 false(修复前会误报 true) ----
        check("2.2", "2.6.0-beta", false, "unparseable local suffix must NOT report update");
        check("latest", "2.6.0", false, "non-numeric remote -> no update (was: true)");
        check("nightly", "2.6.0", false, "word tag -> no update (was: true)");

        if (failures > 0) {
            System.out.println("FAILED: " + failures + " case(s)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }
}
