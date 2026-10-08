package com.aaron.breeze;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class Store extends SQLiteOpenHelper {
    public Store(Context c) { this(c,"breeze.db"); }
    public Store(Context c,String name) { super(c,name,null,1); }
    @Override public void onConfigure(SQLiteDatabase db){super.onConfigure(db);db.execSQL("PRAGMA synchronous=FULL");}
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, name TEXT NOT NULL, config TEXT NOT NULL, device TEXT, first_record INTEGER)");
        d.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY, session INTEGER NOT NULL, time INTEGER NOT NULL, probe REAL, room REAL, humidity REAL, speed INTEGER, mode INTEGER, uptime INTEGER, fault INTEGER, device TEXT, record INTEGER, UNIQUE(device,record))");
        d.execSQL("CREATE INDEX sample_time ON samples(session,time)");
        d.execSQL("CREATE TABLE weather (session INTEGER NOT NULL,time INTEGER NOT NULL,temp REAL,humidity REAL,location TEXT NOT NULL, PRIMARY KEY(session,time))");
        d.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY,next_record INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int old,int next) { throw new IllegalStateException("Migration required"); }
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
            ContentValues v=new ContentValues();v.put("session",session);v.put("device",device);v.put("record",index);v.put("time",time);put(v,"probe",(flags&8)!=0?b.getShort(10)/100.0:Double.NaN);put(v,"room",(flags&16)!=0?b.getShort(12)/100.0:Double.NaN);put(v,"humidity",(flags&32)!=0?(b.getShort(14)&65535)/100.0:Double.NaN);v.put("speed",Math.min(3,data[16]&255));v.put("mode",(flags&2)!=0?1:0);v.put("fault",data[18]&255);db.insertOrThrow("samples",null,v);
            if((flags&128)!=0){ContentValues s=new ContentValues();s.put("end",time);db.update("sessions",s,"id=?",new String[]{""+session});}
            setCursor(device,index+1);db.setTransactionSuccessful();return session;
        }finally{db.endTransaction();}
    }
    public static class Session { public long id,start,end; public int count; public String label; }
    public List<Session> sessions() { List<Session> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,start,COALESCE(end,0),name,(SELECT count(*) FROM samples WHERE session=sessions.id) FROM sessions ORDER BY start DESC",null)){while(c.moveToNext()){Session s=new Session();s.id=c.getLong(0);s.start=c.getLong(1);s.end=c.getLong(2);s.label=c.getString(3);s.count=c.getInt(4);out.add(s);}}return out; }
    public static class Point { public long time; public double probe,room,humidity; public int speed; public Point(long t,double p,double r,double h,int s){time=t;probe=p;room=r;humidity=h;speed=s;} }
    private static double number(Cursor c,int col){return c.isNull(col)?Double.NaN:c.getDouble(col);}
    public List<Point> points(long id) {List<Point> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,probe,room,humidity,speed FROM samples WHERE session=? ORDER BY time,id",new String[]{""+id})){while(c.moveToNext())out.add(new Point(c.getLong(0),number(c,1),number(c,2),number(c,3),c.getInt(4)));}return out;}
    public List<Point> weather(long id){List<Point> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,temp,humidity FROM weather WHERE session=? ORDER BY time",new String[]{""+id})){while(c.moveToNext())out.add(new Point(c.getLong(0),number(c,1),Double.NaN,number(c,2),0));}return out;}
    public void weather(long id,long time,double temp,double humidity,String location){ContentValues v=new ContentValues();v.put("session",id);v.put("time",time);put(v,"temp",temp);put(v,"humidity",humidity);v.put("location",location);getWritableDatabase().insertWithOnConflict("weather",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public String weatherLocation(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT location FROM weather WHERE session=? LIMIT 1",new String[]{""+id})){return c.moveToFirst()?c.getString(0):"";}}
    public String csv(long id) {
        StringBuilder b=new StringBuilder("source,time_iso_utc,body_temperature_c,fan_temperature_c,humidity_percent,controller_uptime_s,fault,location\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,probe,room,humidity,speed,mode,uptime,fault FROM samples WHERE session=? ORDER BY time,id",new String[]{""+id})){while(c.moveToNext()) b.append("sensor,").append(java.time.Instant.ofEpochMilli(c.getLong(0))).append(',').append(val(c,1)).append(',').append(val(c,2)).append(',').append(val(c,3)).append(',').append(c.getLong(6)).append(',').append(c.getInt(7)).append(",\n");}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,temp,humidity,location FROM weather WHERE session=? ORDER BY time",new String[]{""+id})){while(c.moveToNext()) b.append("outdoor_model,").append(java.time.Instant.ofEpochMilli(c.getLong(0))).append(',').append(val(c,1)).append(",,").append(val(c,2)).append(",,,\"").append(c.getString(3).replace("\"","\"\"")).append("\"\n");}
        return b.toString();
    }
    private String val(Cursor c,int n){return c.isNull(n)?"":c.getString(n);}
}
