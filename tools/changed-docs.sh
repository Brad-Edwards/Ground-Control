#!/usr/bin/env bash
# Print the Markdown docs changed vs BASE_REF, INCLUDING uncommitted changes,
# one path per line, deduped, restricted to files that still exist on disk.
#
# Why the union of three diffs: the /implement completion gate runs
# `make vale-lint` on the WORKING TREE before the change is committed (Step 6
# `verify`). A committed-only diff (`$BASE_REF...HEAD`) reports nothing at that
# point, so prose lint false-greens locally and CI becomes the first place a
# real Vale error surfaces (the #1507 shakeout hit exactly this). Unioning the
# committed, staged, and unstaged diffs makes the local gate see precisely the
# doc set CI will lint after the commit.
#
# Usage: tools/changed-docs.sh [BASE_REF]   (BASE_REF defaults to origin/dev)
#
# --diff-filter=ACMR excludes deletions; the trailing existence check drops any
# path that is present in the committed range but has since been removed from
# the working tree, so Vale is never handed a missing file.
BASE_REF="${1:-origin/dev}"
{
  git diff --name-only --diff-filter=ACMR "$BASE_REF...HEAD"   # committed vs base
  git diff --name-only --diff-filter=ACMR --cached             # staged, not committed
  git diff --name-only --diff-filter=ACMR                      # unstaged edits to tracked files
  git ls-files --others --exclude-standard                     # new, untracked, not gitignored
} 2>/dev/null \
  | grep -E '\.(md|markdown)$' \
  | sort -u \
  | while IFS= read -r f; do
      # `if` (no else) keeps the loop's exit status 0 even when the final
      # candidate no longer exists on disk — a bare `[ -f ] && …` would leave
      # the loop exiting non-zero on that last iteration.
      if [ -f "$f" ]; then printf '%s\n' "$f"; fi
    done
