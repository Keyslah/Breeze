package com.aaron.breeze;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native History controls and explicitly labeled summaries, independent of cursor selection. */
final class OuraHistoryView extends LinearLayout {
    interface Changed{void changed(int mask);}
    private final LinearLayout summaries;private OuraData.Cache cache=OuraData.Cache.empty();
    private int mask;private final TextView imported;
    OuraHistoryView(Context c,int initial,Changed changed){
        super(c);setOrientation(VERTICAL);mask=initial;
        TextView title=text("Oura",18,0xffe5eee6);addView(title);
        String[] labels={"deviation","BPM","resp. rate","sleep"};
        int[] bits={OuraData.TEMPERATURE,OuraData.HEART,OuraData.BREATH,OuraData.STAGES};
        int[] colors={0xffe9bd72,OuraPlot.HEART_COLOR,0xff79d5c3,0xff69bce1};
        LinearLayout controls=new LinearLayout(c);controls.setOrientation(HORIZONTAL);addView(controls);
        for(int i=0;i<labels.length;i++){
            final int bit=bits[i];CheckBox box=new CheckBox(c);box.setText(labels[i]);box.setContentDescription(new String[]{"Oura temperature deviation","BPM","Oura respiratory rate","sleep"}[i]);box.setTag("historyOura"+bit);box.setTextSize(13);box.setTextColor(colors[i]);box.setButtonTintList(ColorStateList.valueOf(colors[i]));box.setMinWidth(0);box.setMinimumWidth(0);box.setPadding(0,0,0,0);
            box.setMinimumHeight(dp(48));box.setChecked((mask&bit)!=0);box.setOnCheckedChangeListener((button,checked)->{mask=checked?mask|bit:mask&~bit;render();changed.changed(mask);});controls.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        }
        summaries=new LinearLayout(c);summaries.setOrientation(VERTICAL);addView(summaries);
        imported=text("",12,0xffa6b9ac);addView(imported);render();
    }
    void data(OuraData.Cache value){cache=value;render();}
    private void render(){
        if(summaries==null)return;summaries.removeAllViews();
        if(cache.periods.isEmpty()){
            if((mask&OuraData.TEMPERATURE)!=0)add("Oura temperature deviation: "+OuraData.UNAVAILABLE);
            if((mask&OuraData.HEART)!=0)add("BPM: "+OuraData.UNAVAILABLE);
            if((mask&OuraData.BREATH)!=0)add("Oura respiratory rate: "+OuraData.UNAVAILABLE);
            if((mask&OuraData.STAGES)!=0)add("sleep: "+OuraData.UNAVAILABLE);
        }else{
            int index=0;for(OuraData.Period period:cache.periods){
                if(mask==0)break;add("Sleep period "+(++index)+" · "+period.label());
                if((mask&OuraData.TEMPERATURE)!=0)add(period.temperatureLabel()+" · nightly summary");
                if((mask&OuraData.BREATH)!=0)add(period.breathLabel());
                if((mask&OuraData.HEART)!=0&&period.heartCount()==0)add("BPM: "+OuraData.UNAVAILABLE);
                if((mask&OuraData.STAGES)!=0&&!period.hasStages())add("sleep: "+OuraData.UNAVAILABLE);
            }
        }
        imported.setText(cache.fetched?"Last successful import: "+OuraData.time(cache.importedAt)+" · "+cache.periods.size()+" sleep periods":"No cached import for this recorded night.");
    }
    private void add(String value){summaries.addView(text(value,14,0xffe5eee6));}
    private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
