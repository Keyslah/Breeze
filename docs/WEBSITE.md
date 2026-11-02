# Breeze project website

The small static website is published at https://keyslah.github.io/Breeze/.
GitHub Pages serves the root of the `gh-pages` branch. Only the contents of
`website/` are published there; no Android source, diagnostics, or credentials
are part of the site. There are no server functions, analytics, or OAuth
callbacks on the website.

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
changes before publishing. Verify the homepage, `privacy.html`, and `terms.html`
over HTTPS after GitHub Pages finishes deployment.
