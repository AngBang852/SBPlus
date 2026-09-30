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

        // 2026-10-04:兼容"已在事务中"的调用方(如 setCookies 的批量路径)。
        // Android 的 SQLiteDatabase 不允许嵌套 beginTransaction —— 外层已开事务时
        // 再调 setTransactionSuccessful 会抛 IllegalStateException(被上层 catch 吞掉,
        // 表现为"导入条数不对/静默失败")。这里按 inTransaction() 判断,只在自己
        // 未处于事务时才开事务。
        boolean ownTx = !db.inTransaction();
        if (ownTx) db.beginTransaction();
        try {
            db.delete("cookies", "host_key=? AND name=? AND path=?",
                    new String[]{ rawHost, name, path });
            db.insertOrThrow("cookies", null, cv);
            if (ownTx) db.setTransactionSuccessful();
        } finally {
            if (ownTx) db.endTransaction();
        }
        // 2026-10-04 实测补充(必读):**只写库不够**。
        // 实测目标设备 Cookies 库:value 列 30/30 为空,全部值以 "v10" 前缀加密存于
        // encrypted_value(系统密钥加密)。新版 Chromium 读 cookie 时只认 encrypted_value,
        // 因此仅写明文 value 列 → 引擎看不见 → 用户"改了 Cookie 却毫无效果"且无任何报错;
        // 引擎退出时还可能用内存态回写覆盖我们写的内容。
        // 我们无法生成合法密文(需要系统密钥),所以这里补一步:同时通过 CookieManager
        // 写入一次 —— 让**引擎自己**去加密落库。两步都做:SQLite 路径兼容老版本
        // (老版本读 value 列),CookieManager 路径保证新版本真正生效。
        applyToEngine(rawHost, name, value, path, secure, httpOnly);
        return true;
    }

    /**
     * 通过引擎写入一条 cookie(2026-10-04 新增)。
     *
     * <p>为什么需要:见 {@link #setCookieWithDb} 的说明 —— 新版 Chromium 只读
     * encrypted_value,直写 SQLite 的明文 value 列对引擎不可见。走 CookieManager
     * 让引擎自己完成加密与落库,这是唯一不触碰系统密钥的正确做法。
     */
    private static void applyToEngine(String rawHost, String name, String value,
                                      String path, boolean secure, boolean httpOnly) {
        try {
            String bare = rawHost == null ? "" : (rawHost.startsWith(".") ? rawHost.substring(1) : rawHost);
            if (bare.isEmpty() || name == null || name.isEmpty()) return;
            StringBuilder ck = new StringBuilder();
            ck.append(name).append('=').append(value == null ? "" : value);
            ck.append("; Path=").append(path == null || path.isEmpty() ? "/" : path);
            ck.append("; Domain=").append(bare);
            if (secure) ck.append("; Secure");
            if (httpOnly) ck.append("; HttpOnly");
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            // 先删同名的旧值(改值时避免新旧并存),再写新值
            try { cm.setCookie("https://" + bare + "/", name + "=; Path=/; Max-Age=0"); } catch (Throwable ignored) {}
            cm.setCookie("https://" + bare + "/", ck.toString());
            cm.setCookie("http://" + bare + "/", ck.toString());
            try { cm.flush(); } catch (Throwable ignored) {}   // 部分实现需要 flush 才落盘
        } catch (Throwable t) {
            XposedBridgeLog("applyToEngine err: " + t);
        }
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
        // 2026-10-04 实测补充:新版 Chromium 只读 encrypted_value,value 列**恒为空**时
        // 它才会走加密列。我们无法生成合法的 encrypted_value(需系统密钥,超出模块能力),
        // 因此这里做的是"让引擎能看见明文 value"的处理 —— 见 setCookieWithDb 的说明,
        // 那里会同步把写库结果再用 CookieManager 灌一次,确保引擎侧真正生效。
        if (hasColumn(db, "cookies", "encrypted_value")) {
            // 显式写入空 BLOB:该列 NOT NULL,不写会 insert 失败。
            cv.put("encrypted_value", new byte[0]);
        }
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
            // 2026-10-04:先记下待删的 name —— 事务提交后要逐条通知引擎删除,
            // 否则引擎侧仍持有内存/加密副本,用户会看到"删了还在"。
            java.util.List<String> namesToErase = new java.util.ArrayList<String>();
            try {
                Cursor nc = db.rawQuery("SELECT name FROM cookies WHERE host_key=? OR host_key=?",
                        new String[]{ keys[0], keys.length > 1 ? keys[1] : keys[0] });
                try {
                    while (nc.moveToNext()) {
                        String n = nc.getString(0);
                        if (n != null && !n.isEmpty()) namesToErase.add(n);
                    }
                } finally { nc.close(); }
            } catch (Throwable ignored) {}
            int total = 0;
            db.beginTransaction();
            try {
                if (keys.length > 0) total += db.delete("cookies", "host_key=?", new String[]{ keys[0] });
                if (keys.length > 1) total += db.delete("cookies", "host_key=?", new String[]{ keys[1] });
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            // 事务提交后再通知引擎(顺序重要:未提交时引擎可能读回旧值)
            eraseFromEngine(rawHost, namesToErase);
            return total;
        } catch (Throwable t) {
            XposedBridgeLog("clear err: " + t);
            return 0;
        } finally {
            closeQuietly(db);
        }
    }

    /**
     * 通过引擎删除若干 cookie(2026-10-04 新增)。
     *
     * <p>与 {@link #applyToEngine} 对称:直删 SQLite 对引擎不可见(新版 Chromium 的值
     * 存在加密列,引擎另有内存副本),必须让引擎自己把 cookie 置为过期。
     */
    private static void eraseFromEngine(String rawHost, java.util.List<String> names) {
        try {
            String bare = rawHost == null ? "" : (rawHost.startsWith(".") ? rawHost.substring(1) : rawHost);
            if (bare.isEmpty() || names == null || names.isEmpty()) return;
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            for (String n : names) {
                if (n == null || n.isEmpty()) continue;
                String expired = n + "=; Path=/; Max-Age=0; Domain=" + bare;
                try { cm.setCookie("https://" + bare + "/", expired); } catch (Throwable ignored) {}
                try { cm.setCookie("http://" + bare + "/", expired); } catch (Throwable ignored) {}
            }
            try { cm.flush(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridgeLog("eraseFromEngine err: " + t);
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
        // 2026-10-04:待同步到引擎的条目。刻意**不在循环里**逐条 applyToEngine ——
        // 那时事务尚未提交,引擎可能读回旧值;而且 N 条会触发 2N 次 CookieManager 调用。
        // 统一收集、事务提交后再灌,顺序与效率都正确。
        java.util.List<String[]> engineSync = new java.util.ArrayList<String[]>();
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
                    // 注意:这里必须用"不开事务"的写库版本,否则嵌套事务会失败。
                    // setCookieWithDb 已改为按 inTransaction() 自适应,可直接复用;
                    // 但它内部也会 applyToEngine —— 为保持"提交后统一灌"的语义,
                    // 这里改用内联写入 + 收集。
                    ContentValues cv = buildCookieRow(db, rawHost, kv[0], kv[1], path, secure, httpOnly);
                    db.delete("cookies", "host_key=? AND name=? AND path=?",
                            new String[]{ rawHost, kv[0], path });
                    db.insertOrThrow("cookies", null, cv);
                    engineSync.add(new String[]{ rawHost, kv[0], kv[1], path,
                            secure ? "1" : "0", httpOnly ? "1" : "0" });
                    ok++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            // 事务提交后再同步引擎(让引擎自己加密落库,新版 Chromium 唯一可靠路径)
            for (String[] e : engineSync) {
                applyToEngine(e[0], e[1], e[2], e[3], "1".equals(e[4]), "1".equals(e[5]));
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
                // 2026-10-04 实测补充:与 setCookieWithDb 同理 —— 新版 Chromium 只读
                // encrypted_value,只写明文 value 列引擎看不见。这里在**事务提交之后**
                // 通过 CookieManager 再写一次,让引擎自己加密落库(顺序很重要:事务未提交
                // 时引擎读到的仍是旧值)。这条是"编辑 Cookie 能生效"的关键。
                applyToEngine(keys[0], kv[0], kv[1], path, secure, httpOnly);
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
        // 2026-10-04 重构(实测驱动):原实现直接读 cookies 表的 value 列,但在当前
        // Chromium 上该列**恒为空** —— 实测目标设备 profile 的 Cookies 库:
        //   总行数 30,value 为空/空串 30 条(100%),encrypted_value 非空 30 条,
        //   且其前缀全为 76 31 30 = ASCII "v10",即全部用系统密钥(Android Keystore)加密。
        // 也就是说:导出的每一行值都是空串,用户拿到文件灌进 curl/yt-dlp 完全无效。
        // 这不是"部分字段丢失",是**导出功能整体失效**。
        //
        // 修法:值改从引擎取。CookieManager.getCookie(url) 返回的是引擎**已解密**的
        // 值(引擎自己持有密钥),且天然包含 httpOnly —— 无需触碰加密链路。
        // host 列表仍走 SQLite:host_key/name/path 是明文列,实测准确。
        StringBuilder sb = new StringBuilder();
        sb.append("# Netscape HTTP Cookie File\n");
        sb.append("# 由 SBPlus 导出\n\n");
        SQLiteDatabase db = null;
        try {
            db = openRO(ctx);
            if (db == null) return sb.toString();
            // 逐 host 取元数据(明文列):host_key / name / path / is_secure / is_httponly / expires_utc
            // 同时读 value 明文列 —— 老版本 Chromium 上它是有效的,作为引擎取不到时的回退。
            Cursor c = db.rawQuery(
                    "SELECT host_key, is_secure, path, is_httponly, expires_utc, name, value"
                            + " FROM cookies ORDER BY host_key, name", null);
            // 同一 host 的 CookieManager.getCookie 结果按 host 缓存,避免重复调用
            java.util.Map<String, String> engineCache = new java.util.HashMap<String, String>();
            try {
                while (c.moveToNext()) {
                    String host = c.getString(0);
                    boolean secure = c.getInt(1) != 0;
                    String path = c.getString(2);
                    boolean httpOnly = c.getInt(3) != 0;
                    long expUtc = c.getLong(4);
                    String name = c.getString(5);
                    String plainValue = c.getString(6);   // 老版本 Chromium 的明文值
                    if (host == null || name == null) continue;
                    // 值优先从引擎取(已解密,新版 Chromium 唯一可靠来源);
                    // 引擎取不到时回退到 value 明文列 —— 兼容老版本 Chromium,
                    // 也兼容"引擎尚未初始化/该 host 未被访问过"的情况。
                    String value = engineCookieValue(host, name, secure, engineCache);
                    if (value == null || value.isEmpty()) {
                        if (plainValue != null && !plainValue.isEmpty()) value = plainValue;
                    }
                    long expSec = expUtc > 0 ? (expUtc / 1000000L) : 0L;
                    // Netscape 格式: domain  flag  path  secure  expiry  name  value
                    // httpOnly:标准 Netscape 无此列,curl 用 "#HttpOnly_" 前缀表示。
                    // 实测 2/30 条为 httpOnly,不写前缀会让这些登录态在导入时降级。
                    if (httpOnly) sb.append("#HttpOnly_");
                    sb.append(host).append('\t')
                      .append(host.startsWith(".") ? "TRUE" : "FALSE").append('\t')
                      .append(path == null || path.isEmpty() ? "/" : path).append('\t')
                      .append(secure ? "TRUE" : "FALSE").append('\t')
                      .append(expSec).append('\t')
                      .append(name).append('\t')
                      .append(value == null ? "" : value).append('\n');
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
     * 从引擎取某个 cookie 的值(已解密)。
     *
     * <p>CookieManager 只能按 URL 取整条 cookie 串(形如 "a=1; b=2"),所以这里
     * 按 host 取一次、解析出所需 name,并按 host 缓存避免重复调用。
     * URL 用 https 优先(secure cookie 只在 https 下可见);取不到再用 http 试一次。
     */
    private static String engineCookieValue(String host, String name, boolean secure,
                                            java.util.Map<String, String> cache) {
        try {
            String bare = host.startsWith(".") ? host.substring(1) : host;
            if (bare.isEmpty()) return "";
            String raw = cache.get(bare);
            if (raw == null) {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                raw = "";
                try { raw = cm.getCookie("https://" + bare + "/"); } catch (Throwable ignored) {}
                if (raw == null || raw.isEmpty()) {
                    // 非 secure 的 cookie 在 https 下也可能可见,但为稳妥再用 http 试一次
                    try { raw = cm.getCookie("http://" + bare + "/"); } catch (Throwable ignored) {}
                }
                if (raw == null) raw = "";
                cache.put(bare, raw);
            }
            if (raw.isEmpty()) return "";
            // 解析 "name=value; name2=value2"
            for (String part : raw.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                if (p.substring(0, eq).equals(name)) return p.substring(eq + 1);
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
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
                    if (ln.isEmpty()) continue;
                    // 2026-10-04 兼容修复:curl 约定用 "#HttpOnly_" 前缀标记 httpOnly cookie。
                    // 原实现把**所有** '#' 开头的行当注释跳过,于是自己导出的带前缀行
                    // (以及 curl 导出的文件)会被整条丢弃 —— 往返一圈登录态就少了。
                    // 这里先识别该前缀,剥掉后按普通行解析。
                    boolean httpOnly = false;
                    if (ln.startsWith("#HttpOnly_")) {
                        httpOnly = true;
                        ln = ln.substring("#HttpOnly_".length()).trim();
                    } else if (ln.startsWith("#")) {
                        continue;   // 其余 '#' 行确实是注释
                    }
                    String[] p = ln.split("\t", -1);
                    if (p.length < 7) continue;
                    String host = p[0];
                    String path = p[2] == null || p[2].isEmpty() ? "/" : p[2];
                    boolean secure = "TRUE".equalsIgnoreCase(p[3]);
                    String name = p[5];
                    String value = p[6];
                    if (host.isEmpty() || name.isEmpty()) continue;
                    // httpOnly 标记按解析结果透传(原实现恒传 false,会静默降级登录态)
                    if (setCookieWithDb(db, host, name, value, path, secure, httpOnly)) ok++;
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

    /**
     * 探测某列是否存在。跨 Chromium 版本表结构会变化,写入前必须确认。
     *
     * <p>2026-10-04 性能修复:加列集合缓存。原实现每次调用都执行一次
     * {@code PRAGMA table_info(cookies)}(全表结构查询),而 {@link #buildCookieRow}
     * 每条 cookie 要问 4 个列 —— 批量导入 N 条就是 **4N 次 PRAGMA**。
     * 列集合在同一个库连接的生命周期内不会变,缓存后降为每连接一次。
     * 缓存键用库路径,避免不同库(理论上)串味;异常时不缓存,退化为原行为。
     */
    private static final java.util.Map<String, java.util.Set<String>> COLUMN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>>();

    private static boolean hasColumn(SQLiteDatabase db, String table, String col) {
        if (db == null) return false;
        String key = null;
        try { key = db.getPath(); } catch (Throwable ignored) {}
        if (key != null && table != null) {
            java.util.Set<String> cols = COLUMN_CACHE.get(key + "|" + table);
            if (cols != null) return cols.contains(col);
        }
        Cursor c = null;
        java.util.Set<String> found = new java.util.HashSet<String>();
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int idx = c.getColumnIndex("name");
            if (idx < 0) return false;
            while (c.moveToNext()) {
                String n = c.getString(idx);
                if (n != null) found.add(n);
            }
            // 探测成功才缓存(异常路径不缓存,下次仍按原逻辑重试)
            if (key != null && table != null && !found.isEmpty()) {
                COLUMN_CACHE.put(key + "|" + table, found);
            }
            return found.contains(col);
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
