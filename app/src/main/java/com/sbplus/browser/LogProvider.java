package com.sbplus.browser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.io.File;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * ContentProvider that stores the SBPlus module logs inside this app's own filesDir.
 *
 * The Xposed hook runs inside the Samsung Browser process (a different UID), so it cannot
 * write directly into this app's private storage. Instead it reports each log line via
 * ContentResolver.insert() on this provider, and we append it to the daily log file here
 * (same process/uid as the file, so no SELinux issue).
 *
 * The provider also owns retention cleanup (age + size), so the UI (LogManagerActivity)
 * can read/export/delete logs and edit retention settings all from within this app, with
 * no cross-uid file access needed.
 *
 * Access control: the provider is exported (the browser process must reach it) but every
 * entry point first goes through {@link #assertCallerAllowed()}, which accepts only
 *   - our own process, or
 *   - an app that holds {@code com.sbplus.browser.permission.ACCESS_LOG} (signature level,
 *     i.e. our own builds), or
 *   - the Samsung Browser package itself (the legitimate hook host).
 * Anything else is rejected with SecurityException. Without this, any installed app could
 * read the module's debug log (which contains page URLs and API parameters), forge
 * entries, or wipe the log with a single delete().
 *
 * Supported operations (authority com.sbplus.browser.log):
 *   insert  -> append a log line (values: "tag", "msg")
 *   query   -> read current logs (path "list" returns file names; path "content" returns text)
 *   delete  -> delete all log files
 *   call    -> "cleanup" triggers explicit retention cleanup; "get_path" returns the dir path
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.sbplus.browser.log";

    /** 唯一被允许调用本 provider 的第三方包(模块的 hook 宿主)。 */
    private static final String[] ALLOWED_CALLER_PACKAGES = {
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
    };

    private static final String LOG_DIR = "sbplus_logs";
    private static final String CONFIG_PREFS = "sbplus_log_config";
    private static final String KEY_KEEP_DAYS = "log_keep_days";
    private static final String KEY_MAX_MB = "log_max_mb";
    public static final int DEFAULT_KEEP_DAYS = 7;
    public static final int DEFAULT_MAX_MB = 10;

    /** 单次 query("content") 返回的日志字符上限。Binder 事务总上限约 1MB 且由整个
     *  事务共享,这里按 UTF-16 计(1 字符 ≈ 2 字节),64K 字符 ≈ 128KB,留足余量。
     *  原值 512K 字符 ≈ 1MB 字节,正好顶满上限,日志一多就抛
     *  TransactionTooLargeException——恰好在最需要排障时失效。 */
    private static final int MAX_QUERY_CHARS = 64 * 1024;

    /** 单条日志消息长度上限,防止超长 msg 放大单次 Binder 事务体积。 */
    private static final int MAX_MSG_CHARS = 8000;

    /**
     * 日期格式化。原实现用静态 {@link SimpleDateFormat} 承载,而 insert() 由
     * ContentProvider 的 Binder 线程池<b>并发</b>调用——SimpleDateFormat 非线程安全,
     * 并发 format() 会产出错误时间戳(日志被归入错误的日期文件),内部状态被破坏时
     * 还会抛 ArrayIndexOutOfBoundsException / NumberFormatException,而 insert()
     * 只捕获 IOException,运行时异常会穿透回 Binder 调用方。
     * 改为 ThreadLocal 持有,每线程一个实例。
     */
    private static final ThreadLocal<SimpleDateFormat> DAY_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                }
            };
    private static final ThreadLocal<SimpleDateFormat> TS_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    /** 串行化日志写入。多个 Binder 线程同时以 FileWriter(f, true) 追加同一文件时,
     *  行与行之间会交错、截断(append 模式的单次 write 不保证整行原子)。 */
    private static final Object sWriteLock = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * 校验调用方身份,不通过则抛 SecurityException。
     *
     * <p>判定顺序:同进程 / 持签名级权限 / 宿主浏览器包名白名单。
     * 注意 getCallingUid() 来自 Binder,不受调用方伪造。
     *
     * <p>fail-closed 原则:任何无法确认身份的路径一律拒绝。具体地——
     * <ul>
     *   <li>不再无条件放行 {@code uid == 0}:root/system 不在白名单内,应与其他
     *       调用方同等对待,否则任何 root 应用都能读走含页面 URL 的日志;</li>
     *   <li>{@code getContext() == null} 时拒绝而非放行:provider 冷启动存在
     *       "已注册但 getContext() 仍为 null" 的竞态窗口,原实现让该窗口内
     *       所有调用绕过校验。</li>
     * </ul>
     *
     * <p>注意本 provider <b>没有</b>在 manifest 声明 android:permission——因为合法
     * 调用方(宿主浏览器)与本应用签名不同,signature 级权限会直接切断日志链路。
     * 因此这里的方法体是<b>唯一</b>的准入防线,query/insert/delete/update/call
     * 五个入口必须逐一调用,不得依赖任何单点校验。
     */
    private void assertCallerAllowed() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;          // 自己的 Activity(LogManagerActivity)

        Context ctx = getContext();
        if (ctx == null) {
            // 原实现此处 return 直接放行;改为拒绝以关闭冷启动竞态窗口。
            throw new SecurityException(
                    "SBPlus LogProvider: context unavailable, denying uid " + uid);
        }

        PackageManager pm = ctx.getPackageManager();

        // 1) 持 signature 级权限者(同签名的其他构建)
        if (ctx.checkCallingPermission("com.sbplus.browser.permission.ACCESS_LOG")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // 2) 宿主浏览器包名白名单
        //    getPackagesForUid(uid) 证明"该 UID 下确实安装着这个包",这是本进程
        //    在 ContentProvider 里能拿到的最强身份证据(ContentProvider 无公开的
        //    getCallingPackage())。若日后改为经 Activity 转发,可在调用方侧
        //    用 getCallingActivity().getPackageName() 做更强的交叉验证。
        try {
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    for (String allowed : ALLOWED_CALLER_PACKAGES) {
                        if (allowed.equals(p)) return;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("SBPlus", "caller lookup failed", t);
        }

        throw new SecurityException("SBPlus LogProvider: caller uid " + uid
                + " is not permitted to access " + AUTHORITY);
    }

    private File logDir() {
        File dir = new File(getContext().getFilesDir(), LOG_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private int keepDays() {
        return getConfig().getInt(KEY_KEEP_DAYS, DEFAULT_KEEP_DAYS);
    }

    private long maxBytes() {
        int mb = getConfig().getInt(KEY_MAX_MB, DEFAULT_MAX_MB);
        return (long) mb * 1024L * 1024L;
    }

    private SharedPreferences getConfig() {
        return getContext().getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE);
    }

    /** Append a log line to the daily file, then occasionally run retention cleanup. */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        assertCallerAllowed();
        if (values == null) return uri;
        String tag = values.getAsString("tag");
        String msg = values.getAsString("msg");
        if (tag == null) tag = "?";
        if (msg == null) msg = "";
        // 净化:msg 内的裸换行会伪造出额外的日志行(如 "正常\n[SECURITY] ..."),
        // 污染日志并让"读日志排障"本身变得不可信;超长 msg 同理会放大事务体积。
        tag = tag.replace('\n', ' ').replace('\r', ' ');
        msg = msg.replace('\r', ' ');
        if (msg.length() > MAX_MSG_CHARS) {
            msg = msg.substring(0, MAX_MSG_CHARS) + "…(truncated)";
        }
        String line = "[" + tag + "] " + msg;

        synchronized (sWriteLock) {                 // 串行化:避免多线程交错写同一文件
            try {
                String day = DAY_FMT.get().format(new Date());
                File f = new File(logDir(), "sbplus_" + day + ".log");
                FileWriter fw = new FileWriter(f, true);
                try {
                    fw.write(TS_FMT.get().format(new Date()) + " " + line + "\n");
                } finally {
                    fw.close();
                }
            } catch (IOException e) {
                // 原实现静默吞掉:磁盘满、目录被删等情况下日志无声丢失,无从排障。
                android.util.Log.w("SBPlus", "log insert failed: " + e);
            }
            maybeCleanup();
        }
        return uri;
    }

    /**
     * 按修改时间<b>升序</b>(最旧在前)。
     *
     * <p>用于「按时间顺序读取日志正文」与「保留策略清理」:二者都要求
     * 先处理最旧的。清理逻辑尤其依赖这个方向——它从前往后累计大小、
     * 删到低于阈值为止,顺序反了会先删掉最新日志。
     */
    private static final Comparator<File> BY_MTIME_ASC = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return Long.compare(a.lastModified(), b.lastModified());
        }
    };

    /**
     * 按修改时间<b>降序</b>(最新在前)。
     *
     * <p>用于「日志文件列表」:用户需要先看到最新的。
     *
     * <p>注意与 {@link #BY_MTIME_ASC} 的区别——两者<b>不可互换</b>。
     * 代码审查时曾建议把三处排序"统一"成一个比较器,那会把列表顺序改反;
     * 故此处刻意保留两个方向,并用命名把各自意图固定下来。
     */
    private static final Comparator<File> BY_MTIME_DESC = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return Long.compare(b.lastModified(), a.lastModified());
        }
    };

    /** Query logs. path "content" -> full text of all logs; any other -> file-name list. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        assertCallerAllowed();
        String path = uri.getLastPathSegment();
        if ("content".equals(path)) {
            MatrixCursor c = new MatrixCursor(new String[]{"text"});
            c.addRow(new Object[]{readAllLogs()});
            return c;
        }
        File[] files = logDir().listFiles();
        MatrixCursor c = new MatrixCursor(new String[]{"name"});
        if (files != null) {
            // 列表要给用户看,最新的放最前 -> 降序。
            Arrays.sort(files, BY_MTIME_DESC);
            for (File f : files) c.addRow(new Object[]{f.getName()});
        }
        return c;
    }

    /** Delete all log files produced by this module. */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        assertCallerAllowed();
        int n = 0;
        File[] files = logDir().listFiles();
        if (files != null) {
            for (File f : files) {
                // 原实现无条件删除目录下所有文件。目录一旦被扩展用途(临时导出文件等),
                // 一次"删除日志"会误删无关文件,因此限定为本模块自己的日志文件。
                if (!isLogFile(f)) continue;
                if (f.delete()) n++;
            }
        }
        return n;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        assertCallerAllowed();
        Bundle out = new Bundle();
        if ("cleanup".equals(method)) {
            cleanup(logDir());
            out.putBoolean("ok", true);
        } else if ("get_path".equals(method)) {
            // 注意:返回的是本应用私有目录的绝对路径。跨 UID 的调用方
            // (宿主浏览器进程)对该路径**没有**读写权限,拿到后直接做文件
            // IO 会得到 EACCES。日志读写请一律走本 provider 的 insert/query。
            out.putString("path", logDir().getAbsolutePath());
        }
        return out;
    }

    @Override
    public String getType(Uri uri) { return "vnd.android.cursor.item/sbplus.log"; }

    @Override
    public int update(Uri uri, ContentValues values, String s, String[] sa) {
        // 原实现返回 0 且不校验调用方——本 provider 不支持更新,显式拒绝而非静默返回。
        assertCallerAllowed();
        throw new UnsupportedOperationException("SBPlus LogProvider does not support update");
    }

    /** 本模块日志文件的判定(文件名形如 sbplus_2026-08-22.log)。 */
    private static boolean isLogFile(File f) {
        String n = f.getName();
        return n.startsWith("sbplus_") && n.endsWith(".log");
    }

    /**
     * 读取全部日志(时间升序)用于 UI 展示,总长度截断到 {@link #MAX_QUERY_CHARS}。
     *
     * <p>两个必要约束:①按字符数截断——日志总量默认上限 10MB,直接塞进 MatrixCursor
     * 会让 Binder 事务超限(TransactionTooLargeException)导致读取直接崩;
     * ②按行流式读取——原实现 {@code new byte[(int) f.length()]} 会为大文件分配等量
     * 堆内存并假定一次 read 读完,既不安全也不必要。
     */
    private String readAllLogs() {
        File[] files = logDir().listFiles();
        StringBuilder sb = new StringBuilder();
        if (files == null) return "";
        // 正文按时间正序拼接:日志阅读顺序 = 发生顺序 -> 升序。
        Arrays.sort(files, BY_MTIME_ASC);
        java.io.BufferedReader br = null;
        try {
            for (File f : files) {
                if (sb.length() >= MAX_QUERY_CHARS) break;
                sb.append("===== ").append(f.getName()).append(" =====\n");
                br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"));
                String ln;
                while ((ln = br.readLine()) != null) {
                    sb.append(ln).append('\n');
                    if (sb.length() >= MAX_QUERY_CHARS) break;
                }
                try { br.close(); } catch (IOException ignored) {}
                br = null;
                sb.append('\n');
            }
        } catch (IOException ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
        if (sb.length() > MAX_QUERY_CHARS) {
            sb.setLength(MAX_QUERY_CHARS);
            sb.append("\n…（日志过长，已截断显示；完整内容请用「导出」）");
        }
        return sb.toString();
    }

    /** Trigger retention cleanup on roughly every insert (cheap; small file count). */
    private void maybeCleanup() {
        // Throttle: run at most once per second regardless of insert rate.
        long now = System.currentTimeMillis();
        if (now - sLastCleanup < 1000L) return;
        sLastCleanup = now;
        cleanup(logDir());
    }

    private static long sLastCleanup = 0;

    /** Enforce age + size retention on the log directory. */
    private void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        long now = System.currentTimeMillis();
        long ageCutoff = now - keepDays() * 24L * 3600L * 1000L;
        long maxBytes = maxBytes();

        // 清理必须从最旧的开始删 -> 升序。顺序反了会先删掉最新日志。
        Arrays.sort(files, BY_MTIME_ASC);

        long total = 0;
        for (File f : files) total += f.length();

        for (File f : files) {
            boolean agedOut = f.lastModified() < ageCutoff;
            boolean overBudget = total > maxBytes;
            if (!agedOut && !overBudget) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }
}
