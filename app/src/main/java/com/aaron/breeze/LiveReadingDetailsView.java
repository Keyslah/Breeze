package com.aaron.breeze;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** The History readout style, without trace controls, for the controller's live values. */
public final class LiveReadingDetailsView extends LinearLayout {
    private static final int[] SERIES={0,1,GraphView.D2_BODY_SERIES,GraphView.HIGHEST_BODY_SERIES,2,4};
    private final TextView[] values=new TextView[SERIES.length];

    public LiveReadingDetailsView(Context context){
        super(context);setOrientation(VERTICAL);setPadding(dp(10),dp(6),dp(10),dp(6));
        for(int row=0;row<3;row++){
            LinearLayout line=new LinearLayout(context);line.setOrientation(HORIZONTAL);
            for(int column=0;column<2;column++){
                int index=row*2+column,series=SERIES[index];
                LinearLayout cell=new LinearLayout(context);cell.setOrientation(VERTICAL);cell.setGravity(Gravity.CENTER_VERTICAL);cell.setPadding(dp(2),0,dp(2),dp(2));
                TextView label=new TextView(context);label.setText(GraphView.LABELS[series]);label.setTextSize(16);label.setTextColor(GraphView.COLORS[series]);label.setMaxLines(2);label.setTag("homeReadingLabel"+series);
                TextView value=new TextView(context);value.setText("—");value.setTextSize(26);value.setTextColor(GraphView.COLORS[series]);value.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));value.setSingleLine();value.setTag("homeReadingValue"+series);values[index]=value;
                cell.addView(label,new LayoutParams(-1,-2));cell.addView(value,new LayoutParams(-1,-2));
                LayoutParams slot=new LayoutParams(0,-1,1);if(column==0)slot.setMarginEnd(dp(12));line.addView(cell,slot);
            }
            addView(line,new LayoutParams(-1,0,1));
        }
    }

    public void showReading(Protocol.Reading reading,Protocol.FanState fan,boolean fahrenheit){
        double d0=reading==null?Double.NaN:reading.probe,d1=fan==null?Double.NaN:fan.secondProbe,d2=reading==null?Double.NaN:reading.thirdProbe;
        double highest=Double.NaN;for(double body:new double[]{d0,d1,d2})if(Double.isFinite(body))highest=Double.isFinite(highest)?Math.max(highest,body):body;
        double[] numbers={d0,d1,d2,highest,reading==null?Double.NaN:reading.room,reading==null?Double.NaN:reading.humidity};
        for(int index=0;index<values.length;index++){
            double number=numbers[index];String display=!Double.isFinite(number)?"—":SERIES[index]==4?String.format(Locale.US,"%.1f%%",number):String.format(Locale.US,"%.1f°%s",fahrenheit?number*1.8+32:number,fahrenheit?"F":"C");
            if(!display.contentEquals(values[index].getText()))values[index].setText(display);
        }
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
