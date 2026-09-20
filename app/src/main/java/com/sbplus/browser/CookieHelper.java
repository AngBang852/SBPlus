package com.sbplus.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * CookieHelper — 直接读写三星浏览器引擎的 Chromium cookie 数据库
 * (/data/data/com.sec.android.app.sbrowser/app_sbrowser/Default/Cookies)。
 *
 * 实测该库 value 为明文, 表结构为标准 Chromium cookies 表:
 *   host_key, name, value, path, expires_utc, is_secure, is_httponly, is_persistent, ...
 *
 * 本模块运行在浏览器进程内, 因此可直接 open 该 SQLite(读写)。
 *
 * <p>资源与事务约束(易踩坑):
 * <ul>
 *   <li>本类的每个公开方法都在 try/finally 中关闭 Cursor 与 SQLiteDatabase。
 *       原实现把 {@code c.close(); db.close();} 写在成功路径上——一旦
 *       rawQuery 或遍历抛异常就永久泄漏 fd + 库句柄,而 Cookie 导入/导出是
 *       循环调用,宿主进程长时间运行后会 fd 耗尽,导致后续所有下载/导出失败。</li>
 *   <li>{@link #setCookie} 的 DELETE + INSERT 必须在同一事务内。原实现无事务:
 *       DELETE 已生效而 INSERT 失败时,该站登录态被彻底删除,属数据丢失。</li>
 *   <li>Chromium 的 cookies 表随版本新增列(如 source_type / is_same_party)。
 *       写入前逐列探测存在性,避免跨浏览器版本时因列不存在而整条失败。</li>
 * </ul>
 */
public final class CookieHelper {

    private static final String COOKIE_DB = "/data/user/0/com.sec.android.app.sbrowser/app_sbrowser/Default/Cookies";

    /** 导入的 Cookie 需要长期有效。expires_utc 为 0 会被 Chromium 当会话 cookie,
     *  进程重启即清除——这正是"Cookie 导入功能不好用"的偶发原因。 */
    private static final long DEFAULT_MAX_AGE_MICROS = 365L * 24L * 3600L * 1000000L;

    private CookieHelper() {}

    private static String dbPath(Context ctx) {
        // 优先用 context.dataDir 构造(与探测一致), 失败用硬编码
        try {
            File f = new File(ctx.getApplicationInfo().dataDir, "app_sbrowser/Default/Cookies");
            if (f.exists()) return f.getAbsolutePath();
        } catch (Throwable ignored) {}
        return COOKIE_DB;
    }

    /**
     * 一条 cookie 库的汇总记录: 显示用的 host + 该 host 下 cookie 条数 + 真实的 host_key。
     *
     * <p>为什么要带 {@code rawKeys}: Chromium 里同一个网站在库里**可能有两行 host_key**
     * —— 带前导点的 {@code .example.com}(domain cookie, 子域共享)与不带点的
     * {@code example.com}(host-only cookie, 仅该主机)。旧实现把所有 host 去点后
     * 只按 {@code host_key=?} 精确查带点的形式, 于是 host-only cookie 恒匹配 0 行:
     * 列表显示 0 条、编辑框空白、保存时删 0 写 0 —— 用户会以为"cookie 丢了"。
     */
    public static final class HostEntry {
        public final String host;              // 显示用(无前导点)
        public final java.util.List<String> rawKeys = new ArrayList<>();  // 真实 host_key(可能 1~2 个)
        public int count;                      // 该 host 下 cookie 总数

        HostEntry(String h) { this.host = h; }
    }

    /**
     * 枚举所有有 cookie 的 host, 并把同网站的带点/不带点两种 host_key 归并成一条。
     *
     * <p>一次开库拿到全部 host_key + 计数, 替代旧实现"先列 host, 再逐个 host 开库查
     * 计数"的 N+1 模式(L3356 每个 host 各 openRO/close 一次, 主线程上数百 host 明显卡顿)。
     */
    public static List<HostEntry> listHostEntries(Context ctx) {
        List<HostEntry> out = new ArrayList<>();
        java.util.Map<String, HostEntry> byHost = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = null;
        try {
            db = openRO(ctx);
            if (db == null) return out;
            Cursor c = db.rawQuery(
                    "SELECT host_key, COUNT(*) FROM cookies GROUP BY host_key ORDER BY host_key", null);
            try {
                while (c.moveToNext()) {
                    String raw = c.getString(0);
                    int n = c.getInt(1);
                    if (raw == null || raw.isEmpty()) continue;
                    String disp = raw.startsWith(".") ? raw.substring(1) : raw;
                    if (disp.isEmpty()) continue;
                    HostEntry e = byHost.get(disp);
                    if (e == null) {
                        e = new HostEntry(disp);
                        byHost.put(disp, e);
                    }
                    e.rawKeys.add(raw);
                    e.count += n;
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            XposedBridgeLog("listHostEntries err: " + t);
        } finally {
            closeQuietly(db);
        }
        out.addAll(byHost.values());
        return out;
    }

    /**
     * 兼容旧调用: 只返回显示名列表(去点前缀, 已按带点/不带点归并)。
     */
    public static List<String> listHosts(Context ctx) {
        List<String> out = new ArrayList<>();
        for (HostEntry e : listHostEntries(ctx)) out.add(e.host);
        return out;
    }

    /** 把显示名还原成库里的全部候选 host_key(带点 + 不带点, 两者都试)。 */
    public static String[] rawKeyCandidates(String host) {
        if (host == null) return new String[0];
        String bare = host.startsWith(".") ? host.substring(1) : host;
        if (bare.isEmpty()) return new String[0];
        return new String[]{ "." + bare, bare };
    }

    /** SQL 片段: 同时匹配带点与不带点两种 host_key。 */
    public static String hostWhereClause() {
        return "(host_key=? OR host_key=?)";
    }

    /** 读取某 host 的所有 cookie 键值(保序)。返回 [name,value,path,is_secure,is_httponly][]. */
    public static List<String[]> readHostCookies(Context ctx, String rawHost) {
        List<String[]> out = new ArrayList<>();
        SQLiteDatabase db = null;
        try {
            db = openRO(ctx);
            if (db == null) return out;
            // 不再要求调用方先补点: 带点与不带点都查, 覆盖 domain cookie 与 host-only cookie
            String[] keys = rawKeyCandidates(rawHost);
            if (keys.length == 0) return out;
            Cursor c = db.rawQuery(
                    "SELECT name, value, path, is_secure, is_httponly FROM cookies"
                            + " WHERE " + hostWhereClause() + " ORDER BY name",
                    new String[]{ keys[0], keys[1] });
            try {
                while (c.moveToNext()) {
                    out.add(new String[]{ c.getString(0), c.getString(1), c.getString(2),
                            String.valueOf(c.getInt(3)), String.valueOf(c.getInt(4)) });
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            XposedBridgeLog("readHost err: " + t);
        } finally {
            closeQuietly(db);
        }
        return out;
    }

    /** 更新/插入单个 cookie(按 host+name+path 覆盖)。返回是否成功。 */
    public static boolean setCookie(Context ctx, String rawHost, String name, String value,
                                    String path, boolean secure, boolean httpOnly) {
        SQLiteDatabase db = null;
        try {
            db = openRW(ctx);
            if (db == null) return false;
            return setCookieWithDb(db, rawHost, name, value, path, secure, httpOnly);
        } catch (Throwable t) {
            XposedBridgeLog("set err: " + t);
            return false;
        } finally {
            closeQuietly(db);
        }
    }

    /** 复用已打开的库句柄写入单条 cookie(供批量路径使用,自身不开/关库)。 */
    private static boolean setCookieWithDb(SQLiteDatabase db, String rawHost, String name,
                                           String value, String path, boolean secure,
                                           boolean httpOnly) {
        if (db == null) return false;
        if (path == null || path.isEmpty()) path = "/";
        ContentValues cv = buildCookieRow(db, rawHost, name, value, path, secure, httpOnly);

        db.beginTransaction();                              // DELETE + INSERT 必须原子
        try {
            db.delete("cookies", "host_key=? AND name=? AND path=?",
                    new String[]{ rawHost, name, path });
            db.insertOrThrow("cookies", null, cv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    /**
     * 构造单条 cookie 的 ContentValues(含跨版本列探测)。
     * 2026-09-20 从 setCookieWithDb 提取,供 applyDiff 的单事务原子路径复用
     * (Android 不允许嵌套调用 setTransactionSuccessful,故外层事务里必须
     * 内联 DELETE+INSERT,不能再调 setCookieWithDb)。
     */
    private static ContentValues buildCookieRow(SQLiteDatabase db, String rawHost, String name,
                                                String value, String path, boolean secure,
                                                boolean httpOnly) {
        long now = System.currentTimeMillis() * 1000L;      // Chromium 用微秒

        ContentValues cv = new ContentValues();
        cv.put("creation_utc", now);
        cv.put("host_key", rawHost);
        cv.put("name", name);
        cv.put("value", value);
        cv.put("path", path);
        cv.put("expires_utc", now + DEFAULT_MAX_AGE_MICROS);
        cv.put("is_secure", secure ? 1 : 0);
        cv.put("is_httponly", httpOnly ? 1 : 0);
        cv.put("last_access_utc", now);
        cv.put("has_expires", 1);
        cv.put("is_persistent", 1);
        cv.put("priority", 1);
        // 逐列探测:旧版 Chromium 无这些列,新版有。硬编码列名会在跨版本时
        // 因 "no such column" 整条失败(catch 后仅返回 false,用户只看到
        // "导入失败"却不知原因)。
        if (hasColumn(db, "cookies", "source_scheme")) cv.put("source_scheme", 2);
        if (hasColumn(db, "cookies", "last_update_utc")) cv.put("last_update_utc", now);
        if (hasColumn(db, "cookies", "has_cross_site_ancestor")) cv.put("has_cross_site_ancestor", 0);
        return cv;
    }

    /** 删除某 host 全部 cookie(带点与不带点都删)。返回删除条数。 */
    public static int clearHost(Context ctx, String rawHost) {
        SQLiteDatabase db = null;
        try {
            db = openRW(ctx);
            if (db == null) return 0;
            String[] keys = rawKeyCandidates(rawHost);
            int total = 0;
            db.beginTransaction();
            try {
                if (keys.length > 0) total += db.delete("cookies", "host_key=?", new String[]{ keys[0] });
                if (keys.length > 1) total += db.delete("cookies", "host_key=?", new String[]{ keys[1] });
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return total;
        } catch (Throwable t) {
            XposedBridgeLog("clear err: " + t);
            return 0;
        } finally {
            closeQuietly(db);
        }
    }

    /**
     * 批量写入(覆盖同名)。返回成功数。
     *
     * <p>原实现逐条调用 {@link #setCookie},每条都 openRW + close 一次库并各自
     * 一个隐式事务——N 条 cookie 就是 N 次 open/close + 2N 次 execSQL。导入几十条
     * 时耗时可达数百毫秒,若在设置页主线程触发会造成明显卡顿甚至 ANR。
     * 这里改为复用同一个库句柄并在单个事务内批量提交。
     *
     * <p>kv 数组为 [name, value, path, is_secure, is_httponly](后三项可省)。
     * **必须把安全属性传进来** —— 旧实现在这里硬编码 {@code false,false},
     * 导致用户每编辑保存一次, 该站所有 cookie 的 Secure/HttpOnly 标记被抹掉,
     * 登录态随之失效。
     */
    public static int setCookies(Context ctx, String rawHost, List<String[]> kvs) {
        if (kvs == null || kvs.isEmpty()) return 0;
        int ok = 0;
        SQLiteDatabase db = null;
        try {
            db = openRW(ctx);
            if (db == null) return 0;
            db.beginTransaction();
            try {
                for (String[] kv : kvs) {
                    if (kv == null || kv.length < 2) continue;
                    if (kv[0] == null || kv[0].isEmpty()) continue;
                    String path = kv.length > 2 && kv[2] != null && !kv[2].isEmpty() ? kv[2] : "/";
                    boolean secure = kv.length > 3 && "1".equals(kv[3]);
                    boolean httpOnly = kv.length > 4 && "1".equals(kv[4]);
                    if (setCookieWithDb(db, rawHost, kv[0], kv[1], path, secure, httpOnly)) ok++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            XposedBridgeLog("setCookies err: " + t);
        } finally {
            closeQuietly(db);
        }
        return ok;
    }

    /**
     * 差量保存: 把编辑框里的 cookie 集合写回, **保留未改动的记录及其安全属性**。
     *
     * <p>为什么不能用"先 clearHost 再 setCookies": 那个老做法会把整站 cookie 全删
     * 再按默认值重建, 于是 path / Secure / HttpOnly / expires 全部丢失。用户只是想
     * 改一个值, 结果登录态被降级。这里改为:
     * <ol>
     *   <li>读出当前该 host 的全部记录(name+path 作键, 带全部属性)</li>
     *   <li>删除"输入框里已不存在"的键</li>
     *   <li>对仍然存在的键: 值没变就**整条不动**; 值变了只更新 value 与 last_update_utc</li>
     *   <li>对新增的键: 按输入框给的属性插入</li>
     * </ol>
     *
     * @param desired 目标集合, 每项 [name, value, path, is_secure, is_httponly]
     * @return 实际写入(新增+更新)的条数; -1 表示打开数据库失败
     */
    public static int applyDiff(Context ctx, String rawHost, List<String[]> desired) {
        SQLiteDatabase db = null;
        try {
            db = openRW(ctx);
            if (db == null) return -1;

            String[] keys = rawKeyCandidates(rawHost);
            if (keys.length == 0) return 0;

            // 1) 读出当前状态: "name\u0000path\u0000host_key" -> [value, secure, httpOnly]
            java.util.Map<String, String[]> existing = new java.util.LinkedHashMap<>();
            Cursor c = db.rawQuery(
                    "SELECT host_key, name, value, path, is_secure, is_httponly FROM cookies"
                            + " WHERE " + hostWhereClause(),
                    new String[]{ keys[0], keys[1] });
            try {
                while (c.moveToNext()) {
                    String hk = c.getString(0), nm = c.getString(1), vl = c.getString(2);
                    String pth = c.getString(3);
                    String sec = String.valueOf(c.getInt(4)), ho = String.valueOf(c.getInt(5));
                    existing.put(diffKey(nm, pth, hk), new String[]{ vl, sec, ho });
                }
            } finally {
                c.close();
            }

            // 2) 目标集合
            java.util.Set<String> want = new java.util.HashSet<>();
            for (String[] kv : desired) {
                if (kv == null || kv.length < 2 || kv[0] == null || kv[0].isEmpty()) continue;
                String path = kv.length > 2 && kv[2] != null && !kv[2].isEmpty() ? kv[2] : "/";
                want.add(kv[0] + "\u0000" + path);
            }

            long now = System.currentTimeMillis() * 1000L;
            db.beginTransaction();
            try {
                // 3) 删掉输入框里已经没有的
                for (java.util.Map.Entry<String, String[]> e : existing.entrySet()) {
                    String[] parts = e.getKey().split("\u0000", -1);
                    if (parts.length < 3) continue;
                    if (!want.contains(parts[1] + "\u0000" + parts[2])) {
                        db.delete("cookies", "host_key=? AND name=? AND path=?",
                                new String[]{ parts[0], parts[1], parts[2] });
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            // 4) 写入/更新(逐条各自事务, 复用 setCookieWithDb)
            int written = 0;
            for (String[] kv : desired) {
                if (kv == null || kv.length < 2 || kv[0] == null || kv[0].isEmpty()) continue;
                String path = kv.length > 2 && kv[2] != null && !kv[2].isEmpty() ? kv[2] : "/";
                boolean secure = kv.length > 3 && "1".equals(kv[3]);
                boolean httpOnly = kv.length > 4 && "1".equals(kv[4]);

                // 已有且值完全相同、属性也一致 -> 不动它(保住 expires 等一切元数据)
                String[] cur = existing.get(diffKey(kv[0], path, keys[0]));
                if (cur == null) cur = existing.get(diffKey(kv[0], path, keys[1]));
                if (cur != null) {
                    boolean sameVal = java.util.Objects.equals(cur[0], kv[1]);
                    boolean sameSec = "1".equals(cur[1]) == secure;
                    boolean sameHo = "1".equals(cur[2]) == httpOnly;
                    if (sameVal && sameSec && sameHo) continue;
                }
                // 2026-09-20 修复:删除与插入合并进同一事务。原实现先提交删除、
                // 再另行 setCookieWithDb 插入,插入一旦失败(磁盘满/库忙/约束冲突),
                // 旧 cookie(往往是登录态)已被永久删除且无法回滚。
                // 注意不能直接外包事务调 setCookieWithDb:setTransactionSuccessful
                // 不允许嵌套调用,故这里内联 DELETE + INSERT。
                if (path == null || path.isEmpty()) path = "/";
                ContentValues cv = buildCookieRow(db, keys[0], kv[0], kv[1], path, secure, httpOnly);
                db.beginTransaction();
                try {
                    db.delete("cookies", "host_key=? AND name=? AND path=?",
                            new String[]{ keys[0], kv[0], path });
                    db.delete("cookies", "host_key=? AND name=? AND path=?",
                            new String[]{ keys[1], kv[0], path });
                    db.insertOrThrow("cookies", null, cv);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                written++;
            }
            return written;
        } catch (Throwable t) {
            XposedBridgeLog("applyDiff err: " + t);
            return -1;
        } finally {
            closeQuietly(db);
        }
    }

    private static String diffKey(String name, String path, String hostKey) {
        return hostKey + "\u0000" + name + "\u0000" + path;
    }

    /** 清空整站 cookie(带点与不带点都清)。返回删除条数。 */
    public static int clearHostAll(Context ctx, String host) {
        int total = 0;
        String[] keys = rawKeyCandidates(host);
        if (keys.length == 0) return 0;
        SQLiteDatabase db = null;
        try {
            db = openRW(ctx);
            if (db == null) return 0;
            db.beginTransaction();
            try {
                total += db.delete("cookies", "host_key=?", new String[]{ keys[0] });
                total += db.delete("cookies", "host_key=?", new String[]{ keys[1] });
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            XposedBridgeLog("clearHostAll err: " + t);
        } finally {
            closeQuietly(db);
        }
        return total;
    }

    /** 清空整个 cookie 库。返回删除条数。 */
    public static int clearAll(Context ctx) {
        SQLiteDatabase db = null;
        int n = 0;
        try {
            db = openRW(ctx);
            if (db == null) return 0;
            db.beginTransaction();
            try {
                n = db.delete("cookies", null, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            XposedBridgeLog("clearAll err: " + t);
        } finally {
            closeQuietly(db);
        }
        return n;
    }

    /**
     * 导出全部 cookie 为 Netscape cookie.txt 文本(可直接被 curl/yt-dlp/多数下载器 使用)。
     */
    public static String exportNetscape(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Netscape HTTP Cookie File\n");
        sb.append("# 由 SBPlus 导出\n\n");
        SQLiteDatabase db = null;
        try {
            db = openRO(ctx);
            if (db == null) return sb.toString();
            Cursor c = db.rawQuery(
                    "SELECT host_key, is_secure, path, is_httponly, expires_utc, name, value"
                            + " FROM cookies ORDER BY host_key, name", null);
            try {
                while (c.moveToNext()) {
                    String host = c.getString(0);
                    boolean secure = c.getInt(1) != 0;
                    String path = c.getString(2);
                    boolean httpOnly = c.getInt(3) != 0;
                    long expUtc = c.getLong(4);
                    String name = c.getString(5);
                    String value = c.getString(6);
                    if (host == null || name == null) continue;
                    long expSec = expUtc > 0 ? (expUtc / 1000000L) : 0L;
                    // Netscape 格式: domain  flag  path  secure  expiry  name  value
                    sb.append(host).append('\t')
                      .append(host.startsWith(".") ? "TRUE" : "FALSE").append('\t')
                      .append(path == null || path.isEmpty() ? "/" : path).append('\t')
                      .append(secure ? "TRUE" : "FALSE").append('\t')
                      .append(expSec).append('\t')
                      .append(name).append('\t')
                      .append(value == null ? "" : value).append('\n');
                    if (httpOnly) { /* Netscape 格式无 HttpOnly 列, 忽略 */ }
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            XposedBridgeLog("exportNetscape err: " + t);
        } finally {
            closeQuietly(db);
        }
        return sb.toString();
    }

    /**
     * 从 Netscape cookie.txt 文本导入。返回导入条数。
     * 每行: domain \t flag \t path \t secure \t expiry \t name \t value
     */
    public static int importNetscape(Context ctx, String text) {
        if (text == null || text.isEmpty()) return 0;
        SQLiteDatabase db = null;
        int ok = 0;
        try {
            db = openRW(ctx);
            if (db == null) return 0;
            db.beginTransaction();
            try {
                String[] lines = text.split("\r?\n");
                for (String line : lines) {
                    if (line == null) continue;
                    String ln = line.trim();
                    if (ln.isEmpty() || ln.startsWith("#")) continue;
                    String[] p = ln.split("\t", -1);
                    if (p.length < 7) continue;
                    String host = p[0];
                    String path = p[2] == null || p[2].isEmpty() ? "/" : p[2];
                    boolean secure = "TRUE".equalsIgnoreCase(p[3]);
                    String name = p[5];
                    String value = p[6];
                    if (host.isEmpty() || name.isEmpty()) continue;
                    if (setCookieWithDb(db, host, name, value, path, secure, false)) ok++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            XposedBridgeLog("importNetscape err: " + t);
        } finally {
            closeQuietly(db);
        }
        return ok;
    }

    /** 探测某列是否存在。跨 Chromium 版本表结构会变化,写入前必须确认。 */
    private static boolean hasColumn(SQLiteDatabase db, String table, String col) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int idx = c.getColumnIndex("name");
            if (idx < 0) return false;
            while (c.moveToNext()) {
                if (col.equals(c.getString(idx))) return true;
            }
        } catch (Throwable t) {
            XposedBridgeLog("hasColumn err: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void closeQuietly(SQLiteDatabase db) {
        if (db == null) return;
        try { db.close(); } catch (Throwable ignored) {}
    }

    private static SQLiteDatabase openRO(Context ctx) {
        try {
            return SQLiteDatabase.openDatabase(dbPath(ctx), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) { XposedBridgeLog("openRO err: " + t); return null; }
    }
    private static SQLiteDatabase openRW(Context ctx) {
        try {
            return SQLiteDatabase.openDatabase(dbPath(ctx), null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable t) { XposedBridgeLog("openRW err: " + t); return null; }
    }

    private static void XposedBridgeLog(String m) {
        try { MainModule.logMsg("[SBPlus] CookieHelper " + m); }
        catch (Throwable ignored) {}
    }
}
