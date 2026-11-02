package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Phone-local totals of acknowledged changes to a known controller estimate. */
final class DreoCorrections {
    static final int HISTORY_LIMIT = 1000;
    private DreoCorrections() {}

    static long count(SharedPreferences preferences) {
        return preferences.getLong("dreoCorrectionCount", 0);
    }

    static void record(SharedPreferences preferences, SharedPreferences.Editor edit, int before, int after,
                       int irSendCounter, DreoReport report) {
        if (!FanSetting.valid(before) || !FanSetting.valid(after) || before == after) return;
        long at = System.currentTimeMillis();
        edit.putLong("dreoCorrectionCount", count(preferences) + 1)
                .putInt("dreoCorrectionFrom", before).putInt("dreoCorrectionTo", after)
                .putLong("dreoCorrectionAt", at);
        try {
            JSONArray previous = history(preferences), next = new JSONArray();
            for (int i = Math.max(0, previous.length() - HISTORY_LIMIT + 1); i < previous.length(); i++)
                next.put(previous.getJSONObject(i));
            next.put(new JSONObject().put("correctedAt", at).put("from", before).put("to", after)
                    .put("irSendCounter", irSendCounter).put("dreoReadAt", report.receivedAtMs)
                    .put("dreoSpeedAt", report.speedTimestamp).put("dreoPowerAt", report.powerTimestamp)
                    .put("dreoModeAt", report.modeTimestamp));
            edit.putString("dreoCorrectionHistory", next.toString());
        } catch (JSONException invalidHistory) {
            // Preserve the existing history if damaged; diagnostics must not interrupt synchronization.
        }
    }

    static String summary(Context context) {
        SharedPreferences preferences = Settings.prefs(context);
        long count = count(preferences);
        String text = "Level corrections: " + count;
        if (count > 0) {
            int before = preferences.getInt("dreoCorrectionFrom", -1);
            int after = preferences.getInt("dreoCorrectionTo", -1);
            long at = preferences.getLong("dreoCorrectionAt", 0);
            text += "\nLast: " + label(before) + " → " + label(after) + " · "
                    + timestamp(at);
        }
        return text + "\nRecorded on this phone from this update; earlier corrections weren't recorded.";
    }

    static String historyText(Context context) {
        SharedPreferences preferences = Settings.prefs(context);
        StringBuilder text = new StringBuilder(summary(context));
        text.append("\n\nTime · estimated level → Dreo level\nMost recent ").append(HISTORY_LIMIT)
                .append(" corrections are kept; the total includes older entries.\n");
        try {
            JSONArray history = history(preferences);
            if (history.length() == 0) text.append("\nNo corrections recorded yet.");
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject event = history.getJSONObject(i);
                text.append("\n").append(timestamp(event.getLong("correctedAt"))).append(" · ")
                        .append(label(event.getInt("from"))).append(" → ").append(label(event.getInt("to")));
            }
        } catch (JSONException invalidHistory) {
            text.append("\nSaved history could not be read. The total is still available.");
        }
        return text.toString();
    }

    private static JSONArray history(SharedPreferences preferences) throws JSONException {
        return new JSONArray(preferences.getString("dreoCorrectionHistory", "[]"));
    }

    private static String timestamp(long at) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(at));
    }

    private static String label(int value) { return value == 0 ? "Off" : FanSetting.label(value); }
}
