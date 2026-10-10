package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

/** Off-screen graph fixtures only; no controller, saved nights or real settings. */
final class FanSettingGraphChecks {
    private static final long START=1800000000000L;
    private final Context context;
    private final float density;
    private long eventTime=SystemClock.uptimeMillis();
    private FanSettingGraphChecks(Context context){this.context=context;density=context.getResources().getDisplayMetrics().density;}
    static void run(Context context){FanSettingGraphChecks checks=new FanSettingGraphChecks(context);checks.stepsAndReadings();checks.visibleBoundaryAndFeelings();checks.migration();}
    private int px(float value){return Math.round(value*density);}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static Store.Point sample(int seconds,double setting){Store.Point point=new Store.Point(START+seconds*1000,32,24,50,0);point.secondProbe=31;point.fanSetting=setting;return point;}
    private GraphView graph(){GraphView graph=new GraphView(context);graph.layout(0,0,px(360),px(346));return graph;}
    private void tap(GraphView graph,float x,float y){
        long down=eventTime+=800;MotionEvent start=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0),end=MotionEvent.obtain(down,down+20,MotionEvent.ACTION_UP,x,y,0);
        try{graph.dispatchTouchEvent(start);graph.dispatchTouchEvent(end);}finally{start.recycle();end.recycle();}
    }
    private Bitmap draw(GraphView graph){Bitmap result=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);canvas.drawColor(0xff20352b);graph.draw(canvas);return result;}
    private boolean colorNear(Bitmap image,int color,float x,float y){
        for(int yy=Math.max(0,Math.round(y)-px(1));yy<=Math.min(image.getHeight()-1,Math.round(y)+px(1));yy++)
            for(int xx=Math.max(0,Math.round(x)-px(1));xx<=Math.min(image.getWidth()-1,Math.round(x)+px(1));xx++)if(image.getPixel(xx,yy)==color)return true;
        return false;
    }
    private void stepsAndReadings(){
        int fan=1<<GraphView.FAN_SETTING_SERIES,color=GraphView.COLORS[GraphView.FAN_SETTING_SERIES];GraphView graph=graph();
        List<Store.Point> points=List.of(sample(0,0),sample(10,3),sample(20,3),sample(30,Double.NaN),sample(40,10),sample(50,2),sample(60,2));
        for(Store.Point point:points)point.humidity=40;
        int sensors=(1<<GraphView.HIGHEST_BODY_SERIES)|(1<<4);
        graph.data(91,points,List.of(),true,sensors);double[] before=graph.visibleTemperatureRangeC();Bitmap sensorsOnly=draw(graph);
        graph.visibleSeries(fan|sensors);double[] after=graph.visibleTemperatureRangeC();
        check(before[0]==after[0]&&before[1]==after[1],"Fan settings do not affect the temperature range");
        Bitmap image=draw(graph);
        try{
            try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),"fan-setting-graph-check.png"))){
                check(image.compress(Bitmap.CompressFormat.PNG,100,output),"Save representative fan setting graph preview");
            }catch(IOException failure){throw new AssertionError("Could not save fan setting graph preview",failure);}
            check(Math.abs(graph.fanSettingY(10)-px(30))<1&&Math.abs(graph.fanSettingY(0)-px(320))<1,"Fan setting shares the original sensor plotting rectangle without added height");
            int temperatureColor=GraphView.COLORS[GraphView.HIGHEST_BODY_SERIES],humidityColor=GraphView.COLORS[4];
            check(colorNear(sensorsOnly,temperatureColor,px(60),px(175)),"The original temperature trace occupies the middle of the full-height plotting area");
            check(colorNear(sensorsOnly,humidityColor,px(60),px(204)),"Humidity uses the same full-height plotting area");
            for(int y=0;y<image.getHeight();y++){
                int original=sensorsOnly.getPixel(px(60),y),overlaid=image.getPixel(px(60),y);
                check((original==temperatureColor)==(overlaid==temperatureColor),"Toggling fan setting cannot move temperature plot pixels");
                check((original==humidityColor)==(overlaid==humidityColor),"Toggling fan setting cannot move humidity plot pixels");
            }
            check(colorNear(image,color,px(60),graph.fanSettingY(0)),"Off is a visible zero level before the first fan change");
            check(colorNear(image,color,px(84),graph.fanSettingY(1.5)),"Fan changes draw vertical steps at the recorded timestamp");
            check(!colorNear(image,color,px(60),graph.fanSettingY(1.5)),"Fan changes never interpolate diagonal intermediate settings");
            check(colorNear(image,color,px(150),graph.fanSettingY(3)),"A setting holds until the next recorded change or Unknown");
            for(int y=30;y<=320;y++)check(!colorNear(image,color,px(204),px(y)),"Unknown leaves a gap instead of carrying a fan setting forward");
        }finally{image.recycle();sensorsOnly.recycle();}
        GraphView.ReadingDetails[] reading={null};graph.setReadingDetailsListener(value->reading[0]=value);
        String[] expected={"Off","3","3","Unknown","H","2","2"};
        for(int i=0;i<expected.length;i++){tap(graph,graph.feelingX(START+i*10000),graph.fanSettingY(3));check(reading[0]!=null&&reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals(expected[i]),"Fan readout is "+expected[i]+" without temperature or percent units");}
        graph.visibleSeries(0);check(reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals("2"),"Hiding the fan line retains its numeric reading");
        graph.data(92,List.of(sample(0,Double.NaN),sample(60,Double.NaN)),List.of(),false,fan);tap(graph,graph.feelingX(START),graph.fanSettingY(0));
        check(reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals("Unknown"),"Legacy missing fan data stays Unknown rather than Off");
        graph.data(94,List.of(sample(0,11),sample(60,10)),List.of(),false,fan);tap(graph,graph.feelingX(START),graph.fanSettingY(0));
        check(reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals("Unknown"),"An out-of-range setting11 cannot become a valid fan reading");
        tap(graph,graph.feelingX(START+60000),graph.fanSettingY(10));check(reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals("H"),"The highest plotted level is H");
    }
    private void visibleBoundaryAndFeelings(){
        int fan=1<<GraphView.FAN_SETTING_SERIES,color=GraphView.COLORS[GraphView.FAN_SETTING_SERIES];GraphView graph=graph();
        graph.data(93,List.of(sample(0,Double.NaN),sample(50,3),sample(70,9),sample(80,Double.NaN),sample(180,Double.NaN)),List.of(),false,fan);
        try{Method setWindow=GraphView.class.getDeclaredMethod("setWindow",double.class,double.class);setWindow.setAccessible(true);setWindow.invoke(graph,(double)(START+60000),60000d);}catch(ReflectiveOperationException error){throw new AssertionError(error);}
        Bitmap image=draw(graph);
        try{check(colorNear(image,color,px(60),graph.fanSettingY(3)),"Zoomed fan line clips the prior held setting correctly at the viewport boundary");}finally{image.recycle();}
        Store.FeelingEntry beforeChange=new Store.FeelingEntry();beforeChange.id=1;beforeChange.session=93;beforeChange.time=START+65000;beforeChange.kind=Store.FEELING_HOT;beforeChange.intensity=5;beforeChange.signedValue=5;
        Store.FeelingEntry unknown=new Store.FeelingEntry();unknown.id=2;unknown.session=93;unknown.time=START+85000;unknown.kind=Store.FEELING_COLD;unknown.intensity=5;unknown.signedValue=-5;
        graph.feelings(List.of(beforeChange,unknown));GraphView.ReadingDetails[] reading={null};graph.setReadingDetailsListener(value->reading[0]=value);
        check(Math.abs(graph.fanSettingY(10)-px(30))<1&&Math.abs(graph.fanSettingY(0)-px(200))<1,"Existing feelings leave the same shared sensor rectangle for the fan line");
        tap(graph,graph.feelingX(beforeChange.time),graph.feelingY(5));check(reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals("3"),"Feeling inspection uses the setting before its time, never a closer future change");
        tap(graph,graph.feelingX(unknown.time),graph.feelingY(-5));check(reading[0].values.get(GraphView.FAN_SETTING_SERIES).value.equals("Unknown"),"Feeling inspection does not skip an explicit Unknown to resurrect older fan state");
        double start=graph.visibleStartTime(),end=graph.visibleEndTime();float feelingY=graph.feelingY(0);graph.visibleSeries(0);
        check(start==graph.visibleStartTime()&&end==graph.visibleEndTime()&&feelingY==graph.feelingY(0),"Fan toggle preserves shared time zoom and the feeling scale");
    }
    private void migration(){
        String name="fan-setting-graph-checks-"+SystemClock.elapsedRealtimeNanos();SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        try{
            for(int prior=0;prior<256;prior++){
                check(prefs.edit().clear().putBoolean("historyHighestBodyDefaultApplied",true).putInt("historyVisibleSeries",prior).commit(),"Prepare isolated graph preference");
                check(Settings.historyVisibleSeries(prefs)==(prior|256),"Fan upgrade enables only its new series and preserves all earlier choices");
                check(prefs.getBoolean("historyFanSettingDefaultApplied",false),"Fan default migration is marked once");
                check(prefs.edit().putInt("historyVisibleSeries",prior).commit(),"Save a user-disabled fan line");
                check(Settings.historyVisibleSeries(prefs)==prior,"A later user choice to hide fan setting survives reload");
            }
        }finally{context.deleteSharedPreferences(name);}
    }
}
