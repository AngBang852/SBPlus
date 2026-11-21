package com.sbplus.browser.patcher;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * APK 签名。
 *
 * <p>用 {@code apksig}（Android SDK 官方签名库，来自 build-tools 的 apksigner.jar），
 * 它自带 conscrypt，不需要额外引入 bouncycastle。
 *
 * <p><b>签名方案选择</b>：三星浏览器原版用 v3 方案。我们签出来的包必须至少支持
 * v2/v3（Android 7+ 要求），否则安装会被拒。这里同时开 v1/v2/v3 —— v1 是给
 * 老系统的兜底，v2/v3 是现在的标准。
 *
 * <p><b>为什么签出来的包必须卸载原版才能装</b>：Android 的 PackageManagerService
 * 校验签名，同包名 + 不同证书 = {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}。
 * 想免卸载只能靠签名校验绕过补丁（需要 LSPosed/NPatch 之类的框架支持）。
 */
public final class ApkSignerWrapper {

    private ApkSignerWrapper() {
    }

    /**
     * 用指定 keystore 签名 APK。
     *
     * @param in       输入 APK（会被替换为签名后的内容）
     * @param keystore keystore 文件（PKCS12 或 JKS）
     * @param storePass keystore 口令
     * @param alias    key alias
     * @param keyPass  key 口令
     */
    public static void sign(File in, File keystore, String storePass,
                            String alias, String keyPass) throws Exception {
        sign(in, in, keystore, storePass, alias, keyPass);
    }

    /**
     * 签名到**独立输出文件**（避免 in-place 签名把 296MB 文件整个加载到内存）。
     * ReVanced 也是这么做的 —— 输入和输出用不同文件。
     */
    public static void sign(File in, File out, File keystore, String storePass,
                            String alias, String keyPass) throws Exception {
        KeyStore ks = KeyStore.getInstance(guessStoreType(keystore));
        FileInputStream fis = new FileInputStream(keystore);
        try {
            ks.load(fis, storePass == null ? null : storePass.toCharArray());
        } finally {
            try {
                fis.close();
            } catch (Throwable ignored) {
            }
        }
        if (!ks.isKeyEntry(alias)) {
            throw new IOException("keystore 里没有别名: " + alias);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass == null ? null : keyPass.toCharArray());
        java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
        if (key == null || chain == null || chain.length == 0) {
            throw new IOException("无法读取私钥或证书链: " + alias);
        }
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        for (java.security.cert.Certificate c : chain) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                alias, key, certs).build();
        List<ApkSigner.SignerConfig> signers = new ArrayList<ApkSigner.SignerConfig>();
        signers.add(signerConfig);

        ApkSigner signer = new ApkSigner.Builder(signers)
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();
        signer.sign();
    }

    /** 按文件扩展名猜 keystore 类型。 */
    private static String guessStoreType(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".p12") || n.endsWith(".pfx") || n.endsWith(".pkcs12")) {
            return "PKCS12";
        }
        // Android 的 release.keystore 通常是 JKS；JDK9+ 默认 PKCS12，
        // 但老 keystore 仍是 JKS，用 JKS 读 PKCS12 会失败 → 先试 JKS 再回退。
        try {
            KeyStore.getInstance("JKS");
            return "JKS";
        } catch (Throwable t) {
            return "PKCS12";
        }
    }
}
