package com.aaron.breeze;

import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real service queue with a fake transport; never opens a Bluetooth device. */
public final class BleRecoveryChecks {
    private final Instrumentation test;
    private final List<FakeService> services=new ArrayList<>();
    private final List<String> temporaryPreferences=new ArrayList<>();

    private BleRecoveryChecks(Instrumentation test){this.test=test;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void main(Runnable action){test.runOnMainSync(action);}
    private SharedPreferences isolatedPreferences(String suffix){
        String name="temporary-ble-recovery-"+System.nanoTime()+"-"+suffix;
        temporaryPreferences.add(name);
        return test.getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);
    }
    private FakeService service(){
        FakeService fake=new FakeService();services.add(fake);
        fake.testPreferences=isolatedPreferences("settings");fake.tracePreferences=isolatedPreferences("trace");return fake;
    }
    private void manualReady(FakeService fake){fake.receive(reading(0));fake.irFirmware=true;fake.fan=new Protocol.FanState();fake.fan.time=System.currentTimeMillis();fake.fan.learning=255;fake.fan.learned=4095;}
    private static int queued(FakeService fake){try{Field field=BreezeService.class.getDeclaredField("queue");field.setAccessible(true);return ((ArrayDeque<?>)field.get(fake)).size();}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
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
    private static Object field(FakeService fake,String name){
        try{Field field=BreezeService.class.getDeclaredField(name);field.setAccessible(true);return field.get(fake);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static boolean retryScheduled(FakeService fake){return ((Handler)field(fake,"handler")).hasCallbacks((Runnable)field(fake,"reconnect"));}
    @SuppressWarnings("unchecked")
    private static void found(FakeService fake,String... addresses){Map<String,android.bluetooth.BluetoothDevice> devices=(Map<String,android.bluetooth.BluetoothDevice>)field(fake,"devices");for(String address:addresses)devices.put(address,null);}
    private static int sequence(byte[] command){return ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getShort(2)&65535;}
    private static byte[] reading(int ack){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0,(byte)1);packet.put(1,(byte)(8|16|32));packet.putShort(4,(short)3100);
        packet.putShort(6,(short)2300);packet.putShort(8,(short)5500);packet.putShort(16,(short)ack);
        return packet.array();
    }
    private static byte[] capability(int seconds){byte[] packet=new byte[20];packet[0]=4;packet[4]=7;packet[8]=64;packet[11]=(byte)seconds;return packet;}
    private static byte[] logicPacket(boolean automatic){byte[] packet=new byte[20];packet[0]=9;packet[1]=(byte)(automatic?1:0);packet[2]=(byte)255;packet[13]=3;packet[19]=1;return packet;}
    private static byte[] fanPacket(int speed,boolean synced){byte[] packet=new byte[20];packet[0]=5;packet[1]=(byte)(synced?4:0);packet[4]=(byte)speed;packet[5]=(byte)speed;packet[6]=packet[14]=packet[15]=(byte)255;packet[19]=7;ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(8,(short)4095);return packet;}
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
        boolean enabled=true,visible=true,permitted=true,realReconnectScheduling;
        int attempts,accepted,recoveries,reconnects,throwOnAttempt,connections,scans;
        String savedAddress="fake-saved-controller",connectedAddress="";
        SharedPreferences testPreferences,tracePreferences;
        int defaultResult=BluetoothStatusCodes.SUCCESS;
        final ArrayDeque<Integer> responses=new ArrayDeque<>();
        final List<byte[]> writes=new ArrayList<>();
        CountDownLatch acceptance,connectionStarted;
        FakeService(){ready=true;}
        @Override boolean allowed(){return permitted;}
        @Override boolean visibleForBluetooth(){return visible;}
        @Override boolean bluetoothEnabled(){return enabled;}
        @Override String savedDeviceAddress(){return savedAddress;}
        @Override public void connect(String address){connections++;connectedAddress=address;if(connectionStarted!=null)connectionStarted.countDown();}
        @Override public void scan(){scans++;scanning=true;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences isolated=switch(name){case "breeze"->testPreferences;case "fan-command-trace"->tracePreferences;default->null;};
            if(isolated==null)throw new AssertionError("Fake service requires isolated preferences for "+name);
            return isolated;
        }
        @Override boolean transportAvailable(){return true;}
        @Override void state(String value){status=value;}
        @Override public void changed(){}
        @Override void scheduleReconnect(){if(realReconnectScheduling)super.scheduleReconnect();else if(visible&&permitted&&enabled)reconnects++;}
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
    private void manualBusyIsNotRetried()throws Exception {
        FakeService fake=service();Results reliable=new Results();
        main(()->{
            manualReady(fake);fake.responses.add(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY);
            check(!fake.fanCommand(Protocol.SEND_IR,0,0),"busy manual tap must report not sent immediately");
            check(fake.attempts==1&&fake.accepted==0&&queued(fake)==0&&!fake.fanWritePending(),"unaccepted manual tap has no queued or pending work");
            check(fake.lastError.startsWith("Not sent"),"busy tap has explicit not-sent feedback");
            fake.command(seq->Protocol.clock(1800000000L,seq),reliable);
            check(fake.attempts==2&&fake.accepted==1,"reliable nonmanual command remains available after rejected tap");
        });
        SystemClock.sleep(600);
        main(()->{check(fake.attempts==2,"rejected manual tap cannot retry into a later command");fake.finishLast();reliable.successOnce();check(fake.recoveries==0,"busy manual rejection keeps the connection");});
    }
    private void manualAcknowledgedOnceAndNoTapBacklog(){
        FakeService fake=service();Results followup=new Results();
        main(()->{
            manualReady(fake);check(fake.fanCommand(Protocol.SEND_IR,0,0),"free transport accepts Power immediately");
            check(fake.accepted==1&&queued(fake)==0,"accepted manual tap bypasses the FIFO");
            check(!fake.fanCommand(Protocol.SEND_IR,1,0)&&!fake.fanCommand(Protocol.SEND_IR,2,0),"additional manual taps are rejected while one write is in flight");
            check(fake.accepted==1&&queued(fake)==0,"rapid manual taps do not accumulate");
            int manualSeq=sequence(fake.writes.get(0));fake.ackLast();
            check(fake.fanWritePending(),"manual ACK still waits for its GATT completion");
            fake.command(seq->Protocol.control(Protocol.STOP_RECORDING,0,seq),followup);
            check(queued(fake)==1&&fake.accepted==1,"nonmanual recording action retains normal reliable queue behavior");
            fake.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);
            check(fake.accepted==2&&queued(fake)==0,"manual completion advances exactly one reliable successor");
            fake.receive(reading(manualSeq));check(followup.values.isEmpty()&&fake.accepted==2,"duplicate manual ACK cannot complete or repeat a successor");
            fake.finishLast();followup.successOnce();
            check(fake.accepted==2&&queued(fake)==0,"rejected speed taps are never transmitted after completion");
            check(fake.fanCommand(Protocol.SEND_IR,1,0),"next deliberate tap sends after previous work completes");
            fake.finishLast();check(fake.accepted==3&&fake.fanCommandStatus.equals("Controller sent Speed +."),"accepted manual execution is confirmed once");
        });
    }
    private void manualDoesNotEnterTargetOrRecordingQueue(){
        FakeService fake=service();Results target=new Results(),recording=new Results();
        main(()->{
            manualReady(fake);fake.command(seq->Protocol.fan(Protocol.TARGET_SPEED,5,0,seq),target);
            check(!fake.fanCommand(Protocol.SEND_IR,0,0),"manual tap does not wait behind an in-flight target command");
            fake.command(seq->Protocol.control(Protocol.START_RECORDING,0,seq),recording);
            check(fake.accepted==1&&queued(fake)==1,"only recording action is queued behind the target");
            fake.finishLast();target.successOnce();check(fake.accepted==2&&fake.writes.get(1)[1]==2&&fake.writes.get(1)[4]==Protocol.START_RECORDING,"target completion preserves recording FIFO order");
            fake.finishLast();recording.successOnce();check(fake.accepted==2&&queued(fake)==0,"no rejected manual transmission appears later");
        });
    }

    private void manualReadinessWaitsForStartupWork(){
        FakeService fake=service();Results clock=new Results(),sync=new Results();
        main(()->{
            manualReady(fake);check(fake.manualIrReady(),"fresh idle transport is ready for a deliberate manual press");
            fake.command(seq->Protocol.clock(1800000000L,seq),clock);
            fake.command(seq->Protocol.sync(0,seq),sync);
            check(!fake.manualIrReady()&&fake.accepted==1&&queued(fake)==1,"startup clock and queued record synchronization keep manual controls waiting");
            check(!fake.fanCommand(Protocol.SEND_IR,0,0)&&fake.fanCommandStatus.startsWith("Not sent"),"a stale UI tap during startup is rejected with current feedback");
            check(fake.accepted==1&&queued(fake)==1,"startup rejection does not queue a later Power command");
            fake.finishLast();clock.successOnce();check(!fake.manualIrReady()&&fake.accepted==2,"manual controls keep waiting for record synchronization acknowledgement");
            fake.finishLast();sync.successOnce();check(fake.manualIrReady(),"manual controls become ready as soon as startup work finishes");
            fake.logicUploading=true;
            check(!fake.manualIrReady()&&!fake.fanCommand(Protocol.SEND_IR,1,0)&&fake.accepted==2,"profile upload also blocks the gap between its commands");
            fake.logicUploading=false;
            check(fake.manualIrReady()&&fake.fanCommand(Protocol.SEND_IR,0,0),"first deliberate press after readiness sends immediately");
            check(fake.accepted==3&&queued(fake)==0&&!fake.manualIrReady(),"one deliberate press produces exactly one manual write");
            fake.finishLast();check(fake.manualIrReady()&&fake.accepted==3&&fake.fanCommandStatus.equals("Controller sent Power."),"acknowledged manual write restores readiness without replaying rejected taps");
        });
    }

    private void manualRejectionReplacesStaleSuccess(){
        FakeService fake=service();
        main(()->{
            manualReady(fake);fake.fanCommandStatus="Controller sent Power.";fake.fan.time=System.currentTimeMillis()-6001;
            check(!fake.manualIrReady()&&!fake.fanCommand(Protocol.SEND_IR,1,0),"stale fan telemetry blocks a manual press");
            check(fake.fanCommandStatus.equals(fake.lastError)&&fake.fanCommandStatus.startsWith("Not sent")&&!fake.fanCommandStatus.contains("Controller sent"),"stale telemetry rejection replaces the previous success");
            manualReady(fake);fake.fanCommandStatus="Controller sent Speed +.";setObject(fake,"irReceiverTestRequested",true);
            check(!fake.manualIrReady()&&!fake.fanCommand(Protocol.SEND_IR,0,0),"receiver test blocks manual controls");
            check(fake.fanCommandStatus.equals(fake.lastError)&&fake.fanCommandStatus.startsWith("Not sent")&&fake.fanCommandStatus.contains("receiver test"),"receiver-test rejection replaces the previous success");
            setObject(fake,"irReceiverTestRequested",false);fake.fan.learning=0;
            check(!fake.manualIrReady()&&!fake.fanCommand(Protocol.SEND_IR,1,0)&&fake.fanCommandStatus.contains("learning"),"learning remains unavailable with current not-sent feedback");
            fake.fan.learning=255;fake.permitted=false;check(!fake.manualIrReady(),"revoked Bluetooth permission disables manual readiness");
            fake.permitted=true;fake.thirdProbeSupported=true;fake.fanCommandStatus="Controller sent Speed -.";fake.reconnectNow();
            check(fake.fanCommandStatus.isEmpty()&&!fake.manualIrReady()&&fake.accepted==0,"reconnect removes the previous connection's success without sending commands");
            check(!fake.thirdProbeSupported,"reconnect clears the old controller's third-probe capability");
            byte[] legacy=reading(0);legacy[1]|=2;ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putShort(10,(short)4000);fake.receive(legacy);
            check(Double.isNaN(fake.latest.thirdProbe),"first legacy frame after reconnect cannot reinterpret its old flag as D2 before capabilities arrive");
        });
    }

    private void profileOnlySynchronizesExplicitUserCorrection(){
        Context context=test.getTargetContext();String name="temporary-ble-profile-anchor-"+System.nanoTime();
        SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        FakeService unknown=service(),corrected=service();unknown.testPreferences=prefs;corrected.testPreferences=prefs;
        try{
            main(()->{
                check(prefs.edit().putInt("fanAnchor",1).putBoolean("fanAnchorCorrectionPending",false).commit(),"Isolated old anchor fixture is saved");
                manualReady(unknown);unknown.logicSupported=true;
                check(!unknown.fan.synced()&&unknown.applyLogicProfile(new LogicProfile()),"Unknown physical fan state still accepts an automatic profile");
                check(unknown.accepted==1&&unknown.writes.get(0)[4]==Protocol.LOGIC_GLOBAL,"An old saved speed must not synchronize an unknown fan before profile upload");
                for(int i=0;i<6;i++)unknown.finishLast();
                check(unknown.accepted==6&&!unknown.logicUploading&&!unknown.fan.synced(),"Profile upload completes without claiming the physical fan is synchronized");
                for(byte[] packet:unknown.writes)check(packet[4]!=Protocol.SYNC_SPEED&&packet[4]!=Protocol.SEND_IR,"Unknown-state profile upload neither guesses speed nor sends manual IR");
                check(prefs.getInt("fanAnchor",-1)==1&&!prefs.getBoolean("fanAnchorCorrectionPending",true),"Applying a profile preserves the old preference without treating it as a new correction");

                check(prefs.edit().putInt("fanAnchor",7).putBoolean("fanAnchorCorrectionPending",true).commit(),"Isolated explicit correction fixture is saved");
                manualReady(corrected);corrected.logicSupported=true;
                check(corrected.applyLogicProfile(new LogicProfile()),"An explicit pending fan correction is accepted");
                byte[] first=corrected.writes.get(0);check(corrected.accepted==1&&first[4]==Protocol.SYNC_SPEED&&first[5]==7&&first[6]==1,"Only the explicit chosen setting is synchronized before the profile");
                corrected.finishLast();
                check(!prefs.getBoolean("fanAnchorCorrectionPending",true)&&corrected.accepted==2&&corrected.writes.get(1)[4]==Protocol.LOGIC_GLOBAL,"Confirmed correction clears its pending flag and starts profile upload");
                for(int i=0;i<6;i++)corrected.finishLast();
                check(corrected.accepted==7&&!corrected.logicUploading,"The correction and six profile packets are each sent once");
            });
        }finally{context.deleteSharedPreferences(name);}
    }

    private void homeLogicPauseSurvivesImplicitEnablePaths(){
        FakeService fake=service(),failed=service(),deferred=service();
        main(()->{
            manualReady(fake);fake.logicSupported=true;fake.receive(logicPacket(true));
            check(fake.homeLogicReady()&&fake.setHomeLogicEnabled(false),"confirmed idle Logic accepts a Home pause request");
            check(fake.accepted==1&&fake.writes.get(0)[4]==Protocol.AUTO&&fake.writes.get(0)[5]==0,"Home pause sends exactly one AUTO zero");
            check(fake.logic.automatic()&&!fake.testPreferences.getBoolean("homeLogicPaused",false)&&!fake.homeLogicReady(),"request alone changes neither confirmed Logic nor persisted pause intent");
            fake.finishLast();check(fake.testPreferences.getBoolean("homeLogicPaused",false)&&fake.logic.automatic()&&!fake.homeLogicReady(),"successful ACK persists pause but waits for confirmed type-nine state");
            fake.receive(logicPacket(false));check(fake.homeLogicReady()&&!fake.logic.automatic(),"confirmed pause restores toggle readiness");
            fake.resumeLogic();check(fake.accepted==1,"implicit resume cannot override the Home pause");
            fake.setFanAnchor(4);check(fake.accepted==2&&fake.writes.get(1)[4]==Protocol.SYNC_SPEED,"explicit fan correction remains available while Logic is paused");
            fake.finishLast();check(fake.accepted==2&&queued(fake)==0,"fan correction cannot automatically resume paused Logic");
            check(!fake.applyLogicProfile(new LogicProfile())&&fake.accepted==2&&fake.testPreferences.getBoolean("homeLogicProfileDeferred",false),"autosaved profile defers its enabling commit while paused");
            fake.reconnectNow();fake.ready=true;manualReady(fake);byte[] pausedCapability=capability(10);pausedCapability[8]|=8;pausedCapability[10]=2;fake.receive(pausedCapability);fake.receive(pausedCapability);
            check(fake.accepted==3&&fake.writes.get(2)[4]==Protocol.AUTO&&fake.writes.get(2)[5]==0&&queued(fake)==0,"reconnect restores pause once instead of uploading an enabling profile");
            fake.finishLast();fake.receive(pausedCapability);check(fake.accepted==3&&fake.testPreferences.getBoolean("homeLogicPaused",false),"repeated capability status retains persisted pause without extra commands");
            check(fake.applyLogicProfileExplicit(new LogicProfile()),"explicit Setup apply may intentionally resume Logic");
            for(int part=0;part<5;part++){check(fake.testPreferences.getBoolean("homeLogicPaused",false),"pause intent survives until the commit ACK");fake.finishLast();}
            check(fake.testPreferences.getBoolean("homeLogicPaused",false)&&fake.writes.get(fake.writes.size()-1)[4]==Protocol.LOGIC_COMMIT,"commit submission alone does not clear pause intent");
            fake.finishLast();check(!fake.testPreferences.getBoolean("homeLogicPaused",true)&&!fake.testPreferences.getBoolean("homeLogicProfileDeferred",true)&&!fake.logicUploading,"only a successful explicit profile commit clears pause intent and deferred upload");

            manualReady(failed);failed.logicSupported=true;failed.testPreferences.edit().putBoolean("homeLogicPaused",true).apply();check(failed.applyLogicProfileExplicit(new LogicProfile()),"failure fixture begins an explicit apply");
            failed.onWriteCompleted(BluetoothGatt.GATT_FAILURE);check(failed.testPreferences.getBoolean("homeLogicPaused",false),"failed explicit profile apply cannot clear pause intent");

            manualReady(deferred);deferred.logicSupported=true;deferred.receive(logicPacket(false));deferred.testPreferences.edit().putBoolean("homeLogicPaused",true).putBoolean("homeLogicProfileDeferred",true).apply();
            check(deferred.setHomeLogicEnabled(true)&&deferred.accepted==1&&deferred.writes.get(0)[4]==Protocol.LOGIC_GLOBAL,"Home resume uploads the latest deferred profile instead of enabling obsolete settings");
            for(int part=0;part<6;part++)deferred.finishLast();
            check(deferred.accepted==6&&!deferred.testPreferences.getBoolean("homeLogicPaused",true)&&!deferred.testPreferences.getBoolean("homeLogicProfileDeferred",true)&&!deferred.homeLogicReady(),"deferred profile commits once while the toggle awaits confirmed state");
            deferred.receive(logicPacket(true));check(deferred.homeLogicReady(),"confirmed deferred-profile activation restores Home toggle readiness");
        });
    }

    private void homeManualCorrectionRunsOnlyAfterAcknowledgement(){
        FakeService fake=service(),writeFirst=service(),failed=service(),rejected=service(),syncFailed=service();List<String> outcomes=new ArrayList<>(),plain=new ArrayList<>(),failure=new ArrayList<>(),rejection=new ArrayList<>(),syncFailure=new ArrayList<>();
        main(()->{
            manualReady(fake);fake.logicSupported=true;
            check(fake.homeFanCommand(1,3,(sent,setting)->outcomes.add(sent+":"+setting)),"Home accepts the first Speed plus touch");
            check(fake.accepted==1&&fake.writes.get(0)[4]==Protocol.SEND_IR&&fake.writes.get(0)[5]==1&&queued(fake)==0,"physical SEND is first, with no prefixed or queued SYNC");
            fake.ackLast();check(fake.accepted==1&&outcomes.isEmpty(),"ACK alone cannot start the post-command correction");
            fake.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);check(fake.accepted==2&&fake.writes.get(1)[4]==Protocol.SYNC_SPEED&&fake.writes.get(1)[5]==4,"completed manual write plus ACK applies typed three as four after Speed plus");
            check(fake.testPreferences.getInt("fanAnchor",-1)==4&&fake.testPreferences.getBoolean("fanAnchorCorrectionPending",false)&&outcomes.isEmpty(),"the adjusted setting remains pending until its own ACK");
            fake.finishLast();check(fake.accepted==2&&queued(fake)==0&&outcomes.equals(List.of("true:4")),"correction completes once and never resumes Logic");
            check(!fake.testPreferences.getBoolean("fanAnchorCorrectionPending",true)&&fake.testPreferences.getInt("fanLastKnownOnSpeed",-1)==4,"successful correction persists the current and retained settings");

            manualReady(writeFirst);check(writeFirst.homeFanCommand(2,null,(sent,setting)->plain.add(sent+":"+setting)),"ordinary Home command needs no correction");
            writeFirst.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);check(writeFirst.accepted==1&&plain.isEmpty(),"write completion without ACK does not complete Home work");
            writeFirst.ackLast();check(writeFirst.accepted==1&&plain.equals(List.of("true:null")),"ordinary known-state command has no extra SYNC");
            writeFirst.receive(fanPacket(2,true));check(writeFirst.testPreferences.getInt("fanAnchor",-1)==2&&writeFirst.testPreferences.getInt("fanLastKnownOnSpeed",-1)==2,"confirmed controller status keeps the phone setting synchronized");

            manualReady(failed);check(failed.homeFanCommand(1,3,(sent,setting)->failure.add(sent+":"+setting)),"failure fixture accepts one physical command");
            failed.onWriteCompleted(BluetoothGatt.GATT_FAILURE);check(failed.accepted==1&&queued(failed)==0&&failure.equals(List.of("false:null"))&&!failed.testPreferences.getBoolean("fanAnchorCorrectionPending",false),"failed manual command cannot submit a correction or replay a physical send");
            failed.ackLast();check(failed.accepted==1&&failure.size()==1,"late ACK after failure cannot start deferred correction");

            manualReady(rejected);check(rejected.homeFanCommand(1,3,(sent,setting)->rejection.add(sent+":"+setting)),"controller rejection fixture submits one physical command");rejected.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);byte[] rejectionAck=reading(sequence(rejected.writes.get(0)));rejectionAck[19]=20;rejected.receive(rejectionAck);
            check(rejected.accepted==1&&rejection.equals(List.of("false:null"))&&queued(rejected)==0&&!rejected.testPreferences.getBoolean("fanAnchorCorrectionPending",false),"rejected command ACK cannot queue SYNC or replay the physical command");

            manualReady(syncFailed);syncFailed.testPreferences.edit().putInt("fanLastKnownOnSpeed",6).apply();check(syncFailed.homeFanCommand(0,3,(sent,setting)->syncFailure.add(sent+":"+setting)),"Power correction failure fixture sends one Power");syncFailed.finishLast();
            syncFailed.onWriteCompleted(BluetoothGatt.GATT_FAILURE);check(syncFailed.accepted==2&&syncFailure.equals(List.of("true:null"))&&syncFailed.testPreferences.getBoolean("fanAnchorCorrectionPending",false)&&!syncFailed.testPreferences.contains("fanLastKnownOnSpeed"),"failed retained-speed correction leaves pending uncertainty and never replays Power or submits final SYNC zero");
        });
    }

    private void homePowerRetainsDeclaredSpeedAndAmbiguityStaysPending(){
        FakeService power=service();List<String> outcomes=new ArrayList<>();
        main(()->{
            manualReady(power);power.receive(fanPacket(6,true));power.logicSupported=true;
            check(power.homeFanCommand(0,3,(sent,setting)->outcomes.add(sent+":"+setting)),"authoritative typed three can correct Power off after the physical command");
            check(power.accepted==1&&power.writes.get(0)[4]==Protocol.SEND_IR,"Power SEND precedes both non-actuating corrections");power.finishLast();
            check(power.accepted==2&&power.writes.get(1)[4]==Protocol.SYNC_SPEED&&power.writes.get(1)[5]==3&&power.testPreferences.getInt("fanAnchor",-1)==0,"after Power ACK the first correction establishes retained three while displaying pending Off zero");power.finishLast();
            check(power.accepted==3&&power.writes.get(2)[4]==Protocol.SYNC_SPEED&&power.writes.get(2)[5]==0&&power.testPreferences.getInt("fanLastKnownOnSpeed",-1)==3&&power.testPreferences.getBoolean("fanAnchorCorrectionPending",false),"retained-speed ACK precedes final Off zero correction");power.finishLast();
            check(outcomes.equals(List.of("true:0"))&&power.accepted==3&&!power.testPreferences.getBoolean("fanAnchorCorrectionPending",true),"Power off ends with confirmed zero without any AUTO or additional Power");
            power.receive(fanPacket(0,true));check(power.homeFanCommand(0,null,(sent,setting)->outcomes.add(sent+":"+setting)),"next ordinary Power command uses the already tracked retained state");power.finishLast();power.receive(fanPacket(3,true));
            check(power.accepted==4&&power.writes.get(3)[4]==Protocol.SEND_IR&&power.testPreferences.getInt("fanAnchor",-1)==3,"ordinary Power restores confirmed three with no mandatory SYNC");
        });
        for(int[] command:new int[][]{{3,3},{6,3},{1,0},{2,0},{0,0}}){FakeService ambiguous=service();List<String> result=new ArrayList<>();main(()->{manualReady(ambiguous);check(ambiguous.homeFanCommand(command[0],command[1],(sent,setting)->result.add(sent+":"+setting)),"ambiguous command still sends first");ambiguous.finishLast();check(ambiguous.accepted==1&&result.equals(List.of("true:null"))&&ambiguous.lastError.startsWith("Check the current fan setting"),"unknown mode, extra, speed while Off or Power restore never invents a correction");});}
    }

    private void homeKnownWrapAndHighPowerTracking(){
        for(int[] transition:new int[][]{{1,9,10},{1,10,1},{2,1,10},{2,10,9}}){
            FakeService fake=service();List<String> outcomes=new ArrayList<>();
            main(()->{
                int slot=transition[0],before=transition[1],after=transition[2];manualReady(fake);fake.receive(fanPacket(before,true));
                check(fake.homeFanCommand(slot,before,(sent,setting)->outcomes.add(sent+":"+setting)),"known H transition sends its physical command first");
                check(fake.accepted==1&&fake.writes.get(0)[4]==Protocol.SEND_IR&&fake.writes.get(0)[5]==slot,"wrap correction cannot precede the requested manual command");fake.finishLast();
                check(fake.accepted==2&&fake.writes.get(1)[4]==Protocol.SYNC_SPEED&&fake.writes.get(1)[5]==after&&outcomes.isEmpty(),"manual ACK queues the exact wrapped setting without another physical command");fake.finishLast();
                check(outcomes.equals(List.of("true:"+after))&&fake.accepted==2&&queued(fake)==0,"known H transition completes once after its correction ACK");
                check(fake.testPreferences.getInt("fanAnchor",-1)==after&&fake.testPreferences.getInt("fanLastKnownOnSpeed",-1)==after&&!fake.testPreferences.getBoolean("fanAnchorCorrectionPending",true),"wrapped setting is persisted and no longer pending");
                fake.receive(fanPacket(after,true));check(fake.fan.speed==after&&fake.fan.synced()&&fake.testPreferences.getInt("fanAnchor",-1)==after,"live controller status agrees with the wrapped setting");
            });
        }
        FakeService high=service();Results corrected=new Results();List<String> power=new ArrayList<>();
        main(()->{
            manualReady(high);check(high.setHomeFanAnchor(10,corrected),"explicit H correction is accepted");
            check(high.accepted==1&&high.writes.get(0)[4]==Protocol.SYNC_SPEED&&high.writes.get(0)[5]==10,"H uses numeric ten in the existing non-actuating SYNC packet");high.finishLast();corrected.successOnce();high.receive(fanPacket(10,true));
            check(high.testPreferences.getInt("fanAnchor",-1)==10&&high.testPreferences.getInt("fanLastKnownOnSpeed",-1)==10,"H is saved as the current and retained-on setting");
            check(high.homeFanCommand(0,10,(sent,setting)->power.add(sent+":"+setting)),"Power off from a declared H setting sends immediately");high.finishLast();
            check(high.accepted==3&&high.writes.get(2)[4]==Protocol.SYNC_SPEED&&high.writes.get(2)[5]==10,"Power ACK first establishes retained H");high.finishLast();
            check(high.accepted==4&&high.writes.get(3)[4]==Protocol.SYNC_SPEED&&high.writes.get(3)[5]==0,"retained-H ACK precedes the final Off correction");high.finishLast();high.receive(fanPacket(0,true));
            check(power.equals(List.of("true:0"))&&high.testPreferences.getInt("fanAnchor",-1)==0&&high.testPreferences.getInt("fanLastKnownOnSpeed",-1)==10,"confirmed Off preserves retained H");
            check(high.homeFanCommand(0,0,(sent,setting)->power.add(sent+":"+setting)),"Power from Off can restore known retained H");high.finishLast();
            check(high.accepted==6&&high.writes.get(5)[4]==Protocol.SYNC_SPEED&&high.writes.get(5)[5]==10,"Power-on ACK corrects to retained H without another physical send");high.finishLast();high.receive(fanPacket(10,true));
            check(power.equals(List.of("true:0","true:10"))&&high.accepted==6&&queued(high)==0&&!high.testPreferences.getBoolean("fanAnchorCorrectionPending",true),"H power cycle completes without AUTO or extra physical commands");
            int writes=high.accepted;for(int speed:new int[]{9,10,1,10,9}){high.receive(fanPacket(speed,true));check(high.fan.speed==speed&&high.fan.synced()&&high.testPreferences.getInt("fanAnchor",-1)==speed&&high.testPreferences.getInt("fanLastKnownOnSpeed",-1)==speed,"each live nine/H/one transition updates the saved Home setting");}
            check(high.accepted==writes&&queued(high)==0,"live fan-status updates never send an automatic correction");
        });
    }

    private void ambiguousHomeDraftCannotCorrectAnotherCommand(){
        for(int[] initial:new int[][]{{3,3},{1,0},{2,0},{0,0}}){FakeService fake=service();List<String> outcomes=new ArrayList<>();main(()->{
            manualReady(fake);fake.testPreferences.edit().putString("homeFanSettingDraft",Integer.toString(initial[1])).putLong("homeFanDraftRevision",1).apply();
            check(fake.homeFanCommand(initial[0],initial[1],(sent,setting)->outcomes.add(sent+":"+setting)),"unknown transition fixture submits its physical command");fake.finishLast();
            check(fake.testPreferences.getBoolean("homeFanDraftNeedsConfirmation",false)&&fake.testPreferences.getString("homeFanSettingDraft","").equals(Integer.toString(initial[1])),"unknown result quarantines the pre-command baseline without discarding visible text");
            check(fake.homeFanCommand(4,initial[1],(sent,setting)->outcomes.add(sent+":"+setting)),"quarantined draft still permits an immediate physical horizontal command");fake.finishLast();
            check(fake.accepted==2&&queued(fake)==0&&outcomes.equals(List.of("true:null","true:null"))&&fake.writes.get(1)[4]==Protocol.SEND_IR&&fake.testPreferences.getBoolean("homeFanDraftNeedsConfirmation",false),"a later oscillation command cannot SYNC an obsolete Off or speed baseline");
            Results confirmed=new Results();check(fake.setHomeFanAnchor(3,confirmed),"explicit human confirmation can replace the quarantined baseline");fake.finishLast();confirmed.successOnce();check(!fake.testPreferences.getBoolean("homeFanDraftNeedsConfirmation",false),"only confirmed Done restores correction eligibility");
        });}
        FakeService newer=service();main(()->{manualReady(newer);newer.testPreferences.edit().putString("homeFanSettingDraft","3").putLong("homeFanDraftRevision",1).apply();check(newer.homeFanCommand(3,3,null),"new-edit fixture submits Mode");newer.testPreferences.edit().putString("homeFanSettingDraft","8").putLong("homeFanDraftRevision",2).apply();newer.finishLast();check(!newer.testPreferences.getBoolean("homeFanDraftNeedsConfirmation",false),"an old ambiguous result cannot quarantine a newer human declaration");});
    }

    private void newerDoneOverridesCapturedHomeCorrection(){
        FakeService fake=service();List<String> manual=new ArrayList<>();Results done=new Results();main(()->{
            manualReady(fake);fake.logicSupported=true;check(fake.homeFanCommand(1,3,(sent,setting)->manual.add(sent+":"+setting)),"older draft three sends Speed plus immediately");
            check(fake.setHomeFanAnchor(7,done)&&fake.accepted==1&&queued(fake)==1,"new explicit Done seven waits behind the physical command");fake.finishLast();
            check(fake.accepted==2&&queued(fake)==0&&fake.writes.get(1)[4]==Protocol.SYNC_SPEED&&fake.writes.get(1)[5]==7&&manual.equals(List.of("true:null")),"older captured correction four is superseded instead of queued behind newer seven");fake.finishLast();done.successOnce();
            check(fake.accepted==2&&fake.testPreferences.getInt("fanAnchor",-1)==7&&!fake.testPreferences.getBoolean("fanAnchorCorrectionPending",true),"newer explicit confirmation wins and its callback is delivered exactly once");
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

    private void advertisedTelemetryCadence(){
        FakeService slow=service(),missing=service(),legacy=service();
        main(()->{
            slow.receive(capability(10));manualReady(slow);slow.logic=new Protocol.LogicState();
            check(slow.telemetryFreshnessMs()==25000,"ten-second capability selects the longer live-reading window");
            for(long age:new long[]{9000,20000}){
                setLong(slow,"lastPacket",SystemClock.elapsedRealtime()-age);slow.fan.time=System.currentTimeMillis()-age;slow.logic.time=System.currentTimeMillis()-age;
                slow.checkConnectionHealth();
                check(slow.fresh()&&slow.freshFan()&&slow.freshLogic()&&slow.manualIrReady()&&slow.recoveries==0,"normal cadence and one missed update preserve readings and immediate manual readiness at age "+age);
            }
            check(slow.fanCommand(Protocol.SEND_IR,0,0)&&slow.accepted==1&&queued(slow)==0,"manual Power still submits immediately between periodic updates");
            slow.finishLast();check(slow.manualIrReady()&&slow.accepted==1,"immediate ACK restores manual readiness without a periodic-update wait");
            setLong(slow,"lastPacket",SystemClock.elapsedRealtime()-25000);slow.checkConnectionHealth();
            check(!slow.fresh()&&!slow.freshFan()&&!slow.freshLogic()&&!slow.manualIrReady()&&slow.ready&&slow.recoveries==0,"readings expire at twenty-five seconds before the link watchdog disconnects");
            setLong(slow,"lastPacket",SystemClock.elapsedRealtime()-30000);slow.checkConnectionHealth();
            check(!slow.ready&&slow.latest==null&&slow.fan==null&&slow.logic==null&&slow.recoveries==1,"thirty seconds without telemetry recovers and clears all live state");
            check(slow.telemetryFreshnessMs()==6000&&((Long)field(slow,"telemetryIntervalMs"))==1000,"connection recovery resets the advertised cadence");

            missing.receive(capability(10));setLong(missing,"readySince",SystemClock.elapsedRealtime()-20000);missing.checkConnectionHealth();
            check(missing.ready&&missing.recoveries==0,"advertised cadence also gives the first live packet its declared watchdog window");
            setLong(missing,"readySince",SystemClock.elapsedRealtime()-30000);missing.checkConnectionHealth();
            check(!missing.ready&&missing.recoveries==1,"a missing first packet still reaches the thirty-second watchdog");

            manualReady(legacy);legacy.logic=new Protocol.LogicState();legacy.logic.time=System.currentTimeMillis();
            check(legacy.telemetryFreshnessMs()==6000,"legacy controllers retain six-second freshness");
            setLong(legacy,"lastPacket",SystemClock.elapsedRealtime()-6000);legacy.checkConnectionHealth();
            check(!legacy.fresh()&&legacy.ready&&legacy.recoveries==0,"legacy readings expire at six seconds without early link recovery");
            setLong(legacy,"lastPacket",SystemClock.elapsedRealtime()-15000);legacy.checkConnectionHealth();
            check(!legacy.ready&&legacy.recoveries==1,"legacy controllers retain the fifteen-second watchdog");
        });
    }

    private void fanAndLogicAgeIndependently(){
        FakeService fake=service();
        main(()->{
            fake.receive(capability(10));manualReady(fake);fake.logic=new Protocol.LogicState();fake.logic.time=System.currentTimeMillis();
            fake.fan.time=System.currentTimeMillis()-25000;
            check(fake.fresh()&&!fake.freshFan()&&!fake.freshLogic()&&!fake.manualIrReady(),"new sensor telemetry cannot keep an independently old fan packet fresh");
            fake.fan.time=System.currentTimeMillis();fake.logic.time=System.currentTimeMillis()-25000;
            check(fake.fresh()&&fake.freshFan()&&!fake.freshLogic()&&fake.manualIrReady(),"old Logic detail expires independently without blocking otherwise ready manual controls");
            fake.logic.time=System.currentTimeMillis();
            check(fake.freshLogic(),"new Logic status restores its live detail");
            fake.receive(capability(0));fake.fan.time=System.currentTimeMillis()-6000;
            check(fake.telemetryFreshnessMs()==6000&&!fake.freshFan(),"invalid advertised cadence falls back to the legacy fan-status limit");
            fake.fan.time=System.currentTimeMillis();fake.logic.time=System.currentTimeMillis()-6000;
            check(fake.freshFan()&&!fake.freshLogic(),"legacy Logic detail retains the six-second limit independently");
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

    private void idleWatchdogRecoversWithoutPostponingRetry()throws Exception {
        FakeService fake=service();fake.realReconnectScheduling=true;fake.connectionStarted=new CountDownLatch(1);
        main(()->{
            fake.ready=false;fake.checkConnectionHealth();
            check(retryScheduled(fake)&&fake.connections==0,"idle disconnected service schedules automatic recovery");
        });
        SystemClock.sleep(1800);
        main(()->{
            fake.checkConnectionHealth();fake.scheduleReconnect();
            check(retryScheduled(fake)&&fake.connections==0,"watchdog and repeated recovery requests retain the pending retry");
        });
        await(fake.connectionStarted,2000,"repeated recovery requests must not postpone the original three-second retry");
        main(()->check(fake.connections==1&&fake.connectedAddress.equals(fake.savedAddress)&&fake.scans==0,"idle recovery reconnects to the saved controller once"));
    }

    private void watchdogRespectsActiveWorkAndControllerChoice(){
        FakeService healthy=service(),scanning=service(),picker=service(),saved=service(),hidden=service(),disabled=service(),denied=service();
        main(()->{
            healthy.receive(reading(0));healthy.checkConnectionHealth();
            check(healthy.ready&&healthy.recoveries==0&&healthy.reconnects==0,"healthy connection remains intact");
            scanning.ready=false;scanning.scanning=true;scanning.checkConnectionHealth();
            check(scanning.reconnects==0,"active scan is allowed to finish without a competing retry");
            picker.ready=false;picker.savedAddress="";found(picker,"first-controller","second-controller");picker.checkConnectionHealth();
            check(picker.reconnects==0&&picker.foundDevices().size()==2,"first pairing with several controllers preserves the user's choice");
            saved.ready=false;found(saved,"first-controller","second-controller");saved.checkConnectionHealth();
            check(saved.reconnects==1,"nearby alternatives cannot strand recovery of a saved controller");
            hidden.ready=false;hidden.visible=false;hidden.realReconnectScheduling=true;hidden.checkConnectionHealth();
            disabled.ready=false;disabled.enabled=false;disabled.realReconnectScheduling=true;disabled.checkConnectionHealth();
            denied.ready=false;denied.permitted=false;denied.realReconnectScheduling=true;denied.checkConnectionHealth();
            check(!retryScheduled(hidden)&&!retryScheduled(disabled)&&!retryScheduled(denied),"background policy, Bluetooth off, and denied permission block automatic retries");
        });
    }

    private void explicitReconnectClearsWorkWithoutReplay()throws Exception {
        FakeService fake=service();Results result=new Results(2);fake.realReconnectScheduling=true;
        main(()->{
            fake.receive(reading(0));fake.sensorFirmware=true;fake.irFirmware=true;
            fake.command(seq->Protocol.control(Protocol.START_RECORDING,0,seq),result);
            fake.command(seq->Protocol.fan(Protocol.TARGET_SPEED,5,0,seq),result);
            fake.scheduleReconnect();found(fake,"old-scan-result");fake.scanning=true;
            check(fake.accepted==1&&queued(fake)==1&&retryScheduled(fake),"fixture has an accepted command, queued successor, and pending retry");
            fake.reconnectNow();result.failures(2);
            check(!fake.ready&&!fake.scanning&&fake.latest==null&&!fake.sensorFirmware&&!fake.irFirmware,"explicit reconnect clears stale readiness, scan, capabilities, and telemetry");
            check(queued(fake)==0&&fake.foundDevices().isEmpty()&&!retryScheduled(fake),"explicit reconnect clears old commands, scan results, and retry timer");
            check(fake.connections==1&&fake.connectedAddress.equals(fake.savedAddress)&&fake.scans==0,"explicit reconnect immediately targets the saved controller");
            fake.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);fake.ackLast();result.failures(2);
            check(fake.accepted==1,"late completion cannot replay the uncertain recording or queued fan command");
        });
        SystemClock.sleep(3200);
        main(()->check(fake.connections==1&&fake.accepted==1,"old reconnect timer cannot replace the new attempt or replay a command"));
        FakeService unpaired=service(),hidden=service(),disabled=service(),denied=service();
        main(()->{
            unpaired.savedAddress="";unpaired.reconnectNow();
            check(unpaired.connections==0&&unpaired.scans==1,"explicit reconnect scans when no controller is saved");
            hidden.visible=false;hidden.reconnectNow();disabled.enabled=false;disabled.reconnectNow();denied.permitted=false;denied.reconnectNow();
            for(FakeService blocked:List.of(hidden,disabled,denied))
                check(blocked.connections==0&&blocked.scans==0&&blocked.accepted==0,"explicit reconnect respects visibility, Bluetooth, and permission guards");
        });
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
        try{main(()->{
            for(FakeService fake:services)try{
                Field field=BreezeService.class.getDeclaredField("handler");field.setAccessible(true);
                ((Handler)field.get(fake)).removeCallbacksAndMessages(null);
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });}finally{for(String name:temporaryPreferences)test.getTargetContext().deleteSharedPreferences(name);}
    }
    public static void run(Instrumentation test)throws Exception {
        BleRecoveryChecks checks=new BleRecoveryChecks(test);
        try{
            checks.manualBusyIsNotRetried();checks.manualAcknowledgedOnceAndNoTapBacklog();checks.manualDoesNotEnterTargetOrRecordingQueue();
            checks.manualReadinessWaitsForStartupWork();checks.manualRejectionReplacesStaleSuccess();
            checks.profileOnlySynchronizesExplicitUserCorrection();checks.homeLogicPauseSurvivesImplicitEnablePaths();checks.homeManualCorrectionRunsOnlyAfterAcknowledgement();checks.homePowerRetainsDeclaredSpeedAndAmbiguityStaysPending();checks.homeKnownWrapAndHighPowerTracking();checks.ambiguousHomeDraftCannotCorrectAnotherCommand();checks.newerDoneOverridesCapturedHomeCorrection();
            checks.busyThenSuccess();checks.expiredBinderFailsWholeQueue();checks.explicitFailureAndCallbackFailure();
            checks.completionOrdersAndFollowup();checks.bluetoothStateAndTelemetry();checks.advertisedTelemetryCadence();checks.fanAndLogicAgeIndependently();
            checks.idleWatchdogRecoversWithoutPostponingRetry();checks.watchdogRespectsActiveWorkAndControllerChoice();checks.explicitReconnectClearsWorkWithoutReplay();
            checks.oldBusyRetryCannotWriteNewCommand();checks.storageCompletionRequiresMetadata();checks.timeoutsDoNotReplay();
        }finally{checks.cleanup();}
    }
}
