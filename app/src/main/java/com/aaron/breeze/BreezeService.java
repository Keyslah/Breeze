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
    private static final String UI_SESSION="ui_session",FORCE_RECONNECT="force_reconnect",STOP_CONNECTION="stop_connection";
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
    public boolean doubleHitSupported=false;
    /** The controller's hardware timer owns the IR LED; the count is bursts since its boot that fell back to software timing. */
    public boolean irHardwareTimed=false;
    public int irSoftwareBursts=0;
    public String doubleHitStatus="Saved on phone · waiting for controller";
    private boolean doubleHitApplying;
    private Integer doubleHitApplied,doubleHitAttempted;
    private int doubleHitGeneration;
    public boolean logicSupported=false,logicUploading=false;
    public boolean logicWindowSupported=false,logicMultiWindowSupported=false;
    private boolean logicWindowApplied,logicClockApplying;
    private long logicClockAt,logicClockWall,logicClockAttempt;
    private String logicClockZone="";
    private int logicClockGeneration;
    /** The controller has Logic, but only the earlier threshold rules: it needs a firmware update for the range. */
    public boolean logicNeedsFirmware=false;
    /** Outcome of the latest attempt to send the range; controller status packets never replace it. */
    public String logicApplyNote="";
    public Protocol.LogicState logic;
    public String logicStatus="Waiting for the controller";
    private boolean logicAppliedForConnection=false;
    private int logicUploadGeneration=0;
    private LogicProfile nextLogicProfile;
    private boolean logicExplicitResumeRequested,logicResumeAfterUpload,nextLogicResumeAfterUpload;
    private boolean homeLogicTogglePending,homeLogicToggleDesired;
    private int homeLogicToggleGeneration;
    public boolean anchorCorrectionPending=false;
    private int anchorCorrectionGeneration=0;
    private int homeAnchorApplyingGeneration=-1,homeAnchorApplyingSpeed;
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
    private PhoneRecording phoneRecording;
    private final RecordingReconciler recordingReconciler=new RecordingReconciler();
    private boolean contactTestStarting;
    private RecordStorageQueue recordStorage;
    private int recordConnectionGeneration,metadataGeneration,downloadGeneration;
    private long phoneCursor=-1;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private DreoSynchronizer dreo;
    DriftDiagnostics drift;
    private String driftInitError="";
    private PowerManager.WakeLock dreoWakeLock;
    private final Runnable renewDreoWakeLock=this::updateDreoWakeLock;
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
    private final VoiceFanCommands voiceFanCommands=new VoiceFanCommands();
    private static class Pending {byte[] bytes;int seq,error;boolean submitted,written,acknowledged;long deadline;Result done;java.util.function.BooleanSupplier valid=()->true;Pending(byte[] b,int s,Result r){bytes=b;seq=s;done=r;}}
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
    /** Only an existing recording listener can retain an already-visible Bluetooth session. */
    void voiceActivityChanged(){
        BedtimeVoiceService voice=BedtimeVoiceService.current;
        uiConnection.setListening(voice!=null&&!voice.isShuttingDown()&&RecordingVoiceController.shouldRunService());
        if(created&&!stoppedForUi)updateBackgroundDisconnect();
    }
    private final Runnable backgroundDisconnect=this::updateBackgroundDisconnect;
    private void updateBackgroundDisconnect(){
        handler.removeCallbacks(backgroundDisconnect);
        uiConnection.setRecording(phoneRecording!=null&&phoneRecording.state().active);
        if(!visibleForBluetooth()){stopForUi();return;}
        long remaining=uiConnection.backgroundRemainingMs();
        if(remaining>0)handler.postDelayed(backgroundDisconnect,remaining);
    }
    @Override public void onCreate(){super.onCreate();created=true;store=new Store(this);adapter=getSystemService(BluetoothManager.class).getAdapter();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Controller connection",NotificationManager.IMPORTANCE_LOW));IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);filter.addAction(Intent.ACTION_SCREEN_OFF);registerReceiver(bluetoothState,filter,Context.RECEIVER_EXPORTED);
        // Meet Android's foreground contract before exposing a service that the UI can stop.
        startForeground(1,notification());foregroundStarted=true;current=this;phoneRecording=PhoneRecording.get(this);
        try{drift=new DriftDiagnostics(new java.io.File(getFilesDir(),"drift-diagnostics"),System::currentTimeMillis,SystemClock::elapsedRealtime);drift.continuityBreak("Breeze service started");}
        catch(Exception error){driftInitError=safeMessage(error);android.util.Log.w(TAG,"Drift diagnostics unavailable",error);}
    }
    private final Runnable periodicSync=new Runnable(){public void run(){if(!visibleForBluetooth()){stopForUi();return;}if(ready&&!syncQueued)download();if(visibleForBluetooth())handler.postDelayed(this,30000);}};
    private final Runnable connectionWatchdog=new Runnable(){public void run(){if(!visibleForBluetooth()){stopForUi();return;}checkConnectionHealth();refreshLogicClock();reconcileRecording();if(visibleForBluetooth())handler.postDelayed(this,3000);}};
    private final Runnable reconnect=this::tryReconnect;
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))setUiVisible(context,false);else handleBluetoothState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR));}};
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP_CONNECTION.equals(intent.getAction())){disconnectPhone(this);return START_NOT_STICKY;}
        long requested=intent==null?-1:intent.getLongExtra(UI_SESSION,-1);
        // Fulfil Android's foreground-start contract even for a start queued before onPause.
        startForeground(1,notification());foregroundStarted=true;
        if(!uiConnection.allows(requested)){if(!visibleForBluetooth())stopForUi();return START_NOT_STICKY;}
        uiSession=requested;stoppedForUi=false;current=this;voiceActivityChanged();
        if(dreo==null&&new DreoAccountStore(this).hasAccount())reloadDreoSync();
        handler.removeCallbacks(periodicSync);handler.removeCallbacks(connectionWatchdog);
        handler.postDelayed(periodicSync,30000);handler.postDelayed(connectionWatchdog,3000);
        updateBackgroundDisconnect();
        if(intent!=null&&intent.getBooleanExtra(FORCE_RECONNECT,false))reconnectNow();
        else if(gatt==null&&!scanning)tryReconnect();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification.Builder builder=new Notification.Builder(this,"connection").setSmallIcon(com.aaron.breeze.R.drawable.ic_breeze).setContentTitle("Breeze • "+status).setContentText(phoneRecording!=null&&phoneRecording.state().active?"Recording until you tap Stop" : dreo!=null&&dreo.active()?(dreoLockReady()?"Dreo state checks every 3 minutes, including while locked":"Dreo checks every 3 minutes · locked-phone setup required"):latest!=null&&latest.active()?"Controller is recording locally":"Connected for setup and record downloads").setContentIntent(open).setOngoing(true);if(dreo!=null&&dreo.active()){PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,BreezeService.class).setAction(STOP_CONNECTION),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);builder.addAction(new Notification.Action.Builder(null,"Disconnect",stop).build());}return builder.build();}
    /** Explicit account changes call this; ordinary Activity refreshes do not restart the poll timer. */
    public void reloadDreoSync(){
        if(dreo!=null){dreo.stop();dreo=null;}
        if(!destroyed&&!stoppedForUi&&new DreoAccountStore(this).hasAccount()){dreo=new DreoSynchronizer(this,handler);dreo.start();}
        dreoActivityChanged();changed();
    }
    public String dreoSummary(){return dreo==null?"Dreo not connected · using estimate":dreo.summary()+(dreo.active()&&!dreoLockReady()?" · locked-phone setup required":"");}
    public String dreoStatus(){
        String value=dreo==null?"Dreo not connected · using estimate":dreo.status();
        if(dreo!=null&&dreo.active())value+="\nChecks every 3 minutes. "+(dreoLockReady()?"Battery exemption granted; synchronization can continue while locked.":"Locked-phone setup required: allow unrestricted battery use for Breeze.");
        return value;
    }
    private boolean dreoLockReady(){return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());}
    void dreoActivityChanged(){
        uiConnection.setSynchronizing(dreo!=null&&dreo.active());updateDreoWakeLock();
        if(created&&!stoppedForUi)updateBackgroundDisconnect();
        if(foregroundStarted&&!destroyed)getSystemService(NotificationManager.class).notify(1,notification());
    }
    private void updateDreoWakeLock(){
        handler.removeCallbacks(renewDreoWakeLock);
        boolean active=!destroyed&&!stoppedForUi&&dreo!=null&&dreo.active();
        if(!active){if(dreoWakeLock!=null&&dreoWakeLock.isHeld())dreoWakeLock.release();return;}
        if(dreoWakeLock==null){dreoWakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Breeze:DreoSync");dreoWakeLock.setReferenceCounted(false);}
        dreoWakeLock.acquire(600000);handler.postDelayed(renewDreoWakeLock,480000);
    }
    private void stopDreoSync(){if(dreo!=null){dreo.stop();dreo=null;}uiConnection.setSynchronizing(false);updateDreoWakeLock();}
    private void invalidateDreo(){if(dreo!=null)dreo.invalidate();}
    private org.json.JSONObject driftContext(){
        try{return DriftDiagnostics.context(fan,logic,controllerFirmware,Settings.logicProfile(this).serialize(),logicAppliedForConnection,homeLogicPaused(),deviceAddress,java.time.ZoneId.systemDefault().getId())
                .put("fanFresh",freshFan()).put("pendingSequence",pending==null?0:pending.seq).put("queuedCommands",queue.size()).put("logicUploading",logicUploading)
                .put("logicApplyNote",logicApplyNote).put("profileDeferred",Settings.prefs(this).getBoolean("homeLogicProfileDeferred",false))
                .put("savedProfileIsAppliedProof",false).put("doubleHitSeconds",Settings.doubleHitTimeout(this));}
        catch(Exception error){return new org.json.JSONObject();}
    }
    void recordDreoPollStart(){if(drift!=null)drift.event("dreo_request","Three-minute check started",driftContext());}
    void recordDreoCheck(DreoReport report,boolean accepted,String reason){if(drift!=null)drift.poll(report,accepted,reason,driftContext());}
    void applyDreoReport(DreoReport report,Result done){
        if(!freshFan()||report.power==null||report.speed==null||report.mode==null||report.mode!=1){done.done(false);return;}
        int value=report.power?report.speed:0;
        boolean busy=pending!=null||!queue.isEmpty()||logicUploading||fan.learning!=255||fan.held()||fan.busy()||irReceiverTestActive()||anchorCorrectionPending;
        // Preserve an observed mismatch even when correction must wait. Only an idle match
        // can discard a window; an in-flight command makes matching inconclusive.
        if(drift!=null&&report.speed>=1&&report.speed<=9&&(!busy||fan.speed!=value))drift.compare(report,fan,driftContext());
        if(report.speed<1||report.speed>9||busy){done.done(false);return;}
        if(fan.synced()&&fan.speed==value){done.done(true);return;}
        // This packet only corrects the estimate. Do not call setFanAnchor(), which also resumes Logic.
        final int generation=anchorCorrectionGeneration;
        final int previousEstimate=fan.synced()?fan.speed:-1;
        final int irSendCounter=fan.sendCounter;
        command(seq->Protocol.fan(Protocol.SYNC_SPEED,value,1,seq),ok->{
            if(ok&&generation==anchorCorrectionGeneration){SharedPreferences preferences=Settings.prefs(this);SharedPreferences.Editor edit=preferences.edit().putInt("fanAnchor",value);if(value>0)edit.putInt("fanLastKnownOnSpeed",value);DreoCorrections.record(preferences,edit,previousEstimate,value,irSendCounter,report);edit.apply();}
            done.done(ok&&generation==anchorCorrectionGeneration);changed();
        });
    }
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
    void onWriteCompleted(int code){if(!visibleForBluetooth()||pending==null||!pending.submitted)return;if(drift!=null)drift.command("write_completed",pending.bytes,Integer.toString(code));if(code!=BluetoothGatt.GATT_SUCCESS)recoverConnection("Controller write failed ("+code+"). Reconnecting…");else{pending.written=true;completeCommand();}}
    void receive(byte[] b){
        if(!visibleForBluetooth()||b.length!=20)return;
        if(drift!=null)drift.packet(b);
        try {
            ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if(b[0]==4){sensorFirmware=Protocol.sensorFirmware(b);thirdProbeSupported=Protocol.thirdProbeFirmware(b);telemetryIntervalMs=Protocol.telemetryIntervalMs(b);irFirmware=sensorFirmware&&(b[4]&255)>=7;irReceiverTestSupported=irFirmware&&(b[8]&2)!=0;receiverMonitorSupported=irFirmware&&(b[8]&4)!=0;boolean logicFirmware=irFirmware&&(b[8]&8)!=0;logicSupported=logicFirmware&&(b[10]&255)>=3;logicNeedsFirmware=logicFirmware&&!logicSupported;logicWindowSupported=Protocol.logicWindowFirmware(b);logicMultiWindowSupported=Protocol.logicMultiWindowFirmware(b);controllerFirmware=(b[5]&255)+"."+(b[6]&255)+"."+(b[7]&255);doubleHitSupported=Protocol.doubleHitFirmware(b);irHardwareTimed=irFirmware&&b[13]==1;irSoftwareBursts=irFirmware?b[14]&255:0;syncDoubleHitTimeout();if(logicSupported&&!logicAppliedForConnection&&!logicUploading&&freshFan()){logicAppliedForConnection=true;if(homeLogicPaused())command(seq->Protocol.fan(Protocol.AUTO,0,0,seq),ok->{logicStatus=ok?"Logic paused.":lastError;changed();});else applyLogicProfile(Settings.logicProfile(this));}changed();}
            else if(b[0]==5){Protocol.FanState previous=fan;fan=Protocol.decodeFan(b,System.currentTimeMillis());if(previous!=null&&(previous.sendCounter!=fan.sendCounter||(previous.synced()&&!fan.synced())))invalidateDreo();if(previous==null||previous.sendCounter!=fan.sendCounter)FanCommandTrace.record(this,"controller sendCounter="+fan.sendCounter+" lastSent="+fan.lastSent+" speed="+fan.speed+" synced="+fan.synced()+" error="+fan.error);anchorCorrectionPending=Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false);if(fan.synced()&&!anchorCorrectionPending){SharedPreferences.Editor estimate=Settings.prefs(this).edit().putInt("fanAnchor",fan.speed);if(fan.speed>0)estimate.putInt("fanLastKnownOnSpeed",fan.speed);estimate.apply();}else if(!fan.synced())Settings.prefs(this).edit().remove("fanLastKnownOnSpeed").apply();changed();}
            else if(b[0]==7){receiveIrReceiverTest(Protocol.decodeIrReceiverTest(b,System.currentTimeMillis()));}
            else if(b[0]==8){receiveReceiverInput(Protocol.decodeReceiverInput(b,System.currentTimeMillis()));}
            else if(b[0]==9){logic=Protocol.decodeLogic(b,System.currentTimeMillis());if(homeLogicTogglePending&&logic.automatic()==homeLogicToggleDesired)homeLogicTogglePending=false;logicStatus=describeLogic(logic);changed();}
            else if(b[0]==1){latest=Protocol.decode(b,System.currentTimeMillis(),thirdProbeSupported);lastPacket=SystemClock.elapsedRealtime();if(current==this)RecordingVoiceController.onTelemetry(this,latest.active());if(pending!=null&&pending.submitted&&latest.ack==pending.seq){pending.acknowledged=true;pending.error=latest.commandError;completeCommand();}changed();}
            else if(b[0]==2||b[0]==6||b[0]==10||b[0]==11){saveRecord(b);}
            else if(b[0]==3){receiveMetadata(b,p);}
            reconcileRecording();
        }catch(Exception e){lastError="Could not save/decode records: "+safeMessage(e)+". Controller records retained.";changed();}
    }
    public void command(IntFunction<byte[]> make,Result done){enqueue(make,done,false);}
    private void recordingCommand(IntFunction<byte[]> make,java.util.function.BooleanSupplier valid,Result done){
        if(!visibleForBluetooth()||!ready){done.done(false);return;}
        seq=seq%65535+1;Pending item=new Pending(make.apply(seq),seq,done);item.valid=valid;queue.add(item);pump();
    }
    private void enqueue(IntFunction<byte[]> make,Result done,boolean priority){if(!visibleForBluetooth()||!ready){lastError="Keep Breeze open and connect to the controller first.";changed();if(done!=null)done.done(false);return;}seq=seq%65535+1;Pending item=new Pending(make.apply(seq),seq,done);if(drift!=null)drift.command("queued",item.bytes,"");if(priority)queue.addFirst(item);else queue.add(item);pump();changed();}
    private void completeCommand(){if(pending==null||!pending.written||!pending.acknowledged)return;Pending done=pending;pending=null;if(drift!=null)drift.command("acknowledged",done.bytes,"controller error="+done.error);if(done.error!=0){failQueue(Protocol.error(done.error));if(done.done!=null)done.done.done(false);}else{lastError="";if(done.done!=null)done.done.done(true);}pump();changed();}
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
        if(!sent.valid.getAsBoolean()){pending=null;if(sent.done!=null)sent.done.done(false);pump();return;}
        if(!allowed()){closeGatt();failQueue("Nearby devices permission was revoked.");return;}
        if(SystemClock.elapsedRealtime()>=sent.deadline){commandTimedOut(sent,connection);return;}
        try{
            int result=submitWrite(sent.bytes);
            if(drift!=null)drift.command("write_result",sent.bytes,Integer.toString(result));
            if(result==BluetoothStatusCodes.SUCCESS){sent.submitted=true;return;}
            if(result==BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY){handler.postDelayed(()->submitPending(sent,connection),BUSY_RETRY_MS);return;}
            recoverConnection("Bluetooth connection unavailable ("+result+"). Reconnecting…");
        }catch(RuntimeException e){android.util.Log.w(TAG,"Bluetooth write could not be submitted",e);recoverConnection("Bluetooth connection expired. Reconnecting…");}
    }
    private void failQueue(String reason){List<Pending> failed=new ArrayList<>();if(pending!=null)failed.add(pending);failed.addAll(queue);pending=null;queue.clear();syncQueued=false;retireQueued=false;lastError=reason;for(Pending item:failed){if(drift!=null)drift.command("failed",item.bytes,reason);if(item.done!=null)item.done.done(false);}changed();}
    private static String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message;}
    private RecordStorageQueue recordStorage(){
        if(recordStorage==null)recordStorage=new RecordStorageQueue(task->handler.post(task));
        return recordStorage;
    }
    private boolean currentRecordConnection(int generation,String archive){
        return !destroyed&&visibleForBluetooth()&&generation==recordConnectionGeneration&&archive.equals(deviceId);
    }
    private void recordStorageFailed(int generation,String archive,Exception error){
        if(!currentRecordConnection(generation,archive))return;
        lastError="Could not save/decode records: "+safeMessage(error)+". Controller records retained.";changed();
    }
    private void saveRecord(byte[] bytes){
        final byte[] record=bytes.clone();final String archive=deviceId;final int generation=recordConnectionGeneration;
        final long index=Integer.toUnsignedLong(ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
        final Store destination=store;
        recordStorage().submit(()->{
            long cursor=destination.cursor(archive),session=-1;
            if(index==cursor){session=destination.ingest(archive,record);cursor=destination.cursor(archive);}
            return new long[]{cursor,session};
        },saved->{
            if(!currentRecordConnection(generation,archive))return;
            phoneCursor=saved[0];
            if(saved[1]>=0){selectedSession=saved[1];transfer="Saved "+phoneCursor+" controller records to this phone";if(index%15==0)changed();}
            else if(index>phoneCursor&&!syncQueued)download();
        },error->recordStorageFailed(generation,archive,error));
    }
    private void receiveMetadata(byte[] bytes,ByteBuffer packet){
        String archive=deviceAddress+":"+Integer.toUnsignedLong(packet.getInt(15));
        if(!archive.equals(deviceId)){deviceId=archive;phoneCursor=-1;}
        controllerBase=Integer.toUnsignedLong(packet.getInt(2));controllerCount=Integer.toUnsignedLong(packet.getInt(6));
        controllerFreeBytes=Integer.toUnsignedLong(packet.getInt(10));controllerStorageReady=bytes[14]!=0;
        if(current==this)RecordingVoiceController.onRecordingMetadata(this,archive,controllerCount,bytes[1]!=0);
        final int generation=recordConnectionGeneration,metadata=++metadataGeneration;final long base=controllerBase;
        final Store destination=store;
        // This read follows every preceding ingest/commit, so retirement cannot outrun durable storage.
        recordStorage().submit(()->{
            long cursor=destination.cursor(archive);boolean skipped=cursor<base;
            if(skipped){destination.setCursor(archive,base);cursor=base;}
            return new long[]{cursor,skipped?1:0};
        },saved->{
            if(!currentRecordConnection(generation,archive)||metadata!=metadataGeneration)return;
            phoneCursor=saved[0];
            if(saved[1]!=0)lastError="Earlier records were already transferred elsewhere. Downloading remaining records.";
            if(phoneCursor==controllerCount){
                syncQueued=false;
                transfer=controllerStorageReady&&controllerBase==controllerCount?"Downloaded and confirmed • controller storage ready":"All "+phoneCursor+" records saved on this phone";
                if(controllerBase<controllerCount&&!retireQueued){
                    retireQueued=true;final long committed=phoneCursor;
                    command(s->Protocol.retire(committed,s),ok->{
                        if(!currentRecordConnection(generation,archive))return;
                        retireQueued=false;
                        if(ok)transfer=controllerBase>=committed&&controllerStorageReady?"Downloaded and confirmed • controller storage ready":"Records saved on phone • waiting for controller storage recovery";
                        changed();
                    });
                }
            }else transfer="Downloading records • "+Math.max(0,phoneCursor-controllerBase)+" / "+(controllerCount-controllerBase);
            changed();
        },error->recordStorageFailed(generation,archive,error));
        changed();
    }
    public void download(){
        if(!visibleForBluetooth()||syncQueued||!ready)return;
        syncQueued=true;final int generation=recordConnectionGeneration,request=++downloadGeneration;final String archive=deviceId;final Store destination=store;
        recordStorage().submit(()->destination.cursor(archive),from->{
            if(destroyed||!visibleForBluetooth()||generation!=recordConnectionGeneration||request!=downloadGeneration||!syncQueued||!ready)return;
            if(!archive.equals(deviceId)){syncQueued=false;download();return;}
            phoneCursor=from;
            command(s->Protocol.sync(from,s),ok->{if(request==downloadGeneration&&!ok)syncQueued=false;});
            handler.postDelayed(()->{if(!destroyed&&visibleForBluetooth()&&generation==recordConnectionGeneration&&request==downloadGeneration&&syncQueued){syncQueued=false;download();}},30000);
        },error->{if(!destroyed&&visibleForBluetooth()&&generation==recordConnectionGeneration&&request==downloadGeneration){syncQueued=false;recordStorageFailed(generation,deviceId,error);}});
    }
    void closeRecordStorage(){
        recordConnectionGeneration++;downloadGeneration++;syncQueued=false;
        final Store closing=store;store=null;
        if(recordStorage!=null)recordStorage.close(()->{if(closing!=null)closing.close();});
        else if(closing!=null){recordStorage().close(closing::close);}
    }
    public void startSession(){
        if(!sensorFirmware){lastError="Update the controller to sensor firmware first.";recordingStartFailed();changed();return;}
        if(!fresh()){lastError="Wait for a fresh controller reading.";recordingStartFailed();changed();return;}
        if(latest.active()){lastError="A recording is already running on the controller.";recordingStartFailed();changed();return;}
        if(!latest.storageReady()){lastError="Controller storage is unavailable. Saving its records to this phone before recording can restart.";recordingStartFailed();download();changed();return;}
        command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)command(s->Protocol.control(Protocol.START_RECORDING,0,s),started->{if(started)download();else recordingStartFailed();});else recordingStartFailed();});
    }
    void phoneRecordingChanged(PhoneRecording owner){
        if(phoneRecording!=owner||destroyed||stoppedForUi)return;
        if(visibleForBluetooth())updateBackgroundDisconnect();
        reconcileRecording();changed();
    }
    void reconcileRecording(){
        if(phoneRecording==null||contactTestStarting)return;
        RecordingIntent intent=phoneRecording.state();
        RecordingReconciler.Action action=recordingReconciler.next(intent,visibleForBluetooth()&&sensorFirmware&&fresh(),latest!=null&&latest.active(),latest!=null&&latest.storageReady(),SystemClock.elapsedRealtime());
        if(action==RecordingReconciler.Action.NONE)return;
        long ticket=recordingReconciler.attempt();
        java.util.function.BooleanSupplier valid=()->recordingReconciler.current(ticket)&&phoneRecording.state().revision==intent.revision;
        Result finished=ok->{if(!recordingReconciler.current(ticket))return;recordingReconciler.completed(ticket,SystemClock.elapsedRealtime());if(ok)download();reconcileRecording();changed();};
        if(action==RecordingReconciler.Action.STOP){recordingCommand(s->Protocol.control(Protocol.STOP_RECORDING,0,s),valid,finished);return;}
        recordingCommand(s->Protocol.clock(System.currentTimeMillis()/1000,s),valid,ok->{
            if(!ok||!valid.getAsBoolean()||!RecordingReconciler.mayStart(phoneRecording.state(),intent.revision)){finished.done(false);return;}
            recordingCommand(s->Protocol.control(Protocol.START_RECORDING,0,s),valid,finished);
        });
    }
    private void recordingStartFailed(){if(current==this)RecordingVoiceController.startFailed(this);}
    public void control(int action,int value){if(!sensorFirmware||(action!=Protocol.STOP_RECORDING&&action!=6)){lastError="Sensor firmware required for this action.";changed();return;}command(s->Protocol.control(action,value,s),ok->{if(ok&&action==Protocol.STOP_RECORDING)download();});}
    private String describeLogic(Protocol.LogicState state){
        if(!state.automatic())return state.error!=0?"Logic is paused · "+Protocol.error(state.error):homeLogicPaused()?"Logic is paused":"Logic is paused · a manual fan command stopped it";
        if(state.waitingSync())return "Logic is on · confirm the current fan setting on Home to synchronize";
        if(state.error!=0)return "Logic is on · "+Protocol.error(state.error);
        return "Logic is on"+(state.boosting()?" · warming boost, "+state.boostSeconds+" s left":"")+(state.windowAdjustment!=0?" · time window "+(state.windowAdjustment>0?"+":"")+state.windowAdjustment:"");
    }
    public boolean freshFan(){return fresh()&&irFirmware&&fan!=null&&freshStatus(fan.time);}
    public void applyDoubleHitTimeout(){if(doubleHitApplying)return;doubleHitAttempted=null;syncDoubleHitTimeout();}
    private void syncDoubleHitTimeout(){
        if(!fresh()||!irFirmware){doubleHitStatus="Saved on phone · waiting for controller";return;}
        if(!doubleHitSupported){doubleHitStatus="Saved on phone · controller update required";return;}
        int seconds=Settings.doubleHitTimeout(this);
        if(doubleHitApplying||Objects.equals(doubleHitAttempted,seconds))return;
        if(Objects.equals(doubleHitApplied,seconds)){doubleHitStatus=doubleHitSummary(seconds);return;}
        doubleHitApplying=true;doubleHitAttempted=seconds;final int generation=doubleHitGeneration;
        doubleHitStatus="Applying timeout…";
        command(seq->Protocol.doubleHitTimeout(seconds,seq),ok->{
            if(generation!=doubleHitGeneration)return;
            doubleHitApplying=false;
            if(ok){doubleHitApplied=seconds;doubleHitStatus=doubleHitSummary(seconds);}
            else doubleHitStatus="Saved on phone · couldn't apply: "+lastError;
            changed();
            if(Settings.doubleHitTimeout(this)!=seconds)syncDoubleHitTimeout();
        });
    }
    private static String doubleHitSummary(int seconds){return seconds==0?"Double hits off":"Double hits after "+seconds+" seconds idle";}
    public boolean freshLogic(){return freshFan()&&logic!=null&&freshStatus(logic.time);}
    private boolean homeLogicPaused(){return Settings.prefs(this).getBoolean("homeLogicPaused",false);}
    public boolean homeLogicReady(){return manualIrReady()&&logicSupported&&freshLogic()&&!homeLogicTogglePending;}
    /** Commands accepted while listening use the same immediate readiness gates and Home controls. */
    boolean voiceFanCommand(BedtimeVoiceCommand.Kind kind,java.util.function.Consumer<String> result){
        return voiceFanCommands.dispatch(kind,SystemClock.elapsedRealtime(),new VoiceFanCommands.Target(){
            public boolean eligible(){BedtimeVoiceService voice=BedtimeVoiceService.current;return voice!=null&&!voice.isShuttingDown()&&voice.ready&&RecordingVoiceController.canRecognize();}
            public boolean ready(){return manualIrReady()&&!fan.busy();}
            public boolean logicReady(){return homeLogicReady();}
            public boolean logicEnabled(){return logic.automatic();}
            public boolean remote(int slot,java.util.function.Consumer<String> done){
                return homeFanCommand(slot,null,(sent,corrected)->done.accept(sent?Protocol.COMMANDS[slot]+" command sent.":lastError));
            }
            public boolean logic(boolean enabled,java.util.function.Consumer<String> done){
                boolean accepted=setHomeLogicEnabled(enabled);if(accepted)done.accept(enabled?"Logic on requested.":"Logic off requested.");return accepted;
            }
            public boolean synchronize(java.util.function.Consumer<String> done){
                if(dreo==null||!dreo.active()){lastError="Not synced · connect the Dreo account in Breeze first.";return false;}
                if(!dreo.requestNow(done)){lastError="Not synced · a Dreo check is already running. Try again shortly.";return false;}
                return true;
            }
            public String failure(){return lastError.isEmpty()?"Fan command was not sent.":lastError;}
        },result);
    }
    public boolean setHomeLogicEnabled(boolean enabled){
        if(!homeLogicReady()){lastError="Wait for the controller to finish preparing.";changed();return false;}
        homeLogicTogglePending=true;homeLogicToggleDesired=enabled;final int generation=++homeLogicToggleGeneration;
        if(enabled&&Settings.prefs(this).getBoolean("homeLogicProfileDeferred",false)){boolean accepted=applyLogicProfileExplicit(Settings.logicProfile(this));if(!accepted)homeLogicTogglePending=false;return accepted;}
        command(seq->Protocol.fan(Protocol.AUTO,enabled?1:0,0,seq),ok->{if(generation!=homeLogicToggleGeneration)return;if(ok){Settings.prefs(this).edit().putBoolean("homeLogicPaused",!enabled).apply();logicStatus=enabled?"Waiting for confirmed Logic state…":"Logic paused.";}else homeLogicTogglePending=false;changed();});return true;
    }
    public boolean manualIrReady(){return freshFan()&&!irReceiverTestActive()&&fan.learning==255&&!fan.held()&&!logicUploading&&visibleForBluetooth()&&ready&&allowed()&&transportAvailable()&&pending==null&&queue.isEmpty();}
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
            if(fan.held())return rejectManualIr("the last press is still waiting for the fan's display to turn off.");
            if(!fan.has(value))return rejectManualIr("learn "+Protocol.COMMANDS[value]+" first.");
            return sendManualIr(value);
        }
        if(fanWritePending()){lastError="Wait for the controller to confirm the previous command.";changed();return false;}
        try{Protocol.fan(action,value,argument,1);invalidateDreo();command(s->Protocol.fan(action,value,argument,s),ok->{if(ok&&action==Protocol.SYNC_SPEED&&logicSupported)resumeLogic();changed();});return true;}catch(IllegalArgumentException e){lastError=e.getMessage();changed();return false;}
    }
    public boolean homeFanCommand(int slot,Integer draft,HomeFanResult done){
        int correctionGeneration=anchorCorrectionGeneration;long draftRevision=Settings.prefs(this).getLong("homeFanDraftRevision",0);boolean needsConfirmation=Settings.prefs(this).getBoolean("homeFanDraftNeedsConfirmation",false);
        Integer corrected=draft==null||needsConfirmation?null:HomeFanCorrection.afterManual(slot,draft,Settings.prefs(this).getInt("fanLastKnownOnSpeed",-1));
        Result previous=homeManualCompletion;
        homeManualCompletion=ok->{if(!ok){if(draft!=null)quarantineHomeDraft(draftRevision);if(done!=null)done.done(false,null);return;}if(correctionGeneration!=anchorCorrectionGeneration){if(done!=null)done.done(true,null);return;}if(needsConfirmation){lastError="Confirm the current fan setting with Done before correcting further commands.";changed();if(done!=null)done.done(true,null);return;}if(draft==null){if(done!=null)done.done(true,null);return;}if(corrected==null){quarantineHomeDraft(draftRevision);lastError="Check the current fan setting after "+Protocol.COMMANDS[slot]+" and apply it with Done.";changed();if(done!=null)done.done(true,null);return;}setHomeFanAnchor(corrected,slot==0&&draft>0?draft:null,synced->{if(!synced)quarantineHomeDraft(draftRevision);if(done!=null)done.done(true,synced?corrected:null);});};
        try{return fanCommand(Protocol.SEND_IR,slot,0);}finally{homeManualCompletion=previous;}
    }
    private void quarantineHomeDraft(long revision){if(Settings.prefs(this).getLong("homeFanDraftRevision",0)==revision)Settings.prefs(this).edit().putBoolean("homeFanDraftNeedsConfirmation",true).apply();}
    public boolean homeFanAnchorApplying(int speed){return homeAnchorApplyingGeneration==anchorCorrectionGeneration&&homeAnchorApplyingSpeed==speed;}
    public boolean setHomeFanAnchor(int speed,Result done){
        long revision=Settings.prefs(this).getLong("homeFanDraftRevision",0);
        return setHomeFanAnchor(speed,null,ok->{if(ok&&revision==Settings.prefs(this).getLong("homeFanDraftRevision",0))Settings.prefs(this).edit().remove("homeFanDraftNeedsConfirmation").apply();if(done!=null)done.done(ok);});
    }
    private boolean setHomeFanAnchor(int speed,Integer retainedOn,Result done){
        if(!FanSetting.valid(speed)||!freshFan()){lastError="Connect the controller and enter 0, 1–9, or H.";changed();if(done!=null)done.done(false);return false;}
        invalidateDreo();
        anchorCorrectionPending=true;final int correction=++anchorCorrectionGeneration;homeAnchorApplyingGeneration=correction;homeAnchorApplyingSpeed=speed;Settings.prefs(this).edit().putInt("fanAnchor",speed).putBoolean("fanAnchorCorrectionPending",true).apply();
        Result completed=ok->{if(correction!=anchorCorrectionGeneration)return;homeAnchorApplyingGeneration=-1;if(done!=null)done.done(ok);};
        if(retainedOn!=null)command(seq->Protocol.fan(Protocol.SYNC_SPEED,retainedOn,1,seq),ok->{if(correction!=anchorCorrectionGeneration)return;if(!ok){Settings.prefs(this).edit().remove("fanLastKnownOnSpeed").apply();completed.done(false);changed();return;}Settings.prefs(this).edit().putInt("fanLastKnownOnSpeed",retainedOn).apply();finishHomeFanAnchor(speed,correction,completed);});
        else finishHomeFanAnchor(speed,correction,completed);return true;
    }
    private void finishHomeFanAnchor(int speed,int correction,Result done){
        command(seq->Protocol.fan(Protocol.SYNC_SPEED,speed,1,seq),ok->{if(correction!=anchorCorrectionGeneration)return;if(ok){anchorCorrectionPending=false;SharedPreferences.Editor estimate=Settings.prefs(this).edit().putBoolean("fanAnchorCorrectionPending",false);if(speed>0)estimate.putInt("fanLastKnownOnSpeed",speed);estimate.apply();}else Settings.prefs(this).edit().remove("fanLastKnownOnSpeed").apply();if(done!=null)done.done(ok);changed();});
    }
    public boolean applyLogicProfile(LogicProfile profile){
        final LogicProfile snapshot=profile.copy();
        if(homeLogicPaused()&&!logicExplicitResumeRequested){Settings.prefs(this).edit().putBoolean("homeLogicProfileDeferred",true).apply();logicApplyNote="Saved on phone · will be sent when Logic resumes";changed();return false;}
        if(!freshFan()||!logicSupported){logicApplyNote=logicNeedsFirmware?"Saved on phone · update the controller firmware to use it":"Saved on phone · will be sent when the controller connects";changed();return false;}
        if(snapshot.hasEnabledTimeWindows()&&!logicWindowSupported){logicApplyNote="Saved on phone · controller update required for time windows";changed();return false;}
        if(snapshot.hasEnabledTimeWindows()&&snapshot.timeWindows.size()>1&&!logicMultiWindowSupported){logicApplyNote="Saved on phone · controller update required for multiple time windows";changed();return false;}
        if(logicUploading){nextLogicProfile=snapshot;nextLogicResumeAfterUpload=logicExplicitResumeRequested;logicApplyNote="Applying…";changed();return true;}
        nextLogicProfile=null;nextLogicResumeAfterUpload=false;logicUploading=true;logicResumeAfterUpload=logicExplicitResumeRequested;logicApplyNote="Applying…";final int generation=++logicUploadGeneration;
        // Only an explicit unsent correction can establish the fan's current physical setting.
        if(Settings.prefs(this).getBoolean("fanAnchorCorrectionPending",false))command(seq->Protocol.fan(Protocol.SYNC_SPEED,Math.max(0,Math.min(FanSetting.MAX,Settings.prefs(this).getInt("fanAnchor",1))),1,seq),ok->{if(generation!=logicUploadGeneration)return;if(ok){anchorCorrectionPending=false;Settings.prefs(this).edit().putBoolean("fanAnchorCorrectionPending",false).apply();beginLogicUpload(snapshot,generation);}else{logicUploading=false;logicApplyNote="Couldn't apply: "+lastError;changed();}});
        else beginLogicUpload(snapshot,generation);changed();return true;
    }
    public boolean applyLogicProfileExplicit(LogicProfile profile){boolean previous=logicExplicitResumeRequested;logicExplicitResumeRequested=true;try{return applyLogicProfile(profile);}finally{logicExplicitResumeRequested=previous;}}
    static final int LOGIC_UPLOAD_PARTS=4;
    private void beginLogicUpload(LogicProfile profile,int generation){
        // Keep every editable window on the phone; send equivalent non-overlapping intervals once per snapshot.
        final LogicProfile controllerProfile=logicMultiWindowSupported?profile.copy():profile;
        if(logicMultiWindowSupported)controllerProfile.replaceTimeWindows(profile.compiledTimeWindows());
        if(!logicWindowSupported){uploadLogicPart(controllerProfile,0,generation);return;}
        syncLogicClock(ok->{if(generation!=logicUploadGeneration)return;if(ok)uploadLogicPart(controllerProfile,0,generation);else{logicUploading=false;homeLogicTogglePending=false;logicApplyNote="Couldn't apply time window: "+lastError;changed();}});
    }
    private void syncLogicClock(Result done){
        final int generation=logicClockGeneration;
        final long at=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        final java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        logicClockAttempt=at;logicClockApplying=true;
        command(seq->Protocol.logicClock(wall/1000,zone,seq),ok->{
            if(generation!=logicClockGeneration)return;
            logicClockApplying=false;
            if(ok){logicClockAt=at;logicClockWall=wall;logicClockZone=zone.getId();}
            if(done!=null)done.done(ok);
        });
    }
    private void refreshLogicClock(){
        if(!logicWindowSupported||!logicWindowApplied||!freshFan()||logicUploading||logicClockApplying||pending!=null||!queue.isEmpty())return;
        long now=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        if(now-logicClockAttempt<30000)return;
        if(logicClockAt==0||now-logicClockAt>=6*60*60*1000L||Math.abs(wall-logicClockWall-(now-logicClockAt))>2000||!java.time.ZoneId.systemDefault().getId().equals(logicClockZone))syncLogicClock(null);
    }
    private void uploadLogicPart(LogicProfile profile,int part,int generation){
        if(generation!=logicUploadGeneration)return;
        // A complete replacement includes a disabled entry when empty; its count also clears removed intervals.
        int windows=logicWindowSupported?(logicMultiWindowSupported?profile.timeWindows.size():1):0;
        int parts=LOGIC_UPLOAD_PARTS+windows;
        IntFunction<byte[]> make;
        if(part==0)make=seq->Protocol.logicGlobal(profile,seq);
        else if(part==1)make=seq->Protocol.logicRange(profile,seq);
        else if(part==2)make=seq->Protocol.logicBoost(profile,seq);
        else if(part<3+windows)make=logicMultiWindowSupported?seq->Protocol.logicWindow(profile,part-3,seq):seq->Protocol.logicWindow(profile,seq);
        else make=Protocol::logicCommit;
        command(make,ok->{if(generation!=logicUploadGeneration)return;if(!ok){logicUploading=false;homeLogicTogglePending=false;logicApplyNote="Couldn't apply: "+lastError;changed();return;}if(part<parts-1){uploadLogicPart(profile,part+1,generation);return;}if(logicResumeAfterUpload)Settings.prefs(this).edit().putBoolean("homeLogicPaused",false).putBoolean("homeLogicProfileDeferred",false).apply();logicUploading=false;logicAppliedForConnection=true;logicWindowApplied=logicWindowSupported&&profile.hasEnabledTimeWindows();logicApplyNote="Applied to fan";if(nextLogicProfile!=null){LogicProfile latest=nextLogicProfile;boolean resume=nextLogicResumeAfterUpload;nextLogicProfile=null;nextLogicResumeAfterUpload=false;if(resume)applyLogicProfileExplicit(latest);else applyLogicProfile(latest);}changed();});
    }
    public void resumeLogic(){if(homeLogicPaused()||!freshFan()||!logicSupported)return;command(seq->Protocol.fan(Protocol.AUTO,1,0,seq),ok->{if(!ok)logicStatus=lastError;changed();});}
    public void setFanAnchor(int speed){
        if(!FanSetting.valid(speed)){lastError="Fan setting must be 0, 1–9, or H; 0 is Off.";changed();return;}
        invalidateDreo();
        anchorCorrectionPending=true;final int correction=++anchorCorrectionGeneration;Settings.prefs(this).edit().putInt("fanAnchor",speed).putBoolean("fanAnchorCorrectionPending",true).apply();
        if(freshFan())command(seq->Protocol.fan(Protocol.SYNC_SPEED,speed,1,seq),ok->{if(correction!=anchorCorrectionGeneration)return;if(ok){anchorCorrectionPending=false;Settings.prefs(this).edit().putBoolean("fanAnchorCorrectionPending",false).apply();if(logicSupported)resumeLogic();}changed();});else changed();
    }
    private boolean rejectManualIr(String reason){lastError="Not sent—"+reason;FanCommandTrace.record(this,lastError);fanCommandStatus=lastError;changed();return false;}
    private boolean sendManualIr(int slot){
        // A manual tap is useful now; never put it behind another command or retry it later.
        if(logicUploading||pending!=null||!queue.isEmpty())return rejectManualIr("controller busy. Tap again.");
        if(!visibleForBluetooth()||!ready||!allowed()||!transportAvailable())return rejectManualIr("controller disconnected.");
        invalidateDreo();
        seq=seq%65535+1;
        final int requestedSeq=seq;
        FanCommandTrace.record(this,"Manual IR request seq="+requestedSeq+" slot="+slot+" previousSendCounter="+(fan==null?-1:fan.sendCounter)+" speed="+(fan==null?-1:fan.speed));
        Result completion=homeManualCompletion;
        Pending sent=new Pending(Protocol.fan(Protocol.SEND_IR,slot,0,seq),seq,ok->{FanCommandTrace.record(this,"Manual IR acknowledgement seq="+requestedSeq+" slot="+slot+" accepted="+ok+" error="+lastError);fanCommandStatus=ok?"Controller sent "+Protocol.COMMANDS[slot]+".":"Send failed: "+lastError;if(completion!=null)completion.done(ok);changed();});
        BluetoothGatt connection=gatt;pending=sent;
        try{
            int result=submitWrite(sent.bytes);
            if(drift!=null)drift.command("write_result",sent.bytes,Integer.toString(result));
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
        contactTestStarting=true;
        RecordingIntent intent=phoneRecording==null?RecordingIntent.UNKNOWN:phoneRecording.state();
        java.util.function.BooleanSupplier valid=()->!intent.known||RecordingReconciler.mayStart(phoneRecording.state(),intent.revision);
        Result finished=started->{contactTestStarting=false;if(started)download();else recordingStartFailed();reconcileRecording();};
        recordingCommand(s->Protocol.clock(System.currentTimeMillis()/1000,s),valid,ok->{if(ok&&valid.getAsBoolean())recordingCommand(s->Protocol.fan(Protocol.CONTACT_TEST,0,0,s),valid,finished);else finished.done(false);});
    }
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("status="+status+" ready="+ready+" firmware="+controllerFirmware);
        RecordingIntent intent=phoneRecording==null?RecordingIntent.UNKNOWN:phoneRecording.state();out.println("phoneRecordingKnown="+intent.known+" phoneRecording="+intent.active+" session="+intent.session+" revision="+intent.revision);
        out.println("dreo="+(dreo==null?"disabled":dreo.diagnostics())+" wakeLockHeld="+(dreoWakeLock!=null&&dreoWakeLock.isHeld())+" batteryExempt="+dreoLockReady());
        out.println("dreoCorrectionCount="+DreoCorrections.count(Settings.prefs(this)));
        out.println("driftDiagnostics="+(drift==null?"not started: "+driftInitError:drift.status()));
        out.println("visibleForBluetooth="+visibleForBluetooth()+" uiSession="+uiSession+" stoppedForUi="+stoppedForUi+" backgroundRemainingMs="+uiConnection.backgroundRemainingMs());
        out.println("scanning="+scanning+" gattPresent="+(gatt!=null)+" reconnectScheduled="+handler.hasCallbacks(reconnect));
        out.println("packetAgeMs="+(lastPacket==0?-1:SystemClock.elapsedRealtime()-lastPacket)+" queued="+queue.size()+" pending="+(pending==null?"none":pending.seq+" submitted="+pending.submitted));
        out.println("telemetryIntervalMs="+telemetryIntervalMs+" telemetryFreshnessMs="+telemetryFreshnessMs()+" linkTimeoutMs="+Protocol.telemetryLinkTimeoutMs(telemetryIntervalMs));
        out.println("controllerRecords="+controllerBase+".."+controllerCount+" freeBytes="+controllerFreeBytes+" storageReady="+controllerStorageReady);
        out.println("phoneCursor="+phoneCursor+" transfer="+transfer);
        out.println("telemetryError="+(latest==null?-1:latest.error)+" commandError="+(latest==null?-1:latest.commandError)+" recording="+(latest!=null&&latest.active())+" controllerUptimeSeconds="+(latest==null?-1:latest.uptime));
        if(latest!=null)out.println("bodyD0C="+latest.probe+" bodyD1C="+(fan==null?Double.NaN:fan.secondProbe)+" bodyD2C="+latest.thirdProbe+" roomC="+latest.room);
        if(fan!=null)out.println("fanSynced="+fan.synced()+" speed="+fan.speed+" target="+fan.target+" sendCounter="+fan.sendCounter+" lastSent="+fan.lastSent+" fanError="+fan.error+" held="+fan.held()+" manualIrReady="+manualIrReady());
        out.println("irHardwareTimed="+irHardwareTimed+" irSoftwareBursts="+irSoftwareBursts+" doubleHitSeconds="+Settings.doubleHitTimeout(this)+" doubleHitStatus="+doubleHitStatus);
        out.println("irReceiverTestSupported="+irReceiverTestSupported+" active="+irReceiverTestActive()+" status="+irReceiverTestStatus);
        if(irReceiverTest!=null)out.println("irReceiverTestId="+irReceiverTest.testId+" state="+irReceiverTest.state+" attempts="+irReceiverTest.attempts+" matched="+irReceiverTest.matched+" framesSeen="+irReceiverTest.framesSeen+" overflows="+irReceiverTest.overflows+" lastRawLength="+irReceiverTest.lastRawLength+" rxIdleLevel="+irReceiverTest.rxIdleLevel+" carrier="+irReceiverTest.carrier+" txPin="+irReceiverTest.txPin+" rxPin="+irReceiverTest.rxPin+" error="+irReceiverTest.error);
        out.println("receiverMonitorSupported="+receiverMonitorSupported+" fanCommandStatus="+fanCommandStatus);
        if(receiverInput!=null)out.println("receiverInputCounter="+receiverInput.counter+" hasInput="+receiverInput.hasInput()+" source="+receiverInput.source()+" label="+receiverInput.label()+" protocol="+receiverInput.protocol+" bits="+receiverInput.bits+" value="+Long.toUnsignedString(receiverInput.value,16)+" rawLength="+receiverInput.rawLength+" flags="+receiverInput.flags);
        out.println("lastError="+lastError);out.println("fanCommandTrace:\n"+FanCommandTrace.read(this));
        out.println("voice="+RecordingVoiceController.describe());
    }
    private void closeGatt(){recordingReconciler.disconnected();contactTestStarting=false;recordConnectionGeneration++;metadataGeneration++;downloadGeneration++;phoneCursor=-1;if(drift!=null)drift.continuityBreak("Bluetooth connection closed");invalidateDreo();logicClockGeneration++;nextLogicProfile=null;nextLogicResumeAfterUpload=false;logicClockApplying=false;logicWindowApplied=false;logicWindowSupported=false;logicMultiWindowSupported=false;logicClockAt=logicClockWall=logicClockAttempt=0;logicClockZone="";doubleHitGeneration++;doubleHitApplying=false;doubleHitApplied=doubleHitAttempted=null;doubleHitSupported=false;irHardwareTimed=false;irSoftwareBursts=0;doubleHitStatus="Saved on phone · waiting for controller";fanCommandStatus="";logicUploadGeneration++;if(logicUploading)logicApplyNote="Couldn't apply: the controller disconnected";logicUploading=false;logicAppliedForConnection=false;logicSupported=false;logicNeedsFirmware=false;logic=null;logicExplicitResumeRequested=logicResumeAfterUpload=nextLogicResumeAfterUpload=homeLogicTogglePending=false;homeLogicToggleGeneration++;logicStatus="Controller disconnected · it keeps running the last applied range";if(irReceiverTestActive()){irReceiverTestRequested=false;irReceiverTestRunning=false;irReceiverTestGeneration++;irReceiverTestStatus="LED receiver test result unavailable: controller disconnected.";}receiverMonitorSupported=false;receiverMonitorCounter=-1;ready=false;rx=null;latest=null;lastPacket=readySince=0;telemetryIntervalMs=Protocol.DEFAULT_TELEMETRY_INTERVAL_MS;sensorFirmware=false;thirdProbeSupported=false;irFirmware=false;irReceiverTestSupported=false;fan=null;controllerFreeBytes=-1;controllerStorageReady=false;controllerFirmware="unknown";BluetoothGatt old=gatt;gatt=null;if(old!=null){try{if(allowed())old.disconnect();}catch(RuntimeException e){android.util.Log.w(TAG,"Could not disconnect expired Bluetooth handle",e);}try{old.close();}catch(RuntimeException e){android.util.Log.w(TAG,"Could not close expired Bluetooth handle",e);}}}
    private void stopForUi(){
        stoppedForUi=true;
        uiConnection.setListening(false);
        stopDreoSync();
        handler.removeCallbacksAndMessages(null);stopScan();closeGatt();devices.clear();
        if(current==this)current=null;
        failQueue("Phone disconnected. Records remain on the controller.");status="Disconnected";
        stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    @Override public void onDestroy(){destroyed=true;uiConnection.setListening(false);uiConnection.setRecording(false);stopDreoSync();unregisterReceiver(bluetoothState);stopScan();closeGatt();failQueue("Phone disconnected. Records remain on the controller.");handler.removeCallbacksAndMessages(null);closeRecordStorage();if(current==this)current=null;super.onDestroy();}
}
