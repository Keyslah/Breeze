package com.aaron.breeze;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Small account dialog within the existing Fan page. No Dreo control operations. */
final class DreoConnectionUi {
    private DreoConnectionUi() {}

    static void showCorrections(Activity activity) {
        TextView history = new TextView(activity);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        history.setPadding(padding, padding, padding, padding);
        history.setText(DreoCorrections.historyText(activity));
        history.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(history);
        new AlertDialog.Builder(activity).setTitle("Correction history").setView(scroll)
                .setNeutralButton("Drift events", (dialog, which) -> DriftHistoryUi.show(activity))
                .setPositiveButton("Done", null).show();
    }

    static void show(Activity activity, ExecutorService worker, Runnable changed) {
        DreoAccountStore store = new DreoAccountStore(activity);
        BreezeService service = BreezeService.current;
        String status = service == null ? "Connect the Breeze controller to start synchronization." : service.dreoStatus();
        if (!store.hasAccount()) {
            signIn(activity, worker, changed);
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Dreo synchronization")
                .setMessage(status + "\n\n" + DreoCorrections.summary(activity) + "\n\nChecks every 3 minutes while the controller is connected, including while locked. "
                        + "Keeping Bluetooth and synchronization active uses more battery. Disconnecting the controller stops the checks.")
                .setPositiveButton("Battery access", (dialog, which) -> batteryAccess(activity))
                .setNeutralButton("Sign in again", (dialog, which) -> signIn(activity, worker, changed))
                .setNegativeButton("Disconnect Dreo", (dialog, which) -> {
                    worker.execute(() -> {
                        try {
                            store.clear();
                            activity.runOnUiThread(() -> { reload(); if (alive(activity)) changed.run(); });
                        } catch (IOException failure) {
                            showError(activity, "Could not clear the saved Dreo account. Try again.");
                        }
                    });
                }).show();
    }

    private static void signIn(Activity activity, ExecutorService worker, Runnable changed) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, padding / 2);
        TextView explanation = new TextView(activity);
        explanation.setText("Use your United States Dreo account. Breeze reads the 565S fan's reported state every 3 minutes and keeps working while locked. Account secrets are encrypted with Android Keystore.");
        form.addView(explanation);
        EditText email = new EditText(activity);
        email.setHint("Dreo email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setSingleLine(true);
        email.setSaveEnabled(false);
        form.addView(email);
        EditText password = new EditText(activity);
        password.setHint("Dreo password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        password.setSaveEnabled(false);
        form.addView(password);
        TextView outcome = new TextView(activity);
        form.addView(outcome);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Connect Dreo (US)")
                .setView(form).setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = email.getText().toString().trim();
            String secret = password.getText().toString();
            if (address.isEmpty() || secret.isEmpty()) { outcome.setText("Enter your Dreo email and password."); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            outcome.setText("Signing in and finding your 565S…");
            password.setText("");
            worker.execute(() -> {
                try {
                    DreoClient client = new DreoClient(new DreoAccountStore(activity));
                    DreoClient.Account account = client.login(address, secret);
                    List<DreoClient.Device> devices = client.devices(account);
                    activity.runOnUiThread(() -> {
                        if (!alive(activity) || !dialog.isShowing()) return;
                        if (devices.isEmpty()) {
                            outcome.setText("No supported TurboPoly 565S (DR-HAF008S) was returned by this account. Nothing was changed.");
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        } else {
                            dialog.dismiss();
                            choose(activity, worker, changed, account, devices);
                        }
                    });
                } catch (IOException failure) {
                    activity.runOnUiThread(() -> {
                        if (!alive(activity) || !dialog.isShowing()) return;
                        outcome.setText(failure.getMessage());
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                }
            });
        }));
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private static void choose(Activity activity, ExecutorService worker, Runnable changed,
                               DreoClient.Account account, List<DreoClient.Device> devices) {
        if (devices.size() == 1) { save(activity, worker, changed, account, devices.get(0)); return; }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = devices.get(i).name + " · " + devices.get(i).model;
        new AlertDialog.Builder(activity).setTitle("Choose your 565S")
                .setItems(names, (dialog, which) -> save(activity, worker, changed, account, devices.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private static void save(Activity activity, ExecutorService worker, Runnable changed,
                             DreoClient.Account account, DreoClient.Device device) {
        worker.execute(() -> {
            try {
                new DreoAccountStore(activity).save(account, device);
                activity.runOnUiThread(() -> {
                    reload();
                    if (!alive(activity)) return;
                    changed.run();
                    PowerManager power = activity.getSystemService(PowerManager.class);
                    boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(activity.getPackageName());
                    new AlertDialog.Builder(activity).setTitle("Dreo connected")
                            .setMessage(device.name + "\n\n" + (unrestricted
                                    ? "Connect the Breeze controller to keep its estimate synchronized every 3 minutes, including while locked."
                                    : "Allow unrestricted battery access so the 3-minute checks can continue while your phone is locked. Then connect the Breeze controller."))
                            .setPositiveButton(unrestricted ? "Done" : "Allow while locked", (dialog, which) -> { if (!unrestricted) batteryAccess(activity); })
                            .setNegativeButton(unrestricted ? null : "Later", null).show();
                });
            } catch (IOException failure) {
                showError(activity, "Could not securely save the Dreo account. Nothing was connected.");
            }
        });
    }

    private static void batteryAccess(Activity activity) {
        PowerManager power = activity.getSystemService(PowerManager.class);
        Intent intent = power != null && power.isIgnoringBatteryOptimizations(activity.getPackageName())
                ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName()));
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException unavailable) {
            showError(activity, "Open Android Settings → Apps → Breeze → Battery and allow unrestricted usage.");
        }
    }

    private static void reload() { if (BreezeService.current != null) BreezeService.current.reloadDreoSync(); }
    private static boolean alive(Activity activity) { return !activity.isFinishing() && !activity.isDestroyed(); }
    private static void showError(Activity activity, String text) {
        activity.runOnUiThread(() -> { if (alive(activity)) new AlertDialog.Builder(activity).setTitle("Dreo").setMessage(text).setPositiveButton("OK", null).show(); });
    }
}
