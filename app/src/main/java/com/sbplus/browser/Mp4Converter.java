package com.sbplus.browser;

/**
 * TS/M4S → MP4 的封装与转码。
 *
 * <p>2026-09-17 从 MainHook 抽出(该文件当时已 21994 行)。抽取标准只有一条:
 * <b>这些方法不引用 MainHook 的任何静态状态</b> —— 经逐一核查,它们只用到
 * {@link MainModule#logMsg} 与 {@link MainHook#T}(日志与文案),因此可以整体
 * 搬出而不改变任何行为。
 *
 * <p>涉及三类工作:
 * <ul>
 *   <li>{@link #smartConvert} —— 入口:先尝试真转码,失败再退回纯 remux</li>
 *   <li>{@link #transcodeTsToMp4} —— 用 MediaCodec 解码再编码为 H.264+AAC</li>
 *   <li>{@link #tsToMp4} —— 纯 remux(MediaExtractor + MediaMuxer,不重编码)</li>
 *   <li>{@link #muxTwoFiles} —— 把分离的视频/音频文件合并成一个 MP4</li>
 * </ul>
 *
 * <p><b>注意</b>:本类的方法全部是纯函数式操作(输入文件 → 输出文件),
 * 不持有任何状态,也不接触宿主视图树。新增逻辑应保持这一性质。
 */
final class Mp4Converter {

    private Mp4Converter() {}

    /** 文案代理:原代码在 MainHook 内直接调用其私有 T(),搬出后转发回去,保持文案完全一致。 */
    private static String T(String zh, String en) {
        return MainHook.T(zh, en);
    }
    /** 双文件 mux 合并: 将 videoFile 的视频轨 + audioFile 的音频轨写入 mp4Out. */
    static java.io.File muxTwoFiles(java.io.File videoFile, java.io.File audioFile, java.io.File mp4Out,
                                     com.sbplus.browser.SbDownloadManager.Task task) {
        android.media.MediaExtractor vx = null, ax = null;
        android.media.MediaMuxer mx = null;
        try {
            vx = new android.media.MediaExtractor();
            vx.setDataSource(videoFile.getAbsolutePath());
            ax = new android.media.MediaExtractor();
            ax.setDataSource(audioFile.getAbsolutePath());
            int vTrack = -1, aTrack = -1;
            String vMime = null, aMime = null;
            android.media.MediaFormat vfmt = null, afmt = null;
            for (int i = 0; i < vx.getTrackCount(); i++) {
                android.media.MediaFormat f = vx.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vTrack = i; vMime = mime; vfmt = f; break; }
            }
            for (int i = 0; i < ax.getTrackCount(); i++) {
                android.media.MediaFormat f = ax.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { aTrack = i; aMime = mime; afmt = f; break; }
            }
            if (vTrack < 0 || aTrack < 0) {
                MainModule.logMsg("[SBPlus] muxTwoFiles: missing track v=" + vTrack + " a=" + aTrack + " vMime=" + vMime + " aMime=" + aMime + " vTracks=" + vx.getTrackCount() + " aTracks=" + ax.getTrackCount());
                return null;
            }
            MainModule.logMsg("[SBPlus] muxTwoFiles: vMime=" + vMime + " aMime=" + aMime);
            mx = new android.media.MediaMuxer(mp4Out.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int mv = mx.addTrack(vfmt);
            int ma = mx.addTrack(afmt);
            mx.start();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            // 写视频轨
            vx.selectTrack(vTrack);
            long vFirst = -1;
            while (true) {
                int sz = vx.readSampleData(buf, 0);
                if (sz < 0) break;
                long t = vx.getSampleTime();
                if (vFirst < 0) vFirst = t;
                info.offset = 0; info.size = sz;
                info.presentationTimeUs = t - vFirst;
                info.flags = (vx.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mx.writeSampleData(mv, buf, info);
                if (!vx.advance()) break;
                if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) return null;
            }
            // 写音频轨
            ax.selectTrack(aTrack);
            long aFirst = -1;
            while (true) {
                int sz = ax.readSampleData(buf, 0);
                if (sz < 0) break;
                long t = ax.getSampleTime();
                if (aFirst < 0) aFirst = t;
                info.offset = 0; info.size = sz;
                info.presentationTimeUs = t - aFirst;
                info.flags = (ax.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mx.writeSampleData(ma, buf, info);
                if (!ax.advance()) break;
                if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) return null;
            }
            mx.stop();
            mx.release(); mx = null;
            return mp4Out;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] muxTwoFiles error: " + t);
            try { MainModule.logMsg("[SBPlus] muxTwoFiles err detail: vEx=" + vx.getTrackCount() + " aEx=" + ax.getTrackCount() + " vFile=" + (videoFile != null ? videoFile.length() : -1) + " aFile=" + (audioFile != null ? audioFile.length() : -1)); } catch (Throwable ignored) {}
            try { mp4Out.delete(); } catch (Throwable ignored) {}
            return null;
        } finally {
            try { if (vx != null) vx.release(); } catch (Throwable ignored) {}
            try { if (ax != null) ax.release(); } catch (Throwable ignored) {}
            try { if (mx != null) mx.release(); } catch (Throwable ignored) {}
        }
    }
    static java.io.File smartConvert(final java.io.File tsFile, final String baseName,
                                      final com.sbplus.browser.SbDownloadManager.Task task,
                                      final android.content.Context ctx) {
        try {
            // 先试真转码(H.264+AAC 输出, 播放器 100% 兼容)
            java.io.File r = transcodeTsToMp4(tsFile, baseName, task, ctx);
            if (r != null && r.exists() && r.length() > 0) return r;
            MainModule.logMsg("[SBPlus] smartConvert transcode failed/unusable, fallback remux");
            return tsToMp4(tsFile, baseName, task, ctx);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] smartConvert error: " + t);
            return tsToMp4(tsFile, baseName, task, ctx);
        }
    }

    /** 用 MediaExtractor 读 TS, MediaMuxer 封装成 MP4 (纯 remux 不重编码)。返回 mp4 文件或 null。 */
    static java.io.File tsToMp4(final java.io.File tsFile, final String baseName,
                                  final com.sbplus.browser.SbDownloadManager.Task task,
                                  final android.content.Context ctx) {
        try {
            final android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            extractor.setDataSource(tsFile.getAbsolutePath());
            final int trackCount = extractor.getTrackCount();
            android.media.MediaMuxer muxer = null;
            final java.util.List<Integer> muxerTracks = new java.util.ArrayList<Integer>();
            try {
                final java.util.List<android.media.MediaFormat> formats = new java.util.ArrayList<android.media.MediaFormat>();
                for (int i = 0; i < trackCount; i++) {
                    final android.media.MediaFormat fmt = extractor.getTrackFormat(i);
                    formats.add(fmt);
                    String mime = "";
                    try { mime = fmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                    MainModule.logMsg("[SBPlus] tsToMp4 track[" + i + "] mime=" + mime);
                }
                if (formats.isEmpty()) { extractor.release(); return null; }
                final java.io.File out = new java.io.File(tsFile.getParentFile(), baseName + ".mp4");
                muxer = new android.media.MediaMuxer(out.getAbsolutePath(),
                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                for (int i = 0; i < trackCount; i++) {
                    android.media.MediaFormat fmt = formats.get(i);
                    // 音频若缺 csd-0, 尝试从首个 sample 的 ADTS 头补 AudioSpecificConfig
                    String mime = "";
                    try { mime = fmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                    // 2026-10-04 修复(审查 S15):原用 fmt.containsKey("csd-0") 判断 —— 但
                // MediaFormat.containsKey(String) 是 **API 29(Android 10)** 才加入的方法,
                // 本项目 minSdk 24。在 Android 7/8/9 上它抛 NoSuchMethodError,被本方法外层
                // 的 catch(Throwable) 吞掉 → tsToMp4 必然返回 null;而 tsToMp4 恰是
                // smartConvert 的兜底路径,于是"转码失败 → remux 兜底"整条链在低版本上全废。
                // 改用"取值判空"这一语义等价、全版本可用的写法。
                boolean hasCsd0;
                try { hasCsd0 = fmt.getByteBuffer("csd-0") != null; } catch (Throwable ignoredCsd) { hasCsd0 = false; }
                if (mime != null && mime.equals("audio/mp4a-latm") && !hasCsd0) {
                        byte[] cfg = decodeAacCsdFromExtractor(extractor, i);
                        if (cfg != null) {
                            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg));
                            MainModule.logMsg("[SBPlus] tsToMp4 audio csd-0 via decoder (adv)");
                            // 从 csd-0 (AudioSpecificConfig) 解析真实采样率/声道并覆盖 format(HE-AAC SBR 关键)
                            try {
                                int[] srch = parseAsc(cfg);
                                if (srch != null) {
                                    fmt.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, srch[0]);
                                    fmt.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, srch[1]);
                                    MainModule.logMsg("[SBPlus] tsToMp4 audio fmt synced sr=" + srch[0] + " ch=" + srch[1]);
                                }
                            } catch (Throwable ignoredAsc) {}
                        } else {
                            int[] p = sniffAacFromExtractor(extractor, i);
                            if (p != null) {
                                byte[] cfg2 = buildAudioSpecificConfig(p[0], p[1]);
                                if (cfg2 != null) {
                                    fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg2));
                                    MainModule.logMsg("[SBPlus] tsToMp4 audio csd-0 set sr=" + p[0] + " ch=" + p[1]);
                                }
                            }
                        }
                    }
                    muxerTracks.add(Integer.valueOf(muxer.addTrack(fmt)));
                }
                muxer.start();
                // 大 buffer: 高码率关键帧可达数 MB, 小 buffer 截断会写坏帧导致跳帧/花屏
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 * 1024 * 1024);
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                long firstPts = -1;
                long lastPts = -1;
                long videoPrevPts = -1;
                for (int ti = 0; ti < trackCount; ti++) {
                    firstPts = -1; lastPts = -1; videoPrevPts = -1;
                    // 转换进度: 按轨更新
                    if (task != null) {
                        task.detail = T("转换 ", "Converting ") + (ti + 1) + "/" + trackCount + T(" 轨", " tracks");
                        task.partCount = (ti + 1);
                        task.partTotal = trackCount;
                        com.sbplus.browser.SbDownloadManager.post(ctx, task);
                    }
                    for (int u = 0; u < trackCount; u++) { try { extractor.unselectTrack(u); } catch (Throwable ignored) {} }
                    extractor.selectTrack(ti);
                    String mime = "";
                    try { mime = formats.get(ti).getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                    boolean isAac = mime != null && mime.equals("audio/mp4a-latm");
                    boolean isVideo = mime != null && mime.startsWith("video/");
                    int idx = muxerTracks.get(ti).intValue();
                    boolean needSeek = true;
                    while (true) {
                        int sampleSize = extractor.readSampleData(buf, 0);
                        if (sampleSize < 0) break;
                        int off = 0, size = sampleSize;
                        if (isAac && sampleSize >= 7) {
                            // 完整跳过 ID3 标签 + ADTS 头(单帧或多帧都处理)
                            int pos = 0;
                            while (pos + 10 <= sampleSize
                                    && (buf.get(pos) & 0xFF) == 'I' && (buf.get(pos + 1) & 0xFF) == 'D' && (buf.get(pos + 2) & 0xFF) == '3') {
                                int tagSize = ((buf.get(pos + 6) & 0x7F) << 21) | ((buf.get(pos + 7) & 0x7F) << 14)
                                        | ((buf.get(pos + 8) & 0x7F) << 7) | (buf.get(pos + 9) & 0x7F);
                                pos += 10 + tagSize;
                                if (pos > sampleSize) { pos = sampleSize; break; }
                            }
                            if (pos + 7 <= sampleSize) {
                                byte b0 = buf.get(pos), b1 = buf.get(pos + 1);
                                if ((b0 & 0xFF) == 0xFF && (b1 & 0xF0) == 0xF0) {
                                    // 逐帧剥掉 ADTS 头, 帧体前移
                                    int src = pos, dst = pos;
                                    while (src + 7 <= sampleSize
                                            && (buf.get(src) & 0xFF) == 0xFF && (buf.get(src + 1) & 0xF0) == 0xF0) {
                                        int fl = ((buf.get(src + 3) & 0x03) << 11) | ((buf.get(src + 4) & 0xFF) << 3) | ((buf.get(src + 5) & 0xE0) >> 5);
                                        int h2 = ((buf.get(src + 1) >> 1) & 0x01) == 1 ? 7 : 9;
                                        if (fl < h2 || src + fl > sampleSize) break;
                                        System.arraycopy(buf.array(), src + h2, buf.array(), dst, fl - h2);
                                        dst += (fl - h2);
                                        src += fl;
                                    }
                                    if (dst > pos) {
                                        off = pos;
                                        size = dst - pos;
                                    }
                                }
                            }
                        }
                        info.offset = off;
                        info.size = size;
                        long pts0 = extractor.getSampleTime();
                        if (firstPts < 0) firstPts = pts0;
                        long pts = pts0 - firstPts;
                        // 音视频独立做时间戳归一化:
                        //  - 视频: PTS 原样(允许 B 帧回跳; 若回跳严重则匀速推进兜底)
                        //  - 音频: 防回退防 0, 保底+1000
                        if (isVideo) {
                            if (videoPrevPts >= 0 && pts + 20000 < videoPrevPts) {
                                // 严重回跳(>20ms): 说明 PTS 乱序严重, 匀速推进避免播放器跳帧
                                pts = videoPrevPts + 33333; // ~30fps 兜底
                            }
                            videoPrevPts = pts;
                            lastPts = pts;
                        } else {
                            if (pts < lastPts) pts = lastPts + 1000;
                            lastPts = pts;
                        }
                        info.presentationTimeUs = pts;
                        info.flags = (extractor.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        try {
                            muxer.writeSampleData(idx, buf, info);
                        } catch (Throwable we) {
                            if (needSeek) { needSeek = false; }
                        }
                        if (!extractor.advance()) break;
                        // 转换期间被取消: 中断并标记
                        if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) {
                            MainModule.logMsg("[SBPlus] tsToMp4 cancelled mid-convert");
                            try { out.delete(); } catch (Throwable ignored) {}
                            return null;
                        }
                    }
                }
                muxer.stop();
                muxer.release();
                muxer = null;
                if (task != null) { task.detail = T("转换完成", "Conversion done"); task.partCount = task.partTotal; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
                MainModule.logMsg("[SBPlus] tsToMp4 OK -> " + out.getAbsolutePath());
                return out;
            } finally {
                try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] tsToMp4 error: " + t);
            if (task != null) { task.detail = T("转换失败: ", "Conversion failed: ") + t; task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
            return null;
        }
    }

    /** 真正的转码: MediaCodec 解码任意视频/音频(HEVC/VP9/AV1/AAC...)再重编码为 H.264+AAC MP4。
     *  解决 remux 产物播放器不兼容导致的跳帧/无声音/花屏。 */
    private static java.io.File transcodeTsToMp4(final java.io.File tsFile, final String baseName,
                                          final com.sbplus.browser.SbDownloadManager.Task task,
                                          final android.content.Context ctx) {
        android.media.MediaExtractor extractor = null;
        android.media.MediaMuxer muxer = null;
        android.media.MediaCodec vDec = null, vEnc = null, aDec = null, aEnc = null;
        android.view.Surface encSurface = null;
        java.io.File out = null;
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) {
                MainModule.logMsg("[SBPlus] transcode requires API 23+, fallback remux");
                return tsToMp4(tsFile, baseName, task, ctx);
            }
            extractor = new android.media.MediaExtractor();
            extractor.setDataSource(tsFile.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) return null;
            android.media.MediaFormat vFmt = null, aFmt = null;
            int vTrack = -1, aTrack = -1;
            for (int i = 0; i < trackCount; i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = "";
                try { mime = f.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                if (mime != null) {
                    if (vTrack < 0 && mime.startsWith("video/")) { vFmt = f; vTrack = i; }
                    else if (aTrack < 0 && mime.startsWith("audio/")) { aFmt = f; aTrack = i; }
                }
            }
            boolean hasVideo = vTrack >= 0, hasAudio = aTrack >= 0;
            if (!hasVideo && !hasAudio) return null;
            final int[] aSrHolder = new int[]{44100, 2}; // 音频采样率/声道(方法级, 供两处使用)
            out = new java.io.File(tsFile.getParentFile(), baseName + ".mp4");
            muxer = new android.media.MediaMuxer(out.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int encVTrack = -1, encATrack = -1;
            boolean needVInfo = hasVideo, needAInfo = hasAudio;

            // ---------- 视频: 解码器 -> Surface -> H.264 编码器 ----------
            if (hasVideo) {
                String vMime = "";
                try { vMime = vFmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                int vw = 0, vh = 0;
                try { vw = vFmt.getInteger(android.media.MediaFormat.KEY_WIDTH); vh = vFmt.getInteger(android.media.MediaFormat.KEY_HEIGHT); } catch (Throwable ignored) {}
                if (vMime == null || vMime.isEmpty() || vw <= 0 || vh <= 0) throw new RuntimeException("bad video fmt");
                MainModule.logMsg("[SBPlus] transcode video " + vMime + " " + vw + "x" + vh);
                vDec = android.media.MediaCodec.createDecoderByType(vMime);
                // 保底: 有些封装 csd 缺失, 由解码器自己探测
                vEnc = android.media.MediaCodec.createEncoderByType("video/avc");
                int bitrate = Math.max(1200000, vw * vh * 4); // ~4Mbps@1080p, 低分辨率也保底
                android.media.MediaFormat encFmt = android.media.MediaFormat.createVideoFormat("video/avc", vw, vh);
                encFmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                encFmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
                encFmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30);
                encFmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                encFmt.setInteger(android.media.MediaFormat.KEY_BITRATE_MODE,
                        android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                encFmt.setInteger(android.media.MediaFormat.KEY_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                encFmt.setInteger(android.media.MediaFormat.KEY_LEVEL, android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42);
                vEnc.configure(encFmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
                encSurface = vEnc.createInputSurface();
                vEnc.start();
                // 解码器输出 Surface 直连编码器输入
                vDec.configure(vFmt, encSurface, null, 0);
                vDec.start();
                // 2026-10-04 修复(审查 S16):**不要**在 start() 后立刻 getOutputFormat() 建轨。
                // 编码器 start() 之后、首个输出帧之前,getOutputFormat() 多数设备上还不带
                // csd-0(H.264 的 SPS/PPS);带 csd 的格式是在 INFO_OUTPUT_FORMAT_CHANGED
                // 事件里给出的 —— 而主循环原先把该事件显式 ignore 了。
                // 后果:addTrack 抛异常或建出无 csd 的坏轨 → 真转码路径静默失败并回落到
                // remux,功能形同虚设。
                // 改为:在此只登记"待建轨",等主循环收到 FORMAT_CHANGED 时用那一刻的 format
                // 建轨(见下方 vFmtReady 分支)。
                needVInfo = true;
            }

            // ---------- 音频: 源已是 AAC, 直接 remux 复制(不重编码, 1秒完成无损不卡死) ----------
            boolean aRemux = false;
            if (hasAudio) {
                String aMime = "";
                try { aMime = aFmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                int sr = 0, ch = 0;
                try { sr = aFmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE); ch = aFmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT); } catch (Throwable ignored) {}
                if (sr <= 0) sr = 44100;
                if (ch <= 0) ch = 2;
                aSrHolder[0] = sr;
                aSrHolder[1] = ch;
                MainModule.logMsg("[SBPlus] transcode audio " + aMime + " sr=" + sr + " ch=" + ch + " -> remux copy");
                aRemux = true;
                try {
                    android.media.MediaFormat aOutFmt = aFmt;
                    encATrack = muxer.addTrack(aOutFmt);
                    needAInfo = false;
                } catch (Throwable at) {
                    MainModule.logMsg("[SBPlus] audio remux addTrack failed: " + at + " (audio will be skipped, video only)");
                    aRemux = false;
                    encATrack = -1;
                    needAInfo = false;
                }
            }
            muxer.start();

            final int TIMEOUT = 12000;
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 * 1024 * 1024);
            long totalUs = 0;
            String vMime2 = "";
            if (hasVideo) { try { vMime2 = vFmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {} }
            MainModule.logMsg("[SBPlus] transcode start, vTrack=" + vTrack + " aTrack=" + aTrack);

            // ---------- 视频转码主循环(解码->渲染->编码->mux) ----------
            if (hasVideo) {
                extractor.unselectTrack(vTrack);
                extractor.selectTrack(vTrack);
                int[] decIn = new int[0];
                byte[] decInBufs = null;
                boolean vEosIn = false, vEosOut = false;
                long vPts = 0;
                long vOutBase = -1, vLastOutPts = -1;
                android.media.MediaCodec.BufferInfo vInfo = new android.media.MediaCodec.BufferInfo();
                java.nio.ByteBuffer[] decOutBufs = null;
                int safety = 0;
                while (!vEosOut && safety < 400000) {
                    safety++;
                    if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) {
                        MainModule.logMsg("[SBPlus] transcode video cancelled");
                        try { out.delete(); } catch (Throwable ignored) {}
                        return null;
                    }
                    // 喂解码器输入
                    if (!vEosIn) {
                        int inIdx = vDec.dequeueInputBuffer(TIMEOUT);
                        if (inIdx >= 0) {
                            java.nio.ByteBuffer inBuf = vDec.getInputBuffer(inIdx);
                            int sz = extractor.readSampleData(inBuf, 0);
                            if (sz < 0) {
                                vDec.queueInputBuffer(inIdx, 0, 0, 0,
                                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                vEosIn = true;
                            } else {
                                long t = extractor.getSampleTime();
                                vDec.queueInputBuffer(inIdx, 0, sz, t, 0);
                                if (task != null && (task.partCount % 500 == 0)) {
                                    task.detail = T("转码中 ", "Transcoding ") + (t / 1000000) + "s";
                                    com.sbplus.browser.SbDownloadManager.post(ctx, task);
                                }
                                extractor.advance();
                            }
                        }
                    }
                    // 解码器输出: 渲染到编码器 Surface
                    android.media.MediaCodec.BufferInfo dInfo = new android.media.MediaCodec.BufferInfo();
                    int dOut = vDec.dequeueOutputBuffer(dInfo, 5000);
                    if (dOut >= 0) {
                        boolean render = dInfo.size > 0;
                        vDec.releaseOutputBuffer(dOut, render);
                        if ((dInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vEosOut = true;
                    } else if (dOut == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore(解码器输出格式变化与本流程无关:画面走 Surface 直连编码器)
                    }
                    // 编码器输出 -> muxer
                    int eOut = vEnc.dequeueOutputBuffer(vInfo, 5000);
                    if (eOut >= 0) {
                        java.nio.ByteBuffer eBuf = vEnc.getOutputBuffer(eOut);
                        if (vInfo.size > 0 && eBuf != null) {
                            // 2026-10-04(S16):轨必须在收到 FORMAT_CHANGED 后才建立。
                            // 若此处 encVTrack 仍为 -1,说明编码器还没给出带 csd 的输出格式
                            // (极端设备上首帧早于该事件) —— 此时写入会抛异常,直接跳过该帧
                            // 并记日志,而不是让它去污染 muxer。
                            if (encVTrack < 0) {
                                vEnc.releaseOutputBuffer(eOut, false);
                                if ((vInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vEosOut = true;
                                continue;
                            }
                            // PTS 归一化到 0 起点 + 强制单调(源 PTS 乱序/大数会导致播放器跳帧)
                            long p = vInfo.presentationTimeUs;
                            if (vOutBase < 0) vOutBase = p;
                            long np = p - vOutBase;
                            if (np < 0) np = 0;
                            if (vLastOutPts >= 0 && np <= vLastOutPts) np = vLastOutPts + 33333; // ~30fps 兜底顺延
                            vLastOutPts = np;
                            vInfo.presentationTimeUs = np;
                            eBuf.position(vInfo.offset);
                            eBuf.limit(vInfo.offset + vInfo.size);
                            try {
                                totalUs = np;
                                muxer.writeSampleData(encVTrack, eBuf, vInfo);
                            } catch (Throwable we) {
                                MainModule.logMsg("[SBPlus] transcode v write err: " + we);
                            }
                        }
                        vEnc.releaseOutputBuffer(eOut, false);
                        if ((vInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vEosOut = true;
                        if (task != null && (task.partCount % 500 == 0)) {
                            task.detail = T("转码 ", "Transcoding ") + (vInfo.presentationTimeUs / 1000000) + "s";
                            com.sbplus.browser.SbDownloadManager.post(ctx, task);
                        }
                    } else if (eOut == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 2026-10-04 修复(审查 S16):**在这里**建轨,而不是 start() 之后。
                        // 编码器给出的这个 format 才带 csd-0(SPS/PPS);用它 addTrack 才能
                        // 产出可正常解码的 H.264 轨。原实现忽略本事件、改用 start() 后立刻
                        // getOutputFormat() 的格式建轨,多数设备上该格式缺 csd → 真转码
                        // 路径静默失败并回落到 remux。
                        try {
                            android.media.MediaFormat vOutFmt = vEnc.getOutputFormat();
                            encVTrack = muxer.addTrack(vOutFmt);
                            needVInfo = false;
                            MainModule.logMsg("[SBPlus] transcode v track added on FORMAT_CHANGED, track="
                                    + encVTrack);
                        } catch (Throwable ae) {
                            MainModule.logMsg("[SBPlus] transcode v addTrack failed: " + ae);
                        }
                    }
                }
                // 关闭视频解码器/编码器
                try { vDec.stop(); vDec.release(); vDec = null; } catch (Throwable ignored) {}
                try { vEnc.stop(); vEnc.release(); vEnc = null; } catch (Throwable ignored) {}
                encSurface = null;
                MainModule.logMsg("[SBPlus] transcode video done, lastPts=" + totalUs);
            }

            // ---------- 音频转码主循环(解码->桥接->编码->mux) ----------
            // ---------- 音频: 源已是 AAC, 直接 remux 复制到 MP4(每秒千帧级, 不卡死无损) ----------
            if (hasAudio) {
                extractor.unselectTrack(aTrack);
                extractor.selectTrack(aTrack);
                java.nio.ByteBuffer aBuf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024);
                android.media.MediaCodec.BufferInfo aInfo = new android.media.MediaCodec.BufferInfo();
                long aBaseUs = -1, aLastPts = -1;
                int aSafety = 0;
                boolean aEos = false;
                while (!aEos && aSafety < 3000000) {
                    aSafety++;
                    if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) {
                        MainModule.logMsg("[SBPlus] transcode audio cancelled");
                        try { out.delete(); } catch (Throwable ignored) {}
                        return null;
                    }
                    aBuf.clear();
                    int sz = extractor.readSampleData(aBuf, 0);
                    if (sz < 0) {
                        aEos = true;
                        break;
                    }
                    long t = extractor.getSampleTime();
                    if (aBaseUs < 0) aBaseUs = t;
                    long np = t - aBaseUs;
                    if (np < 0) np = 0;
                    if (aLastPts >= 0 && np <= aLastPts) np = aLastPts + 1000; // 单调兜底
                    aLastPts = np;
                    aInfo.offset = 0;
                    aInfo.size = sz;
                    aInfo.presentationTimeUs = np;
                    aInfo.flags = 0;
                    aBuf.position(0);
                    aBuf.limit(sz);
                    try {
                        muxer.writeSampleData(encATrack, aBuf, aInfo);
                    } catch (Throwable we) {
                        MainModule.logMsg("[SBPlus] audio copy write err: " + we);
                    }
                    extractor.advance();
                    if (task != null && (aSafety % 5000 == 0)) {
                        task.detail = T("音频 ", "Audio ") + (np / 1000000) + "s";
                        com.sbplus.browser.SbDownloadManager.post(ctx, task);
                    }
                }
                MainModule.logMsg("[SBPlus] transcode audio done (remux copy, samples=" + aSafety + ")");
            }

            if (needVInfo || needAInfo) throw new RuntimeException("codec output format missing");
            muxer.stop();
            muxer.release();
            muxer = null;
            if (task != null) { task.detail = T("转换完成", "Conversion done"); task.partCount = task.partTotal; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
            MainModule.logMsg("[SBPlus] transcode OK -> " + out.getAbsolutePath() + " dur=" + (totalUs / 1000000) + "s");
            return out;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] transcodeTsToMp4 error: " + t);
            try { if (out != null) out.delete(); } catch (Throwable ignored) {}
            if (task != null) { task.detail = T("转换失败: ", "Conversion failed: ") + t; task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
            return null;
        } finally {
            try { if (aDec != null) aDec.release(); } catch (Throwable ignored) {}
            try { if (aEnc != null) aEnc.release(); } catch (Throwable ignored) {}
            try { if (vDec != null) vDec.release(); } catch (Throwable ignored) {}
            try { if (vEnc != null) vEnc.release(); } catch (Throwable ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
        }
    }
    /** 解析 AudioSpecificConfig: 返回 [采样率, 声道数], 失败 null。 */
    static int[] parseAsc(byte[] asc) {
        try {
            if (asc == null || asc.length < 2) return null;
            int b0 = asc[0] & 0xFF, b1 = asc[1] & 0xFF;
            int sfIdx = ((b0 & 0x07) << 1) | ((b1 >> 7) & 0x01);
            int chCfg = (b1 >> 3) & 0x0F;
            int[] srt = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                         16000, 12000, 11025, 8000, 7350};
            if (sfIdx >= 0 && sfIdx < srt.length && chCfg >= 1 && chCfg <= 7) {
                return new int[]{srt[sfIdx], chCfg};
            }
            // SBR/PS: 前 5 bit 是 audioObjectType=5(HE-AAC), 后跟 samplingFrequencyIndex 在更高位
            // 常见 HE-AAC: 0x2B 0x92... 直接尝试从第 2 字节解析
            // 注意: asc.length 可能恰好 2,访问 asc[2] 会越界,须先检查长度。
            if ((sfIdx > 12 || chCfg < 1 || chCfg > 7) && asc.length > 2) {
                int ext = ((asc[2] & 0xF8) >> 3) & 0x1F; // 简化: 尝试
                if (ext > 0 && ext < srt.length) return new int[]{srt[ext], chCfg > 0 && chCfg <= 7 ? chCfg : 2};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 解码 AAC 首帧拿真实 csd-0(正确处理 HE-AAC/SBR 采样率), 失败返回 null。
     *  原理: 用系统 MediaCodec 解码第一帧, 从输出 format 的 csd-0 得到播放器认的 ASC。 */
    static byte[] decodeAacCsdFromExtractor(android.media.MediaExtractor ext, int track) {
        try {
            int tcnt = ext.getTrackCount();
            for (int u = 0; u < tcnt; u++) { try { ext.unselectTrack(u); } catch (Throwable ignored) {} }
            ext.selectTrack(track);
            java.nio.ByteBuffer fb = java.nio.ByteBuffer.allocate(4096);
            int n = ext.readSampleData(fb, 0);
            if (n < 7) return null;
            byte[] raw = new byte[n];
            fb.position(0);
            fb.get(raw);
            // 剥 ADTS 头
            byte b0 = raw[0], b1 = raw[1];
            if ((b0 & 0xFF) == 0xFF && (b1 & 0xF0) == 0xF0) {
                int protectionAbsent = (b1 >> 1) & 0x01;
                int hdr = protectionAbsent == 1 ? 7 : 9;
                byte[] payload = new byte[n - hdr];
                System.arraycopy(raw, hdr, payload, 0, n - hdr);
                android.media.MediaCodec codec = null;
                try {
                    android.media.MediaFormat inFmt = new android.media.MediaFormat();
                    inFmt.setString(android.media.MediaFormat.KEY_MIME, "audio/mp4a-latm");
                    inFmt.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, 44100);
                    inFmt.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, 2);
                    codec = android.media.MediaCodec.createDecoderByType("audio/mp4a-latm");
                    codec.configure(inFmt, null, null, 0);
                    codec.start();
                    int inIdx = codec.dequeueInputBuffer(1000000);
                    if (inIdx >= 0) {
                        java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();
                        inBuf.put(payload);
                        long pts = ext.getSampleTime();
                        codec.queueInputBuffer(inIdx, 0, payload.length, pts, 0);
                    }
                    android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
                    int outIdx = codec.dequeueOutputBuffer(bi, 2000000);
                    if (outIdx >= 0) {
                        android.media.MediaFormat outFmt = codec.getOutputFormat();
                        java.nio.ByteBuffer csd = outFmt.getByteBuffer("csd-0");
                        if (csd != null) {
                            byte[] out = new byte[csd.remaining()];
                            csd.get(out);
                            return out;
                        }
                    }
                } catch (Throwable t) {
                    MainModule.logMsg("[SBPlus] decodeAacCsd error: " + t);
                } finally {
                    try { if (codec != null) codec.stop(); } catch (Throwable ignored) {}
                    try { if (codec != null) codec.release(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] decodeAacCsdFromExtractor error: " + t);
        }
        return null;
    }

    /** 从 extractor 选中 track 的首个 sample 读 ADTS 头, 返回 {采样率, 声道}. */
    static int[] sniffAacFromExtractor(android.media.MediaExtractor ext, int track) {
        try {
            int save = -1;
            try { save = ext.getSampleTrackIndex(); } catch (Throwable ignored) {}
            for (int u = 0; u < ext.getTrackCount(); u++) { try { ext.unselectTrack(u); } catch (Throwable ignored) {} }
            ext.selectTrack(track);
            java.nio.ByteBuffer hb = java.nio.ByteBuffer.allocate(64);
            int n = ext.readSampleData(hb, 0);
            if (n < 7) return null;
            byte b0 = hb.get(0), b1 = hb.get(1);
            if ((b0 & 0xFF) != 0xFF || (b1 & 0xF0) != 0xF0) return null;
            int sfIdx = (b1 >> 2) & 0x0F;
            int chan = ((b1 & 0x01) << 2) | ((hb.get(2) >> 6) & 0x03);
            int[] srTab = {96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
            int sr = (sfIdx >= 0 && sfIdx < srTab.length) ? srTab[sfIdx] : -1;
            return new int[]{sr, chan};
        } catch (Throwable t) { return null; }
    }

    static byte[] buildAudioSpecificConfig(int sr, int ch) {
        int sfIdx;
        switch (sr) {
            case 96000: sfIdx = 0; break;
            case 88200: sfIdx = 1; break;
            case 64000: sfIdx = 2; break;
            case 48000: sfIdx = 3; break;
            case 44100: sfIdx = 4; break;
            case 32000: sfIdx = 5; break;
            case 24000: sfIdx = 6; break;
            case 22050: sfIdx = 7; break;
            case 16000: sfIdx = 8; break;
            case 12000: sfIdx = 9; break;
            case 11025: sfIdx = 10; break;
            case 8000:  sfIdx = 11; break;
            default: sfIdx = -1;
        }
        if (sfIdx < 0 || ch < 1 || ch > 8) return null;
        int asc = (2 << 11) | (sfIdx << 7) | (ch << 3);
        return new byte[] { (byte)((asc >> 8) & 0xFF), (byte)(asc & 0xFF) };
    }
}
