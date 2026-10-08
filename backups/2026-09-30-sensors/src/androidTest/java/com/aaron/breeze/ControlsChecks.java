package com.aaron.breeze;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Isolated preferences and a fake controller: never sends BLE or changes real settings. */
public final class ControlsChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static TextView find(View root,String text){
        if(root instanceof TextView&&((TextView)root).getText().toString().equals(text))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){TextView result=find(group.getChildAt(i),text);if(result!=null)return result;}}
        return null;
    }
    private static void refresh(Activity screen){try{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("refresh");method.setAccessible(true);method.invoke(screen);}catch(Exception e){throw new RuntimeException(e);}}
    private static final class FakeController extends BreezeService {
        int started=0,lastAction=-1,lastValue=-1,lastDuration,lastNeutral,jogs;boolean writePending;
        @Override public boolean fresh(){return ready&&latest!=null;}
        @Override public void startSession(Protocol.Config c){started++;latest.flags|=3;changed();}
        @Override public void control(int action,int value){lastAction=action;lastValue=value;if(action==Protocol.STOP_RULES)latest.flags&=~3;changed();}
        @Override public boolean motorWritePending(){return writePending;}
        @Override public void jogMotor(boolean positive,int speed,int duration,int neutral){lastAction=Protocol.MOTOR_JOG;lastValue=speed;lastDuration=duration;lastNeutral=neutral;jogs++;motor.running=true;motor.continuousRun=false;motor.powered=true;motor.direction=positive?1:-1;motor.speed=speed;motor.remainingMs=duration;motor.neutral=neutral;motor.pulse=neutral+(positive?100:-100);motorBusy=true;}
        @Override public void runMotor(boolean positive,int speed,int neutral){jogMotor(positive,speed,0,neutral);lastAction=Protocol.MOTOR_RUN;motor.continuousRun=true;}
        @Override public void stopMotor(){lastAction=Protocol.MOTOR_STOP;motor.running=false;motor.continuousRun=false;motorBusy=false;writePending=false;motor.pulse=motor.neutral;}
        @Override public void setMotorNeutral(int neutral){lastAction=Protocol.MOTOR_NEUTRAL;lastNeutral=neutral;motor.neutral=neutral;motor.pulse=neutral;motor.powered=true;}
        @Override public void releaseMotor(){lastAction=4;motor.powered=false;motor.running=false;motor.continuousRun=false;motorBusy=false;writePending=false;}
        @Override public void changed(){} // Activity refresh is requested explicitly by the test.
    }
    private static final class QueueController extends BreezeService {
        @Override public int checkSelfPermission(String permission){return android.content.pm.PackageManager.PERMISSION_GRANTED;}
        @Override public boolean fresh(){return ready&&latest!=null;}
        @Override public void changed(){} // No context, broadcast, GATT or hardware is used.
    }
    private static java.lang.reflect.Field field(Class<?> type,String name)throws Exception{java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    @SuppressWarnings("unchecked") private static void checkStopQueue()throws Exception{
        QueueController controller=new QueueController();controller.ready=true;
        Class<?> pendingType=Class.forName("com.aaron.breeze.BreezeService$Pending");java.lang.reflect.Constructor<?> constructor=pendingType.getDeclaredConstructor(byte[].class,int.class,BreezeService.Result.class);constructor.setAccessible(true);
        Object inFlight=constructor.newInstance(Protocol.clock(1800000000L,1),1,null);field(BreezeService.class,"pending").set(controller,inFlight);
        java.util.ArrayDeque<Object> queue=(java.util.ArrayDeque<Object>)field(BreezeService.class,"queue").get(controller);
        queue.add(constructor.newInstance(Protocol.motorJog(true,20,100,1500,2),2,null));queue.add(constructor.newInstance(Protocol.motorNeutral(1498,3),3,null));queue.add(constructor.newInstance(Protocol.sync(0,4),4,null));
        queue.add(constructor.newInstance(Protocol.motorRun(false,50,1500,5),5,null));check(controller.motorWritePending(),"queued continuous run counts as pending motor write");
        controller.stopMotor();check(field(BreezeService.class,"pending").get(controller)==inFlight,"Stop preserves in-flight GATT ordering");check(queue.size()==2,"Stop cancels queued jog and trim only");check(((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==Protocol.MOTOR_STOP,"Stop goes ahead of background sync");
        controller.releaseMotor();check(queue.size()==2&&((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==4,"Release replaces queued Stop and preserves sync");
        controller.releaseMotor();check(queue.size()==2,"repeated Release does not accumulate commands");
        controller.stopMotor();check(queue.size()==2&&((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==Protocol.MOTOR_STOP,"latest Stop replaces queued Release and preserves sync");check(field(BreezeService.class,"pending").get(controller)==inFlight,"Stop and Release preserve in-flight command");
        controller.stopMotor();check(queue.size()==2,"repeated Stop does not accumulate commands");
        field(pendingType,"written").setBoolean(inFlight,true);field(pendingType,"acknowledged").setBoolean(inFlight,true);field(pendingType,"error").setInt(inFlight,9);
        java.lang.reflect.Method complete=BreezeService.class.getDeclaredMethod("completeCommand");complete.setAccessible(true);complete.invoke(controller);check(queue.size()==1&&((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==Protocol.MOTOR_STOP,"failed prior command retains queued Stop");
        field(BreezeService.class,"pending").set(controller,inFlight);controller.releaseMotor();complete.invoke(controller);check(queue.size()==1&&((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==4,"failed prior command retains queued Release");
        queue.clear();field(BreezeService.class,"pending").set(controller,null);controller.motor=new Protocol.MotorReading();controller.motor.version=4;controller.motor.running=true;
        java.lang.reflect.Method busy=BreezeService.class.getDeclaredMethod("refreshMotorBusy");busy.setAccessible(true);busy.invoke(controller);check(controller.motorBusy,"running telemetry stays busy after ACK");controller.motor.running=false;busy.invoke(controller);check(!controller.motorBusy,"completion telemetry releases busy");
        controller.latest=new Protocol.Reading();controller.motor.testing=true;field(BreezeService.class,"pending").set(controller,inFlight);controller.runMotor(true,20,1500);check(queue.isEmpty(),"v4 service rejects continuous run");
        controller.motor.version=5;controller.motor.running=true;controller.motor.continuousRun=true;busy.invoke(controller);controller.runMotor(false,40,1500);check(queue.size()==1&&((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==Protocol.MOTOR_RUN,"active continuous run accepts update after previous ACK");controller.runMotor(true,60,1500);check(queue.size()==1,"pending continuous write blocks repeated queueing");controller.stopMotor();check(queue.size()==1&&((byte[])field(pendingType,"bytes").get(queue.peek()))[4]==Protocol.MOTOR_STOP,"Stop cancels queued continuous update");queue.clear();field(BreezeService.class,"pending").set(controller,null);
    }
    public static void run(Instrumentation test) throws Exception {
        Context target=test.getTargetContext();String prefsName="temporary-rules-test-"+System.nanoTime();
        Context isolated=new ContextWrapper(target){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefsName,mode);}};
        try{
            Protocol.Config c=Settings.load(isolated);c.low=27;c.medium=30;c.high=34;c.dwell=60;c.ambientWeight=20;c.humidityThreshold=70;c.humidityBoost=5;
            Settings.saveRules(isolated,"Summer",c);c.low=28;Settings.saveRules(isolated,"Winter",c);
            check(Settings.ruleNames(isolated).size()==2,"two named profiles");
            c.offAngle=120;c.highAngle=105;c.mediumAngle=90;c.lowAngle=75;c.calibrated=true;Settings.save(isolated,c);
            Settings.loadRules(isolated,"Summer");Protocol.Config loaded=Settings.load(isolated);
            check(loaded.low==27&&loaded.high==34&&loaded.dwell==60&&loaded.ambientWeight==20&&loaded.humidityThreshold==70&&loaded.humidityBoost==5,"all rule fields restored");
            check(loaded.offAngle==120&&loaded.lowAngle==75&&loaded.calibrated,"loading rules preserves calibration");
            Settings.loadRules(isolated,"Winter");check(Settings.load(isolated).low==28,"profile selection");
            try{Settings.saveRules(isolated,"  ",c);throw new AssertionError("blank name accepted");}catch(IllegalArgumentException expected){}
        }finally{target.deleteSharedPreferences(prefsName);}
        test.runOnMainSync(()->{try{checkStopQueue();}catch(Exception e){throw new RuntimeException(e);}});
        final FakeController[] fake={null};
        test.runOnMainSync(()->{check(BreezeService.current==null,"real controller service must not be running during UI test");fake[0]=new FakeController();fake[0].ready=true;fake[0].status="Connected";fake[0].latest=new Protocol.Reading();fake[0].latest.probe=31;fake[0].latest.room=23;fake[0].latest.speed=2;BreezeService.current=fake[0];});
        Activity activity=null;
        try{
            activity=test.startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;
            test.waitForIdleSync();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();
                check(!find(root,"Connect controller").isShown(),"connection box hidden when connected");
                check(find(root,"Off").isShown()&&find(root,"High").isShown()&&find(root,"Medium").isShown()&&find(root,"Low").isShown(),"four visible fan controls");
                for(String name:new String[]{"Off","High","Medium","Low"})find(root,name).performClick();
                check(fake[0].lastAction==2&&fake[0].lastValue==1,"manual Low routes correctly");
                find(root,"Start").performClick();check(fake[0].started==1,"Start applies rules and records without a dialog");
                refresh(screen);
            });
            test.waitForIdleSync();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();find(root,"Stop").performClick();check(fake[0].lastAction==Protocol.STOP_RULES,"Stop uses preserving action");
                refresh(screen);
            });
            test.waitForIdleSync();
            Bitmap image=test.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(target.getExternalFilesDir(null),"controls-connected-test.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();find(root,"Rules").performClick();root=screen.getWindow().getDecorView();
                check(find(root,"Save temperature rules").isShown()&&find(root,"Load settings").isShown(),"save and load visible at top");
                find(root,"Save temperature rules").performClick();
            });
            test.waitForIdleSync();
            check(!test.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Cancel").isEmpty(),"save prompts for a name");
            // Cancel the prompt without changing the user's preferences.
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();find(root,"Tonight").performClick();root=screen.getWindow().getDecorView();find(root,"Motor test").performClick();root=screen.getWindow().getDecorView();
                check(!find(root,"Direction A").isEnabled(),"missing telemetry prevents jog");
                fake[0].motor=new Protocol.MotorReading();fake[0].motor.version=3;fake[0].motor.testing=true;refresh(screen);check(!find(root,"Direction A").isEnabled(),"positional firmware cannot jog");
                fake[0].motor.version=4;fake[0].motor.neutral=1495;refresh(screen);
                SeekBar speed=findSlider(root,"Speed"),duration=findSlider(root,"Duration"),trim=findSlider(root,"Stop trim");
                check(speed.getMin()==1&&speed.getMax()==100&&duration.getMin()==20&&duration.getMax()==2000&&trim.getMin()==1400&&trim.getMax()==1600,"all slider ranges match protocol bounds");
                check(speed.getProgress()==20&&duration.getProgress()==100&&trim.getProgress()==1495,"conservative jog defaults and controller trim");
                check(find(root,"Timed").isSelected()&&!find(root,"Continuous").isEnabled(),"v4 keeps timed fallback and disables continuous option");
                int previous=fake[0].lastAction;speed.setProgress(35);duration.setProgress(240);trim.setProgress(1498);check(fake[0].lastAction==previous&&fake[0].jogs==0,"sliders alone send no movement");
                find(root,"Direction A").performClick();refresh(screen);check(fake[0].jogs==1&&fake[0].motor.direction==1&&fake[0].lastValue==35&&fake[0].lastDuration==240&&fake[0].lastNeutral==1498,"direction A sends selected speed duration and trim");
                check(!find(root,"Direction A").isEnabled()&&!find(root,"Direction B").isEnabled()&&find(root,"Stop").isEnabled(),"jogs blocked while running and Stop remains enabled");
                find(root,"Stop").performClick();refresh(screen);check(fake[0].lastAction==Protocol.MOTOR_STOP&&!fake[0].motor.running,"Stop ends motion");
                find(root,"Direction B").performClick();refresh(screen);check(fake[0].motor.direction==-1&&fake[0].jogs==2,"direction B reverses command");
                find(root,"Stop").performClick();refresh(screen);find(root,"Apply stop trim").performClick();refresh(screen);check(fake[0].lastAction==Protocol.MOTOR_NEUTRAL&&fake[0].lastNeutral==1498&&!fake[0].motor.running,"explicit trim does not start jog");
                fake[0].motorBusy=true;refresh(screen);check(!find(root,"Direction A").isEnabled()&&find(root,"Stop").isEnabled(),"Stop allowed while command busy");fake[0].motorBusy=false;
                fake[0].latest.flags|=1;refresh(screen);check(!find(root,"Direction A").isEnabled()&&find(root,"Stop").isEnabled(),"recording guard retains Stop");fake[0].latest.flags&=~1;
                fake[0].ready=false;refresh(screen);check(!find(root,"Direction A").isEnabled()&&!find(root,"Stop").isEnabled(),"disconnect disables commands");fake[0].ready=true;refresh(screen);
                find(root,"Release motor").performClick();refresh(screen);check(!fake[0].motor.powered&&find(root,"Direction A").isEnabled(),"released continuous servo can jog without endpoint initialization");
                previous=fake[0].lastAction;fake[0].motor.version=5;refresh(screen);check(find(root,"Continuous").isSelected()&&((View)duration.getParent()).getVisibility()==View.GONE&&fake[0].lastAction==previous,"v5 defaults to Continuous without a command");
                find(root,"Timed").performClick();check(((View)duration.getParent()).getVisibility()==View.VISIBLE&&fake[0].lastAction==previous,"Timed selection alone sends no command");find(root,"Continuous").performClick();speed.setProgress(60);check(fake[0].lastAction==previous,"continuous selection and speed slider send no command");
                find(root,"Direction A").performClick();refresh(screen);check(fake[0].lastAction==Protocol.MOTOR_RUN&&fake[0].motor.continuousRun&&fake[0].lastDuration==0&&fake[0].lastValue==60,"continuous tap starts indefinite run");check(find(root,"Direction B").isEnabled()&&find(root,"Stop").isEnabled(),"continuous run can change direction after ACK");
                speed.setProgress(70);find(root,"Direction B").performClick();refresh(screen);check(fake[0].motor.direction==-1&&fake[0].lastValue==70,"continuous tap updates speed and direction");
                fake[0].writePending=true;refresh(screen);check(!find(root,"Direction A").isEnabled()&&find(root,"Stop").isEnabled(),"pending ACK blocks another continuous command but keeps Stop");fake[0].writePending=false;refresh(screen);
                previous=fake[0].lastAction;find(root,"Timed").performClick();check(fake[0].lastAction==previous&&fake[0].motor.continuousRun&&!find(root,"Direction A").isEnabled(),"switching mode does not stop or replace active motion");find(root,"Stop").performClick();refresh(screen);
                find(root,"Direction A").performClick();refresh(screen);check(fake[0].lastAction==Protocol.MOTOR_JOG&&fake[0].lastDuration==240&&!fake[0].motor.continuousRun,"Timed retains selected duration");find(root,"Continuous").performClick();check(!find(root,"Direction A").isEnabled(),"cannot replace an active timed jog with continuous run");find(root,"Stop").performClick();refresh(screen);
                fake[0].motor.version=4;refresh(screen);check(!find(root,"Continuous").isEnabled()&&find(root,"Timed").isSelected(),"v4 fallback prevents sending continuous action");
                find(root,"Tonight").performClick();root=screen.getWindow().getDecorView();check(!find(root,"Start").isEnabled()&&!find(root,"Off").isEnabled()&&!find(root,"High").isEnabled(),"continuous firmware gates positional fan and session controls");
                find(root,"Setup").performClick();root=screen.getWindow().getDecorView();check(!find(root,"Save servo positions").isEnabled(),"continuous firmware protects positional calibration");
            });
        }finally{
            final Activity close=activity;test.runOnMainSync(()->{BreezeService.current=null;if(close!=null)close.finish();});
        }
    }
    private static SeekBar findSlider(View root,String name){if(root instanceof SeekBar&&name.contentEquals(root.getContentDescription()))return (SeekBar)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){SeekBar slider=findSlider(group.getChildAt(i),name);if(slider!=null)return slider;}}return null;}
}
