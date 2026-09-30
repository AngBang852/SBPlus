package com.sbplus.browser;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BookmarkOrganizer —— 把分类结果落库：建文件夹 + 改 PARENT。
 *
 * <p>设计底线（三条不可协商）：
 * <ol>
 *   <li><b>只改 PARENT</b> —— 不动 TITLE / URL，不删任何行。浏览器书签没有回收站，
 *       删了就没了。</li>
 *   <li><b>只处理根目录下的书签</b> —— 主人自己分过的文件夹一律不碰。</li>
 *   <li><b>认不出的留原位</b> —— 不塞进「其他」。塞进去只会让主人更难找。</li>
 * </ol>
 *
 * <p>写入字段组合照抄 {@code MainHook.insertCheckedTree} 里已验证过的那一套，
 * 其中 {@code DIRTY=1} 必须带上，否则浏览器不同步这条改动。
 */
public final class BookmarkOrganizer {

    /** 整理结果统计。 */
    public static final class Stats {
        public int moved;            // 移动的书签数
        public int keptInPlace;      // 无法归类、保留原位的
        public int foldersCreated;   // 新建的文件夹数
        public int foldersReused;    // 复用的已有文件夹数
        public int failed;           // 写入失败的
        public final Map<String, Integer> perCategory = new java.util.LinkedHashMap<String, Integer>();
    }

    /** 一条待整理的书签（从库里读出的最小信息）。 */
    public static final class Entry {
        public long id;
        public int index;             // 书签序号，用于回查分类结果（不是列表下标！）
        public String title;
        public String url;
        public long parent;
        public String category;   // 目标路径；null = 保留原位
    }

    private BookmarkOrganizer() {}

    /**
     * 读取根目录下的全部书签（不含文件夹，也不递归进主人自建的文件夹）。
     *
     * <p>只读副本的安全做法参考 {@code MainHook.readBookmarkNodes}：BOOKMARKS 是视图，
     * 少一列会让整条 SELECT 失败、界面表现为「看不到书签」，所以可选列必须先探测。
     */
    public static List<Entry> readRootBookmarks(SQLiteDatabase db, long rootParent) {
        List<Entry> out = new ArrayList<Entry>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT _ID, TITLE, URL, PARENT FROM BOOKMARKS "
                    + "WHERE FOLDER=0 AND (DELETED IS NULL OR DELETED=0) AND PARENT=?", 
                    new String[]{ String.valueOf(rootParent) });
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(0);
                e.title = c.isNull(1) ? "" : c.getString(1);
                e.url = c.isNull(2) ? "" : c.getString(2);
                e.parent = c.getLong(3);
                out.add(e);
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] readRootBookmarks error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * 探测「根目录」的 PARENT 值。
     *
     * <p>三星的根书签 PARENT 不一定是 0（可能是特殊 ID）。探测方式是找那些
     * <b>自己的 PARENT 指向一个不存在的节点</b>的书签 —— 那就是根。
     * 探测不出来时退化为 0（与导入功能的默认一致）。
     */
    public static long detectRootParent(SQLiteDatabase db) {
        Cursor c = null;
        try {
            // 先试 0 —— 绝大多数三星版本根就是 0
            c = db.rawQuery("SELECT COUNT(*) FROM BOOKMARKS WHERE FOLDER=0 "
                    + "AND (DELETED IS NULL OR DELETED=0) AND PARENT=0", null);
            if (c.moveToNext() && c.getLong(0) > 0) {
                c.close();
                c = null;
                return 0L;
            }
            c.close();
            c = null;
            // 退化：找 PARENT 指向不存在的 _ID 的那些书签，取出现最多的 PARENT
            c = db.rawQuery("SELECT PARENT, COUNT(*) AS n FROM BOOKMARKS "
                    + "WHERE FOLDER=0 AND (DELETED IS NULL OR DELETED=0) "
                    + "AND PARENT NOT IN (SELECT _ID FROM BOOKMARKS) "
                    + "GROUP BY PARENT ORDER BY n DESC LIMIT 1", null);
            if (c.moveToNext()) {
                long p = c.getLong(0);
                MainModule.logMsg("[SBPlus] detectRootParent -> " + p + " (n=" + c.getLong(1) + ")");
                return p;
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] detectRootParent error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return 0L;
    }

    /**
     * 执行整理：按类目建文件夹并改书签的 PARENT。
     *
     * <p>调用方<b>必须</b>先完成备份和事务开启 —— 本方法只做写入，不做保护。
     *
     * @param db         已打开的库（READWRITE，且已 beginTransaction）
     * @param entries    待整理的条目（category 为 null 的会被跳过，保留原位）
     * @param rootParent 根目录的 PARENT 值
     * @return 统计
     */
    public static Stats organize(SQLiteDatabase db, List<Entry> entries, long rootParent) {
        Stats st = new Stats();

        // ---- 1. 为每条路径找到或创建文件夹 ----
        // category 可能是 "技术" 或 "技术/前端开发"。后者要建两层：
        // 先在根下找到/建出「技术」，再在它下面找到/建出「前端开发」。
        Map<String, Long> folderIds = new HashMap<String, Long>();
        for (Entry e : entries) {
            if (e.category == null || e.category.isEmpty()) {
                st.keptInPlace++;
                continue;
            }
            if (folderIds.containsKey(e.category)) continue;

            Long leaf = ensurePath(db, e.category, rootParent, st);
            if (leaf == null) {
                // 建不出来 -> 该类目整体放弃，条目保留原位（宁可不动，不要乱动）
                MainModule.logMsg("[SBPlus] ensurePath failed for: " + e.category);
                continue;
            }
            folderIds.put(e.category, leaf);
        }

        // ---- 2. 逐条改 PARENT ----
        for (Entry e : entries) {
            if (e.category == null || e.category.isEmpty()) continue;
            Long target = folderIds.get(e.category);
            if (target == null) { st.keptInPlace++; continue; }

            try {
                ContentValues cv = new ContentValues();
                cv.put("PARENT", target);
                cv.put("DIRTY", 1);         // 不带这个浏览器不会同步
                cv.put("MODIFIED", System.currentTimeMillis() / 1000);
                int n = db.update("BOOKMARKS", cv, "_ID=?",
                        new String[]{ String.valueOf(e.id) });
                if (n > 0) {
                    st.moved++;
                    Integer cur = st.perCategory.get(e.category);
                    st.perCategory.put(e.category, cur == null ? 1 : cur + 1);
                } else {
                    st.failed++;
                }
            } catch (Throwable t) {
                st.failed++;
                MainModule.logMsg("[SBPlus] move failed id=" + e.id + ": " + t);
            }
        }
        return st;
    }

    /** 在根目录下按名字找已存在的文件夹（幂等：重复整理不会重复建）。 */
    /**
     * 逐层建出 "大类/子类" 这样的路径，返回最末一级的文件夹 ID。
     *
     * <p>每一层都先查后建：同名文件夹已存在就复用，所以重复整理不会
     * 建出一堆「技术」「技术(1)」。
     *
     * @return 叶子文件夹 ID；任一层失败返回 null
     */
    static Long ensurePath(SQLiteDatabase db, String path, long rootParent, Stats st) {
        String[] parts = path.split("/");
        long parent = rootParent;
        Long last = null;
        for (String raw : parts) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            Long existing = findFolder(db, name, parent);
            if (existing == null && parent == rootParent) {
                // 第二道防线：根层查不到时，看看有没有「孤儿」——同名文件夹
                // 存在于别的父级下、且那个父级已经不存在了。AI 跨批漂移时会
                // 造出这种东西，复用它们比再造一个干净。
                Long orphan = findOrphanFolder(db, name);
                if (orphan != null) {
                    // 2026-10-04 修复:复用孤儿文件夹时必须把它**挂回新父级**。
                    // 原实现只借用它的 _ID(见下方 existing = orphan),却没有 UPDATE
                    // 它的 PARENT —— 于是这个文件夹的父级仍指向一个已不存在的 id,
                    // 整棵子树在浏览器里依然断链/不可见,"复用"只省了一次插入,
                    // 并没有真正修复结构。这里补一次 UPDATE。
                    // 注意:必须在借用之前改,否则 last 会指向一个仍断链的节点。
                    try {
                        android.content.ContentValues pv = new android.content.ContentValues();
                        pv.put("PARENT", rootParent);
                        pv.put("DIRTY", 1);   // 标记需同步,与项目其它写入一致
                        int n = db.update("BOOKMARKS", pv, "_ID=?",
                                new String[]{ String.valueOf(orphan) });
                        MainModule.logMsg("[SBPlus] orphan folder " + orphan
                                + " re-parented to " + rootParent + " (rows=" + n + ")");
                    } catch (Throwable te) {
                        MainModule.logMsg("[SBPlus] re-parent orphan failed: " + te);
                    }
                    MainModule.logMsg("[SBPlus] reuse orphan folder '" + name + "' id=" + orphan);
                    existing = orphan;
                }
            }
            if (existing != null) {
                last = existing;
                st.foldersReused++;
                MainModule.logMsg("[SBPlus] reuse folder '" + name + "' id=" + existing
                        + " under " + parent);
            } else {
                Long created = createFolder(db, name, parent);
                if (created == null) {
                    MainModule.logMsg("[SBPlus] create FAILED '" + name + "' under " + parent);
                    return null;
                }
                last = created;
                st.foldersCreated++;
                MainModule.logMsg("[SBPlus] created folder '" + name + "' id=" + created
                        + " under " + parent);
            }
            parent = last;
        }
        return last;
    }

    /**
     * 找一个「孤儿」同名文件夹：名字对得上、但它的父级已经不在库里了。
     *
     * <p>只在根层找不到时兜底。不去匹配正常的二级文件夹 —— 那会把
     * 「学习/工具」和「技术/工具」合并掉，是错的。
     */
    static Long findOrphanFolder(SQLiteDatabase db, String name) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT _ID, PARENT FROM BOOKMARKS WHERE FOLDER=1 AND TITLE=? "
                    + "AND (DELETED IS NULL OR DELETED=0)", new String[]{ name });
            while (c.moveToNext()) {
                long id = c.getLong(0);
                long p = c.getLong(1);
                if (p == 0) continue;
                Cursor d = null;
                try {
                    d = db.rawQuery("SELECT COUNT(*) FROM BOOKMARKS WHERE _ID=? AND FOLDER=1",
                            new String[]{ String.valueOf(p) });
                    if (d.moveToNext() && d.getLong(0) == 0) return id;   // 父级不存在 -> 孤儿
                } finally {
                    if (d != null) try { d.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] findOrphanFolder error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 一组重复书签：URL 相同，可能标题不同。 */
    public static final class DupGroup {
        public final String url;
        public final List<DupItem> items = new ArrayList<DupItem>();
        DupGroup(String url) { this.url = url; }
        /** 建议保留的那条 —— 一般是最早创建的。 */
        public DupItem keep() {
            DupItem best = items.get(0);
            for (DupItem it : items) if (it.created < best.created) best = it;
            return best;
        }
    }

    /** 重复组里的一条书签。 */
    public static final class DupItem {
        public final long id;
        public final String title;
        public final long created;
        public final String folder;      // 所在文件夹名，便于主人判断
        DupItem(long id, String title, long created, String folder) {
            this.id = id; this.title = title; this.created = created; this.folder = folder;
        }
    }

    /**
     * 扫描重复书签（URL 完全相同）。
     *
     * <p>只读。URL 比较<b>区分大小写</b>吗？不区分 —— 同一站点的路径大小写
     * 通常等价，而书签里大小写混用多半是同一条被存了两次。
     *
     * <p>不处理「同一页面不同参数」（如 ?utm_source=xxx）：那多半是主人
     * 有意存的不同入口，自动判定风险太高，宁可漏报也不要误删。
     *
     * @param maxGroups 最多返回多少组，防止书签太多时卡住
     */
    public static List<DupGroup> findDuplicateBookmarks(SQLiteDatabase db, int maxGroups) {
        List<DupGroup> out = new ArrayList<DupGroup>();
        Cursor c = null;
        try {
            // 2026-10-04 两处修复:
            // ① DELETED 口径统一 —— 本文件其它查询用 (DELETED IS NULL OR DELETED=0),
            //    这里原用 DELETED=0。SQL 三值逻辑下 NULL=0 结果为 NULL(非真),于是
            //    **DELETED 为 NULL 的存量行会被静默漏掉**,去重对这些书签不生效。
            //    统一为超集写法(不会多选,只会覆盖原口径漏掉的行)。
            // ② URL 大小写 —— 本方法 javadoc 明确承诺"不区分大小写",但 SQLite 默认
            //    BINARY 排序下 GROUP BY URL / URL=? 都是区分大小写的,承诺与实现相反:
            //    仅大小写不同的重复书签永远查不出来。改用 LOWER(URL) 兑现承诺。
            c = db.rawQuery(
                    "SELECT LOWER(URL) AS lu, COUNT(*) AS n FROM BOOKMARKS "
                    + "WHERE FOLDER=0 AND (DELETED IS NULL OR DELETED=0)"
                    + " AND URL IS NOT NULL AND URL<>'' "
                    + "GROUP BY lu HAVING n>1 ORDER BY n DESC LIMIT ?",
                    new String[]{ String.valueOf(Math.max(1, maxGroups)) });
            List<String> urls = new ArrayList<String>();
            while (c.moveToNext()) urls.add(c.getString(0));
            c.close();
            c = null;

            for (String url : urls) {
                DupGroup g = new DupGroup(url);
                Cursor d = null;
                try {
                    d = db.rawQuery(
                            "SELECT _ID, TITLE, COALESCE(CREATED,0), PARENT FROM BOOKMARKS "
                            + "WHERE FOLDER=0 AND (DELETED IS NULL OR DELETED=0)"
                            + " AND LOWER(URL)=?",
                            new String[]{ url });
                    while (d.moveToNext()) {
                        long parent = d.getLong(3);
                        g.items.add(new DupItem(
                                d.getLong(0),
                                d.isNull(1) ? "" : d.getString(1),
                                d.getLong(2),
                                folderNameOf(db, parent)));
                    }
                } finally {
                    if (d != null) try { d.close(); } catch (Throwable ignored) {}
                }
                if (g.items.size() > 1) out.add(g);
            }
            int total = 0;
            for (DupGroup g : out) total += g.items.size() - 1;
            MainModule.logMsg("[SBPlus] duplicate bookmarks: " + out.size()
                    + " groups, " + total + " redundant copies");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] findDuplicateBookmarks error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 文件夹 id -> 名字；找不到返回空串（根目录）。 */
    static String folderNameOf(SQLiteDatabase db, long folderId) {
        if (folderId == 0) return "";
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT TITLE FROM BOOKMARKS WHERE _ID=? AND FOLDER=1",
                    new String[]{ String.valueOf(folderId) });
            if (c.moveToNext()) return c.isNull(0) ? "" : c.getString(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return "";
    }

    /**
     * 删除指定的书签（去重用）。
     *
     * <p><b>这是本模块里唯一真正删除数据的地方。</b>所以：
     * <ul>
     *   <li>调用方必须先备份</li>
     *   <li>只删显式传进来的 ID，不做任何「顺手清理」</li>
     *   <li>软删（DELETED=1）而不是物理删除 —— 浏览器自己的回收站还能找回</li>
     * </ul>
     *
     * @return 实际删除的条数
     */
    public static int softDeleteBookmarks(SQLiteDatabase db, List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("DELETED", 1);
                cv.put("DIRTY", 1);
                cv.put("MODIFIED", System.currentTimeMillis() / 1000);
                n += db.update("BOOKMARKS", cv, "_ID=? AND FOLDER=0",
                        new String[]{ String.valueOf(id) });
            } catch (Throwable t) {
                MainModule.logMsg("[SBPlus] softDelete " + id + " error: " + t);
            }
        }
        return n;
    }

    /** 一个空文件夹。 */
    public static final class EmptyFolder {
        public final long id;
        public final String name;
        public final long parent;
        EmptyFolder(long id, String name, long parent) {
            this.id = id; this.name = name; this.parent = parent;
        }
    }

    /**
     * 找出所有<b>空且无子文件夹</b>的文件夹。
     *
     * <p>只读，不改任何东西 —— 删不删由主人决定。
     *
     * <p>判定「空」的口径：该文件夹下既没有书签（FOLDER=0），
     * 也没有下级文件夹（FOLDER=1）。有任意一个就不算空。
     */
    public static List<EmptyFolder> listEmptyFolders(SQLiteDatabase db) {
        List<EmptyFolder> out = new ArrayList<EmptyFolder>();
        android.database.Cursor c = null;
        try {
            // 所有文件夹的 id / 名字 / 父级
            Map<Long, String> names = new HashMap<Long, String>();
            Map<Long, Long> parents = new HashMap<Long, Long>();
            List<Long> allFolders = new ArrayList<Long>();
            c = db.rawQuery("SELECT _ID, TITLE, PARENT FROM BOOKMARKS WHERE FOLDER=1", null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                names.put(id, c.isNull(1) ? "" : c.getString(1));
                parents.put(id, c.getLong(2));
                allFolders.add(id);
            }
            c.close();
            c = null;

            // 有内容的父级集合：书签的 PARENT + 子文件夹的 PARENT
            Set<Long> hasContent = new HashSet<Long>();
            c = db.rawQuery("SELECT DISTINCT PARENT FROM BOOKMARKS WHERE FOLDER=0", null);
            while (c.moveToNext()) hasContent.add(c.getLong(0));
            c.close();
            c = null;
            c = db.rawQuery("SELECT DISTINCT PARENT FROM BOOKMARKS WHERE FOLDER=1", null);
            while (c.moveToNext()) hasContent.add(c.getLong(0));

            for (Long id : allFolders) {
                if (hasContent.contains(id)) continue;
                Long p = parents.get(id);
                out.add(new EmptyFolder(id, names.get(id), p == null ? 0 : p));
            }
            MainModule.logMsg("[SBPlus] empty folders found: " + out.size());
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] listEmptyFolders error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * 删除指定的空文件夹。
     *
     * <p>删之前<b>再确认一次是空的</b>：列表是之前拍的快照，中间可能
     * 有书签被移进去，直接删会连带删掉里面的东西。
     *
     * @return 实际删掉的个数
     */
    public static int deleteEmptyFolders(SQLiteDatabase db, List<EmptyFolder> folders) {
        int n = 0;
        for (EmptyFolder f : folders) {
            try {
                if (!isEmptyFolder(db, f.id)) continue;
                db.delete("BOOKMARKS", "_ID=?", new String[]{ String.valueOf(f.id) });
                n++;
            } catch (Throwable t) {
                MainModule.logMsg("[SBPlus] delete folder " + f.id + " error: " + t);
            }
        }
        return n;
    }

    /** 该文件夹当下是否确实为空。 */
    static boolean isEmptyFolder(SQLiteDatabase db, long folderId) {
        android.database.Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM BOOKMARKS WHERE PARENT=?",
                    new String[]{ String.valueOf(folderId) });
            if (c.moveToNext()) return c.getLong(0) == 0;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] isEmptyFolder error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * 在指定父级下按名字找文件夹。
     *
     * <p>{@code parent} 必须参与匹配：两级结构下「技术/工具」和「学习/工具」
     * 是两个不同的文件夹，只看名字会把它们当成同一个，子类就会串到别的大类下。
     */
    /** 一个与其他文件夹重名的文件夹。 */
    public static final class DupFolder {
        public final long id;
        public final String name;
        public final long parent;
        public final int items;      // 直接装在里面的条目数
        DupFolder(long id, String name, long parent, int items) {
            this.id = id; this.name = name; this.parent = parent; this.items = items;
        }
    }

    /**
     * 找出同一父级下的重名文件夹。
     *
     * <p>只读。「技术」在根下出现了三次，前两个各装几条书签 —— 这种就要
     * 让主人看到，才能判断是合并还是留着。
     */
    public static List<DupFolder> listDuplicateFolders(SQLiteDatabase db) {
        List<DupFolder> out = new ArrayList<DupFolder>();
        Cursor c = null;
        try {
            // 按 (父级, 名字) 分组，取出现次数 > 1 的
            c = db.rawQuery(
                    "SELECT PARENT, TITLE, COUNT(*) AS n FROM BOOKMARKS "
                    + "WHERE FOLDER=1 AND (DELETED IS NULL OR DELETED=0) "
                    + "GROUP BY PARENT, TITLE HAVING n > 1", null);
            List<long[]> groups = new ArrayList<long[]>();
            List<String> names = new ArrayList<String>();
            while (c.moveToNext()) {
                groups.add(new long[]{ c.getLong(0), c.getLong(2) });
                names.add(c.isNull(1) ? "" : c.getString(1));
            }
            c.close();
            c = null;

            for (int g = 0; g < groups.size(); g++) {
                long parent = groups.get(g)[0];
                String name = names.get(g);
                Cursor d = null;
                try {
                    d = db.rawQuery("SELECT _ID FROM BOOKMARKS WHERE FOLDER=1 AND TITLE=? "
                            + "AND PARENT=? AND (DELETED IS NULL OR DELETED=0)",
                            new String[]{ name, String.valueOf(parent) });
                    while (d.moveToNext()) {
                        long fid = d.getLong(0);
                        out.add(new DupFolder(fid, name, parent, countChildren(db, fid)));
                    }
                } finally {
                    if (d != null) try { d.close(); } catch (Throwable ignored) {}
                }
            }
            MainModule.logMsg("[SBPlus] duplicate folders: " + out.size() + " rows in "
                    + groups.size() + " name groups");
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] listDuplicateFolders error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 某文件夹里直接装了多少条目（书签 + 子文件夹）。 */
    static int countChildren(SQLiteDatabase db, long folderId) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM BOOKMARKS WHERE PARENT=?",
                    new String[]{ String.valueOf(folderId) });
            if (c.moveToNext()) return (int) c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    static Long findFolder(SQLiteDatabase db, String name, long parent) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT _ID FROM BOOKMARKS WHERE FOLDER=1 AND TITLE=? AND PARENT=? "
                    + "AND (DELETED IS NULL OR DELETED=0) LIMIT 1",
                    new String[]{ name, String.valueOf(parent) });
            if (c.moveToNext()) return c.getLong(0);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] findFolder error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 诊断：把某个父级下所有同名文件夹都列出来。
     *
     * <p>「重复文件夹」的排查入口 —— 如果同一个父级下有多个同名文件夹，
     * 说明查重真的失效了；如果一个都没有，说明重复是<b>跨父级</b>产生的，
     * 那是另一回事。
     */
    static void dumpDuplicates(SQLiteDatabase db, String name, long parent) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT _ID, PARENT, FOLDER, TITLE, "
                    + "COALESCE(DELETED,-1), COALESCE(EDITABLE,-1) FROM BOOKMARKS "
                    + "WHERE TITLE=? AND PARENT=?",
                    new String[]{ name, String.valueOf(parent) });
            int n = 0;
            while (c.moveToNext()) {
                MainModule.logMsg("[SBPlus] dup: id=" + c.getLong(0)
                        + " parent=" + c.getLong(1) + " folder=" + c.getLong(2)
                        + " deleted=" + c.getLong(4) + " editable=" + c.getLong(5)
                        + " title=" + c.getString(3));
                n++;
            }
            MainModule.logMsg("[SBPlus] dup count for '" + name + "' under " + parent + " = " + n);
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] dumpDuplicates error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
    }

    /** 新建一个文件夹。字段照抄已验证过的写入组合。 */
    static Long createFolder(SQLiteDatabase db, String name, long parent) {
        try {
            long now = System.currentTimeMillis() / 1000;
            ContentValues cv = new ContentValues();
            cv.put("FOLDER", 1);
            // PARENT 必须用传入的父级 —— 写死根目录的话，子类会被丢到根下，
            // 两级结构就废了。
            cv.put("PARENT", parent);
            cv.put("TITLE", name);
            cv.put("URL", "");
            cv.put("SURL", "");
            cv.put("DELETED", 0);
            cv.put("DIRTY", 1);
            cv.put("CREATED", now);
            cv.put("MODIFIED", now);
            cv.put("EDITABLE", 1);
            cv.put("bookmark", 1);
            cv.put("type", 1);
            long id = db.insert("BOOKMARKS", null, cv);
            if (id == -1) {
                MainModule.logMsg("[SBPlus] insert returned -1 for '" + name + "'");
                return null;
            }
            // 自检：BOOKMARKS 是视图，插进去的行未必能被视图自己的过滤条件查回来。
            // 查不回来的话，下一次 findFolder 会认为「还不存在」而重复建 ——
            // 这正是重复文件夹的来源。
            Cursor chk = null;
            try {
                chk = db.rawQuery("SELECT _ID, PARENT, FOLDER FROM BOOKMARKS WHERE _ID=?",
                        new String[]{ String.valueOf(id) });
                if (!chk.moveToNext()) {
                    MainModule.logMsg("[SBPlus] WARN newly created folder id=" + id
                            + " '" + name + "' NOT visible through BOOKMARKS view!");
                } else {
                    MainModule.logMsg("[SBPlus] verify new folder id=" + id
                            + " parent=" + chk.getLong(1) + " folder=" + chk.getLong(2));
                }
            } finally {
                if (chk != null) try { chk.close(); } catch (Throwable ignored) {}
            }
            return id;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] createFolder error: " + t);
            return null;
        }
    }
}
