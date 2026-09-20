package com.sbplus.browser;

import android.app.Activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Log management screen: view, export and delete module logs, plus edit retention
 * settings (keep-days and max size). Logs live in this app's filesDir via LogProvider,
 * and retention settings are shared to the hook-side cleanup via SharedPreferences.
 */
public class LogManagerActivity extends Activity {

    private static final Uri LOG_URI = Uri.parse("content://com.sbplus.browser.log");
    private static final String CONFIG_PREFS = "sbplus_log_config";

    private TextView logText;

    /**
     * Activity 是否已销毁。
     *
     * <p>loadLogs 现在是异步的:后台读完后通过 runOnUiThread 回主线程更新文本。
     * 若用户在此期间退出该页面,回调仍会执行并触碰已销毁的视图——
     * 轻则白做一次 UI 更新,重则在部分 ROM 上抛
     * "View not attached to window manager"。这里用标志位拦掉。
     */
    private volatile boolean sDestroyed;

    @Override
    protected void onDestroy() {
        sDestroyed = true;
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 布局已抽到 res/layout/activity_log_manager.xml(与 activity_main.xml 风格统一)。
        // 本方法现在只负责"把运行时数据填进去 + 挂监听",不再做视图构建。
        setContentView(R.layout.activity_log_manager);

        SharedPreferences cfg = getSharedPreferences(CONFIG_PREFS, MODE_PRIVATE);

        logText = findViewById(R.id.tv_logs);

        final EditText daysInput = findViewById(R.id.et_keep_days);
        daysInput.setText(String.valueOf(cfg.getInt("log_keep_days", LogProvider.DEFAULT_KEEP_DAYS)));

        final EditText mbInput = findViewById(R.id.et_max_mb);
        mbInput.setText(String.valueOf(cfg.getInt("log_max_mb", LogProvider.DEFAULT_MAX_MB)));

        Button saveBtn = findViewById(R.id.btn_save);
        saveBtn.setOnClickListener(v -> {
            try {
                int days = Integer.parseInt(daysInput.getText().toString().trim());
                int mb = Integer.parseInt(mbInput.getText().toString().trim());
                if (days < 1) days = 1;
                if (mb < 1) mb = 1;
                cfg.edit().putInt("log_keep_days", days).putInt("log_max_mb", mb).commit();
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });

        Button viewBtn = findViewById(R.id.btn_view);
        viewBtn.setOnClickListener(v -> loadLogs());

        Button exportBtn = findViewById(R.id.btn_export);
        exportBtn.setOnClickListener(v -> exportLogs());

        Button delBtn = findViewById(R.id.btn_delete);
        delBtn.setOnClickListener(v -> {
            getContentResolver().delete(LOG_URI, null, null);
            logText.setText("（日志已清空）");
            Toast.makeText(this, "日志已删除", Toast.LENGTH_SHORT).show();
        });

        loadLogs();
    }

    private void loadLogs() {
        // 一次跨进程查询可能要读数 MB 日志(LogProvider 单次上限 64K 字符,
        // 但整体仍可能较大),在主线程做会直接卡住 UI。放到后台线程,
        // 结果回主线程更新文本。
        logText.setText("正在读取…");
        new Thread(new Runnable() {
            @Override public void run() {
                // 注意:result 必须先初始化再赋值,不能让「try 与 catch 各赋一次」
                // 依赖确定赋值分析——被匿名内部类捕获时那样写通不过编译。
                String result = "（暂无日志）";
                try {
                    String text = queryLogContent();
                    if (text != null && !text.isEmpty()) result = text;
                } catch (Throwable t) {
                    // 与"没有日志"区分开:这是查询失败,必须让用户看见原因,
                    // 否则表现为"日志页一直空白",无从判断是没日志还是坏了。
                    result = "读取日志失败: " + t;
                }
                final String shown = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // 页面可能已被关闭:此时不能再碰 logText。
                        if (sDestroyed || isFinishing()) return;
                        logText.setText(shown);
                    }
                });
            }
        }, "SBPlus-log-read").start();
    }

    /**
     * 查询日志正文。调用方负责在后台线程执行。
     *
     * <p>Cursor 用 finally 关闭:原实现只在 {@code moveToFirst()} 成功的分支里
     * close,空结果或中途抛异常都会泄漏 Cursor(它持有跨进程的窗口,
     * 泄漏会累积到 provider 侧报 "CursorWindow" 相关错误)。
     */
    private String queryLogContent() {
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    Uri.withAppendedPath(LOG_URI, "content"), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
            return null;
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignored) {} }
        }
    }

    private void exportLogs() {
        String content = null;
        String err = null;
        try {
            content = queryLogContent();
        } catch (Throwable t) {
            err = String.valueOf(t);
        }

        if (err != null) {
            // 明确区分"查询失败"与"没有日志":原实现把两者都吞成
            // "暂无日志可导出",用户会以为日志是空的,而实际是读取出错。
            Toast.makeText(this, "读取日志失败: " + err, Toast.LENGTH_LONG).show();
            return;
        }
        if (content == null || content.isEmpty()) {
            Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "SBPlus 日志");
            send.putExtra(Intent.EXTRA_TEXT, content);
            startActivity(Intent.createChooser(send, "导出日志"));
        } catch (Throwable t) {
            // 没有可接收 ACTION_SEND 的应用时 createChooser 会抛
            // ActivityNotFoundException,原来完全没有兜底。
            Toast.makeText(this, "导出失败: " + t, Toast.LENGTH_LONG).show();
        }
    }
}
