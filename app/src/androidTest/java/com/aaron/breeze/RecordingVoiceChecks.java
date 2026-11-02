package com.aaron.breeze;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** Real Activity/microphone lifecycle with fake telemetry; never sends Bluetooth or writes records. */
public final class RecordingVoiceChecks {
    private RecordingVoiceChecks() { }

    private static final class FakeController extends BreezeService {
        int starts;
        FakeController() {
            ready = true;
            sensorFirmware = true;
            latest = new Protocol.Reading();
            latest.flags = 128;
            latest.probe = 31.3;
            latest.room = 23.1;
            latest.humidity = 58.2;
        }
        @Override public boolean fresh() { return ready && latest != null; }
        @Override public void startSession() { starts++; }
        @Override public void changed() { }
        @Override public void scan() { throw new AssertionError("Unexpected Bluetooth scan"); }
        @Override public void connect(String address) { throw new AssertionError("Unexpected Bluetooth connection"); }
        @Override public void command(IntFunction<byte[]> make, Result done) { throw new AssertionError("Unexpected Bluetooth command"); }
        @Override public void control(int action, int value) { throw new AssertionError("Unexpected controller mutation"); }
        @Override public void download() { throw new AssertionError("Unexpected controller transfer"); }
        @Override public void startContactTest() { throw new AssertionError("Unexpected probe command"); }
        @Override public boolean fanCommand(int action, int value, int argument) { throw new AssertionError("Unexpected fan command"); }
    }

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            return "SKIPPED: unlock the phone before independent voice lifecycle checks\n";
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "SKIPPED: allow Breeze microphone access before independent voice lifecycle checks\n";
        boolean[] occupied = {false};
        test.runOnMainSync(() -> occupied[0] = BreezeService.current != null || BedtimeVoiceService.current != null);
        if (occupied[0])
            return "SKIPPED: existing app services were left untouched; run in an isolated instrumentation process\n";

        FakeController fake = new FakeController();
        boolean wasDisabled = RecordingVoiceController.isListeningDisabled(context);
        boolean wasPluggedOnly = RecordingVoiceController.isOnlyListenWhilePluggedIn(context);
        boolean hadPluggedOnly = Settings.prefs(context).contains("onlyListenWhilePluggedIn");
        Activity activity = null;
        StringBuilder evidence = new StringBuilder();
        test.runOnMainSync(() -> {
            check(BreezeService.current == null && BedtimeVoiceService.current == null, "Existing app services must remain untouched");
            BreezeService.current = fake;
            RecordingVoiceController.startFailed(context);
            RecordingVoiceController.onTelemetry(context, false);
            RecordingVoiceController.setOnlyListenWhilePluggedIn(context, false);
            RecordingVoiceController.setListeningDisabled(context, false);
        });
        try {
            activity = launch(test, context);
            Activity first = activity;
            test.runOnMainSync(() -> RecordingVoiceController.resume(first));
            awaitReady(test);
            test.runOnMainSync(() -> {
                check(!fake.latest.active(), "No sensor recording is needed to start listening");
                check(RecordingVoiceController.canRecognize(), "Idle listener accepts fan commands");
                check(!RecordingVoiceController.canLogFeeling(), "Idle listener cannot save warm/cold feelings");
            });
            evidence.append("Enabled listening starts while sensors are idle; fan commands are eligible and feeling saves are gated.\n");
            Thread firstWorker = voiceWorker(test);
            test.runOnMainSync(() -> {
                TextView start = find(first.getWindow().getDecorView(), "Start recording");
                check(start != null && start.isEnabled(), "Home recording control is available");
                start.performClick();
                check(fake.starts == 1, "Home start routes only to fake controller");
                check(RecordingVoiceController.isStarting(), "Recording awaits controller confirmation");
                RecordingVoiceController.onTelemetry(context, false);
                check(RecordingVoiceController.isStarting(), "Idle packet retains pending start");
                check(RecordingVoiceController.canRecognize(), "Pending recording keeps fan commands available");
                check(!RecordingVoiceController.canLogFeeling(), "Pending recording cannot save feelings");
                check(BedtimeVoiceService.current.ready, "Recording startup preserves microphone readiness");
                check(RecordingVoiceController.isStarting(), "Preparation does not imply active recording");
                telemetry(context, fake, true);
                check(RecordingVoiceController.canLogFeeling(), "Confirmed recording enables feeling saves");
            });
            awaitReady(test);
            check(voiceWorker(test) == firstWorker, "Starting sensor recording reuses the listener worker");
            evidence.append("Home start routes only to fake recording; fan commands remain available while feeling saves await active telemetry.\n");

            test.runOnMainSync(first::finish);
            test.waitForIdleSync();
            await(test, "Activity destruction", 5000, first::isDestroyed);
            activity = null;
            test.runOnMainSync(() -> {
                check(BedtimeVoiceService.current != null, "Closing Activity does not stop enabled listening");
                telemetry(context, fake, false);
                check(RecordingVoiceController.canRecognize(), "Recording end preserves fan command eligibility");
                check(!RecordingVoiceController.canLogFeeling(), "Recording end immediately blocks feeling saves");
            });
            awaitReady(test);
            check(voiceWorker(test) == firstWorker && firstWorker.isAlive(), "Recording end preserves the same worker while Activity is destroyed");
            evidence.append("Sensor recording can stop with Activity destroyed while fan listening stays ready on the same worker.\n");
            test.runOnMainSync(() -> RecordingVoiceController.suppress(context));
            awaitStopped(test, firstWorker);

            test.runOnMainSync(() -> {
                telemetry(context, fake, false);
                check(RecordingVoiceController.isSuppressed(), "Idle telemetry preserves an explicit pause");
                telemetry(context, fake, true);
                check(BedtimeVoiceService.current == null, "Telemetry alone does not start a background microphone");
            });
            activity = launch(test, context);
            Activity reopened = activity;
            awaitReady(test);
            evidence.append("Opening visible Breeze automatically rejoins an already active recording.\n");

            Thread rejoinedWorker = voiceWorker(test);
            test.runOnMainSync(() -> {
                find(reopened.getWindow().getDecorView(), "Setup").performClick();
                TextView pause = find(reopened.getWindow().getDecorView(), "Pause listening");
                check(pause != null && pause.isEnabled(), "Pause listening control is available");
                pause.performClick();
                check(RecordingVoiceController.isSuppressed(), "Explicit pause suppresses listening");
            });
            awaitStopped(test, rejoinedWorker);
            for (int attempt = 0; attempt < 5; attempt++) {
                test.runOnMainSync(() -> {
                    telemetry(context, fake, true);
                    RecordingVoiceController.reconcile(reopened);
                    check(RecordingVoiceController.isSuppressed(), "Active telemetry retains explicit pause");
                    check(BedtimeVoiceService.current == null, "Paused listening must not restart the microphone");
                });
                Thread.sleep(150);
            }
            test.runOnMainSync(() -> {
                telemetry(context, fake, false);
                RecordingVoiceController.reconcile(reopened);
                check(RecordingVoiceController.isSuppressed(), "Recording end retains an explicit pause");
                check(BedtimeVoiceService.current == null, "Idle reconciliation cannot undo pause");
                telemetry(context, fake, false);
                check(RecordingVoiceController.isSuppressed(), "Repeated idle telemetry cannot undo pause");
                telemetry(context, fake, true);
                RecordingVoiceController.reconcile(reopened);
            });
            awaitReady(test);
            evidence.append("Pause survives repeated active and idle telemetry/reconciliation; a new recording permits visible Activity reconciliation.\n");

            Thread replacedWorker = voiceWorker(test);
            BedtimeVoiceService[] replaced = {null};
            test.runOnMainSync(() -> {
                replaced[0] = BedtimeVoiceService.current;
                check(replaced[0] != null && replacedWorker != null, "Active listener exists before rapid recording rollover");
                telemetry(context, fake, false);
                check(!RecordingVoiceController.canLogFeeling(), "Rollover idle boundary blocks feeling saves");
                telemetry(context, fake, true);
                check(RecordingVoiceController.canLogFeeling(), "New confirmed recording permits feeling saves");
                RecordingVoiceController.reconcile(reopened);
            });
            await(test, "listener preserved through rapid recording rollover", 45000,
                    () -> BedtimeVoiceService.current == replaced[0] && replacedWorker.isAlive()
                            && BedtimeVoiceService.current.ready && RecordingVoiceController.canRecognize()
                            && !RecordingVoiceController.isSuppressed());
            evidence.append("Rapid recording rollover preserves the existing ready worker and updates only feeling eligibility.\n");
            Thread disabledWorker = voiceWorker(test);
            android.os.PowerManager.WakeLock[] disabledLock = {null};
            test.runOnMainSync(() -> {
                disabledLock[0] = (android.os.PowerManager.WakeLock) field(BedtimeVoiceService.current, "wakeLock");
                check(disabledLock[0].isHeld(), "Listener holds the wake lock before disable");
                RecordingVoiceController.setListeningDisabled(context, true);
                check(fake.latest.active(), "Disabling listening preserves controller recording");
                check(!RecordingVoiceController.canRecognize(), "Disable immediately vetoes recognition");
                RecordingVoiceController.resume(reopened);
                RecordingVoiceController.reconcile(reopened);
            });
            awaitStopped(test, disabledWorker);
            test.runOnMainSync(() -> {
                check(!disabledLock[0].isHeld(), "Disable releases the listener wake lock");
                telemetry(context, fake, false);
                telemetry(context, fake, true);
                RecordingVoiceController.reconcile(reopened);
                check(BedtimeVoiceService.current == null, "Later recording cannot override disabled listening");
            });
            evidence.append("Persistent disable stops the active worker, releases its wake lock, preserves controller recording and vetoes later resume/reconcile/recording.\n");
            return "PASS: independent fan voice and recording-gated feelings using real Activity, foreground microphone and native model; fake controller only; no record writes\n" + evidence;
        } finally {
            try {
                if (activity != null) {
                    Activity closing = activity;
                    test.runOnMainSync(closing::finish);
                    test.waitForIdleSync();
                    await(test, "test Activity cleanup", 5000, closing::isDestroyed);
                }
            } finally {
                try {
                    Thread worker = voiceWorker(test);
                    test.runOnMainSync(() -> {
                        fake.latest.flags = 128;
                        RecordingVoiceController.setListeningDisabled(context, true);
                        RecordingVoiceController.startFailed(context);
                        RecordingVoiceController.onTelemetry(context, false);
                        context.stopService(new Intent(context, BedtimeVoiceService.class));
                    });
                    awaitStopped(test, worker);
                } finally {
                    test.runOnMainSync(() -> {
                        RecordingVoiceController.setOnlyListenWhilePluggedIn(context, wasPluggedOnly);
                        if (!hadPluggedOnly) Settings.prefs(context).edit().remove("onlyListenWhilePluggedIn").apply();
                        RecordingVoiceController.setListeningDisabled(context, wasDisabled);
                        if (BreezeService.current == fake) BreezeService.current = null;
                    });
                }
            }
        }
    }

    private static Activity launch(Instrumentation test, Context context) {
        Activity result = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        return result;
    }
    private static void telemetry(Context context, FakeController fake, boolean active) {
        fake.latest.flags = 128 | (active ? 1 : 0);
        fake.latest.time = System.currentTimeMillis();
        RecordingVoiceController.onTelemetry(context, active);
    }
    private static void awaitReady(Instrumentation test) throws Exception {
        await(test, "microphone readiness", 45000, () -> BedtimeVoiceService.current != null
                && BedtimeVoiceService.current.ready && RecordingVoiceController.canRecognize());
    }
    private static void awaitStopped(Instrumentation test, Thread worker) throws Exception {
        await(test, "microphone worker release", 8000, () -> BedtimeVoiceService.current == null
                && (worker == null || !worker.isAlive()));
    }
    private static Thread voiceWorker(Instrumentation test) {
        Thread[] result = {null};
        test.runOnMainSync(() -> { if (BedtimeVoiceService.current != null) result[0] = (Thread) field(BedtimeVoiceService.current, "worker"); });
        return result[0];
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void await(Instrumentation test, String description, long timeout, BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            boolean[] done = {false};
            test.runOnMainSync(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        String[] state = {""};
        test.runOnMainSync(() -> state[0] = RecordingVoiceController.describe()
                + " service=" + BedtimeVoiceService.lastStatus);
        throw new AssertionError("Timed out: " + description + "; " + state[0]);
    }
    private static TextView find(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int n = 0; n < group.getChildCount(); n++) { TextView match = find(group.getChildAt(n), text); if (match != null) return match; }
        }
        return null;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
