#!/usr/bin/env python3
"""
Git Merge Guard Hook (PreToolUse, Bash matcher) — entry point.

The user owns every protected-branch merge and every pull-request merge. Claude
may commit and push, may run `git push --force-with-lease <remote>
<feature-branch>` to update a PR after rebasing, and — issue #1382 — may run one
narrow local maintenance merge: incorporate the integration branch
(`origin/dev`) into the current non-protected feature branch so an open PR can be
kept current. Real conflict resolution followed by an ordinary `git commit`
completes it. Everything that is a protected-branch merge, a pull-request merge,
or a destructive history rewrite stays blocked.

The policy itself lives in ./git_merge_guard/, split out under issue #1467 for
the 500-LOC limit (docs/CODING_STANDARDS.md). This file stays a script at its
original path because the hook is invoked by path, not imported.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from git_merge_guard import main  # noqa: E402

if __name__ == "__main__":
    main()
