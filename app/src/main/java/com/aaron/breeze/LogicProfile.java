package com.aaron.breeze;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Saved inputs for controller-owned automatic fan control: one range from Off to H. */
public final class LogicProfile {
    public static final int VERSION=2;
    /** A daily schedule has only 1,440 distinct local minutes, regardless of the number of saved windows. */
    public static final int MAX_COMPILED_TIME_WINDOWS=1440;
    /** Each step needs this long after the previous infrared command, in either direction. */
    public static final int MINIMUM_STEP_MS=1000;
    public String id="default",name="Default";
    public double offRoomF=68,offBodyF=98,fullRoomF=74,fullBodyF=97,bodyWeight=1,steadiness=0.3;
    public int averageSeconds=30,riseSeconds=10,fallSeconds=5;
    public final Boost boost=new Boost();
    /** Compatibility reference for the first saved time window. */
    public final TimeWindow timeWindow=new TimeWindow();
    public final List<TimeWindow> timeWindows=new ArrayList<>();
    public LogicProfile(){timeWindows.add(timeWindow);}
    /** Daily local-time adjustment. All active windows add together before the target is clamped. */
    public static final class TimeWindow {
        public boolean enabled=false;
        public int startMinute=270,endMinute=480,adjustment=-1;
        public void validate(){
            range(startMinute,0,1439,"Time window start");range(endMinute,0,1439,"Time window end");
            range(adjustment,-FanSetting.MAX,FanSetting.MAX,"Time window adjustment");
            if(enabled&&startMinute==endMinute)throw new IllegalArgumentException("Choose different start and end times for the time window.");
        }
        /** Start is included, end is excluded; a start after the end spans midnight. */
        public boolean activeAtMinute(int minute){
            range(minute,0,1439,"Local minute");validate();
            return enabled&&(startMinute<endMinute?minute>=startMinute&&minute<endMinute:minute>=startMinute||minute<endMinute);
        }
        public int adjustLevel(int normalLevel,int minute){
            range(normalLevel,0,FanSetting.MAX,"Normal fan level");
            return Math.max(0,Math.min(FanSetting.MAX,normalLevel+(activeAtMinute(minute)?adjustment:0)));
        }
        public TimeWindow copy(){TimeWindow result=new TimeWindow();result.copyFrom(this);return result;}
        private void copyFrom(TimeWindow other){enabled=other.enabled;startMinute=other.startMinute;endMinute=other.endMinute;adjustment=other.adjustment;}
    }
    /** Replace the editor's windows with detached saved values while preserving the legacy first reference. */
    public void replaceTimeWindows(List<TimeWindow> windows){
        if(windows==null)throw new IllegalArgumentException("Time windows are missing.");
        range(windows.size(),1,Integer.MAX_VALUE,"Time window count");
        List<TimeWindow> detached=new ArrayList<>();
        for(TimeWindow window:windows){if(window==null)throw new IllegalArgumentException("A time window is missing.");window.validate();detached.add(window.copy());}
        timeWindow.copyFrom(detached.get(0));timeWindows.clear();timeWindows.add(timeWindow);
        for(int i=1;i<detached.size();i++)timeWindows.add(detached.get(i));
    }
    private void validateTimeWindows(){
        range(timeWindows.size(),1,Integer.MAX_VALUE,"Time window count");
        if(timeWindows.get(0)!=timeWindow)throw new IllegalArgumentException("The first time window must use the profile's first window reference.");
        for(TimeWindow window:timeWindows){if(window==null)throw new IllegalArgumentException("A time window is missing.");window.validate();}
    }
    public boolean hasEnabledTimeWindows(){validateTimeWindows();for(TimeWindow window:timeWindows)if(window.enabled)return true;return false;}
    public long windowAdjustmentAtMinute(int minute){
        range(minute,0,1439,"Local minute");validateTimeWindows();long adjustment=0;
        for(TimeWindow window:timeWindows)if(window.activeAtMinute(minute))adjustment+=window.adjustment;
        return adjustment;
    }
    public int adjustLevel(int normalLevel,int minute){
        range(normalLevel,0,FanSetting.MAX,"Normal fan level");
        return (int)Math.max(0,Math.min(FanSetting.MAX,normalLevel+windowAdjustmentAtMinute(minute)));
    }
    /**
     * Compile any number of saved windows into equivalent non-overlapping controller intervals.
     * Only the final sum is limited: offsets outside [-H,H] already put every normal target at Off or H.
     */
    public List<TimeWindow> compiledTimeWindows(){
        validateTimeWindows();long[] changes=new long[MAX_COMPILED_TIME_WINDOWS+1];
        for(TimeWindow window:timeWindows){
            if(!window.enabled)continue;
            if(window.startMinute<window.endMinute)addWindowChange(changes,window.startMinute,window.endMinute,window.adjustment);
            else {addWindowChange(changes,window.startMinute,1440,window.adjustment);addWindowChange(changes,0,window.endMinute,window.adjustment);}
        }
        List<TimeWindow> compiled=new ArrayList<>();long sum=0;int start=0,previous=0;
        for(int minute=0;minute<=1440;minute++){
            sum+=changes[minute];int adjustment=minute==1440?0:(int)Math.max(-FanSetting.MAX,Math.min(FanSetting.MAX,sum));
            if(adjustment!=previous){
                if(previous!=0){
                    if(start==0&&minute==1440){compiled.add(compiledWindow(0,720,previous));compiled.add(compiledWindow(720,0,previous));}
                    else compiled.add(compiledWindow(start,minute%1440,previous));
                }
                start=minute;previous=adjustment;
            }
        }
        if(compiled.isEmpty())compiled.add(new TimeWindow());
        return compiled;
    }
    private static void addWindowChange(long[] changes,int start,int end,int adjustment){changes[start]+=adjustment;changes[end]-=adjustment;}
    private static TimeWindow compiledWindow(int start,int end,int adjustment){TimeWindow w=new TimeWindow();w.enabled=true;w.startMinute=start;w.endMinute=end;w.adjustment=adjustment;return w;}
    public static final class Boost {
        public boolean enabled=false;
        public int amount=2,windowSeconds=30,riseSeconds=3,holdSeconds=120,returnSeconds=5,curve=1;
        public double triggerRiseF=1;
    }
    private static void range(int value,int low,int high,String label){if(value<low||value>high)throw new IllegalArgumentException(label+" must be "+low+"–"+high+".");}
    private static void decimal(double value,double low,double high,String label,String unit){if(!Double.isFinite(value)||value<low||value>high||Math.abs(value*100-Math.rint(value*100))>0.000001)throw new IllegalArgumentException(label+" must be "+trim(low)+"–"+trim(high)+unit+", with at most two decimal places.");}
    private static String trim(double value){return value==Math.rint(value)?Long.toString((long)value):Double.toString(value);}
    /** Fan levels the room adds for each degree between the two points; the body weight supplies the rest. */
    public double roomWeight(){return (FanSetting.MAX+bodyWeight*(offBodyF-fullBodyF))/(fullRoomF-offRoomF);}
    /** 0 at the Off point and 10 at the Full point. */
    public double score(double roomF,double bodyF){return roomWeight()*(roomF-offRoomF)+bodyWeight*(bodyF-offBodyF);}
    /** The setting for a steady reading: Off at or below 0, then one setting per unit up to H. */
    public int level(double roomF,double bodyF){double score=score(roomF,bodyF);if(!(score>0.0001))return 0;return (int)Math.min(FanSetting.MAX,Math.ceil(score-0.0001));}
    /** Milliseconds between automatic steps for a requested full-range time; nine speed steps span 1 to H. */
    public static int stepMillis(int fullRangeSeconds){return Math.max(MINIMUM_STEP_MS,(fullRangeSeconds*1000+FanSetting.MAX-2)/(FanSetting.MAX-1));}
    public void validate(){
        if(id==null||id.trim().isEmpty())throw new IllegalArgumentException("Profile identity is missing.");
        if(name==null||name.trim().isEmpty()||name.trim().length()>80)throw new IllegalArgumentException("Enter a profile name of 1–80 characters.");
        decimal(offRoomF,-50,200,"Fan off room temperature"," °F");decimal(fullRoomF,-50,200,"Full speed room temperature"," °F");
        if(fullRoomF-offRoomF<0.999999)throw new IllegalArgumentException("Full speed room temperature must be at least 1 °F above the fan off room temperature.");
        decimal(offBodyF,-4,176,"Fan off body temperature"," °F");decimal(fullBodyF,-4,176,"Full speed body temperature"," °F");
        decimal(bodyWeight,0,10,"Body weight"," levels per °F");decimal(steadiness,0,1,"Steadiness"," levels");
        if(FanSetting.MAX+bodyWeight*(offBodyF-fullBodyF)<0.0999999)throw new IllegalArgumentException("Body weight is too high for these two points: the room would no longer raise the fan. Lower it or move the full speed body temperature.");
        range(averageSeconds,1,300,"Averaging seconds");range(riseSeconds,1,120,"Increase seconds");range(fallSeconds,1,120,"Decrease seconds");
        Boost b=boost;range(b.amount,1,FanSetting.MAX-1,"Boost levels");decimal(b.triggerRiseF,0.01,20,"Boost trigger"," °F");range(b.windowSeconds,1,300,"Boost detection seconds");range(b.riseSeconds,1,120,"Boost ramp up seconds");range(b.holdSeconds,1,3600,"Boost hold seconds");range(b.returnSeconds,1,120,"Boost return seconds");range(b.curve,0,1,"Boost curve");
        validateTimeWindows();
    }
    public String serialize(){
        validate();Properties p=new Properties();put(p,"version",VERSION);put(p,"id",id);put(p,"name",name.trim());
        put(p,"offRoomF",offRoomF);put(p,"offBodyF",offBodyF);put(p,"fullRoomF",fullRoomF);put(p,"fullBodyF",fullBodyF);put(p,"bodyWeight",bodyWeight);put(p,"steadiness",steadiness);
        put(p,"averageSeconds",averageSeconds);put(p,"riseSeconds",riseSeconds);put(p,"fallSeconds",fallSeconds);
        Boost b=boost;put(p,"boost.enabled",b.enabled);put(p,"boost.amount",b.amount);put(p,"boost.triggerRiseF",b.triggerRiseF);put(p,"boost.windowSeconds",b.windowSeconds);put(p,"boost.riseSeconds",b.riseSeconds);put(p,"boost.holdSeconds",b.holdSeconds);put(p,"boost.returnSeconds",b.returnSeconds);put(p,"boost.curve",b.curve);
        saveWindow(p,"timeWindow.",timeWindow);
        put(p,"timeWindows.count",timeWindows.size());
        for(int i=0;i<timeWindows.size();i++)saveWindow(p,"timeWindows."+i+".",timeWindows.get(i));
        try{StringWriter out=new StringWriter();p.store(out,null);return out.toString();}catch(java.io.IOException e){throw new IllegalArgumentException("Could not save profile.",e);}
    }
    /** Everything the controller acts on, ignoring the name and the stored timestamp comment. */
    public String settingsSignature(){return serialize().replaceAll("(?m)^(#|name=|id=).*$","");}
    public boolean sameSettings(LogicProfile other){return other!=null&&settingsSignature().equals(other.settingsSignature());}
    public LogicProfile copy(){return deserialize(serialize());}
    private static void put(Properties p,String key,Object value){p.setProperty(key,String.valueOf(value));}
    private static void saveWindow(Properties p,String prefix,TimeWindow w){put(p,prefix+"enabled",w.enabled);put(p,prefix+"startMinute",w.startMinute);put(p,prefix+"endMinute",w.endMinute);put(p,prefix+"adjustment",w.adjustment);}
    private static TimeWindow loadWindow(Properties p,String prefix){
        TimeWindow w=new TimeWindow();w.enabled=bool(p,prefix+"enabled",false);w.startMinute=integer(p,prefix+"startMinute",270);w.endMinute=integer(p,prefix+"endMinute",480);w.adjustment=integer(p,prefix+"adjustment",-1);return w;
    }
    private static int integer(Properties p,String key,int fallback){return Integer.parseInt(p.getProperty(key,String.valueOf(fallback)));}
    private static double decimal(Properties p,String key,double fallback){return Double.parseDouble(p.getProperty(key,String.valueOf(fallback)));}
    private static boolean bool(Properties p,String key,boolean fallback){String value=p.getProperty(key,String.valueOf(fallback));if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Invalid profile setting: "+key);return Boolean.parseBoolean(value);}
    private static Properties properties(String text)throws java.io.IOException {Properties p=new Properties();p.load(new StringReader(text));return p;}
    public static LogicProfile deserialize(String text){
        try{
            Properties p=properties(text);if(integer(p,"version",0)!=VERSION)throw new IllegalArgumentException("Unsupported logic profile.");
            LogicProfile result=new LogicProfile();result.id=p.getProperty("id",result.id);result.name=p.getProperty("name",result.name);
            result.offRoomF=decimal(p,"offRoomF",68);result.offBodyF=decimal(p,"offBodyF",98);result.fullRoomF=decimal(p,"fullRoomF",74);result.fullBodyF=decimal(p,"fullBodyF",97);result.bodyWeight=decimal(p,"bodyWeight",1);result.steadiness=decimal(p,"steadiness",0.3);
            result.averageSeconds=integer(p,"averageSeconds",30);result.riseSeconds=integer(p,"riseSeconds",10);result.fallSeconds=integer(p,"fallSeconds",5);
            Boost b=result.boost;b.enabled=bool(p,"boost.enabled",false);b.amount=integer(p,"boost.amount",2);b.triggerRiseF=decimal(p,"boost.triggerRiseF",1);b.windowSeconds=integer(p,"boost.windowSeconds",30);b.riseSeconds=integer(p,"boost.riseSeconds",3);b.holdSeconds=integer(p,"boost.holdSeconds",120);b.returnSeconds=integer(p,"boost.returnSeconds",5);b.curve=integer(p,"boost.curve",1);
            List<TimeWindow> windows=new ArrayList<>();
            if(p.containsKey("timeWindows.count")){
                int count=integer(p,"timeWindows.count",1);range(count,1,Integer.MAX_VALUE,"Time window count");
                for(int i=0;i<count;i++){
                    String prefix="timeWindows."+i+".";
                    for(String field:new String[]{"enabled","startMinute","endMinute","adjustment"})if(!p.containsKey(prefix+field))throw new IllegalArgumentException("Missing saved time window setting: "+prefix+field);
                    windows.add(loadWindow(p,prefix));
                }
            }else windows.add(loadWindow(p,"timeWindow."));
            result.replaceTimeWindows(windows);
            result.validate();return result;
        }catch(java.io.IOException|NumberFormatException e){throw new IllegalArgumentException("Invalid saved logic profile.",e);}
    }
    /**
     * Starting range for someone upgrading from the Warm/Cool threshold rules. Those thresholds have no
     * equivalent here, so only the smoothing and pacing times carry over; anything unreadable keeps its default.
     */
    public static LogicProfile fromThresholdProfile(String earlier){
        LogicProfile result=new LogicProfile();if(earlier==null)return result;
        try{
            Properties p=properties(earlier);LogicProfile carried=new LogicProfile();
            carried.averageSeconds=integer(p,"averageSeconds",carried.averageSeconds);carried.riseSeconds=integer(p,"riseSeconds",carried.riseSeconds);carried.fallSeconds=integer(p,"fallSeconds",carried.fallSeconds);
            carried.validate();return carried;
        }catch(java.io.IOException|RuntimeException unreadable){return result;}
    }
}
