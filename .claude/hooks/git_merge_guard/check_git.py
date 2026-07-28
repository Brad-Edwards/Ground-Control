"""Split from git-merge-guard.py under issue #1467 for the 500-LOC limit
(docs/CODING_STANDARDS.md). Definitions are unchanged.
"""

import json
import shlex
import sys
from .protected_branches import GIT_GLOBAL_OPTS_WITH_VALUE, SHELL_EXPANSION_CHARS, SHELL_OPERATOR_SET, UNPARSEABLE_FALLBACK_DENY, check_gh, check_git_merge, check_git_push, deny, normalize_operators, split_segments, strip_global_opts, strip_wrappers


def check_git(args, inspectable, path_qualified):
    rest = strip_global_opts(args, GIT_GLOBAL_OPTS_WITH_VALUE)
    global_opts = args[: len(args) - len(rest)]
    subcommand = None
    sub_index = None
    for index, tok in enumerate(rest):
        if not tok.startswith("-"):
            subcommand = tok
            sub_index = index
            break
    if subcommand is None:
        return
    after = rest[sub_index + 1:]

    if subcommand == "merge":
        check_git_merge(global_opts, after, inspectable, path_qualified)
    if subcommand == "reset" and "--hard" in after:
        deny("Claude may not run 'git reset --hard'. The user handles destructive history rewrites.")
    if subcommand == "push":
        check_git_push(after)


def main():
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        deny("Claude command hook received malformed JSON input; refusing to evaluate the command.")
    if not isinstance(data, dict):
        deny("Claude command hook received a non-object payload; refusing to evaluate the command.")
    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        sys.exit(0)  # no command to gate
    command = tool_input.get("command", "")
    if command == "":
        sys.exit(0)
    if not isinstance(command, str):
        deny("Claude command hook received a non-string command; refusing to evaluate the command.")

    try:
        normalized = normalize_operators(command)
        tokens = shlex.split(normalized, comments=True)
    except ValueError:
        for needle in UNPARSEABLE_FALLBACK_DENY:
            if needle in command:
                deny(f"Claude may not run '{needle}'. The user handles merges and destructive history rewrites.")
        sys.exit(0)

    # A merge is authorized only as a single, directly inspectable invocation:
    # any shell operator anywhere makes the merge's directory / repository
    # context ambiguous, and any shell expansion / substitution character can
    # execute arbitrary shell after this hook returns (even inside a quoted `-m`
    # value), so both taint the whole command for merge purposes.
    has_operators = any(tok in SHELL_OPERATOR_SET for tok in tokens)
    has_expansion = any(ch in command for ch in SHELL_EXPANSION_CHARS)

    for segment in split_segments(tokens):
        argv = strip_wrappers(segment)
        if not argv:
            continue
        wrapped = len(argv) != len(segment)
        inspectable = not has_operators and not has_expansion and not wrapped
        program = argv[0].rsplit("/", 1)[-1]
        path_qualified = "/" in argv[0]
        if program == "git":
            check_git(argv[1:], inspectable, path_qualified)
        elif program == "gh":
            check_gh(argv[1:])

    sys.exit(0)
