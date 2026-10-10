package com.aaron.breeze;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Native, scalable readouts kept outside the graph's fixed drawing surface. */
public final class GraphReadingDetailsView extends LinearLayout {
    public interface SeriesChangedListener {void onSeriesChanged(int series,boolean checked);}
    private static final int INK=0xffe5eee6,MUTED=0xffa6b9ac;
    private final TextView feeling,note;
    private final LinearLayout rows;
    private final List<ReadingCell> cells=new ArrayList<>();
    private int columns=1;
    private int visibleSeries=GraphView.DEFAULT_SERIES;
    private boolean syncingSeries;
    private SeriesChangedListener seriesChangedListener;

    public GraphReadingDetailsView(Context context){
        super(context);setOrientation(VERTICAL);setPadding(0,0,0,dp(4));
        rows=new LinearLayout(context);rows.setOrientation(VERTICAL);addView(rows,wrap());
        for(int series=0;series<GraphView.LABELS.length;series++)cells.add(new ReadingCell(series));
        rebuildRows();
        feeling=text("",18,INK);feeling.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));feeling.setPadding(dp(2),dp(4),dp(2),0);feeling.setVisibility(GONE);addView(feeling,wrap());
        note=text("",13,MUTED);note.setPadding(0,dp(4),0,0);addView(note,wrap());
        note.setVisibility(GONE);
    }

    public void setOnSeriesChangedListener(SeriesChangedListener listener){seriesChangedListener=listener;}
    public void setVisibleSeries(int mask){
        visibleSeries=mask&GraphView.ALL_SERIES;syncingSeries=true;
        try{for(int series=0;series<cells.size();series++)cells.get(series).label.setChecked((visibleSeries&(1<<series))!=0);}
        finally{syncingSeries=false;}
    }

    public void showReading(GraphView.ReadingDetails details){
        for(int series=0;series<cells.size();series++){
            String value=details!=null&&series<details.values.size()?details.values.get(series).value:emptyValue(series);
            if(!value.contentEquals(cells.get(series).value.getText()))cells.get(series).value.setText(value);
        }
        String message=details==null?"":details.note;
        if(!message.contentEquals(note.getText()))note.setText(message);note.setVisibility(message.isEmpty()?GONE:VISIBLE);
        GraphView.ReadingValue rating=details==null?null:details.feeling;
        feeling.setVisibility(rating==null?GONE:VISIBLE);
        if(rating!=null){String messageFeeling=rating.label+" · "+rating.value;if(!messageFeeling.contentEquals(feeling.getText()))feeling.setText(messageFeeling);feeling.setTextColor(rating.color);}
    }

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        int available=MeasureSpec.getSize(widthMeasureSpec)-getPaddingLeft()-getPaddingRight();
        float minimum=dp(140);for(ReadingCell cell:cells)minimum=Math.max(minimum,cell.minimumWidth());
        int desired=cells.size()>1&&MeasureSpec.getMode(widthMeasureSpec)!=MeasureSpec.UNSPECIFIED&&available>=minimum*2+dp(12)?2:1;
        if(columns!=desired){columns=desired;rebuildRows();}
        super.onMeasure(widthMeasureSpec,heightMeasureSpec);
    }

    private void rebuildRows(){
        for(ReadingCell cell:cells)if(cell.getParent() instanceof ViewGroup)((ViewGroup)cell.getParent()).removeView(cell);
        rows.removeAllViews();
        for(int index=0;index<cells.size();index+=columns){
            LinearLayout row=new LinearLayout(getContext());row.setOrientation(HORIZONTAL);
            for(int column=0;column<columns;column++){
                int at=index+column;View cell=at<cells.size()?cells.get(at):new View(getContext());
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1);
                if(column<columns-1)params.setMarginEnd(dp(12));row.addView(cell,params);
            }
            rows.addView(row,wrap());
        }
    }

    private final class ReadingCell extends LinearLayout {
        final CheckBox label;final TextView value;
        ReadingCell(int series){
            super(GraphReadingDetailsView.this.getContext());setOrientation(VERTICAL);setPadding(dp(2),0,dp(2),dp(2));
            label=new CheckBox(getContext());label.setText(GraphView.LABELS[series]);label.setTextSize(16);label.setTextColor(GraphView.COLORS[series]);
            label.setButtonTintList(ColorStateList.valueOf(GraphView.COLORS[series]));label.setMinWidth(0);label.setMinimumWidth(0);label.setMinHeight(dp(48));label.setMinimumHeight(dp(48));
            label.setPadding(0,0,0,0);label.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);label.setContentDescription(GraphView.LABELS[series]+" trace");label.setChecked((visibleSeries&(1<<series))!=0);
            label.setOnCheckedChangeListener((button,checked)->{
                if(syncingSeries)return;
                visibleSeries=checked?visibleSeries|(1<<series):visibleSeries&~(1<<series);
                if(seriesChangedListener!=null)seriesChangedListener.onSeriesChanged(series,checked);
            });
            value=text(emptyValue(series),26,GraphView.COLORS[series]);value.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            value.setPadding(label.getCompoundPaddingLeft(),0,0,0);
            addView(label,wrap());addView(value,wrap());
        }
        float minimumWidth(){
            float word=0;for(String part:label.getText().toString().split(" "))word=Math.max(word,label.getPaint().measureText(part));
            float labelWidth=word+label.getCompoundPaddingLeft()+label.getCompoundPaddingRight();
            float valueWidth=Math.max(value.getPaint().measureText("100.0°F"),value.getPaint().measureText("Unknown"))+value.getPaddingLeft()+value.getPaddingRight();
            return Math.max(labelWidth,valueWidth)+getPaddingLeft()+getPaddingRight()+dp(4);
        }
    }
    private String emptyValue(int series){return series==GraphView.FAN_SETTING_SERIES?"Unknown":"—";}
    private TextView text(String content,int size,int color){TextView view=new TextView(getContext());view.setText(content);view.setTextSize(size);view.setTextColor(color);return view;}
    private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
