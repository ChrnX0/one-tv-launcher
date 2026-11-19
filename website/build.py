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
WORLD_COLOR = {"continue": "#F5C04A", "watch": "#FF6A2B", "listen": "#3CC6B4", "play": "#6FA8FF", "apps": "#8FD07A"}


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
    ("games", "#4FC3A1", "gamepad"), ("phone", "#8FD07A", "phone"), ("windows", "#4FA3FF", "folder"),
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
    st = [(t[2], "#3CC6B4", "98.5"), (t[3], "#FF6A2B", "101.3"), (t[4], "#F5C04A", "89.1"), (t[5], "#4FC3A1", "")]
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
    tiles = [("cloud", "#4FC3A1", t[2]), ("monitor", "#4FA3FF", t[3]), ("gamepad", "#FF6A2B", t[4]),
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


# ---------------------------------------------------------------------------------------------------------------
# Pages, built on the Taste Skill kit (website/kit, MIT, copied unchanged to site/assets/): system.css + system.js
# for the frame (12-column grid, sections of one or two screens), motion.css/js for hovers and the hero's entrance,
# layout.css/js for the collage hero, moments/ for the two scroll moments. Our own CSS lives in assets/site.css
# (page CSS: the TV screens' frames, the note card, the tabs' screens) and assets/tv.css (the TV drawings).
# Motion values follow the transitions.dev token scale (150/250/400 ms, cubic-bezier(.22,1,.36,1)).
# ---------------------------------------------------------------------------------------------------------------

KIT_FILES = [
    "system.css", "system.js", "ui/base.css", "ui/cards.css", "ui/ui.js", "motion/motion.css", "motion/motion.js",
    "layout/layout.css", "layout/layout.js", "backgrounds/backgrounds.css", "backgrounds/backgrounds.js",
    "icons/icon.css", "moments/moments.js", "moments/lib.js", "moments/word-highlight/moment.css",
    "moments/word-highlight/moment.js", "moments/iris-opens/moment.css", "moments/iris-opens/moment.js",
]

PALETTE = """:root {
  --p-bg: #121528; --p-surface: #1b1e30; --p-ink: #fff3e6; --p-muted: #b3afb4;
  --p-accent: #ff6a2b; --p-accent-ink: #121528; --p-action: #ff6a2b; --p-action-ink: #121528;
  --p-inv-bg: #fff3e6; --p-inv-surface: #ece1d7; --p-inv-ink: #121528; --p-inv-muted: #5a5965;
  --p-inv-accent: #ce3d00; --p-inv-accent-ink: #fff3e6; --p-inv-action: #121528; --p-inv-action-ink: #fff3e6;
  --font-display: "Manrope", system-ui, sans-serif; --font-text: "Manrope", system-ui, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace;
  --display-weight: 600; --display-tracking: -0.03em; --display-tracking-xl: -0.04em; --ly-scale: 0.97;
}"""

ARROW = ('<svg class="m-arrow" viewBox="0 0 12 12" aria-hidden="true"><path class="m-arrow__stem" d="M1 6h8"/>'
         '<path class="m-arrow__head" d="M4.5 2.5 8 6l-3.5 3.5"/></svg>')
CHEV = ('<svg class="chev" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.5l3 3 3-3" fill="none" '
        'stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>')

FEATURES = json.loads((ROOT / "features.json").read_text())["chapters"]
N_FEATURES = sum(len(c["features"]) for c in FEATURES)


def S(L):
    """This language's site texts, with the English ones filling any gap (a new text ships before its translation)."""
    en = L.langs[DEFAULT]["site"]
    own = L.d.get("site", {})

    def merge(a, b):
        if isinstance(a, dict):
            return {k: merge(a[k], (b or {}).get(k) if isinstance(b, dict) else None) for k in a}
        return a if b is None else b
    return merge(en, own)


def fmt(s, **kw):
    for k, v in kw.items():
        s = s.replace("{" + k + "}", str(v))
    return s


def help_url(L):
    return f"{L.prefix}help.html?l={L.code}"


def legal_url(L, page):
    return f"{L.prefix}{page}.html{'#pt' if L.code.startswith('pt') else ''}"


def head(L, page, title, description, moments=()):
    alts = "\n".join(f'<link rel="alternate" hreflang="{esc(L.langs[c]["lang"])}" href="{L.abs_url(page, c)}">'
                     for c in L.langs)
    rtl = ' dir="rtl"' if L.d.get("dir") == "rtl" else ""
    a = L.prefix + "assets/"
    pre = ""
    if moments:
        pre = ("<script>document.documentElement.classList.add('mo-js');setTimeout(()=>document.documentElement"
               ".classList.add('mo-safe'),2500)</script>\n"
               f'<link rel="modulepreload" href="{a}moments/moments.js">\n<link rel="modulepreload" href="{a}moments/lib.js">\n'
               + "".join(f'<link rel="modulepreload" href="{a}moments/{m}/moment.js">\n'
                         f'<link rel="stylesheet" href="{a}moments/{m}/moment.css">\n' for m in moments))
    return f"""<!doctype html>
<html lang="{esc(L.d['lang'])}"{rtl} data-lang="{L.code}" data-default="{DEFAULT}" data-type="software" data-radius="round" data-buttons="tonal" data-motion>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{esc(title)}</title>
<meta name="description" content="{esc(description)}">
<meta property="og:type" content="website">
<meta property="og:title" content="{esc(title)}">
<meta property="og:description" content="{esc(description)}">
<meta property="og:image" content="{SITE_URL}assets/og.png">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="{a}favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="{a}favicon-180.png">
<meta name="theme-color" content="#121528">
<link rel="canonical" href="{L.abs_url(page)}">
{alts}
<link rel="alternate" hreflang="x-default" href="{L.abs_url(page, DEFAULT)}">
<meta name="site-kind" content="software">
<meta name="page-type" content="software">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400..800&family=JetBrains+Mono:wght@400..500&display=swap" rel="stylesheet">
<link rel="stylesheet" href="{a}system.css">
<link rel="stylesheet" href="{a}ui/cards.css">
<link rel="stylesheet" href="{a}motion/motion.css">
<link rel="stylesheet" href="{a}backgrounds/backgrounds.css">
<link rel="stylesheet" href="{a}layout/layout.css">
<link rel="stylesheet" href="{a}icons/icon.css">
<link rel="stylesheet" href="{a}tv.css">
<link rel="stylesheet" href="{a}site.css">
<style>
{PALETTE}
</style>
<script>document.documentElement.classList.add('m-enter');setTimeout(()=>document.documentElement.classList.add('m-enter-done'),700)</script>
<script src="{a}backgrounds/backgrounds.js"></script>
{pre}</head>
<body>
"""


def logo(L, href):
    return f'<a class="nav__logo" href="{href}" aria-label="Sofaly">{LOGO}<span>Sofaly</span></a>'


def nav(L, page):
    s = S(L)
    ui = L.ui
    cols = []
    for part in (FEATURES[:6], FEATURES[6:]):
        links = "".join(f'<a href="{L.url("features")}#{c["id"]}">{esc(L.d["chapters"][c["id"]][0])}'
                        f'<span>{esc(L.d["chapters"][c["id"]][1])}</span></a>' for c in part)
        cols.append(f'<div class="menu-col span-4">{links}</div>')
    cur = lambda p: ' aria-current="page"' if p == page else ""
    return f"""<header class="nav nav--centre">
  <div class="wrap nav__bar">
    <a class="skip-link" href="#top">{esc(ui['skip'])}</a>
    {logo(L, L.url('index'))}
    <nav class="nav__links" aria-label="Sofaly">
      <div class="nav__item">
        <a class="nav__link" href="{L.url('features')}"{cur('features')}>{esc(ui['nav_features'])} {CHEV}</a>
        <div class="nav__menu"><div class="wrap grid">{''.join(cols)}</div></div>
      </div>
      <div class="nav__item"><a class="nav__link" href="{L.url('plans')}"{cur('plans')}>{esc(ui['nav_plans'])}</a></div>
      <div class="nav__item"><a class="nav__link" href="{L.url('developers')}"{cur('developers')}>{esc(ui['nav_developers'])}</a></div>
      <div class="nav__item"><a class="nav__link" href="{help_url(L)}">{esc(ui['nav_help'])}</a></div>
    </nav>
    <div class="nav__actions">
      <a class="btn btn--primary" href="{L.url('features')}">{esc(fmt(s['cta_main'], n=N_FEATURES))}</a>
    </div>
    <button class="nav__toggle" type="button" aria-expanded="false" aria-label="{esc(s['toggle'])}">
      <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true"><path d="M2 5h14M2 13h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
    </button>
  </div>
</header>
<main id="top">
"""


def lang_select(L, page):
    s = S(L)
    opts = "".join(f'<option value="{L.url(page, c)}"{" selected" if c == L.code else ""} lang="{esc(L.langs[c]["lang"])}" data-code="{c}">'
                   f'{esc(L.langs[c]["label"])}</option>' for c in L.langs)
    return (f'<label class="lang-pick"><span>{esc(s["lang_label"])}</span>'
            f'<select data-lang-pick>{opts}</select></label>')


def closing(L, page, close_html):
    s, ui = S(L), L.ui
    f = s["footer"]
    a = L.prefix + "assets/"
    return f"""</main>
<footer class="footer closing footer--dark ground-inverse" id="contact" aria-labelledby="close-h">
  <div class="wrap">
    {close_html}
    <div class="closing__links">
      <div class="grid">
        <div class="span-4 stack-4">
          {logo(L, L.url('index'))}
          <p class="footer__lede">{esc(f['lede'])}</p>
        </div>
        <div class="span-2 footer__col"><h3>{esc(f['col_product'])}</h3><ul>
          <li><a href="{L.url('features')}">{esc(ui['nav_features'])}</a></li>
          <li><a href="{L.url('plans')}">{esc(ui['nav_plans'])}</a></li>
          <li><a href="{L.prefix}compatibility.html">{esc(f['compat'])}</a></li></ul></div>
        <div class="span-2 footer__col"><h3>{esc(f['col_build'])}</h3><ul>
          <li><a href="{L.url('developers')}">{esc(ui['nav_developers'])}</a></li>
          <li><a href="{L.prefix}plugins.html">{esc(f['plugins'])}</a></li></ul></div>
        <div class="span-2 footer__col"><h3>{esc(f['col_help'])}</h3><ul>
          <li><a href="{help_url(L)}">{esc(ui['nav_help'])}</a></li>
          <li><a href="{legal_url(L, 'privacy')}">{esc(ui['footer_privacy'])}</a></li>
          <li><a href="{legal_url(L, 'terms')}">{esc(ui['footer_terms'])}</a></li></ul></div>
        <div class="span-2 footer__col"><h3>{esc(s['lang_label'])}</h3>{lang_select(L, page)}</div>
      </div>
      <div class="footer__legal">
        <span>&copy; 2026 Sofaly</span>
        <span class="footer__mark">{esc(ui['footer_trademark'])}</span>
      </div>
    </div>
  </div>
</footer>
<script type="module" src="{a}system.js"></script>
<script type="module" src="{a}motion/motion.js"></script>
<script type="module" src="{a}layout/layout.js"></script>
<script src="{a}site.js" defer></script>
</body>
</html>
"""


def close_word(L, h2, text, label, href):
    return f"""<div class="closing__cta close--word">
      <div class="section-head--split">
        <div><h2 id="close-h">{esc(h2)}</h2><p class="t-lead">{esc(text)}</p></div>
        <a class="btn btn--primary" href="{href}">{esc(label)}{ARROW}</a>
      </div>
      <p class="close__word" aria-hidden="true">Sofaly</p>
    </div>"""


# ---- the TV pictures ----

def tv_screen(L, cls=""):
    """The cross home screen without the TV's stand: the screen itself is the product window."""
    return tv_mock(L).replace('<div class="stand"></div>\n', "").replace('class="tv"', f'class="tv {cls}"'.strip())


# the moment (ms into each screen's 7 s choreography) a still screen shows: the menu open, the toast in, the dots filled
FREEZE = {"alarm": 6500, "phone": 5000, "kids": 5200, "forecast": 4000, "player": 4200, "radio": 3000,
          "screensaver": 3000, "cards": 1500, "games": 3500, "windows": 2700}


def screen(L, key, extra=""):
    t = L.d["show"]["screens"][key][2]
    color = dict((k, c) for k, c, _ in SHOW)[key]
    return (f'<div class="tv tv--still {extra}" data-t="{FREEZE[key]}"><div class="screen" style="--c:{color};--k:{color}">'
            f'<div class="scr scr-{key}" style="--k:{color}">{SCREENS[key](t, L.d["home"]["mock"])}</div></div></div>')


def page_index(L):
    s, ui, h = S(L), L.ui, L.d["home"]
    hero, cross, house, st, mv, tour, cmp_, pl, fq, cl = (s[k] for k in (
        "hero", "cross", "house", "statement", "movies", "tour", "compare", "plans", "faq", "close"))
    cta = fmt(s["cta_main"], n=N_FEATURES)
    names = json.dumps({w: h["worlds"][w][0] for w in WORLDS}, ensure_ascii=False)
    note = "".join(f'<div class="note__line" data-step><i>✓</i><span>{x}</span></div>' for x in hero["note_lines"])

    cards = []
    for n, ((title, text), key) in enumerate(zip(house["cards"], ["alarm", "phone", "kids", "forecast"])):
        loop = ""
        cards.append(f"""<article class="card card--row card--open span-6">
            <h3>{esc(title)}</h3>
            <p>{esc(text)}</p>
            <div class="panel panel--tv" role="img" aria-label="{esc(title)}"><div class="stage stage--fade-b"><div class="tv-fit"{loop}>{screen(L, key)}</div></div></div>
          </article>""")

    facts = "".join(f"<li>{esc(x)}</li>" for x in mv["facts"])
    tabs, slots, panels = [], [], []
    for n, key in enumerate(["radio", "screensaver", "cards", "games", "windows"]):
        label, text, link = tour["tabs"][key]
        sel = "true" if n == 0 else "false"
        hid = "" if n == 0 else " hidden"
        anchor = {"radio": "listen", "screensaver": "sky", "cards": "home", "games": "play", "windows": "phone"}[key]
        tabs.append(f'<button class="tab" type="button" role="tab" id="t-{key}" aria-selected="{sel}" '
                    f'aria-controls="p-{key} s-{key}"{"" if n == 0 else " tabindex=\"-1\""}>{esc(label)}</button>')
        panels.append(f'<div class="tab-panel" role="tabpanel" id="p-{key}" aria-labelledby="t-{key}"{hid}>'
                      f'<p>{esc(text)}</p><a class="link" href="{L.url("features")}#{anchor}">{esc(link)}</a></div>')
        slots.append(f'<div class="tab-screen" id="s-{key}" role="img" aria-label="{esc(label)}"{hid}>{screen(L, key)}</div>')

    row_icons = ["shield", "text", "alarm", "radio", "film", "phone", "moon", "lock"]
    rowl = [(f'<div class="grid cmp__row" role="row"><span class="span-4 cmp__k" role="rowheader">{icon(ic)}{esc(a)}</span>'
                   f'<span class="span-4 cmp__them" role="cell">{esc(b)}</span><span class="span-4 cmp__us" role="cell">{esc(c)}</span></div>'
                   ) for (a, b, c), ic in zip(h["why_rows"], row_icons)]
    rows_a, rows_b = "".join(rowl[:4]), "".join(rowl[4:])
    head_row = (f'<div class="grid cmp__row cmp__head" role="row"><span class="span-4" role="columnheader">{esc(cmp_["col"])}</span>'
                f'<span class="span-4" role="columnheader">{esc(h["why_head"][1])}</span><span class="span-4" role="columnheader">{LOGO}{esc(h["why_head"][2])}</span></div>')

    plan_cards = plans_three(L, s)
    faq = "".join(f'<div class="faq__item"><h3>{esc(q)}</h3><p>{esc(a)}</p></div>' for q, a in fq["items"])

    body = f"""
  <section class="hero hero--collage" data-enter aria-labelledby="hero-h">
    <div class="wrap"><div class="hero-text grid hero__grid">
      <div class="hero__text span-5">
        <h1 id="hero-h">{esc(hero['h1'])}</h1>
        <p class="hero__lead">{esc(hero['lead'])}</p>
        <div class="btn-row"><a class="btn btn--primary" href="{L.url('features')}">{esc(cta)}{ARROW}</a><a class="btn btn--secondary" href="{L.url('plans')}">{esc(s['cta_plans'])}</a></div>
        <p class="hero__proof">{esc(hero['proof'])}</p>
      </div>
      <div class="hero__art span-6 start-7" role="img" aria-label="{esc(hero['art'])}">
        <div class="ly-piece ly-piece--main hero-tv" aria-hidden="true" data-names='{esc(names)}' data-of="{esc(ui['of'])}">{tv_screen(L)}</div>
        <div class="ly-piece ly-piece--note note" aria-hidden="true" data-ly-steps="700">
          <div class="note__row"><h4>{esc(hero['note_title'])}</h4><span class="note__chip" data-step data-step-at="3">{esc(hero['note_chip'])}</span></div>
          {note}
        </div>
      </div>
    </div></div>
  </section>

  <section class="section" id="cross" aria-labelledby="cross-h">
    <div class="wrap">
      <div class="grid">
        <h2 class="span-5" id="cross-h">{esc(cross['h2'])}</h2>
        <div class="span-6 start-7 stack-4"><p class="muted">{esc(cross['text'])}</p><a class="link" href="{L.url('features')}#home">{esc(cross['link'])}</a></div>
      </div>
      <div class="ui app fill tv-app" role="img" aria-label="{esc(cross['art'])}" data-ui-pause-host>
        <div class="tv-app__screen hero-tv" data-walk data-names='{esc(names)}' data-of="{esc(ui['of'])}">{tv_screen(L, 'tv--wide')}</div>
      </div>
    </div>
  </section>

  <section class="section g2" id="house" aria-labelledby="house-h">
    <div class="wrap">
      <div class="group">
        <div class="section-head">
          <h2 id="house-h">{esc(house['h2'])} <span class="muted">{esc(house['h2_muted'])}</span></h2>
        </div>
        <div class="grid">{cards[0]}{cards[1]}</div>
      </div>
      <div class="grid">{cards[2]}{cards[3]}</div>
    </div>
  </section>

  <section class="section statement" id="why" data-moment="word-highlight" aria-label="{esc(st['label'])}">
    <div class="wrap">
      <p class="t-label">{esc(st['label'])}</p>
      <p class="mo-wh__text">{st['text']}</p>
    </div>
  </section>

  <section class="section" id="movies" aria-labelledby="movies-h">
    <div class="wrap">
      <div class="grid">
        <div class="span-5 stack-5">
          <h2 id="movies-h">{esc(mv['h2'])}</h2>
          <p class="t-lead">{esc(mv['text'])}</p>
          <ul class="facts">{facts}</ul>
          <a class="link" href="{L.url('features')}#watch">{esc(mv['link'])}</a>
        </div>
        <div class="frame frame--split frame--tv span-6 start-7" role="img" aria-label="{esc(mv['art'])}">
          <div class="stage stage--fade-br"><div class="tv-fit tv-fit--cut">{screen(L, 'player')}</div></div>
        </div>
      </div>
    </div>
  </section>

  <section class="section ground-inverse" id="screens" data-moment="iris-opens" data-moment-opts='{{"from": "base"}}' aria-labelledby="screens-h">
    <div class="wrap">
      <div class="grid">
        <div class="span-5 stack-5">
          <div class="section-head"><h2 id="screens-h">{esc(tour['h2'])}</h2><p class="muted">{esc(tour['text'])}</p></div>
          <div class="tabs" role="tablist" aria-label="{esc(tour['h2'])}">{''.join(tabs)}</div>
          {''.join(panels)}
        </div>
        <div class="frame frame--split frame--tv span-6 start-7">
          <div class="stage stage--fade-br"><div class="tv-fit tv-fit--cut">{''.join(slots)}</div></div>
        </div>
      </div>
    </div>
  </section>

  <section class="section" id="compare" aria-labelledby="compare-h">
    <div class="wrap">
      <div class="group group--m">
        <div class="section-head"><h2 id="compare-h">{esc(cmp_['h2'])}</h2><p class="muted">{esc(cmp_['text'])}</p></div>
        <div class="cmp" role="table" aria-labelledby="compare-h">{head_row}{rows_a}</div>
      </div>
      <div class="group group--m">
        <div class="cmp" role="table" aria-labelledby="compare-h">{rows_b}</div>
        <p class="t-small muted cmp__src">{esc(h['why_source'])}</p>
      </div>
    </div>
  </section>

  <section class="section" id="plans" aria-labelledby="plans-h">
    <div class="wrap">
      <div class="section-head"><h2 id="plans-h">{esc(pl['h2'])}</h2><p class="muted">{esc(pl['text'])}</p></div>
      {plan_cards}
    </div>
  </section>

  <section class="section" id="faq" aria-labelledby="faq-h">
    <div class="wrap">
      <div class="grid">
        <div class="span-5 stack-4"><h2 id="faq-h">{esc(fq['h2'])}</h2><p class="muted">{esc(fq['text'])}</p><a class="link" href="{help_url(L)}">{esc(fq['link'])}</a></div>
        <div class="faq span-6 start-7">{faq}</div>
      </div>
    </div>
  </section>
"""
    return (head(L, "index", hero["title"], hero["description"], moments=("word-highlight", "iris-opens"))
            + nav(L, "index") + body
            + closing(L, "index", close_word(L, cl["h2"], cl["text"], cta, L.url("features"))))


def plan_lists(L):
    by = {"basic": [], "pro": [], "ultra": []}
    for c in FEATURES:
        for fid, plan, _ in c["features"]:
            by[plan].append(L.d["features"][fid][0])
    return by


def plans_three(L, s, full=False):
    pp, pl = L.d["plans_page"], s["plans"]
    by = plan_lists(L)
    out = []
    for plan in ["basic", "pro", "ultra"]:
        price = pl["free"] if plan == "basic" else pp["price_soon"]
        first = {"pro": pp["everything_basic"], "ultra": pp["everything_pro"]}.get(plan)
        items = by[plan] if full else by[plan][:5]
        lines = (f"<li><b>{esc(first)}</b></li>" if first else "") + "".join(f"<li>{esc(x)}</li>" for x in items)
        featured = " is-featured" if plan == "pro" else ""
        btn = "btn--primary" if plan == "pro" else "btn--secondary"
        out.append(f"""<article class="card card--plan span-4{featured}" id="plan-{plan}">
          <h3><span class="world-ico" aria-hidden="true">{icon({"basic": "tv", "pro": "house", "ultra": "people"}[plan])}</span>{esc(L.plan_name(plan))}</h3>
          <p class="muted">{esc(pp[plan + '_who'])}</p>
          <p class="price">{esc(price)}</p>
          <p class="t-small muted">{esc(fmt(pl['features_n'], n=len(by[plan])))}</p>
          <a class="btn {btn}" href="{L.url('plans')}#plan-{plan}-list">{esc(fmt(pl['cta'], plan=L.plan_name(plan)))}</a>
          <ul class="plan__list">{lines}</ul>
        </article>""")
    return f'<div class="grid plans-grid">{"".join(out)}</div>'


def plan_badge(L, plan):
    return f'<span class="badge badge--{plan}">{esc(L.plan_name(plan))}</span>'


# the picture beside each world's list: the app's own screen where the site draws one, else that world's settings list
WORLD_SCREEN = {"watch": "player", "listen": "radio", "play": "games", "house": "alarm",
                "sky": "forecast", "family": "kids", "phone": "phone"}


def world_visual(L, c, title):
    if c["id"] == "home":
        return tv_screen(L, "tv--still")
    if c["id"] in WORLD_SCREEN:
        return screen(L, WORLD_SCREEN[c["id"]])
    rows = "".join(f'<li><span class="ls-ico">{icon(ic)}</span><span>{esc(L.d["features"][fid][0])}</span><i class="ls-on"></i></li>'
                   for fid, _, ic in c["features"][:5])
    return (f'<div class="tv tv--still"><div class="screen scr-list" style="--k:{c["color"]}">'
            f'<div class="ls-head">{icon(c["icon"])}<b>{esc(title)}</b></div><ul class="ls-rows">{rows}</ul></div></div>')


def page_features(L):
    s, fp = S(L), L.d["features_page"]
    cta = fmt(s["cta_main"], n=N_FEATURES)
    def feat(fid, plan, ic):
        return (f'<li class="feat" id="{fid}"><h3><span class="feat__ico" aria-hidden="true">{icon(ic)}</span>{esc(L.d["features"][fid][0])}</h3>'
                f'<p>{plan_badge(L, plan)} {esc(L.d["features"][fid][1])}</p></li>')
    def list_screen(c, title, fs):
        rows = "".join(f'<li><span class="ls-ico">{icon(ic)}</span><span>{esc(L.d["features"][fid][0])}</span><i class="ls-on"></i></li>'
                       for fid, _, ic in fs[:5])
        return (f'<div class="tv tv--still"><div class="screen scr-list" style="--k:{c["color"]}">'
                f'<div class="ls-head">{icon(c["icon"])}<b>{esc(title)}</b></div><ul class="ls-rows">{rows}</ul></div></div>')
    # The cross is two screens: the home screen itself, then its cards (its own TV screen and heading)
    worlds = []
    for c in FEATURES:
        t, lead = L.d["chapters"][c["id"]]
        if c["id"] == "home":
            cut = next(i for i, f in enumerate(c["features"]) if f[0].startswith("card-"))
            worlds.append((c["id"], c, t, lead, c["features"][:cut], world_visual(L, c, t)))
            ct, clead = L.d["show"]["screens"]["cards"][:2]
            worlds.append(("home-cards", c, ct, clead, c["features"][cut:], screen(L, "cards")))
        else:
            worlds.append((c["id"], c, t, lead, c["features"], world_visual(L, c, t)))
    secs = []
    for n, (sid, c, t, lead, fs, art) in enumerate(worlds):
        ground = "ground-inverse" if sid == "sky" else ("ground-surface" if n % 2 else "")
        moment = ' data-moment="iris-opens" data-moment-opts=\'{"from": "base"}\'' if sid == "sky" else ""
        # on a phone these worlds take two whole screens (their lists run past one and a half)
        # the longest lists take two screens: on a wide screen two halves, each beside a TV; on a phone one list
        two = sid in ("home", "watch", "house", "sky")
        tall = " world--tall" if two or sid in ("phone", "care") else ""  # two screens on a phone
        head_html = (f'<h2 id="{sid}-h">{esc(t)}</h2>'
                     f'<p class="world__lead">{esc(lead)} <span class="muted">{esc(fmt(s["plans"]["features_n"], n=len(fs)))}</span></p>')
        def ul(part):
            return f'<ul class="feats world__feats">{"".join(feat(*f) for f in part)}</ul>'
        def group(text, art, more="", cls=""):
            # a phone shows the TV between the two halves of the list; a wide screen keeps the list in one column
            more = f'<div class="span-6 world__more">{more}</div>' if more else ""
            return (f'<div class="group"><div class="grid world__grid"><div class="span-6 world__text">{text}</div>'
                    f'<div class="world-art span-6 start-7{cls}" role="img" aria-label="{esc(t)}"><div class="world-art__tv">{art}</div></div>{more}</div></div>')
        if two:
            k = len(fs) // 2
            groups = (group(head_html + ul(fs[:k]), art)
                      + group(ul(fs[k:]), list_screen(c, t, fs[k:]), cls=" world-art--second"))
        else:
            k = (len(fs) + 1) // 2
            groups = group(head_html + ul(fs[:k]), art, ul(fs[k:]))
        secs.append(f"""
  <section class="section world{' world--two' if two else ''}{' world--short' if len(fs) <= 3 else ''} {ground}{tall}" id="{sid}"{moment} aria-labelledby="{sid}-h" style="--c:{c['color']}{';--h:2' if two else ''}">
    <div class="wrap">
      {groups}
    </div>
  </section>""")
    body = f"""
  <section class="hero hero--map" data-enter aria-labelledby="hero-h">
    <div class="wrap"><div class="hero-text grid hero__grid">
      <div class="hero__text span-5">
        <h1 id="hero-h">{esc(fp['h1'])}</h1>
        <p class="hero__lead">{esc(fp['lead'])} <span class="muted">{esc(fmt(s['plans']['features_n'], n=N_FEATURES))}</span></p>
        <div class="btn-row"><a class="btn btn--primary" href="#{FEATURES[0]['id']}">{esc(L.d['chapters'][FEATURES[0]['id']][0])}{ARROW}</a></div>
      </div>
      <div class="hero__art dev-art span-6 start-7" role="img" aria-label="{esc(fp['h1'])}">{tv_screen(L, "tv--still")}</div>
    </div></div>
  </section>
{''.join(secs)}
"""
    return (head(L, "features", fp["title"] + " · Sofaly", fp["description"], moments=("iris-opens",)) + nav(L, "features") + body
            + closing(L, "features", close_word(L, L.d["plans_page"]["h1"], L.d["plans_page"]["lead"], s["cta_plans"], L.url("plans"))))


SHOWN = 18  # a plan's list shows this many on the plans page; the features page has every one


def page_plans(L):
    s, pp, pl = S(L), L.d["plans_page"], S(L)["plans"]
    by = plan_lists(L)
    cta = fmt(s["cta_main"], n=N_FEATURES)
    # each plan's whole list beside a screen that plan opens: the cross for Basic, the phone for Pro, the forecast for Ultra
    vis = {"basic": tv_screen(L, "tv--still"), "pro": screen(L, "phone"), "ultra": screen(L, "forecast")}
    def plan_sec(plan, ground, frame):
        first = {"pro": pp["everything_basic"], "ultra": pp["everything_pro"]}.get(plan)
        lis = (f'<li class="plan-all__first"><b>{esc(first)}</b></li>' if first else "") + "".join(f"<li>{esc(x)}</li>" for x in by[plan][:SHOWN])
        name = L.plan_name(plan)
        more = (f'<a class="link" href="{L.url("features")}">{esc(fmt(pl["features_n"], n=len(by[plan])))}{ARROW}</a>'
                if len(by[plan]) > SHOWN else "")
        if frame:
            art = (f'<div class="frame frame--split frame--tv span-6 start-7" role="img" aria-label="Sofaly {esc(name)}">'
                   f'<div class="stage stage--fade-br"><div class="tv-fit tv-fit--cut">{vis[plan]}</div></div></div>')
        else:
            art = (f'<div class="panel panel--tv plan-vis span-6 start-7" role="img" aria-label="Sofaly {esc(name)}">'
                   f'<div class="stage stage--fade-b"><div class="tv-fit">{vis[plan]}</div></div></div>')
        moment = ' data-moment="iris-opens" data-moment-opts=\'{"from": "base"}\'' if ground == "ground-inverse" else ""
        return f"""
  <section class="section plan-sec {ground}" id="plan-{plan}-list"{moment} aria-labelledby="plan-{plan}-h">
    <div class="wrap">
      <div class="grid">
        <div class="span-5 stack-5 plan-sec__text">
          <h2 id="plan-{plan}-h">Sofaly {esc(name)}</h2>
          <p class="t-lead">{esc(pp[plan + '_text'])}</p>
          <ul class="plan-all plan-all--{plan}">{lis}</ul>
          {more}
        </div>
        {art}
      </div>
    </div>
  </section>"""
    body = f"""
  <section class="hero hero--plans" data-enter aria-labelledby="hero-h">
    <div class="wrap"><div class="hero-text grid hero__grid">
      <div class="hero__text span-5">
        <h1 id="hero-h">{esc(pp['h1'])}</h1>
        <p class="hero__lead">{esc(pp['lead'])}</p>
        <div class="btn-row"><a class="btn btn--primary" href="{L.url('features')}">{esc(cta)}{ARROW}</a></div>
      </div>
      <div class="hero__art dev-art span-6 start-7" role="img" aria-label="{esc(pp['h1'])}">{tv_screen(L, "tv--still")}</div>
    </div></div>
  </section>

  <section class="section" id="plans" aria-labelledby="plans-h">
    <div class="wrap">
      <div class="section-head"><h2 id="plans-h">{esc(pl['h2'])}</h2><p class="muted">{esc(pl['text'])}</p></div>
      {plans_three(L, s)}
    </div>
  </section>
{plan_sec("basic", "ground-surface", True)}{plan_sec("pro", "ground-inverse", False)}{plan_sec("ultra", "", True)}
"""
    return (head(L, "plans", pp["title"], pp["description"], moments=("iris-opens",)) + nav(L, "plans") + body
            + closing(L, "plans", close_word(L, s["close"]["h2"], s["close"]["text"], cta, L.url("features"))))


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
        addRow(arrayOf("Match score", "2 x 1",
                       "38' second half", "#1E7A46"))
    }"""


def page_developers(L):
    s, d = S(L), L.d["dev_page"]
    demo = "".join(f'<div class="dev-tile" style="--tile:{c}"><small>{esc(a)}</small><b>{esc(b)}</b></div>'
                   for (a, b), c in zip(d["demo"], ["#1E7A46", "#2B4C9B", "#B4531C"]))
    why = "".join(f'<li class="dev-why"><h3>{esc(t)}</h3><p class="muted">{esc(x)}</p></li>' for i, t, x in d["why"])
    ideas = "".join(f'<div class="dev-tile" style="--tile:{c}"><small>{esc(t)}</small><b>{esc(x)}</b></div>'
                    for (t, x), c in zip(d["can"], ["#1E7A46", "#2B4C9B", "#B4531C", "#1F6F8B", "#8A5A12", "#3F5F2A"]))
    rules = "".join(f"<li>{esc(r)}</li>" for r in d["rules"])
    # the card the two steps' code answers with, drawn as the TV shows it
    result = ('<div class="dev-tile dev-tile--one" style="--tile:#1E7A46"><small>Match score</small><b>2 x 1</b>'
              "<span>38' second half</span></div>")
    # the person's switch for each plugin: the rules' "the person chooses which ones show"
    toggles = "".join(f'<li><span class="ls-ico">{icon("puzzle")}</span><span>{esc(t)}</span><i class="ls-on"></i></li>'
                      for t, _ in d["can"][:4])
    def tv(inner, cls=""):
        return f'<div class="tv tv--still"><div class="screen {cls}">{inner}</div></div>'
    def split(sid, ground, left, art_label, art):
        moment = ' data-moment="iris-opens" data-moment-opts=\'{"from": "base"}\'' if ground == "ground-inverse" else ""
        vis = (f'<div class="frame frame--split frame--tv span-6 start-7" role="img" aria-label="{esc(art_label)}"><div class="stage stage--fade-br"><div class="tv-fit tv-fit--cut">{art}</div></div></div>'
               if sid == "steps" else
               f'<div class="panel panel--tv dev-vis span-6 start-7" role="img" aria-label="{esc(art_label)}"><div class="stage"><div class="tv-fit">{art}</div></div></div>')
        return f"""
  <section class="section {ground}" id="{sid}"{moment} aria-labelledby="{sid}-h">
    <div class="wrap">
      <div class="grid dev-split">
        <div class="span-5 stack-5">{left}</div>
        {vis}
      </div>
    </div>
  </section>"""
    body = f"""
  <section class="hero hero--dev" data-enter aria-labelledby="hero-h">
    <div class="wrap"><div class="hero-text grid hero__grid">
      <div class="hero__text span-5">
        <h1 id="hero-h">{esc(d['h1'])}</h1>
        <p class="hero__lead">{esc(d['lead'])}</p>
        <div class="btn-row"><a class="btn btn--primary" href="{L.prefix}plugins.html">{esc(d['cta_spec'])}{ARROW}</a></div>
      </div>
      <div class="hero__art dev-art span-6 start-7" role="img" aria-label="{esc(d['h1'])}">
        {tv(f'<div class="dev-row">{demo}</div>')}
      </div>
    </div></div>
  </section>
{split("why", "ground-surface", f'<h2 id="why-h">{esc(d["why_title"])}</h2><ul class="dev-whys">{why}</ul>', d["can_title"],
       tv(f'<div class="dev-row dev-row--six">{ideas}</div>'))}
{split("steps", "ground-inverse", f'<h2 id="steps-h">{esc(d["steps_title"])}</h2>'
       f'<h3>1. {esc(d["step1"])}</h3><pre class="code"><code>{PROVIDER}</code></pre>'
       f'<h3>2. {esc(d["step2"])}</h3><pre class="code"><code>{ROW}</code></pre>'
       f'<a class="link" href="{L.prefix}plugins.html">{esc(d["cta_spec"])}{ARROW}</a>', d["step2"],
       tv(f'<div class="dev-row dev-row--one">{result}</div>'))}
{split("rules", "ground-surface", f'<h2 id="rules-h">{esc(d["rules_title"])}</h2><ul class="dev-rules">{rules}</ul>'
       f'<h3>{esc(d["soon_title"])}</h3><p class="muted">{esc(d["soon_text"])}</p>', d["rules_title"],
       tv(f'<div class="ls-head">{icon("puzzle")}<b>{esc(L.d["features"]["plugin-cards"][0])}</b></div><ul class="ls-rows">{toggles}</ul>', "scr-list"))}
"""
    return (head(L, "developers", d["title"], d["description"], moments=("iris-opens",)) + nav(L, "developers") + body
            + closing(L, "developers", close_word(L, d["template_title"], d["template_text"].split(".")[0] + ".", d["cta_spec"], f"{L.prefix}plugins.html")))


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
        if f.is_file():
            shutil.copy2(f, assets / f.name)
    for rel in KIT_FILES:  # the kit, unchanged (its MIT notice goes with it)
        (assets / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "kit" / rel, assets / rel)
    shutil.copy2(ROOT / "kit" / "LICENSE.txt", assets / "KIT-LICENSE.txt")
    for code, data in langs.items():
        L = Lang(code, data, langs)
        folder = OUT if code == DEFAULT else OUT / code
        folder.mkdir(parents=True, exist_ok=True)
        for page in PAGES:
            (folder / f"{page}.html").write_text(BUILDERS[page](L))
    print(f"built {len(PAGES)} pages x {len(langs)} languages into {OUT}")


if __name__ == "__main__":
    main()
