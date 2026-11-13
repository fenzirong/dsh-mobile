package com.dsh.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** 合并版（内置 Termux）使用的连接配置。 */
public final class DshPrefs {

    private static final String FILE = "dsh_mobile";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DESKTOP = "desktop_mode";
    private static final String KEY_MIRROR = "mirror_url";

    public static final String DEFAULT_BASE = "http://127.0.0.1:3090";

    private final SharedPreferences sp;

    public DshPrefs(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        String v = sp.getString(KEY_BASE, DEFAULT_BASE);
        return v == null || v.isEmpty() ? DEFAULT_BASE : v;
    }

    public void setBaseUrl(String value) {
        sp.edit().putString(KEY_BASE, normalizeBase(value)).apply();
    }

    public String token() {
        String v = sp.getString(KEY_TOKEN, "");
        return v == null ? "" : v;
    }

    public void setToken(String value) {
        sp.edit().putString(KEY_TOKEN, value == null ? "" : value.trim()).apply();
    }

    public boolean desktopMode() {
        return sp.getBoolean(KEY_DESKTOP, true);
    }

    public void setDesktopMode(boolean value) {
        sp.edit().putBoolean(KEY_DESKTOP, value).apply();
    }

    public String mirrorUrl() {
        String v = sp.getString(KEY_MIRROR, "");
        return v == null ? "" : v;
    }

    public void setMirrorUrl(String value) {
        sp.edit().putString(KEY_MIRROR, value == null ? "" : value).apply();
    }

    public boolean configured() {
        return !token().isEmpty();
    }

    public String entryUrl() {
        String base = normalizeBase(baseUrl());
        String t = token();
        if (t.isEmpty()) {
            return base + "/";
        }
        return base + "/?token=" + Uri.encode(t);
    }

    public static String normalizeBase(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return DEFAULT_BASE;
        }
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
