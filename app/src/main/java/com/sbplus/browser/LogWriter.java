package com.sbplus.browser;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

/**
 * Logging facade used by the Xposed hook (running inside the Samsung Browser process).
 *
 * Each log line is reported to the SBPlus app's LogProvider (authority
 * com.sbplus.browser.log) via ContentResolver.insert, so it ends up stored in the module
 * app's own filesDir — where the app can then display / export / delete it and where the
 * retention cleanup lives. This sidesteps the SELinux restriction that blocks direct
 * cross-UID file writes from the browser process.
 *
 * Before the target app's Application context is captured, we only mirror to
 * XposedBridge.log (logcat).
 */
public final class LogWriter {

    private static final Uri LOG_URI = Uri.parse("content://com.sbplus.browser.log");

    private static volatile Context sContext;

    private LogWriter() {}

    /** Called once the target app Application context is captured. */
    public static void init(Context ctx) {
        if (ctx == null) return;
        sContext = ctx;
        MainModule.logMsg("[SBPlus] LogWriter ready (provider-backed)");
    }

    /** Log a message to logcat and forward it to the module app's log store. */
    public static void log(String tag, String msg) {
        String line = "[" + tag + "] " + msg;
        MainModule.logMsg("[SBPlus] " + line);

        Context ctx = sContext;
        if (ctx == null) return; // context not ready yet → logcat only

        try {
            ContentValues v = new ContentValues();
            v.put("tag", tag);
            v.put("msg", msg);
            ctx.getContentResolver().insert(LOG_URI, v);
        } catch (Throwable ignored) {
            // Best-effort logging; never let logging break the hook.
        }
    }

    // ---------------------------------------------------------------- 关于异步化的结论
    //
    // 2026-09-17 复核:审核报告 T-22 建议把本类改为「异步队列投递,消除 hook 侧
    // 跨进程阻塞」,理由是 ContentResolver.insert 会经 Binder 同步阻塞。
    //
    // 实测调用点后认定:**该改动收益不足、风险偏高,故不做**。依据:
    //   1. 全部 14 个调用点都是**低频事件边界**——模块加载、feature disabled、
    //      区域选择、onDownloadStarted、hook 注册失败等;没有一处位于绘制、
    //      滚动、解析等逐帧/逐请求路径上。
    //   2. 最频繁的一个是 onDownloadStarted,每次下载触发一次。
    //      即便单次 insert 付出数毫秒,也不构成用户可感知的卡顿。
    //   3. 异步化需要引入:队列、工作线程、退出时的 flush 语义、以及对
    //      「日志乱序/丢失」的新权衡。对一个 Best-effort 日志门面而言,
    //      复杂度增量明显超过它解决的问题。
    //
    // 若将来出现真正的高频日志需求(例如逐请求追踪),应当**在那条链路上**
    // 单独做批量缓冲,而不是把整个门面改成异步——后者会让低频调用也背上
    // 队列与线程的开销。
}
