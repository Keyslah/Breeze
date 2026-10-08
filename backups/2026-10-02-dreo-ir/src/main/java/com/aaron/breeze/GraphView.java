package com.aaron.breeze;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class GraphView extends View {
    private List<Store.Point> points=new ArrayList<>(),outdoor=new ArrayList<>();
    private final Paint paint=new Paint(3);
    private final int[] colors={Color.rgb(154,197,164),Color.rgb(214,143,75),Color.rgb(102,139,182)};
    private boolean fahrenheit=true,humidity=false;
    private int selected=-1;
    public GraphView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setContentDescription("Temperature history graph. Touch to inspect a reading and its timestamp.");}
    public void data(List<Store.Point> p,List<Store.Point> o,boolean f,boolean h){points=p;outdoor=o;fahrenheit=f;humidity=h;if(selected>=p.size())selected=-1;invalidate();}
    private float dp(float d){return d*getResources().getDisplayMetrics().density;}
    private float unit(double c){return(float)(fahrenheit?c*1.8+32:c);}
    private void text(Canvas c,String t,float x,float y,int color,float size){paint.setColor(color);paint.setTextSize(dp(size));paint.setStyle(Paint.Style.FILL);c.drawText(t,x,y,paint);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float l=dp(38),r=getWidth()-dp(12),top=dp(15),bottom=getHeight()-dp(72);
        if(points.isEmpty()){text(c,"Your night, at a glance",dp(16),dp(82),Color.rgb(229,238,230),18);text(c,"Download a session to see its timeline.",dp(16),dp(108),Color.rgb(166,185,172),12);return;}
        long start=points.get(0).time,end=Math.max(start+60000,points.get(points.size()-1).time);
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
        if(humidity){min=0;max=100;}else{for(Store.Point p:points){if(Double.isFinite(p.probe)){min=Math.min(min,unit(p.probe));max=Math.max(max,unit(p.probe));}if(Double.isFinite(p.room)){min=Math.min(min,unit(p.room));max=Math.max(max,unit(p.room));}}for(Store.Point p:outdoor)if(p.time>=start-3600000&&p.time<=end+3600000&&Double.isFinite(p.probe)){min=Math.min(min,unit(p.probe));max=Math.max(max,unit(p.probe));}if(min==Float.MAX_VALUE){min=fahrenheit?60:15;max=fahrenheit?100:40;}min=(float)Math.floor(min-1);max=(float)Math.ceil(Math.max(max+1,min+4));}
        for(int i=0;i<=4;i++){float y=bottom-(bottom-top)*i/4;paint.setColor(Color.rgb(55,78,63));paint.setStrokeWidth(dp(1));c.drawLine(l,y,r,y,paint);text(c,String.format(Locale.US,"%.0f",min+(max-min)*i/4),dp(2),y+dp(4),Color.rgb(166,185,172),10);}
        line(c,points,0,start,end,min,max,l,r,top,bottom,colors[0],humidity?65000:35000);
        if(!humidity)line(c,points,1,start,end,min,max,l,r,top,bottom,colors[1],65000);
        line(c,outdoor,2,start,end,min,max,l,r,top,bottom,colors[2],3700000);
        SimpleDateFormat tf=new SimpleDateFormat("h:mm a",Locale.US);text(c,tf.format(new Date(start)),l,bottom+dp(18),Color.rgb(166,185,172),10);String et=tf.format(new Date(end));paint.setTextSize(dp(10));text(c,et,r-paint.measureText(et),bottom+dp(18),Color.rgb(166,185,172),10);
        if(selected>=0){Store.Point p=points.get(selected);float x=l+(r-l)*(p.time-start)/(float)(end-start);paint.setColor(colors[0]);paint.setStrokeWidth(dp(1));c.drawLine(x,top,x,bottom,paint);String value=humidity?(Double.isFinite(p.humidity)?String.format(Locale.US,"%.1f%% RH",p.humidity):"No RH reading"):(Double.isFinite(p.probe)?String.format(Locale.US,"%.1f°%s",unit(p.probe),fahrenheit?"F":"C"):"Probe fault");text(c,new SimpleDateFormat("MMM d, h:mm:ss a",Locale.US).format(new Date(p.time))+"  ·  "+value,dp(4),getHeight()-dp(28),Color.rgb(229,238,230),11);text(c,"Fan temp: "+(Double.isFinite(p.room)?String.format(Locale.US,"%.1f°%s",unit(p.room),fahrenheit?"F":"C"):"—"),dp(4),getHeight()-dp(9),Color.rgb(166,185,172),11);}else{text(c,"Touch the graph for the exact time and reading",dp(4),getHeight()-dp(22),Color.rgb(166,185,172),11);}
    }
    private void line(Canvas c,List<Store.Point> list,int channel,long start,long end,float min,float max,float l,float r,float top,float bottom,int color,long gap){
        Path path=new Path();boolean has=false;long prev=0;
        c.save();c.clipRect(l,top,r,bottom);int stride=Math.max(1,list.size()/1500);
        for(int i=0;i<list.size();i+=stride){Store.Point p=list.get(i);double value=humidity?p.humidity:(channel==1?p.room:p.probe);if(!Double.isFinite(value)){if(!humidity&&channel==0)has=false;continue;}float v=humidity?(float)value:unit(value);float x=l+(r-l)*(p.time-start)/(float)(end-start),y=bottom-(bottom-top)*(v-min)/(max-min);if(!has||p.time-prev>gap*stride)path.moveTo(x,y);else path.lineTo(x,y);has=true;prev=p.time;}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(color);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);if(channel==2)paint.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));c.drawPath(path,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);c.restore();
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){if(points.isEmpty())return false;if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){getParent().requestDisallowInterceptTouchEvent(true);float ratio=Math.max(0,Math.min(1,(e.getX()-dp(38))/(getWidth()-dp(50))));long t=points.get(0).time+(long)((points.get(points.size()-1).time-points.get(0).time)*ratio);int lo=0,hi=points.size()-1;while(lo<hi){int mid=(lo+hi)/2;if(points.get(mid).time<t)lo=mid+1;else hi=mid;}selected=lo;invalidate();return true;}if(e.getAction()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
