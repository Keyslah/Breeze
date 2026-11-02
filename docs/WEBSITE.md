# Breeze project website

The small static website is published at https://keyslah.github.io/Breeze/.
GitHub Pages serves the root of the `gh-pages` branch. Only the contents of
`website/` are published there; no Android source, diagnostics, or credentials
are part of the site. There are no server functions or analytics. A browser-only
Oura callback hands a response to the Android app; it never exchanges tokens.

The homepage and stylesheet are maintained in `website/`. The canonical legal
documents are `PRIVACY.md` and `TERMS.md` at the repository root. Regenerate their
HTML after editing either document:

```powershell
python -m pip install -r tools/website-requirements.txt
python tools/build_website.py
```

Review and commit the changes on `main`, then publish with the existing branch:

```powershell
git push origin main
git subtree split --prefix=website -b gh-pages
git push origin gh-pages
```

Do not force-push. If a remote branch has changed, inspect and preserve its
changes before publishing. Verify the homepage, `privacy.html`, `terms.html`,
and `oura-callback.html` over HTTPS after GitHub Pages finishes deployment.

## Oura callback contract

Register this exact redirect URI:

`https://keyslah.github.io/Breeze/oura-callback.html`

This is a callback location, not proof that a particular registered Oura client
supports the client-only OAuth flow. The registered client must be verified
before use. There is no confidential client secret or code-exchange backend.
The Android app now has the Oura receiver and sign-in flow. Real consent and
data import still need verification for the registered client.

The page accepts Oura's documented client-only token response in the fragment,
or a denial/error with a state. It rejects query tokens, authorization codes,
refresh tokens, duplicate parameters, mixed query/fragment responses, and
missing required values. It strips the browser URL and holds the response
only in page memory. Handoff expires after 300 seconds or sooner if the token
expires, and cleanup runs on expiry, use, or pagehide. There is no local storage,
clipboard copying, logging, telemetry, or network request from the script.
Its Content Security Policy blocks network connections and permits only the
integrity-pinned callback script. Rebuild the HTML after changing that script.

A button click launches this fixed Android intent destination:

`intent://oura/callback#Intent;scheme=com.aaron.breeze;package=com.aaron.breeze;S.oura_response=ENCODED_JSON;end`

The native receiver must be BROWSABLE and match `com.aaron.breeze://oura/callback`.
The `oura_response` string extra is percent-encoded JSON in the intent URI;
Android decodes the string extra. It contains `version: 1`, `state`, and either
`error` or `access_token`, `token_type`, `expires_in` (seconds), and `scope`.
Only these fields are forwarded. The page never displays tokens or accepts a
caller-supplied destination, package, component, or fallback URL.
Failed response checks display a fixed reason (such as `scope_missing`), never
the rejected value. The script URL includes its content hash to avoid reusing
a stale script after an updated page is deployed.

The Android implementation must generate an unpredictable one-use state of
32–128 URL-safe characters, verify it against its own unexpired pending request,
reject unsolicited/replayed responses, validate expiry and scope again, clear
intent data/extras, and encrypt accepted tokens with Android Keystore. No
website response alone establishes that authorization or import succeeded.

Run callback security checks without a browser or real credentials:

```powershell
node --test tests/oura_callback_test.cjs
```
