package com.aaron.breeze;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

/*
 * Read-only protocol adapted from JeffSteinbok/hass-dreo, commit
 * 63e584f8d126a5159c801fb84e2dbe576ffebd4d, pydreo/helpers.py and constant.py.
 *
 * MIT License
 * Copyright (c) 2023 Jeff Steinbok, Andy Cooper
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public final class DreoClient {
    private static final String BASE = "https://app-api-us.dreo-tech.com";
    private static final String LOGIN = "/api/oauth/login";
    private static final String DEVICES = "/api/v2/user-device/device/list";
    private static final String STATE = "/api/user-device/device/state";
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final DreoAccountStore store;

    public static final class Account {
        // Password hash is credential material too: keep all three encrypted, never log them.
        public final String email, passwordHash, token;
        public Account(String email, String passwordHash, String token) {
            if (email == null || passwordHash == null || token == null) throw new IllegalArgumentException("Incomplete Dreo account.");
            this.email = email;
            this.passwordHash = passwordHash;
            this.token = token;
        }
    }

    public static final class Device {
        public final String serial, name, model, seriesName;
        public Device(String serial, String name, String model, String seriesName) {
            this.serial = serial == null ? "" : serial;
            this.name = name == null || name.isEmpty() ? "Dreo 565S" : name;
            this.model = model == null ? "" : model;
            this.seriesName = seriesName == null ? "" : seriesName;
        }
        public boolean supported() {
            return !serial.isEmpty() && serial.length() <= 256 && (model.equals("DR-HAF008S")
                    || (model.equals("DR-HAF008") && seriesName.equals("565S")));
        }
    }

    public static final class AuthRequiredException extends IOException {
        public AuthRequiredException() { super("Dreo sign-in expired or was rejected. Connect Dreo again."); }
    }

    public DreoClient(DreoAccountStore store) { this.store = store; }

    /** Network methods are synchronous and must run on a worker, never the Android UI thread. */
    public Account login(String email, String password) throws IOException {
        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new IOException("Enter your Dreo email and password.");
        }
        try {
            byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] digest;
            try { digest = MessageDigest.getInstance("MD5").digest(bytes); }
            finally { java.util.Arrays.fill(bytes, (byte) 0); }
            StringBuilder hash = new StringBuilder(32);
            for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            java.util.Arrays.fill(digest, (byte) 0);
            return loginHash(email.trim(), hash.toString());
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Dreo sign-in could not be prepared.");
        }
    }

    private Account loginHash(String email, String hash) throws IOException {
        try {
            JSONObject body = new JSONObject();
            // Public mobile-protocol constants from the upstream integration, not account secrets.
            body.put("acceptLanguage", "en").put("client_id", "7de37c362ee54dcf9c4561812309347a")
                    .put("client_secret", "32dfa0764f25451d99f94e1693498791")
                    .put("email", email).put("encrypt", "ciphertext").put("grant_type", "email-password")
                    .put("himei", "faede31549d649f58864093158787ec9").put("password", hash).put("scope", "all");
            JSONObject response = request(LOGIN, "", null, body);
            if (!success(response)) throw new AuthRequiredException();
            JSONObject data = response.optJSONObject("data");
            if (data == null) throw new IOException("Dreo returned an invalid sign-in response.");
            if (!"NA".equals(string(data, "region"))) throw new IOException("This Dreo connection supports US accounts only.");
            String token = string(data, "access_token");
            if (token.isEmpty() || token.length() > 16384 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new IOException("Dreo returned an invalid sign-in response.");
            }
            return new Account(email, hash, token);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Dreo returned an invalid sign-in response.");
        }
    }

    /** Discovery never replaces a previously connected fan until the user selects and saves one. */
    public List<Device> devices(Account account) throws IOException {
        List<Device> result = new ArrayList<>();
        int pages = 1;
        for (int page = 1; page <= pages; page++) {
            JSONObject response = request(DEVICES, "acceptLanguage=en&method=devices&pageNo=" + page + "&pageSize=100", account, null);
            result.addAll(parseDevices(response));
            Object total = response.optJSONObject("data").opt("totalPage");
            if (DreoReport.integral(total)) {
                long count = ((Number) total).longValue();
                if (count < 0 || count > 10) throw new IOException("Dreo returned an unsupported device-list size.");
                pages = Math.max(1, (int) count);
            }
        }
        return result;
    }

    static List<Device> parseDevices(JSONObject response) throws IOException {
        JSONObject data = response == null ? null : response.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        if (!success(response) || list == null) throw new IOException("Dreo device discovery failed.");
        List<Device> devices = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            Device device = new Device(string(item, "sn"), string(item, "deviceName"), string(item, "model"), string(item, "seriesName"));
            if (device.supported()) devices.add(device);
        }
        return devices;
    }

    /** A single read, with at most one authentication refresh and one retry after HTTP 401/403. */
    public DreoReport fetch() throws IOException {
        if (store == null) throw new IOException("Connect Dreo first.");
        DreoAccountStore.Session selected = store.load();
        if (selected == null) throw new AuthRequiredException();
        long started = System.currentTimeMillis();
        JSONObject response;
        try {
            response = readState(selected.account, selected.device);
        } catch (AuthRequiredException expired) {
            String requestedSerial = selected.device.serial;
            Account refreshed = loginHash(selected.account.email, selected.account.passwordHash);
            if (!store.replaceIfCurrent(selected, refreshed)) throw new IOException("Dreo connection changed during synchronization.");
            selected = store.load();
            if (selected == null || !selected.account.token.equals(refreshed.token)
                    || !selected.device.serial.equals(requestedSerial)) throw new IOException("Dreo connection changed during synchronization.");
            // Use the second request time so a reauthentication delay is not mistaken for fresh state.
            started = System.currentTimeMillis();
            response = readState(selected.account, selected.device);
        }
        long received = System.currentTimeMillis();
        if (!store.isCurrent(selected)) throw new IOException("Dreo connection changed during synchronization.");
        return DreoReport.parse(response, selected.device.serial, started, received);
    }

    private JSONObject readState(Account account, Device device) throws IOException {
        if (!device.supported()) throw new IOException("Select a supported Dreo 565S fan.");
        return request(STATE, "deviceSn=" + URLEncoder.encode(device.serial, StandardCharsets.UTF_8), account, null);
    }

    private static boolean success(JSONObject response) {
        Object code = response == null ? null : response.opt("code");
        return DreoReport.integral(code) && ((Number) code).longValue() == 0;
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private JSONObject request(String path, String parameters, Account account, JSONObject loginBody) throws IOException {
        // This allowlist is deliberately incapable of sending fan controls/settings.
        if (!(LOGIN.equals(path) && loginBody != null && account == null)
                && !((STATE.equals(path) || DEVICES.equals(path)) && loginBody == null && account != null)) {
            throw new IOException("Unsupported Dreo request.");
        }
        HttpsURLConnection connection = null;
        try {
            String query = parameters.isEmpty() ? "" : parameters + "&";
            URL url = new URL(BASE + path + "?" + query + "timestamp=" + System.currentTimeMillis());
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("ua", "dreo/2.8.2");
            connection.setRequestProperty("lang", "en");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "okhttp/4.9.1");
            if (account != null) connection.setRequestProperty("Authorization", "Bearer " + account.token);
            connection.setRequestMethod(loginBody == null ? "GET" : "POST");
            if (loginBody != null) {
                connection.setDoOutput(true);
                byte[] body = loginBody.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                finally { java.util.Arrays.fill(body, (byte) 0); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new AuthRequiredException();
            if (status == 429) throw new IOException("Dreo is limiting requests. Synchronization will retry later.");
            if (status != 200) throw new IOException("Dreo is temporarily unavailable.");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Dreo returned an oversized response.");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8));
            }
        } catch (AuthRequiredException error) {
            throw error;
        } catch (Exception error) {
            // Deliberately do not attach causes or raw response text; they may contain tokens or account data.
            throw new IOException("Dreo could not be read. Using the fan estimate until synchronization succeeds.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
