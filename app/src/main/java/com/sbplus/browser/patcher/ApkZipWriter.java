package com.sbplus.browser.patcher;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

/**
 * 最小 ZIP 写入器：支持**重名条目** + zipalign。
 *
 * <p><b>为什么不能用 java.util.zip.ZipOutputStream</b>
 *
 * <p>它对条目名做 {@code HashSet} 去重，遇到重名直接抛
 * {@code ZipException: duplicate entry: xxx}。而三星浏览器的 APK 里存在
 * <b>大量重名条目</b>（实测 156 组，例如两个 {@code META-INF/}、
 * 多组同名资源，内容不同 —— 资源表靠偏移引用，名字只是标签）。
 * 这不是异常包，是 ZIP 规范允许的（规范只要求 central directory 可寻址，
 * 不要求名字唯一），而 Android 的安装器、apksig 都能正确处理。
 *
 * <p>所以必须在写入层绕开去重 —— 自己拼 ZIP 结构。
 *
 * <p><b>顺带做 zipalign</b>：{@code STORED} 条目按 4 字节对齐、{@code .so}
 * 按 4096 页对齐。不做的话 Android 11+ 安装会报
 * {@code INSTALL_PARSE_FAILED_NOT_APK}（未对齐的 .so 无法 mmap）。
 *
 * <p><b>为什么用 RandomAccessFile 而不是流式写</b>
 *
 * <p>{@code DEFLATED} 条目在写 local header 时还不知道压缩后大小与 CRC，
 * 必须先占位、写完数据再 seek 回来回填。{@code STORED} 条目则相反（大小已知，
 * 一次写成）。两条路径共用同一套 header 拼装逻辑。
 *
 * <p><b>ZIP64 说明</b>：本写入器<b>不支持</b> ZIP64（不写 0xFFFFFFFF 降级记录）。
 * 三星浏览器 APK 约 300MB、条目数 5600+，都在 32 位范围内。若将来遇到
 * 超 4GB 的单条目或超 65535 个条目，这里会主动抛异常而不是静默写坏。
 */
final class ApkZipWriter implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ---- ZIP 结构签名 ----
    private static final int SIG_LOCAL = 0x04034b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_EOCD = 0x06054b50;

    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    /** 通用标志位 bit 11：文件名按 UTF-8 编码。 */
    private static final int FLAG_UTF8 = 0x0800;
    /** 解压所需版本 2.0（支持 deflate）。 */
    private static final int VERSION_NEEDED = 20;
    /**
     * 创建者版本：高字节 3 = Unix，低字节 20 = 2.0。
     * 写 Unix 是为了让外部属性里的权限位有意义（apksig 会读它做签名校验）。
     */
    private static final int VERSION_MADE_BY = (3 << 8) | 20;

    /** 用于对齐填充的 extra field id（0xFFFF 是规范保留的「无操作」段）。 */
    private static final int EXTRA_ID_PADDING = 0xFFFF;

    /** 单条目大小上限（ZIP64 门槛）。 */
    private static final long MAX_32 = 0xFFFFFFFFL;
    /** 条目数上限（EOCD 的 16 位计数器）。 */
    private static final int MAX_ENTRIES = 0xFFFF;

    private final RandomAccessFile out;
    private final List<Cd> cds = new ArrayList<Cd>();
    /** 当前写入位置。手动维护，避免每次 getFilePointer 的系统调用开销。 */
    private long offset;
    private boolean closed;

    /** central directory 条目（写完数据后才知道的字段在这里补齐）。 */
    private static final class Cd {
        byte[] name;
        int method;
        int flags;
        long crc;
        long compSize;
        long size;
        long localOffset;
        int modTime;
        int modDate;
        int extAttr;
    }

    ApkZipWriter(File f) throws IOException {
        this.out = new RandomAccessFile(f, "rw");
        this.out.setLength(0);
    }

    // ------------------------------------------------------------------ 写入入口

    /**
     * 写入一个 {@code STORED}（不压缩）条目。
     *
     * <p>调用方必须预先算好 CRC 与长度 —— 这是 STORED 能「一次写成」的前提。
     * 需要对齐的条目（{@code .so} / {@code .dex} / {@code resources.arsc}）
     * 通过 {@code alignment} 指定，写入器会在 local header 后补 extra field 把
     * <b>数据起始偏移</b>顶到对齐边界。
     *
     * @param name      APK 内路径
     * @param crc       数据 CRC32
     * @param size      数据字节数
     * @param alignment 数据起始偏移的对齐要求（1 = 不对齐）
     * @param data      数据源，必须恰好提供 {@code size} 字节
     */
    void putStored(String name, long crc, long size, int alignment, InputStream data)
            throws IOException {
        ensureOpen();
        if (size < 0 || size > MAX_32) {
            throw new IOException("条目过大（不支持 ZIP64）: " + name + " = " + size);
        }
        byte[] nb = name.getBytes(UTF8);
        int extraLen = extraLenFor(nb.length, alignment);
        long localOffset = offset;
        int modTime = 0;
        int modDate = dosDate(2020, 1, 1);
        writeLocalHeader(nb, METHOD_STORED, FLAG_UTF8, crc, size, size, extraLen, modTime, modDate);
        copyExact(data, size);
        cds.add(buildCd(nb, METHOD_STORED, FLAG_UTF8, crc, size, size, localOffset, modTime, modDate));
    }

    /**
     * 写入一个 {@code DEFLATED}（压缩）条目。
     *
     * <p>压缩后大小与 CRC 在写完之前未知，所以 local header 先写 0，
     * 数据落盘后再 seek 回填（{@code CRC32} 的偏移见 {@link #writeLocalHeader}）。
     */
    void putDeflated(String name, InputStream data) throws IOException {
        ensureOpen();
        byte[] nb = name.getBytes(UTF8);
        long localOffset = offset;
        int modTime = 0;
        int modDate = dosDate(2020, 1, 1);
        // 先占位：crc / compSize / size 全写 0，稍后回填。
        writeLocalHeader(nb, METHOD_DEFLATED, FLAG_UTF8, 0, 0, 0, 0, modTime, modDate);

        CRC32 crc = new CRC32();
        long size = 0;
        long compSize = 0;
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        byte[] buf = new byte[64 * 1024];
        byte[] cbuf = new byte[64 * 1024];
        try {
            int n;
            while ((n = data.read(buf)) > 0) {
                crc.update(buf, 0, n);
                size += n;
                def.setInput(buf, 0, n);
                // needsInput() 为 false 表示内部缓冲还满着，继续吐压缩数据。
                while (!def.needsInput()) {
                    int c = def.deflate(cbuf, 0, cbuf.length);
                    if (c <= 0) break;
                    out.write(cbuf, 0, c);
                    compSize += c;
                }
            }
            def.finish();
            while (!def.finished()) {
                int c = def.deflate(cbuf, 0, cbuf.length);
                if (c <= 0) break;
                out.write(cbuf, 0, c);
                compSize += c;
            }
        } finally {
            def.end();
        }
        offset += compSize;

        if (compSize > MAX_32 || size > MAX_32) {
            throw new IOException("压缩条目过大（不支持 ZIP64）: " + name);
        }
        // 回填 local header 的 CRC(offset+14) / compSize(+18) / size(+22)。
        //
        // 关键：writeLe32 会推进 offset 计数器（它服务于顺序写入路径）。
        // 这里是在**已经写过的地方**改字节，文件指针会 seek 回去，
        // 所以 offset 必须先存下来、写完再恢复 —— 否则计数器会比真实文件位置
        // 多出 12 字节，导致中央目录的偏移整体错位、ZIP 无法解析
        // （实测症状：产物能生成，但 python / aapt / Android 都读不了）。
        long resume = offset;
        offset = localOffset + 14;      // 让 writeLe32 的计数与真实位置一致
        out.seek(localOffset + 14);
        writeLe32((int) crc.getValue());
        writeLe32((int) compSize);
        writeLe32((int) size);
        offset = resume;
        out.seek(resume);

        cds.add(buildCd(nb, METHOD_DEFLATED, FLAG_UTF8, crc.getValue(), compSize, size,
                localOffset, modTime, modDate));
    }

    /**
     * 直接写入一个已有 {@link ZipEntry} 对应的数据（保留其 method / 时间）。
     *
     * <p>用于「原样复制」：调用方从源 ZIP 读 entry 和数据流，这里负责按原
     * method 重写。STORED 条目走 {@link #putStored}，其余走 {@link #putDeflated}。
     */
    void putEntry(ZipEntry e, InputStream data, int alignment) throws IOException {
        if (e.getMethod() == ZipEntry.STORED) {
            long size = e.getSize();
            long crc = e.getCrc();
            if (size < 0 || crc < 0) {
                throw new IOException("源条目缺少 size/crc，无法 STORED 复制: " + e.getName());
            }
            putStored(e.getName(), crc, size, alignment, data);
        } else {
            putDeflated(e.getName(), data);
        }
    }

    // ------------------------------------------------------------------ 收尾

    @Override
    public void close() throws IOException {
        if (closed) {
            out.close();
            return;
        }
        closed = true;
        try {
            if (cds.size() > MAX_ENTRIES) {
                throw new IOException("条目数超过 65535，需 ZIP64（未实现）");
            }
            long cdStart = offset;
            for (Cd cd : cds) {
                writeLe32(SIG_CENTRAL);
                writeLe16(VERSION_MADE_BY);
                writeLe16(VERSION_NEEDED);
                writeLe16(cd.flags);
                writeLe16(cd.method);
                writeLe16(cd.modTime);
                writeLe16(cd.modDate);
                writeLe32((int) cd.crc);
                writeLe32((int) cd.compSize);
                writeLe32((int) cd.size);
                writeLe16(cd.name.length);
                writeLe16(0);              // extra field 长度
                writeLe16(0);              // comment 长度
                writeLe16(0);              // 起始磁盘号
                writeLe16(0);              // 内部属性
                writeLe32(cd.extAttr);     // 外部属性（权限位）
                writeLe32((int) cd.localOffset);
                out.write(cd.name);
                offset += cd.name.length;
            }
            long cdSize = offset - cdStart;

            writeLe32(SIG_EOCD);
            writeLe16(0);              // 本磁盘号
            writeLe16(0);              // central directory 起始磁盘号
            writeLe16(cds.size());    // 本磁盘条目数
            writeLe16(cds.size());    // 总条目数
            writeLe32((int) cdSize);
            writeLe32((int) cdStart);
            writeLe16(0);              // comment 长度
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------------ 底层拼接

    private void writeLocalHeader(byte[] name, int method, int flags, long crc,
                                  long compSize, long size, int extraLen,
                                  int modTime, int modDate) throws IOException {
        writeLe32(SIG_LOCAL);
        writeLe16(VERSION_NEEDED);
        writeLe16(flags);
        writeLe16(method);
        writeLe16(modTime);
        writeLe16(modDate);
        writeLe32((int) crc);
        writeLe32((int) compSize);
        writeLe32((int) size);
        writeLe16(name.length);
        writeLe16(extraLen);
        out.write(name);
        offset += name.length;
        if (extraLen > 0) {
            // 对齐填充：一个 id=0xFFFF 的空 extra field，长度 extraLen-4。
            writeLe16(EXTRA_ID_PADDING);
            writeLe16(extraLen - 4);
            for (int i = 4; i < extraLen; i++) {
                out.write(0);
                offset++;
            }
        }
    }

    private static Cd buildCd(byte[] name, int method, int flags, long crc,
                               long compSize, long size, long localOffset,
                               int modTime, int modDate) {
        Cd cd = new Cd();
        cd.name = name;
        cd.method = method;
        cd.flags = flags;
        cd.crc = crc;
        cd.compSize = compSize;
        cd.size = size;
        cd.localOffset = localOffset;
        cd.modTime = modTime;
        cd.modDate = modDate;
        // 外部属性：低 16 位是 Unix 权限。0644 = rw-r--r--，目录用 0755。
        cd.extAttr = 0100644 << 16;
        if (name.length > 0 && name[name.length - 1] == '/') {
            cd.extAttr = 040755 << 16;
        }
        return cd;
    }

    /**
     * 计算对齐所需的 extra field 长度。
     *
     * <p>local header 固定 30 字节 + 文件名，数据紧随其后。要让它落在
     * {@code alignment} 的整数倍上，需补 {@code pad} 字节。
     *
     * <p>extra field 的最小单位是 4 字节（id 2 + 长度 2），所以 {@code pad}
     * 落在 1..3 时必须再加一个 alignment 周期 —— 否则拼出来的字段头非法。
     */
    private int extraLenFor(int nameLen, int alignment) {
        if (alignment <= 1) return 0;
        int base = 30 + nameLen;
        int rem = (int) ((offset + base) % alignment);
        if (rem == 0) return 0;
        int pad = alignment - rem;
        if (pad < 4) pad += alignment;
        return pad;
    }

    /** DOS 日期（1980 起算）。时间统一写 0 点，避免时区影响。 */
    private static int dosDate(int year, int month, int day) {
        return ((year - 1980) << 9) | (month << 5) | day;
    }

    private void writeLe32(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
        offset += 4;
    }

    private void writeLe16(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        offset += 2;
    }

    /** 精确拷贝 {@code size} 字节；源数据不足视为损坏包。 */
    private void copyExact(InputStream in, long size) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remain = size;
        while (remain > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remain));
            if (n < 0) {
                throw new IOException("源数据不足，期望 " + size + " 字节，缺 " + remain);
            }
            out.write(buf, 0, n);
            offset += n;
            remain -= n;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("writer 已关闭");
    }
}
