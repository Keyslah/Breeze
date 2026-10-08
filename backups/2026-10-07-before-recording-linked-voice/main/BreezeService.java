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
    private static final long COMMAND_TIMEOUT_MS=6000,LINK_TIMEOUT_MS=15000,CONNECT_TIMEOUT_MS=20000;
    private static final long BUSY_RETRY_MS=250,RECONNECT_DELAY_MS=3000;
    public static BreezeService current;
    public static final String UPDATE="com.aaron.breeze.UPDATE";
    public String status="Disconnected", transfer="Records stay on the controller until downloaded.", lastError="";
    public Protocol.Reading latest;
    public boolean sensorFirmware=false;
    public boolean irFirmware=false;
    public Protocol.FanState fan;
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
    private int scanGeneration=0;
    private ScanCallback scanCallback;
    private boolean destroyed=false, syncQueued=false, retireQueued=false;
    private final HashMap<String,BluetoothDevice> devices=new HashMap<>();
    public interface Result {void done(boolean success);}
    private static class Pending {byte[] bytes;int seq,error;boolean submitted,written,acknowledged;long deadline;Result done;Pending(byte[] b,int s,Result r){bytes=b;seq=s;done=r;}}
    @Override public void onCreate(){super.onCreate();current=this;store=new Store(this);adapter=getSystemService(BluetoothManager.class).getAdapter();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Controller connection",NotificationManager.IMPORTANCE_LOW));registerReceiver(bluetoothState,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),Context.RECEIVER_EXPORTED);handler.postDelayed(periodicSync,30000);handler.postDelayed(connectionWatchdog,3000);}
    private final Runnable periodicSync=new Runnable(){public void run(){if(ready&&!syncQueued)download();if(!destroyed)handler.postDelayed(this,30000);}};
    private final Runnable connectionWatchdog=new Runnable(){public void run(){checkConnectionHealth();if(!destroyed)handler.postDelayed(this,3000);}};
    private final Runnable reconnect=()->{if(destroyed||ready||gatt!=null||scanning||!allowed()||!bluetoothEnabled())return;String saved=Settings.prefs(this).getString("device","");if(saved.isEmpty())scan();else connect(saved);};
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){handleBluetoothState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR));}};
    @Override public int onStartCommand(Intent intent,int flags,int id){startForeground(1,notification());if(gatt==null&&!scanning)scan();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"connection").setSmallIcon(com.aaron.breeze.R.drawable.ic_breeze).setContentTitle("Breeze • "+status).setContentText(latest!=null&&latest.active()?"Controller is recording locally":"Connected for setup and record downloads").setContentIntent(open).setOngoing(true).build();}
    public void changed(){sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
    void state(String s){status=s;changed();if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager.class).notify(1,notification());}
    public boolean fresh(){return ready&&latest!=null&&SystemClock.elapsedRealtime()-lastPacket<6000;}
    boolean allowed(){return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    boolean bluetoothEnabled(){return adapter!=null&&allowed()&&adapter.isEnabled();}
    void handleBluetoothState(int state){
        if(destroyed)return;
        if(state==BluetoothAdapter.STATE_TURNING_OFF||state==BluetoothAdapter.STATE_OFF){handler.removeCallbacks(reconnect);stopScan();closeGatt();failQueue("Bluetooth is off. Breeze will reconnect when it is on.");state("Bluetooth off");}
        else if(state==BluetoothAdapter.STATE_ON){lastError="";state("Reconnecting to controller…");scheduleReconnect();}
    }
    void checkConnectionHealth(){
        if(destroyed)return;
        if(ready&&SystemClock.elapsedRealtime()-(lastPacket==0?readySince:lastPacket)>=LINK_TIMEOUT_MS)recoverConnection("Controller connection stopped responding. Reconnecting…");
    }
    void scheduleReconnect(){handler.removeCallbacks(reconnect);if(!destroyed&&allowed()&&bluetoothEnabled())handler.postDelayed(reconnect,RECONNECT_DELAY_MS);}
    void recoverConnection(String reason){
        android.util.Log.w(TAG,reason);stopScan();closeGatt();failQueue(reason);state(bluetoothEnabled()?"Reconnecting to controller…":"Bluetooth off");scheduleReconnect();
    }
    public void scan(){
        if(ready){if(fresh()){state("Connected");return;}recoverConnection("Controller connection stopped responding. Reconnecting…");return;}
        if(!allowed()){lastError="Nearby devices permission is needed.";changed();return;}
        if(adapter==null||!adapter.isEnabled()){lastError="Turn Bluetooth on, then connect again.";changed();return;}
        if(scanning)return;
        handler.removeCallbacks(reconnect);devices.clear();scanning=true;final int generation=++scanGeneration;scanCallback=scannerCallback(generation);state("Searching for Breeze Sensors…");
        try{adapter.getBluetoothLeScanner().startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);}catch(RuntimeException e){recoverConnection("Bluetooth scan could not start. Reconnecting…");return;}
        handler.postDelayed(()->{if(scanning&&generation==scanGeneration){stopScan();state(devices.isEmpty()?"No controller found":"Choose a controller");if(devices.isEmpty())scheduleReconnect();}},12000);
    }
    private void stopScan(){if(scanning&&allowed()&&adapter!=null&&adapter.isEnabled()){try{BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();if(scanner!=null)scanner.stopScan(scanCallback);}catch(RuntimeException e){android.util.Log.w(TAG,"Could not stop old Bluetooth scan",e);}}scanning=false;scanGeneration++;}
    private ScanCallback scannerCallback(int generation){return new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{if(generation!=scanGeneration||!scanning||ready||destroyed)return;BluetoothDevice d=result.getDevice();devices.put(d.getAddress(),d);String saved=Settings.prefs(BreezeService.this).getString("device","");if(d.getAddress().equals(saved)){connect(d.getAddress());}else{changed();handler.postDelayed(()->{if(generation==scanGeneration&&scanning&&!ready&&devices.size()==1)connect(devices.keySet().iterator().next());},800);}});}
        @Override public void onScanFailed(int code){handler.post(()->{if(generation!=scanGeneration||!scanning||ready||destroyed)return;stopScan();lastError="Bluetooth scan failed ("+code+").";state("Scan stopped");scheduleReconnect();});}
    };}
    public List<String> foundDevices(){List<String> list=new ArrayList<>(devices.keySet());Collections.sort(list);return list;}
    public void connect(String address){
        if(!allowed()||!bluetoothEnabled())return;handler.removeCallbacks(reconnect);stopScan();closeGatt();failQueue("Reconnecting to the controller.");deviceAddress=address;deviceId=address;Settings.prefs(this).edit().putString("device",address).apply();lastError="";state("Connecting…");
        try{gatt=adapter.getRemoteDevice(address).connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);}catch(RuntimeException e){recoverConnection("Bluetooth connection could not start. Reconnecting…");return;}
        if(gatt==null){recoverConnection("Bluetooth connection could not start. Reconnecting…");return;}
        BluetoothGatt attempt=gatt;handler.postDelayed(()->{if(gatt==attempt&&!ready)recoverConnection("Controller connection timed out. Reconnecting…");},CONNECT_TIMEOUT_MS);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state){handler.post(()->{if(g!=gatt||!allowed())return;if(code!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){recoverConnection("Controller disconnected. Reconnecting…");return;}if(state==BluetoothProfile.STATE_CONNECTED){BreezeService.this.state("Discovering controller…");try{if(!g.discoverServices())recoverConnection("Could not discover the controller. Reconnecting…");}catch(RuntimeException e){recoverConnection("Bluetooth connection expired. Reconnecting…");}}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){handler.post(()->{
            if(g!=gatt||!allowed())return;BluetoothGattService s=g.getService(Protocol.SERVICE);if(code!=BluetoothGatt.GATT_SUCCESS||s==null){recoverConnection("Controller service unavailable. Reconnecting…");return;}rx=s.getCharacteristic(Protocol.RX);BluetoothGattCharacteristic tx=s.getCharacteristic(Protocol.TX);if(rx==null||tx==null){recoverConnection("Controller firmware is incompatible.");return;}BluetoothGattDescriptor ccc=tx.getDescriptor(Protocol.CCC);
            try{if(ccc==null||!g.setCharacteristicNotification(tx,true)){recoverConnection("Could not enable live readings. Reconnecting…");return;}int result=g.writeDescriptor(ccc,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);if(result!=BluetoothStatusCodes.SUCCESS)recoverConnection("Could not subscribe to controller ("+result+"). Reconnecting…");}catch(RuntimeException e){recoverConnection("Bluetooth connection expired. Reconnecting…");}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){handler.post(()->{if(g!=gatt||!allowed()||!Protocol.CCC.equals(d.getUuid())||!Protocol.TX.equals(d.getCharacteristic().getUuid()))return;if(code==BluetoothGatt.GATT_SUCCESS){ready=true;readySince=SystemClock.elapsedRealtime();lastError="";state("Connected");command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)download();});}else recoverConnection("Notification subscription failed. Reconnecting…");});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){if(!Protocol.TX.equals(c.getUuid()))return;byte[] b=value.clone();handler.post(()->{if(g==gatt)receive(b);});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(()->{if(g==gatt&&Protocol.RX.equals(c.getUuid()))onWriteCompleted(code);});}
    };
    void onWriteCompleted(int code){if(pending==null||!pending.submitted)return;if(code!=BluetoothGatt.GATT_SUCCESS)recoverConnection("Controller write failed ("+code+"). Reconnecting…");else{pending.written=true;completeCommand();}}
    void receive(byte[] b){
        if(b.length!=20)return;
        try {
            ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if(b[0]==4){sensorFirmware=Protocol.sensorFirmware(b);irFirmware=sensorFirmware&&(b[4]&255)>=7;controllerFirmware=(b[5]&255)+"."+(b[6]&255)+"."+(b[7]&255);changed();}
            else if(b[0]==5){fan=Protocol.decodeFan(b,System.currentTimeMillis());changed();}
            else if(b[0]==1){latest=Protocol.decode(b,System.currentTimeMillis());lastPacket=SystemClock.elapsedRealtime();if(pending!=null&&pending.submitted&&latest.ack==pending.seq){pending.acknowledged=true;pending.error=latest.commandError;completeCommand();}changed();}
            else if(b[0]==2||b[0]==6){long index=Integer.toUnsignedLong(p.getInt(2)),cursor=store.cursor(deviceId);if(index==cursor){long id=store.ingest(deviceId,b);if(id>=0)selectedSession=id;transfer="Saved "+store.cursor(deviceId)+" controller records to this phone";if(index%15==0)changed();}else if(index>cursor&&!syncQueued)download();}
            else if(b[0]==3){deviceId=deviceAddress+":"+Integer.toUnsignedLong(p.getInt(15));controllerBase=Integer.toUnsignedLong(p.getInt(2));controllerCount=Integer.toUnsignedLong(p.getInt(6));controllerFreeBytes=Integer.toUnsignedLong(p.getInt(10));controllerStorageReady=b[14]!=0;long cursor=store.cursor(deviceId);if(cursor<controllerBase){store.setCursor(deviceId,controllerBase);lastError="Earlier records were already transferred elsewhere. Downloading remaining records.";cursor=controllerBase;}
                if(cursor==controllerCount){syncQueued=false;transfer=controllerStorageReady&&controllerBase==controllerCount?"Downloaded and confirmed • controller storage ready":"All "+cursor+" records saved on this phone";if(controllerBase<controllerCount&&!retireQueued){retireQueued=true;final long saved=cursor;command(s->Protocol.retire(saved,s),ok->{retireQueued=false;if(ok)transfer=controllerBase>=saved&&controllerStorageReady?"Downloaded and confirmed • controller storage ready":"Records saved on phone • waiting for controller storage recovery";changed();});}}
                else transfer="Downloading records • "+Math.max(0,cursor-controllerBase)+" / "+(controllerCount-controllerBase);
                changed();
            }
        }catch(Exception e){lastError="Could not save/decode records: "+safeMessage(e)+". Controller records retained.";changed();}
    }
    public void command(IntFunction<byte[]> make,Result done){enqueue(make,done,false);}
    private void enqueue(IntFunction<byte[]> make,Result done,boolean priority){if(!ready){lastError="Connect to the controller first.";changed();if(done!=null)done.done(false);return;}seq=seq%65535+1;Pending item=new Pending(make.apply(seq),seq,done);if(priority)queue.addFirst(item);else queue.add(item);pump();changed();}
    private void completeCommand(){if(pending==null||!pending.written||!pending.acknowledged)return;Pending done=pending;pending=null;if(done.error!=0){failQueue(Protocol.error(done.error));if(done.done!=null)done.done.done(false);}else{lastError="";if(done.done!=null)done.done.done(true);}pump();changed();}
    boolean transportAvailable(){return gatt!=null&&rx!=null;}
    int submitWrite(byte[] bytes){return gatt.writeCharacteristic(rx,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);}
    private void pump(){
        if(!allowed()){closeGatt();failQueue("Nearby devices permission was revoked.");return;}
        if(pending!=null||queue.isEmpty()||!ready)return;
        if(!transportAvailable()){recoverConnection("Bluetooth connection unavailable. Reconnecting…");return;}
        pending=queue.remove();Pending sent=pending;BluetoothGatt connection=gatt;sent.deadline=SystemClock.elapsedRealtime()+COMMAND_TIMEOUT_MS;
        handler.postDelayed(()->commandTimedOut(sent,connection),COMMAND_TIMEOUT_MS);submitPending(sent,connection);
    }
    private void commandTimedOut(Pending sent,BluetoothGatt connection){if(pending==sent&&gatt==connection)recoverConnection("Controller did not confirm the command. Reconnecting; check its state before retrying.");}
    private void submitPending(Pending sent,BluetoothGatt connection){
        if(pending!=sent||gatt!=connection||!ready||sent.submitted)return;
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
    public void download(){if(syncQueued||!ready)return;syncQueued=true;long from=store.cursor(deviceId);command(s->Protocol.sync(from,s),ok->{if(!ok)syncQueued=false;});handler.postDelayed(()->{if(syncQueued){syncQueued=false;download();}},30000);}
    public void startSession(){
        if(!sensorFirmware){lastError="Update the controller to sensor firmware first.";changed();return;}
        if(!fresh()){lastError="Wait for a fresh controller reading.";changed();return;}
        if(latest.active()){lastError="A recording is already running on the controller.";changed();return;}
        if(!latest.storageReady()){lastError="Controller storage is unavailable. Saving its records to this phone before recording can restart.";download();changed();return;}
        command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)command(s->Protocol.control(Protocol.START_RECORDING,0,s),started->{if(started)download();});});
    }
    public void control(int action,int value){if(!sensorFirmware||(action!=Protocol.STOP_RECORDING&&action!=6)){lastError="Sensor firmware required for this action.";changed();return;}command(s->Protocol.control(action,value,s),ok->{if(ok&&action==Protocol.STOP_RECORDING)download();});}
    public boolean freshFan(){return fresh()&&irFirmware&&fan!=null&&System.currentTimeMillis()-fan.time<6000;}
    public boolean fanWritePending(){if(pending!=null&&pending.bytes[1]==6)return true;for(Pending item:queue)if(item.bytes[1]==6)return true;return false;}
    public void fanCommand(int action,int value,int argument){
        if(!freshFan()){lastError="Wait for fresh IR-controller telemetry.";changed();return;}
        if(fanWritePending()){lastError="Wait for the controller to confirm the previous command.";changed();return;}
        try{Protocol.fan(action,value,argument,1);command(s->Protocol.fan(action,value,argument,s),ok->changed());}catch(IllegalArgumentException e){lastError=e.getMessage();changed();}
    }
    public void startContactTest(){
        if(!freshFan()){lastError="Connect the updated IR controller first.";changed();return;}
        if(latest.active()){lastError="Stop the current recording before starting a probe test.";changed();return;}
        command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)command(s->Protocol.fan(Protocol.CONTACT_TEST,0,0,s),started->{if(started)download();});});
    }
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("status="+status+" ready="+ready+" firmware="+controllerFirmware);
        out.println("packetAgeMs="+(lastPacket==0?-1:SystemClock.elapsedRealtime()-lastPacket)+" queued="+queue.size()+" pending="+(pending==null?"none":pending.seq+" submitted="+pending.submitted));
        out.println("controllerRecords="+controllerBase+".."+controllerCount+" freeBytes="+controllerFreeBytes+" storageReady="+controllerStorageReady);
        out.println("phoneCursor="+(store==null?-1:store.cursor(deviceId))+" transfer="+transfer);
        out.println("telemetryError="+(latest==null?-1:latest.error)+" commandError="+(latest==null?-1:latest.commandError)+" recording="+(latest!=null&&latest.active()));
        out.println("lastError="+lastError);
    }
    private void closeGatt(){ready=false;rx=null;latest=null;lastPacket=readySince=0;sensorFirmware=false;irFirmware=false;fan=null;controllerFreeBytes=-1;controllerStorageReady=false;controllerFirmware="unknown";BluetoothGatt old=gatt;gatt=null;if(old!=null){try{if(allowed())old.disconnect();}catch(RuntimeException e){android.util.Log.w(TAG,"Could not disconnect expired Bluetooth handle",e);}try{old.close();}catch(RuntimeException e){android.util.Log.w(TAG,"Could not close expired Bluetooth handle",e);}}}
    @Override public void onDestroy(){destroyed=true;unregisterReceiver(bluetoothState);stopScan();closeGatt();failQueue("Phone disconnected. Records remain on the controller.");handler.removeCallbacksAndMessages(null);store.close();current=null;super.onDestroy();}
}
