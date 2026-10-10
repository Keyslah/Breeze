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
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.IntFunction;
import org.json.JSONObject;

/** Emulator-only actual Activity checks with isolated files/preferences and fake transport. */
public final class NamedLogicProfileChecks {
    private NamedLogicProfileChecks(){}
    private static final String LIBRARY="logicSavedProfilesV1";
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
    private static void same(LogicProfile expected,LogicProfile actual,String message){check(fields(expected).equals(fields(actual)),message);}
    private static Map<String,LogicProfile> profiles(Context context){Map<String,LogicProfile> result=new HashMap<>();for(LogicProfile profile:Settings.savedLogicProfiles(context)){check(result.put(profile.name,profile)==null,"Unique saved names");}return result;}
    private static LogicProfile profile(String name,int variant){
        LogicProfile p=new LogicProfile();p.id="shared-identity";p.name=name;p.roomBoundaryF=75.25+variant;p.coolRoomThresholdF=69.5+variant;p.probeMask=variant==0?3:7;p.averageSeconds=47+variant;p.riseSeconds=17+variant;p.fallSeconds=13+variant;p.maxSpeed=8-variant;
        for(int i=0;i<2;i++){LogicProfile.Rule r=i==0?p.warm:p.cool;r.bodyThresholdF=97.35-i+variant;r.stepF=0.35+i*0.2+variant*0.1;r.minimumSpeed=4-i-variant;r.offBelow=i==1;r.boost.enabled=(i==variant);r.boost.amount=3+i;r.boost.triggerRiseF=0.65+i*0.2;r.boost.windowSeconds=43+i*16+variant;r.boost.riseSeconds=4+i*4+variant;r.boost.holdSeconds=149+i*122+variant;r.boost.returnSeconds=7+i*4+variant;r.boost.curve=(i+variant)%2;}
        p.validate();return p;
    }
    private static String mismatchedEntry(LogicProfile profile){try{return new JSONObject().put("Wrong entry name",profile.serialize()).toString();}catch(Exception failure){throw new AssertionError(failure);}}
    private static void storage(Context context){
        SharedPreferences prefs=Settings.prefs(context);LogicProfile legacy=profile("Legacy active",0);check(prefs.edit().putString("logicProfileV1",legacy.serialize()).commit(),"Prepare legacy active fixture");
        Map<String,LogicProfile> library=profiles(context);check(library.size()==1,"First access seeds exactly the existing active profile");same(legacy,library.get(legacy.name),"Legacy seed retains every field");
        LogicProfile typed=profile("Partially typed name",1);Settings.saveLogicProfile(context,typed);check(profiles(context).size()==1&&!profiles(context).containsKey(typed.name),"Autosave does not add typed names to saved profiles");same(legacy,profiles(context).get(legacy.name),"Autosave preserves legacy snapshot");
        LogicProfile a=profile("Profile A",0),b=profile("Profile B",1);Settings.saveNamedLogicProfile(context,a);Settings.saveNamedLogicProfile(context,b);library=profiles(context);check(library.size()==3,"Explicit A and B survive despite the same profile identity");same(a,library.get(a.name),"Saving B preserves A");same(b,library.get(b.name),"Saving B stores every field");
        b.warm.boost.holdSeconds++;Settings.saveLogicProfile(context,b);same(profile("Profile B",1),profiles(context).get(b.name),"Autosaving an existing name does not overwrite its saved snapshot");
        a.cool.boost.returnSeconds=43;Settings.saveNamedLogicProfile(context,a);check(profiles(context).size()==3,"Same-name explicit save replaces one snapshot");same(a,profiles(context).get(a.name),"Same-name explicit save updates the whole snapshot");
        LogicProfile detached=profiles(context).get(a.name);detached.name="Detached";detached.cool.boost.returnSeconds=99;same(a,profiles(context).get(a.name),"Returned library values cannot mutate saved snapshots");
        same(a,Settings.loadLogicProfile(context,a.name),"Load returns all fields including independent thresholds, boosts and probes");same(a,Settings.logicProfile(context),"Offline load persists active profile");same(a,Settings.logicProfile(new IsolatedContext(((IsolatedContext)context).scope.base,((IsolatedContext)context).scope)),"Active loaded profile survives a fresh context");
        String libraryBefore=prefs.getString(LIBRARY,null);try{Settings.loadLogicProfile(context,"Missing profile");throw new AssertionError("Missing profile was accepted");}catch(IllegalArgumentException expected){}same(a,Settings.logicProfile(context),"Missing selection preserves active profile");check(libraryBefore.equals(prefs.getString(LIBRARY,null)),"Load never rewrites saved snapshots");
        LogicProfile spaced=profile("  Trimmed profile  ",1);Settings.saveNamedLogicProfile(context,spaced);check(profiles(context).containsKey("Trimmed profile")&&!profiles(context).containsKey(spaced.name),"Explicit save normalizes surrounding name spaces");same(spaced,Settings.loadLogicProfile(context," Trimmed profile "),"Load accepts surrounding name spaces");try{Settings.loadLogicProfile(context,"trimmed profile");throw new AssertionError("Case-insensitive lookup unexpectedly accepted");}catch(IllegalArgumentException expected){}
        String validLibrary=prefs.getString(LIBRARY,null);LogicProfile beforeCorruption=Settings.logicProfile(context);
        for(String corrupt:new String[]{"[]","{} trailing","{\"Bad\":4}",mismatchedEntry(a)}){
            check(prefs.edit().putString(LIBRARY,corrupt).commit(),"Prepare corrupt isolated library");
            try{Settings.savedLogicProfiles(context);throw new AssertionError("Corrupt library accepted by list");}catch(IllegalArgumentException expected){}
            try{Settings.loadLogicProfile(context,a.name);throw new AssertionError("Corrupt library accepted by load");}catch(IllegalArgumentException expected){}
            try{Settings.saveNamedLogicProfile(context,a);throw new AssertionError("Corrupt library overwritten by explicit save");}catch(IllegalArgumentException expected){}
            same(beforeCorruption,Settings.logicProfile(context),"Library failures preserve active profile");check(corrupt.equals(prefs.getString(LIBRARY,null)),"Library failures preserve the stored collection exactly");
        }
        check(prefs.edit().putString(LIBRARY,validLibrary).commit(),"Restore valid isolated collection");
        check(prefs.edit().remove(LIBRARY).putString("logicProfileV1",legacy.serialize()).commit(),"Prepare pre-library autosave fixture");Settings.saveLogicProfile(context,typed);library=profiles(context);check(library.size()==1&&library.containsKey(legacy.name),"First autosave seeds the prior active profile, not the draft");same(legacy,library.get(legacy.name),"First autosave retains the old settings exactly");
    }
    private static List<TextView> labels(View view,String label){List<TextView> result=new ArrayList<>();collectLabels(view,label,result);return result;}
    private static void collectLabels(View view,String label,List<TextView> result){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))result.add((TextView)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectLabels(group.getChildAt(i),label,result);}}
    private static EditText input(View view,String label){if(view instanceof EditText&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=input(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
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
            storage(isolated);LogicProfile a=profile("UI A",0);Settings.saveNamedLogicProfile(isolated,a);Settings.saveNamedLogicProfile(isolated,profile("UI B",1));Settings.loadLogicProfile(isolated,a.name);
            onMain(test,()->{check(BreezeService.current==null&&BedtimeVoiceService.current==null,"Existing services are left untouched");BreezeService.current=fake;installed[0]=true;activeScope=scope;});
            activity=test.startActivitySync(new Intent(real,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;test.waitForIdleSync();
            onMain(test,()->click(screen,"Setup",0));test.waitForIdleSync();
            LogicProfile[] savedA={null},savedB={null};
            onMain(test,()->{
                check(Settings.prefs(screen)==Settings.prefs(isolated),"Activity uses isolated preferences");List<TextView> saves=labels(root(screen),"Save profile"),loads=labels(root(screen),"Load profile");check(saves.size()==2&&loads.size()==2,"Top and bottom Save/Load pairs exist");
                for(int i=0;i<2;i++){check(saves.get(i).getParent()==loads.get(i).getParent(),"Save and Load share their row");LinearLayout row=(LinearLayout)saves.get(i).getParent();check(row.getOrientation()==LinearLayout.HORIZONTAL&&row.indexOfChild(saves.get(i))<row.indexOfChild(loads.get(i)),"Load follows Save horizontally");}
                check(fake.applied.isEmpty(),"Constructing Logic does not upload");input(root(screen),"Profile name").setText("UI A");input(root(screen),"Room temperature at or above (°F)").setText("77.25");click(screen,"Save profile",0);savedA[0]=Settings.logicProfile(isolated);check(fake.applied.size()==1,"Top Save applies once");
                input(root(screen),"Profile name").setText("UI B");input(root(screen),"Room temperature below (°F)").setText("68.5");click(screen,"Save profile",1);savedB[0]=Settings.logicProfile(isolated);check(fake.applied.size()==2,"Bottom Save applies once");same(savedA[0],profiles(isolated).get("UI A"),"Saving UI B preserves UI A");
                input(root(screen),"Profile name").setText("Unsaved typed name");
            });
            SystemClock.sleep(750);test.waitForIdleSync();
            int[] before={0};LogicProfile[] cancelBefore={null};String[] libraryBefore={null};
            onMain(test,()->{check(Settings.logicProfile(isolated).name.equals("Unsaved typed name"),"Typing autosaves active state");check(!profiles(isolated).containsKey("Unsaved typed name"),"Typed name does not pollute saved library");same(savedB[0],profiles(isolated).get("UI B"),"Autosave preserves B snapshot");before[0]=fake.applied.size();cancelBefore[0]=Settings.logicProfile(isolated);libraryBefore[0]=Settings.prefs(isolated).getString(LIBRARY,null);click(screen,"Load profile",0);});
            dialogClick(test,"Cancel");onMain(test,()->{check(fake.applied.size()==before[0],"Cancel never applies another profile");same(cancelBefore[0],Settings.logicProfile(isolated),"Cancel preserves active profile");});
            // Opening flushes the draft. Selection must cancel its old closure/debounce
            // before rebuilding, so the saved A cannot be replaced by the pending draft.
            onMain(test,()->{input(root(screen),"Profile name").setText("Pending debounce draft");input(root(screen),"Room temperature at or above (°F)").setText("78.25");click(screen,"Load profile",0);before[0]=fake.applied.size();});
            dialogClick(test,"UI A");onMain(test,()->{same(savedA[0],Settings.logicProfile(isolated),"Chooser loads A in full");check(fake.applied.size()==before[0]+1,"Selection applies exactly once");same(savedA[0],fake.applied.get(fake.applied.size()-1),"Uploaded profile is selected A");check(input(root(screen),"Profile name").getText().toString().equals("UI A"),"Loaded name replaces editor");before[0]=fake.applied.size();});
            SystemClock.sleep(750);test.waitForIdleSync();onMain(test,()->{same(savedA[0],Settings.logicProfile(isolated),"Pending debounce cannot overwrite loaded A");check(fake.applied.size()==before[0],"No stale delayed profile application");check(libraryBefore[0].equals(Settings.prefs(isolated).getString(LIBRARY,null)),"Load and typing preserve saved collection bytes");fake.ready=false;click(screen,"Load profile",1);});
            dialogClick(test,"UI B");onMain(test,()->{same(savedB[0],Settings.logicProfile(isolated),"Offline chooser load persists B");check(fake.applied.size()==before[0]+1,"Offline load makes only one fake apply attempt");check(fake.unexpected.isEmpty(),"No hardware path invoked: "+fake.unexpected);});
            return "PASS: isolated named-profile legacy seeding, explicit A/B snapshots, active-only autosave and typed names, same-name upsert, full-field load and detached values; top/bottom same-row Save/Load, chooser selection once/cancel, debounce protection, offline persistence; emulator and fake controller only.\n";
        }finally{
            try{if(activity!=null){Activity closing=activity;onMain(test,closing::finish);test.waitForIdleSync();}}
            finally{try{onMain(test,()->{activeScope=null;if(installed[0]&&BreezeService.current==fake)BreezeService.current=null;});}finally{scope.close();check(originalPreferences.equals(Settings.prefs(real).getAll()),"Real preferences remained unchanged");}}
        }
    }
}
