package com.aaron.breeze;

import android.content.Context;
import android.content.SharedPreferences;
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

/** Account material is AES-GCM encrypted with an AndroidKeyStore key, including the device selection. */
public final class DreoAccountStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "breeze.dreo.account.v1";
    private static final String AAD = "breeze.dreo.account.v1";
    private final SharedPreferences preferences;

    public static final class Session {
        public final DreoClient.Account account;
        public final DreoClient.Device device;
        private Session(DreoClient.Account account, DreoClient.Device device) {
            this.account = account;
            this.device = device;
        }
    }

    public DreoAccountStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("dreo_account", Context.MODE_PRIVATE);
    }

    public boolean hasAccount() {
        synchronized (LOCK) { return preferences.contains("sealed"); }
    }

    public Session load() throws IOException {
        synchronized (LOCK) {
            String sealed = preferences.getString("sealed", null);
            if (sealed == null) return null;
            try {
                JSONObject envelope = new JSONObject(sealed);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
                cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
                byte[] plaintext = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP));
                JSONObject payload;
                try { payload = new JSONObject(new String(plaintext, StandardCharsets.UTF_8)); }
                finally { java.util.Arrays.fill(plaintext, (byte) 0); }
                if (payload.getInt("version") != 1) throw new IOException();
                DreoClient.Account account = new DreoClient.Account(payload.getString("email"), payload.getString("passwordHash"), payload.getString("token"));
                DreoClient.Device device = new DreoClient.Device(payload.getString("serial"), payload.getString("name"), payload.getString("model"), payload.getString("seriesName"));
                if (!device.supported() || account.email.isEmpty() || account.passwordHash.isEmpty() || account.token.isEmpty()) throw new IOException();
                return new Session(account, device);
            } catch (Exception error) {
                // Never include a crypto, JSON, or network exception containing account material.
                throw new IOException("Saved Dreo sign-in cannot be opened. Connect Dreo again.");
            }
        }
    }

    public void save(DreoClient.Account account, DreoClient.Device device) throws IOException {
        synchronized (LOCK) {
            if (account == null || device == null || !device.supported()) throw new IOException("Select a supported Dreo 565S fan.");
            try {
                JSONObject payload = new JSONObject();
                payload.put("version", 1).put("email", account.email).put("passwordHash", account.passwordHash).put("token", account.token)
                        .put("serial", device.serial).put("name", device.name).put("model", device.model).put("seriesName", device.seriesName);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
                byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
                byte[] encrypted;
                try { encrypted = cipher.doFinal(plaintext); }
                finally { java.util.Arrays.fill(plaintext, (byte) 0); }
                JSONObject envelope = new JSONObject();
                envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
                envelope.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
                if (!preferences.edit().putString("sealed", envelope.toString()).commit()) throw new IOException();
            } catch (Exception error) {
                throw new IOException("Dreo sign-in could not be saved securely.");
            }
        }
    }

    /** Prevent a finished network request from restoring an account the user disconnected or replaced. */
    boolean replaceIfCurrent(Session expected, DreoClient.Account account) throws IOException {
        synchronized (LOCK) {
            Session current = load();
            if (!same(current, expected)) return false;
            save(account, expected.device);
            return true;
        }
    }

    boolean isCurrent(Session expected) throws IOException {
        synchronized (LOCK) { return same(load(), expected); }
    }

    private static boolean same(Session left, Session right) {
        return left != null && right != null && left.account.email.equals(right.account.email)
                && left.account.passwordHash.equals(right.account.passwordHash) && left.account.token.equals(right.account.token)
                && left.device.serial.equals(right.device.serial);
    }

    public void clear() throws IOException {
        synchronized (LOCK) {
            if (!preferences.edit().remove("sealed").commit()) throw new IOException("Dreo sign-in could not be disconnected.");
        }
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) return (SecretKey) keyStore.getKey(ALIAS, null);
        if (!create) throw new IOException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(false).build());
        // No unlock-per-use requirement: synchronization must continue after the phone is locked.
        return generator.generateKey();
    }
}
