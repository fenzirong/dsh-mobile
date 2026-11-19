package com.dsh.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「一键启动」编排器。
 *
 * <p>把 {@code assets/dsh-boot.sh} 落到 {@code $PREFIX/tmp}，通过 TermuxService 的
 * app-shell 在后台执行；随后轮询状态文件与 dsh 输出日志，拿到带 token 的访问 URL
 * 后回调上层直接加载，用户全程只需要点一次按钮。
 */
public final class DshLauncher {

    public static final String PREFIX = "/data/data/com.dshapp/files/usr";
    private static final String TMP = PREFIX + "/tmp";
    private static final String SCRIPT = TMP + "/dsh-boot.sh";
    private static final String STATUS = TMP + "/dsh-boot.status";
    private static final String WEBLOG = TMP + "/dsh-web.log";
    private static final String BOOTLOG = TMP + "/dsh-boot.log";

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3090;

    private static final long POLL_INTERVAL_MS = 800L;
    private static final long OVERALL_TIMEOUT_MS = 20 * 60 * 1000L;

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s\"'<>]+\\?token=[^\\s\"'<>]+");

    public interface Listener {
        /** 脚本报告的阶段码，如 check / install-node / start / error:xxx。 */
        void onStatus(String code);

        /** 已拿到可用的带 token 地址。 */
        void onReady(String url);

        /** 启动器自身的问题（非脚本报错）。 */
        void onProblem(String message);

        /** 启动日志的最新几行，用于在界面上显示实时进度。 */
        void onDetail(String logTail);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private boolean active;
    private long deadline;
    private String lastStatus = "";
    private String lastDetail = "";

    public DshLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 若已有正在运行的 dsh 主机（端口在监听且日志里有 token），直接复用，免重新启动。
     */
    public void reuseIfRunning(final Listener target) {
        this.listener = target;
        io.execute(() -> {
            final String url = portOpen() ? readUrl() : null;
            main.post(() -> {
                if (url != null) {
                    target.onReady(url);
                } else {
                    target.onStatus("");
                }
            });
        });
    }

    /**
     * 「一键启动」入口：已在运行的实例直接复用，否则跑完整启动脚本。
     */
    public void reuseOrStart(final String mirrorUrl, final Listener target) {
        this.listener = target;
        io.execute(() -> {
            final String url = portOpen() ? readUrl() : null;
            main.post(() -> {
                if (url != null) {
                    target.onReady(url);
                } else {
                    start(mirrorUrl, target);
                }
            });
        });
    }

    public void start(final String mirrorUrl, Listener target) {        this.listener = target;
        this.active = true;
        this.deadline = System.currentTimeMillis() + OVERALL_TIMEOUT_MS;
        this.lastStatus = "";
        this.lastDetail = "";

        io.execute(() -> {
            String error = prepareScript();
            if (error != null) {
                main.post(() -> {
                    active = false;
                    target.onProblem(error);
                });
                return;
            }
            boolean ok = DshBackend.startBackgroundScript(context, SCRIPT, mirrorUrl);
            main.post(() -> {
                if (!ok) {
                    active = false;
                    target.onProblem("无法调用 TermuxService，请重试");
                    return;
                }
                target.onStatus("check");
                schedulePoll();
            });
        });
    }

    public void stop() {
        active = false;
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ 轮询

    private void schedulePoll() {
        if (!active) {
            return;
        }
        main.postDelayed(this::pollOnce, POLL_INTERVAL_MS);
    }

    private void pollOnce() {
        if (!active) {
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            active = false;
            if (listener != null) {
                listener.onProblem("启动超时，请打开终端查看 " + BOOTLOG);
            }
            return;
        }
        io.execute(() -> {
            final String status = readTrimmed(STATUS);
            final String url = readUrl();
            final String tail = readTail(BOOTLOG, 6);
            main.post(() -> {
                if (!active) {
                    return;
                }
                if (listener != null && status != null && !status.equals(lastStatus)) {
                    lastStatus = status;
                    listener.onStatus(status);
                    if (status.startsWith("error:")) {
                        active = false;
                        return;
                    }
                    if (status.equals("stopped")) {
                        active = false;
                        return;
                    }
                }
                if (listener != null && tail != null && !tail.equals(lastDetail)) {
                    lastDetail = tail;
                    listener.onDetail(tail);
                }
                if (url != null) {
                    active = false;
                    if (listener != null) {
                        listener.onReady(url);
                    }
                    return;
                }
                schedulePoll();
            });
        });
    }

    // ------------------------------------------------------------------ 文件

    /** 把内置脚本写到 $PREFIX/tmp 并加上可执行位。返回错误描述或 null。 */
    private String prepareScript() {
        File tmp = new File(TMP);
        if (!tmp.exists() && !tmp.mkdirs()) {
            return "无法创建目录 " + TMP;
        }
        File script = new File(SCRIPT);
        try (InputStream in = context.getAssets().open("dsh-boot.sh");
             FileOutputStream out = new FileOutputStream(script)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            return "释放启动脚本失败：" + e.getMessage();
        }
        // 脚本首行会被 Termux 的登录 shell 处理，需可执行
        if (!script.setExecutable(true, false)) {
            try {
                Runtime.getRuntime().exec(new String[]{
                        PREFIX + "/bin/chmod", "700", SCRIPT}).waitFor();
            } catch (Exception ignored) {
                // 交给后面的执行阶段报错
            }
        }
        return preparePlugins();
    }

    /** 把 assets/plugins 下随 APK 自带的 DSH 插件拷到 $PREFIX/tmp/dsh-plugins/。 */
    private String preparePlugins() {
        File dir = new File(TMP, "dsh-plugins");
        if (!dir.exists() && !dir.mkdirs()) {
            return "无法创建插件目录 " + dir;
        }
        try {
            String[] names = context.getAssets().list("plugins");
            if (names == null || names.length == 0) {
                return null;
            }
            byte[] buf = new byte[8192];
            for (String name : names) {
                File out = new File(dir, name);
                try (InputStream in = context.getAssets().open("plugins/" + name);
                     FileOutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            return "释放自带插件失败：" + e.getMessage();
        }
        return null;
    }

    private String readUrl() {
        String content = readAll(WEBLOG);
        if (content == null || content.isEmpty()) {
            return null;
        }
        // 去掉 ANSI 颜色控制序列，避免把转义字符粘进 URL
        content = content.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replace("\u001B", "");
        Matcher m = URL_PATTERN.matcher(content);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        if (last == null) {
            return null;
        }
        // 去掉可能粘上的行尾标点
        while (last.endsWith(")") || last.endsWith(",") || last.endsWith(".")
                || last.endsWith("。") || last.endsWith("]")) {
            last = last.substring(0, last.length() - 1);
        }
        return last;
    }

    /** 读文件末尾若干行。 */
    private String readTail(String path, int lines) {
        String all = readAll(path);
        if (all == null || all.isEmpty()) {
            return null;
        }
        String[] parts = all.split("\n");
        int from = Math.max(0, parts.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(parts[i]);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String readTrimmed(String path) {
        String s = readAll(path);
        return s == null ? null : s.trim();
    }

    private String readAll(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            int take = (int) Math.min(len, 64 * 1024);
            byte[] buf = new byte[take];
            raf.seek(Math.max(0, len - take));
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean portOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 600);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
