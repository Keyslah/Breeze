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
        private final Context context;
        int started,lastAction=-1; boolean stale,manualPending;
        FakeController(Context context){this.context=context;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override public boolean fresh(){return ready&&!stale;}
        @Override public void startSession(){started++;latest.flags|=1;RecordingVoiceController.onTelemetry(context,true);}
        @Override public void control(int action,int value){lastAction=action;latest.flags&=~1;RecordingVoiceController.onTelemetry(context,false);}
        @Override public boolean freshFan(){return fresh()&&irFirmware&&fan!=null;}
        @Override public boolean fanWritePending(){return manualPending;}
        @Override public boolean manualIrReady(){return freshFan()&&!manualPending&&fan.learning==255;}
        @Override public boolean fanCommand(int action,int value,int argument){lastAction=action;if(action==Protocol.LEARN){fan.learning=value;fan.learnState=1;}else if(action==Protocol.MARK_TEST)fan.phase=value;return true;}
        @Override public void startContactTest(){latest.flags|=1;fan.phase=1;RecordingVoiceController.onTelemetry(context,true);}
        @Override public void changed(){}
    }
    private static String temperature(double c,boolean f){return String.format(Locale.US,"%.1f°%s",f?c*1.8+32:c,f?"F":"C");}
    public static void run(Instrumentation test)throws Exception {
        Context context=test.getTargetContext();boolean originalF=true;
        FakeController fake=new FakeController(context);fake.ready=true;fake.sensorFirmware=true;fake.latest=new Protocol.Reading();fake.latest.flags=128;fake.latest.probe=31.3;fake.latest.thirdProbe=36.25;fake.latest.room=23.1;fake.latest.humidity=58.2;fake.irFirmware=true;fake.fan=new Protocol.FanState();fake.fan.secondProbe=30.5;fake.fan.learning=255;fake.fan.resultSlot=255;fake.fan.lastSent=255;
        test.runOnMainSync(()->{check(BreezeService.current==null,"real service must be stopped");BreezeService.current=fake;});
        Activity activity=null;
        try{
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();
                fake.fan.flags|=4;
                int[] settings={0,1,2,3,4,5,6,7,8,9,10,1,10,9};
                String[] labels={"0","1","2","3","4","5","6","7","8","9","H","1","H","9"};
                for(int index=0;index<settings.length;index++){
                    fake.fan.speed=settings[index];refresh(screen);
                    TextView current=root.findViewWithTag("homeCurrentFanSetting");
                    check(labels[index].contentEquals(current.getText()),"Home follows live fan setting "+labels[index]);
                }
                fake.fan.flags&=~4;fake.fan.speed=0;refresh(screen);
                for(String name:new String[]{"D0 body","D1 body","D2 body","Highest body temp","SHT40 temp","Humidity"})check(find(root,name)!=null&&find(root,name).isShown(),name+" is visible at launch");
                for(String absent:new String[]{"Motor test","Rules","Start","Off","Low","Medium","High","Temperature timeline","Connect controller"})check(find(root,absent)==null,"home excludes "+absent);
                check(find(root,temperature(31.3,originalF))!=null,"body reading");check(find(root,temperature(23.1,originalF))!=null,"fan reading");check(find(root,"58.2%")!=null,"humidity reading");
                check(find(root,"86.9°F")!=null,"D1 raw reading visible");
                check(find(root,"97.3°F")!=null,"D2 raw reading visible");
                fake.latest.thirdProbe=Double.NaN;refresh(screen);check(find((View)find(root,"D2 body").getParent(),"—")!=null,"missing D2 shows a dash");check(find(root,"97.3°F")==null&&find(root,temperature(31.3,originalF))!=null&&find(root,"86.9°F")!=null&&find(root,temperature(23.1,originalF))!=null&&find(root,"58.2%")!=null,"missing D2 keeps the other readings");fake.latest.thirdProbe=36.25;refresh(screen);
                fake.latest.probe=29;refresh(screen);check(find(root,"84.2°F")!=null,"sudden drop remains raw; no contact rules");fake.latest.probe=31.3;refresh(screen);
                fake.latest.probe=Double.NaN;refresh(screen);root=screen.getWindow().getDecorView();check(find(root,"—")!=null&&find(root,"58.2%")!=null,"missing probe keeps humidity");
                fake.stale=true;refresh(screen);check(find(root,"58.2%")==null,"stale values disappear");fake.stale=false;fake.latest.probe=31.3;refresh(screen);
                TextView homeStart=find(root,"Start recording");check(homeStart!=null&&homeStart.isEnabled(),"home recording button enabled");
                fake.latest.flags&=~128;refresh(screen);check(!homeStart.isEnabled(),"full controller storage cannot start another recording");
                fake.latest.flags|=1;refresh(screen);check(find(root,"Stop recording").isEnabled(),"stop remains available if an active controller reports a storage fault");
                fake.latest.flags=128;refresh(screen);
                check(root.findViewWithTag("homeReadings") instanceof LiveReadingDetailsView&&root.findViewWithTag("homeControls")!=null,"Home separates its live readings and recording controls into the two halves");
                homeStart.performClick();check(fake.started==1,"home starts recording");refresh(screen);find(root,"Stop recording").performClick();check(fake.lastAction==Protocol.STOP_RECORDING,"home stops recording");refresh(screen);fake.started=0;
                find(root,"Setup").performClick();root=screen.getWindow().getDecorView();check(find(root,"Firmware Update")==null,"firmware update is absent from Setup");check(find(root,"Start recording")==null&&find(root,"Stop recording")==null,"Setup does not duplicate Home recording controls");
                find(root,"Home").performClick();root=screen.getWindow().getDecorView();fake.sensorFirmware=false;refresh(screen);check(!find(root,"Start recording").isEnabled(),"old firmware cannot start recording");
                fake.sensorFirmware=true;find(root,"Fan").performClick();root=screen.getWindow().getDecorView();
                check(find(root,"RECEIVER")==null,"canceled receiver panel remains hidden");
                fake.manualPending=true;refresh(screen);check(!find(root,"Power").isEnabled()&&!find(root,"Speed +").isEnabled()&&!find(root,"Speed -").isEnabled(),"manual buttons wait until the controller can accept the first tap");fake.manualPending=false;refresh(screen);check(find(root,"Power").isEnabled()&&find(root,"Speed +").isEnabled()&&find(root,"Speed -").isEnabled(),"manual buttons become ready after setup finishes");
                check(find(root,"Automatic control · off")==null,"unused automatic control placeholder removed");
                find(root,"Start probe test").performClick();refresh(screen);find(root,"Mark: lying on probes").performClick();check(fake.fan.phase==2,"lying marker routes");find(root,"Mark: off probes").performClick();check(fake.fan.phase==3,"off marker routes");find(root,"Stop probe test").performClick();refresh(screen);
                find(root,"Learn Remote").performClick();root=screen.getWindow().getDecorView();find(root,"Learn").performClick();refresh(screen);check(find(root,"Point the Dreo remote at the ESP32 and press Power.")!=null,"learn workflow waits for controller");check(find(root,"Learned")==null,"not optimistically saved");
                fake.fan.learning=255;fake.fan.learnState=2;fake.fan.resultSlot=0;fake.fan.learned=1;refresh(screen);check(find(root,"Learned")!=null&&find(root,"Relearn")!=null,"save confirmation reflected");find(root,"Test").performClick();check(fake.lastAction==Protocol.SEND_IR,"manual test routes to learned IR");
                find(root,"Home").performClick();check(find(screen.getWindow().getDecorView(),"Humidity").isShown(),"home restored");
            });
        }finally{
            if(activity!=null){Activity closing=activity;test.runOnMainSync(closing::finish);test.waitForIdleSync();}
            test.runOnMainSync(()->{RecordingVoiceController.startFailed(context);RecordingVoiceController.onTelemetry(context,false);BreezeService.current=null;});

        }
    }
}
