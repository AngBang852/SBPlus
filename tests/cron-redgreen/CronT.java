package com.sbplus.browser;

/**
 * CronUtils.parseCronField 的红-绿回归测试(2026-09-20 审核修复 S3)。
 *
 * 修复前预期(RED):
 *   - 步长为 0 或负数的步长段(如 "星号斜杠0"、"5斜杠0"、"星号斜杠-1")
 *     会使步进循环永不终止 → 线程 join 超时仍存活 → FAIL
 *   - "50-70"(分钟) 越界值未截断 → 与期望 [50..59] 不符 → FAIL
 *   - "90-120" 与分钟域完全不相交 → 期望空集,实际返回 90..120 → FAIL
 * 修复后(GREEN):全部通过,且有效表达式 "星号斜杠15" 的行为不变。
 */
public class CronT {

    static int failures = 0;

    static String show(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.append(']').toString();
    }

    static boolean eq(int[] a, int[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static int[] seq(int... v) { return v; }

    static void runCase(final String field, final int min, final int max,
                        final int[] expect, final String desc) throws Exception {
        final Object[] res = new Object[1];
        final Thread t = new Thread(new Runnable() { @Override public void run() {
            try { res[0] = CronUtils.parseCronField(field, min, max); }
            catch (Throwable th) { res[0] = th; }
        }});
        t.setDaemon(true);
        t.start();
        t.join(3000);
        if (t.isAlive()) {
            System.out.println("FAIL [infinite loop] " + desc + " field=" + field);
            failures++;
            return;
        }
        if (res[0] instanceof Throwable) {
            System.out.println("FAIL [threw] " + desc + " field=" + field + " -> " + res[0]);
            failures++;
            return;
        }
        int[] got = (int[]) res[0];
        boolean ok = eq(got, expect);
        System.out.println((ok ? "pass" : "FAIL") + " " + desc + " field=" + field
                + " got=" + show(got) + (ok ? "" : " want=" + show(expect)));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        runCase("*/0",   0, 59, new int[]{},           "step=0 must not loop");
        runCase("5/0",   0, 59, new int[]{},           "step=0 from 5 must not loop");
        runCase("*/-1",  0, 59, new int[]{},           "negative step must not loop");
        runCase("50-70", 0, 59, seq(50,51,52,53,54,55,56,57,58,59), "range clamped to field max");
        runCase("90-120",0, 59, new int[]{},           "disjoint range -> empty");
        runCase("*/15",  0, 59, seq(0,15,30,45),       "valid step unchanged");
        runCase("1-5/0,10", 0, 59, seq(10),            "bad part skipped, good part kept");
        if (failures > 0) { System.out.println("FAILED: " + failures + " case(s)"); System.exit(1); }
        System.out.println("ALL PASS");
    }
}
