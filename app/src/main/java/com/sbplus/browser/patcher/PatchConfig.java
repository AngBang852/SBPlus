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
 * <p><b>两种输出模式</b>：
 * <ul>
 *   <li>{@link Mode#SAME_PACKAGE} —— 保持原包名。资源与 manifest 除注入的 provider
 *       外原样不动。优点是最贴近原版（三星 Stub、账号、默认浏览器设置都不受影响）；
 *       缺点是必须卸载原版才能装（签名不同）。</li>
 *   <li>{@link Mode#COEXIST} —— 改包名，可与原版共存。用 ARSCLib 原地改
 *       resources.arsc 与 AndroidManifest.xml，**不经过 aapt2** ——
 *       这是绕开三星 11 个私有属性编译失败的关键。</li>
 * </ul>
 */
public final class PatchConfig {

    /** 输出模式。 */
    public enum Mode {
        /** 原包名：必须卸载原版才能装。 */
        SAME_PACKAGE,
        /** 共存版：改包名 + 改应用名，可与原版并存。 */
        COEXIST
    }

    /** 应用上下文（注入时需要读 assets 里的运行时 dex 与 so）。 */
    public final Context context;
    /** 源 APK。 */
    public final File sourceApk;
    /** 工作目录（中间产物）。 */
    public final File workDir;
    /** 输出 APK。 */
    public final File outputApk;
    /** 输出模式。 */
    public final Mode mode;
    /** 共存版完整包名（用户可自定义，例如 com.sec.android.app.sbrowser.sbplus）。
     *  原包名模式下此字段为 null。 */
    public final String coexistPackage;
    /** 共存版应用名（桌面显示）。 */
    public final String appLabel;
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
                       Mode mode, String coexistPackage, String appLabel,
                       File keystore, String keystorePass, String keyAlias, String keyPass) {
        this.context = context;
        this.sourceApk = sourceApk;
        this.workDir = workDir;
        this.outputApk = outputApk;
        this.mode = mode;
        this.coexistPackage = coexistPackage;
        this.appLabel = appLabel;
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
