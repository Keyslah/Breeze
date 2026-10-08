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
    public static BreezeService current;
    public static final String UPDATE="com.aaron.breeze.UPDATE";
    public String status="Disconnected", transfer="Records stay on the controller until downloaded.", lastError="";
    public Protocol.Reading latest;
    public Protocol.MotorReading motor;
    public boolean motorBusy=false;
    public boolean ready=false, scanning=false;
    public long selectedSession=-1, controllerCount=0, controllerBase=0;
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
    private long lastPacket=0;
    private boolean destroyed=false, syncQueued=false, retireQueued=false;
    private final HashMap<String,BluetoothDevice> devices=new HashMap<>();
    public interface Result {void done(boolean success);}
    private static class Pending {byte[] bytes;int seq,error;boolean written,acknowledged;Result done;Pending(byte[] b,int s,Result r){bytes=b;seq=s;done=r;}}
    @Override public void onCreate(){super.onCreate();current=this;store=new Store(this);adapter=getSystemService(BluetoothManager.class).getAdapter();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Controller connection",NotificationManager.IMPORTANCE_LOW));handler.postDelayed(periodicSync,30000);}
    private final Runnable periodicSync=new Runnable(){public void run(){if(ready&&!syncQueued)download();if(!destroyed)handler.postDelayed(this,30000);}};
    @Override public int onStartCommand(Intent intent,int flags,int id){startForeground(1,notification());if(gatt==null&&!scanning)scan();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"connection").setSmallIcon(com.aaron.breeze.R.drawable.ic_breeze).setContentTitle("Breeze • "+status).setContentText(latest!=null&&latest.active()?"Controller is recording locally":"Connected for setup and record downloads").setContentIntent(open).setOngoing(true).build();}
    private static boolean motorCommand(Pending p){return p!=null&&p.bytes[1]==2&&(p.bytes[4]==4||p.bytes[4]>=Protocol.MOTOR_JOG&&p.bytes[4]<=Protocol.MOTOR_NEUTRAL);}
    private void refreshMotorBusy(){motorBusy=motor!=null&&motor.running||motorCommand(pending);if(!motorBusy)for(Pending item:queue)if(motorCommand(item)){motorBusy=true;break;}}
    public void changed(){refreshMotorBusy();sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
    public boolean continuousMotor(){return motor!=null&&motor.continuous();}
    private void state(String s){status=s;changed();if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager.class).notify(1,notification());}
    public boolean fresh(){return ready&&latest!=null&&System.currentTimeMillis()-lastPacket<6000;}
    private boolean allowed(){return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    public void scan(){
        if(ready){state("Connected");return;}
        if(!allowed()){lastError="Nearby devices permission is needed.";changed();return;}
        if(adapter==null||!adapter.isEnabled()){lastError="Turn Bluetooth on, then connect again.";changed();return;}
        if(scanning)return;
        devices.clear();scanning=true;state("Searching for Breeze Fan…");
        adapter.getBluetoothLeScanner().startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        handler.postDelayed(()->{if(scanning){stopScan();state(devices.isEmpty()?"No controller found":"Choose a controller");}},12000);
    }
    private void stopScan(){if(scanning&&allowed()&&adapter!=null&&adapter.isEnabled())adapter.getBluetoothLeScanner().stopScan(scanCallback);scanning=false;}
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{if(!scanning||ready||destroyed)return;BluetoothDevice d=result.getDevice();devices.put(d.getAddress(),d);String saved=Settings.prefs(BreezeService.this).getString("device","");if(d.getAddress().equals(saved)){connect(d.getAddress());}else{changed();handler.postDelayed(()->{if(scanning&&!ready&&devices.size()==1)connect(devices.keySet().iterator().next());},800);}});}
        @Override public void onScanFailed(int code){handler.post(()->{if(!scanning||ready||destroyed)return;scanning=false;lastError="Bluetooth scan failed ("+code+").";state("Scan stopped");});}
    };
    public List<String> foundDevices(){List<String> list=new ArrayList<>(devices.keySet());Collections.sort(list);return list;}
    public void connect(String address){
        if(!allowed())return;stopScan();closeGatt();deviceAddress=address;deviceId=address;Settings.prefs(this).edit().putString("device",address).apply();lastError="";state("Connecting…");gatt=adapter.getRemoteDevice(address).connectGatt(this,true,callback,BluetoothDevice.TRANSPORT_LE);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state){handler.post(()->{if(g!=gatt||!allowed())return;if(state==BluetoothProfile.STATE_CONNECTED){BreezeService.this.state("Discovering controller…");g.discoverServices();}else if(state==BluetoothProfile.STATE_DISCONNECTED){ready=false;rx=null;latest=null;motor=null;motorBusy=false;failQueue("Connection lost. Records remain on the controller.");BreezeService.this.state("Disconnected • waiting to reconnect");}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){handler.post(()->{
            if(g!=gatt||!allowed())return;BluetoothGattService s=g.getService(Protocol.SERVICE);if(code!=BluetoothGatt.GATT_SUCCESS||s==null){lastError="Controller service unavailable.";changed();return;}rx=s.getCharacteristic(Protocol.RX);BluetoothGattCharacteristic tx=s.getCharacteristic(Protocol.TX);if(rx==null||tx==null){lastError="Controller firmware is incompatible.";changed();return;}BluetoothGattDescriptor ccc=tx.getDescriptor(Protocol.CCC);if(ccc==null||!g.setCharacteristicNotification(tx,true)){lastError="Could not enable live readings.";changed();return;}ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);if(!g.writeDescriptor(ccc)){lastError="Could not subscribe to controller.";changed();}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){handler.post(()->{if(g!=gatt||!allowed())return;if(code==BluetoothGatt.GATT_SUCCESS){ready=true;lastError="";state("Connected");command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)download();});}else{lastError="Notification subscription failed.";changed();}});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){byte[] b=c.getValue().clone();handler.post(()->{if(g==gatt)receive(b);});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(()->{if(g!=gatt)return;if(code!=BluetoothGatt.GATT_SUCCESS)failQueue("Controller write failed ("+code+").");else if(pending!=null){pending.written=true;completeCommand();}});}
    };
    private void receive(byte[] b){
        if(b.length!=20)return;
        try {
            ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if(b[0]==4){motor=Protocol.decodeMotor(b);changed();}
            else if(b[0]==1){latest=Protocol.decode(b,System.currentTimeMillis());lastPacket=latest.time;if(pending!=null&&latest.ack==pending.seq){pending.acknowledged=true;pending.error=latest.commandError;completeCommand();}changed();}
            else if(b[0]==2){long index=Integer.toUnsignedLong(p.getInt(2)),cursor=store.cursor(deviceId);if(index==cursor){long id=store.ingest(deviceId,b);if(id>=0)selectedSession=id;transfer="Saved "+store.cursor(deviceId)+" controller records to this phone";if(index%15==0)changed();}else if(index>cursor&&!syncQueued)download();}
            else if(b[0]==3){deviceId=deviceAddress+":"+Integer.toUnsignedLong(p.getInt(15));controllerBase=Integer.toUnsignedLong(p.getInt(2));controllerCount=Integer.toUnsignedLong(p.getInt(6));long cursor=store.cursor(deviceId);if(cursor<controllerBase){store.setCursor(deviceId,controllerBase);lastError="Earlier records were already transferred elsewhere. Downloading remaining records.";cursor=controllerBase;}
                if(cursor==controllerCount){syncQueued=false;transfer="All "+cursor+" records saved on this phone";if(controllerBase<controllerCount&&!retireQueued){retireQueued=true;final long saved=cursor;command(s->Protocol.retire(saved,s),ok->{retireQueued=false;if(ok)transfer="Downloaded and confirmed • controller storage cleared";changed();});}}
                else transfer="Downloading records • "+Math.max(0,cursor-controllerBase)+" / "+(controllerCount-controllerBase);
                changed();
            }
        }catch(Exception e){lastError="Could not save/decode records: "+e.getMessage()+". Controller records retained.";changed();}
    }
    public void command(IntFunction<byte[]> make,Result done){enqueue(make,done,false);}
    private void enqueue(IntFunction<byte[]> make,Result done,boolean priority){if(!ready){lastError="Connect to the controller first.";changed();if(done!=null)done.done(false);return;}seq=seq%65535+1;Pending item=new Pending(make.apply(seq),seq,done);if(priority)queue.addFirst(item);else queue.add(item);pump();changed();}
    private void completeCommand(){if(pending==null||!pending.written||!pending.acknowledged)return;Pending done=pending;pending=null;if(done.error!=0){lastError=Protocol.error(done.error);queue.removeIf(p->!(p.bytes[1]==2&&(p.bytes[4]==Protocol.MOTOR_STOP||p.bytes[4]==4)));motorBusy=false;if(done.done!=null)done.done.done(false);}else{lastError="";if(done.done!=null)done.done.done(true);}pump();changed();}
    private void pump(){if(!allowed()){failQueue("Nearby devices permission was revoked.");return;}if(pending!=null||queue.isEmpty()||!ready||gatt==null||rx==null)return;pending=queue.remove();Pending sent=pending;rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);rx.setValue(sent.bytes);if(!gatt.writeCharacteristic(rx)){failQueue("Bluetooth is busy. Try again.");return;}handler.postDelayed(()->{if(pending==sent)failQueue("Controller did not confirm the command. Check connection before retrying.");},6000);}
    private void failQueue(String reason){Pending old=pending;pending=null;queue.clear();syncQueued=false;retireQueued=false;motorBusy=false;lastError=reason;if(old!=null&&old.done!=null)old.done.done(false);changed();}
    public void download(){if(syncQueued||!ready)return;syncQueued=true;long from=store.cursor(deviceId);command(s->Protocol.sync(from,s),ok->{if(!ok)syncQueued=false;});handler.postDelayed(()->{if(syncQueued){syncQueued=false;download();}},30000);}
    public void startSession(Protocol.Config c){
        if(continuousMotor()){lastError=Protocol.error(10);changed();return;}
        if(!fresh()){lastError="Wait for a fresh controller reading.";changed();return;}
        if(latest.active()){lastError="A session is already running on the controller.";changed();return;}
        command(s->Protocol.config(c,s),ok->{if(ok)command(s->Protocol.clock(System.currentTimeMillis()/1000,s),clockOk->{if(clockOk)command(s->Protocol.control(Protocol.START_RULES,0,s),started->{if(started)download();});});});
    }
    public void control(int action,int value){if(continuousMotor()&&action!=4&&action!=6&&action!=Protocol.MOTOR_STOP){lastError=Protocol.error(10);changed();return;}command(s->Protocol.control(action,value,s),ok->{if(ok&&(action==0||action==Protocol.STOP_RULES))download();});}
    private boolean motorAvailable(){
        if(motorBusy)return false;
        if(!fresh()){lastError="Wait for a fresh controller connection.";changed();return false;}
        if(latest.active()){lastError="Stop recording before testing the motor.";changed();return false;}
        if(!continuousMotor()){lastError="Upload the continuous motor-test firmware first.";changed();return false;}
        if(!motor.canJog()){lastError="Wait for the motor to stop.";changed();return false;}return true;
    }
    public void jogMotor(boolean positive,int speed,int durationMs,int neutral){if(!motorAvailable())return;try{Protocol.motorJog(positive,speed,durationMs,neutral,1);command(s->Protocol.motorJog(positive,speed,durationMs,neutral,s),ok->changed());}catch(IllegalArgumentException e){lastError=e.getMessage();changed();}}
    public void setMotorNeutral(int neutral){if(!motorAvailable())return;try{Protocol.motorNeutral(neutral,1);command(s->Protocol.motorNeutral(neutral,s),ok->changed());}catch(IllegalArgumentException e){lastError=e.getMessage();changed();}}
    public void stopMotor(){
        // Keep the in-flight GATT write/ACK intact; stop is the very next command.
        queue.removeIf(BreezeService::motorCommand);
        if(pending!=null&&pending.bytes[1]==2&&pending.bytes[4]==Protocol.MOTOR_STOP){changed();return;}
        enqueue(s->Protocol.control(Protocol.MOTOR_STOP,0,s),ok->changed(),true);
    }
    public void releaseMotor(){queue.removeIf(BreezeService::motorCommand);if(pending!=null&&pending.bytes[1]==2&&pending.bytes[4]==4){changed();return;}enqueue(s->Protocol.control(4,0,s),ok->changed(),true);}
    private void closeGatt(){ready=false;rx=null;latest=null;motor=null;motorBusy=false;if(gatt!=null){if(allowed()){gatt.disconnect();gatt.close();}gatt=null;}queue.clear();pending=null;}
    @Override public void onDestroy(){destroyed=true;stopScan();closeGatt();handler.removeCallbacksAndMessages(null);store.close();current=null;super.onDestroy();}
}
