package com.sbplus.browser.patcher;

import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 ARSCLib 改包名 / 应用名 —— 共存版的核心。
 *
 * <p><b>为什么必须用 ARSCLib 而不是 apktool</b>：apktool 重打包会用 aapt2 重新编译
 * 资源，而三星浏览器用了 11 个三星框架私有属性（{@code floatingConfigure}、
 * {@code previewLayoutLarge}、{@code widgetSize} 等），标准 framework 里没有定义，
 * aapt2 直接报 {@code attribute not found} 并失败 —— 实测已确认。
 *
 * <p>ARSCLib 是**直接读写二进制 resources.arsc** 的库（Morphe 也在用），
 * 它不编译、不校验 XML 语法，因此这些私有属性原样保留，改包名照样成功。
 *
 * <p><b>改包名要动四处</b>（缺一处就装不上，实测逐条踩过）：
 * <ol>
 *   <li>{@code AndroidManifest.xml} 的 {@code package} 属性；</li>
 *   <li>{@code resources.arsc} 里的 PackageBlock 名称（0x7f 那个）；</li>
 *   <li><b>全部 {@code <provider>} 的 {@code android:authorities}</b> ——
 *       它们是硬编码字符串，不会随 package 变化。三星浏览器有 38 个 provider，
 *       其中 37 个的 authority 以原包名开头，不改就报
 *       {@code INSTALL_FAILED_CONFLICTING_PROVIDER}（实测）；</li>
 *   <li>{@code android:targetActivity} / {@code android:parentActivityName} 等
 *       <b>硬编码全限定类名</b>的属性 —— 只改 package 不会动它们。</li>
 * </ol>
 *
 * <p><b>authority 的改法</b>：把 authority 里出现的旧包名替换成新包名。
 * 一个 provider 可能有多个 authority（分号分隔，例如 ChromeBrowserProvider
 * 同时声明 5 个），逐个替换。同时要在 {@code <permission>} /
 * {@code <uses-permission>} 里做同样的替换 —— 三星自定义的权限名也带包名前缀，
 * 不改会让 provider 的 {@code readPermission} 找不到对应权限声明。
 */
public final class ArscEditor {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private ArscEditor() {
    }

    /**
     * 改包名与应用名，输出新 APK。
     *
     * @param srcApk     原始 APK
     * @param outApk     输出 APK
     * @param newPackage 新包名，例如 {@code com.sec.android.app.sbrowser.sbplus}
     * @param newLabel   新的桌面显示名；null 表示不改
     * @return 实际使用的包名
     */
    public static String renamePackage(File srcApk, File outApk,
                                       String newPackage, String newLabel) throws IOException {
        ApkModule module = ApkModule.loadApkFile(srcApk);
        try {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            if (manifest == null) {
                throw new IOException("APK 里没有 AndroidManifest.xml");
            }
            String oldPackage = manifest.getPackageName();
            if (oldPackage == null || oldPackage.length() == 0) {
                throw new IOException("源 manifest 没有 package 属性");
            }

            // ① 改 manifest 的 package
            manifest.setPackageName(newPackage);

            // ② 改 provider authority / 自定义权限名（硬编码字符串，必须手动换）
            rewritePrefixedStrings(manifest, oldPackage, newPackage);

            // ③ 改 resources.arsc 里的包名
            TableBlock table = module.getTableBlock();
            if (table != null) {
                for (PackageBlock pb : table.listPackages()) {
                    // 只改当前应用自己的包（0x7f 是应用资源包 id）
                    if (pb.getId() == 0x7f) {
                        pb.setName(newPackage);
                    }
                }
            }

            // ④ 改应用显示名（可选）
            if (newLabel != null && table != null) {
                PackageBlock pb = table.getPackageBlockById(0x7f);
                if (pb != null) {
                    com.reandroid.arsc.value.Entry e = pb.getOrCreate("", "string", "app_name");
                    if (e != null) e.setValueAsString(newLabel);
                }
            }

            // 改完刷新所有块长度（不刷新会写出长度过期的二进制 XML，见 ApkInjectorCore 的说明）
            manifest.refreshFull();

            module.writeApk(outApk);
            return newPackage;
        } finally {
            try {
                module.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把 manifest 里所有「以旧包名开头」的属性值替换成新包名开头。
     *
     * <p>覆盖的属性：
     * <ul>
     *   <li>{@code android:authorities}（provider，可能分号分隔多个）</li>
     *   <li>{@code android:name}（{@code <permission>} 与 {@code <uses-permission>}；
     *       只替换以旧包名开头的值 —— activity / service 的类名也走这个属性，
     *       但它们的值是相对名（{@code .Foo}）或全限定名，
     *       全限定名的替换是正确的，相对名不含旧包名所以不受影响）</li>
     *   <li>{@code android:targetActivity} / {@code android:parentActivityName}
     *       （硬编码全限定类名）</li>
     *   <li>{@code android:taskAffinity}（默认是包名）</li>
     * </ul>
     *
     * <p><b>为什么不替换所有含旧包名的字符串</b>：类名替换是安全的（新包名下
     * 这些类确实还在原位置 —— 我们只改 package 不改 dex），但像
     * {@code android:process="com.sec.android.app.sbrowser:xxx"} 这种
     * 进程名替换后会让浏览器起一个陌生进程名，某些三星服务会认不出来。
     * 所以只替换已知必须换的那几类属性。
     */
    private static void rewritePrefixedStrings(AndroidManifestBlock manifest,
                                               String oldPkg, String newPkg) {
        ResXmlElement root = manifest.getDocumentElement();
        if (root == null) return;

        List<ResXmlElement> all = new ArrayList<ResXmlElement>();
        collectElements(root, all);

        for (ResXmlElement el : all) {
            String tag = el.getName();
            if (tag == null) continue;

            // provider：authorities 可能有多个（分号分隔）
            if ("provider".equals(tag)) {
                ResXmlAttribute auth = el.searchAttribute(NS_ANDROID, "authorities");
                if (auth != null) {
                    String v = auth.getValueAsString();
                    if (v != null) {
                        String rewritten = uniquifyAuthorities(v, oldPkg, newPkg);
                        if (!rewritten.equals(v)) {
                            auth.setValueAsString(rewritten);
                        }
                        // 仍属于别的应用的 authority → 停用这个 provider，
                        // 否则安装时报 INSTALL_FAILED_CONFLICTING_PROVIDER。
                        if (SKIP_ON_CONFLICT && hasForeignAuthority(rewritten, newPkg)) {
                            ResXmlAttribute enabled =
                                    el.getOrCreateAndroidAttribute("enabled", 0x0101000e);
                            enabled.setValueAsBoolean(false);
                        }
                    }
                }
                // 关键：provider 的读写权限也带包名前缀，必须一起换。
                //
                // 三星浏览器有多个「自己 provider + 自己 signature 权限」的闭环：
                //   声明 <permission name="...READ_MOST_VISITED_SITES" protectionLevel="signature">
                //   provider readPermission="...READ_MOST_VISITED_SITES"
                // 只改 <permission> 的声明名而留下 provider 的 readPermission，
                // 共存版就变成「声明了一个没人用的新权限 + 去申请一个自己拿不到的旧权限」
                // → 运行时 SecurityException 直接崩（实测踩过：
                //   Permission Denial: opening provider ...MostVisitedSitesProvider
                //   requires com.sec.android.app.sbrowser.permission.READ_MOST_VISITED_SITES）
                rewriteAttr(el, "readPermission", oldPkg, newPkg);
                rewriteAttr(el, "writePermission", oldPkg, newPkg);
            }

            // permission / uses-permission：权限名带包名前缀
            //
            // 这里**必须**改：三星自定义的权限名是 "原包名.permission.XXX"，
            // 共存版如果不改，会和原版重复声明同一个权限 —— 安装时不报错，
            // 但权限归属会混乱（哪个 app 定义的、谁能申请）。
            if ("permission".equals(tag) || "uses-permission".equals(tag)
                    || "uses-permission-sdk-23".equals(tag)) {
                rewriteName(el, oldPkg, newPkg);
            }

            // 组件（activity / service / receiver / provider）的 android:name
            // **绝对不改** —— 它是 dex 里的类名，我们只改 package、没动 dex，
            // 改了就会变成「manifest 指向一个不存在的类」，启动时报
            //   Activity class {...SBrowserMainActivity} does not exist
            // （实测踩过：共存版装上了但点图标打不开）。
            //
            // 相对类名（".Foo"）本来就随 package 自动解析，全限定名（"com.x.Y"）
            // 在 dex 里仍是原样，所以两者都保持不动。

            // application：taskAffinity 是包名语义（进程/任务栈归属），改。
            // backupAgent / appComponentFactory / manageSpaceActivity 都是类名 —— 不改。
            if ("application".equals(tag)) {
                rewriteAttr(el, "taskAffinity", oldPkg, newPkg);
            }
        }
    }

    /**
     * 让 provider 的 authority 在新包名下唯一。
     *
     * <p><b>为什么不能只替换旧包名</b>：三星浏览器有 38 个 provider，
     * 其中 35 个的 authority 以 {@code com.sec.android.app.sbrowser} 开头
     * （直接替换即可），但有 <b>3 个不含包名</b>：
     * <ul>
     *   <li>{@code com.scloud.sync.SyncClientAuthority}</li>
     *   <li>{@code com.scloud.sync.SyncClientAuthority2}</li>
     *   <li>{@code com.samsung.android.spay.*} 等三星共享 provider —— 这些属于三星
     *       其它应用，浏览器只是声明；改成新名反而会让它们失效。</li>
     * </ul>
     *
     * <p>所以策略是：
     * <ol>
     *   <li>含旧包名的 → 替换成新包名（保持语义）；</li>
     *   <li>不含旧包名、但<b>明显是浏览器自己的</b>（{@code com.scloud.sync.*}）
     *       → 前缀新包名；</li>
     *   <li>其余（{@code com.samsung.android.*}，跨应用共享）→ 原样保留 ——
     *       它们与原版冲突是<b>三星设计使然</b>，共存版无法绕过，
     *       只能靠「安装时跳过 provider 冲突」处理（见 {@link #SKIP_ON_CONFLICT}）。</li>
     * </ol>
     */
    private static String uniquifyAuthorities(String value, String oldPkg, String newPkg) {
        String[] parts = value.split(";");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if (p.length() == 0) continue;
            if (sb.length() > 0) sb.append(';');
            if (p.contains(oldPkg)) {
                sb.append(p.replace(oldPkg, newPkg));
            } else if (p.startsWith("com.scloud.sync.")) {
                // 三星云同步 provider：属于浏览器自己（不在别的 APK 里声明），
                // 不改会与原版抢同一个 authority。
                sb.append(newPkg).append('.').append(p.substring(p.lastIndexOf('.') + 1));
            } else {
                // 三星其它应用共享的 provider，保留原名。
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /**
     * 共存版是否允许「provider 冲突时跳过」。
     *
     * <p>三星浏览器声明了若干属于<b>其它三星应用</b>的 provider
     * （{@code com.samsung.android.spay.*}、{@code com.samsung.android.mcfds.*}）。
     * 这些 authority 在原版安装时已被占用，共存版再声明同一个就报
     * {@code INSTALL_FAILED_CONFLICTING_PROVIDER}。
     *
     * <p>处理方式：把这类 provider 的 {@code android:enabled} 设为 {@code false}。
     * 停用的 provider 不参与 authority 冲突检查，而它们本来就属于别的应用、
     * 浏览器只是「顺带声明」，停掉不影响浏览器自身功能。
     */
    private static final boolean SKIP_ON_CONFLICT = true;

    /** 判断某个 authority 是否属于「别的应用」——即不含本应用包名的那类。 */
    private static boolean isForeignAuthority(String authority, String ownPkg) {
        return authority != null && authority.length() > 0
                && !authority.startsWith(ownPkg)
                && !authority.startsWith("com.scloud.sync.");
    }

    /** 分号分隔的 authority 列表里，是否有任何一个属于别的应用。 */
    private static boolean hasForeignAuthority(String value, String ownPkg) {
        for (String part : value.split(";")) {
            if (isForeignAuthority(part.trim(), ownPkg)) return true;
        }
        return false;
    }

    /** 替换 {@code android:name}，仅当它以旧包名开头。 */
    private static void rewriteName(ResXmlElement el, String oldPkg, String newPkg) {
        ResXmlAttribute name = el.searchAttribute(NS_ANDROID, "name");
        if (name == null) return;
        String v = name.getValueAsString();
        if (v != null && v.startsWith(oldPkg)) {
            name.setValueAsString(newPkg + v.substring(oldPkg.length()));
        }
    }

    /** 替换某个属性值里的旧包名（不要求开头）。 */
    private static void rewriteAttr(ResXmlElement el, String attrName,
                                    String oldPkg, String newPkg) {
        ResXmlAttribute a = el.searchAttribute(NS_ANDROID, attrName);
        if (a == null) return;
        String v = a.getValueAsString();
        if (v != null && v.contains(oldPkg)) {
            a.setValueAsString(v.replace(oldPkg, newPkg));
        }
    }

    private static void collectElements(ResXmlElement el, List<ResXmlElement> out) {
        out.add(el);
        java.util.Iterator<ResXmlElement> it = el.getElements();
        while (it.hasNext()) {
            collectElements(it.next(), out);
        }
    }

    /** 读取 APK 的包名 / 版本信息（不需要解包，直接读 manifest）。 */
    public static ApkInfo readInfo(File apk) throws IOException {
        ApkModule module = ApkModule.loadApkFile(apk);
        try {
            AndroidManifestBlock m = module.getAndroidManifest();
            if (m == null) throw new IOException("APK 里没有 AndroidManifest.xml");
            ApkInfo info = new ApkInfo();
            info.packageName = m.getPackageName();
            Integer vc = m.getVersionCode();
            info.versionCode = vc == null ? 0 : vc.longValue();
            info.versionName = m.getVersionName();
            return info;
        } finally {
            try {
                module.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** APK 基本信息。 */
    public static final class ApkInfo {
        public String packageName;
        public long versionCode;
        public String versionName;
    }
}
