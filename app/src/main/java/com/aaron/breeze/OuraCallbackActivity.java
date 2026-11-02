package com.aaron.breeze;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Fixed browser callback receiver. Unsolicited/replayed responses cannot authorize Breeze. */
public final class OuraCallbackActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        Intent incoming=getIntent();String response=null;
        try { if(incoming!=null&&OuraOAuth.CALLBACK.equals(String.valueOf(incoming.getData())))response=incoming.getStringExtra("oura_response"); }
        catch(Exception invalid){/* Do not log an intent or exception containing account material. */}
        if(incoming!=null){incoming.setData(null);incoming.removeExtra("oura_response");}
        setIntent(new Intent());
        TextView message=new TextView(this);message.setText("Verifying Oura sign-in…");message.setPadding(24,48,24,24);setContentView(message);
        final String returned=response;
        new Thread(()->{
            try {
                OuraOAuth.Response accepted=new OuraAccountStore(this).accept(returned);
                if(accepted.error.isEmpty()) OuraConnection.probe(this);
                else OuraConnection.status(this,"Oura returned "+accepted.error+". Check the Oura application settings before trying again.");
            }catch(Exception invalid){OuraConnection.status(this,"Oura sign-in could not be verified. Start Connect Oura again from History.");}
            runOnUiThread(()->{
                startActivity(new Intent(this,MainActivity.class).putExtra("showOuraHistory",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();
            });
        },"Breeze-Oura-sign-in").start();
    }
}
