package com.aaron.breeze;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG=0xfff4f5ef,INK=0xff233831,MUTED=0xff758078,TEAL=0xff126b60,WHITE=0xffffffff;
    private LinearLayout shell,content,nav,connectionCard;
    private final List<Button> fanButtons=new ArrayList<>();
    private static final int[] FAN_ORDER={0,3,2,1};
    private Store store;
    private String tab="Tonight";
    private boolean demo=false,fahrenheit=true,showHumidity=false;
    private long selected=-1,lastRefresh=0;
    private TextView connection,probeValue,climate,status,transfer,ruleSummary,graphHeading,notice;
    private Button sessionButton,motorCenter,motorMinus,motorPlus,motorRelease;
    private TextView motorPosition;
    private int motorStep=2;
    private GraphView graph;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();}};
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1500);}};
    private String exportText="";
    private long exportSession=-1;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);if(!Settings.prefs(this).contains("place"))Settings.prefs(this).edit().putString("query","53214").putString("place","Milwaukee, Wisconsin • ZIP 53214 (city weather)").putString("latitude","43.0389").putString("longitude","-87.90647").apply();fahrenheit=Settings.prefs(this).getBoolean("fahrenheit",true);if(state!=null){tab=state.getString("tab","Tonight");selected=state.getLong("selected",-1);demo=state.getBoolean("demo",false);exportSession=state.getLong("exportSession",-1);motorStep=state.getInt("motorStep",2);}build();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("tab",tab);b.putLong("selected",selected);b.putBoolean("demo",demo);b.putLong("exportSession",exportSession);b.putInt("motorStep",motorStep);}
    @Override protected void onResume(){super.onResume();registerReceiver(updates,new IntentFilter(BreezeService.UPDATE),Context.RECEIVER_NOT_EXPORTED);handler.post(tick);}
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
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(primary?WHITE:TEAL);b.setBackground(background(primary?TEAL:0xffe8eee5,14));b.setMinHeight(dp(48));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setOnClickListener(v->action.run());return b;}
    private void addButton(LinearLayout p,String title,boolean primary,Runnable action){Button b=button(title,primary,action);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.topMargin=dp(8);p.addView(b,lp);}
    private void two(LinearLayout p,String a,Runnable aa,String b,Runnable bb){LinearLayout r=row();Button x=button(a,false,aa),y=button(b,false,bb);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),dp(8),dp(3),0);r.addView(x,lp);r.addView(y,new LinearLayout.LayoutParams(lp));p.addView(r);}
    private void build(){
        motorPosition=null;connection=null;connectionCard=null;probeValue=null;graph=null;status=null;transfer=null;notice=null;fanButtons.clear();
        shell=col();shell.setBackgroundColor(BG);shell.setPadding(dp(20),0,dp(20),0);shell.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20),bars.top,dp(20),bars.bottom);return insets;});setContentView(shell);
        LinearLayout header=row();TextView brand=bold("breeze",34,TEAL);header.addView(brand,new LinearLayout.LayoutParams(0,dp(64),1));Button units=button(fahrenheit?"°F":"°C",false,()->{fahrenheit=!fahrenheit;Settings.prefs(this).edit().putBoolean("fahrenheit",fahrenheit).apply();build();});header.addView(units,new LinearLayout.LayoutParams(dp(58),dp(40)));shell.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);content=col();scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content.addView(text(switch(tab){case "Rules"->"Your comfort, your settings.";case "History"->"Every reading. Every night.";case "Setup"->"Make it yours.";case "Motor test"->"One small movement at a time.";default->"A little cooler. A little calmer.";},16,MUTED));gap(content,16);
        switch(tab){case "Rules":rules();break;case "History":history();break;case "Setup":setup();break;case "Motor test":motorTest();break;default:tonight();}
        gap(content,14);nav=row();for(String title:new String[]{"Tonight","Rules","History","Setup"}){TextView t=bold(title,13,title.equals(tab)?TEAL:MUTED);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(18),0,dp(18));t.setOnClickListener(v->{tab=title;build();});nav.addView(t,new LinearLayout.LayoutParams(0,dp(58),1));}shell.addView(nav);refresh();
    }
    private void tonight(){
        connectionCard=card();connection=bold("○  Controller disconnected",13,TEAL);connectionCard.addView(connection);two(connectionCard,"Connect controller",this::connect,"Download records",()->{if(service())BreezeService.current.download();});
        LinearLayout controls=col();LinearLayout r=row();
        for(int speed:FAN_ORDER){Button b=button(Protocol.SPEEDS[speed],false,()->{if(service())BreezeService.current.control(2,speed);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(2),0,dp(2),0);r.addView(b,lp);fanButtons.add(b);}controls.addView(r);
        sessionButton=button("Start",true,this::sessionAction);LinearLayout.LayoutParams startParams=new LinearLayout.LayoutParams(-1,dp(48));startParams.topMargin=dp(8);controls.addView(sessionButton,startParams);
        status=text("Connect your controller",12,MUTED);controls.addView(status);notice=text("",12,0xffb25436);controls.addView(notice);content.addView(controls);gap(content,10);
        LinearLayout temperatures=row();temperatures.setPadding(dp(12),dp(8),dp(12),dp(8));temperatures.setBackground(background(0xffe3ecdf,14));probeValue=bold("Body temp  —",14,INK);climate=bold("Fan temp  —",14,INK);temperatures.addView(probeValue,new LinearLayout.LayoutParams(0,-2,1));temperatures.addView(climate,new LinearLayout.LayoutParams(0,-2,1));content.addView(temperatures);addButton(content,"Motor test",false,()->{tab="Motor test";build();});gap(content,14);
        LinearLayout chart=card();graphHeading=bold("Temperature timeline",18,INK);chart.addView(graphHeading);chart.addView(text("● Body temp   ● Fan temp   ┄ Outdoors",12,MUTED));graph=new GraphView(this);chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(245)));two(chart,"Temperature",()->{showHumidity=false;refresh();},"Humidity",()->{showHumidity=true;refresh();});
        LinearLayout sync=card();ruleSummary=text("",12,MUTED);sync.addView(ruleSummary);transfer=text("Records remain on the controller until downloaded and confirmed.",13,MUTED);sync.addView(transfer);addButton(sync,"Download records",false,()->{if(service())BreezeService.current.download();});addButton(sync,demo?"Exit sample preview":"Preview a sample night",false,()->{demo=!demo;build();});
    }
    private void sessionAction(){
        if(!service())return;BreezeService b=BreezeService.current;
        if(!b.fresh()){toast("Wait for a fresh controller reading.");return;}
        if(b.latest.active())b.control(Protocol.STOP_RULES,0);else b.startSession(Settings.load(this));
    }
    private EditText field(LinearLayout p,String label,String value,boolean decimal){p.addView(text(label,13,MUTED));EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setTextSize(17);e.setTextColor(INK);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));p.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;}
    private double number(EditText e){return Double.parseDouble(e.getText().toString().trim());}
    private String num(double c){return String.format(Locale.US,"%.1f",fahrenheit?c*1.8+32:c);}
    private double celsius(EditText e){double v=number(e);return fahrenheit?(v-32)/1.8:v;}
    private void rules(){
        Protocol.Config cfg=Settings.load(this);LinearLayout actions=card();
        LinearLayout c=card();c.addView(bold("When it gets warmer…",23,INK));c.addView(text("Choose the body temperature where each speed begins. Below Low, the fan is Off.",14,MUTED));
        EditText low=field(c,"Low starts at °"+(fahrenheit?"F":"C"),num(cfg.low),true),medium=field(c,"Medium starts at",num(cfg.medium),true),high=field(c,"High starts at",num(cfg.high),true);
        EditText hysteresis=field(c,"Cooling hysteresis (°C difference)",""+cfg.hysteresis,true),dwell=field(c,"Minimum time between speed changes (seconds)",""+cfg.dwell,false);
        LinearLayout a=card();a.addView(bold("Optional room influence",19,INK));a.addView(text("Leave both influences at zero to use only the probe. Missing room data falls back to the probe.",13,MUTED));EditText weight=field(a,"Fan temperature weight (0–50%)",""+cfg.ambientWeight,false),rh=field(a,"Humidity boost starts above (% RH)",""+cfg.humidityThreshold,false),boost=field(a,"Added °C per 10% RH above that level",String.format(Locale.US,"%.1f",cfg.humidityBoost/10.0),true);
        a.addView(text("These adjust the temperature used by your rules. Raw sensor values stay unchanged in the records.",12,MUTED));
        addButton(actions,"Save temperature rules",true,()->{try{Protocol.Config n=Settings.load(this);n.low=celsius(low);n.medium=celsius(medium);n.high=celsius(high);n.hysteresis=number(hysteresis);n.dwell=(int)number(dwell);n.ambientWeight=(int)number(weight);n.humidityThreshold=(int)number(rh);n.humidityBoost=(int)Math.round(number(boost)*10);n.validate();nameRules(n);}catch(Exception e){error(e.getMessage());}});
        addButton(actions,"Load settings",false,this::loadRules);
        actions.addView(text("Rules apply when you next press Start.",12,MUTED));
    }
    private void nameRules(Protocol.Config config){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Name");name.setText(Settings.prefs(this).getString("ruleName",""));name.selectAll();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Save temperature rules").setView(name).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a name");return;}
            try{if(Settings.ruleNames(this).contains(title)){new AlertDialog.Builder(this).setTitle("Replace “"+title+"”?").setPositiveButton("Replace",(d,w)->saveRules(title,config,dialog)).setNegativeButton("Cancel",null).show();}else saveRules(title,config,dialog);}catch(Exception e){error(e.getMessage());}
        }));dialog.show();
    }
    private void saveRules(String name,Protocol.Config config,AlertDialog dialog){try{Settings.saveRules(this,name,config);dialog.dismiss();toast("Saved “"+name+"”. Applies when you next press Start.");}catch(Exception e){error(e.getMessage());}}
    private void loadRules(){try{
        List<String> names=Settings.ruleNames(this);if(names.isEmpty()){toast("No saved settings yet. Save temperature rules with a name first.");return;}
        new AlertDialog.Builder(this).setTitle("Load settings").setItems(names.toArray(new String[0]),(d,w)->{try{Settings.loadRules(this,names.get(w));build();toast("Loaded “"+names.get(w)+"”. Applies when you next press Start.");}catch(Exception e){error(e.getMessage());}}).setNegativeButton("Cancel",null).show();
    }catch(Exception e){error(e.getMessage());}}
    private void history(){
        List<Store.Session> sessions=store.sessions();LinearLayout c=card();c.addView(bold("Your recorded nights",23,INK));c.addView(text("Stored on this phone after a confirmed controller download.",13,MUTED));if(sessions.isEmpty()){c.addView(text("No sessions yet. Connect your controller and download its records.",15,INK));addButton(c,"Connect controller",true,this::connect);return;}
        Store.Session chosen=sessions.get(0);for(Store.Session s:sessions)if(s.id==selected)chosen=s;selected=chosen.id;final Store.Session session=chosen;
        List<String> labels=new ArrayList<>();for(Store.Session s:sessions)labels.add(date(s.start)+" · "+s.count+" records"+(s.end==0?" · open / interrupted":""));Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));spinner.setSelection(sessions.indexOf(chosen));spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(first){first=false;return;}selected=sessions.get(position).id;build();}});c.addView(spinner,new LinearLayout.LayoutParams(-1,dp(56)));
        c.addView(text("Started "+date(session.start)+"\n"+(session.end>0?"Ended "+date(session.end):"No end marker yet; session may be active or was interrupted by power loss."),12,MUTED));
        LinearLayout chart=card();graphHeading=bold("Temperature timeline",19,INK);chart.addView(graphHeading);chart.addView(text("● Body temp   ● Fan temp   ┄ Outdoors",12,MUTED));graph=new GraphView(this);chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(300)));two(chart,"Temperature",()->{showHumidity=false;refresh();},"Humidity",()->{showHumidity=true;refresh();});
        LinearLayout outdoor=card();outdoor.addView(bold("Add the outside story",19,INK));outdoor.addView(text("Hourly outdoor model data from Open-Meteo. Add it in the morning; no API key needed.",13,MUTED));String location=store.weatherLocation(selected);outdoor.addView(text(location.isEmpty()?"Weather location: "+Settings.prefs(this).getString("place","53214 • choose location in Setup"):"Weather: "+location,13,TEAL));addButton(outdoor,"Fetch outdoor weather",true,()->fetchWeather(session));addButton(outdoor,"Export session as CSV",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,42);});
        outdoor.addView(text("Open-Meteo · CC BY 4.0\nOutdoor data is modeled weather, not a sensor at your home.",11,MUTED));
        LinearLayout readings=card();readings.addView(bold("Latest readings in this session",18,INK));List<Store.Point> data=store.points(selected);for(int i=Math.max(0,data.size()-12);i<data.size();i++){Store.Point p=data.get(i);readings.addView(text(new SimpleDateFormat("h:mm:ss a",Locale.US).format(new Date(p.time))+"   "+temperature(p.probe)+"   "+Protocol.SPEEDS[p.speed],14,INK));}
    }
    private void setup(){
        LinearLayout c=card();c.addView(bold("Controller connection",21,INK));connection=text("Disconnected",14,TEAL);c.addView(connection);notice=text("",12,0xffb25436);c.addView(notice);two(c,"Connect / choose",this::connect,"Disconnect phone",()->{stopService(new Intent(this,BreezeService.class));toast("Phone disconnected. The controller keeps recording and controlling the fan.");handler.postDelayed(this::refresh,400);});
        c.addView(text("The XIAO owns the recording. Once records are committed to this phone, it is told to erase only those confirmed records.",13,MUTED));addButton(c,"Download controller records",false,()->{if(service())BreezeService.current.download();});addButton(c,"Initialize new controller storage",false,()->new AlertDialog.Builder(this).setTitle("Format controller log storage?").setMessage("Only for first setup when storage cannot mount. This permanently erases the controller's log partition. Never use it to fix a controller holding records you need.").setPositiveButton("Erase and initialize",(d,w)->{if(service())BreezeService.current.control(6,0);}).setNegativeButton("Cancel",null).show());
        Protocol.Config cfg=Settings.load(this);LinearLayout servo=card();servo.addView(bold("Calibrate the fan dial",21,INK));servo.addView(text("LEFT → RIGHT:  Off  ·  High  ·  Medium  ·  Low\nEach speed has its own servo position. Angles below are unverified starting placeholders over 45°.",14,MUTED));
        EditText off=field(servo,"Off position (°)",""+cfg.offAngle,false),high=field(servo,"High position (°)",""+cfg.highAngle,false),medium=field(servo,"Medium position (°)",""+cfg.mediumAngle,false),low=field(servo,"Low position (°)",""+cfg.lowAngle,false);
        CheckBox calibrated=new CheckBox(this);calibrated.setText("I verified all four positions on this fan");calibrated.setChecked(cfg.calibrated);servo.addView(calibrated);addButton(servo,"Save servo positions",true,()->{try{Protocol.Config n=Settings.load(this);n.offAngle=(int)number(off);n.highAngle=(int)number(high);n.mediumAngle=(int)number(medium);n.lowAngle=(int)number(low);n.calibrated=calibrated.isChecked();n.validate();Settings.save(this,n);toast("Positions saved. Sent with your next session.");}catch(Exception e){error(e.getMessage());}});
        addButton(servo,"Motor test",false,()->{tab="Motor test";build();});
        LinearLayout weather=card();weather.addView(bold("Outdoor weather location",21,INK));EditText query=new EditText(this);query.setSingleLine();query.setText(Settings.prefs(this).getString("query","53214"));weather.addView(query);weather.addView(text(Settings.prefs(this).getString("place","Search ZIP 53214 and choose its location."),13,MUTED));addButton(weather,"Search city or ZIP",true,()->searchPlace(query.getText().toString().trim()));
        LinearLayout hardware=card();hardware.addView(bold("Wiring reference",21,INK));hardware.addView(text("D0 / GPIO0  ·  Thermistor divider midpoint\nD1 / GPIO1  ·  Joystick Y (3.3 V supply)\nD2 / GPIO2  ·  Joystick button to GND\nD7 / GPIO17 ·  Servo signal\nD4 / GPIO22 ·  SHT40 SDA\nD5 / GPIO23 ·  SHT40 SCL\n\n10 kΩ resistor: 3.3 V → resistor → D0 → thermistor → GND. SHT40 uses 3.3 V. Servo uses a separate regulated 5 V supply with common GND.\n\nJoystick up/down changes cooling strength. Short press starts a session, or toggles Auto/Manual while recording. Hold 1.5 seconds to end the session and move to Off.\n\nAfter controller power loss, reconnect the phone to set its clock before starting another session.",14,INK));
    }
    private void motorTest(){
        LinearLayout c=card();connection=text("Controller connection",13,TEAL);c.addView(connection);notice=text("",13,0xffb25436);c.addView(notice);
        addButton(c,"Connect controller",false,this::connect);
        LinearLayout test=card();test.addView(bold("Motor test",23,INK));
        motorPosition=text("Waiting for controller",15,INK);test.addView(motorPosition);
        test.addView(text("Set the starting position with the servo detached from the knob. Then tap − or + to check which way it turns.",13,MUTED));
        motorCenter=button("Set starting position",false,()->{
            if(!service())return;
            new AlertDialog.Builder(this).setTitle("Set starting position?").setMessage("Detach the servo from the knob first. This first command can make a larger movement to the middle of the test range. After that, − and + make only the selected small step.").setPositiveButton("Set position",(d,w)->{if(service())BreezeService.current.testMotor(Protocol.MOTOR_CENTER,0);}).setNegativeButton("Cancel",null).show();
        });test.addView(motorCenter,new LinearLayout.LayoutParams(-1,dp(48)));gap(test,12);
        TextView step=text("",16,INK);test.addView(step);
        SeekBar slider=new SeekBar(this);slider.setMin(2);slider.setMax(100);slider.setProgress(motorStep);slider.setContentDescription("Motor movement per tap");test.addView(slider,new LinearLayout.LayoutParams(-1,dp(48)));
        Runnable label=()->step.setText(String.format(Locale.US,"Step per tap: %.2f° (%d µs)",motorStep*.18,motorStep));label.run();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}public void onProgressChanged(SeekBar bar,int value,boolean user){motorStep=value;label.run();refresh();}});
        LinearLayout r=row();motorMinus=button("− Step",true,()->{if(service())BreezeService.current.testMotor(Protocol.MOTOR_MINUS,motorStep);});motorPlus=button("+ Step",true,()->{if(service())BreezeService.current.testMotor(Protocol.MOTOR_PLUS,motorStep);});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(56),1);lp.setMargins(dp(3),dp(8),dp(3),dp(8));r.addView(motorMinus,lp);r.addView(motorPlus,new LinearLayout.LayoutParams(lp));test.addView(r);
        test.addView(text("The slider changes the step size only. Each tap makes one step. Angles are estimated; observe the actual movement.",12,MUTED));
        motorRelease=button("Release motor",false,()->{if(service())BreezeService.current.testMotor(4,0);});test.addView(motorRelease,new LinearLayout.LayoutParams(-1,dp(48)));
        test.addView(text("No temperature sensors or recording needed for this test.",12,MUTED));
    }
    private void connect(){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},7);return;}
        BreezeService b=BreezeService.current;if(b!=null&&b.ready){toast("Controller connected.");return;}if(b!=null){List<String> devices=b.foundDevices();if(!devices.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose Breeze controller").setItems(devices.stream().map(s->"Breeze Fan · "+s).toArray(String[]::new),(d,w)->b.connect(devices.get(w))).setNegativeButton("Scan again",(d,w)->b.scan()).show();return;}b.scan();toast("Searching. Tap Connect / choose again when found.");}else{startForegroundService(new Intent(this,BreezeService.class));toast("Searching for Breeze Fan. Tap Connect again to choose it.");}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==7&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)connect();}
    private boolean service(){if(demo){toast("Exit the sample preview to control your fan.");return false;}if(BreezeService.current==null||!BreezeService.current.ready){toast("Connect to your Breeze controller first.");return false;}return true;}
    private String temperature(double c){return Double.isFinite(c)?String.format(Locale.US,"%.1f°%s",fahrenheit?c*1.8+32:c,fahrenheit?"F":"C"):"—";}
    private String date(long t){return new SimpleDateFormat("MMM d, yyyy · h:mm a",Locale.US).format(new Date(t));}
    private void refresh(){
        BreezeService b=BreezeService.current;boolean fresh=b!=null&&b.fresh();Protocol.Reading r=fresh?b.latest:null;
        if(connection!=null)connection.setText(demo?"SAMPLE PREVIEW · simulated readings":b==null?"○  Controller disconnected":"●  "+(b.ready?"Connected":b.status+(b.foundDevices().isEmpty()?"":" · "+b.foundDevices().size()+" found")));
        if(notice!=null){String message=b==null?"":b.lastError;notice.setText(message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
        if(connectionCard!=null)connectionCard.setVisibility(!demo&&b!=null&&b.ready?View.GONE:View.VISIBLE);
        if(probeValue!=null){
            probeValue.setText("Body temp  "+(demo?temperature(31.3):r!=null?temperature(r.probe):"—"));climate.setText("Fan temp  "+(demo?temperature(23.1):r!=null?temperature(r.room):"—"));
            status.setText(demo?"Sample preview":r==null?"Waiting for controller":(r.active()?"Recording · Rules "+(r.automatic()?"on":"paused"):"Rules and recording off")+(r.error==0?"":" · "+Protocol.error(r.error)));
            sessionButton.setText(r!=null&&r.active()?"Stop":"Start");sessionButton.setEnabled(!demo&&fresh);
            for(int i=0;i<fanButtons.size();i++){Button button=fanButtons.get(i);boolean chosen=!demo&&r!=null&&r.speed==FAN_ORDER[i];button.setEnabled(!demo&&fresh);button.setSelected(chosen);button.setTextColor(chosen?WHITE:TEAL);button.setBackground(background(chosen?TEAL:0xffe8eee5,14));}
            transfer.setText(b==null?"Records stay on the controller until downloaded and confirmed.":b.transfer);Protocol.Config c=Settings.load(this);ruleSummary.setText("Low "+temperature(c.low)+" · Medium "+temperature(c.medium)+" · High "+temperature(c.high)+(c.calibrated?"":"\nServo movement disabled until calibration."));
        }
        if(motorPosition!=null){
            Protocol.MotorReading m=b==null?null:b.motor;boolean idle=fresh&&r!=null&&!r.active()&&m!=null&&!b.motorBusy&&!demo;
            motorCenter.setEnabled(idle);motorMinus.setEnabled(idle&&m.canStep()&&m.pulse-motorStep>=1000);motorPlus.setEnabled(idle&&m.canStep()&&m.pulse+motorStep<=2000);motorRelease.setEnabled(idle&&m.powered);
            motorPosition.setText(!fresh?"Waiting for controller":r.active()?"Stop recording before testing":m==null?"Motor-test firmware required":b.motorBusy?"Moving…":m.canStep()?String.format(Locale.US,"Commanded position: %.2f° · %d µs",(m.pulse-1000)*.18,m.pulse):"Motor released · set the starting position");
        }
        if(graph!=null){long id=selected;if(tab.equals("Tonight")){List<Store.Session> list=store.sessions();id=list.isEmpty()?-1:list.get(0).id;}List<Store.Point> p=demo&&tab.equals("Tonight")?sample():id<0?new ArrayList<>():store.points(id);List<Store.Point> o=demo&&tab.equals("Tonight")?sampleOutdoor():id<0?new ArrayList<>():store.weather(id);graph.data(p,o,fahrenheit,showHumidity);if(graphHeading!=null)graphHeading.setText((demo&&tab.equals("Tonight")?"Sample · ":"")+(showHumidity?"Humidity timeline (%)":"Temperature timeline (°"+(fahrenheit?"F":"C")+")"));}
    }
    private List<Store.Point> sample(){List<Store.Point> p=new ArrayList<>();long start=System.currentTimeMillis()-8*3600000;for(int i=0;i<=960;i++){double t=30.8+1.2*Math.sin(i/76.0)+.3*Math.cos(i/18.0);p.add(new Store.Point(start+i*30000,t,i%2==0?23.8-.8*i/960+Math.sin(i/110.0)*.3:Double.NaN,i%2==0?58+6*Math.sin(i/150.0):Double.NaN,t>=33?3:t>=31?2:t>=29?1:0));}return p;}
    private List<Store.Point> sampleOutdoor(){List<Store.Point> p=new ArrayList<>();long start=System.currentTimeMillis()-8*3600000;for(int i=0;i<=8;i++)p.add(new Store.Point(start+i*3600000,20-i*.6,Double.NaN,63+i,0));return p;}
    private void searchPlace(String query){if(query.isEmpty())return;toast("Looking up "+query+"…");worker.execute(()->{try{List<Weather.Place> p=Weather.search(query);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(p.isEmpty()){error("No matching location. Try the city name.");return;}new AlertDialog.Builder(this).setTitle("Choose weather location").setItems(p.stream().map(x->x.label).toArray(String[]::new),(d,w)->{Weather.Place place=p.get(w);Settings.prefs(this).edit().putString("query",query).putString("place",place.label).putString("latitude",""+place.lat).putString("longitude",""+place.lon).apply();build();}).show();});}catch(Exception e){runOnUiThread(()->error(e.getMessage()));}});}
    private void fetchWeather(Store.Session s){String lat=Settings.prefs(this).getString("latitude","");if(lat.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose your weather location").setMessage("Search ZIP 53214 in Setup first.").setPositiveButton("Open Setup",(d,w)->{tab="Setup";build();}).show();return;}Weather.Place p=new Weather.Place();p.lat=Double.parseDouble(lat);p.lon=Double.parseDouble(Settings.prefs(this).getString("longitude","0"));p.label=Settings.prefs(this).getString("place","");toast("Fetching outdoor weather…");worker.execute(()->{try{int count=Weather.fetch(store,s,p);runOnUiThread(()->{toast("Added "+count+" hourly outdoor readings.");if(!isDestroyed())build();});}catch(Exception e){runOnUiThread(()->error("Weather unavailable: "+e.getMessage()));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new java.io.IOException("Cannot open export destination");if(exportSession<0)throw new java.io.IOException("Choose a session to export again.");out.write(store.csv(exportSession).getBytes(StandardCharsets.UTF_8));toast("CSV exported.");}catch(Exception e){error(e.getMessage());}}}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Breeze").setMessage(message==null?"Check your entries.":message).setPositiveButton("OK",null).show();}
}
