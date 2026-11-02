package com.aaron.breeze;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class GraphView extends View {
    public static final int HIGHEST_BODY_SERIES=6,D2_BODY_SERIES=7,FAN_SETTING_SERIES=8,DREO_TEMPERATURE_SERIES=9;
    public static final int ALL_SERIES=1023,DEFAULT_SERIES=120|(1<<FAN_SETTING_SERIES);
    private static final int BODY_TEMPERATURE_SERIES=3|(1<<HIGHEST_BODY_SERIES)|(1<<D2_BODY_SERIES);
    private static final int AMBIENT_TEMPERATURE_SERIES=12|(1<<DREO_TEMPERATURE_SERIES);
    static final long DREO_MAX_GAP_MS=240000;
    static final double MIN_BODY_TEMPERATURE_C=(90d-32)/1.8;
    public static final String[] LABELS={"D0 body","D1 body","Room temp","Outdoor temp","Humidity","Outdoor humidity","Highest body temp","D2 body","Fan setting","Dreo fan temp"};
    public static final int[] COLORS={0xff9ac5a4,0xffef9393,0xffe0b04f,0xff78a9ed,0xffc69cf0,0xff5ad3d0,0xffffb86b,0xffd6b7ee,0xff79d5c3,0xffe998ca};
    private static final int MUTED=0xffa6b9ac,INK=0xffe5eee6;
    private List<Store.Point> points=new ArrayList<>(),outdoor=new ArrayList<>(),dreoTemperatures=new ArrayList<>();
    private List<Store.FeelingEntry> feelings=new ArrayList<>();
    private final Paint paint=new Paint(3);
    private boolean fahrenheit=true;
    private boolean smoothHighestBody;
    private HighestBodySmoother.Result smoothedHighestBody;
    private int selected=-1,visible=DEFAULT_SERIES;
    private static final double MIN_WINDOW_MS=60000;
    private long sessionId=Long.MIN_VALUE;
    private double windowStart,windowEnd;
    private boolean hasWindow,pinchGesture;
    private final ScaleGestureDetector scale;
    private final GestureDetector taps;
    private final int touchSlop;
    private float downX,downY,lastX;
    private int dragAxis;
    private double scaleAnchor;
    private long selectedFeeling=-1;
    public interface ReadingDetailsListener { void onReadingSelected(ReadingDetails details); }
    public static final class ReadingValue {
        public final String label,value;public final int color;
        public ReadingValue(String label,String value,int color){this.label=label;this.value=value;this.color=color;}
    }
    public static final class ReadingDetails {
        public final long time;
        public final String timestamp,note;public final List<ReadingValue> values;
        public final ReadingValue feeling;
        public ReadingDetails(long time,List<ReadingValue> values,String note){this(time,values,note,null);}
        public ReadingDetails(long time,List<ReadingValue> values,String note,ReadingValue feeling){this.time=time;this.timestamp=new SimpleDateFormat("MMM d, yyyy · h:mm:ss a",Locale.US).format(new Date(time));this.values=List.copyOf(values);this.note=note;this.feeling=feeling;}
    }
    private ReadingDetailsListener readingDetailsListener;
    public void setReadingDetailsListener(ReadingDetailsListener listener){readingDetailsListener=listener;notifyReadingDetails();}
    public GraphView(Context c){
        super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setContentDescription("Recorded body temperatures use the right axis. Room, outdoor and optional Dreo fan temperatures use an independent ambient temperature axis on the left. Fan setting maps Off at the bottom to H at the top; humidity maps 0 percent at the bottom to 100 percent at the top. These scales do not change the body temperature range. All readings share actual timestamps. Pinch to zoom, drag sideways to pan, tap to inspect a reading, or double-tap to show the whole night.");
        touchSlop=ViewConfiguration.get(c).getScaledTouchSlop();
        scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){
                pinchGesture=true;disallowParent(true);scaleAnchor=timeAt(detector.getFocusX());return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector){
                float factor=detector.getScaleFactor();if(!Float.isFinite(factor)||factor<=0)return false;
                double span=Math.max(Math.min(MIN_WINDOW_MS,fullSpan()),Math.min(fullSpan(),windowSpan()/factor));
                setWindow(scaleAnchor-ratioAt(detector.getFocusX())*span,span);return true;
            }
        });
        // Quick-scale turns a double tap into a one-finger zoom and conflicts with reset.
        scale.setQuickScaleEnabled(false);
        taps=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public boolean onSingleTapUp(MotionEvent event){selectAt(event.getX(),event.getY());performClick();return true;}
            @Override public boolean onDoubleTap(MotionEvent event){resetZoom();return true;}
        });
    }
    public void data(List<Store.Point> p,List<Store.Point> o,boolean f,int mask){data(p.isEmpty()?Long.MIN_VALUE:p.get(0).time,p,o,f,mask);}
    public void data(long night,List<Store.Point> p,List<Store.Point> o,boolean f,int mask){
        boolean changed=night!=sessionId,wasFull=!hasWindow||!isZoomed();
        long inspected=selected>=0&&selected<points.size()?points.get(selected).time:Long.MIN_VALUE;
        double oldStart=windowStart,oldSpan=windowSpan();
        points=p;outdoor=o;fahrenheit=f;visible=mask&ALL_SERIES;sessionId=night;refreshHighestBodySmoothing();
        if(changed){feelings=new ArrayList<>();dreoTemperatures=new ArrayList<>();selectedFeeling=-1;}
        if(!hasTimeline()){hasWindow=false;selected=-1;}
        else if(changed||!hasWindow){selected=-1;resetZoom();}
        else{
            if(wasFull)setWindow(fullStart(),fullSpan());else setWindow(oldStart,oldSpan);
            selected=inspected==Long.MIN_VALUE?-1:nearestIndex(inspected);
            if(selected>=0&&(points.get(selected).time<windowStart||points.get(selected).time>windowEnd))selected=-1;
        }
        notifyReadingDetails();invalidate();
    }
    /** Supply the selected night's sparse cloud observations after data(); never invent older readings. */
    public void dreoTemperatures(List<Store.Point> entries){dreoTemperatures=entries;notifyReadingDetails();invalidate();}
    public void resetZoom(){if(hasTimeline()){hasWindow=true;setWindow(fullStart(),fullSpan());}invalidate();}
    public void feelings(List<Store.FeelingEntry> entries){
        boolean wasFull=!hasWindow||!isZoomed();double oldStart=windowStart,oldSpan=windowSpan();
        feelings=entries;boolean found=false;for(Store.FeelingEntry entry:entries)if(entry.id==selectedFeeling)found=true;
        if(!found)selectedFeeling=-1;
        if(!hasTimeline()){hasWindow=false;selected=-1;}
        else if(!hasWindow)resetZoom();
        else if(wasFull)setWindow(fullStart(),fullSpan());else setWindow(oldStart,oldSpan);
        notifyReadingDetails();invalidate();
    }
    private boolean hasTimeline(){return !points.isEmpty()||!feelings.isEmpty();}
    public boolean isZoomed(){return hasWindow&&hasTimeline()&&windowSpan()<fullSpan()-1;}
    double visibleStartTime(){return windowStart;}
    double visibleEndTime(){return windowEnd;}
    private double fullStart(){double first=points.isEmpty()?Double.POSITIVE_INFINITY:points.get(0).time;for(Store.FeelingEntry entry:feelings)first=Math.min(first,entry.time);return Double.isFinite(first)?first:0;}
    private double fullEnd(){double last=points.isEmpty()?Double.NEGATIVE_INFINITY:points.get(points.size()-1).time;for(Store.FeelingEntry entry:feelings)last=Math.max(last,entry.time);return Math.max(fullStart()+MIN_WINDOW_MS,last);}
    private double fullSpan(){return fullEnd()-fullStart();}
    private double windowSpan(){return windowEnd-windowStart;}
    private float plotLeft(){return dp(36);}
    private float plotRight(){return getWidth()-dp(36);}
    private float chartBottom(){return getHeight()-dp(26);}
    private float sensorBottom(){return chartBottom()-(feelings.isEmpty()?0:dp(120));}
    float fanSettingY(double setting){return sensorBottom()-(sensorBottom()-dp(30))*(float)setting/FanSetting.MAX;}
    private float feelingTop(){return chartBottom()-dp(80);}
    float feelingX(long time){return plotLeft()+(plotRight()-plotLeft())*(float)((time-windowStart)/windowSpan());}
    float feelingY(int signedValue){return chartBottom()-(chartBottom()-feelingTop())*(signedValue+10)/20f;}
    private int feelingValue(Store.FeelingEntry entry){return entry.signedValue!=0?entry.signedValue:(Store.FEELING_HOT.equals(entry.kind)?entry.intensity:-entry.intensity);}
    private double ratioAt(float x){return Math.max(0,Math.min(1,(x-plotLeft())/Math.max(1,plotRight()-plotLeft())));}
    private double timeAt(float x){return windowStart+windowSpan()*ratioAt(x);}
    private void setWindow(double start,double span){
        span=Math.max(Math.min(MIN_WINDOW_MS,fullSpan()),Math.min(fullSpan(),span));
        windowStart=Math.max(fullStart(),Math.min(fullEnd()-span,start));windowEnd=windowStart+span;hasWindow=true;
        if(selected>=0&&selected<points.size()&&(points.get(selected).time<windowStart||points.get(selected).time>windowEnd))selected=-1;
        for(Store.FeelingEntry entry:feelings)if(entry.id==selectedFeeling&&(entry.time<windowStart||entry.time>windowEnd))selectedFeeling=-1;
        notifyReadingDetails();invalidate();
    }
    private void disallowParent(boolean value){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(value);}
    private int firstIndexAtOrAfter(List<Store.Point> list,double time){
        int lo=0,hi=list.size();while(lo<hi){int mid=(lo+hi)/2;if(list.get(mid).time<time)lo=mid+1;else hi=mid;}return lo;
    }
    private int nearestIndex(double time){
        if(points.isEmpty())return -1;
        int next=firstIndexAtOrAfter(points,time);if(next==0)return 0;if(next==points.size())return next-1;
        return time-points.get(next-1).time<=points.get(next).time-time?next-1:next;
    }
    private void selectAt(float x,float y){
        if(!hasTimeline())return;
        selectedFeeling=-1;float distance=dp(22);
        for(Store.FeelingEntry entry:feelings){
            if(entry.time<windowStart||entry.time>windowEnd)continue;
            float markerX=feelingX(entry.time),markerY=feelingY(feelingValue(entry));
            float d=(float)Math.hypot(markerX-x,markerY-y);if(d<distance){distance=d;selectedFeeling=entry.id;}
        }
        selected=selectedFeeling>=0||(!feelings.isEmpty()&&y>=feelingTop()-dp(10))?-1:nearestIndex(timeAt(x));
        if(selected>=0&&(points.get(selected).time<windowStart||points.get(selected).time>windowEnd))selected=-1;
        notifyReadingDetails();invalidate();
    }
    public void visibleSeries(int mask){visible=mask&ALL_SERIES;notifyReadingDetails();invalidate();}
    public void setSmoothHighestBody(boolean enabled){
        if(smoothHighestBody==enabled)return;
        smoothHighestBody=enabled;refreshHighestBodySmoothing();notifyReadingDetails();invalidate();
    }
    private void refreshHighestBodySmoothing(){
        if(!smoothHighestBody){smoothedHighestBody=null;return;}
        long[] times=new long[points.size()];double[] highest=new double[points.size()];
        for(int at=0;at<points.size();at++){Store.Point point=points.get(at);times[at]=point.time;highest[at]=highestBodyTemperature(point);}
        smoothedHighestBody=HighestBodySmoother.smooth(times,highest);
    }
    private boolean enabled(int series){return (visible&(1<<series))!=0;}
    private boolean isOutdoor(int series){return series==3||series==5;}
    private boolean isHumidity(int series){return series==4||series==5;}
    private boolean isAmbient(int series){return (AMBIENT_TEMPERATURE_SERIES&(1<<series))!=0;}
    private boolean breaksOnMissing(int series){return series==0||series==1||series==HIGHEST_BODY_SERIES||series==D2_BODY_SERIES||series==DREO_TEMPERATURE_SERIES;}
    private long sampleGap(int series){return series==DREO_TEMPERATURE_SERIES?DREO_MAX_GAP_MS:isOutdoor(series)?3700000:breaksOnMissing(series)||series==FAN_SETTING_SERIES?35000:65000;}
    private boolean continuous(int series,Store.Point earlier,Store.Point later){return later.time>=earlier.time&&later.time-earlier.time<=sampleGap(series)&&(series!=DREO_TEMPERATURE_SERIES||Objects.equals(earlier.segment,later.segment));}
    private float dp(float d){return d*getResources().getDisplayMetrics().density;}
    private float unit(double c){return(float)(fahrenheit?c*1.8+32:c);}
    private double value(Store.Point p,int series){return switch(series){case 0->bodyTemperature(p.probe);case 1->bodyTemperature(p.secondProbe);case 2->p.room;case 4,5->p.humidity;case HIGHEST_BODY_SERIES->highestBodyTemperature(p);case D2_BODY_SERIES->bodyTemperature(p.thirdProbe);case FAN_SETTING_SERIES->validFanSetting(p.fanSetting);default->p.probe;};}
    private static double bodyTemperature(double value){return Double.isFinite(value)&&value>=MIN_BODY_TEMPERATURE_C?value:Double.NaN;}
    private double validFanSetting(double setting){return Double.isFinite(setting)&&setting>=0&&setting<=FanSetting.MAX&&setting==Math.rint(setting)?setting:Double.NaN;}
    private double highestBodyTemperature(Store.Point point){
        return HighestBodySmoother.highest(bodyTemperature(point.probe),bodyTemperature(point.secondProbe),bodyTemperature(point.thirdProbe));
    }
    private double displayedValue(List<Store.Point> recorded,int at,int series){return series==HIGHEST_BODY_SERIES&&smoothHighestBody?smoothedHighestBody.valueAt(at):value(recorded.get(at),series);}
    private List<Store.Point> samples(int series){return series==DREO_TEMPERATURE_SERIES?dreoTemperatures:isOutdoor(series)?outdoor:points;}
    private void text(Canvas c,String t,float x,float y,int color,float size){paint.setColor(color);paint.setTextSize(dp(size));paint.setStyle(Paint.Style.FILL);c.drawText(t,x,y,paint);}
    private void rightText(Canvas c,String t,float right,float y,int color,float size){paint.setTextSize(dp(size));text(c,t,right-paint.measureText(t),y,color,size);}
    private void notifyReadingDetails(){if(readingDetailsListener!=null)readingDetailsListener.onReadingSelected(selectedReadingDetails());}
    private ReadingDetails selectedReadingDetails(){
        for(Store.FeelingEntry entry:feelings)if(entry.id==selectedFeeling){
            int signed=feelingValue(entry);String kind=signed>0?"Warm":"Cold";
            List<ReadingValue> readings=new ArrayList<>();
            for(int series=0;series<LABELS.length;series++)readings.add(readingValue(series,series==FAN_SETTING_SERIES?fanSettingAt(entry.time):series==DREO_TEMPERATURE_SERIES?dreoTemperatureAt(entry.time):nearestFiniteValue(series,entry.time)));
            String note="Nearest recorded sensor readings; fan setting uses the latest reading at or before this time.";
            return new ReadingDetails(entry.time,readings,note,
                    new ReadingValue("Feeling",kind+" "+Math.abs(signed)+" / 10",feelingColor(entry)));
        }
        int readingIndex=selected;
        boolean latest=readingIndex<0||readingIndex>=points.size();
        if(latest){
            if(points.isEmpty()||!hasWindow)return null;
            // Follow the newest visible sample until the user inspects a specific reading.
            readingIndex=firstIndexAtOrAfter(points,windowEnd);
            if(readingIndex==points.size()||points.get(readingIndex).time>windowEnd)readingIndex--;
            if(readingIndex<0||points.get(readingIndex).time<windowStart)return null;
        }
        Store.Point point=points.get(readingIndex),weather=null;long distance=Long.MAX_VALUE;
        for(Store.Point sample:outdoor){long delta=Math.abs(sample.time-point.time);if(delta<distance&&delta<=3700000){distance=delta;weather=sample;}}
        List<ReadingValue> readings=new ArrayList<>();
        for(int series=0;series<LABELS.length;series++){
            Store.Point sample=isOutdoor(series)?weather:point;double number=series==DREO_TEMPERATURE_SERIES?dreoTemperatureAt(point.time):sample==null?Double.NaN:series==HIGHEST_BODY_SERIES?displayedValue(points,readingIndex,series):value(sample,series);
            readings.add(readingValue(series,number));
        }
        String note=(latest?"Latest visible recorded reading. ":"")+"Outdoor values use the nearest available hourly weather reading."+(enabled(DREO_TEMPERATURE_SERIES)?" Dreo values are the latest received cloud observation at or before this time, up to 4 minutes old.":"");
        return new ReadingDetails(point.time,readings,note);
    }
    private ReadingValue readingValue(int series,double number){
        String reading=series==FAN_SETTING_SERIES?(!Double.isFinite(number)?"Unknown":number==0?"Off":FanSetting.label((int)number)):!Double.isFinite(number)?"—":isHumidity(series)?String.format(Locale.US,"%.1f%%",number):String.format(Locale.US,"%.1f°%s",(double)unit(number),fahrenheit?"F":"C");
        return new ReadingValue(LABELS[series],reading,COLORS[series]);
    }
    double dreoTemperatureAt(long time){
        int at=firstIndexAtOrAfter(dreoTemperatures,time);
        if(at==dreoTemperatures.size()||dreoTemperatures.get(at).time>time)at--;
        if(at<0||time-dreoTemperatures.get(at).time>DREO_MAX_GAP_MS)return Double.NaN;
        return dreoTemperatures.get(at).probe;
    }
    private double fanSettingAt(long time){
        int at=firstIndexAtOrAfter(points,time);
        if(at==points.size()||points.get(at).time>time)at--;
        if(at<0||time-points.get(at).time>sampleGap(FAN_SETTING_SERIES))return Double.NaN;
        return value(points.get(at),FAN_SETTING_SERIES);
    }
    private double nearestFiniteValue(int series,long time){
        List<Store.Point> recorded=samples(series);long tolerance=sampleGap(series);
        if((BODY_TEMPERATURE_SERIES&(1<<series))!=0){
            int at=nearestIndex(time);
            return at<0||Math.abs(points.get(at).time-time)>tolerance?Double.NaN:displayedValue(points,at,series);
        }
        long nearest=tolerance+1;double result=Double.NaN;
        for(int at=firstIndexAtOrAfter(recorded,time-tolerance);at<recorded.size();at++){
            Store.Point sample=recorded.get(at);if(sample.time>time+tolerance)break;
            double number=displayedValue(recorded,at,series);if(!Double.isFinite(number))continue;
            long distance=Math.abs(sample.time-time);if(distance<nearest){nearest=distance;result=number;}
        }
        return result;
    }
    double[] visibleTemperatureRangeC(){return temperatureRangeC((visible&BODY_TEMPERATURE_SERIES)!=0?BODY_TEMPERATURE_SERIES:AMBIENT_TEMPERATURE_SERIES);}
    double[] visibleAmbientTemperatureRangeC(){return temperatureRangeC(AMBIENT_TEMPERATURE_SERIES);}
    private double[] temperatureRangeC(int seriesMask){
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(int series=0;series<LABELS.length;series++)if(enabled(series)&&(seriesMask&(1<<series))!=0){
            List<Store.Point> list=samples(series);
            int first=Math.max(0,firstIndexAtOrAfter(list,windowStart)-1),limit=Math.min(list.size(),firstIndexAtOrAfter(list,windowEnd)+1);
            Store.Point previous=null;double previousValue=Double.NaN;
            for(int i=first;i<limit;i++){
                Store.Point point=list.get(i);double current=displayedValue(list,i,series);
                if(!Double.isFinite(current)){if(breaksOnMissing(series))previous=null;continue;}
                if(point.time>=windowStart&&point.time<=windowEnd){min=Math.min(min,current);max=Math.max(max,current);}
                if(previous!=null&&point.time>previous.time&&continuous(series,previous,point)){
                    double from=Math.max(windowStart,previous.time),to=Math.min(windowEnd,point.time);
                    if(from<=to){
                        double left=previousValue+(current-previousValue)*(from-previous.time)/(point.time-previous.time);
                        double right=previousValue+(current-previousValue)*(to-previous.time)/(point.time-previous.time);
                        min=Math.min(min,Math.min(left,right));max=Math.max(max,Math.max(left,right));
                    }
                }
                previous=point;previousValue=current;
            }
        }
        boolean body=(seriesMask&BODY_TEMPERATURE_SERIES)!=0;
        if(!Double.isFinite(min))return new double[]{body?MIN_BODY_TEMPERATURE_C:15,40};
        double center=(min+max)/2,half=Math.max(0.05,(max-min)/2+Math.max(0.025,(max-min)*0.08));
        return new double[]{body?Math.max(MIN_BODY_TEMPERATURE_C,center-half):center-half,center+half};
    }
    String temperatureTickLabel(double c){double[] range=visibleTemperatureRangeC();return temperatureTickLabel(c,(range[1]-range[0])*(fahrenheit?1.8:1)/4);}
    private String temperatureTickLabel(double c,double tickStep){int decimals=tickStep<0.1?2:tickStep<1?1:0;return String.format(Locale.US,"%."+decimals+"f",(double)unit(c));}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float l=plotLeft(),r=plotRight(),top=dp(30),bottom=sensorBottom(),footer=chartBottom();
        if(!hasTimeline()){text(c,"No readings downloaded for this night.",dp(4),dp(70),INK,13);return;}
        double start=windowStart,end=windowEnd;boolean body=!points.isEmpty()&&(visible&BODY_TEMPERATURE_SERIES)!=0,ambient=(visible&AMBIENT_TEMPERATURE_SERIES)!=0,humidity=!points.isEmpty()&&(visible&48)!=0;
        double[] temperatureRange=temperatureRangeC(BODY_TEMPERATURE_SERIES),ambientRange=visibleAmbientTemperatureRangeC();
        float min=unit(temperatureRange[0]),max=unit(temperatureRange[1]),ambientMin=unit(ambientRange[0]),ambientMax=unit(ambientRange[1]);
        String units=fahrenheit?"°F":"°C";
        if(body)rightText(c,"Body "+units,getWidth()-dp(1),dp(13),INK,10);
        if(ambient)text(c,"Ambient "+units,dp(1),dp(13),COLORS[2],10);
        else if(humidity)text(c,"% RH",dp(1),dp(13),COLORS[4],10);
        if(enabled(FAN_SETTING_SERIES))text(c,"Fan 0–H",l,dp(26),COLORS[FAN_SETTING_SERIES],10);
        if(humidity)rightText(c,"Humidity 0–100%",r,dp(26),COLORS[4],10);
        for(int i=0;!points.isEmpty()&&i<=4;i++){
            float y=bottom-(bottom-top)*i/4;paint.setColor(0xff374e3f);paint.setStrokeWidth(dp(1));c.drawLine(l,y,r,y,paint);
            if(body)rightText(c,temperatureTickLabel(temperatureRange[0]+(temperatureRange[1]-temperatureRange[0])*i/4,(max-min)/4),getWidth()-dp(1),y+dp(4),MUTED,10);
            if(ambient)text(c,temperatureTickLabel(ambientRange[0]+(ambientRange[1]-ambientRange[0])*i/4,(ambientMax-ambientMin)/4),dp(1),y+dp(4),COLORS[2],10);
            else if(humidity)text(c,""+(i*25),dp(1),y+dp(4),COLORS[4],10);
        }
        for(int series=0;series<LABELS.length;series++)if(enabled(series)&&series!=FAN_SETTING_SERIES)line(c,series,start,end,isHumidity(series)?0:isAmbient(series)?ambientMin:min,isHumidity(series)?100:isAmbient(series)?ambientMax:max,l,r,top,bottom);
        if(points.isEmpty())text(c,"No sensor readings downloaded yet.",l,top+dp(30),MUTED,12);
        else if(visible==0)text(c,"Check a reading below to show it.",l,top+dp(30),MUTED,12);
        int previousPhase=0;for(Store.Point point:points){if(point.phase!=0&&point.phase!=previousPhase&&point.time>=start&&point.time<=end){float x=l+(r-l)*(float)((point.time-start)/(end-start));paint.setColor(0xff8ea596);paint.setStrokeWidth(dp(1));c.drawLine(x,top,x,bottom,paint);String label=point.phase==1?"Baseline":point.phase==2?"On":"Off";text(c,label,Math.min(x+dp(3),r-dp(38)),top+dp(12),0xffd3dfd4,9);}previousPhase=point.phase;}
        fanLine(c,l,r,top,bottom,humidity||ambient);feelingMarkers(c,l,r,top,bottom);
        SimpleDateFormat tf=new SimpleDateFormat(end-start<=120000?"h:mm:ss a":"h:mm a",Locale.US);text(c,tf.format(new Date((long)start)),l,footer+dp(18),MUTED,10);String et=tf.format(new Date((long)end));paint.setTextSize(dp(10));text(c,et,r-paint.measureText(et),footer+dp(18),MUTED,10);
        if(selected>=0){
            Store.Point p=points.get(selected);float x=l+(r-l)*(float)((p.time-start)/(end-start));paint.setColor(INK);paint.setStrokeWidth(dp(1));c.save();c.clipRect(l,top,r,bottom);c.drawLine(x,top,x,bottom,paint);c.restore();
        }
    }
    private void fanLine(Canvas canvas,float left,float right,float top,float bottom,boolean humidity){
        if(!enabled(FAN_SETTING_SERIES))return;
        int color=COLORS[FAN_SETTING_SERIES];
        Path path=new Path();Store.Point previous=null;double previousValue=Double.NaN;
        int first=Math.max(0,firstIndexAtOrAfter(points,windowStart)-1),limit=Math.min(points.size(),firstIndexAtOrAfter(points,windowEnd)+1);
        canvas.save();canvas.clipRect(left,top,right,bottom);
        for(int i=first;i<limit;i++){
            Store.Point point=points.get(i);double setting=value(point,FAN_SETTING_SERIES);float x=feelingX(point.time);
            boolean connected=previous!=null&&Double.isFinite(previousValue)&&point.time>=previous.time&&point.time-previous.time<=sampleGap(FAN_SETTING_SERIES);
            if(connected)path.lineTo(x,fanSettingY(previousValue));
            if(Double.isFinite(setting)){
                if(connected)path.lineTo(x,fanSettingY(setting));else path.moveTo(x,fanSettingY(setting));
                if(point.time>=windowStart&&point.time<=windowEnd){paint.setColor(color);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,fanSettingY(setting),dp(2),paint);}
            }
            previous=point;previousValue=setting;
        }
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);canvas.restore();
        for(int setting:new int[]{0,3,6,FanSetting.MAX}){
            String label=FanSetting.label(setting);float x=humidity?left+dp(3):dp(1),y=fanSettingY(setting)+dp(setting==FanSetting.MAX?12:4);
            if(humidity){paint.setTextSize(dp(10));paint.setColor(0xff20352b);canvas.drawRoundRect(x-dp(2),y-dp(11),x+paint.measureText(label)+dp(2),y+dp(3),dp(2),dp(2),paint);}
            text(canvas,label,x,y,color,10);
        }
    }
    private int feelingColor(Store.FeelingEntry entry){return feelingValue(entry)>0?0xffefa172:0xff75c9ed;}
    private void feelingMarkers(Canvas canvas,float left,float right,float top,float bottom){
        if(feelings.isEmpty())return;
        text(canvas,"Feeling · + warm / − cold · 0 neutral",left,feelingTop()-dp(17),INK,11);
        for(int value:new int[]{10,5,0,-5,-10}){
            float y=feelingY(value);paint.setColor(value==0?MUTED:0xff374e3f);paint.setStrokeWidth(dp(value==0?1.5f:1));canvas.drawLine(left,y,right,y,paint);
            text(canvas,(value>0?"+":"")+value,dp(1),y+dp(4),value>0?0xffefa172:value<0?0xff75c9ed:MUTED,10);
        }
        for(Store.FeelingEntry entry:feelings)if(entry.id==selectedFeeling&&entry.time>=windowStart&&entry.time<=windowEnd){
            paint.setColor(feelingColor(entry));paint.setStrokeWidth(dp(1));canvas.drawLine(feelingX(entry.time),top,feelingX(entry.time),bottom,paint);
        }
        canvas.save();canvas.clipRect(left,feelingTop()-dp(7),right,chartBottom()+dp(7));
        for(Store.FeelingEntry entry:feelings){
            if(entry.time<windowStart||entry.time>windowEnd)continue;
            boolean hot=feelingValue(entry)>0;float x=feelingX(entry.time),y=feelingY(feelingValue(entry));
            if(entry.id==selectedFeeling){paint.setColor(feelingColor(entry));paint.setStrokeWidth(dp(1));canvas.drawLine(x,feelingTop(),x,chartBottom(),paint);}
            paint.setColor(feelingColor(entry));paint.setStyle(Paint.Style.FILL);
            if(hot){Path marker=new Path();marker.moveTo(x,y-dp(6));marker.lineTo(x-dp(6),y+dp(5));marker.lineTo(x+dp(6),y+dp(5));marker.close();canvas.drawPath(marker,paint);}
            else canvas.drawCircle(x,y,dp(5),paint);
        }
        canvas.restore();
    }
    private void line(Canvas c,int series,double start,double end,float min,float max,float l,float r,float top,float bottom){
        List<Store.Point> list=samples(series);Path path=new Path();boolean has=false;long prev=0;int previousIndex=-1;
        // Retain compatibility with 30-second body / 60-second SHT40 historical sessions.
        long gap=sampleGap(series);
        c.save();c.clipRect(l,top,r,bottom);
        int first=Math.max(0,firstIndexAtOrAfter(list,start)-1),limit=Math.min(list.size(),firstIndexAtOrAfter(list,end)+1),stride=series==DREO_TEMPERATURE_SERIES?1:Math.max(1,(limit-first)/1500);
        for(int i=first;i<limit;i+=stride){Store.Point p=list.get(i);double v=displayedValue(list,i,series);if(!Double.isFinite(v)){if(breaksOnMissing(series))has=false;continue;}
            float converted=isHumidity(series)?(float)v:unit(v);float x=l+(r-l)*(float)((p.time-start)/(end-start)),y=bottom-(bottom-top)*(converted-min)/(max-min);
            boolean smoothingBreak=series==HIGHEST_BODY_SERIES&&smoothHighestBody&&previousIndex>=0&&!smoothedHighestBody.sameSegment(previousIndex,i);
            boolean segmentBreak=series==DREO_TEMPERATURE_SERIES&&previousIndex>=0&&!continuous(series,list.get(previousIndex),p);
            boolean skippedBodyBreak=has&&stride>1&&(BODY_TEMPERATURE_SERIES&(1<<series))!=0&&bodyBreakBetween(list,series,previousIndex,i);
            if(!has||p.time-prev>gap*stride||smoothingBreak||segmentBreak||skippedBodyBreak)path.moveTo(x,y);else path.lineTo(x,y);has=true;prev=p.time;previousIndex=i;
            if(series==DREO_TEMPERATURE_SERIES&&p.time>=start&&p.time<=end){paint.setColor(COLORS[series]);paint.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(2.5f),paint);}
        }
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(COLORS[series]);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);if(isOutdoor(series))paint.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));c.drawPath(path,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);c.restore();
    }
    /** Decimation must not hide an off-body reading or a real sampling gap between drawn points. */
    private boolean bodyBreakBetween(List<Store.Point> list,int series,int first,int last){
        for(int at=first+1;at<=last;at++)if(!Double.isFinite(value(list.get(at),series))||!continuous(series,list.get(at-1),list.get(at)))return true;
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!hasTimeline())return false;
        int action=e.getActionMasked();
        // Claim the first move so ScrollView cannot steal a mostly-horizontal diagonal
        // gesture before this view classifies it. Vertical intent releases the parent.
        if(action==MotionEvent.ACTION_DOWN){downX=lastX=e.getX();downY=e.getY();dragAxis=0;pinchGesture=false;disallowParent(true);}
        if(action==MotionEvent.ACTION_POINTER_DOWN){pinchGesture=true;disallowParent(true);}
        scale.onTouchEvent(e);taps.onTouchEvent(e);
        if(action==MotionEvent.ACTION_MOVE&&!pinchGesture){
            float dx=e.getX()-downX,dy=e.getY()-downY;
            if(dragAxis==0&&(Math.abs(dx)>touchSlop||Math.abs(dy)>touchSlop))dragAxis=Math.abs(dx)>Math.abs(dy)?1:2;
            if(dragAxis==1){
                disallowParent(true);
                if(isZoomed())setWindow(windowStart-(e.getX()-lastX)/Math.max(1,plotRight()-plotLeft())*windowSpan(),windowSpan());
                else selectAt(e.getX(),e.getY());
            }else if(dragAxis==2)disallowParent(false);
            lastX=e.getX();
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){disallowParent(false);dragAxis=0;pinchGesture=false;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
