package com.sbplus.browser;

/**
 * 模块内统一线程池。
 *
 * <p>2026-09-17 之前 MainHook 里有 20+ 处裸 {@code new Thread(...).start()}:
 * 版本检查、脚本更新、缩略图拉取、下载调度…… 各自无上限地创建线程。
 * 下载高峰时(嗅探出几十条媒体 + 分片下载)线程数完全不可控,宿主进程
 * 调度压力与内存占用随之膨胀。这里收敛为两个有界池:
 *
 * <ul>
 *   <li>{@link #bg} —— 轻量一次性任务:版本/脚本更新检查、缩略图、预取、
 *       对话框数据准备。核心 3 线程。</li>
 *   <li>{@link #heavy} —— 重量下载任务:m3u8/DASH 批量下载调度、任务续传。
 *       核心 2 线程。真正的并发度仍由 SbDownloadManager.acquireTaskSlot
 *       按用户配置控制,本池只负责「调度线程」本身不失控。</li>
 * </ul>
 *
 * <p><b>不收编的线程</b>(有意保留,勿"顺手优化"):
 * <ul>
 *   <li>{@code scheduleUserscriptAutoUpdate} 的 {@code while(true)} 调度循环 ——
 *       常驻线程,放进共享池会永久占死一个池线程;</li>
 *   <li>图标加载的 ThreadFactory(本身已是为专用池服务的工厂);</li>
 *   <li>各内部类的自建线程(如下载分片池)。</li>
 * </ul>
 *
 * <p>线程空闲 30 秒后自动退出(allowCoreThreadTimeOut),宿主进程常驻期间
 * 不会积累闲置线程。队列饱和(极端异常场景)时退回独立线程兜底,保证任务不丢。
 */
final class SbExecutors {

    private SbExecutors() {}

    private static final java.util.concurrent.ThreadPoolExecutor BG =
            pool("SBPlus-bg-", 3, 256);
    private static final java.util.concurrent.ThreadPoolExecutor HEAVY =
            pool("SBPlus-dl-", 2, 64);
    /** GM_xmlhttpRequest 异步桥专用:IO 密集、单请求有 15/30s 超时兜底,4 核足够。 */
    private static final java.util.concurrent.ThreadPoolExecutor NET =
            pool("SBPlus-xhr-", 4, 64);

    private static java.util.concurrent.ThreadPoolExecutor pool(final String prefix,
                                                                int core, int queueCap) {
        java.util.concurrent.ThreadPoolExecutor p = new java.util.concurrent.ThreadPoolExecutor(
                core, core, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(queueCap),
                new java.util.concurrent.ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger seq =
                            new java.util.concurrent.atomic.AtomicInteger();
                    @Override public Thread newThread(Runnable r) {
                        return new Thread(r, prefix + seq.incrementAndGet());
                    }
                });
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** 提交轻量一次性任务(版本检查/缩略图/预取等)。 */
    static void bg(Runnable r) { exec(BG, r); }

    /** 提交重量下载任务(批量下载调度/续传)。 */
    static void heavy(Runnable r) { exec(HEAVY, r); }

    /** 提交跨域 XHR 桥任务(GM_xmlhttpRequest 异步执行)。 */
    static void net(Runnable r) { exec(NET, r); }

    private static void exec(java.util.concurrent.ThreadPoolExecutor p, Runnable r) {
        try {
            p.execute(r);
        } catch (Throwable t) {
            // 队列饱和的极端兜底:退回旧行为(独立线程),任务不丢。
            try { new Thread(r).start(); } catch (Throwable ignored) {}
        }
    }
}
