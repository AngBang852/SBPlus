package com.sbplus.browser;

/**
 * 文本 / 转义 / 版本号工具。
 *
 * <p>2026-09-17 从 MainHook 抽出。类名带 {@code Sb} 前缀是为了避免与
 * Android 框架里的同名类({@code android.text.TextUtils})在阅读与静态导入时混淆 ——
 * 本类与框架类无任何关系。
 *
 * <p>全部方法无状态、无副作用、不接触视图与 Context。搬移时逐字对齐原文,
 * 包含几处「看起来可简化但实际有原因」的写法(例如 {@link #htmlEscape} 里
 * 单双引号用 {@code String.valueOf((char) 34/39)} 而不是字面量 —— 保持原样)。
 */
final class SbTextUtils {

    private SbTextUtils() {}

    /** HTML 转义:& < > " ' 五个字符。null 视为空串。 */
    static String htmlEscape(String s) {
        if (s == null) return "";
        String q = String.valueOf((char) 34);
        String apos = String.valueOf((char) 39);
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace(q, "&quot;").replace(apos, "&#39;");
    }

    /** HTML 反转义。null 视为空串。 */
    static String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", String.valueOf((char) 34)).replace("&#39;", "'").replace("&#x27;", "'");
    }

    /** 从 {@code tagStart} 处的开标签中取出其文本内容(到下一个 {@code </} 或串尾)。 */
    static String extractTagText(String html, int tagStart) {
        int gt = html.indexOf(">", tagStart);
        if (gt < 0) return "";
        int end = html.indexOf("</", gt);
        if (end < 0) end = html.length();
        return htmlUnescape(html.substring(gt + 1, end));
    }

    /**
     * 从 {@code aStart} 处的 {@code <A>} 标签中取出 href 值(去引号后反转义)。
     * 大小写 {@code HREF=}/{@code href=} 都认;缺失返回空串。
     */
    static String extractHref(String html, int aStart) {
        int gt = html.indexOf(">", aStart);
        if (gt < 0) return "";
        String tag = html.substring(aStart, gt);
        char q = (char) 34;
        int hrefIdx = tag.indexOf("HREF=");
        if (hrefIdx < 0) hrefIdx = tag.indexOf("href=");
        if (hrefIdx < 0) return "";
        int quote = tag.indexOf(q, hrefIdx);
        if (quote < 0) return "";
        int quote2 = tag.indexOf(q, quote + 1);
        if (quote2 < 0) return "";
        return htmlUnescape(tag.substring(quote + 1, quote2));
    }

    /**
     * 把字符串转成可安全嵌入 {@code <script>} 的 JSON 字符串字面量(含首尾双引号)。
     *
     * <p>要点:除常规转义外,还转义 {@code <} 为 {@code \u003C} —— 否则内容里出现
     * {@code </script>} 会提前闭合宿主脚本块,整段注入代码被截断。
     * 同时处理 U+2028/U+2029(JS 里属于行终止符,不转义会导致语法错误)。
     */
    static String quoteJsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '<':  sb.append("\\u003C"); break;   // 防 </script> 提前闭合
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 轻量 JSON 字符串转义(含首尾双引号)。与 {@link #quoteJsonString} 的区别:
     * 不处理 {@code <} 与控制字符 —— 仅用于内容可控的场合。
     */
    static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        String e = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + e + "\"";
    }

    /** JS 单引号字符串转义(用于拼接 javascript: 注入,防止单引号/反斜杠破坏 JS 语法)。 */
    static String jsQuote(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "").replace("\n", "\\n");
    }

    /** 剥离 tag 前导的 'v',用于展示(如 v2.0 -> 2.0)。 */
    static String stripV(String s) {
        if (s == null) return "";
        String r = s.trim();
        if (r.toLowerCase().startsWith("v")) r = r.substring(1);
        return r;
    }

    /**
     * 版本号比较:remote 是否比 local 新。
     *
     * <p>按 {@code .} 分段逐位比较,缺位补 0。任一段不是数字时退化为
     * 「字符串不相等即认为不同」—— 原实现即如此,保持不动:这样
     * {@code 2.0-beta} 这类非纯数字版本至少不会静默判成"无需更新"。
     */
    static boolean versionNewer(String remote, String local) {
        String r = (remote == null ? "" : remote).trim();
        String l = (local == null ? "" : local).trim();
        if (r.isEmpty()) return false;
        if (l.isEmpty()) return true;
        if (r.toLowerCase().startsWith("v")) r = r.substring(1);
        if (l.toLowerCase().startsWith("v")) l = l.substring(1);
        try {
            String[] rp = r.split("\\.");
            String[] lp = l.split("\\.");
            int n = Math.max(rp.length, lp.length);
            for (int i = 0; i < n; i++) {
                int rv = i < rp.length ? Integer.parseInt(rp[i].trim()) : 0;
                int lv = i < lp.length ? Integer.parseInt(lp[i].trim()) : 0;
                if (rv != lv) return rv > lv;
            }
            return false;
        } catch (NumberFormatException e) {
            return !r.equals(l);
        }
    }

    /**
     * 不区分大小写的包含判断。
     *
     * <p><b>约定</b>:第二个参数必须已经是小写({@code lowerNeedle}) —— 调用方
     * 传入前自行 toLowerCase。这样避免每次调用都产生一个新字符串,
     * 该方法用于每帧热路径上的图标识别,字符串分配是真开销。
     */
    static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        if (haystack == null || lowerNeedle == null) return false;
        int hl = haystack.length(), nl = lowerNeedle.length();
        if (nl == 0) return true;
        if (nl > hl) return false;
        outer:
        for (int i = 0; i <= hl - nl; i++) {
            for (int j = 0; j < nl; j++) {
                char a = haystack.charAt(i + j);
                if (a >= 'A' && a <= 'Z') a = (char) (a + 32);
                if (a != lowerNeedle.charAt(j)) continue outer;
            }
            return true;
        }
        return false;
    }

    /** 按逗号切分并去空白项;null/空白输入返回空数组(不返回 null)。 */
    static String[] splitComma(String s) {
        if (s == null || s.trim().isEmpty()) return new String[0];
        String[] arr = s.split(",");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String x : arr) {
            String t = x.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }
}
