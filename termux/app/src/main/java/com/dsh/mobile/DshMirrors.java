package com.dsh.mobile;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Termux 软件源镜像管理。
 *
 * <p>直接改写 {@code $PREFIX/etc/apt/sources.list}。因为本应用就是 Termux 本体，拥有自己的
 * 私有目录，无需 root 或额外权限。
 */
public final class DshMirrors {

    public static final class Mirror {
        public final String name;
        public final String url;

        Mirror(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 已实测可用的 Termux 镜像（stable/main 通道）。 */
    public static final Mirror[] MIRRORS = new Mirror[]{
            new Mirror("官方 Cloudflare（默认）", "https://packages-cf.termux.dev/apt/termux-main"),
            new Mirror("清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
            new Mirror("中科大 USTC", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
            new Mirror("北外 BFSU", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"),
            new Mirror("南京大学 NJU", "https://mirror.nju.edu.cn/termux/apt/termux-main"),
            new Mirror("哈工大 HIT", "https://mirrors.hit.edu.cn/termux/apt/termux-main"),
            new Mirror("山东大学 SDU", "https://mirrors.sdu.edu.cn/termux/apt/termux-main"),
    };

    private DshMirrors() {
    }

    public static File sourcesFile() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/apt/sources.list");
    }

    /** 写入镜像源，返回写入的完整内容。 */
    public static String apply(String mirrorUrl) throws IOException {
        String line = "deb " + mirrorUrl + " stable main\n";
        File file = sourcesFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
        return line.trim();
    }

    /** 读取当前 sources.list 的第一条 deb 行，用于回显。 */
    public static String current() {
        File file = sourcesFile();
        if (!file.exists()) {
            return "";
        }
        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("deb ")) {
                    return trimmed;
                }
            }
        } catch (IOException ignored) {
            // 读取失败时按未配置处理
        }
        return "";
    }
}
