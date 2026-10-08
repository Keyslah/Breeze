package com.aaron.breeze;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class GraphView extends View {
    public static final int ALL_SERIES=63;
    public static final String[] LABELS={"D0 body","D1 body","SHT40 temp","Outdoor temp","Humidity","Outdoor humidity"};
    public static final int[] COLORS={0xff9ac5a4,0xffef9393,0xffe0b04f,0xff78a9ed,0xffc69cf0,0xff5ad3d0};
    private static final int MUTED=0xffa6b9ac,INK=0xffe5eee6;
    private List<Store.Point> points=new ArrayList<>(),outdoor=new ArrayList<>();
    private List<Store.FeelingEntry> feelings=new ArrayList<>();
    private final Paint paint=new Paint(3);
    private boolean fahrenheit=true;
    private int selected=-1,visible=ALL_SERIES;
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
    public GraphView(Context c){
        super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setContentDescription("Recorded temperatures and humidity. Temperature on the left axis, humidity on the right. Pinch to zoom, drag sideways to pan, tap to inspect a reading, or double-tap to show the whole night.");
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
        points=p;outdoor=o;fahrenheit=f;visible=mask&ALL_SERIES;sessionId=night;
        if(changed){feelings=new ArrayList<>();selectedFeeling=-1;}
        if(p.isEmpty()){hasWindow=false;selected=-1;}
        else if(changed||!hasWindow){selected=-1;resetZoom();}
        else{
            if(wasFull)setWindow(fullStart(),fullSpan());else setWindow(oldStart,oldSpan);
            selected=inspected==Long.MIN_VALUE?-1:nearestIndex(inspected);
            if(selected>=0&&(points.get(selected).time<windowStart||points.get(selected).time>windowEnd))selected=-1;
        }
        invalidate();
    }
    public void resetZoom(){if(!points.isEmpty()){hasWindow=true;setWindow(fullStart(),fullSpan());}invalidate();}
    public void feelings(List<Store.FeelingEntry> entries){
        boolean wasFull=!hasWindow||!isZoomed();double oldStart=windowStart,oldSpan=windowSpan();
        feelings=entries;boolean found=false;for(Store.FeelingEntry entry:entries)if(entry.id==selectedFeeling)found=true;
        if(!found)selectedFeeling=-1;
        if(hasWindow&&!points.isEmpty()){if(wasFull)setWindow(fullStart(),fullSpan());else setWindow(oldStart,oldSpan);}
        invalidate();
    }
    public boolean isZoomed(){return hasWindow&&!points.isEmpty()&&windowSpan()<fullSpan()-1;}
    double visibleStartTime(){return windowStart;}
    double visibleEndTime(){return windowEnd;}
    private double fullStart(){double first=points.isEmpty()?0:points.get(0).time;for(Store.FeelingEntry entry:feelings)first=Math.min(first,entry.time);return first;}
    private double fullEnd(){double last=points.isEmpty()?0:points.get(points.size()-1).time;for(Store.FeelingEntry entry:feelings)last=Math.max(last,entry.time);return Math.max(fullStart()+MIN_WINDOW_MS,last);}
    private double fullSpan(){return fullEnd()-fullStart();}
    private double windowSpan(){return windowEnd-windowStart;}
    private float plotLeft(){return dp(36);}
    private float plotRight(){return getWidth()-dp(36);}
    private double ratioAt(float x){return Math.max(0,Math.min(1,(x-plotLeft())/Math.max(1,plotRight()-plotLeft())));}
    private double timeAt(float x){return windowStart+windowSpan()*ratioAt(x);}
    private void setWindow(double start,double span){
        span=Math.max(Math.min(MIN_WINDOW_MS,fullSpan()),Math.min(fullSpan(),span));
        windowStart=Math.max(fullStart(),Math.min(fullEnd()-span,start));windowEnd=windowStart+span;hasWindow=true;
        if(selected>=0&&selected<points.size()&&(points.get(selected).time<windowStart||points.get(selected).time>windowEnd))selected=-1;
        for(Store.FeelingEntry entry:feelings)if(entry.id==selectedFeeling&&(entry.time<windowStart||entry.time>windowEnd))selectedFeeling=-1;
        invalidate();
    }
    private void disallowParent(boolean value){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(value);}
    private int firstIndexAtOrAfter(List<Store.Point> list,double time){
        int lo=0,hi=list.size();while(lo<hi){int mid=(lo+hi)/2;if(list.get(mid).time<time)lo=mid+1;else hi=mid;}return lo;
    }
    private int nearestIndex(double time){
        int next=firstIndexAtOrAfter(points,time);if(next==0)return 0;if(next==points.size())return next-1;
        return time-points.get(next-1).time<=points.get(next).time-time?next-1:next;
    }
    private void selectAt(float x,float y){
        if(points.isEmpty())return;
        selectedFeeling=-1;float distance=dp(22);
        for(Store.FeelingEntry entry:feelings){
            if(entry.time<windowStart||entry.time>windowEnd)continue;
            float markerX=plotLeft()+(plotRight()-plotLeft())*(float)((entry.time-windowStart)/windowSpan());
            float markerY=dp(Store.FEELING_HOT.equals(entry.kind)?70:89);
            float d=(float)Math.hypot(markerX-x,markerY-y);if(d<distance){distance=d;selectedFeeling=entry.id;}
        }
        selected=selectedFeeling>=0?-1:nearestIndex(timeAt(x));
        if(selected>=0&&(points.get(selected).time<windowStart||points.get(selected).time>windowEnd))selected=-1;
        invalidate();
    }
    public void visibleSeries(int mask){visible=mask&ALL_SERIES;invalidate();}
    private boolean enabled(int series){return (visible&(1<<series))!=0;}
    private boolean isOutdoor(int series){return series==3||series==5;}
    private float dp(float d){return d*getResources().getDisplayMetrics().density;}
    private float unit(double c){return(float)(fahrenheit?c*1.8+32:c);}
    private double value(Store.Point p,int series){return switch(series){case 1->p.secondProbe;case 2->p.room;case 4,5->p.humidity;default->p.probe;};}
    private List<Store.Point> samples(int series){return isOutdoor(series)?outdoor:points;}
    private void text(Canvas c,String t,float x,float y,int color,float size){paint.setColor(color);paint.setTextSize(dp(size));paint.setStyle(Paint.Style.FILL);c.drawText(t,x,y,paint);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float l=plotLeft(),r=plotRight(),top=dp(30),bottom=getHeight()-dp(110);
        if(points.isEmpty()){text(c,"No readings downloaded for this night.",dp(4),dp(70),INK,13);return;}
        double start=windowStart,end=windowEnd,domainStart=fullStart(),domainEnd=fullEnd();boolean temperatures=(visible&15)!=0,humidity=(visible&48)!=0;
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
        for(int series=0;series<4;series++)if(enabled(series))for(Store.Point p:samples(series)){
            double v=value(p,series);if(p.time<domainStart-3600000||p.time>domainEnd+3600000||!Double.isFinite(v))continue;
            min=Math.min(min,unit(v));max=Math.max(max,unit(v));
        }
        if(min==Float.MAX_VALUE){min=fahrenheit?60:15;max=fahrenheit?100:40;}
        min=(float)Math.floor(min-1);max=(float)Math.ceil(Math.max(max+1,min+4));
        if(temperatures)text(c,fahrenheit?"°F":"°C",dp(1),dp(16),INK,11);
        if(humidity)text(c,"% RH",r-dp(1),dp(16),COLORS[4],10);
        for(int i=0;i<=4;i++){
            float y=bottom-(bottom-top)*i/4;paint.setColor(0xff374e3f);paint.setStrokeWidth(dp(1));c.drawLine(l,y,r,y,paint);
            if(temperatures)text(c,String.format(Locale.US,"%.0f",min+(max-min)*i/4),dp(1),y+dp(4),MUTED,10);
            if(humidity)text(c,""+(i*25),r+dp(5),y+dp(4),COLORS[4],10);
        }
        for(int series=0;series<6;series++)if(enabled(series))line(c,series,start,end,series>=4?0:min,series>=4?100:max,l,r,top,bottom);
        if(visible==0)text(c,"Check a reading below to show it.",l,top+dp(30),MUTED,12);
        int previousPhase=0;for(Store.Point point:points){if(point.phase!=0&&point.phase!=previousPhase&&point.time>=start&&point.time<=end){float x=l+(r-l)*(float)((point.time-start)/(end-start));paint.setColor(0xff8ea596);paint.setStrokeWidth(dp(1));c.drawLine(x,top,x,bottom,paint);String label=point.phase==1?"Baseline":point.phase==2?"On":"Off";text(c,label,Math.min(x+dp(3),r-dp(38)),top+dp(12),0xffd3dfd4,9);}previousPhase=point.phase;}
        feelingMarkers(c,l,r,top,bottom);
        SimpleDateFormat tf=new SimpleDateFormat(end-start<=120000?"h:mm:ss a":"h:mm a",Locale.US);text(c,tf.format(new Date((long)start)),l,bottom+dp(18),MUTED,10);String et=tf.format(new Date((long)end));paint.setTextSize(dp(10));text(c,et,r-paint.measureText(et),bottom+dp(18),MUTED,10);
        Store.FeelingEntry feeling=null;for(Store.FeelingEntry entry:feelings)if(entry.id==selectedFeeling)feeling=entry;
        if(feeling!=null){
            text(c,new SimpleDateFormat("MMM d, h:mm:ss a",Locale.US).format(new Date(feeling.time)),dp(2),bottom+dp(39),INK,11);
            text(c,feeling.kind+" "+feeling.intensity+"/10",dp(2),bottom+dp(57),feelingColor(feeling),12);
            text(c,"How you felt · tap a trace for sensor readings.",dp(2),bottom+dp(76),MUTED,10);
        }else if(selected>=0){
            Store.Point p=points.get(selected);float x=l+(r-l)*(float)((p.time-start)/(end-start));paint.setColor(INK);paint.setStrokeWidth(dp(1));c.save();c.clipRect(l,top,r,bottom);c.drawLine(x,top,x,bottom,paint);c.restore();
            text(c,new SimpleDateFormat("MMM d, h:mm:ss a",Locale.US).format(new Date(p.time)),dp(2),bottom+dp(39),INK,11);
            String[] shortNames={"D0","D1","SHT40","Outside","RH","Outside RH"};
            Store.Point weather=null;long distance=Long.MAX_VALUE;for(Store.Point o:outdoor){long d=Math.abs(o.time-p.time);if(d<distance&&d<=3700000){distance=d;weather=o;}}
            for(int series=0;series<6;series++)if(enabled(series)){
                Store.Point sample=isOutdoor(series)?weather:p;double v=sample==null?Double.NaN:value(sample,series);
                String reading=!Double.isFinite(v)?"—":String.format(Locale.US,series>=4?"%.1f%%":"%.1f°",series>=4?v:(double)unit(v));
                text(c,shortNames[series]+" "+reading,dp(2)+(series%2)*(getWidth()/2f),bottom+dp(57+(series/2)*17),COLORS[series],10);
            }
        }else text(c,isZoomed()?"Drag sideways to pan · double-tap to reset.":"Pinch to zoom · tap for readings and time.",dp(2),bottom+dp(48),MUTED,11);
    }
    private int feelingColor(Store.FeelingEntry entry){return Store.FEELING_HOT.equals(entry.kind)?0xffefa172:0xff75c9ed;}
    private void feelingMarkers(Canvas canvas,float left,float right,float top,float bottom){
        canvas.save();canvas.clipRect(left,top,right,bottom);
        for(Store.FeelingEntry entry:feelings){
            if(entry.time<windowStart||entry.time>windowEnd)continue;
            boolean hot=Store.FEELING_HOT.equals(entry.kind);float x=left+(right-left)*(float)((entry.time-windowStart)/windowSpan()),y=dp(hot?70:89);
            if(entry.id==selectedFeeling){paint.setColor(feelingColor(entry));paint.setStrokeWidth(dp(1));canvas.drawLine(x,top,x,bottom,paint);}
            paint.setColor(feelingColor(entry));paint.setStyle(Paint.Style.FILL);
            if(hot){Path marker=new Path();marker.moveTo(x,y-dp(6));marker.lineTo(x-dp(6),y+dp(5));marker.lineTo(x+dp(6),y+dp(5));marker.close();canvas.drawPath(marker,paint);}
            else canvas.drawCircle(x,y,dp(5),paint);
        }
        canvas.restore();
    }
    private void line(Canvas c,int series,double start,double end,float min,float max,float l,float r,float top,float bottom){
        List<Store.Point> list=samples(series);Path path=new Path();boolean has=false;long prev=0;
        // Retain compatibility with 30-second body / 60-second SHT40 historical sessions.
        long gap=isOutdoor(series)?3700000:series==0||series==1?35000:65000;
        c.save();c.clipRect(l,top,r,bottom);
        int first=Math.max(0,firstIndexAtOrAfter(list,start)-1),limit=Math.min(list.size(),firstIndexAtOrAfter(list,end)+1),stride=Math.max(1,(limit-first)/1500);
        for(int i=first;i<limit;i+=stride){Store.Point p=list.get(i);double v=value(p,series);if(!Double.isFinite(v)){if(series==0||series==1)has=false;continue;}
            float converted=series>=4?(float)v:unit(v);float x=l+(r-l)*(float)((p.time-start)/(end-start)),y=bottom-(bottom-top)*(converted-min)/(max-min);
            if(!has||p.time-prev>gap*stride)path.moveTo(x,y);else path.lineTo(x,y);has=true;prev=p.time;
        }
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(COLORS[series]);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);if(isOutdoor(series))paint.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));c.drawPath(path,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);c.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(points.isEmpty())return false;
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
