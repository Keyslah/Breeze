package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Tokens and one-use OAuth state never leave encrypted private app preferences. */
final class OuraAccountStore {
    private static final Object LOCK = new Object();
    private final String alias;
    private final SharedPreferences prefs;
    OuraAccountStore(Context context) { this(context,"oura_account","breeze.oura.account.v1"); }
    OuraAccountStore(Context context,String preferencesName,String keyAlias) { prefs=context.getApplicationContext().getSharedPreferences(preferencesName,Context.MODE_PRIVATE);alias=keyAlias; }

    String begin(long start,long end) throws IOException {
        synchronized (LOCK) {
            JSONObject data=read();String state=OuraOAuth.newState();
            try {
                data.put("pendingState",state).put("pendingWall",System.currentTimeMillis()).put("pendingElapsed",SystemClock.elapsedRealtime());
                data.put("targetStart",start).put("targetEnd",end);write(data);return OuraOAuth.authorizationUrl(state);
            } catch (Exception failure) { throw new IOException("Oura sign-in could not be prepared securely."); }
        }
    }

    OuraOAuth.Response accept(String json) throws IOException {
        synchronized (LOCK) {
            JSONObject data=read();long now=System.currentTimeMillis();
            OuraOAuth.Response response=OuraOAuth.validate(json,data.optString("pendingState",""),data.optLong("pendingWall"),data.optLong("pendingElapsed"),now,SystemClock.elapsedRealtime());
            data.remove("pendingState");data.remove("pendingWall");data.remove("pendingElapsed");
            try {
                if (response.error.isEmpty()) data.put("token",response.token).put("issuedAt",now).put("expiresAt",Math.addExact(now,response.lifetimeMs));
                write(data);return response;
            } catch (Exception failure) { throw new IOException("Oura sign-in could not be saved securely."); }
        }
    }

    String token() throws IOException {
        synchronized (LOCK) {
            JSONObject data=read();long now=System.currentTimeMillis();String token=data.optString("token","");
            if (token.isEmpty()) throw new IOException("Connect Oura to authorize access.");
            if (now<data.optLong("issuedAt") || data.optLong("expiresAt")-now<=30000) {
                data.remove("token");write(data);throw new IOException("Oura sign-in expired. Connect Oura again.");
            }
            return token;
        }
    }

    long[] target() throws IOException { synchronized (LOCK) { JSONObject d=read();return new long[]{d.optLong("targetStart"),d.optLong("targetEnd")}; } }
    void rejectToken(String expected) throws IOException {
        synchronized (LOCK) { JSONObject d=read();if(expected.equals(d.optString("token",""))){d.remove("token");write(d);} }
    }

    private JSONObject read() throws IOException {
        String sealed=prefs.getString("sealed",null);if(sealed==null)return new JSONObject();
        try {
            JSONObject envelope=new JSONObject(sealed);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));byte[] plain=cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP));
            try { return new JSONObject(new String(plain,StandardCharsets.UTF_8)); }
            finally { java.util.Arrays.fill(plain,(byte)0); }
        } catch(Exception failure) { throw new IOException("Saved Oura sign-in cannot be opened securely."); }
    }

    private void write(JSONObject data) throws IOException {
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));byte[] plain=data.toString().getBytes(StandardCharsets.UTF_8);byte[] encrypted;
            try { encrypted=cipher.doFinal(plain); } finally { java.util.Arrays.fill(plain,(byte)0); }
            JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(encrypted,Base64.NO_WRAP));
            if(!prefs.edit().putString("sealed",envelope.toString()).commit())throw new IOException();
        }catch(Exception failure){throw new IOException("Oura sign-in could not be saved securely.");}
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        if(!create)throw new IOException();
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
