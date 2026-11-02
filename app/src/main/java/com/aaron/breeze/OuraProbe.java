package com.aaron.breeze;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

/** On-demand V2 import. Tokens stay on the phone; only the four measurements enter the cache. */
final class OuraProbe {
    private static final ZoneId CENTRAL=ZoneId.of("America/Chicago");
    private final Context context;
    private final OuraAccountStore account;
    OuraProbe(Context context){this.context=context.getApplicationContext();account=new OuraAccountStore(context);}

    JSONObject run() throws Exception {
        long[] target=account.target();
        try(Store store=new Store(context)){
            java.util.List<Store.Session> sessions=store.sessions();Store.Session chosen=sessions.isEmpty()?null:sessions.get(0);
            for(Store.Session candidate:sessions)if(candidate.start==target[0]){chosen=candidate;break;}
            return run(store,chosen);
        }
    }

    JSONObject run(Store store,Store.Session selected) throws Exception {
        if(selected==null)throw new IOException("Choose a Breeze recorded night to refresh Oura.");
        long now=System.currentTimeMillis(),start=selected.start,end=selected.end>start?selected.end:now;
        String token=account.token();
        if(now<OuraConnection.prefs(context).getLong("retryAfter",0))throw new IOException("Oura requested a pause. Try Refresh Oura later.");
        LocalDate from=Instant.ofEpochMilli(start).atZone(CENTRAL).toLocalDate().minusDays(1);
        LocalDate to=Instant.ofEpochMilli(end).atZone(CENTRAL).toLocalDate().plusDays(1);
        JSONArray records=collection("sleep",from,to,"id,day,bedtime_start,bedtime_end,type,heart_rate,sleep_phase_5_min,sleep_phase_30_sec,average_breath,readiness",token);
        JSONArray filtered=new JSONArray();Map<String,JSONObject> latest=new HashMap<>();
        for(int i=0;i<records.length();i++){
            JSONObject record=records.getJSONObject(i);
            String id=record.optString("id","");if(id.isEmpty())continue;
            latest.put(id,record);
        }
        boolean needsReadiness=false;
        for(JSONObject record:latest.values())if(!finite(record.optJSONObject("readiness"),"temperature_deviation"))needsReadiness=true;
        Map<String,JSONObject> readiness=new HashMap<>();
        boolean readinessUnavailable=false;
        if(needsReadiness){
            try{
            JSONArray daily=collection("daily_readiness",from,to,"id,day,timestamp,temperature_deviation",token);
            for(int i=0;i<daily.length();i++){JSONObject r=daily.getJSONObject(i);readiness.put(r.optString("day",""),r);}
            }catch(IOException unavailable){readinessUnavailable=true;}
        }
        JSONArray metadata=new JSONArray();
        java.util.List<JSONObject> ordered=new java.util.ArrayList<>(latest.values());
        ordered.sort(java.util.Comparator.comparingLong(r->timestamp(r.optString("bedtime_start",""))));
        for(JSONObject record:ordered){
            JSONObject kept=OuraData.retain(record,readiness.get(record.optString("day","")));
            JSONObject temperature=record.optJSONObject("readiness");String temperatureSource="sleep.readiness";
            if(!finite(temperature,"temperature_deviation")){temperature=readiness.get(record.optString("day",""));temperatureSource="daily_readiness";}
            boolean hasTemperature=finite(temperature,"temperature_deviation");
            if(hasTemperature){
                kept.put("temperature_deviation_c",temperature.getDouble("temperature_deviation")).put("temperature_source",temperatureSource);
                if(temperatureSource.equals("daily_readiness"))kept.put("temperature_record_id",temperature.optString("id","")).put("temperature_timestamp",temperature.optString("timestamp",""));
            }
            filtered.put(kept);
            OuraData.Period period=new OuraData.Period(kept);
            if(!period.completed(now)||!OuraData.overlaps(period.start,period.end,start,end))continue;
            JSONObject heart=record.optJSONObject("heart_rate");JSONArray samples=heart==null?null:heart.optJSONArray("items");int available=0,missing=0;
            if(samples!=null)for(int i=0;i<samples.length();i++){Object value=samples.opt(i);if(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()))available++;else missing++;}
            String stages30=record.optString("sleep_phase_30_sec",""),stages5=record.optString("sleep_phase_5_min","");
            if("null".equals(stages30))stages30="";if("null".equals(stages5))stages5="";
            JSONObject summary=new JSONObject().put("bedtime_start",record.optString("bedtime_start")).put("bedtime_end",record.optString("bedtime_end"))
                    .put("oura_day",record.optString("day")).put("temperature_available",hasTemperature).put("temperature_source",hasTemperature?temperatureSource:"unavailable")
                    .put("respiratory_average_available",finite(record,"average_breath")).put("heart_rate_samples",available).put("heart_rate_missing",missing)
                    .put("heart_rate_start",heart==null?JSONObject.NULL:heart.opt("timestamp")).put("heart_rate_interval_seconds",heart==null?JSONObject.NULL:heart.opt("interval"))
                    .put("stages_30_second_count",stages30.length()).put("stages_5_minute_count",stages5.length());
            metadata.put(summary);
        }
        java.util.List<OuraData.Period> periods=new java.util.ArrayList<>();
        for(int i=0;i<filtered.length();i++)periods.add(new OuraData.Period(filtered.getJSONObject(i)));
        store.saveOura(selected.id,start,end,from.toString(),to.toString(),periods,now);
        int count=store.oura(selected).periods.size();
        String message=count==0?"Oura connected. No completed sleep period is available for this recorded night. Refresh Oura after new data syncs."
                :"Oura connected. "+count+" completed sleep period"+(count==1?"":"s")+" available offline.";
        if(readinessUnavailable)message+=" Temperature data could not be refreshed; other available measurements were imported.";
        return new JSONObject().put("authorization_verified",true).put("imported_at",now).put("completed_periods",count).put("periods",metadata).put("readiness_request_unavailable",readinessUnavailable).put("message",message);
    }

    private static boolean finite(JSONObject object,String key){Object value=object==null?null:object.opt(key);return value instanceof Number&&Double.isFinite(((Number)value).doubleValue());}
    private static long timestamp(String value){try{return OffsetDateTime.parse(value).toInstant().toEpochMilli();}catch(Exception invalid){return -1;}}

    private JSONArray collection(String endpoint,LocalDate start,LocalDate end,String fields,String token)throws Exception{
        JSONArray all=new JSONArray();String cursor="";Set<String> seen=new HashSet<>();
        for(int page=0;page<100;page++){
            String address="https://api.ouraring.com/v2/usercollection/"+endpoint+"?start_date="+start+"&end_date="+end+"&fields="+encode(fields);
            if(!cursor.isEmpty())address+="&next_token="+encode(cursor);
            JSONObject result=get(address,token);JSONArray rows=result.optJSONArray("data");
            if(rows==null)throw new IOException("Oura returned an unexpected data format.");
            for(int i=0;i<rows.length();i++)all.put(rows.get(i));
            cursor=result.isNull("next_token")?"":result.optString("next_token","");
            if(cursor.isEmpty())return all;
            if(cursor.length()>8192||!seen.add(cursor))throw new IOException("Oura returned an invalid continuation token.");
        }
        throw new IOException("Oura returned too many pages for this recording.");
    }

    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private JSONObject get(String address,String token)throws Exception{
        HttpsURLConnection connection=(HttpsURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("User-Agent","Breeze/1.0");connection.setRequestProperty("Accept","application/json");
        try{
            int code=connection.getResponseCode();
            if(code==401){account.rejectToken(token);throw new IOException("Oura sign-in expired or was revoked. Connect Oura again.");}
            if(code==403)throw new IOException("Oura denied access to sleep data. Check that Breeze has Daily access in Oura.");
            if(code==429){long delay=60;try{delay=Math.max(1,Math.min(86400,Long.parseLong(connection.getHeaderField("Retry-After"))));}catch(Exception ignored){}
                OuraConnection.prefs(context).edit().putLong("retryAfter",System.currentTimeMillis()+delay*1000).apply();throw new IOException("Oura requested a pause. Try Refresh Oura later.");}
            if(code!=200)throw new IOException("Oura data is temporarily unavailable (HTTP "+code+"). Try Refresh Oura later.");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(out.size()+count>4*1024*1024)throw new IOException("Oura returned an oversized response.");out.write(buffer,0,count);}
                try{return new JSONObject(out.toString(StandardCharsets.UTF_8));}catch(Exception malformed){throw new IOException("Oura returned an unexpected data format.");}
            }
        }finally{connection.disconnect();}
    }
}
