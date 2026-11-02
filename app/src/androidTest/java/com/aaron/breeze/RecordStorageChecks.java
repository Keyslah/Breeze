package com.aaron.breeze;

import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real temporary SQLite files and the production receive path; no Bluetooth, Activity, or account. */
final class RecordStorageChecks {
    private final Instrumentation test;
    private final Context context;
    private RecordStorageChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void main(Runnable task){test.runOnMainSync(task);}
    private static void await(CountDownLatch latch,String message)throws InterruptedException{check(latch.await(5,TimeUnit.SECONDS),message);}
    private static void field(Object target,String name,Object value){try{Field f=BreezeService.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new AssertionError(e);}}
    private static Object field(Object target,String name){try{Field f=BreezeService.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    static void awaitStorage(Instrumentation test,BreezeService service)throws InterruptedException{
        CountDownLatch done=new CountDownLatch(1);
        test.runOnMainSync(()->{RecordStorageQueue queue=(RecordStorageQueue)field(service,"recordStorage");if(queue==null)done.countDown();else check(queue.submit(()->null,unused->done.countDown(),error->{throw new AssertionError(error);}),"storage queue still open");});
        await(done,"storage work and main-thread completions finish");
    }
    private static byte[] metadata(int count){ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);b.put(0,(byte)3).putInt(2,0).putInt(6,count).putInt(10,0).putInt(15,1234);return b.array();}
    private static byte[] record(int index,int event){
        ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)2).put((byte)(8|16|32|event)).putInt(index).putInt(1800000000+index*10).putShort((short)3050).putShort((short)2250).putShort((short)6000).put((byte)2).put((byte)90).put((byte)0).put((byte)0);
        byte[] bytes=b.array();bytes[19]=(byte)Protocol.crc8(bytes,19);return bytes;
    }
    private static final class FakeService extends BreezeService {
        final List<byte[]> writes=new ArrayList<>();
        FakeService(){ready=true;}
        @Override boolean allowed(){return true;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override boolean bluetoothEnabled(){return false;}
        @Override boolean transportAvailable(){return true;}
        @Override public void changed(){}
        @Override void state(String status){this.status=status;}
        @Override void scheduleReconnect(){}
        @Override int submitWrite(byte[] bytes){writes.add(bytes.clone());return BluetoothStatusCodes.SUCCESS;}
    }
    private static final class BlockingStore extends Store {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        final AtomicInteger mainAccesses=new AtomicInteger();
        boolean block=true;
        BlockingStore(Context c,String name){super(c,name);}
        private void access(){if(Looper.myLooper()==Looper.getMainLooper())mainAccesses.incrementAndGet();}
        @Override public long cursor(String device){access();return super.cursor(device);}
        @Override public void setCursor(String device,long next){access();super.setCursor(device,next);}
        @Override public synchronized long ingest(String device,byte[] bytes){
            access();if(block){block=false;entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test commit gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            return super.ingest(device,bytes);
        }
        @Override public synchronized void close(){access();try{super.close();}finally{closed.countDown();}}
    }
    private void scenario(boolean disconnect,boolean shutdown,boolean corrupt)throws Exception{
        String name="temporary-background-records-"+System.nanoTime()+".db",archive="fixture:1234";
        BlockingStore store=new BlockingStore(context,name);FakeService service=new FakeService();
        try{
            main(()->{field(service,"store",store);field(service,"deviceAddress","fixture");service.receive(metadata(2));});
            awaitStorage(test,service);
            byte[] second=record(1,128);if(corrupt)second[10]^=1;
            main(()->{service.receive(record(0,64));service.receive(second);service.receive(metadata(2));});
            await(store.entered,"worker entered first record transaction");
            main(()->check(service.writes.isEmpty(),"no controller retirement before blocked durable writes finish"));
            if(disconnect)main(()->service.handleBluetoothState(BluetoothAdapter.STATE_OFF));
            if(shutdown)main(service::closeRecordStorage);
            // The main-thread calls above completed while the storage worker was blocked.
            store.release.countDown();
            if(shutdown){await(store.closed,"shutdown drains accepted records before closing database");main(()->check(service.writes.isEmpty(),"shutdown suppresses late retirement callbacks"));}
            else{
                awaitStorage(test,service);
                main(()->{
                    if(disconnect||corrupt)check(service.writes.isEmpty(),"stale/corrupt download cannot retire controller records");
                    else{check(service.writes.size()==1&&service.writes.get(0)[1]==5,"one retirement follows the completed commit barrier");check(Integer.toUnsignedLong(ByteBuffer.wrap(service.writes.get(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(4))==2,"retirement uses committed cursor 2");}
                    if(corrupt)check(service.lastError.contains("Controller records retained"),"corrupt input remains visible as retained data");
                    service.closeRecordStorage();
                });
                await(store.closed,"storage closes off the UI thread");
            }
            check(store.mainAccesses.get()==0,"0 SQLite cursor/ingest/close accesses on main thread");
            try(Store reopened=new Store(context,name)){
                check(reopened.cursor(archive)==(corrupt?1:2),"committed cursor survives reopening");
                check(reopened.points(reopened.sessions().get(0).id).size()==(corrupt?1:2),"all valid records survive shutdown/reconnection");
                try(android.database.Cursor c=reopened.getReadableDatabase().rawQuery("PRAGMA synchronous",null)){check(c.moveToFirst()&&c.getInt(0)==2,"SQLite synchronous remains FULL (2)");}
            }
        }finally{
            store.release.countDown();main(()->{service.closeRecordStorage();((Handler)field(service,"handler")).removeCallbacksAndMessages(null);});
            await(store.closed,"temporary store closed before cleanup");context.deleteDatabase(name);
        }
    }
    static String run(Instrumentation test)throws Exception{
        RecordStorageChecks checks=new RecordStorageChecks(test);
        checks.scenario(false,false,false);checks.scenario(true,false,false);checks.scenario(false,true,false);checks.scenario(false,false,true);
        return "PASS: 4 background record-storage scenarios; ordered durable commits before retirement, responsive main thread, stale connection suppression, shutdown drain, corrupt-record retention, restart persistence, synchronous FULL preserved; temporary databases and fake transport only\n";
    }
}
