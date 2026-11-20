#!/usr/bin/env python3
"""Keeps the GitHub page in step with the app and the website (Paulo, 20/11/2026: "the repo has to receive the updates").

Why: every new feature used to be remembered by hand in the README, the Release notes and the CHANGELOG, and the
three fell behind the app within days. What for: whoever opens github.com/ChrnX0/sofaly sees the same Sofaly the
TV and sofaly.tv show. How: the README feature list comes from the website's own sources (website/features.json for
the order, website/content/en.json for the words, plus the EXTRA list in website/home/build_home.py), and the
"What changed" list comes from res/values/strings_versoes.xml, the same text the TV shows in Settings > System.

    python3 scripts/readme.py           rewrite README.md, .github/release-notes.md and CHANGELOG.md
    python3 scripts/readme.py --check   exit 1 if any of them is out of date (the "Build e Release" workflow runs this)

Only the blocks between <!-- auto:NAME --> and <!-- /auto:NAME --> are touched; everything else is written by hand.
No dependencies beyond Python 3.8.
"""
import ast
import datetime
import html
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
NOTES = ROOT / ".github/release-notes.md"
CHANGELOG = ROOT / "CHANGELOG.md"
FEATURES = ROOT / "website/features.json"
WORDS = ROOT / "website/content/en.json"
HOME_WORDS = ROOT / "website/content/home/en.json"
BUILD_HOME = ROOT / "website/home/build_home.py"
CHANGES = ROOT / "app/src/main/res/values/strings_versoes.xml"


def extra_features():
    """EXTRA from build_home.py (Portuguese, translated through website/content/home/en.json), read without
    importing the module so this script never runs the site build."""
    tree = ast.parse(BUILD_HOME.read_text(encoding="utf-8"))
    extra = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", "") == "EXTRA" for t in node.targets):
            extra = ast.literal_eval(node.value)
    words = json.loads(HOME_WORDS.read_text(encoding="utf-8"))
    out, missing = {}, []
    for chapter, items in extra.items():
        for title, what, where, _icon in items:
            # The site shows the "where" line only on some pages, so it may have no translation: then it is left out.
            row = [words.get(title), words.get(what), words.get(where, "")]
            if None in row:
                missing.append(title)
                continue
            out.setdefault(chapter, []).append(row)
    return out, missing


def changes():
    """The "What changed" items of this version, unescaped the way Android shows them."""
    for arr in ET.parse(CHANGES).getroot().iter("string-array"):
        if arr.get("name") == "novidades":
            return [re.sub(r"\\(['\"@?])", r"\1", (i.text or "").strip()) for i in arr.iter("item")]
    return []


def features_block():
    order = json.loads(FEATURES.read_text(encoding="utf-8"))["chapters"]
    words = json.loads(WORDS.read_text(encoding="utf-8"))
    extra, missing = extra_features()
    if missing:
        sys.exit("readme.py: no English text in website/content/home/en.json for: " + "; ".join(missing))
    lines, total = [], 0
    for ch in order:
        name, tagline = words["chapters"][ch["id"]]
        rows = [words["features"][fid] for fid, _plan, _icon in ch["features"]] + extra.get(ch["id"], [])
        total += len(rows)
        lines.append("<details>")
        lines.append(f"<summary><b>{html.escape(name)}</b> · {html.escape(tagline)} ({len(rows)})</summary>\n")
        for title, what, where in rows:
            lines.append(f"- **{title}.** {what}" + (f" <sub>{where}</sub>" if where else ""))
        lines.append("\n</details>\n")
    head = (f"**{total} features in {len(order)} areas**, the same list as [sofaly.tv](https://sofaly.tv/#mundos). "
            "Open an area to see each one and where to find it on the TV.\n")
    return head + "\n" + "\n".join(lines).rstrip() + "\n"


def changes_block():
    return "\n".join(f"- {c}" for c in changes()) + "\n"


def put(text, name, body):
    start, end = f"<!-- auto:{name} -->", f"<!-- /auto:{name} -->"
    if start not in text or end not in text:
        sys.exit(f"readme.py: markers {start} … {end} missing")
    a = text.index(start) + len(start)
    return text[:a] + "\n" + body + text[text.index(end):]


def changelog(text, now):
    """The "Current version" block mirrors strings_versoes.xml. When that list is rewritten for a new Release, the
    previous block moves down into the history with the date it was replaced, so nothing is lost."""
    m = re.search(r"<!-- auto:current -->\n(.*?)<!-- /auto:current -->", text, re.S)
    if not m:
        sys.exit("readme.py: markers <!-- auto:current --> missing in CHANGELOG.md")
    old, new = m.group(1), changes_block()
    text = put(text, "current", new)
    if old.strip() and old.strip() != new.strip():
        entry = f"## Until {now}\n\n{old.rstrip()}\n\n"
        text = text.replace("<!-- history -->\n", "<!-- history -->\n\n" + entry, 1)
    return text


def main():
    check = "--check" in sys.argv[1:]
    today = datetime.date.today().isoformat()
    targets = {
        README: lambda t: put(put(t, "features", features_block()), "changes", changes_block()),
        NOTES: lambda t: put(t, "changes", changes_block()),
        CHANGELOG: lambda t: changelog(t, today),
    }
    stale = []
    for path, fn in targets.items():
        old = path.read_text(encoding="utf-8")
        new = fn(old)
        if check:
            # In check mode the CHANGELOG history line would carry today's date; compare only the current block.
            if path is CHANGELOG:
                new = put(old, "current", changes_block())
            if new != old:
                stale.append(path.relative_to(ROOT).as_posix())
        elif new != old:
            path.write_text(new, encoding="utf-8")
            print("updated", path.relative_to(ROOT).as_posix())
    if stale:
        print("Out of date: " + ", ".join(stale) + ". Run: python3 scripts/readme.py", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
