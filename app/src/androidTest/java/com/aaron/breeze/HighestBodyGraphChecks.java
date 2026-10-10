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
        checks.valuesAndReadouts();checks.renderingAndGaps();checks.preferenceMigration();
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
    private static void checkboxes(View view,List<CheckBox> result){
        if(view instanceof CheckBox)result.add((CheckBox)view);
        else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)checkboxes(group.getChildAt(i),result);}
    }
    private void valuesAndReadouts(){
        GraphView graph=graph();GraphReadingDetailsView panel=new GraphReadingDetailsView(context);
        GraphView.ReadingDetails[] reading={null};graph.setReadingDetailsListener(value->{reading[0]=value;panel.showReading(value);});
        List<CheckBox> controls=new ArrayList<>();checkboxes(panel,controls);
        check(controls.size()==9,"Highest body temperature, D2 and fan setting retain every earlier source reading");
        for(int i=0;i<9;i++)check(controls.get(i).isChecked()==((i>=3&&i<=6)||i==GraphView.FAN_SETTING_SERIES),"Graph defaults hide D0, D1 and D2 while retaining the existing non-body choices and showing fan setting");
        List<Store.Point> samples=List.of(sample(0,34,31,30),sample(10,30,35,31),sample(20,30,31,36),
                sample(30,Double.NaN,Double.POSITIVE_INFINITY,37),sample(40,-5,-3,Double.NEGATIVE_INFINITY),
                sample(50,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY),sample(60,32,31,30));
        int highest=1<<GraphView.HIGHEST_BODY_SERIES;
        graph.data(81,samples,List.of(sample(0,100,Double.NaN,Double.NaN)),false,highest);
        String[] expected={"34.0°C","35.0°C","36.0°C","37.0°C","-3.0°C","—","32.0°C"};
        for(int i=0;i<expected.length;i++){
            select(graph,i*10);
            check(reading[0]!=null&&reading[0].values.get(6).value.equals(expected[i]),"Highest temperature uses the finite maximum at the selected timestamp: "+expected[i]);
        }
        select(graph,0);
        int[] bodySeries={0,1,GraphView.D2_BODY_SERIES};
        for(int i=0;i<bodySeries.length;i++){
            int series=bodySeries[i];String value=reading[0].values.get(series).value;
            check(value.equals(new String[]{"34.0°C","31.0°C","30.0°C"}[i]),"Unchecked source temperature retains its numerical reading");
            check(!controls.get(series).isChecked()&&((TextView)((ViewGroup)controls.get(series).getParent()).getChildAt(1)).getText().toString().equals(value),"Unchecked source control stays visible alongside its actual value");
        }
        check(reading[0].values.get(2).value.equals("75.0°C"),"The room reading remains independent even when hotter than every body probe");
        double[] range=graph.visibleTemperatureRangeC();
        check(range[0]<-3&&range[1]>37&&range[1]<50,"Highest-only temperature range excludes hot room/outdoor temperatures and humidity");
        graph.data(81,samples,new ArrayList<>(),true,highest);select(graph,0);
        check(reading[0].values.get(6).value.equals("93.2°F"),"Aggregate readout converts Celsius to Fahrenheit, never percent");
        check(reading[0].values.get(GraphView.D2_BODY_SERIES).value.equals("86.0°F"),"D2 readout converts as a temperature rather than humidity");
        double[] fahrenheitRange=graph.visibleTemperatureRangeC();
        check(Math.abs(range[0]-fahrenheitRange[0])<0.000001&&Math.abs(range[1]-fahrenheitRange[1])<0.000001,"Temperature unit conversion leaves the physical aggregate range unchanged");
        graph.visibleSeries(0);
        check(reading[0].values.get(6).value.equals("93.2°F")&&reading[0].values.get(0).value.equals("93.2°F"),"Hiding every trace retains aggregate and individual readouts");
        Store.Point legacy=new Store.Point(START,31,75,55,0);legacy.secondProbe=32;
        graph.data(85,List.of(legacy),new ArrayList<>(),false,highest);select(graph,0);
        check(reading[0].values.get(6).value.equals("32.0°C")&&reading[0].values.get(GraphView.D2_BODY_SERIES).value.equals("—"),"Legacy recordings lacking D2 use D0/D1 without substituting the room temperature");
        legacy.probe=Double.NaN;legacy.secondProbe=Double.NaN;graph.data(85,List.of(legacy),new ArrayList<>(),false,highest);select(graph,0);
        check(reading[0].values.get(6).value.equals("—")&&reading[0].values.get(2).value.equals("75.0°C"),"Missing body probes never fall back to a valid room reading");
    }
    private Bitmap draw(GraphView graph){Bitmap result=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);graph.draw(new Canvas(result));return result;}
    private int colorCount(Bitmap image,int color,int left,int top,int right,int bottom){
        int count=0;for(int y=px(top);y<px(bottom);y++)for(int x=px(left);x<px(right);x++)if(image.getPixel(x,y)==color)count++;return count;
    }
    private void renderingAndGaps(){
        int highest=1<<GraphView.HIGHEST_BODY_SERIES,color=GraphView.COLORS[GraphView.HIGHEST_BODY_SERIES];
        GraphView graph=graph();List<Store.Point> samples=List.of(sample(0,31,30,29),sample(10,31,32,29),sample(20,31,32,33),
                sample(30,Double.NaN,Double.NaN,Double.NaN),sample(40,31,32,33),sample(50,31,32,29),sample(60,31,30,29));
        graph.data(82,samples,new ArrayList<>(),false,highest);
        double[] range=graph.visibleTemperatureRangeC();
        check(range[0]>30&&range[0]<31&&range[1]>33&&range[1]<34,"Aggregate-only auto-range uses each recorded maximum, excluding lower source values");
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
        graph.data(83,List.of(sample(0,Double.NaN,Double.NaN,30),sample(30,Double.NaN,Double.NaN,32),sample(60,Double.NaN,Double.NaN,30)),new ArrayList<>(),false,highest);
        Bitmap historical=draw(graph);
        try{check(colorCount(historical,color,70,30,100,320)>0,"Historical 30-second body readings connect in the highest trace");}finally{historical.recycle();}
        graph.visibleSeries(1<<GraphView.D2_BODY_SERIES);Bitmap third=draw(graph);
        try{check(colorCount(third,GraphView.COLORS[GraphView.D2_BODY_SERIES],70,30,100,320)>0,"D2 can be enabled as an independent temperature trace");}finally{third.recycle();}
        graph.data(86,List.of(sample(0,Double.NaN,Double.NaN,30),sample(60,Double.NaN,Double.NaN,32)),new ArrayList<>(),false,highest);
        Bitmap disconnected=draw(graph);
        try{check(colorCount(disconnected,color,70,30,290,320)==0,"Body readings separated by a minute do not join across the 35-second body gap");}finally{disconnected.recycle();}
        graph.data(84,List.of(sample(0,Double.NaN,Double.NaN,Double.NaN),sample(60,Double.NaN,Double.NaN,Double.NaN)),new ArrayList<>(),false,highest);
        Bitmap missing=draw(graph);
        try{check(colorCount(missing,color,36,30,324,320)==0,"All-missing samples never render a zero or stale aggregate");}finally{missing.recycle();}
    }
    private void preferenceMigration(){
        String name="highest-body-graph-checks-"+SystemClock.elapsedRealtimeNanos();
        SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        try{
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
