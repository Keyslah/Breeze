package com.aaron.breeze;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class HistoryGraphHeader extends LinearLayout {
    final CheckBox sleep;final TextView time;
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm:ss",Locale.US).withZone(ZoneId.of("America/Chicago"));
    HistoryGraphHeader(Context context){
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(56));
        sleep=new CheckBox(context);sleep.setTag("historySleepOnly");sleep.setText("sleep");sleep.setTextSize(22);sleep.setTextColor(0xffe5eee6);sleep.setButtonTintList(ColorStateList.valueOf(0xff69bce1));sleep.setMinHeight(dp(48));sleep.setPadding(0,0,0,0);
        sleep.setContentDescription("Show only data inside Oura sleep periods");addView(sleep,new LayoutParams(-2,-2));
        time=new TextView(context);time.setTag("historyGraphTime");time.setTextSize(32);time.setTextColor(0xffe5eee6);time.setMaxLines(1);time.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        LayoutParams params=new LayoutParams(0,-2,1);params.setMarginStart(dp(8));addView(time,params);
    }
    void time(long value){time.setText(value>0?CLOCK.format(Instant.ofEpochMilli(value)):"—");}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
