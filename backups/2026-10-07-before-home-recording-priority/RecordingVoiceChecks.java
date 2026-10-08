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
        @Override public void fanCommand(int action, int value, int argument) { throw new AssertionError("Unexpected fan command"); }
    }

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            return "SKIPPED: unlock the phone before recording-linked voice lifecycle checks\n";
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "SKIPPED: allow Breeze microphone access before recording-linked voice lifecycle checks\n";
        boolean[] occupied = {false};
        test.runOnMainSync(() -> occupied[0] = BreezeService.current != null || BedtimeVoiceService.current != null);
        if (occupied[0])
            return "SKIPPED: existing app services were left untouched; run in an isolated instrumentation process\n";

        FakeController fake = new FakeController();
        Activity activity = null;
        StringBuilder evidence = new StringBuilder();
        test.runOnMainSync(() -> {
            check(BreezeService.current == null && BedtimeVoiceService.current == null, "Existing app services must remain untouched");
            BreezeService.current = fake;
            RecordingVoiceController.startFailed(context);
            RecordingVoiceController.onTelemetry(context, false);
        });
        try {
            activity = launch(test, context);
            Activity first = activity;
            test.runOnMainSync(() -> {
                TextView start = find(first.getWindow().getDecorView(), "Start recording");
                check(start != null && start.isEnabled(), "Home recording control is available");
                start.performClick();
                check(fake.starts == 1, "Home start routes only to fake controller");
                check(RecordingVoiceController.isStarting(), "Voice arms before recording confirmation");
                RecordingVoiceController.onTelemetry(context, false);
                check(RecordingVoiceController.isStarting(), "Idle packet retains pending start");
                check(!RecordingVoiceController.canRecognize(), "Pending recording cannot accept voice commands");
            });
            await(test, "armed microphone preparation", 25000, () -> {
                BedtimeVoiceService voice = BedtimeVoiceService.current;
                return voice != null && boolField(voice, "captureReady");
            });
            test.runOnMainSync(() -> {
                check(!BedtimeVoiceService.current.ready, "Armed idle microphone stays gated");
                check(RecordingVoiceController.isStarting(), "Preparation does not imply active recording");
                telemetry(context, fake, true);
            });
            awaitReady(test);
            evidence.append("Home start prearms; idle packet stays gated; active telemetry enables listening.\n");

            Thread firstWorker = voiceWorker(test);
            test.runOnMainSync(first::finish);
            test.waitForIdleSync();
            await(test, "Activity destruction", 5000, first::isDestroyed);
            activity = null;
            test.runOnMainSync(() -> {
                check(BedtimeVoiceService.current != null, "Closing Activity does not stop an active recording listener");
                telemetry(context, fake, false);
            });
            awaitStopped(test, firstWorker);
            evidence.append("Confirmed idle stops microphone worker with Activity destroyed.\n");

            test.runOnMainSync(() -> {
                telemetry(context, fake, true);
                check(BedtimeVoiceService.current == null, "Telemetry alone does not start a background microphone");
            });
            activity = launch(test, context);
            Activity reopened = activity;
            awaitReady(test);
            evidence.append("Opening visible Breeze automatically rejoins an already active recording.\n");

            Thread rejoinedWorker = voiceWorker(test);
            test.runOnMainSync(() -> {
                TextView pause = find(reopened.getWindow().getDecorView(), "Pause listening");
                check(pause != null && pause.isEnabled(), "Pause listening control is available");
                pause.performClick();
                check(RecordingVoiceController.isSuppressed(), "Explicit pause suppresses this recording");
            });
            awaitStopped(test, rejoinedWorker);
            for (int attempt = 0; attempt < 5; attempt++) {
                test.runOnMainSync(() -> {
                    telemetry(context, fake, true);
                    RecordingVoiceController.reconcile(reopened);
                    check(RecordingVoiceController.isSuppressed(), "Active telemetry retains explicit pause");
                    check(BedtimeVoiceService.current == null, "Paused recording must not restart the microphone");
                });
                Thread.sleep(150);
            }
            test.runOnMainSync(() -> {
                telemetry(context, fake, false);
                check(!RecordingVoiceController.isSuppressed(), "Confirmed recording end resets pause");
                telemetry(context, fake, true);
                RecordingVoiceController.reconcile(reopened);
            });
            awaitReady(test);
            evidence.append("Pause survives repeated active telemetry/reconciliation and resets for the next recording.\n");

            Thread replacedWorker = voiceWorker(test);
            BedtimeVoiceService[] replaced = {null};
            test.runOnMainSync(() -> {
                replaced[0] = BedtimeVoiceService.current;
                check(replaced[0] != null && replacedWorker != null, "Active listener exists before rapid recording rollover");
                telemetry(context, fake, false);
                telemetry(context, fake, true);
                RecordingVoiceController.reconcile(reopened);
            });
            await(test, "previous recording worker release", 8000, () -> !replacedWorker.isAlive());
            await(test, "automatic listener replacement after rapid recording rollover", 45000,
                    () -> BedtimeVoiceService.current != null && BedtimeVoiceService.current != replaced[0]
                            && BedtimeVoiceService.current.ready && RecordingVoiceController.canRecognize()
                            && !RecordingVoiceController.isSuppressed());
            evidence.append("Idle then active in one callback releases the old worker and automatically readies a new listener.\n");
            return "PASS: recording-linked voice lifecycle using real Activity, foreground microphone and native model; fake controller only; no record writes\n" + evidence;
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
                        RecordingVoiceController.startFailed(context);
                        RecordingVoiceController.onTelemetry(context, false);
                        context.stopService(new Intent(context, BedtimeVoiceService.class));
                    });
                    awaitStopped(test, worker);
                } finally {
                    test.runOnMainSync(() -> { if (BreezeService.current == fake) BreezeService.current = null; });
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
        await(test, "recording microphone readiness", 45000, () -> BedtimeVoiceService.current != null
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
    private static boolean boolField(Object target, String name) { return (Boolean) field(target, name); }
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
