package com.aaron.breeze;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;

/** Pure state/freshness policy; HTTP success alone never establishes a physical fan state. */
final class DreoSyncPolicy {
    static final long SETTLE_MS = 10000;
    private long generation, lastChangeAt = Long.MIN_VALUE, lastChangeWallTime=Long.MAX_VALUE;
    private final Map<String, Version> versions = new HashMap<>();
    private DreoReport candidate;
    private DreoReport observed;
    private long candidateAt;
    private boolean settling = true;
    private boolean confirmed;
    private boolean versionsChanged;
    private String reason = "Waiting for Dreo state · using estimate";

    private static final class Version {
        final Object value; final long timestamp;
        Version(Object value, long timestamp) { this.value=value; this.timestamp=timestamp; }
    }

    long generation() { return generation; }
    void changed(long now) {
        changed(now,Long.MAX_VALUE);
    }
    void changed(long now,long wallTime) {
        generation++; lastChangeAt=now; settling=true; confirmed=false; candidate=null;
        lastChangeWallTime=wallTime;
        reason="Waiting for Dreo to settle · using estimate";
    }
    void unavailable(String message) { confirmed=false; candidate=null; settling=true; reason=message+" · using estimate"; }
    boolean confirmed() { return confirmed; }
    String reason() { return reason; }
    boolean needsConfirmation() { return settling; }
    DreoReport observed() { return observed; }
    boolean versionsChanged() { return versionsChanged; }
    String saveVersions() {
        JSONObject saved=new JSONObject();
        try{for(Map.Entry<String,Version> entry:versions.entrySet())saved.put(entry.getKey(),new JSONObject().put("value",entry.getValue().value).put("timestamp",entry.getValue().timestamp));}
        catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
        versionsChanged=false;return saved.toString();
    }
    void restoreVersions(String saved) {
        if(saved==null)return;
        try{
            JSONObject object=new JSONObject(saved);
            for(String field:new String[]{"connected","power","speed","mode","oscillation"}){
                JSONObject entry=object.optJSONObject(field);if(entry==null)continue;
                Object rawTime=entry.opt("timestamp"),value=entry.opt("value");
                if(!(rawTime instanceof Number)||((Number)rawTime).longValue()<=0)continue;
                boolean booleanField=field.equals("power")||field.equals("connected");
                if(booleanField&&!(value instanceof Boolean))continue;
                if(!booleanField){if(!(value instanceof Integer)&&!(value instanceof Long))continue;value=((Number)value).intValue();}
                versions.put(field,new Version(value,((Number)rawTime).longValue()));
            }
        }catch(org.json.JSONException ignored){}
    }

    boolean accept(long requestGeneration, DreoReport report, long now) {
        if(requestGeneration!=generation) return false;
        confirmed=false;
        if(stale("connected",report.connected,report.connectedTimestamp)||stale("power",report.power,report.powerTimestamp)||stale("speed",report.speed,report.speedTimestamp)
                ||stale("mode",report.mode,report.modeTimestamp)||stale("oscillation",report.oscillation,report.oscillationTimestamp)) {
            unavailable("Older or conflicting Dreo state ignored"); return false;
        }
        remember("connected",report.connected,report.connectedTimestamp); remember("power",report.power,report.powerTimestamp); remember("speed",report.speed,report.speedTimestamp);
        remember("mode",report.mode,report.modeTimestamp); remember("oscillation",report.oscillation,report.oscillationTimestamp);
        observed=report;
        if(!Boolean.TRUE.equals(report.connected)) { unavailable("Dreo fan offline or connection unknown"); return false; }
        if(report.power==null||report.speed==null||report.mode==null||report.connectedTimestamp<=0||report.powerTimestamp<=0||report.speedTimestamp<=0||report.modeTimestamp<=0) {
            unavailable("Dreo state incomplete"); return false;
        }
        if(report.speed<1||report.speed>9) { unavailable("Dreo speed is outside the verified 1–9 range"); return false; }
        if(report.mode!=1) { unavailable("Dreo mode is not Normal"); return false; }
        // These are value-change timestamps: an unchanged value can validly have an old timestamp.
        if(settling) {
            if(lastChangeAt!=Long.MIN_VALUE&&now-lastChangeAt<SETTLE_MS) { reason="Waiting for Dreo to settle · using estimate"; return false; }
            boolean changedAfterCommand=Math.max(report.powerTimestamp,report.speedTimestamp)>lastChangeWallTime;
            if(!changedAfterCommand&&(candidate==null||!sameState(candidate,report))) {
                candidate=report; candidateAt=now; reason="Confirming Dreo state · using estimate"; return false;
            }
            if(!changedAfterCommand&&now-candidateAt<SETTLE_MS) return false;
            settling=false;
        }
        confirmed=true; reason="Dreo reported"; return true;
    }

    private boolean stale(String field,Object value,long timestamp) {
        if(value==null)return false;
        Version previous=versions.get(field);
        return previous!=null&&(timestamp<previous.timestamp||(timestamp==previous.timestamp&&!Objects.equals(value,previous.value)));
    }
    private void remember(String field,Object value,long timestamp) {
        if(value!=null&&timestamp>0){Version previous=versions.get(field);if(previous==null||previous.timestamp!=timestamp){versions.put(field,new Version(value,timestamp));versionsChanged=true;}}
    }
    private static boolean sameState(DreoReport a,DreoReport b) {
        return Objects.equals(a.power,b.power)&&Objects.equals(a.speed,b.speed)&&Objects.equals(a.mode,b.mode);
    }
}
