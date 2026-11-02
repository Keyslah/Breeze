package com.aaron.breeze;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Local incident viewer. Reading an incident never sends a controller command. */
final class DriftHistoryUi {
    private DriftHistoryUi() {}

    static void show(Activity activity) {
        AlertDialog loading = new AlertDialog.Builder(activity).setTitle("Drift events")
                .setMessage("Loading saved events…").setNegativeButton("Cancel", null).show();
        BreezeService service = BreezeService.current;
        DriftJournal active = service != null && service.drift != null ? service.drift.journal : null;
        new Thread(() -> {
            try {
                DriftJournal journal = active != null ? active
                        : new DriftJournal(new File(activity.getFilesDir(), "drift-diagnostics"));
                List<File> incidents = journal.incidents();
                String[] labels = new String[incidents.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = time(incidentTime(incidents.get(i)));
                activity.runOnUiThread(() -> {
                    if (!alive(activity) || !loading.isShowing()) return;
                    loading.dismiss();
                    if (incidents.isEmpty()) {
                        new AlertDialog.Builder(activity).setTitle("Drift events")
                                .setMessage("No saved drift events yet.\n\nBreeze keeps a temporary timeline since the last matching ESP and Dreo check. If a later check disagrees, that timeline is saved here before correction.\n\nEvent recording starts with this update; earlier corrections do not have a saved timeline.")
                                .setPositiveButton("Done", null).show();
                    } else {
                        new AlertDialog.Builder(activity).setTitle("Drift events · newest first")
                                .setItems(labels, (dialog, which) -> showIncident(activity, journal, incidents.get(which)))
                                .setPositiveButton("Done", null).show();
                    }
                });
            } catch (Exception failure) {
                failed(activity, loading, failure);
            }
        }, "Breeze-drift-history").start();
    }

    private static void showIncident(Activity activity, DriftJournal journal, File incident) {
        AlertDialog loading = new AlertDialog.Builder(activity).setTitle("Drift event")
                .setMessage("Loading timeline…").setNegativeButton("Cancel", null).show();
        new Thread(() -> {
            try {
                String contents = journal.readIncident(incident.getName());
                String summary = timeline(contents, false);
                String details = timeline(contents, true);
                activity.runOnUiThread(() -> {
                    if (!alive(activity) || !loading.isShowing()) return;
                    loading.dismiss();
                    showTimeline(activity, incident, summary, details);
                });
            } catch (Exception failure) {
                failed(activity, loading, failure);
            }
        }, "Breeze-drift-incident").start();
    }

    private static void showTimeline(Activity activity, File incident, String summary, String details) {
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, padding / 2);
        CheckBox full = new CheckBox(activity);
        full.setText("Show full event data");
        layout.addView(full);
        TextView text = new TextView(activity);
        text.setText(summary);
        text.setTextIsSelectable(true);
        text.setPadding(0, padding / 2, 0, padding / 2);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(text);
        layout.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        full.setOnCheckedChangeListener((button, checked) -> {
            text.setText(checked ? details : summary);
            scroll.scrollTo(0, 0);
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(time(incidentTime(incident)))
                .setView(layout).setPositiveButton("Done", null)
                .setNegativeButton("All events", (ignored, which) -> show(activity))
                .setNeutralButton("Copy", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                Toast.makeText(activity, "Clipboard unavailable.", Toast.LENGTH_SHORT).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Breeze drift event", text.getText()));
            Toast.makeText(activity, "Timeline copied.", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private static String timeline(String contents, boolean full) {
        StringBuilder result = new StringBuilder("Times use this phone's local time zone. Dreo reports and ESP estimates do not confirm physical IR reception.\n\n");
        String[] lines = contents.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            try {
                JSONObject event = new JSONObject(lines[i]);
                if ("window_open".equals(event.optString("type"))) {
                    result.append("Window opened: ").append(time(event.optLong("openedAt", 0))).append('\n')
                            .append(event.optBoolean("baselineConfirmed", false)
                                    ? "Starts with a matching ESP and Dreo check."
                                    : "No matching checkpoint was available when this window started.")
                            .append('\n');
                } else {
                    result.append(time(event.optLong("at", 0))).append('\n')
                            .append(event.optString("description", event.optString("type", "Event"))).append('\n');
                    if (!full) {
                        JSONObject data = event.optJSONObject("data");
                        String type = event.optString("type");
                        if (data != null && ("matching_checkpoint".equals(type) || "mismatch".equals(type))) {
                            JSONObject checkpoint = new JSONObject();
                            for (String key : new String[]{"estimate", "reportedLevel", "sendCounter"})
                                if (data.has(key)) checkpoint.put(key, data.get(key));
                            if (checkpoint.length() > 0) result.append(checkpoint.toString(2)).append('\n');
                        }
                    }
                }
                if (full) result.append(event.toString(2)).append('\n');
            } catch (JSONException malformed) {
                result.append("Could not read event on line ").append(i + 1).append('.').append('\n');
                if (full) result.append(lines[i]).append('\n');
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static long incidentTime(File incident) {
        try { return Long.parseLong(incident.getName().substring(9, 28)); }
        catch (RuntimeException invalidName) { return incident.lastModified(); }
    }

    private static String time(long milliseconds) {
        return milliseconds > 0 ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(new Date(milliseconds)) : "Time unavailable";
    }

    private static boolean alive(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void failed(Activity activity, AlertDialog loading, Exception failure) {
        activity.runOnUiThread(() -> {
            if (!alive(activity) || !loading.isShowing()) return;
            loading.dismiss();
            String reason = failure.getMessage();
            new AlertDialog.Builder(activity).setTitle("Could not read drift events")
                    .setMessage(reason == null || reason.isEmpty() ? failure.getClass().getSimpleName() : reason)
                    .setPositiveButton("OK", null).show();
        });
    }
}
