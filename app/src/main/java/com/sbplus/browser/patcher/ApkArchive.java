package com.sbplus.browser.patcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * APK 解包 / 重打包的底层工具。
 *
 * <p><b>为什么不用 apktool 那套流程</b>：apktool 重打包时会用 aapt2 重新编译资源，
 * 而三星浏览器用了 11 个三星框架私有属性（floatingConfigure、previewLayoutLarge、
 * widgetSize 等），标准 framework 里没有定义，aapt2 直接失败。实测已确认。
 *
 * <p>所以这里的策略是：
 * <ul>
 *   <li><b>资源完全不碰</b> —— 解包时只把需要的文件（dex）取出来，重打包时
 *       用原始 APK 作为底包，只替换改过的条目，其余字节原样复制；</li>
 *   <li><b>共存版改包名</b>走 {@link ArscEditor}（ARSCLib），直接改二进制 arsc，
 *       同样不经过 aapt2。</li>
 * </ul>
 */
public final class ApkArchive {

    private ApkArchive() {
    }

    /**
     * 把 APK 里所有 {@code classes*.dex} 抽到 {@code outDir}。
     *
     * @return 抽出的 dex 文件名列表（按原始顺序）
     */
    public static List<String> extractDex(File apk, File outDir) throws IOException {
        List<String> names = new ArrayList<String>();
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建目录: " + outDir);
        }
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!isDex(n)) continue;
                names.add(n);
                copy(zf.getInputStream(e), new File(outDir, n));
            }
        } finally {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    /**
     * 以原始 APK 为底包，替换其中若干条目，输出新 APK。
     *
     * <p>未列出的条目**逐字节原样复制**，所以签名外的所有内容（资源、lib、assets、
     * manifest）都保持和原版完全一致 —— 这是「只改 dex」模式能成立的关键。
     *
     * @param base        原始 APK
     * @param replacements 要替换的条目：key = APK 内路径，value = 本地新文件
     * @param out         输出 APK
     */
    public static void repack(File base, java.util.Map<String, File> replacements,
                              File out) throws IOException {
        // 2026-11-19 重写：改用 ApkZipWriter。
        //
        // 原实现用 java.util.zip.ZipOutputStream，它在 putNextEntry 时对条目名做
        // HashSet 去重，遇到重名直接抛 ZipException("duplicate entry: xxx")。
        // 三星浏览器 APK 里有 156 组重名条目（内容不同，ZIP 规范允许），
        // 所以原实现**在任何 APK 上都必然失败**——这是打包一直失败的根因。
        //
        // ApkZipWriter 自己拼 ZIP 结构，不做去重，并顺带完成 zipalign
        // （STORED 条目 4 字节、.so 4096 字节对齐），否则 Android 11+ 拒绝安装。
        ZipFile zf = new ZipFile(base);
        ApkZipWriter zw = new ApkZipWriter(out);
        try {
            java.util.Set<String> done = new java.util.HashSet<String>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) {
                    // 目录条目：空数据，不对齐（避免无谓填充）。
                    zw.putStored(name, 0, 0, 1, EMPTY_STREAM);
                    continue;
                }
                File repl = replacements.get(name);
                if (repl != null) {
                    putFile(zw, name, repl);
                    done.add(name);
                } else {
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
            }
            // 新增条目（原 APK 里没有的，例如注入的 dex）
            for (java.util.Map.Entry<String, File> ent : replacements.entrySet()) {
                if (done.contains(ent.getKey())) continue;
                putFile(zw, ent.getKey(), ent.getValue());
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

    /** 空输入流（目录条目的数据源）。 */
    private static final InputStream EMPTY_STREAM = new InputStream() {
        @Override
        public int read() {
            return -1;
        }
    };

    /**
     * 条目的对齐要求。
     *
     * <p>{@code .so} 必须页对齐（4096），否则 Android 11+ 报
     * {@code INSTALL_PARSE_FAILED_NOT_APK}；{@code resources.arsc} 与
     * {@code .dex} 需要 4 字节对齐（mmap 直接映射）。
     */
    private static int alignmentFor(String name) {
        if (name.endsWith(".so")) return 4096;
        return 4;
    }

    /**
     * 写入一个本地文件为条目。
     *
     * <p>dex 用 {@code STORED}（Android 10+ 要求可直接 mmap，压缩过的 dex
     * 会被拒装或触发解压开销）；其余用 {@code DEFLATED}。
     */
    private static void putFile(ApkZipWriter zw, String name, File f) throws IOException {
        if (isDex(name)) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            long size = 0;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    crc.update(buf, 0, n);
                    size += n;
                }
            } finally {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
            fis = new java.io.FileInputStream(f);
            try {
                zw.putStored(name, crc.getValue(), size, 4, fis);
            } finally {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        } else {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try {
                zw.putDeflated(name, fis);
            } finally {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 判断是否为 {@code classes*.dex}。 */
    public static boolean isDex(String name) {
        if (name == null || !name.endsWith(".dex")) return false;
        if (name.contains("/")) return false;
        return name.equals("classes.dex") || name.startsWith("classes") && name.endsWith(".dex");
    }

    static void copy(InputStream in, File out) throws IOException {
        FileOutputStream fos = new FileOutputStream(out);
        try {
            copy(in, fos);
        } finally {
            try {
                fos.close();
            } catch (Throwable ignored) {
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static void copy(File in, OutputStream out) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(in);
        try {
            copy(fis, out);
        } finally {
            try {
                fis.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    static byte[] readAll(File f) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            long len = f.length();
            if (len > Integer.MAX_VALUE) throw new IOException("文件过大: " + f);
            byte[] data = new byte[(int) len];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
            return data;
        } finally {
            try {
                fis.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 递归删除（用于清理工作目录）。 */
    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
    }
}
