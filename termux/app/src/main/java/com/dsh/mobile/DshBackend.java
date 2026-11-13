package com.dsh.mobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

/**
 * 在合并版中直接通过 TermuxService 启动 DSH 主机。
 *
 * <p>本应用就是 Termux 本体，因此无需 RUN_COMMAND 权限或 allow-external-apps 设置，
 * 直接投递 {@code ACTION_SERVICE_EXECUTE} 即可在终端会话里运行命令。
 */
public final class DshBackend {

    private DshBackend() {
    }

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
}
