#!/usr/bin/env python3
"""Builds the Sofaly website into site/ (published by GitHub Pages today and by sofaly.tv later).

Source of truth:
  website/features.json      order of chapters and features, and each feature's minimum plan (follows Planos.kt)
  website/content/<l>.json   every visible text, one file per language (en is the default at the site root)
  website/assets/            CSS, JS and images copied as they are to site/assets/

Output: site/index.html, features.html, plans.html, developers.html (English) and site/<l>/... for every other
language. Pages are plain static HTML (fast, readable without JavaScript, good for search engines); the only
script cycles the TV mock-up in the hero and offers the visitor's language once.

Run: python3 website/build.py   (no dependencies). Pages that the app opens by URL keep their names
(plans.html, help.html); help, privacy, terms and compatibility are not generated here.
"""
import html
import json
import os
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / "site"
# SOFALY_PREVIEW=<folder>: build a copy with explicit index.html links (for hosts that don't serve folder indexes).
PREVIEW = os.environ.get("SOFALY_PREVIEW")
if PREVIEW:
    OUT = Path(PREVIEW)
SITE_URL = "https://sofaly.tv/"
DEFAULT = "en"
PAGES = ["index", "features", "plans", "developers"]

# Line icons (24x24, drawn with a 2px rounded stroke, like the icons on the TV).
ICONS = {
    "cross": '<path d="M9 4h6v5h5v6h-5v5H9v-5H4V9h5z"/>',
    "replay": '<path d="M4 12a8 8 0 1 0 2.4-5.7"/><path d="M4 4v5h5"/><path d="M10 9l5 3-5 3z"/>',
    "search": '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
    "panel": '<rect x="3.5" y="4" width="17" height="16" rx="3"/><path d="M8 9h8M8 13h8M8 17h4"/>',
    "remote": '<rect x="8" y="2.5" width="8" height="19" rx="4"/><circle cx="12" cy="8" r="1.8"/><path d="M12 13v.01M12 16.5v.01"/>',
    "play": '<circle cx="12" cy="12" r="9"/><path d="M10 8.5l5.5 3.5-5.5 3.5z"/>',
    "image": '<rect x="3" y="5" width="18" height="14" rx="3"/><circle cx="9" cy="10" r="1.6"/><path d="M4 17l5-4 4 3 3-2 5 4"/>',
    "brush": '<path d="M14.5 4.5l5 5-8 8-5-5z"/><path d="M6.5 12.5c-2 0-3 1.5-3 3.5 0 1.2-.5 2-1 2.5 3 .5 6-.5 6-3.5"/>',
    "puzzle": '<path d="M9 4h3a2 2 0 1 1 4 0h3v5a2 2 0 1 1 0 4v6h-5a2 2 0 1 0-4 0H5v-5a2 2 0 1 0 0-4V4z"/>',
    "tv": '<rect x="3" y="5" width="18" height="12" rx="3"/><path d="M12 17v3M8 20.5h8"/>',
    "folder": '<path d="M3.5 7a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
    "film": '<rect x="4" y="3.5" width="16" height="17" rx="2"/><path d="M8 3.5v17M16 3.5v17M4 8h4M4 12h4M4 16h4M16 8h4M16 12h4M16 16h4"/>',
    "skip": '<path d="M5 6l7 6-7 6zM12 6l7 6-7 6z"/><path d="M20 6v12"/>',
    "subtitle": '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 12h3M12 12h5M7 15.5h7M16 15.5h1"/>',
    "eye": '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    "eye-off": '<path d="M2.5 12S6 5.5 12 5.5c2 0 3.7.7 5.1 1.7M21.5 12s-3.5 6.5-9.5 6.5c-2 0-3.7-.7-5.1-1.7"/><path d="M4 20L20 4"/>',
    "star": '<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8L3.5 9.7l5.9-.8z"/>',
    "list": '<path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.5" cy="6" r="1"/><circle cx="4.5" cy="12" r="1"/><circle cx="4.5" cy="18" r="1"/>',
    "radio": '<rect x="3" y="8" width="18" height="12" rx="3"/><path d="M7 8l10-4.5"/><circle cx="8.5" cy="14" r="2.5"/><path d="M14 12.5h4M14 15.5h4"/>',
    "mic": '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/>',
    "moon": '<path d="M20 14.5A8 8 0 1 1 9.5 4 6.5 6.5 0 0 0 20 14.5z"/>',
    "headphones": '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="14" width="4.5" height="6.5" rx="2"/><rect x="16.5" y="14" width="4.5" height="6.5" rx="2"/>',
    "gamepad": '<path d="M7 7h10a5 5 0 0 1 4.8 6.4l-1 3.4a2.5 2.5 0 0 1-4.2 1L14.5 16h-5l-2.1 1.8a2.5 2.5 0 0 1-4.2-1l-1-3.4A5 5 0 0 1 7 7z"/><path d="M7.5 10v4M5.5 12h4"/><path d="M16 11h.01M18 13h.01"/>',
    "monitor": '<rect x="3" y="4" width="18" height="12" rx="2.5"/><path d="M9 20h6M12 16v4"/>',
    "wifi": '<path d="M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0"/><path d="M12 19.5v.01"/>',
    "bluetooth": '<path d="M7 7l10 10-5 4V3l5 4L7 17"/>',
    "shield": '<path d="M12 3l7.5 3v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/>',
    "people": '<circle cx="9" cy="8" r="3.2"/><path d="M3 19.5a6 6 0 0 1 12 0"/><circle cx="17" cy="9" r="2.6"/><path d="M15.5 14.2a5 5 0 0 1 5.5 5.3"/>',
    "grid": '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
    "move": '<path d="M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3"/>',
    "house": '<path d="M3.5 11L12 4l8.5 7"/><path d="M5.5 9.5V20h13V9.5"/><path d="M10 20v-5h4v5"/>',
    "alarm": '<circle cx="12" cy="13" r="7.5"/><path d="M12 9v4l2.5 2M4 5l3-2.5M20 5l-3-2.5"/>',
    "hourglass": '<path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/>',
    "power": '<path d="M12 3v9"/><path d="M6.5 6.5a8 8 0 1 0 11 0"/>',
    "sparkle": '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"/>',
    "bolt": '<path d="M13 2.5L5 13.5h6l-1 8 8-11h-6z"/>',
    "bulb": '<path d="M9 18h6M10 21h4"/><path d="M8.5 15a6.5 6.5 0 1 1 7 0c-.6.5-.9 1.2-.9 2H9.4c0-.8-.3-1.5-.9-2z"/>',
    "bell": '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
    "sun": '<circle cx="12" cy="12" r="4.5"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"/>',
    "chart": '<path d="M4 20V4M4 20h16"/><path d="M8 16v-4M12 16V8M16 16v-6"/>',
    "calendar": '<rect x="3.5" y="5" width="17" height="15" rx="3"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
    "rain": '<path d="M7 15a4.5 4.5 0 1 1 1-8.9A5.5 5.5 0 0 1 18.5 8 3.5 3.5 0 0 1 18 15z"/><path d="M8 18l-1 2.5M12 18l-1 2.5M16 18l-1 2.5"/>',
    "photo": '<rect x="3" y="6" width="18" height="14" rx="3"/><circle cx="12" cy="13" r="3.5"/><path d="M8 6l1.5-2.5h5L16 6"/>',
    "sunrise": '<path d="M3 18h18M6 18a6 6 0 0 1 12 0"/><path d="M12 3v5M9.5 5.5L12 3l2.5 2.5M4 11l1.5 1M20 11l-1.5 1"/>',
    "child": '<circle cx="12" cy="6.5" r="3"/><path d="M7 21l1.5-7.5h7L17 21M8.5 13.5L5 11M15.5 13.5L19 11"/>',
    "door": '<path d="M5 21V4.5A1.5 1.5 0 0 1 6.5 3h11A1.5 1.5 0 0 1 19 4.5V21M3 21h18"/><path d="M15 12v.01"/>',
    "note": '<path d="M5 3.5h10l4 4V20a.5.5 0 0 1-.5.5h-13A.5.5 0 0 1 5 20z"/><path d="M15 3.5V8h4M8.5 12h7M8.5 15.5h5"/>',
    "heart": '<path d="M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 7.5 3c0 5.4-7.5 10-7.5 10z"/>',
    "video": '<rect x="3" y="6" width="13" height="12" rx="3"/><path d="M16 10.5l5-3v9l-5-3z"/>',
    "phone": '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
    "chat": '<path d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v9a1.5 1.5 0 0 1-1.5 1.5H10l-4.5 4v-4h0A1.5 1.5 0 0 1 4 14.5z"/>',
    "share": '<circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="6" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="M8.2 10.8l7.6-3.6M8.2 13.2l7.6 3.6"/>',
    "cast": '<path d="M3 8V6.5A1.5 1.5 0 0 1 4.5 5h15A1.5 1.5 0 0 1 21 6.5v11a1.5 1.5 0 0 1-1.5 1.5H14"/><path d="M3 12a8 8 0 0 1 7 7M3 15.5a4.5 4.5 0 0 1 3.5 3.5M3 19v.01"/>',
    "lock": '<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/><path d="M12 14.5v2"/>',
    "cloud": '<path d="M7 18.5a4.5 4.5 0 1 1 1-8.9A5.5 5.5 0 0 1 18.5 11 3.75 3.75 0 0 1 18 18.5z"/>',
    "wrench": '<path d="M14.5 4a5 5 0 0 0-5.3 6.7L3.5 16.5l4 4 5.8-5.7A5 5 0 0 0 20 9.5l-3 3-3.5-1-1-3.5 3-3z"/>',
    "stethoscope": '<path d="M6 3v6a4 4 0 0 0 8 0V3"/><path d="M10 13v2.5a4.5 4.5 0 0 0 9 0V13"/><circle cx="19" cy="11" r="2"/>',
    "ball": '<circle cx="12" cy="12" r="9"/><path d="M12 7.5l4 2.9-1.5 4.7h-5L8 10.4z"/><path d="M12 3v4.5M16 10.4l4.3-1.4M14.5 15.1l2.7 3.7M9.5 15.1l-2.7 3.7M8 10.4L3.7 9"/>',
    "globe": '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z"/>',
    "text": '<path d="M4 7V5h11v2M9.5 5v14M7.5 19h4M14 12v-1.5h7V12M17.5 10.5V19M16 19h3"/>',
    "access": '<circle cx="12" cy="4.5" r="2"/><path d="M4.5 8.5l7.5 1.5 7.5-1.5M12 10v5l-3.5 6.5M12 15l3.5 6.5"/>',
    "speaker": '<path d="M4 9.5h4l5-4.5v14l-5-4.5H4z"/><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"/>',
}

WORLDS = ["continue", "watch", "listen", "play", "apps"]
WORLD_ICON = {"continue": "replay", "watch": "tv", "listen": "headphones", "play": "gamepad", "apps": "grid"}
WORLD_COLOR = {"continue": "#F5C04A", "watch": "#FF6A2B", "listen": "#3CC6B4", "play": "#8C8CFF", "apps": "#8FD07A"}


def esc(s):
    return html.escape(str(s), quote=True)


def icon(name, cls="ico"):
    return (f'<svg class="{cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
            f'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">{ICONS[name]}</svg>')


LOGO = ('<svg class="logo-mark" viewBox="0 -10 120 112" aria-hidden="true">'
        '<path d="M60 26 L35 8 M60 26 L89 14" stroke="#FF6A2B" stroke-width="8.5" stroke-linecap="round"/>'
        '<path d="M51 28 A9 9 0 0 1 69 28 Z" fill="#FF6A2B"/>'
        '<rect x="23.75" y="25.25" width="72.5" height="57.5" rx="15.75" fill="var(--logo-screen)" stroke="#FF6A2B" stroke-width="13.5"/>'
        '<rect x="3.5" y="49" width="20.5" height="40.5" rx="9.5" fill="#FF6A2B"/>'
        '<rect x="96" y="49" width="20.5" height="40.5" rx="9.5" fill="#FF6A2B"/>'
        '<rect x="12" y="76" width="96" height="13.5" rx="5" fill="#FF6A2B"/>'
        '<path d="M12.5 82 H24.5 L17 98.8 Q16 101 13.6 101 H11.4 Q9 101 9.3 98.6 Z" fill="#FF6A2B"/>'
        '<path d="M107.5 82 H95.5 L103 98.8 Q104 101 106.4 101 H108.6 Q111 101 110.7 98.6 Z" fill="#FF6A2B"/>'
        '<path d="M37 55 Q37 44 48 43" fill="none" stroke="#FFF3E6" stroke-width="5.5" stroke-linecap="round" opacity=".85"/>'
        '</svg>')


class Lang:
    def __init__(self, code, data, langs):
        self.code, self.d, self.langs = code, data, langs
        self.prefix = "" if code == DEFAULT else "../"
        self.ui = data["ui"]

    def url(self, page, code=None):
        """Relative link from a page of this language to `page` in `code` (default: same language)."""
        code = code or self.code
        f = "index.html" if PREVIEW and page == "index" else ("" if page == "index" else f"{page}.html")
        if code != self.code:
            f = self.prefix + ("" if code == DEFAULT else f"{code}/") + f
        return f or "./"

    def abs_url(self, page, code=None):
        code = code or self.code
        return SITE_URL + ("" if code == DEFAULT else f"{code}/") + ("" if page == "index" else f"{page}.html")

    def plan_name(self, plan):
        return self.ui[f"plan_{plan}"]


def head(L, page, title, description):
    alts = "\n".join(f'<link rel="alternate" hreflang="{esc(L.langs[c]["lang"])}" href="{L.abs_url(page, c)}">'
                     for c in L.langs)
    rtl = ' dir="rtl"' if L.d.get("dir") == "rtl" else ""
    return f"""<!doctype html>
<html lang="{esc(L.d['lang'])}"{rtl} data-lang="{L.code}" data-default="{DEFAULT}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)}</title>
<meta name="description" content="{esc(description)}">
<meta name="theme-color" content="#121528">
<link rel="canonical" href="{L.abs_url(page)}">
{alts}
<link rel="alternate" hreflang="x-default" href="{L.abs_url(page, DEFAULT)}">
<meta property="og:title" content="{esc(title)}">
<meta property="og:description" content="{esc(description)}">
<meta property="og:image" content="{SITE_URL}assets/og.png">
<meta property="og:type" content="website">
<link rel="icon" href="{L.prefix}assets/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="{L.prefix}assets/favicon-180.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=Nunito:wght@600;700;800&display=swap">
<link rel="stylesheet" href="{L.prefix}assets/site.css">
<script src="{L.prefix}assets/site.js" defer></script>
</head>
<body>
<a class="skip" href="#main">{esc(L.ui['skip'])}</a>
"""


def header(L, page):
    ui = L.ui
    links = [("features", ui["nav_features"]), ("plans", ui["nav_plans"]), ("developers", ui["nav_developers"])]
    nav = "".join(f'<a href="{L.url(p)}"{" aria-current=\"page\"" if p == page else ""}>{esc(t)}</a>'
                  for p, t in links)
    nav += f'<a href="{L.prefix}help.html?l={L.code}">{esc(ui["nav_help"])}</a>'
    opts = "".join(f'<option value="{L.url(page, c)}"{" selected" if c == L.code else ""} data-code="{c}">'
                   f'{esc(L.langs[c]["label"])}</option>' for c in L.langs)
    return f"""<header class="top">
  <div class="wrap top-in">
    <a class="brand" href="{L.url('index')}" aria-label="Sofaly">{LOGO}<span class="wordmark">sofa<b>ly</b></span></a>
    <nav class="menu" aria-label="Sofaly">{nav}</nav>
    <label class="lang"><span class="sr">{esc(ui['language'])}</span>{icon('globe')}<select id="lang">{opts}</select></label>
  </div>
</header>
<main id="main">
"""


def footer(L):
    ui = L.ui
    return f"""</main>
<footer class="foot">
  <div class="wrap foot-in">
    <div><a class="brand" href="{L.url('index')}">{LOGO}<span class="wordmark">sofa<b>ly</b></span></a>
      <p>{esc(ui['footer_tagline'])}</p></div>
    <nav aria-label="Links">
      <a href="{L.url('features')}">{esc(ui['nav_features'])}</a>
      <a href="{L.url('plans')}">{esc(ui['nav_plans'])}</a>
      <a href="{L.url('developers')}">{esc(ui['nav_developers'])}</a>
      <a href="{L.prefix}help.html?l={L.code}">{esc(ui['nav_help'])}</a>
      <a href="{L.prefix}compatibility.html">{esc(ui['footer_compat'])}</a>
      <a href="{L.prefix}privacy.html{'#pt' if L.code.startswith('pt') else ''}">{esc(ui['footer_privacy'])}</a>
      <a href="{L.prefix}terms.html{'#pt' if L.code.startswith('pt') else ''}">{esc(ui['footer_terms'])}</a>
    </nav>
  </div>
  <p class="wrap fine">© 2026 Sofaly · {esc(ui['footer_trademark'])}</p>
</footer>
</body>
</html>
"""


def tv_mock(L):
    """The hero TV: the real cross layout, drawn in HTML so it is crisp, light and translatable."""
    h, m = L.d["home"], L.d["home"]["mock"]
    row = []
    for w in WORLDS:
        name = h["worlds"][w][0]
        items = "".join(f"<li>{esc(i)}</li>" for i in m[f"{w}_items"])
        row.append(f'<div class="w" data-w="{w}" style="--c:{WORLD_COLOR[w]}">'
                   f'<span class="w-ico">{icon(WORLD_ICON[w])}</span><span class="w-name">{esc(name)}</span>'
                   f'<div class="w-big"><b>{esc(m[w + "_title"])}</b><span>{esc(m[w + "_detail"])}</span></div>'
                   f'<ul class="w-col">{items}</ul></div>')
    return f"""<div class="tv" aria-hidden="true">
  <div class="screen">
    <i class="art"></i>
    <div class="bar">
      <span class="pill">{icon('search')}{icon('mic')}</span>
      <span class="pill">{wx('sun')}{esc(m['weather'])}</span>
      <span class="clock"><b>08:57</b><small>{esc(m['day'])}<br>{esc(m['date'])}</small></span>
    </div>
    <div class="label"><span class="label-name"></span> <span class="label-n"></span></div>
    <div class="row">{''.join(row)}</div>
    <span class="key"></span>
  </div>
  <div class="stand"></div>
</div>"""


# ---- Showcase: the other TV screens, drawn in HTML/CSS and animated by themselves (no video, no images). ----
SHOW = [  # id, accent color, icon of the tab
    ("forecast", "#6FB7FF", "sun"), ("screensaver", "#E2B866", "image"), ("player", "#FF6A2B", "film"),
    ("radio", "#3CC6B4", "radio"), ("alarm", "#F5C04A", "alarm"), ("cards", "#FFB547", "star"),
    ("games", "#A88BFF", "gamepad"), ("phone", "#8FD07A", "phone"), ("windows", "#4FA3FF", "folder"),
    ("kids", "#5FD3E8", "moon"),
]
WX = {"sun": "#FFC94A", "cloud": "#DCE3F5", "rain": "#6FB7FF", "moon": "#CFD6FF"}


def wx(name, cls="ico"):
    return f'<span class="wx" style="--x:{WX[name]}">{icon(name, cls)}</span>'


def scr_forecast(t, m):
    hours = [("18h", "rain", 23, 71), ("19h", "cloud", 22, 61), ("20h", "cloud", 21, 49), ("21h", "moon", 21, 36),
             ("22h", "moon", 20, 21), ("23h", "moon", 20, 10), ("0h", "moon", 19, 5), ("1h", "moon", 19, 3),
             ("2h", "moon", 19, 2), ("3h", "cloud", 19, 8), ("4h", "cloud", 18, 12)]
    lis = f'<li class="now" style="--i:0"><small>{esc(t[3])}</small>{wx("rain")}<b>24°</b><em>76%</em></li>' + "".join(
        f'<li style="--i:{n + 1}"><small>{h}</small>{wx(k)}<b>{tp}°</b><em>{r}%</em></li>'
        for n, (h, k, tp, r) in enumerate(hours))
    return f"""<div class="fc-now">{wx('rain', 'ico fc-big')}<div><b class="fc-temp">24°</b><span>{esc(t[0])}</span>
      <small>{esc(t[4])}</small></div></div>
    <div class="fc-box"><small>{esc(t[1])}</small><ol class="fc-hours">{lis}</ol></div>
    <div class="fc-box fc-rain"><small>{esc(t[2])}</small>
      <svg viewBox="0 0 220 40" preserveAspectRatio="none"><path class="fc-area" d="M0 8 C30 6 45 14 70 22 S120 36 150 37 S200 30 220 26 V40 H0Z"/>
      <path class="fc-line" d="M0 8 C30 6 45 14 70 22 S120 36 150 37 S200 30 220 26"/></svg></div>"""


def scr_screensaver(t, m):
    row = (f'<span>{wx("cloud")}<b>24°</b></span><span>{wx("moon")}{esc(t[2])}</span>'
           f'<span>{wx("rain")}<b>12%</b></span><span>{wx("sun")}<b>06:12</b></span>')
    return f"""<div class="ss-art"><i class="ss-moon"></i><i class="ss-hill"></i><i class="ss-sea"></i></div>
    <div class="ss-clock"><small>{esc(t[0])}</small><b>21<i>:</i>47</b><span>{esc(m['day'])}, {esc(m['date'])}</span></div>
    <div class="ss-row">{row}</div><em class="ss-cap">{esc(t[1])}</em>"""


def scr_player(t, m):
    return f"""<div class="pl-art"><i class="pl-sun"></i><i class="pl-m1"></i><i class="pl-m2"></i></div>
    <div class="pl-title"><b>{esc(t[0])}</b><span>2026 · 1h 52 · ★ 8.1</span></div>
    <div class="pl-skip">{icon('skip')}{esc(t[4])}</div>
    <div class="pl-menu"><small>{esc(t[1])}</small><span class="o1">{icon('subtitle')}{esc(t[2])}</span>
      <span class="o2">{icon('speaker')}{esc(t[3])}</span></div>
    <div class="pl-bar"><i></i></div><div class="pl-time"><span>0:42:10</span><span>1:52:00</span></div>"""


def scr_radio(t, m):
    st = [(t[2], "#3CC6B4", "98.5"), (t[3], "#FF6A2B", "101.3"), (t[4], "#F5C04A", "89.1"), (t[5], "#A88BFF", "")]
    lis = "".join(f'<li style="--i:{n};--d:{c}"><i></i><span>{esc(a)}</span><small>{f}</small></li>' for n, (a, c, f) in enumerate(st))
    bars = "".join(f'<i style="--i:{n}"></i>' for n in range(14))
    return f"""<div class="rd-list"><small>{esc(t[1])}</small><ul>{lis}</ul></div>
    <div class="rd-now"><div class="rd-cover">{icon('radio')}</div><small>{esc(t[0])}</small><b>{esc(t[2])}</b>
      <span>98.5 FM</span><div class="eq">{bars}</div></div>"""


def scr_alarm(t, m):
    return f"""<i class="al-dawn"></i><i class="al-sun"></i>
    <div class="al-txt"><small>{esc(t[0])}</small><b>06:30</b><span>{wx('sun')}22° · {esc(m['day'])}</span></div>
    <div class="al-cec">{icon('power')}{esc(t[3])}</div>
    <div class="al-vol">{icon('speaker')}<i><b></b></i><em>{esc(t[1])}</em></div>
    <div class="al-snooze">{esc(t[2])}</div>"""


def scr_cards(t, m):
    return f"""<div class="cd-bar"><span class="pill cd-pill"><b class="c1">{icon('calendar')}{esc(t[0])}</b>
      <b class="c2">{icon('bell')}{esc(t[1])}</b><b class="c3">{icon('bolt')}{esc(t[2])}</b></span>
      <span class="pill">{wx('sun')}25°</span><span class="pill cd-rate">{icon('chart')}USD 5.32 <em>▲0.4%</em> · BTC ▲</span>
      <span class="clock"><b>19:20</b></span></div>
    <div class="cd-grid">
      <div class="cd-card cd-count"><small>{esc(t[7])}</small><b>12</b><span>{esc(t[0])}</span></div>
      <div class="cd-card cd-match">{icon('ball')}<small>{esc(t[3])}</small><b>{esc(t[4])}</b><span>{esc(t[8])}</span></div>
      <div class="cd-card cd-moon"><i class="cd-moonball"></i><small>{esc(t[5])}</small><b>32%</b></div>
      <div class="cd-card cd-rates"><small>{esc(t[6])}</small><b>USD 5.32</b><span>EUR 6.18 · BTC · ETH</span></div>
    </div>"""


def scr_games(t, m):
    tiles = [("cloud", "#A88BFF", t[2]), ("monitor", "#4FA3FF", t[3]), ("gamepad", "#FF6A2B", t[4]),
             ("people", "#3CC6B4", t[5]), ("cloud", "#F5C04A", t[2]), ("gamepad", "#8FD07A", t[4])]
    g = "".join(f'<li style="--i:{n};--d:{c}">{icon(i)}<span>{esc(x)}</span></li>' for n, (i, c, x) in enumerate(tiles))
    return f"""<div class="gm-head">{icon('gamepad')}<b>{esc(t[6])}</b></div><ul class="gm-tiles">{g}</ul>
    <div class="gm-net"><span>{icon('wifi')}{esc(t[1])}</span><i><b></b></i><em>42 ms · 180 Mb/s</em></div>
    <div class="gm-toast">{icon('gamepad')}{esc(t[0])}</div>"""


def scr_phone(t, m):
    btn = [("remote", t[3]), ("text", t[4]), ("monitor", t[5]), ("photo", t[6])]
    b = "".join(f'<span>{icon(i)}{esc(x)}</span>' for i, x in btn)
    return f"""<div class="ph-tv"><div class="ph-toast">{icon('chat')}<div><small>{esc(t[2])}</small><b>{esc(t[1])}</b></div></div></div>
    <div class="ph-phone"><div class="ph-scr"><b class="ph-brand">sofa<em>ly</em></b><div class="ph-grid">{b}</div>
      <div class="ph-field"><span>{esc(t[1])}</span></div><div class="ph-send">{esc(t[0])}</div><i class="ph-tap"></i></div></div>"""


def scr_windows(t, m):
    return f"""<div class="wn"><div class="wn-bar"><i></i><i></i><i></i><b>{esc(t[0])}</b></div>
      <div class="wn-body">
        <div class="wn-drive">{icon('monitor')}<div><b>{esc(t[2])} (C:)</b><i><b style="width:62%"></b></i></div></div>
        <div class="wn-drive wn-tv">{LOGO}<div><b>{esc(t[1])} (S:)</b><i><b class="wn-fill"></b></i><small>{esc(t[4])}</small></div></div>
        <div class="wn-file wn-url">{icon('globe')}<span>{esc(t[3])}.url</span></div>
      </div></div>
    <div class="wn-drag">{icon('film')}<span>movie.mkv</span></div>"""


def scr_kids(t, m):
    dots = "".join(f'<i style="--i:{n}"></i>' for n in range(4))
    return f"""<i class="kd-stars"></i><div class="kd-moon">{icon('moon')}</div>
    <div class="kd-txt"><b>{esc(t[0])}</b><span>{esc(t[1])}</span><small>{esc(t[2])}</small></div>
    <div class="kd-pin"><small>{icon('lock')}{esc(t[3])}</small><div>{dots}</div></div>"""


SCREENS = {"forecast": scr_forecast, "screensaver": scr_screensaver, "player": scr_player, "radio": scr_radio,
           "alarm": scr_alarm, "cards": scr_cards, "games": scr_games, "phone": scr_phone, "windows": scr_windows,
           "kids": scr_kids}


def showcase(L):
    s, m = L.d["show"], L.d["home"]["mock"]
    scr, tabs = [], []
    for n, (k, c, ic) in enumerate(SHOW):
        tab, cap, t = s["screens"][k]
        scr.append(f'<div class="scr scr-{k}" data-k="{k}" style="--k:{c}"{"" if n == 0 else " hidden"}>{SCREENS[k](t, m)}</div>')
        tabs.append(f'<li><button type="button" class="tab{" on" if n == 0 else ""}" style="--k:{c}" data-k="{k}" '
                    f'aria-pressed="{"true" if n == 0 else "false"}">{icon(ic)}<span>{esc(tab)}</span><i></i></button></li>')
    caps = "".join(f'<p class="cap" data-k="{k}"{"" if n == 0 else " hidden"}>{esc(s["screens"][k][1])}</p>'
                   for n, (k, _, _) in enumerate(SHOW))
    return f"""<section class="band showcase" id="see">
  <div class="wrap">
    <h2>{esc(s['title'])}</h2>
    <p class="sub">{esc(s['lead'])}</p>
    <div class="show">
      <div class="tv tv-show" aria-hidden="true"><div class="screen">{''.join(scr)}</div><div class="stand"></div></div>
      <div class="show-side">
        <ul class="tabs" aria-label="{esc(s['title'])}">{''.join(tabs)}</ul>
        <div class="caps" aria-live="polite">{caps}</div>
      </div>
    </div>
  </div>
</section>"""


def page_index(L):
    h = L.d["home"]
    ui = L.ui
    worlds = "".join(
        f'<li class="world" style="--c:{WORLD_COLOR[w]}"><span class="world-ico">{icon(WORLD_ICON[w])}</span>'
        f'<h3>{esc(h["worlds"][w][0])}</h3><p>{esc(h["worlds"][w][1])}</p></li>' for w in WORLDS)
    th = "".join(f"<th scope=\"col\">{esc(x)}</th>" for x in h["why_head"])
    rows = "".join(f'<tr><th scope="row">{esc(a)}</th><td class="them">{esc(b)}</td><td class="us">{esc(c)}</td></tr>'
                   for a, b, c in h["why_rows"])
    hl = "".join(f'<li class="card">{icon(i)}<h3>{esc(t)}</h3><p>{esc(d)}</p></li>' for i, t, d in h["highlights"])
    feats = json.loads((ROOT / "features.json").read_text())["chapters"]
    chapters = "".join(
        f'<li><a class="chap" href="{L.url("features")}#{c["id"]}" style="--c:{c["color"]}">{icon(c["icon"])}'
        f'<span><b>{esc(L.d["chapters"][c["id"]][0])}</b><small>{esc(L.d["chapters"][c["id"]][1])}</small></span>'
        f'<em>{len(c["features"])}</em></a></li>' for c in feats)
    faq = "".join(f"<details><summary>{esc(q)}</summary><p>{esc(a)}</p></details>" for q, a in h["faq"])
    names = json.dumps({w: h["worlds"][w][0] for w in WORLDS}, ensure_ascii=False)
    return (head(L, "index", h["title"], h["description"]) + header(L, "index") + f"""
<section class="hero">
  <div class="wrap hero-in">
    <div class="hero-text">
      <p class="eyebrow">{esc(h['eyebrow'])}</p>
      <h1>{esc(h['h1'])}</h1>
      <p class="lead">{esc(h['lead'])}</p>
      <div class="ctas"><span class="btn primary" aria-disabled="true">{icon('play')}{esc(ui['cta_get'])}</span>
        <a class="btn" href="{L.url('features')}">{esc(ui['cta_see'])}</a></div>
      <p class="note">{icon('shield')}{esc(h['hero_note'])}</p>
    </div>
    <div class="hero-tv" data-names='{esc(names)}' data-of="{esc(ui['of'])}">{tv_mock(L)}</div>
  </div>
</section>
{showcase(L)}
<section class="band">
  <div class="wrap">
    <h2>{esc(h['cross_title'])}</h2>
    <p class="sub">{esc(h['cross_lead'])}</p>
    <ul class="worlds">{worlds}</ul>
  </div>
</section>

<section class="band alt">
  <div class="wrap">
    <h2>{esc(h['why_title'])}</h2>
    <p class="sub">{esc(h['why_lead'])}</p>
    <div class="table-wrap"><table class="compare"><thead><tr>{th}</tr></thead><tbody>{rows}</tbody></table></div>
    <p class="source">{esc(h['why_source'])}</p>
  </div>
</section>

<section class="band">
  <div class="wrap">
    <h2>{esc(h['highlights_title'])}</h2>
    <ul class="cards">{hl}</ul>
  </div>
</section>

<section class="band alt">
  <div class="wrap">
    <h2>{esc(h['chapters_title'])}</h2>
    <p class="sub">{esc(h['chapters_lead'])}</p>
    <ul class="chapters">{chapters}</ul>
  </div>
</section>

<section class="band">
  <div class="wrap split">
    <div class="panel">
      {icon('lock', 'ico big')}
      <h2>{esc(h['privacy_title'])}</h2>
      <p>{esc(h['privacy_text'])}</p>
      <a class="more" href="{L.prefix}privacy.html{'#pt' if L.code.startswith('pt') else ''}">{esc(h['privacy_link'])} →</a>
    </div>
    <div class="panel dev">
      {icon('puzzle', 'ico big')}
      <h2>{esc(h['dev_title'])}</h2>
      <p>{esc(h['dev_text'])}</p>
      <a class="more" href="{L.url('developers')}">{esc(h['dev_link'])} →</a>
    </div>
  </div>
</section>

<section class="band alt">
  <div class="wrap">
    <h2>{esc(h['plans_title'])}</h2>
    <p class="sub">{esc(h['plans_text'])}</p>
    {plans_cards(L, compact=True)}
    <p class="center"><a class="btn" href="{L.url('plans')}">{esc(h['plans_link'])}</a></p>
  </div>
</section>

<section class="band">
  <div class="wrap narrow">
    <h2>{esc(h['faq_title'])}</h2>
    <div class="faq">{faq}</div>
  </div>
</section>

<section class="final">
  <div class="wrap">
    {LOGO}
    <h2>{esc(h['final_title'])}</h2>
    <p>{esc(h['final_text'])}</p>
    <span class="btn primary" aria-disabled="true">{icon('play')}{esc(ui['cta_get'])}</span>
  </div>
</section>
""" + footer(L))


def plan_badge(L, plan):
    return f'<span class="plan plan-{plan}">{esc(L.plan_name(plan))}</span>'


def page_features(L):
    fp = L.d["features_page"]
    chapters = json.loads((ROOT / "features.json").read_text())["chapters"]
    toc = "".join(f'<a href="#{c["id"]}" style="--c:{c["color"]}">{icon(c["icon"])}{esc(L.d["chapters"][c["id"]][0])}</a>'
                  for c in chapters)
    body = []
    for c in chapters:
        t, lead = L.d["chapters"][c["id"]]
        items = []
        for fid, plan, ic in c["features"]:
            title, text, how = L.d["features"][fid]
            items.append(f'<li class="feat" id="{fid}">{icon(ic)}<div><h3>{esc(title)} {plan_badge(L, plan)}</h3>'
                         f'<p>{esc(text)}</p><p class="how"><span>{esc(L.ui["how"])}</span> {esc(how)}</p></div></li>')
        body.append(f'<section class="chapter" id="{c["id"]}" style="--c:{c["color"]}">'
                    f'<div class="chapter-head">{icon(c["icon"], "ico big")}<div><h2>{esc(t)}</h2><p>{esc(lead)}</p></div>'
                    f'<span class="count">{len(c["features"])}</span></div>'
                    f'<ul class="feats">{"".join(items)}</ul></section>')
    return (head(L, "features", fp["title"] + " · Sofaly", fp["description"]) + header(L, "features") + f"""
<section class="page-head">
  <div class="wrap">
    <h1>{esc(fp['h1'])}</h1>
    <p class="lead">{esc(fp['lead'])}</p>
  </div>
</section>
<div class="wrap with-toc">
  <nav class="toc" aria-label="{esc(fp['index'])}">{toc}</nav>
  <div class="chapters-body">{''.join(body)}</div>
</div>
<p class="wrap center"><a class="more" href="#main">↑ {esc(L.ui['back_top'])}</a></p>
""" + footer(L))


def plans_cards(L, compact=False):
    pp = L.d["plans_page"]
    chapters = json.loads((ROOT / "features.json").read_text())["chapters"]
    by_plan = {"basic": [], "pro": [], "ultra": []}
    for c in chapters:
        for fid, plan, _ in c["features"]:
            by_plan[plan].append(L.d["features"][fid][0])
    out = []
    for plan in ["basic", "pro", "ultra"]:
        lst = ""
        if not compact:
            first = {"pro": f"<li class=\"all\">{esc(pp['everything_basic'])}</li>",
                     "ultra": f"<li class=\"all\">{esc(pp['everything_pro'])}</li>"}.get(plan, "")
            lst = f'<p class="inc">{esc(pp["includes"])}</p><ul class="inc-list">{first}' + "".join(
                f"<li>{esc(t)}</li>" for t in by_plan[plan]) + "</ul>"
        out.append(f'<div class="plan-card plan-{plan}"><h3>{esc(L.plan_name(plan))}</h3>'
                   f'<p class="who">{esc(pp[plan + "_who"])}</p><p class="price">{esc(pp["price_soon"])}</p>'
                   f'<p>{esc(pp[plan + "_text"])}</p>{lst}</div>')
    return f'<div class="plans">{"".join(out)}</div>'


def page_plans(L):
    pp = L.d["plans_page"]
    return (head(L, "plans", pp["title"], pp["description"]) + header(L, "plans") + f"""
<section class="page-head">
  <div class="wrap">
    <h1>{esc(pp['h1'])}</h1>
    <p class="lead">{esc(pp['lead'])}</p>
  </div>
</section>
<section class="band tight"><div class="wrap">{plans_cards(L)}</div></section>
""" + footer(L))


PROVIDER = """&lt;provider
    android:name=".CardProvider"
    android:authorities="com.example.score.card"
    android:exported="true"&gt;
    &lt;intent-filter&gt;
        &lt;action android:name="tv.sofaly.plugin.CARTAO" /&gt;
    &lt;/intent-filter&gt;
&lt;/provider&gt;"""

ROW = """override fun query(uri: Uri, p: Array&lt;String&gt;?, s: String?,
                   a: Array&lt;String&gt;?, o: String?): Cursor =
    MatrixCursor(arrayOf("titulo", "valor", "detalhe", "cor")).apply {
        addRow(arrayOf("Match score", "2 x 1", "38' second half", "#1E7A46"))
    }"""


def page_developers(L):
    d = L.d["dev_page"]
    demo = "".join(f'<div class="demo-tile" style="--tile:{c}"><small>{esc(a)}</small><b>{esc(b)}</b></div>'
                   for (a, b), c in zip(d["demo"], ["#1E7A46", "#2B4C9B", "#7A3FB0"]))
    why = "".join(f'<li class="card">{icon(i)}<h3>{esc(t)}</h3><p>{esc(x)}</p></li>' for i, t, x in d["why"])
    can = "".join(f'<li class="mini-card"><b>{esc(t)}</b><span>{esc(x)}</span></li>' for t, x in d["can"])
    rules = "".join(f"<li>{icon('shield')}<span>{esc(r)}</span></li>" for r in d["rules"])
    return (head(L, "developers", d["title"], d["description"]) + header(L, "developers") + f"""
<section class="hero dev-hero">
  <div class="wrap hero-in">
    <div class="hero-text">
      <p class="eyebrow">{esc(d['eyebrow'])}</p>
      <h1>{esc(d['h1'])}</h1>
      <p class="lead">{esc(d['lead'])}</p>
      <div class="ctas"><a class="btn primary" href="{L.prefix}plugins.html">{icon('puzzle')}{esc(d['cta_spec'])}</a></div>
    </div>
    <div class="card-demo" aria-hidden="true">
      {demo}
    </div>
  </div>
</section>
<section class="band"><div class="wrap"><h2>{esc(d['why_title'])}</h2><ul class="cards">{why}</ul></div></section>
<section class="band alt"><div class="wrap"><h2>{esc(d['can_title'])}</h2><ul class="minis">{can}</ul></div></section>
<section class="band"><div class="wrap narrow">
  <h2>{esc(d['steps_title'])}</h2>
  <ol class="steps">
    <li><h3>{esc(d['step1'])}</h3><pre><code>{PROVIDER}</code></pre></li>
    <li><h3>{esc(d['step2'])}</h3><pre><code>{ROW}</code></pre></li>
  </ol>
  <p class="center"><a class="more" href="{L.prefix}plugins.html">{esc(d['cta_spec'])} →</a></p>
</div></section>
<section class="band alt"><div class="wrap split">
  <div class="panel"><h2>{esc(d['rules_title'])}</h2><ul class="rules">{rules}</ul></div>
  <div class="panel"><h2>{esc(d['template_title'])}</h2><p>{esc(d['template_text'])}</p>
    <h3 class="soon">{esc(d['soon_title'])}</h3><p>{esc(d['soon_text'])}</p></div>
</div></section>
""" + footer(L))


BUILDERS = {"index": page_index, "features": page_features, "plans": page_plans, "developers": page_developers}


def check(code, data, features):
    """Every feature in features.json needs its three texts; a missing one stops the build (no half pages)."""
    missing = [f for c in features["chapters"] for f, _, _ in c["features"]
               if not isinstance(data["features"].get(f), list) or len(data["features"][f]) != 3]
    missing += [c["id"] for c in features["chapters"] if c["id"] not in data["chapters"]]
    if missing:
        raise SystemExit(f"{code}: missing texts for {missing}")


def main():
    features = json.loads((ROOT / "features.json").read_text())
    for c in features["chapters"]:
        for _, _, ic in c["features"]:
            assert ic in ICONS, ic
    langs = {}
    for p in sorted((ROOT / "content").glob("*.json")):
        data = json.loads(p.read_text())
        check(p.stem, data, features)
        langs[p.stem] = data
    order = [DEFAULT] + [c for c in langs if c != DEFAULT]
    langs = {c: langs[c] for c in order}
    assets = OUT / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    for f in (ROOT / "assets").iterdir():
        shutil.copy2(f, assets / f.name)
    for code, data in langs.items():
        L = Lang(code, data, langs)
        folder = OUT if code == DEFAULT else OUT / code
        folder.mkdir(parents=True, exist_ok=True)
        for page in PAGES:
            (folder / f"{page}.html").write_text(BUILDERS[page](L))
    print(f"built {len(PAGES)} pages x {len(langs)} languages into {OUT}")


if __name__ == "__main__":
    main()
