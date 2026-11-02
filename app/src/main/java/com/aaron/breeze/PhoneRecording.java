package com.aaron.breeze;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One phone-owned recording per database; all durable work stays off the UI/BLE thread. */
final class PhoneRecording {
    private static final ConcurrentHashMap<String, PhoneRecording> instances = new ConcurrentHashMap<>();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "Breeze-recording-store"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private volatile RecordingIntent state = RecordingIntent.UNKNOWN;
    private volatile boolean loaded, busy;
    private volatile String error = "";

    static PhoneRecording get(Context context) {
        return instances.computeIfAbsent(context.getDatabasePath("breeze.db").getAbsolutePath(), key -> new PhoneRecording(context));
    }
    private PhoneRecording(Context source) {
        context = source.getApplicationContext() == null ? source : source.getApplicationContext();
        writer.execute(() -> {
            try (Store store = new Store(context)) { state = store.recordingIntent(); loaded = true; }
            catch (RuntimeException failure) { error = "Could not read recording state: " + failure.getMessage(); }
            main.post(this::changed);
        });
    }
    RecordingIntent state() { return state; }
    boolean ready() { return loaded && !busy; }
    String error() { return error; }
    interface Result { void done(String error); }
    void setActive(boolean active, Result result) {
        if (!ready()) return;
        busy = true;
        final long pressedAt = System.currentTimeMillis();
        writer.execute(() -> {
            String failureMessage = "";
            RecordingIntent committed = state;
            try (Store store = new Store(context)) { committed = store.setPhoneRecording(active, pressedAt); error = ""; }
            catch (RuntimeException failure) { failureMessage = "Could not save recording state: " + failure.getMessage(); error = failureMessage; }
            String failure = failureMessage;RecordingIntent saved = committed;
            main.post(() -> { state = saved; busy = false; try { result.done(failure); } finally { changed(); } });
        });
    }
    private void changed() {
        RecordingVoiceController.onPhoneRecording(context, state);
        BreezeService controller = BreezeService.current;
        if (controller != null) controller.phoneRecordingChanged(this);
        context.sendBroadcast(new Intent(BreezeService.UPDATE).setPackage(context.getPackageName()));
    }
}
