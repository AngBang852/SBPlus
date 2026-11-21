package com.sbplus.browser.patcher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * APK 打补丁引擎的配置与产物描述。
 *
 * <p>对标 Morphe（ReVanced 系）的思路：不联网、不上传、全部在设备本地完成。
 * 用户选一个原始 APK → 注入运行时 → 产出可安装的修改版。
 *
 * <p><b>只支持原包名（SAME_PACKAGE）</b>。2026-11-22 起<b>移除共存版</b>：
 * 真机实测表明共存版可以安装、Pine 也能初始化，但启动即崩在
 * {@code MostVisitedSitesProvider} 的权限校验 —— 该 authority 硬编码在浏览器
 * dex 里，改 manifest 无法解决。与其留下一个"能装不能用"的入口，不如彻底去掉。
 */
public final class PatchConfig {

    /** 应用上下文（注入时需要读 assets 里的运行时 dex 与 so）。 */
    public final Context context;
    /** 源 APK。 */
    public final File sourceApk;
    /** 工作目录（中间产物）。 */
    public final File workDir;
    /** 输出 APK。 */
    public final File outputApk;
    /** 要应用的补丁 id 列表；空表示全部可用补丁。 */
    public final List<String> patchIds = new ArrayList<String>();
    /** 签名 keystore。 */
    public final File keystore;
    public final String keystorePass;
    public final String keyAlias;
    public final String keyPass;
    /** 进度回调（在主线程外调用，UI 侧自行 post）。 */
    public ProgressListener listener;

    public PatchConfig(Context context, File sourceApk, File workDir, File outputApk,
                       File keystore, String keystorePass, String keyAlias, String keyPass) {
        this.context = context;
        this.sourceApk = sourceApk;
        this.workDir = workDir;
        this.outputApk = outputApk;
        this.keystore = keystore;
        this.keystorePass = keystorePass;
        this.keyAlias = keyAlias;
        this.keyPass = keyPass;
    }

    /** 进度回调。stage 用于 UI 分组，detail 是给人看的一句话。 */
    public interface ProgressListener {
        void onProgress(String stage, String detail, int percent);
    }

    public void report(String stage, String detail, int percent) {
        if (listener != null) {
            try {
                listener.onProgress(stage, detail, percent);
            } catch (Throwable ignored) {
            }
        }
    }
}
