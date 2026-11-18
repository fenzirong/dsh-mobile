package com.dsh.tdsh;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * Termux 原生运行时的准入闸门。
 *
 * <h3>这条路是什么</h3>
 * 不用 Ubuntu、不用 glibc：直接用 **bionic 编译的 node / bash**（Termux 那套用户态）
 * 跑 dsh。理论上这是启动最快的一条 —— 系统调用直达内核，没有 PRoot 的 ptrace，
 * 也没有 tawcroot 的 seccomp 模拟。
 *
 * <h3>为什么还需要 tawcroot</h3>
 * Termux 的二进制把 `/data/data/com.termux/files/usr` 硬编码进了 RUNPATH 与 shebang，
 * 而我们的包名是 `com.dsh.tdsh`（长度不同，**不能**像工作区那个合并版项目
 * 那样做等长字节替换）。所以我们不补丁二进制，而是用 tawcroot 把
 * Termux 那个前缀**绑到我们的目录**上 —— 硬编码路径天然正确，
 * 顺带还解决了原生模式下 `link(2)` 被 SELinux 拒绝的问题（tawcroot 模拟硬链接）。
 *
 * <h3>闸门做什么</h3>
 * 和 tawcroot 那道一样：跑一组覆盖"dsh 内核 + 插件市场"依赖的探针，
 * 任一项失败就回落 PRoot，并把原因写进设置页。
 */
public final class TermuxGate {

    private static final String TAG = "DSH_TERMUX";

    private static final String KEY_GATE = "termux_gate";
    private static final String KEY_GATE_VC = "termux_gate_vc";

    private TermuxGate() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
    }

    /** 用户态是否已就位（只看文件在不在；能不能跑由自检决定）。 */
    public static boolean installed(Context c) {
        try {
            return Env.termuxInstalled(Env.base(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给设置页显示的一句话。 */
    public static String note(Context c) {
        if (!installed(c)) return "用户态未安装（files/probe/termux/usr 下没有 node 或 dsh）";
        try {
            String g = prefs(c).getString(KEY_GATE, null);
            if (g == null) return "已安装，尚未自检";
            if ("ok".equals(g)) return "自检通过";
            return "自检未通过：" + (g.startsWith("fail:") ? g.substring(5) : g);
        } catch (Throwable t) {
            return "已安装，状态未知";
        }
    }

    static void fail(Context c, String why) {
        try {
            prefs(c).edit().putString(KEY_GATE, "fail:" + why).apply();
        } catch (Throwable ignore) { }
    }

    /**
     * 确保可用。
     *
     * @return {@code null} = 可用；否则是回落原因
     */
    public static String ensureUsable(Context c, File base) {
        try {
            if (!Env.termuxInstalled(base)) return "用户态未安装";
            final int vc = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            SharedPreferences sp = prefs(c);
            if (sp.getInt(KEY_GATE_VC, -1) == vc && "ok".equals(sp.getString(KEY_GATE, null))) {
                return null;
            }
            Env.sRuntime = Env.RT_TERMUX;
            String out = Env.execInContainer(c, PROBE, 120000L, null);
            if (out == null) return "探针没有输出（native node 起不来 / 超时）";
            if (out.contains("GATE_OK")) {
                sp.edit().putString(KEY_GATE, "ok").putInt(KEY_GATE_VC, vc).apply();
                Log.i(TAG, "termux 原生运行时自检通过");
                return null;
            }
            String why = "未知";
            int i = out.indexOf("GATE_FAIL_");
            if (i >= 0) {
                int j = out.indexOf('\n', i);
                why = out.substring(i + "GATE_FAIL_".length(), j < 0 ? out.length() : j).trim();
            }
            Log.w(TAG, "termux 自检失败: " + why + "\n" + out);
            return why;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 探针：与 tawcroot 那道同源（都要证明"插件市场依赖的底层能力"在），
     * 额外确认我们跑的是**真 Termux 用户态**（PREFIX 下有 node/bash/dsh）。
     */
    private static final String PROBE =
            "set +e\n"
          + "p=$PREFIX; [ -n \"$p\" ] || p=/data/data/com.termux/files/usr\n"
          + "command -v node >/dev/null 2>&1 || { echo GATE_FAIL_NO_NODE_IN_PATH; exit 0; }\n"
          + "node -v >/dev/null 2>&1 || { echo GATE_FAIL_NODE_RUN; exit 0; }\n"
          + "[ -x \"$p/bin/bash\" ] || { echo GATE_FAIL_NO_BASH; exit 0; }\n"
          + "[ -f \"$p/lib/node_modules/@deepseek-ai/dsh/lib/bin.js\" ] || { echo GATE_FAIL_NO_DSH; exit 0; }\n"
          + "d=/tmp/.dsh-termux-gate; rm -rf $d; mkdir -p $d; cd $d || { echo GATE_FAIL_TMP; exit 0; }\n"
          + "echo A > a; ln a b 2>/dev/null || { echo GATE_FAIL_HARDLINK_LN; exit 0; }\n"
          + "[ \"$(cat b 2>/dev/null)\" = A ] || { echo GATE_FAIL_HARDLINK_READ; exit 0; }\n"
          + "[ \"$(stat -c %h b 2>/dev/null)\" -ge 2 ] || { echo GATE_FAIL_HARDLINK_NLINK; exit 0; }\n"
          + "ln -s a sy 2>/dev/null || { echo GATE_FAIL_SYMLINK_LN; exit 0; }\n"
          + "[ \"$(readlink sy 2>/dev/null)\" = a ] || { echo GATE_FAIL_SYMLINK_READ; exit 0; }\n"
          + "[ -r /proc/self/status ] || { echo GATE_FAIL_PROC; exit 0; }\n"
          + "mkdir -p /sdcard/.dsh-phone 2>/dev/null; echo x > /sdcard/.dsh-phone/gate 2>/dev/null "
          + "|| { echo GATE_FAIL_SDCARD_WRITE; exit 0; }\n"
          + "[ -s /sdcard/.dsh-phone/gate ] || { echo GATE_FAIL_SDCARD_READ; exit 0; }\n"
          + "rm -f /sdcard/.dsh-phone/gate\n"
          + "if command -v pnpm >/dev/null 2>&1; then pnpm -v >/dev/null 2>&1 "
          + "|| { echo GATE_FAIL_PNPM; exit 0; }; fi\n"
          + "cd /; rm -rf $d\n"
          + "echo GATE_OK\n";
}
