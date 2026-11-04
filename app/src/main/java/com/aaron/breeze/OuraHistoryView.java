package com.aaron.breeze;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Low-priority period summaries, placed below the shared graph controls. */
final class OuraHistoryView extends LinearLayout {
    interface Changed{void changed(int mask);}
    private final LinearLayout summaries;private OuraData.Cache cache=OuraData.Cache.empty();
    private int mask;private final TextView imported;
    OuraHistoryView(Context c,int initial,Changed changed){
        super(c);setOrientation(VERTICAL);mask=initial;
        summaries=new LinearLayout(c);summaries.setOrientation(VERTICAL);addView(summaries);
        imported=text("",12,0xffa6b9ac);addView(imported);render();
    }
    void mask(int value){mask=value;render();}
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
