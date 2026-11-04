package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

final class GraphSmoothingSettingsView extends LinearLayout {
    GraphSmoothingSettingsView(Context context,SharedPreferences prefs){
        super(context);setOrientation(VERTICAL);
        for(boolean room:new boolean[]{false,true}){
            int color=GraphView.COLORS[room?2:GraphView.HIGHEST_BODY_SERIES];String label=room?"Room":"Body",key=room?"historyRoomSmoothMinutes":"historyBodySmoothMinutes";
            TextView value=new TextView(context);value.setTag(key+"Label");value.setTextColor(color);value.setTextSize(18);addView(value,new LayoutParams(-1,-2));
            SeekBar slider=new SeekBar(context);slider.setTag(key);slider.setContentDescription(label+" smoothing window in minutes");slider.setMax(Settings.MAX_SMOOTH_MINUTES-Settings.MIN_SMOOTH_MINUTES);slider.setKeyProgressIncrement(1);slider.setProgressTintList(ColorStateList.valueOf(color));slider.setThumbTintList(ColorStateList.valueOf(color));
            slider.setProgress(Settings.historySmoothingMinutes(prefs,room)-Settings.MIN_SMOOTH_MINUTES);value.setText(label+" · "+Settings.historySmoothingMinutes(prefs,room)+" min");
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){int minutes=progress+Settings.MIN_SMOOTH_MINUTES;value.setText(label+" · "+minutes+" min");if(fromUser)prefs.edit().putInt(key,minutes).apply();}
                public void onStartTrackingTouch(SeekBar bar){}
                public void onStopTrackingTouch(SeekBar bar){}
            });
            addView(slider,new LayoutParams(-1,Math.round(48*getResources().getDisplayMetrics().density)));
        }
    }
}
