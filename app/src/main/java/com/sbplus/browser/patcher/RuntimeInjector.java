package com.sbplus.browser.patcher;

import java.io.File;
import java.io.IOException;

/**
 * 设备端的运行时注入器。
 *
 * <p>只做三件与 Android 环境相关的事：从 assets 取运行时 dex 与 so、
 * 把结果写到指定路径、提供日志。真正的注入逻辑在 {@link ApkInjectorCore}
 * —— 它是纯 Java（不碰 Android API），PC 上也能跑，两边是同一份代码。
 */
final class RuntimeInjector {

    /** 运行时 dex 在 assets 里的名字。 */
    static final String ASSET_RUNTIME_DEX = "sbplus-runtime.dex";
    /** 运行时 native 库在 assets 里的名字（单 ABI，arm64）。 */
    static final String ASSET_PINE_SO = "libpine.so";

    /** so 在 APK 内的路径。 */
    static final String SO_ENTRY = "lib/arm64-v8a/libpine.so";

    private final android.content.Context context;

    RuntimeInjector(android.content.Context context) {
        this.context = context;
    }

    /**
     * 执行注入：读入 {@code baseApk}，产出 {@code outApk}（未签名）。
     *
     * @return 注入摘要（给日志用）
     */
    String inject(File baseApk, File outApk) throws IOException {
        File work = new File(context.getCacheDir(), "sbplus-inject");
        if (!work.isDirectory() && !work.mkdirs()) {
            throw new IOException("无法创建 " + work);
        }
        File runtimeDex = new File(work, ASSET_RUNTIME_DEX);
        copyAsset(ASSET_RUNTIME_DEX, runtimeDex);
        File pineSo = new File(work, ASSET_PINE_SO);
        copyAsset(ASSET_PINE_SO, pineSo);

        String dexName = ApkInjectorCore.nextDexName(baseApk);
        byte[] rawManifest = ApkInjectorCore.readEntry(baseApk, "AndroidManifest.xml");
        if (rawManifest == null) {
            throw new IOException("APK 里没有 AndroidManifest.xml");
        }
        byte[] newManifest = ApkInjectorCore.patchManifest(rawManifest);

        ApkInjectorCore.repack(baseApk, outApk, newManifest, dexName, runtimeDex,
                SO_ENTRY, pineSo);

        return "注入 " + dexName + " (" + (runtimeDex.length() / 1024) + "KB) + "
                + SO_ENTRY + " (" + (pineSo.length() / 1024) + "KB) + provider";
    }

    /**
     * 把 assets 里的文件复制到缓存目录。
     *
     * <p><b>2026-11-20 修复「改了代码但打出来的包还是旧行为」</b>：
     *
     * <p>原实现是 {@code if (out.isFile() && out.length() > 0) return;} —— 只要缓存
     * 文件存在就跳过复制。这看着像合理的缓存，实际是个陷阱：
     * <ul>
     *   <li>缓存目录 {@code getCacheDir()} 在<b>模块 app 更新时不会被清</b>；</li>
     *   <li>于是改了 MainHook、重新生成了 assets 里的 sbplus-runtime.dex，
     *       再打开补丁页打包，用的仍是<b>上一次</b>缓存的旧 dex；</li>
     *   <li>症状：明明修了 bug、明明重新编译安装过模块，打出来的包行为没变
     *       （实测踩过：闪退修复"没生效"，其实是旧 dex 被打进去了）。</li>
     * </ul>
     *
     * <p>现在改成<b>每次都比对内容</b>：长度不同直接重写；长度相同则比 CRC。
     * assets 里的文件只有 1~1.4MB，CRC 校验是毫秒级，换来的是「所见即所得」。
     */
    private void copyAsset(String assetName, File out) throws IOException {
        java.io.InputStream in = context.getAssets().open(assetName);
        try {
            // 先算 assets 里那份的 CRC 与长度，与缓存文件比对。
            java.util.zip.CRC32 srcCrc = new java.util.zip.CRC32();
            long srcLen = 0;
            byte[] buf = new byte[128 * 1024];
            int n;
            java.io.ByteArrayOutputStream keep = null;
            // 1MB 级别直接读进内存，省一次 IO（比"先算 CRC 再重新打开流"更简单）
            keep = new java.io.ByteArrayOutputStream(2 * 1024 * 1024);
            while ((n = in.read(buf)) > 0) {
                keep.write(buf, 0, n);
                srcCrc.update(buf, 0, n);
                srcLen += n;
            }
            byte[] data = keep.toByteArray();

            if (out.isFile() && out.length() == srcLen) {
                java.util.zip.CRC32 dstCrc = new java.util.zip.CRC32();
                java.io.FileInputStream fis = new java.io.FileInputStream(out);
                try {
                    int m;
                    while ((m = fis.read(buf)) > 0) dstCrc.update(buf, 0, m);
                } finally {
                    try {
                        fis.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (dstCrc.getValue() == srcCrc.getValue()) {
                    return;   // 内容一致，复用缓存
                }
            }

            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            try {
                os.write(data);
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
