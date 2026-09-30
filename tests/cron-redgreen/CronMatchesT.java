package com.sbplus.browser;

/**
 * CronUtils.cronMatches 的日/周语义与段数校验测试(2026-10-04 修复)。
 *
 * 修复前的行为(RED):
 *   - 日/周无条件 AND: "0 0 13 * 5" 只在"13 号且是周五"才触发,
 *     而标准 Vixie cron 是 OR(13 号**或**周五) → 一年可能一次都不触发
 *   - 段数只判 < 5: 6 段式(含秒) "0 0 12 * * *" 前 5 段被当成"分 时 日 月 周",
 *     实际在 00:00 触发而非 12:00 → 字段整体错位且用户无从察觉
 *
 * 修复后(GREEN): 下表全部通过。
 */
public class CronMatchesT {

    static int failures = 0;

    /** 构造一个指定"年-月-日 时:分"的 Calendar(月份按 1-12 传)。 */
    static java.util.Calendar cal(int y, int mon, int d, int h, int mi) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(y, mon - 1, d, h, mi, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c;
    }

    static void check(String cron, java.util.Calendar c, boolean want, String desc) {
        boolean got;
        try {
            got = CronUtils.cronMatches(cron, c);
        } catch (Throwable t) {
            System.out.println("FAIL [threw] " + desc + " cron=" + cron + " -> " + t);
            failures++;
            return;
        }
        boolean ok = (got == want);
        System.out.println((ok ? "pass" : "FAIL") + " " + desc
                + " cron=\"" + cron + "\" got=" + got + (ok ? "" : " want=" + want));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // 2026-10-13 是周二; 2026-10-16 是周五; 2026-10-14 是周三
        // ---- 日/周 OR 语义(标准 Vixie cron) ----
        // "13 号 或 周五": 13 号(周二)应命中
        check("0 0 13 * 5", cal(2026, 10, 13, 0, 0), true,
                "day/week both restricted -> OR (13th matches)");
        // 同一条 cron: 16 号(周五)也应命中
        check("0 0 13 * 5", cal(2026, 10, 16, 0, 0), true,
                "day/week both restricted -> OR (Friday matches)");
        // 同一条 cron: 14 号(周三)不应命中
        check("0 0 13 * 5", cal(2026, 10, 14, 0, 0), false,
                "day/week OR: neither 13th nor Friday -> no match");
        // 只有"日"受限、周为 * → 只看日(AND 的等价情形)
        check("0 0 13 * *", cal(2026, 10, 13, 0, 0), true, "only day restricted -> match 13th");
        check("0 0 13 * *", cal(2026, 10, 16, 0, 0), false, "only day restricted -> 16th no match");
        // 只有"周"受限、日为 * → 只看周
        check("0 0 * * 5", cal(2026, 10, 16, 0, 0), true, "only dow restricted -> match Friday");
        check("0 0 * * 5", cal(2026, 10, 13, 0, 0), false, "only dow restricted -> Tuesday no match");
        // 两者都不受限 → 每天
        check("0 0 * * *", cal(2026, 10, 14, 0, 0), true, "both unrestricted -> daily");

        // ---- 段数必须恰为 5 ----
        check("0 0 12 * * *", cal(2026, 10, 14, 12, 0), false,
                "6-field (with seconds) must NOT match (was: misaligned)");
        check("0 0 12 * * *", cal(2026, 10, 14, 0, 0), false,
                "6-field must not match even at the misaligned time");
        check("0 0 12 *", cal(2026, 10, 14, 12, 0), false, "4-field must not match");

        // ---- 正常 5 段式回归 ----
        check("30 12 * * *", cal(2026, 10, 14, 12, 30), true, "normal daily at 12:30");
        check("30 12 * * *", cal(2026, 10, 14, 12, 31), false, "normal daily wrong minute");
        check("0 0 1 1 *", cal(2026, 1, 1, 0, 0), true, "yearly Jan 1 00:00");

        // ---- once 语法仍可用(段级替换,与段数校验不冲突) ----
        // once 是**段级**语法: 出现在 5 段表达式内,该段被替换为 *。
        // 注意段数校验在替换之前执行,故 once 段本身不影响 5 段判定。
        check("* * * * once", cal(2026, 10, 14, 0, 0), true,
                "once as a field -> replaced by *, still 5 fields -> match");
        check("30 12 * * once", cal(2026, 10, 14, 12, 30), true,
                "once in dow field with normal others -> match");
        // 单独的 "once"(单 token)不是合法 cron → 不匹配
        check("once", cal(2026, 10, 14, 0, 0), false,
                "bare 'once' is a single token, not 5 fields -> no match");

        if (failures > 0) {
            System.out.println("FAILED: " + failures + " case(s)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }
}
