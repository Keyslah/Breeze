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
        int started=0,lastAction=-1,lastValue=-1;
        @Override public boolean fresh(){return ready&&latest!=null;}
        @Override public void startSession(Protocol.Config c){started++;latest.flags|=3;changed();}
        @Override public void control(int action,int value){lastAction=action;lastValue=value;if(action==Protocol.STOP_RULES)latest.flags&=~3;changed();}
        @Override public void testMotor(int action,int value){lastAction=action;lastValue=value;if(action==Protocol.MOTOR_PLUS)motor.pulse=Math.min(2400,motor.pulse+value);if(action==Protocol.MOTOR_MINUS)motor.pulse=Math.max(500,motor.pulse-value);if(action==4)motor.powered=false;}
        @Override public void changed(){} // Activity refresh is requested explicitly by the test.
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
                check(!find(root,"+ Step").isEnabled(),"missing motor telemetry prevents test movement");
                fake[0].motor=new Protocol.MotorReading();fake[0].motor.testing=true;refresh(screen);check(!find(root,"+ Step").isEnabled(),"uninitialized servo cannot step");
                fake[0].motor.powered=true;fake[0].motor.pulse=1500;refresh(screen);
                find(root,"+ Step").performClick();check(fake[0].lastAction==Protocol.MOTOR_PLUS&&fake[0].lastValue==2&&fake[0].motor.pulse==1502,"one tiny positive step");
                find(root,"− Step").performClick();check(fake[0].motor.pulse==1500,"reverse step returns to previous pulse");
                SeekBar slider=findSlider(root);int previous=fake[0].lastAction;slider.setProgress(20);check(fake[0].lastAction==previous&&fake[0].motor.pulse==1500,"slider alone sends no motion");
                find(root,"+ Step").performClick();check(fake[0].lastValue==20&&fake[0].motor.pulse==1520,"step uses selected slider amount");
                fake[0].motor.pulse=2399;refresh(screen);check(find(root,"+ Step").isEnabled(),"partial upper step allowed");find(root,"+ Step").performClick();refresh(screen);check(fake[0].motor.pulse==2400&&!find(root,"+ Step").isEnabled(),"upper endpoint reached and guarded");check(slider.getProgress()==20&&fake[0].lastValue==20,"upper endpoint preserves selected step");
                find(root,"− Step").performClick();refresh(screen);check(fake[0].motor.pulse==2380,"reverse from endpoint uses full selected step");
                fake[0].motor.pulse=501;refresh(screen);check(find(root,"− Step").isEnabled(),"partial lower step allowed");find(root,"− Step").performClick();refresh(screen);check(fake[0].motor.pulse==500&&!find(root,"− Step").isEnabled(),"lower endpoint reached and guarded");check(slider.getProgress()==20&&fake[0].lastValue==20,"lower endpoint preserves selected step");
                fake[0].motor.pulse=1500;
                fake[0].latest.flags|=1;refresh(screen);check(!find(root,"− Step").isEnabled(),"recording blocks tests");fake[0].latest.flags&=~1;
                fake[0].motor.pulse=1500;refresh(screen);find(root,"Release motor").performClick();refresh(screen);check(!find(root,"+ Step").isEnabled(),"released servo needs initialization");
            });
        }finally{
            final Activity close=activity;test.runOnMainSync(()->{BreezeService.current=null;if(close!=null)close.finish();});
        }
    }
    private static SeekBar findSlider(View root){if(root instanceof SeekBar)return (SeekBar)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){SeekBar slider=findSlider(group.getChildAt(i));if(slider!=null)return slider;}}return null;}
}
