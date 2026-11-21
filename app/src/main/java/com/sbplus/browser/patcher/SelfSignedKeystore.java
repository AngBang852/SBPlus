package com.sbplus.browser.patcher;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 本机自签名证书（2026-11-22 代码审查 S1 修复）。
 *
 * <p><b>要解决的问题</b>：原实现把一张固定的 PKCS12 私钥打包在
 * {@code assets/patch.keystore} 里，口令也硬编码在源码中。任何拿到模块 APK 的人
 * 都能解出这张私钥，从而签出与用户已安装的补丁版浏览器<b>同证书</b>的包 ——
 * Android 的更新校验只看证书，于是可以构造 versionCode 更高的恶意包，
 * 以"正常更新"的姿态覆盖用户设备上的补丁版浏览器。这不是理论风险：
 * 私钥随 APK 公开分发，取用成本为零。
 *
 * <p><b>现在的做法</b>：私钥<b>不再随 APK 分发</b>，首次打补丁时在本机生成
 * 一对 RSA 2048 密钥 + 一张自签名 X.509 证书，存进应用私有目录。
 * 每台设备/每次全新安装的证书都不同，第三方无法伪造"同证书更新"。
 *
 * <p><b>为什么自己拼 DER 而不是用 BouncyCastle</b>：本模块刻意不引入第三方
 * 加密库（历史上 androidx.core / conscrypt 的同名类冲突已经让宿主崩溃过）。
 * 自签证书只需要构造一棵固定的 ASN.1 树，用 JDK 自带的
 * {@code KeyPairGenerator + Signature + CertificateFactory} 就够了，
 * 不增加任何依赖、不增加 APK 体积。
 *
 * <p><b>口令怎么存</b>：随机 32 字节（Base64）写入同目录的 {@code .pass} 文件，
 * 与应用私有存储同权限（MODE 600，其它应用与普通 adb 备份都读不到）。
 * 这比硬编码在 dex 里强得多 —— 后者是"全世界都能读"，前者是"只有本应用进程能读"。
 *
 * <p><b>兼容性</b>：旧版本已经在 {@code filesDir/patch.keystore} 留下过固定
 * 私钥（见 {@link #LEGACY_KEYSTORE_NAME}）。为了不破坏这些用户已经装上的
 * 补丁版浏览器（换证书会导致 {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}，
 * 必须卸载重装），这里<b>优先沿用</b>旧文件，并由调用方在界面上提示
 * "重装可切换到本机证书"。新装用户走随机证书路径。
 */
final class SelfSignedKeystore {

    /** 新生成的 keystore 文件名（扩展名决定 ApkSignerWrapper 走 PKCS12）。 */
    static final String KEYSTORE_NAME = "sbplus-signing.p12";
    /** 口令文件名。 */
    static final String PASS_NAME = "sbplus-signing.pass";
    /** 旧版本遗留的固定 keystore 文件名。 */
    static final String LEGACY_KEYSTORE_NAME = "patch.keystore";
    /**
     * 旧版本硬编码的口令（只用于<b>读取</b>遗留文件，新流程不再使用）。
     *
     * <p>保留它的唯一目的：让 2026-11-22 之前已经打过补丁的用户，在升级模块后
     * 仍能用同一张证书覆盖安装 —— 换证书会触发
     * {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}，只能卸载重装（丢浏览器数据）。
     * 新装用户根本没有这个文件，走 {@link #obtain} 的随机证书分支。
     */
    static final String LEGACY_PASS = "sbpluspatch";
    /**
     * 旧版本 keystore 的别名。
     *
     * <p><b>不能写成新别名 sbplus</b>：旧版 PatchActivity 构造 PatchConfig 时
     * storePass / alias / keyPass 三个参数传的是同一个字符串，实测
     * {@code keytool -list} 显示别名就是 {@code sbpluspatch}。写成 sbplus 会让
     * {@code ks.isKeyEntry()} 返回 false，旧用户被静默换成新证书 —— 正是这条兼容
     * 分支要避免的事故。
     */
    static final String LEGACY_ALIAS = "sbpluspatch";
    /** 新 keystore 的别名。 */
    static final String ALIAS = "sbplus";

    private static final int KEY_BITS = 2048;
    /** 证书有效期：20 年（UTCTime 只能表示到 2049，必须留出余量）。 */
    private static final long VALIDITY_MS = 20L * 365L * 24L * 3600L * 1000L;

    private static final SecureRandom RND = new SecureRandom();

    private SelfSignedKeystore() {
    }

    /** 一张可用的签名身份：文件 + 口令 + 别名 + 是否来自旧版本的公开证书。 */
    static final class Identity {
        final File keystore;
        final String alias;
        final String storePass;
        final String keyPass;
        /** true = 沿用旧版本的固定证书（公开私钥）。 */
        final boolean legacy;

        Identity(File keystore, String alias, String storePass, String keyPass, boolean legacy) {
            this.keystore = keystore;
            this.alias = alias;
            this.storePass = storePass;
            this.keyPass = keyPass;
            this.legacy = legacy;
        }

        /** 给界面用的一句话说明。 */
        String describe() {
            return legacy
                    ? "旧版公开证书（" + keystore.getName() + "）"
                    : "本机随机证书（" + keystore.getName() + "）";
        }
    }

    /**
     * 取得签名身份：旧版遗留文件 → 已有随机证书 → 现场生成一张。
     *
     * @param filesDir 应用私有目录（{@code context.getFilesDir()}）
     * @throws Exception 生成失败（密钥算法不可用、磁盘满等），由调用方决定是否阻断
     */
    static Identity obtain(File filesDir) throws Exception {
        File legacy = new File(filesDir, LEGACY_KEYSTORE_NAME);
        if (legacy.isFile() && legacy.length() > 0) {
            // 旧用户：私钥已经在设备上，换证书只会让已装补丁版浏览器无法覆盖升级。
            return new Identity(legacy, LEGACY_ALIAS, LEGACY_PASS, LEGACY_PASS, true);
        }

        File keystore = new File(filesDir, KEYSTORE_NAME);
        File passFile = new File(filesDir, PASS_NAME);
        if (keystore.isFile() && keystore.length() > 0 && passFile.isFile()) {
            String pass = readText(passFile);
            if (pass != null && pass.length() > 0) {
                Identity id = new Identity(keystore, ALIAS, pass, pass, false);
                try {
                    loadIdentity(id);      // 自检：能读出来才复用
                    return id;
                } catch (Throwable t) {
                    // 文件损坏 / 口令丢失 → 重新生成（旧文件随后被覆盖）
                }
            }
        }
        return create(filesDir, keystore, passFile);
    }

    /** 生成一对新密钥 + 自签证书，落盘为 PKCS12，并做一次"能签能验"的自检。 */
    private static Identity create(File filesDir, File keystore, File passFile) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_BITS, RND);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate cert = buildCertificate(kp, "CN=SBPlus Patch, O=SBPlus, C=CN");
        String pass = randomPass();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), pass.toCharArray(),
                new Certificate[]{cert});

        // 先写临时文件再改名：避免"keystore 写了一半、口令已落盘"的半成品状态。
        File tmp = new File(filesDir, KEYSTORE_NAME + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            ks.store(os, pass.toCharArray());
        } finally {
            closeQuietly(os);
        }

        Identity id = new Identity(keystore, ALIAS, pass, pass, false);
        // 自检 1：重新加载，确认私钥与证书链都在
        loadIdentityFrom(tmp, ALIAS, pass);
        // 自检 2：真签一次、真验一次，确认证书与私钥配对
        selfTest(kp.getPrivate(), cert);

        if (keystore.exists() && !keystore.delete()) {
            // Windows/部分文件系统上偶发占用；改名会失败的场景极少，报出来比静默好
            throw new java.io.IOException("无法替换旧 keystore: " + keystore);
        }
        if (!tmp.renameTo(keystore)) {
            throw new java.io.IOException("无法写入 keystore: " + keystore);
        }
        writeText(passFile, pass);
        return id;
    }

    /** 读取并校验一个签名身份。 */
    static void loadIdentity(Identity id) throws Exception {
        loadIdentityFrom(id.keystore, id.alias, id.storePass);
    }

    private static void loadIdentityFrom(File file, String alias, String pass) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(file);
        try {
            ks.load(in, pass.toCharArray());
        } finally {
            closeQuietly(in);
        }
        if (!ks.isKeyEntry(alias)) {
            throw new java.io.IOException("keystore 里没有别名: " + alias);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        Certificate[] chain = ks.getCertificateChain(alias);
        if (key == null || chain == null || chain.length == 0) {
            throw new java.io.IOException("keystore 缺少私钥或证书链: " + alias);
        }
    }

    /** 用私钥签一段随机数据，再用证书里的公钥验签 —— 保证两者确实配对。 */
    private static void selfTest(PrivateKey key, X509Certificate cert) throws Exception {
        byte[] probe = new byte[32];
        RND.nextBytes(probe);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(probe);
        byte[] sig = s.sign();

        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(cert.getPublicKey());
        v.update(probe);
        if (!v.verify(sig)) {
            throw new java.io.IOException("自签证书自检失败：证书公钥与私钥不匹配");
        }
    }

    /** 生成自签名 X.509 证书（手写 DER，不依赖第三方库）。 */
    private static X509Certificate buildCertificate(KeyPair kp, String dn) throws Exception {
        long now = System.currentTimeMillis();
        long notBefore = now - 24L * 3600L * 1000L;   // 回拨一天，容忍设备时钟偏差
        long notAfter = now + VALIDITY_MS;

        byte[] serial = new BigInteger(63, RND).add(BigInteger.ONE).toByteArray(); // 正数

        byte[] tbs = derSeq(
                derExplicit(0, derInt(2)),                 // version: v3
                derTLV(0x02, serial),                      // serialNumber
                algorithmIdentifier(),                      // signature
                distinguishedName(dn),                      // issuer
                derSeq(derUtcTime(notBefore), derUtcTime(notAfter)),
                distinguishedName(dn),                      // subject
                subjectPublicKeyInfo(kp));                  // subjectPublicKeyInfo

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(kp.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();

        byte[] certDer = derSeq(tbs, algorithmIdentifier(), derBitString(sig));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
    }

    // ───────────────────────── ASN.1 / DER ─────────────────────────

    /** 1.2.840.113549.1.1.1 (rsaEncryption) */
    private static final byte[] OID_RSA = {
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};
    /** 1.2.840.113549.1.1.11 (sha256WithRSAEncryption) */
    private static final byte[] OID_SHA256_RSA = {
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    /** 2.5.4.3 (commonName) */
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};
    /** 2.5.4.10 (organizationName) */
    private static final byte[] OID_O = {0x55, 0x04, 0x0A};
    /** 2.5.4.6 (countryName) */
    private static final byte[] OID_C = {0x55, 0x04, 0x06};

    private static byte[] algorithmIdentifier() {
        return derSeq(derTLV(0x06, OID_SHA256_RSA), new byte[]{0x05, 0x00});
    }

    /** 解析 "CN=xxxx" 这样的简易 DN（只支持 CN/O/C，够用即可）。 */
    private static byte[] distinguishedName(String dn) {
        java.util.List<byte[]> rdns = new java.util.ArrayList<byte[]>();
        for (String part : dn.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim().toUpperCase(Locale.US);
            String v = p.substring(eq + 1).trim();
            byte[] oid;
            if ("CN".equals(k)) oid = OID_CN;
            else if ("O".equals(k)) oid = OID_O;
            else if ("C".equals(k)) oid = OID_C;
            else continue;
            rdns.add(derSet(derSeq(derTLV(0x06, oid),
                    derTLV(0x0C, v.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));   // UTF8String
        }
        return derSeq(rdns.toArray(new byte[0][]));
    }

    private static byte[] subjectPublicKeyInfo(KeyPair kp) {
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] rsaKey = derSeq(derTLV(0x02, pub.getModulus().toByteArray()),
                derTLV(0x02, pub.getPublicExponent().toByteArray()));
        byte[] alg = derSeq(derTLV(0x06, OID_RSA), new byte[]{0x05, 0x00});
        return derSeq(alg, derBitString(rsaKey));
    }

    private static byte[] derUtcTime(long millis) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return derTLV(0x17, f.format(new Date(millis)).getBytes("US-ASCII"));
    }

    private static byte[] derInt(int v) {
        return derTLV(0x02, BigInteger.valueOf(v).toByteArray());
    }

    private static byte[] derBitString(byte[] data) {
        byte[] v = new byte[data.length + 1];
        v[0] = 0;                                  // 未使用位数
        System.arraycopy(data, 0, v, 1, data.length);
        return derTLV(0x03, v);
    }

    private static byte[] derSeq(byte[]... parts) {
        return derTLV(0x30, concat(parts));
    }

    private static byte[] derSet(byte[]... parts) {
        return derTLV(0x31, concat(parts));
    }

    private static byte[] derExplicit(int tag, byte[] inner) {
        return derTLV(0xA0 | tag, inner);
    }

    private static byte[] derTLV(int tag, byte[] value) {
        byte[] len = derLength(value.length);
        byte[] out = new byte[1 + len.length + value.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(value, 0, out, 1 + len.length, value.length);
        return out;
    }

    private static byte[] derLength(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        int bytes = n <= 0xFF ? 1 : n <= 0xFFFF ? 2 : n <= 0xFFFFFF ? 3 : 4;
        byte[] out = new byte[bytes + 1];
        out[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) {
            out[bytes - i] = (byte) ((n >>> (8 * i)) & 0xFF);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static String randomPass() {
        byte[] b = new byte[32];
        RND.nextBytes(b);
        return android.util.Base64.encodeToString(b,
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    }

    private static String readText(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, "UTF-8").trim();
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeText(File f, String text) throws Exception {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            closeQuietly(os);
        }
        // 私有目录默认就是 600；显式收紧一次，避免被同 UID 的调试工具顺手读走
        try {
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
