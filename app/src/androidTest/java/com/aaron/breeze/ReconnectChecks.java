package com.aaron.breeze;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Real Activity/service/controller recovery; never sends recording or infrared actions. */
public final class ReconnectChecks {
    private ReconnectChecks() { }

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !context.getSystemService(PowerManager.class).isInteractive())
            return "SKIPPED: unlock the phone before the physical reconnect check\n";
        String savedDevice = Settings.prefs(context).getString("device", "");
        if (savedDevice.isEmpty()) return "SKIPPED: connect and save a controller before the physical reconnect check\n";
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            return "SKIPPED: allow Nearby devices before the physical reconnect check\n";
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) return "SKIPPED: turn Bluetooth on before the physical reconnect check\n";

        boolean listeningDisabled = RecordingVoiceController.isListeningDisabled(context);
        int process = android.os.Process.myPid();
        Activity activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        boolean[] tested = {false};
        try {
            await(test, "initial fresh physical controller telemetry", 45000, ReconnectChecks::fresh);
            boolean[] recording = {false};
            test.runOnMainSync(() -> recording[0] = BreezeService.current.latest.active());
            if (recording[0]) return "SKIPPED: active controller recording was left untouched; run reconnect checks while idle\n";
            tested[0] = true;

            BreezeService[] first = {null};
            Object[] firstGatt = {null};
            test.runOnMainSync(() -> {
                checkHomeButtons(activity);
                first[0] = BreezeService.current;
                firstGatt[0] = field(BreezeService.class, first[0], "gatt");
                check(firstGatt[0] != null, "Fresh connection must own a real GATT handle");
                Button reconnect = reconnect(activity);
                check(reconnect.isEnabled(), "Reconnect remains available while connected for a stuck link");
                check(reconnect.performClick(), "Home reconnect has a click handler");
            });
            await(test, "Home reconnect replaces GATT and receives fresh telemetry", 45000,
                    () -> fresh() && field(BreezeService.class, BreezeService.current, "gatt") != firstGatt[0]);
            test.runOnMainSync(() -> {
                check(BreezeService.current == first[0], "Manual reconnect resets the link without replacing the running service");
                checkSameActivity(activity, process);
            });

            test.runOnMainSync(() -> {
                BreezeService before = BreezeService.current;
                Object beforeGatt = field(BreezeService.class, before, "gatt");
                UiConnectionState policy = policy();
                // Simulate a screen-off event followed by unlock without a new Activity onResume.
                policy.setVisible(false);
                check(!(Boolean) field(UiConnectionState.class, policy, "visible"), "Missed-unlock fixture starts hidden");
                check(policy.backgroundRemainingMs() > 0, "Missed-unlock fixture retains the two-minute grace");
                refresh(activity);
                check((Boolean) field(UiConnectionState.class, policy, "visible"), "Foreground refresh repairs missed unlock visibility");
                check(policy.backgroundRemainingMs() == 0, "Recovered visibility cancels the background deadline");
                check(BreezeService.current == before && before.visibleForBluetooth(), "Recovered foreground remains eligible for Bluetooth");
                check(field(BreezeService.class, before, "gatt") == beforeGatt, "Visibility repair retains a healthy existing link");
                checkSameActivity(activity, process);
            });

            BreezeService[] stopped = {null};
            test.runOnMainSync(() -> {
                stopped[0] = BreezeService.current;
                check(context.stopService(new Intent(context, BreezeService.class)), "Stop the running service to simulate its loss while Home remains resumed");
            });
            // Let the Activity's normal refresh tick recover it, without invoking start or reconnect here.
            await(test, "foreground tick revives a stopped service and receives fresh telemetry", 45000,
                    () -> BreezeService.current != stopped[0] && fresh());
            test.runOnMainSync(() -> {
                check((Boolean) field(BreezeService.class, stopped[0], "destroyed"), "Old service actually stopped before recovery");
                checkSameActivity(activity, process);
                BreezeService.disconnectPhone(activity);
                refresh(activity);
            });
            await(test, "explicit Disconnect leaves service stopped", 8000, () -> BreezeService.current == null);
            long suppressedUntil = SystemClock.elapsedRealtime() + 3500;
            do {
                test.runOnMainSync(() -> {
                    refresh(activity);
                    check(BreezeService.current == null, "Automatic foreground refresh must respect manual Disconnect");
                    check(!policy().requestStart(false), "Manual disconnect remains suppressed until a deliberate reconnect");
                    Button reconnect = reconnect(activity);
                    check(reconnect.isEnabled(), "Home reconnect remains enabled while disconnected");
                    check(!button(activity, "Start recording").isEnabled(), "Recording waits for a fresh controller connection");
                    checkSameActivity(activity, process);
                });
                Thread.sleep(150);
            } while (SystemClock.elapsedRealtime() < suppressedUntil);

            test.runOnMainSync(() -> check(reconnect(activity).performClick(), "Disconnected Home reconnect handles the tap"));
            await(test, "Home reconnect clears deliberate disconnect and restores fresh readings", 45000, ReconnectChecks::fresh);
            test.runOnMainSync(() -> {
                refresh(activity);
                checkSameActivity(activity, process);
                checkHomeButtons(activity);
                check(savedDevice.equals(Settings.prefs(context).getString("device", "")), "Reconnect preserves the saved controller");
                check(RecordingVoiceController.isListeningDisabled(context) == listeningDisabled, "Reconnect preserves the listening preference");
                check(!BreezeService.current.latest.active(), "Reconnect never starts a controller recording");
            });
            return "PASS: Home Reconnect below Start recording; real GATT replaced and fresh telemetry restored in the same Activity/process; missed unlock visibility repaired without discarding healthy link; foreground tick revives a stopped service; manual Disconnect survives refresh until Home Reconnect; saved controller and listening preference preserved; no recording/IR actions sent\n";
        } finally {
            if (tested[0]) {
                test.runOnMainSync(() -> {
                    if (!activity.isDestroyed() && !fresh()) {
                        BreezeService.setUiVisible(activity, true);
                        BreezeService.reconnectForVisibleUi(activity);
                    }
                });
                // Keep Home and its connection available after the checks, including after a failure.
                await(test, "restore visible controller connection", 45000, ReconnectChecks::fresh);
            }
        }
    }

    private static boolean fresh() { return BreezeService.current != null && BreezeService.current.fresh(); }
    private static UiConnectionState policy() { return (UiConnectionState) field(BreezeService.class, null, "uiConnection"); }
    private static void checkSameActivity(Activity activity, int process) {
        check(!activity.isFinishing() && !activity.isDestroyed()
                && (Boolean) field(MainActivity.class, activity, "activityResumed"), "Recovery keeps the original Activity resumed");
        check(android.os.Process.myPid() == process, "Recovery stays in the original app process");
    }
    private static Button reconnect(Activity activity) { return button(activity, "Reconnect"); }
    private static Button button(Activity activity, String label) {
        TextView match = find(activity.getWindow().getDecorView(), label);
        check(match instanceof Button, "Home contains the " + label + " button");
        return (Button) match;
    }
    private static void checkHomeButtons(Activity activity) {
        Button start = button(activity, "Start recording"), reconnect = reconnect(activity);
        check(start.getParent() == reconnect.getParent(), "Reconnect is in the Home recording section");
        ViewGroup parent = (ViewGroup) start.getParent();
        int from = parent.indexOfChild(start), to = parent.indexOfChild(reconnect);
        check(to > from, "Reconnect is below Start recording");
        for (int index = from + 1; index < to; index++) {
            View between = parent.getChildAt(index);
            check(!(between instanceof TextView) && !(between instanceof ViewGroup) && !between.isClickable(),
                    "Only spacing separates Start recording from Reconnect");
        }
    }
    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.equals(((TextView) view).getText().toString())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView result = find(group.getChildAt(index), label);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static Object field(Class<?> owner, Object target, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void refresh(Activity activity) {
        try { Method method = MainActivity.class.getDeclaredMethod("refresh"); method.setAccessible(true); method.invoke(activity); }
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
        test.runOnMainSync(() -> {
            BreezeService service = BreezeService.current;
            state[0] = service == null ? "service absent" : service.status + "; " + service.lastError
                    + "; eligible=" + service.visibleForBluetooth() + "; ready=" + service.ready;
        });
        throw new AssertionError("Timed out: " + description + "; " + state[0]);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
