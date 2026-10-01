package com.sbplus.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


/**
 * SBPlus module entry / status screen.
 *
 * Shows the current version (tap to check for updates), a project link, and buttons
 * to jump into the embedded settings sub-page and the log manager. On launch it also
 * performs a silent update check (notification-style toast only, no dialog).
 */
public class MainActivity extends Activity {

    public static final String PREFS_NAME = "samsung_download_bridge";
    public static final String KEY_VERSION_NAME = "version_name";
    public static final String KEY_VERSION_CODE = "version_code";

    private TextView mVersionView;

    /** 激活状态三件套（2026-10-04 新增）。 */
    private android.view.View mStatusDot;
    private TextView mStatusView;
    private TextView mStatusDetailView;

    /**
     * 服务绑定是**异步**的：首次打开界面时 registerListener 刚注册，
     * XposedService 往往还没绑定完，此刻读到的是"未激活"。
     * 若只靠 onResume 刷新，用户会看到一个错误的"未激活"并一直停在那里。
     * 故注册监听器，绑定完成时自动重刷。
     */
    private final ModuleStatus.Listener mStatusListener = new ModuleStatus.Listener() {
        @Override
        public void onStatusChanged() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && !isDestroyed()) refreshModuleStatus();
                }
            });
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        // 用户很可能是"去 LSPosed 勾选作用域后再回来"——回到前台时刷新一次，
        // 否则界面还停留在上次的旧状态，会让人以为没生效。
        ModuleStatus.addListener(mStatusListener);
        refreshModuleStatus();
    }

    @Override
    protected void onPause() {
        ModuleStatus.removeListener(mStatusListener);
        super.onPause();
    }

    /**
     * 刷新激活状态显示。
     *
     * <p>三态文案刻意写明"下一步该做什么" —— 只说"未激活"对用户没有帮助，
     * 说清"去 LSPosed 启用"才是可执行的。
     */
    private void refreshModuleStatus() {
        if (mStatusView == null) return;
        try {
            ModuleStatus.State st = ModuleStatus.getState();
            switch (st) {
                case ACTIVE_READY: {
                    boolean canQuery = ModuleStatus.canQueryRunningTargets();
                    boolean running = canQuery && ModuleStatus.isBrowserRunning();
                    mStatusDot.setBackgroundResource(R.drawable.dot_ok);
                    mStatusView.setText("已激活");
                    StringBuilder sb = new StringBuilder();
                    String fw = ModuleStatus.getFrameworkName();
                    String fv = ModuleStatus.getFrameworkVersion();
                    if (!fw.isEmpty()) {
                        sb.append("框架：").append(fw);
                        if (!fv.isEmpty()) sb.append(" ").append(fv);
                        sb.append('\n');
                    }
                    sb.append("作用域：已包含三星浏览器\n");
                    if (!canQuery) {
                        // getRunningTargets 需要框架 API 102+；旧框架下无法查询，
                        // 这里必须说"未知"而不是"未运行" —— 后者是错误结论。
                        sb.append("浏览器：运行状态未知（框架版本较低，无法查询）");
                    } else {
                        sb.append(running
                                ? "浏览器：正在运行，模块已加载"
                                : "浏览器：未在运行（打开浏览器后模块才会加载）");
                    }
                    mStatusDetailView.setText(sb.toString());
                    break;
                }
                case ACTIVE_NO_SCOPE: {
                    mStatusDot.setBackgroundResource(R.drawable.dot_warn);
                    mStatusView.setText("已启用，但作用域未勾选");
                    StringBuilder sb = new StringBuilder();
                    String fw = ModuleStatus.getFrameworkName();
                    if (!fw.isEmpty()) sb.append("框架：").append(fw).append('\n');
                    sb.append("请在 LSPosed 中勾选「三星浏览器」作为作用域，然后重启浏览器。");
                    mStatusDetailView.setText(sb.toString());
                    break;
                }
                default: {
                    mStatusDot.setBackgroundResource(R.drawable.dot_err);
                    mStatusView.setText("未激活");
                    mStatusDetailView.setText(
                            "未检测到框架服务。请确认：\n"
                                    + "① 已在 LSPosed 中启用本模块；\n"
                                    + "② 作用域已勾选「三星浏览器」；\n"
                                    + "③ 已重启三星浏览器。");
                    break;
                }
            }
        } catch (Throwable t) {
            mStatusView.setText("状态未知");
            mStatusDetailView.setText("读取失败：" + t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 把版本号写入 prefs，供浏览器进程的 SBPlus 菜单读取。
        // 浏览器进程通过 MainModule.getRemotePreferences(PREFS_NAME) 读取
        // (见 MainHook.readModuleVersion),该通道不受文件权限位限制,
        // 因此普通的 apply() 即可,无需让 prefs 世界可读。
        //
        // 原先这里在 apply() 之后又跟了一句
        //   try { getSharedPreferences(...).edit().commit(); } catch (Throwable ignored) {}
        // 这是死代码:它提交的是一个**全新的空编辑器**,与上面那次写入无关,
        // 既不会改变已写入的值,也解决不了任何跨进程可见性问题(注释里设想的
        // makeWorldReadable 从未被调用)。删除即可,行为不变。
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_VERSION_NAME, BuildConfig.VERSION_NAME)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .apply();

        mVersionView = findViewById(R.id.tv_version);
        TextView projectView = findViewById(R.id.tv_project);
        Button openSettingsBtn = findViewById(R.id.btn_open_settings);
        Button openLogsBtn = findViewById(R.id.btn_open_logs);

        // 2026-10-04 新增：激活状态显示。
        // 用户装上模块后最常见的问题是"怎么没反应"，而原因通常只有三种：
        // ① 没在 LSPosed 里启用；② 启用了但没勾选三星浏览器作用域；③ 都对了但浏览器未重启。
        // 这三种状态原先在界面上完全看不出来，用户只能靠"功能有没有生效"去猜。
        mStatusDot = findViewById(R.id.dot_status);
        mStatusView = findViewById(R.id.tv_status);
        mStatusDetailView = findViewById(R.id.tv_status_detail);
        ModuleStatus.init(this);
        refreshModuleStatus();

        mVersionView.setText("版本 " + BuildConfig.VERSION_NAME);

        // 项目地址：点击用浏览器打开 GitHub 仓库
        projectView.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(UpdateChecker.projectUrl())));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开项目地址", Toast.LENGTH_SHORT).show();
            }
        });

        // 问题反馈（GitHub）：打开 Issues 页面并预填环境信息
        TextView feedbackGithubView = findViewById(R.id.tv_feedback_github);
        feedbackGithubView.setOnClickListener(v -> {
            try {
                StringBuilder body = new StringBuilder();
                body.append("## 环境信息\n\n");
                body.append("| 项目 | 值 |\n");
                body.append("|------|----|\n");
                body.append("| 模块版本 | ").append(BuildConfig.VERSION_NAME).append(" |\n");
                try {
                    android.content.pm.PackageInfo pi = getPackageManager()
                            .getPackageInfo("com.sec.android.app.sbrowser", 0);
                    body.append("| 浏览器版本 | ").append(pi.versionName).append(" |\n");
                } catch (Exception ignored) {}
                body.append("| 设备型号 | ").append(android.os.Build.BRAND).append(" ")
                        .append(android.os.Build.MODEL).append(" |\n");
                body.append("| Android 版本 | ").append(android.os.Build.VERSION.RELEASE)
                        .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(") |\n");
                body.append("\n## 问题描述\n\n");
                body.append("<!-- 请在此描述您遇到的问题，包括复现步骤 -->\n");

                String title = java.net.URLEncoder.encode("[反馈] ", "UTF-8");
                String bodyEncoded = java.net.URLEncoder.encode(body.toString(), "UTF-8");
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/AngBang852/SBPlus/issues/new?title=" + title + "&body=" + bodyEncoded)));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开反馈页面", Toast.LENGTH_SHORT).show();
            }
        });

        // 问题反馈（邮件）：调起邮件客户端并预填环境信息
        TextView feedbackEmailView = findViewById(R.id.tv_feedback_email);
        feedbackEmailView.setOnClickListener(v -> {
            try {
                StringBuilder body = new StringBuilder();
                body.append("模块版本: ").append(BuildConfig.VERSION_NAME).append("\n");
                try {
                    android.content.pm.PackageInfo pi = getPackageManager()
                            .getPackageInfo("com.sec.android.app.sbrowser", 0);
                    body.append("浏览器版本: ").append(pi.versionName).append("\n");
                } catch (Exception ignored) {}
                body.append("设备型号: ").append(android.os.Build.BRAND).append(" ")
                        .append(android.os.Build.MODEL).append("\n");
                body.append("Android 版本: ").append(android.os.Build.VERSION.RELEASE)
                        .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n");
                body.append("问题描述:\n");

                Intent i = new Intent(Intent.ACTION_SENDTO);
                i.setData(Uri.parse("mailto:poiuy865@foxmail.com"));
                i.putExtra(Intent.EXTRA_SUBJECT, "[SBPlus 反馈]");
                i.putExtra(Intent.EXTRA_TEXT, body.toString());
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开邮件应用", Toast.LENGTH_SHORT).show();
            }
        });

        // 版本号：点击手动检测更新（有更新弹窗确认下载）
        mVersionView.setOnClickListener(v -> checkUpdate(true));

        openSettingsBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent();
                i.setClassName("com.sec.android.app.sbrowser",
                        "com.sec.android.app.sbrowser.settings.SettingsActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra("sbrowser.settings.show_fragment",
                        "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom");
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this,
                        "无法打开 SBPlus 设置: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });

        openLogsBtn.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, LogManagerActivity.class));
        });

        // 启动时静默检测更新（只提示，不弹窗）
        checkUpdate(false);
    }

    /**
     * Query latest release from GitHub.
     *
     * @param interactive true: pull-to-check (dialog on success/failure);
     *                    false: auto-check on launch (toast only, no dialog).
     */
    private void checkUpdate(final boolean interactive) {
        final String local = BuildConfig.VERSION_NAME;
        mVersionView.setEnabled(false);
        UpdateChecker.check(local, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info, String error) {
                mVersionView.setEnabled(true);
                if (error != null) {
                    if (interactive) {
                        Toast.makeText(MainActivity.this,
                                "检测失败：" + error, Toast.LENGTH_LONG).show();
                    }
                    // silent failure: stay quiet
                    return;
                }
                if (info == null) return;
                if (info.newer) {
                    if (interactive) {
                        showUpdateDialog(info);
                    } else {
                        // auto-check: toast only
                        Toast.makeText(MainActivity.this,
                                "发现新版本 " + info.tagName + "，点击版本号更新",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    if (interactive) {
                        Toast.makeText(MainActivity.this,
                                "已是最新版本（" + info.tagName + "）",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void showUpdateDialog(final UpdateChecker.UpdateInfo info) {
        String note = info.body;
        if (note == null || note.trim().isEmpty()) note = "（无更新说明）";
        if (note.length() > 500) note = note.substring(0, 500) + "…";

        final String downloadUrl = info.downloadUrl;

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("发现新版本：" + info.tagName);
        b.setMessage("当前版本：" + BuildConfig.VERSION_NAME + "\n\n" + note);
        b.setPositiveButton("下载更新", (d, w) -> {
            if (downloadUrl != null && !downloadUrl.isEmpty()) {
                UpdateChecker.openDownload(MainActivity.this, downloadUrl);
                Toast.makeText(MainActivity.this,
                        "已打开下载页面，下载后请手动安装", Toast.LENGTH_LONG).show();
            } else {
                UpdateChecker.openDownload(MainActivity.this, UpdateChecker.projectUrl());
                Toast.makeText(MainActivity.this,
                        "未找到 apk 资源，已打开项目页面", Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }
}
