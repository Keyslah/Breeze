package com.aaron.breeze;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
    private static final int BG=0xff101d18,INK=0xffe5eee6,MUTED=0xffa6b9ac,TEAL=0xff9ac5a4,WHITE=0xff20352b,POWER_ON=0xff40e0d0;
    private static final int BLE_PERMISSION_REQUEST=7,EXPORT_REQUEST=42,VOICE_PERMISSION_REQUEST=43,FEELING_EXPORT_REQUEST=44;
    private LinearLayout shell,content,nav;
    private Store store;
    private String tab="Home";
    private boolean fahrenheit=true;
    private int visibleSeries=GraphView.DEFAULT_SERIES;
    private long selected=-1;
    private boolean followLatestHistory=true;
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
    private TextView logicApplyLabel;
    private EditText doubleHitTimeout;
    private TextView doubleHitStatus;
    private TextView dreoStatus;
    private TextView ouraStatus;
    private String doubleHitError="";
    private Button setupLogicButton,logicProfileButton;
    private Runnable logicPreview;
    private java.util.function.Supplier<LogicProfile> logicFormReader;
    private String logicProfileName="",logicFormError="",logicFormNote="";
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
    private CheckBox disableListening,onlyListenWhilePluggedIn;
    private boolean activityResumed;
    private int pendingVoiceAction;
    private int pendingBluetoothAction; // 1 = Connect / choose, 2 = Reconnect after permission approval.
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean logicExplicitApply;
    private final Runnable deferredLogicSave=()->{if(logicAutoSaveReady&&logicSavePending&&logicSaveAction!=null)logicSaveAction.run();};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService historyWorker=Executors.newSingleThreadExecutor();
    private SavedHistory savedHistory,renderedHistory;
    private boolean historyLoading,activityDestroyed;
    private int viewGeneration;
    private String historyLoadError="";
    private TextView historyLoadingLabel,weatherLocationLabel;
    private int failedHistoryGeneration=-1;
    private final Runnable queuedRefresh=()->{if(activityResumed&&!activityDestroyed)refresh();};
    private final Runnable loadSavedHistory=this::loadSavedHistory;
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(!handler.hasCallbacks(queuedRefresh))handler.postDelayed(queuedRefresh,16);}};
    private final Runnable tick=new Runnable(){public void run(){reconcileBluetooth();if(activityResumed&&!getSystemService(KeyguardManager.class).isKeyguardLocked())RecordingVoiceController.reconcile(MainActivity.this);refresh();handler.postDelayed(this,1500);}};
    private long exportSession=-1;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);
        Settings.logicProfile(this);
        if(!Settings.prefs(this).contains("fanAnchor"))Settings.prefs(this).edit().putInt("fanAnchor",1).apply();
        if(!Settings.prefs(this).contains("place"))Settings.prefs(this).edit().putString("query","53214").putString("place","Milwaukee, Wisconsin • ZIP 53214 (city weather)").putString("latitude","43.0389").putString("longitude","-87.90647").apply();
        fahrenheit=true;visibleSeries=Settings.historyVisibleSeries(Settings.prefs(this));
        if(state!=null){tab=state.getString("tab","Home");if(!Arrays.asList("Home","Fan","Learn Remote","History","Setup").contains(tab))tab="Home";selected=state.getLong("selected",-1);followLatestHistory=state.getBoolean("followLatestHistory",true);exportSession=state.getLong("exportSession",-1);pendingVoiceAction=state.getInt("pendingVoiceAction",0);pendingBluetoothAction=state.getInt("pendingBluetoothAction",0);}
        if(getIntent().getBooleanExtra("showOuraHistory",false)){tab="History";getIntent().removeExtra("showOuraHistory");}
        build();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.getBooleanExtra("showOuraHistory",false)){intent.removeExtra("showOuraHistory");tab="History";build();}}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("tab",tab);b.putLong("selected",selected);b.putBoolean("followLatestHistory",followLatestHistory);b.putLong("exportSession",exportSession);b.putInt("pendingVoiceAction",pendingVoiceAction);b.putInt("pendingBluetoothAction",pendingBluetoothAction);}
    @Override protected void onResume(){super.onResume();activityResumed=true;FanCommandTrace.record(this,"resume tab="+tab);IntentFilter filter=new IntentFilter(BreezeService.UPDATE);filter.addAction(BedtimeVoiceService.UPDATE);filter.addAction(Store.FEELINGS_CHANGED);registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);reconcileBluetooth();handler.post(tick);}
    @Override protected void onPause(){flushLogicEdits();saveDoubleHitEdits();FanCommandTrace.record(this,"pause tab="+tab);activityResumed=false;BreezeService.setUiVisible(this,false);handler.removeCallbacks(tick);handler.removeCallbacks(queuedRefresh);handler.removeCallbacks(loadSavedHistory);unregisterReceiver(updates);super.onPause();}
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
    @Override protected void onDestroy(){activityDestroyed=true;handler.removeCallbacks(loadSavedHistory);handler.removeCallbacks(queuedRefresh);disarmLogicForm();if(manualFeedback!=null)manualFeedback.cancel();worker.execute(()->{historyWorker.execute(store::close);historyWorker.shutdown();});worker.shutdown();super.onDestroy();}
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
        viewGeneration++;renderedHistory=null;historyLoadingLabel=null;weatherLocationLabel=null;
        flushLogicEdits();disarmLogicForm();saveDoubleHitEdits();
        logicStatus=null;logicApplyLabel=null;setupLogicButton=null;logicProfileButton=null;
        doubleHitTimeout=null;doubleHitStatus=null;doubleHitError="";
        dreoStatus=null;
        ouraStatus=null;
        liveReadings=null;homeLogicButton=null;homeRemoteButtons.clear();homeTouchedButton=null;homeTouchGeneration++;homeMessage="";
        currentFanSetting=null;currentFanDisplayed="";
        receiverLatest=null;receiverExternal=null;receiverLed=null;receiverSendStatus=null;irReceiverTestButton=null;irReceiverTestStatus=null;fanUtilityButtons.clear();
        bedtimeStatus=null;bedtimeButton=null;disableListening=null;onlyListenWhilePluggedIn=null;lastFeelingStatus=null;feelingEntries=null;unassignedFeelings=null;unassignedStamp="";
        connection=null;probeValue=null;secondProbeValue=null;thirdProbeValue=null;climate=null;humidityValue=null;graph=null;graphHeading=null;status=null;transfer=null;notice=null;sessionButton=null;fanStatus=null;learningStatus=null;testStatus=null;startProbeTest=null;onProbes=null;offProbes=null;stopProbeTest=null;remoteButtons.clear();targetButtons.clear();learnButtons.clear();testButtons.clear();clearButtons.clear();learnedLabels.clear();
        boolean homeTab="Home".equals(tab);int side=dp(homeTab?12:20);shell=col();shell.setBackground(new GraniteDrawable());shell.setPadding(side,0,side,0);shell.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(side,bars.top,side,bars.bottom);return insets;});setContentView(shell);
        getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout header=row();TextView brand=bold("breeze",homeTab?26:34,TEAL);header.addView(brand,new LinearLayout.LayoutParams(0,dp(homeTab?42:64),1));shell.addView(header);
        content=col();if(homeTab){content.setTag("homeRoot");shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));}else{ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}
        switch(tab){case "Fan":fanPage();break;case "Learn Remote":learnRemote();break;case "History":history();break;case "Setup":setup();break;default:home();}
        if(!homeTab)gap(content,14);nav=row();for(String title:new String[]{"Home","Fan","History","Setup"}){TextView t=bold(title,14,title.equals(tab)||(title.equals("Fan")&&tab.equals("Learn Remote"))?TEAL:MUTED);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(homeTab?8:18),0,dp(homeTab?8:18));t.setOnClickListener(v->{FanCommandTrace.record(this,"navigate from="+tab+" to="+title);tab=title;if("History".equals(title))followLatestHistory=true;build();});nav.addView(t,new LinearLayout.LayoutParams(0,dp(homeTab?48:58),1));}shell.addView(nav);refresh();
    }
    private TextView readingCard(String label){
        LinearLayout c=card();c.setPadding(dp(22),dp(20),dp(22),dp(20));
        c.addView(text(label,16,MUTED));TextView value=bold("—",46,INK);value.setFontFeatureSettings("tnum");c.addView(value);return value;
    }
    private void home(){
        liveReadings=new LiveReadingDetailsView(this);liveReadings.setTag("homeReadings");liveReadings.setBackground(background(WHITE,18));content.addView(liveReadings,new LinearLayout.LayoutParams(-1,0,0.55f));
        LinearLayout currentRow=row();LinearLayout current=col();current.setGravity(Gravity.CENTER_VERTICAL);homeSlot(currentRow,current,true);currentFanControl(current);homeSlot(currentRow,homeButton("Reconnect","homeReconnect",()->connect(true)),false);LinearLayout.LayoutParams currentLayout=new LinearLayout.LayoutParams(-1,dp(76));currentLayout.topMargin=dp(6);content.addView(currentRow,currentLayout);
        LinearLayout controls=col();controls.setTag("homeControls");controls.setPadding(0,dp(6),0,0);content.addView(controls,new LinearLayout.LayoutParams(-1,0,1));
        String[] tags={"homePower","homeSpeedPlus","homeSpeedMinus","homeMode","homeHorizontal","homeVertical"};String[] names={"Power","Speed +","Speed -","Mode","Horizontal","Vertical"};
        for(int line=0;line<2;line++){LinearLayout buttons=row();for(int column=0;column<3;column++){int slot=line*3+column;Button button=homeButton(names[slot],tags[slot],()->homeManual(slot));homeRemoteButtons.add(button);homeSlot(buttons,button,column<2);}homeRow(controls,buttons,1);}
        LinearLayout actions=row();homeLogicButton=homeButton("Logic","homeLogic",this::homeLogic);homeSlot(actions,homeLogicButton,true);sessionButton=homeButton("Start recording","homeRecording",this::sessionAction);homeSlot(actions,sessionButton,false);homeRow(controls,actions,1);
        connection=text("Controller connection",12,TEAL);connection.setTag("homeConnection");connection.setMaxLines(2);connection.setPadding(0,dp(3),0,0);controls.addView(connection,new LinearLayout.LayoutParams(-1,-2));notice=text("",12,0xffedb597);notice.setTag("homeFeedback");notice.setMaxLines(2);notice.setPadding(0,0,0,0);controls.addView(notice,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout secondary=liveReadings.secondaryReadings();secondary.setBackground(background(WHITE,18));LinearLayout.LayoutParams secondaryLayout=new LinearLayout.LayoutParams(-1,0,0.7f);secondaryLayout.topMargin=dp(4);content.addView(secondary,secondaryLayout);
    }
    private void homeRow(LinearLayout parent,LinearLayout row,float weight){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,0,weight);params.bottomMargin=dp(4);parent.addView(row,params);}
    private void homeSlot(LinearLayout row,View view,boolean margin){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);if(margin)params.setMarginEnd(dp(6));row.addView(view,params);}
    private Button homeButton(String title,String tag,Runnable action){
        Button button=button(title,false,action);button.setTag(tag);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(4),dp(2),dp(4),dp(2));button.setMaxLines(2);button.setElevation(0);button.setTranslationZ(0);button.setStateListAnimator(null);button.setFocusable(false);button.setFocusableInTouchMode(false);
        button.setBackgroundTintList(null);homeButtonColor(button,0xff2c4436);
        button.setOnClickListener(view->{homeMessage="";try{action.run();clearHomeEditingFocus();}finally{if(homeTouchedButton==view)homeTouchedButton=null;refresh();}});
        // Android may post performClick after UP. Hold readiness until that click has run.
        button.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN){homeTouchedButton=view;homeTouchGeneration++;}else if(event.getActionMasked()==MotionEvent.ACTION_UP){int generation=homeTouchGeneration;handler.postDelayed(()->{if(homeTouchGeneration==generation&&homeTouchedButton==view){homeTouchedButton=null;refresh();}},250);}else if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(homeTouchedButton==view){homeTouchedButton=null;homeTouchGeneration++;}refresh();}return false;});return button;
    }
    private void homeButtonColor(Button button,int color){
        GradientDrawable fill=(GradientDrawable)button.getBackground();ColorStateList colors=fill.getColor();
        if(colors!=null&&colors.isStateful()&&colors.getDefaultColor()==color)return;
        int pressed=Color.rgb(Color.red(color)*3/4,Color.green(color)*3/4,Color.blue(color)*3/4);
        // Each button owns its pressed fill. Queue readiness must not recolor the whole page.
        fill.setColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed},new int[0]},new int[]{pressed,color}));
    }
    private void textIfChanged(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
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
        if(!CurrentFanSettingState.edited(input.getText().toString(),currentFanDisplayed)&&!Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false)&&known&&!Settings.prefs(this).contains("homeFanSettingDraft"))return true;
        int value;try{value=FanSetting.parse(input.getText().toString());}catch(IllegalArgumentException failure){homeMessage="Enter 0 for Off, 1–9, or H for Highest.";refresh();return false;}
        if(connected!=null&&connected.homeFanAnchorApplying(value))return true;
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
        LinearLayout voice=card();voice.addView(bold("Voice commands",21,INK));
        voice.addView(text("Say \"Breeze, warm seven\" or \"Breeze, cold three\" (1–10). Feelings save silently during recording, even with the phone locked.",14,INK));
        disableListening=new CheckBox(this);disableListening.setText("Disable listening");disableListening.setTextColor(INK);disableListening.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));disableListening.setChecked(RecordingVoiceController.isListeningDisabled(this));disableListening.setOnCheckedChangeListener((button,checked)->{RecordingVoiceController.setListeningDisabled(this,checked);if(checked)pendingVoiceAction=0;else if(voicePermission(3))resumeRecordingVoice();refresh();});voice.addView(disableListening);
        voice.addView(text("While checked, the microphone stays off until you uncheck it. Sensor recording continues normally.",12,MUTED));
        onlyListenWhilePluggedIn=new CheckBox(this);onlyListenWhilePluggedIn.setTag("onlyListenWhilePluggedIn");onlyListenWhilePluggedIn.setText("Only listen while plugged in");onlyListenWhilePluggedIn.setTextColor(INK);onlyListenWhilePluggedIn.setButtonTintList(ColorStateList.valueOf(TEAL));onlyListenWhilePluggedIn.setChecked(RecordingVoiceController.isOnlyListenWhilePluggedIn(this));onlyListenWhilePluggedIn.setOnCheckedChangeListener((button,checked)->{RecordingVoiceController.setOnlyListenWhilePluggedIn(this,checked);refresh();});voice.addView(onlyListenWhilePluggedIn);
        voice.addView(text("Pauses the microphone when external power is disconnected. Plugging back in resumes an already active listener. Disable listening still overrides this setting.",12,MUTED));
        voice.addView(text("Fan commands work without recording: \"Breeze, power\" toggles power; \"Breeze, oscillate\" toggles horizontal movement; \"Breeze, logic\" toggles Logic; \"Breeze, fix fan\" checks Dreo and syncs the app with the fan.",14,INK));
        bedtimeStatus=text("Open Breeze to start enabled listening",13,TEAL);voice.addView(bedtimeStatus);
        bedtimeButton=button("Pause listening",false,this::toggleBedtimeVoice);voice.addView(bedtimeButton,new LinearLayout.LayoutParams(-1,dp(48)));
        voice.addView(text("\"Breeze, stop listening\" pauses all voice commands. Resume here, or start a new recording, to listen again.",12,MUTED));
        lastFeelingStatus=text("",13,TEAL);voice.addView(lastFeelingStatus);
        addButton(voice,"View saved feelings",false,()->{List<Store.FeelingEntry> saved=savedHistory==null?List.of():savedHistory.allFeelings;if(!saved.isEmpty()&&saved.get(saved.size()-1).session>0){selected=saved.get(saved.size()-1).session;followLatestHistory=false;}tab="History";build();});
    }
    private void toggleBedtimeVoice(){
        if(RecordingVoiceController.isListeningDisabled(this))return;
        if(BedtimeVoiceService.current!=null){RecordingVoiceController.suppress(this);refresh();return;}
        if(!RecordingVoiceController.canStartListening(this))return;
        if(!voicePermission(3))return;
        resumeRecordingVoice();
    }
    private void resumeRecordingVoice(){
        if(!RecordingVoiceController.canStartListening(this))return;
        try{RecordingVoiceController.resume(this);refresh();}catch(RuntimeException failure){error("Could not resume listening. Keep Breeze open and try again. "+failure.getMessage());}
    }
    private boolean voicePermission(int action){
        if(!RecordingVoiceController.canStartListening(this))return false;
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
        if(RecordingVoiceController.canStartListening(this)&&!voicePermission(probeTest?2:1))return;
        try{RecordingVoiceController.prepareStart(this);followLatestHistory=true;selected=-1;if(probeTest)controller.startContactTest();else controller.startSession();}
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
        LinearLayout c=card();c.addView(bold("Dreo fan",22,INK));fanConnection(c);fanStatus=text("",14,INK);c.addView(fanStatus);
        dreoStatus=text("",13,MUTED);dreoStatus.setTag("dreoStatus");c.addView(dreoStatus);
        addButton(c,"Dreo synchronization",false,()->DreoConnectionUi.show(this,worker,this::refresh));
        addButton(c,"Correction history",false,()->DreoConnectionUi.showCorrections(this));
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
        LinearLayout oura=card();oura.addView(bold("Oura",18,INK));ouraStatus=text(OuraConnection.status(this),13,MUTED);ouraStatus.setTag("ouraConnectionStatus");oura.addView(ouraStatus);
        two(oura,"Connect Oura",()->OuraConnection.start(this,worker,savedHistory==null?null:savedHistory.session,()->{if(ouraStatus!=null)ouraStatus.setText(OuraConnection.status(this));}),"Refresh Oura",()->{worker.execute(()->{OuraConnection.probe(this);handler.post(()->{if(ouraStatus!=null)ouraStatus.setText(OuraConnection.status(this));});});ouraStatus.setText("Checking Oura…");});
        unassignedFeelings=card();unassignedFeelings.setVisibility(View.GONE);
        if(!historyMatchesSelection()){LinearLayout loading=card();historyLoadingLabel=text("Loading recorded night…",15,INK);loading.addView(historyLoadingLabel);requestSavedHistory();return;}
        List<Store.Session> sessions=savedHistory.sessions;if(sessions.isEmpty()){selected=-1;LinearLayout empty=card();empty.addView(bold("Recorded nights",23,INK));empty.addView(text("No sessions yet. Connect your controller and download its records.",15,INK));addButton(empty,"Connect controller",true,this::connect);return;}
        Store.Session chosen=sessions.get(0);if(!followLatestHistory)for(Store.Session s:sessions)if(s.id==selected)chosen=s;selected=chosen.id;final Store.Session session=chosen;
        LinearLayout chart=card();LinearLayout chartTitle=row();chartTitle.setMinimumHeight(dp(40));graphHeading=bold("Recorded night",18,INK);chartTitle.addView(graphHeading,new LinearLayout.LayoutParams(-2,-2));
        SimpleDateFormat headerTime=new SimpleDateFormat("MMM d · h:mm:ss a",Locale.US);TextView graphTime=text(headerTime.format(new Date(session.start)),15,INK);graphTime.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);graphTime.setMaxLines(2);LinearLayout.LayoutParams timeLayout=new LinearLayout.LayoutParams(0,-2,1);timeLayout.setMarginStart(dp(8));chartTitle.addView(graphTime,timeLayout);chart.addView(chartTitle);
        graph=new GraphView(this);graph.setSmoothHighestBody(Settings.historySmoothHighestBody(Settings.prefs(this)));chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(346)));
        CheckBox smoothHighest=new CheckBox(this);smoothHighest.setTag("historySmoothHighestBody");smoothHighest.setText("Smooth highest body temp");smoothHighest.setTextColor(GraphView.COLORS[GraphView.HIGHEST_BODY_SERIES]);smoothHighest.setButtonTintList(ColorStateList.valueOf(GraphView.COLORS[GraphView.HIGHEST_BODY_SERIES]));smoothHighest.setChecked(Settings.historySmoothHighestBody(Settings.prefs(this)));smoothHighest.setOnCheckedChangeListener((button,checked)->{Settings.prefs(this).edit().putBoolean("historySmoothHighestBody",checked).apply();graph.setSmoothHighestBody(checked);});
        GraphReadingDetailsView readingDetails=new GraphReadingDetailsView(this);readingDetails.setVisibleSeries(visibleSeries);readingDetails.setOnSeriesChangedListener((series,checked)->{if(checked)visibleSeries|=1<<series;else visibleSeries&=~(1<<series);Settings.prefs(this).edit().putInt("historyVisibleSeries",visibleSeries).apply();graph.visibleSeries(visibleSeries);});chart.addView(readingDetails,new LinearLayout.LayoutParams(-1,-2));graph.setReadingDetailsListener(details->{readingDetails.showReading(details);graphTime.setText(headerTime.format(new Date(details==null?session.start:details.time)));graphTime.setContentDescription(details==null?date(session.start):details.timestamp);});
        content.removeView(chart);content.addView(chart,0);
        chart.addView(smoothHighest);
        two(chart,"Reset zoom",()->graph.resetZoom(),"Choose recorded night",this::chooseRecordedNight);
        addButton(chart,"Graph help",false,()->new AlertDialog.Builder(this).setTitle("Graph controls").setMessage("Tap for readings, pinch to zoom, drag sideways to pan. Double-tap or Reset zoom shows the whole night.\n\nHighest body temp shows the highest available D0, D1 or D2 body temperature at each recorded timestamp. It is on by default; the three individual body temperature traces start unchecked. Body readings below 90°F are excluded from the graph, its temperature range and smoothing; those intervals remain gaps. Saved raw readings are unchanged. Room temperature is separate.\n\nSmooth Highest temp averages the highest body readings within one minute before and after each sample. It smooths only the Highest trace and its labeled readout, keeps gaps, and leaves original recordings, exports and other traces unchanged. Uncheck it to see the original line.\n\nCheckboxes show or hide plotted traces; the readings remain available. Body temperature uses the right axis. Room, outdoor and Dreo temperatures share a separate left axis. Fan setting and humidity use independent scales, so neither changes the body-temperature range. Dreo temperatures are collected while this phone can reach Dreo; missing intervals stay blank. Dashed traces are hourly outdoor weather. Feeling ratings use +1 to +10 for warm and −1 to −10 for cold.").setPositiveButton("OK",null).show());

        LinearLayout feelings=card();feelingEntries=new FeelingEntriesView(this,store,session,this::refresh,savedHistory.feelings);feelings.addView(feelingEntries);
        addButton(feelings,"Export feeling entries",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-feelings-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,FEELING_EXPORT_REQUEST);});

        LinearLayout outdoor=card();outdoor.addView(bold("Outdoor weather",19,INK));String location=savedHistory.weatherLocation;weatherLocationLabel=text(location.isEmpty()?Settings.prefs(this).getString("place","Choose location in Setup"):location,13,TEAL);outdoor.addView(weatherLocationLabel);addButton(outdoor,"Fetch outdoor weather",true,()->fetchWeather(session));addButton(outdoor,"Export session as CSV",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,EXPORT_REQUEST);});
        outdoor.addView(text("Open-Meteo hourly weather model · CC BY 4.0",11,MUTED));
    }
    private String feelingLabel(Store.FeelingEntry entry){return ("COLD".equals(entry.state)?"Cold":"Warm")+" "+entry.intensity+" / 10";}
    private String feelingTime(long time){return new SimpleDateFormat("MMM d, yyyy · h:mm:ss a",Locale.US).format(new Date(time));}
    private boolean historyMatchesSelection(){
        if(savedHistory==null||!savedHistory.includesGraph)return false;
        long desired=followLatestHistory?(savedHistory.sessions.isEmpty()?-1:savedHistory.sessions.get(0).id):selected;
        return savedHistory.selected()==desired;
    }
    private void requestSavedHistory(){
        if(activityDestroyed||!activityResumed||historyLoading||failedHistoryGeneration==viewGeneration||!("History".equals(tab)||lastFeelingStatus!=null))return;
        boolean graphNeeded="History".equals(tab);
        if(savedHistory!=null&&savedHistory.revision==store.revision()
                &&(!graphNeeded||historyMatchesSelection()))return;
        if(!handler.hasCallbacks(loadSavedHistory))handler.postDelayed(loadSavedHistory,150);
    }
    private void loadSavedHistory(){
        if(activityDestroyed||!activityResumed||historyLoading)return;
        boolean graphNeeded="History".equals(tab);
        if(!graphNeeded&&lastFeelingStatus==null)return;
        historyLoading=true;
        final int generation=viewGeneration;
        final long requested=selected;
        final boolean follow=followLatestHistory;
        historyWorker.execute(()->{
            SavedHistory data=null;String failure="";
            try{data=new SavedHistory(store,requested,follow,graphNeeded);}
            catch(RuntimeException error){failure="Could not load recorded history. Try reopening History.";android.util.Log.w("BreezeHistory","History read failed",error);}
            final SavedHistory loaded=data;final String failed=failure;
            handler.post(()->{
                historyLoading=false;if(activityDestroyed)return;
                if(generation!=viewGeneration){requestSavedHistory();return;}
                historyLoadError=failed;
                if(loaded==null){failedHistoryGeneration=viewGeneration;if(historyLoadingLabel!=null)textIfChanged(historyLoadingLabel,failed);return;}
                long oldSelected=selected;savedHistory=loaded;
                if("History".equals(tab)){
                    selected=loaded.selected();
                    if(oldSelected!=selected||graph==null){build();return;}
                }
                refresh();
            });
        });
    }
    private void refreshSavedFeelings(){
        if(savedHistory==null)return;
        if(lastFeelingStatus!=null){List<Store.FeelingEntry> entries=savedHistory.allFeelings;if(entries.isEmpty())textIfChanged(lastFeelingStatus,"No feeling entries saved yet.");else{Store.FeelingEntry latest=entries.get(entries.size()-1);textIfChanged(lastFeelingStatus,"Last saved: "+feelingLabel(latest)+"\n"+feelingTime(latest.time)+(latest.session<=0?"\nSaved on phone • waiting to link to a recorded night":""));}}
        if(feelingEntries!=null&&savedHistory.selected()==selected)feelingEntries.showEntries(savedHistory.feelings);
        if(unassignedFeelings==null)return;
        List<Store.FeelingEntry> waiting=savedHistory.waiting;StringBuilder stamp=new StringBuilder();for(Store.FeelingEntry entry:waiting)stamp.append(entry.id).append(':').append(entry.time).append(':').append(entry.state).append(':').append(entry.intensity).append(';');
        String next=stamp.toString();if(next.equals(unassignedStamp))return;unassignedStamp=next;unassignedFeelings.removeAllViews();unassignedFeelings.setVisibility(waiting.isEmpty()?View.GONE:View.VISIBLE);
        if(waiting.isEmpty())return;
        unassignedFeelings.addView(bold("Saved feelings awaiting a night",19,INK));unassignedFeelings.addView(text("These entries are saved on this phone. Breeze links them when the matching controller records arrive. You can also choose their recorded night.",13,MUTED));
        for(Store.FeelingEntry entry:waiting)addButton(unassignedFeelings,feelingTime(entry.time)+" · "+feelingLabel(entry)+"\nChoose recorded night",false,()->assignFeelingNight(entry));
    }
    private void assignFeelingNight(Store.FeelingEntry entry){
        List<Store.Session> nights=savedHistory==null?List.of():savedHistory.sessions;if(nights.isEmpty()){toast("Download a recorded night from the controller first.");return;}
        String[] labels=nights.stream().map(night->date(night.start)+(night.end>0?" to "+date(night.end):" · no end marker")).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Choose night for "+feelingLabel(entry)).setItems(labels,(dialog,which)->{try{store.assignFeeling(entry.id,nights.get(which).id);selected=nights.get(which).id;followLatestHistory=false;build();}catch(RuntimeException failure){error(failure.getMessage());}}).setNegativeButton("Cancel",null).show();
    }
    private void chooseRecordedNight(){
        List<Store.Session> nights=savedHistory==null?List.of():savedHistory.sessions;if(nights.isEmpty()){build();return;}
        String[] labels=new String[nights.size()];int current=0;
        for(int i=0;i<nights.size();i++){Store.Session night=nights.get(i);labels[i]=date(night.start)+"\n"+night.count+" readings"+(night.end==0?" · open / interrupted":" · ended "+new SimpleDateFormat("h:mm a",Locale.US).format(new Date(night.end)));if(night.id==selected)current=i;}
        new AlertDialog.Builder(this).setTitle("Choose recorded night ("+nights.size()+")").setSingleChoiceItems(labels,current,(dialog,which)->{selected=nights.get(which).id;followLatestHistory=false;dialog.dismiss();build();}).setNegativeButton("Cancel",null).show();
    }
    private EditText logicInput(LinearLayout parent,String label,String description,String value,int inputType){
        parent.addView(text(label,13,MUTED));EditText input=new EditText(this);input.setSingleLine();input.setTextColor(INK);input.setHintTextColor(MUTED);input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TEAL));input.setTextSize(16);input.setInputType(inputType);input.setText(value);input.setContentDescription(description);parent.addView(input,new LinearLayout.LayoutParams(-1,dp(48)));return input;
    }
    private static String logicNumber(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private EditText logicDecimal(LinearLayout parent,String label,String description,double value){return logicInput(parent,label,description,logicNumber(value),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);}
    private EditText logicDecimal(LinearLayout parent,String label,double value){return logicDecimal(parent,label,label,value);}
    private EditText logicInteger(LinearLayout parent,String label,int value){return logicInput(parent,label,label,Integer.toString(value),InputType.TYPE_CLASS_NUMBER);}
    private String logicTimeLabel(int minute){java.text.DateFormat format=android.text.format.DateFormat.getTimeFormat(this);format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.format(new Date(minute*60_000L));}
    private Button logicTimeButton(String label,String tag,int[] minutes,int index){
        Button control=button("",false,()->{});control.setTag(tag);Runnable refresh=()->{String time=logicTimeLabel(minutes[index]);control.setText(label+" · "+time);control.setContentDescription("Time window "+label.toLowerCase(Locale.ROOT)+", "+time);};refresh.run();
        control.setOnClickListener(view->new TimePickerDialog(this,(picker,hour,minute)->{minutes[index]=hour*60+minute;refresh.run();logicEdited(true);},minutes[index]/60,minutes[index]%60,android.text.format.DateFormat.is24HourFormat(this)).show());return control;
    }
    private double logicDecimalValue(EditText input){try{double value=Double.parseDouble(input.getText().toString().trim());if(!Double.isFinite(value))throw new NumberFormatException();return value;}catch(NumberFormatException failure){throw new IllegalArgumentException("Enter a number for "+input.getContentDescription()+".");}}
    private int logicIntegerValue(EditText input){try{return Integer.parseInt(input.getText().toString().trim());}catch(NumberFormatException failure){throw new IllegalArgumentException("Enter a whole number for "+input.getContentDescription()+".");}}
    private CheckBox logicCheck(LinearLayout parent,String label,boolean checked){CheckBox check=new CheckBox(this);check.setText(label);check.setTextColor(INK);check.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));check.setChecked(checked);check.setOnCheckedChangeListener((control,value)->logicEdited(true));parent.addView(check);return check;}
    private void logicSection(LinearLayout parent,String title){gap(parent,14);parent.addView(bold(title,17,TEAL));}
    /** One point of the range: a room and a body temperature side by side. */
    private EditText[] logicPoint(LinearLayout parent,String title,double roomF,double bodyF){
        parent.addView(text(title,14,INK));LinearLayout pair=row();pair.setGravity(Gravity.TOP);LinearLayout room=col(),body=col();
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=dp(6);pair.addView(room,left);LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=dp(6);pair.addView(body,right);parent.addView(pair);
        return new EditText[]{logicDecimal(room,"Room (°F)",title+" · room (°F)",roomF),logicDecimal(body,"Body (°F)",title+" · body (°F)",bodyF)};
    }
    /** Fan settings across the range for steady readings, as plain rows and columns. */
    static String logicPreviewTable(LogicProfile profile){
        double[] bodies={94,97,99};StringBuilder table=new StringBuilder(String.format(Locale.US,"%-9s",""));
        for(double body:bodies)table.append(String.format(Locale.US,"%9s","Body "+logicNumber(body)+"°"));
        for(int row=0;row<4;row++){
            double room=profile.offRoomF+(profile.fullRoomF-profile.offRoomF)*row/3;
            table.append('\n').append(String.format(Locale.US,"%-9s","Room "+logicNumber(Math.rint(room*10)/10)+"°"));
            for(double body:bodies){int level=profile.level(room,body);table.append(String.format(Locale.US,"%9s",level==0?"Off":FanSetting.label(level)));}
        }
        return table.toString();
    }
    static String logicPace(int fullRangeSeconds){
        int step=LogicProfile.stepMillis(fullRangeSeconds);boolean floor=fullRangeSeconds*1000<(FanSetting.MAX-1)*LogicProfile.MINIMUM_STEP_MS;
        return String.format(Locale.US,"→ %.1f s per level",step/1000.0)+(floor?" (the minimum)":"");
    }
    private void logicEdited(boolean immediate){if(!logicAutoSaveReady||logicSaveAction==null)return;logicSavePending=true;handler.removeCallbacks(deferredLogicSave);if(logicPreview!=null)logicPreview.run();if(immediate)logicSaveAction.run();else handler.postDelayed(deferredLogicSave,600);}
    private void flushLogicEdits(){handler.removeCallbacks(deferredLogicSave);if(logicAutoSaveReady&&logicSavePending&&logicSaveAction!=null)logicSaveAction.run();}
    private void applyLogicNow(){if(!logicAutoSaveReady||logicSaveAction==null)return;logicExplicitApply=true;logicSavePending=true;flushLogicEdits();}
    private void disarmLogicForm(){handler.removeCallbacks(deferredLogicSave);logicSavePending=false;logicExplicitApply=false;logicAutoSaveReady=false;logicSaveAction=null;logicFormReader=null;logicPreview=null;}
    private void setupLogicToggle(){
        BreezeService controller=BreezeService.current;if(!service())return;
        if(!controller.homeLogicReady()){toast("Wait for the controller to finish preparing.");return;}
        flushLogicEdits();if(!controller.setHomeLogicEnabled(!controller.logic.automatic()))toast(controller.lastError);refresh();
    }
    private void showLogicProfiles(){
        flushLogicEdits();
        try{
            List<LogicProfile> profiles=Settings.savedLogicProfiles(this);
            if(profiles.isEmpty()){new AlertDialog.Builder(this).setTitle("Load profile").setMessage("No saved profiles yet. Use Save as… to keep the current settings under a name.").setPositiveButton("OK",null).show();return;}
            String[] names=new String[profiles.size()];for(int i=0;i<profiles.size();i++)names[i]=profiles.get(i).name;
            new AlertDialog.Builder(this).setTitle("Load profile").setItems(names,(dialog,which)->{
                // Loading replaces the form. Ask first when it holds edits that were never applied.
                if(Settings.logicDraftPending(this))new AlertDialog.Builder(this).setTitle("Replace unapplied changes?").setMessage("The changes on this page have not been applied to the fan. Loading "+names[which]+" replaces them.").setPositiveButton("Load",(confirm,button)->loadLogicProfile(names[which])).setNegativeButton("Cancel",null).show();
                else loadLogicProfile(names[which]);
            }).setNegativeButton("Cancel",null).show();
        }catch(RuntimeException failure){toast("Could not load profiles: "+failure.getMessage());}
    }
    private void loadLogicProfile(String name){
        try{
            LogicProfile loaded=Settings.loadLogicProfile(this,name);
            // Disarm the old form before rebuilding: a focus change or delayed save must not restore it.
            disarmLogicForm();Settings.saveLogicDraft(this,loaded);build();
        }catch(RuntimeException failure){toast("Not loaded: "+failure.getMessage());}
    }
    private void saveLogicProfileAs(){
        flushLogicEdits();if(logicFormReader==null)return;
        final LogicProfile edited;try{edited=logicFormReader.get();}catch(RuntimeException failure){toast(failure.getMessage()==null?"Check the settings.":failure.getMessage());return;}
        EditText name=new EditText(this);name.setSingleLine();name.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);name.setText(edited.name);name.setSelectAllOnFocus(true);name.setContentDescription("Profile name");
        new AlertDialog.Builder(this).setTitle("Save profile as").setView(name).setPositiveButton("Save",(dialog,button)->{
            String chosen=name.getText().toString().trim();
            try{
                if(Settings.hasSavedLogicProfile(this,chosen)&&!chosen.equals(edited.name))new AlertDialog.Builder(this).setTitle("Replace "+chosen+"?").setMessage("A saved profile already has this name.").setPositiveButton("Replace",(confirm,which)->storeLogicProfile(edited,chosen)).setNegativeButton("Cancel",null).show();
                else storeLogicProfile(edited,chosen);
            }catch(RuntimeException failure){toast("Not saved: "+failure.getMessage());}
        }).setNegativeButton("Cancel",null).show();
    }
    private void storeLogicProfile(LogicProfile edited,String name){
        try{
            edited.name=name;Settings.saveNamedLogicProfile(this,edited);Settings.saveLogicDraft(this,edited);logicProfileName=edited.name.trim();
            if(logicProfileButton!=null)logicProfileButton.setText("Profile: "+logicProfileName+" ▾");toast("Saved "+logicProfileName+".");refreshLogicApply(BreezeService.current);
        }catch(RuntimeException failure){toast("Not saved: "+failure.getMessage());}
    }
    private void installLogicAutosave(View view){
        if(view instanceof EditText){EditText input=(EditText)view;input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence value,int start,int count,int after){}public void onTextChanged(CharSequence value,int start,int before,int count){}public void afterTextChanged(android.text.Editable value){logicEdited(false);}});input.setOnFocusChangeListener((field,focused)->{if(!focused)flushLogicEdits();});input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);input.setOnEditorActionListener((field,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){flushLogicEdits();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);input.clearFocus();return true;}return false;});}
        else if(view instanceof Spinner){Spinner spinner=(Spinner)view;int[] selected={spinner.getSelectedItemPosition()};spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View field,int position,long id){if(position!=selected[0]){selected[0]=position;logicEdited(true);}}public void onNothingSelected(AdapterView<?> parent){}});}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)installLogicAutosave(group.getChildAt(i));}
    }
    private final class LogicTimeWindowEditor {
        final LinearLayout view=col();
        final TextView title;
        final CheckBox enabled;
        final int[] minutes;
        final Button start,end,remove;
        final EditText adjustment;
        LogicTimeWindowEditor(LogicProfile.TimeWindow window,Consumer<LogicTimeWindowEditor> onRemove){
            view.setPadding(dp(10),dp(6),dp(10),dp(10));
            LinearLayout heading=row();title=bold("",16,INK);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            remove=button("Remove",false,()->onRemove.accept(this));heading.addView(remove,new LinearLayout.LayoutParams(-2,dp(48)));view.addView(heading);
            enabled=logicCheck(view,"Enable this time window",window.enabled);
            minutes=new int[]{window.startMinute,window.endMinute};LinearLayout times=row();
            start=logicTimeButton("Start","",minutes,0);end=logicTimeButton("End","",minutes,1);
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(56),1);left.rightMargin=dp(4);times.addView(start,left);
            LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(56),1);right.leftMargin=dp(4);times.addView(end,right);view.addView(times);
            adjustment=logicInput(view,"Level adjustment","Time window level adjustment",Integer.toString(window.adjustment),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        void number(int index,int count){
            String suffix=index==0?"":Integer.toString(index),label="Time window "+(index+1);
            title.setText(label);view.setTag("logicTimeWindow"+suffix);enabled.setTag("logicTimeWindowEnabled"+suffix);
            start.setTag("logicTimeWindowStart"+suffix);end.setTag("logicTimeWindowEnd"+suffix);adjustment.setTag("logicTimeWindowAdjustment"+suffix);remove.setTag("logicTimeWindowRemove"+suffix);
            enabled.setContentDescription("Enable "+label.toLowerCase(Locale.US));start.setContentDescription(label+" start");end.setContentDescription(label+" end");adjustment.setContentDescription(label+" level adjustment");remove.setContentDescription("Remove "+label.toLowerCase(Locale.US));
            remove.setEnabled(count>1);remove.setAlpha(count>1?1f:0.45f);
        }
        LogicProfile.TimeWindow read(){
            LogicProfile.TimeWindow window=new LogicProfile.TimeWindow();window.enabled=enabled.isChecked();window.startMinute=minutes[0];window.endMinute=minutes[1];window.adjustment=logicIntegerValue(adjustment);return window;
        }
    }
    private final class LogicTimeWindowsEditor {
        final LinearLayout view=col(),windows=col();
        final List<LogicTimeWindowEditor> editors=new ArrayList<>();
        final Button add;
        LogicTimeWindowsEditor(LogicProfile profile){
            view.addView(windows);
            add=button("Add window",false,()->append(new LogicProfile.TimeWindow(),true));add.setTag("logicTimeWindowAdd");
            for(LogicProfile.TimeWindow window:profile.timeWindows)append(window,false);
            view.addView(add,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        void append(LogicProfile.TimeWindow window,boolean changed){
            LogicTimeWindowEditor editor=new LogicTimeWindowEditor(window,this::remove);editors.add(editor);windows.addView(editor.view);renumber();
            if(changed){installLogicAutosave(editor.view);logicEdited(true);}
        }
        void remove(LogicTimeWindowEditor editor){
            if(editors.size()<=1)return;
            editors.remove(editor);windows.removeView(editor.view);renumber();logicEdited(true);
        }
        void renumber(){
            for(int i=0;i<editors.size();i++)editors.get(i).number(i,editors.size());
        }
        List<LogicProfile.TimeWindow> read(){List<LogicProfile.TimeWindow> result=new ArrayList<>();for(LogicTimeWindowEditor editor:editors)result.add(editor.read());return result;}
    }
    private void logicCard(){
        LogicProfile profile=Settings.logicDraft(this);logicProfileName=profile.name;logicFormError="";LinearLayout logic=card();
        LinearLayout heading=row();heading.addView(bold("Logic",24,INK),new LinearLayout.LayoutParams(0,-2,1));setupLogicButton=button("Logic",false,this::setupLogicToggle);setupLogicButton.setTag("setupLogic");heading.addView(setupLogicButton,new LinearLayout.LayoutParams(-2,dp(46)));logic.addView(heading);
        logicStatus=text("Waiting for the controller",14,TEAL);logic.addView(logicStatus);
        LinearLayout profiles=row();logicProfileButton=button("Profile: "+logicProfileName+" ▾",false,this::showLogicProfiles);logicProfileButton.setTag("logicProfile");Button saveAs=button("Save as…",false,this::saveLogicProfileAs);
        LinearLayout.LayoutParams wide=new LinearLayout.LayoutParams(0,dp(48),1.6f);wide.rightMargin=dp(4);profiles.addView(logicProfileButton,wide);
        LinearLayout.LayoutParams narrow=new LinearLayout.LayoutParams(0,dp(48),1);narrow.leftMargin=dp(4);profiles.addView(saveAs,narrow);LinearLayout.LayoutParams profileRow=new LinearLayout.LayoutParams(-1,-2);profileRow.topMargin=dp(8);logic.addView(profiles,profileRow);
        addButton(logic,"Apply to fan",true,this::applyLogicNow);
        logicApplyLabel=text("",13,0xffedb597);logicApplyLabel.setTag("logicApply");logic.addView(logicApplyLabel);
        logic.addView(text("Edits stay on this phone until you tap Apply to fan.",12,MUTED));

        logicSection(logic,"Range");
        logic.addView(text("One range from Off through 1–9 to H. Room and body temperature add together between these two points.",12,MUTED));
        EditText[] off=logicPoint(logic,"Fan off at",profile.offRoomF,profile.offBodyF),full=logicPoint(logic,"Full speed at",profile.fullRoomF,profile.fullBodyF);
        EditText weight=logicDecimal(logic,"Body weight (levels per °F)",profile.bodyWeight);
        TextView roomWeight=text("",13,MUTED);roomWeight.setTag("logicRoomWeight");logic.addView(roomWeight);
        TextView preview=text("",12,INK);preview.setTypeface(Typeface.MONOSPACE);preview.setTag("logicPreview");logic.addView(preview);

        logicSection(logic,"Smoothing and pacing");
        EditText average=logicInteger(logic,"Body averaging window (seconds)",profile.averageSeconds),rise=logicInteger(logic,"Full-range speed increase (seconds)",profile.riseSeconds);
        TextView risePace=text("",12,MUTED);risePace.setTag("logicRisePace");logic.addView(risePace);
        EditText fall=logicInteger(logic,"Full-range speed decrease (seconds)",profile.fallSeconds);
        TextView fallPace=text("",12,MUTED);fallPace.setTag("logicFallPace");logic.addView(fallPace);
        logic.addView(text("The fan needs at least 1 second between steps in either direction, so anything under 9 seconds runs at 1 second per level.",12,MUTED));

        logicSection(logic,"Time windows");
        logic.addView(text("Overlapping windows add together: −1 and −3 make −4. The total is applied once to the Logic level, including boost, and the result stays between 0 (Off) and H.",12,MUTED));
        logic.addView(text("Repeats daily in your phone’s local time. An end before the start continues after midnight.",12,MUTED));
        LogicTimeWindowsEditor timeWindows=new LogicTimeWindowsEditor(profile);logic.addView(timeWindows.view);
        logic.addView(text("Uncheck any window to disable it and keep its settings. Disable every window to use normal Logic all day.",12,MUTED));

        logicSection(logic,"Sudden warming boost");
        CheckBox boostEnabled=logicCheck(logic,"Boost after a sudden body temperature rise",profile.boost.enabled);LinearLayout boost=col();logic.addView(boost);boost.setVisibility(profile.boost.enabled?View.VISIBLE:View.GONE);boostEnabled.setOnCheckedChangeListener((control,enabled)->{boost.setVisibility(enabled?View.VISIBLE:View.GONE);logicEdited(true);});
        EditText amount=logicInteger(boost,"Extra fan levels",profile.boost.amount),trigger=logicDecimal(boost,"Body rise that triggers boost (°F)",profile.boost.triggerRiseF),window=logicInteger(boost,"Detect the rise within (seconds)",profile.boost.windowSeconds),up=logicInteger(boost,"Boost ramp up (seconds)",profile.boost.riseSeconds),hold=logicInteger(boost,"Hold boost (seconds)",profile.boost.holdSeconds),back=logicInteger(boost,"Return to normal speed (seconds)",profile.boost.returnSeconds);
        boost.addView(text("Boost curve",13,MUTED));Spinner curve=new Spinner(this);curve.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Smooth","Linear"}));curve.setSelection(profile.boost.curve==1?0:1);boost.addView(curve,new LinearLayout.LayoutParams(-1,dp(48)));
        boost.addView(text("Ignored while the reading recovers after the fan has been Off, such as when you get back into bed.",12,MUTED));

        gap(logic,14);TextView advancedToggle=bold("▸ Advanced",17,TEAL);advancedToggle.setTag("logicAdvanced");advancedToggle.setMinHeight(dp(44));advancedToggle.setGravity(Gravity.CENTER_VERTICAL);logic.addView(advancedToggle);
        LinearLayout advanced=col();advanced.setVisibility(View.GONE);logic.addView(advanced);
        advancedToggle.setOnClickListener(view->{boolean show=advanced.getVisibility()!=View.VISIBLE;advanced.setVisibility(show?View.VISIBLE:View.GONE);advancedToggle.setText(show?"▾ Advanced":"▸ Advanced");});
        EditText steadiness=logicDecimal(advanced,"Steadiness (levels past a boundary before changing)",profile.steadiness);
        advanced.addView(text("Stops the fan flipping between two settings when the readings sit on a boundary.",12,MUTED));
        addButton(advanced,"Sync current fan speed",false,this::syncFan);

        logicFormReader=()->{
            LogicProfile edited=new LogicProfile();edited.id=profile.id;edited.name=logicProfileName;
            edited.offRoomF=logicDecimalValue(off[0]);edited.offBodyF=logicDecimalValue(off[1]);edited.fullRoomF=logicDecimalValue(full[0]);edited.fullBodyF=logicDecimalValue(full[1]);edited.bodyWeight=logicDecimalValue(weight);edited.steadiness=logicDecimalValue(steadiness);
            edited.averageSeconds=logicIntegerValue(average);edited.riseSeconds=logicIntegerValue(rise);edited.fallSeconds=logicIntegerValue(fall);
            edited.replaceTimeWindows(timeWindows.read());
            edited.boost.enabled=boostEnabled.isChecked();edited.boost.amount=logicIntegerValue(amount);edited.boost.triggerRiseF=logicDecimalValue(trigger);edited.boost.windowSeconds=logicIntegerValue(window);edited.boost.riseSeconds=logicIntegerValue(up);edited.boost.holdSeconds=logicIntegerValue(hold);edited.boost.returnSeconds=logicIntegerValue(back);edited.boost.curve=curve.getSelectedItemPosition()==0?1:0;
            edited.validate();return edited;
        };
        java.util.function.Supplier<LogicProfile> reader=logicFormReader;
        logicPreview=()->{
            try{LogicProfile edited=reader.get();roomWeight.setText(String.format(Locale.US,"Room weight · %.2f levels per °F (worked out from the two points and the body weight)",edited.roomWeight()));preview.setText(logicPreviewTable(edited));}
            catch(RuntimeException invalid){roomWeight.setText("Room weight · —");preview.setText("");}
            try{risePace.setText(logicPace(logicIntegerValue(rise)));}catch(RuntimeException invalid){risePace.setText("");}
            try{fallPace.setText(logicPace(logicIntegerValue(fall)));}catch(RuntimeException invalid){fallPace.setText("");}
        };
        logicSaveAction=()->{
            boolean apply=logicExplicitApply;logicExplicitApply=false;logicSavePending=false;
            try{
                LogicProfile edited=reader.get();Settings.saveLogicDraft(this,edited);logicFormError="";
                if(apply){
                    Settings.saveLogicProfile(this,edited);BreezeService controller=BreezeService.current;
                    if(controller!=null){logicFormNote="";controller.applyLogicProfile(edited);}else logicFormNote="Saved on phone · will be sent when the controller connects";
                }
            }catch(RuntimeException failure){logicFormError=(apply?"Not applied: ":"Not saved: ")+(failure.getMessage()==null?"Check the settings.":failure.getMessage());}
            refreshLogicApply(BreezeService.current);
        };
        installLogicAutosave(logic);logicPreview.run();logicAutoSaveReady=true;
    }
    private void setup(){
        logicCard();
        doubleHitCard();
        LinearLayout c=card();c.addView(bold("Controller connection",21,INK));connection=text("Disconnected",14,TEAL);c.addView(connection);notice=text("",12,0xffedb597);c.addView(notice);two(c,"Connect / choose",this::connect,"Disconnect phone",()->{BreezeService.disconnectPhone(this);toast("Phone disconnected. The controller keeps recording.");handler.postDelayed(this::refresh,400);});
        c.addView(text("Bluetooth disconnects 2 minutes after you leave Breeze or lock the phone. Returning cancels the countdown. The controller keeps recording independently.",12,MUTED));
        addButton(c,"Download controller records",false,()->{if(service())BreezeService.current.download();});addButton(c,"Initialize new controller storage",false,()->new AlertDialog.Builder(this).setTitle("Format controller log storage?").setMessage("Only for first setup when storage cannot mount. This permanently erases the controller's log partition. Never use it to fix a controller holding records you need.").setPositiveButton("Erase and initialize",(d,w)->{if(service())BreezeService.current.control(6,0);}).setNegativeButton("Cancel",null).show());
        status=text("Connect your controller",14,MUTED);c.addView(status);transfer=text("",13,MUTED);c.addView(transfer);
        bedtimeVoiceCard();
        LinearLayout weather=card();weather.addView(bold("Outdoor weather location",21,INK));EditText query=new EditText(this);query.setSingleLine();query.setText(Settings.prefs(this).getString("query","53214"));weather.addView(query);weather.addView(text(Settings.prefs(this).getString("place","Search ZIP 53214 and choose its location."),13,MUTED));addButton(weather,"Search city or ZIP",true,()->searchPlace(query.getText().toString().trim()));
        LinearLayout hardware=card();hardware.addView(bold("Sensors",21,INK));hardware.addView(text("Body temperatures · D0, D1 and D2\nRoom temperature + humidity · SHT40\nD4 · SDA    D5 · SCL\nIR receiver OUT · D6\nIR transmitter transistor base · D7 via 1 kΩ\n\nEach body probe divider: 3.3 V → 10 kΩ resistor → D0, D1 or D2 → thermistor → GND. SHT40 uses 3.3 V and GND.\n\nAfter controller power loss, reconnect the phone to set its clock before starting another recording.",14,INK));
    }
    private void doubleHitCard(){
        LinearLayout card=card();card.setTag("doubleHitSettings");card.addView(bold("Idle time (seconds)",21,INK));
        EditText input=new EditText(this);doubleHitTimeout=input;input.setTag("doubleHitTimeout");input.setContentDescription("Idle time (seconds)");input.setSingleLine();input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setTextSize(18);input.setTextColor(INK);input.setBackgroundTintList(ColorStateList.valueOf(TEAL));input.setSelectAllOnFocus(true);input.setText(Integer.toString(Settings.doubleHitTimeout(this)));input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);card.addView(input,new LinearLayout.LayoutParams(-1,dp(48)));
        card.addView(text("0 disables double hits. Otherwise enter how long the fan keeps its display on (60 with Dreo's Display Auto Off). The first press on a dark display only lights it, so a press sent later than that goes out twice. Close to that time Breeze waits a few seconds until the display is certainly off.",12,MUTED));
        doubleHitStatus=text("",12,MUTED);doubleHitStatus.setTag("doubleHitStatus");card.addView(doubleHitStatus);
        input.setOnEditorActionListener((view,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){if(saveDoubleHitEdits(true)){input.clearFocus();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);}return true;}return false;});
        input.setOnFocusChangeListener((view,focused)->{if(!focused&&view==doubleHitTimeout)saveDoubleHitEdits();});
    }
    private boolean saveDoubleHitEdits(){return saveDoubleHitEdits(false);}
    private boolean saveDoubleHitEdits(boolean retryUnchanged){
        if(doubleHitTimeout==null)return true;
        int seconds;
        try{seconds=Integer.parseInt(doubleHitTimeout.getText().toString().trim());if(seconds<0||seconds>Protocol.MAX_DOUBLE_HIT_SECONDS)throw new NumberFormatException();}
        catch(NumberFormatException invalid){doubleHitError="Enter a whole number from 0 to "+Protocol.MAX_DOUBLE_HIT_SECONDS+" seconds.";refreshDoubleHitStatus(BreezeService.current);return false;}
        doubleHitError="";
        boolean edited=seconds!=Settings.doubleHitTimeout(this);
        if(edited){
            try{Settings.saveDoubleHitTimeout(this,seconds);}catch(RuntimeException failure){doubleHitError="Not saved: "+(failure.getMessage()==null?"Try again.":failure.getMessage());refreshDoubleHitStatus(BreezeService.current);return false;}
        }
        if(edited||retryUnchanged){BreezeService controller=BreezeService.current;if(controller!=null)controller.applyDoubleHitTimeout();}
        refreshDoubleHitStatus(BreezeService.current);return true;
    }
    private void refreshDoubleHitStatus(BreezeService controller){
        if(doubleHitStatus==null)return;
        String message=!doubleHitError.isEmpty()?doubleHitError:controller==null||!controller.visibleForBluetooth()||!controller.ready?"Saved on phone · will be sent when the controller connects":controller.sensorFirmware&&!controller.doubleHitSupported?"Saved on phone · update the controller firmware to use double hits":controller.doubleHitStatus==null||controller.doubleHitStatus.isEmpty()?"Saved on phone":controller.doubleHitStatus;
        textIfChanged(doubleHitStatus,message);
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
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==BLE_PERMISSION_REQUEST){if(!hasBluetoothPermission())pendingBluetoothAction=0;else if(pendingBluetoothAction!=0&&canUseBluetooth())connect(pendingBluetoothAction==2);}if(r==VOICE_PERMISSION_REQUEST){int action=pendingVoiceAction;pendingVoiceAction=0;if(!RecordingVoiceController.canStartListening(this)){if(action==1||action==2)startRecordingWithVoice(action==2);return;}if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){if(action==1||action==2)startRecordingWithVoice(action==2);else if(action==3)resumeRecordingVoice();}else error("Microphone permission is needed for voice commands. Allow it in Android Settings → Apps → Breeze → Permissions, then try again.");}}
    private boolean service(){if(!canUseBluetooth()||BreezeService.current==null||!BreezeService.current.ready){toast("Connect to your Breeze controller first.");return false;}return true;}
    private String temperature(double c){return Double.isFinite(c)?String.format(Locale.US,"%.1f°%s",fahrenheit?c*1.8+32:c,fahrenheit?"F":"C"):"—";}
    private String date(long t){return new SimpleDateFormat("MMM d, yyyy · h:mm a",Locale.US).format(new Date(t));}
    private String humidity(double h){return Double.isFinite(h)?String.format(Locale.US,"%.1f%%",h):"—";}
    private void refreshVoiceStatus(){
        if(bedtimeStatus==null)return;
        boolean disabled=RecordingVoiceController.isListeningDisabled(this),powerBlocked=RecordingVoiceController.isPowerBlocked(this);
        BedtimeVoiceService voice=BedtimeVoiceService.current;
        boolean microphone=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        String result=voice==null?RecordingVoiceController.lastResult:voice.lastHeard;
        boolean showResult=!disabled&&!powerBlocked&&!result.isEmpty()&&!result.startsWith("Saved ")&&!result.startsWith("Queued ");
        String status=disabled?"Listening disabled until you uncheck Disable listening.":RecordingVoiceController.isSuppressed()?RecordingVoiceController.status():powerBlocked?"Listening paused · plug in the phone to listen.":voice==null?(!microphone?"Allow microphone access to use voice commands.":RecordingVoiceController.status()):voice.status;
        bedtimeStatus.setText(status+(showResult?"\n"+result:""));bedtimeButton.setEnabled(!disabled&&(voice!=null||!powerBlocked));
        bedtimeButton.setVisibility(!disabled?View.VISIBLE:View.GONE);
        bedtimeButton.setText(voice!=null?"Pause listening":powerBlocked?"Waiting for power":!microphone?"Allow microphone":RecordingVoiceController.isSuppressed()?"Resume listening":"Start listening");
        if(disableListening!=null&&disableListening.isChecked()!=disabled)disableListening.setChecked(disabled);
        boolean pluggedOnly=RecordingVoiceController.isOnlyListenWhilePluggedIn(this);
        if(onlyListenWhilePluggedIn!=null&&onlyListenWhilePluggedIn.isChecked()!=pluggedOnly)onlyListenWhilePluggedIn.setChecked(pluggedOnly);
    }
    private void refresh(){
        if(activityDestroyed)return;
        requestSavedHistory();
        refreshVoiceStatus();
        BreezeService b=BreezeService.current;boolean fresh=b!=null&&b.fresh();Protocol.Reading r=fresh?b.latest:null;
        refreshDoubleHitStatus(b);
        if(connection!=null){String message=b==null||!b.visibleForBluetooth()?"○  Controller disconnected":fresh?"●  Connected":"○  "+(b.ready?"Waiting for live readings…":b.status+(b.foundDevices().isEmpty()?"":" · "+b.foundDevices().size()+" found"));if(liveReadings!=null){if(b!=null&&new DreoAccountStore(this).hasAccount())message+="\n"+b.dreoSummary();else if(!fresh||!b.freshFan()||!b.fan.synced())message+="\nShowing last fan setting · Done confirms it";else message+=" · Fan estimate";}textIfChanged(connection,message);}
        if(notice!=null){String message=liveReadings!=null&&!homeMessage.isEmpty()?homeMessage:b==null?"":liveReadings!=null&&b.freshFan()&&b.fan.held()?"Waiting for the fan's display to turn off, then sending.":r!=null&&!r.storageReady()?"Controller storage unavailable • recording paused.\n"+b.transfer:b.lastError;textIfChanged(notice,message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
        if(probeValue!=null){probeValue.setText(temperature(r==null?Double.NaN:r.probe));secondProbeValue.setText(temperature(b!=null&&b.freshFan()?b.fan.secondProbe:Double.NaN));thirdProbeValue.setText(temperature(r==null?Double.NaN:r.thirdProbe));climate.setText(temperature(r==null?Double.NaN:r.room));humidityValue.setText(humidity(r==null?Double.NaN:r.humidity));}
        refreshFan(b,r);
        refreshCurrentFanSetting(b);
        if(liveReadings!=null)refreshHome(b,r,fresh);
        if(logicStatus!=null){
            boolean usable=b!=null&&!b.logicNeedsFirmware&&b.freshLogic();Protocol.LogicState state=usable?b.logic:null;
            StringBuilder message=new StringBuilder(b==null?"Waiting for the controller":b.logicNeedsFirmware?"The controller firmware is older than this app. Update it to use Logic.":b.logicStatus==null||b.logicStatus.isEmpty()?"Connecting…":b.logicStatus);
            if(state!=null){
                // The controller reports zero for a reading it does not have; its error says which one.
                message.append("\nRoom ").append(state.error==31?"—":String.format(Locale.US,"%.1f°",state.roomF)).append(" · body avg ").append(Double.isFinite(state.averageBodyF)?String.format(Locale.US,"%.1f°",state.averageBodyF):"—");
                Protocol.FanState fan=b.freshFan()?b.fan:null;
                message.append("\n").append(state.automatic()?"Target "+(state.target==0?"Off":FanSetting.label(state.target))+(Double.isFinite(state.score)?String.format(Locale.US," (range position %.1f of 10)",state.score):"")+" · ":"").append("fan est. ").append(fan==null||!fan.synced()?"unknown":fan.speed==0?"Off":FanSetting.label(fan.speed));
            }
            logicStatus.setText(message);
        }
        if(setupLogicButton!=null){boolean known=b!=null&&!b.logicNeedsFirmware&&b.freshLogic();setupLogicButton.setText(!known?"Logic":b.logic.automatic()?"Pause Logic":"Resume Logic");boolean ready=b!=null&&b.homeLogicReady();setupLogicButton.setEnabled(ready);setupLogicButton.setAlpha(ready?1f:0.5f);}
        refreshLogicApply(b);
        if(sessionButton!=null){textIfChanged(sessionButton,r!=null&&r.active()?"Stop recording":RecordingVoiceController.isStarting()?"Starting recording…":"Start recording");if(sessionButton!=homeTouchedButton)sessionButton.setEnabled(fresh&&b.sensorFirmware&&(!"Home".equals(tab)||!b.irFirmware||b.manualIrReady())&&(r.active()||r.storageReady()&&!RecordingVoiceController.isStarting()));}
        if(status!=null){status.setText(r==null?"Waiting for controller":!b.sensorFirmware?"Update the controller to sensor firmware":(r.active()?"Recording on controller":"Live readings · recording stopped")+(r.error==0?"":" · "+Protocol.error(r.error)));transfer.setText(b==null?"Records stay on the controller until downloaded and confirmed.":b.transfer);}
        if(graph!=null&&savedHistory!=null&&savedHistory.includesGraph&&savedHistory.selected()==selected&&renderedHistory!=savedHistory){
            graph.data(selected,savedHistory.points,savedHistory.weather,fahrenheit,visibleSeries);
            graph.dreoTemperatures(savedHistory.dreo);graph.feelings(savedHistory.feelings);renderedHistory=savedHistory;
            if(weatherLocationLabel!=null)textIfChanged(weatherLocationLabel,savedHistory.weatherLocation.isEmpty()?Settings.prefs(this).getString("place","Choose location in Setup"):savedHistory.weatherLocation);
        }
        refreshSavedFeelings();
    }
    /** Unsent edits and the outcome of the last Apply stay visible; controller status never overwrites them. */
    private void refreshLogicApply(BreezeService controller){
        if(logicApplyLabel==null)return;
        String note=!logicFormError.isEmpty()?logicFormError:Settings.logicDraftPending(this)?"Changes not yet applied":controller==null?logicFormNote:controller.logicApplyNote;
        logicApplyLabel.setText(note);logicApplyLabel.setVisibility(note.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void homeEnabled(Button button,boolean enabled){if(button!=homeTouchedButton)button.setEnabled(enabled);}
    private void refreshHome(BreezeService controller,Protocol.Reading reading,boolean fresh){
        boolean manual=controller!=null&&controller.manualIrReady();liveReadings.showReading(reading,controller!=null&&controller.freshFan()?controller.fan:null,fahrenheit);
        for(Button button:homeRemoteButtons)homeEnabled(button,manual);
        boolean powerKnown=controller!=null&&controller.freshFan()&&controller.fan.synced();
        boolean powerOn=powerKnown&&controller.fan.speed>0;
        Button power=homeRemoteButtons.get(0);homeButtonColor(power,powerOn?POWER_ON:0xff2c4436);power.setTextColor(powerOn?BG:TEAL);power.setStateDescription(powerKnown?(powerOn?"On":"Off"):"Unknown");
        boolean known=controller!=null&&controller.freshLogic();boolean active=known&&controller.logic.automatic();homeButtonColor(homeLogicButton,active?0xff78b5e1:0xffb59cdb);homeLogicButton.setTextColor(BG);homeLogicButton.setContentDescription(known?(active?"Logic active":"Logic paused"):"Logic waiting for controller");homeEnabled(homeLogicButton,controller!=null&&controller.homeLogicReady());
    }
    private void refreshFan(BreezeService b,Protocol.Reading r){
        if(dreoStatus!=null)dreoStatus.setText((b==null?"Using an estimate. Connect the controller to synchronize with Dreo.":b.dreoStatus())+"\n\n"+DreoCorrections.summary(this));
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
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request!=EXPORT_REQUEST&&request!=FEELING_EXPORT_REQUEST)return;
        final Uri uri=data.getData();final long session=exportSession;
        worker.execute(()->{
            String failure=null;
            try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new java.io.IOException("Cannot open export destination");
                if(session<0)throw new java.io.IOException("Choose a session to export again.");
                out.write((request==FEELING_EXPORT_REQUEST?store.feelingsCsv(session):store.csv(session)).getBytes(StandardCharsets.UTF_8));
            }catch(Exception error){failure=error.getMessage();}
            final String message=failure;
            handler.post(()->{if(activityDestroyed)return;if(message==null)toast("CSV exported.");else error(message);});
        });
    }
    private void toast(String message){if("Home".equals(tab)){homeMessage=message;refresh();return;}Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void manualFeedback(String message){if("Home".equals(tab))return;if(manualFeedback!=null)manualFeedback.cancel();manualFeedback=Toast.makeText(this,message,Toast.LENGTH_SHORT);manualFeedback.show();}
    private void error(String message){if("Home".equals(tab)){homeMessage=message==null?"Check your entries.":message;refresh();return;}if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Breeze").setMessage(message==null?"Check your entries.":message).setPositiveButton("OK",null).show();}
}
