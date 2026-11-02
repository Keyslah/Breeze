package com.aaron.breeze;

import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Public application configuration and validation; contains no client secret. */
final class OuraOAuth {
    static final String CLIENT_ID = "503005e1-8796-4d76-8ce0-c228b8a8d2ab";
    static final String REDIRECT = "https://keyslah.github.io/Breeze/oura-callback.html";
    static final String CALLBACK = "com.aaron.breeze://oura/callback";
    static final long PENDING_LIFETIME_MS = 10 * 60 * 1000L;

    static String newState() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String authorizationUrl(String state) {
        if (!state.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid sign-in state");
        return "https://cloud.ouraring.com/oauth/authorize?response_type=token&client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(REDIRECT) + "&scope=daily&state=" + encode(state);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static final class Response {
        final String token, error;
        final long lifetimeMs;
        Response(String token, String error, long lifetimeMs) { this.token=token;this.error=error;this.lifetimeMs=lifetimeMs; }
    }

    static Response validate(String json, String expectedState, long startedWall, long startedElapsed,
                             long nowWall, long nowElapsed) throws IOException {
        if (expectedState == null || expectedState.isEmpty() || nowWall < startedWall || nowElapsed < startedElapsed
                || nowWall-startedWall >= PENDING_LIFETIME_MS || nowElapsed-startedElapsed >= PENDING_LIFETIME_MS)
            throw new IOException("This Oura sign-in request expired. Connect Oura again.");
        try {
            if (json == null || json.length() > 32768) throw new IllegalArgumentException();
            JSONObject response = new JSONObject(json);
            Object version=response.opt("version"), state=response.opt("state");
            if (!(version instanceof Number) || ((Number)version).doubleValue()!=1 || !(state instanceof String)
                    || !MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8), ((String)state).getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException();
            if (response.has("error")) {
                Object error=response.opt("error");
                if (response.has("access_token") || !(error instanceof String) || !((String)error).matches("[a-z_]{1,80}"))
                    throw new IllegalArgumentException();
                return new Response("",(String)error,0);
            }
            Object token=response.opt("access_token"), type=response.opt("token_type"), scope=response.opt("scope"), expiry=response.opt("expires_in");
            if (!(token instanceof String) || ((String)token).isEmpty() || ((String)token).length()>16384
                    || !((String)token).matches("[A-Za-z0-9._~+/-]+=*") || !(type instanceof String)
                    || !"bearer".equalsIgnoreCase((String)type) || !(expiry instanceof Number))
                throw new IllegalArgumentException();
            // RFC 6749 section 4.2.2: scope may be omitted when unchanged from our
            // Daily-only request, already bound by the one-use state above. Do not
            // synthesize granted scope; the callback probes the sleep API before
            // reporting that Oura is connected. Returned scope labels do not
            // establish access; Oura enforces it on the requested sleep endpoint.
            // Malformed scope remains invalid, including null or an empty string.
            if (response.has("scope") && (!(scope instanceof String) || ((String)scope).length()>1024
                    || !((String)scope).matches("[\\x21-\\x7e]+(?: +[\\x21-\\x7e]+)*")))
                throw new IllegalArgumentException();
            double seconds=((Number)expiry).doubleValue();
            if (!Double.isFinite(seconds) || seconds<=0 || seconds!=Math.rint(seconds) || seconds>(Long.MAX_VALUE-nowWall)/1000L)
                throw new IllegalArgumentException();
            long lifetime=Math.multiplyExact(((Number)expiry).longValue(),1000L);
            return new Response((String)token,"",lifetime);
        } catch (Exception invalid) {
            throw new IOException("This Oura sign-in response could not be verified. Start Connect Oura again.");
        }
    }
}
