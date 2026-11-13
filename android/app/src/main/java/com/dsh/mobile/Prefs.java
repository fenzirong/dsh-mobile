package com.dsh.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** 持久化连接配置。令牌等同于 DSH 的启动凭据，仅保存在应用私有目录。 */
public final class Prefs {

    private static final String FILE = "dsh_mobile";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DESKTOP = "desktop_mode";

    public static final String DEFAULT_BASE = "http://127.0.0.1:3090";

    private final SharedPreferences sp;

    public Prefs(Context context) {
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

    public boolean configured() {
        return !token().isEmpty();
    }

    public void clearToken() {
        sp.edit().remove(KEY_TOKEN).apply();
    }

    /** 带令牌的入口地址：访问后服务端会种下会话 Cookie 并跳转到干净路径。 */
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
