package com.sbplus.browser.patcher;

import java.io.File;
import java.io.IOException;

/**
 * 补丁引擎主流程（完整功能注入版）。
 *
 * <p><b>为什么是「注入运行时」而不是「逐条改 smali」</b>
 *
 * <p>早先的实现是：反汇编 5 个 dex → 在 smali 里把 {@code shouldShowUpdateCard}
 * 之类的方法体替换掉 → 重汇编。这条路只能做「改返回值」这类手术，
 * 做不了 SBPlus 的真正功能，因为那些功能需要：
 * <ul>
 *   <li>在浏览器进程里<b>运行一整段自己的代码</b>（下载接管、媒体嗅探、油猴引擎、
 *       书签整理、主题着色……几十个类、几万行）；</li>
 *   <li>在运行时<b>拦截方法调用</b>（hook），而不是改静态指令；</li>
 *   <li>有<b>自己的设置界面</b>与持久化存储。</li>
 * </ul>
 *
 * <p>所以现在改成 Morphe / ReVanced 那套：<b>把模块的运行时整包注入 APK</b>。
 * <ol>
 *   <li>模块编译产物里的 hook 运行时（MainHook 22 个功能 + Pine + libxposed API）
 *       单独打成一个 dex，塞进浏览器 APK；</li>
 *   <li>{@code libpine.so} 塞进 {@code lib/arm64-v8a/}；</li>
 *   <li>manifest 里加一个 {@code ContentProvider}，它在
 *       {@code Application.onCreate} 之前触发，负责初始化 Pine 并构造 MainModule，
 *       把 libxposed 的生命周期走一遍 —— 之后 MainHook 的 22 个功能全部生效，
 *       和 LSPosed 注入的效果一致。</li>
 * </ol>
 *
 * <p><b>为什么这条路不需要反汇编浏览器 dex</b>：注入点在 manifest（二进制 XML，
 * ARSCLib 能直接改），dex 只是「追加一个新文件」，不需要理解原有字节码。
 * 所以整条流程从「几分钟」降到「几十秒」，也不会因为改错 smali 让浏览器崩溃。
 *
 * <p><b>代价</b>：注入的运行时与原模块是同一份代码，所以功能集合完全一致，
 * 但<b>没有</b> LSPosed 的「模块作用域 / 热重载 / 远程 prefs」——
 * 这些由 {@code com.sbplus.browser.rt.PineXposedImpl} 用本地实现替代。
 */
public final class PatchEngine {

    private final PatchConfig config;

    public PatchEngine(PatchConfig config) {
        this.config = config;
    }

    /** 执行完整注入流程。 */
    public void run() throws Exception {
        long t0 = System.currentTimeMillis();
        File work = config.workDir;
        if (!work.exists() && !work.mkdirs()) {
            throw new IOException("无法创建工目录: " + work);
        }

        Patch.Logger log = new Patch.Logger() {
            @Override
            public void log(String msg) {
                config.report("log", msg, -1);
            }
        };

        // ① 读 APK 信息
        config.report("info", "读取 APK 信息…", 5);
        ArscEditor.ApkInfo info = ArscEditor.readInfo(config.sourceApk);
        log.log("源包名: " + info.packageName + " / " + info.versionName
                + " (" + info.versionCode + ")");

        // ② 版本校验：只允许打已验证版本，避免产出改坏的包
        BrowserVersions.Version matched =
                BrowserVersions.match(info.packageName, info.versionName);
        if (!BrowserVersions.isSupportedPackage(info.packageName)) {
            throw new IOException("这不是三星浏览器（包名 " + info.packageName
                    + "），无法注入");
        }
        if (matched == null) {
            log.log("⚠ 未验证版本 " + info.versionName
                    + "，仍尝试注入（功能可能部分失效）");
        } else {
            log.log("✓ 已验证版本 " + info.versionName);
        }

        // ③ 只支持原包名：直接以源 APK 为底包。
        //    （共存版 2026-11-22 移除：能装但启动崩在 dex 硬编码的
        //      MostVisitedSitesProvider authority，manifest 改不动。）
        File base = config.sourceApk;

        // ④ 注入运行时（dex + so + provider）
        config.report("inject", "注入 SBPlus 运行时…", 45);
        File staged = new File(work, "staged.apk");
        RuntimeInjector injector = new RuntimeInjector(config.context);
        String summary = injector.inject(base, staged);
        log.log(summary);

        // ⑤ 签名（输出到独立文件，避免大包 in-place 签名把整个文件读进内存）
        config.report("sign", "签名…", 80);
        File outApk = config.outputApk;
        if (outApk.exists() && !outApk.delete()) {
            throw new IOException("无法覆盖输出文件: " + outApk);
        }
        ApkSignerWrapper.sign(staged, outApk, config.keystore, config.keystorePass,
                config.keyAlias, config.keyPass);

        long ms = System.currentTimeMillis() - t0;
        config.report("done", "完成，用时 " + (ms / 1000) + "s → " + outApk.getName(), 100);
    }
}