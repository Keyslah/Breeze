package com.aaron.breeze;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.security.KeyStore;
import org.json.JSONObject;

/** Real Android Keystore checks using isolated synthetic credentials, with no UI or network calls. */
final class OuraAuthChecks {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JSONObject response(String state)throws Exception{return new JSONObject().put("version",1).put("state",state).put("access_token","SYNTHETIC_TEST_TOKEN_NOT_REAL").put("token_type","bearer").put("scope","daily").put("expires_in",3600);}
    static String run(Context context)throws Exception{
        String name="temporary-oura-auth-"+System.nanoTime(),alias="breeze.test."+name;
        try{
            OuraAccountStore store=new OuraAccountStore(context,name,alias);
            String state=Uri.parse(store.begin(1800000000000L,1800000001000L)).getQueryParameter("state");
            String sealed=context.getSharedPreferences(name,0).getString("sealed","");
            check(!sealed.isEmpty()&&!sealed.contains(state),"pending state encrypted");
            try{store.accept(response("x".repeat(43)).toString());throw new AssertionError("mismatched state accepted");}catch(IOException expected){}
            store.accept(response(state).toString());
            check(store.token().equals("SYNTHETIC_TEST_TOKEN_NOT_REAL"),"accepted token usable");
            sealed=context.getSharedPreferences(name,0).getString("sealed","");check(!sealed.contains("SYNTHETIC_TEST_TOKEN_NOT_REAL"),"token encrypted");
            store=new OuraAccountStore(context,name,alias);check(store.token().equals("SYNTHETIC_TEST_TOKEN_NOT_REAL"),"encrypted account survives reopening");
            check(store.target()[0]==1800000000000L&&store.target()[1]==1800000001000L,"selected recording bounds preserved");
            try{store.accept(response(state).toString());throw new AssertionError("replayed state accepted");}catch(IOException expected){}
            store.rejectToken("different_token");check(store.token().equals("SYNTHETIC_TEST_TOKEN_NOT_REAL"),"old failure cannot clear a new token");
            store.rejectToken("SYNTHETIC_TEST_TOKEN_NOT_REAL");try{store.token();throw new AssertionError("rejected token retained");}catch(IOException expected){}
            state=Uri.parse(store.begin(0,0)).getQueryParameter("state");store.accept(response(state).put("expires_in",1).toString());
            try{store.token();throw new AssertionError("nearly expired token used");}catch(IOException expected){}
            state=Uri.parse(store.begin(0,0)).getQueryParameter("state");
            JSONObject omittedScope=response(state);omittedScope.remove("scope");
            store.accept(omittedScope.toString());
            store=new OuraAccountStore(context,name,alias);check(store.token().equals("SYNTHETIC_TEST_TOKEN_NOT_REAL"),"omitted scope accepted and encrypted");
            try{store.accept(omittedScope.toString());throw new AssertionError("scope-omitted replay accepted");}catch(IOException expected){}
            return "PASS: Oura encrypted state/token persistence, wrong-state rejection, one-use callback, selected recording bounds, token replacement guard, expiry, omitted-scope persistence and replay rejection; isolated synthetic credentials, no network or UI\n";
        }finally{
            // Only disposable test preferences and the key created by this invocation are removed.
            context.deleteSharedPreferences(name);KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(alias))keys.deleteEntry(alias);
        }
    }
}
