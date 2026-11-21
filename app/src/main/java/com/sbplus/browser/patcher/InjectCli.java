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
 *
 * <p>2026-11-22：共存版（改包名）已移除，不再支持第 5 个"新包名"参数。
 */
public final class InjectCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法: InjectCli <source.apk> <runtime.dex> <libpine.so> <out.apk>");
            System.exit(2);
        }
        File src = new File(args[0]);
        File dex = new File(args[1]);
        File so = new File(args[2]);
        File out = new File(args[3]);

        long t0 = System.currentTimeMillis();

        System.out.println("[1/3] 读 manifest…");
        byte[] raw = ApkInjectorCore.readEntry(src, "AndroidManifest.xml");
        if (raw == null) {
            System.err.println("FAIL: 源 APK 里没有 AndroidManifest.xml");
            System.exit(1);
        }
        System.out.println("      原 manifest " + raw.length + " 字节");

        System.out.println("[2/3] 改 manifest（插入 provider）…");
        byte[] patched = ApkInjectorCore.patchManifest(raw);
        System.out.println("      新 manifest " + patched.length + " 字节 ("
                + (patched.length - raw.length) + ")");

        System.out.println("[3/3] 打包（保留条目顺序 + 对齐）…");
        String dexName = ApkInjectorCore.nextDexName(src);
        System.out.println("      新 dex 条目名: " + dexName);
        ApkInjectorCore.repack(src, out, patched, dexName, dex,
                RuntimeInjector.SO_ENTRY, so);

        long ms = System.currentTimeMillis() - t0;
        System.out.println("完成，用时 " + (ms / 1000) + "s");
        System.out.println("      产物: " + out.getAbsolutePath()
                + "  " + (out.length() / 1024 / 1024) + " MB");
    }
}
