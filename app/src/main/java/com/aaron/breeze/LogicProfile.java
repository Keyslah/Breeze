package com.aaron.breeze;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

/** Saved inputs for controller-owned automatic fan control. */
public final class LogicProfile {
    public String id="default",name="default";
    public double roomBoundaryF=71,coolRoomThresholdF=71;
    public int averageSeconds=60,riseSeconds=10,fallSeconds=5,maxSpeed=9,probeMask=7;
    public final Rule warm=new Rule(3,false),cool=new Rule(1,true);
    public static final class Rule {
        public double bodyThresholdF=98,stepF=0.4;
        public int minimumSpeed;
        public boolean offBelow;
        public final Boost boost=new Boost();
        Rule(int minimum,boolean off){minimumSpeed=minimum;offBelow=off;}
    }
    public static final class Boost {
        public boolean enabled=false;
        public int amount=2,windowSeconds=30,riseSeconds=3,holdSeconds=120,returnSeconds=5,curve=1;
        public double triggerRiseF=0.4;
    }
    private static void range(int value,int low,int high,String label){if(value<low||value>high)throw new IllegalArgumentException(label+" must be "+low+"–"+high+".");}
    private static void speedRange(int value,int high,String label){if(!FanSetting.validOn(value)||value>high)throw new IllegalArgumentException(label+" must be "+(high==FanSetting.MAX?"1–9 or H":"1–"+high)+".");}
    private static void temperature(double value,double low,double high,String label){if(!Double.isFinite(value)||value<low||value>high||Math.abs(value*100-Math.rint(value*100))>0.000001)throw new IllegalArgumentException(label+" must be "+low+"–"+high+" °F, with at most two decimal places.");}
    public void validate(){
        if(id==null||id.trim().isEmpty())throw new IllegalArgumentException("Profile identity is missing.");
        if(name==null||name.trim().isEmpty()||name.trim().length()>80)throw new IllegalArgumentException("Enter a profile name of 1–80 characters.");
        temperature(roomBoundaryF,-50,200,"Warm room threshold");temperature(coolRoomThresholdF,-50,200,"Cool room threshold");if(coolRoomThresholdF>roomBoundaryF)throw new IllegalArgumentException("Cool room threshold must be at or below the warm room threshold.");range(averageSeconds,1,300,"Averaging seconds");range(riseSeconds,1,120,"Increase seconds");range(fallSeconds,1,120,"Decrease seconds");speedRange(maxSpeed,FanSetting.MAX,"Maximum speed");range(probeMask,1,7,"Body probe selection");
        validateRule(warm,"Warm room");validateRule(cool,"Cool room");
    }
    private void validateRule(Rule r,String label){
        temperature(r.bodyThresholdF,-4,176,label+" body threshold");temperature(r.stepF,0.01,20,label+" temperature step");speedRange(r.minimumSpeed,maxSpeed,label+" starting speed");
        Boost b=r.boost;range(b.amount,1,FanSetting.MAX-1,label+" boost levels");temperature(b.triggerRiseF,0.01,20,label+" boost trigger");range(b.windowSeconds,1,300,label+" boost observation seconds");range(b.riseSeconds,1,120,label+" boost rise seconds");range(b.holdSeconds,1,3600,label+" boost hold seconds");range(b.returnSeconds,1,120,label+" boost return seconds");range(b.curve,0,1,label+" boost curve");
    }
    public String serialize(){
        validate();Properties p=new Properties();put(p,"version",1);put(p,"id",id);put(p,"name",name.trim());put(p,"roomBoundaryF",roomBoundaryF);put(p,"coolRoomThresholdF",coolRoomThresholdF);put(p,"averageSeconds",averageSeconds);put(p,"riseSeconds",riseSeconds);put(p,"fallSeconds",fallSeconds);put(p,"maxSpeed",maxSpeed);put(p,"probeMask",probeMask);writeRule(p,"warm",warm);writeRule(p,"cool",cool);
        try{StringWriter out=new StringWriter();p.store(out,null);return out.toString();}catch(java.io.IOException e){throw new IllegalArgumentException("Could not save profile.",e);}
    }
    private static void put(Properties p,String key,Object value){p.setProperty(key,String.valueOf(value));}
    private static void writeRule(Properties p,String key,Rule r){
        put(p,key+".thresholdF",r.bodyThresholdF);put(p,key+".stepF",r.stepF);put(p,key+".minimumSpeed",r.minimumSpeed);put(p,key+".offBelow",r.offBelow);Boost b=r.boost;key+=".boost";
        put(p,key+".enabled",b.enabled);put(p,key+".amount",b.amount);put(p,key+".triggerRiseF",b.triggerRiseF);put(p,key+".windowSeconds",b.windowSeconds);put(p,key+".riseSeconds",b.riseSeconds);put(p,key+".holdSeconds",b.holdSeconds);put(p,key+".returnSeconds",b.returnSeconds);put(p,key+".curve",b.curve);
    }
    private static int integer(Properties p,String key,int fallback){return Integer.parseInt(p.getProperty(key,String.valueOf(fallback)));}
    private static double decimal(Properties p,String key,double fallback){return Double.parseDouble(p.getProperty(key,String.valueOf(fallback)));}
    private static boolean bool(Properties p,String key,boolean fallback){String value=p.getProperty(key,String.valueOf(fallback));if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Invalid profile setting: "+key);return Boolean.parseBoolean(value);}
    public static LogicProfile deserialize(String text){
        try{Properties p=new Properties();p.load(new StringReader(text));if(integer(p,"version",0)!=1)throw new IllegalArgumentException("Unsupported logic profile.");LogicProfile result=new LogicProfile();result.id=p.getProperty("id",result.id);result.name=p.getProperty("name",result.name);result.roomBoundaryF=decimal(p,"roomBoundaryF",71);result.coolRoomThresholdF=decimal(p,"coolRoomThresholdF",result.roomBoundaryF);result.averageSeconds=integer(p,"averageSeconds",60);result.riseSeconds=integer(p,"riseSeconds",10);result.fallSeconds=integer(p,"fallSeconds",5);result.maxSpeed=integer(p,"maxSpeed",9);result.probeMask=integer(p,"probeMask",7);readRule(p,"warm",result.warm);readRule(p,"cool",result.cool);result.validate();return result;}catch(java.io.IOException|NumberFormatException e){throw new IllegalArgumentException("Invalid saved logic profile.",e);}
    }
    private static void readRule(Properties p,String key,Rule r){
        r.bodyThresholdF=decimal(p,key+".thresholdF",98);r.stepF=decimal(p,key+".stepF",0.4);r.minimumSpeed=integer(p,key+".minimumSpeed",r.minimumSpeed);r.offBelow=bool(p,key+".offBelow",r.offBelow);Boost b=r.boost;key+=".boost";
        b.enabled=bool(p,key+".enabled",false);b.amount=integer(p,key+".amount",2);b.triggerRiseF=decimal(p,key+".triggerRiseF",0.4);b.windowSeconds=integer(p,key+".windowSeconds",30);b.riseSeconds=integer(p,key+".riseSeconds",3);b.holdSeconds=integer(p,key+".holdSeconds",120);b.returnSeconds=integer(p,key+".returnSeconds",5);b.curve=integer(p,key+".curve",1);
    }
}
