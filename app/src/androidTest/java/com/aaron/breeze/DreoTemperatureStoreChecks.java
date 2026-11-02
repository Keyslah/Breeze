package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.List;

/** Uses only a uniquely named disposable database; never touches the user's history/account. */
public final class DreoTemperatureStoreChecks {
    private static final long START=1793080800000L;
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void closeTo(double actual,double expected,String message){check(Math.abs(actual-expected)<0.000001,message);}
    public static String run(Instrumentation instrumentation){
        Context context=instrumentation.getTargetContext();String name="temporary-dreo-temperature-"+System.nanoTime()+".db";
        SQLiteDatabase legacy=context.openOrCreateDatabase(name,0,null);Store store=null,reader=null;
        try{
            legacy.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY,start INTEGER NOT NULL,end INTEGER,name TEXT NOT NULL,config TEXT NOT NULL,device TEXT,first_record INTEGER)");
            legacy.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY,session INTEGER NOT NULL,time INTEGER NOT NULL,probe REAL,room REAL,humidity REAL,speed INTEGER,mode INTEGER,uptime INTEGER,fault INTEGER,device TEXT,record INTEGER,second_probe REAL,phase INTEGER NOT NULL DEFAULT 0,third_probe REAL,third_probe_record INTEGER,fan_setting INTEGER,fan_setting_record INTEGER,UNIQUE(device,record))");
            legacy.execSQL("CREATE INDEX sample_time ON samples(session,time)");
            legacy.execSQL("CREATE INDEX sample_third_probe_record ON samples(device,third_probe_record)");
            legacy.execSQL("CREATE INDEX sample_fan_setting_record ON samples(device,fan_setting_record)");
            legacy.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
            legacy.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL,PRIMARY KEY(session,time))");
            legacy.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY,session INTEGER,time INTEGER,kind TEXT,intensity INTEGER,state TEXT,signed_value INTEGER,local_timestamp TEXT,zone_id TEXT,local_time_derived INTEGER,event_token TEXT,controller_device TEXT,anchor_record INTEGER)");
            legacy.execSQL("INSERT INTO sessions VALUES (1,"+START+","+(START+1_200_000)+",'Preserved night','Profile','board',10)");
            legacy.execSQL("INSERT INTO samples VALUES (1,1,"+(START+600_000)+",36,23,50,0,0,100,0,'board',10,35,0,34,11,4,12)");
            legacy.execSQL("INSERT INTO cursors VALUES ('board',13)");
            legacy.execSQL("INSERT INTO weather VALUES (1,"+START+",16,70,'Saved place')");
            legacy.execSQL("INSERT INTO feelings VALUES (1,1,"+(START+600_000)+",'Cold',5,'COLD',-5,'2026-10-27T01:10:00-05:00','America/Chicago',0,'saved-token','board',12)");
            legacy.setVersion(6);legacy.close();

            store=new Store(context,name);reader=new Store(context,name);
            check(store.getReadableDatabase().getVersion()==9,"Schema6 upgrades to9");
            check(store.sessions().size()==1&&store.sessions().get(0).count==1,"Migration preserves session and sample count");
            Store.Point sensor=store.points(1).get(0);
            check(sensor.probe==36&&sensor.secondProbe==35&&sensor.thirdProbe==34&&sensor.fanSetting==4,"Migration preserves all three probes and fan setting");
            check(store.cursor("board")==13&&store.weather(1).get(0).probe==16&&store.feelings(1).get(0).signedValue==-5,"Migration preserves cursor, weather and feelings");
            check(store.dreoTemperatures(1).isEmpty(),"Migration invents no historical Dreo readings");
            long revision=reader.revision();
            store.setCursor("another",7);check(reader.revision()==revision,"Cursor-only changes do not reload graph history");
            store.recordDreoTemperature("fan-a",START+1000,START-86_400_000,68.0,"F","run-a");
            check(reader.revision()>revision,"Separate Store instances share history revision");revision=reader.revision();
            store.recordDreoTemperature("fan-a",START+1000,START-86_400_000,99.0,"F","run-a");
            check(reader.revision()==revision,"Duplicate observation neither overwrites nor invalidates cached history");
            store.recordDreoTemperature("fan-a",START+181_000,START-86_400_000,69.0,"F","run-a");
            store.recordDreoTemperature("fan-a",START+361_000,START-86_400_000,70.0,"F","run-a",false);
            store.recordDreoTemperature("fan-a",START+541_000,START-86_400_000,71.0,"F","run-b");
            store.recordDreoTemperature("fan-b",START+721_000,START-86_400_000,72.0,"F","run-b");
            store.recordDreoTemperature("fan-a",START-1,START-86_400_000,73.0,"F","earlier");
            store.recordDreoTemperature("fan-a",START+1_200_001,START-86_400_000,74.0,"F","later");
            List<Store.Point> series=reader.dreoTemperatures(1);
            check(series.size()==5,"Readings stay inside the selected night's real bounds");
            check(series.get(0).time==START+1000&&series.get(1).time==START+181_000,"Receipt timestamps are retained; old value-change timestamps never backfill");
            closeTo(series.get(0).probe,20,"Verified Fahrenheit is converted to Celsius");
            closeTo(series.get(1).probe,(69-32)/1.8,"Repeated unchanged report timestamps still make separate observations");
            check(Double.isNaN(series.get(2).probe),"Unavailable observations are explicit graph gaps");
            check(!series.get(1).segment.equals(series.get(3).segment),"A restarted collection run cannot connect to its predecessor");
            check(!series.get(3).segment.equals(series.get(4).segment),"A changed Dreo device cannot connect to the previous fan");
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT raw_temperature,source_unit,temp_c FROM dreo_temperatures WHERE received_at=?",new String[]{""+(START+361_000)})){
                check(c.moveToFirst()&&c.getDouble(0)==70&&c.getString(1).equals("F")&&c.isNull(2),"Unusable responses preserve raw value and source unit without inventing a graph point");
            }
            revision=reader.revision();store.weather(1,START+1000,17,71,"Saved place");check(reader.revision()>revision,"Weather invalidates history cache");
            revision=reader.revision();store.addFeeling(1,START+2000,Store.FEELING_HOT,2);check(reader.revision()>revision,"Feeling inserts invalidate history cache");
            revision=reader.revision();store.updateFeeling(1,1,START+600_000,Store.FEELING_COLD,6);check(reader.revision()>revision,"Feeling edits invalidate history cache");
            store.close();store=new Store(context,name);
            check(store.dreoTemperatures(1).size()==5&&store.points(1).size()==1,"Cloud and controller records persist independently across helper restart");
            return "PASS: additive schema6-to8 migration preserves sensors, fan settings, weather, feelings and cursor; timestamped Dreo data, duplicate protection, unavailable/restart/device gaps, night bounds and cross-helper history revisions\n";
        }finally{
            if(legacy.isOpen())legacy.close();if(store!=null)store.close();if(reader!=null)reader.close();context.deleteDatabase(name);
        }
    }
}
