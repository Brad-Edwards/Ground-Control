#!/usr/bin/env python3
"""Defense-in-depth guard against direct worktree creation by agent Bash.

The canonical /implement boundary is gc_prepare_implement_branch. This hook
blocks the direct command shape across Claude Bash calls; /integrate is
unaffected because its isolated worktrees are created inside the MCP server,
not by an agent Bash command.
"""

from __future__ import annotations

import json
import re
import sys


WORKTREE_ADD_RE = re.compile(
    r"(?:^|[;&|]\s*)"
    r"(?:command\s+|env(?:\s+[A-Za-z_][A-Za-z0-9_]*=[^\s]+)*\s+)?"
    r"(?:[^\s;&|]*/)?git"
    r"(?:\s+-C\s+(?:'[^']*'|\"[^\"]*\"|[^\s;&|]+))*"
    r"\s+worktree\s+add(?:\s|$)",
    re.IGNORECASE,
)


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return 0
    command = (payload.get("tool_input") or {}).get("command")
    if not isinstance(command, str) or not WORKTREE_ADD_RE.search(command):
        return 0
    print(
        json.dumps(
            {
                "decision": "block",
                "reason": (
                    "/implement must create or switch its issue branch in the invocation checkout "
                    "through gc_prepare_implement_branch. Direct 'git worktree add' is prohibited; "
                    "the /integrate MCP operation retains its separate isolated-worktree contract."
                ),
            }
        )
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
