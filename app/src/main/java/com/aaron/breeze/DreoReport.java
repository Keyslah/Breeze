package com.aaron.breeze;

import org.json.JSONObject;
import java.io.IOException;

/** An immutable Dreo cloud snapshot. A field is absent unless its value is explicitly valid. */
public final class DreoReport {
    public final String serial;
    public final Integer speed, mode, oscillation;
    public final Boolean power, connected;
    public final Double temperatureF;
    public final long temperatureTimestamp;
    public final long speedTimestamp, powerTimestamp, modeTimestamp, oscillationTimestamp, connectedTimestamp;
    public final long requestStartedAtMs, receivedAtMs;

    private DreoReport(String serial, JSONObject values, long started, long received) {
        this.serial = serial;
        speed = integerState(values, "windlevel", 1, 9);
        power = booleanState(values, "poweron");
        // Preserve an unfamiliar reported mode number without assigning it an invented name.
        mode = integerState(values, "mode", 1, Integer.MAX_VALUE);
        oscillation = integerState(values, "oscmode", 0, 3);
        connected = booleanState(values, "connected");
        temperatureF = temperatureState(values);
        temperatureTimestamp = timestamp(values,"temperature",temperatureF,received);
        speedTimestamp = timestamp(values, "windlevel", speed, received);
        powerTimestamp = timestamp(values, "poweron", power, received);
        modeTimestamp = timestamp(values, "mode", mode, received);
        oscillationTimestamp = timestamp(values, "oscmode", oscillation, received);
        connectedTimestamp = timestamp(values, "connected", connected, received);
        requestStartedAtMs = started;
        receivedAtMs = received;
    }

    /** A timestamped, connected cloud observation; an old change time does not imply stale temperature. */
    public double temperatureC(){
        return temperatureF==null||temperatureTimestamp==0||Boolean.FALSE.equals(connected)
                ?Double.NaN:DreoTemperature.celsius(temperatureF,DreoTemperature.SOURCE_UNIT);
    }

    private static Double temperatureState(JSONObject values){
        JSONObject entry=values.optJSONObject("temperature");Object state=entry==null?null:entry.opt("state");
        if(!(state instanceof Number))return null;
        double value=((Number)state).doubleValue();
        return Double.isFinite(DreoTemperature.celsius(value,DreoTemperature.SOURCE_UNIT))?value:null;
    }

    public static DreoReport parse(JSONObject response, String expectedSerial, long started, long received) throws IOException {
        JSONObject data = response == null ? null : response.optJSONObject("data");
        JSONObject values = data == null ? null : data.optJSONObject("mixed");
        Object code = response == null ? null : response.opt("code");
        Object serial = data == null ? null : data.opt("sn");
        if (!integral(code) || ((Number) code).longValue() != 0 || values == null
                || expectedSerial == null || expectedSerial.isEmpty() || !(serial instanceof String)
                || !expectedSerial.equals(serial) || started <= 0 || received < started) {
            throw new IOException("Dreo returned an invalid device state.");
        }
        return new DreoReport(expectedSerial, values, started, received);
    }

    private static Integer integerState(JSONObject values, String key, int min, int max) {
        JSONObject entry = values.optJSONObject(key);
        Object state = entry == null ? null : entry.opt("state");
        if (!integral(state)) return null;
        long value = ((Number) state).longValue();
        return value >= min && value <= max ? (int) value : null;
    }

    private static Boolean booleanState(JSONObject values, String key) {
        JSONObject entry = values.optJSONObject(key);
        Object state = entry == null ? null : entry.opt("state");
        return state instanceof Boolean ? (Boolean) state : null;
    }

    private static long timestamp(JSONObject values, String key, Object validState, long received) {
        JSONObject entry = values.optJSONObject(key);
        Object raw = entry == null ? null : entry.opt("timestamp");
        if (validState == null || !integral(raw)) return 0;
        long seconds = ((Number) raw).longValue();
        // The observed API uses Unix seconds. Do not silently accept milliseconds or future stamps.
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000 || seconds > received / 1000 + 300) return 0;
        return seconds * 1000;
    }

    static boolean integral(Object value) {
        return value instanceof Integer || value instanceof Long;
    }
}
