package com.sbplus.browser.patcher.patches;

import com.sbplus.browser.patcher.Patch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桌面版 UA 常开：把浏览器的默认 User-Agent 改成桌面 Chrome。
 *
 * <p>对应 Xposed 版的 {@code hookUaOverride}（简化为「永远桌面 UA」，
 * 不做每站点配置 —— 静态补丁没有运行时 UI 可交互）。
 *
 * <p><b>目标</b>：{@code com/sec/android/app/sbrowser/common/device/DebugSettings}
 * 里的 {@code getCscUserAgent()Ljava/lang/String;} —— 它返回 CSC 配置里的 UA 字符串。
 * 直接替换方法体返回桌面 UA 常量。
 *
 * <p><b>为什么选这个方法</b>：它是 UA 的来源之一，且是纯 getter，替换风险低。
 * 更彻底的做法是改 {@code ContentSettings} 的 UA 设置，但那涉及 native 回调，
 * 静态补丁不好做。
 *
 * <p><b>注意</b>：桌面 UA 会让部分站点跳转到 PC 版页面。这是「增强」也是「副作用」，
 * 所以默认不勾选。
 */
public final class UaOverridePatch implements Patch {

    private static final String CLS =
            "com/sec/android/app/sbrowser/common/device/DebugSettings";
    private static final String METHOD = "getCscUserAgent()Ljava/lang/String;";
    private static final String MARK = "# SBPlus: ua-override";

    /** 桌面 Chrome UA（Windows 版，兼容性最好）。 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    @Override
    public String id() {
        return "ua-override";
    }

    @Override
    public String name() {
        return "桌面版 UA";
    }

    @Override
    public String description() {
        return "把默认 User-Agent 改成桌面 Chrome，让网站返回 PC 版页面"
                + "（副作用：部分站点会跳 PC 版布局）";
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
        return false;
    }

    @Override
    public boolean apply(PatchContext ctx) throws Exception {
        File f = ctx.findSmali(CLS);
        if (f == null) {
            ctx.log.log("ua-override: 找不到 " + CLS);
            return false;
        }
        String src = read(f);
        if (src.contains(MARK)) return false;

        // 方法可能带 public / public final 等修饰，用正则宽松匹配
        Pattern p = Pattern.compile(
                "(\\.method\\s+(?:public\\s+|private\\s+|protected\\s+)?(?:static\\s+)?"
                        + Pattern.quote("getCscUserAgent") + "\\(\\)Ljava/lang/String;)");
        Matcher m = p.matcher(src);
        if (!m.find()) {
            ctx.log.log("ua-override: 方法不存在（可能已改名）");
            return false;
        }
        int start = m.start();
        int end = src.indexOf(".end method", start);
        if (end < 0) return false;

        StringBuilder sb = new StringBuilder();
        sb.append(m.group(1)).append('\n');
        sb.append("    .locals 1\n\n");
        sb.append("    ").append(MARK).append('\n');
        sb.append("    const-string v0, \"").append(DESKTOP_UA).append("\"\n\n");
        sb.append("    return-object v0\n");
        sb.append(".end method");

        String out = src.substring(0, start) + sb + src.substring(end + ".end method".length());
        write(f, out);
        ctx.log.log("ua-override: getCscUserAgent 已改为桌面 UA");
        return true;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(Charset.forName("UTF-8")));
    }
}
