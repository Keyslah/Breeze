package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.widget.CheckBox;
import android.widget.LinearLayout;

/** The two local graph smoothing switches share one row. */
final class GraphSmoothingControls extends LinearLayout {
    GraphSmoothingControls(Context context,SharedPreferences prefs,GraphView graph){
        super(context);setOrientation(HORIZONTAL);setBaselineAligned(false);setGravity(android.view.Gravity.CENTER_VERTICAL);
        graph.smoothingWindows(Settings.historySmoothingMinutes(prefs,false),Settings.historySmoothingMinutes(prefs,true));
        graph.setSmoothHighestBody(Settings.historySmoothHighestBody(prefs));graph.setSmoothRoom(Settings.historySmoothRoom(prefs));
        for(boolean room:new boolean[]{false,true}){
            String key=room?"historySmoothRoom":"historySmoothHighestBody";
            int color=GraphView.COLORS[room?2:GraphView.HIGHEST_BODY_SERIES];
            CheckBox box=new CheckBox(context);box.setTag(key);box.setText(room?"Smooth room temp":"Smooth highest body temp");box.setTextSize(14);box.setTextColor(color);box.setButtonTintList(ColorStateList.valueOf(color));box.setPadding(0,0,0,0);box.setMinimumHeight(dp(56));
            box.setChecked(room?Settings.historySmoothRoom(prefs):Settings.historySmoothHighestBody(prefs));
            box.setOnCheckedChangeListener((button,checked)->{prefs.edit().putBoolean(key,checked).apply();if(room)graph.setSmoothRoom(checked);else graph.setSmoothHighestBody(checked);});
            addView(box,new LayoutParams(0,-2,1));
        }
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
