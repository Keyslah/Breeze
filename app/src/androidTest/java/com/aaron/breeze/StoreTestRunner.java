package com.aaron.breeze;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import java.nio.*;
public class StoreTestRunner extends Instrumentation {
    private boolean controlsOnly,bleRecoveryOnly,historyZoomOnly,feelingEntriesOnly,bedtimeVoiceOnly,recordingVoiceOnly,voiceLoggingOnly,signedFeelingsOnly,listeningDisabledOnly,batteryPolicyOnly;
    @Override public void onCreate(Bundle args){super.onCreate(args);controlsOnly=args!=null&&args.getString("controls","").equals("true");bleRecoveryOnly=args!=null&&args.getString("bleRecovery","").equals("true");historyZoomOnly=args!=null&&args.getString("historyZoom","").equals("true");feelingEntriesOnly=args!=null&&args.getString("feelingEntries","").equals("true");bedtimeVoiceOnly=args!=null&&args.getString("bedtimeVoice","").equals("true");recordingVoiceOnly=args!=null&&args.getString("recordingVoice","").equals("true");voiceLoggingOnly=args!=null&&args.getString("voiceLogging","").equals("true");signedFeelingsOnly=args!=null&&args.getString("signedFeelings","").equals("true");listeningDisabledOnly=args!=null&&args.getString("listeningDisabled","").equals("true");batteryPolicyOnly=args!=null&&args.getString("batteryPolicy","").equals("true");start();}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private byte[] record(int index,int event,int seconds){ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)2).put((byte)(8|16|32|event)).putInt(index).putInt(1800000000+seconds).putShort((short)3050).putShort((short)2250).putShort((short)6000).put((byte)2).put((byte)90).put((byte)0).put((byte)0);byte[] result=b.array();result[19]=(byte)Protocol.crc8(result,19);return result;}
    private void migration(Context c){
        String name="temporary-v1-migration-"+System.nanoTime()+".db";
        android.database.sqlite.SQLiteDatabase d=c.openOrCreateDatabase(name,0,null);
        Store migrated=null;
        try {
        d.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
        d.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, UNIQUE(device,record))");
        d.execSQL("CREATE INDEX sample_time ON samples(session,time)");
        d.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
        d.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
            d.execSQL("INSERT INTO sessions (id,start,name,config,device,first_record) VALUES (1,1800000000000,'Existing session','Existing config','old-board',0)");
            d.execSQL("INSERT INTO samples (session,time,probe,room,humidity,device,record) VALUES (1,1800000000000,32.5,23,55,'old-board',0)");
            d.execSQL("INSERT INTO cursors VALUES ('old-board',1)");d.setVersion(1);d.close();
            migrated=new Store(c,name);
            check(migrated.cursor("old-board")==1,"migration preserves cursor");
            check(migrated.sessions().size()==1,"migration preserves session");
            Store.Point point=migrated.points(1).get(0);
            check(point.probe==32.5&&Double.isNaN(point.secondProbe)&&point.phase==0,"migration preserves sample with missing D1");
        } finally {if(d.isOpen())d.close();if(migrated!=null)migrated.close();c.deleteDatabase(name);}
    }
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();String name="temporary-transfer-test-"+System.nanoTime()+".db";Store s=null;try{
        if(listeningDisabledOnly){String report=ListeningDisabledChecks.run(this);result.putString("stream",report);finish(report.startsWith("SKIPPED")?0:-1,result);return;}
        if(batteryPolicyOnly){String report=BatteryPolicyChecks.run(this);result.putString("stream",report);finish(report.startsWith("SKIPPED")?0:-1,result);return;}
        if(voiceLoggingOnly){result.putString("stream",VoiceFeelingChecks.run(this));finish(-1,result);return;}
        if(signedFeelingsOnly){result.putString("stream",SignedFeelingGraphChecks.run(this));finish(-1,result);return;}
        if(recordingVoiceOnly){String report=RecordingVoiceChecks.run(this);result.putString("stream",report);finish(report.startsWith("SKIPPED")?0:-1,result);return;}
        if(bedtimeVoiceOnly){result.putString("stream",BedtimeVoiceChecks.run(this));finish(-1,result);return;}
        if(feelingEntriesOnly){FeelingEntryChecks.run(this);result.putString("stream","PASS: Hot/Cold time and 1-10 entries, night isolation, editing, persistence, v2 migration preserves history, validation and sensor CSV compatibility\n");finish(-1,result);return;}
        if(historyZoomOnly){HistoryZoomChecks.run(this);result.putString("stream","PASS: pinch zoom, horizontal pan, reset, same-night refresh preservation, changed-night reset, vertical scrolling and feeling markers\n");finish(-1,result);return;}
        if(bleRecoveryOnly){BleRecoveryChecks.run(this);result.putString("stream","PASS: transient busy retry, expired binder recovery, precise rejection status, accepted-write failure and timeout without replay, ACK/callback ordering, serialized follow-up commands, Bluetooth off/on, telemetry watchdog, stale timer suppression, durable storage recovery confirmation; fake transport only\n");finish(-1,result);return;}
        if(controlsOnly){if(c.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()){result.putString("stream","SKIPPED: unlock the phone before running visible UI checks\n");finish(0,result);return;}ControlsChecks.run(this);result.putString("stream","PASS: four raw readings, Fahrenheit, no sudden-drop rules, probe test markers, IR learning confirmations, manual command routing, old firmware gating\n");finish(-1,result);return;}
        migration(c);s=new Store(c,name);check(s.cursor("board")==0,"empty cursor");long id=s.ingest("board",record(0,64,0));check(id>0,"session creation");check(s.cursor("board")==1,"durable cursor advances");check(s.ingest("board",record(0,64,0))==-1,"duplicate ignored");check(s.ingest("board",record(2,0,20))==-1,"gap not acknowledged");check(s.cursor("board")==1,"gap does not advance cursor");
        s.close();s=new Store(c,name);check(s.cursor("board")==1,"cursor survives reopening");check(s.ingest("board",record(1,0,10))==id,"resume existing session");check(s.ingest("board",record(2,128,20))==id,"end same session");check(s.sessions().get(0).end==1800000020000L,"end timestamp exact");check(s.points(id).size()==3,"three unique samples");String[] csvRow=s.csv(id).split("\n")[1].split(",",-1);check(Double.parseDouble(csvRow[2])==30.5 && Double.parseDouble(csvRow[3])==22.5 && Double.parseDouble(csvRow[4])==60 && csvRow.length==10,"CSV values");byte[] bad=record(3,0,30);bad[10]^=1;try{s.ingest("board",bad);throw new AssertionError("corrupt record accepted");}catch(IllegalArgumentException expected){}check(s.cursor("board")==3,"corruption not acknowledged");
        byte[] dual=record(3,64,30);dual[0]=6;dual[1]|=2;ByteBuffer.wrap(dual).order(ByteOrder.LITTLE_ENDIAN).putShort(16,(short)2400);dual[18]=(byte)128;dual[19]=(byte)Protocol.crc8(dual,19);long dualId=s.ingest("board",dual);check(s.points(dualId).get(0).secondProbe==24,"D1 raw value stored");check(s.points(dualId).get(0).phase==2,"lying marker stored");String[] dualCsv=s.csv(dualId).split("\n")[1].split(",",-1);check(Double.parseDouble(dualCsv[8])==24&&dualCsv[9].equals("Lying on probes"),"D1 and marker exported");
        s.weather(id,1800000000000L,16.5,70,"Milwaukee");check(s.weather(id).size()==1,"outdoor persisted");check(s.csv(id).contains("outdoor_model"),"outdoor exported");
        java.util.List<Weather.Place> locations=Weather.search("53214");check(!locations.isEmpty(),"ZIP resolves");Store.Session ws=new Store.Session();ws.id=id;ws.start=System.currentTimeMillis()-12*3600000;ws.end=System.currentTimeMillis();int weatherCount=Weather.fetch(s,ws,locations.get(0));check(weatherCount>0,"live weather received");
        result.putString("stream","PASS: v1 migration, dual-probe records and markers, durable download, duplicate suppression, gap recovery, restart persistence, CRC, timestamps, session boundary, weather storage, CSV, live ZIP 53214 lookup and "+weatherCount+" hourly weather records\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}finally{if(s!=null)s.close();c.deleteDatabase(name);}}
}
