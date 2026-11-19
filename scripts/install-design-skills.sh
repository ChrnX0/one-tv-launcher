#!/usr/bin/env bash
# Installs the third-party design skills used by AI coding sessions into .claude/skills/
# (git-ignored; see docs/design-tools.md). They are fetched at a pinned commit instead of
# committed: Taste Skill is ~40 MB, and the transitions.dev license forbids redistributing its
# library (our repo mirror must not carry it). Runs on every session start
# (.claude/settings.json) and does nothing when the pinned versions are already there.
# Needs git and network to github.com the first time.
#
#   design-taste-frontend  Taste Skill, https://www.tasteskill.dev (MIT, free, no API key)
#   transitions-dev        transitions.dev free transitions + motion tokens (free for
#   transitions-polish     commercial use, no redistribution of the library; Pro not used)
#
#   scripts/install-design-skills.sh           # install or update to the pinned commit
#   scripts/install-design-skills.sh --force   # reinstall
#
# To update: change a *_COMMIT to a newer commit of its repo, read its changelog, run again.

set -euo pipefail

TASTE_SKILL_REPO="https://github.com/Leonxlnx/taste-skill.git"
TASTE_SKILL_COMMIT="f6b76575f949707b4e3c37cd86cb44b0b98643e8"   # v2.5.1, 07/11/2026
TRANSITIONS_REPO="https://github.com/Jakubantalik/transitions.dev.git"
TRANSITIONS_COMMIT="738de6da39c042fd71ae10c8d819b42630880a29"  # 15/11/2026

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKILLS="$ROOT/.claude/skills"
FORCE="${1:-}"

# install <repo> <commit> <license file> <source folder>:<skill name> ...
# Fetches only the pinned commit (no history) and copies each skill folder plus the license.
install() {
  local repo="$1" commit="$2" license="$3"; shift 3
  local pair need=0
  for pair in "$@"; do
    local stamp="$SKILLS/${pair#*:}/.installed-commit"
    [[ "$FORCE" == "--force" || ! -f "$stamp" || "$(cat "$stamp")" != "$commit" ]] && need=1
  done
  [[ $need == 0 ]] && return 0   # already at the pinned commit: keeps session start fast

  local tmp; tmp="$(mktemp -d)"
  git -C "$tmp" init -q
  git -C "$tmp" remote add origin "$repo"
  if ! git -C "$tmp" fetch -q --depth 1 origin "$commit"; then
    echo "install-design-skills: could not fetch $repo (offline?); skipping" >&2
    rm -rf "$tmp"; return 0   # never break a session start because of the network
  fi
  git -C "$tmp" checkout -q FETCH_HEAD
  for pair in "$@"; do
    local src="${pair%%:*}" name="${pair#*:}"
    rm -rf "${SKILLS:?}/$name"
    mkdir -p "$SKILLS"
    cp -R "$tmp/$src" "$SKILLS/$name"
    cp "$tmp/$license" "$SKILLS/$name/LICENSE.txt"
    echo "$commit" > "$SKILLS/$name/.installed-commit"
    echo "install-design-skills: $name ${commit:0:7} installed"
  done
  rm -rf "$tmp"
}

install "$TASTE_SKILL_REPO" "$TASTE_SKILL_COMMIT" LICENSE \
  skills/taste-skill:design-taste-frontend
install "$TRANSITIONS_REPO" "$TRANSITIONS_COMMIT" LICENSE \
  skills/transitions-dev:transitions-dev \
  skills/transitions-polish:transitions-polish
