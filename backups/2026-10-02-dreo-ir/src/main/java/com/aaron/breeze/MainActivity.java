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
    private static final int BG=0xff101d18,INK=0xffe5eee6,MUTED=0xffa6b9ac,TEAL=0xff9ac5a4,WHITE=0xff20352b;
    private LinearLayout shell,content,nav;
    private Store store;
    private String tab="Home";
    private boolean fahrenheit=true,showHumidity=false;
    private long selected=-1;
    private TextView connection,probeValue,climate,humidityValue,status,transfer,graphHeading,notice;
    private Button sessionButton;
    private GraphView graph;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();}};
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1500);}};
    private long exportSession=-1;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);
        if(!Settings.prefs(this).contains("place"))Settings.prefs(this).edit().putString("query","53214").putString("place","Milwaukee, Wisconsin • ZIP 53214 (city weather)").putString("latitude","43.0389").putString("longitude","-87.90647").apply();
        fahrenheit=Settings.prefs(this).getBoolean("fahrenheit",true);
        if(state!=null){tab=state.getString("tab","Home");if(!Arrays.asList("Home","History","Setup").contains(tab))tab="Home";selected=state.getLong("selected",-1);exportSession=state.getLong("exportSession",-1);}
        build();
        if(BreezeService.current==null&&Settings.prefs(this).contains("device")&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)startForegroundService(new Intent(this,BreezeService.class));
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("tab",tab);b.putLong("selected",selected);b.putLong("exportSession",exportSession);}
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
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(primary?WHITE:TEAL);b.setBackground(background(primary?TEAL:0xff2c4436,14));b.setMinHeight(dp(48));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setOnClickListener(v->action.run());return b;}
    private void addButton(LinearLayout p,String title,boolean primary,Runnable action){Button b=button(title,primary,action);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.topMargin=dp(8);p.addView(b,lp);}
    private void two(LinearLayout p,String a,Runnable aa,String b,Runnable bb){LinearLayout r=row();Button x=button(a,false,aa),y=button(b,false,bb);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),dp(8),dp(3),0);r.addView(x,lp);r.addView(y,new LinearLayout.LayoutParams(lp));p.addView(r);}
    private void build(){
        connection=null;probeValue=null;climate=null;humidityValue=null;graph=null;graphHeading=null;status=null;transfer=null;notice=null;sessionButton=null;
        shell=col();shell.setBackground(new GraniteDrawable());shell.setPadding(dp(20),0,dp(20),0);shell.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20),bars.top,dp(20),bars.bottom);return insets;});setContentView(shell);
        getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout header=row();TextView brand=bold("breeze",34,TEAL);header.addView(brand,new LinearLayout.LayoutParams(0,dp(64),1));Button units=button(fahrenheit?"°F":"°C",false,()->{fahrenheit=!fahrenheit;Settings.prefs(this).edit().putBoolean("fahrenheit",fahrenheit).apply();build();});header.addView(units,new LinearLayout.LayoutParams(dp(58),dp(40)));shell.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);content=col();scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!tab.equals("Home")){content.addView(text(tab.equals("History")?"Every reading. Every night.":"Connection and recording",16,MUTED));gap(content,16);}
        switch(tab){case "History":history();break;case "Setup":setup();break;default:home();}
        gap(content,14);nav=row();for(String title:new String[]{"Home","History","Setup"}){TextView t=bold(title,14,title.equals(tab)?TEAL:MUTED);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(18),0,dp(18));t.setOnClickListener(v->{tab=title;build();});nav.addView(t,new LinearLayout.LayoutParams(0,dp(58),1));}shell.addView(nav);refresh();
    }
    private TextView readingCard(String label){
        LinearLayout c=card();c.setPadding(dp(22),dp(20),dp(22),dp(20));
        c.addView(text(label,16,MUTED));TextView value=bold("—",46,INK);value.setFontFeatureSettings("tnum");c.addView(value);return value;
    }
    private void home(){gap(content,18);probeValue=readingCard("Body temp");climate=readingCard("Fan temp");humidityValue=readingCard("Humidity");}
    private void sessionAction(){if(!service())return;BreezeService b=BreezeService.current;if(!b.fresh()){toast("Wait for a fresh controller reading.");return;}if(b.latest.active())b.control(Protocol.STOP_RECORDING,0);else b.startSession();}
    private void history(){
        List<Store.Session> sessions=store.sessions();LinearLayout c=card();c.addView(bold("Your recorded nights",23,INK));c.addView(text("Stored on this phone after a confirmed controller download.",13,MUTED));if(sessions.isEmpty()){c.addView(text("No sessions yet. Connect your controller and download its records.",15,INK));addButton(c,"Connect controller",true,this::connect);return;}
        Store.Session chosen=sessions.get(0);for(Store.Session s:sessions)if(s.id==selected)chosen=s;selected=chosen.id;final Store.Session session=chosen;
        List<String> labels=new ArrayList<>();for(Store.Session s:sessions)labels.add(date(s.start)+" · "+s.count+" records"+(s.end==0?" · open / interrupted":""));Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));spinner.setSelection(sessions.indexOf(chosen));spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(first){first=false;return;}selected=sessions.get(position).id;build();}});c.addView(spinner,new LinearLayout.LayoutParams(-1,dp(56)));
        c.addView(text("Started "+date(session.start)+"\n"+(session.end>0?"Ended "+date(session.end):"No end marker yet; session may be active or was interrupted by power loss."),12,MUTED));
        LinearLayout chart=card();graphHeading=bold("Temperature timeline",19,INK);chart.addView(graphHeading);chart.addView(text("● Body temp   ● Fan temp   ┄ Outdoors",12,MUTED));graph=new GraphView(this);chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(300)));two(chart,"Temperature",()->{showHumidity=false;refresh();},"Humidity",()->{showHumidity=true;refresh();});
        LinearLayout outdoor=card();outdoor.addView(bold("Add the outside story",19,INK));outdoor.addView(text("Hourly outdoor model data from Open-Meteo. Add it in the morning; no API key needed.",13,MUTED));String location=store.weatherLocation(selected);outdoor.addView(text(location.isEmpty()?"Weather location: "+Settings.prefs(this).getString("place","53214 • choose location in Setup"):"Weather: "+location,13,TEAL));addButton(outdoor,"Fetch outdoor weather",true,()->fetchWeather(session));addButton(outdoor,"Export session as CSV",false,()->{exportSession=session.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Breeze-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date(session.start))+".csv");startActivityForResult(i,42);});
        outdoor.addView(text("Open-Meteo · CC BY 4.0\nOutdoor data is modeled weather, not a sensor at your home.",11,MUTED));
        LinearLayout readings=card();readings.addView(bold("Latest readings in this session",18,INK));List<Store.Point> data=store.points(selected);for(int i=Math.max(0,data.size()-12);i<data.size();i++){Store.Point p=data.get(i);readings.addView(text(new SimpleDateFormat("h:mm:ss a",Locale.US).format(new Date(p.time))+"   "+temperature(p.probe)+"   "+temperature(p.room)+"   "+humidity(p.humidity),14,INK));}
    }
    private void setup(){
        LinearLayout c=card();c.addView(bold("Controller connection",21,INK));connection=text("Disconnected",14,TEAL);c.addView(connection);notice=text("",12,0xffedb597);c.addView(notice);two(c,"Connect / choose",this::connect,"Disconnect phone",()->{stopService(new Intent(this,BreezeService.class));toast("Phone disconnected. The controller keeps recording.");handler.postDelayed(this::refresh,400);});
        c.addView(text("The XIAO owns the recording. Once records are committed to this phone, it is told to erase only those confirmed records.",13,MUTED));addButton(c,"Download controller records",false,()->{if(service())BreezeService.current.download();});addButton(c,"Initialize new controller storage",false,()->new AlertDialog.Builder(this).setTitle("Format controller log storage?").setMessage("Only for first setup when storage cannot mount. This permanently erases the controller's log partition. Never use it to fix a controller holding records you need.").setPositiveButton("Erase and initialize",(d,w)->{if(service())BreezeService.current.control(6,0);}).setNegativeButton("Cancel",null).show());
        LinearLayout recording=card();recording.addView(bold("Sensor recording",21,INK));status=text("Connect your controller",14,MUTED);recording.addView(status);sessionButton=button("Start recording",true,this::sessionAction);recording.addView(sessionButton,new LinearLayout.LayoutParams(-1,dp(48)));transfer=text("",13,MUTED);recording.addView(transfer);recording.addView(text("Body temperature every 30 seconds. Fan temperature and humidity every 60 seconds. Readings remain live when recording is stopped.",13,MUTED));
        LinearLayout weather=card();weather.addView(bold("Outdoor weather location",21,INK));EditText query=new EditText(this);query.setSingleLine();query.setText(Settings.prefs(this).getString("query","53214"));weather.addView(query);weather.addView(text(Settings.prefs(this).getString("place","Search ZIP 53214 and choose its location."),13,MUTED));addButton(weather,"Search city or ZIP",true,()->searchPlace(query.getText().toString().trim()));
        LinearLayout hardware=card();hardware.addView(bold("Sensors",21,INK));hardware.addView(text("Body temperature · D0\nFan temperature + humidity · SHT40\nD4 · SDA    D5 · SCL\n\nBody probe divider: 3.3 V → 10 kΩ resistor → D0 → thermistor → GND. SHT40 uses 3.3 V and GND.\n\nAfter controller power loss, reconnect the phone to set its clock before starting another recording.",14,INK));
    }
    private void connect(){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},7);return;}
        BreezeService b=BreezeService.current;if(b!=null&&b.ready){toast("Controller connected.");return;}if(b!=null){List<String> devices=b.foundDevices();if(!devices.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose Breeze controller").setItems(devices.stream().map(s->"Breeze Sensors · "+s).toArray(String[]::new),(d,w)->b.connect(devices.get(w))).setNegativeButton("Scan again",(d,w)->b.scan()).show();return;}b.scan();toast("Searching. Tap Connect / choose again when found.");}else{startForegroundService(new Intent(this,BreezeService.class));toast("Searching for Breeze Sensors. Tap Connect again to choose it.");}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==7&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)connect();}
    private boolean service(){if(BreezeService.current==null||!BreezeService.current.ready){toast("Connect to your Breeze controller first.");return false;}return true;}
    private String temperature(double c){return Double.isFinite(c)?String.format(Locale.US,"%.1f°%s",fahrenheit?c*1.8+32:c,fahrenheit?"F":"C"):"—";}
    private String date(long t){return new SimpleDateFormat("MMM d, yyyy · h:mm a",Locale.US).format(new Date(t));}
    private String humidity(double h){return Double.isFinite(h)?String.format(Locale.US,"%.1f%%",h):"—";}
    private void refresh(){
        BreezeService b=BreezeService.current;boolean fresh=b!=null&&b.fresh();Protocol.Reading r=fresh?b.latest:null;
        if(connection!=null)connection.setText(b==null?"○  Controller disconnected":"●  "+(b.ready?"Connected":b.status+(b.foundDevices().isEmpty()?"":" · "+b.foundDevices().size()+" found")));
        if(notice!=null){String message=b==null?"":b.lastError;notice.setText(message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
        if(probeValue!=null){probeValue.setText(temperature(r==null?Double.NaN:r.probe));climate.setText(temperature(r==null?Double.NaN:r.room));humidityValue.setText(humidity(r==null?Double.NaN:r.humidity));}
        if(status!=null){status.setText(r==null?"Waiting for controller":!b.sensorFirmware?"Update the controller to sensor firmware":(r.active()?"Recording on controller":"Live readings · recording stopped")+(r.error==0?"":" · "+Protocol.error(r.error)));sessionButton.setText(r!=null&&r.active()?"Stop recording":"Start recording");sessionButton.setEnabled(fresh&&b.sensorFirmware);transfer.setText(b==null?"Records stay on the controller until downloaded and confirmed.":b.transfer);}
        if(graph!=null){graph.data(selected<0?new ArrayList<>():store.points(selected),selected<0?new ArrayList<>():store.weather(selected),fahrenheit,showHumidity);if(graphHeading!=null)graphHeading.setText(showHumidity?"Humidity timeline (%)":"Temperature timeline (°"+(fahrenheit?"F":"C")+")");}
    }
    private void searchPlace(String query){if(query.isEmpty())return;toast("Looking up "+query+"…");worker.execute(()->{try{List<Weather.Place> p=Weather.search(query);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(p.isEmpty()){error("No matching location. Try the city name.");return;}new AlertDialog.Builder(this).setTitle("Choose weather location").setItems(p.stream().map(x->x.label).toArray(String[]::new),(d,w)->{Weather.Place place=p.get(w);Settings.prefs(this).edit().putString("query",query).putString("place",place.label).putString("latitude",""+place.lat).putString("longitude",""+place.lon).apply();build();}).show();});}catch(Exception e){runOnUiThread(()->error(e.getMessage()));}});}
    private void fetchWeather(Store.Session s){String lat=Settings.prefs(this).getString("latitude","");if(lat.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose your weather location").setMessage("Search ZIP 53214 in Setup first.").setPositiveButton("Open Setup",(d,w)->{tab="Setup";build();}).show();return;}Weather.Place p=new Weather.Place();p.lat=Double.parseDouble(lat);p.lon=Double.parseDouble(Settings.prefs(this).getString("longitude","0"));p.label=Settings.prefs(this).getString("place","");toast("Fetching outdoor weather…");worker.execute(()->{try{int count=Weather.fetch(store,s,p);runOnUiThread(()->{toast("Added "+count+" hourly outdoor readings.");if(!isDestroyed())build();});}catch(Exception e){runOnUiThread(()->error("Weather unavailable: "+e.getMessage()));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new java.io.IOException("Cannot open export destination");if(exportSession<0)throw new java.io.IOException("Choose a session to export again.");out.write(store.csv(exportSession).getBytes(StandardCharsets.UTF_8));toast("CSV exported.");}catch(Exception e){error(e.getMessage());}}}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Breeze").setMessage(message==null?"Check your entries.":message).setPositiveButton("OK",null).show();}
}
