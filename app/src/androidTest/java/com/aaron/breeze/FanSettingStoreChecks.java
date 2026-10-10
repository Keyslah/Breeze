package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/** Only creates and removes uniquely named temporary databases. */
public final class FanSettingStoreChecks {
    private static final int EPOCH=1800000000;
    private static final String OLD_HEADER="source,time_iso_utc,body_temperature_c,room_temperature_c,humidity_percent,controller_uptime_s,fault,location,second_body_temperature_c,test_phase,third_body_temperature_c";
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static byte[] crc(byte[] packet){packet[19]=(byte)Protocol.crc8(packet,19);return packet;}
    private static byte[] base(int type,int index,int event,int seconds){
        ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)type).put((byte)(8|16|32|event|(type==6?2:0))).putInt(index).putInt(EPOCH+seconds);
        b.putShort((short)3050).putShort((short)2250).putShort((short)6000);
        if(type==6)b.putShort((short)3150).put((byte)128);else b.put((byte)2).put((byte)90).put((byte)0);
        return crc(b.array());
    }
    private static byte[] third(int index,int seconds,boolean valid){
        ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)10).put((byte)(valid?8:0)).putInt(index).putInt(EPOCH+seconds).putShort((short)(valid?3467:0)).putInt(index-1);
        return crc(b.array());
    }
    private static byte[] setting(int index,int seconds,int value){
        ByteBuffer b=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0,(byte)11).put(1,(byte)(value>=0?1:0)).putInt(2,index).putInt(6,EPOCH+seconds);
        b.put(10,(byte)Math.max(0,value)).putInt(12,index-2);return crc(b.array());
    }
    private static long fanRecord(Store store,String device,int record){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT fan_setting_record FROM samples WHERE device=? AND record=?",new String[]{device,""+record})){
            check(c.moveToFirst(),"Parent exists");return c.isNull(0)?-1:c.getLong(0);
        }
    }
    private static void rejected(Store store,String device,byte[] packet){
        long cursor=store.cursor(device);
        try{store.ingest(device,packet);throw new AssertionError("Invalid fan companion was accepted");}catch(IllegalArgumentException expected){}
        check(store.cursor(device)==cursor,"Rejected fan companion must not advance cursor");
    }
    private static void migration(Context context){
        String name="temporary-fan-setting-v5-"+System.nanoTime()+".db";
        SQLiteDatabase db=context.openOrCreateDatabase(name,0,null);Store store=null;
        try{
            db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
            db.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, second_probe REAL, phase INTEGER NOT NULL DEFAULT 0, third_probe REAL, third_probe_record INTEGER, UNIQUE(device,record))");
            db.execSQL("CREATE INDEX sample_time ON samples(session,time)");
            db.execSQL("CREATE INDEX sample_third_probe_record ON samples(device,third_probe_record)");
            db.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
            db.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY, session INTEGER REFERENCES sessions(id), time INTEGER NOT NULL CHECK(time>0), kind TEXT NOT NULL CHECK(kind IN ('Hot','Cold')), intensity INTEGER NOT NULL CHECK(intensity BETWEEN 1 AND 10), state TEXT NOT NULL CHECK(state IN ('WARM','COLD')), signed_value INTEGER NOT NULL CHECK(signed_value=CASE state WHEN 'WARM' THEN intensity ELSE -intensity END), local_timestamp TEXT NOT NULL, zone_id TEXT NOT NULL, local_time_derived INTEGER NOT NULL DEFAULT 0, event_token TEXT UNIQUE, controller_device TEXT, anchor_record INTEGER, CHECK(kind=CASE state WHEN 'WARM' THEN 'Hot' ELSE 'Cold' END))");
            db.execSQL("CREATE INDEX feeling_time ON feelings(session,time,id)");
            db.execSQL("CREATE INDEX feeling_pending ON feelings(controller_device,anchor_record) WHERE session IS NULL");
            db.execSQL("INSERT INTO sessions VALUES (7,1800000000000,1800000010000,'Saved night','Saved configuration','old-board',10)");
            db.execSQL("INSERT INTO samples VALUES (9,7,1800000000000,30.5,22.5,60,0,1,123,4,'old-board',10,31.5,2,34.67,11)");
            db.execSQL("INSERT INTO samples VALUES (10,7,1800000010000,31,23,61,3,1,133,0,'old-board',12,32,2,NULL,NULL)");
            db.execSQL("INSERT INTO cursors VALUES ('old-board',13)");
            db.execSQL("INSERT INTO weather VALUES (7,1800000000000,16.5,70,'Milwaukee')");
            db.execSQL("INSERT INTO feelings VALUES (5,7,1800000000000,'Cold',7,'COLD',-7,'2027-01-15T02:00:00-06:00','America/Chicago',0,'saved-token','old-board',11)");
            db.setVersion(5);db.close();store=new Store(context,name);
            check(store.getReadableDatabase().getVersion()==6,"Version5 upgrades additively to6");
            List<Store.Point> points=store.points(7);
            check(points.size()==2&&Double.isNaN(points.get(0).fanSetting)&&Double.isNaN(points.get(1).fanSetting),"Neither legacy speed0 nor legacy speed3 invents a fan setting");
            check(points.get(0).speed==0&&points.get(1).speed==3&&points.get(0).thirdProbe==34.67&&points.get(0).secondProbe==31.5&&points.get(0).phase==2,"All old readings and legacy speed fields survive");
            check(fanRecord(store,"old-board",10)==-1&&store.cursor("old-board")==13,"Migration preserves cursor without inventing fan companion identities");
            check(store.sessions().get(0).label.equals("Saved night")&&store.sessions().get(0).count==2&&store.weather(7).get(0).probe==16.5&&store.feelings(7).get(0).signedValue==-7,"Night, weather and voice annotation survive");
            String[] row=store.csv(7).split("\n")[1].split(",",-1);
            check(row.length==12&&row[5].equals("123")&&row[6].equals("4")&&Double.parseDouble(row[10])==34.67&&row[11].isEmpty(),"Migration preserves old CSV fields and appends a blank fan setting");
        }finally{if(db.isOpen())db.close();if(store!=null)store.close();context.deleteDatabase(name);}
    }
    private static void groupedDownloadAndCsv(Context context){
        String name="temporary-fan-setting-download-"+System.nanoTime()+".db";Store store=new Store(context,name);String device="fan-board";
        try{
            long old=store.ingest("legacy-board",base(2,0,64,0));check(Double.isNaN(store.points(old).get(0).fanSetting),"Legacy type2 stays unknown");
            long session=store.ingest(device,base(6,0,64,0));
            check(Double.isNaN(store.points(session).get(0).fanSetting),"A base record alone has no fan setting");
            store.ingest(device,third(1,0,true));String[] before=store.csv(session).split("\n")[1].split(",",-1);
            check(store.ingest(device,setting(2,0,0))==session,"Fan companion returns the parent session");
            Store.Point point=store.points(session).get(0);
            check(point.fanSetting==0&&point.thirdProbe==34.67&&point.probe==30.5&&point.secondProbe==31.5,"Known Off merges into the same temperature point");
            check(store.cursor(device)==3&&store.points(session).size()==1&&fanRecord(store,device,0)==2,"Three contiguous records create one point and cursor3");
            String[] after=store.csv(session).split("\n")[1].split(",",-1);
            check(Arrays.equals(Arrays.copyOf(before,11),Arrays.copyOf(after,11))&&after[11].equals("0"),"Adding fan setting leaves all original eleven CSV cells unchanged");
            check(store.ingest(device,setting(2,0,9))==-1&&store.points(session).get(0).fanSetting==0,"Duplicate does not overwrite committed Off");
            check(store.ingest(device,setting(5,10,9))==-1&&store.cursor(device)==3,"A gap does not skip unreceived temperatures");
            store.ingest(device,base(6,3,0,10));store.ingest(device,third(4,10,true));store.close();store=new Store(context,name);
            check(store.cursor(device)==5&&Double.isNaN(store.points(session).get(1).fanSetting),"Resume point survives restart before fan companion");
            check(store.ingest(device,setting(5,10,10))==session&&store.points(session).get(1).fanSetting==10,"Resumed H setting merges into durable parent");
            store.ingest(device,base(6,6,128,20));store.ingest(device,third(7,20,false));store.ingest(device,setting(8,20,-1));
            check(store.cursor(device)==9&&store.points(session).size()==3&&Double.isNaN(store.points(session).get(2).fanSetting)&&fanRecord(store,device,6)==8,"Unknown stays null while its verified record identity is durable");
            store.weather(session,1800000000000L,16.5,70,"Milwaukee");String[] rows=store.csv(session).split("\n");
            check(rows[0].equals(OLD_HEADER+",fan_setting"),"Fan setting is the appended twelfth column");
            for(String row:rows)check(row.split(",",-1).length==12,"Every CSV row has twelve fields");
            check(rows[2].split(",",-1)[11].equals("10")&&rows[3].split(",",-1)[11].isEmpty()&&rows[4].split(",",-1)[11].isEmpty(),"H exports as internal10; unknown and outdoor fan cells stay empty");
        }finally{store.close();context.deleteDatabase(name);}
    }
    private static void invalidRecordsAndRollback(Context context){
        String name="temporary-fan-setting-invalid-"+System.nanoTime()+".db";Store store=new Store(context,name);String device="fan-board";
        try{
            store.setCursor("missing",2);rejected(store,"missing",setting(2,0,9));check(store.sessions().isEmpty(),"Missing parent creates no night");
            long session=store.ingest(device,base(6,0,64,0));store.setCursor(device,2);rejected(store,device,setting(2,0,9));
            check(Double.isNaN(store.points(session).get(0).fanSetting),"Missing D2 companion prevents a fan attachment");
            store.setCursor(device,1);store.ingest(device,third(1,0,true));
            rejected(store,device,setting(2,0,11));
            rejected(store,device,setting(2,1,9));store.setCursor("other-board",2);rejected(store,"other-board",setting(2,0,9));
            byte[] damaged=setting(2,0,9);damaged[10]^=1;rejected(store,device,damaged);
            byte[] wrongParent=setting(2,0,9);ByteBuffer.wrap(wrongParent).order(ByteOrder.LITTLE_ENDIAN).putInt(12,1);rejected(store,device,crc(wrongParent));
            check(store.cursor(device)==2&&fanRecord(store,device,0)==-1&&store.points(session).size()==1,"Bad CRC, time, device and parent preserve cursor and sample");
            SQLiteDatabase db=store.getWritableDatabase();db.execSQL("CREATE TRIGGER reject_fan_cursor BEFORE INSERT ON cursors WHEN NEW.device='fan-board' AND NEW.next_record=3 BEGIN SELECT RAISE(ABORT,'injected fan cursor failure'); END");
            try{store.ingest(device,setting(2,0,9));throw new AssertionError("Cursor failure was ignored");}catch(SQLiteException expected){}
            check(store.cursor(device)==2&&Double.isNaN(store.points(session).get(0).fanSetting)&&fanRecord(store,device,0)==-1,"Cursor failure rolls back setting and companion identity together");
            db.execSQL("DROP TRIGGER reject_fan_cursor");check(store.ingest(device,setting(2,0,9))==session&&store.cursor(device)==3&&store.points(session).get(0).fanSetting==9,"Retry commits exactly once after rollback");
        }finally{store.close();context.deleteDatabase(name);}
    }
    private static void voiceAnchors(Context context){
        String name="temporary-fan-setting-voice-"+System.nanoTime()+".db";Store store=new Store(context,name);String device="voice-board";
        try{
            long first=store.ingest(device,base(6,0,64,0));store.ingest(device,third(1,0,true));
            Store.FeelingEntry early=store.logTemperatureFeeling("early-fan",1800000000000L,device,2,"WARM",7,"America/Chicago");
            check(early.isPending,"A type11 anchor cannot alias its base before verification");
            store.ingest(device,setting(2,0,-1));check(store.feelings(first).get(0).id==early.id,"Verified unknown setting resolves the waiting annotation");
            long second=store.ingest(device,base(6,3,64,10));store.ingest(device,third(4,10,true));
            Store.FeelingEntry waiting=store.logTemperatureFeeling("waiting-fan",1800000010000L,device,5,"COLD",4,"America/Chicago");
            Store.FeelingEntry wrongTime=store.logTemperatureFeeling("wrong-time-fan",1800000000000L,device,5,"WARM",5,"America/Chicago");
            Store.FeelingEntry wrongDevice=store.logTemperatureFeeling("wrong-device-fan",1800000010000L,"other-board",5,"WARM",6,"America/Chicago");
            check(waiting.isPending&&wrongTime.isPending&&wrongDevice.isPending,"Unverified fan anchors remain pending");
            Store.FeelingEntry late=store.logTemperatureFeeling("late-first-fan",1800000000000L,device,2,"COLD",2,"America/Chicago");
            check(!late.isPending&&late.session==first,"A verified type11 anchor selects the original night, not the latest");
            store.close();store=new Store(context,name);store.ingest(device,setting(5,10,9));
            check(store.feelings(second).size()==1&&store.feelings(second).get(0).id==waiting.id&&store.unassignedFeelings().size()==2,"Verified type11 resolves across restart with device and time safeguards");
            Store.FeelingEntry after=store.logTemperatureFeeling("after-second-fan",1800000010000L,device,5,"WARM",8,"America/Chicago");
            check(!after.isPending&&after.session==second&&store.points(first).size()==1&&store.points(second).size()==1,"Post-download type11 anchor retains the exact parent session without extra samples");
        }finally{store.close();context.deleteDatabase(name);}
    }
    public static String run(Instrumentation test){
        Context context=test.getTargetContext();migration(context);groupedDownloadAndCsv(context);invalidRecordsAndRollback(context);voiceAnchors(context);
        return "PASS: additive v5-to-v6 fan-setting migration; legacy values stay unknown; type6/10/11 merge, Off/9/H/unknown, duplicate/gap/restart, exact parent and CRC validation, atomic cursor rollback, verified type11 voice anchors, twelve-column CSV; temporary databases only\n";
    }
}
