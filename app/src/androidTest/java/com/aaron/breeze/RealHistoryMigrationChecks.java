package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Migrates only a disposable copy of the supplied offline database, never the live history. */
final class RealHistoryMigrationChecks {
    private static final String[] TABLES={"sessions","samples","weather","feelings","cursors"};
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static final class Fingerprint {
        final long rows;
        final String sha256;
        Fingerprint(long rows,byte[] hash){this.rows=rows;StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));sha256=hex.toString();}
    }
    private static void number(MessageDigest hash,long value){hash.update(ByteBuffer.allocate(8).putLong(value).array());}
    private static void bytes(MessageDigest hash,byte[] value){number(hash,value.length);hash.update(value);}
    private static Fingerprint fingerprint(SQLiteDatabase db,String table)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");long rows=0;
        // These five fixed table names come from this test, not external SQL input.
        try(Cursor cursor=db.rawQuery("SELECT rowid,* FROM "+table+" ORDER BY rowid",null)){
            number(hash,cursor.getColumnCount());
            for(String column:cursor.getColumnNames())bytes(hash,column.getBytes(StandardCharsets.UTF_8));
            while(cursor.moveToNext()){
                rows++;
                for(int column=0;column<cursor.getColumnCount();column++){
                    int type=cursor.getType(column);hash.update((byte)type);
                    switch(type){
                        case Cursor.FIELD_TYPE_NULL:break;
                        case Cursor.FIELD_TYPE_INTEGER:number(hash,cursor.getLong(column));break;
                        case Cursor.FIELD_TYPE_FLOAT:number(hash,Double.doubleToRawLongBits(cursor.getDouble(column)));break;
                        case Cursor.FIELD_TYPE_STRING:bytes(hash,cursor.getString(column).getBytes(StandardCharsets.UTF_8));break;
                        case Cursor.FIELD_TYPE_BLOB:bytes(hash,cursor.getBlob(column));break;
                        default:throw new AssertionError("Unknown SQLite value type "+type);
                    }
                }
            }
        }
        number(hash,rows);return new Fingerprint(rows,hash.digest());
    }
    private static Map<String,Fingerprint> fingerprints(SQLiteDatabase db)throws Exception{
        Map<String,Fingerprint> result=new LinkedHashMap<>();
        for(String table:TABLES)result.put(table,fingerprint(db,table));
        return result;
    }
    private static void integrity(SQLiteDatabase db){
        try(Cursor cursor=db.rawQuery("PRAGMA integrity_check",null)){
            check(cursor.moveToFirst()&&"ok".equals(cursor.getString(0))&&!cursor.moveToNext(),"SQLite integrity_check must return exactly ok");
        }
    }
    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();File source=new File(context.getFilesDir(),"history-before.db");
        check(source.isFile(),"Place the offline source at files/history-before.db before this check");
        String name="temporary-real-history-migration-"+System.nanoTime()+".db";
        File fixture=context.getDatabasePath(name);boolean created=false;Store store=null;
        try{
            Files.createDirectories(fixture.toPath().getParent());
            Files.createFile(fixture.toPath());created=true;
            Files.copy(source.toPath(),fixture.toPath(),StandardCopyOption.REPLACE_EXISTING);
            Map<String,Fingerprint> before;
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(fixture.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(db.getVersion()==6,"Offline source must be schema 6");integrity(db);before=fingerprints(db);
            }
            store=new Store(context,name);SQLiteDatabase migrated=store.getWritableDatabase();
            check(migrated.getVersion()==9,"Migration must change schema 6 -> 9");integrity(migrated);
            Map<String,Fingerprint> after=fingerprints(migrated);StringBuilder counts=new StringBuilder();
            for(String table:TABLES){
                Fingerprint old=before.get(table),now=after.get(table);
                check(old.rows==now.rows&&old.sha256.equals(now.sha256),table+" row count or complete row fingerprint changed");
                if(counts.length()>0)counts.append(", ");counts.append(table).append('=').append(now.rows);
            }
            try(Cursor cursor=migrated.rawQuery("SELECT COUNT(*) FROM dreo_temperatures",null)){
                check(cursor.moveToFirst()&&cursor.getLong(0)==0,"New Dreo observation table must contain 0 fabricated readings");
            }
            return "PASS: actual offline phone history schema 6 -> 7; integrity_check ok before and after; identical complete-row SHA-256 fingerprints for "+counts+"; dreo_temperatures=0; source preserved, disposable copy only\n";
        }finally{if(store!=null)store.close();if(created)context.deleteDatabase(name);}
    }
}
