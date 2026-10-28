package com.lukzi.mobileoptimizer;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SecurityGuard {

    private static volatile String sCachedHwid;

    private SecurityGuard() {}

    public static String getDeviceHwid(Context context) {
        if (sCachedHwid != null) return sCachedHwid;
        synchronized (SecurityGuard.class) {
            if (sCachedHwid != null) return sCachedHwid;
            sCachedHwid = build(context);
            return sCachedHwid;
        }
    }

    private static String build(Context context) {
        StringBuilder sb = new StringBuilder(256);
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            sb.append(safe(androidId));
        } catch (Throwable ignored) {}
        sb.append('|').append(safe(Build.BOARD));
        sb.append('|').append(safe(Build.BRAND));
        sb.append('|').append(safe(Build.DEVICE));
        sb.append('|').append(safe(Build.HARDWARE));
        sb.append('|').append(safe(Build.MODEL));
        sb.append('|').append(safe(Build.PRODUCT));
        sb.append('|').append(safe(Build.MANUFACTURER));
        sb.append('|').append(Build.VERSION.SDK_INT);
        return sha256(sb.toString());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return TextUtils.isEmpty(input) ? "DEV_UNKNOWN"
                    : String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
