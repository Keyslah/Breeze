package com.aaron.breeze;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.json.JSONObject;

/** Off-screen adversarial viewport checks; source arrays and recordings remain untouched. */
final class SleepWindowChecks {
    private static final long START=1793685600000L;
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    static OuraData.Period period(long start,long end)throws Exception{
        JSONObject source=new JSONObject().put("id","sleep-"+start).put("day","2026-11-03").put("type","long_sleep")
            .put("bedtime_start",Instant.ofEpochMilli(start).toString()).put("bedtime_end",Instant.ofEpochMilli(end).toString());
        return new OuraData.Period(OuraData.retain(source,null));
    }
    private static Store.Point point(long time,double body){Store.Point p=new Store.Point(time,body,body,50,0);p.fanSetting=4;return p;}
    private static void field(GraphView graph,String name,double value)throws Exception{Field field=GraphView.class.getDeclaredField(name);field.setAccessible(true);field.setDouble(graph,value);}
    private static List<?> list(GraphView graph,String name)throws Exception{Field field=GraphView.class.getDeclaredField(name);field.setAccessible(true);return (List<?>)field.get(graph);}
    static void run(Context context)throws Exception{
        GraphView graph=new GraphView(context);float density=context.getResources().getDisplayMetrics().density;
        int width=Math.round(360*density),height=Math.round(346*density);graph.layout(0,0,width,height);
        List<Store.Point> source=List.of(point(START-10000,100),point(START,36),point(START+10000,36),point(START+110000,36),point(START+120000,36),point(START+130000,100));
        graph.data(1,source,List.of(point(START-1000,-50)),false,GraphView.ALL_SERIES);
        graph.dreoTemperatures(List.of(point(START-1000,200)));
        Store.FeelingEntry feeling=new Store.FeelingEntry();feeling.id=1;feeling.time=START-1000;feeling.signedValue=5;graph.feelings(List.of(feeling));
        graph.oura(List.of(period(START,START+120000)),0,START-10000,START+130000);
        GraphView.ReadingDetails[] selected={null};graph.setReadingDetailsListener(value->selected[0]=value);graph.setSmoothHighestBody(true);graph.setSmoothRoom(true);graph.sleepOnly(true);
        check(graph.visibleStartTime()==START&&graph.visibleEndTime()==START+120000,"Sleep fills exact Oura interval");
        check(list(graph,"points").size()==4&&list(graph,"outdoor").isEmpty()&&list(graph,"dreoTemperatures").isEmpty()&&list(graph,"feelings").isEmpty(),"All sources actually exclude outside readings");
        check(selected[0].values.get(6).value.equals("36.0°C"),"Outside extremes cannot affect smoothing/readouts");
        check(selected[0].values.get(3).value.equals("—")&&selected[0].values.get(9).value.equals("—"),"Nearby outside weather and Dreo readings cannot leak into readouts");
        check(graph.visibleTemperatureRangeC()[1]<37&&graph.visibleAmbientTemperatureRangeC()[1]<37,"Outside extremes cannot affect either temperature scale");
        Method window=GraphView.class.getDeclaredMethod("setWindow",double.class,double.class);window.setAccessible(true);
        for(double requested:new double[]{START-3600000d,START+3600000d}){window.invoke(graph,requested,60000d);check(graph.visibleStartTime()>=START&&graph.visibleEndTime()<=START+120000,"Zoom/pan constrained to sleep");}
        double from=graph.visibleStartTime(),to=graph.visibleEndTime();graph.data(1,source,List.of(point(START-1000,-50)),false,GraphView.ALL_SERIES);graph.oura(List.of(period(START,START+120000)),OuraData.HEART,START-10000,START+130000);
        check(graph.visibleStartTime()==from&&graph.visibleEndTime()==to&&list(graph,"points").size()==4,"Refresh retains zoom and strict filtering");
        // Deliberately bypass pan limits: filtering must still work in an outside viewport.
        Method notify=GraphView.class.getDeclaredMethod("notifyReadingDetails");notify.setAccessible(true);
        for(long outside:new long[]{START-120000,START+180000}){
            field(graph,"windowStart",outside);field(graph,"windowEnd",outside+60000);notify.invoke(graph);check(selected[0]==null,"No readout in an outside viewport");
            Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);graph.draw(new Canvas(image));
            for(int y=Math.round(45*density);y<Math.round(310*density);y++)for(int x=Math.round(55*density);x<Math.round(275*density);x++)for(int color:GraphView.COLORS)check(image.getPixel(x,y)!=color,"No sensor pixels outside sleep");image.recycle();
        }
        graph.sleepOnly(false);check(list(graph,"points").size()==6&&source.get(0).probe==100&&list(graph,"outdoor").size()==1,"Uncheck restores original unmodified data");
        graph.sleepOnly(true);graph.oura(List.of(),0,START,START+120000);check(list(graph,"points").isEmpty()&&selected[0]==null,"Missing sleep never falls back to unfiltered data");
        graph.oura(List.of(period(START,START+20000)),0,START-10000,START+130000);graph.resetZoom();check(graph.visibleEndTime()-graph.visibleStartTime()==20000,"Short period never extends to 60 seconds");
        graph.data(2,List.of(point(START,40)),List.of(),false,GraphView.ALL_SERIES);check(list(graph,"points").isEmpty(),"Changing night cannot reuse preceding sleep bounds");
        graph.data(3,List.of(point(START,34),point(START+10000,34),point(START+20000,40),point(START+30000,40)),List.of(),false,1<<6);
        graph.oura(List.of(period(START,START+10000),period(START+20000,START+30000)),0,START,START+30000);graph.resetZoom();
        check(selected[0].values.get(6).value.equals("40.0°C"),"Smoothing cannot cross even a 10-second non-sleep gap");
        SleepWindow union=new SleepWindow(List.of(period(START,START+10000),period(START+20000,START+30000)));
        check(!union.contains(START+15000)&&!union.connected(START+10000,START+20000),"Between-period data and interpolation stay excluded");
    }
}
