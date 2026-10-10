package com.aaron.breeze;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.nio.*;
import java.util.*;
import java.util.function.IntFunction;

@SuppressWarnings("deprecation")
// All BLE entry points use allowed(); Android revokes permissions by stopping this process.
@android.annotation.SuppressLint("MissingPermission")
public class BreezeService extends Service {
    private static final String TAG="BreezeBLE";
    private static final long COMMAND_TIMEOUT_MS=6000,CONNECT_TIMEOUT_MS=20000;
    private static final long BUSY_RETRY_MS=250,RECONNECT_DELAY_MS=3000;
    private static final String UI_SESSION="ui_session",FORCE_RECONNECT="force_reconnect";
    private static final UiConnectionState uiConnection=new UiConnectionState(SystemClock::elapsedRealtime);
    public static BreezeService current;
    public static final String UPDATE="com.aaron.breeze.UPDATE";
    public String status="Disconnected", transfer="Records stay on the controller until downloaded.", lastError="";
    public Protocol.Reading latest;
    public boolean sensorFirmware=false,thirdProbeSupported=false;
    public boolean irFirmware=false;
    public Protocol.FanState fan;
    public boolean irReceiverTestSupported;
    public Protocol.IrReceiverTest irReceiverTest;
    public String irReceiverTestStatus="";
    private boolean irReceiverTestRunning,irReceiverTestRequested;
    private int irReceiverTestGeneration,irReceiverPreviousTestId=-1;
    public boolean receiverMonitorSupported;
    public Protocol.ReceiverInput receiverInput,lastExternalReceiverInput,lastLedReceiverInput;
    private int receiverMonitorCounter=-1;
    public String fanCommandStatus="";
    public boolean logicSupported=false,logicUploading=false;
    public Protocol.LogicState logic;
    public String logicStatus="Automatic profile will apply when the controller connects.";
    private boolean logicAppliedForConnection=false;
    private int logicUploadGeneration=0;
    private LogicProfile nextLogicProfile;
    private boolean logicExplicitResumeRequested,logicResumeAfterUpload,nextLogicResumeAfterUpload;
    private boolean homeLogicTogglePending,homeLogicToggleDesired;
    private int homeLogicToggleGeneration;
    public boolean anchorCorrectionPending=false;
    private int anchorCorrectionGeneration=0;
    public boolean ready=false, scanning=false;
    public long selectedSession=-1, controllerCount=0, controllerBase=0;
    public long controllerFreeBytes=-1;
    public boolean controllerStorageReady=false;
    private String controllerFirmware="unknown";
    private String deviceId="";
    private String deviceAddress="";
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private Store store;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<Pending> queue=new ArrayDeque<>();
    private Pending pending;
    private int seq=(int)(System.currentTimeMillis()%60000)+1;
    private long lastPacket=0,readySince=0;
    private long telemetryIntervalMs=Protocol.DEFAULT_TELEMETRY_INTERVAL_MS;
    private int scanGeneration=0;
    private ScanCallback scanCallback;
    private boolean destroyed=false, created=false, foregroundStarted=false, stoppedForUi=true, syncQueued=false, retireQueued=false;
    private long uiSession=-1;
    private final HashMap<String,BluetoothDevice> devices=new HashMap<>();
    public interface Result {void done(boolean success);}
    public interface HomeFanResult {void done(boolean sent,Integer correctedSetting);}
    private Result homeManualCompletion;
    private static class Pending {byte[] bytes;int seq,error;boolean submitted,written,acknowledged;long deadline;Result done;Pending(byte[] b,int s,Result r){bytes=b;seq=s;done=r;}}
    public static void setUiVisible(Context context,boolean visible){
        uiConnection.setVisible(visible&&screenAvailable(context));
        BreezeService service=current;
        // onStartCommand must accept the session before foreground refresh can retire it.
        if(service!=null&&service.created&&!service.stoppedForUi)service.updateBackgroundDisconnect();
    }
    public static boolean startForVisibleUi(Context context,boolean userRequested){
        return startForVisibleUi(context,userRequested,false);
    }
    public static boolean reconnectForVisibleUi(Context context){
        return startForVisibleUi(context,true,true);
    }
    private static boolean startForVisibleUi(Context context,boolean userRequested,boolean reconnect){
        if(!screenAvailable(context)||!uiConnection.requestStart(userRequested))return false;
        context.startForegroundService(new Intent(context,BreezeService.class).putExtra(UI_SESSION,uiConnection.generation()).putExtra(FORCE_RECONNECT,reconnect));
        return true;
    }
    public static void disconnectPhone(Context context){
        uiConnection.disconnect();
        BreezeService service=current;
        if(service!=null&&service.created)service.stopForUi();
        context.stopService(new Intent(context,BreezeService.class));
    }
    private static boolean screenAvailable(Context context){PowerManager power=context.getSystemService(PowerManager.class);KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);return power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isKeyguardLocked();}
    boolean visibleForBluetooth(){return !destroyed&&!stoppedForUi&&current==this&&uiConnection.allows(uiSession);}
    private final Runnable backgroundDisconnect=this::updateBackgroundDisconnect;
    private void updateBackgroundDisconnect(){
        handler.removeCallbacks(backgroundDisconnect);
        if(!visibleForBluetooth()){stopForUi();return;}
        long remaining=uiConnection.backgroundRemainingMs();
        if(remaining>0)handler.postDelayed(backgroundDisconnect,remaining);
    }
    @Override public void onCreate(){super.onCreate();created=true;store=new Store(this);adapter=getSystemService(BluetoothManager.class).getAdapter();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Controller connection",NotificationManager.IMPORTANCE_LOW));IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);filter.addAction(Intent.ACTION_SCREEN_OFF);registerReceiver(bluetoothState,filter,Context.RECEIVER_EXPORTED);
        // Meet Android's foreground contract before exposing a service that the UI can stop.
        startForeground(1,notification());foregroundStarted=true;current=this;
    }
    private final Runnable periodicSync=new Runnable(){public void run(){if(!visibleForBluetooth()){stopForUi();return;}if(ready&&!syncQueued)download();if(visibleForBluetooth())handler.postDelayed(this,30000);}};
    private final Runnable connectionWatchdog=new Runnable(){public void run(){if(!visibleForBluetooth()){stopForUi();return;}checkConnectionHealth();if(visibleForBluetooth())handler.postDelayed(this,3000);}};
    private final Runnable reconnect=this::tryReconnect;
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))setUiVisible(context,false);else handleBluetoothState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR));}};
    @Override public int onStartCommand(Intent intent,int flags,int id){
        long requested=intent==null?-1:intent.getLongExtra(UI_SESSION,-1);
        // Fulfil Android's foreground-start contract even for a start queued before onPause.
        startForeground(1,notification());foregroundStarted=true;
        if(!uiConnection.allows(requested)){if(!visibleForBluetooth())stopForUi();return START_NOT_STICKY;}
        uiSession=requested;stoppedForUi=false;current=this;
        handler.removeCallbacks(periodicSync);handler.removeCallbacks(connectionWatchdog);
        handler.postDelayed(periodicSync,30000);handler.postDelayed(connectionWatchdog,3000);
        updateBackgroundDisconnect();
        if(intent!=null&&intent.getBooleanExtra(FORCE_RECONNECT,false))reconnectNow();
        else if(gatt==null&&!scanning)tryReconnect();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"connection").setSmallIcon(com.aaron.breeze.R.drawable.ic_breeze).setContentTitle("Breeze • "+status).setContentText(latest!=null&&latest.active()?"Controller is recording locally":"Connected for setup and record downloads").setContentIntent(open).setOngoing(true).build();}
    public void changed(){sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
    void state(String s){status=s;changed();if(foregroundStarted&&visibleForBluetooth()&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED))getSystemService(NotificationManager.class).notify(1,notification());}
    public long telemetryFreshnessMs(){return Protocol.telemetryFreshnessMs(telemetryIntervalMs);}
    private boolean freshStatus(long receivedAt){return System.currentTimeMillis()-receivedAt<telemetryFreshnessMs();}
    public boolean fresh(){return visibleForBluetooth()&&ready&&latest!=null&&SystemClock.elapsedRealtime()-lastPacket<telemetryFreshnessMs();}
    boolean allowed(){return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    boolean bluetoothEnabled(){return adapter!=null&&allowed()&&adapter.isEnabled();}
    void handleBluetoothState(int state){
        if(!visibleForBluetooth())return;
        if(state==BluetoothAdapter.STATE_TURNING_OFF||state==BluetoothAdapter.STATE_OFF){handler.removeCallbacks(reconnect);stopScan();closeGatt();failQueue("Bluetooth is off. Breeze will reconnect when it is on.");state("Bluetooth off");}
        else if(state==BluetoothAdapter.STATE_ON){lastError="";state("Reconnecting to controller…");scheduleReconnect();}
    }
    void checkConnectionHealth(){
        if(!visibleForBluetooth())return;
        if(ready&&SystemClock.elapsedRealtime()-(lastPacket==0?readySince:lastPacket)>=Protocol.telemetryLinkTimeoutMs(telemetryIntervalMs))recoverConnection("Controller connection stopped responding. Reconnecting…");
        else if(!ready&&gatt==null&&!scanning&&!handler.hasCallbacks(reconnect)&&(!savedDeviceAddress().isEmpty()||devices.size()<=1))scheduleReconnect();
    }
    String savedDeviceAddress(){return Settings.prefs(this).getString("device","");}
    void tryReconnect(){
        if(!visibleForBluetooth()||ready||gatt!=null||scanning||!allowed()||!bluetoothEnabled())return;
        String saved=savedDeviceAddress();if(saved.isEmpty())scan();else connect(saved);
    }
    void scheduleReconnect(){if(!handler.hasCallbacks(reconnect)&&visibleForBluetooth()&&allowed()&&bluetoothEnabled())handler.postDelayed(reconnect,RECONNECT_DELAY_MS);}
    void reconnectNow(){
        if(!visibleForBluetooth())return;
        handler.removeCallbacks(reconnect);stopScan();closeGatt();failQueue("Reconnecting to the controller.");devices.clear();
        if(!allowed()){lastError="Nearby devices permission is needed.";state("Permission needed");return;}
        if(!bluetoothEnabled()){lastError="Turn Bluetooth on. Breeze will reconnect when it is on.";state("Bluetooth off");return;}
        lastError="";state("Reconnecting to controller…");tryReconnect();
    }
    void recoverConnection(String reason){
        if(!visibleForBluetooth())return;
        android.util.Log.w(TAG,reason);stopScan();closeGatt();failQueue(reason);state(bluetoothEnabled()?"Reconnecting to controller…":"Bluetooth off");scheduleReconnect();
    }
    public void scan(){
        if(!visibleForBluetooth())return;
        if(ready){if(fresh()){state("Connected");return;}recoverConnection("Controller connection stopped responding. Reconnecting…");return;}
        if(!allowed()){lastError="Nearby devices permission is needed.";changed();return;}
        if(adapter==null||!adapter.isEnabled()){lastError="Turn Bluetooth on, then connect again.";changed();return;}
        if(scanning)return;
        handler.removeCallbacks(reconnect);devices.clear();scanning=true;final int generation=++scanGeneration;scanCallback=scannerCallback(generation);state("Searching for Breeze Sensors…");
        try{adapter.getBluetoothLeScanner().startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);}catch(RuntimeException e){recoverConnection("Bluetooth scan could not start. Reconnecting…");return;}
        handler.postDelayed(()->{if(visibleForBluetooth()&&scanning&&generation==scanGeneration){stopScan();state(devices.isEmpty()?"No controller found":"Choose a controller");if(devices.isEmpty()||!savedDeviceAddress().isEmpty())scheduleReconnect();}},12000);
    }
    private void stopScan(){if(scanning&&allowed()&&adapter!=null&&adapter.isEnabled()){try{BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();if(scanner!=null)scanner.stopScan(scanCallback);}catch(RuntimeException e){android.util.Log.w(TAG,"Could not stop old Bluetooth scan",e);}}scanning=false;scanGeneration++;}
    private ScanCallback scannerCallback(int generation){return new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{if(!visibleForBluetooth()||generation!=scanGeneration||!scanning||ready)return;BluetoothDevice d=result.getDevice();devices.put(d.getAddress(),d);String saved=Settings.prefs(BreezeService.this).getString("device","");if(d.getAddress().equals(saved)){connect(d.getAddress());}else{changed();handler.postDelayed(()->{if(visibleForBluetooth()&&generation==scanGeneration&&scanning&&!ready&&devices.size()==1)connect(devices.keySet().iterator().next());},800);}});}
        @Override public void onScanFailed(int code){handler.post(()->{if(!visibleForBluetooth()||generation!=scanGeneration||!scanning||ready)return;stopScan();lastError="Bluetooth scan failed ("+code+").";state("Scan stopped");scheduleReconnect();});}
    };}
    public List<String> foundDevices(){List<String> list=new ArrayList<>(devices.keySet());Collections.sort(list);return list;}
    public void connect(String address){
        if(!visibleForBluetooth()||!allowed()||!bluetoothEnabled())return;handler.removeCallbacks(reconnect);stopScan();closeGatt();failQueue("Reconnecting to the controller.");deviceAddress=address;deviceId=address;if(current==this)RecordingVoiceController.controllerConnecting(address);Settings.prefs(this).edit().putString("device",address).apply();lastError="";state("Connecting…");
        try{gatt=adapter.getRemoteDevice(address).connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);}catch(RuntimeException e){recoverConnection("Bluetooth connection could not start. Reconnecting…");return;}
        if(gatt==null){recoverConnection("Bluetooth connection could not start. Reconnecting…");return;}
        BluetoothGatt attempt=gatt;handler.postDelayed(()->{if(visibleForBluetooth()&&gatt==attempt&&!ready)recoverConnection("Controller connection timed out. Reconnecting…");},CONNECT_TIMEOUT_MS);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state){handler.post(()->{if(!visibleForBluetooth()||g!=gatt||!allowed())return;if(code!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){recoverConnection("Controller disconnected. Reconnecting…");return;}if(state==BluetoothProfile.STATE_CONNECTED){BreezeService.this.state("Discovering controller…");try{if(!g.discoverServices())recoverConnection("Could not discover the controller. Reconnecting…");}catch(RuntimeException e){recoverConnection("Bluetooth connection expired. Reconnecting…");}}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){handler.post(()->{
            if(!visibleForBluetooth()||g!=gatt||!allowed())return;BluetoothGattService s=g.getService(Protocol.SERVICE);if(code!=BluetoothGatt.GATT_SUCCESS||s==null){recoverConnection("Controller service unavailable. Reconnecting…");return;}rx=s.getCharacteristic(Protocol.RX);BluetoothGattCharacteristic tx=s.getCharacteristic(Protocol.TX);if(rx==null||tx==null){recoverConnection("Controller firmware is incompatible.");return;}BluetoothGattDescriptor ccc=tx.getDescriptor(Protocol.CCC);
            try{if(ccc==null||!g.setCharacteristicNotification(tx,true)){recoverConnection("Could not enable live readings. Reconnecting…");return;}int result=g.writeDescriptor(ccc,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);if(result!=BluetoothStatusCodes.SUCCESS)recoverConnection("Could not subscribe to controller ("+result+"). Reconnecting…");}catch(RuntimeException e){recoverConnection("Bluetooth connection expired. Reconnecting…");}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){handler.post(()->{if(!visibleForBluetooth()||g!=gatt||!allowed()||!Protocol.CCC.equals(d.getUuid())||!Protocol.TX.equals(d.getCharacteristic().getUuid()))return;if(code==BluetoothGatt.GATT_SUCCESS){ready=true;readySince=SystemClock.elapsedRealtime();lastError="";state("Connected");command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)download();});}else recoverConnection("Notification subscription failed. Reconnecting…");});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){if(!Protocol.TX.equals(c.getUuid()))return;byte[] b=value.clone();handler.post(()->{if(visibleForBluetooth()&&g==gatt)receive(b);});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(()->{if(visibleForBluetooth()&&g==gatt&&Protocol.RX.equals(c.getUuid()))onWriteCompleted(code);});}
    };
    void onWriteCompleted(int code){if(!visibleForBluetooth()||pending==null||!pending.submitted)return;if(code!=BluetoothGatt.GATT_SUCCESS)recoverConnection("Controller write failed ("+code+"). Reconnecting…");else{pending.written=true;completeCommand();}}
    void receive(byte[] b){
        if(!visibleForBluetooth()||b.length!=20)return;
        try {
            ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if(b[0]==4){sensorFirmware=Protocol.sensorFirmware(b);thirdProbeSupported=Protocol.thirdProbeFirmware(b);telemetryIntervalMs=Protocol.telemetryIntervalMs(b);irFirmware=sensorFirmware&&(b[4]&255)>=7;irReceiverTestSupported=irFirmware&&(b[8]&2)!=0;receiverMonitorSupported=irFirmware&&(b[8]&4)!=0;logicSupported=irFirmware&&(b[8]&8)!=0&&(b[10]&255)>=2;controllerFirmware=(b[5]&255)+"."+(b[6]&255)+"."+(b[7]&255);if(logicSupported&&!logicAppliedForConnection&&!logicUploading&&freshFan()){logicAppliedForConnection=true;if(homeLogicPaused())command(seq->Protocol.fan(Protocol.AUTO,0,0,seq),ok->{logicStatus=ok?"Logic paused.":lastError;changed();});else applyLogicProfile(Settings.logicProfile(this));}changed();}
            else if(b[0]==5){Protocol.FanState previous=fan;fan=Protocol.decodeFan(b,System.currentTimeMillis());if(previous==null||previous.sendCounter!=fan.sendCounter)FanCommandTrace.record(this,"controller sendCounter="+fan.sendCounter+" lastSent="+fan.lastSent+" speed="+fan.speed+" synced="+fan.synced()+" error="+fan.error);anchorCorrectionPending=Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false);if(fan.synced()&&!anchorCorrectionPending){SharedPreferences.Editor estimate=Settings.prefs(this).edit().putInt("fanAnchor",fan.speed);if(fan.speed>0)estimate.putInt("fanLastKnownOnSpeed",fan.speed);estimate.apply();}else if(!fan.synced())Settings.prefs(this).edit().remove("fanLastKnownOnSpeed").apply();changed();}
            else if(b[0]==7){receiveIrReceiverTest(Protocol.decodeIrReceiverTest(b,System.currentTimeMillis()));}
            else if(b[0]==8){receiveReceiverInput(Protocol.decodeReceiverInput(b,System.currentTimeMillis()));}
            else if(b[0]==9){logic=Protocol.decodeLogic(b,System.currentTimeMillis());if(homeLogicTogglePending&&logic.automatic()==homeLogicToggleDesired)homeLogicTogglePending=false;if(!logicUploading)logicStatus=logic.waitingSync()?"Automatic control is on. Confirm the fan's current speed to synchronize.":logic.noBody()?"Waiting for a valid Highest body reading.":logic.error!=0?Protocol.error(logic.error):logic.automatic()?"Automatic · "+(logic.rule==255?"between room rules · holding current setting":logic.rule==0?"warm-room rule":"cool-room rule")+(logic.boosting()?" · warming boost":"")+" · target "+(logic.target==0?"Off":FanSetting.label(logic.target)):"Manual override · save the profile or return to automatic.";changed();}
            else if(b[0]==1){latest=Protocol.decode(b,System.currentTimeMillis(),thirdProbeSupported);lastPacket=SystemClock.elapsedRealtime();if(current==this)RecordingVoiceController.onTelemetry(this,latest.active());if(pending!=null&&pending.submitted&&latest.ack==pending.seq){pending.acknowledged=true;pending.error=latest.commandError;completeCommand();}changed();}
            else if(b[0]==2||b[0]==6||b[0]==10||b[0]==11){long index=Integer.toUnsignedLong(p.getInt(2)),cursor=store.cursor(deviceId);if(index==cursor){long id=store.ingest(deviceId,b);if(id>=0)selectedSession=id;transfer="Saved "+store.cursor(deviceId)+" controller records to this phone";if(index%15==0)changed();}else if(index>cursor&&!syncQueued)download();}
            else if(b[0]==3){deviceId=deviceAddress+":"+Integer.toUnsignedLong(p.getInt(15));controllerBase=Integer.toUnsignedLong(p.getInt(2));controllerCount=Integer.toUnsignedLong(p.getInt(6));controllerFreeBytes=Integer.toUnsignedLong(p.getInt(10));controllerStorageReady=b[14]!=0;if(current==this)RecordingVoiceController.onRecordingMetadata(this,deviceId,controllerCount,b[1]!=0);long cursor=store.cursor(deviceId);if(cursor<controllerBase){store.setCursor(deviceId,controllerBase);lastError="Earlier records were already transferred elsewhere. Downloading remaining records.";cursor=controllerBase;}
                if(cursor==controllerCount){syncQueued=false;transfer=controllerStorageReady&&controllerBase==controllerCount?"Downloaded and confirmed • controller storage ready":"All "+cursor+" records saved on this phone";if(controllerBase<controllerCount&&!retireQueued){retireQueued=true;final long saved=cursor;command(s->Protocol.retire(saved,s),ok->{retireQueued=false;if(ok)transfer=controllerBase>=saved&&controllerStorageReady?"Downloaded and confirmed • controller storage ready":"Records saved on phone • waiting for controller storage recovery";changed();});}}
                else transfer="Downloading records • "+Math.max(0,cursor-controllerBase)+" / "+(controllerCount-controllerBase);
                changed();
            }
        }catch(Exception e){lastError="Could not save/decode records: "+safeMessage(e)+". Controller records retained.";changed();}
    }
    public void command(IntFunction<byte[]> make,Result done){enqueue(make,done,false);}
    private void enqueue(IntFunction<byte[]> make,Result done,boolean priority){if(!visibleForBluetooth()||!ready){lastError="Keep Breeze open and connect to the controller first.";changed();if(done!=null)done.done(false);return;}seq=seq%65535+1;Pending item=new Pending(make.apply(seq),seq,done);if(priority)queue.addFirst(item);else queue.add(item);pump();changed();}
    private void completeCommand(){if(pending==null||!pending.written||!pending.acknowledged)return;Pending done=pending;pending=null;if(done.error!=0){failQueue(Protocol.error(done.error));if(done.done!=null)done.done.done(false);}else{lastError="";if(done.done!=null)done.done.done(true);}pump();changed();}
    boolean transportAvailable(){return gatt!=null&&rx!=null;}
    int submitWrite(byte[] bytes){return gatt.writeCharacteristic(rx,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);}
    private void pump(){
        if(!visibleForBluetooth())return;
        if(!allowed()){closeGatt();failQueue("Nearby devices permission was revoked.");return;}
        if(pending!=null||queue.isEmpty()||!ready)return;
        if(!transportAvailable()){recoverConnection("Bluetooth connection unavailable. Reconnecting…");return;}
        pending=queue.remove();Pending sent=pending;BluetoothGatt connection=gatt;sent.deadline=SystemClock.elapsedRealtime()+COMMAND_TIMEOUT_MS;
        handler.postDelayed(()->commandTimedOut(sent,connection),COMMAND_TIMEOUT_MS);submitPending(sent,connection);
    }
    private void commandTimedOut(Pending sent,BluetoothGatt connection){if(visibleForBluetooth()&&pending==sent&&gatt==connection)recoverConnection("Controller did not confirm the command. Reconnecting; check its state before retrying.");}
    private void submitPending(Pending sent,BluetoothGatt connection){
        if(!visibleForBluetooth()||pending!=sent||gatt!=connection||!ready||sent.submitted)return;
        if(!allowed()){closeGatt();failQueue("Nearby devices permission was revoked.");return;}
        if(SystemClock.elapsedRealtime()>=sent.deadline){commandTimedOut(sent,connection);return;}
        try{
            int result=submitWrite(sent.bytes);
            if(result==BluetoothStatusCodes.SUCCESS){sent.submitted=true;return;}
            if(result==BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY){handler.postDelayed(()->submitPending(sent,connection),BUSY_RETRY_MS);return;}
            recoverConnection("Bluetooth connection unavailable ("+result+"). Reconnecting…");
        }catch(RuntimeException e){android.util.Log.w(TAG,"Bluetooth write could not be submitted",e);recoverConnection("Bluetooth connection expired. Reconnecting…");}
    }
    private void failQueue(String reason){List<Pending> failed=new ArrayList<>();if(pending!=null)failed.add(pending);failed.addAll(queue);pending=null;queue.clear();syncQueued=false;retireQueued=false;lastError=reason;for(Pending item:failed)if(item.done!=null)item.done.done(false);changed();}
    private static String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message;}
    public void download(){if(!visibleForBluetooth()||syncQueued||!ready)return;syncQueued=true;long from=store.cursor(deviceId);command(s->Protocol.sync(from,s),ok->{if(!ok)syncQueued=false;});handler.postDelayed(()->{if(visibleForBluetooth()&&syncQueued){syncQueued=false;download();}},30000);}
    public void startSession(){
        if(!sensorFirmware){lastError="Update the controller to sensor firmware first.";recordingStartFailed();changed();return;}
        if(!fresh()){lastError="Wait for a fresh controller reading.";recordingStartFailed();changed();return;}
        if(latest.active()){lastError="A recording is already running on the controller.";recordingStartFailed();changed();return;}
        if(!latest.storageReady()){lastError="Controller storage is unavailable. Saving its records to this phone before recording can restart.";recordingStartFailed();download();changed();return;}
        command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)command(s->Protocol.control(Protocol.START_RECORDING,0,s),started->{if(started)download();else recordingStartFailed();});else recordingStartFailed();});
    }
    private void recordingStartFailed(){if(current==this)RecordingVoiceController.startFailed(this);}
    public void control(int action,int value){if(!sensorFirmware||(action!=Protocol.STOP_RECORDING&&action!=6)){lastError="Sensor firmware required for this action.";changed();return;}command(s->Protocol.control(action,value,s),ok->{if(ok&&action==Protocol.STOP_RECORDING)download();});}
    public boolean freshFan(){return fresh()&&irFirmware&&fan!=null&&freshStatus(fan.time);}
    public boolean freshLogic(){return freshFan()&&logic!=null&&freshStatus(logic.time);}
    private boolean homeLogicPaused(){return Settings.prefs(this).getBoolean("homeLogicPaused",false);}
    public boolean homeLogicReady(){return manualIrReady()&&logicSupported&&freshLogic()&&!homeLogicTogglePending;}
    public boolean setHomeLogicEnabled(boolean enabled){
        if(!homeLogicReady()){lastError="Wait for the controller to finish preparing.";changed();return false;}
        homeLogicTogglePending=true;homeLogicToggleDesired=enabled;final int generation=++homeLogicToggleGeneration;
        if(enabled&&Settings.prefs(this).getBoolean("homeLogicProfileDeferred",false)){boolean accepted=applyLogicProfileExplicit(Settings.logicProfile(this));if(!accepted)homeLogicTogglePending=false;return accepted;}
        command(seq->Protocol.fan(Protocol.AUTO,enabled?1:0,0,seq),ok->{if(generation!=homeLogicToggleGeneration)return;if(ok){Settings.prefs(this).edit().putBoolean("homeLogicPaused",!enabled).apply();logicStatus=enabled?"Waiting for confirmed Logic state…":"Logic paused.";}else homeLogicTogglePending=false;changed();});return true;
    }
    public boolean manualIrReady(){return freshFan()&&!irReceiverTestActive()&&fan.learning==255&&!logicUploading&&visibleForBluetooth()&&ready&&allowed()&&transportAvailable()&&pending==null&&queue.isEmpty();}
    public boolean fanWritePending(){if(pending!=null&&pending.bytes[1]==6)return true;for(Pending item:queue)if(item.bytes[1]==6)return true;return false;}
    public boolean irReceiverTestActive(){return irReceiverTestRunning||irReceiverTestRequested;}
    public boolean startIrReceiverTest(){
        if(irReceiverTestActive()){lastError="The LED receiver test is already running.";changed();return false;}
        if(!freshFan()){lastError="Wait for the controller to connect and send fresh readings.";changed();return false;}
        if(!irReceiverTestSupported){lastError="Update the controller firmware to enable the LED receiver test.";changed();return false;}
        if(fan.learning!=255||fan.busy()||fanWritePending()){lastError="Wait for learning or the current fan command to finish.";changed();return false;}
        irReceiverTestRequested=true;irReceiverPreviousTestId=irReceiverTest==null?-1:irReceiverTest.testId;final int generation=++irReceiverTestGeneration;
        irReceiverTestStatus="LED receiver test requested. Aim the LED at the receiver; the controller starts in 5 seconds.";lastError="";changed();
        command(s->Protocol.fan(Protocol.IR_RECEIVER_TEST,0,0,s),ok->{
            if(generation!=irReceiverTestGeneration)return;
            if(!ok){irReceiverTestRequested=false;irReceiverTestRunning=false;irReceiverTestStatus="LED receiver test could not start: "+lastError;changed();}
        });
        handler.postDelayed(()->{if(generation==irReceiverTestGeneration&&irReceiverTestActive()){irReceiverTestRequested=false;irReceiverTestRunning=false;irReceiverTestStatus="LED receiver test result unavailable: controller timed out. Reconnect before retrying.";lastError=irReceiverTestStatus;changed();}},25000);
        return true;
    }
    private void receiveIrReceiverTest(Protocol.IrReceiverTest result){
        if(irReceiverTestRequested&&result.testId==irReceiverPreviousTestId)return;
        irReceiverTest=result;
        if(result.state!=0){irReceiverTestRequested=false;irReceiverTestRunning=result.active();irReceiverTestStatus=result.description();}
        else if(!irReceiverTestRequested){irReceiverTestRunning=false;if(irReceiverTestStatus.isEmpty())irReceiverTestStatus=result.description();}
        android.util.Log.i(TAG,"LED receiver test: id="+result.testId+" state="+result.state+" attempts="+result.attempts+" matched="+result.matched+" frames="+result.framesSeen+" error="+result.error);changed();
    }
    private void receiveReceiverInput(Protocol.ReceiverInput input){
        if(!input.hasInput()){if(receiverInput==null){receiverInput=input;changed();}return;}
        if(input.counter==receiverMonitorCounter)return;
        receiverMonitorCounter=input.counter;receiverInput=input;
        if(input.duringLedSend())lastLedReceiverInput=input;else lastExternalReceiverInput=input;
        android.util.Log.i(TAG,"Receiver input #"+input.counter+": "+input.source()+" · "+input.label()+" · "+input.details());changed();
    }
    public boolean fanCommand(int action,int value,int argument){
        if(irReceiverTestActive()){if(action==Protocol.SEND_IR)return rejectManualIr("wait for the LED receiver test to finish.");lastError="Wait for the LED receiver test to finish.";changed();return false;}
        if(!freshFan()){if(action==Protocol.SEND_IR)return rejectManualIr("wait for fresh IR-controller telemetry.");lastError="Wait for fresh IR-controller telemetry.";changed();return false;}
        if(action==Protocol.SEND_IR){
            try{Protocol.fan(action,value,argument,1);}catch(IllegalArgumentException error){return rejectManualIr(error.getMessage());}
            if(fan.learning!=255)return rejectManualIr("learning is active.");
            if(!fan.has(value))return rejectManualIr("learn "+Protocol.COMMANDS[value]+" first.");
            return sendManualIr(value);
        }
        if(fanWritePending()){lastError="Wait for the controller to confirm the previous command.";changed();return false;}
        try{Protocol.fan(action,value,argument,1);command(s->Protocol.fan(action,value,argument,s),ok->{if(ok&&action==Protocol.SYNC_SPEED&&logicSupported)resumeLogic();changed();});return true;}catch(IllegalArgumentException e){lastError=e.getMessage();changed();return false;}
    }
    public boolean homeFanCommand(int slot,Integer draft,HomeFanResult done){
        int correctionGeneration=anchorCorrectionGeneration;long draftRevision=Settings.prefs(this).getLong("homeFanDraftRevision",0);boolean needsConfirmation=Settings.prefs(this).getBoolean("homeFanDraftNeedsConfirmation",false);
        Integer corrected=draft==null||needsConfirmation?null:HomeFanCorrection.afterManual(slot,draft,Settings.prefs(this).getInt("fanLastKnownOnSpeed",-1));
        Result previous=homeManualCompletion;
        homeManualCompletion=ok->{if(!ok){if(draft!=null)quarantineHomeDraft(draftRevision);if(done!=null)done.done(false,null);return;}if(correctionGeneration!=anchorCorrectionGeneration){if(done!=null)done.done(true,null);return;}if(needsConfirmation){lastError="Confirm the current fan setting with Done before correcting further commands.";changed();if(done!=null)done.done(true,null);return;}if(draft==null){if(done!=null)done.done(true,null);return;}if(corrected==null){quarantineHomeDraft(draftRevision);lastError="Check the current fan setting after "+Protocol.COMMANDS[slot]+" and apply it with Done.";changed();if(done!=null)done.done(true,null);return;}setHomeFanAnchor(corrected,slot==0&&draft>0?draft:null,synced->{if(!synced)quarantineHomeDraft(draftRevision);if(done!=null)done.done(true,synced?corrected:null);});};
        try{return fanCommand(Protocol.SEND_IR,slot,0);}finally{homeManualCompletion=previous;}
    }
    private void quarantineHomeDraft(long revision){if(Settings.prefs(this).getLong("homeFanDraftRevision",0)==revision)Settings.prefs(this).edit().putBoolean("homeFanDraftNeedsConfirmation",true).apply();}
    public boolean setHomeFanAnchor(int speed,Result done){
        long revision=Settings.prefs(this).getLong("homeFanDraftRevision",0);
        return setHomeFanAnchor(speed,null,ok->{if(ok&&revision==Settings.prefs(this).getLong("homeFanDraftRevision",0))Settings.prefs(this).edit().remove("homeFanDraftNeedsConfirmation").apply();if(done!=null)done.done(ok);});
    }
    private boolean setHomeFanAnchor(int speed,Integer retainedOn,Result done){
        if(!FanSetting.valid(speed)||!freshFan()){lastError="Connect the controller and enter 0, 1–9, or H.";changed();if(done!=null)done.done(false);return false;}
        anchorCorrectionPending=true;final int correction=++anchorCorrectionGeneration;Settings.prefs(this).edit().putInt("fanAnchor",speed).putBoolean("fanAnchorCorrectionPending",true).apply();
        if(retainedOn!=null)command(seq->Protocol.fan(Protocol.SYNC_SPEED,retainedOn,1,seq),ok->{if(correction!=anchorCorrectionGeneration)return;if(!ok){Settings.prefs(this).edit().remove("fanLastKnownOnSpeed").apply();if(done!=null)done.done(false);changed();return;}Settings.prefs(this).edit().putInt("fanLastKnownOnSpeed",retainedOn).apply();finishHomeFanAnchor(speed,correction,done);});
        else finishHomeFanAnchor(speed,correction,done);return true;
    }
    private void finishHomeFanAnchor(int speed,int correction,Result done){
        command(seq->Protocol.fan(Protocol.SYNC_SPEED,speed,1,seq),ok->{if(correction!=anchorCorrectionGeneration)return;if(ok){anchorCorrectionPending=false;SharedPreferences.Editor estimate=Settings.prefs(this).edit().putBoolean("fanAnchorCorrectionPending",false);if(speed>0)estimate.putInt("fanLastKnownOnSpeed",speed);estimate.apply();}else Settings.prefs(this).edit().remove("fanLastKnownOnSpeed").apply();if(done!=null)done.done(ok);changed();});
    }
    public boolean applyLogicProfile(LogicProfile profile){
        final LogicProfile snapshot=LogicProfile.deserialize(profile.serialize());
        if(homeLogicPaused()&&!logicExplicitResumeRequested){Settings.prefs(this).edit().putBoolean("homeLogicProfileDeferred",true).apply();logicStatus="Profile saved on phone · Logic paused.";changed();return false;}
        if(!freshFan()||!logicSupported){logicStatus="Saved · automatic profile will apply when the updated controller connects.";changed();return false;}
        if(logicUploading){nextLogicProfile=snapshot;nextLogicResumeAfterUpload=logicExplicitResumeRequested;logicStatus="Applying the latest saved profile…";changed();return true;}
        logicUploading=true;logicResumeAfterUpload=logicExplicitResumeRequested;logicStatus="Applying "+snapshot.name+"…";final int generation=++logicUploadGeneration;
        // Only an explicit unsent correction can establish the fan's current physical setting.
        if(Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false))command(seq->Protocol.fan(Protocol.SYNC_SPEED,Math.max(0,Math.min(FanSetting.MAX,Settings.prefs(this).getInt("fanAnchor",1))),1,seq),ok->{if(generation!=logicUploadGeneration)return;if(ok){anchorCorrectionPending=false;Settings.prefs(this).edit().putBoolean("fanAnchorCorrectionPending",false).apply();uploadLogicPart(snapshot,0,generation);}else{logicUploading=false;logicStatus=lastError;changed();}});
        else uploadLogicPart(snapshot,0,generation);changed();return true;
    }
    public boolean applyLogicProfileExplicit(LogicProfile profile){boolean previous=logicExplicitResumeRequested;logicExplicitResumeRequested=true;try{return applyLogicProfile(profile);}finally{logicExplicitResumeRequested=previous;}}
    private void uploadLogicPart(LogicProfile profile,int part,int generation){
        if(generation!=logicUploadGeneration)return;
        IntFunction<byte[]> make=switch(part){case 0->seq->Protocol.logicGlobal(profile,seq);case 1->seq->Protocol.logicRule(profile,0,seq);case 2->seq->Protocol.logicRule(profile,1,seq);case 3->seq->Protocol.logicBoost(profile,0,seq);case 4->seq->Protocol.logicBoost(profile,1,seq);default->Protocol::logicCommit;};
        command(make,ok->{if(generation!=logicUploadGeneration)return;if(!ok){logicUploading=false;homeLogicTogglePending=false;logicStatus="Profile saved on phone; controller update failed: "+lastError;changed();return;}if(part<5){uploadLogicPart(profile,part+1,generation);return;}if(logicResumeAfterUpload)Settings.prefs(this).edit().putBoolean("homeLogicPaused",false).putBoolean("homeLogicProfileDeferred",false).apply();logicUploading=false;logicAppliedForConnection=true;logicStatus="Automatic control · "+profile.name;if(nextLogicProfile!=null){LogicProfile latest=nextLogicProfile;boolean resume=nextLogicResumeAfterUpload;nextLogicProfile=null;nextLogicResumeAfterUpload=false;if(resume)applyLogicProfileExplicit(latest);else applyLogicProfile(latest);}changed();});
    }
    public void resumeLogic(){if(homeLogicPaused()||!freshFan()||!logicSupported)return;command(seq->Protocol.fan(Protocol.AUTO,1,0,seq),ok->{logicStatus=ok?"Automatic control resumed.":lastError;changed();});}
    public void setFanAnchor(int speed){
        if(!FanSetting.valid(speed)){lastError="Fan setting must be 0, 1–9, or H; 0 is Off.";changed();return;}
        anchorCorrectionPending=true;final int correction=++anchorCorrectionGeneration;Settings.prefs(this).edit().putInt("fanAnchor",speed).putBoolean("fanAnchorCorrectionPending",true).apply();
        if(freshFan())command(seq->Protocol.fan(Protocol.SYNC_SPEED,speed,1,seq),ok->{if(correction!=anchorCorrectionGeneration)return;if(ok){anchorCorrectionPending=false;Settings.prefs(this).edit().putBoolean("fanAnchorCorrectionPending",false).apply();if(logicSupported)resumeLogic();}changed();});else changed();
    }
    private boolean rejectManualIr(String reason){lastError="Not sent—"+reason;FanCommandTrace.record(this,lastError);fanCommandStatus=lastError;changed();return false;}
    private boolean sendManualIr(int slot){
        // A manual tap is useful now; never put it behind another command or retry it later.
        if(logicUploading||pending!=null||!queue.isEmpty())return rejectManualIr("controller busy. Tap again.");
        if(!visibleForBluetooth()||!ready||!allowed()||!transportAvailable())return rejectManualIr("controller disconnected.");
        seq=seq%65535+1;
        final int requestedSeq=seq;
        FanCommandTrace.record(this,"Manual IR request seq="+requestedSeq+" slot="+slot+" previousSendCounter="+(fan==null?-1:fan.sendCounter)+" speed="+(fan==null?-1:fan.speed));
        Result completion=homeManualCompletion;
        Pending sent=new Pending(Protocol.fan(Protocol.SEND_IR,slot,0,seq),seq,ok->{FanCommandTrace.record(this,"Manual IR acknowledgement seq="+requestedSeq+" slot="+slot+" accepted="+ok+" error="+lastError);fanCommandStatus=ok?"Controller sent "+Protocol.COMMANDS[slot]+".":"Send failed: "+lastError;if(completion!=null)completion.done(ok);changed();});
        BluetoothGatt connection=gatt;pending=sent;
        try{
            int result=submitWrite(sent.bytes);
            if(result!=BluetoothStatusCodes.SUCCESS){
                pending=null;
                if(result==BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)return rejectManualIr("Bluetooth busy. Tap again.");
                recoverConnection("Bluetooth connection unavailable ("+result+"). Reconnecting…");return rejectManualIr("controller connection unavailable ("+result+").");
            }
            sent.submitted=true;sent.deadline=SystemClock.elapsedRealtime()+COMMAND_TIMEOUT_MS;
            handler.postDelayed(()->commandTimedOut(sent,connection),COMMAND_TIMEOUT_MS);
            lastError="";fanCommandStatus="Sending "+Protocol.COMMANDS[slot]+"…";changed();return true;
        }catch(RuntimeException error){pending=null;android.util.Log.w(TAG,"Manual IR write could not be submitted",error);recoverConnection("Bluetooth connection expired. Reconnecting…");return rejectManualIr("controller connection expired.");}
    }
    public void startContactTest(){
        if(irReceiverTestActive()){lastError="Wait for the LED receiver test to finish.";recordingStartFailed();changed();return;}
        if(!freshFan()){lastError="Connect the updated IR controller first.";recordingStartFailed();changed();return;}
        if(latest.active()){lastError="Stop the current recording before starting a probe test.";recordingStartFailed();changed();return;}
        command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)command(s->Protocol.fan(Protocol.CONTACT_TEST,0,0,s),started->{if(started)download();else recordingStartFailed();});else recordingStartFailed();});
    }
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("status="+status+" ready="+ready+" firmware="+controllerFirmware);
        out.println("visibleForBluetooth="+visibleForBluetooth()+" uiSession="+uiSession+" stoppedForUi="+stoppedForUi+" backgroundRemainingMs="+uiConnection.backgroundRemainingMs());
        out.println("scanning="+scanning+" gattPresent="+(gatt!=null)+" reconnectScheduled="+handler.hasCallbacks(reconnect));
        out.println("packetAgeMs="+(lastPacket==0?-1:SystemClock.elapsedRealtime()-lastPacket)+" queued="+queue.size()+" pending="+(pending==null?"none":pending.seq+" submitted="+pending.submitted));
        out.println("telemetryIntervalMs="+telemetryIntervalMs+" telemetryFreshnessMs="+telemetryFreshnessMs()+" linkTimeoutMs="+Protocol.telemetryLinkTimeoutMs(telemetryIntervalMs));
        out.println("controllerRecords="+controllerBase+".."+controllerCount+" freeBytes="+controllerFreeBytes+" storageReady="+controllerStorageReady);
        out.println("phoneCursor="+(store==null?-1:store.cursor(deviceId))+" transfer="+transfer);
        out.println("telemetryError="+(latest==null?-1:latest.error)+" commandError="+(latest==null?-1:latest.commandError)+" recording="+(latest!=null&&latest.active())+" controllerUptimeSeconds="+(latest==null?-1:latest.uptime));
        if(latest!=null)out.println("bodyD0C="+latest.probe+" bodyD1C="+(fan==null?Double.NaN:fan.secondProbe)+" bodyD2C="+latest.thirdProbe+" roomC="+latest.room);
        if(fan!=null)out.println("fanSynced="+fan.synced()+" speed="+fan.speed+" target="+fan.target+" sendCounter="+fan.sendCounter+" lastSent="+fan.lastSent+" fanError="+fan.error+" manualIrReady="+manualIrReady());
        out.println("irReceiverTestSupported="+irReceiverTestSupported+" active="+irReceiverTestActive()+" status="+irReceiverTestStatus);
        if(irReceiverTest!=null)out.println("irReceiverTestId="+irReceiverTest.testId+" state="+irReceiverTest.state+" attempts="+irReceiverTest.attempts+" matched="+irReceiverTest.matched+" framesSeen="+irReceiverTest.framesSeen+" overflows="+irReceiverTest.overflows+" lastRawLength="+irReceiverTest.lastRawLength+" rxIdleLevel="+irReceiverTest.rxIdleLevel+" carrier="+irReceiverTest.carrier+" txPin="+irReceiverTest.txPin+" rxPin="+irReceiverTest.rxPin+" error="+irReceiverTest.error);
        out.println("receiverMonitorSupported="+receiverMonitorSupported+" fanCommandStatus="+fanCommandStatus);
        if(receiverInput!=null)out.println("receiverInputCounter="+receiverInput.counter+" hasInput="+receiverInput.hasInput()+" source="+receiverInput.source()+" label="+receiverInput.label()+" protocol="+receiverInput.protocol+" bits="+receiverInput.bits+" value="+Long.toUnsignedString(receiverInput.value,16)+" rawLength="+receiverInput.rawLength+" flags="+receiverInput.flags);
        out.println("lastError="+lastError);out.println("fanCommandTrace:\n"+FanCommandTrace.read(this));
        out.println("voice="+RecordingVoiceController.describe());
    }
    private void closeGatt(){fanCommandStatus="";logicUploadGeneration++;logicUploading=false;logicAppliedForConnection=false;logicSupported=false;logic=null;logicExplicitResumeRequested=logicResumeAfterUpload=nextLogicResumeAfterUpload=homeLogicTogglePending=false;homeLogicToggleGeneration++;logicStatus="Automatic profile remains on the controller; reconnect to edit.";if(irReceiverTestActive()){irReceiverTestRequested=false;irReceiverTestRunning=false;irReceiverTestGeneration++;irReceiverTestStatus="LED receiver test result unavailable: controller disconnected.";}receiverMonitorSupported=false;receiverMonitorCounter=-1;ready=false;rx=null;latest=null;lastPacket=readySince=0;telemetryIntervalMs=Protocol.DEFAULT_TELEMETRY_INTERVAL_MS;sensorFirmware=false;thirdProbeSupported=false;irFirmware=false;irReceiverTestSupported=false;fan=null;controllerFreeBytes=-1;controllerStorageReady=false;controllerFirmware="unknown";BluetoothGatt old=gatt;gatt=null;if(old!=null){try{if(allowed())old.disconnect();}catch(RuntimeException e){android.util.Log.w(TAG,"Could not disconnect expired Bluetooth handle",e);}try{old.close();}catch(RuntimeException e){android.util.Log.w(TAG,"Could not close expired Bluetooth handle",e);}}}
    private void stopForUi(){
        stoppedForUi=true;
        handler.removeCallbacksAndMessages(null);stopScan();closeGatt();devices.clear();
        if(current==this)current=null;
        failQueue("Phone disconnected. Records remain on the controller.");status="Disconnected";
        stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    @Override public void onDestroy(){destroyed=true;unregisterReceiver(bluetoothState);stopScan();closeGatt();failQueue("Phone disconnected. Records remain on the controller.");handler.removeCallbacksAndMessages(null);if(store!=null)store.close();if(current==this)current=null;super.onDestroy();}
}
