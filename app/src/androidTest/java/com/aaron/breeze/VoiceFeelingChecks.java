package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Only task-created database names and fabricated sensor records; no BLE or breeze.db access. */
public final class VoiceFeelingChecks {
    private static final long BASE=1800000000000L;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
    private static void constrained(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(SQLiteConstraintException expected){}}
    private static Store.FeelingEntry byToken(Store store,String token){for(Store.FeelingEntry entry:store.allFeelings())if(token.equals(entry.eventToken))return entry;throw new AssertionError("Missing token "+token);}

    /** Capture notifications without waking the real Activity or its real database. */
    private static final class TestContext extends ContextWrapper {
        Store observed;
        int notifications;
        TestContext(Context context){super(context);}
        @Override public Context getApplicationContext(){return this;}
        @Override public void sendBroadcast(Intent intent){
            check(Store.FEELINGS_CHANGED.equals(intent.getAction()),"feeling update action");
            check(getPackageName().equals(intent.getPackage()),"feeling update is package scoped");
            check(observed!=null&&!observed.getWritableDatabase().inTransaction(),"notification follows commit");
            notifications++;
        }
    }

    public static String run(Instrumentation instrumentation){
        migration(instrumentation.getTargetContext());
        currentTimeAndValidation(instrumentation.getTargetContext());
        delayedBindingAndRecovery(instrumentation.getTargetContext());
        recordingContextIdentity(instrumentation);
        asynchronousWriter(instrumentation);
        return "PASS: v3 to v4 preserves all manual feelings, IDs, nights, probes, weather and cursors; current device time with original offset/zone; WARM/COLD signed bounds and validation; committed notifications; durable pending entries, exact archive/record binding and interval guards; UUID retry idempotence versus deliberate repeats; reopen and manual recovery; accepted parser commands through the real asynchronous writer and committed main-thread callback with immutable capture time; temporary databases only\n";
    }

    private static void recordingContextIdentity(Instrumentation test){
        Context quiet=new ContextWrapper(test.getTargetContext()){
            @Override public Context getApplicationContext(){return this;}
            @Override public void sendBroadcast(Intent intent){/* Do not wake real UI or storage. */}
        };
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{
            boolean exercised=false;
            try{
                check(BreezeService.current==null&&BedtimeVoiceService.current==null,"recording identity test requires isolated services; existing services stay untouched");
                exercised=true;
                RecordingVoiceController.onTelemetry(quiet,true);
                RecordingVoiceController.onRecordingMetadata(quiet,"AA:BB:CC:DD:EE:FF:71",7,true);
                RecordingVoiceController.RecordingContext first=RecordingVoiceController.snapshotForCommand();
                check("AA:BB:CC:DD:EE:FF:71".equals(first.archive)&&first.anchorRecord==6,"active metadata binds exact archive and final record");
                RecordingVoiceController.controllerConnecting("AA:BB:CC:DD:EE:FF");
                RecordingVoiceController.RecordingContext same=RecordingVoiceController.snapshotForCommand();
                check(first.archive.equals(same.archive)&&same.anchorRecord==6,"same-controller reconnect preserves recording proof");
                RecordingVoiceController.controllerConnecting("11:22:33:44:55:66");
                RecordingVoiceController.RecordingContext different=RecordingVoiceController.snapshotForCommand();
                check(different.archive==null&&different.anchorRecord==-1,"different controller cannot reuse recording proof");
                RecordingVoiceController.onRecordingMetadata(quiet,"11:22:33:44:55:66:72",9,true);
                check(RecordingVoiceController.snapshotForCommand().anchorRecord==8,"new active metadata establishes its own anchor");
                RecordingVoiceController.onTelemetry(quiet,false);
                RecordingVoiceController.RecordingContext idle=RecordingVoiceController.snapshotForCommand();
                check(idle.archive==null&&idle.anchorRecord==-1,"confirmed idle clears archive and recording anchor");
            }catch(Throwable error){failure.set(error);}
            finally{
                if(exercised)try{RecordingVoiceController.onTelemetry(quiet,false);}catch(Throwable error){failure.compareAndSet(null,error);}
            }
        });
        if(failure.get()!=null)throw new AssertionError("Recording identity integration failed",failure.get());
    }

    /** Redirect every database entry point used by production SQLiteOpenHelper. */
    private static final class WriterContext extends ContextWrapper {
        final String databaseName="temporary-voice-writer-"+System.nanoTime()+".db";
        final AtomicBoolean blockFirstOpen=new AtomicBoolean();
        final CountDownLatch openReached=new CountDownLatch(1),releaseOpen=new CountDownLatch(1);
        final AtomicInteger databaseAccesses=new AtomicInteger(),feelingUpdates=new AtomicInteger(),voiceUpdates=new AtomicInteger();
        volatile CountDownLatch callbackFinished=new CountDownLatch(0);
        WriterContext(Context context){super(context);}
        @Override public Context getApplicationContext(){return this;}
        private String redirected(String name){
            check("breeze.db".equals(name)||databaseName.equals(name),"unexpected writer database name: "+name);
            databaseAccesses.incrementAndGet();
            if(blockFirstOpen.compareAndSet(true,false)){
                check(!Looper.getMainLooper().isCurrentThread(),"database writer must be off the main thread");
                openReached.countDown();
                check(await(releaseOpen,10),"test gate released writer");
            }
            return databaseName;
        }
        @Override public File getDatabasePath(String name){return getBaseContext().getDatabasePath(redirected(name));}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){
            return getBaseContext().openOrCreateDatabase(redirected(name),mode,factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler errorHandler){
            return getBaseContext().openOrCreateDatabase(redirected(name),mode,factory,errorHandler);
        }
        @Override public boolean deleteDatabase(String name){return getBaseContext().deleteDatabase(redirected(name));}
        @Override public void sendBroadcast(Intent intent){
            check(getPackageName().equals(intent.getPackage()),"writer update is package scoped");
            if(Store.FEELINGS_CHANGED.equals(intent.getAction()))feelingUpdates.incrementAndGet();
            else if(BedtimeVoiceService.UPDATE.equals(intent.getAction()))voiceUpdates.incrementAndGet();
            else throw new AssertionError("Unexpected writer broadcast: "+intent.getAction());
        }
    }

    private static boolean await(CountDownLatch latch,int seconds){
        try{return latch.await(seconds,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError("Interrupted while awaiting writer",e);}
    }

    private static void asynchronousWriter(Instrumentation test){
        check(!Looper.getMainLooper().isCurrentThread(),"writer test waits on instrumentation thread");
        WriterContext context=new WriterContext(test.getTargetContext());
        String previousResult=RecordingVoiceController.lastResult;
        try{
            check(context.getDatabasePath("breeze.db").getName().equals(context.databaseName),"literal production database resolves only to unique task temporary file");
            BedtimeVoiceCommand warm=BedtimeVoiceCommand.parse("Breeze warm two");
            BedtimeVoiceCommand cold=BedtimeVoiceCommand.parse("hey Breeze log cold level ten");
            check(warm.kind==BedtimeVoiceCommand.Kind.FEELING&&cold.kind==BedtimeVoiceCommand.Kind.FEELING,"spoken commands accepted before writer");
            String token=UUID.randomUUID().toString(),zone=ZoneId.systemDefault().getId();
            long capturedAt=System.currentTimeMillis();
            context.blockFirstOpen.set(true);
            saveViaWriter(test,context,token,capturedAt,zone,warm,1,true);
            saveViaWriter(test,context,UUID.randomUUID().toString(),System.currentTimeMillis(),zone,cold,2,false);
            saveViaWriter(test,context,token,capturedAt,zone,warm,2,false);
            saveViaWriter(test,context,UUID.randomUUID().toString(),capturedAt,zone,warm,3,false);
            try(Store reopened=new Store(context)){
                check(reopened.allFeelings().size()==3,"async writer close/reopen keeps deliberate repeats and deduplicates UUID retry");
                Store.FeelingEntry original=byToken(reopened,token);
                check(original.time==capturedAt&&original.state.equals("WARM")&&original.signedValue==2,"async persistence retains immutable captured timestamp and normalized result");
                check(OffsetDateTime.parse(original.localTimestamp).toInstant().toEpochMilli()==capturedAt,"async persistence retains full captured local time");
            }
            check(context.databaseAccesses.get()>1,"production writer used guarded database context");
            check(context.feelingUpdates.get()==3&&context.voiceUpdates.get()==4,"only committed new rows update graph while every writer completion updates status");
        }finally{
            context.releaseOpen.countDown();
            boolean finished=await(context.callbackFinished,10);
            if(finished){
                test.waitForIdleSync();
                test.runOnMainSync(()->RecordingVoiceController.lastResult=previousResult);
                // Only this task-created file is removed, and only after the writer has closed it.
                context.deleteDatabase(context.databaseName);
            }
        }
    }

    private static void saveViaWriter(Instrumentation test,WriterContext context,String token,long capturedAt,
            String zone,BedtimeVoiceCommand command,int expectedRows,boolean delayed){
        CountDownLatch finished=new CountDownLatch(1);context.callbackFinished=finished;
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->RecordingVoiceController.saveFeeling(context,token,capturedAt,zone,
                new RecordingVoiceController.RecordingContext(null,-1),command.state,command.intensity,result->{
                    try{
                        check(Looper.getMainLooper().isCurrentThread(),"writer completion delivered on main thread");
                        check(result.startsWith("Saved "),"writer reported committed save: "+result);
                        // A new helper opened during the callback must already see the committed row.
                        try(Store observed=new Store(context)){
                            check(observed.allFeelings().size()==expectedRows,"callback observes committed row count");
                            Store.FeelingEntry entry=byToken(observed,token);
                            check(entry.time==capturedAt&&entry.intensity==command.intensity&&entry.state.equals(command.state),"callback observes immutable accepted payload");
                        }
                    }catch(Throwable error){failure.set(error);}finally{finished.countDown();}
                }));
        if(delayed){
            check(await(context.openReached,10),"production worker reached guarded database open");
            SystemClock.sleep(40);
            check(System.currentTimeMillis()>capturedAt,"writer gate separated acceptance time from storage time");
            context.releaseOpen.countDown();
        }
        check(await(finished,10),"production writer completed accepted command");
        test.waitForIdleSync();
        if(failure.get()!=null)throw new AssertionError("Production writer callback failed",failure.get());
    }

    private static void migration(Context base){
        TestContext context=new TestContext(base);String name="temporary-voice-v3-"+System.nanoTime()+".db";
        SQLiteDatabase original=context.openOrCreateDatabase(name,0,null);Store store=null;
        try{
            original.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
            original.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, second_probe REAL, phase INTEGER NOT NULL DEFAULT 0, UNIQUE(device,record))");
            original.execSQL("CREATE INDEX sample_time ON samples(session,time)");
            original.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
            original.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
            original.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY, session INTEGER NOT NULL REFERENCES sessions(id), time INTEGER NOT NULL CHECK(time>0), kind TEXT NOT NULL CHECK(kind IN ('Hot','Cold')), intensity INTEGER NOT NULL CHECK(intensity BETWEEN 1 AND 10))");
            original.execSQL("CREATE INDEX feeling_time ON feelings(session,time,id)");
            original.execSQL("INSERT INTO sessions VALUES (1,1800000000000,1800000030000,'Original night','Original config','old:91',0)");
            original.execSQL("INSERT INTO sessions VALUES (2,1800086400000,NULL,'Other night','Other config','other:91',0)");
            original.execSQL("INSERT INTO samples (id,session,time,probe,room,humidity,uptime,fault,device,record,second_probe,phase) VALUES (9,1,1800000000000,32.5,23,55,12,0,'old:91',0,31.75,2)");
            original.execSQL("INSERT INTO weather VALUES (1,1800000000000,18,70,'Milwaukee')");
            original.execSQL("INSERT INTO cursors VALUES ('old:91',1)");
            original.execSQL("INSERT INTO feelings VALUES (7,1,1800000015000,'Hot',10)");
            original.execSQL("INSERT INTO feelings VALUES (12,2,1800086415000,'Cold',1)");
            original.setVersion(3);original.close();

            store=new Store(context,name);context.observed=store;
            check(store.getReadableDatabase().getVersion()==4,"v3 upgrades to v4");
            check(store.sessions().size()==2&&store.sessions().get(1).label.equals("Original night"),"all nights retained");
            check(store.points(1).size()==1&&store.points(1).get(0).secondProbe==31.75&&store.points(1).get(0).phase==2,"samples and phase retained");
            check(store.weather(1).size()==1&&store.weatherLocation(1).equals("Milwaukee"),"weather retained");
            check(store.cursor("old:91")==1,"cursor retained");
            List<Store.FeelingEntry> entries=store.allFeelings();check(entries.size()==2,"both feelings retained");
            Store.FeelingEntry hot=entries.get(0),cold=entries.get(1);
            check(hot.id==7&&hot.session==1&&hot.time==BASE+15000&&hot.kind.equals("Hot")&&hot.state.equals("WARM")&&hot.signedValue==10,"legacy Hot canonical mapping and identity");
            check(cold.id==12&&cold.session==2&&cold.kind.equals("Cold")&&cold.state.equals("COLD")&&cold.signedValue==-1,"legacy Cold canonical mapping and identity");
            check(hot.localTimeDerived&&cold.localTimeDerived,"legacy original timezone remains explicitly unknown");
            check(OffsetDateTime.parse(hot.localTimestamp).toInstant().toEpochMilli()==hot.time,"derived legacy timestamp retains full instant");
            String sensorCsv=store.csv(1),originalTimestamp=hot.localTimestamp,originalZone=hot.zoneId;
            store.updateFeeling(7,1,hot.time,"Cold",4);
            hot=store.feelings(1).get(0);
            check(hot.state.equals("COLD")&&hot.signedValue==-4,"manual edit recomputes canonical sign");
            check(hot.localTimestamp.equals(originalTimestamp)&&hot.zoneId.equals(originalZone)&&hot.localTimeDerived,"intensity edit preserves timestamp metadata");
            store.updateFeeling(7,1,BASE+17000,"Hot",2);
            check(!store.feelings(1).get(0).localTimeDerived,"date/time edit records current zone metadata");
            Store testing=store;
            constrained(()->testing.getWritableDatabase().execSQL("UPDATE feelings SET signed_value=-2 WHERE id=7"),"database accepted wrong sign for WARM");
            constrained(()->testing.getWritableDatabase().execSQL("UPDATE feelings SET state='COLD' WHERE id=7"),"database accepted state inconsistent with kind and sign");
            constrained(()->testing.getWritableDatabase().execSQL("UPDATE feelings SET intensity=11,signed_value=11 WHERE id=7"),"database accepted invalid canonical intensity");
            check(store.csv(1).equals(sensorCsv),"feeling edits leave sensor export intact");
            String feelingCsv=store.feelingsCsv(1);
            check(feelingCsv.startsWith("source,time_iso_utc,feeling,intensity_1_to_10,state,signed_value,time_local,zone_id,local_time_derived\n"),"feeling export preserves original columns and adds canonical timestamp metadata");
            check(feelingCsv.contains(",Hot,2,WARM,2,")&&feelingCsv.contains(store.feelings(1).get(0).localTimestamp),"feeling export contains canonical sign and full local timestamp");
            store.close();store=new Store(context,name);context.observed=store;
            check(store.allFeelings().size()==2&&store.feelings(1).get(0).signedValue==2,"migration and edits survive reopen");
        }finally{if(original.isOpen())original.close();if(store!=null)store.close();context.deleteDatabase(name);}
    }

    private static void currentTimeAndValidation(Context base){
        TestContext context=new TestContext(base);String name="temporary-voice-now-"+System.nanoTime()+".db";Store store=new Store(context,name);context.observed=store;
        try{
            for(String state:new String[]{"warm","CoLd"})for(int value:new int[]{1,10}){
                long before=System.currentTimeMillis();Store.FeelingEntry entry=store.logTemperatureFeeling(state,value);long after=System.currentTimeMillis();
                check(entry.time>=before&&entry.time<=after,"timestamp is immediate device wall clock");
                check(entry.state.equals(state.equals("warm")?"WARM":"COLD"),"state normalized case insensitively");
                check(entry.signedValue==(state.equals("warm")?value:-value),"signed boundaries correct");
                check(entry.intensity==value&&entry.isPending&&entry.session<=0,"standalone entry persists without any recorded night");
                check(!entry.localTimeDerived&&entry.zoneId.equals(ZoneId.systemDefault().getId()),"capture preserves current device timezone");
                OffsetDateTime captured=OffsetDateTime.parse(entry.localTimestamp);
                check(captured.toInstant().toEpochMilli()==entry.time,"full milliseconds and offset retain exact timestamp");
                check(captured.getOffset().equals(Instant.ofEpochMilli(entry.time).atZone(ZoneId.of(entry.zoneId)).getOffset()),"local UTC offset matches capture zone");
            }
            check(store.sessions().isEmpty()&&store.allFeelings().size()==4,"logging creates no fake controller night");
            int committed=context.notifications;
            Store testing=store;
            for(String state:new String[]{null,"","HOT","hot","comfortable"})rejected(()->testing.logTemperatureFeeling(state,5),"invalid state accepted");
            for(int value:new int[]{0,11,-4})for(String state:new String[]{"WARM","COLD"})rejected(()->testing.logTemperatureFeeling(state,value),"invalid intensity accepted");
            rejected(()->testing.logTemperatureFeeling("bad-time",0,null,-1,"WARM",5,"UTC"),"invalid capture time accepted");
            rejected(()->testing.logTemperatureFeeling("bad-zone",BASE,null,-1,"WARM",5,"Not/AZone"),"invalid zone accepted");
            rejected(()->testing.logTemperatureFeeling("missing-archive",BASE,null,0,"WARM",5,"UTC"),"orphan anchor accepted");
            check(store.allFeelings().size()==4&&context.notifications==committed,"invalid entries never persist or notify");
            Store.FeelingEntry specificZone=store.logTemperatureFeeling("capture-zone",BASE+123,null,-1,"COLD",3,"Pacific/Honolulu");
            check(specificZone.localTimestamp.endsWith("-10:00")&&specificZone.zoneId.equals("Pacific/Honolulu"),"captured original zone is independent of writer's current zone");
            store.close();store=new Store(context,name);context.observed=store;
            check(store.allFeelings().size()==5&&store.unassignedFeelings().size()==5,"all standalone entries survive process-style reopen");
            check(byToken(store,"capture-zone").localTimestamp.equals(specificZone.localTimestamp),"saved original local timestamp survives reopen");
        }finally{store.close();context.deleteDatabase(name);}
    }

    private static void delayedBindingAndRecovery(Context base){
        TestContext context=new TestContext(base);String name="temporary-voice-binding-"+System.nanoTime()+".db";Store store=new Store(context,name);context.observed=store;
        try{
            String archive="AA:BB:CC:DD:EE:FF:71",zone="America/Chicago";
            Store.FeelingEntry waiting=store.logTemperatureFeeling("utterance-a",BASE+15000,archive,1,"WARM",7,zone);
            check(waiting.isPending,"voice persists before download exists");
            int saveNotifications=context.notifications;
            store.close();store=new Store(context,name);context.observed=store;
            check(store.unassignedFeelings().size()==1,"pending binding survives reopen");
            long first=store.ingest(archive,record(0,64,0));
            store.ingest(archive,record(1,0,10));
            check(store.unassignedFeelings().size()==1,"anchor alone cannot assign beyond downloaded interval");
            check(context.notifications==saveNotifications,"unrelated sensor ingest does not broadcast feelings");
            store.ingest(archive,record(2,0,20));
            check(store.unassignedFeelings().isEmpty()&&store.feelings(first).get(0).id==waiting.id,"late covering sample binds exact anchor's session");
            check(context.notifications==saveNotifications+1,"binding broadcasts once after sensor transaction commits");

            Store.FeelingEntry duplicate=store.logTemperatureFeeling("utterance-a",BASE+15000,archive,1,"warm",7,zone);
            check(duplicate.id==waiting.id&&store.allFeelings().size()==1,"same utterance retry is idempotent after assignment");
            check(context.notifications==saveNotifications+1,"duplicate retry sends no extra changed event");
            Store.FeelingEntry deliberateRepeat=store.logTemperatureFeeling("utterance-b",BASE+15000,archive,1,"WARM",7,zone);
            check(deliberateRepeat.id!=waiting.id&&store.feelings(first).size()==2,"identical deliberate repeat with distinct UUID is retained");
            Store testing=store;
            rejected(()->testing.logTemperatureFeeling("utterance-a",BASE+15000,archive,1,"COLD",7,zone),"reused token silently changed payload");
            check(store.feelings(first).size()==2,"conflicting token leaves original data intact");

            Store.FeelingEntry offline=store.logTemperatureFeeling("offline",BASE+25000,archive,2,"COLD",4,zone);
            check(offline.isPending,"disconnected entry remains durable until time span is known");
            store.ingest(archive,record(3,128,30));
            check(store.feelings(first).size()==3&&store.unassignedFeelings().isEmpty(),"downloaded END resolves offline entry in correct recording");

            Store.FeelingEntry beyondEnd=store.logTemperatureFeeling("past-end",BASE+35000,archive,2,"WARM",2,zone);
            check(beyondEnd.isPending,"stale active context cannot attach beyond actual END");
            Store.FeelingEntry nextVoice=store.logTemperatureFeeling("next-night",BASE+65000,archive,4,"COLD",6,zone);
            check(nextVoice.isPending,"next recording entry queues while prior night already exists");
            long second=store.ingest(archive,record(4,64,60));
            check(second!=first&&store.feelings(second).isEmpty(),"new START does not attach older pending voice by newest-night guessing");
            store.ingest(archive,record(5,0,70));
            check(store.feelings(second).size()==1&&store.feelings(second).get(0).id==nextVoice.id,"next voice follows its own anchor");
            check(store.feelings(first).size()==3,"new downloads do not move previous feelings");

            Store.FeelingEntry wrongArchive=store.logTemperatureFeeling("wrong-archive",BASE+65000,"AA:BB:CC:DD:EE:FF:72",4,"WARM",1,zone);
            Store.FeelingEntry unknown=store.logTemperatureFeeling("no-identity",BASE+65000,null,-1,"COLD",1,zone);
            check(wrongArchive.isPending&&unknown.isPending,"time match never substitutes for exact archive identity");
            store.ingest(archive,record(6,128,80));
            check(store.unassignedFeelings().size()==3,"unresolved entries remain available for deliberate recovery");
            Store assigning=store;
            rejected(()->assigning.assignFeeling(unknown.id,9999),"missing recovery night accepted");
            store.assignFeeling(unknown.id,second);
            check(store.feelings(second).size()==2&&store.unassignedFeelings().size()==2,"explicit assignment recovers an entry without changing its time");
            rejected(()->assigning.assignFeeling(unknown.id,first),"already assigned entry silently moved");
            store.close();store=new Store(context,name);context.observed=store;
            check(store.allFeelings().size()==7&&store.unassignedFeelings().size()==2,"all assigned and pending entries survive reopen");
            check(store.points(first).size()==4&&store.points(second).size()==3&&store.cursor(archive)==7,"voice logging preserves controller samples and cursor");
        }finally{store.close();context.deleteDatabase(name);}
    }

    private static byte[] record(int index,int flags,int seconds){
        ByteBuffer buffer=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte)6).put((byte)(8|16|32|2|flags)).putInt(index).putInt((int)(BASE/1000)+seconds)
                .putShort((short)3050).putShort((short)2250).putShort((short)6000).putShort((short)2400).put((byte)128).put((byte)0);
        byte[] bytes=buffer.array();bytes[19]=(byte)Protocol.crc8(bytes,19);return bytes;
    }
}
