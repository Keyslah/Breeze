package com.aaron.breeze;

import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real service queue with a fake transport; never opens a Bluetooth device. */
public final class BleRecoveryChecks {
    private final Instrumentation test;
    private final List<FakeService> services=new ArrayList<>();

    private BleRecoveryChecks(Instrumentation test){this.test=test;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void main(Runnable action){test.runOnMainSync(action);}
    private FakeService service(){FakeService fake=new FakeService();services.add(fake);return fake;}
    private static void await(CountDownLatch signal,long timeout,String message)throws InterruptedException {
        check(signal.await(timeout,TimeUnit.MILLISECONDS),message);
    }
    private static void setLong(FakeService fake,String name,long value){
        try{Field field=BreezeService.class.getDeclaredField(name);field.setAccessible(true);field.setLong(fake,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void setObject(FakeService fake,String name,Object value){
        try{Field field=BreezeService.class.getDeclaredField(name);field.setAccessible(true);field.set(fake,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static int sequence(byte[] command){return ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getShort(2)&65535;}
    private static byte[] reading(int ack){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0,(byte)1);packet.put(1,(byte)(8|16|32));packet.putShort(4,(short)3100);
        packet.putShort(6,(short)2300);packet.putShort(8,(short)5500);packet.putShort(16,(short)ack);
        return packet.array();
    }
    private static byte[] metadata(int base,int count,int freeBytes,boolean storageReady,int archive){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0,(byte)3);packet.putInt(2,base);packet.putInt(6,count);packet.putInt(10,freeBytes);
        packet.put(14,(byte)(storageReady?1:0));packet.putInt(15,archive);return packet.array();
    }
    private static final class Results implements BreezeService.Result {
        final List<Boolean> values=new ArrayList<>();
        final CountDownLatch completed;
        Results(){this(1);}
        Results(int count){completed=new CountDownLatch(count);}
        @Override public void done(boolean success){values.add(success);completed.countDown();}
        void successOnce(){check(values.equals(List.of(true)),"command must succeed exactly once: "+values);}
        void failures(int count){check(values.size()==count&&!values.contains(true),"all "+count+" commands must fail exactly once: "+values);}
    }
    private static final class FakeService extends BreezeService {
        boolean enabled=true;
        int attempts,accepted,recoveries,reconnects,throwOnAttempt;
        int defaultResult=BluetoothStatusCodes.SUCCESS;
        final ArrayDeque<Integer> responses=new ArrayDeque<>();
        final List<byte[]> writes=new ArrayList<>();
        CountDownLatch acceptance;
        FakeService(){ready=true;}
        @Override boolean allowed(){return true;}
        @Override boolean bluetoothEnabled(){return enabled;}
        @Override boolean transportAvailable(){return true;}
        @Override void state(String value){status=value;}
        @Override public void changed(){}
        @Override void scheduleReconnect(){if(enabled)reconnects++;}
        @Override void recoverConnection(String reason){recoveries++;super.recoverConnection(reason);}
        @Override int submitWrite(byte[] bytes){
            attempts++;writes.add(bytes.clone());
            if(attempts==throwOnAttempt)throw new IllegalStateException("fake expired Bluetooth binder");
            int result=responses.isEmpty()?defaultResult:responses.removeFirst();
            if(result==BluetoothStatusCodes.SUCCESS){accepted++;if(acceptance!=null)acceptance.countDown();}
            return result;
        }
        void ackLast(){receive(reading(sequence(writes.get(writes.size()-1))));}
        void finishLast(){onWriteCompleted(BluetoothGatt.GATT_SUCCESS);ackLast();}
    }

    private void busyThenSuccess()throws Exception {
        FakeService fake=service();Results result=new Results();fake.acceptance=new CountDownLatch(1);
        main(()->{
            fake.responses.add(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY);
            fake.command(seq->Protocol.clock(1800000000L,seq),result);
            check(fake.attempts==1&&fake.accepted==0&&fake.ready,"busy rejection keeps the command and connection");
            fake.receive(reading(sequence(fake.writes.get(0))));
            fake.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);
            check(result.values.isEmpty(),"unsubmitted command cannot be completed by a stale ACK/callback");
        });
        await(fake.acceptance,2000,"busy write should retry and be accepted");
        main(()->{
            check(fake.attempts==2&&fake.accepted==1,"busy retry must accept exactly one write");
            check(Arrays.equals(fake.writes.get(0),fake.writes.get(1)),"retry must preserve the complete packet and sequence");
            fake.finishLast();result.successOnce();check(fake.recoveries==0,"transient busy does not discard the connection");
        });
    }

    private void expiredBinderFailsWholeQueue()throws Exception {
        FakeService fake=service();Results result=new Results(3);fake.throwOnAttempt=2;
        main(()->{
            fake.responses.add(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY);
            for(int i=0;i<3;i++)fake.command(seq->Protocol.clock(1800000000L,seq),result);
            check(fake.attempts==1,"queued commands cannot bypass the rejected pending command");
        });
        await(result.completed,2000,"expired binder should fail pending and queued commands");
        main(()->{
            result.failures(3);check(!fake.ready&&fake.latest==null,"expired binder clears connection readiness and telemetry");
            check(fake.recoveries==1&&fake.reconnects==1&&fake.accepted==0,"expired binder schedules one recovery without an accepted write");
            check(fake.lastError.contains("expired"),"binder exception is identified as an expired connection");
            fake.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);fake.ackLast();result.failures(3);
        });
    }

    private void explicitFailureAndCallbackFailure(){
        FakeService rejected=service();Results rejection=new Results();
        main(()->{
            rejected.defaultResult=BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            rejected.command(seq->Protocol.clock(1800000000L,seq),rejection);
            rejection.failures(1);check(!rejected.ready&&rejected.reconnects==1,"disconnected result invalidates and reconnects");
            check(rejected.lastError.contains("("+BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND+")"),"submission error retains the precise status");
            check(!rejected.lastError.contains("busy"),"non-busy failure cannot be reported as busy");
        });
        FakeService callback=service();Results callbackResults=new Results(2);
        main(()->{
            callback.command(seq->Protocol.clock(1800000000L,seq),callbackResults);
            callback.command(seq->Protocol.clock(1800000001L,seq),callbackResults);
            callback.onWriteCompleted(BluetoothGatt.GATT_FAILURE);
            callbackResults.failures(2);check(callback.accepted==1&&!callback.ready&&callback.recoveries==1,"failed accepted write is not replayed and fails its queued successor");
        });
    }

    private void completionOrdersAndFollowup(){
        FakeService ackFirst=service();Results ackResults=new Results();
        main(()->{
            ackFirst.command(seq->Protocol.clock(1800000000L,seq),ackResults);
            ackFirst.ackLast();check(ackResults.values.isEmpty(),"ACK alone cannot finish the write");
            ackFirst.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);ackResults.successOnce();
            ackFirst.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);ackFirst.ackLast();ackResults.successOnce();
        });
        FakeService callbackFirst=service();Results callbackResults=new Results();
        main(()->{
            callbackFirst.command(seq->Protocol.clock(1800000000L,seq),callbackResults);
            callbackFirst.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);
            check(callbackResults.values.isEmpty(),"Android callback alone cannot confirm controller execution");
            int seq=sequence(callbackFirst.writes.get(0));callbackFirst.receive(reading(seq%65535+1));
            check(callbackResults.values.isEmpty(),"other command's ACK must be ignored");
            callbackFirst.ackLast();callbackResults.successOnce();
        });
        FakeService followup=service();Results first=new Results(),second=new Results(),third=new Results();
        main(()->{
            followup.command(seq->Protocol.clock(1800000000L,seq),ok->{first.done(ok);if(ok)followup.command(seq->Protocol.clock(1800000002L,seq),third);});
            followup.command(seq->Protocol.clock(1800000001L,seq),second);
            check(followup.accepted==1,"only first command may be in flight");
            followup.finishLast();first.successOnce();
            check(followup.accepted==2&&second.values.isEmpty()&&third.values.isEmpty(),"completion callback enqueues one successor without overlapping writes");
            check(ByteBuffer.wrap(followup.writes.get(1)).order(ByteOrder.LITTLE_ENDIAN).getInt(4)==1800000001,"already queued successor stays ahead of callback-enqueued command");
            followup.finishLast();second.successOnce();check(followup.accepted==3&&third.values.isEmpty(),"third command waits for second completion");
            followup.finishLast();third.successOnce();check(followup.recoveries==0,"serialized callback follow-up needs no recovery");
        });
    }

    private void bluetoothStateAndTelemetry(){
        FakeService fake=service();Results result=new Results(2);
        main(()->{
            fake.command(seq->Protocol.clock(1800000000L,seq),result);
            fake.command(seq->Protocol.clock(1800000001L,seq),result);
            fake.enabled=false;fake.handleBluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
            result.failures(2);check(!fake.ready&&fake.status.equals("Bluetooth off")&&fake.reconnects==0,"Bluetooth off clears ready and waits for adapter");
            fake.handleBluetoothState(BluetoothAdapter.STATE_OFF);result.failures(2);
            fake.enabled=true;fake.handleBluetoothState(BluetoothAdapter.STATE_ON);
            check(fake.reconnects==1&&fake.status.contains("Reconnecting"),"Bluetooth on schedules recovery");
        });
        FakeService missing=service(),stale=service(),fresh=service();
        main(()->{
            long now=SystemClock.elapsedRealtime();
            setLong(missing,"readySince",now);missing.checkConnectionHealth();check(missing.ready,"new connection gets time for its first packet");
            setLong(missing,"readySince",now-15001);missing.checkConnectionHealth();
            check(!missing.ready&&missing.recoveries==1,"missing first telemetry invalidates the connection");
            stale.latest=Protocol.decode(reading(0),System.currentTimeMillis());stale.sensorFirmware=true;stale.irFirmware=true;stale.fan=new Protocol.FanState();
            setLong(stale,"lastPacket",now-15001);stale.checkConnectionHealth();
            check(!stale.ready&&stale.latest==null&&!stale.sensorFirmware&&!stale.irFirmware&&stale.fan==null,"stale telemetry clears cached connection capabilities and readings");
            fresh.receive(reading(0));fresh.checkConnectionHealth();check(fresh.fresh()&&fresh.recoveries==0,"fresh telemetry keeps the connection");
        });
    }

    private void oldBusyRetryCannotWriteNewCommand()throws Exception {
        FakeService fake=service();Results old=new Results(),next=new Results();
        main(()->{
            fake.responses.add(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY);
            fake.command(seq->Protocol.clock(1800000000L,seq),old);
            fake.recoverConnection("test invalidation");old.failures(1);
            fake.ready=true;fake.command(seq->Protocol.clock(1800000001L,seq),next);
            check(fake.attempts==2&&fake.accepted==1,"replacement command is accepted once");
        });
        SystemClock.sleep(600);
        main(()->{check(fake.attempts==2,"retry belonging to failed command cannot write into replacement connection");fake.finishLast();next.successOnce();old.failures(1);});
    }

    private void storageCompletionRequiresMetadata(){
        android.content.Context context=test.getTargetContext();
        String databaseName="temporary-ble-storage-completion-"+System.nanoTime()+".db";
        Store isolated=new Store(context,databaseName);FakeService fake=service();
        String address="fake-storage-controller";int archive=0x12345678,count=9;
        try{
            main(()->{
                setObject(fake,"store",isolated);setObject(fake,"deviceAddress",address);
                isolated.setCursor(address+":"+archive,count);
                fake.receive(metadata(7,count,0,false,archive));
                check(fake.controllerBase==7&&fake.controllerCount==count&&fake.controllerFreeBytes==0&&!fake.controllerStorageReady,"metadata exposes unreclaimed controller storage");
                check(fake.accepted==1&&fake.writes.get(0)[1]==5,"durable phone cursor queues retirement");
                check(ByteBuffer.wrap(fake.writes.get(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(4)==count,"retirement acknowledges the complete saved cursor");
                fake.finishLast();
                check(fake.transfer.contains("waiting for controller storage recovery")&&!fake.transfer.contains("storage ready"),"successful command ACK cannot falsely confirm reclaimed space");
                fake.receive(metadata(7,count,0,false,archive));
                check(fake.accepted==2&&fake.writes.get(1)[1]==5,"next metadata can retry uncompleted storage retirement");
                fake.receive(metadata(7,count,0,false,archive));
                check(fake.accepted==2,"repeated metadata does not duplicate retirement while its ACK is pending");
                fake.receive(metadata(count,count,98304,true,archive));fake.finishLast();
                check(fake.controllerBase==count&&fake.controllerStorageReady&&fake.controllerFreeBytes==98304,"new metadata proves storage recovery and available bytes");
                check(fake.transfer.equals("Downloaded and confirmed • controller storage ready"),"retirement completion reflects confirmed ready storage");
                check(fake.accepted==2&&isolated.cursor(address+":"+archive)==count,"storage completion does not erase or change the durable phone cursor");

                fake.sensorFirmware=true;byte[] active=reading(0);active[1]|=(byte)(1|128);fake.receive(active);
                fake.startSession();check(fake.accepted==2&&fake.lastError.contains("already running"),"active recording cannot issue another start");
                fake.receive(reading(0));fake.startSession();
                check(fake.accepted==3&&fake.writes.get(2)[1]==3&&fake.lastError.contains("storage is unavailable"),"unavailable storage downloads saved records before attempting recording");
                fake.finishLast();fake.receive(metadata(count,count,98304,true,archive));
            });
        }finally{
            main(()->{setObject(fake,"store",null);isolated.close();context.deleteDatabase(databaseName);});
        }
    }

    private void timeoutsDoNotReplay()throws Exception {
        FakeService accepted=service(),busy=service();Results acceptedResult=new Results(),busyResult=new Results();
        main(()->{
            accepted.command(seq->Protocol.clock(1800000000L,seq),acceptedResult);
            accepted.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);
            busy.defaultResult=BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            busy.command(seq->Protocol.clock(1800000001L,seq),busyResult);
        });
        await(acceptedResult.completed,8500,"accepted write without controller ACK must time out");
        await(busyResult.completed,2000,"persistent busy must reach bounded timeout");
        main(()->{
            acceptedResult.failures(1);busyResult.failures(1);
            check(accepted.attempts==1&&accepted.accepted==1&&!accepted.ready&&accepted.recoveries==1,"accepted uncertain command is never automatically resubmitted");
            check(busy.attempts>1&&busy.accepted==0&&!busy.ready&&busy.recoveries==1,"persistent busy retries are bounded and end in recovery");
            accepted.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);accepted.ackLast();acceptedResult.failures(1);
        });
        int[] attempts=new int[1];main(()->attempts[0]=busy.attempts);SystemClock.sleep(350);
        main(()->{check(busy.attempts==attempts[0]&&busy.recoveries==1,"late busy timer cannot submit or recover again");busyResult.failures(1);});
    }

    private void cleanup(){
        main(()->{
            for(FakeService fake:services)try{
                Field field=BreezeService.class.getDeclaredField("handler");field.setAccessible(true);
                ((Handler)field.get(fake)).removeCallbacksAndMessages(null);
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
    }
    public static void run(Instrumentation test)throws Exception {
        BleRecoveryChecks checks=new BleRecoveryChecks(test);
        try{
            checks.busyThenSuccess();checks.expiredBinderFailsWholeQueue();checks.explicitFailureAndCallbackFailure();
            checks.completionOrdersAndFollowup();checks.bluetoothStateAndTelemetry();
            checks.oldBusyRetryCannotWriteNewCommand();checks.storageCompletionRequiresMetadata();checks.timeoutsDoNotReplay();
        }finally{checks.cleanup();}
    }
}
