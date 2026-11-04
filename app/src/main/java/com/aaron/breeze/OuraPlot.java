package com.aaron.breeze;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.util.List;
import java.util.Locale;

/** Oura overlays the existing plot; a shared-time sleep band sits above the time axis. */
final class OuraPlot {
    static final int HEART_COLOR=0xfff0a2a9;
    static final int[] STAGE_COLORS={0,0xff9288ee,0xff69bce1,0xffdf9ce5,0xffe9bd72};
    static int heightDp(List<OuraData.Period> periods,int mask){return sleepHeightDp(periods,mask);}
    static int sleepHeightDp(List<OuraData.Period> periods,int mask){return periods.isEmpty()||(mask&OuraData.STAGES)==0?0:40;}
    interface X {float at(double time);}
    private static void text(Canvas c,Paint p,String text,float x,float y,int color,float density,float size){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size*density);c.drawText(text,x,y,p);}
    static void heart(Canvas c,List<OuraData.Period> periods,int mask,float left,float right,float top,float bottom,float axisRight,boolean stacked,float density,X x){
        if(periods.isEmpty()||(mask&OuraData.HEART)==0)return;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);double[] range=heartRange(periods);
        p.setTextAlign(Paint.Align.RIGHT);text(c,p,"BPM",axisRight,(stacked?26:13)*density,HEART_COLOR,density,10);
        for(int tick=0;tick<=4;tick++){
            double value=range[0]+(range[1]-range[0])*tick/4;
            text(c,p,String.format(Locale.US,value==Math.rint(value)?"%.0f":"%.1f",value),axisRight,bottom-(bottom-top)*tick/4+(stacked?11:4)*density,HEART_COLOR,density,10);
        }
        c.save();c.clipRect(left,top,right,bottom);
        for(OuraData.Period period:periods){
            c.save();c.clipRect(Math.max(left,x.at(period.start)),top,Math.min(right,x.at(period.end)),bottom);
            Path path=new Path();boolean previous=false;
            for(int i=0;i<period.heart.length;i++){
                if(!period.heartValid(i)){previous=false;continue;}
                float px=x.at(period.heartTime(i)),py=bottom-(bottom-top)*(float)((period.heart[i]-range[0])/(range[1]-range[0]));
                if(previous)path.lineTo(px,py);else path.moveTo(px,py);
                previous=true;p.setColor(HEART_COLOR);p.setStyle(Paint.Style.FILL);c.drawCircle(px,py,1.5f*density,p);
            }
            p.setColor(HEART_COLOR);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*density);c.drawPath(path,p);c.restore();
        }c.restore();
    }
    static void sleep(Canvas c,List<OuraData.Period> periods,int mask,float y,float left,float right,float density,X x){
        if(sleepHeightDp(periods,mask)==0)return;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        text(c,p,"sleep",density,y+15*density,0xffe5eee6,density,10);
        float top=y+5*density,bottom=y+19*density;c.save();c.clipRect(left,top,right,bottom);
        for(OuraData.Period period:periods)for(int i=0;i<period.stages.length();i++){
            long start=period.stageStart(i);if(start>=period.end)break;int stage=period.stage(i);if(stage==0)continue;
            long end=Math.min(period.end,start+period.stageIntervalSeconds*1000L);
            p.setStyle(Paint.Style.FILL);p.setColor(STAGE_COLORS[stage]);c.drawRect(x.at(start),top,x.at(end),bottom,p);
        }c.restore();
        String[] labels={"Awake","Light","Deep","REM"};int[] stages={4,2,1,3};
        for(int i=0;i<4;i++)text(c,p,labels[i],left+(right-left)*i/4,y+34*density,STAGE_COLORS[stages[i]],density,12);
    }
    static double[] heartRange(List<OuraData.Period> periods){double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;for(OuraData.Period p:periods)for(int i=0;i<p.heart.length;i++)if(p.heartValid(i)){min=Math.min(min,p.heart[i]);max=Math.max(max,p.heart[i]);}return Double.isFinite(min)?new double[]{Math.max(0,Math.floor(min-5)),Math.ceil(max+5)}:new double[]{0,100};}
}
