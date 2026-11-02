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
        String[] labels={"Temperature deviation","Overnight heart rate","Respiratory rate","Sleep stages"};
        int[] bits={OuraData.TEMPERATURE,OuraData.HEART,OuraData.BREATH,OuraData.STAGES};
        int[] colors={0xffe9bd72,OuraPlot.HEART_COLOR,0xff79d5c3,0xff69bce1};
        for(int i=0;i<labels.length;i++){
            final int bit=bits[i];CheckBox box=new CheckBox(c);box.setText(labels[i]);box.setTag("historyOura"+bit);box.setTextSize(16);box.setTextColor(colors[i]);box.setButtonTintList(ColorStateList.valueOf(colors[i]));
            box.setMinimumHeight(dp(48));box.setChecked((mask&bit)!=0);box.setOnCheckedChangeListener((button,checked)->{mask=checked?mask|bit:mask&~bit;render();changed.changed(mask);});addView(box);
        }
        summaries=new LinearLayout(c);summaries.setOrientation(VERTICAL);addView(summaries);
        imported=text("",12,0xffa6b9ac);addView(imported);render();
    }
    void data(OuraData.Cache value){cache=value;render();}
    private void render(){
        if(summaries==null)return;summaries.removeAllViews();
        if(cache.periods.isEmpty()){
            if((mask&OuraData.TEMPERATURE)!=0)add("Oura temperature deviation: "+OuraData.UNAVAILABLE);
            if((mask&OuraData.HEART)!=0)add("Overnight heart rate: "+OuraData.UNAVAILABLE);
            if((mask&OuraData.BREATH)!=0)add("Oura respiratory rate: "+OuraData.UNAVAILABLE);
            if((mask&OuraData.STAGES)!=0)add("Sleep stages: "+OuraData.UNAVAILABLE);
        }else{
            int index=0;for(OuraData.Period period:cache.periods){
                if(mask==0)break;add("Sleep period "+(++index)+" · "+period.label());
                if((mask&OuraData.TEMPERATURE)!=0)add(period.temperatureLabel()+" · nightly summary");
                if((mask&OuraData.BREATH)!=0)add(period.breathLabel());
                if((mask&OuraData.HEART)!=0)add(period.heartCount()==0?"Overnight heart rate: "+OuraData.UNAVAILABLE:"Overnight heart rate: BPM timeline above");
                if((mask&OuraData.STAGES)!=0)add(period.hasStages()?"Sleep stages: timeline above":"Sleep stages: "+OuraData.UNAVAILABLE);
            }
        }
        imported.setText(cache.fetched?"Last successful import: "+OuraData.time(cache.importedAt)+" · "+cache.periods.size()+" sleep periods":"No cached import for this recorded night.");
    }
    private void add(String value){summaries.addView(text(value,14,0xffe5eee6));}
    private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
