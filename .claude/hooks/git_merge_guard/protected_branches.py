"""Split from git-merge-guard.py under issue #1467 for the 500-LOC limit
(docs/CODING_STANDARDS.md). Definitions are unchanged.
"""

import subprocess
import sys


PROTECTED_BRANCHES = {"main", "dev"}


# The one remote-tracking ref that may be merged into a feature branch.
INTEGRATION_SOURCE_REF = "refs/remotes/origin/dev"


# Bounded per-probe timeout for the read-only git queries below. At most three
# probes run per merge, comfortably inside the hook's registered wall-clock.
GIT_QUERY_TIMEOUT_SECONDS = 5


# Shell control operators we split sub-commands at. Order matters: the
# longest match must come first so `&&` isn't mis-tokenized as `&` `&`.
SHELL_OPERATORS = ("|&", "&&", "||", ";;", ";", "|", "&", "(", ")", "{", "}", "\n")


SHELL_OPERATOR_SET = set(SHELL_OPERATORS)


# Characters that trigger shell expansion / substitution / redirection /
# pathname (glob) expansion and can change argv after this hook validates it.
# Several stay active even inside a double-quoted operand, and globs expand
# against the working tree, so the token-based operator scan above never sees
# them. Any occurrence in a candidate merge command makes it not directly
# inspectable: `$(...)` / `${...}` / `` `...` `` substitution, `<(...)` / `>(...)`
# process substitution or redirection, and `*` / `?` / `[` pathname globbing
# (which could expand a lone `-m` value into extra source operands before Git
# runs).
SHELL_EXPANSION_CHARS = ("$", "`", "<", ">", "*", "?", "[")


# Leading command wrappers we look past to find the real git/gh invocation.
COMMAND_WRAPPERS = {"env", "sudo", "command", "nice", "nohup", "time", "stdbuf", "xargs"}


# Global options before the `git` subcommand that consume the following argv
# token as their value (the `--foo=value` form is handled separately).
GIT_GLOBAL_OPTS_WITH_VALUE = {
    "-C", "-c", "--exec-path", "--git-dir", "--work-tree",
    "--namespace", "--super-prefix", "--list-cmds",
}


# Same idea for `gh`.
GH_GLOBAL_OPTS_WITH_VALUE = {"-R", "--repo", "--cwd"}


# `git push` options that consume the following argv token as their value.
# `--force-with-lease` is intentionally NOT here — its value form is
# `--force-with-lease=<ref>`; a bare `--force-with-lease` is a standalone flag
# and the next argv token is the next push positional.
PUSH_OPTS_WITH_VALUE = {"--repo", "-o", "--push-option", "--receive-pack", "--exec"}


# Closed set of `git merge` options the base-update maintenance merge may carry.
MERGE_ALLOWED_BOOL_OPTS = {"--no-edit", "--no-ff", "--ff", "--ff-only"}


MERGE_ALLOWED_VALUE_OPTS = {"-m", "--message"}


# Substrings dangerous enough to block even if the command can't be parsed.
UNPARSEABLE_FALLBACK_DENY = (
    "git merge",
    "git reset --hard",
    "gh pr merge",
    "git push --force",
    "git push -f",
)


def deny(message):
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(2)


def normalize_operators(cmd):
    """Insert spaces around shell control operators that aren't inside quotes
    or escaped, so `shlex.split` returns them as their own tokens. Not a full
    shell parser; just enough that `a;b`, `a&&b`, `a|b` become `a ; b`,
    `a && b`, `a | b`."""
    out = []
    i = 0
    n = len(cmd)
    in_single = False
    in_double = False
    while i < n:
        ch = cmd[i]
        if in_single:
            out.append(ch)
            if ch == "'":
                in_single = False
            i += 1
            continue
        if in_double:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(cmd[i + 1])
                i += 2
                continue
            if ch == '"':
                in_double = False
            i += 1
            continue
        if ch == "\\" and i + 1 < n:
            out.append(ch)
            out.append(cmd[i + 1])
            i += 2
            continue
        if ch == "'":
            in_single = True
            out.append(ch)
            i += 1
            continue
        if ch == '"':
            in_double = True
            out.append(ch)
            i += 1
            continue
        # Match a (longest) shell operator at this position.
        matched = None
        for op in SHELL_OPERATORS:
            if cmd.startswith(op, i):
                matched = op
                break
        if matched is not None:
            out.append(" ")
            out.append(matched)
            out.append(" ")
            i += len(matched)
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def split_segments(tokens):
    segments, current = [], []
    for token in tokens:
        if token in SHELL_OPERATOR_SET:
            if current:
                segments.append(current)
                current = []
        else:
            current.append(token)
    if current:
        segments.append(current)
    return segments


def strip_wrappers(argv):
    """Drop leading `VAR=value` assignments and `env`/`sudo`/… wrappers."""
    i = 0
    while i < len(argv):
        tok = argv[i]
        if "=" in tok and not tok.startswith("-") and "/" not in tok.split("=", 1)[0]:
            i += 1
            continue
        if tok.rsplit("/", 1)[-1] in COMMAND_WRAPPERS:
            i += 1
            # `env` and friends may be followed by VAR=value pairs.
            while i < len(argv) and "=" in argv[i] and not argv[i].startswith("-"):
                i += 1
            continue
        break
    return argv[i:]


def strip_global_opts(argv, opts_with_value):
    """Drop leading global options before the subcommand, including their
    values when the option is separated from its value by whitespace
    (e.g. `git -C <dir>`). The `--foo=value` form is self-contained."""
    i = 0
    while i < len(argv):
        tok = argv[i]
        if not tok.startswith("-"):
            break
        if "=" in tok:
            i += 1  # `--foo=bar`: value is attached
            continue
        if tok in opts_with_value:
            i += 2  # skip the option and its separate value
            continue
        i += 1  # boolean global flag (e.g. `--help`, `--version`, `-p`)
    return argv[i:]


def looks_protected(refspec):
    """True when a push refspec resolves to (or could resolve to) a protected
    destination."""
    if not refspec:
        return False
    if "*" in refspec:
        # Wildcard / matching refspec — could match protected branches.
        return True
    ref = refspec.lstrip("+")
    src, _, dst = ref.partition(":")
    target = dst if dst else src
    target = target.removeprefix("refs/heads/")
    if target == "HEAD":
        return True
    return target in PROTECTED_BRANCHES


def has_short_force_flag(tok):
    """True for short-option clusters that include `f` (e.g. `-f`, `-fu`,
    `-uf`). Excludes long options (`--foo`)."""
    return (
        tok.startswith("-")
        and not tok.startswith("--")
        and len(tok) >= 2
        and "f" in tok[1:]
    )


def git_query(args):
    """Run a read-only git command with fixed argv in the hook's working
    directory. Return stripped stdout on success, or None on any failure
    (missing binary, non-zero exit, timeout). Never uses a shell."""
    try:
        proc = subprocess.run(
            ["git", *args],
            capture_output=True,
            text=True,
            timeout=GIT_QUERY_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    return proc.stdout.strip()


def current_branch():
    """The checked-out local branch name, or None on detached HEAD / unborn
    branch / unreadable worktree. Resolves the FULL symbolic ref and requires
    the `refs/heads/` namespace: `symbolic-ref --short` yields an
    ambiguity-dependent abbreviation (a colliding tag can turn `main` into
    `heads/main`), which would evade the exact protected-set membership check."""
    ref = git_query(["symbolic-ref", "--quiet", "HEAD"])
    if not ref or not ref.startswith("refs/heads/"):
        return None
    return ref[len("refs/heads/"):]


def branch_merge_options_configured(destination):
    """True when `branch.<destination>.mergeOptions` is set. Git applies that
    config to merges into the branch even when the option text never appears on
    the command line, so a configured value defeats the closed command-line
    option policy (it can inject `--squash`, `-s ours`, …)."""
    value = git_query(["config", "--get-all", f"branch.{destination}.mergeOptions"])
    return bool(value)


def merge_source(after):
    """Enforce the closed merge-option set and return the single source operand.
    Denies on an unsupported option or on anything other than exactly one
    source."""
    sources = []
    i = 0
    n = len(after)
    while i < n:
        tok = after[i]
        if tok.startswith("-"):
            if tok in MERGE_ALLOWED_VALUE_OPTS:
                if i + 1 >= n:
                    deny(f"Claude may not run 'git merge' with a dangling '{tok}' option.")
                i += 2
                continue
            if "=" in tok and tok.split("=", 1)[0] in MERGE_ALLOWED_VALUE_OPTS:
                i += 1
                continue
            if tok in MERGE_ALLOWED_BOOL_OPTS:
                i += 1
                continue
            deny(
                "Claude may only run a plain base-update 'git merge' (allowed options: "
                "--no-edit, --no-ff, --ff, --ff-only, -m/--message). "
                f"Option '{tok}' is not permitted; the user handles other merges."
            )
        else:
            sources.append(tok)
            i += 1
    if len(sources) != 1:
        deny(
            "Claude may run 'git merge' only with exactly one explicit source — the "
            "integration branch 'origin/dev'. The user handles other merges."
        )
    return sources[0]


def check_git_merge(global_opts, after, inspectable, path_qualified):
    if not inspectable:
        deny(
            "Claude may only run a single, unchained 'git merge origin/dev' with no "
            "wrappers and no shell expansion, substitution, or globbing "
            "($, backtick, <, >, *, ?, [). Run fetch and merge as separate commands; "
            "the user handles the rest."
        )
    if path_qualified:
        deny(
            "Claude may only run 'git merge' as the unqualified 'git' resolved from PATH, "
            "not via an explicit path ('./git', '/usr/bin/git', …); an explicit path could "
            "run a different binary than this guard probes. The user handles that case."
        )
    if global_opts:
        deny(
            "Claude may not run 'git merge' with git global options (-C/-c/--git-dir/…); "
            "the repository context must be unambiguous."
        )
    source = merge_source(after)

    destination = current_branch()
    if destination is None:
        deny(
            "Claude may not run 'git merge' without a resolvable current branch "
            "(detached HEAD or unreadable worktree). The user handles that case."
        )
    if destination in PROTECTED_BRANCHES:
        deny(
            f"Claude may not merge while on a protected branch ('{destination}'). "
            "The user handles protected-branch merges."
        )
    if branch_merge_options_configured(destination):
        deny(
            "Claude may not run 'git merge' while 'branch."
            f"{destination}.mergeOptions' is configured; the effective merge mode is not "
            "the inspected command line. The user handles that case."
        )

    resolved = git_query(["rev-parse", "--symbolic-full-name", source])
    if resolved != INTEGRATION_SOURCE_REF:
        deny(
            f"Claude may only merge the integration branch ({INTEGRATION_SOURCE_REF}) "
            "into a feature branch. The user handles other merge sources."
        )


    # Allowed: base-update merge of origin/dev into a non-protected feature branch.


def check_gh(args):
    rest = strip_global_opts(args, GH_GLOBAL_OPTS_WITH_VALUE)
    positional = [t for t in rest if not t.startswith("-")]
    if positional[:2] == ["pr", "merge"]:
        deny("Claude may not run 'gh pr merge'. The user handles all pull-request merges.")


def check_git_push(args):
    plain_force = False
    lease_force = False
    for tok in args:
        if tok == "--force" or tok.startswith("--force="):
            plain_force = True
        elif tok == "--force-with-lease" or tok.startswith("--force-with-lease="):
            lease_force = True
        elif has_short_force_flag(tok):
            plain_force = True

    if plain_force:
        deny(
            "Claude may not run a plain 'git push --force' / 'git push -f'. "
            "Use '--force-with-lease <remote> <feature-branch>' after rebasing onto the base. "
            "The user handles anything riskier."
        )
    if not lease_force:
        return  # ordinary push — allowed

    positionals = []
    skip_next = False
    for tok in args:
        if skip_next:
            skip_next = False
            continue
        if tok.startswith("-"):
            if tok in PUSH_OPTS_WITH_VALUE and "=" not in tok:
                skip_next = True
            continue
        positionals.append(tok)
    refspecs = positionals[1:] if len(positionals) >= 2 else []

    if not refspecs:
        deny(
            "Claude may not run 'git push --force-with-lease' without an explicit "
            "'<remote> <feature-branch>'. Name the feature branch so the target is unambiguous; "
            "force-pushing the resolved upstream could hit a protected branch."
        )
    if any(looks_protected(r) for r in refspecs):
        deny(
            "Claude may not force-push to 'main', 'dev', 'HEAD', or a wildcard refspec. "
            "The user handles protected-branch history."
        )
