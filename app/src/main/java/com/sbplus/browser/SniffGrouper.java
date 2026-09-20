package com.sbplus.browser;

/**
 * 嗅探结果的分组与配对,以及下载任务的展示文案。
 *
 * <p>2026-09-17 从 MainHook 抽出。选取标准:<b>不引用 MainHook 的任何静态状态</b>。
 * 搬移时逐方法与原文比对,不含任何行为改动 —— 包括原实现里那些看起来
 * "可疑但其实是有意为之"的规则(例如 DASH 配对时"同一音频可配多个视频流,
 * 音频不标 used")。
 *
 * <p>本类只做纯粹的列表/字符串运算,不持有状态,不接触 Context 与视图树。
 * 翻译文案通过 {@link MainHook#T} 转发,以保持与抽离前逐字节一致。
 */
final class SniffGrouper {

    private SniffGrouper() {}

    /** 文案代理:转发到 MainHook 的私有 T(),保持文案一致。 */
    private static String T(String zh, String en) {
        return MainHook.T(zh, en);
    }

    /**
     * 识别分段组。返回 group 列表(每个 group 是 idxList 中属于同一分段视频的
     * 多个索引,按序号排序)。
     *
     * <p>只保留至少 2 个分片、且能按序号聚成一组的结果。B 站 DASH 的 m4s
     * 音视频分离流被明确排除 —— 它们各自是完整流,误当分片拼接会产出坏文件。
     */
    static java.util.List<java.util.List<Integer>> groupSegments(final java.util.List<Integer> idxList,
                                                                 final java.util.List<String> urls,
                                                                 final java.util.List<String> types) {
        java.util.List<java.util.List<Integer>> result = new java.util.ArrayList<java.util.List<Integer>>();
        try {
            // map: base -> ordered map of seq->idx
            java.util.Map<String, java.util.TreeMap<Integer, Integer>> map = new java.util.LinkedHashMap<String, java.util.TreeMap<Integer, Integer>>();
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                String[] sb = M3u8Helper.segmentInfo(url);
                String base = sb[0];
                if (base == null) continue;
                // B站 DASH m4s 音视频分离流不参与普通分段合并(避免把 -1视频/-2音频 当分段拼坏)
                if (url != null) {
                    String ul = url.toLowerCase();
                    if ((ul.contains("bilivideo.com") || ul.contains("upos-sz") || ul.contains("upgcx"))
                            && ul.contains(".m4s")) {
                        continue;
                    }
                }
                int seq = 0;
                try { seq = Integer.parseInt(sb[1]); } catch (Throwable ignored) { seq = 0; }
                java.util.TreeMap<Integer, Integer> m = map.get(base);
                if (m == null) { m = new java.util.TreeMap<Integer, Integer>(); map.put(base, m); }
                m.put(seq, Integer.valueOf(i));
            }
            for (java.util.Map.Entry<String, java.util.TreeMap<Integer, Integer>> e : map.entrySet()) {
                java.util.TreeMap<Integer, Integer> m = e.getValue();
                // 至少 2 个分片才合并
                if (m.size() < 2) continue;
                // 必须能按 1,2,3... 连续排序 (允许 0,1,2 或 1,2,3)
                java.util.List<Integer> seqs = new java.util.ArrayList<Integer>(m.keySet());
                java.util.List<Integer> group = new java.util.ArrayList<Integer>();
                for (Integer k : seqs) group.add(m.get(k));
                if (group.size() >= 2) result.add(group);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] groupSegments error: " + t);
        }
        return result;
    }

    /**
     * YouTube DASH 音视频配对。返回所有 [videoIdx, audioIdx] 对。
     *
     * <p>2026-09-18 新增。背景:此前 DASH 配对只认 B站域名
     * (bilivideo.com / upos-sz / upgcx),而 YouTube 的 adaptiveFormats
     * 同样是**视频与音频分离**(itag=137 是纯视频、140 是纯音频),
     * 结果是两路各自单独下载 —— 用户拿到"无声视频 + 独立音频"两个文件,
     * 体验与 B站"一键下载合成好的 mp4"不一致。
     *
     * <p>配对依据(区别于 B站的"路径尾号 -1/-2"规则):
     * YouTube 所有分片 URL 的路径都是 {@code /videoplayback},靠 **query 参数**
     * 区分流。因此以 {@code itag} 参数为身份:
     * 视频流取 itag,音频流取 itag;由于页面同时给出多档清晰度,
     * 这里返回**每一路视频 × 同一路音频**的全部组合(B站规则同样允许
     * 一个音频配多个视频),让用户既能选清晰度、又都能合出声音。
     *
     * <p>音频选择(2026-09-18 按用户要求确定):**优先 AAC(itag=140)**。
     * 理由:Opus(itag 249/250/251)虽然码率更高,但兼容性差 —— 部分安卓播放器、
     * 相册与第三方 App 无法解码,用户合并出来会"没声音"。AAC 是通用格式,
     * 因此**不选最高码率,而是优先 140**;仅当没有 140 时才退回其它音频。
     */
    static java.util.List<int[]> findYouTubeDashPairs(final java.util.List<Integer> idxList,
                                                      final java.util.List<String> urls,
                                                      final java.util.List<String> types) {
        java.util.List<int[]> result = new java.util.ArrayList<int[]>();
        try {
            java.util.List<Integer> vids = new java.util.ArrayList<Integer>();
            Integer bestAudio = null;
            int bestAudioScore = -1;
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                if (url == null) continue;
                String lower = url.toLowerCase();
                // YouTube 分片特征:googlevideo 域名 + videoplayback 路径 + itag 参数
                if (!(lower.contains("googlevideo.com") || lower.contains("youtube.com"))) continue;
                if (lower.indexOf("videoplayback") < 0) continue;
                if (lower.indexOf("itag=") < 0) continue;
                String type = (i < types.size()) ? types.get(i) : "";
                boolean isAudio = "audio".equals(type) || lower.contains("mime=audio");
                boolean isVideo = "video".equals(type) || lower.contains("mime=video");
                if (isAudio) {
                    // 评分:越高越优先。AAC(140) 最高,其次 m4a/AAC 家族,最后才 Opus。
                    int score = 0;
                    long itag = extractLongParam(url, "itag");
                    if (itag == 140) score = 100;                       // AAC 128k —— 首选
                    else if (lower.contains("mp4a") || lower.contains("audio%2fmp4")) score = 80; // AAC 家族
                    else if (itag == 249 || itag == 250 || itag == 251) score = 20;  // Opus —— 兼容性差,降权
                    else score = 40;
                    // 同分时用码率兜底(取不到则 0,配合 score 比较不会漏选)
                    long bw = extractLongParam(url, "bitrate");
                    if (bw < 0) bw = 0;
                    if (bestAudio == null || score > bestAudioScore
                            || (score == bestAudioScore && bw > 0)) {
                        bestAudioScore = score;
                        bestAudio = Integer.valueOf(i);
                    }
                } else if (isVideo) {
                    vids.add(Integer.valueOf(i));
                }
            }
            if (bestAudio == null || vids.isEmpty()) return result;
            for (Integer vi : vids) {
                if (vi.intValue() == bestAudio.intValue()) continue;
                result.add(new int[]{vi.intValue(), bestAudio.intValue()});
            }
            return result;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] findYouTubeDashPairs error: " + t);
            return result;
        }
    }

    /**
     * 把用户**勾选的视频**与音频轨配对,用于"手动选中后自动合并下载"。
     *
     * <p>2026-09-18 从 YouTube 专用改为**通用**(按用户要求:"其他平台都一样")。
     * 适用于所有"视频/音频分离"的站点:YouTube(googlevideo)、抖音(douyinvod 的
     * media-audio-und-mp4a)、以及任何被嗅探标记为 video/audio 的组合。
     *
     * <p>配对规则(按用户要求"每个视频档位和每个音频档位都能相互选中匹配"):
     * <ol>
     *   <li>用户**同时勾了视频和音频** → 按勾选**全组合**配对(每个视频 × 每个音频);</li>
     *   <li>用户**只勾了视频** → 自动挑一路最佳音频(优先 AAC,见 {@link #audioScore})配对;
     *       这很关键:用户通常只勾画质,若不自动配音频,合出来就是无声视频;</li>
     *   <li>用户**只勾了音频** → 不配对,按普通下载处理。</li>
     * </ol>
     *
     * @return 每个 [videoIdx, audioIdx] 对;无法配对的内容留给后续普通下载流程。
     */
    static java.util.List<int[]> matchYouTubeSelection(final java.util.List<Integer> selected,
                                                       final java.util.List<String> urls,
                                                       final java.util.List<String> types) {
        java.util.List<int[]> result = new java.util.ArrayList<int[]>();
        try {
            if (selected == null || selected.isEmpty()) return result;
            // 1) 把勾选项分成 视频 / 音频 两组
            java.util.List<Integer> selVideos = new java.util.ArrayList<Integer>();
            java.util.List<Integer> selAudios = new java.util.ArrayList<Integer>();
            for (int i : selected) {
                if (i < 0 || i >= urls.size()) continue;
                String t = (i < types.size()) ? types.get(i) : "";
                if ("audio".equals(t)) selAudios.add(Integer.valueOf(i));
                else if ("video".equals(t)) {
                    // 已经是可独立播放的成品(自带音轨)就不参与配对,避免被拆开
                    if (!isSeparateVideoStream(urls.get(i))) continue;
                    selVideos.add(Integer.valueOf(i));
                }
            }
            if (selVideos.isEmpty()) return result;
            // 2) 确定要用的音频集合
            java.util.List<Integer> useAudios = selAudios;
            if (useAudios.isEmpty()) {
                // 用户只勾了视频:在**整份列表**里自动找最佳音频
                Integer best = null;
                int bestScore = -1;
                for (int i = 0; i < urls.size(); i++) {
                    String t = (i < types.size()) ? types.get(i) : "";
                    if (!"audio".equals(t)) continue;
                    int sc = audioScore(urls.get(i));
                    if (best == null || sc > bestScore) { bestScore = sc; best = Integer.valueOf(i); }
                }
                if (best == null) return result;   // 没有音频轨:不配对
                useAudios = new java.util.ArrayList<Integer>();
                useAudios.add(best);
            }
            // 3) 全组合配对
            for (Integer vi : selVideos) {
                for (Integer ai : useAudios) {
                    if (vi.intValue() == ai.intValue()) continue;
                    result.add(new int[]{vi.intValue(), ai.intValue()});
                }
            }
            return result;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] matchYouTubeSelection error: " + t);
            return result;
        }
    }

    /**
     * 是否是"需要另配音频"的纯视频流(分离式 DASH)。
     * YouTube 的 videoplayback、抖音的 media-video、B站的 m4s 都属此类;
     * 普通 mp4(自带音轨)则不是,不应被配对逻辑拆开。
     */
    private static boolean isSeparateVideoStream(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.contains("videoplayback")) return true;             // YouTube
        if (lower.contains("media-video")) return true;               // 抖音 DASH 视频
        if (lower.contains("douyinvod") && lower.endsWith(".m4s")) return true;
        if (lower.endsWith(".m4s")) return true;                      // 通用 m4s 分片
        return false;
    }

    /** 是否 YouTube 分片流(googlevideo + videoplayback + itag)。 */
    private static boolean isYouTubeStream(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return (lower.contains("googlevideo.com") || lower.contains("youtube.com"))
                && lower.indexOf("videoplayback") >= 0
                && lower.indexOf("itag=") >= 0;
    }

    /** 是否音频流。 */
    private static boolean isYouTubeAudio(String url, int idx, java.util.List<String> types) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        String type = (types != null && idx < types.size()) ? types.get(idx) : "";
        return "audio".equals(type) || lower.contains("mime=audio");
    }

    /** 音频优先级评分:AAC(140) 最高,Opus 最低(兼容性差)。 */
    private static int audioScore(String url) {
        try {
            String lower = url.toLowerCase();
            long itag = extractLongParam(url, "itag");
            if (itag == 140) return 100;
            if (lower.contains("mp4a") || lower.contains("audio%2fmp4")) return 80;
            if (itag == 249 || itag == 250 || itag == 251) return 20;
            return 40;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从 URL 的 query 里取一个整型参数值;取不到返回 -1。 */
    private static long extractLongParam(String url, String name) {
        try {
            if (url == null || name == null) return -1;
            int q = url.indexOf('?');
            if (q < 0) return -1;
            String query = url.substring(q + 1);
            String key = name + "=";
            int idx = query.indexOf(key);
            while (idx >= 0) {
                // 确保是完整参数名(前面是 & 或串首)
                if (idx == 0 || query.charAt(idx - 1) == '&') {
                    int end = idx + key.length();
                    int stop = end;
                    while (stop < query.length() && query.charAt(stop) != '&' && query.charAt(stop) != '#') stop++;
                    String val = query.substring(end, stop);
                    int pct = val.indexOf('%');
                    if (pct >= 0) val = val.substring(0, pct);
                    try { return Long.parseLong(val.trim()); } catch (Throwable t) { return -1; }
                }
                idx = query.indexOf(key, idx + 1);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * B站 DASH 音视频配对检测。返回所有 [videoIdx, audioIdx] 对。
     * 规则:同 base(bilivideo m4s)下 video 尾号 -1 + audio 尾号 -2。
     */
    static java.util.List<int[]> findAllDashPairs(final java.util.List<Integer> idxList,
                                                  final java.util.List<String> urls,
                                                  final java.util.List<String> types) {
        java.util.List<int[]> result = new java.util.ArrayList<int[]>();
        try {
            // base -> audioIdx (bilivideo m4s 音频)
            java.util.Map<String, Integer> audioByBase = new java.util.HashMap<String, Integer>();
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                if (url == null) continue;
                String lower = url.toLowerCase();
                boolean bili = lower.contains("bilivideo.com") || lower.contains("upos-sz") || lower.contains("upgcx");
                if (!bili) continue;
                String path = url.split("[?#]")[0];
                String lowerPath = path.toLowerCase();
                if (!lowerPath.endsWith(".m4s") && !lowerPath.endsWith(".m4a")) continue;
                // 原实现直接 path.substring(0, path.lastIndexOf('.')):路径无点号时
                // 返回 -1 会抛 StringIndexOutOfBoundsException,被外层 catch 捕获后
                // 整个配对过程提前结束并静默返回半成品——该批 DASH 音视频对全部丢失。
                String noExt = M3u8Helper.stripExtension(path);
                if (noExt == null) continue;
                // 配对 key 只用路径部分(去域名): B站视频/音频走不同 CDN 节点域名,但路径相同
                String pkey = noExt;
                try {
                    int sch = pkey.indexOf("://");
                    if (sch >= 0) { int psl = pkey.indexOf('/', sch + 3); if (psl > 0) pkey = pkey.substring(psl); }
                } catch (Throwable ignored) {}
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?:^|[-_])(\\d+)[-_](\\d+)$").matcher(pkey);
                if (!mm.find()) continue;
                String base = pkey.substring(0, mm.start(1));
                if (base.isEmpty()) continue;
                String seq = mm.group(1);
                String type = (i < types.size()) ? types.get(i) : "";
                boolean isAudioType = "audio".equals(type);
                boolean looksAudio = isAudioType || seq.equals("2") || lower.contains("mime=audio") || lower.contains("audio/mp4");
                boolean looksVideo = !isAudioType && ("video".equals(type) || seq.equals("1") || lower.contains("mime=video") || lower.contains("video/mp4"));
                if (looksAudio && !looksVideo) audioByBase.put(base, Integer.valueOf(i));
            }
            // 第二遍: 找视频流与音频配对
            java.util.Set<Integer> used = new java.util.HashSet<Integer>();
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                if (url == null) continue;
                String lower = url.toLowerCase();
                if (!(lower.contains("bilivideo.com") || lower.contains("upos-sz") || lower.contains("upgcx"))) continue;
                String path = url.split("[?#]")[0];
                if (!path.toLowerCase().endsWith(".m4s")) continue;
                // 同 15765:原实现 lastIndexOf('.') 返回 -1 时抛异常,导致整批配对丢失
                String noExt = M3u8Helper.stripExtension(path);
                if (noExt == null) continue;
                String pkey2 = noExt;
                try {
                    int sch2 = pkey2.indexOf("://");
                    if (sch2 >= 0) { int psl2 = pkey2.indexOf('/', sch2 + 3); if (psl2 > 0) pkey2 = pkey2.substring(psl2); }
                } catch (Throwable ignored) {}
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?:^|[-_])(\\d+)[-_](\\d+)$").matcher(pkey2);
                if (!mm.find()) continue;
                String base = pkey2.substring(0, mm.start(1));
                String seq = mm.group(1);
                String type = (i < types.size()) ? types.get(i) : "";
                boolean isAudioType = "audio".equals(type);
                boolean looksVideo = !isAudioType && ("video".equals(type) || seq.equals("1") || lower.contains("mime=video") || lower.contains("video/mp4"));
                if (!looksVideo) continue;
                if (used.contains(Integer.valueOf(i))) continue;
                Integer ai = audioByBase.get(base);
                if (ai == null && audioByBase.size() == 1) ai = audioByBase.values().iterator().next();
                // 同一音频可配多个视频流(不同编码清晰度),音频不标 used; 视频标 used 防自身重复
                if (ai != null && ai.intValue() != i) {
                    result.add(new int[]{i, ai.intValue()});
                    used.add(Integer.valueOf(i));
                }
            }
            return result;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] findAllDashPairs error: " + t);
            return result;
        }
    }

    /** 根据已用时间与完成比例估算剩余时间。无可估算时返回空串。 */
    static String etaText(com.sbplus.browser.SbDownloadManager.Task t) {
        try {
            int p = t.percent();
            if (p <= 0 || p >= 100) return "";
            long elapsed = System.currentTimeMillis() - t.lastTime;
            if (elapsed < 0) elapsed = 0;
            long etaMs = (long)(elapsed * (100.0 / p - 1.0));
            long sec = etaMs / 1000;
            if (sec <= 0) return T("剩余 <1s", "<1s left");
            long hh = sec / 3600, mm = (sec % 3600) / 60, ss = sec % 60;
            String s = hh > 0 ? String.format("%dh%02dm", hh, mm) : (mm > 0 ? String.format("%dm%02ds", mm, ss) : String.format("%ds", ss));
            return T("剩余 ", "ETA ") + s;
        } catch (Throwable t2) { return ""; }
    }

    /** 任务状态行文案。失败时返回空串(不抛)。 */
    static String statusText(com.sbplus.browser.SbDownloadManager.Task t) {
        try {
            switch (t.status) {
                case com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING:
                    String sizeTxt = "";
                    if (t.totalSizeBytes > 0) {
                        sizeTxt = StrUtils.fmtSize(t.totalBytes) + "/" + StrUtils.fmtSize(t.totalSizeBytes);
                    } else if (t.totalBytes > 0) {
                        sizeTxt = StrUtils.fmtSize(t.totalBytes);
                    }
                    return T((!sizeTxt.isEmpty() ? sizeTxt + " · " : "") + "下载中 " + t.percent() + "%", (!sizeTxt.isEmpty() ? sizeTxt + " · " : "") + "DL " + t.percent() + "%");
                case com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING:
                    return T("转换 MP4 中...", "Converting MP4...");
                case com.sbplus.browser.SbDownloadManager.STATUS_DONE:
                    String sizeDone = t.totalSizeBytes > 0 ? StrUtils.fmtSize(t.totalSizeBytes) : (t.totalBytes > 0 ? StrUtils.fmtSize(t.totalBytes) : "");
                    return T((!sizeDone.isEmpty() ? sizeDone + " · " : "") + "已完成", (!sizeDone.isEmpty() ? sizeDone + " · " : "") + "Done");
                default:
                    return T("失败", "Failed");
            }
        } catch (Throwable t2) { return ""; }
    }
}
