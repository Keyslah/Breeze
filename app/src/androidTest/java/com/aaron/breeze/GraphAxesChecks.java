package com.aaron.breeze;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Off-screen fixtures: independent axes, presentation order and sparse Dreo observations. */
final class GraphAxesChecks {
    private static final long START=1800000000000L;
    private final Context context;
    private final float density;
    private GraphAxesChecks(Context context){this.context=context;density=context.getResources().getDisplayMetrics().density;}
    static void run(Context context){GraphAxesChecks checks=new GraphAxesChecks(context);checks.independentAxes();checks.readoutOrder();checks.dreoGaps();checks.bodyCutoff();checks.decimatedBodyGap();checks.preview(false);checks.preview(true);}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private int px(float value){return Math.round(value*density);}
    private GraphView graph(){GraphView graph=new GraphView(context);graph.layout(0,0,px(360),px(346));return graph;}
    private static Store.Point point(int seconds,double body,double room){Store.Point p=new Store.Point(START+seconds*1000L,body,room,45,0);p.fanSetting=seconds%11;return p;}
    private static Store.Point dreo(int seconds,double temperature,String segment){Store.Point p=point(seconds,temperature,Double.NaN);p.segment=segment;return p;}
    private static void same(double[] first,double[] second,String message){check(first[0]==second[0]&&first[1]==second[1],message);}
    private void independentAxes(){
        GraphView graph=graph();List<Store.Point> recorded=List.of(point(0,36.5,21),point(10,36.7,22),point(20,36.6,23));
        graph.data(1,recorded,List.of(point(0,-10,Double.NaN),point(20,-9,Double.NaN)),false,1<<GraphView.HIGHEST_BODY_SERIES);
        double[] body=graph.visibleTemperatureRangeC();check(body[1]-body[0]<0.3,"A 0.2°C body variation has a narrow axis");
        graph.dreoTemperatures(List.of(dreo(0,80,"first"),dreo(20,81,"first")));
        graph.visibleSeries((1<<GraphView.HIGHEST_BODY_SERIES)|(1<<GraphView.FAN_SETTING_SERIES));
        same(body,graph.visibleTemperatureRangeC(),"Fan Off–H cannot flatten body temperature");
        graph.visibleSeries((1<<GraphView.HIGHEST_BODY_SERIES)|(1<<2)|(1<<3)|(1<<4)|(1<<5)|(1<<8)|(1<<9));
        same(body,graph.visibleTemperatureRangeC(),"Room, outdoor, Dreo, humidity and fan together cannot flatten body temperature");
        double[] ambient=graph.visibleAmbientTemperatureRangeC();check(ambient[0]<-10&&ambient[1]>81,"All enabled ambient sources use their own temperature axis");
        check(Math.abs(graph.fanSettingY(0)-px(320))<1&&Math.abs(graph.fanSettingY(10)-px(30))<1,"Fan range stays mapped to the existing plot rectangle");
        graph.setSmoothHighestBody(true);GraphView.ReadingDetails[] details={null};graph.setReadingDetailsListener(value->details[0]=value);
        check(details[0].values.get(6).label.equals("Highest body temp"),"Smoothing does not append a parenthetical label");
        check(graph.getContentDescription().toString().contains("independent ambient temperature axis"),"Axis mapping is explicitly described");
    }
    private static void collect(View view,List<CheckBox> checks){if(view instanceof CheckBox)checks.add((CheckBox)view);else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++)collect(group.getChildAt(index),checks);}}
    private void readoutOrder(){
        for(int width:new int[]{240,400}){
            GraphReadingDetailsView panel=new GraphReadingDetailsView(context);panel.measure(View.MeasureSpec.makeMeasureSpec(px(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            List<CheckBox> controls=new ArrayList<>();collect(panel,controls);int[] expected={6,2,8,9,3,5,4,0,1,7};
            check(controls.size()==expected.length,"All ten readings remain available");
            for(int at=0;at<expected.length;at++)check(controls.get(at).getTag().equals("historySeries"+expected[at]),"Primary readings precede secondary readings at width "+width+"dp");
            String[] shortNames={"body","room","fan","dreo"};
            android.view.ViewParent primary=controls.get(0).getParent().getParent();
            for(int at=0;at<4;at++){
                check(controls.get(at).getText().toString().equals(shortNames[at]),"Primary label is concise: "+shortNames[at]);
                check(controls.get(at).getParent().getParent()==primary,"The existing first four readings share one row at "+width+"dp");
            }
            check(!controls.get(3).isChecked(),"New Dreo trace is off by default");
            final int[] selected={-1};panel.setOnSeriesChangedListener((series,checked)->selected[0]=series);controls.get(0).performClick();
            check(selected[0]==GraphView.HIGHEST_BODY_SERIES,"Display reordering preserves persisted series identity");
        }
    }
    private boolean colorNear(Bitmap image,int color,float x,float y){for(int row=Math.max(0,Math.round(y)-px(1));row<=Math.min(image.getHeight()-1,Math.round(y)+px(1));row++)for(int col=Math.max(0,Math.round(x)-px(1));col<=Math.min(image.getWidth()-1,Math.round(x)+px(1));col++)if(image.getPixel(col,row)==color)return true;return false;}
    private void dreoGaps(){
        GraphView graph=graph();graph.data(2,List.of(point(0,36,22),point(1200,36,22)),List.of(),false,1<<GraphView.DREO_TEMPERATURE_SERIES);
        graph.dreoTemperatures(List.of(dreo(0,30,"first"),dreo(180,30,"first"),dreo(360,30,"second"),dreo(540,Double.NaN,"second"),dreo(720,30,"second"),dreo(1200,30,"second")));
        check(Double.isNaN(graph.dreoTemperatureAt(START-1)),"A future Dreo observation is never shown as earlier history");
        check(graph.dreoTemperatureAt(START+180000)==30,"Dreo readout uses its received observation");
        check(Double.isNaN(graph.dreoTemperatureAt(START+550000)),"An unavailable observation clears the Dreo readout");
        check(Double.isNaN(graph.dreoTemperatureAt(START+1000000)),"A Dreo value more than four minutes old is unavailable");
        Bitmap image=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);graph.draw(new Canvas(image));
        int color=GraphView.COLORS[GraphView.DREO_TEMPERATURE_SERIES];
        try{
            check(colorNear(image,color,graph.feelingX(START+90000),px(175)),"Consecutive successful observations join within one polling run");
            for(int seconds:new int[]{270,450,630,960})check(!colorNear(image,color,graph.feelingX(START+seconds*1000L),px(175)),"Dreo trace does not bridge a run change, missing reading or long time gap at "+seconds+" seconds");
            check(colorNear(image,color,graph.feelingX(START+360000),px(175)),"An isolated observation remains visible as a point");
        }finally{image.recycle();}
        graph.data(3,List.of(point(0,36,22)),List.of(),false,1<<9);
        check(Double.isNaN(graph.dreoTemperatureAt(START)),"Changing nights clears the preceding night's cloud observations");
    }
    private void bodyCutoff(){
        GraphView graph=graph();GraphView.ReadingDetails[] details={null};graph.setReadingDetailsListener(value->details[0]=value);
        double cutoff=(90d-32)/1.8;Store.Point exact=point(0,cutoff,20);exact.secondProbe=cutoff;exact.thirdProbe=cutoff;
        graph.data(5,List.of(exact),List.of(),true,GraphView.ALL_SERIES);
        for(int series:new int[]{0,1,6,7})check(details[0].values.get(series).value.equals("90.0°F"),"Exactly 90°F remains an eligible body reading for series "+series);
        graph.data(5,List.of(exact),List.of(),false,GraphView.ALL_SERIES);
        check(details[0].values.get(6).value.equals("32.2°C"),"The cutoff is independent of display units");
        Store.Point below=point(0,cutoff-0.000001,20);below.secondProbe=31;below.thirdProbe=25;below.fanSetting=3;
        graph.data(6,List.of(below),List.of(),true,GraphView.ALL_SERIES);graph.dreoTemperatures(List.of(dreo(0,10,"cutoff")));
        for(int series:new int[]{0,1,6,7})check(details[0].values.get(series).value.equals("—"),"Below 90°F is excluded from body readout "+series);
        check(details[0].values.get(2).value.equals("68.0°F")&&details[0].values.get(9).value.equals("50.0°F")&&details[0].values.get(8).value.equals("3"),"Cutoff does not filter ambient temperatures or fan levels");
        check(graph.visibleTemperatureRangeC()[0]>=cutoff,"All off-body readings cannot expand the body axis below 90°F");
        check(below.probe==cutoff-0.000001&&below.secondProbe==31&&below.thirdProbe==25,"Display filtering never modifies recorded samples");
        List<Store.Point> gap=List.of(point(0,33,20),point(10,31,20),point(20,35,20));
        graph.data(7,gap,List.of(),false,1<<6);graph.setSmoothHighestBody(true);
        check(details[0].values.get(6).value.equals("35.0°C"),"Smoothing cannot average across an off-body gap");
        double[] range=graph.visibleTemperatureRangeC();check(range[0]>cutoff&&range[1]>35,"Body range excludes the off-body low sample while retaining eligible readings");
        Store.FeelingEntry feeling=new Store.FeelingEntry();feeling.id=1;feeling.session=7;feeling.time=START+10000;feeling.kind=Store.FEELING_HOT;feeling.intensity=5;feeling.signedValue=5;graph.feelings(List.of(feeling));
        try{java.lang.reflect.Field selected=GraphView.class.getDeclaredField("selectedFeeling");selected.setAccessible(true);selected.setLong(graph,1);java.lang.reflect.Method notify=GraphView.class.getDeclaredMethod("notifyReadingDetails");notify.setAccessible(true);notify.invoke(graph);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
        check(details[0].values.get(0).value.equals("—")&&details[0].values.get(6).value.equals("—"),"Feeling inspection does not reach across an off-body sample to invent a body reading");
    }
    private void decimatedBodyGap(){
        // Keep enough samples to decimate, with a visible gap at an index the stride skips.
        List<Store.Point> samples=new ArrayList<>();
        for(int at=0;at<3002;at++){long millis=at<=1500?at:at==1501?15000:30000+at-1502;Store.Point sample=new Store.Point(START+millis,at==1501?31:33,20,50,0);samples.add(sample);}
        GraphView graph=graph();graph.data(8,samples,List.of(),false,1);Bitmap image=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);graph.draw(new Canvas(image));
        try{
            int color=GraphView.COLORS[0];check(!colorNear(image,color,graph.feelingX(START+15000),px(175)),"Decimation does not bridge a below-90°F reading between retained points");
            check(colorNear(image,color,graph.feelingX(START+30500),px(175)),"Eligible body readings after the gap remain visible");
        }finally{image.recycle();}
    }
    /** Synthetic off-screen render for emulator QA; this never captures the user's display. */
    private void preview(boolean feelings){
        GraphView graph=graph();List<Store.Point> samples=new ArrayList<>(),cloud=new ArrayList<>();
        for(int second=0;second<=1200;second+=10){Store.Point p=point(second,36.5+0.08*Math.sin(second/90.0),22+0.2*Math.sin(second/180.0));p.secondProbe=p.probe-0.03;p.thirdProbe=p.probe-0.06;p.humidity=55+10*Math.sin(second/300.0);p.fanSetting=second<240?3:second<600?5:second<900?4:2;samples.add(p);}
        for(int second=0;second<=1200;second+=180)cloud.add(dreo(second,23+0.2*Math.cos(second/180.0),"preview"));
        graph.data(4,samples,List.of(point(0,18,Double.NaN),point(1200,18.5,Double.NaN)),true,GraphView.ALL_SERIES);graph.dreoTemperatures(cloud);
        if(feelings){Store.FeelingEntry warm=new Store.FeelingEntry();warm.id=1;warm.session=4;warm.time=START+600000;warm.kind=Store.FEELING_HOT;warm.intensity=7;warm.signedValue=7;graph.feelings(List.of(warm));}
        Bitmap image=Bitmap.createBitmap(px(360),px(346),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(0xff20352b);graph.draw(canvas);
        try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),feelings?"graph-axes-feelings.png":"graph-axes-combined.png"))){check(image.compress(Bitmap.CompressFormat.PNG,100,output),"Write synthetic axis preview");}
        catch(java.io.IOException error){throw new AssertionError("Could not write synthetic graph preview",error);}finally{image.recycle();}
    }
}
