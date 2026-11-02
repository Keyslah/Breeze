package com.aaron.breeze;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.util.List;
import java.util.Locale;

/** Extra rows on GraphView's existing viewport, never on its temperature axes. */
final class OuraPlot {
    static final int HEART_COLOR=0xfff0a2a9;
    static final int[] STAGE_COLORS={0,0xff9288ee,0xff69bce1,0xffdf9ce5,0xffe9bd72};
    static int heightDp(List<OuraData.Period> periods,int mask){
        return (mask&(OuraData.HEART|OuraData.STAGES))==0?0:periods.size()*(38+((mask&OuraData.HEART)!=0?106:0)+((mask&OuraData.STAGES)!=0?68:0));
    }
    interface X {float at(double time);}
    private static void text(Canvas c,Paint p,String text,float x,float y,int color,float density,float size){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size*density);c.drawText(text,x,y,p);}
    static void draw(Canvas c,List<OuraData.Period> periods,int mask,float y,float left,float right,float density,X x){
        if(heightDp(periods,mask)==0)return;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        int index=0;
        for(OuraData.Period period:periods){
            text(c,p,"Oura sleep period "+(++index),left,y+14*density,0xffe5eee6,density,12);y+=28*density;
            if((mask&OuraData.HEART)!=0){
                text(c,p,"Heart rate · BPM",left,y+12*density,HEART_COLOR,density,11);
                float top=y+24*density,bottom=y+88*density;double[] range=heartRange(period);
                if(period.heartCount()==0)text(c,p,OuraData.UNAVAILABLE,left,top+26*density,0xffa6b9ac,density,12);
                else{
                    for(int tick=0;tick<=2;tick++){
                        float at=bottom-(bottom-top)*tick/2;p.setColor(0xff374e3f);p.setStrokeWidth(density);c.drawLine(left,at,right,at,p);
                        text(c,p,String.format(Locale.US,"%.0f",range[0]+(range[1]-range[0])*tick/2),2*density,at+4*density,HEART_COLOR,density,10);
                    }
                    c.save();c.clipRect(left,top,right,bottom);Path path=new Path();boolean previous=false;
                    for(int i=0;i<period.heart.length;i++){
                        if(!period.heartValid(i)){previous=false;continue;}
                        float px=x.at(period.heartTime(i)),py=bottom-(bottom-top)*(float)((period.heart[i]-range[0])/(range[1]-range[0]));
                        if(previous)path.lineTo(px,py);else path.moveTo(px,py);
                        previous=true;p.setColor(HEART_COLOR);p.setStyle(Paint.Style.FILL);c.drawCircle(px,py,1.5f*density,p);
                    }
                    p.setColor(HEART_COLOR);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*density);c.drawPath(path,p);c.restore();
                }
                y+=106*density;
            }
            if((mask&OuraData.STAGES)!=0){
                text(c,p,"Sleep stages · "+(period.stageIntervalSeconds==30?"30 seconds":"5 minutes"),left,y+12*density,0xffe5eee6,density,11);
                float top=y+20*density,bottom=y+37*density;
                if(!period.hasStages())text(c,p,OuraData.UNAVAILABLE,left,bottom,0xffa6b9ac,density,12);
                else{
                    c.save();c.clipRect(left,top,right,bottom);
                    for(int i=0;i<period.stages.length();i++){
                        long start=period.stageStart(i);if(start>=period.end)break;int stage=period.stage(i);if(stage==0)continue;
                        long end=Math.min(period.end,start+period.stageIntervalSeconds*1000L);
                        p.setStyle(Paint.Style.FILL);p.setColor(STAGE_COLORS[stage]);c.drawRect(x.at(start),top,x.at(end),bottom,p);
                    }c.restore();
                    String[] labels={"Awake","Light","Deep","REM"};int[] stages={4,2,1,3};
                    for(int i=0;i<4;i++)text(c,p,labels[i],left+(right-left)*i/4,y+53*density,STAGE_COLORS[stages[i]],density,10);
                }y+=68*density;
            }
            y+=10*density;
        }
    }
    static double[] heartRange(OuraData.Period p){double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;for(int i=0;i<p.heart.length;i++)if(p.heartValid(i)){min=Math.min(min,p.heart[i]);max=Math.max(max,p.heart[i]);}return Double.isFinite(min)?new double[]{Math.max(0,Math.floor(min-5)),Math.ceil(max+5)}:new double[]{0,100};}
}
