package com.aaron.breeze;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

@SuppressWarnings("deprecation")
// All BLE entry points use allowed(); Android revokes permissions by stopping this process.
@android.annotation.SuppressLint("MissingPermission")
public class BreezeService extends Service {
    public static BreezeService current;
    public static final String UPDATE="com.aaron.breeze.UPDATE";
    public String status="Disconnected", transfer="Records stay on the controller until downloaded.", lastError="";
    public String firmwareVersion="",otaSelectedName="",otaSelectedVersion="",otaStatus="Choose a Breeze firmware .bin file.";
    public long otaSelectedSize=0;
    public int otaProgress=0,otaPhase=Protocol.OTA_IDLE;
    public volatile boolean otaBusy=false;
    public boolean otaSupported=false;
    public Protocol.Reading latest;
    public boolean sensorFirmware=false;
    public boolean irFirmware=false;
    public Protocol.FanState fan;
    public boolean ready=false, scanning=false;
    public long selectedSession=-1, controllerCount=0, controllerBase=0;
    private String deviceId="";
    private String deviceAddress="";
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private Store store;
    private ConnectivityManager connectivity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService otaWorker=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Pending> queue=new ArrayDeque<>();
    private Pending pending;
    private int seq=(int)(System.currentTimeMillis()%60000)+1;
    private long lastPacket=0;
    private boolean destroyed=false, syncQueued=false, retireQueued=false;
    private FirmwareImage otaImage,activeOtaImage;
    private ConnectivityManager.NetworkCallback otaNetworkCallback;
    private Network otaNetwork;
    private volatile HttpURLConnection otaConnection;
    private volatile int otaRun=0;
    private int otaSelection=0;
    private String activeOtaToken="";
    private boolean otaDeviceAccepted=false,otaHttpAccepted=false,otaSawDisconnect=false,otaUploadStarted=false,otaConfirmationQueued=false;
    private final HashMap<String,BluetoothDevice> devices=new HashMap<>();
    public interface Result {void done(boolean success);}
    private static class Pending {byte[] bytes;int seq,error;boolean written,acknowledged;Result done;Pending(byte[] b,int s,Result r){bytes=b;seq=s;done=r;}}
    @Override public void onCreate(){super.onCreate();current=this;store=new Store(this);adapter=getSystemService(BluetoothManager.class).getAdapter();connectivity=getSystemService(ConnectivityManager.class);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Controller connection",NotificationManager.IMPORTANCE_LOW));handler.postDelayed(periodicSync,30000);}
    private final Runnable periodicSync=new Runnable(){public void run(){if(ready&&!syncQueued&&!otaBusy)download();if(!destroyed)handler.postDelayed(this,30000);}};
    @Override public int onStartCommand(Intent intent,int flags,int id){startForeground(1,notification());if(gatt==null&&!scanning)scan();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"connection").setSmallIcon(com.aaron.breeze.R.drawable.ic_breeze).setContentTitle("Breeze • "+status).setContentText(otaBusy?otaStatus:latest!=null&&latest.active()?"Controller is recording locally":"Connected for setup and record downloads").setContentIntent(open).setOngoing(true).build();}
    public void changed(){sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
    private void state(String s){status=s;changed();if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager.class).notify(1,notification());}
    public boolean fresh(){return ready&&latest!=null&&System.currentTimeMillis()-lastPacket<6000;}
    private boolean allowed(){return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    public void scan(){
        if(otaBusy){lastError="Wait for the firmware update to finish or cancel it first.";changed();return;}
        if(ready){state("Connected");return;}
        if(!allowed()){lastError="Nearby devices permission is needed.";changed();return;}
        if(adapter==null||!adapter.isEnabled()){lastError="Turn Bluetooth on, then connect again.";changed();return;}
        if(scanning)return;
        devices.clear();scanning=true;state("Searching for Breeze Sensors…");
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
        if(otaBusy){lastError="Wait for the firmware update to finish or cancel it first.";changed();return;}
        if(!allowed())return;stopScan();closeGatt();deviceAddress=address;deviceId=address;Settings.prefs(this).edit().putString("device",address).apply();lastError="";state("Connecting…");gatt=adapter.getRemoteDevice(address).connectGatt(this,true,callback,BluetoothDevice.TRANSPORT_LE);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state){handler.post(()->{if(g!=gatt||!allowed())return;if(state==BluetoothProfile.STATE_CONNECTED){BreezeService.this.state("Discovering controller…");g.discoverServices();}else if(state==BluetoothProfile.STATE_DISCONNECTED){boolean expected=otaBusy&&(otaDeviceAccepted||otaHttpAccepted);ready=false;rx=null;latest=null;sensorFirmware=false;irFirmware=false;fan=null;if(expected){otaSawDisconnect=true;releaseOtaNetwork();otaStatus="Rebooting • waiting for Bluetooth to reconnect";changed();}else{otaSupported=false;firmwareVersion="";failQueue("Connection lost. Records remain on the controller.");if(otaBusy)failOta("Bluetooth connection disappeared before the controller accepted the firmware.");}BreezeService.this.state("Disconnected • waiting to reconnect");}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){handler.post(()->{
            if(g!=gatt||!allowed())return;BluetoothGattService s=g.getService(Protocol.SERVICE);if(code!=BluetoothGatt.GATT_SUCCESS||s==null){lastError="Controller service unavailable.";changed();return;}rx=s.getCharacteristic(Protocol.RX);BluetoothGattCharacteristic tx=s.getCharacteristic(Protocol.TX);if(rx==null||tx==null){lastError="Controller firmware is incompatible.";changed();return;}BluetoothGattDescriptor ccc=tx.getDescriptor(Protocol.CCC);if(ccc==null||!g.setCharacteristicNotification(tx,true)){lastError="Could not enable live readings.";changed();return;}ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);if(!g.writeDescriptor(ccc)){lastError="Could not subscribe to controller.";changed();}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){handler.post(()->{if(g!=gatt||!allowed())return;if(code==BluetoothGatt.GATT_SUCCESS){ready=true;lastError="";state("Connected");if(!(otaBusy&&(otaDeviceAccepted||otaHttpAccepted)))command(s->Protocol.clock(System.currentTimeMillis()/1000,s),ok->{if(ok)download();});}else{lastError="Notification subscription failed.";changed();}});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){byte[] b=c.getValue().clone();handler.post(()->{if(g==gatt)receive(b);});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(()->{if(g!=gatt)return;if(code!=BluetoothGatt.GATT_SUCCESS)failQueue("Controller write failed ("+code+").");else if(pending!=null){pending.written=true;completeCommand();}});}
    };
    private void receive(byte[] b){
        if(b.length!=20)return;
        try {
            ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if(b[0]==4){
                sensorFirmware=Protocol.sensorFirmware(b);irFirmware=sensorFirmware&&(b[4]&255)>=7;otaSupported=false;firmwareVersion="";
                if(sensorFirmware){
                    Protocol.Capability capability=Protocol.decodeCapability(b);irFirmware=capability.irSupported();otaSupported=capability.otaSupported;firmwareVersion=capability.firmwareVersion;
                    if(otaBusy&&(otaDeviceAccepted||otaHttpAccepted)&&otaSawDisconnect&&!firmwareVersion.isEmpty()){
                        if(!firmwareVersion.equals(activeOtaImage.version))failOta("Controller reconnected, but reports firmware "+firmwareVersion+" instead of "+activeOtaImage.version+".");
                        else if(!capability.confirmationPending)completeOta();
                        else confirmUpdatedFirmware();
                    }
                }
                changed();
            }
            else if(b[0]==5){fan=Protocol.decodeFan(b,System.currentTimeMillis());changed();}
            else if(b[0]==7){handleOtaStatus(Protocol.decodeOta(b));}
            else if(b[0]==1){latest=Protocol.decode(b,System.currentTimeMillis());lastPacket=latest.time;if(pending!=null&&latest.ack==pending.seq){pending.acknowledged=true;pending.error=latest.commandError;completeCommand();}changed();}
            else if(b[0]==2||b[0]==6){long index=Integer.toUnsignedLong(p.getInt(2)),cursor=store.cursor(deviceId);if(index==cursor){long id=store.ingest(deviceId,b);if(id>=0)selectedSession=id;transfer="Saved "+store.cursor(deviceId)+" controller records to this phone";if(index%15==0)changed();}else if(index>cursor&&!syncQueued)download();}
            else if(b[0]==3){deviceId=deviceAddress+":"+Integer.toUnsignedLong(p.getInt(15));controllerBase=Integer.toUnsignedLong(p.getInt(2));controllerCount=Integer.toUnsignedLong(p.getInt(6));long cursor=store.cursor(deviceId);if(cursor<controllerBase){store.setCursor(deviceId,controllerBase);lastError="Earlier records were already transferred elsewhere. Downloading remaining records.";cursor=controllerBase;}
                if(cursor==controllerCount){syncQueued=false;transfer="All "+cursor+" records saved on this phone";if(controllerBase<controllerCount&&!retireQueued){retireQueued=true;final long saved=cursor;command(s->Protocol.retire(saved,s),ok->{retireQueued=false;if(ok)transfer="Downloaded and confirmed • controller storage cleared";changed();});}}
                else transfer="Downloading records • "+Math.max(0,cursor-controllerBase)+" / "+(controllerCount-controllerBase);
                changed();
            }
        }catch(Exception e){if(b[0]==7&&otaBusy)failOta("Invalid OTA status from the controller: "+safeMessage(e));else{lastError="Could not save/decode records: "+safeMessage(e)+". Controller records retained.";changed();}}
    }
    public void command(IntFunction<byte[]> make,Result done){enqueue(make,done,false);}
    private void enqueue(IntFunction<byte[]> make,Result done,boolean priority){if(!ready){lastError="Connect to the controller first.";changed();if(done!=null)done.done(false);return;}seq=seq%65535+1;byte[] bytes=make.apply(seq);if(otaBusy&&bytes[1]!=7){lastError="Firmware update is in progress; other controller commands are paused.";changed();if(done!=null)done.done(false);return;}Pending item=new Pending(bytes,seq,done);if(priority)queue.addFirst(item);else queue.add(item);pump();changed();}
    private void completeCommand(){if(pending==null||!pending.written||!pending.acknowledged)return;Pending done=pending;pending=null;if(done.error!=0){lastError=Protocol.error(done.error);queue.clear();syncQueued=false;retireQueued=false;if(done.done!=null)done.done.done(false);}else{lastError="";if(done.done!=null)done.done.done(true);}pump();changed();}
    private void pump(){if(!allowed()){failQueue("Nearby devices permission was revoked.");return;}if(pending!=null||queue.isEmpty()||!ready||gatt==null||rx==null)return;pending=queue.remove();Pending sent=pending;rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);rx.setValue(sent.bytes);if(!gatt.writeCharacteristic(rx)){failQueue("Bluetooth is busy. Try again.");return;}handler.postDelayed(()->{if(pending==sent)failQueue("Controller did not confirm the command. Check connection before retrying.");},6000);}
    private void failQueue(String reason){Pending old=pending;pending=null;queue.clear();syncQueued=false;retireQueued=false;lastError=reason;if(old!=null&&old.done!=null)old.done.done(false);changed();}
    public boolean hasSelectedFirmware(){return otaImage!=null;}
    public boolean canCancelFirmwareUpdate(){return otaBusy&&!otaUploadStarted&&otaPhase<=Protocol.OTA_READY;}
    public void selectFirmware(Uri uri,String displayName,long declaredSize){
        if(otaBusy){otaStatus="Wait for the current firmware update to finish.";changed();return;}
        int selection=++otaSelection;otaImage=null;otaSelectedName=displayName==null?"":displayName;otaSelectedVersion="";otaSelectedSize=0;otaProgress=0;otaPhase=Protocol.OTA_IDLE;otaStatus="Checking "+(otaSelectedName.isEmpty()?"firmware file":otaSelectedName)+"…";changed();
        otaWorker.execute(()->{try{InputStream input=getContentResolver().openInputStream(uri);if(input==null)throw new IOException("Android could not open the selected file.");FirmwareImage image=FirmwareImage.read(displayName,declaredSize,input);handler.post(()->{if(destroyed||selection!=otaSelection||otaBusy)return;otaImage=image;otaSelectedName=image.name;otaSelectedVersion=image.version;otaSelectedSize=image.size();otaStatus="Ready to install firmware "+image.version+".";lastError="";changed();});}catch(Exception e){handler.post(()->{if(destroyed||selection!=otaSelection||otaBusy)return;otaImage=null;otaSelectedVersion="";otaSelectedSize=0;otaStatus="File rejected: "+safeMessage(e);changed();});}});
    }
    public void startFirmwareUpdate(){
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::startFirmwareUpdate);return;}
        if(otaBusy){otaStatus="A firmware update is already running.";changed();return;}
        if(otaImage==null){otaStatus="Select and validate a Breeze firmware .bin file first.";changed();return;}
        if(!ready){otaStatus="Connect to the Breeze controller over Bluetooth first.";changed();return;}
        if(!otaSupported){otaStatus="This controller needs the first OTA-capable firmware installed over USB.";changed();return;}
        if(latest!=null&&latest.active()){otaStatus="Stop sensor recording before updating firmware.";changed();return;}
        activeOtaImage=otaImage;otaBusy=true;otaDeviceAccepted=false;otaHttpAccepted=false;otaSawDisconnect=false;otaUploadStarted=false;otaConfirmationQueued=false;activeOtaToken="";otaProgress=0;otaPhase=Protocol.OTA_PREPARING;otaStatus="Preparing • asking the controller to start update Wi-Fi";lastError="";int run=++otaRun;changed();
        command(s->Protocol.ota(Protocol.ENTER_OTA,s),ok->{if(run!=otaRun||!otaBusy)return;if(!ok){String reason=lastError.isEmpty()?"ESP32 could not enter firmware update mode.":lastError;failOta(reason);return;}if(otaPhase<=Protocol.OTA_PREPARING){otaStatus="Preparing • waiting for the controller's update Wi-Fi";changed();}});
        handler.postDelayed(()->{if(run==otaRun&&otaBusy&&otaPhase<Protocol.OTA_READY)failOta("The controller did not make update Wi-Fi ready in time.");},20000);
    }
    public void cancelFirmwareUpdate(){
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::cancelFirmwareUpdate);return;}
        if(!otaBusy){otaStatus="No firmware update is running.";changed();return;}
        if(!canCancelFirmwareUpdate()){otaStatus="The controller is writing or verifying firmware; it is no longer safe to cancel.";changed();return;}
        int run=otaRun;otaStatus="Canceling firmware update…";changed();command(s->Protocol.ota(Protocol.CANCEL_OTA,s),ok->{if(run!=otaRun||!otaBusy)return;if(ok)finishCanceledOta();else failOta(lastError.isEmpty()?"The controller could not cancel update mode.":lastError);});
    }
    private void handleOtaStatus(Protocol.OtaStatus update){
        if(!otaBusy)return;
        if(update.state==Protocol.OTA_ERROR){otaPhase=Protocol.OTA_ERROR;failOta(Protocol.otaError(update.error));return;}
        if((otaDeviceAccepted||otaHttpAccepted)&&update.state<Protocol.OTA_REBOOTING)return;
        if(otaPhase>=Protocol.OTA_UPLOADING&&update.state<otaPhase)return;
        otaPhase=update.state;otaProgress=Math.max(otaProgress,update.progress);
        switch(update.state){
            case Protocol.OTA_PREPARING -> otaStatus="Preparing • controller is starting update Wi-Fi";
            case Protocol.OTA_READY -> {otaStatus="Connecting • approve the Breeze update Wi-Fi prompt";connectOtaWifi(update);}
            case Protocol.OTA_UPLOADING -> otaStatus="Uploading • "+otaProgress+"%";
            case Protocol.OTA_VERIFYING -> otaStatus="Verifying firmware on the ESP32";
            case Protocol.OTA_REBOOTING -> markDeviceAccepted(otaRun);
            case Protocol.OTA_COMPLETE -> otaStatus="Verifying the restarted controller";
            default -> {}
        }
        changed();
    }
    private void connectOtaWifi(Protocol.OtaStatus readyStatus){
        if(otaNetworkCallback!=null||otaUploadStarted||!otaBusy)return;
        if(readyStatus.total>0&&activeOtaImage.size()>readyStatus.total){failOta("Selected firmware is "+activeOtaImage.size()+" bytes, but the controller's OTA slot is "+readyStatus.total+" bytes.");return;}
        WifiManager wifi=getSystemService(WifiManager.class);if(wifi==null||!wifi.isWifiEnabled()){failOta("Turn Wi-Fi on, then tap Update ESP32 again.");return;}
        String token=readyStatus.tokenHex();if(token.equals("0000000000000000")){failOta("Controller returned an invalid update session token.");return;}activeOtaToken=token;
        WifiNetworkSpecifier specifier=new WifiNetworkSpecifier.Builder().setSsid(readyStatus.ssid()).setWpa2Passphrase(token).build();
        NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build();int run=otaRun;
        ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){if(run!=otaRun||!otaBusy||otaUploadStarted)return;otaNetwork=network;otaStatus="Connecting • update Wi-Fi joined";changed();}
            @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){
                if(run!=otaRun||!otaBusy||otaUploadStarted||!network.equals(otaNetwork))return;
                boolean hasIpv4=false;for(LinkAddress address:properties.getLinkAddresses())if(address.getAddress() instanceof Inet4Address){hasIpv4=true;break;}
                if(!hasIpv4)return;
                otaUploadStarted=true;FirmwareImage image=activeOtaImage;String sessionToken=activeOtaToken;otaStatus="Connecting • starting firmware upload";changed();otaWorker.execute(()->uploadFirmware(network,image,sessionToken,run));
            }
            @Override public void onUnavailable(){if(run==otaRun&&otaBusy)failOta("Wi-Fi connection was not approved or could not be established.");}
            @Override public void onLost(Network network){if(run==otaRun&&otaBusy&&network.equals(otaNetwork)&&!otaDeviceAccepted&&!otaHttpAccepted)failOta("The ESP32 update Wi-Fi connection disappeared during upload.");}
        };otaNetworkCallback=callback;
        try{connectivity.requestNetwork(request,callback,handler);}catch(Exception e){otaNetworkCallback=null;failOta("Could not request the ESP32 update Wi-Fi: "+safeMessage(e));return;}
        handler.postDelayed(()->{if(run==otaRun&&otaBusy&&!otaUploadStarted)failOta("Timed out waiting for approval of the ESP32 update Wi-Fi.");},60000);
    }
    private void uploadFirmware(Network network,FirmwareImage image,String token,int run){
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)network.openConnection(new URL("http://192.168.4.1/firmware"));otaConnection=connection;connection.setRequestMethod("POST");connection.setConnectTimeout(15000);connection.setReadTimeout(90000);connection.setDoOutput(true);connection.setUseCaches(false);connection.setRequestProperty("Content-Type","application/octet-stream");connection.setRequestProperty("X-Breeze-Token",token);connection.setRequestProperty("X-Firmware-SHA256",image.sha256);connection.setFixedLengthStreamingMode(image.bytes.length);
            try(OutputStream output=connection.getOutputStream()){int sent=0,last=-1;while(sent<image.bytes.length){if(run!=otaRun||!otaBusy)throw new CancellationException();int count=Math.min(16384,image.bytes.length-sent);output.write(image.bytes,sent,count);sent+=count;int progress=(int)(sent*100L/image.bytes.length);if(progress!=last){last=progress;final int value=progress;handler.post(()->{if(run==otaRun&&otaBusy&&otaPhase==Protocol.OTA_UPLOADING){otaProgress=Math.max(otaProgress,value);otaStatus="Uploading • "+otaProgress+"%";changed();}});}}}
            int code=connection.getResponseCode();String response=readResponse(code>=400?connection.getErrorStream():connection.getInputStream());if(code!=200)throw new IOException(serverError(response,"ESP32 rejected the firmware (HTTP "+code+")."));if(!response.replace(" ","").contains("\"ok\":true"))throw new IOException(serverError(response,"ESP32 did not confirm the firmware image."));
            handler.post(()->{if(run==otaRun&&otaBusy){otaHttpAccepted=true;markDeviceAccepted(run);releaseOtaNetwork();}});
        }catch(CancellationException ignored){}catch(Exception e){handler.post(()->{if(run==otaRun&&otaBusy){if(otaDeviceAccepted)releaseOtaNetwork();else failOta("Upload failed: "+safeMessage(e));}});}finally{if(connection!=null)connection.disconnect();if(otaConnection==connection)otaConnection=null;}
    }
    private void markDeviceAccepted(int run){boolean first=!otaDeviceAccepted;otaDeviceAccepted=true;otaProgress=100;otaPhase=Protocol.OTA_REBOOTING;otaStatus="Rebooting • waiting for Bluetooth to reconnect";changed();if(first)handler.postDelayed(()->{if(run==otaRun&&otaBusy&&otaDeviceAccepted)failOta("The controller did not reconnect and report the new firmware version in time.");},90000);}
    private void confirmUpdatedFirmware(){
        if(!otaBusy||otaConfirmationQueued)return;int run=otaRun;otaConfirmationQueued=true;otaStatus="Verifying • confirming Bluetooth and firmware health";changed();
        command(s->Protocol.ota(Protocol.CONFIRM_OTA,s),ok->{if(run!=otaRun||!otaBusy)return;otaConfirmationQueued=false;if(ok)completeOta();else failOta(lastError.isEmpty()?"The restarted firmware could not be confirmed.":lastError);});
    }
    private void completeOta(){String installed=activeOtaImage.version;otaBusy=false;++otaRun;releaseOtaNetwork();otaPhase=Protocol.OTA_COMPLETE;otaProgress=100;otaStatus="Complete • firmware "+installed+" is running";activeOtaImage=null;activeOtaToken="";lastError="";status="Connected";changed();}
    private void finishCanceledOta(){otaBusy=false;++otaRun;releaseOtaNetwork();otaPhase=Protocol.OTA_IDLE;otaProgress=0;otaStatus="Firmware update canceled. The selected file is still ready.";activeOtaImage=null;activeOtaToken="";lastError="";changed();}
    private void failOta(String reason){
        if(reason==null||reason.trim().isEmpty())reason="Firmware update failed.";boolean askControllerToCancel=ready&&otaPhase>=Protocol.OTA_PREPARING&&otaPhase<=Protocol.OTA_READY;otaBusy=false;++otaRun;releaseOtaNetwork();otaPhase=Protocol.OTA_ERROR;otaStatus="Error • "+reason;lastError=reason;activeOtaImage=null;activeOtaToken="";changed();final String retained=reason;if(askControllerToCancel)command(s->Protocol.ota(Protocol.CANCEL_OTA,s),ok->{lastError=retained;changed();});
    }
    private void releaseOtaNetwork(){HttpURLConnection connection=otaConnection;otaConnection=null;if(connection!=null)connection.disconnect();ConnectivityManager.NetworkCallback callback=otaNetworkCallback;otaNetworkCallback=null;otaNetwork=null;if(callback!=null&&connectivity!=null)try{connectivity.unregisterNetworkCallback(callback);}catch(IllegalArgumentException ignored){}}
    private static String readResponse(InputStream input)throws IOException{if(input==null)return "";try(input;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[1024];int count,total=0;while((count=input.read(buffer))!=-1&&total<8192){int keep=Math.min(count,8192-total);output.write(buffer,0,keep);total+=keep;}return new String(output.toByteArray(),StandardCharsets.UTF_8);}}
    private static String serverError(String body,String fallback){int key=body.indexOf("\"error\"");if(key>=0){int colon=body.indexOf(':',key),start=colon<0?-1:body.indexOf('\"',colon),end=start<0?-1:body.indexOf('\"',start+1);if(start>=0&&end>start+1)return body.substring(start+1,end);}return fallback;}
    private static String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message;}
    public void download(){if(otaBusy||syncQueued||!ready)return;syncQueued=true;long from=store.cursor(deviceId);command(s->Protocol.sync(from,s),ok->{if(!ok)syncQueued=false;});handler.postDelayed(()->{if(syncQueued){syncQueued=false;download();}},30000);}
    public void startSession(){
        if(!sensorFirmware){lastError="Update the controller to sensor firmware first.";changed();return;}
        if(!fresh()){lastError="Wait for a fresh controller reading.";changed();return;}
        if(latest.active()){lastError="A recording is already running on the controller.";changed();return;}
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
    private void closeGatt(){ready=false;rx=null;latest=null;sensorFirmware=false;irFirmware=false;otaSupported=false;firmwareVersion="";fan=null;if(gatt!=null){if(allowed()){gatt.disconnect();gatt.close();}gatt=null;}queue.clear();pending=null;}
    @Override public void onDestroy(){destroyed=true;++otaRun;otaBusy=false;releaseOtaNetwork();otaWorker.shutdownNow();stopScan();closeGatt();handler.removeCallbacksAndMessages(null);store.close();current=null;super.onDestroy();}
}
