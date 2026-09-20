package com.sbplus.browser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * BookmarkAiClient —— 调 OpenAI 兼容接口做分类（纯 JDK，无 Android 依赖）。
 *
 * <p>接口形状与主人现有 provider 完全一致（都是 {@code baseURL + /chat/completions}），
 * 所以一个实现通吃 DeepSeek / hcnsec / tokenrouter 等所有 OpenAI 兼容服务。
 */
public final class BookmarkAiClient {

    /** 一批发多少条。太少浪费往返，太多可能超出输出长度限制。 */
    public static final int BATCH_SIZE = 50;

    private final String baseUrl;    // 例: https://api.deepseek.com/v1
    private final String apiKey;
    private final String model;      // 例: deepseek-chat
    private final int timeoutMs;

    public BookmarkAiClient(String baseUrl, String apiKey, String model) {
        // 120 秒。分类请求的输入很长（系统提示 + 几十条书签），小模型服务端
        // 排队也慢，30 秒实测会整批超时 —— 那不是「慢」，是直接失败。
        this(baseUrl, apiKey, model, 120000);
    }

    public BookmarkAiClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.timeoutMs = timeoutMs;
    }

    /** 配置是否完整。 */
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    /** 组装完整请求 URL。 */
    public String endpoint() {
        return baseUrl + "/chat/completions";
    }

    /**
     * 组装请求体（JSON）。
     *
     * <p>{@code temperature:0} 是刻意的：分类任务要的是稳定复现，不是创造力。
     * 同样输入两次调用应给同样结果，否则主人重新整理一次会得到不同分类。
     *
     * @param systemPrompt 系统提示词（两阶段分类各用各的，所以由调用方传入）
     * @param userPayload  用户消息体
     */
    public String buildRequestBody(String systemPrompt, String userPayload) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"model\":\"").append(esc(model)).append("\",");
        sb.append("\"temperature\":0,");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(esc(systemPrompt)).append("\"},");
        sb.append("{\"role\":\"user\",\"content\":\"").append(esc(userPayload)).append("\"}");
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 发起一次请求，返回 AI 的原始文本回包。
     *
     * <p>对 5xx 网关错误和网络超时<b>自动重试</b>：这类失败几乎都是服务端瞬时
     * 过载，等几秒再来一次往往就过了。不重试的话，一个 504 会让整批书签白跑。
     *
     * <p>4xx 不重试 —— Key 错了、地址错了，重试多少次都一样。
     */
    public String callWith(String systemPrompt, String userPayload) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            if (attempt > 0) {
                // 退避：2s, 5s
                try { Thread.sleep(attempt == 1 ? 2000 : 5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                MainModule.logMsg("[SBPlus] retry " + attempt + "/" + RETRIES);
            }
            try {
                return callOnce(systemPrompt, userPayload);
            } catch (RetryableException re) {
                last = re.cause;
                MainModule.logMsg("[SBPlus] retryable failure: " + re.cause.getMessage());
            }
            // 不再 catch(IOException) 直接抛 —— SSLHandshakeException 等网络层异常
            // 也是 IOException 的子类，那样写会把它们误判为「不可重试」。
            // 不可重试的判断已经由 callOnce 内部的 4xx 分支负责（它直接抛 IOException）。
        }
        throw last != null ? last : new IOException("请求失败");
    }

    /** 重试次数。3 次总尝试（1 次原始 + 2 次重试）。 */
    private static final int RETRIES = 2;

    /** 标记「值得重试」的失败。 */
    private static final class RetryableException extends Exception {
        final IOException cause;
        RetryableException(IOException c) { this.cause = c; }
    }

    private String callOnce(String systemPrompt, String userPayload)
            throws IOException, RetryableException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint()).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Accept", "application/json");

            byte[] body = buildRequestBody(systemPrompt, userPayload)
                    .getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            try { os.write(body); } finally { os.close(); }

            int code = c.getResponseCode();
            if (code != 200) {
                String err = readAll(c.getErrorStream());
                IOException io = new IOException("HTTP " + code + " @ " + safeHost()
                        + (err.isEmpty() ? "" : ": " + friendlyError(code, err)));
                // 5xx = 服务端瞬时问题，值得重试
                if (code >= 500) throw new RetryableException(io);
                throw io;
            }
            return extractContent(readAll(c.getInputStream()));
        } catch (java.net.SocketTimeoutException te) {
            throw new RetryableException(new IOException(
                    "请求超时（" + (timeoutMs / 1000) + " 秒）@ " + safeHost()));
        } catch (javax.net.ssl.SSLException se) {
            // SSLHandshakeException / connection closed 几乎都是服务端或中间层
            // 在压力下主动断开，重试往往能成。实测 649 条书签时全部栽在这里。
            throw new RetryableException(new IOException(
                    "连接被中断 @ " + safeHost() + "（" + se.getClass().getSimpleName() + "）"));
        } catch (java.net.SocketException se) {
            // ConnectException 是 SocketException 的子类，不能并列 catch。
            // 连接被拒/重置同样是瞬时过载的常见表现，一起重试。
            throw new RetryableException(new IOException(
                    "连接失败 @ " + safeHost() + "：" + se.getMessage()));
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    /**
     * 从 OpenAI 格式回包里取出 {@code choices[0].message.content}。
     *
     * <p>不引入 JSON 库（Android 侧有 org.json，但 PC 单测没有）——
     * 这里用定位法足够，因为回包结构固定。
     */
    static String extractContent(String respJson) {
        if (respJson == null) return "";
        int ci = respJson.indexOf("\"content\"");
        if (ci < 0) return "";
        int colon = respJson.indexOf(':', ci);
        if (colon < 0) return "";
        int q1 = respJson.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < respJson.length(); i++) {
            char ch = respJson.charAt(i);
            if (ch == '\\') {
                if (i + 1 >= respJson.length()) break;
                char nx = respJson.charAt(++i);
                switch (nx) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < respJson.length()) {
                            try {
                                sb.append((char) Integer.parseInt(respJson.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {}
                        }
                        break;
                    default: sb.append(nx);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 只取主机名，用于错误提示。 */
    private String safeHost() {
        try {
            String s = baseUrl;
            int i = s.indexOf("://");
            if (i >= 0) s = s.substring(i + 3);
            int j = s.indexOf('/');
            if (j >= 0) s = s.substring(0, j);
            return s;
        } catch (Throwable t) {
            return baseUrl;
        }
    }

    /**
     * 把服务端的原始错误翻译成主人能看懂的一句话。
     *
     * <p>Cloudflare 的 5xx 页面有几百字符的说明文档链接，直接丢给主人没用 ——
     * 真正要传达的只有「中转站过载了，换一个或等会儿再试」。
     */
    private static String friendlyError(int code, String raw) {
        switch (code) {
            case 401:
                return "API Key 无效或已过期";
            case 403:
                return "没有权限（可能是 Key 未开通该模型）";
            case 404:
                return "接口地址不对，或该模型不存在";
            case 429:
                return "请求太频繁，稍后再试";
            case 500:
            case 502:
            case 503:
            case 504:
                return "服务商暂时不可用（网关超时，多半是过载）";
            default:
                return truncate(raw, 200);
        }
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
