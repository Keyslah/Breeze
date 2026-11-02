package com.aaron.breeze;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Only the four requested measurements. Original offsets and sampling metadata stay in JSON. */
final class OuraData {
    static final ZoneId CENTRAL=ZoneId.of("America/Chicago");
    static final int TEMPERATURE=1,HEART=2,BREATH=4,STAGES=8,ALL=15;
    static final String UNAVAILABLE="Unavailable from Oura";
    static long timestamp(String value){try{return OffsetDateTime.parse(value).toInstant().toEpochMilli();}catch(Exception invalid){return -1;}}
    static String time(long value){return DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a z",Locale.US).withZone(CENTRAL).format(Instant.ofEpochMilli(value));}
    static double number(JSONObject value,String key){Object raw=value==null?null:value.opt(key);return raw instanceof Number&&Double.isFinite(((Number)raw).doubleValue())?((Number)raw).doubleValue():Double.NaN;}
    static boolean overlaps(long a,long b,long c,long d){return a<d&&b>c&&b>a&&d>c;}
    static double deviationF(double c){return c*1.8;}
    static String string(JSONObject value,String key){Object raw=value.opt(key);return raw instanceof String?(String)raw:"";}

    static JSONObject retain(JSONObject source,JSONObject readiness)throws Exception{
        JSONObject kept=new JSONObject();
        for(String key:new String[]{"id","day","bedtime_start","bedtime_end","type","heart_rate","average_breath","sleep_phase_5_min","sleep_phase_30_sec"})if(source.has(key))kept.put(key,source.get(key));
        JSONObject temperature=source.optJSONObject("readiness");String origin="sleep.readiness";
        if(!Double.isFinite(number(temperature,"temperature_deviation"))){temperature=readiness;origin="daily_readiness";}
        if(Double.isFinite(number(temperature,"temperature_deviation"))){
            kept.put("temperature_deviation_c",number(temperature,"temperature_deviation")).put("temperature_source",origin);
            if(origin.equals("daily_readiness"))kept.put("temperature_record_id",string(temperature,"id")).put("temperature_timestamp",string(temperature,"timestamp"));
        }
        kept.put("temperature_unit","delta_celsius").put("temperature_kind","nightly_summary")
            .put("heart_rate_unit","beats_per_minute").put("heart_rate_kind","samples")
            .put("respiratory_unit","breaths_per_minute").put("respiratory_kind","sleep_period_average");
        return kept;
    }

    static final class Period {
        final String id,day,type,startText,endText,raw,stages,stageSource;
        final long start,end,heartStart;
        final double temperatureC,breath,heartIntervalMs;
        final double[] heart;
        final int stageIntervalSeconds;
        Period(JSONObject data){
            raw=data.toString();id=string(data,"id");day=string(data,"day");type=string(data,"type");
            startText=string(data,"bedtime_start");endText=string(data,"bedtime_end");start=timestamp(startText);end=timestamp(endText);
            temperatureC=number(data,"temperature_deviation_c");double respiratory=number(data,"average_breath");breath=respiratory>0?respiratory:Double.NaN;
            JSONObject hr=data.optJSONObject("heart_rate");heartStart=hr==null?-1:timestamp(string(hr,"timestamp"));
            double interval=number(hr,"interval")*1000;JSONArray items=hr==null?null:hr.optJSONArray("items");
            boolean usable=heartStart>0&&interval>0&&Double.isFinite(interval)&&items!=null&&items.length()<=100000
                &&heartStart+interval*items.length()<Long.MAX_VALUE;
            heartIntervalMs=usable?interval:Double.NaN;heart=new double[usable?items.length():0];
            for(int i=0;i<heart.length;i++){Object value=items.opt(i);double v=value instanceof Number?((Number)value).doubleValue():Double.NaN;heart[i]=Double.isFinite(v)&&v>0?v:Double.NaN;}
            String fine=string(data,"sleep_phase_30_sec"),coarse=string(data,"sleep_phase_5_min");
            boolean fineUsable=fine.length()<=100000&&fine.matches(".*[1-4].*");
            stages=fineUsable?fine:coarse.length()<=100000?coarse:"";stageIntervalSeconds=fineUsable?30:300;
            stageSource=fineUsable?"sleep_phase_30_sec":"sleep_phase_5_min";
        }
        boolean completed(long now){return !id.isEmpty()&&start>0&&end>start&&end<=now&&!type.equals("deleted")&&!type.equals("rest");}
        double heartTime(int index){return heartStart+index*heartIntervalMs;}
        boolean heartValid(int index){double t=heartTime(index);return index>=0&&index<heart.length&&Double.isFinite(heart[index])&&t>=start&&t<end;}
        int heartCount(){int count=0;for(int i=0;i<heart.length;i++)if(heartValid(i))count++;return count;}
        boolean hasStages(){for(int i=0;i<stages.length()&&stageStart(i)<end;i++)if(stage(i)>0)return true;return false;}
        long stageStart(int index){return start+index*stageIntervalSeconds*1000L;}
        int stage(int index){char ch=stages.charAt(index);return ch>='1'&&ch<='4'?ch-'0':0;}
        String label(){return time(start)+" – "+time(end);}
        String temperatureLabel(){return "Oura temperature deviation: "+(Double.isFinite(temperatureC)?String.format(Locale.US,"%+.1f °F",deviationF(temperatureC)):UNAVAILABLE);}
        String breathLabel(){return "Oura respiratory rate: "+(Double.isFinite(breath)?String.format(Locale.US,"%.1f breaths/min, sleep-period average",breath):UNAVAILABLE);}
    }

    /** Identical time spans from alternate source revisions are drawn once; raw records stay cached. */
    static List<Period> displayPeriods(List<Period> input){
        Map<String,Period> spans=new HashMap<>();
        for(Period period:input){String span=period.start+":"+period.end;Period old=spans.get(span);
            if(old==null||quality(period)>quality(old)||(quality(period)==quality(old)&&period.id.compareTo(old.id)>0))spans.put(span,period);}
        List<Period> result=new ArrayList<>(spans.values());result.sort(Comparator.comparingLong((Period p)->p.start).thenComparing(p->p.id));return List.copyOf(result);
    }
    private static int quality(Period p){return (p.hasStages()?(p.stageIntervalSeconds==30?8:4):0)+(p.heartCount()>0?2:0)+(Double.isFinite(p.temperatureC)?1:0);}
    static final class Cache {
        final boolean fetched;final long importedAt;final List<Period> periods;
        Cache(boolean fetched,long importedAt,List<Period> periods){this.fetched=fetched;this.importedAt=importedAt;this.periods=displayPeriods(periods);}
        static Cache empty(){return new Cache(false,0,List.of());}
    }
}
