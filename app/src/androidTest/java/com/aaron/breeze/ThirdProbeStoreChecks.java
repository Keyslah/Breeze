package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Isolated databases only; never changes the user's saved nights or controller. */
public final class ThirdProbeStoreChecks {
    private static final int EPOCH=1800000000;
    private static final String OLD_CSV_HEADER="source,time_iso_utc,body_temperature_c,room_temperature_c,humidity_percent,controller_uptime_s,fault,location,second_body_temperature_c,test_phase";
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static byte[] crc(byte[] data){data[19]=(byte)Protocol.crc8(data,19);return data;}
    private static byte[] base(int type,int index,int event,int seconds){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte)type).put((byte)(8|16|32|event|(type==6?2:0))).putInt(index).putInt(EPOCH+seconds);
        packet.putShort((short)3050).putShort((short)2250).putShort((short)6000);
        if(type==6)packet.putShort((short)3150).put((byte)128);else packet.put((byte)2).put((byte)90).put((byte)0);
        return crc(packet.array());
    }
    private static byte[] third(int index,int parent,int seconds,boolean valid,int hundredths){
        ByteBuffer packet=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte)10).put((byte)(valid?8:0)).putInt(index).putInt(EPOCH+seconds).putShort((short)hundredths).putInt(parent);
        return crc(packet.array());
    }
    private static void rejected(Store store,String device,byte[] record,String message){
        long cursor=store.cursor(device);
        try{store.ingest(device,record);throw new AssertionError(message);}catch(IllegalArgumentException expected){}
        check(store.cursor(device)==cursor,"Rejected companion must not advance the durable cursor");
    }
    private static int count(SQLiteDatabase db,String table){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){check(c.moveToFirst(),"Count is available");return c.getInt(0);}}
    private static long companion(Store store,String device,long record){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT third_probe_record FROM samples WHERE device=? AND record=?",new String[]{device,""+record})){
            check(c.moveToFirst(),"Parent sample exists");return c.isNull(0)?-1:c.getLong(0);
        }
    }

    private static void migration(Context context){
        String name="temporary-third-probe-v4-"+System.nanoTime()+".db";
        SQLiteDatabase db=context.openOrCreateDatabase(name,0,null);Store migrated=null;
        try{
            db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
            db.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, second_probe REAL, phase INTEGER NOT NULL DEFAULT 0, UNIQUE(device,record))");
            db.execSQL("CREATE INDEX sample_time ON samples(session,time)");
            db.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
            db.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY, session INTEGER REFERENCES sessions(id), time INTEGER NOT NULL CHECK(time>0), kind TEXT NOT NULL CHECK(kind IN ('Hot','Cold')), intensity INTEGER NOT NULL CHECK(intensity BETWEEN 1 AND 10), state TEXT NOT NULL CHECK(state IN ('WARM','COLD')), signed_value INTEGER NOT NULL CHECK(signed_value=CASE state WHEN 'WARM' THEN intensity ELSE -intensity END), local_timestamp TEXT NOT NULL, zone_id TEXT NOT NULL, local_time_derived INTEGER NOT NULL DEFAULT 0, event_token TEXT UNIQUE, controller_device TEXT, anchor_record INTEGER, CHECK(kind=CASE state WHEN 'WARM' THEN 'Hot' ELSE 'Cold' END))");
            db.execSQL("CREATE INDEX feeling_time ON feelings(session,time,id)");
            db.execSQL("CREATE INDEX feeling_pending ON feelings(controller_device,anchor_record) WHERE session IS NULL");
            db.execSQL("INSERT INTO sessions VALUES (7,1800000000000,1800000010000,'Original night','Original configuration','old-controller',10)");
            db.execSQL("INSERT INTO samples (id,session,time,probe,room,humidity,speed,mode,uptime,fault,device,record,second_probe,phase) VALUES (9,7,1800000000000,30.5,22.5,60,2,1,123,4,'old-controller',10,31.5,2)");
            db.execSQL("INSERT INTO weather VALUES (7,1800000000000,16.5,70,'Milwaukee')");
            db.execSQL("INSERT INTO cursors VALUES ('old-controller',11)");
            db.execSQL("INSERT INTO feelings VALUES (5,7,1800000000000,'Cold',7,'COLD',-7,'2027-01-15T02:00:00-06:00','America/Chicago',0,'original-token','old-controller',10)");
            db.setVersion(4);db.close();
            migrated=new Store(context,name);check(migrated.getReadableDatabase().getVersion()==6,"Version 4 upgrades to version 6");
            List<Store.Session> sessions=migrated.sessions();check(sessions.size()==1&&sessions.get(0).id==7&&sessions.get(0).count==1&&sessions.get(0).label.equals("Original night")&&sessions.get(0).end==1800000010000L,"Migration preserves the existing night and sample count");
            Store.Point point=migrated.points(7).get(0);check(point.probe==30.5&&point.room==22.5&&point.humidity==60&&point.secondProbe==31.5&&point.phase==2&&Double.isNaN(point.thirdProbe),"Migration preserves every old temperature and leaves D2 missing");
            check(migrated.cursor("old-controller")==11&&migrated.weather(7).get(0).probe==16.5&&migrated.weatherLocation(7).equals("Milwaukee"),"Migration preserves download cursor and weather");
            check(companion(migrated,"old-controller",10)==-1,"Migration does not invent a companion identity for old samples");
            Store.FeelingEntry feeling=migrated.feelings(7).get(0);check(feeling.id==5&&feeling.signedValue==-7&&feeling.eventToken.equals("original-token")&&feeling.localTimestamp.equals("2027-01-15T02:00:00-06:00")&&feeling.zoneId.equals("America/Chicago"),"Migration preserves feeling identity, value, and captured local time");
            String[] row=migrated.csv(7).split("\n")[1].split(",",-1);check(row.length==12&&row[5].equals("123")&&row[6].equals("4")&&row[10].isEmpty(),"Migrated CSV retains original uptime and fault with a blank D2 cell");
        }finally{if(db.isOpen())db.close();if(migrated!=null)migrated.close();context.deleteDatabase(name);}
    }

    private static void pairedDownloadAndCsv(Context context){
        String name="temporary-third-probe-download-"+System.nanoTime()+".db";Store store=new Store(context,name);String device="paired-controller";
        try{
            long legacy=store.ingest(device,base(2,0,64,0));check(Double.isNaN(store.points(legacy).get(0).thirdProbe),"Legacy type 2 never invents D2");
            long session=store.ingest(device,base(6,1,64,10));check(Double.isNaN(store.points(session).get(0).thirdProbe),"A base record alone has missing D2");
            check(store.ingest(device,third(2,1,10,true,3467))==session,"Companion belongs to the existing parent night");
            Store.Point point=store.points(session).get(0);check(point.thirdProbe==34.67&&point.probe==30.5&&point.secondProbe==31.5&&point.room==22.5&&point.phase==2,"Companion adds only D2 to the original sample");
            check(store.cursor(device)==3&&store.sessions().size()==2&&store.points(session).size()==1,"Companion advances its own cursor without adding a night or sample");
            check(store.ingest(device,third(2,1,10,true,3999))==-1&&store.points(session).get(0).thirdProbe==34.67,"Duplicate companion cannot overwrite committed D2");
            check(store.ingest(device,third(4,3,20,true,3525))==-1&&store.cursor(device)==3,"Gap cannot skip the missing parent");
            store.ingest(device,base(6,3,0,20));store.close();store=new Store(context,name);
            check(store.cursor(device)==4&&store.points(session).size()==2&&Double.isNaN(store.points(session).get(1).thirdProbe),"Restart between base and companion preserves the exact resume point");
            check(store.ingest(device,third(4,3,20,true,3525))==session,"Resumed companion finds the durable parent");
            store.ingest(device,base(6,5,128,30));store.ingest(device,third(6,5,30,false,3999));
            check(Double.isNaN(store.points(session).get(2).thirdProbe)&&companion(store,device,5)==6,"Invalid D2 stays null while its verified companion identity is retained");
            check(store.cursor(device)==7&&store.points(session).size()==3&&store.sessions().get(0).end==1800000030000L,"End companion preserves the parent end marker and sample count");
            store.weather(session,1800000010000L,16.5,70,"Milwaukee");String[] rows=store.csv(session).split("\n");
            check(rows[0].equals(OLD_CSV_HEADER+",third_body_temperature_c,fan_setting"),"CSV appends D2 after the unchanged first ten columns");
            for(String row:rows)check(row.split(",",-1).length==12,"Every sensor and outdoor CSV row has twelve fields");
            String[] first=rows[1].split(",",-1),missing=rows[3].split(",",-1),outdoor=rows[4].split(",",-1);
            check(Double.parseDouble(first[2])==30.5&&Double.parseDouble(first[3])==22.5&&Double.parseDouble(first[4])==60&&Double.parseDouble(first[8])==31.5&&first[9].equals("Lying on probes")&&Double.parseDouble(first[10])==34.67,"CSV preserves D0, room, humidity, D1, and phase positions");
            check(missing[10].isEmpty()&&outdoor[0].equals("outdoor_model")&&outdoor[10].isEmpty(),"Missing D2 and outdoor D2 export as empty cells");
            store.close();store=new Store(context,name);check(store.cursor(device)==7&&store.points(session).get(1).thirdProbe==35.25,"D2 and companion cursor remain durable after reopening");
            long local=store.start("local sample");Protocol.Reading reading=new Protocol.Reading();reading.time=1800000100000L;reading.probe=30;reading.room=22;reading.humidity=50;reading.thirdProbe=36.75;store.sample(local,reading);
            check(store.points(local).get(0).thirdProbe==36.75,"Local samples copy a supplied third probe");
            Protocol.Reading absent=new Protocol.Reading();absent.time=1800000101000L;store.sample(local,absent);check(Double.isNaN(store.points(local).get(1).thirdProbe),"A reading without D2 stays missing instead of becoming zero");
        }finally{store.close();context.deleteDatabase(name);}
    }

    private static void invalidParentsAndAtomicCursor(Context context){
        String name="temporary-third-probe-invalid-"+System.nanoTime()+".db";Store store=new Store(context,name);
        try{
            store.setCursor("missing-parent",1);rejected(store,"missing-parent",third(1,0,0,true,3400),"Missing parent was accepted");
            check(store.sessions().isEmpty()&&count(store.getReadableDatabase(),"samples")==0,"Rejected companion creates no sample or session");
            long session=store.ingest("controller",base(6,0,64,0));
            store.setCursor("other-controller",1);rejected(store,"other-controller",third(1,0,0,true,3400),"A different controller's parent was accepted");
            rejected(store,"controller",third(1,0,1,true,3400),"Mismatched parent timestamp was accepted");
            rejected(store,"controller",third(1,7,0,true,3400),"Nonpreceding parent was accepted");
            byte[] damaged=third(1,0,0,true,3400);damaged[10]^=1;rejected(store,"controller",damaged,"Companion with damaged CRC was accepted");
            check(store.cursor("controller")==1&&Double.isNaN(store.points(session).get(0).thirdProbe),"Rejected companions leave the base and its cursor intact");
            SQLiteDatabase db=store.getWritableDatabase();db.execSQL("CREATE TRIGGER reject_companion_cursor BEFORE INSERT ON cursors WHEN NEW.device='controller' AND NEW.next_record=2 BEGIN SELECT RAISE(ABORT,'injected cursor failure'); END");
            try{store.ingest("controller",third(1,0,0,true,3400));throw new AssertionError("Injected cursor failure was ignored");}catch(SQLiteException expected){}
            check(store.cursor("controller")==1&&Double.isNaN(store.points(session).get(0).thirdProbe)&&companion(store,"controller",0)==-1,"Failed cursor commit rolls back both D2 and companion identity atomically");
            db.execSQL("DROP TRIGGER reject_companion_cursor");check(store.ingest("controller",third(1,0,0,true,3400))==session,"A failed transaction can retry the same companion safely");
            check(store.cursor("controller")==2&&store.points(session).size()==1&&store.points(session).get(0).thirdProbe==34,"Only the successful transaction advances cursor and saves D2");
        }finally{store.close();context.deleteDatabase(name);}
    }

    private static void voiceAnchorsRequireVerifiedCompanion(Context context){
        String name="temporary-third-probe-feelings-"+System.nanoTime()+".db";Store store=new Store(context,name);String device="feelings-controller";
        try{
            long first=store.ingest(device,base(6,0,64,0));
            Store.FeelingEntry early=store.logTemperatureFeeling("before-first-companion",1800000000000L,device,1,"WARM",7,"America/Chicago");
            check(early.isPending,"A feeling anchored to an undownloaded companion remains pending");
            store.ingest(device,third(1,0,0,false,0));
            check(store.feelings(first).size()==1&&store.feelings(first).get(0).id==early.id,"Verified companion resolves the earlier feeling even when its D2 reading is missing");
            long second=store.ingest(device,base(6,2,64,10));
            Store.FeelingEntry waiting=store.logTemperatureFeeling("before-second-companion",1800000010000L,device,3,"COLD",4,"America/Chicago");
            Store.FeelingEntry wrongTime=store.logTemperatureFeeling("wrong-companion-time",1800000000000L,device,3,"WARM",5,"America/Chicago");
            Store.FeelingEntry wrongDevice=store.logTemperatureFeeling("wrong-companion-device",1800000010000L,"different-controller",3,"WARM",6,"America/Chicago");
            check(waiting.isPending&&wrongTime.isPending&&wrongDevice.isPending&&store.feelings(second).isEmpty(),"No adjacent-index alias is assumed before the companion is verified");
            Store.FeelingEntry late=store.logTemperatureFeeling("after-first-companion",1800000000000L,device,1,"COLD",2,"America/Chicago");
            check(!late.isPending&&late.session==first&&store.feelings(first).size()==2,"A previously verified companion resolves to its original night instead of the latest night");
            store.close();store=new Store(context,name);store.ingest(device,third(3,2,10,true,3400));
            check(store.feelings(second).size()==1&&store.feelings(second).get(0).id==waiting.id,"Companion identity resolves a waiting feeling after app restart");
            check(store.unassignedFeelings().size()==2,"Companion matching keeps device and timestamp safeguards");
            Store.FeelingEntry after=store.logTemperatureFeeling("after-second-companion",1800000010000L,device,3,"WARM",8,"America/Chicago");
            check(!after.isPending&&after.session==second&&store.points(first).size()==1&&store.points(second).size()==1,"Post-download voice anchors use the verified companion without extra samples");
        }finally{store.close();context.deleteDatabase(name);}
    }

    public static String run(Instrumentation test){
        Context context=test.getTargetContext();migration(context);pairedDownloadAndCsv(context);invalidParentsAndAtomicCursor(context);voiceAnchorsRequireVerifiedCompanion(context);
        return "PASS: additive v4-to-v6 migration retains nights, readings, feelings, weather, and cursors; D2 companion attaches to exact parent; legacy/missing D2 stays null; duplicate/gap/restart/CRC/parent checks; atomic D2, companion identity, and cursor commit; verified companion voice anchors before/after download; twelve-column CSV and local samples; isolated databases only\n";
    }
}
