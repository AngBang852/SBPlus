package com.sbplus.browser;

import android.webkit.JavascriptInterface;

/**
 * 注入到页面 WebView 的 JS 桥，用于让 GM_xmlhttpRequest 走 Java 侧 HttpURLConnection，
 * 从而绕过浏览器同源策略，实现跨域请求（翻译脚本调用翻译 API 依赖此能力）。
 */
public class SbplusJsBridge {

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
     * 跨域请求。由页面 GM_xmlhttpRequest 通过 window.__sbplus__.gmXhr(...) 调用。
     * @return JSON 字符串：{"status":200,"responseText":"...","error":"..."}
     */
    @JavascriptInterface
    public String gmXhr(String method, String url, String headersJson, String data) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(method == null ? "GET" : method.toUpperCase());
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (SBPlus Userscript)");

            // 解析 headersJson：{"k":"v",...}
            if (headersJson != null && !headersJson.isEmpty()) {
                try {
                    org.json.JSONObject h = new org.json.JSONObject(headersJson);
                    java.util.Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        conn.setRequestProperty(k, h.optString(k));
                    }
                } catch (Throwable ignored) {}
            }

            // 写请求体
            if (data != null && !data.isEmpty()
                    && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                conn.setDoOutput(true);
                byte[] body = data.getBytes("UTF-8");
                java.io.OutputStream os = conn.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            }

            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            String responseText = readStream(is);
            MainModule.logMsg("[SBPlus] gmXhr " + method + " " + url + " -> status=" + status + " len=" + (responseText == null ? 0 : responseText.length()));

            org.json.JSONObject result = new org.json.JSONObject();
            result.put("status", status);
            result.put("responseText", responseText == null ? "" : responseText);
            result.put("error", "");
            return result.toString();
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] gmXhr ERROR " + method + " " + url + " -> " + t);
            try {
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("status", -1);
                result.put("responseText", "");
                result.put("error", String.valueOf(t));
                return result.toString();
            } catch (Throwable t2) {
                return "{\"status\":-1,\"responseText\":\"\",\"error\":\"bridge error\"}";
            }
        }
    }

    private String readStream(java.io.InputStream is) {
        if (is == null) return null;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
