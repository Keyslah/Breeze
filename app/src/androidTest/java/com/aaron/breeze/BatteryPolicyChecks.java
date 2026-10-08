package com.aaron.breeze;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/** Real Activity/service lifecycle; grace expiry is advanced, not a wall-clock timing test. */
public final class BatteryPolicyChecks {
    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            return "SKIPPED: unlock the phone for the visible Bluetooth lifecycle check\n";
        Activity activity = null;
        try {
            test.runOnMainSync(() -> {
                check(RecordingVoiceController.isListeningDisabled(context), "Listening must remain disabled for this check");
                context.stopService(new Intent(context, BreezeService.class));
            });
            await(test, "initial Bluetooth stop", () -> BreezeService.current == null);
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.waitForIdleSync();
            Activity first = activity;
            test.runOnMainSync(() -> check(BreezeService.startForVisibleUi(first, true), "Visible unlocked Activity permits Bluetooth"));
            await(test, "visible Bluetooth service", () -> BreezeService.current != null && BreezeService.current.visibleForBluetooth());
            BreezeService firstService = BreezeService.current;
            test.runOnMainSync(first::finish);
            await(test, "Activity closes while Bluetooth remains in grace", first::isDestroyed);
            activity = null;
            test.runOnMainSync(() -> {
                check(BreezeService.current == firstService && firstService.visibleForBluetooth(), "Leaving retains the existing Bluetooth service during grace");
                Handler handler = (Handler) field(firstService, "handler");
                check(handler.hasCallbacks((Runnable) field(firstService, "backgroundDisconnect")), "Leaving schedules the two-minute disconnect");
                check(!BreezeService.startForVisibleUi(context, true), "Hidden explicit start cannot create new Bluetooth work");
                check(BedtimeVoiceService.current == null && !RecordingVoiceController.shouldRunService(), "No microphone service while disabled");
            });
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.waitForIdleSync();
            Activity reopened = activity;
            test.runOnMainSync(() -> {
                check(BreezeService.current == firstService && firstService.visibleForBluetooth(), "Quick return keeps the same service instance");
                Handler handler = (Handler) field(firstService, "handler");
                check(!handler.hasCallbacks((Runnable) field(firstService, "backgroundDisconnect")), "Quick return cancels the old disconnect timer");
            });
            test.runOnMainSync(reopened::finish);
            await(test, "second Activity closes", reopened::isDestroyed);
            activity = null;
            test.runOnMainSync(() -> expireGrace(firstService));
            await(test, "Bluetooth stop at advanced grace deadline", () -> BreezeService.current == null);
            test.runOnMainSync(() -> {
                check(!firstService.scanning && !firstService.ready, "Expiry stops scans and clears connection readiness");
                check(field(firstService, "gatt") == null, "Expiry closes the GATT connection");
                Handler handler = (Handler) field(firstService, "handler");
                for (String name : new String[]{"backgroundDisconnect", "reconnect", "periodicSync", "connectionWatchdog"})
                    check(!handler.hasCallbacks((Runnable) field(firstService, name)), "Expiry removes " + name);
                check(!BreezeService.startForVisibleUi(context, true), "Expired hidden session rejects explicit starts");
                firstService.scan();
                firstService.scheduleReconnect();
                firstService.download();
                check(!firstService.scanning && field(firstService, "gatt") == null, "Stale requests cannot restart expired Bluetooth");
                check(!handler.hasCallbacks((Runnable) field(firstService, "reconnect")), "Stale retry stays cancelled");
            });
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.waitForIdleSync();
            Activity afterExpiry = activity;
            test.runOnMainSync(() -> check(BreezeService.startForVisibleUi(afterExpiry, true), "Reopening after expiry permits a fresh Bluetooth start"));
            await(test, "Bluetooth service after expired session", () -> BreezeService.current != null && BreezeService.current.visibleForBluetooth());
            test.runOnMainSync(() -> BreezeService.disconnectPhone(afterExpiry));
            await(test, "manual phone disconnect", () -> BreezeService.current == null);
            test.runOnMainSync(() -> {
                check(!BreezeService.startForVisibleUi(afterExpiry, false), "Automatic refresh cannot undo manual disconnect");
                check(RecordingVoiceController.isListeningDisabled(context), "Visibility and disconnect never change the checkbox");
                check(BedtimeVoiceService.current == null && !RecordingVoiceController.shouldRunService(), "Microphone remains disabled across the grace period");
            });
            return "PASS: visible Bluetooth start; leaving retains service during grace; quick return keeps its instance and cancels timer; advanced grace deadline closes GATT and removes scans/retries/sync; expired hidden and stale requests blocked; reopening permitted; manual disconnect immediate; microphone remains disabled\n";
        } finally {
            if (activity != null) {
                Activity closing = activity;
                test.runOnMainSync(closing::finish);
                test.waitForIdleSync();
            }
            test.runOnMainSync(() -> { BreezeService.setUiVisible(context, false); BreezeService.disconnectPhone(context); });
            await(test, "final Bluetooth cleanup", () -> BreezeService.current == null);
        }
    }

    private static void expireGrace(BreezeService service) {
        try {
            Field policyField = BreezeService.class.getDeclaredField("uiConnection");
            policyField.setAccessible(true);
            UiConnectionState policy = (UiConnectionState) policyField.get(null);
            long remaining = policy.backgroundRemainingMs();
            check(remaining > 0, "A real background grace deadline must exist before advancing it");
            Field deadline = UiConnectionState.class.getDeclaredField("backgroundDeadlineMs");
            deadline.setAccessible(true);
            deadline.setLong(policy, deadline.getLong(policy) - remaining - 1);
            check(policy.backgroundRemainingMs() == 0, "The advanced deadline must be expired");
            ((Runnable) field(service, "backgroundDisconnect")).run();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object field(Object target, String name) {
        try { Field field = BreezeService.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void await(Instrumentation test, String description, BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        do {
            boolean[] done = {false};
            test.runOnMainSync(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out: " + description);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
