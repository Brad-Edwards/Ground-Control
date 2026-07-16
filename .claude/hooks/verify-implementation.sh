#!/usr/bin/env bash
# Stop hook: blocks Claude from finishing if implementation checklist is incomplete.
# Only enforces when /implement was invoked in THIS session (scoped by $PPID).
#
# The former universal changelog-fragment check was removed in #1399 (GC-P027):
# Release Please now owns CHANGELOG.md and the Towncrier `changelog.d/` convention
# was retired, so there is no per-PR fragment to enforce. Product-version-mirror
# consistency and Conventional Commit PR titles are enforced in CI instead
# (`tools/policy/checks.py::run_version_mirror_consistency_check` and
# `.github/workflows/pr-title.yml`), not in this host-local Stop hook.
#
# /review and /security-review are intentionally NOT enforced here — /implement
# owns its own review gate via gc_codex_review and /review-tests. See
# .claude/skills/implement/SKILL.md.

set -euo pipefail

INPUT=$(cat)

# Don't loop — if we're already in a stop-hook retry, let it through
STOP_HOOK_ACTIVE=$(echo "$INPUT" | jq -r '.stop_hook_active // false')
if [ "$STOP_HOOK_ACTIVE" = "true" ]; then
  exit 0
fi

# Only enforce if /implement was invoked in this session
SKILL_LOG="/tmp/claude-skill-log/${PPID}.jsonl"
if [ ! -f "$SKILL_LOG" ]; then
  exit 0
fi

HAS_IMPLEMENT=$(grep -c '"skill":"implement"' "$SKILL_LOG" || true)
if [ "$HAS_IMPLEMENT" -eq 0 ]; then
  exit 0
fi

# Find repo root (no hardcoded paths)
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo "")
if [ -z "$REPO_ROOT" ]; then
  exit 0
fi
cd "$REPO_ROOT"

# Determine base branch (dev if it exists, otherwise main)
if git rev-parse --verify origin/dev >/dev/null 2>&1; then
  BASE="origin/dev"
else
  BASE="origin/main"
fi

# Build the full changed-path set: committed work against the base branch,
# unstaged working-tree changes, staged-but-uncommitted changes, and
# untracked-but-not-ignored paths. Passed to any project-specific extra checks.
CHANGED=$( {
  git diff --name-only --diff-filter=ACDMRTUXB "${BASE}...HEAD" 2>/dev/null || true
  git diff --name-only --diff-filter=ACDMRTUXB HEAD 2>/dev/null || true
  git diff --cached --name-only --diff-filter=ACDMRTUXB 2>/dev/null || true
  git ls-files --others --exclude-standard 2>/dev/null || true
} | sort -u )
if [ -z "$CHANGED" ]; then
  exit 0
fi

REASONS=""

# Run project-specific checks if they exist
EXTRA_CHECKS="$REPO_ROOT/.claude/hooks/verify-extra.sh"
if [ -f "$EXTRA_CHECKS" ]; then
  EXTRA_REASONS=$(CHANGED="$CHANGED" bash "$EXTRA_CHECKS" || true)
  if [ -n "$EXTRA_REASONS" ]; then
    REASONS="${REASONS}${EXTRA_REASONS}"
  fi
fi

if [ -n "$REASONS" ]; then
  jq -n --arg reason "$REASONS" '{"decision": "block", "reason": $reason}'
  exit 0
fi

# All checks passed
exit 0
