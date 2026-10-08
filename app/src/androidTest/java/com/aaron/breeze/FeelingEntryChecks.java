package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import java.util.List;

/** Uses only unique temporary databases; never opens the phone's breeze.db. */
public final class FeelingEntryChecks {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void rejected(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}

    public static void run(Instrumentation instrumentation){
        Context context=new ContextWrapper(instrumentation.getTargetContext()){
            @Override public Context getApplicationContext(){return this;}
            @Override public void sendBroadcast(Intent intent){/* Temporary entries must not wake the real app's database. */}
        };String name="temporary-feeling-v2-"+System.nanoTime()+".db";Store store=null;
        SQLiteDatabase original=context.openOrCreateDatabase(name,0,null);
        try{
            original.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
            original.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, second_probe REAL, phase INTEGER NOT NULL DEFAULT 0, UNIQUE(device,record))");
            original.execSQL("CREATE INDEX sample_time ON samples(session,time)");
            original.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
            original.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
            original.execSQL("INSERT INTO sessions (id,start,end,name,config,device,first_record) VALUES (1,1800000000000,1800000030000,'Existing night','Existing config','board',0)");
            original.execSQL("INSERT INTO sessions (id,start,end,name,config) VALUES (2,1800086400000,1800086430000,'Second night','Existing config')");
            original.execSQL("INSERT INTO samples (session,time,probe,room,humidity,uptime,fault,device,record,second_probe,phase) VALUES (1,1800000000000,32.5,23,55,12,0,'board',0,31.75,2)");
            original.execSQL("INSERT INTO weather (session,time,temp,humidity,location) VALUES (1,1800000000000,18,70,'Milwaukee')");original.execSQL("INSERT INTO cursors VALUES ('board',1)");original.setVersion(2);original.close();
            store=new Store(context,name);check(store.getReadableDatabase().getVersion()==4,"v2 upgrades to v4");check(store.sessions().size()==2&&store.sessions().get(1).label.equals("Existing night"),"migration keeps both nights");check(store.cursor("board")==1,"migration keeps cursor");check(store.points(1).get(0).secondProbe==31.75&&store.points(1).get(0).phase==2,"migration keeps both probes and markers");check(store.weather(1).size()==1,"migration keeps weather");
            String sensorCsv=store.csv(1);long hot=store.addFeeling(1,1800000015000L,Store.FEELING_HOT,10);long cold=store.addFeeling(1,1800000005000L,Store.FEELING_COLD,1);store.addFeeling(2,1800086415000L,Store.FEELING_HOT,4);
            List<Store.FeelingEntry> feelings=store.feelings(1);check(feelings.size()==2&&feelings.get(0).id==cold&&feelings.get(1).id==hot,"feelings sort by date/time");check(store.feelings(2).size()==1,"entries stay in their selected night");check(store.csv(1).equals(sensorCsv),"annotations leave sensor CSV unchanged");check(store.feelingsCsv(1).contains("user_feeling,")&&store.feelingsCsv(1).contains(",Hot,10")&&store.feelingsCsv(1).contains(",Cold,1"),"annotations have a separate export");
            Store testing=store;rejected(()->testing.addFeeling(1,0,Store.FEELING_HOT,5),"invalid timestamp accepted");rejected(()->testing.addFeeling(1,1800000000000L,"Warm",5),"invalid choice accepted");rejected(()->testing.addFeeling(1,1800000000000L,Store.FEELING_HOT,0),"zero intensity accepted");rejected(()->testing.addFeeling(1,1800000000000L,Store.FEELING_COLD,11),"too-high intensity accepted");rejected(()->testing.addFeeling(999,1800000000000L,Store.FEELING_COLD,5),"missing night accepted");rejected(()->testing.updateFeeling(hot,2,1800086415000L,Store.FEELING_COLD,3),"entry moved to another night");
            store.updateFeeling(hot,1,1800000025000L,Store.FEELING_COLD,7);store.close();store=new Store(context,name);feelings=store.feelings(1);Store.FeelingEntry edited=feelings.get(1);check(edited.id==hot&&edited.session==1&&edited.time==1800000025000L&&edited.kind.equals(Store.FEELING_COLD)&&edited.intensity==7,"edits persist after reopen");check(store.points(1).size()==1&&store.csv(1).equals(sensorCsv),"edits preserve recorded samples");
        }finally{if(original.isOpen())original.close();if(store!=null)store.close();context.deleteDatabase(name);}
    }
}
