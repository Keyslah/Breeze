package com.aaron.breeze;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.IntFunction;
import org.json.JSONObject;

/** Emulator-only actual Activity checks with isolated files/preferences and fake transport. */
public final class NamedLogicProfileChecks {
    private NamedLogicProfileChecks(){}
    private static final String LIBRARY="logicSavedProfilesV2",EARLIER="logicProfileV1",EARLIER_LIBRARY="logicSavedProfilesV1";
    private static volatile Scope activeScope;
    private interface MainAction {void run() throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void onMain(Instrumentation test,MainAction action)throws Exception {
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Named-profile UI fixture",failure[0]);
    }
    private static final class Scope {
        final Context base;final String prefix="temporary-named-logic-"+SystemClock.elapsedRealtimeNanos();
        final Set<String> preferenceNames=new LinkedHashSet<>(),databaseNames=new LinkedHashSet<>();
        final File files,cache;
        Scope(Context base){this.base=base;File root=new File(base.getCacheDir(),prefix);files=new File(root,"files");cache=new File(root,"cache");check(files.mkdirs()&&cache.mkdirs(),"Create isolated fixture directories");}
        String preference(String name){String scoped=prefix+"-"+name;preferenceNames.add(scoped);return scoped;}
        String database(String name){String scoped=prefix+"-"+name;databaseNames.add(scoped);return scoped;}
        void close(){for(String name:preferenceNames)base.deleteSharedPreferences(name);for(String name:databaseNames)base.deleteDatabase(name);removeTemp(files.getParentFile());}
        void removeTemp(File file){check(file.getAbsolutePath().startsWith(files.getParentFile().getAbsolutePath()),"Cleanup stays inside current fixture");File[] children=file.listFiles();if(children!=null)for(File child:children)removeTemp(child);check(!file.exists()||file.delete(),"Remove current-test temporary file");}
    }
    private static final class IsolatedContext extends ContextWrapper {
        final Scope scope;
        IsolatedContext(Context base,Scope scope){super(base);this.scope=scope;}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return scope.base.getSharedPreferences(scope.preference(name),mode);}
        @Override public File getDatabasePath(String name){return scope.base.getDatabasePath(scope.database(name));}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return scope.base.openOrCreateDatabase(scope.database(name),mode,factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler errorHandler){return scope.base.openOrCreateDatabase(scope.database(name),mode,factory,errorHandler);}
        @Override public File getFilesDir(){return scope.files;}
        @Override public File getCacheDir(){return scope.cache;}
    }
    /** Called by this runner before MainActivity.onCreate, never by production code. */
    static void isolateActivity(Activity activity){
        Scope scope=activeScope;if(scope==null||!(activity instanceof MainActivity))return;
        try{Field base=ContextWrapper.class.getDeclaredField("mBase");base.setAccessible(true);base.set(activity,new IsolatedContext(activity.getBaseContext(),scope));}
        catch(ReflectiveOperationException failure){throw new AssertionError("Could not isolate Activity context",failure);}
    }
    private static Properties fields(LogicProfile profile){try{Properties result=new Properties();result.load(new StringReader(profile.serialize()));return result;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void same(LogicProfile expected,LogicProfile actual,String message){check(actual!=null&&fields(expected).equals(fields(actual)),message);}
    private static Map<String,LogicProfile> profiles(Context context){Map<String,LogicProfile> result=new HashMap<>();for(LogicProfile profile:Settings.savedLogicProfiles(context)){check(result.put(profile.name,profile)==null,"Unique saved names");}return result;}
    private static LogicProfile profile(String name,int variant){
        LogicProfile p=new LogicProfile();p.id="shared-identity";p.name=name;p.offRoomF=66.25+variant;p.offBodyF=97.25+variant;p.fullRoomF=75.5+variant;p.fullBodyF=96.75-variant;p.bodyWeight=0.75+variant*0.5;p.steadiness=0.25+variant*0.25;p.averageSeconds=47+variant;p.riseSeconds=17+variant;p.fallSeconds=13+variant;
        LogicProfile.Boost b=p.boost;b.enabled=variant==1;b.amount=3+variant;b.triggerRiseF=0.75+variant*0.5;b.windowSeconds=43+variant;b.riseSeconds=4+variant;b.holdSeconds=149+variant;b.returnSeconds=7+variant;b.curve=variant%2;
        LogicProfile.TimeWindow w=p.timeWindow;w.enabled=variant==1;w.startMinute=variant==0?270:1320;w.endMinute=variant==0?480:300;w.adjustment=variant==0?-1:2;
        p.validate();return p;
    }
    private static String mismatchedEntry(LogicProfile profile){try{return new JSONObject().put("Wrong entry name",profile.serialize()).toString();}catch(Exception failure){throw new AssertionError(failure);}}
    private static void storage(Context context){
        SharedPreferences prefs=Settings.prefs(context);String earlier="version=1\nname=Legacy active\nroomBoundaryF=73.0\ncoolRoomThresholdF=72.0\naverageSeconds=41\nriseSeconds=14\nfallSeconds=8\n",earlierLibrary="{\"Legacy active\":\"kept as stored\"}";
        check(prefs.edit().putString(EARLIER,earlier).putString(EARLIER_LIBRARY,earlierLibrary).commit(),"Prepare earlier threshold fixtures");
        LogicProfile first=Settings.logicProfile(context);check(first.name.equals("Default")&&first.offRoomF==68&&first.offBodyF==98&&first.fullRoomF==74&&first.fullBodyF==97&&first.bodyWeight==1,"First access starts on the default range");check(first.averageSeconds==41&&first.riseSeconds==14&&first.fallSeconds==8,"Only the earlier timing carries over");
        check(!first.timeWindow.enabled&&first.timeWindow.startMinute==270&&first.timeWindow.endMinute==480&&first.timeWindow.adjustment==-1,"Earlier profiles receive a disabled morning time window");
        check(prefs.getBoolean("homeLogicProfileDeferred",false),"The new range is queued for the controller");check(earlier.equals(prefs.getString(EARLIER,null))&&earlierLibrary.equals(prefs.getString(EARLIER_LIBRARY,null)),"Earlier threshold profiles stay exactly as stored");
        check(profiles(context).isEmpty()&&!Settings.logicDraftPending(context),"No named range or unsent edit exists yet");
        LogicProfile a=profile("Profile A",0),b=profile("Profile B",1);Settings.saveNamedLogicProfile(context,a);Settings.saveNamedLogicProfile(context,b);Map<String,LogicProfile> library=profiles(context);check(library.size()==2,"Explicit A and B survive despite the same profile identity");same(a,library.get(a.name),"Saving B preserves A");same(b,library.get(b.name),"Saving B stores every field");
        same(first,Settings.logicProfile(context),"Saving named copies never changes the applied range");check(!Settings.logicDraftPending(context),"Saving named copies never creates unsent edits");
        a.boost.returnSeconds=43;Settings.saveNamedLogicProfile(context,a);check(profiles(context).size()==2,"Same-name save replaces one snapshot");same(a,profiles(context).get(a.name),"Same-name save updates the whole snapshot");
        LogicProfile detached=profiles(context).get(a.name);detached.name="Detached";detached.boost.returnSeconds=99;same(a,profiles(context).get(a.name),"Returned library values cannot mutate saved snapshots");
        same(a,Settings.loadLogicProfile(context,a.name),"Load returns every field");same(first,Settings.logicProfile(context),"Reading a named copy applies nothing");check(Settings.hasSavedLogicProfile(context," Profile A ")&&!Settings.hasSavedLogicProfile(context,"profile a"),"Name lookup trims but is case-sensitive");
        Settings.saveLogicDraft(context,a);check(Settings.logicDraftPending(context),"Loaded settings wait as unsent edits");same(a,Settings.logicDraft(context),"The form shows the loaded settings");same(first,Settings.logicProfile(context),"Unsent edits leave the applied range alone");same(a,Settings.logicDraft(new IsolatedContext(((IsolatedContext)context).scope.base,((IsolatedContext)context).scope)),"Unsent edits survive a fresh context");
        Settings.saveLogicProfile(context,a);check(!Settings.logicDraftPending(context),"Applying clears the unsent edits");same(a,Settings.logicProfile(context),"Applying stores the range");
        LogicProfile renamed=a.copy();renamed.name="Only a new name";Settings.saveLogicDraft(context,renamed);check(!Settings.logicDraftPending(context)&&Settings.logicProfile(context).name.equals("Only a new name"),"A rename alone is not an unsent edit");Settings.saveLogicProfile(context,a);
        String libraryBefore=prefs.getString(LIBRARY,null);try{Settings.loadLogicProfile(context,"Missing profile");throw new AssertionError("Missing profile was accepted");}catch(IllegalArgumentException expected){}same(a,Settings.logicProfile(context),"Missing selection preserves the applied range");check(libraryBefore.equals(prefs.getString(LIBRARY,null)),"Load never rewrites saved snapshots");
        LogicProfile spaced=profile("  Trimmed profile  ",1);Settings.saveNamedLogicProfile(context,spaced);check(profiles(context).containsKey("Trimmed profile")&&!profiles(context).containsKey(spaced.name),"Save normalizes surrounding name spaces");same(spaced,Settings.loadLogicProfile(context," Trimmed profile "),"Load accepts surrounding name spaces");try{Settings.loadLogicProfile(context,"trimmed profile");throw new AssertionError("Case-insensitive lookup unexpectedly accepted");}catch(IllegalArgumentException expected){}
        String validLibrary=prefs.getString(LIBRARY,null);LogicProfile beforeCorruption=Settings.logicProfile(context);
        for(String corrupt:new String[]{"[]","{} trailing","{\"Bad\":4}",mismatchedEntry(a)}){
            check(prefs.edit().putString(LIBRARY,corrupt).commit(),"Prepare corrupt isolated library");
            try{Settings.savedLogicProfiles(context);throw new AssertionError("Corrupt library accepted by list");}catch(IllegalArgumentException expected){}
            try{Settings.loadLogicProfile(context,a.name);throw new AssertionError("Corrupt library accepted by load");}catch(IllegalArgumentException expected){}
            try{Settings.saveNamedLogicProfile(context,a);throw new AssertionError("Corrupt library overwritten by save");}catch(IllegalArgumentException expected){}
            same(beforeCorruption,Settings.logicProfile(context),"Library failures preserve the applied range");check(corrupt.equals(prefs.getString(LIBRARY,null)),"Library failures preserve the stored collection exactly");
        }
        check(prefs.edit().putString(LIBRARY,validLibrary).commit(),"Restore valid isolated collection");
        check(earlier.equals(prefs.getString(EARLIER,null))&&earlierLibrary.equals(prefs.getString(EARLIER_LIBRARY,null)),"No range operation rewrote the earlier threshold profiles");
    }
    private static List<TextView> labels(View view,String label){List<TextView> result=new ArrayList<>();collectLabels(view,label,result);return result;}
    private static void collectLabels(View view,String label,List<TextView> result){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))result.add((TextView)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectLabels(group.getChildAt(i),label,result);}}
    private static View tagged(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=tagged(group.getChildAt(i),tag);if(found!=null)return found;}}return null;}
    private static String shown(Activity activity,String tag){return ((TextView)tagged(root(activity),tag)).getText().toString();}
    private static void timeWindowShown(Activity activity,LogicProfile expected){
        View toggle=tagged(root(activity),"logicTimeWindowEnabled"),start=tagged(root(activity),"logicTimeWindowStart"),end=tagged(root(activity),"logicTimeWindowEnd"),adjustment=tagged(root(activity),"logicTimeWindowAdjustment");
        check(labels(root(activity),"Time windows").size()==1&&toggle instanceof CheckBox&&start instanceof Button&&end instanceof Button&&adjustment instanceof EditText,"Time windows have one section heading and accessible editable controls");
        check(((CheckBox)toggle).isChecked()==expected.timeWindow.enabled,"Time window enabled state is restored");
        java.text.DateFormat time=android.text.format.DateFormat.getTimeFormat(activity);time.setTimeZone(TimeZone.getTimeZone("UTC"));
        check(((Button)start).getText().toString().equals("Start · "+time.format(new Date(expected.timeWindow.startMinute*60_000L)))&&((Button)end).getText().toString().equals("End · "+time.format(new Date(expected.timeWindow.endMinute*60_000L))),"Start and end restore in the phone's time format");
        check(((EditText)adjustment).getText().toString().equals(Integer.toString(expected.timeWindow.adjustment))&&(((EditText)adjustment).getInputType()&InputType.TYPE_NUMBER_FLAG_SIGNED)!=0,"Time window restores the adjustment and accepts signed whole numbers");
        check(start.getContentDescription()!=null&&end.getContentDescription()!=null&&adjustment.getContentDescription()!=null,"Time window fields have accessibility labels");
    }
    private static EditText input(View view,String label){if(view instanceof EditText&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=input(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private static String value(Activity activity,String label){return input(root(activity),label).getText().toString();}
    private static View root(Activity activity){return activity.getWindow().getDecorView();}
    private static void click(Activity activity,String label,int index){List<TextView> found=labels(root(activity),label);check(found.size()>index,"Missing control: "+label);check(found.get(index).performClick(),"Control did not handle click: "+label);}
    private static boolean matches(CharSequence value,String label){return value!=null&&label.equalsIgnoreCase(value.toString().trim());}
    private static boolean clickAccessible(AccessibilityNodeInfo node,String label){
        if(node==null)return false;
        if(node.isVisibleToUser()&&node.isEnabled()&&(matches(node.getText(),label)||matches(node.getContentDescription(),label))){AccessibilityNodeInfo target=node;while(target!=null&&!target.isClickable())target=target.getParent();if(target!=null&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}
        for(int i=0;i<node.getChildCount();i++)if(clickAccessible(node.getChild(i),label))return true;
        return false;
    }
    private static void describeAccessible(AccessibilityNodeInfo node,StringBuilder out,int depth){
        if(node==null||out.length()>6000)return;
        out.append('\n').append(depth).append(' ').append(node.getClassName()).append(" text=").append(node.getText()).append(" description=").append(node.getContentDescription()).append(" clickable=").append(node.isClickable()).append(" enabled=").append(node.isEnabled()).append(" visible=").append(node.isVisibleToUser());
        for(int i=0;i<node.getChildCount();i++)describeAccessible(node.getChild(i),out,depth+1);
    }
    private static void dialogClick(Instrumentation test,String label)throws Exception {
        long deadline=SystemClock.uptimeMillis()+5000;String lastTree="No active accessibility window";
        do{AccessibilityNodeInfo window=test.getUiAutomation().getRootInActiveWindow();if(window!=null){if(clickAccessible(window,label)){test.waitForIdleSync();return;}StringBuilder tree=new StringBuilder();describeAccessible(window,tree,0);lastTree=tree.toString();}SystemClock.sleep(60);}while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("Dialog control not found: "+label+"; last accessibility tree:"+lastTree);
    }
    private static final class FakeController extends BreezeService {
        final List<LogicProfile> applied=new ArrayList<>();String unexpected="";
        FakeController(Context context){attachBaseContext(context);ready=true;sensorFirmware=irFirmware=logicSupported=true;latest=new Protocol.Reading();latest.flags=128;fan=new Protocol.FanState();fan.flags=4;fan.speed=1;fan.learning=fan.resultSlot=fan.lastSent=255;}
        private void reject(String name){if(unexpected.isEmpty())unexpected=name;}
        @Override public boolean fresh(){return ready;}
        @Override public boolean freshFan(){return ready;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override public void changed(){}
        @Override public boolean applyLogicProfile(LogicProfile profile){applied.add(LogicProfile.deserialize(profile.serialize()));return ready;}
        @Override public void command(IntFunction<byte[]> make,Result result){reject("transport command");}
        @Override public void scan(){reject("scan");}
        @Override public void connect(String address){reject("connect");}
        @Override public void download(){reject("download");}
        @Override public boolean fanCommand(int action,int value,int argument){reject("fan command");return false;}
        @Override public void setFanAnchor(int speed){reject("anchor correction");}
        @Override public void control(int action,int value){reject("recording control");}
        @Override public void startSession(){reject("recording start");}
        @Override public void startContactTest(){reject("probe test");}
    }
    private static boolean emulator(){return Build.FINGERPRINT.startsWith("generic")||Build.MODEL.contains("sdk_gphone")||Build.HARDWARE.equals("goldfish")||Build.HARDWARE.equals("ranchu");}
    public static String run(Instrumentation test)throws Exception {
        if(!emulator())return "SKIPPED: named profile checks run only on an emulator; physical phone untouched.\n";
        Context real=test.getTargetContext();Map<String,?> originalPreferences=new HashMap<>(Settings.prefs(real).getAll());Scope scope=new Scope(real);Context isolated=new IsolatedContext(real,scope);Activity activity=null;FakeController fake=new FakeController(isolated);boolean[] installed={false};
        try{
            storage(isolated);LogicProfile a=profile("UI A",0),b=profile("UI B",1);Settings.saveNamedLogicProfile(isolated,a);Settings.saveNamedLogicProfile(isolated,b);Settings.saveLogicProfile(isolated,a);
            onMain(test,()->{check(BreezeService.current==null&&BedtimeVoiceService.current==null,"Existing services are left untouched");BreezeService.current=fake;installed[0]=true;activeScope=scope;});
            activity=test.startActivitySync(new Intent(real,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();
            onMain(test,()->click(screen,"Setup",0));test.waitForIdleSync();
            int[] before={0};
            onMain(test,()->{
                check(Settings.prefs(screen)==Settings.prefs(isolated),"Activity uses isolated preferences");
                TextView chooser=(TextView)tagged(root(screen),"logicProfile");List<TextView> saveAs=labels(root(screen),"Save as…");check(chooser!=null&&saveAs.size()==1&&labels(root(screen),"Apply to fan").size()==1,"One profile chooser, one Save as and one Apply exist");
                LinearLayout row=(LinearLayout)chooser.getParent();check(saveAs.get(0).getParent()==row&&row.getOrientation()==LinearLayout.HORIZONTAL&&row.indexOfChild(chooser)<row.indexOfChild(saveAs.get(0)),"Save as follows the profile chooser on one row");
                check(chooser.getText().toString().equals("Profile: UI A ▾")&&labels(root(screen),"Save profile").isEmpty()&&labels(root(screen),"Load profile").isEmpty(),"The chooser names the active profile; the old Save/Load pair is gone");
                check(fake.applied.isEmpty()&&value(screen,"Fan off at · room (°F)").equals("66.25")&&value(screen,"Body weight (levels per °F)").equals("0.75"),"Constructing Logic shows the applied range and does not upload");
                timeWindowShown(screen,a);((EditText)tagged(root(screen),"logicTimeWindowAdjustment")).setText("-2");click(screen,"Home",0);click(screen,"Setup",0);
                LogicProfile timeDraft=a.copy();timeDraft.timeWindow.adjustment=-2;timeWindowShown(screen,timeDraft);same(timeDraft,Settings.logicDraft(isolated),"Negative time-window edit survives leaving and reopening Setup");same(a,Settings.logicProfile(isolated),"Time-window draft does not change the applied profile");check(Settings.logicDraftPending(isolated)&&fake.applied.isEmpty(),"Time-window edit waits for Apply and sends nothing");
                input(root(screen),"Full speed at · room (°F)").setText("79.5");click(screen,"Apply to fan",0);
                check(fake.applied.size()==1&&fake.applied.get(0).fullRoomF==79.5&&Settings.logicProfile(isolated).fullRoomF==79.5&&!Settings.logicDraftPending(isolated),"Apply uploads the edited range once");same(a,profiles(isolated).get("UI A"),"Apply never rewrites the named copy");
                check(fake.applied.get(0).timeWindow.adjustment==-2&&!fake.applied.get(0).timeWindow.enabled&&fake.applied.get(0).timeWindow.startMinute==a.timeWindow.startMinute&&fake.applied.get(0).timeWindow.endMinute==a.timeWindow.endMinute,"Apply includes the signed adjustment and preserves disabled times");
                click(screen,"Save as…",0);
            });
            dialogClick(test,"Save");
            onMain(test,()->{check(profiles(isolated).get("UI A").fullRoomF==79.5&&profiles(isolated).size()>=2,"Save as with the current name updates that copy");check(fake.applied.size()==1,"Saving a named copy never uploads");same(b,profiles(isolated).get("UI B"),"Saving UI A preserves UI B");
                check(profiles(isolated).get("UI A").timeWindow.adjustment==-2&&!profiles(isolated).get("UI A").timeWindow.enabled,"Save as keeps the edited time-window fields");
                input(root(screen),"Body weight (levels per °F)").setText("2");tagged(root(screen),"logicProfile").performClick();});
            dialogClick(test,"UI B");dialogClick(test,"Cancel");
            onMain(test,()->{check(Settings.logicDraftPending(isolated)&&Settings.logicDraft(isolated).bodyWeight==2&&value(screen,"Body weight (levels per °F)").equals("2"),"Declining the replacement keeps the unsent edit");check(fake.applied.size()==1,"Cancel never applies another profile");tagged(root(screen),"logicProfile").performClick();});
            dialogClick(test,"UI B");dialogClick(test,"Load");
            onMain(test,()->{same(b,Settings.logicDraft(isolated),"Chooser loads B in full into the form");check(Settings.logicDraftPending(isolated)&&Settings.logicProfile(isolated).fullRoomF==79.5&&fake.applied.size()==1,"Loading waits for Apply and uploads nothing");
                timeWindowShown(screen,b);
                check(value(screen,"Fan off at · room (°F)").equals("67.25")&&((TextView)tagged(root(screen),"logicProfile")).getText().toString().equals("Profile: UI B ▾")&&shown(screen,"logicApply").equals("Changes not yet applied"),"Loaded values and name replace the editor");before[0]=fake.applied.size();});
            SystemClock.sleep(750);test.waitForIdleSync();
            onMain(test,()->{same(b,Settings.logicDraft(isolated),"A pending debounce from the old form cannot overwrite loaded B");check(fake.applied.size()==before[0],"No stale delayed profile application");click(screen,"Apply to fan",0);
                check(fake.applied.size()==before[0]+1,"Apply after loading uploads exactly once");same(b,fake.applied.get(fake.applied.size()-1),"Uploaded range is loaded B");same(b,Settings.logicProfile(isolated),"Applied range is loaded B");check(!Settings.logicDraftPending(isolated),"Nothing is left unsent");
                before[0]=fake.applied.size();tagged(root(screen),"logicProfile").performClick();});
            dialogClick(test,"Cancel");
            onMain(test,()->{check(fake.applied.size()==before[0],"Closing the chooser applies nothing");same(b,Settings.logicProfile(isolated),"Closing the chooser preserves the applied range");
                fake.ready=false;input(root(screen),"Body weight (levels per °F)").setText("1.5");click(screen,"Apply to fan",0);check(fake.applied.size()==before[0]+1&&Settings.logicProfile(isolated).bodyWeight==1.5,"An offline Apply is stored and makes one attempt");check(fake.unexpected.isEmpty(),"No hardware path invoked: "+fake.unexpected);});
            onMain(test,()->{
                fake.ready=true;before[0]=fake.applied.size();LogicProfile enabled=Settings.logicProfile(isolated);timeWindowShown(screen,enabled);((CheckBox)tagged(root(screen),"logicTimeWindowEnabled")).setChecked(false);
                check(!Settings.logicDraft(isolated).timeWindow.enabled&&Settings.logicProfile(isolated).timeWindow.enabled&&fake.applied.size()==before[0],"Disabling a time window stays a draft until Apply");click(screen,"Home",0);click(screen,"Setup",0);
                LogicProfile disabled=enabled.copy();disabled.timeWindow.enabled=false;timeWindowShown(screen,disabled);same(disabled,Settings.logicDraft(isolated),"Disabled window and its times persist after rebuilding the editor");click(screen,"Apply to fan",0);
                check(fake.applied.size()==before[0]+1&&!Settings.logicDraftPending(isolated),"Apply uploads disabled window exactly once");same(disabled,Settings.logicProfile(isolated),"Disabled window persists in applied profile");same(disabled,fake.applied.get(fake.applied.size()-1),"Controller receives disabled window with its original times and adjustment");same(b,profiles(isolated).get("UI B"),"Disabling the active window leaves the named snapshot intact");check(fake.unexpected.isEmpty(),"No hardware path invoked by time-window edits: "+fake.unexpected);
            });
            onMain(test,()->{
                before[0]=fake.applied.size();
                ((EditText)tagged(root(screen),"logicTimeWindowAdjustment")).setText("-1");
                ((CheckBox)tagged(root(screen),"logicTimeWindowEnabled")).setChecked(true);
                for(int i=0;i<32;i++)click(screen,"Add window",0);
                View add=tagged(root(screen),"logicTimeWindowAdd"),last=tagged(root(screen),"logicTimeWindow32");
                LinearLayout editor=(LinearLayout)add.getParent();View windows=(View)last.getParent();
                check(add.isEnabled()&&editor.indexOfChild(add)==editor.getChildCount()-1&&editor.indexOfChild(windows)<editor.indexOfChild(add),"Add window stays below the last of 33 windows and has no fixed count limit");
                ((EditText)tagged(root(screen),"logicTimeWindowAdjustment1")).setText("-3");
                ((CheckBox)tagged(root(screen),"logicTimeWindowEnabled1")).setChecked(true);
                LogicProfile overlap=Settings.logicDraft(isolated);
                check(overlap.timeWindows.size()==33&&overlap.windowAdjustmentAtMinute(270)==-4&&fake.applied.size()==before[0],"Independent -1 and -3 windows overlap as -4 and editing never uploads");
                ((CheckBox)tagged(root(screen),"logicTimeWindowEnabled1")).setChecked(false);
                LogicProfile disabled=Settings.logicDraft(isolated);LogicProfile.TimeWindow saved=disabled.timeWindows.get(1);
                check(disabled.timeWindows.size()==33&&!saved.enabled&&saved.adjustment==-3&&saved.startMinute==270&&saved.endMinute==480&&disabled.windowAdjustmentAtMinute(270)==-1,"Unchecking keeps the window and all settings but excludes its adjustment");
                click(screen,"Home",0);click(screen,"Setup",0);
                same(disabled,Settings.logicDraft(isolated),"All windows and disabled settings survive editor rebuild");
                check(!((CheckBox)tagged(root(screen),"logicTimeWindowEnabled1")).isChecked()&&shown(screen,"logicTimeWindowAdjustment1").equals("-3")&&tagged(root(screen),"logicTimeWindow32")!=null,"Disabled window remains visible alongside all others");
                Settings.saveNamedLogicProfile(isolated,disabled);same(disabled,Settings.loadLogicProfile(isolated,disabled.name),"Named snapshots retain every enabled and disabled window");
                ((CheckBox)tagged(root(screen),"logicTimeWindowEnabled1")).setChecked(true);
                tagged(root(screen),"logicTimeWindowRemove").performClick();
                LogicProfile remaining=Settings.logicDraft(isolated);
                check(remaining.timeWindows.size()==32&&remaining.timeWindow.enabled&&remaining.timeWindow.adjustment==-3&&remaining.timeWindow.startMinute==270&&tagged(root(screen),"logicTimeWindow32")==null,"Removing the first window preserves and renumbers the remaining values");
                click(screen,"Apply to fan",0);check(fake.applied.size()==before[0]+1,"Apply uploads the complete edited list once");same(remaining,fake.applied.get(fake.applied.size()-1),"Apply snapshot includes every remaining window");check(fake.unexpected.isEmpty(),"No hardware path invoked by multi-window edits");
            });
            return "PASS: isolated range profiles start from defaults with carried timing, leave earlier threshold profiles untouched, keep applied/unsent/named settings apart, same-name upsert, full-field load and detached values; chooser and Save as on one row, Apply-only upload, confirmed replacement of unsent edits, debounce protection, offline Apply; time-window controls, local time labels, signed adjustment draft/Apply, named roundtrip, overnight values and persistent disable; emulator and fake controller only.\n";
        }finally{
            try{if(activity!=null){Activity closing=activity;onMain(test,closing::finish);test.waitForIdleSync();}}
            finally{try{onMain(test,()->{activeScope=null;if(installed[0]&&BreezeService.current==fake)BreezeService.current=null;});}finally{scope.close();check(originalPreferences.equals(Settings.prefs(real).getAll()),"Real preferences remained unchanged");}}
        }
    }
}
