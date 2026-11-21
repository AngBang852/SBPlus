package com.sbplus.browser.patcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * 安装结果接收器。
 *
 * <p>{@link PackageInstaller#commit} 需要一个 PendingIntent 来回报结果，
 * 系统安装完成后会广播到这里。用 BroadcastReceiver 而不是 Activity，
 * 是因为安装可能在用户切走之后才完成。
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String msg;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                msg = "安装成功";
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // 系统需要用户确认：把确认界面拉起来
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Throwable t) {
                        Toast.makeText(context, "无法打开安装确认: " + t,
                                Toast.LENGTH_LONG).show();
                    }
                }
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                msg = "安装被取消";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                msg = "安装被系统阻止（可能需要在设置里允许未知来源）";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                msg = "安装冲突：签名不同，需先卸载原版";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                msg = "安装不兼容（系统版本或架构不符）";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                msg = "APK 无效（打包可能出错）";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                msg = "存储空间不足";
                break;
            default:
                String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                msg = "安装失败(" + status + ")" + (detail == null ? "" : ": " + detail);
                break;
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }
}
