package com.dsh.mobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

/**
 * 在合并版中通过 TermuxService 执行命令。
 *
 * <p>本应用就是 Termux 本体，因此无需 RUN_COMMAND 权限或 allow-external-apps 设置，
 * 直接投递 {@code ACTION_SERVICE_EXECUTE} 即可。
 */
public final class DshBackend {

    private DshBackend() {
    }

    /**
     * 在终端会话里跑一条命令（用户可见，用于「打开终端」场景）。
     */
    public static boolean start(Context context, String command) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String scheme = TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE;

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), "com.termux.app.TermuxService");
        intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        intent.setData(Uri.parse(scheme + "://" + prefix + "/bin/bash"));
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS,
                new String[]{"-lc", command});
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR,
                TermuxConstants.TERMUX_HOME_DIR_PATH);
        intent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER,
                ExecutionCommand.Runner.TERMINAL_SESSION.getName());
        intent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, "0");
        try {
            context.startService(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在后台跑一个脚本（app-shell runner，不占用终端会话，App 退到后台也不会被回收）。
     *
     * @param scriptPath 脚本绝对路径（需带可执行位与 shebang）
     * @param arg        传给脚本的第一个参数，可为 null
     */
    public static boolean startBackgroundScript(Context context, String scriptPath, String arg) {
        String scheme = TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE;

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), "com.termux.app.TermuxService");
        intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        intent.setData(Uri.parse(scheme + "://" + scriptPath));
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS,
                arg == null || arg.isEmpty() ? new String[0] : new String[]{arg});
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR,
                TermuxConstants.TERMUX_HOME_DIR_PATH);
        intent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER,
                ExecutionCommand.Runner.APP_SHELL.getName());
        try {
            context.startService(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
