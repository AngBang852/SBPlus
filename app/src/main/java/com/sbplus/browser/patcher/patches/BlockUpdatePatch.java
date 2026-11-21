package com.sbplus.browser.patcher.patches;

import com.sbplus.browser.patcher.Patch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * 屏蔽「设置页更新卡片」与更新小红点。
 *
 * <p>对应 Xposed 版的 {@code hookBlockUpdate}。
 *
 * <p><b>目标</b>：{@code com/sec/android/app/sbrowser/settings/utils/SettingsUtils}
 * <ul>
 *   <li>{@code shouldShowUpdateCard()Z} —— 设置页顶部的更新卡片</li>
 *   <li>{@code hasNewUpdate()Z} —— 更新小红点</li>
 * </ul>
 *
 * <p><b>做法</b>：把方法体整体替换成 {@code const/4 v0, 0x0; return v0}。
 * 比「在方法开头插 goto」更彻底 —— 不会留下死代码，也不会因为寄存器数量
 * 对不上而出错。
 *
 * <p><b>为什么这个补丁稳定</b>：只依赖两个方法名，不依赖方法体内容。
 * 三星改实现不影响；只有改方法名才会失效（历史上没改过）。
 */
public final class BlockUpdatePatch implements Patch {

    private static final String CLS =
            "com/sec/android/app/sbrowser/settings/utils/SettingsUtils";
    /** 幂等标记：打过补丁的文件里会有这个注释。 */
    private static final String MARK = "# SBPlus: block-update";

    @Override
    public String id() {
        return "block-update";
    }

    @Override
    public String name() {
        return "屏蔽更新提示";
    }

    @Override
    public String description() {
        return "移除设置页的更新卡片与更新小红点，避免被引导去应用商店升级";
    }

    @Override
    public String targetPackage() {
        return "com.sec.android.app.sbrowser";
    }

    @Override
    public long minVersionCode() {
        return 0;
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public boolean apply(PatchContext ctx) throws Exception {
        File f = ctx.findSmali(CLS);
        if (f == null) {
            ctx.log.log("block-update: 找不到 " + CLS);
            return false;
        }
        String src = read(f);
        if (src.contains(MARK)) {
            return false; // 已打过
        }
        boolean a = replaceMethodBody(src, "shouldShowUpdateCard", MARK);
        boolean b = replaceMethodBody(src, "hasNewUpdate", MARK);
        if (!a && !b) {
            ctx.log.log("block-update: 两个目标方法都不存在");
            return false;
        }
        String out = src;
        if (a) out = replaceMethodBodyRaw(out, "shouldShowUpdateCard", MARK);
        if (b) out = replaceMethodBodyRaw(out, "hasNewUpdate", MARK);
        write(f, out);
        ctx.log.log("block-update: 已改 " + (a ? "shouldShowUpdateCard " : "")
                + (b ? "hasNewUpdate" : ""));
        return true;
    }

    /** 只做「能不能改」的判断，不落盘。 */
    private static boolean replaceMethodBody(String src, String methodName, String mark) {
        String sig = ".method public static " + methodName + "()Z";
        int i = src.indexOf(sig);
        if (i < 0) return false;
        int end = src.indexOf(".end method", i);
        return end > i;
    }

    /** 真正替换方法体。 */
    private static String replaceMethodBodyRaw(String src, String methodName, String mark) {
        String sig = ".method public static " + methodName + "()Z";
        int i = src.indexOf(sig);
        if (i < 0) return src;
        int end = src.indexOf(".end method", i);
        if (end < 0) return src;
        StringBuilder sb = new StringBuilder();
        sb.append(sig).append('\n');
        sb.append("    .locals 1\n\n");
        sb.append("    ").append(mark).append('\n');
        sb.append("    const/4 v0, 0x0\n\n");
        sb.append("    return v0\n");
        sb.append(".end method");
        return src.substring(0, i) + sb + src.substring(end + ".end method".length());
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(Charset.forName("UTF-8")));
    }
}
