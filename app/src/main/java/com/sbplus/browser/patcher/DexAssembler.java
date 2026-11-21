package com.sbplus.browser.patcher;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * dex ⇄ smali 双向转换。
 *
 * <p>直接用 dexlib2 / smali 的 API，不经过命令行 —— Android 上没有
 * {@code java -jar}，而且我们要在应用进程内完成全部工作。
 *
 * <p><b>为什么走 smali 而不是直接改 dex 二进制</b>：dex 指令变长，改一个方法体
 * 会让后续所有偏移量失效。smali 是文本，改完整体重新汇编，偏移由汇编器重算，
 * 安全得多。Morphe 也是这个路子。
 *
 * <p><b>API 说明</b>（smali 2.5.2 实测签名，非猜测）：
 * <ul>
 *   <li>{@code Smali.assemble(SmaliOptions, List<String>)} —— 输入是**目录列表**，
 *       不是单个文件；输出路径写在 {@code options.outputDexFile}</li>
 *   <li>{@code Baksmali.disassembleDexFile(DexBackedDexFile, File, int, BaksmaliOptions)}</li>
 * </ul>
 */
public final class DexAssembler {

    /** Android 10+ 的 dex 版本；三星浏览器 minSdk 30，用 035 即可。 */
    private static final int API_LEVEL = 30;

    private DexAssembler() {
    }

    /**
     * 把 dex 反汇编成 smali 目录树。
     *
     * @param dex    输入的 dex 文件
     * @param outDir 输出目录（smali 根，下面按包名建子目录）
     */
    public static void disassemble(File dex, File outDir) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建目录: " + outDir);
        }
        DexBackedDexFile df = DexFileFactory.loadDexFile(dex, Opcodes.forApi(API_LEVEL));
        BaksmaliOptions opts = new BaksmaliOptions();
        opts.apiLevel = API_LEVEL;
        Baksmali.disassembleDexFile(df, outDir,
                Math.max(1, Runtime.getRuntime().availableProcessors()), opts);
    }

    /**
     * 把一个 smali 目录树汇编成 dex。
     *
     * <p>目录里可以包含**新增**的类（补丁注入的逻辑），汇编器会一并编进去 ——
     * 这是「注入任意逻辑」能力的基础。
     *
     * @param smaliDir 输入 smali 根目录
     * @param outDex   输出 dex
     */
    public static void assemble(File smaliDir, File outDex) throws IOException {
        SmaliOptions opts = new SmaliOptions();
        opts.apiLevel = API_LEVEL;
        opts.outputDexFile = outDex.getAbsolutePath();
        opts.jobs = Math.max(1, Runtime.getRuntime().availableProcessors());
        opts.verboseErrors = true;
        List<String> dirs = new ArrayList<String>();
        dirs.add(smaliDir.getAbsolutePath());
        boolean ok;
        try {
            ok = Smali.assemble(opts, dirs);
        } catch (Exception e) {
            throw new IOException("汇编异常: " + e.getMessage(), e);
        }
        if (!ok || !outDex.isFile()) {
            throw new IOException("汇编失败（smali 语法错误或输出未生成）");
        }
    }

    /**
     * 读取 dex 里的类列表（用于判断目标类是否存在、版本是否匹配）。
     */
    public static List<String> listClasses(File dex) throws IOException {
        List<String> out = new ArrayList<String>();
        DexBackedDexFile df = DexFileFactory.loadDexFile(dex, Opcodes.forApi(API_LEVEL));
        for (ClassDef cd : df.getClasses()) {
            out.add(cd.getType());
        }
        return out;
    }
}
