package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Off-screen aggregate checks and isolated temporary preferences; never touches saved nights. */
final class HighestBodyGraphChecks {
    private static final long START=1800000000000L;
    private final Context context;
    private final float density;
    private long eventTime=SystemClock.uptimeMillis();
    private HighestBodyGraphChecks(Context context){this.context=context;density=context.getResources().getDisplayMetrics().density;}
    static void run(Context context){
        HighestBodyGraphChecks checks=new HighestBodyGraphChecks(context);
        checks.valuesAndReadouts();checks.renderingAndGaps();checks.smoothingAndSelection();checks.smoothedMissingAndTimeGaps();checks.preferenceMigration();
    }
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private int px(float value){return Math.round(value*density);}
    private GraphView graph(){GraphView result=new GraphView(context);result.layout(0,0,px(360),px(346));return result;}
    private static Store.Point sample(long seconds,double first,double second,double third){
        Store.Point point=new Store.Point(START+seconds*1000,first,75,99,0);point.secondProbe=second;point.thirdProbe=third;return point;
    }
    private void select(GraphView graph,int seconds){
        float x=px(36)+px(288)*seconds/60f;long down=eventTime+=800;
        MotionEvent start=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,px(100),0);
        MotionEvent end=MotionEvent.obtain(down,down+20,MotionEvent.ACTION_UP,x,px(100),0);
        try{graph.dispatchTouchEvent(start);graph.dispatchTouchEvent(end);}finally{start.recycle();end.recycle();}
    }
    private void selectTime(GraphView graph,int seconds){
        float x=graph.feelingX(START+seconds*1000L);long down=eventTime+=800;
        MotionEvent start=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,px(100),0),end=MotionEvent.obtain(down,down+20,MotionEvent.ACTION_UP,x,px(100),0);
        try{graph.dispatchTouchEvent(start);graph.dispatchTouchEvent(end);}finally{start.recycle();end.recycle();}
    }
    private static void window(GraphView graph,int seconds,int spanSeconds){
        try{Method method=GraphView.class.getDeclaredMethod("setWindow",double.class,double.class);method.setAccessible(true);method.invoke(graph,(double)(START+seconds*1000L),spanSeconds*1000d);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void checkboxes(View view,List<CheckBox> result){
        if(view instanceof CheckBox)result.add((CheckBox)view);
        else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)checkboxes(group.getChildAt(i),result);}
    }
    private void valuesAndReadouts(){
        GraphView graph=graph();GraphReadingDetailsView panel=new GraphReadingDetailsView(context);
        GraphView.ReadingDetails[] reading={null};graph.setReadingDetailsListener(value->{reading[0]=value;panel.showReading(value);});
        List<CheckBox> controls=new ArrayList<>();checkboxes(panel,controls);controls.sort(java.util.Comparator.comparingInt(control->Integer.parseInt(control.getTag().toString().substring(13))));
        check(controls.size()==10,"Highest body temperature, D2 and fan setting retain every earlier source reading");
        for(int i=0;i<10;i++)check(controls.get(i).isChecked()==((i>=3&&i<=6)||i==GraphView.FAN_SETTING_SERIES),"Graph defaults hide D0, D1 and D2 while retaining the existing non-body choices and showing fan setting");
        List<Store.Point> samples=List.of(sample(0,34,33,32.5),sample(10,32.5,35,33),sample(20,32.5,33,36),
                sample(30,Double.NaN,Double.POSITIVE_INFINITY,37),sample(40,-5,-3,Double.NEGATIVE_INFINITY),
                sample(50,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY),sample(60,33,32.5,32.4));
        int highest=1<<GraphView.HIGHEST_BODY_SERIES;
        graph.data(81,samples,List.of(sample(0,100,Double.NaN,Double.NaN)),false,highest);
        String[] expected={"34.0°C","35.0°C","36.0°C","37.0°C","—","—","33.0°C"};
        for(int i=0;i<expected.length;i++){
            select(graph,i*10);
            check(reading[0]!=null&&reading[0].values.get(6).value.equals(expected[i]),"Highest temperature uses the maximum body reading at or above 90°F at the selected timestamp: "+expected[i]);
        }
        select(graph,0);
        int[] bodySeries={0,1,GraphView.D2_BODY_SERIES};
        for(int i=0;i<bodySeries.length;i++){
            int series=bodySeries[i];String value=reading[0].values.get(series).value;
            check(value.equals(new String[]{"34.0°C","33.0°C","32.5°C"}[i]),"Unchecked eligible source temperature retains its numerical reading");
            check(!controls.get(series).isChecked()&&((TextView)((ViewGroup)controls.get(series).getParent()).getChildAt(1)).getText().toString().equals(value),"Unchecked source control stays visible alongside its actual value");
        }
        check(reading[0].values.get(2).value.equals("75.0°C"),"The room reading remains independent even when hotter than every body probe");
        double[] range=graph.visibleTemperatureRangeC();
        check(range[0]>32&&range[0]<33&&range[1]>37&&range[1]<50,"Highest-only temperature range excludes body readings below 90°F, hot room/outdoor temperatures and humidity");
        graph.data(81,samples,new ArrayList<>(),true,highest);select(graph,0);
        check(reading[0].values.get(6).value.equals("93.2°F"),"Aggregate readout converts Celsius to Fahrenheit, never percent");
        check(reading[0].values.get(GraphView.D2_BODY_SERIES).value.equals("90.5°F"),"D2 readout converts as a temperature rather than humidity");
        double[] fahrenheitRange=graph.visibleTemperatureRangeC();
        check(Math.abs(range[0]-fahrenheitRange[0])<0.000001&&Math.abs(range[1]-fahrenheitRange[1])<0.000001,"Temperature unit conversion leaves the physical aggregate range unchanged");
        graph.visibleSeries(0);
        check(reading[0].values.get(6).value.equals("93.2°F")&&reading[0].values.get(0).value.equals("93.2°F"),"Hiding every trace retains aggregate and individual readouts");
        Store.Point legacy=new Store.Point(START,31,75,55,0);legacy.secondProbe=33;
        graph.data(85,List.of(legacy),new ArrayList<>(),false,highest);select(graph,0);
        check(reading[0].values.get(6).value.equals("33.0°C")&&reading[0].values.get(0).value.equals("—")&&reading[0].values.get(GraphView.D2_BODY_SERIES).value.equals("—"),"Legacy recordings lacking D2 use eligible D0/D1 readings without substituting the room temperature");
        legacy.probe=Double.NaN;legacy.secondProbe=Double.NaN;graph.data(85,List.of(legacy),new ArrayList<>(),false,highest);select(graph,0);
        check(reading[0].values.get(6).value.equals("—")&&reading[0].values.get(2).value.equals("75.0°C"),"Missing body probes never fall back to a valid room reading");
    }
    private Bitmap draw(GraphView graph){Bitmap result=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);graph.draw(new Canvas(result));return result;}
    private int colorCount(Bitmap image,int color,int left,int top,int right,int bottom){
        int count=0;for(int y=px(top);y<px(bottom);y++)for(int x=px(left);x<px(right);x++)if(image.getPixel(x,y)==color)count++;return count;
    }
    private void renderingAndGaps(){
        int highest=1<<GraphView.HIGHEST_BODY_SERIES,color=GraphView.COLORS[GraphView.HIGHEST_BODY_SERIES];
        GraphView graph=graph();List<Store.Point> samples=List.of(sample(0,34,33,32.5),sample(10,34,35,32.5),sample(20,34,35,36),
                sample(30,Double.NaN,Double.NaN,Double.NaN),sample(40,34,35,36),sample(50,34,35,32.5),sample(60,34,33,32.5));
        graph.data(82,samples,new ArrayList<>(),false,highest);
        double[] range=graph.visibleTemperatureRangeC();
        check(range[0]>33&&range[0]<34&&range[1]>36&&range[1]<37,"Aggregate-only auto-range uses each recorded maximum, excluding lower source values");
        Bitmap celsius=draw(graph);
        try{
            check(colorCount(celsius,color,70,30,100,320)>0,"A highest-only selection renders a temperature trace");
            check(colorCount(celsius,color,155,30,205,320)==0,"An all-missing body reading breaks the aggregate line instead of carrying a value across the gap");
            check(colorCount(celsius,0xffe5eee6,325,0,360,30)>0,"A highest-only selection renders the right-side temperature unit");
            for(int source:new int[]{0,1,GraphView.D2_BODY_SERIES})check(colorCount(celsius,GraphView.COLORS[source],36,30,324,320)==0,"Unchecked body traces do not render");
            graph.data(82,samples,new ArrayList<>(),true,highest);Bitmap fahrenheit=draw(graph);
            try{
                check(colorCount(fahrenheit,color,70,30,100,320)>0,"Aggregate temperature trace remains visible in Fahrenheit");
                check(colorCount(fahrenheit,color,155,30,205,320)==0,"Fahrenheit conversion preserves missing-reading gaps");
            }finally{fahrenheit.recycle();}
        }finally{celsius.recycle();}
        graph.data(83,List.of(sample(0,Double.NaN,Double.NaN,34),sample(30,Double.NaN,Double.NaN,36),sample(60,Double.NaN,Double.NaN,34)),new ArrayList<>(),false,highest);
        Bitmap historical=draw(graph);
        try{check(colorCount(historical,color,70,30,100,320)>0,"Historical 30-second body readings connect in the highest trace");}finally{historical.recycle();}
        graph.visibleSeries(1<<GraphView.D2_BODY_SERIES);Bitmap third=draw(graph);
        try{check(colorCount(third,GraphView.COLORS[GraphView.D2_BODY_SERIES],70,30,100,320)>0,"D2 can be enabled as an independent temperature trace");}finally{third.recycle();}
        graph.data(86,List.of(sample(0,Double.NaN,Double.NaN,34),sample(60,Double.NaN,Double.NaN,36)),new ArrayList<>(),false,highest);
        Bitmap disconnected=draw(graph);
        try{check(colorCount(disconnected,color,70,30,290,320)==0,"Body readings separated by a minute do not join across the 35-second body gap");}finally{disconnected.recycle();}
        graph.data(84,List.of(sample(0,Double.NaN,Double.NaN,Double.NaN),sample(60,Double.NaN,Double.NaN,Double.NaN)),new ArrayList<>(),false,highest);
        Bitmap missing=draw(graph);
        try{check(colorCount(missing,color,36,30,324,320)==0,"All-missing samples never render a zero or stale aggregate");}finally{missing.recycle();}
    }
    private void smoothingAndSelection(){
        GraphView graph=graph();GraphReadingDetailsView panel=new GraphReadingDetailsView(context);GraphView.ReadingDetails[] reading={null};
        graph.setReadingDetailsListener(value->{reading[0]=value;panel.showReading(value);});
        List<Store.Point> samples=new ArrayList<>();for(int second=0;second<=180;second+=10){Store.Point point=sample(second,second==60?42:34,33,32.5);point.fanSetting=5;samples.add(point);}
        graph.data(87,samples,List.of(),false,1<<GraphView.HIGHEST_BODY_SERIES);window(graph,30,120);selectTime(graph,60);
        check(reading[0].time==START+60000&&reading[0].values.get(6).value.equals("42.0°C"),"Smoothing defaults off and selection reports the original peak");
        List<GraphView.ReadingValue> raw=reading[0].values;double[] rawRange=graph.visibleTemperatureRangeC();double start=graph.visibleStartTime(),end=graph.visibleEndTime();
        graph.setSmoothHighestBody(true);
        check(reading[0].time==START+60000&&reading[0].values.get(6).value.equals("34.6°C"),"The inspected Highest value matches its centered 13-sample average");
        check(reading[0].values.get(6).label.equals("Highest body temp")&&!reading[0].note.contains("2-minute average"),"Smoothing keeps the concise label without adding an always-visible explanation");
        List<CheckBox> controls=new ArrayList<>();checkboxes(panel,controls);controls.sort(java.util.Comparator.comparingInt(control->Integer.parseInt(control.getTag().toString().substring(13))));
        check(controls.get(6).getText().toString().equals("body"),"The integrated readout keeps its concise Highest body temp label");
        for(int series=0;series<raw.size();series++)if(series!=GraphView.HIGHEST_BODY_SERIES)check(raw.get(series).value.equals(reading[0].values.get(series).value),"Smoothing leaves source, room, weather, humidity and fan readouts unchanged");
        double[] smoothedRange=graph.visibleTemperatureRangeC();check(smoothedRange[1]<rawRange[1]&&smoothedRange[1]<37,"Highest-only autoscale follows smoothed values instead of the raw spike");
        check(start==graph.visibleStartTime()&&end==graph.visibleEndTime()&&graph.isZoomed(),"Toggling smoothing preserves the zoom and inspected timestamp");
        for(int i=0;i<samples.size();i++){Store.Point point=samples.get(i);check(point.time==START+i*10000L&&point.probe==(i==6?42:34)&&point.secondProbe==33&&point.thirdProbe==32.5&&point.room==75&&point.humidity==99&&point.fanSetting==5,"Display smoothing never rewrites recorded points");}
        graph.visibleSeries(1);double[] rawOnlyBefore=graph.visibleTemperatureRangeC();graph.setSmoothHighestBody(false);double[] rawOnlyAfter=graph.visibleTemperatureRangeC();
        check(rawOnlyBefore[0]==rawOnlyAfter[0]&&rawOnlyBefore[1]==rawOnlyAfter[1],"A displayed individual probe keeps the same scale when Highest smoothing changes");
        check(reading[0].values.get(6).value.equals("42.0°C")&&controls.get(6).getText().toString().equals("body"),"Unchecking smoothing restores the original Highest value and label");
        graph.visibleSeries(1<<GraphView.HIGHEST_BODY_SERIES);double[] restored=graph.visibleTemperatureRangeC();check(restored[0]==rawRange[0]&&restored[1]==rawRange[1],"Unchecking smoothing exactly restores the raw temperature scale");
        graph.setSmoothHighestBody(true);graph.data(87,samples,List.of(),true,1<<GraphView.HIGHEST_BODY_SERIES);
        check(reading[0].time==START+60000&&reading[0].values.get(6).value.equals("94.3°F")&&reading[0].values.get(0).value.equals("107.6°F"),"Refresh keeps selection and converts smoothed Highest and raw probe values independently");
    }
    private void smoothedMissingAndTimeGaps(){
        GraphView graph=graph();GraphView.ReadingDetails[] reading={null};graph.setReadingDetailsListener(value->reading[0]=value);
        List<Store.Point> samples=List.of(sample(0,34,33,32.5),sample(10,36,33,32.5),sample(20,Double.NaN,Double.NaN,Double.NaN),sample(30,40,33,32.5),sample(40,42,33,32.5),sample(80,33,32.5,32.4),sample(90,35,32.5,32.4));
        graph.data(88,samples,List.of(),false,1<<GraphView.HIGHEST_BODY_SERIES);graph.setSmoothHighestBody(true);
        int[] seconds={0,10,20,30,40,80,90};String[] expected={"35.0°C","35.0°C","—","41.0°C","41.0°C","34.0°C","34.0°C"};
        for(int i=0;i<seconds.length;i++){selectTime(graph,seconds[i]);check(reading[0].values.get(6).value.equals(expected[i]),"Smoothing never averages across missing values or a recording gap");}
        Bitmap image=draw(graph);int color=GraphView.COLORS[6];
        try{check(colorCount(image,color,85,30,115,320)==0,"The smoothed line leaves an all-missing sample gap");check(colorCount(image,color,180,30,270,320)==0,"The smoothed line does not bridge a recording time gap");}
        finally{image.recycle();}
    }
    private void preferenceMigration(){
        String name="highest-body-graph-checks-"+SystemClock.elapsedRealtimeNanos();
        SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        try{
            check(Settings.historySmoothHighestBody(prefs)&&Settings.historySmoothRoom(prefs),"Both smoothing choices default on when not explicitly saved");
            check(prefs.edit().putBoolean("historySmoothHighestBody",true).commit()&&Settings.historySmoothHighestBody(prefs),"The optional smoothing choice persists");
            check(prefs.edit().putBoolean("historySmoothHighestBody",false).commit()&&!Settings.historySmoothHighestBody(prefs),"The original unsmoothed view can be restored persistently");
            check(Settings.historyVisibleSeries(prefs)==376,"Fresh graph defaults show highest body temperature, fan setting and existing non-body defaults");
            for(int old=0;old<64;old++){
                check(prefs.edit().clear().putInt("historyVisibleSeries",old).putString("unrelated","keep").commit(),"Prepare isolated legacy preference fixture");
                int expected=(old&56)|64|256;
                check(Settings.historyVisibleSeries(prefs)==expected,"Existing preferences receive the highest default while preserving weather and humidity choices: "+old);
                check(prefs.getBoolean("historyHighestBodyDefaultApplied",false)&&prefs.getInt("historyVisibleSeries",-1)==expected,"Migration persists both the mask and its one-time marker");
                check(prefs.getString("unrelated","").equals("keep"),"Graph preference migration preserves unrelated preferences");
                int chosen=old;
                check(prefs.edit().putInt("historyVisibleSeries",chosen).commit(),"Prepare a later user-selected mask");
                check(Settings.historyVisibleSeries(prefs)==chosen,"Subsequent launches preserve user choices, including re-enabled source lines and disabled highest line");
            }
            check(prefs.edit().putInt("historyVisibleSeries",1<<GraphView.D2_BODY_SERIES).commit(),"Prepare a user-enabled D2 trace");
            check(Settings.historyVisibleSeries(prefs)==128,"A user-enabled D2 trace persists after the default migration");
        }finally{context.deleteSharedPreferences(name);}
    }
}
