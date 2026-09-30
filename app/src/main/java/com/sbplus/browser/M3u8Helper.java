package com.sbplus.browser;

/**
 * m3u8 播放列表解析与 URL 工具。
 *
 * <p>2026-09-17 从 MainHook 抽出(该文件当时已 21346 行)。抽取标准只有一条:
 * <b>被搬出的方法不引用 MainHook 的任何静态状态</b> —— 经逐一核查,这一组
 * 全部通过(仅 {@link #httpGetText} 用到 MainModule 日志)。
 *
 * <p>本类只做两件事:把播放列表文本解析成分片 URL 列表;把相对 URL 归一化。
 * 不持有状态,不接触宿主视图树,不依赖 Context。
 */
final class M3u8Helper {

    private M3u8Helper() {}

    /**
     * 判断播放列表是否为「直播流」（live / event 流）。
     *
     * <p>判据（按 HLS 规范,命中任一即视为直播,全部不中才算点播）:
     * <ul>
     *   <li>不含 {@code #EXT-X-ENDLIST} —— 点播列表在末尾必有此标签,直播没有</li>
     *   <li>含 {@code #EXT-X-PLAYLIST-TYPE:EVENT} —— 追加型直播</li>
     *   <li>含 {@code #EXT-X-PLAYLIST-TYPE:VOD} 之外的 LIVE 声明,或滑动窗口特征
     *       ({@code #EXT-X-MEDIA-SEQUENCE} 存在 —— 滑动窗口直播会持续更新它)</li>
     * </ul>
     *
     * <p><b>必须传入媒体列表(media playlist)的内容</b>。master(多码率)列表
     * 从不含 ENDLIST,用它判断会把所有多码率点播误判成直播 —— 判据只对
     * 媒体列表成立。调用方见 MainHook.downloadM3u8Internal / downloadAndMergeSegments。
     */
    static boolean isLiveStream(String mediaPlaylistText) {
        try {
            if (mediaPlaylistText == null || mediaPlaylistText.isEmpty()) return false;
            String upper = mediaPlaylistText.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("#EXT-X-ENDLIST")) return false;
            if (upper.contains("#EXT-X-PLAYLIST-TYPE:EVENT")) return true;
            if (upper.contains("#EXT-X-PLAYLIST-TYPE:VOD")) return false;
            // 无 PLAYLIST-TYPE 时,ENDLIST 缺失 + MEDIA-SEQUENCE 滑动窗口是直播的典型特征
            if (upper.contains("#EXT-X-MEDIA-SEQUENCE")) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断播放列表是否**加密**(AES-128 / SAMPLE-AES)。
     *
     * <p>2026-10-04 新增(审查中等项):原 {@link #parseM3u8Ts} 会跳过所有 {@code #} 行,
     * 其中就包括 {@code #EXT-X-KEY:METHOD=AES-128,URI="..."}。于是加密流的分片被当作
     * **明文**下载,产出一个无法播放的文件 —— 用户只看到"下载完成",打开才发现是坏的,
     * 且中间文件已删无法重试。这里提供检测,由调用方快速失败并明确告知原因。
     *
     * <p>判据:出现 {@code #EXT-X-KEY} 且 METHOD 不是 NONE。
     * ({@code METHOD=NONE} 表示该段不加密,属合法情况。)
     *
     * @param mediaPlaylistText 媒体列表正文(master 列表不含 KEY 标签)
     */
    static boolean isEncryptedStream(String mediaPlaylistText) {
        try {
            if (mediaPlaylistText == null || mediaPlaylistText.isEmpty()) return false;
            String upper = mediaPlaylistText.toUpperCase(java.util.Locale.ROOT);
            int idx = upper.indexOf("#EXT-X-KEY");
            if (idx < 0) return false;
            // 取该标签所在行,检查 METHOD
            int eol = upper.indexOf('\n', idx);
            String line = (eol > 0) ? upper.substring(idx, eol) : upper.substring(idx);
            int mi = line.indexOf("METHOD=");
            if (mi < 0) return false;               // 无 METHOD 声明,不判定
            String method = line.substring(mi + "METHOD=".length()).trim();
            int comma = method.indexOf(',');
            if (comma > 0) method = method.substring(0, comma);
            method = method.trim();
            // METHOD=NONE 表示不加密(合法);其余(AES-128/SAMPLE-AES/…)都视为加密
            return !method.isEmpty() && !method.startsWith("NONE");
        } catch (Throwable t) {
            // 判定失败时保守返回 false —— 不因检测本身出错而阻断下载
            return false;
        }
    }

    /**
     * 解析 m3u8 内容,返回分片绝对 URL 列表;若是 variant(master)列表则返回
     * 其指向的子播放列表 URL(调用方需再取一次内容)。
     *
     * <p>注意:返回 {@code null} 表示解析失败(与"空列表"含义不同)。
     */
    static java.util.List<String> parseM3u8Segments(String content, String baseUrl) {
        try {
            if (content == null) return null;
            java.util.List<String> segs = new java.util.ArrayList<String>();
            boolean variant = false;
            String[] lines = content.split("\r?\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXT-X-STREAM-INF")) { variant = true; continue; }
                if (line.startsWith("#")) continue;
                if (variant) { segs.add(resolveUrl(line, baseUrl)); variant = false; }
            }
            return segs;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] parseM3u8 error: " + t);
            return null;
        }
    }

    /** 解析 m3u8 内容返回分片(.ts/.m4s) 绝对 URL 列表; variant 已展开。失败返回空列表。 */
    static java.util.List<String> parseM3u8Ts(String content, String baseUrl) {
        try {
            if (content == null) return new java.util.ArrayList<String>();
            java.util.List<String> segs = new java.util.ArrayList<String>();
            String[] lines = content.split("\r?\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue;
                String resolved = resolveUrl(line, baseUrl);
                String pl = resolved.split("[?#]")[0].toLowerCase();
                if (pl.endsWith(".ts") || pl.endsWith(".m4s") || pl.endsWith(".aac") || pl.endsWith(".mp3")) {
                    segs.add(resolved);
                }
            }
            return segs;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] parseM3u8Ts error: " + t);
            return new java.util.ArrayList<String>();
        }
    }

    /** 相对/绝对 URL 统一解析为绝对 URL。异常时原样返回,不抛。 */
    static String resolveUrl(String u, String base) {
        try {
            if (u == null) return base;
            if (u.startsWith("http://") || u.startsWith("https://")) return u;
            if (u.startsWith("//")) return "https:" + u;
            if (base == null) return u;
            int q = base.indexOf('?');
            String baseN = (q >= 0) ? base.substring(0, q) : base;
            int slash = baseN.lastIndexOf('/');
            String dir = (slash >= 0) ? baseN.substring(0, slash + 1) : baseN + "/";
            return dir + u;
        } catch (Throwable t) { return u; }
    }

    /** 下载 m3u8 获取文本内容(UTF-8)。失败返回 null。 */
    static String httpGetText(String url) {
        try {
            byte[] b = httpGetBytes(url);
            if (b == null) return null;
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] httpGetText error: " + t);
            return null;
        }
    }

    /**
     * GET 下载字节。
     *
     * <p>刻意保留原有的短超时(连接 5s / 读取 8s)与 Referer=url 的设置 ——
     * 后者是部分防盗链站点能返回内容的前提,改动会破坏现有可用性。
     */
    static byte[] httpGetBytes(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Referer", url);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return null; }
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) if (r > 0) bos.write(buf, 0, r);
            is.close(); conn.disconnect();
            return bos.toByteArray();
        } catch (Throwable ignored) { return null; }
    }

    /**
     * 返回 {base, seq}。若 URL 是分段 m4s/ts 等媒体分片则 base!=null;否则 base==null。
     * 用于把同一视频的连续分片归组。
     */
    static String[] segmentInfo(String url) {
        try {
            if (url == null) return new String[]{null, "0"};
            String path = url.split("[?#]")[0];
            String lower = path.toLowerCase();
            if (!lower.endsWith(".m4s") && !lower.endsWith(".ts") && !lower.endsWith(".mp4") && !lower.endsWith(".m4v")
                    && !lower.endsWith(".m4a") && !lower.endsWith(".aac") && !lower.endsWith(".mp3")
                    && !lower.endsWith(".ogg") && !lower.endsWith(".opus")) {
                return new String[]{null, "0"};
            }
            // 去掉扩展名后,找末尾的序号模式: -N / _N
            String noExt = stripExtension(path);
            if (noExt == null) return new String[]{null, "0"};
            // 匹配末尾 "-数字" 或 "_数字" (可以是 _数字_数字 等,取最后一段数字)
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([-_])(\\d+)$").matcher(noExt);
            if (mm.find()) {
                String base = noExt.substring(0, mm.start());
                String seq = mm.group(2);
                // 排除: base 为空 或 base 本身就是纯数字编号(如 foo/123/ )不构成分段
                if (base.isEmpty()) return new String[]{null, "0"};
                return new String[]{base, seq};
            }
            return new String[]{null, "0"};
        } catch (Throwable t) {
            return new String[]{null, "0"};
        }
    }

    /**
     * 去掉路径末尾的扩展名。无扩展名(找不到 '.')或 '.' 位于首字符时返回 null,
     * 由调用方跳过该条目。
     *
     * <p>替代原先直接使用的 {@code path.substring(0, path.lastIndexOf('.'))} ——
     * 后者在路径不含点号时(如 https://host/12345)lastIndexOf 返回 -1,
     * 抛 StringIndexOutOfBoundsException。
     */
    static String stripExtension(String path) {
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        if (dot <= 0) return null;
        return path.substring(0, dot);
    }
}
