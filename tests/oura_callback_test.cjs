// Synthetic OAuth values only. These checks do not start a browser or contact Oura.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { parseResponse, intentFor, start } = require("../website/oura-callback.js");
const state = "s".repeat(43);
const syntheticToken = "synthetic_test_value_NOT_A_REAL_TOKEN";
function fragment(overrides = {}) {
  return "#" + new URLSearchParams({ state, access_token: syntheticToken, token_type: "Bearer", expires_in: "3600", scope: "daily", ...overrides });
}
function fakeBrowser(search = "", hash = fragment()) {
  const status = { textContent: "" }, button = { hidden: true, onclick: null };
  const events = new Map(), timers = new Map();
  let now = 1000;
  const browser = {
    location: { origin: "https://keyslah.github.io", pathname: "/Breeze/oura-callback.html", search, hash, href: "" },
    document: { getElementById: id => id === "callback-status" ? status : button },
    history: { replaceState: (value, title, pathname) => {
      assert.equal(value, null); assert.equal(title, "");
      assert.equal(pathname, "/Breeze/oura-callback.html");
      browser.location.search = browser.location.hash = "";
    } },
    performance: { now: () => now },
    setTimeout: (fn, delay) => { timers.set(1, { fn, delay }); return 1; },
    clearTimeout: id => timers.delete(id),
    addEventListener: (name, fn) => events.set(name, fn)
  };
  for (const forbidden of ["fetch", "XMLHttpRequest", "localStorage", "sessionStorage", "console", "navigator"]) {
    Object.defineProperty(browser, forbidden, { get() { throw new Error("Unexpected browser capability: " + forbidden); } });
  }
  return { browser, status, button, events, timers, advance: ms => { now += ms; } };
}

test("ordinary direct visits contain no response and cannot launch the app", () => {
  const f = fakeBrowser("", ""); start(f.browser);
  assert.equal(f.button.hidden, true); assert.equal(f.button.onclick, null);
  assert.match(f.status.textContent, /Start Connect Oura from History/);
  assert.equal(f.browser.location.href, "");
});

test("documented fragment response is allowlisted and expiry is numeric", () => {
  const parsed = parseResponse("", fragment({ redirect_uri: "https://example.invalid", custom: "ignored" }));
  assert.deepEqual(parsed, { kind: "token", response: { version: 1, state, access_token: syntheticToken, token_type: "bearer", expires_in: 3600, scope: "daily" } });
});

test("omitted scope reaches the native receiver without a manufactured permission", () => {
  const params = new URLSearchParams(fragment().slice(1)); params.delete("scope");
  const parsed = parseResponse("", "#" + params);
  assert.deepEqual(parsed, { kind: "token", response: { version: 1, state, access_token: syntheticToken, token_type: "bearer", expires_in: 3600 } });
  const f = fakeBrowser("", "#" + params); start(f.browser);
  assert.equal(f.button.hidden, false); assert.equal(f.browser.location.hash, "");
  f.button.onclick();
  const returned = JSON.parse(decodeURIComponent(f.browser.location.href.split("S.oura_response=")[1].slice(0, -4)));
  assert.equal(Object.hasOwn(returned, "scope"), false);
  assert.equal(returned.state, state); assert.equal(returned.access_token, syntheticToken);
  params.delete("state"); assert.equal(parseResponse("", "#" + params).reason, "state_missing");
});

test("returned scope labels are preserved for the app to verify access with Oura", () => {
  const parsed = parseResponse("", fragment({ scope: "synthetic_permission_label" }));
  assert.equal(parsed.kind, "token");
  assert.equal(parsed.response.scope, "synthetic_permission_label");
  const f = fakeBrowser("", fragment({ scope: "synthetic_permission_label" }));start(f.browser);
  assert.equal(f.button.hidden, false);
  assert.match(f.status.textContent, /check this response before accepting/);
  assert.equal(f.status.textContent.includes("connected"), false);
});

test("query tokens, refresh tokens and authorization codes are never relayed", () => {
  for (const query of ["?access_token=synthetic", "?code=synthetic", "?refresh_token=synthetic"]) {
    assert.equal(parseResponse(query, "").kind, "unsupported");
  }
  assert.equal(parseResponse("", fragment({ code: "synthetic" })).kind, "unsupported");
  assert.equal(parseResponse("", fragment({ refresh_token: "synthetic" })).kind, "unsupported");
});

test("duplicates and mixed query/fragment responses are rejected", () => {
  assert.equal(parseResponse("", fragment() + "&state=" + state).kind, "invalid");
  assert.equal(parseResponse("?state=" + state, fragment()).kind, "invalid");
  assert.equal(parseResponse("", fragment() + "&access_token=another").kind, "invalid");
});

test("invalid state, malformed credentials, scope and expiry are rejected", () => {
  for (const override of [
    { state: "" }, { state: "short" }, { state: "x".repeat(129) }, { state: "<".repeat(43) },
    { access_token: "" }, { access_token: "x".repeat(16385) }, { access_token: "x;package=evil" },
    { access_token: "x\nsecret" }, { token_type: "basic" }, { scope: "" },
    { expires_in: "0" }, { expires_in: "-1" }, { expires_in: "1.5" }, { expires_in: "1e5" },
    { expires_in: "9007199254740992" }, { scope: "daily\nemail" }, { scope: " " }
  ]) assert.equal(parseResponse("", fragment(override)).kind, "invalid", Object.keys(override).join());
  assert.equal(parseResponse("", "#" + "x".repeat(65537)).kind, "invalid");
});

test("failed checks identify only fixed reasons and never echo response values", () => {
  for (const [override, reason] of [
    [{ state: "" }, "state_missing"], [{ state: "short" }, "state_format"],
    [{ access_token: "" }, "token_missing"], [{ access_token: "private;bad" }, "token_format"],
    [{ token_type: "private_type" }, "token_type"], [{ expires_in: "" }, "expiry_missing"],
    [{ expires_in: "private_expiry" }, "expiry_format"], [{ scope: "" }, "scope_empty"],
    [{ scope: "private\nscope" }, "scope_format"]
  ]) {
    assert.deepEqual(parseResponse("", fragment(override)), { kind: "invalid", reason });
    const f = fakeBrowser("", fragment(override)); start(f.browser);
    assert.ok(f.status.textContent.includes("Check: " + reason));
    assert.equal(f.status.textContent.includes("private"), false);
    assert.equal(f.status.textContent.includes(syntheticToken), false);
    assert.equal(f.status.textContent.includes(state), false);
    assert.equal(f.browser.location.hash, ""); assert.equal(f.button.hidden, true);
    assert.equal(f.button.onclick, null); assert.equal(f.browser.location.href, "");
  }
  assert.equal(parseResponse("?state=" + state, fragment()).reason, "mixed_locations");
  assert.equal(parseResponse("", fragment() + "&state=" + state).reason, "duplicate_parameter");
  assert.equal(parseResponse("", "#" + "x".repeat(65537)).reason, "response_too_large");
});

test("denial uses fixed error fields and never reflects descriptions", () => {
  const parsed = parseResponse("?" + new URLSearchParams({ state, error: "access_denied", error_description: "<script>synthetic</script>" }), "");
  assert.deepEqual(parsed, { kind: "error", response: { version: 1, state, error: "access_denied" } });
  assert.equal(parseResponse("", fragment({ error: "access_denied" })).kind, "invalid");
  assert.equal(parseResponse("?error=access_denied", "").kind, "invalid");
});

test("intent uses a fixed package and extra, percent-encoding routing punctuation", () => {
  const response = { version: 1, state, error: "x;package=evil;S.browser_fallback_url=https://example.invalid" };
  const intent = intentFor(response);
  assert.ok(intent.startsWith("intent://oura/callback#Intent;scheme=com.aaron.breeze;package=com.aaron.breeze;S.oura_response="));
  assert.ok(intent.endsWith(";end"));
  assert.equal((intent.match(/;package=/g) || []).length, 1);
  assert.equal(intent.includes(";S.browser_fallback_url="), false);
  assert.deepEqual(JSON.parse(decodeURIComponent(intent.split("S.oura_response=")[1].slice(0, -4))), response);
});

test("URL is scrubbed before handoff, no token is displayed, and click is required", () => {
  const f = fakeBrowser(); start(f.browser);
  assert.equal(f.browser.location.hash, ""); assert.equal(f.browser.location.search, "");
  assert.equal(f.browser.location.href, ""); assert.equal(f.button.hidden, false);
  assert.equal(f.status.textContent.includes(syntheticToken), false);
  f.advance(1500); f.button.onclick();
  const response = JSON.parse(decodeURIComponent(f.browser.location.href.split("S.oura_response=")[1].slice(0, -4)));
  assert.equal(response.expires_in, 3598); assert.equal(response.access_token, syntheticToken);
  assert.equal(f.button.hidden, true); assert.equal(f.button.onclick, null); assert.equal(f.timers.size, 0);
});

test("page expiry and pagehide discard the response and button handler", () => {
  const f = fakeBrowser(); start(f.browser);
  assert.equal(f.timers.get(1).delay, 300000); f.timers.get(1).fn();
  assert.equal(f.button.hidden, true); assert.equal(f.button.onclick, null);
  assert.equal(f.browser.location.href, "");
  const second = fakeBrowser(); start(second.browser); second.events.get("pagehide")();
  assert.equal(second.button.onclick, null); assert.equal(second.timers.size, 0);
});

test("short-lived token expires sooner and cannot be handed off by a delayed click", () => {
  const f = fakeBrowser("", fragment({ expires_in: "1" })); start(f.browser);
  assert.equal(f.timers.get(1).delay, 1000); f.advance(1100); f.button.onclick();
  assert.equal(f.browser.location.href, ""); assert.equal(f.button.hidden, true);
  assert.match(f.status.textContent, /expired/);
});

test("a delayed timer cannot extend the handoff lifetime", () => {
  const f = fakeBrowser(); start(f.browser);
  f.advance(300000); f.button.onclick();
  assert.equal(f.browser.location.href, ""); assert.equal(f.button.onclick, null);
  assert.match(f.status.textContent, /expired/);
});

test("failed URL cleanup and wrong origins fail closed", () => {
  const f = fakeBrowser(); f.browser.history.replaceState = () => { throw new Error("disabled"); }; start(f.browser);
  assert.equal(f.button.hidden, true); assert.equal(f.button.onclick, null);
  const second = fakeBrowser(); second.browser.location.origin = "https://example.invalid"; start(second.browser);
  assert.equal(second.button.hidden, true); assert.equal(second.button.onclick, null);
});

test("callback CSP and integrity hash match the script and disable network connections", () => {
  const root = path.resolve(__dirname, "../website");
  const html = fs.readFileSync(path.join(root, "oura-callback.html"), "utf8");
  const hash = crypto.createHash("sha256").update(fs.readFileSync(path.join(root, "oura-callback.js"))).digest("base64");
  assert.ok(html.includes(`integrity="sha256-${hash}"`));
  assert.ok(html.includes(`script-src 'sha256-${hash}'`));
  assert.ok(html.includes("connect-src 'none'")); assert.ok(html.includes("form-action 'none'"));
  assert.ok(html.includes('name="referrer" content="no-referrer"'));
  assert.equal(html.includes("'unsafe-inline'"), false);
  assert.equal(html.includes("'unsafe-eval'"), false);
});
