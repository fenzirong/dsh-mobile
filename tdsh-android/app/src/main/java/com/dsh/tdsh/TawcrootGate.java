package com.dsh.tdsh;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * tawcroot 运行时的**准入闸门**：先自检，过了才用，不过就回落 PRoot。
 *
 * <h3>它是谁、为什么值得接</h3>
 * tawcroot 是 TAWC 项目里一个从零写的 fake chroot：用 seccomp + SIGSYS 在
 * **同一个进程内**模拟路径翻译，而不是像 PRoot 那样 ptrace 停进程。
 * 我们的真机 profile 显示 dsh 冷启动 59 秒里 86% 是路径类系统调用
 * （14 万次主动上下文切换 = 14 万次 ptrace 往返），正好是它要解决的问题；
 * 上游实测"启动快约三倍"。
 *
 * <h3>为什么必须先自检，而不是直接信任</h3>
 * 它是**另一套实现**，能力边界与 PRoot 不同：
 * <ul>
 *   <li>没有 {@code -0}（它永远在 guest 里伪装 uid 0）；</li>
 *   <li>没有 {@code --link2symlink}（它自己模拟硬链接，仓库放在 {@code <rootfs>/../tawcroot}）；</li>
 *   <li>没有 {@code -w}（工作目录要用 sh 包一层）；</li>
 *   <li>单进程 → 没有真正隔离，且（上游文档）Android 12+ 的 phantom process killer
 *       会限制子进程数。</li>
 * </ul>
 * 任何一条没实现好，都会以最难查的方式表现出来：<b>插件市场装不上/装了不生效</b>。
 * 所以这里跑一组"正好覆盖插件市场依赖的底层能力"的探针，任意一项失败就回落。
 *
 * <h3>闸门覆盖了什么</h3>
 * <ol>
 *   <li><b>硬链接</b>：dsh 的原子写（临时文件 + link）和 pnpm 的某些布局都靠它 ——
 *       Android 的 SELinux 禁止普通 App 建硬链接，这正是 tawcroot 要模拟的东西；</li>
 *   <li><b>符号链接</b>：pnpm 的 {@code node_modules} 就是符号链接树；</li>
 *   <li><b>execve</b>：起 node、起 bash 工具、跑 npm/pnpm；</li>
 *   <li><b>/proc</b>：node 读 {@code /proc/self/*}，我们的心跳也读；</li>
 *   <li><b>/sdcard 绑定</b>：工作区与「手机文件」插件；</li>
 *   <li><b>pnpm 可用</b>（若已装）：插件市场的前置条件。</li>
 * </ol>
 */
public final class TawcrootGate {

    private static final String TAG = "DSH_TAWCROOT";

    /** 运行方式（"proot" / "tawcroot"）。 */
    private static final String KEY_MODE = "runtime_mode";
    /** 上次自检结论（"ok" 或 "fail:<原因>"）。 */
    private static final String KEY_GATE = "tawcroot_gate";
    /** 自检结论对应的 versionCode —— App 升级后要重测一次。 */
    private static final String KEY_GATE_VC = "tawcroot_gate_vc";

    private TawcrootGate() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
    }

    /** 用户选定的运行方式；默认 PRoot（兼容优先）。 */
    public static String mode(Context c) {
        try {
            return prefs(c).getString(KEY_MODE, Env.RT_PROOT);
        } catch (Throwable t) {
            return Env.RT_PROOT;
        }
    }

    public static void setMode(Context c, String m) {
        try {
            prefs(c).edit().putString(KEY_MODE, m).apply();
        } catch (Throwable ignore) { }
    }

    /** 用户是否想用 tawcroot。 */
    public static boolean isWanted(Context c) {
        return Env.RT_TAWCROOT.equals(mode(c));
    }

    /** 用户是否想用 Termux 原生运行时（同一个 runtime_mode 键的第三个取值）。 */
    public static boolean isTermuxWanted(Context c) {
        return Env.RT_TERMUX.equals(mode(c));
    }

    /** 上次自检的人话结论（设置页显示用）。 */
    public static String gateNote(Context c) {
        try {
            String g = prefs(c).getString(KEY_GATE, null);
            if (g == null) return "尚未自检";
            if ("ok".equals(g)) return "自检通过";
            return "自检未通过：" + (g.startsWith("fail:") ? g.substring(5) : g);
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 自检失败 → 永久回落（直到用户手动重测或 App 升级）。 */
    static void fallback(Context c, String why) {
        try {
            prefs(c).edit()
                    .putString(KEY_MODE, Env.RT_PROOT)
                    .putString(KEY_GATE, "fail:" + why)
                    .apply();
        } catch (Throwable ignore) { }
        Env.sRuntime = Env.RT_PROOT;
    }

    /** 用户点「重新自检」：清掉结论，下次启动再测。 */
    public static void rearm(Context c) {
        try {
            prefs(c).edit()
                    .putString(KEY_MODE, Env.RT_TAWCROOT)
                    .remove(KEY_GATE)
                    .remove(KEY_GATE_VC)
                    .apply();
        } catch (Throwable ignore) { }
    }

    /** 把 assets 里那份 tawcroot 装到 bin/（幂等，按大小+版本重写）。 */
    public static boolean install(Context c, File base) {
        try {
            File dst = Env.tawcrootBin(base);
            File dir = dst.getParentFile();
            if (dir != null) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File tmp = new File(dst.getAbsolutePath() + ".new");
            //noinspection ResultOfMethodCallIgnored
            if (tmp.exists()) tmp.delete();
            try (InputStream in = c.getAssets().open("tawcroot");
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            android.system.Os.chmod(tmp.getAbsolutePath(), 0700);
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                if (!dst.exists()) return false;
            }
            return dst.exists();
        } catch (Throwable t) {
            Log.w(TAG, "安装 tawcroot 失败", t);
            return false;
        }
    }

    /**
     * 确保 tawcroot 能用。
     *
     * @return {@code null} = 可用；否则是失败原因（调用方必须回落到 PRoot）
     */
    public static String ensureUsable(Context c, File base, String libDir,
                                      String apiKey, String tokenDir) {
        try {
            final int vc = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            SharedPreferences sp = prefs(c);
            if (sp.getInt(KEY_GATE_VC, -1) == vc && "ok".equals(sp.getString(KEY_GATE, null))) {
                return null;                      // 本版本已验过，直接用
            }
            if (!install(c, base)) return "tawcroot 二进制没装上（assets 缺失？）";

            /*
             * 自检必须在 tawcroot 下跑 —— 所以先切运行时，之后所有
             * containerCommand() 都走 tawcroot 分支。
             */
            Env.sRuntime = Env.RT_TAWCROOT;
            String out = Env.execInContainer(c, PROBE, 120000L, null);
            if (out == null) {
                return "探针没有输出（tawcroot 起不来 / 超时）";
            }
            if (out.contains("GATE_OK")) {
                sp.edit().putString(KEY_GATE, "ok").putInt(KEY_GATE_VC, vc).apply();
                Log.i(TAG, "tawcroot 自检通过");
                return null;
            }
            // 把探针自己说的失败点抠出来，作为回落原因写进设置页
            String why = "未知";
            int i = out.indexOf("GATE_FAIL_");
            if (i >= 0) {
                int j = out.indexOf('\n', i);
                why = out.substring(i + "GATE_FAIL_".length(), j < 0 ? out.length() : j).trim();
            }
            Log.w(TAG, "tawcroot 自检失败: " + why + "\n" + out);
            return why;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            // 无论成败，调用方会按返回值决定要不要切回 PRoot
        }
    }

    /**
     * 探针脚本：只覆盖"插件市场 + dsh 内核"真正依赖的底层能力。
     *
     * <p>每一项失败都打印一个**可读的标记**（GATE_FAIL_xxx），
     * 这样设置页能直接告诉用户"卡在硬链接"还是"卡在 /sdcard"，不用猜。
     */
    private static final String PROBE =
            "set +e\n"
          + "d=/tmp/.dsh-tawcroot-gate; rm -rf $d; mkdir -p $d; cd $d || { echo GATE_FAIL_TMP; exit 0; }\n"
          // ① 硬链接（dsh 原子写 / pnpm）：Android 禁止 App 建硬链接，tawcroot 用 link store 模拟
          + "echo A > a 2>/dev/null; ln a b 2>/dev/null || { echo GATE_FAIL_HARDLINK_LN; exit 0; }\n"
          + "[ \"$(cat b 2>/dev/null)\" = A ] || { echo GATE_FAIL_HARDLINK_READ; exit 0; }\n"
          + "[ \"$(stat -c %h b 2>/dev/null)\" -ge 2 ] || { echo GATE_FAIL_HARDLINK_NLINK; exit 0; }\n"
          // ② 符号链接（pnpm 的 node_modules 就是链接树）
          + "ln -s a sy 2>/dev/null || { echo GATE_FAIL_SYMLINK_LN; exit 0; }\n"
          + "[ \"$(readlink sy 2>/dev/null)\" = a ] || { echo GATE_FAIL_SYMLINK_READ; exit 0; }\n"
          // ③ execve（node / bash / npm）
          + "node -e '0' 2>/dev/null || { echo GATE_FAIL_NODE; exit 0; }\n"
          + "/bin/sh -c 'test -n \"$HOME\"' 2>/dev/null || { echo GATE_FAIL_SH; exit 0; }\n"
          // ④ /proc（node 与我们的心跳都读它）
          + "[ -r /proc/self/status ] || { echo GATE_FAIL_PROC; exit 0; }\n"
          // ⑤ /sdcard 绑定（工作区 + 手机文件插件）
          + "mkdir -p /sdcard/.dsh-phone 2>/dev/null; echo x > /sdcard/.dsh-phone/gate 2>/dev/null "
          + "|| { echo GATE_FAIL_SDCARD_WRITE; exit 0; }\n"
          + "[ -s /sdcard/.dsh-phone/gate ] || { echo GATE_FAIL_SDCARD_READ; exit 0; }\n"
          + "rm -f /sdcard/.dsh-phone/gate\n"
          // ⑥ 插件市场前置：pnpm 在不在、能不能跑（没装就不算失败，市场会按需装）
          + "if command -v pnpm >/dev/null 2>&1; then pnpm -v >/dev/null 2>&1 "
          + "|| { echo GATE_FAIL_PNPM; exit 0; }; fi\n"
          // ⑦ 收尾：确认没有把 tawcroot 的内部状态写脏
          + "cd /; rm -rf $d\n"
          + "echo GATE_OK\n";

    /** 硬链接仓库路径（给清理逻辑用）。 */
    public static File storeOf(File base) { return Env.tawcrootStore(base); }
}
