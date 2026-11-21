package com.sbplus.browser.patcher;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 注入的核心逻辑（**不依赖 Android 运行时**，可在 PC 上跑测试）。
 *
 * <p>把「改 manifest」「拼 ZIP」两件事抽出来做成纯函数，好处是：
 * <ul>
 *   <li>PC 上可以直接跑一遍完整注入 + 签名，验证产出的包能不能装 ——
 *       不必每次都推到手机上试（一次 300MB 的传输 + 安装要几分钟）；</li>
 *   <li>逻辑与 Android 的 {@code Context} 解耦，{@link RuntimeInjector} 只负责
 *       从 assets 取文件、把结果交回来。</li>
 * </ul>
 *
 * <p>这个类同时被 {@link RuntimeInjector}（设备端）和
 * {@code tools/InjectTest.java}（PC 端验证）调用，所以两者跑的是**同一份代码**，
 * 不存在「PC 上验证过、手机上不работа」的分裂。
 */
public final class ApkInjectorCore {

    /** provider 的 authority（必须全局唯一）。 */
    static final String PROVIDER_AUTHORITY = "com.sbplus.browser.patchinit";
    /** provider 的类名。 */
    static final String PROVIDER_CLASS = "com.sbplus.browser.rt.PatchInitProvider";

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private ApkInjectorCore() {
    }

    /**
     * 在 manifest 里插入 provider 节点，返回新的二进制 XML。
     *
     * @param rawManifest 原 AndroidManifest.xml 的字节
     */
    public static byte[] patchManifest(byte[] rawManifest) throws IOException {
        AndroidManifestBlock manifest = new AndroidManifestBlock();
        manifest.readBytes(new ByteArrayInputStream(rawManifest));

        ResXmlElement root = manifest.getDocumentElement();
        if (root == null) {
            throw new IOException("manifest 没有根元素");
        }
        ResXmlElement application = null;
        for (ResXmlElement e : root.listElements("application")) {
            application = e;
            break;
        }
        if (application == null) {
            throw new IOException("manifest 里没有 <application>");
        }
        if (hasProvider(application)) {
            // 幂等：已注入过就不重复加（重复 provider 会让安装失败）。
        } else {
            addProvider(application);
        }

        // 关键：改完必须 refreshFull()。
        //
        // ARSCLib 的二进制 XML 是「块链」结构：根 chunk 头里记着总长度，
        // 各子 chunk（字符串池、资源映射、元素节点）也各记自己的长度。
        // 插入新元素后这些长度全部过期，不刷新就直接 writeBytes，
        // 出来的字节头里 size 仍是旧值（实测 224264），aapt / Android 解析时
        // 按旧长度截断 → "failed to initialize ResXMLTree"。
        manifest.refreshFull();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(rawManifest.length + 4096);
        // ARSCLib 的 writeBytes 只接受 File（ResXmlDocument.writeBytes(File)），
        // 用临时文件中转，避免依赖内部 API。
        //
        // 2026-11-22 代码审查修复：原来 finally 里是空的（注释写「系统临时目录会自动清理」），
        // 但每次注入都会留下一个 ~220KB 的 sbplus-manifest*.xml，反复注入会持续堆积。
        // 改为读完立刻删；Windows 上删除失败（文件仍被占用）也不影响主流程，静默忽略。
        File tmp = File.createTempFile("sbplus-manifest", ".xml");
        try {
            manifest.writeBytes(tmp);
            InputStream in = new FileInputStream(tmp);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            try {
                if (!tmp.delete()) tmp.deleteOnExit();
            } catch (Throwable ignored) {
            }
        }
        return bos.toByteArray();
    }

    /** 判断是否已经有本模块的 provider。 */
    static boolean hasProvider(ResXmlElement application) {
        for (ResXmlElement p : application.listElements("provider")) {
            ResXmlAttribute name = p.searchAttribute(NS_ANDROID, "name");
            if (name != null && PROVIDER_CLASS.equals(name.getValueAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 插入 {@code <provider>}。
     *
     * <p>{@code android:initOrder=1000} 让它尽量早于其它 provider 初始化 ——
     * hook 越早装好，能拦到的浏览器初始化路径就越完整。
     */
    static void addProvider(ResXmlElement application) {
        ResXmlElement provider = application.newElement("provider");

        ResXmlAttribute name = provider.getOrCreateAndroidAttribute("name", 0x01010003);
        name.setValueAsString(PROVIDER_CLASS);

        ResXmlAttribute authorities =
                provider.getOrCreateAndroidAttribute("authorities", 0x01010018);
        authorities.setValueAsString(PROVIDER_AUTHORITY);

        ResXmlAttribute exported =
                provider.getOrCreateAndroidAttribute("exported", 0x01010010);
        exported.setValueAsBoolean(false);

        ResXmlAttribute enabled =
                provider.getOrCreateAndroidAttribute("enabled", 0x0101000e);
        enabled.setValueAsBoolean(true);

        ResXmlAttribute initOrder =
                provider.getOrCreateAndroidAttribute("initOrder", 0x0101001a);
        initOrder.setData(1000);
        initOrder.setValueType(com.reandroid.arsc.value.ValueType.DEC);
    }

    /**
     * 按原条目顺序逐个写出，替换 manifest、追加 dex 与 so。
     *
     * <p><b>不用 ARSCLib 的 writeApk</b>：它内部按名字建 map，会吞掉源 APK 里
     * 156 组重名条目（ZIP 规范允许、资源表按偏移引用）。必须按条目序列逐个写。
     */
    public static void repack(File baseApk, File outApk, byte[] newManifest,
                              String dexName, File dexFile,
                              String soName, File soFile) throws IOException {
        ZipFile zf = new ZipFile(baseApk);
        ApkZipWriter zw = new ApkZipWriter(outApk);
        boolean manifestWritten = false;
        boolean soReplaced = false;
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();

                if ("AndroidManifest.xml".equals(name)) {
                    if (manifestWritten) continue;
                    zw.putStored(name, crc32(newManifest), newManifest.length, 4,
                            new ByteArrayInputStream(newManifest));
                    manifestWritten = true;
                    continue;
                }
                if (soName.equals(name)) {
                    putStoredFile(zw, name, soFile, 4096);
                    soReplaced = true;
                    continue;
                }
                if (e.isDirectory()) {
                    zw.putStored(name, 0, 0, 1, EMPTY);
                    continue;
                }
                InputStream in = zf.getInputStream(e);
                try {
                    zw.putEntry(e, in, alignmentFor(name));
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!manifestWritten) {
                zw.putStored("AndroidManifest.xml", crc32(newManifest),
                        newManifest.length, 4, new ByteArrayInputStream(newManifest));
            }
            putStoredFile(zw, dexName, dexFile, 4);
            if (!soReplaced) {
                putStoredFile(zw, soName, soFile, 4096);
            }
            zw.close();
        } catch (IOException ioe) {
            try {
                zw.close();
            } catch (Throwable ignored) {
            }
            throw ioe;
        } finally {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 找出下一个可用的 {@code classesN.dex} 名字。 */
    public static String nextDexName(File apk) throws IOException {
        int max = 0;
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".dex") || n.contains("/")) continue;
                if ("classes.dex".equals(n)) {
                    max = Math.max(max, 1);
                } else if (n.startsWith("classes")) {
                    String num = n.substring("classes".length(), n.length() - 4);
                    try {
                        max = Math.max(max, Integer.parseInt(num));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } finally {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
        return "classes" + (max + 1) + ".dex";
    }

    /** 读 APK 内某个条目的全部字节。 */
    public static byte[] readEntry(File apk, String name) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            InputStream in = zf.getInputStream(e);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(
                        (int) Math.max(1024, e.getSize()));
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static void putStoredFile(ApkZipWriter zw, String name, File f, int alignment)
            throws IOException {
        long size = f.length();
        CRC32 crc = new CRC32();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        in = new FileInputStream(f);
        try {
            zw.putStored(name, crc.getValue(), size, alignment, in);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final InputStream EMPTY = new InputStream() {
        @Override
        public int read() {
            return -1;
        }
    };

    /** {@code .so} 页对齐；{@code .dex} / {@code resources.arsc} 4 字节对齐。 */
    static int alignmentFor(String name) {
        if (name.endsWith(".so")) return 4096;
        return 4;
    }

    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }
}