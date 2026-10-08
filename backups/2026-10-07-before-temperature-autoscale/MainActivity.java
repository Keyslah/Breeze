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

public class MainActivity extends Activity {
    private static final int BG=0xff101d18,INK=0xffe5eee6,MUTED=0xffa6b9ac,TEAL=0xff9ac5a4,WHITE=0xff20352b;
    private static final int BLE_PERMISSION_REQUEST=7,EXPORT_REQUEST=42,VOICE_PERMISSION_REQUEST=43;
    private LinearLayout shell,content,nav;
    private Store store;
    private String tab="Home";
    private boolean fahrenheit=true;
    private int visibleSeries=GraphView.ALL_SERIES;
    private long selected=-1;
    private TextView connection,probeValue,secondProbeValue,climate,humidityValue,status,transfer,graphHeading,notice,fanStatus,learningStatus,testStatus;
    private final List<Button> remoteButtons=new ArrayList<>(),targetButtons=new ArrayList<>(),learnButtons=new ArrayList<>(),testButtons=new ArrayList<>(),clearButtons=new ArrayList<>();
    private final List<TextView> learnedLabels=new ArrayList<>();
    private Button startProbeTest,onProbes,offProbes,stopProbeTest;
    private Button sessionButton;
    private GraphView graph;
    private TextView bedtimeStatus;
    private Button bedtimeButton;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();}};
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1500);}};
    private long exportSession=-1;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);
        if(!Settings.prefs(this).contains("place"))Settings.prefs(this).edit().putString("query","53214").putString("place","Milwaukee, Wisconsin • ZIP 53214 (city weather)").putString("latitude","43.0389").putString("longitude","-87.90647").apply();
        fahrenheit=true;visibleSeries=Settings.prefs(this).getInt("historyVisibleSeries",GraphView.ALL_SERIES)&GraphView.ALL_SERIES;
        if(state!=null){tab=state.getString("tab","Home");if(!Arrays.asList("Home","Fan","Learn Remote","History","Setup").contains(tab))tab="Home";selected=state.getLong("selected",-1);exportSession=state.getLong("exportSession",-1);}
        build();
        if(BreezeService.current==null&&Settings.prefs(this).contains("device")&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)startForegroundService(new Intent(this,BreezeService.class));
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("tab",tab);b.putLong("selected",selected);b.putLong("exportSession",exportSession);}
    @Override protected void onResume(){super.onResume();IntentFilter filter=new IntentFilter(BreezeService.UPDATE);filter.addAction(BedtimeVoiceService.UPDATE);registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);handler.post(tick);}
    @Override protected void onPause(){handler.removeCallbacks(tick);unregisterReceiver(updates);super.onPause();}
    @Override protected void onDestroy(){worker.shutdown();store.close();super.onDestroy();}
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
        bedtimeStatus=null;bedtimeButton=null;
        connection=null;probeValue=null;secondProbeValue=null;climate=null;humidityValue=null;graph=null;graphHeading=null;status=null;transfer=null;notice=null;sessionButton=null;fanStatus=null;learningStatus=null;testStatus=null;startProbeTest=null;onProbes=null;offProbes=null;stopProbeTest=null;remoteButtons.clear();targetButtons.clear();learnButtons.clear();testButtons.clear();clearButtons.clear();learnedLabels.clear();
        shell=col();shell.setBackground(new GraniteDrawable());shell.setPadding(dp(20),0,dp(20),0);shell.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20),bars.top,dp(20),bars.bottom);return insets;});setContentView(shell);
        getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout header=row();TextView brand=bold("breeze",34,TEAL);header.addView(brand,new LinearLayout.LayoutParams(0,dp(64),1));TextView units=bold("°F",16,TEAL);units.setGravity(Gravity.CENTER);header.addView(units,new LinearLayout.LayoutParams(dp(58),dp(40)));shell.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);content=col();scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!tab.equals("Home")){content.addView(text(switch(tab){case "History"->"Every reading. Every night.";case "Fan","Learn Remote"->"Dreo remote and probe testing";default->"Connection and recording";},16,MUTED));gap(content,16);}
        switch(tab){case "Fan":fanPage();break;case "Learn Remote":learnRemote();break;case "History":history();break;case "Setup":setup();break;default:home();}
        gap(content,14);nav=row();for(String title:new String[]{"Home","Fan","History","Setup"}){TextView t=bold(title,14,title.equals(tab)||(title.equals("Fan")&&tab.equals("Learn Remote"))?TEAL:MUTED);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(18),0,dp(18));t.setOnClickListener(v->{tab=title;build();});nav.addView(t,new LinearLayout.LayoutParams(0,dp(58),1));}shell.addView(nav);refresh();
    }
    private TextView readingCard(String label){
        LinearLayout c=card();c.setPadding(dp(22),dp(20),dp(22),dp(20));
        c.addView(text(label,16,MUTED));TextView value=bold("—",46,INK);value.setFontFeatureSettings("tnum");c.addView(value);return value;
    }
    private void home(){gap(content,18);bedtimeVoiceCard();probeValue=readingCard("Under-sheet · D0");secondProbeValue=readingCard("Under-sheet · D1");climate=readingCard("Room temperature");humidityValue=readingCard("Humidity");sessionButton=button("Start recording",true,this::sessionAction);content.addView(sessionButton,new LinearLayout.LayoutParams(-1,dp(48)));notice=text("",13,0xffedb597);content.addView(notice);}
    private void bedtimeVoiceCard(){
        LinearLayout voice=card();voice.addView(bold("Bedtime voice test",21,INK));
        voice.addView(text("Start here, wait for the spoken ready message, then lock your phone. Say \"Breeze, warm seven\" or \"Breeze, cold three\" in one sentence.",14,INK));
        bedtimeStatus=text("Not listening",13,TEAL);voice.addView(bedtimeStatus);
        bedtimeButton=button("Start bedtime listening",true,this::toggleBedtimeVoice);voice.addView(bedtimeButton,new LinearLayout.LayoutParams(-1,dp(48)));
        voice.addView(text("It replies \"Heard warm seven. Test only.\" No history entries are saved during this test. Say \"Breeze, stop listening\" to stop. Turn media volume up so you can hear it. Stops automatically after 12 hours.",12,MUTED));
    }
    private void toggleBedtimeVoice(){
        if(BedtimeVoiceService.current!=null){stopService(new Intent(this,BedtimeVoiceService.class));refresh();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            List<String> permissions=new ArrayList<>();permissions.add(Manifest.permission.RECORD_AUDIO);
            if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(permissions.toArray(new String[0]),VOICE_PERMISSION_REQUEST);return;
        }
        startBedtimeVoice();
    }
    private void startBedtimeVoice(){
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()){error("Start bedtime listening while the phone is unlocked, then lock it after the ready message.");return;}
        try{startForegroundService(new Intent(this,BedtimeVoiceService.class));if(bedtimeStatus!=null)bedtimeStatus.setText("Preparing offline voice test…");}
        catch(RuntimeException failure){error("Could not start bedtime listening. Keep Breeze open and try again. "+failure.getMessage());}
    }
    private void sendFan(int action,int value,int argument){if(service())BreezeService.current.fanCommand(action,value,argument);}
    private void fanConnection(LinearLayout c){connection=text("Controller connection",14,TEAL);c.addView(connection);notice=text("",13,0xffedb597);c.addView(notice);addButton(c,"Connect controller",false,this::connect);}
    private void fanPage(){
        LinearLayout c=card();c.addView(bold("Dreo fan",22,INK));fanConnection(c);fanStatus=text("",14,INK);c.addView(fanStatus);c.addView(text("IR has no fan feedback. Speed is an estimate. Sync again after using the fan's buttons, its original remote, or after a power loss.",13,MUTED));
        addButton(c,"Sync / set current speed",false,this::syncFan);addButton(c,"Forget estimated state",false,()->sendFan(Protocol.FORGET_FAN,0,0));
        LinearLayout manual=card();manual.addView(bold("Manual controls",20,INK));
        for(int slot=0;slot<6;slot++){final int chosen=slot;Button b=button(Protocol.COMMANDS[slot],slot==0,()->sendFan(Protocol.SEND_IR,chosen,0));manual.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));gap(manual,6);remoteButtons.add(b);}
        manual.addView(text("Target speed · requires a synchronized estimate",13,MUTED));
        for(int rowIndex=0;rowIndex<3;rowIndex++){LinearLayout r=row();for(int col=1;col<=3;col++){int speed=rowIndex*3+col;Button b=button(""+speed,false,()->sendFan(Protocol.TARGET_SPEED,speed,0));r.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));targetButtons.add(b);}manual.addView(r);gap(manual,5);}
        addButton(manual,"Cancel speed change",false,()->sendFan(Protocol.FORGET_FAN,0,0));addButton(manual,"Learn Remote",true,()->{tab="Learn Remote";build();});
        LinearLayout auto=card();auto.addView(bold("Automatic control · off",20,INK));auto.addView(text("No temperature rules yet. First collect the two-probe contact test data, then decide how fan control should respond.",14,MUTED));
        LinearLayout test=card();test.addView(bold("Probe contact test",20,INK));testStatus=text("",14,TEAL);test.addView(testStatus);test.addView(text("1. Start a baseline recording.\n2. Lie on the probes and tap the lying marker. Wait until their temperatures settle.\n3. Get off and tap the off marker. Keep recording as they cool.\n4. Stop, then inspect both curves in History.\n\nRaw readings are kept. No sudden-drop rejection or recovery rules are applied.",14,MUTED));
        startProbeTest=button("Start probe test",true,()->{if(service())BreezeService.current.startContactTest();});test.addView(startProbeTest,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,7);
        onProbes=button("Mark: lying on probes",false,()->sendFan(Protocol.MARK_TEST,2,0));test.addView(onProbes,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,7);
        offProbes=button("Mark: off probes",false,()->sendFan(Protocol.MARK_TEST,3,0));test.addView(offProbes,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,7);
        stopProbeTest=button("Stop recording",false,()->{if(service())BreezeService.current.control(Protocol.STOP_RECORDING,0);});test.addView(stopProbeTest,new LinearLayout.LayoutParams(-1,dp(48)));addButton(test,"View recorded graphs",false,()->{tab="History";build();});
    }
    private void syncFan(){
        LinearLayout form=col();form.setPadding(dp(20),dp(8),dp(20),dp(8));form.addView(text("Read the actual speed on the fan. It must be ON in Normal/manual mode.",15,INK));
        Spinner speed=new Spinner(this);speed.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"1","2","3","4","5","6","7","8","9"}));form.addView(speed);
        CheckBox confirm=new CheckBox(this);confirm.setText("Fan is on in Normal/manual mode");form.addView(confirm);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Set current speed estimate").setView(form).setPositiveButton("Sync",null).setNegativeButton("Cancel",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(!confirm.isChecked()){toast("Confirm the fan is on in Normal/manual mode.");return;}sendFan(Protocol.SYNC_SPEED,speed.getSelectedItemPosition()+1,1);dialog.dismiss();}));dialog.show();
    }
    private void learnRemote(){
        LinearLayout c=card();c.addView(bold("Learn Remote",22,INK));fanConnection(c);learningStatus=text("",14,INK);c.addView(learningStatus);c.addView(text("Point the Dreo remote at the receiver and press the requested button once. Learning may also operate the real fan. The old command stays saved until the new capture is stored successfully.",13,MUTED));
        c.addView(text("Raw replay carrier (kHz)",13,MUTED));EditText carrier=new EditText(this);carrier.setInputType(InputType.TYPE_CLASS_NUMBER);carrier.setText("38");c.addView(carrier);c.addView(text("38 kHz is the receiver's nominal frequency. It cannot measure the remote's carrier; verify replay on the fan.",12,MUTED));addButton(c,"Cancel learning",false,()->sendFan(Protocol.CANCEL_LEARN,0,0));
        for(int slot=0;slot<Protocol.COMMANDS.length;slot++){final int chosen=slot;LinearLayout item=card();item.addView(bold(Protocol.COMMANDS[slot],18,INK));TextView label=text("Not learned",13,MUTED);item.addView(label);learnedLabels.add(label);LinearLayout r=row();
            Button learn=button("Learn",false,()->{try{sendFan(Protocol.LEARN,chosen,Integer.parseInt(carrier.getText().toString().trim()));}catch(NumberFormatException e){error("Enter a carrier between 30 and 60 kHz.");}});
            Button test=button("Test",false,()->sendFan(Protocol.SEND_IR,chosen,0));Button clear=button("Clear",false,()->new AlertDialog.Builder(this).setTitle("Clear "+Protocol.COMMANDS[chosen]+"?").setMessage("This removes the saved command from the controller.").setPositiveButton("Clear",(d,w)->sendFan(Protocol.CLEAR_LEARNED,chosen,0)).setNegativeButton("Cancel",null).show());
            for(Button b:new Button[]{learn,test,clear})r.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));item.addView(r);learnButtons.add(learn);testButtons.add(test);clearButtons.add(clear);
        }
        addButton(content,"Back to fan",false,()->{tab="Fan";build();});
    }
    private void sessionAction(){if(!service())return;BreezeService b=BreezeService.current;if(!b.fresh()){toast("Wait for a fresh controller reading.");return;}if(b.latest.active())b.control(Protocol.STOP_RECORDING,0);else b.startSession();}
    private void history(){
        List<Store.Session> sessions=store.sessions();LinearLayout c=card();c.addView(bold("Your recorded nights",23,INK));c.addView(text("Stored on this phone after a confirmed controller download.",13,MUTED));if(sessions.isEmpty()){c.addView(text("No sessions yet. Connect your controller and download its records.",15,INK));addButton(c,"Connect controller",true,this::connect);return;}
        Store.Session chosen=sessions.get(0);for(Store.Session s:sessions)if(s.id==selected)chosen=s;selected=chosen.id;final Store.Session session=chosen;
        c.addView(bold(date(session.start)+" · "+session.count+" readings",16,INK));
        addButton(c,"Choose recorded night",false,this::chooseRecordedNight);

        c.addView(text("Started "+date(session.start)+"\n"+(session.end>0?"Ended "+date(session.end):"No end marker yet; session may be active or was interrupted by power loss."),12,MUTED));
        LinearLayout chart=card();LinearLayout chartTitle=row();graphHeading=bold("Recorded night",19,INK);chartTitle.addView(graphHeading,new LinearLayout.LayoutParams(0,-2,1));chartTitle.addView(button("Reset zoom",false,()->graph.resetZoom()),new LinearLayout.LayoutParams(-2,dp(48)));chart.addView(chartTitle);
        graph=new GraphView(this);chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(310)));
        for(int rowIndex=0;rowIndex<3;rowIndex++){LinearLayout choices=row();for(int colIndex=0;colIndex<2;colIndex++){
            final int series=rowIndex*2+colIndex;CheckBox choice=new CheckBox(this);choice.setText(GraphView.LABELS[series]);choice.setTextSize(12);choice.setTextColor(GraphView.COLORS[series]);choice.setButtonTintList(android.content.res.ColorStateList.valueOf(GraphView.COLORS[series]));choice.setMinHeight(dp(48));choice.setChecked((visibleSeries&(1<<series))!=0);
            choice.setOnCheckedChangeListener((button,checked)->{if(checked)visibleSeries|=1<<series;else visibleSeries&=~(1<<series);Settings.prefs(this).edit().putInt("historyVisibleSeries",visibleSeries).apply();graph.visibleSeries(visibleSeries);});
            choices.addView(choice,new LinearLayout.LayoutParams(0,-2,1));
        }chart.addView(choices);}
        chart.addView(text("Pinch to zoom, drag sideways to pan, and tap for a reading. Double-tap or Reset zoom shows the whole night. Temperature uses the left axis; humidity uses the right. Outdoor traces are dashed.",12,MUTED));

        LinearLayout feelings=card();feelings.addView(new FeelingEntriesView(this,store,session,this::refresh));

        LinearLayout outdoor=card();outdoor.addView(bold("Add the outside story",19,INK));outdoor.addView(text("Hourly outdoor model data from Open-Meteo. Add it in the morning; no API key needed.",13,MUTED));String location=store.weatherLocation(selected);outdoor.addView(text(location.isEmpty()?"Weather location: "+Settings.prefs(this).getString("place","53214 • choose location in Setup"):"Weather: "+location,13,TEAL));addButton(outdoor,"Fetch outdoor weather",true,()->fetchWeather(session));addButton(outdoor,"Export session as CSV",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,EXPORT_REQUEST);});
        outdoor.addView(text("Open-Meteo · CC BY 4.0\nOutdoor data is modeled weather, not a sensor at your home.",11,MUTED));
        LinearLayout readings=card();readings.addView(bold("Latest readings in this session",18,INK));List<Store.Point> data=store.points(selected);for(int i=Math.max(0,data.size()-12);i<data.size();i++){Store.Point p=data.get(i);readings.addView(text(new SimpleDateFormat("h:mm:ss a",Locale.US).format(new Date(p.time))+"   "+temperature(p.probe)+" / "+temperature(p.secondProbe)+"   "+temperature(p.room)+"   "+humidity(p.humidity),14,INK));}
    }
    private void chooseRecordedNight(){
        List<Store.Session> nights=store.sessions();if(nights.isEmpty()){build();return;}
        String[] labels=new String[nights.size()];int current=0;
        for(int i=0;i<nights.size();i++){Store.Session night=nights.get(i);labels[i]=date(night.start)+"\n"+night.count+" readings"+(night.end==0?" · open / interrupted":" · ended "+new SimpleDateFormat("h:mm a",Locale.US).format(new Date(night.end)));if(night.id==selected)current=i;}
        new AlertDialog.Builder(this).setTitle("Choose recorded night ("+nights.size()+")").setSingleChoiceItems(labels,current,(dialog,which)->{selected=nights.get(which).id;dialog.dismiss();build();}).setNegativeButton("Cancel",null).show();
    }
    private void setup(){
        LinearLayout c=card();c.addView(bold("Controller connection",21,INK));connection=text("Disconnected",14,TEAL);c.addView(connection);notice=text("",12,0xffedb597);c.addView(notice);two(c,"Connect / choose",this::connect,"Disconnect phone",()->{stopService(new Intent(this,BreezeService.class));toast("Phone disconnected. The controller keeps recording.");handler.postDelayed(this::refresh,400);});
        c.addView(text("The XIAO owns the recording. Once records are committed to this phone, it is told to erase only those confirmed records.",13,MUTED));addButton(c,"Download controller records",false,()->{if(service())BreezeService.current.download();});addButton(c,"Initialize new controller storage",false,()->new AlertDialog.Builder(this).setTitle("Format controller log storage?").setMessage("Only for first setup when storage cannot mount. This permanently erases the controller's log partition. Never use it to fix a controller holding records you need.").setPositiveButton("Erase and initialize",(d,w)->{if(service())BreezeService.current.control(6,0);}).setNegativeButton("Cancel",null).show());
        LinearLayout recording=card();recording.addView(bold("Sensor recording",21,INK));status=text("Connect your controller",14,MUTED);recording.addView(status);sessionButton=button("Start recording",true,this::sessionAction);recording.addView(sessionButton,new LinearLayout.LayoutParams(-1,dp(48)));transfer=text("",13,MUTED);recording.addView(transfer);recording.addView(text("Both body probes, SHT40 temperature and humidity every 10 seconds. Readings remain live when recording is stopped.",13,MUTED));
        LinearLayout weather=card();weather.addView(bold("Outdoor weather location",21,INK));EditText query=new EditText(this);query.setSingleLine();query.setText(Settings.prefs(this).getString("query","53214"));weather.addView(query);weather.addView(text(Settings.prefs(this).getString("place","Search ZIP 53214 and choose its location."),13,MUTED));addButton(weather,"Search city or ZIP",true,()->searchPlace(query.getText().toString().trim()));
        LinearLayout hardware=card();hardware.addView(bold("Sensors",21,INK));hardware.addView(text("Body temperatures · D0 and D1\nRoom temperature + humidity · SHT40\nD4 · SDA    D5 · SCL\nIR receiver OUT · D6\nIR transmitter transistor base · D7 via 1 kΩ\n\nEach body probe divider: 3.3 V → 10 kΩ resistor → D0 or D1 → thermistor → GND. SHT40 uses 3.3 V and GND.\n\nAfter controller power loss, reconnect the phone to set its clock before starting another recording.",14,INK));
    }
    private void connect(){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},BLE_PERMISSION_REQUEST);return;}
        BreezeService b=BreezeService.current;if(b!=null&&b.fresh()){toast("Controller connected.");return;}if(b!=null){List<String> devices=b.foundDevices();if(!devices.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose Breeze controller").setItems(devices.stream().map(s->"Breeze Sensors · "+s).toArray(String[]::new),(d,w)->b.connect(devices.get(w))).setNegativeButton("Scan again",(d,w)->b.scan()).show();return;}b.scan();toast("Searching. Tap Connect / choose again when found.");}else{startForegroundService(new Intent(this,BreezeService.class));toast("Searching for Breeze Sensors. Tap Connect again to choose it.");}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==BLE_PERMISSION_REQUEST&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)connect();if(r==VOICE_PERMISSION_REQUEST){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startBedtimeVoice();else error("Microphone permission is needed for the voice test. You can allow it in Android Settings → Apps → Breeze → Permissions.");}}
    private boolean service(){if(BreezeService.current==null||!BreezeService.current.ready){toast("Connect to your Breeze controller first.");return false;}return true;}
    private String temperature(double c){return Double.isFinite(c)?String.format(Locale.US,"%.1f°%s",fahrenheit?c*1.8+32:c,fahrenheit?"F":"C"):"—";}
    private String date(long t){return new SimpleDateFormat("MMM d, yyyy · h:mm a",Locale.US).format(new Date(t));}
    private String humidity(double h){return Double.isFinite(h)?String.format(Locale.US,"%.1f%%",h):"—";}
    private void refresh(){
        if(bedtimeStatus!=null){BedtimeVoiceService voice=BedtimeVoiceService.current;bedtimeStatus.setText(voice==null?BedtimeVoiceService.lastStatus:voice.status+(voice.lastHeard.isEmpty()?"":"\nLast heard: "+voice.lastHeard));bedtimeButton.setText(voice==null?"Start bedtime listening":"Stop bedtime listening");}
        BreezeService b=BreezeService.current;boolean fresh=b!=null&&b.fresh();Protocol.Reading r=fresh?b.latest:null;
        if(connection!=null)connection.setText(b==null?"○  Controller disconnected":"●  "+(b.ready?"Connected":b.status+(b.foundDevices().isEmpty()?"":" · "+b.foundDevices().size()+" found")));
        if(notice!=null){String message=b==null?"":r!=null&&!r.storageReady()?"Controller storage unavailable • recording paused.\n"+b.transfer:b.lastError;notice.setText(message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
        if(probeValue!=null){probeValue.setText(temperature(r==null?Double.NaN:r.probe));secondProbeValue.setText(temperature(b!=null&&b.freshFan()?b.fan.secondProbe:Double.NaN));climate.setText(temperature(r==null?Double.NaN:r.room));humidityValue.setText(humidity(r==null?Double.NaN:r.humidity));}
        refreshFan(b,r);
        if(sessionButton!=null){sessionButton.setText(r!=null&&r.active()?"Stop recording":"Start recording");sessionButton.setEnabled(fresh&&b.sensorFirmware&&(r.active()||r.storageReady()));}
        if(status!=null){status.setText(r==null?"Waiting for controller":!b.sensorFirmware?"Update the controller to sensor firmware":(r.active()?"Recording on controller":"Live readings · recording stopped")+(r.error==0?"":" · "+Protocol.error(r.error)));transfer.setText(b==null?"Records stay on the controller until downloaded and confirmed.":b.transfer);}
        if(graph!=null){graph.data(selected,selected<0?new ArrayList<>():store.points(selected),selected<0?new ArrayList<>():store.weather(selected),fahrenheit,visibleSeries);graph.feelings(selected<0?new ArrayList<>():store.feelings(selected));}
    }
    private void refreshFan(BreezeService b,Protocol.Reading r){
        Protocol.FanState f=b!=null&&b.freshFan()?b.fan:null;boolean available=f!=null&&!b.fanWritePending();boolean learning=f!=null&&f.learning!=255;
        if(fanStatus!=null)fanStatus.setText(f==null?"Waiting for IR-controller telemetry":(f.synced()?"Estimated speed "+f.speed+(f.busy()?" → "+f.target:""):"Fan speed unknown · sync with the physical fan")+(f.error==0?"":"\n"+Protocol.error(f.error)));
        for(Button button:remoteButtons)button.setEnabled(available&&!learning);
        for(Button button:targetButtons)button.setEnabled(available&&!learning&&f.synced()&&!f.busy());
        if(learningStatus!=null){String result=f==null?"Waiting for IR-controller telemetry":learning?"Point the Dreo remote at the ESP32 and press "+Protocol.COMMANDS[f.learning]+".":switch(f.learnState){case 2->"Saved "+(f.resultSlot<Protocol.COMMANDS.length?Protocol.COMMANDS[f.resultSlot]:"command")+" on controller";case 3->"Learning timed out. Previous command retained.";case 4->"Learning canceled.";case 5->"Capture/save failed. "+Protocol.error(f.error);default->"Choose a button to learn.";};learningStatus.setText(result+(f!=null&&f.error!=0?"\n"+Protocol.error(f.error):""));}
        for(int i=0;i<learnButtons.size();i++){boolean learned=f!=null&&f.has(i);learnedLabels.get(i).setText(f==null?"Waiting for controller":learned?"Learned":"Not learned");learnButtons.get(i).setText(learned?"Relearn":"Learn");learnButtons.get(i).setEnabled(available);testButtons.get(i).setEnabled(available&&!learning&&learned);clearButtons.get(i).setEnabled(available&&!learning&&learned);}
        if(testStatus!=null){boolean recording=r!=null&&r.active();testStatus.setText(recording?"Recording raw probe data"+(f!=null&&f.phase>0?" · "+Store.phaseName(f.phase):""):"Ready to record raw probe data");startProbeTest.setEnabled(available&&!recording);onProbes.setEnabled(available&&recording&&f.phase>0);offProbes.setEnabled(available&&recording&&f.phase>0);stopProbeTest.setEnabled(available&&recording);}
    }
    private void searchPlace(String query){if(query.isEmpty())return;toast("Looking up "+query+"…");worker.execute(()->{try{List<Weather.Place> p=Weather.search(query);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(p.isEmpty()){error("No matching location. Try the city name.");return;}new AlertDialog.Builder(this).setTitle("Choose weather location").setItems(p.stream().map(x->x.label).toArray(String[]::new),(d,w)->{Weather.Place place=p.get(w);Settings.prefs(this).edit().putString("query",query).putString("place",place.label).putString("latitude",""+place.lat).putString("longitude",""+place.lon).apply();build();}).show();});}catch(Exception e){runOnUiThread(()->error(e.getMessage()));}});}
    private void fetchWeather(Store.Session s){String lat=Settings.prefs(this).getString("latitude","");if(lat.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose your weather location").setMessage("Search ZIP 53214 in Setup first.").setPositiveButton("Open Setup",(d,w)->{tab="Setup";build();}).show();return;}Weather.Place p=new Weather.Place();p.lat=Double.parseDouble(lat);p.lon=Double.parseDouble(Settings.prefs(this).getString("longitude","0"));p.label=Settings.prefs(this).getString("place","");toast("Fetching outdoor weather…");worker.execute(()->{try{int count=Weather.fetch(store,s,p);runOnUiThread(()->{toast("Added "+count+" hourly outdoor readings.");if(!isDestroyed())build();});}catch(Exception e){runOnUiThread(()->error("Weather unavailable: "+e.getMessage()));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==EXPORT_REQUEST){try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException("Cannot open export destination");if(exportSession<0)throw new java.io.IOException("Choose a session to export again.");out.write(store.csv(exportSession).getBytes(StandardCharsets.UTF_8));toast("CSV exported.");}catch(Exception e){error(e.getMessage());}}}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Breeze").setMessage(message==null?"Check your entries.":message).setPositiveButton("OK",null).show();}
}
