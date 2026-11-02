package com.aaron.breeze;

import android.app.Instrumentation;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Real correction/queue/ACK code, with every transport and preference access isolated. */
public final class DreoCorrectionChecks {
    private final Instrumentation test;
    private final List<FakeService> services=new ArrayList<>();
    private final List<String> temporaryPreferences=new ArrayList<>();
    private final List<java.io.File> temporaryDiagnostics=new ArrayList<>();

    private DreoCorrectionChecks(Instrumentation test){this.test=test;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}

    private FakeService service(int speed){
        String name="temporary-dreo-correction-"+System.nanoTime();temporaryPreferences.add(name);
        FakeService fake=new FakeService(test.getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE));services.add(fake);
        java.io.File directory=new java.io.File(test.getTargetContext().getCacheDir(),name);temporaryDiagnostics.add(directory);
        try{fake.drift=new DriftDiagnostics(directory,System::currentTimeMillis,android.os.SystemClock::elapsedRealtime);}
        catch(Exception error){throw new AssertionError(error);}
        fake.receive(reading(0));fake.irFirmware=true;fake.logicSupported=true;
        fake.logic=new Protocol.LogicState();fake.logic.flags=0;
        fake.fan=new Protocol.FanState();fake.fan.time=System.currentTimeMillis();fake.fan.flags=4|16;
        fake.fan.speed=speed;fake.fan.learning=255;fake.fan.learned=4095;
        fake.settings.edit().putInt("fanAnchor",speed).putBoolean("homeLogicPaused",true).putString("fixtureProfile","keep").commit();
        return fake;
    }

    private static final class FakeService extends BreezeService {
        final SharedPreferences settings;
        final List<byte[]> writes=new ArrayList<>();
        FakeService(SharedPreferences settings){this.settings=settings;ready=true;}
        @Override boolean visibleForBluetooth(){return true;}
        @Override boolean allowed(){return true;}
        @Override boolean transportAvailable(){return true;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            if(!"breeze".equals(name))throw new AssertionError("Unexpected preference access: "+name);
            return settings;
        }
        @Override public Object getSystemService(String name){throw new AssertionError("Fixture cannot access an Android system service");}
        @Override public void changed(){}
        @Override void state(String value){status=value;}
        @Override public void connect(String address){throw new AssertionError("Fixture cannot connect to Bluetooth");}
        @Override public void scan(){throw new AssertionError("Fixture cannot scan for Bluetooth");}
        @Override void scheduleReconnect(){throw new AssertionError("Fixture cannot schedule Bluetooth reconnection");}
        @Override void recoverConnection(String reason){throw new AssertionError("Unexpected fixture transport recovery");}
        @Override public void download(){throw new AssertionError("Fixture cannot access controller records");}
        @Override int submitWrite(byte[] bytes){
            if(bytes[1]==6&&bytes[4]==Protocol.SYNC_SPEED&&fan!=null&&fan.synced()&&fan.speed!=(bytes[5]&255)){
                try{
                    List<java.io.File> incidents=drift.journal.incidents();
                    check(!incidents.isEmpty(),"Mismatch window must be archived before submitting SYNC_SPEED");
                    String saved=drift.journal.readIncident(incidents.get(0).getName());
                    check(saved.contains("\"type\":\"mismatch\"")&&saved.contains("\"estimate\":"+fan.speed),"Archive contains the pre-correction estimate");
                    String[] lines=saved.trim().split("\n");
                    check(new JSONObject(lines[lines.length-1]).getString("type").equals("mismatch"),"Incident closes at the mismatch before the correction is queued");
                }catch(Exception error){throw new AssertionError(error);}
            }
            writes.add(bytes.clone());return BluetoothStatusCodes.SUCCESS;
        }
        void acknowledge(){acknowledge(0);}
        void acknowledge(int error){
            byte[] last=writes.get(writes.size()-1);
            byte[] packet=reading(ByteBuffer.wrap(last).order(ByteOrder.LITTLE_ENDIAN).getShort(2)&65535);
            packet[19]=(byte)error;receive(packet);
        }
    }

    private static byte[] reading(int acknowledgement){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0,(byte)1);packet.put(1,(byte)(8|16|32));packet.putShort(4,(short)3100);
        packet.putShort(6,(short)2300);packet.putShort(8,(short)5500);packet.putShort(16,(short)acknowledgement);
        return packet.array();
    }
    private static JSONObject field(Object value,long timestampMs)throws Exception{
        return new JSONObject().put("state",value).put("timestamp",timestampMs/1000);
    }
    private static DreoReport observedEquivalent(int mode)throws Exception{
        // Sanitized equivalent of the successful October 11 read, not a raw response capture.
        // The serial is synthetic; these values and timestamps came from parsed app state.
        JSONObject mixed=new JSONObject()
                .put("connected",field(true,1791643524000L))
                .put("mode",field(mode,1791643869000L))
                .put("oscmode",field(0,1791643987000L))
                .put("poweron",field(false,1791693429000L))
                .put("windlevel",field(1,1791693426000L));
        JSONObject response=new JSONObject().put("code",0).put("data",new JSONObject().put("sn","sanitized-observed-fan").put("mixed",mixed));
        return DreoReport.parse(response,"sanitized-observed-fan",1791694823505L,1791694823626L);
    }
    private static void onlyCorrection(FakeService fake,int count){
        check(fake.writes.size()==count,"Expected exactly "+count+" correction packets");
        for(byte[] packet:fake.writes){
            check(packet.length==20&&packet[1]==6&&packet[4]==Protocol.SYNC_SPEED,"Only SYNC_SPEED is permitted; no AUTO or SEND_IR");
            check(packet[5]==0&&ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getShort(6)==1,"Reported Off maps to SYNC_SPEED 0 with confirmed Normal mode");
        }
    }
    private static void pausedUnchanged(FakeService fake){
        check(fake.settings.getBoolean("homeLogicPaused",false),"Correction must preserve paused Logic preference");
        check(fake.logic.flags==0&&!fake.logic.automatic(),"Correction must preserve paused Logic telemetry");
        check("keep".equals(fake.settings.getString("fixtureProfile","")),"Correction must preserve unrelated fixture settings");
    }
    private static void correctionCount(FakeService fake,long expected){
        check(DreoCorrections.count(fake.settings)==expected,"Expected "+expected+" acknowledged level corrections");
        if(expected==0)check(history(fake.settings).length()==0,"Uncounted correction must not create a history event");
    }
    private static JSONArray history(SharedPreferences preferences){
        try{return new JSONArray(preferences.getString("dreoCorrectionHistory","[]"));}
        catch(JSONException error){throw new AssertionError("Correction history must remain valid JSON",error);}
    }
    private static long checkEvent(JSONArray events,int index,int before,int irSendCounter,DreoReport report){
        try{
            JSONObject event=events.getJSONObject(index);
            check(event.getInt("from")==before&&event.getInt("to")==0,"History stores the captured estimate and Dreo Off");
            check(event.getLong("correctedAt")>0,"History has a correction timestamp");
            check(event.getInt("irSendCounter")==irSendCounter,"History stores the IR counter from before synchronization");
            check(event.getLong("dreoReadAt")==report.receivedAtMs,"History preserves the Dreo response timestamp");
            check(event.getLong("dreoSpeedAt")==report.speedTimestamp&&event.getLong("dreoPowerAt")==report.powerTimestamp
                    &&event.getLong("dreoModeAt")==report.modeTimestamp,"History preserves source value timestamps separately");
            return event.getLong("correctedAt");
        }catch(JSONException error){throw new AssertionError("Correction history event is incomplete",error);}
    }
    private static void invalidateCorrectionGeneration(FakeService fake){
        try{
            Field generation=BreezeService.class.getDeclaredField("anchorCorrectionGeneration");generation.setAccessible(true);
            generation.setInt(fake,generation.getInt(fake)+1);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private void exercise(DreoReport off,DreoReport nonNormal){
        FakeService wrong=service(5);wrong.fan.sendCounter=41;List<Boolean> corrected=new ArrayList<>();
        wrong.applyDreoReport(off,corrected::add);onlyCorrection(wrong,1);
        check(corrected.isEmpty()&&wrong.settings.getInt("fanAnchor",-1)==5,"No correction success or persistence before ACK");
        correctionCount(wrong,0);
        wrong.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);
        check(corrected.isEmpty(),"Write callback alone is insufficient");
        correctionCount(wrong,0);
        // Fan telemetry may already contain the new setting when the command ACK arrives.
        wrong.fan.speed=0;
        wrong.acknowledge();
        check(corrected.equals(List.of(true)),"Correction acknowledges exactly once");
        check(wrong.settings.getInt("fanAnchor",-1)==0,"Acknowledged Dreo Off corrects isolated persisted estimate");
        correctionCount(wrong,1);
        wrong.acknowledge();
        check(corrected.equals(List.of(true)),"Duplicate ACK must not complete a correction twice");
        correctionCount(wrong,1);
        JSONArray firstHistory=history(wrong.settings);
        check(firstHistory.length()==1,"Duplicate ACK must not append another history event");
        long firstCorrectedAt=checkEvent(firstHistory,0,5,41,off);
        onlyCorrection(wrong,1);pausedUnchanged(wrong);

        check(wrong.settings.edit().commit(),"Flush isolated correction preferences before rereading");
        SharedPreferences reread=test.getTargetContext().getSharedPreferences(temporaryPreferences.get(0),Context.MODE_PRIVATE);
        check(DreoCorrections.count(reread)==1,"Correction total persists outside the service instance");
        Context offline=new ContextWrapper(null){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                if(!"breeze".equals(name))throw new AssertionError("Unexpected offline preference access: "+name);
                return reread;
            }
            @Override public Object getSystemService(String name){throw new AssertionError("Offline summary cannot access system services");}
        };
        String summary=DreoCorrections.summary(offline);
        check(summary.contains("Level corrections: 1")&&summary.contains("Last: 5 → Off"),"Offline summary shows the persisted count and captured previous level");
        check(summary.contains("Recorded on this phone from this update"),"Summary describes the count's phone-local scope");

        wrong.fan.flags=4|16;wrong.fan.speed=4;wrong.fan.sendCounter=54;wrong.fan.time=System.currentTimeMillis();
        List<Boolean> secondCorrection=new ArrayList<>();wrong.applyDreoReport(off,secondCorrection::add);
        onlyCorrection(wrong,2);correctionCount(wrong,1);
        wrong.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);wrong.acknowledge();
        check(secondCorrection.equals(List.of(true)),"A separate acknowledged mismatch records a second correction");
        correctionCount(wrong,2);
        check(wrong.settings.edit().commit(),"Flush both correction events before offline rereading");
        JSONArray bothHistory=history(reread);
        check(bothHistory.length()==2,"Two successful mismatches persist two history events");
        check(checkEvent(bothHistory,0,5,41,off)==firstCorrectedAt,"Appending preserves the first correction timestamp");
        check(checkEvent(bothHistory,1,4,54,off)>=firstCorrectedAt,"History timestamps are chronological");
        String offlineHistory=DreoCorrections.historyText(offline);
        check(offlineHistory.contains("Level corrections: 2")&&offlineHistory.contains("5 → Off")&&offlineHistory.contains("4 → Off"),
                "History remains available offline with both corrected levels");
        pausedUnchanged(wrong);

        FakeService matching=service(0);List<Boolean> unchanged=new ArrayList<>();
        matching.applyDreoReport(off,unchanged::add);
        check(unchanged.equals(List.of(true)),"Matching reported Off succeeds without a write");onlyCorrection(matching,0);pausedUnchanged(matching);
        correctionCount(matching,0);

        FakeService unknown=service(5);unknown.fan.flags&=~4;List<Boolean> established=new ArrayList<>();
        unknown.applyDreoReport(off,established::add);onlyCorrection(unknown,1);
        unknown.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);unknown.acknowledge();
        check(established.equals(List.of(true)),"Unknown estimate can still be synchronized");
        correctionCount(unknown,0);pausedUnchanged(unknown);

        FakeService sameUnknown=service(0);sameUnknown.fan.flags&=~4;List<Boolean> sameEstablished=new ArrayList<>();
        sameUnknown.applyDreoReport(off,sameEstablished::add);onlyCorrection(sameUnknown,1);
        sameUnknown.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);sameUnknown.acknowledge();
        check(sameEstablished.equals(List.of(true)),"Unsynchronized matching level can be confirmed");
        correctionCount(sameUnknown,0);pausedUnchanged(sameUnknown);

        FakeService failed=service(5);List<Boolean> rejectedCommand=new ArrayList<>();
        failed.applyDreoReport(off,rejectedCommand::add);onlyCorrection(failed,1);
        failed.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);failed.acknowledge(22);
        check(rejectedCommand.equals(List.of(false)),"Controller command error rejects the correction");
        check(failed.settings.getInt("fanAnchor",-1)==5,"Failed correction preserves the saved estimate");
        correctionCount(failed,0);pausedUnchanged(failed);

        FakeService stale=service(5);List<Boolean> rejectedStale=new ArrayList<>();
        stale.applyDreoReport(off,rejectedStale::add);onlyCorrection(stale,1);
        invalidateCorrectionGeneration(stale);
        stale.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);stale.acknowledge();
        check(rejectedStale.equals(List.of(false)),"Newer manual correction invalidates the older callback");
        check(stale.settings.getInt("fanAnchor",-1)==5,"Stale callback preserves the saved estimate");
        correctionCount(stale,0);pausedUnchanged(stale);

        FakeService mode=service(5);List<Boolean> rejectedMode=new ArrayList<>();
        mode.applyDreoReport(nonNormal,rejectedMode::add);
        check(rejectedMode.equals(List.of(false)),"Non-Normal mode is rejected");onlyCorrection(mode,0);pausedUnchanged(mode);
        correctionCount(mode,0);

        FakeService busy=service(5);busy.fan.flags|=32;busy.fan.target=6;List<Boolean> rejectedBusy=new ArrayList<>();
        busy.applyDreoReport(off,rejectedBusy::add);
        check(rejectedBusy.equals(List.of(false)),"Active target/ramp is rejected");onlyCorrection(busy,0);pausedUnchanged(busy);
        correctionCount(busy,0);
        try{check(busy.drift.journal.incidents().size()==1,"Busy mismatch is preserved even though correction waits");}
        catch(Exception error){throw new AssertionError(error);}

        FakeService busyMatch=service(0);busyMatch.fan.flags|=32;busyMatch.fan.target=6;
        busyMatch.drift.event("fixture_event","Keep until an idle matching check",new JSONObject());
        busyMatch.applyDreoReport(off,ok->check(!ok,"Busy match remains inconclusive"));
        try{check(new String(java.nio.file.Files.readAllBytes(busyMatch.drift.journal.currentFile().toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("fixture_event"),"Busy match must not discard the pending window");}
        catch(Exception error){throw new AssertionError(error);}

        FakeService pending=service(5);List<Boolean> first=new ArrayList<>(),second=new ArrayList<>();
        pending.applyDreoReport(off,first::add);pending.applyDreoReport(off,second::add);
        check(first.isEmpty()&&second.equals(List.of(false)),"Pending controller command rejects another correction");onlyCorrection(pending,1);
        correctionCount(pending,0);
        pending.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);pending.acknowledge();
        check(first.equals(List.of(true)),"The original pending correction completes");onlyCorrection(pending,1);pausedUnchanged(pending);
        correctionCount(pending,1);

        FakeService retained=service(4);JSONArray seeded=new JSONArray();
        try{
            for(int i=0;i<DreoCorrections.HISTORY_LIMIT;i++)seeded.put(new JSONObject().put("correctedAt",i+1L).put("from",1).put("to",0));
            check(retained.settings.edit().putLong("dreoCorrectionCount",DreoCorrections.HISTORY_LIMIT)
                    .putString("dreoCorrectionHistory",seeded.toString()).commit(),"Seed isolated history at its retention limit");
            List<Boolean> retainedCorrection=new ArrayList<>();retained.applyDreoReport(off,retainedCorrection::add);
            retained.onWriteCompleted(BluetoothGatt.GATT_SUCCESS);retained.acknowledge();
            check(retainedCorrection.equals(List.of(true)),"Correction succeeds when history reaches its retention limit");
            correctionCount(retained,DreoCorrections.HISTORY_LIMIT+1L);
            JSONArray retainedHistory=history(retained.settings);
            check(retainedHistory.length()==DreoCorrections.HISTORY_LIMIT,"History remains bounded while total continues increasing");
            check(retainedHistory.getJSONObject(0).getLong("correctedAt")==2,"Retention drops only the oldest event");
            checkEvent(retainedHistory,DreoCorrections.HISTORY_LIMIT-1,4,0,off);
            onlyCorrection(retained,1);pausedUnchanged(retained);
        }catch(JSONException error){throw new AssertionError(error);}
    }
    private void cleanup(){
        try{test.runOnMainSync(()->{
            for(FakeService fake:services)try{
                Field field=BreezeService.class.getDeclaredField("handler");field.setAccessible(true);
                ((Handler)field.get(fake)).removeCallbacksAndMessages(null);
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}
        });}finally{
            for(String name:temporaryPreferences)test.getTargetContext().deleteSharedPreferences(name);
            for(java.io.File directory:temporaryDiagnostics)try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(directory.toPath())){
                for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.delete(path);
            }catch(java.io.IOException error){throw new AssertionError(error);}
        }
    }
    public static String run(Instrumentation test)throws Exception{
        DreoCorrectionChecks checks=new DreoCorrectionChecks(test);
        DreoReport off=observedEquivalent(1),nonNormal=observedEquivalent(2);
        BreezeService original=BreezeService.current;
        try{
            test.runOnMainSync(()->checks.exercise(off,nonNormal));
            check(BreezeService.current==original,"Fixture never replaces the live service reference");
            return "PASS: real Dreo correction method sends exactly SYNC_SPEED 0/Normal for isolated wrong estimates; waits for write callback and ACK; counts each acknowledged change once even when telemetry changes before ACK; persists two timestamped corrections, source timestamps and IR counters for offline display; bounds history without resetting the total; matching, unknown, failed and stale corrections do not increment; non-Normal, busy and pending guards hold; paused Logic stays paused. Sanitized equivalent of observed Dreo fields, fake transport only. No Bluetooth, IR, Dreo network, account access, real settings or service-reference changes.\n";
        }finally{checks.cleanup();}
    }
}
