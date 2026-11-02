package com.aaron.breeze;

import android.app.Instrumentation;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothStatusCodes;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable databases and a fake BLE transport only; no real controller or UI interaction. */
final class PhoneRecordingChecks {
    private static final long START=System.currentTimeMillis()/1000*1000-86400000;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Field field(String name)throws Exception{Field f=BreezeService.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Field activityField(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void invoke(MainActivity activity,String name){try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}catch(Exception failure){throw new AssertionError(failure);}}
    private static byte[] record(int index,int flags,long time){
        ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)6).put((byte)flags).putInt(index).putInt((int)(time/1000));
        b.putShort((short)3650).putShort((short)2200).putShort((short)5000).putShort((short)3700).put((byte)0).put((byte)0);
        byte[] bytes=b.array();bytes[19]=(byte)Protocol.crc8(bytes,19);return bytes;
    }
    private static long scalar(SQLiteDatabase db,String sql){try(android.database.Cursor c=db.rawQuery(sql,null)){c.moveToFirst();return c.getLong(0);}}
    private static void storage(Context context)throws Exception{
        String name="temporary-phone-recording-store-"+System.nanoTime()+".db";
        long session;
        try{
            try(Store store=new Store(context,name)){
                long legacy=store.ingest("legacy",record(0,64|8,START-20000));store.ingest("legacy",record(1,128|8,START-10000));
                String before=store.csv(legacy);SQLiteDatabase db=store.getWritableDatabase();
                db.execSQL("DROP TABLE phone_recordings");db.execSQL("DROP TABLE recording_intent");db.setVersion(8);
                check(before.contains("sensor,"),"Legacy migration fixture populated");
            }
            try(Store store=new Store(context,name)){
                check(store.getReadableDatabase().getVersion()==9,"Additive schema 8 -> 9");
                check(!store.recordingIntent().known&&store.sessions().size()==1&&store.cursor("legacy")==2,"Migration preserves history/cursor without inventing recording intent");
                RecordingIntent intent=store.setPhoneRecording(true,START+123);session=intent.session;
                check(intent.active&&store.sessions().size()==2&&store.points(session).isEmpty(),"Start with zero sensors creates one empty recording");
                check(store.setPhoneRecording(true,START+1000).session==session&&store.sessions().size()==2,"Repeated Start is idempotent");
                store.recordDreoTemperature("synthetic-dreo",START+2000,START+2000,72.0,"F","segment");
                check(store.dreoTemperatures(session).size()==1,"Dreo is visible without any ESP sample");
                Store.FeelingEntry feeling=store.logTemperatureFeeling("synthetic-feeling",START+3000,null,-1,"WARM",4,"America/Chicago");
                check(feeling.session==session&&!feeling.isPending,"Voice feeling belongs to phone session without an ESP anchor");
            }
            try(Store store=new Store(context,name)){
                check(store.recordingIntent().active&&store.recordingIntent().session==session,"Open session survives database/process reopen");
                check(store.ingest("first-boot",record(0,64,START+10000))==session,"Late ESP arrival joins the existing session even with zero channels");
                check(Double.isNaN(store.points(session).get(0).probe),"No missing sensor is fabricated");
                check(store.ingest("first-boot",record(1,128|8,START+20000))==session,"Controller stop marker stays in same session");
                check(store.sessions().get(0).end==0&&store.recordingIntent().active,"Controller stop cannot end user recording");
                check(store.ingest("second-boot",record(0,64|8|16|32,START+30000))==session,"Reboot/new archive rejoins same session");
                check(store.sessions().size()==2&&store.points(session).size()==3,"Reconnect does not split or duplicate history");
                store.setPhoneRecording(false,START+40000);
                check(store.sessions().get(0).end==START+40000,"Only explicit Stop closes at the requested time");
                store.ingest("second-boot",record(1,128|8,START+50000));
                check(store.points(session).size()==3&&store.sessions().get(0).count==3&&store.csv(session).split("\n").length==4,"Late post-Stop data is excluded from plots, counts and export");
                check(scalar(store.getReadableDatabase(),"SELECT count(*) FROM samples WHERE session="+session)==4&&store.cursor("second-boot")==2,"Late raw record and durable acknowledgment are preserved");
                check(store.sessions().get(0).end==START+40000,"Late hardware Stop cannot extend user session");
                RecordingIntent next=store.setPhoneRecording(true,START+60000);
                check(next.session!=session&&store.ingest("second-boot",record(2,64|8,START+70000))==next.session,"A subsequent Start creates a distinct session");
                check(store.sessions().get(2).end==START-10000&&store.points(store.sessions().get(2).id).size()==2,"Legacy session remains unchanged");
                store.setPhoneRecording(false,START+80000);
            }
            try(Store store=new Store(context,name)){check(store.recordingIntent().known&&!store.recordingIntent().active,"Offline Stop persists across reopen");}
        }finally{context.deleteDatabase(name);}
    }
    private static final class IsolatedContext extends ContextWrapper {
        final String prefix="temporary-recording-service-"+System.nanoTime()+"-";
        final Set<String> databases=new HashSet<>(),preferences=new HashSet<>();
        IsolatedContext(Context base){super(base);}
        String db(String name){databases.add(prefix+name);return prefix+name;}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getDatabasePath(String name){return super.getDatabasePath(db(name));}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase(db(name),mode,factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler errors){return super.openOrCreateDatabase(db(name),mode,factory,errors);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){preferences.add(prefix+name);return super.getSharedPreferences(prefix+name,mode);}
        @Override public void sendBroadcast(Intent intent){}
        @Override public boolean stopService(Intent intent){return true;}
        @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("Test must never start a real service");}
        @Override public ComponentName startService(Intent intent){throw new AssertionError("Test must never start a real service");}
        void close(){for(String name:databases)getBaseContext().deleteDatabase(name);for(String name:preferences)getBaseContext().deleteSharedPreferences(name);}
    }
    private static final class FakeService extends BreezeService {
        boolean online;
        final List<byte[]> writes=new ArrayList<>();
        FakeService(Context context){attachBaseContext(context);ready=true;sensorFirmware=true;latest=new Protocol.Reading();latest.flags=128;}
        @Override boolean allowed(){return true;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override boolean transportAvailable(){return true;}
        @Override public boolean fresh(){return online&&ready;}
        @Override public void changed(){}
        @Override public void download(){}
        @Override int submitWrite(byte[] bytes){writes.add(bytes.clone());return BluetoothStatusCodes.SUCCESS;}
        void finish(boolean active){
            byte[] written=writes.get(writes.size()-1),packet=new byte[20];packet[0]=1;packet[1]=(byte)(128|(active?1:0));
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(16,ByteBuffer.wrap(written).order(ByteOrder.LITTLE_ENDIAN).getShort(2));
            onWriteCompleted(BluetoothGatt.GATT_SUCCESS);receive(packet);
        }
        int controls(int action){int result=0;for(byte[] b:writes)if(b[1]==2&&b[4]==action)result++;return result;}
    }
    private static void set(Instrumentation test,PhoneRecording owner,boolean active)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] error={"not completed"};
        test.runOnMainSync(()->owner.setActive(active,value->{error[0]=value;done.countDown();}));
        check(done.await(5,TimeUnit.SECONDS)&&error[0].isEmpty(),"Recording intent committed: "+error[0]);test.waitForIdleSync();
    }
    private static void service(Instrumentation test)throws Exception{
        IsolatedContext context=new IsolatedContext(test.getTargetContext());PhoneRecording[] owners={null};FakeService[] services={null};
        try{
            test.runOnMainSync(()->{owners[0]=PhoneRecording.get(context);services[0]=new FakeService(context);});
            PhoneRecording owner=owners[0];FakeService fake=services[0];field("phoneRecording").set(fake,owner);
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(!owner.ready()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
            check(owner.ready(),"Isolated recording state loaded");
            set(test,owner,true);long session=owner.state().session;
            test.runOnMainSync(()->{fake.reconcileRecording();check(fake.writes.isEmpty(),"Offline Start sends no hardware command");fake.online=true;fake.reconcileRecording();check(fake.writes.size()==1&&fake.writes.get(0)[1]==4,"ESP arrival starts clock handshake without requiring any sensor");});
            set(test,owner,false);
            test.runOnMainSync(()->{fake.finish(false);check(fake.controls(Protocol.START_RECORDING)==0,"Stop during clock ACK prevents delayed START");});
            set(test,owner,true);
            test.runOnMainSync(()->{fake.reconcileRecording();fake.finish(false);check(fake.controls(Protocol.START_RECORDING)==1,"New explicit Start reaches ESP");fake.finish(true);RecordingVoiceController.onTelemetry(context,false);check(RecordingVoiceController.describe().contains("active=true"),"Inactive hardware telemetry cannot clear phone voice recording state");});
            long resumed=owner.state().session;check(resumed!=session,"Explicit Stop/Start owns a new session");
            test.runOnMainSync(()->{fake.online=false;fake.reconcileRecording();check(owner.state().active,"Disconnect does not stop recording");});
            set(test,owner,false);
            test.runOnMainSync(()->{fake.reconcileRecording();check(fake.controls(Protocol.STOP_RECORDING)==0,"Offline Stop is saved without transport");fake.online=true;fake.reconcileRecording();check(fake.controls(Protocol.STOP_RECORDING)==1,"Reconnect sends the pending explicit Stop");fake.finish(false);});
            // Hold another command ahead of START to prove the send-time guard, beyond the clock callback guard.
            set(test,owner,true);
            test.runOnMainSync(()->{fake.reconcileRecording();fake.command(s->Protocol.clock(1800000000L,s),null);fake.finish(false);});
            set(test,owner,false);
            test.runOnMainSync(()->{fake.finish(false);check(fake.controls(Protocol.START_RECORDING)==1,"A queued START is discarded after explicit Stop");});
            try(Store reopened=new Store(context)){check(!reopened.recordingIntent().active,"Service telemetry never overwrites durable user Stop");}
            controls(test,context,owner);
        }finally{
            if(services[0]!=null){Handler handler=(Handler)field("handler").get(services[0]);test.runOnMainSync(()->handler.removeCallbacksAndMessages(null));}
            context.close();
        }
    }
    private static final class OffscreenActivity extends MainActivity {
        @Override public android.content.res.Resources.Theme getTheme(){return getBaseContext().getTheme();}
        void attach(Context context){attachBaseContext(context);}
    }
    private static void controls(Instrumentation test,Context context,PhoneRecording owner)throws Exception{
        OffscreenActivity[] screen={null};android.widget.Button[] buttons={null};Store store=new Store(context);
        try{
            test.runOnMainSync(()->{screen[0]=new OffscreenActivity();screen[0].attach(new android.view.ContextThemeWrapper(context,R.style.AppTheme));buttons[0]=new android.widget.Button(screen[0]);});
            activityField("store").set(screen[0],store);activityField("phoneRecording").set(screen[0],owner);activityField("sessionButton").set(screen[0],buttons[0]);
            check(BreezeService.current==null,"Offscreen Home check has no controller");
            test.runOnMainSync(()->{invoke(screen[0],"refresh");check(buttons[0].isEnabled()&&buttons[0].getText().toString().equals("Start recording"),"Start button enabled with no controller");invoke(screen[0],"sessionAction");});
            waitSaved(owner);
            test.runOnMainSync(()->{invoke(screen[0],"refresh");check(owner.state().active&&buttons[0].isEnabled()&&buttons[0].getText().toString().equals("Stop recording"),"Zero-sensor Home Start commits and exposes Stop");RecordingVoiceController.onTelemetry(context,false);invoke(screen[0],"refresh");check(buttons[0].getText().toString().equals("Stop recording"),"Inactive ESP telemetry cannot turn Home Stop into Start");invoke(screen[0],"sessionAction");});
            waitSaved(owner);check(!owner.state().active,"Home Stop works while controller is absent");
        }finally{
            if(screen[0]!=null){for(String name:new String[]{"worker","historyWorker","ouraWorker"})((ExecutorService)activityField(name).get(screen[0])).shutdown();Handler handler=(Handler)activityField("handler").get(screen[0]);test.runOnMainSync(()->handler.removeCallbacksAndMessages(null));}
            store.close();
        }
    }
    private static void waitSaved(PhoneRecording owner)throws Exception{long deadline=SystemClock.elapsedRealtime()+5000;while(!owner.ready()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);check(owner.ready(),"Home recording state saved within 5 seconds");}
    static String run(Instrumentation test)throws Exception{
        storage(test.getTargetContext());service(test);
        return "PASS: phone-owned recording; zero sensors; late ESP arrival; controller Stop and reboot markers; same-session multi-archive data; explicit offline Stop; persistence; Dreo and feelings without ESP; late-record preservation; schema 8 -> 9; real BLE queue with fake transport; Stop during clock handshake and queued START; actual Home Start/Stop handlers with offscreen controls and no controller. No physical fan commands or device UI interaction.\n";
    }
}
