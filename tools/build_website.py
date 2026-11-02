"""Render the legal pages from the repository's canonical Markdown documents.

Install tools/website-requirements.txt, then run this script from any directory.
Only website/privacy.html, website/terms.html, and website/.nojekyll are written.
"""
from html import escape
from pathlib import Path

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


if __name__ == "__main__":
    WEBSITE.mkdir(exist_ok=True)
    render_page("PRIVACY.md", "privacy.html", "Privacy policy", "privacy")
    render_page("TERMS.md", "terms.html", "Terms of service", "terms")
    (WEBSITE / ".nojekyll").write_text("", encoding="utf-8")
    print("Rendered website/privacy.html and website/terms.html.")
