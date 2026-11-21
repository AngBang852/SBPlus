package com.sbplus.browser.rt;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * 补丁版浏览器的自启动入口。
 *
 * <p><b>为什么用 ContentProvider 而不是改 Application</b>
 *
 * <p>Android 的进程启动顺序是：
 * <pre>
 *   Application.attachBaseContext()
 *     ↓
 *   安装全部 ContentProvider（逐个调 onCreate）
 *     ↓
 *   Application.onCreate()
 *     ↓
 *   首个 Activity
 * </pre>
 *
 * <p>Provider 的 {@code onCreate} 早于 {@code Application.onCreate}，
 * 正是 hook 需要装好的时机 —— 晚于它，浏览器的各个 Service/Manager 已经初始化完、
 * 目标方法可能已被调用过；早于它又没有 Context 可用。
 *
 * <p>更关键的是：<b>这条路不需要改浏览器的任何 dex</b>。只往
 * {@code AndroidManifest.xml} 里加一个 {@code <provider>} 节点即可，
 * 而 manifest 是二进制 XML，用 ARSCLib 改不会触发 aapt2 的三星私有属性问题。
 * 相比「往 {@code SBrowserApplication.attachBaseContext} 插 smali 指令」，
 * 少改一个 8MB 的 dex、少一次全量反汇编重汇编，也少一类寄存器/签名风险。
 *
 * <p><b>为什么这个 provider 是 exported=false</b>：它没有任何对外功能，
 * 只为「进程启动时被系统实例化一次」而存在。导出它只会多一个攻击面。
 */
public final class PatchInitProvider extends ContentProvider {

    private static final String TAG = "SBPlus-InitProvider";

    @Override
    public boolean onCreate() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) {
                Log.w(TAG, "getContext() returned null, skip bootstrap");
                return false;
            }
            PatchBootstrap.install(ctx);
            return true;
        } catch (Throwable t) {
            // 绝不让初始化异常拖垮浏览器启动 —— provider 抛异常会让进程直接起不来。
            Log.e(TAG, "bootstrap failed", t);
            return false;
        }
    }

    // ── 下面四个方法是为了满足 ContentProvider 抽象方法，全部不支持 ──

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
