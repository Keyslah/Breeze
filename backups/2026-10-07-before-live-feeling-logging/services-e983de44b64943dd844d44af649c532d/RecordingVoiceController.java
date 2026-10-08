package com.aaron.breeze;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Main-thread bridge between confirmed controller telemetry and the phone microphone. */
public final class RecordingVoiceController {
    private static final RecordingVoiceState state = new RecordingVoiceState();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static volatile boolean starting;
    private static volatile String message = "Listening starts automatically with recording.";
    private static final Runnable startDeadline = () -> {
        if (!state.isPending()) return;
        state.onStartTimeout();
        state.suppress();
        message = "Voice stopped because recording did not start.";
        stopMicrophone();
        changed();
    };

    private RecordingVoiceController() { }

    /** Called while the Activity is visible, before either kind of recording command. */
    public static void prepareStart(Context visibleActivity) {
        remember(visibleActivity);
        requireUnlocked(visibleActivity);
        if (BedtimeVoiceService.current != null && BedtimeVoiceService.current.isShuttingDown())
            throw new IllegalStateException("The previous listener is stopping. Start recording again in a moment.");
        state.beginStart();
        message = "Preparing voice • waiting for recording";
        main.removeCallbacks(startDeadline);
        main.postDelayed(startDeadline, 30000);
        try { startMicrophone(visibleActivity); }
        catch (RuntimeException error) {
            state.onStartFailed();
            main.removeCallbacks(startDeadline);
            changed();
            throw error;
        }
    }

    /** Only an unlocked, resumed Activity calls this; BLE callbacks never start a service. */
    public static void reconcile(Context visibleActivity) {
        remember(visibleActivity);
        BreezeService controller = BreezeService.current;
        if (controller == null || !controller.fresh()) return;
        onTelemetry(visibleActivity, controller.latest.active());
        if (visibleActivity.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        if (state.shouldStart(true, starting || BedtimeVoiceService.current != null)) {
            try { startMicrophone(visibleActivity); }
            catch (RuntimeException ignored) { /* Failure is already exposed as voice status. */ }
        }
    }

    public static void suppress(Context context) {
        remember(context);
        state.suppress();
        message = "Listening paused for this recording.";
        stopMicrophone();
        changed();
    }

    public static void resume(Context visibleActivity) {
        remember(visibleActivity);
        requireUnlocked(visibleActivity);
        BreezeService controller = BreezeService.current;
        if (controller == null || !controller.fresh() || !controller.latest.active())
            throw new IllegalStateException("Wait for confirmation that the controller is recording.");
        state.onTelemetry(true);
        state.resume();
        message = "Preparing voice for the current recording…";
        startMicrophone(visibleActivity);
    }

    public static boolean isSuppressed() { return state.isSuppressed(); }
    public static boolean isStarting() { return state.isPending(); }
    public static String status() { return message; }
    static boolean canRecognize() { return state.canRecognize(); }
    static boolean shouldRunService() { return state.shouldRunService(); }

    /** A valid live state packet is authoritative, even if its command acknowledgment failed. */
    static void onTelemetry(Context context, boolean active) {
        remember(context);
        boolean wasActive = state.isActive(), wasPending = state.isPending();
        state.onTelemetry(active);
        if (active) main.removeCallbacks(startDeadline);
        if (!state.shouldRunService()) {
            if (!active && !state.isPending()) message = "Listening starts automatically with recording.";
            stopMicrophone();
        } else if (BedtimeVoiceService.current != null) {
            BedtimeVoiceService.current.recordingStateChanged();
        } else if (active && !starting) {
            message = "Recording • open Breeze to enable listening.";
        }
        if (wasActive != active || wasPending != state.isPending()) changed();
    }

    static void startFailed(Context context) {
        remember(context);
        if (!state.isPending()) return;
        state.onStartFailed();
        main.removeCallbacks(startDeadline);
        if (!state.isActive()) {
            message = "Listening starts when recording starts successfully.";
            stopMicrophone();
            changed();
        }
    }

    static void listenerFailed(Context context, String reason) {
        remember(context);
        state.suppress();
        starting = false;
        message = reason;
        changed();
    }

    static void listenerStarted() { starting = false; }
    static void listenerStopped() { starting = false; }

    public static String describe() {
        return "active=" + state.isActive() + " pending=" + state.isPending()
                + " suppressed=" + state.isSuppressed() + " starting=" + starting
                + " listener=" + (BedtimeVoiceService.current != null) + " status=" + message;
    }

    private static void startMicrophone(Context context) {
        if (BedtimeVoiceService.current != null) {
            // Its destruction broadcasts an update; only a still-visible Activity may retry.
            if (!BedtimeVoiceService.current.isShuttingDown())
                BedtimeVoiceService.current.recordingStateChanged();
            return;
        }
        if (starting || !state.shouldRunService()) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.suppress();
            message = "Allow microphone access to enable listening during recording.";
            changed();
            throw new SecurityException(message);
        }
        starting = true;
        try {
            context.startForegroundService(new Intent(context, BedtimeVoiceService.class));
            Log.i("BreezeVoice", "ARM " + describe());
        } catch (RuntimeException error) {
            starting = false;
            state.suppress();
            message = "Listening could not start. Open Breeze and tap Resume listening.";
            changed();
            throw error;
        }
    }

    private static void stopMicrophone() {
        if (BedtimeVoiceService.current != null) BedtimeVoiceService.current.recordingStateChanged();
        if (app != null && (starting || BedtimeVoiceService.current != null))
            app.stopService(new Intent(app, BedtimeVoiceService.class));
        starting = false;
    }

    private static void remember(Context context) { app = context.getApplicationContext(); }
    private static void requireUnlocked(Context context) {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            throw new IllegalStateException("Open Breeze while unlocked to enable listening for this recording.");
    }
    private static void changed() {
        if (app != null) app.sendBroadcast(new Intent(BedtimeVoiceService.UPDATE).setPackage(app.getPackageName()));
    }
}
