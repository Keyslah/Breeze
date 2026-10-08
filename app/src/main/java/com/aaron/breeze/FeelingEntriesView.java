package com.aaron.breeze;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Phone-owned annotations, independent of controller recording and download state. */
public final class FeelingEntriesView extends LinearLayout {
    private static final int INK=0xffe5eee6,MUTED=0xffa6b9ac,TEAL=0xff9ac5a4,CARD=0xff20352b;
    private final Store store;
    private final Store.Session session;
    private final Runnable changed;
    private final LinearLayout entries;
    private String renderedEntries;

    public FeelingEntriesView(Context context,Store store,Store.Session session,Runnable changed){
        super(context);this.store=store;this.session=session;this.changed=changed;
        setOrientation(VERTICAL);
        TextView title=text("How you felt",20,INK);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));addView(title);
        Button add=button("Add feeling entry",true);add.setOnClickListener(v->edit(null));addView(add,buttonParams());
        entries=new LinearLayout(context);entries.setOrientation(VERTICAL);addView(entries);refreshEntries();
    }

    public void refreshEntries(){
        List<Store.FeelingEntry> saved=store.feelings(session.id);StringBuilder signature=new StringBuilder();for(Store.FeelingEntry entry:saved)signature.append(entry.id).append(':').append(entry.time).append(':').append(entry.kind).append(':').append(entry.intensity).append(';');String next=signature.toString();if(next.equals(renderedEntries))return;renderedEntries=next;entries.removeAllViews();
        if(saved.isEmpty()){TextView empty=text("No feeling entries for this night yet.",13,MUTED);empty.setPadding(0,dp(12),0,dp(3));entries.addView(empty);return;}
        for(Store.FeelingEntry entry:saved){
            Button item=button(new SimpleDateFormat("MMM d, yyyy · h:mm:ss a",Locale.US).format(new Date(entry.time))+"\n"+entry.kind+" · "+entry.intensity+" / 10",false);
            item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.setContentDescription("Edit feeling entry: "+item.getText());item.setOnClickListener(v->edit(entry));entries.addView(item,buttonParams());
        }
    }

    private void edit(Store.FeelingEntry entry){
        Context context=getContext();Calendar moment=Calendar.getInstance();moment.setTimeInMillis(entry==null?session.start:entry.time);if(entry==null){moment.set(Calendar.SECOND,0);moment.set(Calendar.MILLISECOND,0);}
        LinearLayout form=new LinearLayout(context);form.setOrientation(VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(8));form.setBackgroundColor(CARD);
        form.addView(text("Date and time",14,INK));LinearLayout dateTime=new LinearLayout(context);dateTime.setOrientation(HORIZONTAL);
        Button date=button("",false),time=button("",false);Runnable showMoment=()->{date.setText(new SimpleDateFormat("MMM d, yyyy",Locale.US).format(moment.getTime()));time.setText(new SimpleDateFormat("h:mm a",Locale.US).format(moment.getTime()));};showMoment.run();
        date.setContentDescription("Choose feeling entry date");time.setContentDescription("Choose feeling entry time");
        date.setOnClickListener(v->new DatePickerDialog(context,(picker,year,month,day)->{moment.set(Calendar.YEAR,year);moment.set(Calendar.MONTH,month);moment.set(Calendar.DAY_OF_MONTH,day);showMoment.run();},moment.get(Calendar.YEAR),moment.get(Calendar.MONTH),moment.get(Calendar.DAY_OF_MONTH)).show());
        time.setOnClickListener(v->new TimePickerDialog(context,(picker,hour,minute)->{moment.set(Calendar.HOUR_OF_DAY,hour);moment.set(Calendar.MINUTE,minute);moment.set(Calendar.SECOND,0);moment.set(Calendar.MILLISECOND,0);showMoment.run();},moment.get(Calendar.HOUR_OF_DAY),moment.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(context)).show());
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(56),1);half.setMargins(dp(2),dp(6),dp(2),dp(8));dateTime.addView(date,half);dateTime.addView(time,new LinearLayout.LayoutParams(half));form.addView(dateTime);
        form.addView(text("Feeling",14,INK));RadioGroup kind=new RadioGroup(context);kind.setOrientation(HORIZONTAL);RadioButton hot=radio(Store.FEELING_HOT),cold=radio(Store.FEELING_COLD);kind.addView(hot,new RadioGroup.LayoutParams(0,dp(52),1));kind.addView(cold,new RadioGroup.LayoutParams(0,dp(52),1));kind.check(entry!=null&&Store.FEELING_COLD.equals(entry.kind)?cold.getId():hot.getId());form.addView(kind);
        TextView rating=text("",16,INK);form.addView(rating);SeekBar intensity=new SeekBar(context);intensity.setMax(9);intensity.setProgress(entry==null?4:entry.intensity-1);intensity.setProgressTintList(ColorStateList.valueOf(TEAL));intensity.setThumbTintList(ColorStateList.valueOf(TEAL));intensity.setContentDescription("Feeling intensity from 1 to 10");
        Runnable showRating=()->{String label=kind.getCheckedRadioButtonId()==cold.getId()?Store.FEELING_COLD:Store.FEELING_HOT;rating.setText(label+" intensity: "+(intensity.getProgress()+1)+" / 10");};showRating.run();
        intensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){showRating.run();}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});kind.setOnCheckedChangeListener((group,id)->showRating.run());form.addView(intensity,new LinearLayout.LayoutParams(-1,dp(48)));form.addView(text("1 · Slight                      10 · Extreme",12,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle(entry==null?"Add feeling entry":"Edit feeling entry").setView(form).setPositiveButton("Save entry",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{String selected=kind.getCheckedRadioButtonId()==cold.getId()?Store.FEELING_COLD:Store.FEELING_HOT;long timestamp=moment.getTimeInMillis();if(entry==null)store.addFeeling(session.id,timestamp,selected,intensity.getProgress()+1);else store.updateFeeling(entry.id,session.id,timestamp,selected,intensity.getProgress()+1);refreshEntries();if(changed!=null)changed.run();dialog.dismiss();Toast.makeText(context,"Feeling entry saved.",Toast.LENGTH_SHORT).show();}
            catch(RuntimeException error){new AlertDialog.Builder(context).setTitle("Entry not saved").setMessage(error.getMessage()==null?"Could not save this entry. Please try again.":error.getMessage()).setPositiveButton("OK",null).show();}
        }));dialog.show();
    }
    private RadioButton radio(String value){RadioButton choice=new RadioButton(getContext());choice.setId(View.generateViewId());choice.setText(value);choice.setTextColor(INK);choice.setButtonTintList(ColorStateList.valueOf(TEAL));return choice;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
    private TextView text(String value,int size,int color){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(3),0,dp(3));return view;}
    private Button button(String title,boolean primary){Button button=new Button(getContext());button.setText(title);button.setTextSize(13);button.setAllCaps(false);button.setTextColor(primary?CARD:TEAL);button.setBackground(background(primary?TEAL:0xff2c4436,14));button.setMinHeight(dp(48));button.setPadding(dp(12),dp(6),dp(12),dp(6));return button;}
    private LinearLayout.LayoutParams buttonParams(){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);return params;}
}
