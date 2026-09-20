package com.sbplus.browser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ModelListFetcher —— 像 DSH 一样，从 OpenAI 兼容服务拉取可用模型列表。
 *
 * <p>目的：让主人在手机上填完地址和 Key 后，<b>点一下就列出可用模型供选择</b>，
 * 而不是手打模型名（打错了要等调用失败才发现）。
 *
 * <p>用标准 {@code GET {baseURL}/models}（OpenAI 规范）。实测主人的 provider
 * （hcnsec / tokenrouter / deepseek）在未带 Key 时都返回 401，说明该接口存在且
 * 要求鉴权 —— 带上 Key 即可拿到列表。
 */
public final class ModelListFetcher {

    /** 拉取到的模型。 */
    public static final class Model {
        public final String id;         // 传给 API 的 model 参数
        public final String ownedBy;    // 可选，部分服务返回
        public Model(String id, String ownedBy) {
            this.id = id;
            this.ownedBy = ownedBy == null ? "" : ownedBy;
        }
        @Override public String toString() { return id; }
    }

    /** 拉取结果：成功带列表，失败带原因（用于界面上给主人看）。 */
    public static final class Result {
        public final List<Model> models;
        public final String error;      // null 表示成功
        public boolean isOk() { return error == null; }
        Result(List<Model> m, String e) { this.models = m; this.error = e; }
    }

    private final String baseUrl;
    private final String apiKey;
    private final int timeoutMs;

    public ModelListFetcher(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, 15000);
    }

    public ModelListFetcher(String baseUrl, String apiKey, int timeoutMs) {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = timeoutMs;
    }

    public String endpoint() {
        return baseUrl + "/models";
    }

    /**
     * 拉取模型列表。
     *
     * <p>失败不抛异常而是返回带 error 的 Result —— 因为「拉不到列表」是网络/配置的
     * 常见情况，界面需要把原因显示给主人（401 是 Key 错、超时是网络问题），
     * 而不是让调用方去猜。
     */
    public Result fetch() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint()).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (!apiKey.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                return new Result(null, "鉴权失败（HTTP " + code + "），请检查 API Key");
            }
            if (code != 200) {
                String err = readAll(c.getErrorStream());
                return new Result(null, "HTTP " + code + (err.isEmpty() ? "" : "：" + truncate(err, 200)));
            }
            String body = readAll(c.getInputStream());
            List<Model> list = parseModels(body);
            if (list.isEmpty()) {
                return new Result(null, "服务返回了空列表");
            }
            return new Result(list, null);
        } catch (SocketTimeoutException e) {
            return new Result(null, "请求超时，检查网络或接口地址");
        } catch (UnknownHostException e) {
            return new Result(null, "无法解析地址，检查接口地址是否写错");
        } catch (Throwable t) {
            return new Result(null, "请求失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + truncate(t.getMessage(), 120)));
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 解析模型列表回包。
     *
     * <p>兼容两种形状（不同服务商不一致）：
     * <ul>
     *   <li>OpenAI 标准：{@code {"data":[{"id":"gpt-4",...}, ...]}}</li>
     *   <li>裸数组：{@code [{"id":"..."}, ...]} 或 {@code ["model-a","model-b"]}</li>
     * </ul>
     * 不引入 JSON 库 —— Android 侧可用 org.json，但 PC 单测没有，用定位法足够。
     */
    static List<Model> parseModels(String json) {
        List<Model> out = new ArrayList<Model>();
        if (json == null || json.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<String>();

        // 形状 1/2: 对象数组里的 "id":"xxx"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String id = unescape(m.group(1)).trim();
            if (!id.isEmpty() && seen.add(id)) out.add(new Model(id, null));
        }

        // 形状 3: 纯字符串数组 ["a","b"] —— 只在没找到 id 字段时尝试
        if (out.isEmpty()) {
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                    "\"([A-Za-z0-9][A-Za-z0-9._:\\-/]{1,80})\"").matcher(json);
            while (m2.find()) {
                String id = m2.group(1);
                // 排除明显是字段名的项
                if (id.equals("data") || id.equals("object") || id.equals("model")
                        || id.equals("id") || id.equals("list") || id.equals("models")) continue;
                if (seen.add(id)) out.add(new Model(id, null));
            }
        }

        Collections.sort(out, new Comparator<Model>() {
            public int compare(Model a, Model b) { return a.id.compareToIgnoreCase(b.id); }
        });
        return out;
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

    private static String unescape(String s) {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
