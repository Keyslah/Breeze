package com.aaron.breeze;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicitly invoked live API inspection. Never runs in ordinary test groups.
 * Uses the on-device token in memory and reports only availability/timing metadata.
 * No UI automation, account export, sensor upload, or selected-night substitution.
 */
final class OuraCloudInspection {
    private static final ZoneId CENTRAL=ZoneId.of("America/Chicago");
    private static long time(JSONObject row,String field){try{return OffsetDateTime.parse(row.getString(field)).toInstant().toEpochMilli();}catch(Exception e){return -1;}}
    private static boolean number(JSONObject row,String field){Object value=row==null?null:row.opt(field);return value instanceof Number&&Double.isFinite(((Number)value).doubleValue());}
    private static JSONArray collection(OuraProbe probe,String endpoint,LocalDate from,LocalDate to,String fields,String token)throws Exception{
        Method method=OuraProbe.class.getDeclaredMethod("collection",String.class,LocalDate.class,LocalDate.class,String.class,String.class);
        method.setAccessible(true);
        try{return (JSONArray)method.invoke(probe,endpoint,from,to,fields,token);}
        catch(InvocationTargetException failure){throw new java.io.IOException("Oura live inspection failed for "+endpoint+"; no credentials or response values reported.");}
    }
    static String run(Context context)throws Exception{
        LocalDate today=LocalDate.now(CENTRAL),from=today.minusDays(30),to=today.plusDays(1);
        OuraAccountStore account=new OuraAccountStore(context);String token=account.token();OuraProbe probe=new OuraProbe(context);
        JSONArray rows=collection(probe,"sleep",from,to,"id,day,bedtime_start,bedtime_end,type,heart_rate,sleep_phase_5_min,sleep_phase_30_sec,average_breath,readiness",token);
        List<JSONObject> completed=new ArrayList<>();int invalidTimes=0,unfinished=0,ignoredType=0;long now=System.currentTimeMillis();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);long start=time(row,"bedtime_start"),end=time(row,"bedtime_end");String type=row.optString("type");
            if("deleted".equals(type)||"rest".equals(type)){ignoredType++;continue;}
            if(start<=0||end<=start){invalidTimes++;continue;}
            if(end>now){unfinished++;continue;}completed.add(row);
        }
        completed.sort(Comparator.comparingLong((JSONObject row)->time(row,"bedtime_start")).reversed());
        JSONArray daily=collection(probe,"daily_readiness",from,to,"id,day,timestamp,temperature_deviation",token);
        Map<String,JSONObject> readiness=new HashMap<>();for(int i=0;i<daily.length();i++){JSONObject row=daily.getJSONObject(i);readiness.put(row.optString("day"),row);}
        JSONArray kept=new JSONArray(),summaries=new JSONArray();
        for(JSONObject row:completed){
            JSONObject filtered=new JSONObject();
            for(String key:new String[]{"id","day","bedtime_start","bedtime_end","type","heart_rate","average_breath","sleep_phase_5_min","sleep_phase_30_sec"})if(row.has(key))filtered.put(key,row.get(key));
            JSONObject temp=row.optJSONObject("readiness");String source="sleep.readiness";
            if(!number(temp,"temperature_deviation")){temp=readiness.get(row.optString("day"));source="daily_readiness";}
            if(number(temp,"temperature_deviation")){
                filtered.put("temperature_deviation_c",temp.getDouble("temperature_deviation")).put("temperature_source",source);
                if(source.equals("daily_readiness"))filtered.put("temperature_record_id",temp.optString("id")).put("temperature_timestamp",temp.optString("timestamp"));
            }
            kept.put(filtered);
            if(summaries.length()<3){
                JSONObject hr=row.optJSONObject("heart_rate");JSONArray items=hr==null?null:hr.optJSONArray("items");int valid=0,missing=0;
                if(items!=null)for(int j=0;j<items.length();j++){Object value=items.opt(j);if(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()))valid++;else missing++;}
                JSONObject summary=new JSONObject().put("start",row.optString("bedtime_start")).put("end",row.optString("bedtime_end"))
                    .put("temperature_available",filtered.has("temperature_deviation_c")).put("temperature_source",filtered.optString("temperature_source","unavailable"))
                    .put("respiratory_available",number(row,"average_breath")).put("heart_rate_samples",valid).put("heart_rate_missing",missing)
                    .put("heart_rate_interval_seconds",hr==null?JSONObject.NULL:hr.opt("interval")).put("heart_rate_start",hr==null?JSONObject.NULL:hr.opt("timestamp"));
                for(String phase:new String[]{"sleep_phase_30_sec","sleep_phase_5_min"}){Object value=row.opt(phase);summary.put(phase+"_count",value instanceof String?((String)value).length():0);}
                summaries.put(summary);
            }
        }
        JSONObject report=new JSONObject().put("query_start_date",from.toString()).put("query_end_date",to.toString()).put("sleep_records_returned",rows.length())
            .put("readiness_records_returned",daily.length()).put("completed_periods",completed.size()).put("invalid_timestamps",invalidTimes)
            .put("unfinished",unfinished).put("ignored_rest_or_deleted",ignoredType).put("latest_completed_metadata",summaries);
        // Separate verification evidence: never substitutes a prior night into the selected recording.
        if(kept.length()>0)OuraConnection.write(context,"oura-recent-verification-periods.json",new JSONObject().put("imported_at",now).put("periods",kept));
        OuraConnection.write(context,"oura-cloud-inspection.json",report);
        return report.toString()+"\n";
    }
}
