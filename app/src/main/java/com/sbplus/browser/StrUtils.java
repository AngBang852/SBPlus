package com.sbplus.browser;

/**
 * 字符串 / 展示格式化工具。
 *
 * <p>2026-09-17 从 MainHook 抽出。选取标准:<b>不引用 MainHook 的任何静态状态</b>,
 * 且不接触 Context 与宿主视图树。搬移时逐方法与原文比对,不改动任何行为 ——
 * 包括原本就存在的格式细节(例如 {@link #fmtSize} 小于 1MB 时不带小数、
 * {@link #sanitizeFileName} 的兜底名是 "script")。
 *
 * <p>这些函数没有状态、没有副作用,可以安全地在任意线程调用。
 */
final class StrUtils {

    private StrUtils() {}

    /**
     * 把任意文本净化为可用作文件名的字符串。
     *
     * <p>替换 Windows/Unix 非法字符为下划线,裁剪首尾空白,超长截断到 60 字符。
     * 空结果兜底为 {@code "script"}。<b>注意</b>:该兜底名沿用自原实现,不要
     * 擅自改成更"合理"的名字 —— 调用方可能已有依赖。
     */
    static String sanitizeFileName(String name) {
        if (name == null) return "script";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|'
                    || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String r = sb.toString().trim();
        if (r.isEmpty()) r = "script";
        if (r.length() > 60) r = r.substring(0, 60);
        return r;
    }

    /**
     * 从 URL 推断扩展名;无法判断时按媒体类型回退。
     *
     * @param type "video" → .mp4,"audio" → .mp3,其余 → .jpg
     */
    static String parseExt(String url, String type) {
        try {
            String path = url.split("[?#]")[0];
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase();
                if (ext.length() <= 6 && ext.matches("[a-z0-9]+")) return "." + ext;
            }
        } catch (Throwable ignored) {}
        return "video".equals(type) ? ".mp4" : ("audio".equals(type) ? ".mp3" : ".jpg");
    }

    /** 秒数格式化为 mm:ss / h:mm:ss。非法输入返回 "?"。 */
    static String fmtDuration(double sec) {
        try {
            if (sec <= 0 || Double.isNaN(sec) || Double.isInfinite(sec)) return "?";
            int s = (int) Math.round(sec);
            int hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
            if (hh > 0) return hh + ":" + (mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss;
            return mm + ":" + (ss < 10 ? "0" : "") + ss;
        } catch (Throwable ignored) { return "?"; }
    }

    /** 从 URL 提取清晰度标签(如 720P),用于标题后缀。无法判断返回空串。
     *
     *  <p>判定顺序:<b>真实宽高优先</b>,URL 仅作最后兜底。
     *  竖屏视频(如 576x1024)按<b>短边</b>判档位 —— 这是播放器的通行口径,
     *  也避免 vH=1024 被误判成 1080P。
     *
     *  <p>历史缺陷:旧实现先做 URL 子串匹配(u.contains("720") 等),
     *  只要签名令牌/ft 参数里偶然出现该数字就会给出错误档位,
     *  导致同一视频每次刷新显示不同画质。现已剔除子串匹配,
     *  URL 仅在明确携带尺寸模式时才参与判定。 */
    static String videoQuality(String url, int vW, int vH) {
        try {
            // 1) 真实宽高最可信
            if (vW > 0 && vH > 0) return tierOf(Math.min(vW, vH));
            // 2) URL 里明确写出的尺寸模式(如 /720x1280/ 或 @1280w_720h)
            int[] wh = dimFromUrl(url);
            if (wh != null) return tierOf(Math.min(wh[0], wh[1]));
            // 3) URL 里明确写出的档位标记(要求紧邻分隔符,避免签名令牌误命中)
            String u = url == null ? "" : url.toLowerCase();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("[/_=](2160|1440|1080|720|576|540|480|360|240)p?[/_.?&-]").matcher(u);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                return tierOf(v);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 短边像素 -> 标准档位名。只认标准档位(容差 10%),非标准值如实显示,不凭空造档位。 */
    static String tierOf(int minSide) {
        if (minSide <= 0) return "";
        final int[] STD = {144, 240, 360, 480, 540, 576, 720, 1080, 1440, 2160};
        int bestV = 0, bestD = Integer.MAX_VALUE;
        for (int std : STD) {
            int d = Math.abs(minSide - std);
            if (d < bestD) { bestD = d; bestV = std; }
        }
        if (bestV > 0 && bestD <= bestV / 10) {
            if (bestV >= 2160) return "4K";
            if (bestV >= 1440) return "2K";
            return bestV + "P";
        }
        return minSide + "P";
    }

    /** 从 URL 提取显式尺寸(如 /720x1280/、@1280w_720h);无则返回 null。 */
    static int[] dimFromUrl(String url) {
        try {
            if (url == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@(\\d{2,5})w_(\\d{2,5})h").matcher(url);
            if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            m = java.util.regex.Pattern.compile("[/_](\\d{2,5})x(\\d{2,5})[/._]").matcher(url);
            if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 字节数格式化。<b>保留原实现的取舍</b>:&gt;=1MB 才带一位小数,
     * 否则整数 KB —— 这是既有展示风格,不是笔误。
     */
    static String fmtSize(long len) {
        try {
            if (len >= 1048576) return String.format("%.1fMB", len / 1048576.0);
            return (len / 1024) + "KB";
        } catch (Throwable ignored) { return "?"; }
    }

    /** 过长 URL 截断用于展示(超过 60 字符截到 57 + "...")。 */
    static String shortUrl(String url) {
        try {
            String u = url;
            if (u.length() > 60) u = u.substring(0, 57) + "...";
            return u;
        } catch (Throwable t) { return url; }
    }

    /**
     * 从 URL 末段推断文件名;无意义名(index.html / 纯数字等)会被跳过,
     * 最终回退为"去协议后截断 40 字符"。始终不返回 null(最差返回空串)。
     */
    static String fileNameFromUrl(String url) {
        try {
            if (url == null) return "";
            String u = url;
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            int f = u.indexOf('#');
            if (f >= 0) u = u.substring(0, f);
            int slash = u.lastIndexOf('/');
            String name = slash >= 0 ? u.substring(slash + 1) : u;
            if (!name.isEmpty()) {
                try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Throwable ignored) {}
                name = name.replace('\\', '/');
                // 排除无意义文件名
                if (!name.equals("/") && !name.isEmpty()
                        && !name.equals("index.html") && !name.equals("index.htm")
                        && !name.matches("^[0-9]+$")) {
                    return name;
                }
            }
        } catch (Throwable ignored) {}
        // 静态fallback: 去掉协议后截断
        try {
            String t = url.replaceFirst("^[a-zA-Z]+://", "");
            if (t.length() > 40) t = t.substring(0, 40);
            return t;
        } catch (Throwable ignored) {}
        return "";
    }
}
