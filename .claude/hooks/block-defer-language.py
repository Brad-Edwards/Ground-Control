#!/usr/bin/env python3
"""
Block-Defer-Language Hook (PreToolUse, Bash matcher)

Enforces ADR-029's finding-disposition contract at tool-call time: when a
`gh issue {create,edit,comment,close}` or `gh pr {create,edit,comment}`
command carries body/title text containing deferral-disposition language
("deferred to a follow-up PR", "in a subsequent PR", "will be addressed in a
follow-up", "fixed in a subsequent PR", "handled as a follow-up issue", or —
on a closing/commenting surface for the issue under implementation — a bare
"defer*"/"TBD later"/"to be filed later"), the call is blocked. The agent
must instead fix the finding now, record `wontfix` with explicit user
authorization, or record `not-applicable` with rationale. "Defer to a
follow-up" is not in the contract; filing a tracking issue does not make it
one. See issue #830 and `architecture/notes/no-deferral-enforcement-preflight.md`.

This hook is intentionally SELF-CONTAINED: it is copied standalone to
~/.claude/hooks/ by `scripts/bootstrap-claude-workflow.sh` and cannot import
`tools/policy/checks.py`. The regex tables and `_classify` below are a
byte-for-byte mirror of `classify_deferral_language` in that module;
`tools/policy/deferral_cases.json` is the shared golden-case file that both
test suites load, so the two copies cannot drift without a test failing.

The hook never invokes `gh`/`git`/`curl` and never makes a network call. It
parses flag-supplied body text with `shlex` (not regex), reads
`--body-file <path>` as a bounded regular-file read only, ignores
`--body-file -` (stdin, which it cannot see — the `bin/policy`
completion-gate check is the backstop there), and additionally extracts
heredoc bodies (`<<'EOF' … EOF`) from the raw command string because the
common `--body "$(cat <<'EOF' … EOF)"` pattern is opaque to `shlex`.

Exit codes:
  0 = allow command
  2 = block command (denial reason printed to stderr, shown to the user)
"""
import json
import re
import shlex
import sys
from pathlib import Path

# --- shared classifier (mirror of tools/policy/checks.py) ------------------

_TIER2_SURFACES = frozenset({"issue-close", "issue-comment"})

_TIER1_PATTERNS = (
    (
        "defer-to-followup",
        r"\bdefer(?:red|s|ring|ral)?\b[^.\n]{0,40}?\b(?:to|until|for|into)\b[^.\n]{0,25}?"
        r"\b(?:follow[-\s]?up|subsequent|later|future|next|another)\b",
    ),
    (
        "in-subsequent-unit",
        r"\b(?:in|to|as)\s+(?:a\s+|the\s+|another\s+)?(?:subsequent|follow[-\s]?up)\s+"
        r"(?:PR|pull\s+request|issue|ticket|commit|iteration|sprint|cycle|change)\b",
    ),
    (
        "addressed-in-followup",
        r"\b(?:will|to|shall|can)\s+be\s+(?:addressed|handled|done|fixed|implemented|resolved|filed|tracked)\s+"
        r"(?:in|by|as)\s+(?:a\s+|the\s+|another\s+)?(?:follow[-\s]?up|subsequent|later|future|next)\b",
    ),
    (
        "fix-in-followup",
        r"\b(?:address|handle|fix|implement|resolve)(?:ed|d)?\s+(?:this\s+|that\s+|it\s+|them\s+|the\s+rest\s+)?"
        r"(?:in|as)\s+(?:a\s+|the\s+|another\s+)?(?:follow[-\s]?up|subsequent)\s+"
        r"(?:PR|pull\s+request|issue|ticket|commit|change)\b",
    ),
    (
        "nonaction-because-provenance",
        r"\b(?:not|won'?t|will\s+not|cannot|can'?t|skip(?:ping)?)\s+"
        r"(?:be\s+)?(?:fix|fixing|address|addressing|repair|repairing|resolve|resolving|handle|handling)\b"
        r"[^.\n]{0,80}\b(?:because|since|as)\b[^.\n]{0,60}"
        r"\b(?:pre-existing|unrelated|outside\s+(?:this\s+)?(?:PR'?s?\s+)?scope|out\s+of\s+scope|owned\s+by)\b",
    ),
    (
        "provenance-used-for-nonaction",
        r"\b(?:pre-existing|unrelated|outside\s+(?:this\s+)?(?:PR'?s?\s+)?scope|out\s+of\s+scope|owned\s+by[^,.;\n]{0,40})\b"
        r"[^.\n]{0,80}(?:\b(?:so|therefore|means)\b|[;:])[^.\n]{0,60}"
        r"\b(?:not|won'?t|will\s+not|skip(?:ping)?|left\s+unresolved|leave\s+unresolved)\b",
    ),
)

_TIER2_PATTERNS = (
    ("tbd-postponement", r"\bTBD\b\s*(?:[.;:,)\]]|$|\b(?:later|in\b|for\b|—|-))"),
    (
        "to-be-done-later",
        r"\bto\s+be\s+(?:done|addressed|filed|tracked|handled|fixed)\s+(?:later|separately|in\s+a\b|elsewhere)\b",
    ),
)

_BARE_WORD_RE = re.compile(r"\bdefer(?:red|s|ring|ral)?\b", re.IGNORECASE)
_NEGATION_BEFORE_RE = re.compile(
    r"\b(?:do\s+not|don'?t|never|no|not|should\s+not|shall\s+not|cannot|can'?t|without|avoid|stop)\b"
    r"(?:\W+\w+){0,3}\W*$",
    re.IGNORECASE,
)
_HISTORICAL_AFTER_RE = re.compile(r"^\W*from\b", re.IGNORECASE)

_TIER1_RES = tuple((name, re.compile(p, re.IGNORECASE | re.DOTALL)) for name, p in _TIER1_PATTERNS)
_TIER2_RES = tuple((name, re.compile(p, re.IGNORECASE)) for name, p in _TIER2_PATTERNS)


def _classify(text, surface):
    """Return ('deny', '<tier>:<name>') or ('allow', None). Mirrors
    tools/policy/checks.py::classify_deferral_language."""
    if not text:
        return ("allow", None)
    for name, rx in _TIER1_RES:
        if rx.search(text):
            return ("deny", f"tier1:{name}")
    if surface in _TIER2_SURFACES:
        for name, rx in _TIER2_RES:
            if rx.search(text):
                return ("deny", f"tier2:{name}")
        for match in _BARE_WORD_RE.finditer(text):
            before = text[max(0, match.start() - 32) : match.start()]
            if _NEGATION_BEFORE_RE.search(before):
                continue
            after = text[match.end() : match.end() + 12]
            if _HISTORICAL_AFTER_RE.match(after):
                continue
            return ("deny", "tier2:bare-defer")
    return ("allow", None)


_DENIAL_GUIDANCE = (
    "Deferral is not a valid disposition (ADR-029). Every reviewer finding must be one of: "
    "(a) fixed now in the same diff; (b) recorded `wontfix` with explicit user authorization quoted; "
    "or (c) recorded `not-applicable` with a rationale. 'Defer to a follow-up PR / issue / later "
    "iteration' is not in the contract — filing a tracking issue does not make it one. Re-route to "
    "fix-now or escalate to the user on the issue thread for authorization."
)

# --- gh-command surface detection ------------------------------------------

# (subcommand, action) → classifier surface. Only these commands are
# inspected; every other `gh` invocation and every non-`gh` command passes
# through untouched.
_SURFACE_MAP = {
    ("issue", "create"): "issue-create",
    ("issue", "edit"): "issue-edit",
    ("issue", "comment"): "issue-comment",
    ("issue", "close"): "issue-close",
    ("pr", "create"): "pr-create",
    ("pr", "edit"): "pr-edit",
    ("pr", "comment"): "pr-comment",
}
# Restrictiveness rank — higher means more patterns apply. Used to pick the
# "effective surface" for heredoc-body scanning when a compound command has
# multiple gh verbs.
_SURFACE_RANK = {
    "issue-comment": 2,
    "issue-close": 2,
    "issue-create": 1,
    "issue-edit": 1,
    "pr-create": 1,
    "pr-edit": 1,
    "pr-comment": 1,
}

# Surfaces that are closing/commenting on the issue under implementation.
# When body text on one of these can't be inspected (e.g. `--body-file -`
# reads stdin, which the hook cannot see), the hook fails CLOSED: it cannot
# rule out deferral language, and there is no completion-gate backstop for
# issue comments the way there is for the PR body.
_FAIL_CLOSED_SURFACES = frozenset({"issue-comment", "issue-close", "pr-comment"})

# Flags whose value is body/title/comment text we scan. `-c` is `gh issue
# close --comment`'s short form; `-b`/`-t` are `--body`/`--title`.
_TEXT_VALUE_FLAGS = {"--body", "-b", "--title", "-t", "--comment", "-c"}
_FILE_VALUE_FLAGS = {"--body-file", "-F"}
_BODY_FILE_MAX_BYTES = 256 * 1024

# `gh` global flags that may appear BEFORE the subcommand. `-R`/`--repo` takes
# a value; the rest take none. Anything else starting with `-` before the
# subcommand is treated conservatively as value-less. This lets the hook see
# `gh -R owner/repo issue comment ...` and `gh --repo=x pr edit ...`.
_GH_GLOBAL_VALUE_FLAGS = frozenset({"-R", "--repo"})
_GH_GLOBAL_NOVALUE_FLAGS = frozenset({"--version", "-v", "--help", "-h"})

# Heredoc body: <<EOF / <<'EOF' / <<-EOF … then the body … then the marker on
# its own line (allowing leading whitespace for <<- and trailing content like
# `)"` after the marker for the `$(cat <<'EOF' … EOF\n)` idiom).
_HEREDOC_RE = re.compile(
    r"<<[-~]?\s*[\"']?([A-Za-z_][A-Za-z0-9_]*)[\"']?\s*\n(.*?)\n[ \t]*\1\b",
    re.DOTALL,
)


def _read_body_file(path_str):
    """Bounded read of a --body-file path. Returns the text, the sentinel
    "<stdin>" for `-` (caller decides fail-open vs fail-closed by surface),
    or None when it cannot/should not be read (missing, not a regular file,
    too large, unreadable)."""
    if path_str == "-":
        return "<stdin>"
    try:
        p = Path(path_str)
        if not p.is_file():
            return None
        if p.stat().st_size > _BODY_FILE_MAX_BYTES:
            return None
        return p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def _parse_gh_command(argv):
    """Given an argv where argv[0] == 'gh', skip leading global flags and
    return (surface, remaining_args) for an inspected issue/PR command, or
    (None, []) otherwise. Handles `gh -R owner/repo issue comment ...`."""
    if not argv or argv[0] != "gh":
        return (None, [])
    i = 1
    while i < len(argv):
        tok = argv[i]
        if tok in _GH_GLOBAL_VALUE_FLAGS:
            i += 2
            continue
        if tok.startswith("--repo=") or tok.startswith("-R="):
            i += 1
            continue
        if tok in _GH_GLOBAL_NOVALUE_FLAGS:
            i += 1
            continue
        if tok.startswith("-"):
            # Unknown global flag before the subcommand — assume value-less.
            i += 1
            continue
        break
    if i + 1 >= len(argv):
        return (None, [])
    sub, action = argv[i], argv[i + 1]
    surface = _SURFACE_MAP.get((sub, action))
    if surface is None:
        return (None, [])
    return (surface, argv[i + 2 :])


def _flag_text_segments(argv):
    """Yield (surface, text-or-"<stdin>") for body/title/comment text supplied
    via flags on an inspected `gh issue|pr ...` argv. Empty when argv is not
    an inspected command."""
    surface, args = _parse_gh_command(argv)
    if surface is None:
        return []
    out = []
    i = 0
    while i < len(args):
        tok = args[i]
        if tok in _TEXT_VALUE_FLAGS:
            if i + 1 < len(args):
                out.append((surface, args[i + 1]))
            i += 2
            continue
        if tok in _FILE_VALUE_FLAGS:
            if i + 1 < len(args):
                txt = _read_body_file(args[i + 1])
                if txt is not None:
                    out.append((surface, txt))
            i += 2
            continue
        matched = False
        for flag in _TEXT_VALUE_FLAGS:
            if flag.startswith("--") and tok.startswith(flag + "="):
                out.append((surface, tok[len(flag) + 1 :]))
                matched = True
                break
        if not matched:
            for flag in _FILE_VALUE_FLAGS:
                if flag.startswith("--") and tok.startswith(flag + "="):
                    txt = _read_body_file(tok[len(flag) + 1 :])
                    if txt is not None:
                        out.append((surface, txt))
                    matched = True
                    break
        i += 1
    return out


def _gh_segments(command):
    """Split a (possibly compound) shell command into argv lists, one per
    top-level `gh ...` invocation. Uses shlex for quoting; treats `&&`, `||`,
    `;`, `|` as separators (shlex tokenizes them as literal tokens)."""
    try:
        tokens = shlex.split(command, comments=False, posix=True)
    except ValueError:
        return []
    segments = []
    current = []
    for tok in tokens:
        if tok in ("&&", "||", ";", "|", "&"):
            if current:
                segments.append(current)
            current = []
        else:
            current.append(tok)
    if current:
        segments.append(current)
    return [seg for seg in segments if seg and seg[0] == "gh"]


def _deny(reason):
    print(f"ERROR: blocked `gh` call — {reason}\n\n{_DENIAL_GUIDANCE}", file=sys.stderr)
    sys.exit(2)


def main():
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)
    command = data.get("tool_input", {}).get("command", "")
    if not command or "gh " not in command:
        sys.exit(0)

    gh_argvs = _gh_segments(command)

    # 1) Flag-supplied body/title/comment text (`--body "..."`, `--body-file path`).
    for argv in gh_argvs:
        for surface, text in _flag_text_segments(argv):
            if text == "<stdin>":
                # Cannot inspect piped stdin. On report surfaces there is no
                # completion-gate backstop for issue comments, so fail closed.
                if surface in _FAIL_CLOSED_SURFACES:
                    _deny(
                        f"a {surface} body is supplied via `--body-file -` (stdin), which the hook "
                        "cannot inspect. Pass the body inline (`--body \"...\"`) or via a regular file "
                        "(`--body-file <path>`) so the deferral check can see it."
                    )
                continue
            decision, pattern = _classify(text, surface)
            if decision == "deny":
                _deny(f"deferral-disposition language detected ({pattern}) on a {surface} surface.")

    # 2) Heredoc bodies (`gh issue comment N --body "$(cat <<'EOF' … EOF)"`),
    #    which shlex cannot extract. Determine the effective (most-restrictive)
    #    surface from the inspected gh verbs in the command, then scan each
    #    heredoc body against it.
    surfaces = [s for s in (_parse_gh_command(a)[0] for a in gh_argvs) if s is not None]
    if surfaces:
        effective = max(surfaces, key=lambda s: _SURFACE_RANK.get(s, 0))
        for _marker, body in _HEREDOC_RE.findall(command):
            decision, pattern = _classify(body, effective)
            if decision == "deny":
                _deny(
                    f"deferral-disposition language detected ({pattern}) in a heredoc body "
                    f"on a {effective} surface."
                )
    sys.exit(0)


if __name__ == "__main__":
    main()
