package com.sbplus.browser.patcher;

import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;

import java.io.File;
import java.io.IOException;

/**
 * APK 信息读取（原 {@code ArscEditor} 的残留部分）。
 *
 * <p><b>2026-11-22：共存版功能整体移除</b>。原先这个类负责用 ARSCLib 直接改
 * 二进制 {@code resources.arsc} 与 {@code AndroidManifest.xml} 来改包名/应用名
 * （绕开 aapt2 对三星 11 个私有属性的编译失败）。真机验证表明共存版虽然能装，
 * 但浏览器 dex 里硬编码了原包名的 provider authority
 * （{@code com.sec.android.app.sbrowser.quickaccess.db.MostVisitedSitesProvider}），
 * 启动时因签名级权限校验直接崩溃 —— 改 manifest 改不到 dex，故该路线放弃。
 *
 * <p>现在只剩 {@link #readInfo}：在"选择 APK"时读出包名/版本，用来判断是否是
 * 三星浏览器、以及版本提示。
 */
public final class ArscEditor {

    private ArscEditor() {
    }

    /** 读取 APK 的包名 / 版本信息（不需要解包，直接读 manifest）。 */
    public static ApkInfo readInfo(File apk) throws IOException {
        ApkModule module = ApkModule.loadApkFile(apk);
        try {
            AndroidManifestBlock m = module.getAndroidManifest();
            if (m == null) throw new IOException("APK 里没有 AndroidManifest.xml");
            ApkInfo info = new ApkInfo();
            info.packageName = m.getPackageName();
            Integer vc = m.getVersionCode();
            info.versionCode = vc == null ? 0 : vc.longValue();
            info.versionName = m.getVersionName();
            return info;
        } finally {
            try {
                module.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** APK 基本信息。 */
    public static final class ApkInfo {
        public String packageName;
        public long versionCode;
        public String versionName;
    }
}
