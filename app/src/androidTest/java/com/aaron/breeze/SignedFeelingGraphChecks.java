package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
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
        test.runOnMainSync(()->{try{SignedFeelingGraphChecks checks=new SignedFeelingGraphChecks(test.getTargetContext());checks.signedPositions();checks.feelingWithReadings();checks.feelingsOnly();checks.integratedLegend();HighestBodyGraphChecks.run(test.getTargetContext());FanSettingGraphChecks.run(test.getTargetContext());}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Signed feeling graph check failed",failure[0]);
        return "PASS: signed feeling marker heights, actual-time spacing, fixed scale, compact feeling with nearest finite sensor readings, stale gaps excluded, feelings-only selection/zoom/refresh, stable integrated trace checkboxes, night isolation, highest body temperature values/rendering/gaps/units/defaults; stepped fan-setting overlay on the same plot, unchanged temperature/humidity plot pixels, Off/Unknown readouts, gaps, viewport clipping, historical feeling lookup, temperature/feeling scale isolation and one-time preference migrations\n";
    }
    private int px(float dp){return Math.round(dp*density);}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static void close(double actual,double expected,String message){check(Math.abs(actual-expected)<2,message+": "+actual+" != "+expected);}
    private GraphView graph(){GraphView graph=new GraphView(context);graph.layout(0,0,px(360),px(346));return graph;}
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
        Bitmap bitmap=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);
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
        check(inspected[0]!=null&&inspected[0].time==START&&inspected[0].values.size()==9&&inspected[0].values.get(0).label.equals("D0 body")
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
    private static Store.Point sample(long time,double body,double second,double room,double humidity){Store.Point point=new Store.Point(time,body,room,humidity,0);point.secondProbe=second;return point;}
    private void feelingWithReadings(){
        GraphView graph=graph();Store.FeelingEntry warm=entry(20,30,7);warm.time+=12345;long time=warm.time;
        List<Store.Point> sensors=List.of(sample(time-60000,25,24,22.2,54.4),sample(time-20000,31.1,Double.NaN,Double.NaN,Double.NaN),
                sample(time+10000,Double.NaN,30.2,23.3,Double.NaN),sample(time+100000,33,32,24,56));
        sensors.get(2).thirdProbe=29.4;
        List<Store.Point> weather=List.of(sample(time-3600000,12.4,Double.NaN,Double.NaN,Double.NaN),sample(time+1800000,Double.NaN,Double.NaN,Double.NaN,67.8));
        GraphView.ReadingDetails[] inspected={null};GraphReadingDetailsView panel=new GraphReadingDetailsView(context);
        graph.setReadingDetailsListener(reading->{inspected[0]=reading;panel.showReading(reading);});
        graph.data(71,sensors,weather,false,GraphView.ALL_SERIES);graph.feelings(List.of(warm));
        tap(graph,graph.feelingX(time),graph.feelingY(7));
        GraphView.ReadingDetails details=inspected[0];check(details!=null&&details.feeling!=null&&details.feeling.label.equals("Feeling")&&details.feeling.value.equals("Warm 7 / 10"),"A selected feeling has exactly one compact feeling field");
        String[] expected={"31.1°C","30.2°C","23.3°C","12.4°C","54.4%","67.8%","30.2°C","29.4°C","Unknown"};
        check(details.values.size()==9,"Selecting a feeling retains all sensor readouts and the highest value from one nearby recorded sample");
        for(int i=0;i<expected.length;i++)check(details.values.get(i).label.equals(GraphView.LABELS[i])&&details.values.get(i).value.equals(expected[i]),"Feeling-time nearest finite series "+i+" must be "+expected[i]);
        check(details.time==time&&details.timestamp.equals(new java.text.SimpleDateFormat("MMM d, yyyy · h:mm:ss a",java.util.Locale.US).format(new java.util.Date(time))),"Header receives the exact feeling time rather than a nearby sensor timestamp");
        graph.data(71,sensors,weather,false,GraphView.ALL_SERIES);graph.feelings(List.of(warm));
        check(selected(graph)==warm.id&&inspected[0].feeling!=null&&inspected[0].values.get(0).value.equals("31.1°C"),"Combined selected feeling and sensor values survive refresh");
        panel.measure(View.MeasureSpec.makeMeasureSpec(px(320),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));panel.layout(0,0,panel.getMeasuredWidth(),panel.getMeasuredHeight());
        Bitmap image=Bitmap.createBitmap(panel.getMeasuredWidth(),panel.getMeasuredHeight(),Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(image);canvas.drawColor(0xff20352b);panel.draw(canvas);
            try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),"feeling-with-readings.png"))){check(image.compress(Bitmap.CompressFormat.PNG,100,output),"Save combined feeling and readings preview");}
            catch(IOException failure){throw new AssertionError("Could not save combined feeling and readings preview",failure);}
        }finally{image.recycle();}
        graph.visibleSeries((1<<0)|(1<<1)|(1<<4));check(inspected[0].values.size()==9&&inspected[0].feeling!=null&&visible(graph)==19,"Trace filtering preserves all nine combined readouts");
        graph.visibleSeries(GraphView.ALL_SERIES);Store.FeelingEntry distant=entry(21,210,-3);distant.time=time+180*MINUTE;graph.feelings(List.of(warm,distant));
        tap(graph,graph.feelingX(distant.time),graph.feelingY(-3));check(inspected[0].feeling.value.equals("Cold 3 / 10"),"Distant cold selection retains its own rating");
        for(GraphView.ReadingValue value:inspected[0].values)check(value.value.equals(value.label.equals("Fan setting")?"Unknown":"—"),"No distant sensor or weather sample may masquerade as a nearby reading");
    }
    private void feelingsOnly(){
        GraphView graph=graph();Store.FeelingEntry first=entry(10,10,1),middle=entry(11,30,-4),last=entry(12,70,10);
        GraphView.ReadingDetails[] inspected={null};graph.setReadingDetailsListener(reading->inspected[0]=reading);
        List<Store.FeelingEntry> entries=List.of(first,middle,last);
        graph.data(71,new ArrayList<>(),new ArrayList<>(),true,GraphView.ALL_SERIES);graph.feelings(entries);
        close(graph.visibleStartTime(),first.time,"A feelings-only night begins at its first real timestamp, not epoch zero");
        close(graph.visibleEndTime(),last.time,"A feelings-only night ends at its last real timestamp");
        Bitmap bitmap=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);
        try{graph.draw(new Canvas(bitmap));check(bitmap.getPixel(px(132),px(296))==COLD,"Feelings render before sensor records arrive");}finally{bitmap.recycle();}
        tap(graph,graph.feelingX(middle.time),graph.feelingY(-4));check(selected(graph)==middle.id,"Feelings-only marker inspection does not require a sensor point");
        check(inspected[0]!=null&&inspected[0].feeling!=null&&inspected[0].values.size()==9,"A feelings-only night still has a compact rating and all readout slots");
        for(GraphView.ReadingValue value:inspected[0].values)check(value.value.equals(value.label.equals("Fan setting")?"Unknown":"—"),"Missing sensor records remain missing in feeling details");
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
    private void integratedLegend(){
        GraphView graph=graph();GraphReadingDetailsView panel=new GraphReadingDetailsView(context);
        GraphView.ReadingDetails[] inspected={null};int[] mask={GraphView.ALL_SERIES},callbacks={0};
        panel.setOnSeriesChangedListener((series,checked)->{callbacks[0]++;mask[0]=checked?mask[0]|(1<<series):mask[0]&~(1<<series);panel.setVisibleSeries(mask[0]);graph.visibleSeries(mask[0]);});
        List<CheckBox> controls=new ArrayList<>();checkboxes(panel,controls);
        check(controls.size()==9&&panel.getVisibility()==View.VISIBLE,"All nine trace controls are present before the first graph selection");
        panel.setVisibleSeries(0);panel.showReading(null);check(callbacks[0]==0,"Programmatic mask and empty refresh do not fire user callbacks");
        for(CheckBox control:controls){check(!control.isChecked()&&control.isEnabled(),"An unchecked trace stays available");TextView value=(TextView)((ViewGroup)control.getParent()).getChildAt(1);check(value.getText().toString().equals(control.getText().toString().equals("Fan setting")?"Unknown":"—"),"Empty readings keep their controls and a dash");}
        panel.setVisibleSeries(GraphView.ALL_SERIES);graph.setReadingDetailsListener(reading->{inspected[0]=reading;panel.showReading(reading);});
        List<Store.Point> sensors=List.of(sample(START,32,31,23,55),sample(START+MINUTE,33,31,24,56));
        List<Store.Point> weather=List.of(sample(START,100,Double.NaN,Double.NaN,70),sample(START+MINUTE,100,Double.NaN,Double.NaN,71));
        graph.data(71,sensors,weather,false,GraphView.ALL_SERIES);tap(graph,px(48),px(100));
        double allMaximum=graph.visibleTemperatureRangeC()[1];
        panel.measure(View.MeasureSpec.makeMeasureSpec(px(372),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));panel.layout(0,0,panel.getMeasuredWidth(),panel.getMeasuredHeight());
        if(context.getResources().getConfiguration().fontScale<=1.05f)check(((ViewGroup)panel.getChildAt(0)).getChildCount()==5,"Nine readouts fit five rows at the phone content width");
        for(CheckBox control:controls)check(control.getHeight()>=px(48),"Each checkbox has a 48dp touch row");
        controls.get(3).performClick();
        check(callbacks[0]==1&&!controls.get(3).isChecked()&&visible(graph)==(GraphView.ALL_SERIES&~(1<<3)),"Unchecking updates the plotted mask once");
        check(graph.visibleTemperatureRangeC()[1]<40&&allMaximum>100,"The unchecked outdoor trace no longer affects the temperature scale");
        check(inspected[0].values.size()==9&&inspected[0].values.get(3).value.equals("100.0°C"),"An unchecked trace retains its actual large reading");
        graph.data(71,sensors,weather,false,mask[0]);List<CheckBox> refreshed=new ArrayList<>();checkboxes(panel,refreshed);
        check(controls.equals(refreshed)&&callbacks[0]==1,"Refresh preserves each checkbox instance and does not toggle it");
        controls.get(3).performClick();
        check(callbacks[0]==2&&controls.get(3).isChecked()&&visible(graph)==GraphView.ALL_SERIES,"The same unchecked control turns its trace back on");
        panel.setVisibleSeries(0);graph.visibleSeries(0);check(inspected[0].values.size()==9&&callbacks[0]==2,"All traces can be hidden without removing any readout or firing sync callbacks");
        panel.showReading(null);refreshed.clear();checkboxes(panel,refreshed);check(controls.equals(refreshed)&&panel.getVisibility()==View.VISIBLE,"Clearing selection preserves all nine interactive controls");
    }
    private static void checkboxes(View view,List<CheckBox> result){if(view instanceof CheckBox)result.add((CheckBox)view);else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int at=0;at<group.getChildCount();at++)checkboxes(group.getChildAt(at),result);}}
    private static int visible(GraphView graph){try{Field field=GraphView.class.getDeclaredField("visible");field.setAccessible(true);return field.getInt(graph);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
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
