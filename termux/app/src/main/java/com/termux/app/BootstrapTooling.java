package com.termux.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 保证 {@code $PREFIX} 里的「前缀改写工具链」与当前 APK 一致。
 *
 * <p>bootstrap 只在**首次安装**时解压，升级 APK 不会刷新 {@code $PREFIX}。
 * 因此从旧版本升级上来的用户，其 prefix 里可能缺少 {@code dshfix} / {@code dsh-fixdeb} /
 * apt 钩子配置，或者还是旧版实现。后果是 apt 装包时 dpkg 拿到未改写前缀的 .deb，
 * 报出 {@code unable to execute ... preinst: Permission denied} 这类看不懂的错误。
 *
 * <p>这里按 versionCode 打标记：版本变了就把这三个文件从内嵌 bootstrap 里重新解出来。
 */
final class BootstrapTooling {

    private static final String TAG = "DshTooling";

    /** 需要随 APK 版本刷新的文件（相对 $PREFIX）。 */
    private static final String[] FILES = {
            "bin/dshfix",
            "bin/dsh-fixdeb",
            "etc/apt/apt.conf.d/99dsh-prefix",
    };

    private static final String MARKER = "etc/dsh-tooling.version";

    private BootstrapTooling() {
    }

    static void ensure(Context context) {
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!prefix.isDirectory()) {
            // bootstrap 还没解压，TermuxInstaller 会一次性装好全部内容
            return;
        }

        int versionCode;
        try {
            versionCode = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }

        File marker = new File(prefix, MARKER);
        if (marker.isFile() && String.valueOf(versionCode).equals(readText(marker))) {
            return;
        }

        try {
            byte[] zip = TermuxInstaller.loadZipBytes();
            int written = extract(zip, prefix);
            writeText(marker, String.valueOf(versionCode));
            Log.i(TAG, "工具链已刷新，写入 " + written + " 个文件");
        } catch (Throwable t) {
            Log.w(TAG, "刷新工具链失败", t);
        }
    }

    private static int extract(byte[] zip, File prefix) throws IOException {
        int written = 0;
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (!isWanted(name) || entry.isDirectory()) {
                    continue;
                }
                File out = new File(prefix, name);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    continue;
                }
                try (FileOutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                // bin/ 下的必须是可执行；apt 配置保持普通文件
                out.setExecutable(name.startsWith("bin/"), false);
                written++;
            }
        }
        return written;
    }

    private static boolean isWanted(String name) {
        for (String f : FILES) {
            if (f.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String readText(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8").trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(text.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "写标记失败", e);
        }
    }
}
