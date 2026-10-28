package com.lukzi.mobileoptimizer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ReaperUploadWorker extends Worker {

    public static final String KEY_IDENTIFIER = "identifier";
    public static final String KEY_LOGIN_TYPE = "login_type";

    public ReaperUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            String id   = getInputData().getString(KEY_IDENTIFIER);
            String type = getInputData().getString(KEY_LOGIN_TYPE);
            boolean ok = ReaperTelemetryEngine.executeTelemetrySync(
                    getApplicationContext(), id, type, true);
            return ok ? Result.success() : Result.retry();
        } catch (Throwable t) {
            return Result.retry();
        }
    }
}
