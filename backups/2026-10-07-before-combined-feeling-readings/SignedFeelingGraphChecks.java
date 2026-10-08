package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Off-screen signed-rating rendering and timestamp checks; no database, microphone or controller. */
public final class SignedFeelingGraphChecks {
    private static final long START=1800000000000L,MINUTE=60000L;
    private static final int WARM=0xffefa172,COLD=0xff75c9ed;
    private final Context context;
    private final float density;
    private long eventTime=SystemClock.uptimeMillis();
    private SignedFeelingGraphChecks(Context context){this.context=context;density=context.getResources().getDisplayMetrics().density;}
    public static String run(Instrumentation test){
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{SignedFeelingGraphChecks checks=new SignedFeelingGraphChecks(test.getTargetContext());checks.signedPositions();checks.feelingsOnly();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Signed feeling graph check failed",failure[0]);
        return "PASS: signed feeling marker heights, irregular actual-time spacing, fixed neutral scale, unchanged temperature scale, feelings-only selection/zoom/refresh and night isolation\n";
    }
    private int px(float dp){return Math.round(dp*density);}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static void close(double actual,double expected,String message){check(Math.abs(actual-expected)<2,message+": "+actual+" != "+expected);}
    private GraphView graph(){GraphView graph=new GraphView(context);graph.layout(0,0,px(360),px(430));return graph;}
    private static Store.FeelingEntry entry(long id,long minutes,int signed){
        Store.FeelingEntry entry=new Store.FeelingEntry();entry.id=id;entry.session=71;entry.time=START+minutes*MINUTE;
        entry.state=signed>0?"WARM":"COLD";entry.kind=signed>0?Store.FEELING_HOT:Store.FEELING_COLD;
        entry.intensity=Math.abs(signed);entry.signedValue=signed;return entry;
    }
    private void signedPositions(){
        GraphView graph=graph();List<Store.Point> samples=List.of(new Store.Point(START,32,23,55,0),new Store.Point(START+60*MINUTE,33,24,60,0));
        graph.data(71,samples,new ArrayList<>(),false,GraphView.ALL_SERIES);double[] before=graph.visibleTemperatureRangeC();
        Store.FeelingEntry warm=entry(1,10,7),cold=entry(2,20,-3),later=entry(3,50,2);
        graph.feelings(List.of(warm,cold,later,entry(4,5,10),entry(5,55,-10)));
        double[] after=graph.visibleTemperatureRangeC();check(Math.abs(after[0]-before[0])<0.000001,"Feelings cannot change sensor minimum");check(Math.abs(after[1]-before[1])<0.000001,"Feelings cannot change sensor maximum");
        Bitmap bitmap=Bitmap.createBitmap(px(360),px(430),Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff20352b);graph.draw(canvas);
            try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),"signed-feeling-graph.png"))){
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Save signed feeling graph preview");
            }catch(IOException failure){throw new AssertionError("Could not save signed feeling graph preview",failure);}
            // Independent expected pixels: 36..324 dp = one hour, and 240..320 dp = +10..-10.
            check(bitmap.getPixel(px(84),px(252))==WARM,"Warm seven is at its timestamp and +7 height");
            check(bitmap.getPixel(px(132),px(292))==COLD,"Cold three is at its timestamp and -3 height");
            check(bitmap.getPixel(px(276),px(272))==WARM,"A thirty-minute gap renders three times wider than a ten-minute gap");
            check(bitmap.getPixel(px(60),px(240))==WARM,"Warm ten reaches +10 without altering sensor scale");
            check(bitmap.getPixel(px(300),px(320))==COLD,"Cold ten reaches -10 without clipping its center");
            check(bitmap.getPixel(px(210),px(280))==0xffa6b9ac,"A fixed zero neutral baseline separates warm and cold");
        }finally{bitmap.recycle();}
        tap(graph,graph.feelingX(cold.time),graph.feelingY(-3));check(selected(graph)==cold.id,"Signed cold marker remains individually selectable");
        graph.visibleSeries(0);close(graph.feelingY(0),px(280),"Sensor toggles do not rescale the feeling axis");
        GraphReadingDetailsView details=new GraphReadingDetailsView(context);GraphView.ReadingDetails[] inspected={null};
        graph.setReadingDetailsListener(reading->{inspected[0]=reading;details.showReading(reading);});
        graph.visibleSeries(GraphView.ALL_SERIES);tap(graph,px(48),px(100));
        check(inspected[0]!=null&&inspected[0].values.size()==6&&inspected[0].values.get(0).label.equals("D0 body")
                &&inspected[0].values.get(0).value.equals("32.0°C")&&inspected[0].values.get(4).value.equals("55.0%"),"Native selected-reading callback carries sensor labels and values with units");
        details.measure(View.MeasureSpec.makeMeasureSpec(px(320),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        details.layout(0,0,details.getMeasuredWidth(),details.getMeasuredHeight());
        Bitmap readout=Bitmap.createBitmap(details.getMeasuredWidth(),details.getMeasuredHeight(),Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(readout);canvas.drawColor(0xff20352b);details.draw(canvas);
            try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),"graph-reading-details.png"))){check(readout.compress(Bitmap.CompressFormat.PNG,100,output),"Save readable graph details preview");}
            catch(IOException failure){throw new AssertionError("Could not save readable graph details preview",failure);}
        }finally{readout.recycle();}
    }
    private void feelingsOnly(){
        GraphView graph=graph();Store.FeelingEntry first=entry(10,10,1),middle=entry(11,30,-4),last=entry(12,70,10);
        List<Store.FeelingEntry> entries=List.of(first,middle,last);
        graph.data(71,new ArrayList<>(),new ArrayList<>(),true,GraphView.ALL_SERIES);graph.feelings(entries);
        close(graph.visibleStartTime(),first.time,"A feelings-only night begins at its first real timestamp, not epoch zero");
        close(graph.visibleEndTime(),last.time,"A feelings-only night ends at its last real timestamp");
        Bitmap bitmap=Bitmap.createBitmap(px(360),px(430),Bitmap.Config.ARGB_8888);
        try{graph.draw(new Canvas(bitmap));check(bitmap.getPixel(px(132),px(296))==COLD,"Feelings render before sensor records arrive");}finally{bitmap.recycle();}
        tap(graph,graph.feelingX(middle.time),graph.feelingY(-4));check(selected(graph)==middle.id,"Feelings-only marker inspection does not require a sensor point");
        pinch(graph);check(graph.isZoomed(),"Feelings-only timeline supports real pinch gestures");
        double start=graph.visibleStartTime(),end=graph.visibleEndTime();
        graph.data(71,new ArrayList<>(),new ArrayList<>(),true,0);graph.feelings(entries);
        close(graph.visibleStartTime(),start,"Empty sensor refresh preserves feelings-only pan");close(graph.visibleEndTime(),end,"Empty sensor refresh preserves feelings-only zoom");
        graph.resetZoom();close(graph.visibleStartTime(),first.time,"Reset restores earliest feeling");close(graph.visibleEndTime(),last.time,"Reset restores latest feeling");
        Store.FeelingEntry nextNight=entry(13,70,10);nextNight.session=72;
        graph.data(72,new ArrayList<>(),new ArrayList<>(),true,GraphView.ALL_SERIES);graph.feelings(List.of(nextNight));
        close(graph.visibleStartTime(),last.time,"Changing nights discards the prior feeling range");close(graph.visibleEndTime()-graph.visibleStartTime(),MINUTE,"Single feeling receives a safe one-minute window");
        graph.feelings(new ArrayList<>());check(!graph.isZoomed(),"Removing the last feeling clears the window safely");
    }
    private static long selected(GraphView graph){try{Field field=GraphView.class.getDeclaredField("selectedFeeling");field.setAccessible(true);return field.getLong(graph);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    private void tap(GraphView graph,float x,float y){long down=eventTime+=800;one(graph,down,down,MotionEvent.ACTION_DOWN,x,y);one(graph,down,down+20,MotionEvent.ACTION_UP,x,y);eventTime=down+20;}
    private void one(GraphView graph,long down,long time,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);try{graph.dispatchTouchEvent(event);}finally{event.recycle();}}
    private void two(GraphView graph,long down,long time,int action,float radius){
        MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};MotionEvent.PointerCoords[] coordinates={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++){properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i].x=px(180+(i==0?-radius:radius));coordinates[i].y=px(125);coordinates[i].pressure=1;coordinates[i].size=1;}
        MotionEvent event=MotionEvent.obtain(down,time,action,2,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{graph.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private void pinch(GraphView graph){
        long down=eventTime+=800;one(graph,down,down,MotionEvent.ACTION_DOWN,px(155),px(125));two(graph,down,down+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),25);
        for(int step=1;step<=7;step++)two(graph,down,down+20+step*20,MotionEvent.ACTION_MOVE,25+step*20);
        two(graph,down,down+180,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),165);one(graph,down,down+200,MotionEvent.ACTION_UP,px(15),px(125));eventTime=down+200;
    }
}
