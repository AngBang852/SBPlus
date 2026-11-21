package com.sbplus.browser.patcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 免 root 打补丁主入口。
 *
 * <p><b>设计（2026-11-19 按用户要求定稿）</b>：
 * <ul>
 *   <li><b>版本改为手动下拉</b> —— 不再根据 APK 自动判断「已验证/未验证」。
 *       用户先选目标版本，再选 APK；下拉里只列<b>支持打包</b>的版本。</li>
 *   <li><b>版本下拉与「选择 APK」按钮并排</b> —— 两个控件同一行，符合
 *       「先定版本、再选文件」的操作顺序。</li>
 *   <li><b>补丁改为只读子页</b> —— 点「查看将注入的功能」进入子页，
 *       列出全部功能（不可勾选）。打补丁时<b>全部功能一次性注入</b>，
 *       与 LSPosed 版完全一致。</li>
 *   <li><b>输出到下载目录</b> —— 产物落在
 *       {@code /sdcard/Download/SBPlus/}，方便用文件管理器找到、发给别人。</li>
 * </ul>
 */
public class PatchActivity extends Activity {

    private static final int REQ_PICK_APK = 0x5B01;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int C_BG = 0xFF121417;
    private static final int C_CARD = 0xFF1C1F24;
    private static final int C_TEXT = 0xFFE8EAED;
    private static final int C_TEXT2 = 0xFF9AA0A6;
    private static final int C_TEXT3 = 0xFF6B7076;
    private static final int C_BRAND = 0xFF4BA6EE;
    private static final int C_OK = 0xFF4CAF50;
    private static final int C_WARN = 0xFFFFA726;
    private static final int C_ERR = 0xFFE05252;

    /** 输出目录：公共下载目录下的子文件夹。 */
    private static final String OUT_DIR_NAME = "SBPlus";

    private File sourceApk;
    private ArscEditor.ApkInfo sourceInfo;
    /** 用户在下拉里选的目标版本（决定「能否打包」，不再依赖 APK 自动识别）。 */
    private BrowserVersions.Version targetVersion;

    private TextView tvSource;
    private TextView tvVersionHint;
    private TextView tvLog;
    private TextView tvProgress;
    private LinearLayout progressWrap;
    private LinearLayout resultWrap;
    private Button btnRun;
    private Spinner spVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    // ==================== UI ====================

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("免 root 打补丁", 26, C_TEXT, true));
        TextView sub = text("把 SBPlus 运行时注入浏览器 APK，产出可直接安装的完整功能版",
                12, C_TEXT2, false);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        // ① 说明
        LinearLayout intro = card();
        intro.addView(label("这是什么"));
        intro.addView(body("给没有 root / LSPosed 的设备另一条路：把模块的 hook 运行时"
                + "整包注入三星浏览器的原始 APK，重签名后安装。"
                + "注入后的浏览器拥有与 LSPosed 版完全一致的 "
                + FeatureCatalog.count() + " 个功能。全程在本机完成，APK 不会上传。"));
        // 2026-11-22 审查 S1：把"证书是本机生成的"这件事讲清楚 ——
        // 用户换设备/重装模块后证书会变，届时需要用新产物覆盖安装（或卸载重装）。
        TextView signNote = text("签名证书在本机首次使用时生成，每台设备各不相同；"
                + "私钥不随模块包分发，第三方无法伪造同证书的更新。", 11, C_TEXT3, false);
        signNote.setPadding(0, dp(10), 0, 0);
        intro.addView(signNote);
        root.addView(intro);

        // ② 版本 + 选 APK（同一行）
        LinearLayout srcCard = card();
        srcCard.addView(label("目标版本 / 原始 APK"));

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);

        spVersion = new Spinner(this);
        List<String> versionNames = new ArrayList<String>();
        for (BrowserVersions.Version v : BrowserVersions.packable(BrowserVersions.PKG_STABLE)) {
            versionNames.add(v.name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, versionNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVersion.setAdapter(adapter);
        spVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<BrowserVersions.Version> list =
                        BrowserVersions.packable(BrowserVersions.PKG_STABLE);
                targetVersion = position < list.size() ? list.get(position) : null;
                updateVersionHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                targetVersion = null;
                updateVersionHint();
            }
        });
        GradientDrawable spBg = new GradientDrawable();
        spBg.setColor(0xFF14171B);
        spBg.setCornerRadius(dp(10));
        spBg.setStroke(dp(1), 0xFF2E3339);
        spVersion.setBackground(spBg);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0,
                dp(48), 1f);
        pickRow.addView(spVersion, spLp);

        Button pick = secondaryButton("选择 APK");
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        pickLp.leftMargin = dp(10);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        });
        pickRow.addView(pick, pickLp);
        srcCard.addView(pickRow, rowLp);

        tvVersionHint = text("", 11, C_TEXT3, false);
        tvVersionHint.setPadding(0, dp(8), 0, 0);
        srcCard.addView(tvVersionHint);

        tvSource = body("尚未选择 APK");
        tvSource.setPadding(0, dp(10), 0, 0);
        srcCard.addView(tvSource);
        root.addView(srcCard);

        // ③ 功能入口（只读子页）
        LinearLayout patchCard = card();
        patchCard.addView(label("将注入的功能"));
        TextView pv = body("共 " + FeatureCatalog.count()
                + " 项，全部一次性注入，不可单独关闭（与 LSPosed 版一致）。");
        pv.setPadding(0, dp(8), 0, dp(10));
        patchCard.addView(pv);
        Button viewFeatures = secondaryButton("查看全部功能");
        viewFeatures.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PatchActivity.this, PatchFeatureActivity.class));
            }
        });
        patchCard.addView(viewFeatures);
        root.addView(patchCard);

        // ④ 输出位置提示
        LinearLayout outCard = card();
        outCard.addView(label("产物位置"));
        TextView outPath = text(outputDir().getAbsolutePath(), 12, C_BRAND, false);
        outPath.setPadding(0, dp(6), 0, 4);
        outPath.setTextIsSelectable(true);
        outCard.addView(outPath);
        outCard.addView(text("在文件管理器的「下载 / Download」里可以直接找到",
                11, C_TEXT3, false));
        root.addView(outCard);

        // ⑥ 执行
        LinearLayout runCard = card();
        btnRun = primaryButton("开始注入");
        btnRun.setEnabled(false);
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPatch();
            }
        });
        runCard.addView(btnRun);

        progressWrap = new LinearLayout(this);
        progressWrap.setOrientation(LinearLayout.VERTICAL);
        progressWrap.setVisibility(View.GONE);
        progressWrap.setPadding(0, dp(12), 0, 0);
        tvProgress = text("准备中…", 12, C_BRAND, false);
        progressWrap.addView(tvProgress);
        tvLog = text("", 11, C_TEXT3, false);
        tvLog.setPadding(0, dp(6), 0, 0);
        progressWrap.addView(tvLog);
        runCard.addView(progressWrap);
        root.addView(runCard);

        // ⑦ 产物
        resultWrap = new LinearLayout(this);
        resultWrap.setOrientation(LinearLayout.VERTICAL);
        resultWrap.setVisibility(View.GONE);
        root.addView(resultWrap);

        return sv;
    }

    /** 输出目录：公共下载目录下的 SBPlus 子目录。 */
    private File outputDir() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(dl, OUT_DIR_NAME);
    }

    /**
     * 把产物从私有目录导出到公共下载目录。
     *
     * <p><b>为什么不直接生成到 /sdcard/Download</b>：targetSdk 34 起是分区存储，
     * 普通 app 无法用 {@code File} 直接写公共目录 —— 只有通过 MediaStore
     * 或 SAF 才能写入。而 apksig 的 {@code setOutputApk(File)} 需要真实文件路径。
     *
     * <p>所以流程是：先在应用私有目录生成（有完整 File 语义），
     * 完成后用 MediaStore 复制一份到 {@code Download/SBPlus/}，
     * 再把公共目录里的 Uri 路径回给用户。这样既满足「产物在下载目录」，
     * 又不依赖 MANAGE_EXTERNAL_STORAGE（那是需要用户去系统设置里手动开的权限，
     * 为一个导出功能要那个权限不值得）。
     *
     * @return 公共目录里的 Uri；导出失败返回 null（此时仍可用私有目录的路径）
     */
    private Uri exportToDownloads(File src, String displayName) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            // API 26-28：分区存储还没强制，直接复制到公共目录即可。
            try {
                File dir = outputDir();
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                File dst = new File(dir, displayName);
                java.io.FileInputStream in = new java.io.FileInputStream(src);
                try {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                    try {
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        try {
                            out.close();
                        } catch (Throwable ignored) {
                        }
                    }
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
                return Uri.fromFile(dst);
            } catch (Throwable t) {
                return null;
            }
        }

        // API 29+：走 MediaStore。
        //
        // 2026-11-22 审查 L3：insert 成功、写入失败时必须把那条 pending 记录删掉，
        // 否则下载目录里会残留一个 0 字节、对其它应用不可见的隐藏条目。
        final android.content.ContentResolver cr = getContentResolver();
        Uri item = null;
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    "application/vnd.android.package-archive");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + OUT_DIR_NAME);
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            item = cr.insert(collection, values);
            if (item == null) return null;

            java.io.InputStream in = new java.io.FileInputStream(src);
            try {
                java.io.OutputStream out = cr.openOutputStream(item);
                if (out == null) {
                    cr.delete(item, null, null);
                    return null;
                }
                try {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }

            // 清除 pending，让文件对其它 app 可见。
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return item;
        } catch (Throwable t) {
            if (item != null) {
                try {
                    cr.delete(item, null, null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    /** 版本下拉选中后刷新提示。 */
    private void updateVersionHint() {
        if (targetVersion == null) {
            tvVersionHint.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已选 ").append(targetVersion.name)
                .append("（versionCode ").append(targetVersion.versionCode).append("）");
        if (sourceInfo != null) {
            if (targetVersion.name.equals(sourceInfo.versionName)) {
                sb.append("\n✓ 与所选 APK 的版本一致");
                tvVersionHint.setTextColor(C_OK);
            } else {
                sb.append("\n⚠ 与所选 APK 的版本不一致（APK 是 ")
                        .append(sourceInfo.versionName).append("）");
                tvVersionHint.setTextColor(C_WARN);
            }
        } else {
            tvVersionHint.setTextColor(C_TEXT3);
        }
        tvVersionHint.setText(sb.toString());
    }

    // ==================== 交互 ====================

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PICK_APK);
        } catch (Throwable t) {
            toast("无法打开文件选择器: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        tvSource.setText("读取中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File f = copyToPrivate(uri);
                    final ArscEditor.ApkInfo info = ArscEditor.readInfo(f);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            sourceApk = f;
                            sourceInfo = info;
                            applySourceInfo();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tvSource.setText("读取失败: " + t);
                            btnRun.setEnabled(false);
                        }
                    });
                }
            }
        }, "sbpatch-pick").start();
    }

    /** 把读到的 APK 信息刷到 UI。 */
    private void applySourceInfo() {
        tvSource.setText(sourceInfo.packageName + "\n版本 " + sourceInfo.versionName
                + "（" + sourceInfo.versionCode + "）\n"
                + String.format(java.util.Locale.US, "%.1f MB",
                sourceApk.length() / 1024.0 / 1024.0));

        if (!BrowserVersions.isSupportedPackage(sourceInfo.packageName)) {
            tvSource.setTextColor(C_ERR);
            tvSource.append("\n⚠ 这不是三星浏览器，无法注入");
            btnRun.setEnabled(false);
            return;
        }
        tvSource.setTextColor(C_TEXT2);
        btnRun.setEnabled(true);
        updateVersionHint();
    }

    private void startPatch() {
        if (sourceApk == null) {
            toast("先选一个 APK");
            return;
        }
        if (targetVersion == null) {
            toast("先选目标版本");
            return;
        }

        // 先把"正在准备"刷到界面上，再去干重活 —— 否则用户点了按钮会觉得卡住。
        btnRun.setEnabled(false);
        resultWrap.setVisibility(View.GONE);
        progressWrap.setVisibility(View.VISIBLE);
        tvLog.setText("");
        tvProgress.setTextColor(C_BRAND);
        tvProgress.setText("准备中…");

        // 2026-11-20 修复"点了开始注入先卡一会才出现进度"：
        //
        // 原实现把 deleteRecursive(work) 和 ensureKeystore() 放在 startPatch()
        // 的**主线程**里。work 目录里留着上次的 285MB 中间产物（staged.apk 等），
        // 递归删除几百 MB 是纯磁盘 IO，主线程被占住 → 进度条还没画出来就已经卡了几秒。
        // 现在把全部准备工作挪进后台线程，主线程只负责把"准备中…"刷上去就返回。
        final File work = new File(getExternalFilesDir(null), "patch_work");
        final File outDir = new File(getExternalFilesDir(null), "patch_out");
        final String outName = "sbplus-" + sourceInfo.versionName + ".apk";
        final File out = new File(outDir, outName);
        final File src = sourceApk;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // ① 清理上次的工作目录（几十到几百 MB，最耗时的一步）
                    ApkArchive.deleteRecursive(work);
                    if (!work.isDirectory() && !work.mkdirs()) {
                        throw new java.io.IOException("无法创建工作目录: " + work);
                    }
                    if (!outDir.isDirectory() && !outDir.mkdirs()) {
                        throw new java.io.IOException("无法创建输出目录: " + outDir);
                    }

                    // ② 签名身份（首次使用时在本机生成；私钥不随模块 APK 分发）
                    final SelfSignedKeystore.Identity signing = ensureSigning();

                    final PatchConfig cfg = new PatchConfig(
                            PatchActivity.this, src, work, out,
                            signing.keystore,
                            signing.storePass, signing.alias, signing.keyPass);
                    cfg.listener = new PatchConfig.ProgressListener() {
                        @Override
                        public void onProgress(String stage, String detail, int percent) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if ("log".equals(stage)) {
                                        appendLog(detail);
                                    } else {
                                        tvProgress.setText(percent >= 0
                                                ? detail + "  " + percent + "%" : detail);
                                    }
                                }
                            });
                        }
                    };

                    // ③ 真正注入
                    new PatchEngine(cfg).run();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            onDone(out, signing);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            onFail(t);
                        }
                    });
                }
            }
        }, "sbpatch-run").start();
    }

    private void appendLog(String line) {
        String cur = tvLog.getText().toString();
        String[] lines = cur.split("\n");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.length - 6);
        for (int i = start; i < lines.length; i++) {
            if (lines[i].length() > 0) sb.append(lines[i]).append('\n');
        }
        sb.append(line);
        tvLog.setText(sb.toString());
    }

    /**
     * 注入完成。
     *
     * <p><b>2026-11-22 审查 H1 修复</b>：原实现在这里（UI 线程）直接调
     * {@link #exportToDownloads}，把 300MB 级产物同步复制到公共下载目录。
     * 这与 2026-11-20 刚修过的"安装写 300MB 占主线程"是同一类问题 ——
     * 表现是注入完成后界面冻住数秒到十余秒，低端机直接 ANR。
     * 现在只把"导出中"卡片刷上去，复制放后台线程，完成后再回 UI 渲染结果。
     */
    private void onDone(final File apk, final SelfSignedKeystore.Identity signing) {
        btnRun.setEnabled(true);
        tvProgress.setTextColor(C_OK);
        tvProgress.setText("完成");
        resultWrap.setVisibility(View.VISIBLE);
        resultWrap.removeAllViews();

        LinearLayout wait = card();
        wait.addView(label("产物"));
        TextView tip = body("正在导出到下载目录…（" + mbText(apk) + "，完成后显示路径）");
        tip.setPadding(0, dp(8), 0, 0);
        wait.addView(tip);
        resultWrap.addView(wait);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri exported = exportToDownloads(apk, apk.getName());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showResult(apk, exported, signing);
                    }
                });
            }
        }, "sbpatch-export").start();
    }

    private static String mbText(File f) {
        return String.format(java.util.Locale.US, "%.1f MB", f.length() / 1024.0 / 1024.0);
    }

    /** 渲染产物卡片（导出完成后在 UI 线程调用）。 */
    private void showResult(final File apk, final Uri exported,
                            final SelfSignedKeystore.Identity signing) {
        resultWrap.removeAllViews();

        // 导出成功就报公共路径（/sdcard/Download/SBPlus/），失败就只报私有路径。
        final String shownPath = exported != null && "file".equals(exported.getScheme())
                ? exported.getPath()
                : (exported != null
                        ? "Download/" + OUT_DIR_NAME + "/" + apk.getName()
                        : apk.getAbsolutePath());

        LinearLayout card = card();
        card.addView(label("产物"));
        TextView path = text(shownPath, 12, C_OK, false);
        path.setPadding(0, dp(8), 0, dp(4));
        path.setTextIsSelectable(true);
        card.addView(path);
        card.addView(text(mbText(apk), 11, C_TEXT3, false));
        if (exported == null) {
            card.addView(text("（导出到下载目录失败，产物在应用私有目录里）",
                    11, C_WARN, false));
        }
        // 2026-11-22 审查 S1：让用户看得见"这次是用哪张证书签的"。
        card.addView(text("签名：" + signing.describe(), 11, C_TEXT3, false));
        if (signing.legacy) {
            card.addView(text("沿用旧版公开证书以保持可覆盖升级；卸载重装后可切到本机随机证书。",
                    11, C_WARN, false));
        }

        Button install = primaryButton("安装");
        install.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installApk(apk);
            }
        });
        card.addView(install);

        Button copy = secondaryButton("复制路径");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("apk", shownPath));
                    toast("路径已复制");
                } catch (Throwable t) {
                    toast("复制失败: " + t);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.addView(copy, lp);
        resultWrap.addView(card);
    }

    private void onFail(Throwable t) {
        btnRun.setEnabled(true);
        tvProgress.setTextColor(C_ERR);
        tvProgress.setText("失败");
        resultWrap.setVisibility(View.VISIBLE);
        resultWrap.removeAllViews();
        LinearLayout card = card();
        card.addView(label("失败原因"));
        card.addView(body(String.valueOf(t)));
        resultWrap.addView(card);
    }

    /**
     * 安装产出的 APK。
     *
     * <p><b>为什么不用 FileProvider</b>：androidx.core 会注册 CoreComponentFactory
     * 接管组件实例化，导致本模块 LogProvider 加载失败、app 启动即崩
     * （实测踩过，Thanox 反复处理 → 手机无限重启）。这里直接走系统
     * {@link android.content.pm.PackageInstaller}，不需要 androidx。
     *
     * <p><b>2026-11-20 修复「点安装没反应」</b>，三个原因都要处理：
     * <ol>
     *   <li><b>缺 REQUEST_INSTALL_PACKAGES 权限</b> —— 没它 {@code commit()}
     *       直接抛 SecurityException，连确认界面都不会弹。已在 manifest 补上。</li>
     *   <li><b>用户没为本应用打开「安装未知应用」</b> —— 这是运行时开关，
     *       代码里只能检测 + 引导，不能替用户打开。所以先查
     *       {@code canRequestPackageInstalls()}，false 就跳系统设置页。</li>
     *   <li><b>285MB 的包在主线程写</b> —— 原实现在点「安装」的 UI 线程里
     *       把整个 APK 流式写进 PackageInstaller 会话。300MB 走一遍主线程，
     *       ANR 边缘，用户看到的就是「点了没反应」。现在全部挪到后台线程。</li>
     * </ol>
     */
    private void installApk(final File apk) {
        // ① 先检查「安装未知应用」权限（Android 8+ 才有这个概念）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("需要「安装未知应用」权限")
                        .setMessage("系统里还没允许本应用安装 APK。\n\n"
                                + "点「去设置」打开开关，然后回来再点一次「安装」。")
                        .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                try {
                                    android.content.Intent i = new android.content.Intent(
                                            android.provider.Settings
                                                    .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            android.net.Uri.parse("package:" + getPackageName()));
                                    startActivity(i);
                                } catch (Throwable t) {
                                    toast("无法打开设置页: " + t);
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
        }

        // ② 后台线程写会话（300MB 不能占主线程）
        toast("正在准备安装…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.pm.PackageInstaller pi =
                            getPackageManager().getPackageInstaller();
                    android.content.pm.PackageInstaller.SessionParams params =
                            new android.content.pm.PackageInstaller.SessionParams(
                                    android.content.pm.PackageInstaller.SessionParams
                                            .MODE_FULL_INSTALL);
                    // 装完由系统自动打开？不 —— 让用户自己决定。这里只提交。
                    int sessionId = pi.createSession(params);
                    android.content.pm.PackageInstaller.Session session = pi.openSession(sessionId);
                    try {
                        java.io.OutputStream os = session.openWrite("base.apk", 0, apk.length());
                        java.io.FileInputStream fis = new java.io.FileInputStream(apk);
                        try {
                            byte[] buf = new byte[256 * 1024];
                            int n;
                            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                            session.fsync(os);
                        } finally {
                            try {
                                fis.close();
                            } catch (Throwable ignored) {
                            }
                            try {
                                os.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        // commit 之后系统会发 STATUS_PENDING_USER_ACTION 广播，
                        // 由 InstallResultReceiver 拉起确认界面。
                        session.commit(buildInstallCallback(sessionId));
                    } finally {
                        try {
                            session.close();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("安装失败: " + t
                                    + "\n\n可点「复制路径」用文件管理器手动安装");
                        }
                    });
                }
            }
        }, "sbpatch-install").start();
    }

    private android.content.IntentSender buildInstallCallback(final int sessionId) {
        Intent i = new Intent(this, InstallResultReceiver.class);
        i.putExtra("session", sessionId);
        return android.app.PendingIntent.getBroadcast(this, sessionId, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_MUTABLE).getIntentSender();
    }

    // ==================== 工具 ====================

    private File copyToPrivate(Uri uri) throws Exception {
        File dir = new File(getExternalFilesDir(null), "patch_src");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "source.apk");
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开所选文件");
        java.io.OutputStream os = new java.io.FileOutputStream(out);
        try {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                os.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 取得签名身份（2026-11-22 审查 S1 修复）。
     *
     * <p>原实现把一张<b>固定的私钥</b>打包在 {@code assets/patch.keystore} 里、
     * 口令硬编码在源码中。任何拿到模块 APK 的人都能解出它，从而签出与用户已装
     * 补丁版浏览器同证书的包 —— Android 的更新校验只看证书，等于把"伪造更新"
     * 的能力公开分发。
     *
     * <p>现在改为：首次使用时<b>在本机生成</b>密钥对与自签证书，存进应用私有目录；
     * 私钥不再随 APK 分发。详见 {@link SelfSignedKeystore}。
     */
    private SelfSignedKeystore.Identity ensureSigning() throws Exception {
        return SelfSignedKeystore.obtain(getFilesDir());
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        l.setBackground(bg);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    private TextView label(String s) {
        TextView t = text(s, 11, C_TEXT3, false);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private TextView body(String s) {
        TextView t = text(s, 13, C_TEXT2, false);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFF0A1420);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BRAND);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(C_BRAND);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFF2E3339);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
