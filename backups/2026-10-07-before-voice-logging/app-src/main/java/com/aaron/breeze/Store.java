package com.aaron.breeze;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class Store extends SQLiteOpenHelper {
    public Store(Context c) { this(c,"breeze.db"); }
    public Store(Context c,String name) { super(c,name,null,3); }
    @Override public void onConfigure(SQLiteDatabase db){super.onConfigure(db);db.execSQL("PRAGMA synchronous=FULL");}
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
        d.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, UNIQUE(device,record))");
        d.execSQL("CREATE INDEX sample_time ON samples(session,time)");
        d.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
        d.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
        d.execSQL("ALTER TABLE samples ADD COLUMN second_probe REAL");
        d.execSQL("ALTER TABLE samples ADD COLUMN phase INTEGER NOT NULL DEFAULT 0");
        createFeelings(d);
    }
    private static void createFeelings(SQLiteDatabase d){
        d.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY, session INTEGER NOT NULL REFERENCES sessions(id), time INTEGER NOT NULL CHECK(time>0), kind TEXT NOT NULL CHECK(kind IN ('Hot','Cold')), intensity INTEGER NOT NULL CHECK(intensity BETWEEN 1 AND 10))");
        d.execSQL("CREATE INDEX feeling_time ON feelings(session,time,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int old,int next) {
        if(old<1||next>3||old>=next)throw new IllegalStateException("Migration required");
        if(old<2&&next>=2){d.execSQL("ALTER TABLE samples ADD COLUMN second_probe REAL");d.execSQL("ALTER TABLE samples ADD COLUMN phase INTEGER NOT NULL DEFAULT 0");}
        if(old<3&&next>=3)createFeelings(d);
    }
    public long start(String config) { ContentValues v=new ContentValues(); v.put("start",System.currentTimeMillis()); v.put("name","Sleep session"); v.put("config",config); return getWritableDatabase().insertOrThrow("sessions",null,v); }
    public void finish(long id) { ContentValues v=new ContentValues();v.put("end",System.currentTimeMillis());getWritableDatabase().update("sessions",v,"id=?",new String[]{""+id}); }
    public void sample(long session,Protocol.Reading r) {
        ContentValues v=new ContentValues();v.put("session",session);v.put("time",r.time); put(v,"probe",r.probe);put(v,"room",r.room);put(v,"humidity",r.humidity);v.put("speed",0);v.put("mode",0);v.put("uptime",r.uptime);v.put("fault",r.error);getWritableDatabase().insertOrThrow("samples",null,v);
    }
    private void put(ContentValues v,String key,double value) { if(Double.isFinite(value))v.put(key,value);else v.putNull(key); }
    public long cursor(String device){try(Cursor c=getReadableDatabase().rawQuery("SELECT next_record FROM cursors WHERE device=?",new String[]{device})){return c.moveToFirst()?c.getLong(0):0;}}
    public void setCursor(String device,long next){ContentValues v=new ContentValues();v.put("device",device);v.put("next_record",next);getWritableDatabase().insertWithOnConflict("cursors",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    // Only ingest contiguous records. A gap causes the BLE client to resume from cursor().
    public synchronized long ingest(String device,byte[] data) {
        Protocol.validateRecord(data);
        java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long index=Integer.toUnsignedLong(b.getInt(2)),time=Integer.toUnsignedLong(b.getInt(6))*1000L;
        if(index!=cursor(device))return -1;
        int flags=data[1]&255;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();long session=-1;
        try {
            try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE device=? ORDER BY first_record DESC LIMIT 1",new String[]{device})){if(c.moveToFirst())session=c.getLong(0);}
            if((flags&64)!=0 || session<0){ContentValues s=new ContentValues();s.put("start",time);s.put("name","Controller session");s.put("config","Recorded on XIAO ESP32C6");s.put("device",device);s.put("first_record",index);session=db.insertOrThrow("sessions",null,s);}
            ContentValues v=new ContentValues();v.put("session",session);v.put("device",device);v.put("record",index);v.put("time",time);put(v,"probe",(flags&8)!=0?b.getShort(10)/100.0:Double.NaN);put(v,"room",(flags&16)!=0?b.getShort(12)/100.0:Double.NaN);put(v,"humidity",(flags&32)!=0?(b.getShort(14)&65535)/100.0:Double.NaN);v.put("speed",data[0]==2?Math.min(3,data[16]&255):0);v.put("mode",data[0]==2&&(flags&2)!=0?1:0);v.put("fault",data[0]==6?data[18]&63:data[18]&255);put(v,"second_probe",data[0]==6&&(flags&2)!=0?b.getShort(16)/100.0:Double.NaN);v.put("phase",data[0]==6?(data[18]&255)>>6:0);db.insertOrThrow("samples",null,v);
            if((flags&128)!=0){ContentValues s=new ContentValues();s.put("end",time);db.update("sessions",s,"id=?",new String[]{""+session});}
            setCursor(device,index+1);db.setTransactionSuccessful();return session;
        }finally{db.endTransaction();}
    }
    public static class Session { public long id,start,end; public int count; public String label; }
    public List<Session> sessions() { List<Session> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,start,COALESCE(end,0),name,(SELECT count(*) FROM samples WHERE session=sessions.id) FROM sessions ORDER BY start DESC",null)){while(c.moveToNext()){Session s=new Session();s.id=c.getLong(0);s.start=c.getLong(1);s.end=c.getLong(2);s.label=c.getString(3);s.count=c.getInt(4);out.add(s);}}return out; }
    public static class Point { public long time; public double probe,room,humidity,secondProbe=Double.NaN; public int speed,phase; public Point(long t,double p,double r,double h,int s){time=t;probe=p;room=r;humidity=h;speed=s;} }
    public static String phaseName(int phase){return switch(phase){case 1->"Baseline";case 2->"Lying on probes";case 3->"Off probes";default->"";};}
    private static double number(Cursor c,int col){return c.isNull(col)?Double.NaN:c.getDouble(col);}
    public List<Point> points(long id) {List<Point> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,probe,room,humidity,speed,second_probe,phase FROM samples WHERE session=? ORDER BY time,id",new String[]{""+id})){while(c.moveToNext()){Point p=new Point(c.getLong(0),number(c,1),number(c,2),number(c,3),c.getInt(4));p.secondProbe=number(c,5);p.phase=c.getInt(6);out.add(p);}}return out;}
    public List<Point> weather(long id){List<Point> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,temp,humidity FROM weather WHERE session=? ORDER BY time",new String[]{""+id})){while(c.moveToNext())out.add(new Point(c.getLong(0),number(c,1),Double.NaN,number(c,2),0));}return out;}
    public static final String FEELING_HOT="Hot",FEELING_COLD="Cold";
    public static class FeelingEntry {public long id,session,time;public String kind;public int intensity;}
    public List<FeelingEntry> feelings(long session){
        List<FeelingEntry> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,session,time,kind,intensity FROM feelings WHERE session=? ORDER BY time,id",new String[]{""+session})){
            while(c.moveToNext()){FeelingEntry entry=new FeelingEntry();entry.id=c.getLong(0);entry.session=c.getLong(1);entry.time=c.getLong(2);entry.kind=c.getString(3);entry.intensity=c.getInt(4);out.add(entry);}
        }
        return out;
    }
    private ContentValues feelingValues(long session,long time,String kind,int intensity){
        if(time<=0)throw new IllegalArgumentException("Choose a valid date and time.");
        if(!FEELING_HOT.equals(kind)&&!FEELING_COLD.equals(kind))throw new IllegalArgumentException("Choose Hot or Cold.");
        if(intensity<1||intensity>10)throw new IllegalArgumentException("Choose an intensity from 1 to 10.");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM sessions WHERE id=?",new String[]{""+session})){if(!c.moveToFirst())throw new IllegalArgumentException("Choose a recorded night.");}
        ContentValues values=new ContentValues();values.put("session",session);values.put("time",time);values.put("kind",kind);values.put("intensity",intensity);return values;
    }
    public long addFeeling(long session,long time,String kind,int intensity){return getWritableDatabase().insertOrThrow("feelings",null,feelingValues(session,time,kind,intensity));}
    public void updateFeeling(long id,long session,long time,String kind,int intensity){
        ContentValues values=feelingValues(session,time,kind,intensity);
        if(getWritableDatabase().update("feelings",values,"id=? AND session=?",new String[]{""+id,""+session})!=1)throw new IllegalArgumentException("This entry is no longer in the selected night.");
    }
    /** Separate export keeps the existing ten-column sensor CSV unchanged. */
    public String feelingsCsv(long session){
        StringBuilder out=new StringBuilder("source,time_iso_utc,feeling,intensity_1_to_10\n");
        for(FeelingEntry entry:feelings(session))out.append("user_feeling,").append(java.time.Instant.ofEpochMilli(entry.time)).append(',').append(entry.kind).append(',').append(entry.intensity).append('\n');
        return out.toString();
    }
    public void weather(long id,long time,double temp,double humidity,String location){ContentValues v=new ContentValues();v.put("session",id);v.put("time",time);put(v,"temp",temp);put(v,"humidity",humidity);v.put("location",location);getWritableDatabase().insertWithOnConflict("weather",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public String weatherLocation(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT location FROM weather WHERE session=? LIMIT 1",new String[]{""+id})){return c.moveToFirst()?c.getString(0):"";}}
    public String csv(long id) {
        StringBuilder b=new StringBuilder("source,time_iso_utc,body_temperature_c,room_temperature_c,humidity_percent,controller_uptime_s,fault,location,second_body_temperature_c,test_phase\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,probe,room,humidity,speed,mode,uptime,fault,second_probe,phase FROM samples WHERE session=? ORDER BY time,id",new String[]{""+id})){while(c.moveToNext()) b.append("sensor,").append(java.time.Instant.ofEpochMilli(c.getLong(0))).append(',').append(val(c,1)).append(',').append(val(c,2)).append(',').append(val(c,3)).append(',').append(c.getLong(6)).append(',').append(c.getInt(7)).append(",,").append(val(c,8)).append(',').append(phaseName(c.getInt(9))).append('\n');}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,temp,humidity,location FROM weather WHERE session=? ORDER BY time",new String[]{""+id})){while(c.moveToNext()) b.append("outdoor_model,").append(java.time.Instant.ofEpochMilli(c.getLong(0))).append(',').append(val(c,1)).append(",,").append(val(c,2)).append(",,,\"").append(c.getString(3).replace("\"","\"\"")).append("\",,\n");}
        return b.toString();
    }
    private String val(Cursor c,int n){return c.isNull(n)?"":c.getString(n);}
}
