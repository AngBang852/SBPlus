package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 主页 Logo 管理: 选本地图片/GIF 复制到应用私有目录, 支持多张切换.
 * 存储: getExternalFilesDir(null)/home_logos/ 下的文件, SharedPreferences 记录当前选中的文件名.
 *
 * <p>性能与健壮性说明(2026-09-17 优化):
 * <ul>
 *   <li>原实现每个 getter 都重新调用 {@code ctx.getSharedPreferences(...)}。
 *       本类一共有 19 处这样的调用, 而 {@code MainHook.homeLogoSignature()} 一次
 *       就要读 6 个值 → 6 次取实例 + 6 次 Map 查找, 且每次都在锁内。</li>
 *   <li>{@link #dirFor} 原本**每次调用**都 {@code mkdirs()}。目录已存在时
 *       {@code mkdirs()} 仍会走到文件系统做一次判定(stat), 在附着路径上属于白付的开销。
 *       这里改为只在首次探测, 之后记忆结果。</li>
 *   <li>{@link #dirFor} 未对 {@code ctx} 为 null 兜底: 原实现在
 *       {@code getExternalFilesDir} 返回 null 时会落到 {@code getFilesDir()},
 *       但若 ctx 本身为 null 则直接 NPE 抛出。</li>
 * </ul>
 */
public class HomeLogoHelper {

    public static final String PREFS = "sbplus_home_logo";
    public static final String KEY_CURRENT = "current";
    public static final String KEY_LIST = "list";
    public static final String KEY_ENABLED = "enabled";

    /** 缓存的 SharedPreferences 实例(volatile 保证引用发布的可见性)。 */
    private static volatile SharedPreferences sPrefs;

    /** 缓存的 Logo 目录。目录在应用生命周期内不会变, 故可安全缓存。 */
    private static volatile File sDir;

    /** 取得(并缓存)本模块的 Logo 配置。ctx 为 null 时返回 null, 调用方需兜底。 */
    private static SharedPreferences prefs(Context ctx) {
        SharedPreferences p = sPrefs;
        if (p != null) return p;
        if (ctx == null) return null;
        try {
            Context app = ctx.getApplicationContext();
            p = (app != null ? app : ctx).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sPrefs = p;
            return p;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] HomeLogoHelper.prefs error: " + t);
            return null;
        }
    }

    private static File dir(Context ctx) {
        return dirFor(ctx);
    }

    /**
     * 公开: Logo 存储目录。
     *
     * <p>契约: **永不返回 null**。调用方直接用它构造 File
     * (如 {@code MainHook.java:5864 的 new File(dirFor(ctx), name)}),
     * 返回 null 会让 {@code new File(null, name)} 抛 NPE。因此 ctx 为 null 或
     * 目录不可用时, 退化为一个"路径指向预期位置但可能不存在"的 File 对象,
     * 由调用方各自的 exists()/listFiles() 判定自然处理。
     */
    public static File dirFor(Context ctx) {
        File d = sDir;
        if (d != null) return d;
        try {
            if (ctx != null) {
                File base = ctx.getExternalFilesDir(null);
                if (base == null) base = ctx.getFilesDir();
                if (base != null) {
                    d = new File(base, "home_logos");
                    if (!d.exists()) d.mkdirs();
                    sDir = d;
                    return d;
                }
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] HomeLogoHelper.dirFor error: " + t);
        }
        // 兜底: 不缓存(下次仍会尝试走正常路径), 但保证返回非 null。
        return new File("home_logos");
    }

    /**
     * 校验文件名合法性: 必须是单层文件名, 不得包含路径分隔符或上跳。
     *
     * <p>本类的 {@code name} 大多来自 SharedPreferences 与目录列举, 本应可信;
     * 但 {@link #removeLogo} 会用它做 {@code delete()}, 且该值是**持久化**的——
     * 一旦被写入 {@code ../} 之类内容, 删除操作就会逃出 home_logos 目录。
     * 这里统一收口, 非法名一律拒绝。
     */
    private static boolean isSafeName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        if (name.indexOf('\0') >= 0) return false;
        if (".".equals(name) || "..".equals(name)) return false;
        return true;
    }

    /** 把选中的图片复制到私有目录, 返回文件名(带扩展名), 失败返回 null. */
    public static String addLogoFromUri(Context ctx, Uri uri) {
        InputStream in = null;
        FileOutputStream fos = null;
        File tmp = null;
        File out = null;
        try {
            File dirFile = dir(ctx);
            if (!dirFile.exists() && !dirFile.mkdirs()) {
                // dirFor 的兜底分支可能在极端环境下给出不可写目录。这里显式判定,
                // 让"存不进去"变成明确的失败(返回 null + 日志), 而不是抛到调用方。
                MainModule.logMsg("[SBPlus] HomeLogoHelper.addLogoFromUri: dir unavailable: " + dirFile);
                return null;
            }
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            String name = "logo_" + System.currentTimeMillis();
            String mime = ctx.getContentResolver().getType(uri);
            if (mime != null && mime.contains("gif")) name += ".gif";
            else if (mime != null && mime.contains("png")) name += ".png";
            else if (mime != null && mime.contains("webp")) name += ".webp";
            else name += ".jpg";
            out = new File(dirFile, name);

            // 先写临时文件、校验后再 rename 到位(2026-09-17 修正)。
            // 原实现直写目标文件:中途失败会留下一个**半截的图片**,
            // 而它会被 listLogos 列出、可被选中为当前 Logo,
            // 表现为"设了 Logo 但显示异常/不显示",且没有任何错误提示。
            tmp = new File(dirFile, name + ".tmp");
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            // != -1 而非 > 0: InputStream 允许返回 0, 用 > 0 做循环条件会静默截断。
            while ((n = in.read(buf)) != -1) {
                if (n > 0) { fos.write(buf, 0, n); total += n; }
            }
            fos.flush();
            fos.close(); fos = null;
            in.close(); in = null;

            if (total <= 0) {
                MainModule.logMsg("[SBPlus] HomeLogoHelper.addLogoFromUri: empty source, abort");
                return null;
            }
            long declared = querySize(ctx, uri);
            if (declared > 0 && declared != total) {
                MainModule.logMsg("[SBPlus] HomeLogoHelper.addLogoFromUri: size mismatch declared="
                        + declared + " copied=" + total + ", abort");
                return null;
            }
            if (!tmp.renameTo(out)) {
                MainModule.logMsg("[SBPlus] HomeLogoHelper.addLogoFromUri: rename failed");
                return null;
            }
            tmp = null;     // 已改名,finally 不必再清理

            // 加入列表
            List<String> list = listLogos(ctx);
            if (!list.contains(name)) {
                list.add(name);
                saveList(ctx, list);
            }
            // 自动设为当前
            SharedPreferences sp = prefs(ctx);
            if (sp != null) {
                sp.edit().putString(KEY_CURRENT, name).putBoolean(KEY_ENABLED, true).apply();
            }
            return name;
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] HomeLogoHelper.addLogoFromUri error: " + t);
            return null;
        } finally {
            // 原实现把两个 close 写在成功路径上: 中途 read 抛异常即泄漏 fd。
            // Logo 可选多张, 反复失败会累积到 fd 耗尽。
            if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            // 失败路径清掉临时文件,不留垃圾。
            if (tmp != null) try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    /** 取 URI 声明的文件大小;取不到返回 -1。 */
    private static long querySize(Context ctx, Uri uri) {
        android.database.Cursor cur = null;
        try {
            cur = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !cur.isNull(idx)) return cur.getLong(idx);
            }
        } catch (Throwable ignored) {
            // 取不到不影响主流程(部分 Provider 不提供 SIZE)
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    public static void saveList(Context ctx, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        SharedPreferences sp = prefs(ctx);
        if (sp != null) sp.edit().putString(KEY_LIST, sb.toString()).apply();
    }

    public static List<String> listLogos(Context ctx) {
        List<String> res = new ArrayList<String>();
        try {
            SharedPreferences sp = prefs(ctx);
            String raw = sp != null ? sp.getString(KEY_LIST, "") : "";
            if (raw != null && !raw.isEmpty()) {
                for (String s : raw.split(",")) {
                    if (!s.isEmpty()) res.add(s);
                }
            }
            // 兜底: 目录里实际存在的文件
            File dirFile = dir(ctx);
            File[] fs = dirFile.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!res.contains(f.getName())) res.add(f.getName());
                }
            }
        } catch (Throwable t) {
            MainModule.logMsg("[SBPlus] HomeLogoHelper.listLogos error: " + t);
        }
        return res;
    }

    /** 当前选中 Logo 的绝对路径, 无则空串. */
    public static String currentPath(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx);
            String name = sp != null ? sp.getString(KEY_CURRENT, "") : "";
            if (!isSafeName(name)) return "";
            File dirFile = dir(ctx);
            File f = new File(dirFile, name);
            return f.exists() ? f.getAbsolutePath() : "";
        } catch (Throwable t) { return ""; }
    }

    public static boolean isEnabled(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        return sp != null && sp.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean en) {
        SharedPreferences sp = prefs(ctx);
        if (sp != null) sp.edit().putBoolean(KEY_ENABLED, en).apply();
    }

    public static void setCurrent(Context ctx, String name) {
        SharedPreferences sp = prefs(ctx);
        if (sp != null) sp.edit().putString(KEY_CURRENT, name).apply();
    }

    public static void removeLogo(Context ctx, String name) {
        if (!isSafeName(name)) return;      // 防止 ../ 之类越出 home_logos 目录
        try {
            File dirFile = dir(ctx);
            File f = new File(dirFile, name);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
        List<String> list = listLogos(ctx);
        list.remove(name);
        saveList(ctx, list);
        SharedPreferences sp = prefs(ctx);
        String cur = sp != null ? sp.getString(KEY_CURRENT, "") : "";
        if (name.equals(cur)) {
            String next = list.isEmpty() ? "" : list.get(0);
            if (sp != null) sp.edit().putString(KEY_CURRENT, next).apply();
        }
    }

    /** 当前 logo 是否启用背景透明化。 */
    public static boolean isAlphaBg(Context ctx, String name) {
        try {
            SharedPreferences sp = prefs(ctx);
            return sp != null && sp.getBoolean("alpha_" + name, false);
        } catch (Throwable ignored) {}
        return false;
    }

    /** 设置当前 logo 背景透明化开关。 */
    public static void setAlphaBg(Context ctx, String name, boolean on) {
        try {
            SharedPreferences sp = prefs(ctx);
            if (sp != null) sp.edit().putBoolean("alpha_" + name, on).apply();
        } catch (Throwable ignored) {}
    }

    /** 位置: X 百分比(0-100, 水平锚点在 Logo 中心)。 */
    public static int getPosX(Context ctx, String name) {
        try {
            SharedPreferences sp = prefs(ctx);
            return sp != null ? sp.getInt("posx_" + name, 50) : 50;
        } catch (Throwable ignored) {}
        return 50;
    }

    public static void setPosX(Context ctx, String name, int v) {
        try {
            SharedPreferences sp = prefs(ctx);
            if (sp != null) sp.edit().putInt("posx_" + name, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 位置: Y 百分比(0-100, 垂直锚点在 Logo 中心)。 */
    public static int getPosY(Context ctx, String name) {
        try {
            SharedPreferences sp = prefs(ctx);
            return sp != null ? sp.getInt("posy_" + name, 20) : 20;
        } catch (Throwable ignored) {}
        return 20;
    }

    public static void setPosY(Context ctx, String name, int v) {
        try {
            SharedPreferences sp = prefs(ctx);
            if (sp != null) sp.edit().putInt("posy_" + name, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 大小: 百分比(50-200, 100=默认)。 */
    public static int getSizePct(Context ctx, String name) {
        try {
            SharedPreferences sp = prefs(ctx);
            return sp != null ? sp.getInt("size_" + name, 100) : 100;
        } catch (Throwable ignored) {}
        return 100;
    }

    public static void setSizePct(Context ctx, String name, int v) {
        try {
            SharedPreferences sp = prefs(ctx);
            if (sp != null) sp.edit().putInt("size_" + name, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 跟随搜索框动画(全局). */
    public static boolean isFollow(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx);
            return sp == null || sp.getBoolean("follow", true);   // 默认开
        } catch (Throwable ignored) {}
        return true;
    }

    public static void setFollow(Context ctx, boolean on) {
        try {
            SharedPreferences sp = prefs(ctx);
            if (sp != null) sp.edit().putBoolean("follow", on).apply();
        } catch (Throwable ignored) {}
    }
}