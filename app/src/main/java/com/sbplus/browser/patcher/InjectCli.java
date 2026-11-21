package com.sbplus.browser.patcher;

import java.io.File;

/**
 * PC 端注入验证工具（不进 APK，只在开发时跑）。
 *
 * <p><b>为什么要有它</b>：一次完整的真机验证要传 300MB + 安装 + 启动，几分钟起步。
 * 而注入逻辑里最容易出错的地方（改 manifest、拼 ZIP、对齐）全是纯字节操作，
 * 完全可以在电脑上先跑通、先验证产出的包结构合法，再推到手机上。
 *
 * <p>它跑的是 {@link ApkInjectorCore} —— 与设备端**同一份代码**，
 * 所以这里验证通过就等于设备端逻辑通过（差别只在 assets 取文件那一步）。
 *
 * <p>用法：
 * <pre>
 *   java -cp "classes;ARSCLib.jar" com.sbplus.browser.patcher.InjectCli \
 *        source.apk runtime.dex libpine.so out.apk
 * </pre>
 */
public final class InjectCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法: InjectCli <source.apk> <runtime.dex> <libpine.so> <out.apk>"
                    + " [新包名]");
            System.err.println("  给出第 5 个参数时走共存版（改包名）；否则原包名。");
            System.exit(2);
        }
        File src = new File(args[0]);
        File dex = new File(args[1]);
        File so = new File(args[2]);
        File out = new File(args[3]);
        String newPkg = args.length >= 5 ? args[4] : null;

        long t0 = System.currentTimeMillis();

        // 共存版：先改包名（ARSCLib 直接改二进制 arsc，不经过 aapt2）
        if (newPkg != null) {
            System.out.println("[0/4] 改包名 → " + newPkg + " …");
            File renamed = new File(out.getParentFile(), "renamed.apk");
            ArscEditor.renamePackage(src, renamed, newPkg, "SBPlus 浏览器");
            src = renamed;
            System.out.println("      完成 " + (renamed.length() / 1024 / 1024) + " MB");
        }

        System.out.println("[1/4] 读 manifest…");
        byte[] raw = ApkInjectorCore.readEntry(src, "AndroidManifest.xml");
        if (raw == null) {
            System.err.println("FAIL: 源 APK 里没有 AndroidManifest.xml");
            System.exit(1);
        }
        System.out.println("      原 manifest " + raw.length + " 字节");

        System.out.println("[2/4] 改 manifest（插入 provider）…");
        byte[] patched = ApkInjectorCore.patchManifest(raw);
        System.out.println("      新 manifest " + patched.length + " 字节 ("
                + (patched.length - raw.length) + ")");

        System.out.println("[3/4] 打包（保留条目顺序 + 对齐）…");
        String dexName = ApkInjectorCore.nextDexName(src);
        System.out.println("      新 dex 条目名: " + dexName);
        ApkInjectorCore.repack(src, out, patched, dexName, dex,
                RuntimeInjector.SO_ENTRY, so);

        long ms = System.currentTimeMillis() - t0;
        System.out.println("[4/4] 完成，用时 " + (ms / 1000) + "s");
        System.out.println("      产物: " + out.getAbsolutePath()
                + "  " + (out.length() / 1024 / 1024) + " MB");
    }
}
