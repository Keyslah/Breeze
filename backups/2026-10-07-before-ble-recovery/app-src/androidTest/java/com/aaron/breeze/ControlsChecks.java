package com.aaron.breeze;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Sensor UI checks use an isolated fake connection, never physical hardware. */
public final class ControlsChecks {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static TextView find(View root,String value){
        if(root instanceof TextView&&((TextView)root).getText().toString().equals(value))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView result=find(g.getChildAt(i),value);if(result!=null)return result;}}
        return null;
    }
    private static void refresh(Activity a){try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("refresh");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}}
    private static class FakeController extends BreezeService {
        int started,lastAction=-1; boolean stale;
        @Override public boolean fresh(){return ready&&!stale;}
        @Override public void startSession(){started++;latest.flags|=1;}
        @Override public void control(int action,int value){lastAction=action;latest.flags&=~1;}
        @Override public boolean freshFan(){return fresh()&&irFirmware&&fan!=null;}
        @Override public void fanCommand(int action,int value,int argument){lastAction=action;if(action==Protocol.LEARN){fan.learning=value;fan.learnState=1;}else if(action==Protocol.MARK_TEST)fan.phase=value;}
        @Override public void startContactTest(){latest.flags|=1;fan.phase=1;}
        @Override public void changed(){}
    }
    private static String temperature(double c,boolean f){return String.format(Locale.US,"%.1f°%s",f?c*1.8+32:c,f?"F":"C");}
    public static void run(Instrumentation test)throws Exception {
        Context context=test.getTargetContext();boolean originalF=true;
        FakeController fake=new FakeController();fake.ready=true;fake.sensorFirmware=true;fake.latest=new Protocol.Reading();fake.latest.probe=31.3;fake.latest.room=23.1;fake.latest.humidity=58.2;fake.irFirmware=true;fake.fan=new Protocol.FanState();fake.fan.secondProbe=30.5;fake.fan.learning=255;fake.fan.resultSlot=255;fake.fan.lastSent=255;
        test.runOnMainSync(()->{check(BreezeService.current==null,"real service must be stopped");BreezeService.current=fake;});
        Activity activity=null;
        try{
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();
                for(String name:new String[]{"Under-sheet · D0","Under-sheet · D1","Room temperature","Humidity"})check(find(root,name)!=null&&find(root,name).isShown(),name+" is visible at launch");
                for(String absent:new String[]{"Motor test","Rules","Start","Off","Low","Medium","High","Temperature timeline","Connect controller"})check(find(root,absent)==null,"home excludes "+absent);
                check(find(root,temperature(31.3,originalF))!=null,"body reading");check(find(root,temperature(23.1,originalF))!=null,"fan reading");check(find(root,"58.2%")!=null,"humidity reading");
                check(find(root,"86.9°F")!=null,"D1 raw reading visible");
                fake.latest.probe=29;refresh(screen);check(find(root,"84.2°F")!=null,"sudden drop remains raw; no contact rules");fake.latest.probe=31.3;refresh(screen);
                fake.latest.probe=Double.NaN;refresh(screen);root=screen.getWindow().getDecorView();check(find(root,"—")!=null&&find(root,"58.2%")!=null,"missing probe keeps humidity");
                fake.stale=true;refresh(screen);check(find(root,"58.2%")==null,"stale values disappear");fake.stale=false;fake.latest.probe=31.3;refresh(screen);
                TextView homeStart=find(root,"Start recording");check(homeStart!=null&&homeStart.isEnabled(),"home recording button enabled");
                ViewGroup homeContent=(ViewGroup)homeStart.getParent();View humidityCard=(View)find(root,"Humidity").getParent();check(homeContent.indexOfChild(homeStart)==homeContent.indexOfChild(humidityCard)+1,"recording directly below humidity");
                homeStart.performClick();check(fake.started==1,"home starts recording");refresh(screen);find(root,"Stop recording").performClick();check(fake.lastAction==Protocol.STOP_RECORDING,"home stops recording");refresh(screen);fake.started=0;
                find(root,"Setup").performClick();root=screen.getWindow().getDecorView();check(find(root,"Firmware Update")==null,"firmware update is absent from Setup");find(root,"Start recording").performClick();check(fake.started==1,"recording starts");refresh(screen);find(root,"Stop recording").performClick();check(fake.lastAction==Protocol.STOP_RECORDING,"recording stops");refresh(screen);
                fake.sensorFirmware=false;refresh(screen);check(!find(root,"Start recording").isEnabled(),"old firmware cannot start recording");
                fake.sensorFirmware=true;find(root,"Fan").performClick();root=screen.getWindow().getDecorView();
                check(find(root,"Automatic control · off")!=null,"automatic rules deferred");
                find(root,"Start probe test").performClick();refresh(screen);find(root,"Mark: lying on probes").performClick();check(fake.fan.phase==2,"lying marker routes");find(root,"Mark: off probes").performClick();check(fake.fan.phase==3,"off marker routes");find(root,"Stop recording").performClick();refresh(screen);
                find(root,"Learn Remote").performClick();root=screen.getWindow().getDecorView();find(root,"Learn").performClick();refresh(screen);check(find(root,"Point the Dreo remote at the ESP32 and press Power.")!=null,"learn workflow waits for controller");check(find(root,"Learned")==null,"not optimistically saved");
                fake.fan.learning=255;fake.fan.learnState=2;fake.fan.resultSlot=0;fake.fan.learned=1;refresh(screen);check(find(root,"Learned")!=null&&find(root,"Relearn")!=null,"save confirmation reflected");find(root,"Test").performClick();check(fake.lastAction==Protocol.SEND_IR,"manual test routes to learned IR");
                find(root,"Home").performClick();check(find(screen.getWindow().getDecorView(),"Humidity").isShown(),"home restored");
            });
        }finally{
            if(activity!=null){Activity closing=activity;test.runOnMainSync(closing::finish);test.waitForIdleSync();}
            test.runOnMainSync(()->BreezeService.current=null);

        }
    }
}
