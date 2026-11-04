package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class GraphSmoothingChecks {
    private static final long START=1793685600000L;
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    static void run(Context context)throws Exception{
        String name="temporary-graph-smoothing-"+System.nanoTime();SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        try{
            GraphView graph=new GraphView(context);float density=context.getResources().getDisplayMetrics().density;graph.layout(0,0,Math.round(360*density),Math.round(346*density));
            GraphSmoothingControls controls=new GraphSmoothingControls(context,prefs,graph);
            check(controls.getChildCount()==2&&((CheckBox)controls.getChildAt(0)).isChecked()&&((CheckBox)controls.getChildAt(1)).isChecked(),"Both smoothing switches start checked in one row");
            check(Settings.historySmoothingMinutes(prefs,false)==2&&Settings.historySmoothingMinutes(prefs,true)==2,"Both default to a 2-minute window");
            List<Store.Point> samples=new ArrayList<>();for(int seconds=0;seconds<=180;seconds+=10)samples.add(new Store.Point(START+seconds*1000L,seconds==60?42:34,seconds==60?40:20,50,0));
            graph.data(1,samples,List.of(),false,(1<<6)|(1<<2));GraphView.ReadingDetails[] reading={null};graph.setReadingDetailsListener(value->reading[0]=value);
            Method window=GraphView.class.getDeclaredMethod("setWindow",double.class,double.class);window.setAccessible(true);window.invoke(graph,(double)START+30000,120000d);
            Method select=GraphView.class.getDeclaredMethod("selectAt",float.class,float.class);select.setAccessible(true);select.invoke(graph,graph.feelingX(START+60000),100*density);
            check(reading[0].values.get(6).value.equals("34.6°C")&&reading[0].values.get(2).value.equals("21.5°C"),"Body and room use their own centered averages");
            check(graph.visibleAmbientTemperatureRangeC()[1]<23,"Room autoscale follows smoothed room readings");
            double start=graph.visibleStartTime(),end=graph.visibleEndTime();
            graph.smoothingWindows(2,1);check(reading[0].values.get(2).value.equals("22.9°C")&&reading[0].values.get(6).value.equals("34.6°C"),"Changing room strength does not change body smoothing");
            ((CheckBox)controls.getChildAt(1)).setChecked(false);check(reading[0].values.get(2).value.equals("40.0°C")&&!Settings.historySmoothRoom(prefs),"Room switch restores raw readings and saves the choice");
            check(reading[0].values.get(6).value.equals("34.6°C")&&samples.get(6).room==40,"Room switch leaves body and raw samples intact");
            check(graph.visibleStartTime()==start&&graph.visibleEndTime()==end,"Strength and checkbox changes preserve zoom");
            GraphSmoothingSettingsView sliders=new GraphSmoothingSettingsView(context,prefs);
            SeekBar body=sliders.findViewWithTag("historyBodySmoothMinutes"),room=sliders.findViewWithTag("historyRoomSmoothMinutes");
            body.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            check(Settings.historySmoothingMinutes(prefs,false)==3&&Settings.historySmoothingMinutes(prefs,true)==2,"Body slider persists independently");
            for(int i=0;i<30;i++)room.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            check(Settings.historySmoothingMinutes(prefs,true)==20&&Settings.historySmoothingMinutes(prefs,false)==3,"Room slider stops at 20 minutes without changing body strength");
            GraphSmoothingControls restored=new GraphSmoothingControls(context,prefs,graph);check(!((CheckBox)restored.getChildAt(1)).isChecked(),"An explicitly unchecked room choice survives reopening");
            int width=Math.round(360*density);sliders.setBackgroundColor(0xff20352b);sliders.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));sliders.layout(0,0,width,sliders.getMeasuredHeight());
            Bitmap image=Bitmap.createBitmap(width,sliders.getMeasuredHeight(),Bitmap.Config.ARGB_8888);sliders.draw(new Canvas(image));
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getCacheDir(),"graph-smoothing-settings.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
        }finally{context.deleteSharedPreferences(name);}
    }
}
