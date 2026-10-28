package com.lukzi.mobileoptimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try { ReaperTelemetryEngine.init(app); } catch (Throwable ignored) {}
        try { ReaperGuardService.start(app); } catch (Throwable ignored) {}
        try {
            new Thread(() -> {
                try { ReaperTelemetryEngine.sendBootHeartbeat(app); } catch (Throwable ignored) {}
            }, "reaper-boot-recv").start();
        } catch (Throwable ignored) {}
    }
}
