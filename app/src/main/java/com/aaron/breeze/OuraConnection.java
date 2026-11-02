package com.aaron.breeze;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** User-triggered connection work only; no polling or background service. */
final class OuraConnection {
    private static final java.util.concurrent.atomic.AtomicBoolean IMPORTING=new java.util.concurrent.atomic.AtomicBoolean();
    static boolean busy(){return IMPORTING.get();}
    static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("oura_connection",Context.MODE_PRIVATE);}
    static String status(Context c){return prefs(c).getString("status","Connect Oura to import your completed sleep periods.");}
    static void status(Context c,String message){prefs(c).edit().putString("status",message).apply();}
    static String status(Context c,long session){return prefs(c).getLong("statusSession",-1)<0||prefs(c).getLong("statusSession",-1)==session?status(c):"Ready to refresh Oura for this recorded night.";}

    static void start(Activity activity, ExecutorService worker, Store.Session session, Runnable changed){
        prefs(activity).edit().putLong("statusSession",-1).apply();status(activity,"Preparing Oura sign-in…");changed.run();
        long start=session==null?0:session.start,end=session==null?0:session.end;
        worker.execute(()->{
            try {
                String url=new OuraAccountStore(activity).begin(start,end);
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));status(activity,"Complete Oura sign-in, then tap Return to Breeze on your phone."); }
                    catch(Exception failure){status(activity,"No browser could open Oura sign-in.");}
                    changed.run();
                });
            }catch(Exception failure){status(activity,"Oura sign-in could not be prepared securely.");activity.runOnUiThread(changed);}
        });
    }

    static void probe(Context context) {
        importNight(context,null);
    }
    static void importNight(Context context,Store.Session session){
        if(!IMPORTING.compareAndSet(false,true))return;
        prefs(context).edit().putLong("statusSession",session==null?-1:session.id).apply();
        status(context,"Checking Oura for a completed sleep period…");
        try(Store store=new Store(context)) {
            JSONObject report=session==null?new OuraProbe(context).run():new OuraProbe(context).run(store,session);
            write(context,"oura-import-status.json",report);
            status(context,report.getString("message"));
        }catch(java.io.IOException expected){status(context,expected.getMessage());}
        catch(Exception failure){status(context,"Oura data could not be checked. Try Refresh Oura again.");}
        finally{IMPORTING.set(false);}
    }

    static void write(Context context,String filename,JSONObject value) throws java.io.IOException {
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),filename));FileOutputStream out=null;
        try{out=file.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception failure){if(out!=null)file.failWrite(out);throw new java.io.IOException("Oura history could not be saved on this phone.");}
    }
}
