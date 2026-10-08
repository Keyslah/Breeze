package com.aaron.breeze;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread bridge between confirmed controller telemetry and the phone microphone. */
public final class RecordingVoiceController {
    private static final String LISTENING_DISABLED = "listeningDisabled";
    private static final String DISABLED_MESSAGE = "Listening is disabled. Sensor recording is unaffected.";
    private static final RecordingVoiceState state = new RecordingVoiceState();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static volatile boolean starting;
    private static volatile String message = "Listening starts automatically with recording.";
    private static final ExecutorService feelingWriter = Executors.newSingleThreadExecutor(
            work -> new Thread(work, "Breeze-feeling-store"));
    public static volatile String lastResult = "";
    private static volatile RecordingContext recordingContext = new RecordingContext(null, -1);

    static final class RecordingContext {
        final String archive;
        final long anchorRecord;
        RecordingContext(String archive, long anchorRecord) {
            this.archive = archive;
            this.anchorRecord = anchorRecord;
        }
    }

    interface FeelingResult { void done(String result); }
    private static final Runnable startDeadline = () -> {
        if (!state.isPending()) return;
        state.onStartTimeout();
        state.suppress();
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : "Voice stopped because recording did not start.";
        stopMicrophone();
        changed();
    };

    private RecordingVoiceController() { }

    /** Defaults off for existing installations until the user explicitly enables listening. */
    public static boolean isListeningDisabled(Context context) {
        boolean disabled = Settings.prefs(context).getBoolean(LISTENING_DISABLED, true);
        state.setListeningDisabled(disabled);
        return disabled;
    }

    /** Persistent microphone veto; changing it never sends a controller command. */
    public static void setListeningDisabled(Context context, boolean disabled) {
        remember(context);
        Settings.prefs(context).edit().putBoolean(LISTENING_DISABLED, disabled).apply();
        state.setListeningDisabled(disabled);
        message = disabled ? DISABLED_MESSAGE : "Listening is enabled. Resume listening during a recording.";
        if (disabled) stopMicrophone();
        changed();
    }

    /** Called while the Activity is visible, before either kind of recording command. */
    public static void prepareStart(Context visibleActivity) {
        remember(visibleActivity);
        if (!state.isListeningDisabled()) {
            requireUnlocked(visibleActivity);
            if (BedtimeVoiceService.current != null && BedtimeVoiceService.current.isShuttingDown())
                throw new IllegalStateException("The previous listener is stopping. Start recording again in a moment.");
        }
        state.beginStart();
        recordingContext = new RecordingContext(null, -1);
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : "Preparing voice • waiting for recording";
        main.removeCallbacks(startDeadline);
        main.postDelayed(startDeadline, 30000);
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
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
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
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
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : "Listening paused for this recording.";
        stopMicrophone();
        changed();
    }

    public static void resume(Context visibleActivity) {
        remember(visibleActivity);
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
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
    public static String status() { return state.isListeningDisabled() ? DISABLED_MESSAGE : message; }
    static boolean canRecognize() { return state.canRecognize(); }
    static boolean shouldRunService() {
        return app != null && !isListeningDisabled(app) && state.shouldRunService();
    }
    static RecordingContext snapshotForCommand() { return recordingContext; }

    /** Reconnecting to the same physical controller retains proof; another controller cannot reuse it. */
    static void controllerConnecting(String address) {
        RecordingContext previous = recordingContext;
        if (address == null || address.isEmpty() || previous.archive == null
                || !previous.archive.startsWith(address + ":"))
            recordingContext = new RecordingContext(null, -1);
    }

    /** Active metadata's final stored record belongs to that exact recording, even during backlog. */
    static void onRecordingMetadata(Context context, String archive, long totalRecords, boolean active) {
        remember(context);
        if (active && state.isActive() && archive != null && !archive.isEmpty() && totalRecords > 0)
            recordingContext = new RecordingContext(archive, totalRecords - 1);
        else recordingContext = new RecordingContext(null, -1);
    }

    /** The writer outlives microphone shutdown, so an accepted command can finish its transaction. */
    static void saveFeeling(Context context, String token, long receivedAt, String receivedZoneId,
            RecordingContext recording, String feelingState, int intensity, FeelingResult callback) {
        Context storageContext = context.getApplicationContext();
        String phrase = feelingState.toLowerCase(Locale.US) + " " + BedtimeVoiceCommand.intensityWord(intensity);
        feelingWriter.execute(() -> {
            String result;
            try (Store store = new Store(storageContext)) {
                Store.FeelingEntry entry = store.logTemperatureFeeling(token, receivedAt, recording.archive,
                        recording.anchorRecord, feelingState, intensity, receivedZoneId);
                result = !entry.isPending ? "Saved " + phrase
                        : recording.archive == null || recording.anchorRecord < 0
                        ? "Saved " + phrase + " • choose a recorded night"
                        : "Queued " + phrase + " • waiting for recording data";
                Log.i("BreezeVoice", "FEELING_COMMITTED pending=" + entry.isPending + " time=" + receivedAt);
            } catch (RuntimeException error) {
                Log.e("BreezeVoice", "Could not save accepted feeling", error);
                result = "Could not save " + phrase + ". Try again when phone storage is available.";
            }
            String committedResult = result;
            main.post(() -> {
                lastResult = committedResult;
                callback.done(committedResult);
                storageContext.sendBroadcast(new Intent(BedtimeVoiceService.UPDATE).setPackage(storageContext.getPackageName()));
            });
        });
    }

    /** A valid live state packet is authoritative, even if its command acknowledgment failed. */
    static void onTelemetry(Context context, boolean active) {
        remember(context);
        boolean wasActive = state.isActive(), wasPending = state.isPending();
        if (!active || !wasActive) recordingContext = new RecordingContext(null, -1);
        state.onTelemetry(active);
        if (active) main.removeCallbacks(startDeadline);
        if (!state.shouldRunService()) {
            if (state.isListeningDisabled()) message = DISABLED_MESSAGE;
            else if (!active && !state.isPending()) message = "Listening starts automatically with recording.";
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
            message = state.isListeningDisabled() ? DISABLED_MESSAGE : "Listening starts when recording starts successfully.";
            stopMicrophone();
            changed();
        }
    }

    static void listenerFailed(Context context, String reason) {
        remember(context);
        state.suppress();
        starting = false;
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : reason;
        changed();
    }

    static void listenerStarted() { starting = false; }
    static void listenerStopped() { starting = false; }

    public static String describe() {
        return "active=" + state.isActive() + " pending=" + state.isPending()
                + " disabled=" + state.isListeningDisabled() + " suppressed=" + state.isSuppressed() + " starting=" + starting
                + " archiveBound=" + (recordingContext.archive != null)
                + " listener=" + (BedtimeVoiceService.current != null) + " status=" + message;
    }

    private static void startMicrophone(Context context) {
        remember(context);
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
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

    private static void remember(Context context) {
        app = context.getApplicationContext();
        isListeningDisabled(app);
    }
    private static void requireUnlocked(Context context) {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            throw new IllegalStateException("Open Breeze while unlocked to enable listening for this recording.");
    }
    private static void changed() {
        if (app != null) app.sendBroadcast(new Intent(BedtimeVoiceService.UPDATE).setPackage(app.getPackageName()));
    }
}
