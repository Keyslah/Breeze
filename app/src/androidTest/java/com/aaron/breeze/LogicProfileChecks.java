package com.aaron.breeze;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntFunction;

/** Real preference/UI checks with an attached fake; never sends controller commands. */
public final class LogicProfileChecks {
    private LogicProfileChecks(){}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static TextView text(View view,String label){if(view instanceof TextView&&label.equals(((TextView)view).getText().toString()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private static EditText input(View view,String label){if(view instanceof EditText&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=input(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private static void refresh(Activity activity){try{Method method=MainActivity.class.getDeclaredMethod("refresh");method.setAccessible(true);method.invoke(activity);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private static Protocol.FanState knownFan(int speed){Protocol.FanState fan=new Protocol.FanState();fan.flags=4;fan.speed=speed;fan.time=System.currentTimeMillis();fan.learning=255;fan.resultSlot=255;fan.lastSent=255;fan.learned=4095;return fan;}
    private static final class FakeController extends BreezeService {
        private final Context context;
        int corrections,applied,lastAnchor=-1;
        FakeController(Context context){attachBaseContext(context);this.context=context;ready=true;sensorFirmware=true;irFirmware=true;logicSupported=true;latest=new Protocol.Reading();latest.flags=128;fan=knownFan(1);}
        @Override public boolean fresh(){return ready;}
        @Override public boolean freshFan(){return ready&&fan!=null;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override public void changed(){}
        @Override public void setFanAnchor(int value){check(FanSetting.valid(value),"valid UI anchor");corrections++;lastAnchor=value;anchorCorrectionPending=true;Settings.prefs(context).edit().putInt("fanAnchor",value).putBoolean("fanAnchorCorrectionPending",true).apply();}
        @Override public boolean setHomeFanAnchor(int value,Result result){setFanAnchor(value);if(result!=null)result.done(true);return true;}
        @Override public boolean applyLogicProfile(LogicProfile profile){profile.validate();applied++;return true;}
        @Override public void command(IntFunction<byte[]> make,Result result){throw new AssertionError("Unexpected controller command");}
        @Override public boolean fanCommand(int action,int value,int argument){throw new AssertionError("Unexpected fan command");}
        @Override public void scan(){throw new AssertionError("Unexpected Bluetooth scan");}
        @Override public void connect(String address){throw new AssertionError("Unexpected Bluetooth connection");}
    }
    private static void storageChecks(Context context){
        LogicProfile profile=Settings.logicProfile(context);String id=profile.id;profile.name="Logic fixture";profile.roomBoundaryF=72;profile.coolRoomThresholdF=70;profile.maxSpeed=10;profile.warm.minimumSpeed=10;profile.warm.boost.amount=9;profile.warm.boost.enabled=true;profile.warm.boost.riseSeconds=4;profile.warm.boost.holdSeconds=151;profile.warm.boost.returnSeconds=7;profile.cool.boost.enabled=false;profile.cool.boost.riseSeconds=6;profile.cool.boost.holdSeconds=233;profile.cool.boost.returnSeconds=9;Settings.saveLogicProfile(context,profile);
        LogicProfile saved=Settings.logicProfile(context);check(saved.maxSpeed==10&&saved.warm.minimumSpeed==10&&saved.warm.boost.amount==9,"H and nine-level boost survive preference round trip");check(id.equals(saved.id)&&saved.warm.boost.enabled&&!saved.cool.boost.enabled,"save preserves identity and independent boost enables");check(saved.roomBoundaryF==72&&saved.coolRoomThresholdF==70,"independent room thresholds persist");check(saved.warm.boost.riseSeconds==4&&saved.warm.boost.holdSeconds==151&&saved.warm.boost.returnSeconds==7&&saved.cool.boost.riseSeconds==6&&saved.cool.boost.holdSeconds==233&&saved.cool.boost.returnSeconds==9,"independent boost timings persist");
        saved.name="Renamed logic fixture";saved.warm.boost.enabled=false;saved.cool.boost.enabled=true;Settings.saveLogicProfile(context,saved);LogicProfile renamed=Settings.logicProfile(context);check(id.equals(renamed.id)&&renamed.name.equals("Renamed logic fixture")&&!renamed.warm.boost.enabled&&renamed.cool.boost.enabled,"rename changes name without changing identity or linking boost enables");
        byte[] warm=Protocol.logicBoost(renamed,0,100),cool=Protocol.logicBoost(renamed,1,101);check(warm.length==20&&cool.length==20&&warm[5]==0&&cool[5]==1&&warm[6]==0&&cool[6]==1,"independent rule enable wire contract");check(ByteBuffer.wrap(warm).order(ByteOrder.LITTLE_ENDIAN).getShort(14)==151&&ByteBuffer.wrap(cool).order(ByteOrder.LITTLE_ENDIAN).getShort(14)==233,"independent hold times on wire");
    }
    public static String run(Instrumentation test)throws Exception {
        Context context=test.getTargetContext();SharedPreferences prefs=Settings.prefs(context);boolean hadProfile=prefs.contains("logicProfileV1"),hadAnchor=prefs.contains("fanAnchor"),hadPending=prefs.contains("fanAnchorCorrectionPending");String originalProfile=prefs.getString("logicProfileV1",null);int originalAnchor=prefs.getInt("fanAnchor",1);boolean originalPending=prefs.getBoolean("fanAnchorCorrectionPending",false);String defaultProfile=new LogicProfile().serialize();Activity activity=null;FakeController fake=null;
        boolean hadLibrary=prefs.contains("logicSavedProfilesV1");String originalLibrary=prefs.getString("logicSavedProfilesV1",null);
        try{
            storageChecks(context);String report="PASS: actual profile preferences save/rename with stable identity; independent per-rule boost enables/timings and wire packets.\n";
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return report+"SKIPPED UI: phone is locked; recording and services left untouched.\n";
            boolean[] occupied={false};test.runOnMainSync(()->occupied[0]=BreezeService.current!=null||BedtimeVoiceService.current!=null);if(occupied[0])return report+"SKIPPED UI: an existing service was left untouched.\n";
            prefs.edit().remove("fanAnchor").putBoolean("fanAnchorCorrectionPending",false).commit();fake=new FakeController(context);final FakeController controller=fake;test.runOnMainSync(()->{check(BreezeService.current==null,"no real controller is replaced");BreezeService.current=controller;});
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();EditText setting=input(root,"Current fan setting");check(setting!=null&&setting.getText().toString().equals("1")&&prefs.getInt("fanAnchor",-1)==1,"Home defaults to persisted 1");check(text(root,"0 = Off · H = Highest · Done")!=null,"Off convention and explicit apply visible");check(((View)setting.getParent()).getParent()==text(root,"Reconnect").getParent(),"fan setting shares the bottom row with Reconnect");
                setting.requestFocus();setting.setText("0");controller.fan=knownFan(7);refresh(screen);check(setting.getText().toString().equals("0"),"focused correction survives controller telemetry");setting.clearFocus();check(controller.corrections==0,"focus loss never competes with a Home fan touch");setting.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);check(controller.lastAnchor==0&&controller.corrections==1&&prefs.getInt("fanAnchor",-1)==0,"Done saves Off through fake only");refresh(screen);check(setting.getText().toString().equals("0"),"old telemetry cannot clobber pending correction");controller.anchorCorrectionPending=false;prefs.edit().putBoolean("fanAnchorCorrectionPending",false).apply();controller.fan=knownFan(3);refresh(screen);check(setting.getText().toString().equals("3"),"unfocused input follows known current speed");setting.requestFocus();setting.setText("H");setting.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);check(controller.lastAnchor==10&&controller.corrections==2&&prefs.getInt("fanAnchor",-1)==10,"Home H correction preserves internal10");refresh(screen);check(setting.getText().toString().equals("H"),"Home shows H for its pending highest correction");
                text(root,"Setup").performClick();root=screen.getWindow().getDecorView();ViewGroup content=(ViewGroup)text(root,"Logic").getParent().getParent();check(content.getChildAt(0)==text(root,"Logic").getParent(),"Logic is the first Setup section");check(controller.applied==0,"constructing fields does not autosave or upload");check(input(root,"Maximum fan speed").getText().toString().equals("H")&&input(root,"Minimum fan speed").getText().toString().equals("H"),"Logic displays highest fan settings as H");EditText profileName=input(root,"Profile name");String id=Settings.logicProfile(context).id;profileName.setText("UI logic rename");View warm=(View)text(root,"Warm room").getParent(),cool=(View)text(root,"Cool room").getParent();EditText warmRoom=input(warm,"Room temperature at or above (°F)"),coolRoom=input(cool,"Room temperature below (°F)");warmRoom.setText("72.5");check(coolRoom.getText().toString().equals("70.0"),"warm edit does not change cool threshold");coolRoom.setText("69.5");check(warmRoom.getText().toString().equals("72.5"),"cool edit does not change warm threshold");warmRoom.setText("72");coolRoom.setText("70");input(cool,"Starting fan speed above the body threshold").setText("h");((CheckBox)text(warm,"Sudden warming boost")).setChecked(true);((CheckBox)text(cool,"Sudden warming boost")).setChecked(false);input(warm,"Hold boost (seconds)").setText("123");input(cool,"Hold boost (seconds)").setText("321");text(root,"Save profile").performClick();LogicProfile saved=Settings.logicProfile(context);check(id.equals(saved.id)&&saved.name.equals("UI logic rename")&&saved.roomBoundaryF==72&&saved.coolRoomThresholdF==70&&saved.warm.boost.enabled&&!saved.cool.boost.enabled&&saved.warm.boost.holdSeconds==123&&saved.cool.boost.holdSeconds==321&&saved.maxSpeed==10&&saved.warm.minimumSpeed==10&&saved.cool.minimumSpeed==10,"UI save preserves identity, independent room thresholds and boosts");check(controller.applied>=1,"UI apply uses attached fake only");int uploads=controller.applied;text(root,"Save profile").performClick();check(controller.applied==uploads+1,"explicit Save reapplies automatic rules even when unchanged");profileName.setText("Autosaved logic rename");
            });
            android.os.SystemClock.sleep(750);test.runOnMainSync(()->{check(Settings.logicProfile(context).name.equals("Autosaved logic rename"),"valid text edits autosave after debounce without Save");View root=screen.getWindow().getDecorView();input(root,"Room temperature at or above (°F)").setText("");});android.os.SystemClock.sleep(750);
            test.runOnMainSync(()->{check(Settings.logicProfile(context).roomBoundaryF==72,"partial invalid typing preserves the last valid profile");View root=screen.getWindow().getDecorView();input(root,"Room temperature at or above (°F)").setText("73");input(root,"Profile name").setText("Saved on leaving Logic");text(root,"Home").performClick();check(Settings.logicProfile(context).name.equals("Saved on leaving Logic")&&Settings.logicProfile(context).roomBoundaryF==73,"navigation flushes valid pending edits");});
            return report+"PASS UI: independent room thresholds; H current/max/min setting inputs; profile rename/boosts; debounced autosave, invalid partial typing, explicit-save apply and navigation flush; Home anchor below Reconnect, default1/Off0, focused edit/pending protection; fake controller only.\n";
        }finally{
            if(activity!=null){Activity closing=activity;test.runOnMainSync(closing::finish);test.waitForIdleSync();}
            if(fake!=null){FakeController closing=fake;test.runOnMainSync(()->{if(BreezeService.current==closing)BreezeService.current=null;});}
            SharedPreferences.Editor restore=prefs.edit().putString("logicProfileV1",hadProfile?originalProfile:defaultProfile).putInt("fanAnchor",hadAnchor?originalAnchor:1);if(hadPending)restore.putBoolean("fanAnchorCorrectionPending",originalPending);else restore.remove("fanAnchorCorrectionPending");if(hadLibrary)restore.putString("logicSavedProfilesV1",originalLibrary);else restore.remove("logicSavedProfilesV1");check(restore.commit(),"restore exact pre-test profile, named library and anchor preferences");
        }
    }
}
