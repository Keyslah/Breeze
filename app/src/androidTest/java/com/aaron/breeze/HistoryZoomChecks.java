package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Off-screen view checks dispatch real touch events without controlling Bluetooth or recording. */
public final class HistoryZoomChecks {
    private final Context context;
    private final float density;
    private long eventTime=SystemClock.uptimeMillis();
    private static final long START=1800000000000L;
    private static final long HOUR=3600000L;

    private HistoryZoomChecks(Context context){this.context=context;density=context.getResources().getDisplayMetrics().density;}
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    private static void close(double actual,double expected,String reason){check(Math.abs(actual-expected)<2,reason+": "+actual+" != "+expected);}
    private int px(float dp){return Math.round(dp*density);}
    private static double span(GraphView graph){return graph.visibleEndTime()-graph.visibleStartTime();}
    private static List<Store.Point> points(long start,long hours){
        List<Store.Point> result=new ArrayList<>();
        for(long time=start;time<=start+hours*HOUR;time+=10000){Store.Point p=new Store.Point(time,32,23,55,0);p.secondProbe=31;result.add(p);}
        return result;
    }
    private GraphView graph(){
        GraphView graph=new GraphView(context);graph.layout(0,0,px(360),px(226));
        graph.data(7,points(START,10),new ArrayList<>(),true,GraphView.ALL_SERIES);return graph;
    }
    private static final class TrackingParent extends FrameLayout {
        boolean blocked,everBlocked;
        TrackingParent(Context context){super(context);}
        @Override public void requestDisallowInterceptTouchEvent(boolean disallow){blocked=disallow;everBlocked|=disallow;super.requestDisallowInterceptTouchEvent(disallow);}
    }
    private void one(View target,long down,long time,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(down,time,action,px(x),px(y),0);
        try{target.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private void two(GraphView graph,long down,long time,int action,float center,float radius,float y){
        MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coordinates={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++){properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i].x=px(center+(i==0?-radius:radius));coordinates[i].y=px(y);coordinates[i].pressure=1;coordinates[i].size=1;}
        MotionEvent event=MotionEvent.obtain(down,time,action,2,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{graph.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private void pinch(GraphView graph,float center){
        long down=eventTime+=800;
        one(graph,down,down,MotionEvent.ACTION_DOWN,center-25,125);
        two(graph,down,down+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),center,25,125);
        for(int step=1;step<=7;step++)two(graph,down,down+20+step*20,MotionEvent.ACTION_MOVE,center,25+step*20,125);
        two(graph,down,down+180,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),center,165,125);
        one(graph,down,down+200,MotionEvent.ACTION_UP,center-165,125);eventTime=down+200;
    }
    private void drag(View target,float x,float y,float toX,float toY){
        long down=eventTime+=800;one(target,down,down,MotionEvent.ACTION_DOWN,x,y);
        // A child first releases a vertical gesture; ScrollView takes over on the
        // next move, resets its last Y, then scrolls on subsequent moves.
        for(int step=1;step<=6;step++)one(target,down,down+step*20,MotionEvent.ACTION_MOVE,x+(toX-x)*step/6,y+(toY-y)*step/6);
        one(target,down,down+140,MotionEvent.ACTION_UP,toX,toY);eventTime=down+140;
    }
    private void tap(GraphView graph,float x,float y){
        long down=eventTime+=800;one(graph,down,down,MotionEvent.ACTION_DOWN,x,y);one(graph,down,down+20,MotionEvent.ACTION_UP,x,y);eventTime=down+20;
    }
    private void doubleTap(GraphView graph){
        long down=eventTime+=800;one(graph,down,down,MotionEvent.ACTION_DOWN,180,125);one(graph,down,down+20,MotionEvent.ACTION_UP,180,125);
        one(graph,down+100,down+100,MotionEvent.ACTION_DOWN,180,125);one(graph,down+100,down+120,MotionEvent.ACTION_UP,180,125);eventTime=down+120;
    }
    private static long field(GraphView graph,String name){
        try{Field field=GraphView.class.getDeclaredField(name);field.setAccessible(true);return ((Number)field.get(graph)).longValue();}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }

    private void pinchPanAndRefresh(){
        GraphView graph=graph();TrackingParent parent=new TrackingParent(context);parent.addView(graph);graph.layout(0,0,px(360),px(226));
        double full=span(graph),center=(graph.visibleStartTime()+graph.visibleEndTime())/2;
        pinch(graph,180);
        double firstSpan=span(graph);int minimumScalingSpan=ViewConfiguration.get(context).getScaledMinimumScalingSpan();
        check(graph.isZoomed()&&firstSpan<full,"pinch must enlarge time detail; span="+firstSpan+", full="+full+", minimumScalingSpan="+minimumScalingSpan+" px, finalFingerSpan="+px(330)+" px");
        close((graph.visibleStartTime()+graph.visibleEndTime())/2,center,"centered pinch preserves the time beneath its focus");
        check(parent.everBlocked&&!parent.blocked,"pinch claims the gesture and releases the parent on finger-up");
        check(field(graph,"selected")==-1,"lifting the last pinch finger cannot select a spurious reading");
        // Android's 27-mm minimum scaling span excludes the beginning of a normal
        // on-screen pinch. Repeated pinches must nevertheless reach deeper detail.
        pinch(graph,180);check(span(graph)<full/2,"two normal pinches must more than double detail; span="+span(graph)+", firstSpan="+firstSpan+", full="+full+", minimumScalingSpan="+minimumScalingSpan+" px");
        double before=graph.visibleStartTime(),duration=span(graph);
        drag(graph,140,125,240,125);check(graph.visibleStartTime()<before,"dragging right reveals earlier readings");close(span(graph),duration,"pan keeps zoom level");
        double start=graph.visibleStartTime(),end=graph.visibleEndTime();
        graph.data(7,points(START,10),new ArrayList<>(),false,0);graph.visibleSeries(GraphView.ALL_SERIES);
        close(graph.visibleStartTime(),start,"same-night fresh data preserves pan");close(graph.visibleEndTime(),end,"units and trace toggles preserve zoom");
        graph.data(7,points(START,11),new ArrayList<>(),true,GraphView.ALL_SERIES);
        close(graph.visibleStartTime(),start,"new recording samples preserve a zoomed start");close(graph.visibleEndTime(),end,"new recording samples preserve a zoomed end");
        drag(graph,180,125,10000,125);close(graph.visibleStartTime(),START,"pan clamps to first reading");
        drag(graph,180,125,-10000,125);close(graph.visibleEndTime(),START+11*HOUR,"pan clamps to last reading");
        tap(graph,180,160);int index=(int)field(graph,"selected");
        long reading=points(START,11).get(index).time;check(Math.abs(reading-(graph.visibleStartTime()+graph.visibleEndTime())/2)<=5000,"tap selection follows the visible window rather than the whole night");
        doubleTap(graph);check(!graph.isZoomed(),"double-tap restores the whole night");close(span(graph),11*HOUR,"reset uses the appended recording range");
        graph.data(7,points(START,12),new ArrayList<>(),true,GraphView.ALL_SERIES);close(span(graph),12*HOUR,"a full-range view expands with new recording samples");
        pinch(graph,180);graph.data(8,points(START,12),new ArrayList<>(),true,GraphView.ALL_SERIES);
        check(!graph.isZoomed(),"different session ID resets even when first timestamps coincide");
        close(graph.visibleStartTime(),START,"session switch returns to the first reading");
        graph.data(8,new ArrayList<>(),new ArrayList<>(),true,GraphView.ALL_SERIES);check(!graph.isZoomed(),"empty dataset clears zoom safely");
        graph.data(8,List.of(new Store.Point(START,32,23,55,0)),new ArrayList<>(),true,GraphView.ALL_SERIES);pinch(graph,180);
        close(span(graph),60000,"single-point data retains a nonzero one-minute window");
    }

    private void pinchEdgesAndMinimum(){
        GraphView graph=graph();pinch(graph,36);close(graph.visibleStartTime(),START,"pinch at left edge retains earliest time");
        graph.resetZoom();pinch(graph,324);close(graph.visibleEndTime(),START+10*HOUR,"pinch at right edge retains latest time");
        graph.resetZoom();for(int i=0;i<32&&span(graph)>60000;i++)pinch(graph,180);
        close(span(graph),60000,"zoom is bounded at one minute and never collapses the axis");
    }

    private ScrollView scrollGraph(GraphView graph){
        ScrollView scroll=new ScrollView(context);LinearLayout column=new LinearLayout(context);column.setOrientation(LinearLayout.VERTICAL);
        column.addView(graph,new LinearLayout.LayoutParams(-1,px(226)));View spacer=new View(context);column.addView(spacer,new LinearLayout.LayoutParams(-1,px(900)));scroll.addView(column);
        int width=View.MeasureSpec.makeMeasureSpec(px(360),View.MeasureSpec.EXACTLY),height=View.MeasureSpec.makeMeasureSpec(px(226),View.MeasureSpec.EXACTLY);
        scroll.measure(width,height);scroll.layout(0,0,px(360),px(226));return scroll;
    }
    private void verticalScroll(){
        GraphView graph=graph();ScrollView scroll=scrollGraph(graph);
        drag(scroll,180,190,180,60);check(scroll.getScrollY()>0,"vertical swipe starting over the graph must still scroll History");
        // Use a separate scroll container so the preceding vertical fling cannot
        // consume the next DOWN just to stop its ongoing animation.
        GraphView horizontal=graph();ScrollView horizontalScroll=scrollGraph(horizontal);pinch(horizontal,180);double before=horizontal.visibleStartTime();
        drag(horizontalScroll,140,125,240,145);
        check(horizontalScroll.getScrollY()==0&&horizontal.visibleStartTime()<before,"mostly-horizontal diagonal pan must stay in the graph rather than scroll History");
    }

    private void subjectiveMarkers(){
        GraphView graph=graph();graph.layout(0,0,px(360),px(346));Store.FeelingEntry hot=new Store.FeelingEntry();hot.id=11;hot.session=7;hot.time=START+5*HOUR;hot.kind=Store.FEELING_HOT;hot.intensity=8;
        Store.FeelingEntry cold=new Store.FeelingEntry();cold.id=12;cold.session=7;cold.time=hot.time;cold.kind=Store.FEELING_COLD;cold.intensity=3;
        graph.feelings(List.of(hot,cold));Bitmap image=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);
        try{graph.draw(new Canvas(image));check(image.getPixel(px(180),Math.round(graph.feelingY(8)))==0xffefa172,"warm marker uses its signed positive intensity");check(image.getPixel(px(180),Math.round(graph.feelingY(-3)))==0xff75c9ed,"cold marker is distinct at the same timestamp and negative intensity");}finally{image.recycle();}
        tap(graph,180,graph.feelingY(8)/density);check(field(graph,"selectedFeeling")==11&&field(graph,"selected")==-1,"hot marker tap selects the subjective entry without relabeling sensor data");
        graph.data(7,points(START,10),new ArrayList<>(),true,GraphView.ALL_SERIES);graph.feelings(List.of(hot,cold));
        check(field(graph,"selectedFeeling")==11,"same-night refresh preserves selected subjective entry");
        tap(graph,180,graph.feelingY(-3)/density);check(field(graph,"selectedFeeling")==12,"cold marker remains independently selectable at the same time");
        pinch(graph,180);double start=graph.visibleStartTime(),end=graph.visibleEndTime();graph.feelings(List.of(hot,cold));
        close(graph.visibleStartTime(),start,"note refresh preserves pan");close(graph.visibleEndTime(),end,"note refresh preserves zoom");
        tap(graph,180,160);check(field(graph,"selectedFeeling")==-1&&field(graph,"selected")>=0,"trace tap returns to measured sensor readings");
        graph.resetZoom();Store.FeelingEntry endCold=new Store.FeelingEntry();endCold.id=cold.id;endCold.session=cold.session;endCold.time=START+10*HOUR+30000;endCold.kind=cold.kind;endCold.intensity=cold.intensity;
        graph.feelings(List.of(hot,endCold));
        close(graph.visibleEndTime(),endCold.time,"note at a session end remains visible after the final sensor sample");
        Store.FeelingEntry gapCold=new Store.FeelingEntry();gapCold.id=endCold.id;gapCold.session=endCold.session;gapCold.time=START+11*HOUR;gapCold.kind=endCold.kind;gapCold.intensity=endCold.intensity;
        graph.feelings(List.of(hot,gapCold));for(int i=0;i<32&&span(graph)>60000;i++)pinch(graph,324);
        check(graph.visibleStartTime()>START+10*HOUR,"fixture reaches a note-only interval beyond the final sensor sample");
        tap(graph,180,160);check(field(graph,"selected")==-1&&field(graph,"selectedFeeling")==-1,"note-only interval cannot inspect an offscreen sensor reading");
        graph.data(9,points(START,10),new ArrayList<>(),true,GraphView.ALL_SERIES);
        close(span(graph),10*HOUR,"switching nights drops the previous night's note range");check(field(graph,"selectedFeeling")==-1,"switching nights clears selected note");
    }

    private void narrowCenter(GraphView graph){
        for(int i=0;i<32&&span(graph)>60000;i++)pinch(graph,180);
        close(span(graph),60000,"temperature range fixture reaches a one-minute visible interval");
    }
    private GraphView temperatureGraph(long night,List<Store.Point> recorded,List<Store.Point> weather,int mask){
        GraphView graph=new GraphView(context);graph.layout(0,0,px(360),px(226));
        graph.data(night,recorded,weather,false,mask);return graph;
    }
    private static double temperatureSpan(GraphView graph){double[] range=graph.visibleTemperatureRangeC();return range[1]-range[0];}
    private static void sameRange(double[] actual,double[] expected,String reason){
        check(Math.abs(actual[0]-expected[0])<0.000001&&Math.abs(actual[1]-expected[1])<0.000001,reason);
    }
    private void visibleTemperatureRange(){
        List<Store.Point> recorded=points(START,10);
        for(Store.Point point:recorded){
            point.probe=32+0.1*Math.sin((point.time-START)*Math.PI/20000);
            point.secondProbe=100;point.room=5;point.humidity=99;
        }
        recorded.get(0).probe=10;recorded.get(recorded.size()-1).probe=40;
        GraphView graph=temperatureGraph(31,recorded,new ArrayList<>(),1);
        double[] full=graph.visibleTemperatureRangeC();
        check(full[0]<=10&&full[1]>=40,"the whole-night temperature range includes enabled nighttime extremes");
        narrowCenter(graph);
        check(graph.visibleStartTime()>START+2*HOUR&&graph.visibleEndTime()<START+8*HOUR,"zoomed fixture excludes distant extremes");
        double[] local=graph.visibleTemperatureRangeC();
        check(local[0]<=31.9&&local[1]>=32.1&&temperatureSpan(graph)<1,"local sub-degree fluctuations receive a narrow visible-time temperature scale");
        Set<String> ticks=new HashSet<>();
        for(int i=0;i<=4;i++)ticks.add(graph.temperatureTickLabel(local[0]+(local[1]-local[0])*i/4));
        check(ticks.size()==5,"narrow temperature scales have distinct decimal tick labels");
        check(graph.temperatureTickLabel((local[0]+local[1])/2).contains("."),"sub-degree Celsius labels retain fractional degrees");
        Bitmap image=Bitmap.createBitmap(px(360),px(226),Bitmap.Config.ARGB_8888);
        try{
            graph.draw(new Canvas(image));int lowest=image.getHeight(),highest=-1;
            for(int y=px(30);y<=px(200);y++)for(int x=px(36);x<=px(324);x++)if(image.getPixel(x,y)==GraphView.COLORS[0]){lowest=Math.min(lowest,y);highest=Math.max(highest,y);}
            check(highest-lowest>=px(45),"a measured 0.2-degree temperature change must draw a visibly varying trace rather than a flat line");
        }finally{image.recycle();}
        graph.visibleSeries(15);double[] combined=graph.visibleTemperatureRangeC();
        check(combined[0]<=5&&combined[1]>=100,"every enabled temperature trace contributes to visible auto-range");
        graph.visibleSeries(1|16);sameRange(graph.visibleTemperatureRangeC(),local,"disabled temperature outliers and enabled humidity do not flatten the body trace");
        double start=graph.visibleStartTime(),end=graph.visibleEndTime();
        graph.data(31,recorded,new ArrayList<>(),true,1);
        sameRange(graph.visibleTemperatureRangeC(),local,"Fahrenheit display preserves the same physical auto-range");
        close(graph.visibleStartTime(),start,"unit conversion preserves the visible-time start");close(graph.visibleEndTime(),end,"unit conversion preserves the visible-time end");
        ticks.clear();for(int i=0;i<=4;i++)ticks.add(graph.temperatureTickLabel(local[0]+(local[1]-local[0])*i/4));
        check(ticks.size()==5&&graph.temperatureTickLabel(32).contains("."),"Fahrenheit tick labels also preserve visible fractional degrees");
        graph.data(31,recorded,new ArrayList<>(),false,1);
        sameRange(graph.visibleTemperatureRangeC(),local,"refreshing the same night preserves the visible temperature range");
        graph.resetZoom();check(temperatureSpan(graph)>30,"Reset zoom restores both whole-night time and temperature detail");
    }
    private List<Store.Point> weather(long duration,double first,double last){
        return List.of(new Store.Point(START,first,Double.NaN,Double.NaN,0),new Store.Point(START+duration,last,Double.NaN,Double.NaN,0));
    }
    private void temperatureBoundaryInterpolation(){
        List<Store.Point> recorded=points(START,1);List<Store.Point> hourly=weather(HOUR,0,10);
        GraphView graph=temperatureGraph(32,recorded,hourly,1<<3);narrowCenter(graph);
        check(hourly.get(0).time<graph.visibleStartTime()&&hourly.get(1).time>graph.visibleEndTime(),"hourly boundary fixture has no actual samples inside the viewport");
        double[] range=graph.visibleTemperatureRangeC();
        double first=10*(graph.visibleStartTime()-START)/HOUR,last=10*(graph.visibleEndTime()-START)/HOUR;
        check(range[0]<=first&&range[1]>=last&&range[0]>4&&range[1]<6,"visible auto-range includes interpolated hourly boundaries without including offscreen endpoints");
        check(temperatureSpan(graph)<1,"an hourly trace crossing a one-minute window receives a useful sub-degree range");
        GraphView missing=temperatureGraph(33,points(START,2),new ArrayList<>(),1<<3);narrowCenter(missing);
        GraphView disconnected=temperatureGraph(34,points(START,2),weather(2*HOUR,0,10),1<<3);narrowCenter(disconnected);
        sameRange(disconnected.visibleTemperatureRangeC(),missing.visibleTemperatureRangeC(),"outdoor segments separated by more than the rendering gap cannot contribute invented interpolated temperatures");
        GraphView noHourly=temperatureGraph(35,recorded,new ArrayList<>(),1<<3);narrowCenter(noHourly);
        GraphView invalid=temperatureGraph(36,recorded,weather(HOUR,0,Double.NaN),1<<3);narrowCenter(invalid);
        sameRange(invalid.visibleTemperatureRangeC(),noHourly.visibleTemperatureRangeC(),"NaN boundary endpoints cannot contribute invented interpolated temperatures");
    }

    public static void run(Instrumentation test){
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{HistoryZoomChecks checks=new HistoryZoomChecks(test.getTargetContext());checks.pinchPanAndRefresh();checks.pinchEdgesAndMinimum();checks.verticalScroll();checks.subjectiveMarkers();checks.visibleTemperatureRange();checks.temperatureBoundaryInterpolation();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("History gesture check failed",failure[0]);
    }
}
