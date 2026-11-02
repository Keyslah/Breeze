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

/** First real-data verification, retaining only the requested measurements in private app files. */
final class OuraProbe {
    private static final ZoneId CENTRAL=ZoneId.of("America/Chicago");
    private final Context context;
    private final OuraAccountStore account;
    OuraProbe(Context context){this.context=context.getApplicationContext();account=new OuraAccountStore(context);}

    JSONObject run() throws Exception {
        long now=System.currentTimeMillis();long[] target=account.target();
        long start=target[0]>0?target[0]:now-7*24*3600000L;
        long end=target[1]>start?target[1]:now;
        String token=account.token();
        if(now<OuraConnection.prefs(context).getLong("retryAfter",0))throw new IOException("Oura requested a pause. Try Refresh Oura later.");
        LocalDate from=Instant.ofEpochMilli(start).atZone(CENTRAL).toLocalDate().minusDays(1);
        LocalDate to=Instant.ofEpochMilli(end).atZone(CENTRAL).toLocalDate().plusDays(1);
        JSONArray records=collection("sleep",from,to,"id,day,bedtime_start,bedtime_end,type,heart_rate,sleep_phase_5_min,sleep_phase_30_sec,average_breath,readiness",token);
        JSONArray filtered=new JSONArray();Map<String,JSONObject> latest=new HashMap<>();
        for(int i=0;i<records.length();i++){
            JSONObject record=records.getJSONObject(i);String type=record.optString("type","");
            if("deleted".equals(type)||"rest".equals(type))continue;
            long periodStart=timestamp(record.optString("bedtime_start","")),periodEnd=timestamp(record.optString("bedtime_end",""));
            if(periodStart<=0||periodEnd<=periodStart||periodEnd>now||periodStart>=end||periodEnd<=start)continue;
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
            JSONObject kept=new JSONObject();
            for(String key:new String[]{"id","day","bedtime_start","bedtime_end","type","heart_rate","average_breath","sleep_phase_5_min","sleep_phase_30_sec"})
                if(record.has(key))kept.put(key,record.get(key));
            JSONObject temperature=record.optJSONObject("readiness");String temperatureSource="sleep.readiness";
            if(!finite(temperature,"temperature_deviation")){temperature=readiness.get(record.optString("day",""));temperatureSource="daily_readiness";}
            boolean hasTemperature=finite(temperature,"temperature_deviation");
            if(hasTemperature){
                kept.put("temperature_deviation_c",temperature.getDouble("temperature_deviation")).put("temperature_source",temperatureSource);
                if(temperatureSource.equals("daily_readiness"))kept.put("temperature_record_id",temperature.optString("id","")).put("temperature_timestamp",temperature.optString("timestamp",""));
            }
            filtered.put(kept);
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
        JSONObject stored=new JSONObject().put("imported_at",now).put("range_start",start).put("range_end",end).put("periods",filtered);
        OuraConnection.write(context,"oura-verification-periods.json",stored);
        String message=filtered.length()==0?"Oura connected. No completed sleep period is available for the selected recording yet. Sync the Oura app, then Refresh Oura."
                :"Oura connected. Imported "+filtered.length()+" completed sleep period"+(filtered.length()==1?"":"s")+" for verification. History display is being prepared.";
        return new JSONObject().put("authorization_verified",true).put("imported_at",now).put("completed_periods",filtered.length()).put("periods",metadata).put("readiness_request_unavailable",readinessUnavailable).put("message",message);
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
