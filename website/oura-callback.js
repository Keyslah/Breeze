/* Browser-only OAuth handoff. No API calls, persistence, logging, or token exchange. */
(function (root) {
  "use strict";

  const MAX_RESPONSE_LENGTH = 65536;
  const HANDOFF_LIFETIME_MS = 300000;

  function uniqueParameters(raw) {
    if (raw.length > MAX_RESPONSE_LENGTH) throw new Error("invalid_response");
    const params = new URLSearchParams(raw.replace(/^[?#]/, ""));
    const keys = new Set();
    for (const [key] of params) {
      if (keys.has(key)) throw new Error("invalid_response");
      keys.add(key);
    }
    return params;
  }

  function parseResponse(search, hash) {
    if (!search && !hash) return { kind: "empty" };
    try {
      const query = uniqueParameters(search);
      const fragment = uniqueParameters(hash);
      // This public static page must not be used for code exchange or query tokens.
      if (query.has("access_token") || query.has("refresh_token") || query.has("code") ||
          fragment.has("code") || fragment.has("refresh_token")) return { kind: "unsupported" };
      if (query.size && fragment.size) return { kind: "invalid" };
      const params = fragment.size ? fragment : query;
      const state = params.get("state") || "";
      if (!/^[A-Za-z0-9_-]{32,128}$/.test(state)) return { kind: "invalid" };
      if (params.has("error")) {
        const error = params.get("error");
        if (params.has("access_token") || !/^[a-z_]{1,80}$/.test(error)) return { kind: "invalid" };
        // Discard descriptions and all unrecognized fields; never render provider text.
        return { kind: "error", response: { version: 1, state, error } };
      }
      if (!fragment.size) return { kind: "invalid" };
      const token = params.get("access_token") || "";
      const type = params.get("token_type") || "";
      const expiry = params.get("expires_in") || "";
      const scope = params.get("scope") || "";
      if (!/^[A-Za-z0-9._~+/-]+=*$/.test(token) || token.length > 16384 ||
          type.toLowerCase() !== "bearer" || !/^\d{1,16}$/.test(expiry) ||
          !Number.isSafeInteger(Number(expiry)) || Number(expiry) <= 0 ||
          scope.length > 1024 || /[^\x20-\x7e]/.test(scope) ||
          !scope.split(/ +/).includes("daily")) return { kind: "invalid" };
      return {
        kind: "token",
        response: { version: 1, state, access_token: token, token_type: "bearer", expires_in: Number(expiry), scope }
      };
    } catch (_) {
      return { kind: "invalid" };
    }
  }

  function intentFor(response) {
    // Fixed package and destination. Response values cannot alter intent routing.
    return "intent://oura/callback#Intent;scheme=com.aaron.breeze;package=com.aaron.breeze;" +
      "S.oura_response=" + encodeURIComponent(JSON.stringify(response)) + ";end";
  }

  function start(browser) {
    let search = browser.location.search;
    let hash = browser.location.hash;
    const status = browser.document.getElementById("callback-status");
    const button = browser.document.getElementById("return-to-breeze");
    button.hidden = true;
    try {
      // Remove OAuth material from the current browser-history entry before parsing.
      browser.history.replaceState(null, "", browser.location.pathname);
    } catch (_) {
      search = hash = "";
      status.textContent = "This browser could not prepare a secure return to Breeze. Close this tab and start again from Breeze.";
      return;
    }
    if (browser.location.origin !== "https://keyslah.github.io" ||
        browser.location.pathname !== "/Breeze/oura-callback.html") {
      search = hash = "";
      status.textContent = "Open the return page through a new sign-in request from Breeze.";
      return;
    }
    const receivedAt = browser.performance.now();
    let result = parseResponse(search, hash);
    search = hash = "";
    let pending = result.response || null;
    const kind = result.kind;
    result = null;
    const messages = {
      empty: "This is the return page for Oura sign-in. Once the Oura-enabled Breeze update is installed, start Connect Oura from History on your Android phone.",
      unsupported: "This sign-in method is not supported by this callback. Return to Breeze to check the Oura application configuration.",
      invalid: "This sign-in response could not be accepted. Return to Breeze and start a new connection.",
      error: "Oura did not complete this sign-in. Return to Breeze to finish the request or try again.",
      token: "Return to Breeze on your Android phone to finish connecting. Breeze will check this response before accepting it."
    };
    status.textContent = messages[kind];
    if (!pending) return;
    button.hidden = false;
    function clear() {
      pending = null;
      button.hidden = true;
      button.onclick = null;
    }
    const timeout = browser.setTimeout(function () {
      clear();
      status.textContent = "This return link has expired. Start Connect Oura again from Breeze.";
    }, Math.min(HANDOFF_LIFETIME_MS, kind === "token" ? pending.expires_in * 1000 : HANDOFF_LIFETIME_MS));
    browser.addEventListener("pagehide", function () { clear(); browser.clearTimeout(timeout); }, { once: true });
    button.onclick = function () {
      if (!pending) return;
      const elapsed = Math.max(0, browser.performance.now() - receivedAt);
      if (elapsed >= HANDOFF_LIFETIME_MS) {
        clear();
        browser.clearTimeout(timeout);
        status.textContent = "This return link has expired. Start Connect Oura again from Breeze.";
        return;
      }
      if (kind === "token") {
        const elapsedSeconds = Math.ceil(elapsed / 1000);
        pending.expires_in -= elapsedSeconds;
        if (pending.expires_in <= 0) {
          clear();
          browser.clearTimeout(timeout);
          status.textContent = "This sign-in response has expired. Start Connect Oura again from Breeze.";
          return;
        }
      }
      const intent = intentFor(pending);
      clear();
      browser.clearTimeout(timeout);
      status.textContent = "Opening Breeze. If it does not open, install the Oura-enabled update and start sign-in again from Breeze.";
      // The click supplies the user gesture required by Chrome's Android intent support.
      browser.location.href = intent;
    };
  }

  if (typeof module === "object" && module.exports) module.exports = { parseResponse, intentFor, start };
  else start(root);
})(typeof window === "object" ? window : globalThis);
