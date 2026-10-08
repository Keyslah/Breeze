package com.aaron.breeze;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import java.util.function.IntFunction;

/** No microphone or BLE: the real preference/UI gate with a fabricated controller. */
public final class ListeningDisabledChecks {
    private ListeningDisabledChecks() { }

    private static final class FakeController extends BreezeService {
        int starts;
        FakeController() {
            ready = true;
            sensorFirmware = true;
            latest = new Protocol.Reading();
            latest.flags = 128;
        }
        @Override public boolean fresh() { return true; }
        @Override public void startSession() { starts++; }
        @Override public void changed() { }
        @Override public void scan() { throw new AssertionError("Unexpected Bluetooth scan"); }
        @Override public void connect(String address) { throw new AssertionError("Unexpected Bluetooth connection"); }
        @Override public void command(IntFunction<byte[]> make, Result done) { throw new AssertionError("Unexpected Bluetooth command"); }
        @Override public void download() { throw new AssertionError("Unexpected controller transfer"); }
    }

    private static final class NoMicrophoneContext extends ContextWrapper {
        NoMicrophoneContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkSelfPermission(String permission) {
            return Manifest.permission.RECORD_AUDIO.equals(permission)
                    ? PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
        }
        @Override public ComponentName startForegroundService(Intent intent) {
            throw new AssertionError("Disabled listening attempted to start a microphone service");
        }
        @Override public void sendBroadcast(Intent intent) { /* Do not refresh the UI during gate-only checks. */ }
    }

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            return "SKIPPED: unlock the phone before disabled listening UI checks\n";
        boolean[] occupied = {false};
        test.runOnMainSync(() -> occupied[0] = BreezeService.current != null || BedtimeVoiceService.current != null);
        if (occupied[0]) return "SKIPPED: existing app services were left untouched; use an isolated instrumentation process\n";
        boolean wasDisabled = RecordingVoiceController.isListeningDisabled(context);
        FakeController fake = new FakeController();
        NoMicrophoneContext denied = new NoMicrophoneContext(context);
        Activity activity = null;
        try {
            test.runOnMainSync(() -> {
                BreezeService.current = fake;
                RecordingVoiceController.setListeningDisabled(context, true);
                RecordingVoiceController.prepareStart(denied);
                check(RecordingVoiceController.isStarting(), "Sensor recording can prepare without microphone permission");
                check(!RecordingVoiceController.shouldRunService(), "Pending sensor recording cannot arm disabled voice");
                RecordingVoiceController.onTelemetry(denied, true);
                RecordingVoiceController.resume(denied);
                RecordingVoiceController.reconcile(denied);
                check(!RecordingVoiceController.canRecognize(), "Resume and reconciliation cannot override disabled listening");
                check(BedtimeVoiceService.current == null, "No microphone service started");
                RecordingVoiceController.onTelemetry(context, false);
            });
            activity = launch(test, context);
            Activity first = activity;
            test.runOnMainSync(() -> {
                find(first.getWindow().getDecorView(), "Setup").performClick();
                CheckBox disable = checkbox(first);
                check(disable.isChecked(), "Saved disabled preference is checked");
                disable.performClick();
                check(!RecordingVoiceController.isListeningDisabled(context), "Unchecking permits listening");
                disable.performClick();
                check(RecordingVoiceController.isListeningDisabled(context), "Checking disables listening");
                find(first.getWindow().getDecorView(), "Home").performClick();
                TextView start = find(first.getWindow().getDecorView(), "Start recording");
                check(start != null && start.isEnabled(), "Sensor start stays available with listening disabled");
                start.performClick();
                check(fake.starts == 1, "Sensor start reaches only the fake controller");
                fake.latest.flags = 129;
                RecordingVoiceController.onTelemetry(context, true);
                check(!RecordingVoiceController.shouldRunService(), "Starting sensor recording preserves voice veto");
            });
            test.runOnMainSync(first::finish);
            test.waitForIdleSync();
            activity = null;
            // A fresh Context and Activity reload the stored preference instead of a checkbox field.
            Context reopened = context.createPackageContext(context.getPackageName(), 0);
            test.runOnMainSync(() -> check(RecordingVoiceController.isListeningDisabled(reopened), "Disabled preference survives fresh context"));
            activity = launch(test, context);
            Activity second = activity;
            test.runOnMainSync(() -> {
                find(second.getWindow().getDecorView(), "Setup").performClick();
                check(checkbox(second).isChecked(), "Activity recreation retains checked disabled preference");
                RecordingVoiceController.resume(second);
                RecordingVoiceController.reconcile(second);
                check(BedtimeVoiceService.current == null, "Reopening an active recording cannot override disabled listening");
            });
            return "PASS: persisted Disable listening checkbox survives Activity recreation; sensor start routes without microphone service; denied microphone permission does not block disabled prepareStart; resume/reconcile/active recording cannot override the veto; fake controller only\n";
        } finally {
            if (activity != null) {
                Activity closing = activity;
                test.runOnMainSync(closing::finish);
                test.waitForIdleSync();
            }
            test.runOnMainSync(() -> {
                fake.latest.flags = 128;
                RecordingVoiceController.startFailed(context);
                RecordingVoiceController.onTelemetry(context, false);
                RecordingVoiceController.setListeningDisabled(context, wasDisabled);
                if (BreezeService.current == fake) BreezeService.current = null;
            });
        }
    }

    private static Activity launch(Instrumentation test, Context context) {
        Activity result = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        return result;
    }
    private static CheckBox checkbox(Activity activity) {
        TextView found = find(activity.getWindow().getDecorView(), "Disable listening");
        check(found instanceof CheckBox, "Disable listening checkbox exists");
        return (CheckBox) found;
    }
    private static TextView find(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int n = 0; n < group.getChildCount(); n++) {
                TextView found = find(group.getChildAt(n), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
