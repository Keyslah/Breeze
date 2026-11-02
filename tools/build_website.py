"""Render the legal pages from the repository's canonical Markdown documents.

Install tools/website-requirements.txt, then run this script from any directory.
Writes the legal HTML, the callback HTML with its script integrity hash, and .nojekyll.
"""
from html import escape
from pathlib import Path
import base64
import hashlib

from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parents[1]
WEBSITE = ROOT / "website"
MARKDOWN = MarkdownIt("commonmark", {"html": False})


def render_page(source: str, destination: str, title: str, current: str) -> None:
    markdown = (ROOT / source).read_text(encoding="utf-8-sig")
    # Keep repository links useful in the deployed site as well.
    markdown = markdown.replace("](PRIVACY.md)", "](privacy.html)").replace("](TERMS.md)", "](terms.html)")
    content = MARKDOWN.render(markdown)
    privacy_current = ' aria-current="page"' if current == "privacy" else ""
    terms_current = ' aria-current="page"' if current == "terms" else ""
    page = f'''<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="{escape(title)} for the Breeze Android application and project website.">
  <meta name="theme-color" content="#14251f">
  <meta name="referrer" content="no-referrer">
  <title>{escape(title)} · Breeze</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <a class="skip" href="#main">Skip to content</a>
  <div class="shell">
    <header class="site-header">
      <a class="brand" href="./"><span class="brand-mark" aria-hidden="true">≈</span>Breeze</a>
      <nav aria-label="Main navigation"><a href="privacy.html"{privacy_current}>Privacy</a><a href="terms.html"{terms_current}>Terms</a><a href="https://github.com/Keyslah/Breeze">GitHub ↗</a></nav>
    </header>
    <main id="main" class="legal">
{content}
    </main>
    <footer><span>Breeze · An independent project by <a href="https://github.com/Keyslah">Keyslah</a></span><div><a href="privacy.html">Privacy policy</a><a href="terms.html">Terms of service</a></div></footer>
  </div>
</body>
</html>
'''
    (WEBSITE / destination).write_text(page, encoding="utf-8", newline="\n")


def render_callback() -> None:
    digest = base64.b64encode(hashlib.sha256((WEBSITE / "oura-callback.js").read_bytes()).digest()).decode("ascii")
    page = f'''<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="referrer" content="no-referrer">
  <meta name="robots" content="noindex, nofollow">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-{digest}'; style-src 'self'; connect-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'">
  <meta name="theme-color" content="#14251f">
  <title>Return to Breeze · Oura sign-in</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <div class="shell">
    <header class="site-header"><a class="brand" href="./"><span class="brand-mark" aria-hidden="true">≈</span>Breeze</a></header>
    <main class="legal">
      <p class="eyebrow">OURA CONNECTION</p>
      <h1>Back to Breeze.</h1>
      <p id="callback-status" role="status" aria-live="polite">Preparing the return to Breeze…</p>
      <button id="return-to-breeze" class="button" type="button" hidden>Return to Breeze ↗</button>
      <noscript><p>JavaScript is required to return the sign-in response to Breeze. Close this tab, enable JavaScript, and start again from Breeze. Do not copy or share the address bar.</p></noscript>
      <p class="muted">This page does not complete sign-in on its own. It needs the Oura-enabled Breeze update on your Android phone.</p>
    </main>
    <footer><span>Breeze · Oura sign-in return</span><div><a href="privacy.html">Privacy policy</a><a href="terms.html">Terms of service</a></div></footer>
  </div>
  <script src="oura-callback.js" integrity="sha256-{digest}" crossorigin="anonymous"></script>
</body>
</html>
'''
    (WEBSITE / "oura-callback.html").write_text(page, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    WEBSITE.mkdir(exist_ok=True)
    render_page("PRIVACY.md", "privacy.html", "Privacy policy", "privacy")
    render_page("TERMS.md", "terms.html", "Terms of service", "terms")
    render_callback()
    (WEBSITE / ".nojekyll").write_text("", encoding="utf-8")
    print("Rendered privacy, terms, and Oura callback pages.")
