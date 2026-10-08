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
    private final Paint paint=new Paint(3);
    private boolean fahrenheit=true;
    private int selected=-1,visible=ALL_SERIES;
    public GraphView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setContentDescription("Recorded temperatures and humidity. Temperature on the left axis, humidity on the right. Touch to inspect a reading and timestamp.");}
    public void data(List<Store.Point> p,List<Store.Point> o,boolean f,int mask){if(!points.isEmpty()&&!p.isEmpty()&&points.get(0).time!=p.get(0).time)selected=-1;points=p;outdoor=o;fahrenheit=f;visible=mask&ALL_SERIES;if(selected>=p.size())selected=-1;invalidate();}
    public void visibleSeries(int mask){visible=mask&ALL_SERIES;invalidate();}
    private boolean enabled(int series){return (visible&(1<<series))!=0;}
    private boolean isOutdoor(int series){return series==3||series==5;}
    private float dp(float d){return d*getResources().getDisplayMetrics().density;}
    private float unit(double c){return(float)(fahrenheit?c*1.8+32:c);}
    private double value(Store.Point p,int series){return switch(series){case 1->p.secondProbe;case 2->p.room;case 4,5->p.humidity;default->p.probe;};}
    private List<Store.Point> samples(int series){return isOutdoor(series)?outdoor:points;}
    private void text(Canvas c,String t,float x,float y,int color,float size){paint.setColor(color);paint.setTextSize(dp(size));paint.setStyle(Paint.Style.FILL);c.drawText(t,x,y,paint);}
    private long endTime(){return Math.max(points.get(0).time+60000,points.get(points.size()-1).time);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float l=dp(36),r=getWidth()-dp(36),top=dp(30),bottom=getHeight()-dp(110);
        if(points.isEmpty()){text(c,"No readings downloaded for this night.",dp(4),dp(70),INK,13);return;}
        long start=points.get(0).time,end=endTime();boolean temperatures=(visible&15)!=0,humidity=(visible&48)!=0;
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
        for(int series=0;series<4;series++)if(enabled(series))for(Store.Point p:samples(series)){
            double v=value(p,series);if(p.time<start-3600000||p.time>end+3600000||!Double.isFinite(v))continue;
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
        int previousPhase=0;for(Store.Point point:points){if(point.phase!=0&&point.phase!=previousPhase){float x=l+(r-l)*(point.time-start)/(float)(end-start);paint.setColor(0xff8ea596);paint.setStrokeWidth(dp(1));c.drawLine(x,top,x,bottom,paint);String label=point.phase==1?"Baseline":point.phase==2?"On":"Off";text(c,label,Math.min(x+dp(3),r-dp(38)),top+dp(12),0xffd3dfd4,9);}previousPhase=point.phase;}
        SimpleDateFormat tf=new SimpleDateFormat("h:mm a",Locale.US);text(c,tf.format(new Date(start)),l,bottom+dp(18),MUTED,10);String et=tf.format(new Date(end));paint.setTextSize(dp(10));text(c,et,r-paint.measureText(et),bottom+dp(18),MUTED,10);
        if(selected>=0){
            Store.Point p=points.get(selected);float x=l+(r-l)*(p.time-start)/(float)(end-start);paint.setColor(INK);paint.setStrokeWidth(dp(1));c.drawLine(x,top,x,bottom,paint);
            text(c,new SimpleDateFormat("MMM d, h:mm:ss a",Locale.US).format(new Date(p.time)),dp(2),bottom+dp(39),INK,11);
            String[] shortNames={"D0","D1","SHT40","Outside","RH","Outside RH"};
            Store.Point weather=null;long distance=Long.MAX_VALUE;for(Store.Point o:outdoor){long d=Math.abs(o.time-p.time);if(d<distance&&d<=3700000){distance=d;weather=o;}}
            for(int series=0;series<6;series++)if(enabled(series)){
                Store.Point sample=isOutdoor(series)?weather:p;double v=sample==null?Double.NaN:value(sample,series);
                String reading=!Double.isFinite(v)?"—":String.format(Locale.US,series>=4?"%.1f%%":"%.1f°",series>=4?v:(double)unit(v));
                text(c,shortNames[series]+" "+reading,dp(2)+(series%2)*(getWidth()/2f),bottom+dp(57+(series/2)*17),COLORS[series],10);
            }
        }else text(c,"Touch for readings and time; outdoors is hourly.",dp(2),bottom+dp(48),MUTED,11);
    }
    private void line(Canvas c,int series,long start,long end,float min,float max,float l,float r,float top,float bottom){
        List<Store.Point> list=samples(series);Path path=new Path();boolean has=false;long prev=0;
        // Retain compatibility with 30-second body / 60-second SHT40 historical sessions.
        long gap=isOutdoor(series)?3700000:series==0||series==1?35000:65000;
        c.save();c.clipRect(l,top,r,bottom);int stride=Math.max(1,list.size()/1500);
        for(int i=0;i<list.size();i+=stride){Store.Point p=list.get(i);double v=value(p,series);if(!Double.isFinite(v)){if(series==0||series==1)has=false;continue;}
            float converted=series>=4?(float)v:unit(v);float x=l+(r-l)*(p.time-start)/(float)(end-start),y=bottom-(bottom-top)*(converted-min)/(max-min);
            if(!has||p.time-prev>gap*stride)path.moveTo(x,y);else path.lineTo(x,y);has=true;prev=p.time;
        }
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(COLORS[series]);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);if(isOutdoor(series))paint.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));c.drawPath(path,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);c.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(points.isEmpty())return false;
        if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
            float ratio=Math.max(0,Math.min(1,(e.getX()-dp(36))/Math.max(1,getWidth()-dp(72))));long t=points.get(0).time+(long)((endTime()-points.get(0).time)*ratio);
            int lo=0,hi=points.size()-1;while(lo<hi){int mid=(lo+hi)/2;if(points.get(mid).time<t)lo=mid+1;else hi=mid;}selected=lo;invalidate();return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){if(e.getAction()==MotionEvent.ACTION_UP)performClick();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
