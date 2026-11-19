# Design tools for AI coding sessions

Installed 19/11/2026 at Paulo's request. All free; no API key; nothing paid is used.

| Tool | What it is | Where | License |
|---|---|---|---|
| [Taste Skill](https://www.tasteskill.dev) 2.5.1 | Agent skill `design-taste-frontend`: art direction rules for web pages plus browser checkers (`consistency.mjs`, `copy-lint.mjs`, `perf.mjs`, `gate.mjs`). Needs Node 22+ and Chrome/Chromium. | `.claude/skills/design-taste-frontend/` (fetched, not committed, ~40 MB) | MIT |
| [transitions.dev](https://transitions.dev) | Agent skills `transitions-dev` (36 CSS transitions with `prefers-reduced-motion` guards and motion tokens) and `transitions-polish` (audits durations, easing, distance, scale, blur against the tokens). | `.claude/skills/transitions-dev/`, `.claude/skills/transitions-polish/` (fetched) | Free incl. commercial; no redistribution of the library. Pro/Business plans and the paid Motion Agent are NOT used. |
| [React Bits](https://reactbits.dev) | ~217 animated React components. We have no React, so it is a reference: port to vanilla CSS/JS on the site or panel, or rebuild in Kotlin. | `.claude/skills/react-bits-reference/SKILL.md` (our own notes, committed) | MIT + Commons Clause |

## How they get installed

`scripts/install-design-skills.sh` fetches Taste Skill and transitions.dev at pinned commits into
`.claude/skills/` (git-ignored). `.claude/settings.json` runs it on every Claude Code session start;
it does nothing when the pinned versions are already there. Run it by hand on a new machine, or
with `--force` to reinstall. To update, change the commit in the script.

Why not commit them: Taste Skill is ~40 MB (would double the repo), and the transitions.dev
license forbids redistributing its library (the public mirror must never carry it).

## When to use what

- Website (`website/`) and web panel (`app/src/main/assets/painel/`): `design-taste-frontend` to
  review or redesign a page; `transitions-dev` / `transitions-polish` for any open/close, hover,
  tab or loading motion.
- TV app (Kotlin Views): the skills don't run there. Use the transitions.dev motion tokens
  (durations, easing) and Taste Skill's rules on type and hierarchy as reference.
- Project rules always win over a skill's defaults: brand orange #FF6A2B on dark and #CE3D00 on
  light, never pink, large type (older viewers), the same visual reading as the cross home, all
  technical names in English, and site improvements wait until the app is done (fixing defects
  is fine).

## Checking a page with Taste Skill

```bash
S=.claude/skills/design-taste-frontend/scripts
python3 website/build.py
node $S/consistency.mjs site/index.html --root site --widths 1440,390 --page software \
  --profile .taste-skill/chrome --out .taste-skill/check --quick   # layout, contrast, a11y, overflow
node $S/copy-lint.mjs site/index.html --kind software             # tells of machine-written copy
```

`.taste-skill/` is git-ignored. Many FAILs are Taste Skill's house style (one section per screen,
a picture in every section); treat contrast, overflow, tap size, focus and a11y findings as
defects, and the rest as suggestions for when the site is reworked.
