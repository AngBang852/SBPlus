package com.sbplus.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载任务管理器 + 系统通知栏进度反馈。
 * 运行在浏览器(宿主)进程内。任务状态通过静态 Map 维护，供下载列表页读取。
 */
public class SbDownloadManager {

    public static final String CHANNEL_DOWNLOAD = "sbplus_downloads_v2";

    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_CONVERTING = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_FAILED = 4;

    public static class Task {
        public final String id;
        public String name;          // 文件名
        public int status;           // STATUS_*
        public long totalBytes = 0;  // 已下载字节
        public long totalSizeBytes = 0; // 源文件总大小(可预知时; 未知为0)
        public volatile long partCount = 0;   // 已完成分片
        public volatile long partTotal = 0;   // 总分片
        public volatile long speedBps = 0;    // 当前速度
        public String detail = "";   // 附加信息
        // lastBytes/lastTime 是**成对**使用的速度计算快照:worker 线程写入,
        // 且必须与前一行 totalBytes 的更新保持一致。声明为 volatile 是为了
        // 让通知栏(系统 UI 线程)读到完整的 64 位值——非 volatile 的 long
        // 在 32 位进程上有撕裂读风险,速度会瞬间显示为天文数字。
        // 复合更新的原子性由 MainHook.sProgressLock 保证。
        public volatile long lastBytes = 0;
        public volatile long lastTime = 0;
        public String outPath = "";  // 完成后的文件路径
        public String url = "";       // 源 URL(m3u8 续传重置用)
        public String kind = "m3u8"; // m3u8 / seg / zip
        public boolean zipped = false; // 是否 zip 打包任务(不参与暂停/续传)

        public Task(String id, String name) {
            this.id = id;
            this.name = name;
            this.lastTime = System.currentTimeMillis();
        }

        public int percent() {
            if (partTotal > 0) return (int) Math.min(100, partCount * 100 / partTotal);
            if (totalSizeBytes > 0) return (int) Math.min(100, totalBytes * 100 / totalSizeBytes);
            return 0;
        }
    }

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<String, Task>();

    /**
     * 任务展示顺序(与 TASKS 配对维护)。
     *
     * <p>用 CopyOnWriteArrayList 而非 ArrayList:原实现靠"所有访问点都恰好写在
     * {@code synchronized} 方法里"这个隐含约定来保证安全,任何一处漏加锁就会
     * 变成并发修改异常(下载进行中同时打开下载列表是最常见的触发路径)。
     * 换成 COW 后,读迭代(all())天然拿到一致快照,约定不再依赖人的记忆。
     * 任务量级为个位数,写时复制的开销可忽略。
     */
    private static final List<String> ORDER = new java.util.concurrent.CopyOnWriteArrayList<String>();

    /**
     * 可缩容的信号量。
     *
     * <p>为什么需要子类:{@code Semaphore.reducePermits(int)} 是 protected 的,
     * 外部无法直接收回许可,所以容量调小时只能重建对象——而重建正是在途下载
     * 把许可 release 到新对象上、导致并发上限被突破的根因。用一个子类把它暴露
     * 出来,就能全程复用同一个信号量实例,许可账目始终守恒。
     */
    private static final class ResizableSemaphore extends java.util.concurrent.Semaphore {
        ResizableSemaphore(int permits) { super(permits); }

        /** 收回 n 个许可(n>0)。若已全部借出则许可数变负,后续 release 先补负数。 */
        void shrink(int n) {
            if (n > 0) reducePermits(n);
        }
    }

    /** 全局任务并发信号量(控制同时下载的任务数). 容量由设置动态调整. */
    private static volatile ResizableSemaphore taskSem = null;
    private static int taskSemCap = -1;

    /**
     * 只保护信号量的「创建 / 调容」这两件瞬时动作。
     *
     * <p>以前这些动作直接挂在类的 monitor 上(方法 synchronized),而阻塞等待
     * 信号量许可(acquire)也写在同一个 synchronized 里 —— 这是**死锁级**的设计
     * 缺陷:并发槽占满时,后来的下载线程会抱着 SbDownloadManager 的类锁无限等待,
     * 于是同一个类里所有调用(register / all / post)全部被堵住。实测下载列表
     * 打开时 UI 线程卡在 all() 上 2.611s(ART 报 Long monitor contention),
     * 列表渲染成空、通知延迟,全是这一处造成的。
     *
     * <p>修法:acquire() 移到锁外,锁内只做 O(1) 的建对象/调许可,持锁时间趋近 0。
     */
    private static final Object SEM_LOCK = new Object();

    /**
     * 获取任务并发槽, 若并发已满则阻塞等待. 返回 true 表示获得槽位.
     *
     * <p>容量变化时做**增量调整**而非重建信号量。原实现直接 new 一个新 Semaphore:
     * 已在途的下载持有的是旧对象的许可,它们结束时调用 release() 会释放到新对象上,
     * 使可用许可数超过容量——并发上限被悄悄突破。这里改为按差值 release/shrink,
     * 保证全程只有一个 Semaphore 实例、许可账目始终守恒。
     */
    public static boolean acquireTaskSlot(int capacity) {
        try {
            if (capacity < 1) capacity = 1;
            ResizableSemaphore sem;
            synchronized (SEM_LOCK) {
                if (taskSem == null) {
                    taskSem = new ResizableSemaphore(capacity);
                    taskSemCap = capacity;
                } else if (taskSemCap != capacity) {
                    resizeLocked(capacity);
                }
                sem = taskSem;
            }
            // 阻塞等待必须在锁外:占满并发时这里会长时间停住,持锁等待会拖死
            // 同类其它调用(下载列表读取、任务注册、通知刷新)。
            sem.acquire();
            return true;
        } catch (Throwable t) { return true; }
    }

    /** 调整容量(调用方须持有 SbDownloadManager 的类锁)。 */
    private static void resizeLocked(int capacity) {
        int delta = capacity - taskSemCap;
        if (delta > 0) {
            taskSem.release(delta);
        } else {
            // 缩容:把多余许可收回。若当前已全部借出,许可数变负,后续 release
            // 先补负数,等效于"超额任务自然收敛",不会死锁也不会突破新上限。
            taskSem.shrink(-delta);
        }
        taskSemCap = capacity;
    }

    public static void releaseTaskSlot() {
        try {
            ResizableSemaphore s = taskSem;
            if (s != null) s.release();
        } catch (Throwable ignored) {}
    }

    /** 直接设置/调整全局任务并发容量(由下载设置调用). */
    public static void setParallelCapacity(int capacity) {
        try {
            if (capacity < 1) capacity = 1;
            synchronized (SEM_LOCK) {
                if (taskSem == null) {
                    taskSem = new ResizableSemaphore(capacity);
                    taskSemCap = capacity;
                    return;
                }
                if (taskSemCap == capacity) return;
                resizeLocked(capacity);
            }
        } catch (Throwable ignored) {}
    }


    // 下面这些方法**不再加 synchronized**:TASKS 是 ConcurrentHashMap、ORDER 是
    // CopyOnWriteArrayList,各自已保证并发安全;继续挂类锁只会让 UI 线程的 all()
    // 与下载线程争抢同一个 monitor(实测卡 2.6 秒),得不偿失。
    public static Task register(String id, String name) {
        Task t = new Task(id, name);
        TASKS.put(id, t);
        ORDER.add(id);
        try { MainModule.logMsg("[SBPlus] task registered " + id + " total=" + TASKS.size()
                + " cls=" + System.identityHashCode(SbDownloadManager.class)
                + " loader=" + System.identityHashCode(SbDownloadManager.class.getClassLoader())); } catch (Throwable ignored) {}
        return t;
    }

    public static Task get(String id) {
        return TASKS.get(id);
    }

    public static List<Task> all() {
        List<Task> list = new ArrayList<Task>();
        for (String id : ORDER) {
            Task t = TASKS.get(id);
            if (t != null) list.add(t);
        }
        return list;
    }

    private static final java.util.Set<String> CANCELLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> PAUSED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 已被用户从列表删除的任务(用于区分"还没注册"和"已删除"两种 TASKS 未命中). */
    private static final java.util.Set<String> REMOVED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记任务取消(中断下载循环 + 关通知). */
    public static void cancel(Context ctx, String id) {
        try {
            if (id != null) CANCELLED.add(id);
            if (id != null) PAUSED.remove(id);
        } catch (Throwable ignored) {}
        try {
            if (ctx == null) return;
            NotificationManager nm = nm(ctx);
            if (nm != null) nm.cancel(notifId(id));
        } catch (Throwable ignored) {}
    }

    /** 暂停任务(保留已下载分片, 不取消). */
    public static void pause(String id) {
        try { if (id != null) PAUSED.add(id); } catch (Throwable ignored) {}
    }

    /** 继续任务(清除暂停标记). */
    public static void resume(String id) {
        try { if (id != null) PAUSED.remove(id); } catch (Throwable ignored) {}
    }

    /** 任务是否已暂停. */
    public static boolean isPaused(String id) {
        return id != null && PAUSED.contains(id);
    }

    /** 任务是否被取消. */
    public static boolean isCancelled(String id) {
        return id != null && CANCELLED.contains(id);
    }

    /** 任务是否仍存在于列表(被删除=false). */
    public static boolean exists(String id) {
        return id != null && TASKS.containsKey(id);
    }

    public static Task remove(String id) {
        Task t = TASKS.remove(id);
        if (t != null) {
            ORDER.remove(id);
            try { REMOVED.add(id); } catch (Throwable ignored) {}
        }
        return t;
    }

    private static int notifId(String id) {
        return (id.hashCode() & 0x7fffffff) % 50000;
    }

    private static NotificationManager nm(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = nm(ctx);
            if (nm == null) return;
            // 渠道重要性 DEFAULT:有横幅+声音,低重要性在三星上会折叠进静默托盘,用户感知不到
            // 渠道重要性只在首次创建时生效,改名让升级设备重建渠道
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_DOWNLOAD);
            if (ch == null) {
                ch = new NotificationChannel(CHANNEL_DOWNLOAD,
                        "下载任务", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("SBPlus 下载进度通知");
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
        }
    }

    public static void post(Context ctx, Task t) {
        try {
            if (ctx == null || t == null) return;
            // 任务已被用户删除 → 关掉它的通知,不再推送。
            // 注意用 REMOVED 而不是 "TASKS 里没有":并发槽占满时下载线程可能还堵在
            // acquireTaskSlot 里,任务尚未 register,此时 TASKS 里查不到它是**正常**
            // 的中间态,若照旧 cancel,通知会在发出前就被自己撤掉(实测现象:
            // 下载全程无通知,只有结束瞬间闪一下)。
            if (REMOVED.contains(t.id)) {
                try {
                    NotificationManager nmx = nm(ctx);
                    if (nmx != null) nmx.cancel(notifId(t.id));
                } catch (Throwable ignored) {}
                return;
            }
            ensureChannel(ctx);
            NotificationManager nm = nm(ctx);
            if (nm == null) return;

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CHANNEL_DOWNLOAD);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download);
            b.setContentTitle("下载: " + t.name);
            b.setOngoing(t.status == STATUS_DOWNLOADING || t.status == STATUS_CONVERTING);
            b.setOnlyAlertOnce(true);
            b.setCategory(Notification.CATEGORY_PROGRESS);

            if (t.status == STATUS_DOWNLOADING) {
                b.setContentText(String.format("已完成 %d/%d 分片 · %s",
                        t.partCount, t.partTotal, fmtSpeed(t.speedBps)));
                if (t.partTotal > 0) {
                    b.setProgress(100, t.percent(), false);
                } else {
                    b.setProgress(0, 0, true);
                }
            } else if (t.status == STATUS_CONVERTING) {
                b.setContentText("正在转换 MP4...");
                b.setProgress(0, 0, true);
            } else if (t.status == STATUS_DONE) {
                b.setContentText("下载完成 ✔ · " + t.outPath);
                b.setProgress(0, 0, false);
                b.setSmallIcon(android.R.drawable.stat_sys_download_done);
                b.setAutoCancel(true);
            } else {
                b.setContentText("下载失败 · " + t.detail);
                b.setProgress(0, 0, false);
                b.setSmallIcon(android.R.drawable.stat_sys_download_done);
                b.setAutoCancel(true);
            }

            // 通知点击 -> 发广播, 由浏览器进程内的接收器弹出下载列表
            try {
                Intent i = new Intent("com.sbplus.browser.ACTION_SHOW_DOWNLOADS");
                i.setPackage(ctx.getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(ctx, notifId(t.id), i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.setContentIntent(pi);
            } catch (Throwable ignored) {}

            nm.notify(notifId(t.id), b.build());

            // 完成后延迟移除通知:留 20 秒给用户看到结果(4 秒太短,常被误以为没通知)
            if (t.status == STATUS_DONE || t.status == STATUS_FAILED) {
                final String fid = t.id;
                final int nid = notifId(t.id);
                new java.lang.Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            java.lang.Thread.sleep(20000);
                            try { nm.cancel(nid); } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                }).start();
            }
        } catch (Throwable ignored) {}
    }



    static String fmtSpeed(long bps) {
        try {
            if (bps >= 1048576) return String.format("%.1f MB/s", bps / 1048576.0);
            return (bps / 1024) + " KB/s";
        } catch (Throwable t) { return ""; }
    }
}
