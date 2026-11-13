package com.dsh.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 通过 Termux 的 RUN_COMMAND 服务在后台拉起 DSH 主机。
 *
 * <p>前提：设备已安装 Termux，且 {@code ~/.termux/termux.properties} 中设置了
 * {@code allow-external-apps=true}。命令在 Termux 的后台会话中执行，输出（含带 token 的
 * 访问 URL）会出现在 Termux 的通知与会话里，需要用户复制回本应用。
 */
public final class TermuxBridge {

    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private TermuxBridge() {
    }

    public static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 在 Termux 后台会话中执行一条命令。
     *
     * @return true 表示 Intent 已成功投递（不代表命令执行成功）。
     */
    public static boolean run(Context context, String command) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        try {
            context.startService(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 打开 Termux 主界面，方便用户查看输出或手动操作。 */
    public static boolean openTermux(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
