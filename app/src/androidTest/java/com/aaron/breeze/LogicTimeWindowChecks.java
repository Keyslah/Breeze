package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/** Exercises the real profile-upload callbacks using isolated settings and manually acknowledged packets. */
public final class LogicTimeWindowChecks {
    private final Instrumentation test;
    private final List<String> temporaryPreferences=new ArrayList<>();
    private LogicTimeWindowChecks(Instrumentation test){this.test=test;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static LogicProfile window(int adjustment){LogicProfile p=new LogicProfile();p.timeWindow.enabled=true;p.timeWindow.adjustment=adjustment;return p;}
    private static LogicProfile windows(int... adjustments){
        LogicProfile p=window(adjustments[0]);
        for(int i=1;i<adjustments.length;i++){LogicProfile.TimeWindow w=new LogicProfile.TimeWindow();w.enabled=true;w.adjustment=adjustments[i];w.startMinute=600+(i-1)*60;w.endMinute=w.startMinute+30;p.timeWindows.add(w);}
        return p;
    }

    private static final class FakeService extends BreezeService {
        final SharedPreferences settings;
        final List<byte[]> packets=new ArrayList<>();
        Result awaiting;
        int sequence;
        FakeService(SharedPreferences settings,boolean supported,boolean multiple){this.settings=settings;ready=true;logicSupported=true;logicWindowSupported=supported;logicMultiWindowSupported=multiple;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            check("breeze".equals(name),"Unexpected preference access: "+name);return settings;
        }
        @Override public boolean freshFan(){return ready;}
        @Override public void changed(){}
        @Override public Object getSystemService(String name){throw new AssertionError("Fixture cannot access a system service");}
        @Override public void connect(String address){throw new AssertionError("Fixture cannot connect to Bluetooth");}
        @Override public void scan(){throw new AssertionError("Fixture cannot scan for Bluetooth");}
        @Override public void download(){throw new AssertionError("Fixture cannot download controller data");}
        @Override public void command(IntFunction<byte[]> make,Result done){
            check(awaiting==null,"Upload must wait for each preceding acknowledgement");
            check(done!=null,"Upload requires an acknowledgement callback");
            byte[] packet=make.apply(++sequence);check(packet.length==20&&packet[1]==6,"Only isolated fan-configuration packets are allowed");
            int action=packet[4]&255;
            check(action==Protocol.LOGIC_CLOCK||action==Protocol.LOGIC_GLOBAL||action==Protocol.LOGIC_RULE||action==Protocol.LOGIC_BOOST||action==Protocol.LOGIC_WINDOW||action==Protocol.LOGIC_COMMIT,"No IR, AUTO or other controller action is allowed");
            packets.add(packet.clone());awaiting=done;
        }
        int pendingAction(){check(awaiting!=null,"Expected an outstanding acknowledgement");return packets.get(packets.size()-1)[4]&255;}
        void acknowledge(boolean success){
            check(awaiting!=null,"An acknowledgement must match an outstanding packet");Result callback=awaiting;awaiting=null;
            if(!success)lastError="Fixture controller rejected command";callback.done(success);
        }
        void finish(){int remaining=2*LogicProfile.MAX_COMPILED_TIME_WINDOWS+16;while(awaiting!=null){check(remaining-->0,"Upload did not terminate");acknowledge(true);}}
        void disconnectFixture(){
            try{java.lang.reflect.Method close=BreezeService.class.getDeclaredMethod("closeGatt");close.setAccessible(true);close.invoke(this);}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
    }

    private FakeService service(boolean supported,boolean paused){
        return service(supported,false,paused);
    }
    private FakeService service(boolean supported,boolean multiple,boolean paused){
        String name="temporary-logic-time-window-"+System.nanoTime();temporaryPreferences.add(name);
        SharedPreferences settings=test.getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);
        check(settings.edit().putBoolean("homeLogicPaused",paused).putString("untouchedFixture","keep").commit(),"Create isolated settings");
        return new FakeService(settings,supported,multiple);
    }
    private static void actions(FakeService fake,int... expected){
        int[] actual=new int[fake.packets.size()];for(int i=0;i<actual.length;i++)actual[i]=fake.packets.get(i)[4]&255;
        check(Arrays.equals(expected,actual),"Expected upload actions "+Arrays.toString(expected)+", got "+Arrays.toString(actual));
        check("keep".equals(fake.settings.getString("untouchedFixture","")),"Unrelated isolated settings must survive");
    }
    private static void advanceTo(FakeService fake,int action){int remaining=20;while(fake.pendingAction()!=action){check(remaining-->0,"Expected stage never arrived");fake.acknowledge(true);}}
    private static void indexedWindow(FakeService fake,int packetIndex,int index,int count,int adjustment,boolean enabled){
        byte[] packet=fake.packets.get(packetIndex);
        ByteBuffer fields=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        check(packet[4]==Protocol.LOGIC_WINDOW&&packet[5]==(enabled?1:0)&&packet[10]==adjustment&&packet[11]==2&&(fields.getShort(12)&65535)==index&&(fields.getShort(14)&65535)==count,"Indexed window contains the applied entry, index and complete replacement count");
    }

    private void supportingController(){
        FakeService fake=service(true,false);LogicProfile p=window(-1);
        check(fake.applyLogicProfile(p),"Supported window should begin uploading");actions(fake,19);
        check(fake.logicUploading,"Upload remains pending before the clock ACK");
        p.timeWindow.adjustment=2; // An edit after Apply cannot alter the already queued snapshot.
        fake.acknowledge(true);actions(fake,19,13);
        fake.acknowledge(true);actions(fake,19,13,14);
        fake.acknowledge(true);actions(fake,19,13,14,15);
        fake.acknowledge(true);actions(fake,19,13,14,15,18);
        byte[] packet=fake.packets.get(4);ByteBuffer fields=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        check(packet[5]==1&&fields.getShort(6)==270&&fields.getShort(8)==480&&packet[10]==-1&&packet[11]==1,"Time window uses the original applied snapshot");
        fake.acknowledge(true);actions(fake,19,13,14,15,18,16);
        check(fake.logicUploading&&!"Applied to fan".equals(fake.logicApplyNote),"COMMIT must be acknowledged before success");
        fake.acknowledge(true);check(!fake.logicUploading&&"Applied to fan".equals(fake.logicApplyNote),"Acknowledged COMMIT completes the upload");
        check(fake.awaiting==null,"No automatic or IR command follows profile COMMIT");
    }

    private void unsupportedController(){
        FakeService enabled=service(false,false);
        check(!enabled.applyLogicProfile(window(-1)),"Enabled window requires advertised controller support");actions(enabled);
        check(!enabled.logicUploading&&enabled.logicApplyNote.contains("controller update required"),"Unsupported window explains the required update");
        FakeService disabled=service(false,false);check(disabled.applyLogicProfile(new LogicProfile()),"Old controller retains its normal profile path");
        actions(disabled,13);disabled.finish();actions(disabled,13,14,15,16);
        check("Applied to fan".equals(disabled.logicApplyNote),"Old controller completes a profile with the window disabled");
    }

    private void cumulativeWindowController(){
        FakeService fake=service(true,true,false);LogicProfile p=windows(-1,-3);
        p.timeWindows.get(1).startMinute=330;p.timeWindows.get(1).endMinute=570;
        check(fake.applyLogicProfile(p),"Multiple windows begin uploading on capable firmware");actions(fake,19);
        p.timeWindows.get(1).adjustment=5;p.timeWindows.remove(1);p.timeWindow.adjustment=2;
        advanceTo(fake,18);actions(fake,19,13,14,15,18);indexedWindow(fake,4,0,3,-1,true);
        fake.acknowledge(true);actions(fake,19,13,14,15,18,18);indexedWindow(fake,5,1,3,-4,true);
        ByteBuffer fields=ByteBuffer.wrap(fake.packets.get(5)).order(ByteOrder.LITTLE_ENDIAN);
        check(fields.getShort(6)==330&&fields.getShort(8)==480,"The original overlap interval combines -1 and -3 into -4 after source edits and removal");
        fake.acknowledge(true);actions(fake,19,13,14,15,18,18,18);indexedWindow(fake,6,2,3,-3,true);
        fields=ByteBuffer.wrap(fake.packets.get(6)).order(ByteOrder.LITTLE_ENDIAN);
        check(fields.getShort(6)==480&&fields.getShort(8)==570,"The later window's remaining interval retains its own adjustment");
        fake.acknowledge(true);actions(fake,19,13,14,15,18,18,18,16);
        check(fake.logicUploading&&!"Applied to fan".equals(fake.logicApplyNote),"All window ACKs still require the final COMMIT ACK");
        fake.acknowledge(true);check(!fake.logicUploading&&"Applied to fan".equals(fake.logicApplyNote),"Complete indexed upload succeeds");
    }

    private void olderControllerCannotTruncateWindows(){
        for(boolean singleWindowSupport:new boolean[]{false,true}){
            FakeService fake=service(singleWindowSupport,false,false);LogicProfile p=windows(-1,-3);
            check(!fake.applyLogicProfile(p),"Old firmware must not silently apply the first of multiple enabled windows");actions(fake);
            check(fake.logicApplyNote.contains("controller update required"),"Unsupported multi-window upload explains required firmware");
            p.timeWindow.enabled=false;
            check(!fake.applyLogicProfile(p),"An enabled later window also requires firmware support");actions(fake);
            p.timeWindows.get(1).enabled=false;
            check(fake.applyLogicProfile(p),"All-disabled windows retain old firmware compatibility");fake.finish();
            if(singleWindowSupport){actions(fake,19,13,14,15,18,16);check(fake.packets.get(4)[5]==0&&fake.packets.get(4)[11]==1,"Legacy packet disables its single window");}
            else actions(fake,13,14,15,16);
        }
    }

    private void incompleteWindowUploadNeverCommits(){
        FakeService fake=service(true,true,true);
        check(fake.applyLogicProfileExplicit(windows(-1,-3)),"Explicit multi-window apply starts while paused");advanceTo(fake,18);
        fake.acknowledge(true);indexedWindow(fake,5,1,2,-3,true);fake.acknowledge(false);
        actions(fake,19,13,14,15,18,18);
        check(!fake.logicUploading&&fake.awaiting==null&&fake.logicApplyNote.contains("Couldn't apply"),"Rejected later window stops without COMMIT");
        check(fake.settings.getBoolean("homeLogicPaused",false),"Incomplete multi-window upload preserves pause intent");
        check(fake.applyLogicProfileExplicit(window(-2)),"A fresh complete replacement can retry after failure");fake.finish();
        actions(fake,19,13,14,15,18,18,19,13,14,15,18,16);indexedWindow(fake,10,0,1,-2,true);
        check(!fake.settings.getBoolean("homeLogicPaused",true),"Only the successful replacement resumes Logic");
    }

    private void latestWindowListCoalescesAndClearsRemovedEntries(){
        FakeService fake=service(true,true,false);LogicProfile latest=windows(-2,-3,1);
        check(fake.applyLogicProfile(windows(-1,-3)),"First indexed list begins");
        check(fake.applyLogicProfile(windows(4,5)),"An intermediate list queues");
        check(fake.applyLogicProfile(latest),"The latest complete list replaces the queued list");
        latest.timeWindows.get(2).adjustment=9;latest.timeWindows.remove(1);
        fake.finish();actions(fake,19,13,14,15,18,18,16,19,13,14,15,18,18,18,16);
        indexedWindow(fake,4,0,2,-1,true);indexedWindow(fake,5,1,2,-3,true);
        indexedWindow(fake,11,0,3,-2,true);indexedWindow(fake,12,1,3,-3,true);indexedWindow(fake,13,2,3,1,true);
        check(fake.applyLogicProfile(window(-4)),"Removing later entries uploads a new complete replacement");fake.finish();
        indexedWindow(fake,19,0,1,-4,true);check(fake.packets.get(20)[4]==Protocol.LOGIC_COMMIT,"Reduced count is committed after its sole entry");
        LogicProfile disabled=windows(-1,-3);disabled.timeWindow.enabled=false;disabled.timeWindows.get(1).enabled=false;
        check(fake.applyLogicProfile(disabled),"A disabled list also replaces the controller's previous windows");fake.finish();
        indexedWindow(fake,25,0,1,-1,false);
        check(fake.packets.get(26)[4]==Protocol.LOGIC_COMMIT&&!fake.logicUploading,"A disabled replacement clears all earlier intervals before COMMIT");
    }

    private void manyWindowsAndDisconnect(){
        FakeService full=service(true,true,false);LogicProfile many=window(-1);many.timeWindow.startMinute=0;many.timeWindow.endMinute=1;
        int count=300;
        for(int i=1;i<count;i++){LogicProfile.TimeWindow w=new LogicProfile.TimeWindow();w.enabled=true;w.startMinute=i*2;w.endMinute=i*2+1;w.adjustment=-1;many.timeWindows.add(w);}
        check(full.applyLogicProfile(many),"Hundreds of separately configured windows upload");full.finish();
        for(int i=0;i<count;i++)indexedWindow(full,4+i,i,count,-1,true);
        check(full.packets.size()==count+5&&full.packets.get(full.packets.size()-1)[4]==Protocol.LOGIC_COMMIT,"Indices and count above 255 send one complete list followed by one COMMIT");
        check(many.timeWindows.size()==count,"Controller compilation does not replace the phone's editable list");

        FakeService disconnected=service(true,true,false);
        check(disconnected.applyLogicProfile(windows(-1,-3)),"Disconnect fixture begins an indexed upload");
        check(disconnected.applyLogicProfile(windows(-2,-4)),"Disconnect fixture has a queued latest list");
        advanceTo(disconnected,18);disconnected.acknowledge(true);disconnected.disconnectFixture();
        check(!disconnected.logicWindowSupported&&!disconnected.logicMultiWindowSupported&&!disconnected.logicUploading,"Disconnect resets both window capabilities and stops the upload");
        disconnected.acknowledge(true);actions(disconnected,19,13,14,15,18,18);
        check(disconnected.awaiting==null&&disconnected.logicApplyNote.contains("disconnected"),"Late ACK cannot commit or replay a queued list after disconnect");
    }

    private void failedStages(){
        FakeService clock=service(true,false);check(clock.applyLogicProfile(window(-1)),"Clock-failure fixture starts");
        clock.acknowledge(false);actions(clock,19);
        check(!clock.logicUploading&&clock.awaiting==null&&clock.logicApplyNote.contains("Couldn't apply time window")&&clock.logicApplyNote.contains(clock.lastError),"Clock failure stops before GLOBAL and explains failure");
        FakeService stage=service(true,false);check(stage.applyLogicProfile(window(-1)),"Window-failure fixture starts");advanceTo(stage,18);
        stage.acknowledge(false);actions(stage,19,13,14,15,18);
        check(!stage.logicUploading&&stage.awaiting==null&&stage.logicApplyNote.contains("Couldn't apply")&&stage.logicApplyNote.contains(stage.lastError),"Window rejection never commits the incomplete profile");
    }

    private void pausedAndExplicitResume(){
        FakeService paused=service(true,true);
        check(!paused.applyLogicProfile(window(-1)),"Ordinary profile change defers while Logic is paused");actions(paused);
        check(paused.settings.getBoolean("homeLogicPaused",false)&&paused.settings.getBoolean("homeLogicProfileDeferred",false),"Deferred profile preserves the pause");
        check(paused.applyLogicProfileExplicit(window(-1)),"Explicit Apply may resume Logic after upload");
        advanceTo(paused,16);check(paused.settings.getBoolean("homeLogicPaused",false)&&paused.settings.getBoolean("homeLogicProfileDeferred",false),"Pause and deferred flags remain until COMMIT ACK");
        paused.acknowledge(true);actions(paused,19,13,14,15,18,16);
        check(!paused.settings.getBoolean("homeLogicPaused",true)&&!paused.settings.getBoolean("homeLogicProfileDeferred",true),"Explicit resume becomes saved only after successful COMMIT");
        FakeService rejected=service(true,true);check(rejected.applyLogicProfileExplicit(window(-2)),"Explicit failure fixture starts");
        advanceTo(rejected,16);rejected.acknowledge(false);
        check(rejected.settings.getBoolean("homeLogicPaused",false)&&!rejected.logicUploading,"Rejected COMMIT preserves paused Logic");
    }

    private void latestProfileCoalesces(){
        FakeService fake=service(true,false);
        check(fake.applyLogicProfile(window(-1)),"First profile starts");
        check(fake.applyLogicProfile(window(-2)),"Next profile queues");
        check(fake.applyLogicProfile(window(2)),"Latest profile replaces the queued profile");actions(fake,19);
        fake.finish();actions(fake,19,13,14,15,18,16,19,13,14,15,18,16);
        check(fake.packets.get(4)[10]==-1&&fake.packets.get(10)[10]==2,"Only original and latest adjustments are uploaded");
        check(!fake.logicUploading&&"Applied to fan".equals(fake.logicApplyNote),"Coalesced latest profile finishes");
    }

    private void failedUploadCannotReplayAnOlderQueuedProfile(){
        for(int failedStage:new int[]{19,18}){
            FakeService fake=service(true,false);check(fake.applyLogicProfile(window(-1)),"Failed original profile starts");
            check(fake.applyLogicProfile(window(-2)),"Older pending profile queues");advanceTo(fake,failedStage);fake.acknowledge(false);
            int failedPackets=fake.packets.size();check(fake.applyLogicProfile(window(2)),"Newer profile can be applied after failure");fake.finish();
            check(fake.packets.size()==failedPackets+6,"Failed upload must not replay the older queued profile after a newer Apply");
            check(fake.packets.get(failedPackets+4)[4]==18&&fake.packets.get(failedPackets+4)[10]==2,"Successful retry uses the newest adjustment");
            check(!fake.logicUploading&&"Applied to fan".equals(fake.logicApplyNote),"Latest Apply completes without stale replay");
        }
    }

    private void exercise(){supportingController();unsupportedController();cumulativeWindowController();olderControllerCannotTruncateWindows();incompleteWindowUploadNeverCommits();latestWindowListCoalescesAndClearsRemovedEntries();manyWindowsAndDisconnect();failedStages();pausedAndExplicitResume();latestProfileCoalesces();failedUploadCannotReplayAnOlderQueuedProfile();}
    public static String run(Instrumentation test){
        LogicTimeWindowChecks checks=new LogicTimeWindowChecks(test);BreezeService original=BreezeService.current;
        try{
            test.runOnMainSync(checks::exercise);
            check(BreezeService.current==original,"Fixture never replaces the live service reference");
            return "PASS: actual profile upload waits for each ACK in CLOCK, GLOBAL, RANGE, BOOST, every indexed WINDOW, COMMIT order; preserves complete snapshots and per-window times; rejects enabled multiple windows on old firmware while retaining single-window/all-disabled compatibility; failed later windows never commit or resume paused Logic; latest complete lists coalesce, and replacement counts clear removed windows; clock/window failures never commit; paused Logic resumes only after explicit successful COMMIT; a newer Apply after failure cannot replay an older queued profile. Fake commands and temporary private settings only; no Bluetooth, IR, actual app settings or service-reference changes.\n";
        }finally{for(String name:checks.temporaryPreferences)test.getTargetContext().deleteSharedPreferences(name);}
    }
}
