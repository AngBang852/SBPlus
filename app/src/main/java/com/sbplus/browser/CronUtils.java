package com.sbplus.browser;

/**
 * 极简 cron 表达式解析与匹配。
 *
 * <p>2026-09-17 从 MainHook 抽出。仅支持 5 段式表达式
 * {@code 分 时 日 月 周},每段可用 {@code *} / 数字 / 逗号列表 / 区间 / 步长,
 * 另支持油猴脚本用的 {@code once} 与 {@code once(expr)} 写法。
 *
 * <p>不引用 MainHook 的任何静态状态,不持有状态,可在任意线程调用。
 * 匹配与推算都以调用方传入的 {@link java.util.Calendar} / 时间戳为准,
 * 本类不读系统时间。
 */
final class CronUtils {

    private CronUtils() {}

    /** 解析 cron 单段,返回其中所有取值(含边界)。字段非法时由调用方的 try 兜住。 */
    static int[] parseCronField(String field, int min, int max) {
        java.util.List<Integer> vals = new java.util.ArrayList<Integer>();
        for (String part : field.split(",")) {
            part = part.trim();
            if (part.equals("*")) {
                for (int i = min; i <= max; i++) vals.add(i);
            } else if (part.contains("/")) {
                String[] sp = part.split("/");
                int step = Integer.parseInt(sp[1]);
                // 2026-09-20 S3 修复:步长非正值会让 for(i+=step) 永不终止,在宿主
                // 进程里 CPU 打满 + ArrayList 撑爆堆(OOM)。跳过该段,保留其余合法段。
                if (step <= 0) continue;
                String range = sp[0].equals("*") ? (min + "-" + max) : sp[0];
                int rs, re;
                if (range.contains("-")) {
                    String[] rp = range.split("-");
                    rs = Integer.parseInt(rp[0]);
                    re = Integer.parseInt(rp[1]);
                } else {
                    rs = Integer.parseInt(range);
                    re = max;
                }
                // 越界截断到字段域:防止超大区间生成巨量无效值(匹配语义不变,
                // contains() 本就只在 [min,max] 内命中)。
                rs = Math.max(rs, min);
                re = Math.min(re, max);
                if (rs > re) continue;
                for (int i = rs; i <= re; i += step) vals.add(i);
            } else if (part.contains("-")) {
                String[] rp = part.split("-");
                int rs = Integer.parseInt(rp[0]);
                int re = Integer.parseInt(rp[1]);
                rs = Math.max(rs, min);
                re = Math.min(re, max);
                if (rs > re) continue;
                for (int i = rs; i <= re; i++) vals.add(i);
            } else {
                vals.add(Integer.parseInt(part));
            }
        }
        int[] arr = new int[vals.size()];
        for (int i = 0; i < vals.size(); i++) arr[i] = vals.get(i);
        return arr;
    }

    /**
     * 判断 cron 表达式是否匹配给定时间。
     *
     * <p>不足 5 段、或任一数字段解析失败时返回 {@code false}(不抛异常) ——
     * 原实现即如此,调用方依赖这个"静默不匹配"来跳过损坏的 @crontab。
     *
     * <p>2026-10-04 两处语义修复(对齐标准 Vixie cron):
     * <ul>
     *   <li><b>日/周改为 OR</b> —— 标准 cron 规定:当"日"与"周"**两者都受限**
     *       (都不是 *)时取 <b>OR</b>;只要有一个是 * 就取 AND(即只看另一个)。
     *       原实现无条件 AND,于是 {@code 0 0 13 * 5}(13 号或周五)实际只在
     *       "13 号且是周五"才触发 —— 一年可能一次都不触发。</li>
     *   <li><b>段数必须恰为 5</b> —— 原实现只判 {@code length < 5},于是用户粘入
     *       6 段式(含秒,如 {@code 0 0 12 * * *})时字段整体错位:前 5 段被当成
     *       "分 时 日 月 周",实际变成"分=0 时=0 日=12" → 在 00:00 触发而非 12:00,
     *       且用户完全无从察觉。这里改为段数不等于 5 一律不匹配(与"损坏表达式
     *       静默不匹配"的既有约定一致)。</li>
     * </ul>
     */
    static boolean cronMatches(String cron, java.util.Calendar cal) {
        try {
            String[] fields = cron.trim().split("\\s+");
            // 2026-10-04:必须**恰好** 5 段。多段(含秒的 6 段式)会让字段整体错位,
            // 静默按错位语义触发,比不触发更危险。
            if (fields.length != 5) return false;
            // 支持 once 语法:替换 once 为 * , once(expr) 为 expr
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].equals("once")) fields[i] = "*";
                else if (fields[i].startsWith("once(") && fields[i].endsWith(")")) {
                    fields[i] = fields[i].substring(5, fields[i].length() - 1);
                }
            }
            String dayField = fields[2], dowField = fields[4];
            int[] minutes = parseCronField(fields[0], 0, 59);
            int[] hours = parseCronField(fields[1], 0, 23);
            int[] days = parseCronField(fields[2], 1, 31);
            int[] months = parseCronField(fields[3], 1, 12);
            int[] dows = parseCronField(fields[4], 0, 6);
            int min = cal.get(java.util.Calendar.MINUTE);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
            int month = cal.get(java.util.Calendar.MONTH) + 1;
            int dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1; // Sunday=0
            if (dow < 0) dow = 0;
            // 分/时/月三段:与语义(必须同时满足)
            if (!contains(minutes, min) || !contains(hours, hour) || !contains(months, month)) {
                return false;
            }
            // 日/周:按标准 cron 的 OR 规则
            boolean dayRestricted = !"*".equals(dayField.trim());
            boolean dowRestricted = !"*".equals(dowField.trim());
            boolean dayHit = contains(days, day);
            boolean dowHit = contains(dows, dow);
            if (dayRestricted && dowRestricted) {
                return dayHit || dowHit;    // 两者都受限 → OR
            }
            // 只有一个受限(或都不受限) → 各自必须满足(不受限的一方恒真)
            return dayHit && dowHit;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] cronMatches error: " + t + " cron=" + cron);
            return false;
        }
    }

    /** 数组内是否含某值。 */
    static boolean contains(int[] arr, int v) {
        for (int a : arr) if (a == v) return true;
        return false;
    }

    /**
     * 计算 cron 的下次匹配时间(从 {@code now} 的下一分钟起逐分检查,
     * 最多查 7 天 = 7*24*60 分钟)。查不到返回 -1。
     *
     * <p>上限 7 天的含义:超出这个范围的表达式(例如只在某个特定月份触发)
     * 会被判为"无下次",调用方据此停止调度 —— 这是原有的取舍,未改动。
     */
    static long cronNextRun(String cron, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.add(java.util.Calendar.MINUTE, 1);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        for (int i = 0; i < 7 * 24 * 60; i++) {
            if (cronMatches(cron, cal)) return cal.getTimeInMillis();
            cal.add(java.util.Calendar.MINUTE, 1);
        }
        return -1;
    }
}
