#!/usr/bin/env python3
"""Marks the moving part of each cross icon (class="a" data-m="<motion>") in art/icon-sets/<set>/<type>.svg.

Why: like the Essential (Linha) set, every icon tells something of its own when chosen: the TV antennas look for the
signal, the reel turns, the needle tunes, the star twinkles. scripts/render-icon-sets.js renders the marked part into
<type>-a.webp and the app moves only that layer (IconeCruz, motion name + pivot in assets/icon-sets/motion.json).
Motions: spin, swing (rotates around the bottom), wiggle, nudge (right), bob (up), pulse (grows), twinkle (fades).
Each entry: (motion, [start of the element(s) as written in the SVG]). Run after editing the art, then the renderer.
Usage: python3 art/icon-sets/motion.py
"""
import os, re, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
TABLE = {
    'vintage': {
        'continue': ('nudge', ['<path d="M48 64 H72"']),
        'watch': ('swing', ['<path d="M50 22 L40 8', '<circle cx="40" cy="8"', '<circle cx="82" cy="6"']),
        'listen': ('nudge', ['<path d="M56 73 V83"']),
        'play': ('pulse', ['<circle cx="82" cy="60"', '<circle cx="97" cy="54"']),
        'apps': ('bob', ['<rect x="64" y="10" width="36" height="36" rx="8" fill="#E2A93B"']),
        'series': ('nudge', ['<path d="M66 78 h40', '<path d="M74 86 H96"']),
        # only the big reel turns (one centre: two reels would orbit around the middle)
        'movies': ('spin', ['<path d="M40 20 V44 M28 32 H52"']),
        'podcasts': ('wiggle', ['<rect x="44" y="8" width="32" height="50" rx="16" fill="#E2A93B"', '<path d="M48 22 H72']),
        'radio': ('nudge', ['<path d="M82 46 V58"']),
        'network': ('twinkle', ['<path d="M86 52 a16 16']),
        'addons': ('wiggle', ['<path d="M22 30 H44', '<path d="M22 30 H44', '<path d="M30 40 H40 M30 48 H36"']),
        'plugin': ('bob', ['<path d="M50 30 V12 M70 30 V12"']),
        'games': ('twinkle', ['<path d="M42 34 H78 M42 42 H66"']),
        'sofa': ('bob', ['<rect x="28" y="30" width="64" height="30"', '<path d="M60 32 V58"']),
        'all-apps': ('pulse', ['<rect x="48" y="36" width="24" height="24" rx="6" fill="#4F7CAC"']),
        'sleep': ('twinkle', ['<path d="M30 20 l3 7', '<circle cx="96" cy="26"']),
        'hub': ('wiggle', ['<path d="M46 66 V60"', '<circle cx="46" cy="58"']),
    },
    'jewel': {
        'continue': ('nudge', ['<path d="M51 35 V65 L77 50 Z"']),
        'watch': ('twinkle', ['<path d="M11 19 H62 L38 77 H11 Z"']),
        'listen': ('pulse', ['<rect x="14" y="52"', '<rect x="84" y="52"']),
        'play': ('pulse', ['<circle cx="86" cy="49"', '<circle cx="95" cy="58"', '<circle cx="77" cy="58"', '<circle cx="86" cy="67"']),
        'apps': ('spin', ['<circle cx="80" cy="30" r="15.5"']),
        'series': ('bob', ['<rect x="28" y="12" width="84" height="54"']),
        'movies': ('spin', ['<circle cx="60" cy="50" r="42"', '<circle cx="60" cy="50" r="6"', '<circle cx="60" cy="26"', '<circle cx="60" cy="74"',
                            '<circle cx="39" cy="38"', '<circle cx="81" cy="38"', '<circle cx="39" cy="62"', '<circle cx="81" cy="62"']),
        'podcasts': ('wiggle', ['<rect x="44" y="8" width="32" height="52"', '<path d="M48 24 H72']),
        'radio': ('swing', ['<path d="M30 30 L84 12"']),
        'network': ('twinkle', ['<path d="M86 52 a16 16']),
        'addons': ('wiggle', ['<path d="M22 30 H44']),
        'plugin': ('bob', ['<rect x="47" y="10"', '<rect x="67" y="10"']),
        'games': ('twinkle', ['<path d="M42 34 H78 M42 42 H66"']),
        'sofa': ('bob', ['<rect x="28" y="30" width="64" height="30"']),
        'all-apps': ('pulse', ['<circle cx="60" cy="50" r="11"']),
        'sleep': ('twinkle', ['<path d="M30 18 l3 7', '<circle cx="98" cy="24"']),
        'hub': ('wiggle', ['<path d="M34 10 H86 V36', '<path d="M34 16 H22']),
    },
    'photoreal': {
        'continue': ('nudge', ['<path d="M55 36 V60 L74 48 Z" fill="#000"', '<path d="M55 36 V60 L74 48 Z" fill="#FFF8F2"']),
        'watch': ('pulse', ['<circle cx="74" cy="55" r="9"']),
        'listen': ('pulse', ['<rect x="8" y="60"', '<rect x="84" y="60"']),
        'play': ('pulse', ['<circle cx="86" cy="46"', '<circle cx="96" cy="56"', '<circle cx="76" cy="56"', '<circle cx="86" cy="66"']),
        'apps': ('bob', ['<rect x="62" y="8" width="38"', '<rect x="64" y="10" width="34"']),
        'series': ('nudge', ['<rect x="70" y="74"']),
        'movies': ('swing', ['<g transform="rotate(-18 16 40)"']),
        'podcasts': ('wiggle', ['<rect x="42" y="6"', '<rect x="46" y="10"', '<rect x="42" y="40"']),
        'radio': ('nudge', ['<rect x="82" y="43"']),
        'network': ('twinkle', ['<path d="M86 52 a16 16', '<circle cx="47" cy="80"']),
        'addons': ('wiggle', ['<path d="M22 30 H44', '<path d="M26 34 H44']),
        'plugin': ('bob', ['<rect x="48" y="8"', '<rect x="66" y="8"']),
        'games': ('twinkle', ['<path d="M40 30 H70 M40 38 H60"']),
        'sofa': ('bob', ['<rect x="26" y="26" width="68"', '<path d="M60 28 V58"']),
        'all-apps': ('pulse', ['<rect x="48" y="36" width="24"', '<rect x="50" y="38" width="20"']),
        'sleep': ('twinkle', ['<circle cx="98" cy="22"', '<circle cx="26" cy="18"', '<circle cx="60" cy="48" r="46"']),
        'hub': ('wiggle', ['<path d="M34 10 H86 V36', '<path d="M38 14 H48', '<path d="M34 16 H22']),
    },
    'vintage2': {
        'continue': ('spin', ['<circle cx="36" cy="38" r="14"', '<circle cx="36" cy="27"', '<circle cx="27" cy="44"', '<circle cx="45" cy="44"']),
        'watch': ('twinkle', ['<rect x="17" y="24" width="66" height="54" rx="14" fill="url(#scan)"', '<path d="M22 34 Q24 27 34 26"']),
        'listen': ('nudge', ['<path d="M60 81 L64 75"']),
        'play': ('swing', ['<rect x="57" y="16"', '<ellipse cx="62" cy="17"']),
        'apps': ('pulse', ['<circle cx="82" cy="72" r="14" fill="#C8452B"']),
        'series': ('swing', ['<path d="M40 22 A20 14']),
        'movies': ('twinkle', ['<path d="M100 62 L120 50']),
        'podcasts': ('wiggle', ['<path d="M44 12 H76', '<path d="M42 18 H78', '<path d="M60 12 V64"']),
        'radio': ('nudge', ['<path d="M85 42 V52"']),
        # the rotary dial turns with its finger holes (symmetric, so the box centre is the dial centre)
        'network': ('spin', ['<circle cx="60" cy="72" r="13"', '<circle cx="60" cy="72" r="3"'] + [f'<circle cx="{c}"' for c in
                    ('69.0" cy="72.0', '67.3" cy="77.3', '62.8" cy="80.6', '57.2" cy="80.6', '52.7" cy="77.3', '51.0" cy="72.0',
                     '52.7" cy="66.7', '57.2" cy="63.4', '62.8" cy="63.4', '67.3" cy="66.7')]),
        'addons': ('wiggle', ['<path d="M22 30 H44', '<path d="M26 34 H44 V40 H26Z"']),
        'plugin': ('bob', ['<rect x="48" y="8"', '<rect x="66" y="8"']),
        'games': ('twinkle', ['<path d="M42 46 H74 M42 54 H64"']),
        'sofa': ('bob', ['<rect x="24" y="24" width="72"', '<path d="M48 26 V56 M72 26 V56"']),
        'all-apps': ('pulse', ['<circle cx="90" cy="80" r="9" fill="#C8452B"']),
        'sleep': ('wiggle', ['<circle cx="32" cy="20" r="12"', '<circle cx="88" cy="20" r="12"', '<path d="M34 22 L26 12']),
        'hub': ('twinkle', ['<path d="M40 40 H58"', '<path d="M40 48 H52"']),
    },
}

def mark(svg, motion, starts):
    hits = 0
    for s in starts:
        i = svg.find(s)
        if i < 0:
            raise SystemExit(f'not found: {s}')
        tag = s.split()[0]
        # add the attributes right after the tag name of THIS element (the first match is the visible one: the
        # Vintage shadow copies are written with fill="#FF6A2B", so the entries include the fill when needed)
        svg = svg[:i] + f'{tag} class="a" data-m="{motion}"' + svg[i + len(tag):]
        hits += 1
    return svg, hits

if __name__ == '__main__':
    for setname, types in TABLE.items():
        for t, (motion, starts) in types.items():
            for name in ([t, 'tv'] if t == 'watch' else [t]):
                p = os.path.join(ROOT, setname, name + '.svg')
                svg = re.sub(r' class="a" data-m="\w+"', '', open(p).read())
                svg, n = mark(svg, motion, starts)
                open(p, 'w').write(svg)
    print('marked')
