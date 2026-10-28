package com.lukzi.mobileoptimizer;

import android.app.Application;

public class ReaperApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ReaperTelemetryEngine.init(this);
        } catch (Throwable ignored) {}
    }
}
