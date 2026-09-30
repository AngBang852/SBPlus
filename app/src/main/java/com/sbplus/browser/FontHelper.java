package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FontHelper — 自定义字体（B方案：SAF 添加字体文件，列表管理多字体）。
 *
 * 字体文件统一存到应用私有外部目录 fonts/ 下（*.ttf|otf），
 * 用 ASCII 安全文件名（font_时间戳.ttf），彻底避开中文/特殊字符编码问题。
 *
 * 主页美化里"字体"条目是开关（启用/停用），点击弹出字体列表：
 *   每个已添加字体：点击选中生效，有删除按钮可删文件。
 *   底部有"添加字体"入口：发起 SAF 选 .ttf/.otf 复制到目录。
 *
 * prefs: sbplus_prefs
 *   font_selected : 当前选中的字体文件名，空=未选（用默认）
 *   font_enabled  : 自定义字体开关
 */
public final class FontHelper {

    public static final String KEY_SELECTED = "font_selected";
    public static final String KEY_ENABLED = "font_enabled";

    private FontHelper() {}

    public static String prefName() { return "sbplus_prefs"; }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE);
    }

    /** 字体目录：优先应用外部私有目录（必然可写），回退到内部私有目录。 */
    private static File dir(Context ctx) {
        File d = null;
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base != null) { d = new File(base, "fonts"); }
        } catch (Throwable ignored) {}
        if (d == null) d = new File(ctx.getFilesDir(), "fonts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ============ 存取 ============
    // 说明:isEnabled / selectedName / shouldApply 会在 TextView.setTypeface /
    // setText / onDraw / View.onAttachedToWindow 这些每帧热路径上被调用,
    // 原实现每次都要读 SharedPreferences,shouldApply 还会额外做一次
    // File.exists() 磁盘 stat —— 这是滚动卡顿与发热的直接来源。
    // 这里做内存缓存,由 SharedPreferences 变更监听失效,行为与原来一致。
    private static volatile boolean sCfgReady;
    private static volatile boolean sCfgEnabled;
    private static volatile String sCfgSelected = "";
    private static volatile String sCfgPath = "";
    private static volatile boolean sCfgShouldApply;
    private static SharedPreferences.OnSharedPreferenceChangeListener sCfgListener;
    /** 监听器实际注册到哪个 SharedPreferences 实例上(反注册时要用同一个)。 */
    private static SharedPreferences sCfgListenerPrefs;

    /** 让字体配置缓存失效(选择/开关变更后调用)。 */
    public static void invalidateCache() {
        sCfgReady = false;
        sCfgFailCount = 0;      // 配置变更 -> 允许立刻重试
    }

    /**
     * 连续读取失败的次数(退避用)。
     *
     * <p>与 {@code ThemeColorHelper} 同一处缺陷的两份拷贝:原实现在 catch 里
     * 什么都不做,随后**无条件**把 {@code sCfgReady} 置 true —— 于是"读不到配置"
     * 被固化成"已就绪且字体关闭"。后果是字体功能静默失效且永不恢复:
     * 用户在设置页选好字体开关打开,回到页面依然不生效,而任何日志都没有。
     *
     * <p>退避(而非无脑重试)的理由相同:{@link #shouldApply} 在文本渲染路径上
     * 被高频调用,若持续失败还每次都重试,会把正确性问题变成性能问题。
     */
    private static volatile int sCfgFailCount;
    private static final int CFG_FAIL_BACKOFF_THRESHOLD = 3;

    private static void ensureCfg(Context ctx) {
        if (sCfgReady) return;
        if (sCfgFailCount >= CFG_FAIL_BACKOFF_THRESHOLD) return;
        synchronized (FontHelper.class) {
            if (sCfgReady) return;
            if (sCfgFailCount >= CFG_FAIL_BACKOFF_THRESHOLD) return;
            boolean en = false;
            String sel = "";
            String path = "";
            try {
                SharedPreferences p = sp(ctx);
                en = p.getBoolean(KEY_ENABLED, false);
                sel = p.getString(KEY_SELECTED, "");
                if (sel == null) sel = "";
                if (!sel.isEmpty()) {
                    File f = new File(dir(ctx), sel);
                    if (f.exists()) path = f.getAbsolutePath();
                }
                if (sCfgListener == null) {
                    sCfgListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                        @Override public void onSharedPreferenceChanged(SharedPreferences s, String k) {
                            invalidateCache();
                        }
                    };
                    p.registerOnSharedPreferenceChangeListener(sCfgListener);
                    sCfgListenerPrefs = p;
                }
            } catch (Throwable t) {
                // 读失败:保持未就绪 + 不写任何缓存字段,下次(或配置变更后)重试。
                sCfgFailCount++;
                XposedBridgeLog("ensureCfg read failed (" + sCfgFailCount
                        + "/" + CFG_FAIL_BACKOFF_THRESHOLD + "), will retry: " + t);
                return;
            }
            sCfgFailCount = 0;
            sCfgEnabled = en;
            sCfgSelected = sel;
            sCfgPath = path;
            sCfgShouldApply = en && !path.isEmpty();
            sCfgReady = true;
        }
    }

    /**
     * 反注册监听器(2026-09-17 新增)。
     *
     * <p>原实现只注册、从不反注册:监听器被 SharedPreferences 持有,而
     * {@code SharedPreferences} 又被系统按名缓存,于是本类的监听器会在进程
     * 生命周期内一直存活。更麻烦的是换 SharedPreferences 实例后,
     * {@code sCfgListener != null} 使新实例上不再注册,旧实例上的监听器却
     * 还在触发 —— 配置变更通知会错乱。
     *
     * <p>由 {@code MainModule} 在宿主上下文销毁 / 模块卸载路径上调用;
     * 也可在确定不再需要时调用。可重复调用(幂等)。
     */
    public static void unregisterListener() {
        synchronized (FontHelper.class) {
            try {
                if (sCfgListener != null && sCfgListenerPrefs != null) {
                    sCfgListenerPrefs.unregisterOnSharedPreferenceChangeListener(sCfgListener);
                }
            } catch (Throwable t) {
                XposedBridgeLog("unregisterListener err: " + t);
            }
            sCfgListener = null;
            sCfgListenerPrefs = null;
        }
    }

    public static boolean isEnabled(Context ctx) {
        try { ensureCfg(ctx); return sCfgEnabled; } catch (Throwable ignored) {}
        return false;
    }
    public static void setEnabled(Context ctx, boolean en) {
        try { sp(ctx).edit().putBoolean(KEY_ENABLED, en).apply(); } catch (Throwable ignored) {}
        invalidateCache();
    }

    public static String selectedName(Context ctx) {
        try { ensureCfg(ctx); return sCfgSelected; } catch (Throwable ignored) {}
        return "";
    }
    public static void selectFont(Context ctx, String name) {
        try { sp(ctx).edit().putString(KEY_SELECTED, name == null ? "" : name).apply(); } catch (Throwable ignored) {}
        invalidateCache();
    }

    /** 扫描字体目录，返回所有 *.ttf|otf 文件名，排序。 */
    public static List<String> listFonts(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            File[] fs = dir(ctx).listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    // 顺带清理残留的 .tmp:addFontFromUri 写临时文件后若进程被杀,
                    // 临时文件会留在目录里(它不被本方法列出,但会占空间)。
                    if (n.endsWith(".tmp")) {
                        try { f.delete(); } catch (Throwable ignored) {}
                        continue;
                    }
                    if (f.isFile() && (n.endsWith(".ttf") || n.endsWith(".otf"))) {
                        out.add(f.getName());
                    }
                }
            }
            Collections.sort(out);
        } catch (Throwable ignored) {}
        return out;
    }

    /** 删除指定字体文件。 */
    public static boolean deleteFont(Context ctx, String name) {
        try {
            File f = new File(dir(ctx), name);
            boolean ok = f.exists() && f.delete();
            if (ok && name.equals(selectedName(ctx))) selectFont(ctx, "");
            if (ok) invalidateCache();
            return ok;
        } catch (Throwable ignored) { return false; }
    }

    /** 当前选中字体的绝对路径，无则空。 */
    public static String selectedPath(Context ctx) {
        try { ensureCfg(ctx); return sCfgPath; } catch (Throwable ignored) {}
        return "";
    }

    /** 是否应用（开关开且选中文件存在）。热路径:走内存缓存,不做磁盘 stat。 */
    public static boolean shouldApply(Context ctx) {
        try { ensureCfg(ctx); return sCfgShouldApply; } catch (Throwable ignored) {}
        return false;
    }

    /** 加载选中字体，失败返回 null。 */
    public static Typeface loadTypeface(Context ctx) {
        try {
            String p = selectedPath(ctx);
            if (p == null || p.isEmpty()) { XposedBridgeLog("load: no selected path"); return null; }
            File f = new File(p);
            if (!f.exists()) { XposedBridgeLog("load: file missing " + p); return null; }
            Typeface tf = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Typeface.Builder(f).build()
                    : Typeface.createFromFile(f);
            if (tf == null) { XposedBridgeLog("load: builder returned null"); return null; }
            XposedBridgeLog("load OK tf=" + tf);
            return tf;
        } catch (Throwable t) { XposedBridgeLog("load err: " + t); return null; }
    }

    /** 复制 URI 字体到目录：文件名用 ASCII 安全名(时间戳), 并保存'原文件名->存储名'映射供列表显示。 */
    public static boolean addFontFromUri(Context ctx, android.net.Uri uri) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        File tmpFile = null;
        File target = null;
        try {
            String display = queryDisplayName(ctx, uri);
            boolean isOtf = display != null && display.toLowerCase().endsWith(".otf");
            String name = "font_" + System.currentTimeMillis() + (isOtf ? ".otf" : ".ttf");
            target = new File(dir(ctx), name);
            int k = 1;
            while (target.exists()) {
                target = new File(dir(ctx), (isOtf ? "font_o" : "font_") + (System.currentTimeMillis() + (k++)) + (isOtf ? ".otf" : ".ttf"));
            }
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) { XposedBridgeLog("add font: openInputStream null for " + uri); return false; }

            // 先写临时文件、校验后再 rename 到位(2026-09-17 修正)。
            // 原实现直接写目标文件:一旦中途失败(URI 被回收、磁盘满、进程被杀),
            // 目录里会留下一个**半截的 .ttf/.otf**——它会被 listFonts 列出、
            // 可以被选中,而 loadTypeface 因文件已损坏返回 null,表现为
            // "选了字体但不生效"且没有任何错误提示。tmp + 原子 rename 消除该状态。
            tmpFile = new File(dir(ctx), name + ".tmp");
            out = new java.io.FileOutputStream(tmpFile);
            byte[] buf = new byte[65536];
            long total = 0;
            int r;
            // != -1 而非 > 0:InputStream 允许返回 0,用 > 0 会在返回 0 时
            // 静默提前结束复制,得到一个被截断的字体文件。
            while ((r = in.read(buf)) != -1) {
                if (r > 0) { out.write(buf, 0, r); total += r; }
            }
            out.flush();
            out.close(); out = null;
            in.close(); in = null;

            // 长度校验:空文件必然无效;与源声明长度不符也可疑。
            // (部分 ContentProvider 不返回 SIZE,故只在能取到时才比对。)
            if (total <= 0) {
                XposedBridgeLog("add font: empty source, abort");
                return false;
            }
            long declared = querySize(ctx, uri);
            if (declared > 0 && declared != total) {
                XposedBridgeLog("add font: size mismatch declared=" + declared + " copied=" + total + ", abort");
                return false;
            }
            // 校验通过 -> 原子落入目标名。rename 在同一目录内是原子的。
            if (!tmpFile.renameTo(target)) {
                XposedBridgeLog("add font: rename failed " + tmpFile + " -> " + target);
                return false;
            }
            tmpFile = null;     // 已改名,finally 不必再清理

            // 保存显示名->存储名 映射(供列表显示原文件名)
            String stored = target.getName();
            mapSaveDisplay(ctx, stored, (display == null || display.isEmpty()) ? stored : display);
            XposedBridgeLog("add font OK -> " + target.getAbsolutePath() + " display=" + display + " bytes=" + total);
            return true;
        } catch (Throwable t) { XposedBridgeLog("add font err: " + t); return false; }
        finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            // 失败路径:清掉临时文件,不留垃圾。
            if (tmpFile != null) try { if (tmpFile.exists()) tmpFile.delete(); } catch (Throwable ignored) {}
        }
    }

    /** 取 URI 声明的文件大小;取不到返回 -1。 */
    private static long querySize(Context ctx, android.net.Uri uri) {
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

    // 显示名映射: prefs map "font_display_<storedName>" -> 原文件名
    private static void mapSaveDisplay(Context ctx, String stored, String display) {
        try { sp(ctx).edit().putString("font_display_" + stored, display).apply(); } catch (Throwable ignored) {}
    }
    public static String displayName(Context ctx, String stored) {
        try {
            String d = sp(ctx).getString("font_display_" + stored, "");
            if (d != null && !d.isEmpty()) return d;
        } catch (Throwable ignored) {}
        return stored;
    }

    private static String queryDisplayName(Context ctx, android.net.Uri uri) {
        try {
            android.database.Cursor cur = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0 && cur.getString(idx) != null) return cur.getString(idx);
                    }
                } finally { cur.close(); }
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        if (last != null) {
            int q = last.indexOf('?');
            if (q >= 0) last = last.substring(0, q);
            return last;
        }
        return null;
    }

    // ============ 设置条目 ============

    /** "字体"条目：SwitchPreferenceCustom 开关 + 点击弹字体列表。 */
    public static Object buildEntry(final Context ctx, ClassLoader cl) {
        try {
            Class<?> switchCls = Class.forName(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", false, cl);
            Object pref = switchCls.getConstructor(Context.class).newInstance(ctx);
            setProperty(pref, "setTitle", "自定义字体");
            setProperty(pref, "setKey", "sbplus_font");
            setProperty(pref, "setSummary", "开关启用后点击配置/管理字体");
            setProperty(pref, "setChecked", Boolean.valueOf(isEnabled(ctx)));
            try { setProperty(pref, "setSelectable", Boolean.TRUE); } catch (Throwable ignored) {}
            try { setProperty(pref, "setDividerVisible", Boolean.TRUE); } catch (Throwable ignored) {}

            // 点击 -> 弹字体列表
            bindClickListener(pref, cl, new Runnable() {
                @Override public void run() {
                    openList(ctx);
                }
            });

            // 开关切换 -> 保存 enabled
            bindChangeListener(pref, ctx, cl, new Runnable() {
                @Override public void run() {}
            });
            return pref;
        } catch (Throwable t) { XposedBridgeLog("buildEntry err: " + t); return null; }
    }

    /** 弹字体列表对话框：每行字体名+删除按钮，点行选中。 */
    public static void openList(final Context ctx) {
        try {
            final android.app.Activity act = (ctx instanceof android.app.Activity) ? (android.app.Activity) ctx : null;
            final List<String> fonts = listFonts(ctx);
            final String sel = selectedName(ctx);

            android.widget.LinearLayout ll = new android.widget.LinearLayout(ctx);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dp(ctx, 6);
            ll.setPadding(pad, pad, pad, pad);

            if (fonts.isEmpty()) {
                android.widget.TextView empty = new android.widget.TextView(ctx);
                empty.setText("（暂无字体，点下方添加字体添加）");
                empty.setTextSize(14); empty.setTextColor(0xFF888888);
                empty.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
                ll.addView(empty);
            } else {
                for (final String name : fonts) {
                    ll.addView(buildFontRow(ctx, name, name.equals(sel), act));
                }
            }

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle("自定义字体");
            b.setView(ll);
            b.setPositiveButton("添加字体", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { pickFont(ctx, act); }
            });
            b.setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    selectFont(ctx, ""); setEnabled(ctx, false);
                    toast(ctx, "已恢复系统默认字体");
                }
            });
            b.setNegativeButton("关闭", null);
            // 2026-10-04 修复(静态 Dialog 泄漏):持有引用本身是有意设计(见下方
            // sCurrentDialog 的说明 —— 行点击需要 dismiss 旧框再重建),但**必须**在
            // 对话框消失时把引用清掉。原实现只在 refreshList 里清,于是用户直接点
            // "关闭"/返回键/点外部关闭时,静态字段仍强引用这个以 Activity 为 context 的
            // AlertDialog → 连带整个 Activity 与视图树泄漏,直到下次打开列表才被覆盖。
            final android.app.AlertDialog dlg = b.show();
            dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface d) {
                    // 只在"当前引用就是本框"时清空:refreshList 会先清引用再建新框,
                    // 若不判断,旧框的 dismiss 回调会把新框的引用误清掉。
                    if (sCurrentDialog == dlg) sCurrentDialog = null;
                }
            });
            sCurrentDialog = dlg;
        } catch (Throwable t) { XposedBridgeLog("openList err: " + t); }
    }

    /**
     * 当前打开的字体列表对话框。
     *
     * <p>2026-09-17:用于"选中某行后立刻刷新选中态"。
     *
     * <p>原实现里,行点击只写配置、**不刷新列表**,于是用户点了字体看不到
     * 选中标记(高亮 + "[使用中]" 前缀),必须关掉对话框重进才能看到 ——
     * 而后台的字体应用其实已经生效了,造成"界面没反应、实际变了"的割裂感。
     * 对照之下,同一个类的"删除"按钮是刷新了的(它在按钮回调里重建列表),
     * 但行点击不能照搬:按钮点击会让 AlertDialog 自动关闭,旧框消失后
     * 新框是替换关系;而行点击不会关闭对话框,直接再 show 一个会叠两层。
     * 故这里持有引用,dismiss 掉旧的再重建。
     */
    private static volatile android.app.AlertDialog sCurrentDialog;

    /** 关闭并重建字体列表,使选中态立即反映最新配置。 */
    private static void refreshList(final Context ctx) {
        android.app.AlertDialog d = sCurrentDialog;
        sCurrentDialog = null;
        if (d != null && d.isShowing()) {
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
        openList(ctx);
    }

    private static android.view.View buildFontRow(final Context ctx, final String name, boolean used, final android.app.Activity act) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        row.setBackgroundColor(used ? 0xFFE3F2FD : 0x00000000);

        android.widget.TextView label = new android.widget.TextView(ctx);
        label.setText((used ? "[使用中] " : "") + displayName(ctx, name));
        label.setTextSize(15);
        label.setTextColor(0xFF000000);
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        // 删除按钮
        android.widget.Button del = new android.widget.Button(ctx);
        del.setText("删除");
        del.setTextSize(12);
        del.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                deleteFont(ctx, name);
                toast(ctx, "已删除: " + name);
                refreshList(ctx);
            }
        });
        row.addView(del);

        // 点行选中。
        // 选中后必须重建列表,否则看不到选中态(高亮 + "[使用中]" 前缀)——
        // 用户会以为没点上,反复点;而字体其实已经写进配置并生效了。
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                selectFont(ctx, name);
                setEnabled(ctx, true);
                toast(ctx, "字体已应用: " + name);
                refreshList(ctx);
            }
        });
        return row;
    }

    /** 发起 SAF 选 .ttf/.otf 添加。 */
    private static void pickFont(Context ctx, android.app.Activity act) {
        try {
            if (act == null) { toast(ctx, "无法获取界面环境"); return; }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            // 用 */* 显示所有文件，避免 MIME 过滤把 .ttf/.otf 隐藏掉
            i.setType("*/*");
            act.startActivityForResult(i, MainHook.REQUEST_FONT_PICK);
        } catch (Throwable t) { XposedBridgeLog("pick err: " + t); toast(ctx, "无法打开文件选择器"); }
    }

    // ============ 反射工具 ============
    private static void setProperty(Object pref, String method, Object value) {
        try { MainHook.callMethod(pref, method, value); } catch (Throwable ignored) {}
    }
    private static Class<?> listenerParamType(Class<?> cls, String methodName) {
        try {
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length > 0) return m.getParameterTypes()[0];
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static void bindClickListener(final Object pref, ClassLoader cl, final Runnable onClick) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            if (listenerType == null) return;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                try { onClick.run(); } catch (Throwable ignored) {}
                                return Boolean.TRUE;
                            }
                        } catch (Throwable ignored) {}
                        return Boolean.FALSE;
                    }
                });
            pref.getClass().getMethod("setOnPreferenceClickListener", listenerType).invoke(pref, proxy);
        } catch (Throwable ignored) {}
    }
    private static void bindChangeListener(final Object pref, final Context ctx, ClassLoader cl, final Runnable onToggle) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            if (listenerType == null) return;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                boolean en = args[1] instanceof Boolean && (Boolean) args[1];
                                setEnabled(ctx, en);
                                try { onToggle.run(); } catch (Throwable ignored) {}
                                return Boolean.TRUE;
                            }
                        } catch (Throwable ignored) {}
                        return Boolean.FALSE;
                    }
                });
            pref.getClass().getMethod("setOnPreferenceChangeListener", listenerType).invoke(pref, proxy);
        } catch (Throwable ignored) {}
    }

    private static void XposedBridgeLog(String m) {
        try { MainModule.logMsg("[SBPlus] " + m); } catch (Throwable ignored) {}
    }
    private static void toast(Context ctx, String msg) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
    private static int dp(Context ctx, int v) {
        try { return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f); } catch (Throwable t) { return v; }
    }
}
