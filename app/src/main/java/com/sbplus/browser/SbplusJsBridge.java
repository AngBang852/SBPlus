package com.sbplus.browser;

import android.webkit.JavascriptInterface;

/**
 * 注入到页面 WebView 的 JS 桥，用于让 GM_xmlhttpRequest 走 Java 侧 HttpURLConnection，
 * 从而绕过浏览器同源策略，实现跨域请求（翻译脚本调用翻译 API 依赖此能力）。
 *
 * 安全约束：本桥运行在**任意网页**的 JS 上下文里(注入是全局的)，因此
 * {@link #gmXhr} / {@link #gmXhrAsync} 的入参完全不可信。所有外部可控参数在真正发起请求前都要经过
 * {@link #isRequestAllowed} 的校验，避免变成一个可被页面脚本滥用的 SSRF/内网探测跳板。
 *
 * Cookie 能力({@link #gmCookieGetAll} / {@link #gmCookieSet})在内容 tab(tab!=null)上
 * 另有「同源默认 + 跨域 @connect 策略」门禁，见 {@link #cookieAccessAllowed}
 * (2026-09-20 S1 修复:此前任意网页可读写任意公网域的 Cookie)。
 */
public class SbplusJsBridge {

    /** 单次 gmXhr 响应体上限：超过即截断。防止脚本拉取超大文件把宿主进程撑爆。 */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;   // 8MB

    /**
     * 发起请求时所在的 Tab。异步完成后需要用它 evaluateJavaScript 回调页面
     * —— @JavascriptInterface 方法无法直接把异步结果推回 JS,必须经 evaluate。
     * 可为 null(旧路径/拿不到 tab 时):此时调用方 JS 会自动退回同步桥。
     */
    final Object tab;
    /** 回调分发器 id,与 MainHook 的分发表对应;tab 为 null 时无意义。 */
    final String dispatchId;

    /** 旧签名兼容:无 tab 的桥(同步 gmXhr 场景,无异步回调能力)。 */
    public SbplusJsBridge() {
        this(null, null);
    }

    /** JS 侧获取本桥的分发器 id(JS 不能读 Java 字段,只能走方法)。无分发器返回空串。 */
    @JavascriptInterface
    public String getDispatchId() {
        return dispatchId == null ? "" : dispatchId;
    }

    public SbplusJsBridge(Object realTab, String dispatcherId) {
        this.tab = realTab;
        this.dispatchId = dispatcherId;
    }

    @JavascriptInterface
    public void gmLog(String msg) {
        MainModule.logMsg("[SBPlus][JS] " + msg);
    }

    @JavascriptInterface
    public void gmError(String scriptName, String errorType, String message, String source, int line) {
        MainHook.logScriptError(scriptName, errorType, message, source, line);
    }

    @JavascriptInterface
    public void gmSetValue(String tag, String key, String value) {
        MainHook.gmSetValue(tag, key, value);
    }

    @JavascriptInterface
    public String gmGetValue(String tag, String key) {
        return MainHook.gmGetValue(tag, key);
    }

    @JavascriptInterface
    public void gmDeleteValue(String tag, String key) {
        MainHook.gmDeleteValue(tag, key);
    }

    @JavascriptInterface
    public String gmListValues(String tag) {
        return MainHook.gmListValues(tag);
    }

    /**
     * GM.cookie 读取（走 CookieManager 全局视图,httpOnly 也可见）。
     *
     * <p>与 JS 端 document.cookie 的差别就在 httpOnly 与跨子域 —— 登录态迁移类
     * 脚本（把 A 站登录态搬到 B 站的工具）依赖这两点。安全约束沿用本类的
     * {@link #isRequestAllowed}:目标不是公网 http(s) 的调用一律拒绝,
     * 防止页面借 cookie 桥探测内网/读本地。
     *
     * @param url  目标 URL（决定读哪个域的 cookie）
     * @return JSON 数组字符串 [{name,value,domain,path,secure,httpOnly,session},...];被拒返回 []
     */
    /** 旧签名兼容:不带脚本身份。内容 tab 上等价于「仅同源」(见 cookieAccessAllowed)。 */
    @JavascriptInterface
    public String gmCookieGetAll(String url) {
        return gmCookieGetAll(url, null);
    }

    /**
     * GM.cookie 读取（走 CookieManager 全局视图,httpOnly 也可见）。
     *
     * <p>与 JS 端 document.cookie 的差别就在 httpOnly 与跨子域 —— 登录态迁移类
     * 脚本（把 A 站登录态搬到 B 站的工具）依赖这两点。
     *
     * <p>2026-09-20 S1 修复:内容 tab(tab!=null,桥注入在任意网页)上,读取目标
     * 必须是当前页同源;跨域要求携带脚本身份(scriptTag)并通过 @connect/用户
     * 确认策略。模块自有后台 WebView(tab==null,只跑用户安装的脚本)保持原有能力。
     *
     * @param url       目标 URL（决定读哪个域的 cookie）
     * @param scriptTag 调用方脚本名(GM_API_JS 传入 __sbplus_current_tag__;可为 null)
     * @return JSON 数组字符串 [{name,value,domain,path,secure,httpOnly,session},...];被拒返回 []
     */
    @JavascriptInterface
    public String gmCookieGetAll(String url, String scriptTag) {
        try {
            if (!isRequestAllowed(url)) {
                MainModule.logMsg("[SBPlus] gmCookieGetAll BLOCKED: " + url);
                return "[]";
            }
            if (!cookieAccessAllowed(url, scriptTag)) {
                MainModule.logMsg("[SBPlus] gmCookieGetAll BLOCKED-CROSS tag=" + scriptTag + " url=" + url);
                return "[]";
            }
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String raw = cm.getCookie(url);
            if (raw == null || raw.isEmpty()) return "[]";
            String host;
            try { host = new java.net.URL(url).getHost(); } catch (Throwable t) { host = ""; }
            boolean isHttps = url != null && url.toLowerCase(java.util.Locale.US).startsWith("https");
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String part : raw.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                String nm = eq >= 0 ? p.substring(0, eq) : p;
                String vl = eq >= 0 ? p.substring(eq + 1) : "";
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", nm);
                o.put("value", vl);
                o.put("domain", host);
                o.put("path", "/");
                o.put("secure", isHttps);
                o.put("httpOnly", false);
                o.put("session", true);
                arr.put(o);
            }
            return arr.toString();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] gmCookieGetAll error: " + t);
            return "[]";
        }
    }

    /**
     * GM.cookie 写入/删除（CookieManager.setCookie,全局生效）。
     * @param url     目标 URL
     * @param cookie  Set-Cookie 头格式的字符串 "name=value; Path=/; Secure" 等
     * @return "ok" / "blocked" / "error"
     */
    /** 旧签名兼容:不带脚本身份。内容 tab 上等价于「仅同源」(见 cookieAccessAllowed)。 */
    @JavascriptInterface
    public String gmCookieSet(String url, String cookie) {
        return gmCookieSet(url, cookie, null);
    }

    /**
     * GM.cookie 写入/删除（CookieManager.setCookie,全局生效）。
     * 2026-09-20 S1 修复:内容 tab 上跨域写入需脚本身份 + @connect 策略(防会话固定)。
     * @param url       目标 URL
     * @param cookie    Set-Cookie 头格式的字符串 "name=value; Path=/; Secure" 等
     * @param scriptTag 调用方脚本名(可为 null)
     * @return "ok" / "blocked" / "error"
     */
    @JavascriptInterface
    public String gmCookieSet(String url, String cookie, String scriptTag) {
        try {
            if (!isRequestAllowed(url)) {
                MainModule.logMsg("[SBPlus] gmCookieSet BLOCKED: " + url);
                return "blocked";
            }
            if (!cookieAccessAllowed(url, scriptTag)) {
                MainModule.logMsg("[SBPlus] gmCookieSet BLOCKED-CROSS tag=" + scriptTag + " url=" + url);
                return "blocked";
            }
            if (cookie == null || cookie.isEmpty()) return "error";
            // 本项目编译用的 android.jar(三星变体)里 setCookie(String,String) 是 void
            // (标准 API 是 boolean)。写入结果只能事后用 getCookie 验证。
            android.webkit.CookieManager.getInstance().setCookie(url, cookie);
            String back = android.webkit.CookieManager.getInstance().getCookie(url);
            boolean ok = back != null && back.contains(cookie.split(";")[0].trim());
            return ok ? "ok" : "error";
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] gmCookieSet error: " + t);
            return "error";
        }
    }

    /**
     * Cookie 访问准入(2026-09-20 S1):同源直读;跨域需要脚本身份 + @connect 策略。
     *
     * <p>已知边界:主世界(main world)注入无法在 JS 层区分「脚本调用」与「页面调用」,
     * scriptTag 理论上可被页面伪冒——与 GM_xmlhttpRequest 的用户确认机制同一接受度,
     * 已记录在 _AUDIT.md;彻底解决需要隔离世界(isolated world)支持,当前宿主注入
     * API 不提供。
     */
    private boolean cookieAccessAllowed(String url, String scriptTag) {
        String targetHost = hostOf(url);
        if (targetHost == null || targetHost.isEmpty()) return false;
        if (tab == null) return true;   // 模块自有后台 WebView:只运行用户安装的脚本
        String pageHost = pageHostOfTab();
        if (pageHost != null && !pageHost.isEmpty() && isSameSite(pageHost, targetHost)) return true;
        if (scriptTag == null || scriptTag.isEmpty()) return false;
        return MainHook.isCookieCrossOriginAllowed(scriptTag, targetHost);
    }

    private static String hostOf(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Throwable t) { return ""; }
    }

    /** 经反射取当前 tab 的 URL host(拿不到返回空串 → 内容 tab 上一律拒绝跨域,保守方向)。 */
    private String pageHostOfTab() {
        try {
            Object u = MainHook.callMethod(tab, "getUrl");
            return hostOf(u == null ? null : String.valueOf(u));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 注册域后缀级同源判断(忽略 www. 前缀;sub.example.com 与 example.com 视为同源)。 */
    private static boolean isSameSite(String a, String b) {
        String x = a.toLowerCase(java.util.Locale.US);
        String y = b.toLowerCase(java.util.Locale.US);
        if (x.startsWith("www.")) x = x.substring(4);
        if (y.startsWith("www.")) y = y.substring(4);
        return x.equals(y) || x.endsWith("." + y) || y.endsWith("." + x);
    }

    /**
     * 资源嗅探结果上报（由页面嗅探 JS 调用）。
     * @param jsons 媒体 JSON 数组字符串，每项含 url/type/title。
     */
    @JavascriptInterface
    public void reportMedia(String jsons) {
        try {
            MainHook.onSniffedMedia(jsons);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] reportMedia error: " + t);
        }
    }

    /**
     * 请求准入校验：只放行 http/https 的公网地址。
     *
     * <p>拒绝的目标及理由：
     * <ul>
     *   <li>非 http(s) 协议(file/ftp/jar/content 等) —— 可被用来读本地文件。</li>
     *   <li>无主机名 —— 非法 URL。</li>
     *   <li>本机/内网地址 —— 页面脚本可借此扫描 127.0.0.1 上的服务、探测家用路由器
     *       后台(典型 DNS rebinding / SSRF 场景)。这正是本方法存在的核心原因。</li>
     * </ul>
     */
    private static boolean isRequestAllowed(String url) {
        try {
            if (url == null || url.isEmpty()) return false;
            java.net.URL u = new java.net.URL(url);
            String proto = u.getProtocol();
            if (!"http".equalsIgnoreCase(proto) && !"https".equalsIgnoreCase(proto)) return false;

            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;

            String h = host.toLowerCase(java.util.Locale.US);
            // IPv6 字面量(URL 里以 [] 包裹,getHost 去掉括号)
            if (h.indexOf(':') >= 0) return false;
            if ("localhost".equals(h) || h.endsWith(".localhost")) return false;
            if (h.endsWith(".local") || h.endsWith(".internal")) return false;
            // IPv4 私有/保留段 + 链路本地云元数据(169.254.169.254)
            if (isPrivateOrLoopbackIpv4(h)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判断点分十进制 IPv4 是否属于回环/私有/链路本地/保留段。 */
    private static boolean isPrivateOrLoopbackIpv4(String h) {
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                o[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;   // 不是纯数字 → 是域名,交给上面的规则
            }
            if (o[i] < 0 || o[i] > 255) return false;
        }
        if (o[0] == 127) return true;                       // 127.0.0.0/8  回环
        if (o[0] == 10) return true;                        // 10.0.0.0/8
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;  // 172.16.0.0/12
        if (o[0] == 192 && o[1] == 168) return true;        // 192.168.0.0/16
        if (o[0] == 169 && o[1] == 254) return true;        // 169.254.0.0/16 链路本地/云元数据
        if (o[0] == 0) return true;                         // 0.0.0.0/8
        if (o[0] >= 224) return true;                       // 组播/保留
        return false;
    }

    /**
     * 跨域请求。由页面 GM_xmlhttpRequest 通过 window.__sbplus__.gmXhr(...) 调用。
     * @return JSON 字符串：{"status":200,"responseText":"...","error":"..."}
     */
    @JavascriptInterface
    public String gmXhr(String method, String url, String headersJson, String data) {
        return execXhr(method, url, headersJson, data);
    }

    /**
     * @connect 策略预检(同步路径用):返回 "ok" 或拒绝 JSON。
     * JS 侧在走同步 gmXhr 之前先问一次,拒绝时直接派发错误,不再发起真实请求。
     */
    @JavascriptInterface
    public String gmConnectCheck(String scriptTag, String url) {
        String deny = MainHook.isXhrAllowedForScript(scriptTag, url);
        return deny == null ? "ok" : deny;
    }

    /**
     * 跨域请求（异步版）。立即返回 true=已受理 / false=未受理(JS 层应退回同步桥)。
     *
     * <p>流程：桥在校验与受理后立即返回 → SbExecutors.net 线程池执行真实 HTTP →
     * 完成后经 {@code MainHook.dispatchXhrResult(dispatchId, id, json)} 用
     * evaluateJavaScript 把结果推回页面分发器 → 分发器按 id 找到 GM_xmlhttpRequest
     * 的 onload/onerror 回调执行。同步等待 JavascriptInterface 线程的问题就此消除。
     *
     * <p>受理由此方法开始（含 isRequestAllowed 校验），后续真正执行在
     * {@link #execXhr} 中会重复校验 —— 双保险，代价可忽略。
     */
    @JavascriptInterface
    public boolean gmXhrAsync(final String id, String method, String url,
                              String headersJson, String data, int timeoutMs,
                              String scriptTag) {
        final String verb = (method == null || method.isEmpty()) ? "GET" : method.toUpperCase();
        final String fUrl = url;
        final String fTag = scriptTag;
        if (tab == null || dispatchId == null) return false;   // 无回调通道,退回同步桥
        if (!isRequestAllowed(url)) {
            MainModule.logMsg("[SBPlus] gmXhrAsync BLOCKED(" + verb + "): " + url);
            // 已受理语义:被拒也是"结果",直接派发错误,不要让脚本干等。
            MainHook.dispatchXhrResult(dispatchId, id, errorJson(-2, "request blocked by SBPlus: target not allowed"));
            return true;
        }
        // #7 @connect 确认链:脚本没声明 @connect 时,该域需用户确认过才放行。
        String denyJson = MainHook.isXhrAllowedForScript(fTag, url);
        if (denyJson != null) {
            MainModule.logMsg("[SBPlus] gmXhrAsync CONNECT-DENY(" + verb + "): tag=" + fTag + " url=" + url);
            MainHook.recordConnectDeny(fTag, url);
            MainHook.dispatchXhrResult(dispatchId, id, denyJson);
            return true;
        }
        SbExecutors.net(new Runnable() {
            @Override public void run() {
                String json = execXhr(verb, fUrl, headersJson, data);
                MainHook.dispatchXhrResult(dispatchId, id, json);
            }
        });
        return true;
    }

    /** 真实 HTTP 执行（同步,在线程池线程上跑）。同步/异步两条路径共用。 */
    private String execXhr(String verb, String url, String headersJson, String data) {
        try {
            if (!isRequestAllowed(url)) {
                MainModule.logMsg("[SBPlus] gmXhr BLOCKED(" + verb + "): " + url);
                return errorJson(-2, "request blocked by SBPlus: target not allowed");
            }

            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(verb);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (SBPlus Userscript)");

            // 解析 headersJson：{"k":"v",...}
            if (headersJson != null && !headersJson.isEmpty()) {
                try {
                    org.json.JSONObject h = new org.json.JSONObject(headersJson);
                    java.util.Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        // 拒绝会破坏连接语义/注入换行的头名
                        if (k == null || k.isEmpty() || k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) continue;
                        conn.setRequestProperty(k, h.optString(k));
                    }
                } catch (Throwable ignored) {}
            }

            // 写请求体
            if (data != null && !data.isEmpty()
                    && ("POST".equalsIgnoreCase(verb) || "PUT".equalsIgnoreCase(verb)
                        || "PATCH".equalsIgnoreCase(verb))) {
                conn.setDoOutput(true);
                byte[] body = data.getBytes("UTF-8");
                java.io.OutputStream os = conn.getOutputStream();
                try {
                    os.write(body);
                    os.flush();
                } finally {
                    try { os.close(); } catch (Throwable ignored) {}
                }
            }

            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            String responseText = readStream(is);
            try { if (is != null) is.close(); } catch (Throwable ignored) {}

            // 响应头 + 重定向后的真实终址:TM 语义,不少脚本依赖。
            org.json.JSONObject result = new org.json.JSONObject();
            result.put("status", status);
            result.put("responseText", responseText == null ? "" : responseText);
            result.put("finalUrl", String.valueOf(conn.getURL()));
            result.put("responseHeaders", headersToJson(conn));
            try { conn.disconnect(); } catch (Throwable ignored) {}
            MainModule.logMsg("[SBPlus] gmXhr " + verb + " " + url + " -> status=" + status
                    + " len=" + (responseText == null ? 0 : responseText.length()));
            return result.toString();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] gmXhr ERROR " + verb + " " + url + " -> " + t);
            return errorJson(-1, String.valueOf(t));
        }
    }

    /** 把 HttpURLConnection 的响应头压成 JSON 对象（头名→逗号连接的值）。 */
    private static String headersToJson(java.net.HttpURLConnection conn) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            java.util.Map<String, java.util.List<String>> fields = conn.getHeaderFields();
            if (fields == null) return "{}";
            for (java.util.Map.Entry<String, java.util.List<String>> e : fields.entrySet()) {
                String name = e.getKey();
                if (name == null) continue;   // status line 的 null 键跳过
                StringBuilder v = new StringBuilder();
                for (String s : e.getValue()) {
                    if (v.length() > 0) v.append(", ");
                    v.append(s);
                }
                o.put(name, v.toString());
            }
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** 构造统一格式的错误响应 JSON。 */
    private static String errorJson(int status, String msg) {
        try {
            org.json.JSONObject result = new org.json.JSONObject();
            result.put("status", status);
            result.put("responseText", "");
            result.put("error", msg == null ? "" : msg);
            return result.toString();
        } catch (Throwable t2) {
            return "{\"status\":-1,\"responseText\":\"\",\"error\":\"bridge error\"}";
        }
    }

    /** 按上限读取响应体；超限即停(避免脚本拉大文件把宿主内存吃光)。 */
    private String readStream(java.io.InputStream is) {
        if (is == null) return null;
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) {
                if (sb.length() + n > MAX_RESPONSE_BYTES) {
                    sb.append(buf, 0, Math.max(0, MAX_RESPONSE_BYTES - sb.length()));
                    MainModule.logMsg("[SBPlus] gmXhr response truncated at " + MAX_RESPONSE_BYTES + " chars");
                    break;
                }
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            // 不关 is：调用方(conn.getInputStream())负责,这里只关装饰流会误关底层
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
