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
        // 2026-10-04 修复(审查 S13):必须用 guard 包住任务体本身。
        // 原实现只 try 了 p.execute(r) —— 那只覆盖"提交"动作;任务体 r.run() 抛出的
        // RuntimeException/Error 会直达池线程,而 Android 的默认 UncaughtExceptionHandler
        // 收到未捕获异常时会**杀死整个进程**。本模块跑在三星浏览器进程里,于是任何一个
        // 下载/嗅探/预取任务抛异常,用户看到的就是浏览器闪退,且日志里只有模块的名字。
        // 这里统一在提交点包一层:池入口(bg/heavy/net)与下面的独立线程兜底都自动受保护,
        // 不必逐个调用点去补 try。
        final Runnable guarded = guard(r);
        try {
            p.execute(guarded);
        } catch (Throwable t) {
            // 队列饱和的极端兜底:退回旧行为(独立线程),任务不丢。
            // 兜底线程同样跑 guarded —— 否则"最该保护的异常路径"反而没有保护。
            try { new Thread(guarded).start(); } catch (Throwable ignored) {}
        }
    }

    /** 把任务体包成"异常绝不逃逸"的 Runnable:失败只记日志,不牵连宿主进程。 */
    private static Runnable guard(final Runnable r) {
        if (r == null) return null;
        return new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    // 与 MainHook 的日志门面一致:走 MainModule(转 LogWriter/logcat)。
                    // 日志本身再抛也不能让异常逃出去,故再套一层。
                    try {
                        MainModule.logMsg("[SBPlus] pool task failed: " + t);
                    } catch (Throwable ignored) {}
                }
            }
        };
    }

    /**
     * 关闭全部池（2026-10-04 新增，供热重载使用）。
     *
     * <p><b>为什么热重载必须调它</b>：libxposed 官方文档明确要求 ——
     * {@code onHotReloading} 返回 true（同意重载）之前，模块必须
     * "stop all module-owned Java and native threads"。
     * 若不停止，这些线程会持有**旧模块 classloader**，导致旧代码无法被回收
     * （官方文档：框架会释放它自己持有的引用，但"模块代码持有的引用"不会被释放），
     * 于是每次热重载都泄漏一整个 classloader。
     *
     * <p><b>用 shutdownNow 而非 shutdown</b>：shutdown 只是不再接受新任务、
     * 已排队任务仍会跑完，而队列里可能有上百个待执行任务（bg 池队列容量 256）——
     * 那意味着"同意重载"之后旧代码还在跑，违反官方要求。
     * shutdownNow 会中断在跑的任务并丢弃排队任务。
     *
     * <p><b>关闭后本代（旧代码）无法再用池</b>——这是**预期行为，不是缺陷**：
     * 热重载会换掉模块 classloader，新一代的 {@code SbExecutors} 是**全新的类**，
     * 上面那几个 static final 字段会在新代里重新初始化、得到全新的池。
     * 因此"旧池被关"只影响旧代码，而旧代码本就该被淘汰。
     * （注意：这依赖"热重载确实换了 classloader"这一前提；若某框架实现不换，
     * 则旧池关闭会导致后续任务全部失败 —— 届时会在日志里看到大量
     * "pool task failed: RejectedExecutionException"，可据此定位。）
     */
    static void shutdownAll() {
        for (java.util.concurrent.ThreadPoolExecutor p : new java.util.concurrent.ThreadPoolExecutor[]{
                BG, HEAVY, NET}) {
            try {
                java.util.List<Runnable> dropped = p.shutdownNow();
                MainModule.logMsg("[SBPlus] executor shutdown, dropped=" + dropped.size());
            } catch (Throwable t) {
                MainModule.logMsg("[SBPlus] executor shutdown error: " + t);
            }
        }
    }
}
