package com.aaron.breeze;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Actual decor-dispatched touches with isolated storage and no physical transport. */
public final class HomeMotionChecks {
    private static final int[] SERIES={0,1,7,6,2,4};
    private static final String[] REMOTES={"homePower","homeSpeedPlus","homeSpeedMinus","homeMode","homeHorizontal","homeVertical"};
    private static final int LOGIC_ACTIVE=0xff78b5e1,LOGIC_INACTIVE=0xffb59cdb;
    private static final String DRAFT="homeFanSettingDraft";
    private static final String DRAFT_NEEDS_CONFIRMATION="homeFanDraftNeedsConfirmation";
    private static final String DRAFT_REVISION="homeFanDraftRevision";
    private static volatile Scope activeScope;
    private HomeMotionChecks(){}
    private interface MainAction {void run() throws Exception;}
    private interface MainCondition {boolean get() throws Exception;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void onMain(Instrumentation test,MainAction action)throws Exception {
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Home motion fixture",failure[0]);
    }
    private static void await(Instrumentation test,MainCondition condition,String message)throws Exception {
        long deadline=SystemClock.uptimeMillis()+5000;
        do{boolean[] complete={false};onMain(test,()->complete[0]=condition.get());if(complete[0])return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError(message);
    }
    private static Field field(Class<?> owner,String name)throws Exception {Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void refresh(Activity activity)throws Exception {Method method=MainActivity.class.getDeclaredMethod("refresh");method.setAccessible(true);method.invoke(activity);}
    private static boolean resumed(Activity activity)throws Exception {return field(MainActivity.class,"activityResumed").getBoolean(activity);}
    private static View root(Activity activity){return activity.getWindow().getDecorView();}
    private static View tagged(Activity activity,String tag){View view=root(activity).findViewWithTag(tag);check(view!=null,"Missing Home tag: "+tag);return view;}
    private static TextView label(View view,String text){
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++){TextView found=label(group.getChildAt(index),text);if(found!=null)return found;}}
        return null;
    }
    private static boolean fullyVisible(View view){Rect visible=new Rect();return view.isShown()&&view.getLocalVisibleRect(visible)&&visible.width()>=view.getWidth()-2&&visible.height()>=view.getHeight()-2;}
    private static Rect bounds(View view){int[] position=new int[2];view.getLocationOnScreen(position);return new Rect(position[0],position[1],position[0]+view.getWidth(),position[1]+view.getHeight());}
    private static void motion(Instrumentation test,View decor,long down,int action,float x,float y)throws Exception {
        onMain(test,()->{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{check(decor.dispatchTouchEvent(event),"Decor rejected touch action "+action);}finally{event.recycle();}});
    }
    private static void tap(Instrumentation test,Activity activity,View view,String description)throws Exception {
        float[] point=new float[2];View decor=root(activity);
        onMain(test,()->{check(view.isEnabled(),description+" is disabled before touch");check(fullyVisible(view),description+" is clipped before touch");Rect local=new Rect();check(view.getLocalVisibleRect(local),description+" has no visible bounds");int[] control=new int[2],window=new int[2];view.getLocationOnScreen(control);decor.getLocationOnScreen(window);point[0]=control[0]-window[0]+local.exactCenterX();point[1]=control[1]-window[1]+local.exactCenterY();});
        long down=SystemClock.uptimeMillis();motion(test,decor,down,MotionEvent.ACTION_DOWN,point[0],point[1]);SystemClock.sleep(60);motion(test,decor,down,MotionEvent.ACTION_UP,point[0],point[1]);test.waitForIdleSync();
    }
    private static void tapTag(Instrumentation test,Activity activity,String tag)throws Exception {View[] view={null};onMain(test,()->view[0]=tagged(activity,tag));tap(test,activity,view[0],tag);}
    private static void pointer(Instrumentation test,long down,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{test.sendPointerSync(event);}finally{event.recycle();}}
    private static void osManualSmoke(Instrumentation test,Activity activity,FakeController fake,String scenario)throws Exception {
        await(test,()->resumed(activity)&&activity.hasWindowFocus(),"OS touch requires a resumed focused Home window");
        for(int slot=0;slot<3;slot++){
            final int expectedSlot=slot;float[] point=new float[2];int[] before={0},corrections={0};
            onMain(test,()->{View control=tagged(activity,REMOTES[expectedSlot]);check(control.isEnabled()&&fullyVisible(control),"OS smoke requires a visible ready "+REMOTES[expectedSlot]);Rect local=new Rect();check(control.getLocalVisibleRect(local),"OS smoke control has visible bounds");int[] screenPosition=new int[2];control.getLocationOnScreen(screenPosition);point[0]=screenPosition[0]+local.exactCenterX();point[1]=screenPosition[1]+local.exactCenterY();before[0]=fake.manualSlots.size();corrections[0]=fake.corrections.size();});
            long down=SystemClock.uptimeMillis();pointer(test,down,MotionEvent.ACTION_DOWN,point[0],point[1]);SystemClock.sleep(60);pointer(test,down,MotionEvent.ACTION_UP,point[0],point[1]);test.waitForIdleSync();
            onMain(test,()->{check(fake.manualSlots.size()==before[0]+1&&fake.manualSlots.get(before[0])==expectedSlot,scenario+" OS-injected touch dispatches the expected command exactly once");check(fake.corrections.size()==corrections[0],"OS touch cannot submit an anchor correction before its ACK");check(fake.pendingDraft==null,"Unedited OS touch has no correction draft");fake.completeManualAck(true);refresh(activity);check(fake.corrections.size()==corrections[0],"Unedited OS touch ACK never sends SYNC");currentSetting(activity,FanSetting.label(fake.fan.speed));});
        }
    }
    private static void navigate(Instrumentation test,Activity activity,String destination)throws Exception {View[] view={null};onMain(test,()->{view[0]=label(root(activity),destination);check(view[0]!=null,"Missing navigation: "+destination);});tap(test,activity,view[0],destination);}

    private static final class Scope {
        final Context base;final String prefix="temporary-home-motion-"+SystemClock.elapsedRealtimeNanos();
        final Set<String> preferences=new LinkedHashSet<>(),databases=new LinkedHashSet<>();
        final File temporaryRoot,files,cache;int reconnects;String unexpected="";
        Scope(Context base){this.base=base;temporaryRoot=new File(base.getCacheDir(),prefix);files=new File(temporaryRoot,"files");cache=new File(temporaryRoot,"cache");check(files.mkdirs()&&cache.mkdirs(),"Create current-test storage");}
        String preference(String name){String scoped=prefix+"-"+name;preferences.add(scoped);return scoped;}
        String database(String name){String scoped=prefix+"-"+name;databases.add(scoped);return scoped;}
        ComponentName start(Intent intent){ComponentName component=intent.getComponent();if(component!=null&&component.getClassName().equals(BreezeService.class.getName())&&intent.getBooleanExtra("force_reconnect",false)){reconnects++;return component;}unexpected="Unexpected service start: "+component;throw new AssertionError(unexpected);}
        void close(){for(String name:preferences)base.deleteSharedPreferences(name);for(String name:databases)base.deleteDatabase(name);removeTemporary(temporaryRoot);}
        void removeTemporary(File file){check(file.getAbsolutePath().equals(temporaryRoot.getAbsolutePath())||file.getAbsolutePath().startsWith(temporaryRoot.getAbsolutePath()+File.separator),"Cleanup stays inside this task's temporary storage");File[] children=file.listFiles();if(children!=null)for(File child:children)removeTemporary(child);check(!file.exists()||file.delete(),"Remove only current-test temporary files");}
    }
    private static final class IsolatedContext extends ContextWrapper {
        final Scope scope;
        IsolatedContext(Context base,Scope scope){super(base);this.scope=scope;}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return scope.base.getSharedPreferences(scope.preference(name),mode);}
        @Override public File getDatabasePath(String name){return scope.base.getDatabasePath(scope.database(name));}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return scope.base.openOrCreateDatabase(scope.database(name),mode,factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler errors){return scope.base.openOrCreateDatabase(scope.database(name),mode,factory,errors);}
        @Override public File getFilesDir(){return scope.files;}
        @Override public File getCacheDir(){return scope.cache;}
        @Override public int checkSelfPermission(String permission){return permission.equals(Manifest.permission.BLUETOOTH_SCAN)||permission.equals(Manifest.permission.BLUETOOTH_CONNECT)||permission.equals(Manifest.permission.POST_NOTIFICATIONS)?PackageManager.PERMISSION_GRANTED:super.checkSelfPermission(permission);}
        @Override public ComponentName startForegroundService(Intent intent){return scope.start(intent);}
        @Override public ComponentName startService(Intent intent){return scope.start(intent);}
        @Override public boolean stopService(Intent intent){ComponentName component=intent.getComponent();check(component!=null&&component.getClassName().equals(BedtimeVoiceService.class.getName()),"Only the isolated voice fixture may be stopped");return true;}
    }
    /** Installed by StoreTestRunner before Activity.onCreate. */
    static void isolateActivity(Activity activity){Scope scope=activeScope;if(scope==null||!(activity instanceof MainActivity))return;try{field(ContextWrapper.class,"mBase").set(activity,new IsolatedContext(activity.getBaseContext(),scope));}catch(Exception failure){throw new AssertionError("Could not isolate Home Activity",failure);}}

    private static final class SavedField {
        final Field field;final Object owner,value;
        SavedField(Class<?> type,Object owner,String name)throws Exception {field=HomeMotionChecks.field(type,name);this.owner=owner;value=field.get(owner);}
        void restore()throws Exception {field.set(owner,value);}
    }
    /** Preserve process policy as well as persistent preferences. No live services are replaced. */
    private static List<SavedField> snapshotProcess()throws Exception {
        List<SavedField> snapshot=new ArrayList<>();Object voice=field(RecordingVoiceController.class,"state").get(null),connection=field(BreezeService.class,"uiConnection").get(null);
        for(String name:new String[]{"app","starting","message","recordingContext","lastResult"})snapshot.add(new SavedField(RecordingVoiceController.class,null,name));
        for(String name:new String[]{"active","pending","suppressed","listeningDisabled"})snapshot.add(new SavedField(RecordingVoiceState.class,voice,name));
        for(String name:new String[]{"visible","disconnected","generation","backgroundDeadlineMs"})snapshot.add(new SavedField(UiConnectionState.class,connection,name));
        return snapshot;
    }
    private static Protocol.LogicState confirmedLogic(boolean automatic){byte[] packet=new byte[20];packet[0]=9;packet[1]=(byte)(automatic?1:0);packet[2]=(byte)255;packet[3]=4;packet[13]=7;packet[19]=1;ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(4,(short)9750).putShort(6,(short)7358);return Protocol.decodeLogic(packet,System.currentTimeMillis());}
    private static final class FakeController extends BreezeService {
        final Context context;final List<Integer> manualSlots=new ArrayList<>(),corrections=new ArrayList<>(),submittedDrafts=new ArrayList<>();final List<Boolean> logicRequests=new ArrayList<>(),focusedAtSend=new ArrayList<>();
        Activity screen;boolean stale,staleFan,staleLogic,manualPending,acceptLogic=true;int recordingStarts,recordingStops,manualRejections,pendingSlot=-1,retainedSpeed=4;long pendingDraftRevision;String unexpected="";Integer pendingDraft;HomeFanResult pendingManualDone;
        FakeController(Context context){this.context=context;attachBaseContext(context);ready=sensorFirmware=thirdProbeSupported=irFirmware=logicSupported=true;latest=new Protocol.Reading();latest.flags=128;latest.probe=31.3;latest.thirdProbe=36.25;latest.room=23.1;latest.humidity=58.2;fan=new Protocol.FanState();fan.flags=4;fan.speed=fan.target=4;fan.secondProbe=30.5;fan.learning=fan.resultSlot=fan.lastSent=255;fan.learned=4095;logic=confirmedLogic(false);}
        private void unexpected(String action){if(unexpected.isEmpty())unexpected=action;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override public boolean fresh(){return ready&&!stale;}
        @Override public boolean freshFan(){return fresh()&&!staleFan&&irFirmware&&fan!=null;}
        @Override public boolean freshLogic(){return freshFan()&&!staleLogic&&logic!=null;}
        @Override public boolean manualIrReady(){return freshFan()&&!manualPending&&fan.learning==255&&!logicUploading;}
        @Override public boolean fanWritePending(){return manualPending;}
        @Override public void changed(){}
        @Override public boolean fanCommand(int action,int slot,int argument){if(action!=Protocol.SEND_IR||slot<0||slot>5||argument!=0){unexpected("fan command "+action+"/"+slot+"/"+argument);return false;}if(!manualIrReady()){manualRejections++;lastError="Not sent—controller is not ready.";return false;}focusedAtSend.add(screen!=null&&tagged(screen,"homeCurrentFanSetting").hasFocus());manualSlots.add(slot);fan.lastSent=slot;fan.sendCounter++;lastError="";return true;}
        private long draftRevision(){Object value=Settings.prefs(context).getAll().get(DRAFT_REVISION);check(value==null||value instanceof Number,"Draft revision is numeric");return value==null?0:((Number)value).longValue();}
        @Override public boolean homeFanCommand(int slot,Integer draft,HomeFanResult done){if(!fanCommand(Protocol.SEND_IR,slot,0))return false;check(pendingManualDone==null,"Fixture holds at most one pending manual ACK");submittedDrafts.add(draft);pendingDraft=draft;pendingDraftRevision=draftRevision();pendingSlot=slot;pendingManualDone=done;manualPending=true;return true;}
        private void confirmSpeed(int speed){Protocol.FanState previous=fan;fan=new Protocol.FanState();fan.flags=4;fan.speed=fan.target=speed;fan.secondProbe=previous.secondProbe;fan.learning=fan.resultSlot=255;fan.lastSent=previous.lastSent;fan.sendCounter=previous.sendCounter;fan.learned=previous.learned;fan.time=System.currentTimeMillis();}
        void completeManualAck(boolean success){
            check(pendingManualDone!=null,"Explicit ACK completes an accepted Home command");HomeFanResult done=pendingManualDone;Integer draft=pendingDraft;int slot=pendingSlot;long revision=pendingDraftRevision;pendingManualDone=null;pendingDraft=null;pendingSlot=-1;manualPending=false;
            if(!success){if(revision==draftRevision()&&Settings.prefs(context).contains(DRAFT))Settings.prefs(context).edit().putBoolean(DRAFT_NEEDS_CONFIRMATION,true).apply();done.done(false,null);return;}
            int observed=fan.speed;if(slot==0){if(observed>0){retainedSpeed=observed;observed=0;}else observed=retainedSpeed;}else if(slot==1&&observed>0)observed=observed==10?1:observed+1;else if(slot==2&&observed>0)observed=observed==1?10:observed-1;confirmSpeed(observed);
            Integer corrected=null;if(draft!=null){if(slot==0&&draft>0)corrected=0;else if(slot==1&&draft>0&&draft<=10)corrected=draft==10?1:draft+1;else if(slot==2&&draft>=1&&draft<=10)corrected=draft==1?10:draft-1;else if(slot==4||slot==5)corrected=draft;}
            if(corrected!=null){final Integer value=corrected;setHomeFanAnchor(value,ok->done.done(true,ok?value:null));}else{if(draft!=null){lastError="Check the current fan setting after "+Protocol.COMMANDS[slot]+" and apply it with Done.";if(revision==draftRevision())Settings.prefs(context).edit().putBoolean(DRAFT_NEEDS_CONFIRMATION,true).apply();}done.done(true,null);}
        }
        @Override public boolean setHomeFanAnchor(int speed,Result done){corrections.add(speed);confirmSpeed(speed);Settings.prefs(context).edit().putInt("fanAnchor",speed).putBoolean("fanAnchorCorrectionPending",false).apply();anchorCorrectionPending=false;if(done!=null)done.done(true);return true;}
        @Override public void setFanAnchor(int speed){unexpected("Home used legacy fan anchor path");}
        @Override public boolean setHomeLogicEnabled(boolean enabled){logicRequests.add(enabled);return acceptLogic;}
        @Override public void startSession(){recordingStarts++;latest.flags|=1;RecordingVoiceController.onTelemetry(context,true);}
        @Override public void control(int action,int value){if(action!=Protocol.STOP_RECORDING||value!=0){unexpected("recording control "+action+"/"+value);return;}recordingStops++;latest.flags&=~1;RecordingVoiceController.onTelemetry(context,false);}
        @Override public boolean applyLogicProfile(LogicProfile profile){unexpected("profile upload");return false;}
        @Override public void command(IntFunction<byte[]> make,Result result){unexpected("transport command");}
        @Override public void scan(){unexpected("Bluetooth scan");}
        @Override public void connect(String address){unexpected("Bluetooth connect");}
        @Override public void download(){unexpected("record download");}
        @Override public void startContactTest(){unexpected("probe test");}
    }
    private static String temperature(double value){return Double.isFinite(value)?String.format(Locale.US,"%.1f°F",value*1.8+32):"—";}
    private static void currentSetting(Activity activity,String expected){EditText input=(EditText)tagged(activity,"homeCurrentFanSetting");check(expected.contentEquals(input.getText()),"Current fan setting expected "+expected+", received "+input.getText());}
    private static void reading(Activity activity,int series,String expected){TextView value=(TextView)tagged(activity,"homeReadingValue"+series);check(expected.contentEquals(value.getText()),GraphView.LABELS[series]+" shows "+expected+", received "+value.getText());}
    private static void assertNoScrollOrChecks(View view){check(!(view instanceof ScrollView),"Home has no ScrollView");check(!(view instanceof CheckBox),"Home has no trace checkboxes");if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++)assertNoScrollOrChecks(group.getChildAt(index));}}
    private static void layout(Activity activity){
        View home=tagged(activity,"homeRoot"),readings=tagged(activity,"homeReadings"),controls=tagged(activity,"homeControls");assertNoScrollOrChecks(home);
        View ancestor=home;while(ancestor.getParent() instanceof View){ancestor=(View)ancestor.getParent();check(!(ancestor instanceof ScrollView),"Home has no scrolling ancestor");}
        Rect area=bounds(home),upper=bounds(readings),lower=bounds(controls);float middle=area.top+area.height()/2f;
        check(area.height()>0&&upper.top>=area.top-2&&upper.bottom<=middle+2,"Live readings fit in Home's top half");
        check(lower.top>=middle-2&&lower.bottom<=area.bottom+2,"Remote controls fit in Home's bottom half");
        GraphReadingDetailsView historyReference=new GraphReadingDetailsView(activity);
        for(int series:SERIES){TextView name=(TextView)tagged(activity,"homeReadingLabel"+series),value=(TextView)tagged(activity,"homeReadingValue"+series),historyName=label(historyReference,GraphView.LABELS[series]);check(historyName!=null,"History reference contains series "+series);TextView historyValue=label((View)historyName.getParent(),"—");check(historyValue!=null,"History reference contains the reading value for series "+series);check(GraphView.LABELS[series].contentEquals(name.getText()),"Home label matches History series "+series);check(name.getCurrentTextColor()==GraphView.COLORS[series]&&value.getCurrentTextColor()==GraphView.COLORS[series],"Home uses History color "+series);check(Math.abs(name.getTextSize()-historyName.getTextSize())<0.1&&Math.abs(value.getTextSize()-historyValue.getTextSize())<0.1,"Home uses History label/value font sizes at the current Android font scale");check(historyValue.getTypeface().equals(value.getTypeface()),"Home uses History value typeface");check(fullyVisible(name)&&fullyVisible(value),"Both reading texts fit: "+GraphView.LABELS[series]);}
        for(String tag:new String[]{"homePower","homeSpeedPlus","homeSpeedMinus","homeMode","homeHorizontal","homeVertical","homeLogic","homeRecording","homeReconnect"}){View view=tagged(activity,tag);check(view instanceof Button,"Home control is a button: "+tag);check(view.getElevation()==0&&view.getStateListAnimator()==null,"Home button has no elevation animation: "+tag);check(!view.isFocusable()&&!view.isFocusableInTouchMode(),"Home button cannot consume a tap for focus: "+tag);check(fullyVisible(view),"Home button fits without scrolling: "+tag);check(bounds(view).top>=middle-2,"Home button is in lower half: "+tag);}
        check(fullyVisible(tagged(activity,"homeCurrentFanSetting")),"Current fan setting fits on Home");
    }
    private static int logicColor(Activity activity){View view=tagged(activity,"homeLogic");check("Logic".contentEquals(((TextView)view).getText()),"Home Logic text remains just Logic");check(view.getBackground() instanceof GradientDrawable,"Logic button has an inspectable fill");android.content.res.ColorStateList fill=((GradientDrawable)view.getBackground()).getColor();check(fill!=null,"Logic button fill is set");return fill.getDefaultColor();}
    private static void manualTouch(Instrumentation test,Activity activity,FakeController fake,int slot,String scenario)throws Exception {
        int[] before={0},corrections={0};onMain(test,()->{before[0]=fake.manualSlots.size();corrections[0]=fake.corrections.size();});tapTag(test,activity,REMOTES[slot]);
        onMain(test,()->{check(fake.manualSlots.size()==before[0]+1,scenario+" first touch dispatches exactly once: "+REMOTES[slot]);check(fake.manualSlots.get(before[0])==slot,scenario+" sends the expected IR slot");check(fake.corrections.size()==corrections[0],scenario+" command is sent before any ACK-dependent anchor correction");check(fake.pendingManualDone!=null&&fake.manualPending,scenario+" waits for explicit manual ACK");});
    }
    private static void manualTap(Instrumentation test,Activity activity,FakeController fake,int slot,String scenario)throws Exception {manualTouch(test,activity,fake,slot,scenario);onMain(test,()->{int corrections=fake.corrections.size();check(fake.pendingDraft==null,scenario+" unedited command carries no draft");fake.completeManualAck(true);refresh(activity);check(fake.corrections.size()==corrections,scenario+" plain command ACK never sends SYNC");currentSetting(activity,FanSetting.label(fake.fan.speed));});}
    private static void manualRow(Instrumentation test,Activity activity,FakeController fake,String scenario)throws Exception {for(int slot=0;slot<REMOTES.length;slot++)manualTap(test,activity,fake,slot,scenario);}
    private static void pauseResume(Instrumentation test,Activity screen,Context context)throws Exception {onMain(test,()->check(screen.moveTaskToBack(true),"Background the isolated Activity"));await(test,()->!resumed(screen),"Home Activity did not pause");context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));await(test,()->resumed(screen)&&screen.hasWindowFocus(),"Same Home Activity did not resume");test.waitForIdleSync();}
    private static void lastSettingFallback(Instrumentation test,Activity screen,FakeController fake,Context context)throws Exception {
        int[] before={0},saved={0},logicRequests={0};
        onMain(test,()->{before[0]=fake.corrections.size();saved[0]=Settings.prefs(context).getInt("fanAnchor",1);logicRequests[0]=fake.logicRequests.size();check(!Settings.prefs(context).contains(DRAFT),"Fallback check starts without an edited draft");fake.confirmSpeed(saved[0]==9?8:9);fake.staleFan=true;refresh(screen);currentSetting(screen,FanSetting.label(saved[0]));check(((TextView)tagged(screen,"homeConnection")).getText().toString().contains("Showing last fan setting"),"Stale status labels the saved-setting fallback");check(fake.corrections.size()==before[0],"Stale fallback cannot automatically send SYNC");fake.staleFan=false;fake.fan.flags&=~4;refresh(screen);currentSetting(screen,FanSetting.label(saved[0]));check(((TextView)tagged(screen,"homeConnection")).getText().toString().contains("Done confirms it"),"Unsynced fallback offers explicit Done confirmation");check(fake.corrections.size()==before[0],"Unknown fallback cannot automatically send SYNC");});
        tapTag(test,screen,"homeCurrentFanSetting");
        onMain(test,()->{EditText input=(EditText)tagged(screen,"homeCurrentFanSetting");currentSetting(screen,FanSetting.label(saved[0]));input.onEditorAction(EditorInfo.IME_ACTION_DONE);check(fake.corrections.size()==before[0]+1&&fake.corrections.get(before[0])==saved[0],"Unchanged Done confirms the displayed unsynced number exactly once");check(fake.fan.synced()&&fake.fan.speed==saved[0],"Done confirms controller status with the saved setting");check(Settings.prefs(context).getInt("fanAnchor",-1)==saved[0]&&!Settings.prefs(context).getBoolean("fanAnchorCorrectionPending",true),"Confirmation ACK persists the saved setting and clears pending synchronization");check(!Settings.prefs(context).contains(DRAFT),"Confirmation leaves no pending draft");check(fake.logicRequests.size()==logicRequests[0],"Home confirmation cannot resume Logic");input.onEditorAction(EditorInfo.IME_ACTION_DONE);check(fake.corrections.size()==before[0]+1,"Repeated unchanged Done after confirmation sends no additional SYNC");});
    }
    private static void changedReadinessDuringTouch(Instrumentation test,Activity activity,FakeController fake)throws Exception {
        View[] control={null};float[] point=new float[2];int[] accepted={0},rejected={0};View decor=root(activity);
        onMain(test,()->{control[0]=tagged(activity,"homeSpeedPlus");check(control[0].isEnabled()&&fullyVisible(control[0]),"Readiness-change fixture starts with a visible ready button");int[] viewPosition=new int[2],decorPosition=new int[2];control[0].getLocationOnScreen(viewPosition);decor.getLocationOnScreen(decorPosition);point[0]=viewPosition[0]-decorPosition[0]+control[0].getWidth()/2f;point[1]=viewPosition[1]-decorPosition[1]+control[0].getHeight()/2f;accepted[0]=fake.manualSlots.size();rejected[0]=fake.manualRejections;});
        long down=SystemClock.uptimeMillis();motion(test,decor,down,MotionEvent.ACTION_DOWN,point[0],point[1]);
        onMain(test,()->{fake.manualPending=true;refresh(activity);check(control[0].isEnabled(),"Refresh retains the touched button until UP so the first gesture reaches its handler");});
        SystemClock.sleep(60);motion(test,decor,down,MotionEvent.ACTION_UP,point[0],point[1]);test.waitForIdleSync();
        onMain(test,()->{check(fake.manualSlots.size()==accepted[0]&&fake.manualRejections==rejected[0]+1,"Mid-gesture readiness loss reaches one immediate rejection without an accepted send; accepted before="+accepted[0]+" actual="+fake.manualSlots.size()+", rejected before="+rejected[0]+" expected="+(rejected[0]+1)+" actual="+fake.manualRejections);check(((TextView)tagged(activity,"homeFeedback")).getText().toString().startsWith("Not sent"),"Mid-gesture rejection has visible inline Not sent feedback");fake.manualPending=false;refresh(activity);});
        SystemClock.sleep(400);test.waitForIdleSync();onMain(test,()->check(fake.manualSlots.size()==accepted[0]&&fake.manualRejections==rejected[0]+1,"Readiness recovery cannot replay the rejected touch; accepted before="+accepted[0]+" actual="+fake.manualSlots.size()+", rejected before="+rejected[0]+" expected="+(rejected[0]+1)+" actual="+fake.manualRejections));
    }
    private static boolean emulator(){return Build.FINGERPRINT.startsWith("generic")||Build.MODEL.contains("sdk_gphone")||Build.HARDWARE.equals("goldfish")||Build.HARDWARE.equals("ranchu");}
    public static String run(Instrumentation test)throws Exception {
        if(!emulator())return "SKIPPED: Home motion checks use an emulator and fake controller; physical phone untouched.\n";
        Context real=test.getTargetContext();if(real.getSystemService(KeyguardManager.class).isKeyguardLocked()||!real.getSystemService(PowerManager.class).isInteractive())return "SKIPPED: unlock the emulator before Home motion checks.\n";
        Map<String,?> originalPreferences=new HashMap<>(Settings.prefs(real).getAll());Scope scope=new Scope(real);Context isolated=new IsolatedContext(real,scope);FakeController fake=new FakeController(isolated);Activity activity=null;List<SavedField> snapshot=new ArrayList<>();boolean[] installed={false};
        try{
            check(Settings.prefs(isolated).edit().putBoolean("listeningDisabled",true).putInt("fanAnchor",4).commit(),"Prepare isolated settings");
            onMain(test,()->{check(BreezeService.current==null&&BedtimeVoiceService.current==null&&!RecordingVoiceController.isStarting()&&!field(RecordingVoiceController.class,"starting").getBoolean(null),"Existing controller/listener state must be idle and is left untouched");snapshot.addAll(snapshotProcess());BreezeService.current=fake;activeScope=scope;installed[0]=true;});
            activity=test.startActivitySync(new Intent(real,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;onMain(test,()->fake.screen=screen);test.waitForIdleSync();
            onMain(test,()->{
                check(Settings.prefs(screen)==Settings.prefs(isolated),"Activity uses isolated settings");layout(screen);
                reading(screen,0,temperature(31.3));reading(screen,1,temperature(30.5));reading(screen,7,temperature(36.25));reading(screen,6,temperature(36.25));reading(screen,2,temperature(23.1));reading(screen,4,"58.2%");
                fake.latest.thirdProbe=Double.NaN;refresh(screen);reading(screen,7,"—");reading(screen,6,temperature(31.3));fake.latest.probe=Double.NaN;fake.fan.secondProbe=Double.NaN;refresh(screen);reading(screen,6,"—");reading(screen,2,temperature(23.1));reading(screen,4,"58.2%");
                fake.latest.probe=31.3;fake.fan.secondProbe=30.5;fake.latest.thirdProbe=36.25;fake.stale=true;refresh(screen);for(int series:SERIES)reading(screen,series,"—");fake.stale=false;refresh(screen);
            });
            manualRow(test,screen,fake,"Cold Home");
            osManualSmoke(test,screen,fake,"Cold Home");
            navigate(test,screen,"History");navigate(test,screen,"Home");manualRow(test,screen,fake,"History → Home");osManualSmoke(test,screen,fake,"History → Home");
            navigate(test,screen,"Setup");navigate(test,screen,"Home");manualRow(test,screen,fake,"Setup → Home");
            pauseResume(test,screen,real);manualRow(test,screen,fake,"Pause → resume");
            int[] expectedCorrections={0,4,2,-1,3,3};
            for(int slot=0;slot<REMOTES.length;slot++){
                final int chosen=slot,expected=expectedCorrections[slot];int[] beforeCorrection={0};
                onMain(test,()->{fake.confirmSpeed(4);refresh(screen);beforeCorrection[0]=fake.corrections.size();});
                tapTag(test,screen,"homeCurrentFanSetting");onMain(test,()->{EditText input=(EditText)tagged(screen,"homeCurrentFanSetting");check(input.hasFocus(),"Current field receives the real touch");input.setText("3");check("3".equals(Settings.prefs(isolated).getString(DRAFT,null)),"Typed draft persists before command dispatch");check(!Settings.prefs(isolated).getBoolean(DRAFT_NEEDS_CONFIRMATION,false),"New user edit clears quarantine before command dispatch");});
                manualTouch(test,screen,fake,slot,"Focused Current fan setting");onMain(test,()->{check(fake.focusedAtSend.get(fake.focusedAtSend.size()-1),"Focused-field touch dispatches its command before clearing focus");check(Integer.valueOf(3).equals(fake.pendingDraft),"Home command captures the valid edited draft");EditText input=(EditText)tagged(screen,"homeCurrentFanSetting");check(!input.hasFocus(),"Remote touch clears editing focus after dispatch");currentSetting(screen,"3");check(fake.corrections.size()==beforeCorrection[0],"Focused draft cannot synchronize before manual ACK");});
                if(slot==1){navigate(test,screen,"History");onMain(test,()->check("3".equals(Settings.prefs(isolated).getString(DRAFT,null)),"Pending draft survives leaving Home before ACK"));}
                if(slot==2){tapTag(test,screen,"homeCurrentFanSetting");onMain(test,()->{EditText input=(EditText)tagged(screen,"homeCurrentFanSetting");long captured=fake.pendingDraftRevision;input.setText("8");input.setText("3");check("3".equals(Settings.prefs(isolated).getString(DRAFT,null))&&fake.draftRevision()>captured,"New same-text draft has a newer revision while the earlier command awaits ACK");});}
                onMain(test,()->{fake.completeManualAck(true);check(fake.corrections.size()==beforeCorrection[0]+(expected<0?0:1),"ACK applies only an unambiguous post-command correction for slot "+chosen);if(expected>=0){check(fake.corrections.get(fake.corrections.size()-1)==expected&&fake.fan.speed==expected,"Draft 3 adjusts to the command's resulting setting for slot "+chosen);if(chosen==2){check("3".equals(Settings.prefs(isolated).getString(DRAFT,null)),"An earlier ACK cannot clear a newer draft even when its text matches the captured draft");EditText input=(EditText)tagged(screen,"homeCurrentFanSetting");check(input.hasFocus(),"Earlier ACK preserves focus on the newer same-text edit");currentSetting(screen,"3");check(!Settings.prefs(isolated).getBoolean(DRAFT_NEEDS_CONFIRMATION,false),"An earlier ACK cannot quarantine the newer draft");input.setText("");check("".equals(Settings.prefs(isolated).getString(DRAFT,null)),"New invalid empty draft remains stored after the earlier ACK");check(!Settings.prefs(isolated).getBoolean(DRAFT_NEEDS_CONFIRMATION,false),"New invalid edit clears quarantine");}else check(!Settings.prefs(isolated).contains(DRAFT),"Successful correction ACK clears the captured draft even off Home");}else check("3".equals(Settings.prefs(isolated).getString(DRAFT,null)),"Ambiguous Mode ACK retains the draft for explicit correction");refresh(screen);});
                if(slot==1)navigate(test,screen,"Home");
                onMain(test,()->currentSetting(screen,chosen==2?"":expected<0?"3":Integer.toString(expected)));
                if(slot==3){
                    onMain(test,()->{check(((TextView)tagged(screen,"homeFeedback")).getText().toString().contains("Done"),"Ambiguous Mode offers inline manual correction");check(Settings.prefs(isolated).getBoolean(DRAFT_NEEDS_CONFIRMATION,false),"Ambiguous Mode quarantines its retained baseline");});
                    navigate(test,screen,"Setup");navigate(test,screen,"Home");onMain(test,()->{currentSetting(screen,"3");check("3".equals(Settings.prefs(isolated).getString(DRAFT,null)),"Ambiguous draft survives tab return");});
                    pauseResume(test,screen,real);onMain(test,()->{currentSetting(screen,"3");check("3".equals(Settings.prefs(isolated).getString(DRAFT,null)),"Ambiguous draft survives Activity pause/resume");});
                    int[] beforeGuard={0};onMain(test,()->beforeGuard[0]=fake.corrections.size());manualTouch(test,screen,fake,4,"Quarantined Mode baseline");
                    onMain(test,()->{check(fake.pendingDraft==null,"A later Horizontal command cannot reuse the quarantined Mode baseline");fake.completeManualAck(true);refresh(screen);check(fake.corrections.size()==beforeGuard[0],"A later Horizontal ACK cannot SYNC the quarantined baseline");currentSetting(screen,"3");check("3".equals(Settings.prefs(isolated).getString(DRAFT,null))&&Settings.prefs(isolated).getBoolean(DRAFT_NEEDS_CONFIRMATION,false),"A later physical command retains quarantined text until a new edit or Done");});
                }
            }
            tapTag(test,screen,"homeCurrentFanSetting");onMain(test,()->{check(fake.corrections.equals(java.util.Arrays.asList(0,4,2,3,3)),"Focused ACK correction matrix matches Power/Plus/Minus/Horizontal/Vertical");EditText input=(EditText)tagged(screen,"homeCurrentFanSetting");input.setText("7");input.onEditorAction(EditorInfo.IME_ACTION_DONE);check(fake.corrections.size()==6&&fake.corrections.get(5)==7,"Done submits one separate explicit anchor correction");check(!input.hasFocus(),"Done clears editing focus");check(!Settings.prefs(isolated).contains(DRAFT),"Done correction ACK clears its draft");input.onEditorAction(EditorInfo.IME_ACTION_DONE);check(fake.corrections.size()==6,"Unchanged Done does not submit another correction");});
            lastSettingFallback(test,screen,fake,isolated);
            onMain(test,()->{fake.manualPending=true;refresh(screen);for(String tag:REMOTES)check(!tagged(screen,tag).isEnabled(),"Pending command disables "+tag);fake.manualPending=false;fake.staleFan=true;refresh(screen);for(String tag:REMOTES)check(!tagged(screen,tag).isEnabled(),"Stale fan status disables "+tag);fake.staleFan=false;refresh(screen);for(String tag:REMOTES)check(tagged(screen,tag).isEnabled(),"Readiness restores "+tag);check(logicColor(screen)==LOGIC_INACTIVE,"Confirmed manual type-9 status is purple");});
            changedReadinessDuringTouch(test,screen,fake);
            tapTag(test,screen,"homeLogic");onMain(test,()->{check(fake.logicRequests.size()==1&&fake.logicRequests.get(0),"Logic requests automatic mode once");check(logicColor(screen)==LOGIC_INACTIVE,"Accepted request does not optimistically turn Logic blue");fake.logic=confirmedLogic(true);refresh(screen);check(logicColor(screen)==LOGIC_ACTIVE,"Confirmed automatic type-9 status is blue");});
            tapTag(test,screen,"homeLogic");onMain(test,()->{check(fake.logicRequests.size()==2&&!fake.logicRequests.get(1),"Logic requests manual mode once");check(logicColor(screen)==LOGIC_ACTIVE,"Accepted stop does not optimistically turn Logic purple");fake.logic=confirmedLogic(false);refresh(screen);check(logicColor(screen)==LOGIC_INACTIVE,"Confirmed manual type-9 status restores purple");fake.acceptLogic=false;});
            tapTag(test,screen,"homeLogic");onMain(test,()->{check(fake.logicRequests.size()==3&&fake.logicRequests.get(2),"Rejected Logic request is attempted once");check(logicColor(screen)==LOGIC_INACTIVE,"Rejected Logic request keeps confirmed color");fake.acceptLogic=true;fake.logic=confirmedLogic(true);fake.staleLogic=true;refresh(screen);check(logicColor(screen)!=LOGIC_ACTIVE,"Stale type-9 automatic state cannot claim active blue");fake.staleLogic=false;fake.logic=confirmedLogic(false);refresh(screen);});
            tapTag(test,screen,"homeRecording");onMain(test,()->{check(fake.recordingStarts==1&&fake.latest.active(),"Recording first touch starts exactly once in the fake");refresh(screen);});
            tapTag(test,screen,"homeRecording");onMain(test,()->{check(fake.recordingStops==1&&!fake.latest.active(),"Recording first touch stops exactly once in the fake");refresh(screen);});
            tapTag(test,screen,"homeReconnect");onMain(test,()->{check(scope.reconnects==1,"Reconnect first touch reaches one isolated service request");check(fake.manualSlots.size()==37,"Thirty matrix decor touches, one quarantine guard touch and six OS-injected touches each dispatch exactly once");check(fake.corrections.equals(java.util.Arrays.asList(0,4,2,3,3,7,7)),"Only five ACK-adjusted drafts, one edited Done and one unchanged unsynced Done produce SYNC");check(fake.unexpected.isEmpty()&&scope.unexpected.isEmpty(),"No physical command path: "+fake.unexpected+scope.unexpected);check(BedtimeVoiceService.current==null,"Disabled listening never starts a microphone");layout(screen);});
            return "PASS: Home top-half History-matched six live readings and lower-half controls without scrolling/checkboxes; 31 decor remote touches plus six OS-injected Power/Speed touches dispatch exactly once; plain commands track confirmed status without SYNC; command precedes focus clear and draft SYNC waits for ACK with Power/Plus/Minus/oscillation adjustment; off-Home ACK clears only its captured draft, a newer same-text edit keeps focus and an invalid raw draft persists; ambiguous Mode retains quarantined text across tabs/resume and the next physical touch cannot SYNC that old baseline, a new edit clears quarantine; unknown/stale status shows saved setting with guidance and no automatic SYNC, unchanged unsynced Done confirms once without resuming Logic; readiness gating and mid-gesture loss rejects once with inline Not sent and no replay; literal Logic with confirmed type-9 colors and no optimistic change; recording/Reconnect fake only; isolated preferences/storage preserved.\n";
        }finally{
            try{if(activity!=null){Activity closing=activity;onMain(test,closing::finish);test.waitForIdleSync();}}
            finally{try{onMain(test,()->{activeScope=null;if(installed[0]){Handler voice=(Handler)field(RecordingVoiceController.class,"main").get(null);voice.removeCallbacks((Runnable)field(RecordingVoiceController.class,"startDeadline").get(null));if(BreezeService.current==fake)BreezeService.current=null;for(SavedField saved:snapshot)saved.restore();}});}finally{scope.close();check(originalPreferences.equals(Settings.prefs(real).getAll()),"Real preferences remained unchanged");}}
        }
    }
}
