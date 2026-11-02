package com.aaron.breeze;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
    private static View tagged(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=tagged(group.getChildAt(i),tag);if(found!=null)return found;}}return null;}
    private static String shown(View root,String tag){return ((TextView)tagged(root,tag)).getText().toString();}
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
    private static final String[] LOGIC_KEYS={"logicProfileV2","logicDraftV2","logicSavedProfilesV2","logicProfileV1","logicSavedProfilesV1"};
    private static void storageChecks(Context context){
        SharedPreferences prefs=Settings.prefs(context);String earlier=prefs.getString("logicProfileV1",null),earlierLibrary=prefs.getString("logicSavedProfilesV1",null);
        LogicProfile profile=new LogicProfile();profile.name="Logic fixture";profile.offRoomF=67.5;profile.fullBodyF=96.5;profile.bodyWeight=1.25;profile.averageSeconds=45;profile.boost.enabled=true;profile.boost.amount=9;profile.boost.holdSeconds=151;Settings.saveLogicProfile(context,profile);
        LogicProfile saved=Settings.logicProfile(context);check(saved.sameSettings(profile)&&saved.name.equals("Logic fixture")&&saved.offRoomF==67.5&&saved.boost.amount==9&&saved.boost.holdSeconds==151,"applied range survives a preference round trip");check(!Settings.logicDraftPending(context),"applying leaves no unsent edits");
        LogicProfile edited=saved.copy();edited.fullRoomF=76;Settings.saveLogicDraft(context,edited);check(Settings.logicDraftPending(context)&&Settings.logicProfile(context).fullRoomF==74&&Settings.logicDraft(context).fullRoomF==76,"unsent edits never change the applied range");
        LogicProfile renamed=saved.copy();renamed.name="Renamed logic fixture";Settings.saveLogicDraft(context,renamed);check(!Settings.logicDraftPending(context)&&Settings.logicProfile(context).name.equals("Renamed logic fixture")&&Settings.logicProfile(context).sameSettings(saved),"a rename alone needs no Apply");
        byte[] range=Protocol.logicRange(saved,100),boost=Protocol.logicBoost(saved,101);check(ByteBuffer.wrap(range).order(ByteOrder.LITTLE_ENDIAN).getShort(6)==6750&&ByteBuffer.wrap(range).order(ByteOrder.LITTLE_ENDIAN).getShort(14)==125&&range[16]==3,"range wire contract");check(boost[6]==1&&boost[7]==9&&ByteBuffer.wrap(boost).order(ByteOrder.LITTLE_ENDIAN).getShort(14)==151&&boost[19]==3,"boost wire contract");
        check(java.util.Objects.equals(earlier,prefs.getString("logicProfileV1",null))&&java.util.Objects.equals(earlierLibrary,prefs.getString("logicSavedProfilesV1",null)),"earlier threshold profiles are never rewritten");
        Settings.saveLogicProfile(context,new LogicProfile());
    }
    public static String run(Instrumentation test)throws Exception {
        Context context=test.getTargetContext();SharedPreferences prefs=Settings.prefs(context);boolean hadAnchor=prefs.contains("fanAnchor"),hadPending=prefs.contains("fanAnchorCorrectionPending"),hadDeferred=prefs.contains("homeLogicProfileDeferred");int originalAnchor=prefs.getInt("fanAnchor",1);boolean originalPending=prefs.getBoolean("fanAnchorCorrectionPending",false),originalDeferred=prefs.getBoolean("homeLogicProfileDeferred",false);Activity activity=null;FakeController fake=null;
        String[] originalLogic=new String[LOGIC_KEYS.length];for(int i=0;i<LOGIC_KEYS.length;i++)originalLogic[i]=prefs.getString(LOGIC_KEYS[i],null);
        try{
            storageChecks(context);String report="PASS: actual range preferences keep applied and unsent settings apart, rename without Apply, preserve earlier threshold profiles, and encode range/boost packets.\n";
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return report+"SKIPPED UI: phone is locked; recording and services left untouched.\n";
            boolean[] occupied={false};test.runOnMainSync(()->occupied[0]=BreezeService.current!=null||BedtimeVoiceService.current!=null);if(occupied[0])return report+"SKIPPED UI: an existing service was left untouched.\n";
            prefs.edit().remove("fanAnchor").putBoolean("fanAnchorCorrectionPending",false).commit();fake=new FakeController(context);final FakeController controller=fake;test.runOnMainSync(()->{check(BreezeService.current==null,"no real controller is replaced");BreezeService.current=controller;});
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();
            test.runOnMainSync(()->{
                View root=screen.getWindow().getDecorView();EditText setting=input(root,"Current fan setting");check(setting!=null&&setting.getText().toString().equals("1")&&prefs.getInt("fanAnchor",-1)==1,"Home defaults to persisted 1");check(text(root,"0 = Off · H = Highest · Done")!=null,"Off convention and explicit apply visible");check(((View)setting.getParent()).getParent()==text(root,"Reconnect").getParent(),"fan setting shares the bottom row with Reconnect");
                setting.requestFocus();setting.setText("0");controller.fan=knownFan(7);refresh(screen);check(setting.getText().toString().equals("0"),"focused correction survives controller telemetry");setting.clearFocus();check(controller.corrections==0,"focus loss never competes with a Home fan touch");setting.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);check(controller.lastAnchor==0&&controller.corrections==1&&prefs.getInt("fanAnchor",-1)==0,"Done saves Off through fake only");refresh(screen);check(setting.getText().toString().equals("0"),"old telemetry cannot clobber pending correction");controller.anchorCorrectionPending=false;prefs.edit().putBoolean("fanAnchorCorrectionPending",false).apply();controller.fan=knownFan(3);refresh(screen);check(setting.getText().toString().equals("3"),"unfocused input follows known current speed");setting.requestFocus();setting.setText("H");setting.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);check(controller.lastAnchor==10&&controller.corrections==2&&prefs.getInt("fanAnchor",-1)==10,"Home H correction preserves internal10");refresh(screen);check(setting.getText().toString().equals("H"),"Home shows H for its pending highest correction");
                text(root,"Setup").performClick();root=screen.getWindow().getDecorView();View card=(View)text(root,"Logic").getParent().getParent();check(((ViewGroup)card.getParent()).getChildAt(0)==card,"Logic is the first Setup section");check(controller.applied==0,"constructing fields does not upload");
                check(input(root,"Fan off at · room (°F)").getText().toString().equals("68")&&input(root,"Fan off at · body (°F)").getText().toString().equals("98")&&input(root,"Full speed at · room (°F)").getText().toString().equals("74")&&input(root,"Full speed at · body (°F)").getText().toString().equals("97")&&input(root,"Body weight (levels per °F)").getText().toString().equals("1"),"the range opens on the default two points");
                check(input(root,"Body rise that triggers boost (°F)").getText().toString().equals("1")&&input(root,"Body averaging window (seconds)").getText().toString().equals("30"),"boost trigger and averaging defaults are shown");
                String table=shown(root,"logicPreview");String[] rows=table.split("\n");check(rows.length==5&&rows[1].trim().split("\\s+").length==5&&rows[1].trim().endsWith("1")&&rows[2].contains("Off")&&rows[4].trim().endsWith("H"),"preview shows the default table: "+table);
                check(shown(root,"logicRoomWeight").startsWith("Room weight · 1.83")&&shown(root,"logicRisePace").equals("→ 1.1 s per level")&&shown(root,"logicFallPace").equals("→ 1.0 s per level (the minimum)"),"derived room weight and honest per-level pacing");
                check(text(root,"Maximum fan speed")==null&&text(root,"Warm room")==null&&text(root,"Cool room")==null&&input(root,"Maximum fan speed")==null,"threshold rules, maximum and probe choice are gone");check(tagged(root,"logicApply").getVisibility()==View.GONE,"nothing is pending on a clean form");
                input(root,"Full speed at · room (°F)").setText("77");check(shown(root,"logicPreview").contains("Room 77°")&&shown(root,"logicRoomWeight").startsWith("Room weight · 1.22"),"preview follows typing at once");
            });
            android.os.SystemClock.sleep(750);test.runOnMainSync(()->{View root=screen.getWindow().getDecorView();check(Settings.logicDraftPending(context)&&Settings.logicDraft(context).fullRoomF==77&&Settings.logicProfile(context).fullRoomF==74&&controller.applied==0,"edits stay on the phone and are never uploaded by typing");refresh(screen);check(shown(root,"logicApply").equals("Changes not yet applied")&&tagged(root,"logicApply").getVisibility()==View.VISIBLE,"unsent edits are labelled");
                text(root,"Apply to fan").performClick();check(controller.applied==1&&Settings.logicProfile(context).fullRoomF==77&&!Settings.logicDraftPending(context),"Apply uploads once through the attached fake only");
                input(root,"Fan off at · body (°F)").setText("");check(shown(root,"logicPreview").isEmpty(),"an incomplete entry blanks the preview");text(root,"Apply to fan").performClick();check(controller.applied==1&&Settings.logicProfile(context).offBodyF==98&&shown(root,"logicApply").startsWith("Not applied: Enter a number for Fan off at · body"),"an invalid form is neither stored nor uploaded: "+shown(root,"logicApply"));
                input(root,"Fan off at · body (°F)").setText("97.5");input(root,"Body weight (levels per °F)").setText("9");check(shown(root,"logicRoomWeight").startsWith("Room weight · 1.61"),"a larger body weight lowers the worked-out room weight");input(root,"Full speed at · body (°F)").setText("99");text(root,"Apply to fan").performClick();check(controller.applied==1&&shown(root,"logicApply").startsWith("Not applied: Body weight is too high"),"a body weight that leaves the room nothing to add is refused");
                input(root,"Full speed at · body (°F)").setText("97");input(root,"Body weight (levels per °F)").setText("0.5");text(root,"\u25b8 Advanced").performClick();check(text(root,"\u25be Advanced")!=null&&input(root,"Steadiness (levels past a boundary before changing)").getText().toString().equals("0.3")&&text(root,"Sync current fan speed")!=null,"Advanced opens steadiness and sync");
                ((CheckBox)text(root,"Boost after a sudden body temperature rise")).setChecked(true);check(input(root,"Extra fan levels").isShown(),"boost fields appear when enabled");
                text(root,"Home").performClick();LogicProfile kept=Settings.logicDraft(context);check(Settings.logicDraftPending(context)&&kept.offBodyF==97.5&&kept.bodyWeight==0.5&&kept.boost.enabled&&Settings.logicProfile(context).offBodyF==98&&controller.applied==1,"leaving Setup keeps valid edits on the phone without applying them");
                text(screen.getWindow().getDecorView(),"Setup").performClick();View again=screen.getWindow().getDecorView();check(input(again,"Fan off at · body (°F)").getText().toString().equals("97.5")&&shown(again,"logicApply").equals("Changes not yet applied")&&((Button)tagged(again,"logicProfile")).getText().toString().equals("Profile: Default \u25be"),"returning shows the unsent edits and the profile name");
            });
            return report+"PASS UI: default range points, live preview table, worked-out room weight and per-level pacing; edits held on the phone until Apply, invalid and unusable ranges refused, Advanced section, navigation keeps edits; Home anchor below Reconnect, default1/Off0, focused edit/pending protection; fake controller only.\n";
        }finally{
            if(activity!=null){Activity closing=activity;test.runOnMainSync(closing::finish);test.waitForIdleSync();}
            if(fake!=null){FakeController closing=fake;test.runOnMainSync(()->{if(BreezeService.current==closing)BreezeService.current=null;});}
            SharedPreferences.Editor restore=prefs.edit().putInt("fanAnchor",hadAnchor?originalAnchor:1);if(hadPending)restore.putBoolean("fanAnchorCorrectionPending",originalPending);else restore.remove("fanAnchorCorrectionPending");if(hadDeferred)restore.putBoolean("homeLogicProfileDeferred",originalDeferred);else restore.remove("homeLogicProfileDeferred");
            for(int i=0;i<LOGIC_KEYS.length;i++){if(originalLogic[i]==null)restore.remove(LOGIC_KEYS[i]);else restore.putString(LOGIC_KEYS[i],originalLogic[i]);}
            check(restore.commit(),"restore exact pre-test range, unsent edits, named library, earlier profiles and anchor preferences");
        }
    }
}
