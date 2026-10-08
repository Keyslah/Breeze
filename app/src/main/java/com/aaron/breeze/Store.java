package com.aaron.breeze;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Store extends SQLiteOpenHelper {
    public static final String FEELINGS_CHANGED="com.aaron.breeze.FEELINGS_CHANGED";
    private final Context context;
    public Store(Context c) { this(c,"breeze.db"); }
    public Store(Context c,String name) { super(c,name,null,4);context=c.getApplicationContext()==null?c:c.getApplicationContext(); }
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
        d.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY, session INTEGER REFERENCES sessions(id), time INTEGER NOT NULL CHECK(time>0), kind TEXT NOT NULL CHECK(kind IN ('Hot','Cold')), intensity INTEGER NOT NULL CHECK(intensity BETWEEN 1 AND 10), state TEXT NOT NULL CHECK(state IN ('WARM','COLD')), signed_value INTEGER NOT NULL CHECK(signed_value=CASE state WHEN 'WARM' THEN intensity ELSE -intensity END), local_timestamp TEXT NOT NULL, zone_id TEXT NOT NULL, local_time_derived INTEGER NOT NULL DEFAULT 0, event_token TEXT UNIQUE, controller_device TEXT, anchor_record INTEGER, CHECK(kind=CASE state WHEN 'WARM' THEN 'Hot' ELSE 'Cold' END))");
        d.execSQL("CREATE INDEX feeling_time ON feelings(session,time,id)");
        d.execSQL("CREATE INDEX feeling_pending ON feelings(controller_device,anchor_record) WHERE session IS NULL");
    }
    private static void createLegacyFeelings(SQLiteDatabase d){
        d.execSQL("CREATE TABLE feelings (id INTEGER PRIMARY KEY, session INTEGER NOT NULL REFERENCES sessions(id), time INTEGER NOT NULL CHECK(time>0), kind TEXT NOT NULL CHECK(kind IN ('Hot','Cold')), intensity INTEGER NOT NULL CHECK(intensity BETWEEN 1 AND 10))");
        d.execSQL("CREATE INDEX feeling_time ON feelings(session,time,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int old,int next) {
        if(old<1||next>4||old>=next)throw new IllegalStateException("Migration required");
        if(old<2&&next>=2){d.execSQL("ALTER TABLE samples ADD COLUMN second_probe REAL");d.execSQL("ALTER TABLE samples ADD COLUMN phase INTEGER NOT NULL DEFAULT 0");}
        if(old<3&&next>=3)createLegacyFeelings(d);
        if(old<4&&next>=4){
            // SQLiteOpenHelper wraps this rebuild in its upgrade transaction.
            d.execSQL("ALTER TABLE feelings RENAME TO feelings_v3");
            d.execSQL("DROP INDEX feeling_time");
            createFeelings(d);
            try(Cursor c=d.rawQuery("SELECT id,session,time,kind,intensity FROM feelings_v3 ORDER BY id",null)){
                while(c.moveToNext()){
                    String state=FEELING_HOT.equals(c.getString(3))?"WARM":"COLD";
                    ContentValues values=canonicalFeelingValues(c.getLong(2),state,c.getInt(4),ZoneOffset.UTC);
                    values.put("id",c.getLong(0));values.put("session",c.getLong(1));
                    // Old entries did not record a zone. UTC is a derived display, not an invented original zone.
                    values.put("local_time_derived",1);
                    d.insertOrThrow("feelings",null,values);
                }
            }
            d.execSQL("DROP TABLE feelings_v3");
        }
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
        int flags=data[1]&255;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();long session=-1;boolean feelingsChanged=false;
        try {
            try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE device=? ORDER BY first_record DESC LIMIT 1",new String[]{device})){if(c.moveToFirst())session=c.getLong(0);}
            if((flags&64)!=0 || session<0){ContentValues s=new ContentValues();s.put("start",time);s.put("name","Controller session");s.put("config","Recorded on XIAO ESP32C6");s.put("device",device);s.put("first_record",index);session=db.insertOrThrow("sessions",null,s);}
            ContentValues v=new ContentValues();v.put("session",session);v.put("device",device);v.put("record",index);v.put("time",time);put(v,"probe",(flags&8)!=0?b.getShort(10)/100.0:Double.NaN);put(v,"room",(flags&16)!=0?b.getShort(12)/100.0:Double.NaN);put(v,"humidity",(flags&32)!=0?(b.getShort(14)&65535)/100.0:Double.NaN);v.put("speed",data[0]==2?Math.min(3,data[16]&255):0);v.put("mode",data[0]==2&&(flags&2)!=0?1:0);v.put("fault",data[0]==6?data[18]&63:data[18]&255);put(v,"second_probe",data[0]==6&&(flags&2)!=0?b.getShort(16)/100.0:Double.NaN);v.put("phase",data[0]==6?(data[18]&255)>>6:0);db.insertOrThrow("samples",null,v);
            if((flags&128)!=0){ContentValues s=new ContentValues();s.put("end",time);db.update("sessions",s,"id=?",new String[]{""+session});}
            setCursor(device,index+1);feelingsChanged=resolvePendingFeelings(db,device)>0;db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(feelingsChanged)notifyFeelingsChanged();
        return session;
    }
    public static class Session { public long id,start,end; public int count; public String label; }
    public List<Session> sessions() { List<Session> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,start,COALESCE(end,0),name,(SELECT count(*) FROM samples WHERE session=sessions.id) FROM sessions ORDER BY start DESC",null)){while(c.moveToNext()){Session s=new Session();s.id=c.getLong(0);s.start=c.getLong(1);s.end=c.getLong(2);s.label=c.getString(3);s.count=c.getInt(4);out.add(s);}}return out; }
    public static class Point { public long time; public double probe,room,humidity,secondProbe=Double.NaN; public int speed,phase; public Point(long t,double p,double r,double h,int s){time=t;probe=p;room=r;humidity=h;speed=s;} }
    public static String phaseName(int phase){return switch(phase){case 1->"Baseline";case 2->"Lying on probes";case 3->"Off probes";default->"";};}
    private static double number(Cursor c,int col){return c.isNull(col)?Double.NaN:c.getDouble(col);}
    public List<Point> points(long id) {List<Point> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,probe,room,humidity,speed,second_probe,phase FROM samples WHERE session=? ORDER BY time,id",new String[]{""+id})){while(c.moveToNext()){Point p=new Point(c.getLong(0),number(c,1),number(c,2),number(c,3),c.getInt(4));p.secondProbe=number(c,5);p.phase=c.getInt(6);out.add(p);}}return out;}
    public List<Point> weather(long id){List<Point> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,temp,humidity FROM weather WHERE session=? ORDER BY time",new String[]{""+id})){while(c.moveToNext())out.add(new Point(c.getLong(0),number(c,1),Double.NaN,number(c,2),0));}return out;}
    public static final String FEELING_HOT="Hot",FEELING_COLD="Cold";
    public static class FeelingEntry {
        public long id,session,time,anchorRecord;
        public String kind,state,localTimestamp,zoneId,eventToken,controllerArchive;
        public int intensity,signedValue;
        public boolean isPending,localTimeDerived;
    }
    private static final String FEELING_COLUMNS="id,session,time,kind,intensity,state,signed_value,local_timestamp,zone_id,local_time_derived,event_token,controller_device,anchor_record";
    private static FeelingEntry feelingEntry(Cursor c){
        FeelingEntry entry=new FeelingEntry();entry.id=c.getLong(0);entry.isPending=c.isNull(1);entry.session=entry.isPending?-1:c.getLong(1);
        entry.time=c.getLong(2);entry.kind=c.getString(3);entry.intensity=c.getInt(4);entry.state=c.getString(5);entry.signedValue=c.getInt(6);
        entry.localTimestamp=c.getString(7);entry.zoneId=c.getString(8);entry.localTimeDerived=c.getInt(9)!=0;entry.eventToken=c.getString(10);
        entry.controllerArchive=c.getString(11);entry.anchorRecord=c.isNull(12)?-1:c.getLong(12);return entry;
    }
    private List<FeelingEntry> readFeelings(String where,String[] args){
        List<FeelingEntry> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+FEELING_COLUMNS+" FROM feelings"+where+" ORDER BY time,id",args)){
            while(c.moveToNext())out.add(feelingEntry(c));
        }
        return out;
    }
    public List<FeelingEntry> feelings(long session){
        return readFeelings(" WHERE session=?",new String[]{""+session});
    }
    public List<FeelingEntry> allFeelings(){return readFeelings("",null);}
    public List<FeelingEntry> unassignedFeelings(){return readFeelings(" WHERE session IS NULL",null);}
    private static String validateFeeling(String state,int intensity){
        String canonical=state==null?"":state.trim().toUpperCase(Locale.ROOT);
        if(!canonical.equals("WARM")&&!canonical.equals("COLD"))throw new IllegalArgumentException("Choose WARM or COLD.");
        if(intensity<1||intensity>10)throw new IllegalArgumentException("Choose an intensity from 1 to 10.");
        return canonical;
    }
    private static ContentValues canonicalFeelingValues(long time,String state,int intensity,ZoneId zone){
        if(time<=0)throw new IllegalArgumentException("Choose a valid date and time.");
        ContentValues values=new ContentValues();values.put("time",time);values.put("state",state);values.put("intensity",intensity);
        values.put("kind",state.equals("WARM")?FEELING_HOT:FEELING_COLD);values.put("signed_value",state.equals("WARM")?intensity:-intensity);
        values.put("local_timestamp",DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(zone)));
        values.put("zone_id",zone.getId());values.put("local_time_derived",0);return values;
    }
    /** Common entry point for an input without a verified controller recording identity. */
    public FeelingEntry logTemperatureFeeling(String state,int intensity){
        String canonical=validateFeeling(state,intensity);long now=System.currentTimeMillis();String zone=ZoneId.systemDefault().getId();
        return logTemperatureFeeling(UUID.randomUUID().toString(),now,null,-1,canonical,intensity,zone);
    }
    public FeelingEntry logTemperatureFeeling(String token,long receivedAt,String archive,long anchorRecord,String state,int intensity){
        return logTemperatureFeeling(token,receivedAt,archive,anchorRecord,state,intensity,ZoneId.systemDefault().getId());
    }
    /**
     * The caller captures receivedAt/zone immediately when an utterance is accepted.
     * Its UUID identifies that utterance across writer retries; deliberate repeats get new UUIDs.
     * A return value confirms the SQLite transaction has committed, even when the night is pending.
     */
    public FeelingEntry logTemperatureFeeling(String token,long receivedAt,String archive,long anchorRecord,String state,int intensity,String receivedZoneId){
        String canonical=validateFeeling(state,intensity);
        if(token==null||token.trim().isEmpty())throw new IllegalArgumentException("An entry identifier is required.");
        if(anchorRecord< -1)throw new IllegalArgumentException("Invalid recording anchor.");
        String device=archive==null||archive.trim().isEmpty()?null:archive;
        if(device==null&&anchorRecord>=0)throw new IllegalArgumentException("A recording anchor needs its controller archive.");
        ZoneId zone;
        try{zone=ZoneId.of(receivedZoneId);}catch(RuntimeException e){throw new IllegalArgumentException("A valid capture time zone is required.",e);}
        ContentValues values=canonicalFeelingValues(receivedAt,canonical,intensity,zone);
        values.put("event_token",token);values.putNull("session");
        if(device==null)values.putNull("controller_device");else values.put("controller_device",device);
        if(anchorRecord<0)values.putNull("anchor_record");else values.put("anchor_record",anchorRecord);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();FeelingEntry saved;boolean changed=false;
        try{
            try(Cursor c=db.rawQuery("SELECT "+FEELING_COLUMNS+" FROM feelings WHERE event_token=?",new String[]{token})){
                if(c.moveToFirst()){
                    saved=feelingEntry(c);
                    if(saved.time!=receivedAt||!saved.state.equals(canonical)||saved.intensity!=intensity
                            ||!Objects.equals(saved.controllerArchive,device)||saved.anchorRecord!=anchorRecord||!saved.zoneId.equals(zone.getId()))
                        throw new IllegalArgumentException("This entry identifier was already used for different data.");
                }else{
                    long id=db.insertOrThrow("feelings",null,values);changed=true;
                    if(device!=null)resolvePendingFeelings(db,device);
                    saved=readFeeling(db,id);
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(changed)notifyFeelingsChanged();
        return saved;
    }
    private static FeelingEntry readFeeling(SQLiteDatabase db,long id){
        try(Cursor c=db.rawQuery("SELECT "+FEELING_COLUMNS+" FROM feelings WHERE id=?",new String[]{""+id})){
            if(!c.moveToFirst())throw new IllegalArgumentException("This entry no longer exists.");return feelingEntry(c);
        }
    }
    /** Exact archive/record identity, bounded by downloaded time, never the selected/latest night. */
    private static int resolvePendingFeelings(SQLiteDatabase db,String device){
        int resolved=0;List<long[]> waiting=new ArrayList<>();
        try(Cursor pending=db.rawQuery("SELECT id,time,anchor_record FROM feelings WHERE session IS NULL AND controller_device=? AND anchor_record IS NOT NULL",new String[]{device})){
            while(pending.moveToNext())waiting.add(new long[]{pending.getLong(0),pending.getLong(1),pending.getLong(2)});
        }
        for(long[] entry:waiting){
            long time=entry[1],session=-1;
            String sql="SELECT s.id FROM samples anchor JOIN sessions s ON s.id=anchor.session WHERE anchor.device=? AND anchor.record=CAST(? AS INTEGER) AND s.device=? AND s.start<=CAST(? AS INTEGER) AND COALESCE(s.end,(SELECT MAX(time) FROM samples WHERE session=s.id))>=CAST(? AS INTEGER) AND NOT EXISTS (SELECT 1 FROM sessions newer WHERE newer.device=s.device AND newer.first_record>s.first_record AND newer.start<=CAST(? AS INTEGER))";
            try(Cursor match=db.rawQuery(sql,new String[]{device,""+entry[2],device,""+time,""+time,""+time})){
                if(match.moveToFirst()){session=match.getLong(0);if(match.moveToNext())session=-1;}
            }
            if(session>0){ContentValues value=new ContentValues();value.put("session",session);resolved+=db.update("feelings",value,"id=? AND session IS NULL",new String[]{""+entry[0]});}
        }
        return resolved;
    }
    /** Deliberate user recovery when an archive identity or recording interval cannot be proved. */
    public void assignFeeling(long id,long session){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            requireSession(db,session);ContentValues value=new ContentValues();value.put("session",session);
            if(db.update("feelings",value,"id=? AND session IS NULL",new String[]{""+id})!=1)throw new IllegalArgumentException("This entry is already assigned or no longer exists.");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        notifyFeelingsChanged();
    }
    private static void requireSession(SQLiteDatabase db,long session){
        try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE id=?",new String[]{""+session})){if(!c.moveToFirst())throw new IllegalArgumentException("Choose a recorded night.");}
    }
    private void notifyFeelingsChanged(){context.sendBroadcast(new Intent(FEELINGS_CHANGED).setPackage(context.getPackageName()));}
    private ContentValues feelingValues(long session,long time,String kind,int intensity){
        if(time<=0)throw new IllegalArgumentException("Choose a valid date and time.");
        if(!FEELING_HOT.equals(kind)&&!FEELING_COLD.equals(kind))throw new IllegalArgumentException("Choose Hot or Cold.");
        String state=validateFeeling(FEELING_HOT.equals(kind)?"WARM":"COLD",intensity);requireSession(getReadableDatabase(),session);
        ContentValues values=canonicalFeelingValues(time,state,intensity,ZoneId.systemDefault());values.put("session",session);return values;
    }
    public long addFeeling(long session,long time,String kind,int intensity){long id=getWritableDatabase().insertOrThrow("feelings",null,feelingValues(session,time,kind,intensity));notifyFeelingsChanged();return id;}
    public void updateFeeling(long id,long session,long time,String kind,int intensity){
        ContentValues values=feelingValues(session,time,kind,intensity);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            FeelingEntry prior=readFeeling(db,id);
            if(prior.session!=session)throw new IllegalArgumentException("This entry is no longer in the selected night.");
            if(prior.time==time){values.put("local_timestamp",prior.localTimestamp);values.put("zone_id",prior.zoneId);values.put("local_time_derived",prior.localTimeDerived?1:0);}
            if(db.update("feelings",values,"id=? AND session=?",new String[]{""+id,""+session})!=1)throw new IllegalArgumentException("This entry is no longer in the selected night.");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        notifyFeelingsChanged();
    }
    /** Separate export keeps the existing ten-column sensor CSV unchanged. */
    public String feelingsCsv(long session){
        StringBuilder out=new StringBuilder("source,time_iso_utc,feeling,intensity_1_to_10,state,signed_value,time_local,zone_id,local_time_derived\n");
        for(FeelingEntry entry:feelings(session))out.append("user_feeling,").append(Instant.ofEpochMilli(entry.time)).append(',').append(entry.kind).append(',').append(entry.intensity)
                .append(',').append(entry.state).append(',').append(entry.signedValue).append(',').append(entry.localTimestamp).append(',').append(entry.zoneId).append(',').append(entry.localTimeDerived).append('\n');
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
