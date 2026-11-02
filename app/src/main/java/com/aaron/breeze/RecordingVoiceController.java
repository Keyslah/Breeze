package com.aaron.breeze;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread bridge between confirmed controller telemetry and the phone microphone. */
public final class RecordingVoiceController {
    private static final String LISTENING_DISABLED = "listeningDisabled";
    private static final String ONLY_LISTEN_WHILE_PLUGGED_IN = "onlyListenWhilePluggedIn";
    private static final String DISABLED_MESSAGE = "Listening is disabled. Sensor recording is unaffected.";
    private static final String POWER_BLOCKED_MESSAGE = "Listening paused until the phone is plugged into power. Sensor recording is unaffected.";
    private static final RecordingVoiceState state = new RecordingVoiceState();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean observingPower;
    private static volatile boolean starting;
    private static volatile String message = "Enable listening in Breeze to use voice controls.";
    private static final ExecutorService feelingWriter = Executors.newSingleThreadExecutor(
            work -> new Thread(work, "Breeze-feeling-store"));
    public static volatile String lastResult = "";
    private static volatile RecordingContext recordingContext = new RecordingContext(null, -1);
    private static final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))
                externalPowerChanged(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
            else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) externalPowerChanged(0);
            else if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) externalPowerChanged(1);
        }
    };

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
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : state.isSuppressed()
                ? "Listening paused. Tap Resume listening." : "Recording did not start. Voice controls remain enabled.";
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
        message = disabled ? DISABLED_MESSAGE : "Listening is enabled. Open Breeze to start voice controls.";
        if (disabled) stopMicrophone();
        changed();
    }

    /** Optional battery-saving policy; existing installations keep their current behavior. */
    public static boolean isOnlyListenWhilePluggedIn(Context context) {
        remember(context);
        return state.isOnlyListenWhilePluggedIn();
    }

    public static void setOnlyListenWhilePluggedIn(Context context, boolean required) {
        remember(context);
        Settings.prefs(context).edit().putBoolean(ONLY_LISTEN_WHILE_PLUGGED_IN, required).apply();
        state.setOnlyListenWhilePluggedIn(required);
        powerPolicyChanged();
    }

    public static boolean isPowerBlocked(Context context) { remember(context); return state.isPowerBlocked(); }
    public static boolean isPowerBlocked() { return state.isPowerBlocked(); }
    public static boolean isExternallyPowered() { return state.isExternallyPowered(); }
    public static boolean canStartListening(Context context) {
        remember(context);
        return !state.isListeningDisabled() && !state.isPowerBlocked();
    }

    /** Called while the Activity is visible, before either kind of recording command. */
    public static void prepareStart(Context visibleActivity) {
        remember(visibleActivity);
        if (!state.isListeningDisabled() && !state.isPowerBlocked()) {
            requireUnlocked(visibleActivity);
            if (BedtimeVoiceService.current != null && BedtimeVoiceService.current.isShuttingDown())
                throw new IllegalStateException("The previous listener is stopping. Start recording again in a moment.");
        }
        state.beginStart();
        recordingContext = new RecordingContext(null, -1);
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : state.isPowerBlocked()
                ? POWER_BLOCKED_MESSAGE : "Preparing voice controls…";
        main.removeCallbacks(startDeadline);
        main.postDelayed(startDeadline, 30000);
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
        if (state.isPowerBlocked()) { powerPolicyChanged(); return; }
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
        RecordingIntent intent = PhoneRecording.get(visibleActivity).state();
        BreezeService controller = BreezeService.current;
        if (intent.known) onPhoneRecording(visibleActivity, intent);
        else if (controller != null && controller.fresh()) onTelemetry(visibleActivity, controller.latest.active());
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
        if (state.isPowerBlocked()) return;
        if (visibleActivity.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        if (visibleActivity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message = "Allow microphone access to enable listening.";
            return;
        }
        if (state.shouldStart(true, starting || BedtimeVoiceService.current != null)) {
            try { startMicrophone(visibleActivity); }
            catch (RuntimeException ignored) { /* Failure is already exposed as voice status. */ }
        }
    }

    public static void suppress(Context context) {
        remember(context);
        state.suppress();
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : "Listening paused. Tap Resume listening.";
        stopMicrophone();
        changed();
    }

    public static void resume(Context visibleActivity) {
        remember(visibleActivity);
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
        requireUnlocked(visibleActivity);
        state.resume();
        if (state.isPowerBlocked()) { powerPolicyChanged(); return; }
        message = "Preparing voice controls…";
        startMicrophone(visibleActivity);
    }

    public static boolean isSuppressed() { return state.isSuppressed(); }
    public static boolean isStarting() { return state.isPending(); }
    public static String status() { return state.isListeningDisabled() ? DISABLED_MESSAGE
            : state.isPowerBlocked() && !state.isSuppressed() ? POWER_BLOCKED_MESSAGE : message; }
    static boolean canRecognize() { return state.canRecognize(); }
    static boolean canLogFeeling() { return state.canLogFeeling(); }
    static boolean shouldCapture() { return state.shouldCapture(); }
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

    static void onPhoneRecording(Context context, RecordingIntent intent) {
        if (intent.known) updateRecordingState(context, intent.active);
    }

    /** Hardware may confirm legacy recordings; it never overrides a phone Start/Stop. */
    static void onTelemetry(Context context, boolean active) {
        RecordingIntent intent = PhoneRecording.get(context).state();
        if (!active) recordingContext = new RecordingContext(null, -1);
        updateRecordingState(context, intent.known ? intent.active : active);
    }
    private static void updateRecordingState(Context context, boolean active) {
        remember(context);
        boolean wasActive = state.isActive(), wasPending = state.isPending();
        if (!active || !wasActive) recordingContext = new RecordingContext(null, -1);
        state.onTelemetry(active);
        if (active) main.removeCallbacks(startDeadline);
        if (!state.shouldRunService()) {
            if (state.isListeningDisabled()) message = DISABLED_MESSAGE;
            stopMicrophone();
        } else if (BedtimeVoiceService.current != null) {
            BedtimeVoiceService.current.recordingStateChanged();
        } else if (!starting) {
            message = state.isPowerBlocked() ? POWER_BLOCKED_MESSAGE : "Open Breeze to start voice controls.";
        }
        if (wasActive != active || wasPending != state.isPending()) changed();
    }

    static void startFailed(Context context) {
        remember(context);
        if (!state.isPending()) return;
        state.onStartFailed();
        main.removeCallbacks(startDeadline);
        message = state.isListeningDisabled() ? DISABLED_MESSAGE : state.isSuppressed()
                ? "Listening paused. Tap Resume listening." : "Recording did not start. Voice controls remain enabled.";
        changed();
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
                + " pluggedOnly=" + state.isOnlyListenWhilePluggedIn() + " powered=" + state.isExternallyPowered()
                + " archiveBound=" + (recordingContext.archive != null)
                + " listener=" + (BedtimeVoiceService.current != null) + " status=" + message;
    }

    private static void startMicrophone(Context context) {
        remember(context);
        if (state.isListeningDisabled()) { stopMicrophone(); return; }
        if (state.isPowerBlocked()) { powerPolicyChanged(); return; }
        if (BedtimeVoiceService.current != null) {
            // Its destruction broadcasts an update; only a still-visible Activity may retry.
            if (!BedtimeVoiceService.current.isShuttingDown())
                BedtimeVoiceService.current.recordingStateChanged();
            return;
        }
        if (starting || !state.shouldRunService()) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.suppress();
            message = "Allow microphone access to enable listening.";
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
        state.setOnlyListenWhilePluggedIn(Settings.prefs(app).getBoolean(ONLY_LISTEN_WHILE_PLUGGED_IN, false));
        if (!observingPower) {
            // One process-lifetime receiver; no wake lock, timer, or microphone service cold start.
            observingPower = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            Intent battery = app.registerReceiver(powerReceiver, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
            state.setExternalPowerSource(battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
        }
    }

    private static void externalPowerChanged(int plugged) {
        boolean wasPowered = state.isExternallyPowered();
        // Charging status is deliberately ignored: a full battery can still be plugged in.
        state.setExternalPowerSource(plugged);
        if (wasPowered != state.isExternallyPowered()) powerPolicyChanged();
    }

    private static void powerPolicyChanged() {
        if (state.isListeningDisabled()) {
            message = DISABLED_MESSAGE;
            stopMicrophone();
        } else {
            if (state.isPowerBlocked() && !state.isSuppressed()) message = POWER_BLOCKED_MESSAGE;
            else if (state.isSuppressed()) message = "Listening paused. Tap Resume listening.";
            else if (BedtimeVoiceService.current == null) message = "Open Breeze to start voice controls.";
            // A previously eligible foreground service may resume capture; broadcasts never create one.
            if (BedtimeVoiceService.current != null) BedtimeVoiceService.current.recordingStateChanged();
        }
        changed();
    }
    private static void requireUnlocked(Context context) {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            throw new IllegalStateException("Open Breeze while unlocked to enable voice controls.");
    }
    private static void changed() {
        if (app != null) app.sendBroadcast(new Intent(BedtimeVoiceService.UPDATE).setPackage(app.getPackageName()));
    }
}
