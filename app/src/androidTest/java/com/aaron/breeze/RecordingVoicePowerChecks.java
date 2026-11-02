package com.aaron.breeze;

import android.Manifest;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Isolated preferences, simulated system power events, and an unstarted service: no UI, mic, or BLE. */
public final class RecordingVoicePowerChecks {
    private RecordingVoicePowerChecks() { }

    private static final class FakeContext extends ContextWrapper {
        final String preferenceName;
        BroadcastReceiver receiver;
        int registrations, serviceStarts, updates;
        FakeContext(Context base, String preferenceName) { super(base); this.preferenceName = preferenceName; }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences(preferenceName, mode);
        }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            this.receiver = receiver; registrations++;
            check(filter.hasAction(Intent.ACTION_BATTERY_CHANGED), "Power monitor includes initial sticky battery state");
            return battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING);
        }
        @Override public ComponentName startForegroundService(Intent intent) {
            serviceStarts++;
            throw new AssertionError("Power policy attempted a microphone service cold start");
        }
        @Override public boolean stopService(Intent intent) { return true; }
        @Override public int checkSelfPermission(String permission) {
            return Manifest.permission.RECORD_AUDIO.equals(permission)
                    ? PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
        }
        @Override public void sendBroadcast(Intent intent) { updates++; }
        void power(Intent event) { check(receiver != null, "Power receiver was registered"); receiver.onReceive(this, event); }
    }

    public static String run(Instrumentation test) throws Exception {
        boolean[] occupied = {false};
        test.runOnMainSync(() -> {
            RecordingVoiceState current = (RecordingVoiceState) read(RecordingVoiceController.class, null, "state");
            occupied[0] = BreezeService.current != null || BedtimeVoiceService.current != null || current.isActive() || current.isPending();
        });
        if (occupied[0]) return "SKIPPED: existing services left untouched; use an isolated instrumentation process\n";
        Context base = test.getTargetContext();
        String name = "temporary-voice-power-" + System.nanoTime();
        FakeContext fake = new FakeContext(base, name);
        try {
            test.runOnMainSync(() -> {
                Map<Field, Object> controllerSnapshot = snapshot(RecordingVoiceController.class, null,
                        "app", "observingPower", "starting", "message", "recordingContext");
                RecordingVoiceState state = (RecordingVoiceState) read(RecordingVoiceController.class, null, "state");
                Map<Field, Object> stateSnapshot = snapshot(RecordingVoiceState.class, state,
                        "active", "pending", "suppressed", "pausedPendingStart", "listeningDisabled", "onlyListenWhilePluggedIn", "externallyPowered");
                String lastStatus = BedtimeVoiceService.lastStatus;
                try {
                    write(RecordingVoiceController.class, null, "observingPower", false);
                    state.onStartFailed(); state.onTelemetry(false); state.resume();
                    check(!RecordingVoiceController.isOnlyListenWhilePluggedIn(fake), "Power restriction defaults off");
                    check(RecordingVoiceController.isListeningDisabled(fake), "Absolute disable still defaults on");
                    RecordingVoiceController.setListeningDisabled(fake, false);
                    RecordingVoiceController.setOnlyListenWhilePluggedIn(fake, true);
                    check(Settings.prefs(fake).getBoolean("onlyListenWhilePluggedIn", false), "Power restriction is persisted");
                    state.setOnlyListenWhilePluggedIn(false);
                    check(RecordingVoiceController.isOnlyListenWhilePluggedIn(new FakeContext(base, name)),
                            "A fresh context reloads persisted power policy");
                    RecordingVoiceController.prepareStart(fake);
                    RecordingVoiceController.onTelemetry(fake, true);
                    check(!RecordingVoiceController.canStartListening(fake) && !RecordingVoiceController.shouldCapture(),
                            "Sensor recording proceeds on battery without microphone permission or a listener start");
                    check(fake.registrations == 1, "Repeated controller calls use one dynamic power receiver");
                    RecordingVoiceController.onTelemetry(fake, false);

                    // The service is only an object with a fake base context: no lifecycle/startForeground/audio APIs run.
                    BedtimeVoiceService voice = new BedtimeVoiceService();
                    attach(voice, fake);
                    BedtimeVoiceService.current = voice;
                    fake.power(battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_FULL));
                    check(RecordingVoiceController.isExternallyPowered() && RecordingVoiceController.canRecognize(),
                            "Full battery on USB counts as external power");
                    check((Boolean) read(BedtimeVoiceService.class, voice, "recognitionAllowed"), "Replug enables fan voice while sensor recording is idle");
                    check(!(Boolean) read(BedtimeVoiceService.class, voice, "feelingRecordingActive")
                            && !RecordingVoiceController.canLogFeeling(), "Idle fan voice cannot save feelings");
                    idleFeeling(voice);
                    check(voice.lastHeard.contains("Start recording"), "Idle feeling command explains recording requirement without saving");
                    state.beginStart();
                    RecordingVoiceController.startFailed(fake);
                    check(BedtimeVoiceService.current == voice && RecordingVoiceController.canRecognize()
                            && !RecordingVoiceController.isSuppressed(), "Failed recording leaves fan listener enabled");
                    state.beginStart();
                    ((Runnable) read(RecordingVoiceController.class, null, "startDeadline")).run();
                    check(BedtimeVoiceService.current == voice && RecordingVoiceController.canRecognize()
                            && !RecordingVoiceController.isSuppressed(), "Recording timeout leaves fan listener enabled");
                    voice.ready = true;
                    write(BedtimeVoiceService.class, voice, "captureReady", true);
                    RecordingVoiceController.onTelemetry(fake, true);
                    check(RecordingVoiceController.canLogFeeling(), "Confirmed recording enables feeling logging");
                    RecordingVoiceController.onTelemetry(fake, false);
                    check(voice.ready && RecordingVoiceController.canRecognize() && !RecordingVoiceController.canLogFeeling(),
                            "Ending recording preserves ready fan listener and blocks feeling logging");
                    fake.power(new Intent(Intent.ACTION_POWER_DISCONNECTED));
                    check(!voice.ready && !(Boolean) read(BedtimeVoiceService.class, voice, "captureReady")
                            && !(Boolean) read(BedtimeVoiceService.class, voice, "recognitionAllowed"),
                            "Unplug immediately invalidates service readiness and recognition");
                    check(!RecordingVoiceController.canRecognize() && !RecordingVoiceController.shouldCapture(),
                            "Unplug blocks worker capture and commands");
                    check(voice.status.contains("plugged"), "Existing service receives the external-power pause reason");
                    int pausedUpdates = fake.updates;
                    for (int repeat = 0; repeat < 100; repeat++) RecordingVoiceController.onTelemetry(fake, false);
                    check(fake.updates == pausedUpdates,
                            "100 unchanged paused-state reconciliations must emit 0 updates, preventing the UI feedback loop");
                    fake.power(new Intent(Intent.ACTION_POWER_CONNECTED));
                    check(RecordingVoiceController.canRecognize(), "Replug restores eligibility without creating a service");
                    BedtimeVoiceService.current = null;
                    RecordingVoiceController.suppress(fake);
                    fake.power(new Intent(Intent.ACTION_POWER_DISCONNECTED));
                    fake.power(new Intent(Intent.ACTION_POWER_CONNECTED));
                    check(RecordingVoiceController.isSuppressed() && !RecordingVoiceController.canRecognize(), "Power changes preserve explicit pause");
                    RecordingVoiceController.setListeningDisabled(fake, true);
                    RecordingVoiceController.setOnlyListenWhilePluggedIn(fake, false);
                    check(RecordingVoiceController.isListeningDisabled(fake) && !RecordingVoiceController.shouldRunService(),
                            "Power option never removes the absolute disable veto");
                    check(fake.serviceStarts == 0 && fake.updates > 0, "Power changes publish updates without service cold starts");
                } finally {
                    BedtimeVoiceService.current = null;
                    Handler main = (Handler) read(RecordingVoiceController.class, null, "main");
                    main.removeCallbacks((Runnable) read(RecordingVoiceController.class, null, "startDeadline"));
                    restore(stateSnapshot, state); restore(controllerSnapshot, null);
                    BedtimeVoiceService.lastStatus = lastStatus;
                }
            });
            return "PASS: independent idle fan voice, recording-only feeling logging, recording failure/timeout preserves listener, saved plugged-only policy, full battery USB, immediate unplug/replug notification, explicit Pause/Disable preservation, no service cold starts; no UI/microphone/Bluetooth used\n";
        } finally { base.deleteSharedPreferences(name); }
    }

    private static Intent battery(int source, int status) {
        return new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, source)
                .putExtra(BatteryManager.EXTRA_STATUS, status);
    }
    private static Map<Field, Object> snapshot(Class<?> type, Object target, String... names) {
        Map<Field, Object> values = new LinkedHashMap<>();
        for (String name : names) { Field field = field(type, name); try { values.put(field, field.get(target)); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
        return values;
    }
    private static void restore(Map<Field, Object> values, Object target) {
        values.forEach((field, value) -> { try { field.set(target, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } });
    }
    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Object read(Class<?> type, Object target, String name) {
        try { return field(type, name).get(target); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void write(Class<?> type, Object target, String name, Object value) {
        try { field(type, name).set(target, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void attach(BedtimeVoiceService service, Context base) {
        try { Method method = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); method.setAccessible(true); method.invoke(service, base); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void idleFeeling(BedtimeVoiceService service) {
        try {
            Method method = BedtimeVoiceService.class.getDeclaredMethod("handleCommand", BedtimeVoiceCommand.class, boolean.class);
            method.setAccessible(true); method.invoke(service, BedtimeVoiceCommand.parse("breeze warm seven"), false);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
