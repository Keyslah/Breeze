package com.aaron.breeze;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class MainActivity extends Activity {
    private static final int BG=0xff101d18,INK=0xffe5eee6,MUTED=0xffa6b9ac,TEAL=0xff9ac5a4,WHITE=0xff20352b;
    private static final int BLE_PERMISSION_REQUEST=7,EXPORT_REQUEST=42,VOICE_PERMISSION_REQUEST=43,FEELING_EXPORT_REQUEST=44;
    private LinearLayout shell,content,nav;
    private Store store;
    private String tab="Home";
    private boolean fahrenheit=true;
    private int visibleSeries=GraphView.DEFAULT_SERIES;
    private long selected=-1;
    private TextView connection,probeValue,secondProbeValue,thirdProbeValue,climate,humidityValue,status,transfer,graphHeading,notice,fanStatus,learningStatus,testStatus;
    private final List<Button> remoteButtons=new ArrayList<>(),targetButtons=new ArrayList<>(),learnButtons=new ArrayList<>(),testButtons=new ArrayList<>(),clearButtons=new ArrayList<>(),fanUtilityButtons=new ArrayList<>();
    private final List<TextView> learnedLabels=new ArrayList<>();
    private Button startProbeTest,onProbes,offProbes,stopProbeTest;
    private Button sessionButton;
    private TextView receiverLatest,receiverExternal,receiverLed,receiverSendStatus;
    private Toast manualFeedback;
    private Button irReceiverTestButton;
    private TextView irReceiverTestStatus;
    private TextView logicStatus;
    private final List<TextView> logicSaveLabels=new ArrayList<>();
    private Runnable logicSaveAction;
    private boolean logicAutoSaveReady,logicSavePending;
    private EditText currentFanSetting;
    private String currentFanDisplayed="";
    private boolean updatingCurrentFanText;
    private LiveReadingDetailsView liveReadings;
    private Button homeLogicButton;
    private final List<Button> homeRemoteButtons=new ArrayList<>();
    private View homeTouchedButton;
    private int homeTouchGeneration;
    private String homeMessage="";
    private final CurrentFanSettingState currentFanSettingState=new CurrentFanSettingState();
    private GraphView graph;
    private TextView bedtimeStatus;
    private TextView lastFeelingStatus;
    private FeelingEntriesView feelingEntries;
    private LinearLayout unassignedFeelings;
    private String unassignedStamp="";
    private Button bedtimeButton;
    private CheckBox disableListening;
    private boolean activityResumed;
    private int pendingVoiceAction;
    private int pendingBluetoothAction; // 1 = Connect / choose, 2 = Reconnect after permission approval.
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean logicExplicitApply;
    private final Runnable deferredLogicSave=()->{if(logicAutoSaveReady&&logicSavePending&&logicSaveAction!=null)logicSaveAction.run();};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();}};
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1500);}};
    private long exportSession=-1;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);
        Settings.logicProfile(this);
        if(!Settings.prefs(this).contains("fanAnchor"))Settings.prefs(this).edit().putInt("fanAnchor",1).apply();
        if(!Settings.prefs(this).contains("place"))Settings.prefs(this).edit().putString("query","53214").putString("place","Milwaukee, Wisconsin • ZIP 53214 (city weather)").putString("latitude","43.0389").putString("longitude","-87.90647").apply();
        fahrenheit=true;visibleSeries=Settings.historyVisibleSeries(Settings.prefs(this));
        if(state!=null){tab=state.getString("tab","Home");if(!Arrays.asList("Home","Fan","Learn Remote","History","Setup").contains(tab))tab="Home";selected=state.getLong("selected",-1);exportSession=state.getLong("exportSession",-1);pendingVoiceAction=state.getInt("pendingVoiceAction",0);pendingBluetoothAction=state.getInt("pendingBluetoothAction",0);}
        build();
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("tab",tab);b.putLong("selected",selected);b.putLong("exportSession",exportSession);b.putInt("pendingVoiceAction",pendingVoiceAction);b.putInt("pendingBluetoothAction",pendingBluetoothAction);}
    @Override protected void onResume(){super.onResume();activityResumed=true;FanCommandTrace.record(this,"resume tab="+tab);IntentFilter filter=new IntentFilter(BreezeService.UPDATE);filter.addAction(BedtimeVoiceService.UPDATE);filter.addAction(Store.FEELINGS_CHANGED);registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);reconcileBluetooth();handler.post(tick);}
    @Override protected void onPause(){flushLogicEdits();FanCommandTrace.record(this,"pause tab="+tab);activityResumed=false;BreezeService.setUiVisible(this,false);handler.removeCallbacks(tick);unregisterReceiver(updates);super.onPause();}
    private boolean canUseBluetooth(){return activityResumed&&getSystemService(PowerManager.class).isInteractive()&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private boolean hasBluetoothPermission(){return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void reconcileBluetooth(){
        if(!canUseBluetooth())return;
        // Android may resume us before the keyguard finishes unlocking. Reconcile again on each tick.
        BreezeService.setUiVisible(this,true);
        if(!hasBluetoothPermission())return;
        if(pendingBluetoothAction!=0){connect(pendingBluetoothAction==2);return;}
        BreezeService controller=BreezeService.current;
        if((controller==null||!controller.visibleForBluetooth())&&!Settings.prefs(this).getString("device","").isEmpty())BreezeService.startForVisibleUi(this,false);
    }
    @Override protected void onDestroy(){handler.removeCallbacks(deferredLogicSave);logicSaveAction=null;logicAutoSaveReady=false;if(manualFeedback!=null)manualFeedback.cancel();worker.shutdown();store.close();super.onDestroy();}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(3));return t;}
    private TextView bold(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private void gap(LinearLayout p,int h){View v=new View(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private LinearLayout card(){LinearLayout l=col();l.setPadding(dp(18),dp(15),dp(18),dp(15));l.setBackground(background(WHITE,22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);content.addView(l,p);return l;}
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(primary?WHITE:TEAL);b.setBackground(background(primary?TEAL:0xff2c4436,14));b.setMinHeight(dp(48));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setOnClickListener(v->action.run());return b;}
    private void addButton(LinearLayout p,String title,boolean primary,Runnable action){Button b=button(title,primary,action);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.topMargin=dp(8);p.addView(b,lp);}
    private void two(LinearLayout p,String a,Runnable aa,String b,Runnable bb){LinearLayout r=row();Button x=button(a,false,aa),y=button(b,false,bb);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),dp(8),dp(3),0);r.addView(x,lp);r.addView(y,new LinearLayout.LayoutParams(lp));p.addView(r);}
    private void build(){
        flushLogicEdits();logicAutoSaveReady=false;logicSaveAction=null;logicSavePending=false;logicSaveLabels.clear();
        logicStatus=null;
        liveReadings=null;homeLogicButton=null;homeRemoteButtons.clear();homeTouchedButton=null;homeTouchGeneration++;homeMessage="";
        currentFanSetting=null;currentFanDisplayed="";
        receiverLatest=null;receiverExternal=null;receiverLed=null;receiverSendStatus=null;irReceiverTestButton=null;irReceiverTestStatus=null;fanUtilityButtons.clear();
        bedtimeStatus=null;bedtimeButton=null;disableListening=null;lastFeelingStatus=null;feelingEntries=null;unassignedFeelings=null;unassignedStamp="";
        connection=null;probeValue=null;secondProbeValue=null;thirdProbeValue=null;climate=null;humidityValue=null;graph=null;graphHeading=null;status=null;transfer=null;notice=null;sessionButton=null;fanStatus=null;learningStatus=null;testStatus=null;startProbeTest=null;onProbes=null;offProbes=null;stopProbeTest=null;remoteButtons.clear();targetButtons.clear();learnButtons.clear();testButtons.clear();clearButtons.clear();learnedLabels.clear();
        boolean homeTab="Home".equals(tab);int side=dp(homeTab?12:20);shell=col();shell.setBackground(new GraniteDrawable());shell.setPadding(side,0,side,0);shell.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(side,bars.top,side,bars.bottom);return insets;});setContentView(shell);
        getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout header=row();TextView brand=bold("breeze",homeTab?26:34,TEAL);header.addView(brand,new LinearLayout.LayoutParams(0,dp(homeTab?42:64),1));shell.addView(header);
        content=col();if(homeTab){content.setTag("homeRoot");shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));}else{ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}
        switch(tab){case "Fan":fanPage();break;case "Learn Remote":learnRemote();break;case "History":history();break;case "Setup":setup();break;default:home();}
        if(!homeTab)gap(content,14);nav=row();for(String title:new String[]{"Home","Fan","History","Setup"}){TextView t=bold(title,14,title.equals(tab)||(title.equals("Fan")&&tab.equals("Learn Remote"))?TEAL:MUTED);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(homeTab?8:18),0,dp(homeTab?8:18));t.setOnClickListener(v->{FanCommandTrace.record(this,"navigate from="+tab+" to="+title);tab=title;build();});nav.addView(t,new LinearLayout.LayoutParams(0,dp(homeTab?48:58),1));}shell.addView(nav);refresh();
    }
    private TextView readingCard(String label){
        LinearLayout c=card();c.setPadding(dp(22),dp(20),dp(22),dp(20));
        c.addView(text(label,16,MUTED));TextView value=bold("—",46,INK);value.setFontFeatureSettings("tnum");c.addView(value);return value;
    }
    private void home(){
        liveReadings=new LiveReadingDetailsView(this);liveReadings.setTag("homeReadings");liveReadings.setBackground(background(WHITE,18));content.addView(liveReadings,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=col();controls.setTag("homeControls");controls.setPadding(0,dp(6),0,0);content.addView(controls,new LinearLayout.LayoutParams(-1,0,1));
        String[] tags={"homePower","homeSpeedPlus","homeSpeedMinus","homeMode","homeHorizontal","homeVertical"};String[] names={"Power","Speed +","Speed -","Mode","Horizontal","Vertical"};
        for(int line=0;line<2;line++){LinearLayout buttons=row();for(int column=0;column<3;column++){int slot=line*3+column;Button button=homeButton(names[slot],tags[slot],()->homeManual(slot));homeRemoteButtons.add(button);homeSlot(buttons,button,column<2);}homeRow(controls,buttons,1);}
        LinearLayout actions=row();homeLogicButton=homeButton("Logic","homeLogic",this::homeLogic);homeSlot(actions,homeLogicButton,true);sessionButton=homeButton("Start recording","homeRecording",this::sessionAction);homeSlot(actions,sessionButton,false);homeRow(controls,actions,1);
        LinearLayout last=row();homeSlot(last,homeButton("Reconnect","homeReconnect",()->connect(true)),true);LinearLayout current=col();current.setGravity(Gravity.CENTER_VERTICAL);homeSlot(last,current,false);currentFanControl(current);homeRow(controls,last,1.4f);
        connection=text("Controller connection",12,TEAL);connection.setTag("homeConnection");connection.setMaxLines(2);connection.setPadding(0,dp(3),0,0);controls.addView(connection,new LinearLayout.LayoutParams(-1,-2));notice=text("",12,0xffedb597);notice.setTag("homeFeedback");notice.setMaxLines(2);notice.setPadding(0,0,0,0);controls.addView(notice,new LinearLayout.LayoutParams(-1,-2));
    }
    private void homeRow(LinearLayout parent,LinearLayout row,float weight){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,0,weight);params.bottomMargin=dp(4);parent.addView(row,params);}
    private void homeSlot(LinearLayout row,View view,boolean margin){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);if(margin)params.setMarginEnd(dp(6));row.addView(view,params);}
    private Button homeButton(String title,String tag,Runnable action){
        Button button=button(title,false,action);button.setTag(tag);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(4),dp(2),dp(4),dp(2));button.setMaxLines(2);button.setElevation(0);button.setTranslationZ(0);button.setStateListAnimator(null);button.setFocusable(false);button.setFocusableInTouchMode(false);
        button.setOnClickListener(view->{homeMessage="";try{action.run();clearHomeEditingFocus();}finally{if(homeTouchedButton==view)homeTouchedButton=null;refresh();}});
        // Android may post performClick after UP. Hold readiness until that click has run.
        button.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN){homeTouchedButton=view;homeTouchGeneration++;}else if(event.getActionMasked()==MotionEvent.ACTION_UP){int generation=homeTouchGeneration;handler.postDelayed(()->{if(homeTouchGeneration==generation&&homeTouchedButton==view){homeTouchedButton=null;refresh();}},250);}else if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(homeTouchedButton==view){homeTouchedButton=null;homeTouchGeneration++;}refresh();}return false;});return button;
    }
    private void clearHomeEditingFocus(){if(currentFanSetting!=null&&currentFanSetting.hasFocus()){currentFanSetting.clearFocus();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(currentFanSetting.getWindowToken(),0);}}
    private void homeManual(int slot){
        homeMessage="";BreezeService controller=BreezeService.current;if(!service())return;
        String drafted=Settings.prefs(this).getString("homeFanSettingDraft",null);long draftRevision=Settings.prefs(this).getLong("homeFanDraftRevision",0);Integer correction=null;if(drafted!=null&&!Settings.prefs(this).getBoolean("homeFanDraftNeedsConfirmation",false))try{correction=FanSetting.parse(drafted);}catch(IllegalArgumentException ignored){}
        FanCommandTrace.record(this,"click tab=Home action="+Protocol.SEND_IR+" value="+slot+" ready="+controller.manualIrReady());
        if(!controller.homeFanCommand(slot,correction,(sent,corrected)->{
            boolean sameDraft=draftRevision==Settings.prefs(this).getLong("homeFanDraftRevision",0)&&drafted!=null&&drafted.equals(Settings.prefs(this).getString("homeFanSettingDraft",null));
            if(corrected!=null&&sameDraft){Settings.prefs(this).edit().remove("homeFanSettingDraft").remove("homeFanDraftNeedsConfirmation").apply();currentFanSettingState.correction(corrected,controller.fan);if(currentFanSetting!=null){currentFanDisplayed=FanSetting.label(corrected);setCurrentFanText(currentFanDisplayed);}}
            if(!sent||corrected==null&&sameDraft)homeMessage=controller.lastError;if(!isFinishing()&&!isDestroyed())refresh();
        }))homeMessage=controller.lastError;refresh();
    }
    private void homeLogic(){homeMessage="";BreezeService controller=BreezeService.current;if(!service())return;if(!controller.homeLogicReady()){homeMessage="Wait for the controller to finish preparing.";refresh();return;}if(!controller.setHomeLogicEnabled(!controller.logic.automatic()))homeMessage=controller.lastError;refresh();}
    private void currentFanControl(LinearLayout parent){
        TextView label=text("Current fan setting",12,GraphView.COLORS[GraphView.FAN_SETTING_SERIES]);label.setPadding(0,0,0,0);parent.addView(label);
        currentFanSetting=new EditText(this);currentFanSetting.setTag("homeCurrentFanSetting");currentFanSetting.setSingleLine();currentFanSetting.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);currentFanSetting.setTextColor(GraphView.COLORS[GraphView.FAN_SETTING_SERIES]);currentFanSetting.setHintTextColor(MUTED);currentFanSetting.setTextSize(22);currentFanSetting.setMinHeight(0);currentFanSetting.setMinimumHeight(0);currentFanSetting.setPadding(dp(3),0,dp(3),0);currentFanSetting.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TEAL));currentFanSetting.setContentDescription("Current fan setting");parent.addView(currentFanSetting,new LinearLayout.LayoutParams(-1,0,1));EditText input=currentFanSetting;input.setHint("Unknown");input.setSelectAllOnFocus(true);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);TextView off=text("0 = Off · H = Highest · Done",10,MUTED);off.setPadding(0,0,0,0);parent.addView(off);
        input.setOnEditorActionListener((field,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){if(saveCurrentFanSetting(input)){getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);parent.requestFocus();}return true;}return false;});
        parent.setFocusableInTouchMode(true);parent.requestFocus();
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence value,int start,int count,int after){}public void onTextChanged(CharSequence value,int start,int before,int count){}public void afterTextChanged(android.text.Editable value){if(!updatingCurrentFanText){String draft=value.toString().trim();SharedPreferences prefs=Settings.prefs(MainActivity.this);BreezeService controller=BreezeService.current;boolean known=controller!=null&&controller.freshFan()&&controller.fan.synced();SharedPreferences.Editor edit=prefs.edit().putLong("homeFanDraftRevision",prefs.getLong("homeFanDraftRevision",0)+1).remove("homeFanDraftNeedsConfirmation");if(CurrentFanSettingState.edited(draft,currentFanDisplayed)||prefs.getBoolean("homeFanDraftNeedsConfirmation",false)||!known||!controller.manualIrReady())edit.putString("homeFanSettingDraft",draft);else edit.remove("homeFanSettingDraft");edit.apply();}}});
    }
    private boolean saveCurrentFanSetting(EditText input){
        if(input!=currentFanSetting)return false;
        BreezeService connected=BreezeService.current;boolean known=connected!=null&&connected.freshFan()&&connected.fan.synced();
        if(!CurrentFanSettingState.edited(input.getText().toString(),currentFanDisplayed)&&(Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false)||known&&!Settings.prefs(this).contains("homeFanSettingDraft")))return true;
        int value;try{value=FanSetting.parse(input.getText().toString());}catch(IllegalArgumentException failure){homeMessage="Enter 0 for Off, 1–9, or H for Highest.";refresh();return false;}
        String drafted=Settings.prefs(this).getString("homeFanSettingDraft",null);long draftRevision=Settings.prefs(this).getLong("homeFanDraftRevision",0);Settings.prefs(this).edit().putInt("fanAnchor",value).putBoolean("fanAnchorCorrectionPending",true).apply();String display=FanSetting.label(value);currentFanDisplayed=display;setCurrentFanText(display);BreezeService controller=BreezeService.current;currentFanSettingState.correction(value,controller==null?null:controller.fan);if(controller!=null)controller.setHomeFanAnchor(value,ok->{if(ok&&draftRevision==Settings.prefs(this).getLong("homeFanDraftRevision",0)&&Objects.equals(drafted,Settings.prefs(this).getString("homeFanSettingDraft",null)))Settings.prefs(this).edit().remove("homeFanSettingDraft").remove("homeFanDraftNeedsConfirmation").apply();if(!isFinishing()&&!isDestroyed())refresh();});homeMessage="";refresh();return true;
    }
    private void refreshCurrentFanSetting(BreezeService controller){
        if(currentFanSetting==null)return;
        String display=currentFanSettingState.display(Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false)||(controller!=null&&controller.anchorCorrectionPending),Math.max(0,Math.min(FanSetting.MAX,Settings.prefs(this).getInt("fanAnchor",1))),controller==null?null:controller.fan,controller!=null&&controller.freshFan()&&!controller.anchorCorrectionPending);
        currentFanDisplayed=display;String draft=Settings.prefs(this).getString("homeFanSettingDraft",null);if(draft!=null&&(currentFanSetting.hasFocus()||!Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false))){setCurrentFanText(draft);return;}
        setCurrentFanText(display);
    }
    private void setCurrentFanText(String display){if(currentFanSetting==null||display.contentEquals(currentFanSetting.getText()))return;updatingCurrentFanText=true;try{currentFanSetting.setText(display);}finally{updatingCurrentFanText=false;}}
    static final class CurrentFanSettingState {
        private Integer pendingValue;
        private Protocol.FanState pendingStatus;
        void correction(int value,Protocol.FanState status){pendingValue=value;pendingStatus=status;}
        String display(boolean correctionPending,int saved,Protocol.FanState status,boolean fresh){
            if(correctionPending)correction(saved,status);
            // A command ACK can arrive before the fan-status packet with the corrected speed.
            else if(pendingValue!=null&&fresh&&status!=null&&status!=pendingStatus){pendingValue=null;pendingStatus=null;}
            if(pendingValue!=null)return FanSetting.label(pendingValue);
            return FanSetting.label(fresh&&status!=null&&status.synced()?status.speed:saved);
        }
        static boolean edited(String value,Object displayed){
            String previous=displayed==null?"":displayed.toString();
            try{return FanSetting.parse(value)!=FanSetting.parse(previous);}catch(IllegalArgumentException ignored){return !value.trim().equals(previous);}
        }
    }
    private void bedtimeVoiceCard(){
        LinearLayout voice=card();voice.addView(bold("Voice during recording",21,INK));
        voice.addView(text("Say \"Breeze, warm seven\" or \"Breeze, cold three\" (1–10). Feelings save silently during recording, even with the phone locked.",14,INK));
        disableListening=new CheckBox(this);disableListening.setText("Disable listening");disableListening.setTextColor(INK);disableListening.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));disableListening.setChecked(RecordingVoiceController.isListeningDisabled(this));disableListening.setOnCheckedChangeListener((button,checked)->{RecordingVoiceController.setListeningDisabled(this,checked);if(checked)pendingVoiceAction=0;refresh();});voice.addView(disableListening);
        voice.addView(text("While checked, the microphone stays off until you uncheck it. Sensor recording continues normally.",12,MUTED));
        bedtimeStatus=text("Starts with recording",13,TEAL);voice.addView(bedtimeStatus);
        bedtimeButton=button("Pause listening",false,this::toggleBedtimeVoice);voice.addView(bedtimeButton,new LinearLayout.LayoutParams(-1,dp(48)));
        voice.addView(text("\"Breeze, stop listening\" pauses voice logging for this recording.",12,MUTED));
        lastFeelingStatus=text("",13,TEAL);voice.addView(lastFeelingStatus);
        addButton(voice,"View saved feelings",false,()->{List<Store.FeelingEntry> saved=store.allFeelings();if(!saved.isEmpty()&&saved.get(saved.size()-1).session>0)selected=saved.get(saved.size()-1).session;tab="History";build();});
    }
    private void toggleBedtimeVoice(){
        if(RecordingVoiceController.isListeningDisabled(this))return;
        if(BedtimeVoiceService.current!=null){RecordingVoiceController.suppress(this);refresh();return;}
        if(!voicePermission(3))return;
        resumeRecordingVoice();
    }
    private void resumeRecordingVoice(){
        if(RecordingVoiceController.isListeningDisabled(this))return;
        try{RecordingVoiceController.resume(this);refresh();}catch(RuntimeException failure){error("Could not resume listening. Keep Breeze open and try again. "+failure.getMessage());}
    }
    private boolean voicePermission(int action){
        if(RecordingVoiceController.isListeningDisabled(this))return false;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)return true;
        pendingVoiceAction=action;List<String> permissions=new ArrayList<>();permissions.add(Manifest.permission.RECORD_AUDIO);
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]),VOICE_PERMISSION_REQUEST);return false;
    }
    private void startRecordingWithVoice(boolean probeTest){
        if(!service())return;BreezeService controller=BreezeService.current;
        if(RecordingVoiceController.isStarting()){toast("Waiting for recording to start.");return;}
        if(!controller.fresh()){toast("Wait for a fresh controller reading.");return;}
        if(controller.latest.active()){toast("A recording is already running.");return;}
        if(!controller.sensorFirmware||(probeTest&&!controller.freshFan())){toast("Connect the updated controller first.");return;}
        if(!controller.latest.storageReady()){toast("Wait for controller storage to be ready.");controller.download();return;}
        if(!RecordingVoiceController.isListeningDisabled(this)&&!voicePermission(probeTest?2:1))return;
        try{RecordingVoiceController.prepareStart(this);if(probeTest)controller.startContactTest();else controller.startSession();}
        catch(RuntimeException failure){error("Could not prepare voice listening. Keep Breeze open and try again. "+failure.getMessage());}
    }
    private void sendFan(int action,int value,int argument){if(service()){
        BreezeService controller=BreezeService.current;
        FanCommandTrace.record(this,"click tab="+tab+" action="+action+" value="+value+" ready="+controller.manualIrReady());
        boolean accepted=controller.fanCommand(action,value,argument);
        if(action==Protocol.SEND_IR){if(accepted){getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);manualFeedback("Sending "+Protocol.COMMANDS[value]+"…");}else manualFeedback(controller.lastError.startsWith("Not sent")?controller.lastError:"Not sent—"+controller.lastError);}
        refresh();
    }}
    private void fanConnection(LinearLayout c){connection=text("Controller connection",14,TEAL);c.addView(connection);notice=text("",13,0xffedb597);c.addView(notice);}
    private void receiverCard(){
        LinearLayout receiver=card();receiver.addView(bold("RECEIVER",16,TEAL));
        receiverLatest=bold("No infrared input received yet.",16,INK);receiver.addView(receiverLatest);
        receiverExternal=text("External input · —",12,MUTED);receiver.addView(receiverExternal);
        receiverLed=text("During LED send · —",12,MUTED);receiver.addView(receiverLed);
        receiverSendStatus=text("",12,TEAL);receiver.addView(receiverSendStatus);
        receiver.addView(text("Press Power on the remote, then aim the LED at the receiver and tap Power below to compare.",12,MUTED));
    }
    private void irReceiverTestControl(LinearLayout c){
        irReceiverTestButton=button("Test LED → receiver",true,()->{if(service()){
            BreezeService controller=BreezeService.current;
            if(controller.startIrReceiverTest()){irReceiverTestButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);toast("LED test scheduled. Aim the LED at the receiver; starts in 5 seconds.");}
            else toast(controller.lastError);
            refresh();
        }});
        c.addView(irReceiverTestButton,new LinearLayout.LayoutParams(-1,dp(54)));
        irReceiverTestStatus=bold("",18,TEAL);c.addView(irReceiverTestStatus);
        c.addView(text("Reconnect the receiver to D6, 3V3 and GND. Aim the transmitter LED at it from 10–30 cm away. The controller checks three infrared test bursts and reports how many it detects.",13,MUTED));gap(c,10);
    }
    private void addFanUtilityButton(LinearLayout c,String title,Runnable action){Button control=button(title,false,action);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(48));params.topMargin=dp(8);c.addView(control,params);fanUtilityButtons.add(control);}
    private void fanPage(){
        LinearLayout c=card();c.addView(bold("Dreo fan",22,INK));fanConnection(c);fanStatus=text("",14,INK);c.addView(fanStatus);c.addView(text("IR has no fan feedback. Speed is an estimate. Sync again after using the fan's buttons, its original remote, or after a power loss.",13,MUTED));
        irReceiverTestControl(c);
        addFanUtilityButton(c,"Sync / set current speed",this::syncFan);addFanUtilityButton(c,"Cancel / forget speed estimate",()->sendFan(Protocol.FORGET_FAN,0,0));
        LinearLayout manual=card();manual.addView(bold("Manual controls",20,INK));
        for(int slot=0;slot<6;slot++){final int chosen=slot;Button b=button(Protocol.COMMANDS[slot],slot==0,()->sendFan(Protocol.SEND_IR,chosen,0));b.setOnTouchListener((view,event)->{if(event.getActionMasked()!=MotionEvent.ACTION_MOVE)FanCommandTrace.record(this,"touch slot="+chosen+" action="+MotionEvent.actionToString(event.getActionMasked())+" enabled="+view.isEnabled()+" focused="+view.hasFocus());return false;});manual.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));gap(manual,6);remoteButtons.add(b);}
        manual.addView(text("Target speed · requires a synchronized estimate",13,MUTED));
        for(int rowIndex=0;rowIndex<3;rowIndex++){LinearLayout r=row();for(int col=1;col<=3;col++){int speed=rowIndex*3+col;Button b=button(""+speed,false,()->sendFan(Protocol.TARGET_SPEED,speed,0));r.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));targetButtons.add(b);}manual.addView(r);gap(manual,5);}
        Button highest=button("H",false,()->sendFan(Protocol.TARGET_SPEED,FanSetting.MAX,0));manual.addView(highest,new LinearLayout.LayoutParams(-1,dp(48)));targetButtons.add(highest);
        addButton(manual,"Learn Remote",true,()->{tab="Learn Remote";build();});
        LinearLayout test=card();test.addView(bold("Probe contact test",20,INK));testStatus=text("",14,TEAL);test.addView(testStatus);test.addView(text("Record a baseline, mark when you lie on and leave the probes, then stop and review History.",14,MUTED));
        startProbeTest=button("Start probe test",true,()->startRecordingWithVoice(true));test.addView(startProbeTest,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,7);
        onProbes=button("Mark: lying on probes",false,()->sendFan(Protocol.MARK_TEST,2,0));test.addView(onProbes,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,7);
        offProbes=button("Mark: off probes",false,()->sendFan(Protocol.MARK_TEST,3,0));test.addView(offProbes,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,7);
        stopProbeTest=button("Stop probe test",false,()->{if(service())BreezeService.current.control(Protocol.STOP_RECORDING,0);});test.addView(stopProbeTest,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void syncFan(){
        LinearLayout form=col();form.setPadding(dp(20),dp(8),dp(20),dp(8));form.addView(text("Read the actual fan setting. Choose 0 only when the fan is off; use Normal/manual mode.",15,INK));
        Spinner speed=new Spinner(this);speed.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Off (0)","1","2","3","4","5","6","7","8","9","H"}));speed.setSelection(Math.max(0,Math.min(FanSetting.MAX,Settings.prefs(this).getInt("fanAnchor",1))));form.addView(speed);
        CheckBox confirm=new CheckBox(this);confirm.setText("I checked the fan setting and Normal/manual mode");form.addView(confirm);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Set current speed estimate").setView(form).setPositiveButton("Sync",null).setNegativeButton("Cancel",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(!confirm.isChecked()){toast("Confirm the fan setting and Normal/manual mode.");return;}sendFan(Protocol.SYNC_SPEED,speed.getSelectedItemPosition(),1);dialog.dismiss();}));dialog.show();
    }
    private void learnRemote(){
        LinearLayout c=card();c.addView(bold("Learn Remote",22,INK));fanConnection(c);learningStatus=text("",14,INK);c.addView(learningStatus);c.addView(text("Point the Dreo remote at the receiver and press the requested button once. Learning may also operate the real fan. The old command stays saved until the new capture is stored successfully.",13,MUTED));
        irReceiverTestControl(c);
        c.addView(text("Raw replay carrier (kHz)",13,MUTED));EditText carrier=new EditText(this);carrier.setInputType(InputType.TYPE_CLASS_NUMBER);carrier.setText("38");c.addView(carrier);c.addView(text("38 kHz is the receiver's nominal frequency. It cannot measure the remote's carrier; verify replay on the fan.",12,MUTED));addFanUtilityButton(c,"Cancel learning",()->sendFan(Protocol.CANCEL_LEARN,0,0));
        for(int slot=0;slot<Protocol.COMMANDS.length;slot++){final int chosen=slot;LinearLayout item=card();item.addView(bold(Protocol.COMMANDS[slot],18,INK));TextView label=text("Not learned",13,MUTED);item.addView(label);learnedLabels.add(label);LinearLayout r=row();
            Button learn=button("Learn",false,()->{try{sendFan(Protocol.LEARN,chosen,Integer.parseInt(carrier.getText().toString().trim()));}catch(NumberFormatException e){error("Enter a carrier between 30 and 60 kHz.");}});
            Button test=button("Test",false,()->sendFan(Protocol.SEND_IR,chosen,0));Button clear=button("Clear",false,()->new AlertDialog.Builder(this).setTitle("Clear "+Protocol.COMMANDS[chosen]+"?").setMessage("This removes the saved command from the controller.").setPositiveButton("Clear",(d,w)->sendFan(Protocol.CLEAR_LEARNED,chosen,0)).setNegativeButton("Cancel",null).show());
            for(Button b:new Button[]{learn,test,clear})r.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));item.addView(r);learnButtons.add(learn);testButtons.add(test);clearButtons.add(clear);
        }
    }
    private void sessionAction(){if(!service())return;BreezeService b=BreezeService.current;if(!b.fresh()){toast("Wait for a fresh controller reading.");return;}if(b.latest.active())b.control(Protocol.STOP_RECORDING,0);else startRecordingWithVoice(false);}
    private void history(){
        unassignedFeelings=card();unassignedFeelings.setVisibility(View.GONE);
        List<Store.Session> sessions=store.sessions();if(sessions.isEmpty()){LinearLayout empty=card();empty.addView(bold("Recorded nights",23,INK));empty.addView(text("No sessions yet. Connect your controller and download its records.",15,INK));addButton(empty,"Connect controller",true,this::connect);return;}
        Store.Session chosen=sessions.get(0);for(Store.Session s:sessions)if(s.id==selected)chosen=s;selected=chosen.id;final Store.Session session=chosen;
        LinearLayout chart=card();LinearLayout chartTitle=row();chartTitle.setMinimumHeight(dp(40));graphHeading=bold("Recorded night",18,INK);chartTitle.addView(graphHeading,new LinearLayout.LayoutParams(-2,-2));
        SimpleDateFormat headerTime=new SimpleDateFormat("MMM d · h:mm:ss a",Locale.US);TextView graphTime=text(headerTime.format(new Date(session.start)),15,INK);graphTime.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);graphTime.setMaxLines(2);LinearLayout.LayoutParams timeLayout=new LinearLayout.LayoutParams(0,-2,1);timeLayout.setMarginStart(dp(8));chartTitle.addView(graphTime,timeLayout);chart.addView(chartTitle);
        graph=new GraphView(this);chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(346)));
        GraphReadingDetailsView readingDetails=new GraphReadingDetailsView(this);readingDetails.setVisibleSeries(visibleSeries);readingDetails.setOnSeriesChangedListener((series,checked)->{if(checked)visibleSeries|=1<<series;else visibleSeries&=~(1<<series);Settings.prefs(this).edit().putInt("historyVisibleSeries",visibleSeries).apply();graph.visibleSeries(visibleSeries);});chart.addView(readingDetails,new LinearLayout.LayoutParams(-1,-2));graph.setReadingDetailsListener(details->{readingDetails.showReading(details);graphTime.setText(headerTime.format(new Date(details==null?session.start:details.time)));graphTime.setContentDescription(details==null?date(session.start):details.timestamp);});
        content.removeView(chart);content.addView(chart,0);
        two(chart,"Reset zoom",()->graph.resetZoom(),"Choose recorded night",this::chooseRecordedNight);
        addButton(chart,"Graph help",false,()->new AlertDialog.Builder(this).setTitle("Graph controls").setMessage("Tap for readings, pinch to zoom, drag sideways to pan. Double-tap or Reset zoom shows the whole night.\n\nHighest body temp shows the highest available D0, D1 or D2 body temperature at each recorded timestamp. It is on by default; the three individual body temperature traces start unchecked. Room temperature is separate.\n\nCheckboxes show or hide plotted traces; the readings remain available. Temperature uses the right axis, humidity the left. Dashed traces are hourly outdoor weather. Feeling ratings use +1 to +10 for warm and −1 to −10 for cold.").setPositiveButton("OK",null).show());

        LinearLayout feelings=card();feelingEntries=new FeelingEntriesView(this,store,session,this::refresh);feelings.addView(feelingEntries);
        addButton(feelings,"Export feeling entries",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-feelings-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,FEELING_EXPORT_REQUEST);});

        LinearLayout outdoor=card();outdoor.addView(bold("Outdoor weather",19,INK));String location=store.weatherLocation(selected);outdoor.addView(text(location.isEmpty()?Settings.prefs(this).getString("place","Choose location in Setup"):location,13,TEAL));addButton(outdoor,"Fetch outdoor weather",true,()->fetchWeather(session));addButton(outdoor,"Export session as CSV",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,EXPORT_REQUEST);});
        outdoor.addView(text("Open-Meteo hourly weather model · CC BY 4.0",11,MUTED));
    }
    private String feelingLabel(Store.FeelingEntry entry){return ("COLD".equals(entry.state)?"Cold":"Warm")+" "+entry.intensity+" / 10";}
    private String feelingTime(long time){return new SimpleDateFormat("MMM d, yyyy · h:mm:ss a",Locale.US).format(new Date(time));}
    private void refreshSavedFeelings(){
        if(lastFeelingStatus!=null){List<Store.FeelingEntry> entries=store.allFeelings();if(entries.isEmpty())lastFeelingStatus.setText("No feeling entries saved yet.");else{Store.FeelingEntry latest=entries.get(entries.size()-1);lastFeelingStatus.setText("Last saved: "+feelingLabel(latest)+"\n"+feelingTime(latest.time)+(latest.session<=0?"\nSaved on phone • waiting to link to a recorded night":""));}}
        if(feelingEntries!=null)feelingEntries.refreshEntries();
        if(unassignedFeelings==null)return;
        List<Store.FeelingEntry> waiting=store.unassignedFeelings();StringBuilder stamp=new StringBuilder();for(Store.FeelingEntry entry:waiting)stamp.append(entry.id).append(':').append(entry.time).append(':').append(entry.state).append(':').append(entry.intensity).append(';');
        String next=stamp.toString();if(next.equals(unassignedStamp))return;unassignedStamp=next;unassignedFeelings.removeAllViews();unassignedFeelings.setVisibility(waiting.isEmpty()?View.GONE:View.VISIBLE);
        if(waiting.isEmpty())return;
        unassignedFeelings.addView(bold("Saved feelings awaiting a night",19,INK));unassignedFeelings.addView(text("These entries are saved on this phone. Breeze links them when the matching controller records arrive. You can also choose their recorded night.",13,MUTED));
        for(Store.FeelingEntry entry:waiting)addButton(unassignedFeelings,feelingTime(entry.time)+" · "+feelingLabel(entry)+"\nChoose recorded night",false,()->assignFeelingNight(entry));
    }
    private void assignFeelingNight(Store.FeelingEntry entry){
        List<Store.Session> nights=store.sessions();if(nights.isEmpty()){toast("Download a recorded night from the controller first.");return;}
        String[] labels=nights.stream().map(night->date(night.start)+(night.end>0?" to "+date(night.end):" · no end marker")).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Choose night for "+feelingLabel(entry)).setItems(labels,(dialog,which)->{try{store.assignFeeling(entry.id,nights.get(which).id);selected=nights.get(which).id;build();}catch(RuntimeException failure){error(failure.getMessage());}}).setNegativeButton("Cancel",null).show();
    }
    private void chooseRecordedNight(){
        List<Store.Session> nights=store.sessions();if(nights.isEmpty()){build();return;}
        String[] labels=new String[nights.size()];int current=0;
        for(int i=0;i<nights.size();i++){Store.Session night=nights.get(i);labels[i]=date(night.start)+"\n"+night.count+" readings"+(night.end==0?" · open / interrupted":" · ended "+new SimpleDateFormat("h:mm a",Locale.US).format(new Date(night.end)));if(night.id==selected)current=i;}
        new AlertDialog.Builder(this).setTitle("Choose recorded night ("+nights.size()+")").setSingleChoiceItems(labels,current,(dialog,which)->{selected=nights.get(which).id;dialog.dismiss();build();}).setNegativeButton("Cancel",null).show();
    }
    private EditText logicInput(LinearLayout parent,String label,String value,int inputType){
        parent.addView(text(label,13,MUTED));EditText input=new EditText(this);input.setSingleLine();input.setTextColor(INK);input.setHintTextColor(MUTED);input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TEAL));input.setTextSize(16);input.setInputType(inputType);input.setText(value);input.setContentDescription(label);parent.addView(input,new LinearLayout.LayoutParams(-1,dp(48)));return input;
    }
    private EditText logicDecimal(LinearLayout parent,String label,double value){return logicInput(parent,label,Double.toString(value),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);}
    private EditText logicInteger(LinearLayout parent,String label,int value){return logicInput(parent,label,Integer.toString(value),InputType.TYPE_CLASS_NUMBER);}
    private EditText logicFanSetting(LinearLayout parent,String label,int value){return logicInput(parent,label,FanSetting.label(value),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);}
    private double logicDecimalValue(EditText input){try{double value=Double.parseDouble(input.getText().toString().trim());if(!Double.isFinite(value))throw new NumberFormatException();return value;}catch(NumberFormatException failure){throw new IllegalArgumentException("Enter a number for "+input.getContentDescription()+".");}}
    private int logicIntegerValue(EditText input){try{return Integer.parseInt(input.getText().toString().trim());}catch(NumberFormatException failure){throw new IllegalArgumentException("Enter a whole number for "+input.getContentDescription()+".");}}
    private CheckBox logicCheck(LinearLayout parent,String label,boolean checked){CheckBox check=new CheckBox(this);check.setText(label);check.setTextColor(INK);check.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));check.setChecked(checked);check.setOnCheckedChangeListener((control,value)->logicEdited(true));parent.addView(check);return check;}
    private void logicSaveFeedback(String message){for(TextView label:logicSaveLabels)label.setText(message);}
    private void logicEdited(boolean immediate){if(!logicAutoSaveReady||logicSaveAction==null)return;logicSavePending=true;logicSaveFeedback("Saving…");handler.removeCallbacks(deferredLogicSave);if(immediate)logicSaveAction.run();else handler.postDelayed(deferredLogicSave,600);}
    private void flushLogicEdits(){handler.removeCallbacks(deferredLogicSave);if(logicAutoSaveReady&&logicSavePending&&logicSaveAction!=null)logicSaveAction.run();}
    private void saveLogicNow(){if(!logicAutoSaveReady||logicSaveAction==null)return;logicExplicitApply=true;logicSavePending=true;logicSaveFeedback("Saving…");flushLogicEdits();}
    private void logicProfileActions(LinearLayout parent){
        LinearLayout actions=row();Button save=button("Save profile",true,this::saveLogicNow),load=button("Load profile",false,this::showLogicProfiles);save.setTextSize(16);load.setTextSize(16);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(54),1);left.rightMargin=dp(4);actions.addView(save,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(54),1);right.leftMargin=dp(4);actions.addView(load,right);parent.addView(actions);
    }
    private void showLogicProfiles(){
        flushLogicEdits();
        try{
            List<LogicProfile> profiles=Settings.savedLogicProfiles(this);
            if(profiles.isEmpty()){new AlertDialog.Builder(this).setTitle("Load profile").setMessage("Name a profile and tap Save profile to keep it here.").setPositiveButton("OK",null).show();return;}
            String[] names=new String[profiles.size()];for(int i=0;i<profiles.size();i++)names[i]=profiles.get(i).name;
            new AlertDialog.Builder(this).setTitle("Load profile").setItems(names,(dialog,which)->loadLogicProfile(names[which])).setNegativeButton("Cancel",null).show();
        }catch(RuntimeException failure){logicSaveFeedback("Could not load profiles: "+failure.getMessage());}
    }
    private void loadLogicProfile(String name){
        try{
            LogicProfile loaded=Settings.loadLogicProfile(this,name);
            // Disarm the old form before rebuilding: a focus change or delayed save must not restore it.
            handler.removeCallbacks(deferredLogicSave);logicSavePending=false;logicExplicitApply=false;logicAutoSaveReady=false;logicSaveAction=null;
            build();BreezeService controller=BreezeService.current;if(controller!=null)controller.applyLogicProfileExplicit(loaded);logicSaveFeedback("Loaded "+loaded.name);
        }catch(RuntimeException failure){logicSaveFeedback("Not loaded: "+failure.getMessage());}
    }
    private static String logicSignature(LogicProfile profile){return profile.serialize().replaceAll("(?m)^#.*$","");}
    private void installLogicAutosave(View view){
        if(view instanceof EditText){EditText input=(EditText)view;input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence value,int start,int count,int after){}public void onTextChanged(CharSequence value,int start,int before,int count){}public void afterTextChanged(android.text.Editable value){logicEdited(false);}});input.setOnFocusChangeListener((field,focused)->{if(!focused)flushLogicEdits();});input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);input.setOnEditorActionListener((field,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){flushLogicEdits();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);input.clearFocus();return true;}return false;});}
        else if(view instanceof Spinner){Spinner spinner=(Spinner)view;int[] selected={spinner.getSelectedItemPosition()};spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View field,int position,long id){if(position!=selected[0]){selected[0]=position;logicEdited(true);}}public void onNothingSelected(AdapterView<?> parent){}});}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)installLogicAutosave(group.getChildAt(i));}
    }
    private Consumer<LogicProfile.Rule> logicRule(LinearLayout parent,String title,String condition,LogicProfile.Rule initial,EditText[] roomInputs,int roomIndex,double roomBoundaryF){
        gap(parent,14);LinearLayout rule=col();rule.setPadding(dp(14),dp(12),dp(14),dp(12));rule.setBackground(background(0xff2c4436,16));parent.addView(rule);rule.addView(bold(title,20,INK));rule.addView(text(condition,13,MUTED));
        EditText room=logicDecimal(rule,roomIndex==0?"Room temperature at or above (°F)":"Room temperature below (°F)",roomBoundaryF);roomInputs[roomIndex]=room;
        EditText body=logicDecimal(rule,"Body threshold (°F)",initial.bodyThresholdF),minimum=logicFanSetting(rule,roomIndex==0?"Minimum fan speed":"Starting fan speed above the body threshold",initial.minimumSpeed),step=logicDecimal(rule,"Temperature increase per speed level (°F)",initial.stepF);
        CheckBox offBelow=logicCheck(rule,"Fan off below the body threshold",initial.offBelow);
        CheckBox boostEnabled=logicCheck(rule,"Sudden warming boost",initial.boost.enabled);LinearLayout boost=col();rule.addView(boost);boost.setVisibility(initial.boost.enabled?View.VISIBLE:View.GONE);boostEnabled.setOnCheckedChangeListener((control,enabled)->{boost.setVisibility(enabled?View.VISIBLE:View.GONE);logicEdited(true);});
        EditText amount=logicInteger(boost,"Extra fan levels",initial.boost.amount),trigger=logicDecimal(boost,"Temperature rise that triggers boost (°F)",initial.boost.triggerRiseF),window=logicInteger(boost,"Detect temperature rise over (seconds)",initial.boost.windowSeconds),rise=logicInteger(boost,"Boost rise time (seconds)",initial.boost.riseSeconds),hold=logicInteger(boost,"Hold boost (seconds)",initial.boost.holdSeconds),back=logicInteger(boost,"Return to normal speed (seconds)",initial.boost.returnSeconds);
        boost.addView(text("Boost curve",13,MUTED));Spinner curve=new Spinner(this);curve.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Smooth","Linear"}));curve.setSelection(initial.boost.curve==1?0:1);boost.addView(curve,new LinearLayout.LayoutParams(-1,dp(48)));
        return target->{target.bodyThresholdF=logicDecimalValue(body);target.minimumSpeed=FanSetting.parse(minimum.getText().toString());target.stepF=logicDecimalValue(step);target.offBelow=offBelow.isChecked();target.boost.enabled=boostEnabled.isChecked();target.boost.amount=logicIntegerValue(amount);target.boost.triggerRiseF=logicDecimalValue(trigger);target.boost.windowSeconds=logicIntegerValue(window);target.boost.riseSeconds=logicIntegerValue(rise);target.boost.holdSeconds=logicIntegerValue(hold);target.boost.returnSeconds=logicIntegerValue(back);target.boost.curve=curve.getSelectedItemPosition()==0?1:0;};
    }
    private void logicCard(){
        LogicProfile profile=Settings.logicProfile(this);LinearLayout logic=card();logic.addView(bold("Logic",24,INK));
        logicProfileActions(logic);TextView topFeedback=text("Saved",14,TEAL);logic.addView(topFeedback);logicSaveLabels.add(topFeedback);logic.addView(text("Edits save automatically. Save profile keeps a named copy.",12,MUTED));
        EditText name=logicInput(logic,"Profile name",profile.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        logicStatus=text("Automatic control · connecting",14,TEAL);logic.addView(logicStatus);
        addButton(logic,"Sync current fan speed",false,this::syncFan);
        logic.addView(text("Body input · Highest body temperature. The averaging window smooths that reading over time.",14,INK));
        logic.addView(text("Normal speed changes move smoothly across the full fan range.",13,MUTED));
        CheckBox includeThird=logicCheck(logic,"Include D2 when its probe is connected",(profile.probeMask&4)!=0);
        EditText average=logicInteger(logic,"Body averaging window (seconds)",profile.averageSeconds),rise=logicInteger(logic,"Full-range speed increase (seconds)",profile.riseSeconds),fall=logicInteger(logic,"Full-range speed decrease (seconds)",profile.fallSeconds),maximum=logicFanSetting(logic,"Maximum fan speed",profile.maxSpeed);
        EditText[] roomInputs=new EditText[2];Consumer<LogicProfile.Rule> warm=logicRule(logic,"Warm room","Room temperature meets or exceeds this setting",profile.warm,roomInputs,0,profile.roomBoundaryF),cool=logicRule(logic,"Cool room","Room temperature is below this setting",profile.cool,roomInputs,1,profile.coolRoomThresholdF);logic.addView(text("Between the room thresholds, keep the current fan setting.",13,MUTED));
        String[] savedSignature={logicSignature(profile)};logicSaveAction=()->{boolean applyExplicitly=logicExplicitApply;logicExplicitApply=false;logicSavePending=false;try{
            LogicProfile saved=new LogicProfile();saved.id=profile.id;saved.name=name.getText().toString().trim();saved.roomBoundaryF=logicDecimalValue(roomInputs[0]);saved.coolRoomThresholdF=logicDecimalValue(roomInputs[1]);saved.averageSeconds=logicIntegerValue(average);saved.riseSeconds=logicIntegerValue(rise);saved.fallSeconds=logicIntegerValue(fall);saved.maxSpeed=FanSetting.parse(maximum.getText().toString());saved.probeMask=3|(includeThird.isChecked()?4:0);warm.accept(saved.warm);cool.accept(saved.cool);String signature=logicSignature(saved);if(applyExplicitly||!signature.equals(savedSignature[0])){if(applyExplicitly)Settings.saveNamedLogicProfile(this,saved);else Settings.saveLogicProfile(this,saved);savedSignature[0]=signature;BreezeService controller=BreezeService.current;if(controller!=null){if(applyExplicitly)controller.applyLogicProfileExplicit(saved);else controller.applyLogicProfile(saved);}}logicSaveFeedback("Saved");
        }catch(RuntimeException failure){logicSaveFeedback("Not saved: "+(failure.getMessage()==null?"Check the settings.":failure.getMessage()));}};
        gap(logic,8);logicProfileActions(logic);TextView bottomFeedback=text("Saved",14,TEAL);logic.addView(bottomFeedback);logicSaveLabels.add(bottomFeedback);installLogicAutosave(logic);logicAutoSaveReady=true;
    }
    private void setup(){
        logicCard();
        LinearLayout c=card();c.addView(bold("Controller connection",21,INK));connection=text("Disconnected",14,TEAL);c.addView(connection);notice=text("",12,0xffedb597);c.addView(notice);two(c,"Connect / choose",this::connect,"Disconnect phone",()->{BreezeService.disconnectPhone(this);toast("Phone disconnected. The controller keeps recording.");handler.postDelayed(this::refresh,400);});
        c.addView(text("Bluetooth disconnects 2 minutes after you leave Breeze or lock the phone. Returning cancels the countdown. The controller keeps recording independently.",12,MUTED));
        addButton(c,"Download controller records",false,()->{if(service())BreezeService.current.download();});addButton(c,"Initialize new controller storage",false,()->new AlertDialog.Builder(this).setTitle("Format controller log storage?").setMessage("Only for first setup when storage cannot mount. This permanently erases the controller's log partition. Never use it to fix a controller holding records you need.").setPositiveButton("Erase and initialize",(d,w)->{if(service())BreezeService.current.control(6,0);}).setNegativeButton("Cancel",null).show());
        status=text("Connect your controller",14,MUTED);c.addView(status);transfer=text("",13,MUTED);c.addView(transfer);
        bedtimeVoiceCard();
        LinearLayout weather=card();weather.addView(bold("Outdoor weather location",21,INK));EditText query=new EditText(this);query.setSingleLine();query.setText(Settings.prefs(this).getString("query","53214"));weather.addView(query);weather.addView(text(Settings.prefs(this).getString("place","Search ZIP 53214 and choose its location."),13,MUTED));addButton(weather,"Search city or ZIP",true,()->searchPlace(query.getText().toString().trim()));
        LinearLayout hardware=card();hardware.addView(bold("Sensors",21,INK));hardware.addView(text("Body temperatures · D0, D1 and D2\nRoom temperature + humidity · SHT40\nD4 · SDA    D5 · SCL\nIR receiver OUT · D6\nIR transmitter transistor base · D7 via 1 kΩ\n\nEach body probe divider: 3.3 V → 10 kΩ resistor → D0, D1 or D2 → thermistor → GND. SHT40 uses 3.3 V and GND.\n\nAfter controller power loss, reconnect the phone to set its clock before starting another recording.",14,INK));
    }
    private void connect(){connect(false);}
    private void connect(boolean forceReconnect){
        if(!canUseBluetooth())return;
        BreezeService.setUiVisible(this,true);
        if(!hasBluetoothPermission()){pendingBluetoothAction=forceReconnect?2:1;requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},BLE_PERMISSION_REQUEST);return;}
        pendingBluetoothAction=0;
        if(forceReconnect){if(BreezeService.reconnectForVisibleUi(this))toast("Reconnecting to Breeze Sensors…");refresh();return;}
        BreezeService b=BreezeService.current;
        if(b==null||!b.visibleForBluetooth()){if(BreezeService.startForVisibleUi(this,true))toast("Searching for Breeze Sensors. Tap Connect again to choose it.");return;}
        if(b.fresh()){toast("Controller connected.");return;}
        List<String> devices=b.foundDevices();if(!devices.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose Breeze controller").setItems(devices.stream().map(s->"Breeze Sensors · "+s).toArray(String[]::new),(d,w)->{if(canUseBluetooth())b.connect(devices.get(w));}).setNegativeButton("Scan again",(d,w)->{if(canUseBluetooth())b.scan();}).show();return;}
        b.scan();toast("Searching. Tap Connect / choose again when found.");
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==BLE_PERMISSION_REQUEST){if(!hasBluetoothPermission())pendingBluetoothAction=0;else if(pendingBluetoothAction!=0&&canUseBluetooth())connect(pendingBluetoothAction==2);}if(r==VOICE_PERMISSION_REQUEST){int action=pendingVoiceAction;pendingVoiceAction=0;if(RecordingVoiceController.isListeningDisabled(this))return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){if(action==1||action==2)startRecordingWithVoice(action==2);else if(action==3)resumeRecordingVoice();}else error("Microphone permission is needed to listen during recording. Allow it in Android Settings → Apps → Breeze → Permissions, then try again.");}}
    private boolean service(){if(!canUseBluetooth()||BreezeService.current==null||!BreezeService.current.ready){toast("Connect to your Breeze controller first.");return false;}return true;}
    private String temperature(double c){return Double.isFinite(c)?String.format(Locale.US,"%.1f°%s",fahrenheit?c*1.8+32:c,fahrenheit?"F":"C"):"—";}
    private String date(long t){return new SimpleDateFormat("MMM d, yyyy · h:mm a",Locale.US).format(new Date(t));}
    private String humidity(double h){return Double.isFinite(h)?String.format(Locale.US,"%.1f%%",h):"—";}
    private void refresh(){
        reconcileBluetooth();
        if(activityResumed&&!getSystemService(KeyguardManager.class).isKeyguardLocked())RecordingVoiceController.reconcile(this);
        if(bedtimeStatus!=null){boolean disabled=RecordingVoiceController.isListeningDisabled(this);BedtimeVoiceService voice=BedtimeVoiceService.current;BreezeService controller=BreezeService.current;boolean recording=controller!=null&&controller.fresh()&&controller.latest.active();boolean microphone=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;String result=voice==null?RecordingVoiceController.lastResult:voice.lastHeard;boolean showResult=!disabled&&!result.isEmpty()&&!result.startsWith("Saved ")&&!result.startsWith("Queued ");bedtimeStatus.setText((disabled?"Listening disabled until you uncheck Disable listening.":voice==null?(recording&&!microphone?"Allow microphone access to listen during recording.":RecordingVoiceController.status()):voice.status)+(showResult?"\n"+result:""));bedtimeButton.setEnabled(!disabled);bedtimeButton.setVisibility(!disabled&&(voice!=null||recording)?View.VISIBLE:View.GONE);bedtimeButton.setText(voice!=null?"Pause listening":microphone?"Resume listening":"Allow microphone");if(disableListening!=null&&disableListening.isChecked()!=disabled)disableListening.setChecked(disabled);}
        BreezeService b=BreezeService.current;boolean fresh=b!=null&&b.fresh();Protocol.Reading r=fresh?b.latest:null;
        if(connection!=null)connection.setText(b==null||!b.visibleForBluetooth()?"○  Controller disconnected":fresh?"●  Connected":"○  "+(b.ready?"Waiting for live readings…":b.status+(b.foundDevices().isEmpty()?"":" · "+b.foundDevices().size()+" found")));
        if(notice!=null){String message=b==null?"":r!=null&&!r.storageReady()?"Controller storage unavailable • recording paused.\n"+b.transfer:b.lastError;notice.setText(message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
        if(probeValue!=null){probeValue.setText(temperature(r==null?Double.NaN:r.probe));secondProbeValue.setText(temperature(b!=null&&b.freshFan()?b.fan.secondProbe:Double.NaN));thirdProbeValue.setText(temperature(r==null?Double.NaN:r.thirdProbe));climate.setText(temperature(r==null?Double.NaN:r.room));humidityValue.setText(humidity(r==null?Double.NaN:r.humidity));}
        refreshFan(b,r);
        refreshCurrentFanSetting(b);
        if(liveReadings!=null)refreshHome(b,r,fresh);
        if(logicStatus!=null){String message=b==null?"Automatic control · waiting for controller":b.logicStatus;if(message==null||message.isEmpty())message="Automatic control · connecting";Protocol.LogicState state=b==null?null:b.logic;if(state!=null&&b.freshLogic()&&Double.isFinite(state.averageBodyF))message+="\nHighest body average "+String.format(Locale.US,"%.2f°F",state.averageBodyF)+(Double.isFinite(state.roomF)?" · room "+String.format(Locale.US,"%.1f°F",state.roomF):"")+"\nFan target "+(state.target==0?"off":FanSetting.label(state.target));logicStatus.setText(message);}
        if(sessionButton!=null){sessionButton.setText(r!=null&&r.active()?"Stop recording":RecordingVoiceController.isStarting()?"Starting recording…":"Start recording");if(sessionButton!=homeTouchedButton)sessionButton.setEnabled(fresh&&b.sensorFirmware&&(!"Home".equals(tab)||!b.irFirmware||b.manualIrReady())&&(r.active()||r.storageReady()&&!RecordingVoiceController.isStarting()));}
        if(status!=null){status.setText(r==null?"Waiting for controller":!b.sensorFirmware?"Update the controller to sensor firmware":(r.active()?"Recording on controller":"Live readings · recording stopped")+(r.error==0?"":" · "+Protocol.error(r.error)));transfer.setText(b==null?"Records stay on the controller until downloaded and confirmed.":b.transfer);}
        if(graph!=null){graph.data(selected,selected<0?new ArrayList<>():store.points(selected),selected<0?new ArrayList<>():store.weather(selected),fahrenheit,visibleSeries);graph.feelings(selected<0?new ArrayList<>():store.feelings(selected));}
        refreshSavedFeelings();
    }
    private void homeEnabled(Button button,boolean enabled){if(button!=homeTouchedButton)button.setEnabled(enabled);button.setAlpha(enabled?1f:0.5f);}
    private void refreshHome(BreezeService controller,Protocol.Reading reading,boolean fresh){
        boolean manual=controller!=null&&controller.manualIrReady();liveReadings.showReading(reading,controller!=null&&controller.freshFan()?controller.fan:null,fahrenheit);
        for(Button button:homeRemoteButtons)homeEnabled(button,manual);
        boolean known=controller!=null&&controller.freshLogic();boolean active=known&&controller.logic.automatic();homeLogicButton.setBackground(background(active?0xff78b5e1:0xffb59cdb,14));homeLogicButton.setTextColor(BG);homeLogicButton.setContentDescription(known?(active?"Logic active":"Logic paused"):"Logic waiting for controller");homeEnabled(homeLogicButton,controller!=null&&controller.homeLogicReady());
        if(connection!=null&&fresh)connection.setText(manual?"●  Connected · controls ready":"●  Connected · preparing controls…");
        if(connection!=null&&(!fresh||controller.fan==null||!controller.freshFan()||!controller.fan.synced()))connection.append("\nShowing last fan setting · Done confirms it");
        if(notice!=null&&!homeMessage.isEmpty()){notice.setText(homeMessage);notice.setVisibility(View.VISIBLE);}
    }
    private void refreshFan(BreezeService b,Protocol.Reading r){
        if(receiverLatest!=null){
            Protocol.ReceiverInput input=b==null?null:b.receiverInput;
            receiverLatest.setText(b==null||!b.ready?"Connect the controller to monitor infrared.":!b.receiverMonitorSupported?"Controller update required for receiver monitoring.":input==null||!input.hasInput()?"No infrared input received yet.":"Last received #"+input.counter+" · "+input.source()+" · "+input.label()+"\n"+input.details());
            Protocol.ReceiverInput external=b==null?null:b.lastExternalReceiverInput,led=b==null?null:b.lastLedReceiverInput;
            receiverExternal.setText(external==null?"External input · —":"External input · "+external.label()+"\n"+external.details());
            receiverLed.setText(led==null?"During LED send · —":"During LED send · "+led.label()+"\n"+led.details());
            String sent=b==null?"":b.fanCommandStatus;receiverSendStatus.setText(sent);receiverSendStatus.setVisibility(sent.isEmpty()?View.GONE:View.VISIBLE);
        }
        boolean irTesting=b!=null&&b.irReceiverTestActive();Protocol.FanState f=b!=null&&b.freshFan()?b.fan:null;boolean available=f!=null&&!b.fanWritePending()&&!irTesting;boolean learning=f!=null&&f.learning!=255;
        if(irReceiverTestButton!=null)irReceiverTestButton.setText(irTesting?"LED receiver test running…":"Test LED → receiver");
        if(irReceiverTestStatus!=null)irReceiverTestStatus.setText(b==null?"Connect the controller to test infrared output.":!b.irReceiverTestStatus.isEmpty()?b.irReceiverTestStatus:b.irReceiverTestSupported?"Ready to test the LED with the receiver.":"Controller update required for this test.");
        for(Button control:fanUtilityButtons)control.setEnabled(available);
        if(fanStatus!=null)fanStatus.setText(f==null?"Waiting for IR-controller telemetry":(f.synced()?"Estimated speed "+FanSetting.label(f.speed)+(f.busy()?" → "+FanSetting.label(f.target):""):"Fan speed unknown · sync with the physical fan")+(f.error==0?"":"\n"+Protocol.error(f.error)));
        boolean manualAvailable=b!=null&&b.manualIrReady();
        if(fanStatus!=null&&f!=null&&!irTesting&&!learning&&!manualAvailable)fanStatus.append("\nWaiting for the controller…");
        for(Button button:remoteButtons)button.setEnabled(manualAvailable);
        for(Button button:targetButtons)button.setEnabled(available&&!learning&&f.synced()&&!f.busy());
        if(learningStatus!=null){String result=f==null?"Waiting for IR-controller telemetry":learning?"Point the Dreo remote at the ESP32 and press "+Protocol.COMMANDS[f.learning]+".":switch(f.learnState){case 2->"Saved "+(f.resultSlot<Protocol.COMMANDS.length?Protocol.COMMANDS[f.resultSlot]:"command")+" on controller";case 3->"Learning timed out. Previous command retained.";case 4->"Learning canceled.";case 5->"Capture/save failed. "+Protocol.error(f.error);default->"Choose a button to learn.";};learningStatus.setText(result+(f!=null&&f.error!=0?"\n"+Protocol.error(f.error):""));}
        for(int i=0;i<learnButtons.size();i++){boolean learned=f!=null&&f.has(i);learnedLabels.get(i).setText(f==null?"Waiting for controller":learned?"Learned":"Not learned");learnButtons.get(i).setText(learned?"Relearn":"Learn");learnButtons.get(i).setEnabled(available);testButtons.get(i).setEnabled(manualAvailable&&learned);clearButtons.get(i).setEnabled(available&&!learning&&learned);}
        if(testStatus!=null){boolean recording=r!=null&&r.active();testStatus.setText(recording?"Recording raw probe data"+(f!=null&&f.phase>0?" · "+Store.phaseName(f.phase):""):"Ready to record raw probe data");startProbeTest.setEnabled(available&&!recording&&!RecordingVoiceController.isStarting());onProbes.setEnabled(available&&recording&&f.phase>0);offProbes.setEnabled(available&&recording&&f.phase>0);stopProbeTest.setEnabled(available&&recording);}
    }
    private void searchPlace(String query){if(query.isEmpty())return;toast("Looking up "+query+"…");worker.execute(()->{try{List<Weather.Place> p=Weather.search(query);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(p.isEmpty()){error("No matching location. Try the city name.");return;}new AlertDialog.Builder(this).setTitle("Choose weather location").setItems(p.stream().map(x->x.label).toArray(String[]::new),(d,w)->{Weather.Place place=p.get(w);Settings.prefs(this).edit().putString("query",query).putString("place",place.label).putString("latitude",""+place.lat).putString("longitude",""+place.lon).apply();build();}).show();});}catch(Exception e){runOnUiThread(()->error(e.getMessage()));}});}
    private void fetchWeather(Store.Session s){String lat=Settings.prefs(this).getString("latitude","");if(lat.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose your weather location").setMessage("Search ZIP 53214 in Setup first.").setPositiveButton("Open Setup",(d,w)->{tab="Setup";build();}).show();return;}Weather.Place p=new Weather.Place();p.lat=Double.parseDouble(lat);p.lon=Double.parseDouble(Settings.prefs(this).getString("longitude","0"));p.label=Settings.prefs(this).getString("place","");toast("Fetching outdoor weather…");worker.execute(()->{try{int count=Weather.fetch(store,s,p);runOnUiThread(()->{toast("Added "+count+" hourly outdoor readings.");if(!isDestroyed())build();});}catch(Exception e){runOnUiThread(()->error("Weather unavailable: "+e.getMessage()));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==EXPORT_REQUEST||request==FEELING_EXPORT_REQUEST){try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException("Cannot open export destination");if(exportSession<0)throw new java.io.IOException("Choose a session to export again.");out.write((request==FEELING_EXPORT_REQUEST?store.feelingsCsv(exportSession):store.csv(exportSession)).getBytes(StandardCharsets.UTF_8));toast("CSV exported.");}catch(Exception e){error(e.getMessage());}}}
    private void toast(String message){if("Home".equals(tab)){homeMessage=message;refresh();return;}Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void manualFeedback(String message){if("Home".equals(tab))return;if(manualFeedback!=null)manualFeedback.cancel();manualFeedback=Toast.makeText(this,message,Toast.LENGTH_SHORT);manualFeedback.show();}
    private void error(String message){if("Home".equals(tab)){homeMessage=message==null?"Check your entries.":message;refresh();return;}if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Breeze").setMessage(message==null?"Check your entries.":message).setPositiveButton("OK",null).show();}
}
