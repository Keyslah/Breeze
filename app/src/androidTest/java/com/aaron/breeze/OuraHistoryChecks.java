package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic, isolated database and off-screen graph checks. Never inserts readings into Breeze. */
final class OuraHistoryChecks {
    static final long START=OuraData.timestamp("2026-11-01T00:30:00-05:00"),END=START+10800000;
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static OuraData.Period period(String id,double temperature)throws Exception{
        JSONObject source=new JSONObject().put("id",id).put("day","2026-11-01").put("type","long_sleep")
            .put("bedtime_start","2026-11-01T00:30:00-05:00").put("bedtime_end","2026-11-01T02:30:00-06:00")
            .put("readiness",new JSONObject().put("temperature_deviation",temperature)).put("average_breath",14.5)
            .put("heart_rate",new JSONObject().put("timestamp","2026-11-01T00:30:00-05:00").put("interval",300)
                .put("items",new JSONArray().put(60).put(60).put(JSONObject.NULL).put(60).put(60)))
            .put("sleep_phase_5_min","12034");
        return new OuraData.Period(OuraData.retain(source,null));
    }
    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();String name="temporary-oura-history-"+System.nanoTime()+".db";
        try{
            try(Store fixture=new Store(context,name)){
                SQLiteDatabase db=fixture.getWritableDatabase();
                db.execSQL("INSERT INTO sessions (id,start,end,name,config) VALUES (1,"+START+","+END+",'Synthetic night','preserve')");
                db.execSQL("INSERT INTO samples (session,time,probe,room,humidity,fan_setting) VALUES (1,"+START+",36.5,22,50,4)");
                db.execSQL("INSERT INTO cursors VALUES ('synthetic-board',42)");
                // These empty tables belong only to this invocation's disposable version-7 fixture.
                db.execSQL("DROP TABLE oura_sleep");db.execSQL("DROP TABLE oura_imports");db.execSQL("DROP TABLE phone_recordings");db.execSQL("DROP TABLE recording_intent");db.setVersion(7);
            }
            Store.Session night;
            try(Store store=new Store(context,name)){
                check(store.getReadableDatabase().getVersion()==9,"Additive 7 -> 9 migration");night=store.sessions().get(0);
                check(store.points(1).size()==1&&store.points(1).get(0).probe==36.5&&store.cursor("synthetic-board")==42,"Legacy recording and cursor preserved");
                check(!store.oura(night).fetched,"Uncached night remains eligible for initial import");
                String before=store.csv(1);OuraData.Period first=period("synthetic-oura",0.5);
                store.saveOura(1,START,END,"2026-10-31","2026-11-03",List.of(first),END+1000);
                store.saveOura(1,START,END,"2026-10-31","2026-11-03",List.of(first),END+2000);
                check(store.oura(night).periods.size()==1,"Two refreshes do not duplicate a source record");
                store.saveOura(1,START,END,"2026-10-31","2026-11-03",List.of(period("synthetic-oura",-0.5)),END+3000);
                check(store.oura(night).periods.get(0).temperatureC==-0.5,"Revised source record updates in place");
                check(before.equals(store.csv(1)),"Oura writes never alter sensor export");
            }
            try(Store reopened=new Store(context,name)){
                OuraData.Cache cache=reopened.oura(night);check(cache.fetched&&cache.importedAt==END+3000&&cache.periods.size()==1,"Offline cache and import time survive reopening without any network");
                check(cache.periods.get(0).heart.length==5&&!cache.periods.get(0).heartValid(2),"Offline null gap preserved");
                reopened.saveOura(1,START,END,"2026-10-31","2026-11-03",List.of(),END+4000);
                check(reopened.oura(night).fetched&&reopened.oura(night).periods.isEmpty(),"Successful empty response is cached without showing obsolete periods");
                try(android.database.Cursor c=reopened.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM oura_sleep",null)){c.moveToFirst();check(c.getInt(0)==1,"Retired source is preserved, not deleted");}
            }
            OuraData.Period synthetic=period("graph-only",0.5);Throwable[] failure={null};
            test.runOnMainSync(()->{try{graph(context,synthetic);}catch(Throwable e){failure[0]=e;}});
            if(failure[0]!=null)throw new AssertionError("Off-screen Oura graph checks",failure[0]);
            return "PASS: additive 7 -> 9 migration, preserved sensor rows/export/cursor, idempotent refresh, source revisions, offline reopen, empty cache, gap rendering, independent BPM/temperature scale and height, zoom/pan preservation, Central DST; synthetic isolated fixtures only\n";
        }finally{context.deleteDatabase(name);}
    }
    private static void graph(Context context,OuraData.Period period)throws Exception{
        float density=context.getResources().getDisplayMetrics().density;int width=Math.round(360*density);
        GraphView graph=new GraphView(context);graph.layout(0,0,width,Math.round(346*density));
        graph.data(1,List.of(new Store.Point(START,36.5,22,50,0),new Store.Point(END,36.7,23,51,0)),List.of(),true,1<<GraphView.HIGHEST_BODY_SERIES);
        float bottom=graph.fanSettingY(0);
        Method window=GraphView.class.getDeclaredMethod("setWindow",double.class,double.class);window.setAccessible(true);window.invoke(graph,(double)START,1200000d);
        double oldStart=graph.visibleStartTime(),oldEnd=graph.visibleEndTime();
        double[] temperature=graph.visibleTemperatureRangeC();
        graph.oura(List.of(period),OuraData.ALL,START,END);int height=Math.round((346+graph.ouraHeightDp())*density);graph.layout(0,0,width,height);
        check(graph.visibleStartTime()==oldStart&&graph.visibleEndTime()==oldEnd,"Oura import preserves zoom and pan");
        check(Math.abs(graph.fanSettingY(0)-bottom)<1,"Extra Oura rows preserve original sensor plot height");
        check(java.util.Arrays.equals(temperature,graph.visibleTemperatureRangeC()),"BPM and temperature deviations never alter body-temperature scale");
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);graph.draw(new Canvas(bitmap));
        // A flat HR fixture makes a bridged null sample detectable at the midpoint.
        int hrY=Math.round((346+28+24+32)*density);
        int gapX=Math.round(36*density+(width-72*density)*0.5f),validX=Math.round(36*density+(width-72*density)*0.125f);
        check((bitmap.getPixel(validX,hrY)&0xffffff)==(OuraPlot.HEART_COLOR&0xffffff),"Real adjacent samples draw HR line");
        check((bitmap.getPixel(gapX,hrY)&0xffffff)!=(OuraPlot.HEART_COLOR&0xffffff),"Null HR sample is a drawn gap");
        int stageY=Math.round((346+28+106+28)*density);
        check((bitmap.getPixel(validX,stageY)&0xffffff)==(OuraPlot.STAGE_COLORS[1]&0xffffff),"Stage uses returned five-minute bins");
        int gapPixel=bitmap.getPixel(gapX,stageY)&0xffffff;
        for(int color:OuraPlot.STAGE_COLORS)if(color!=0)check(gapPixel!=(color&0xffffff),"Unknown stage remains a gap");
        bitmap.recycle();
        graph.oura(List.of(period),0,START,END);graph.layout(0,0,width,Math.round(346*density));
        check(graph.ouraHeightDp()==0&&graph.visibleStartTime()==oldStart&&graph.visibleEndTime()==oldEnd,"Hiding Oura preserves viewport");
        check(new GraphView.ReadingDetails(START,List.of(),"").timestamp.contains("CDT"),"Readout uses Central DST regardless of device zone");
    }
}
