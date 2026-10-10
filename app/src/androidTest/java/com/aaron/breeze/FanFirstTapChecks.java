package com.aaron.breeze;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Window-injected touch regression. All fan/transport paths terminate in the fake. */
public final class FanFirstTapChecks {
    private FanFirstTapChecks(){}
    private interface MainAction {void run() throws Exception;}
    private interface MainCondition {boolean get() throws Exception;}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    // Never throw a fixture assertion through the application's main Looper.
    private static void onMain(Instrumentation test,MainAction action)throws Exception {
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("UI fixture failure",failure[0]);
    }
    private static void await(Instrumentation test,MainCondition condition,String reason)throws Exception {
        long deadline=SystemClock.uptimeMillis()+5000;
        do{boolean[] done={false};onMain(test,()->done[0]=condition.get());if(done[0])return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError(reason);
    }
    private static TextView text(View view,String label){
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    private static View target(Activity screen,String label){View view=text(screen.getWindow().getDecorView(),label);check(view!=null,"Missing control: "+label);return view;}
    private static boolean resumed(Activity screen)throws Exception {Field field=MainActivity.class.getDeclaredField("activityResumed");field.setAccessible(true);return field.getBoolean(screen);}
    private static ScrollView scrollParent(View view){while(view.getParent() instanceof View){view=(View)view.getParent();if(view instanceof ScrollView)return (ScrollView)view;}return null;}
    private static boolean fullyVisible(View view){Rect local=new Rect();return view.isShown()&&view.getLocalVisibleRect(local)&&local.height()>=view.getHeight()-2&&local.width()>=view.getWidth()-2;}
    private static float[] center(View view){Rect local=new Rect();check(view.isShown()&&view.getLocalVisibleRect(local)&&!local.isEmpty(),"Control is not visible");int[] location=new int[2];view.getLocationOnScreen(location);return new float[]{location[0]+local.exactCenterX(),location[1]+local.exactCenterY()};}
    private static void event(Instrumentation test,long down,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{test.sendPointerSync(event);}finally{event.recycle();}}
    private static void tap(Instrumentation test,Activity screen,String label)throws Exception {
        float[][] point={null};onMain(test,()->{View view=target(screen,label);check(view.isEnabled(),label+" is disabled before touch");check(fullyVisible(view),label+" is clipped before touch");point[0]=center(view);});
        long down=SystemClock.uptimeMillis();event(test,down,MotionEvent.ACTION_DOWN,point[0][0],point[0][1]);SystemClock.sleep(70);event(test,down,MotionEvent.ACTION_UP,point[0][0],point[0][1]);test.waitForIdleSync();
    }
    private static void reveal(Instrumentation test,Activity screen,String label)throws Exception {
        for(int attempt=0;attempt<12;attempt++){
            boolean[] visible={false};float[][] swipe={null};onMain(test,()->{View view=target(screen,label);visible[0]=fullyVisible(view);if(visible[0])return;ScrollView scroll=scrollParent(view);check(scroll!=null,"No scroll parent for "+label);int[] location=new int[2],targetLocation=new int[2];scroll.getLocationOnScreen(location);view.getLocationOnScreen(targetLocation);float x=location[0]+scroll.getWidth()*0.5f,top=location[1]+scroll.getHeight()*0.25f,bottom=location[1]+scroll.getHeight()*0.75f;boolean below=targetLocation[1]>=location[1]+scroll.getHeight()/2;swipe[0]=new float[]{x,below?bottom:top,below?top:bottom};});
            if(visible[0])return;
            float[] path=swipe[0];long down=SystemClock.uptimeMillis();event(test,down,MotionEvent.ACTION_DOWN,path[0],path[1]);
            for(int step=1;step<=10;step++){SystemClock.sleep(25);event(test,down,MotionEvent.ACTION_MOVE,path[0],path[1]+(path[2]-path[1])*step/10);}
            // Stop at the end of the drag so a fling does not intentionally consume the next tap.
            SystemClock.sleep(130);event(test,down,MotionEvent.ACTION_MOVE,path[0],path[2]);SystemClock.sleep(35);event(test,down,MotionEvent.ACTION_UP,path[0],path[2]);test.waitForIdleSync();SystemClock.sleep(80);
        }
        throw new AssertionError("Could not reveal "+label+" with touchscreen drags");
    }
    private static final class FakeController extends BreezeService {
        final List<Integer> taps=new ArrayList<>();String unexpected="";int anchorCalls;int retainedSpeed=1;
        FakeController(Context context){attachBaseContext(context);ready=true;sensorFirmware=true;irFirmware=true;latest=new Protocol.Reading();latest.flags=128;latest.probe=31;latest.room=23;latest.humidity=55;fan=new Protocol.FanState();fan.flags=4;fan.speed=1;fan.learning=255;fan.resultSlot=255;fan.lastSent=255;fan.learned=4095;}
        private void unexpected(String operation){if(unexpected.isEmpty())unexpected=operation;}
        @Override public boolean fresh(){return ready;}
        @Override public boolean freshFan(){return ready&&fan!=null;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override public boolean manualIrReady(){return true;}
        @Override public boolean fanWritePending(){return false;}
        @Override public void changed(){}
        @Override public boolean fanCommand(int action,int value,int argument){
            if(action!=Protocol.SEND_IR||value<0||value>=Protocol.COMMANDS.length){unexpected("fanCommand "+action+"/"+value);return false;}
            taps.add(value);fan.lastSent=value;fan.sendCounter++;
            if(value==0){if(fan.speed>0){retainedSpeed=fan.speed;fan.speed=0;}else fan.speed=retainedSpeed;}
            else if(value==1&&fan.speed>0)fan.speed=fan.speed==10?1:fan.speed+1;
            else if(value==2&&fan.speed>0)fan.speed=fan.speed==1?10:fan.speed-1;
            fan.target=fan.speed;return true;
        }
        @Override public void setFanAnchor(int speed){anchorCalls++;unexpected("unexpected anchor correction "+speed);}
        @Override public boolean applyLogicProfile(LogicProfile profile){unexpected("unexpected profile upload");return false;}
        @Override public void command(IntFunction<byte[]> make,Result result){unexpected("unexpected transport command");}
        @Override public void scan(){unexpected("unexpected Bluetooth scan");}
        @Override public void connect(String address){unexpected("unexpected Bluetooth connect");}
        @Override public void download(){unexpected("unexpected download");}
        @Override public void control(int action,int value){unexpected("unexpected recording control");}
        @Override public void startSession(){unexpected("unexpected recording start");}
        @Override public void startContactTest(){unexpected("unexpected probe test");}
    }
    private static void firstTap(Instrumentation test,Activity screen,FakeController fake,String label,String scenario,StringBuilder report)throws Exception {
        reveal(test,screen,label);int[] before={0},after={0},slot={-1};String[] state={""};
        onMain(test,()->{before[0]=fake.taps.size();View focus=screen.getCurrentFocus();state[0]="focus="+(focus==null?"none":focus.getClass().getSimpleName())+", speed="+fake.fan.speed;});
        tap(test,screen,label);onMain(test,()->{after[0]=fake.taps.size();if(after[0]>before[0])slot[0]=fake.taps.get(before[0]);});
        if(after[0]!=before[0]+1){tap(test,screen,label);int[] second={0};onMain(test,()->second[0]=fake.taps.size());throw new AssertionError(scenario+" "+label+": first touch dispatched "+(after[0]-before[0])+", second dispatched "+(second[0]-after[0])+"; "+state[0]);}
        check(label.equals(Protocol.COMMANDS[slot[0]]),scenario+" sent wrong command");
        onMain(test,()->{check(fake.unexpected.isEmpty(),fake.unexpected);check(fake.anchorCalls==0,"Navigation changed the fan anchor");});
        report.append("PASS ").append(scenario).append(" · ").append(label).append(": first touchscreen tap dispatched once; ").append(state[0]).append('\n');
    }
    private static Map<String,Object> snapshot(SharedPreferences prefs){Map<String,Object> result=new HashMap<>();for(Map.Entry<String,?> item:prefs.getAll().entrySet()){Object value=item.getValue();result.put(item.getKey(),value instanceof Set?new HashSet<>((Set<?>)value):value);}return result;}
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs,Map<String,Object> saved){
        SharedPreferences.Editor edit=prefs.edit();for(String key:prefs.getAll().keySet())if(!saved.containsKey(key))edit.remove(key);
        for(Map.Entry<String,Object> item:saved.entrySet()){String key=item.getKey();Object value=item.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);else throw new AssertionError("Unsupported preference type: "+key);}
        check(edit.commit(),"Restore pre-test preferences");check(snapshot(prefs).equals(saved),"Preference snapshot restored exactly");
    }
    public static String run(Instrumentation test)throws Exception {
        Context context=test.getTargetContext();if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return "SKIPPED: unlock the phone before touchscreen checks.\n";
        boolean[] occupied={false};onMain(test,()->occupied[0]=BreezeService.current!=null||BedtimeVoiceService.current!=null);if(occupied[0])return "SKIPPED: existing connection/voice service left untouched.\n";
        SharedPreferences prefs=Settings.prefs(context);Map<String,Object> saved=snapshot(prefs);Activity activity=null;FakeController fake=new FakeController(context);boolean[] installed={false};StringBuilder report=new StringBuilder();
        try{
            onMain(test,()->{check(BreezeService.current==null&&BedtimeVoiceService.current==null,"Service became occupied before fixture start");BreezeService.current=fake;installed[0]=true;});
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();await(test,()->screen.hasWindowFocus()&&resumed(screen),"Fixture Activity did not become interactive");
            tap(test,screen,"Fan");
            for(String label:new String[]{"Power","Speed +","Speed -"}){tap(test,screen,"Home");tap(test,screen,"Fan");firstTap(test,screen,fake,label,"Fan → Home → Fan",report);}
            for(String label:new String[]{"Power","Speed +","Speed -"}){
                reveal(test,screen,label);onMain(test,()->check(screen.moveTaskToBack(true),"Could not background fixture Activity"));await(test,()->!resumed(screen),"Fixture did not pause");
                context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));await(test,()->resumed(screen)&&screen.hasWindowFocus(),"Same fixture Activity did not resume");test.waitForIdleSync();firstTap(test,screen,fake,label,"Pause → resume same Activity",report);
            }
            onMain(test,()->{check(fake.taps.size()==6,"Exactly six fake commands dispatched");check(fake.unexpected.isEmpty(),fake.unexpected);});
            return report.append("All actions used injected MotionEvents; fake controller only, no physical IR/Bluetooth/recording commands.\n").toString();
        }finally{
            try{if(activity!=null){Activity closing=activity;onMain(test,closing::finish);test.waitForIdleSync();}}
            finally{try{if(installed[0])onMain(test,()->{if(BreezeService.current==fake)BreezeService.current=null;});}finally{restore(prefs,saved);}}
        }
    }
}
