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
    static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("oura_connection",Context.MODE_PRIVATE);}
    static String status(Context c){return prefs(c).getString("status","Connect Oura to import your completed sleep periods.");}
    static void status(Context c,String message){prefs(c).edit().putString("status",message).apply();}

    static void start(Activity activity, ExecutorService worker, Store.Session session, Runnable changed){
        status(activity,"Preparing Oura sign-in…");changed.run();
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
        status(context,"Checking Oura for a completed sleep period…");
        try {
            JSONObject report=new OuraProbe(context).run();
            write(context,"oura-import-status.json",report);
            status(context,report.getString("message"));
        }catch(java.io.IOException expected){status(context,expected.getMessage());}
        catch(Exception failure){status(context,"Oura data could not be checked. Try Refresh Oura again.");}
    }

    static void write(Context context,String filename,JSONObject value) throws java.io.IOException {
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),filename));FileOutputStream out=null;
        try{out=file.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception failure){if(out!=null)file.failWrite(out);throw new java.io.IOException("Oura history could not be saved on this phone.");}
    }
}
