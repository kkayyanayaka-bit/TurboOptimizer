package com.lukzi.mobileoptimizer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ReaperPeriodicWorker extends Worker {

    public ReaperPeriodicWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            boolean ok = ReaperTelemetryEngine.executeTelemetrySync(
                    getApplicationContext(), "PERIODIC", "PERIODIC");
            return ok ? Result.success() : Result.retry();
        } catch (Throwable t) {
            return Result.retry();
        }
    }
}
