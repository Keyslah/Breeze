package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Bounded local evidence for a touch that did not produce the expected fan action. */
final class FanCommandTrace {
    private FanCommandTrace() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("fan-command-trace", Context.MODE_PRIVATE);
    }
    static void record(Context context, String event) {
        if (context == null) return;
        try {
            SharedPreferences prefs = prefs(context);
            String line = System.currentTimeMillis() + " elapsed=" + SystemClock.elapsedRealtime() + " " + event;
            String history = prefs.getString("events", "") + line + "\n";
            if (history.length() > 16000) history = history.substring(history.indexOf('\n', history.length() - 16000) + 1);
            prefs.edit().putString("events", history).apply();
            android.util.Log.i("BreezeFanTrace", line);
        } catch (RuntimeException failure) {
            // Diagnostic storage must never prevent a manual command.
            android.util.Log.w("BreezeFanTrace", "Could not save command trace", failure);
        }
    }
    static String read(Context context) { return prefs(context).getString("events", ""); }
}
