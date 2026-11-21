package com.sbplus.browser.patcher;

import java.io.File;

/**
 * 一个补丁的定义。
 *
 * <p>补丁 = 「对 smali 源做一次确定的修改」。实现方拿到解包后的工作目录，
 * 直接改文件即可，不需要理解 dex 格式 —— 汇编由 {@link DexAssembler} 统一负责。
 *
 * <p><b>为什么在 smali 层做而不是二进制 patch</b>：dex 的指令是变长的，
 * 改一个方法体几乎必然影响后续所有偏移量；smali 层改完再整体汇编，
 * 偏移由汇编器重新计算，安全得多。Morphe 也是这个思路。
 *
 * <p><b>幂等性要求</b>：{@link #apply} 必须可重复调用而不叠加修改
 * （用特征串判断是否已经打过），否则重复打补丁会把代码改坏。
 */
public interface Patch {

    /** 补丁 id，用于 UI 勾选与日志。 */
    String id();

    /** 显示名。 */
    String name();

    /** 一句话说明（给用户看这个补丁干什么）。 */
    String description();

    /** 适用的目标包名；null 表示不限。 */
    String targetPackage();

    /** 适用的最低浏览器 versionCode；0 表示不限。 */
    long minVersionCode();

    /** 是否默认勾选。 */
    boolean defaultEnabled();

    /**
     * 执行补丁。
     *
     * @param ctx 解包后的工作环境
     * @return true 表示确实做了修改；false 表示目标不存在（版本不匹配）或已打过
     */
    boolean apply(PatchContext ctx) throws Exception;

    /** 补丁执行环境。 */
    final class PatchContext {
        /** 解包根目录（含 AndroidManifest.xml、smali/、smali_classes2/、res/ 等）。 */
        public final File root;
        /** 原始 APK 的 versionCode / versionName / packageName。 */
        public final long versionCode;
        public final String versionName;
        public final String packageName;
        /** 日志出口。 */
        public final Logger log;

        public PatchContext(File root, long versionCode, String versionName,
                            String packageName, Logger log) {
            this.root = root;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.packageName = packageName;
            this.log = log;
        }

        /** 所有 smali 根目录（smali、smali_classes2 … smali_classesN）。 */
        public File[] smaliRoots() {
            java.util.List<File> out = new java.util.ArrayList<File>();
            File[] all = root.listFiles();
            if (all == null) return new File[0];
            for (File f : all) {
                if (!f.isDirectory()) continue;
                String n = f.getName();
                if (n.equals("smali") || n.startsWith("smali_classes")) out.add(f);
            }
            return out.toArray(new File[0]);
        }

        /**
         * 按「类描述符」找 smali 文件，例如
         * {@code com/sec/android/app/sbrowser/settings/SettingsActivity}。
         */
        public File findSmali(String classPath) {
            for (File r : smaliRoots()) {
                File f = new File(r, classPath + ".smali");
                if (f.isFile()) return f;
            }
            return null;
        }
    }

    /** 简易日志接口（由引擎实现，转发到 UI 与 logcat）。 */
    interface Logger {
        void log(String msg);
    }
}
