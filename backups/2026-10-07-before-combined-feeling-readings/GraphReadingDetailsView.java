package com.aaron.breeze;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Native, scalable readouts kept outside the graph's fixed drawing surface. */
public final class GraphReadingDetailsView extends LinearLayout {
    private static final int INK=0xffe5eee6,MUTED=0xffa6b9ac;
    private final TextView timestamp,note;
    private final LinearLayout rows;
    private final List<ReadingCell> cells=new ArrayList<>();
    private int columns=1;
    private String renderedKey;

    public GraphReadingDetailsView(Context context){
        super(context);setOrientation(VERTICAL);setPadding(0,dp(4),0,dp(12));
        TextView heading=text("Selected reading",16,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));addView(heading,wrap());
        timestamp=text("",16,INK);timestamp.setPadding(0,dp(6),0,dp(10));addView(timestamp,wrap());
        rows=new LinearLayout(context);rows.setOrientation(VERTICAL);addView(rows,wrap());
        note=text("",16,MUTED);note.setPadding(0,dp(8),0,0);addView(note,wrap());
        setVisibility(GONE);
    }

    public void showReading(GraphView.ReadingDetails details){
        if(details==null){renderedKey=null;setVisibility(GONE);return;}
        StringBuilder key=new StringBuilder(details.timestamp).append('\n').append(details.note);
        for(GraphView.ReadingValue value:details.values)key.append('\n').append(value.label).append(':').append(value.value).append(':').append(value.color);
        if(key.toString().equals(renderedKey)){setVisibility(VISIBLE);return;}
        renderedKey=key.toString();timestamp.setText(details.timestamp);note.setText(details.note);note.setVisibility(details.note.isEmpty()?GONE:VISIBLE);
        cells.clear();for(GraphView.ReadingValue value:details.values)cells.add(new ReadingCell(value));
        rebuildRows();setVisibility(VISIBLE);requestLayout();
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
        final TextView label,value;
        ReadingCell(GraphView.ReadingValue reading){
            super(GraphReadingDetailsView.this.getContext());setOrientation(VERTICAL);setPadding(dp(2),dp(6),dp(2),dp(12));
            label=text(reading.label,16,reading.color);value=text(reading.value,26,reading.color);
            value.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));value.setPadding(0,dp(3),0,0);
            addView(label,wrap());addView(value,wrap());
        }
        float minimumWidth(){return Math.max(label.getPaint().measureText(label.getText().toString()),value.getPaint().measureText(value.getText().toString()))+getPaddingLeft()+getPaddingRight()+dp(8);}
    }
    private TextView text(String content,int size,int color){TextView view=new TextView(getContext());view.setText(content);view.setTextSize(size);view.setTextColor(color);return view;}
    private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
