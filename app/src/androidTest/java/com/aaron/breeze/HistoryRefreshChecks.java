package com.aaron.breeze;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** No Activity launch, real database, Bluetooth, microphone or device input. */
final class HistoryRefreshChecks {
    private static final long START=1800000000000L;
    private interface Action {void run() throws Exception;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void main(Instrumentation test,Action action){Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    private static Field field(String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void invoke(MainActivity activity,String name)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
    private static boolean text(View view,String expected){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(expected))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++)if(text(group.getChildAt(index),expected))return true;}return false;}
    private static final class FixtureActivity extends MainActivity {
        // An off-screen fixture has no Window. Activity.setTheme() dereferences its Window;
        // all fixture widgets instead use the supplied themed base Context directly.
        @Override public Resources.Theme getTheme(){return getBaseContext().getTheme();}
    }
    private static final class QueueWorker extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();boolean closed;
        @Override public void execute(Runnable task){if(closed)throw new java.util.concurrent.RejectedExecutionException();tasks.add(task);}
        void runNext(){check(Looper.myLooper()!=Looper.getMainLooper(),"History work runs away from the main thread");check(tasks.size()==1,"Only one history request is queued");tasks.removeFirst().run();}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;tasks.clear();return List.of();}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&tasks.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
    private static final class FakeStore extends Store {
        long version=1;int queries;boolean empty;String location="Fixture";
        FakeStore(Context context){super(context,"unused-history-refresh-fixture.db");}
        @Override public long revision(){return version;}
        private void query(){check(Looper.myLooper()!=Looper.getMainLooper(),"History queries never run on the main thread");queries++;}
        @Override public List<Session> sessions(){query();if(empty)return List.of();Session newest=new Session(),older=new Session();newest.id=2;older.id=1;newest.start=older.start=START;newest.end=older.end=START+600000;return List.of(newest,older);}
        @Override public List<FeelingEntry> allFeelings(){query();return List.of();}
        @Override public List<Point> points(long session){query();return List.of(new Point(START,36,22,50,0),new Point(START+600000,36.2,23,50,0));}
        @Override public List<Point> weather(long session){query();return List.of();}
        @Override public List<Point> dreoTemperatures(long session){query();return List.of();}
        @Override public String weatherLocation(long session){query();return location;}
    }
    private static final class CountingGraph extends GraphView {
        int loads;CountingGraph(Context context){super(context);}
        @Override public void data(long session,List<Store.Point> points,List<Store.Point> weather,boolean fahrenheit,int mask){loads++;super.data(session,points,weather,fahrenheit,mask);}
    }
    private static void load(Instrumentation test,MainActivity activity,QueueWorker worker){
        main(test,()->{Handler handler=(Handler)field("handler").get(activity);handler.removeCallbacks((Runnable)field("loadSavedHistory").get(activity));invoke(activity,"loadSavedHistory");});
        worker.runNext();test.waitForIdleSync();
    }
    static String run(Instrumentation test){
        MainActivity[] holder={null};FakeStore[] stores={null};CountingGraph[] graphs={null};QueueWorker worker=new QueueWorker();
        try{
            main(test,()->{
                Context context=new ContextThemeWrapper(test.getTargetContext(),R.style.AppTheme);MainActivity activity=new FixtureActivity();holder[0]=activity;
                Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(activity,context);
                stores[0]=new FakeStore(context);graphs[0]=new CountingGraph(context);graphs[0].layout(0,0,360,346);
                field("store").set(activity,stores[0]);field("tab").set(activity,"History");field("selected").setLong(activity,1);field("followLatestHistory").setBoolean(activity,false);field("activityResumed").setBoolean(activity,true);
                ((ExecutorService)field("historyWorker").get(activity)).shutdown();field("historyWorker").set(activity,worker);field("graph").set(activity,graphs[0]);
            });
            MainActivity activity=holder[0];FakeStore store=stores[0];CountingGraph graph=graphs[0];
            load(test,activity,worker);check(store.queries==6&&graph.loads==1,"Initial history loads one background snapshot");
            main(test,()->{
                Method window=GraphView.class.getDeclaredMethod("setWindow",double.class,double.class);window.setAccessible(true);window.invoke(graph,(double)(START+120000),120000d);
                for(int count=0;count<20;count++)invoke(activity,"refresh");
                check(store.queries==6&&graph.loads==1,"Twenty unchanged UI refreshes perform zero history queries or graph reloads");
                Handler handler=(Handler)field("handler").get(activity);check(!handler.hasCallbacks((Runnable)field("loadSavedHistory").get(activity)),"Unchanged refreshes queue no history work");
                field("activityResumed").setBoolean(activity,false);store.version++;invoke(activity,"refresh");invoke(activity,"loadSavedHistory");
                check(worker.tasks.isEmpty()&&!handler.hasCallbacks((Runnable)field("loadSavedHistory").get(activity))&&store.queries==6,"Paused screens do not query or queue history even when the data changes");
                field("activityResumed").setBoolean(activity,true);
            });
            double from=graph.visibleStartTime(),to=graph.visibleEndTime();store.version++;store.location="Updated weather place";
            main(test,()->field("weatherLocationLabel").set(activity,new TextView(test.getTargetContext())));
            load(test,activity,worker);check(store.queries==12&&graph.loads==2,"A changed revision loads exactly one new snapshot");
            main(test,()->check(((TextView)field("weatherLocationLabel").get(activity)).getText().toString().equals("Updated weather place"),"A completed weather refresh updates the displayed place for the same night"));
            check(graph.visibleStartTime()==from&&graph.visibleEndTime()==to,"Refreshing the same night preserves zoom");
            main(test,()->{
                store.version++;invoke(activity,"loadSavedHistory");field("viewGeneration").setInt(activity,1);field("selected").setLong(activity,2);
            });
            worker.runNext();test.waitForIdleSync();
            main(test,()->check(((SavedHistory)field("savedHistory").get(activity)).selected()==1&&graph.loads==2,"A stale selection callback never replaces the visible graph"));
            load(test,activity,worker);main(test,()->check(((SavedHistory)field("savedHistory").get(activity)).selected()==2&&graph.loads==3,"The replacement request uses the new selected night"));
            main(test,()->{field("selected").setLong(activity,1);field("followLatestHistory").setBoolean(activity,false);});load(test,activity,worker);
            main(test,()->{
                field("followLatestHistory").setBoolean(activity,true);field("selected").setLong(activity,2);invoke(activity,"requestSavedHistory");
                check(((Handler)field("handler").get(activity)).hasCallbacks((Runnable)field("loadSavedHistory").get(activity)),"Returning to latest reloads a cached explicitly older night even without a database change");
            });load(test,activity,worker);
            main(test,()->{store.empty=true;store.version++;field("selected").setLong(activity,-1);});load(test,activity,worker);
            main(test,()->{
                SavedHistory snapshot=(SavedHistory)field("savedHistory").get(activity);check(snapshot.includesGraph&&snapshot.sessions.isEmpty()&&snapshot.selected()==-1,"No recorded nights produces a complete empty snapshot");
                LinearLayout content=new LinearLayout(activity);field("content").set(activity,content);field("graph").set(activity,null);invoke(activity,"history");
                check(text(content,"No sessions yet.")&&!text(content,"Loading recorded night"),"An empty initial database renders the empty state rather than remaining on Loading");
            });
            return "PASS: background history snapshots, twenty unchanged refreshes without queries or reloads, paused load suppression, changed revisions, preserved zoom, stale selection rejection, return to latest, and empty-history presentation\n";
        }finally{
            if(holder[0]!=null)main(test,()->{field("activityDestroyed").setBoolean(holder[0],true);((Handler)field("handler").get(holder[0])).removeCallbacksAndMessages(null);((ExecutorService)field("worker").get(holder[0])).shutdownNow();((ExecutorService)field("historyWorker").get(holder[0])).shutdownNow();worker.shutdownNow();if(stores[0]!=null)stores[0].close();});
        }
    }
}
