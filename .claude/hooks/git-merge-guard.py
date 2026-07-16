#!/usr/bin/env python3
"""
Git Merge Guard Hook (PreToolUse, Bash matcher)

The user owns every protected-branch merge and every pull-request merge. Claude
may commit and push, may run `git push --force-with-lease <remote>
<feature-branch>` to update a PR after rebasing, and — issue #1382 — may run one
narrow local maintenance merge: incorporate the integration branch
(`origin/dev`) into the current non-protected feature branch so an open PR can be
kept current. Real conflict resolution followed by an ordinary `git commit`
completes it. Everything that is a protected-branch merge, a pull-request merge,
or a destructive history rewrite stays blocked.

Blocked unconditionally:
  - `gh pr merge`
  - `git reset --hard`
  - `git push` with a plain `--force` / `--force=<...>` / `-f` (any non-lease
    force, including short-option clusters such as `-fu` / `-uf`)

Blocked even for `--force-with-lease`:
  - a push whose explicit destination refspec is `main` or `dev`
  - a push whose refspec contains a wildcard `*` (could match protected refs)
  - a push whose refspec destination is `HEAD` (ambiguous; may be on a
    protected branch)
  - a `--force-with-lease` push with no explicit `<remote> <refspec>` (so the
    target is unambiguous — the agent must name the feature branch)

`git merge` — allowed only when EVERY condition holds (otherwise denied):
  - The invocation is a single, directly inspectable command run through the
    unqualified PATH `git` (an explicit `./git` / `/usr/bin/git` could execute a
    different binary than this guard probes with, so it is refused for merges):
    no shell operators (`;`, `&&`, `|`, subshell, …), no shell expansion,
    substitution, or pathname globbing anywhere in the raw command (`$`,
    backtick, `<`, `>`, `*`, `?`, `[` — several stay active even inside the
    double-quoted `-m` value and globs expand against the working tree, so
    `-m "$(...)"` / a backtick / a `*` would run arbitrary shell — including a
    `git checkout` to a protected branch — or inject extra source operands after
    this hook returns success), no wrappers (`env` / `sudo` / `VAR=`), and no git
    global options (`-C`, `-c`, `--git-dir`, …). Chained, directory-changing,
    expansion-bearing, wrapped, relocated, or explicit-path merges are ambiguous
    at hook time; fetch and merge run as separate calls.
  - Only the closed base-update option set appears on the command line:
    `--no-edit`, `--no-ff`, `--ff`, `--ff-only`, `-m`/`--message`. `--squash`,
    `-s`/`-X` strategies, `--allow-unrelated-histories`, `--no-verify`, and the
    source-less stateful modes `--continue`/`--abort`/`--quit` are refused.
  - No `branch.<destination>.mergeOptions` is configured. Git applies that key
    even when the prohibited mode (`--squash`, `-s ours`, …) never appears in
    the command text, so a configured value means the effective merge is not the
    inspected command line — deny rather than approve a later execution.
  - Exactly one source operand is present.
  - The current branch (destination) resolves via the FULL symbolic ref
    (`git symbolic-ref HEAD` → `refs/heads/<name>`; the `--short` abbreviation is
    ambiguity-dependent and a colliding tag could disguise a protected branch)
    and is NOT in the protected set (`main`, `dev`). The protected set is a
    hardcoded constant, not derived from any agent-writable ref or working-tree
    file: `refs/remotes/origin/*` and the working tree are both writable with a
    single `git update-ref` / edit, so a config-file authority could be forged to
    drop a protected branch. Adding a protected branch is a reviewed change to
    this constant.
  - The single source resolves (via `git rev-parse --symbolic-full-name`) to
    exactly `refs/remotes/origin/dev`. Local `dev`, `origin/main`, tags, SHAs,
    same-OID aliases, and names that merely contain `dev` are refused; identity
    is by symbolic ref, never by commit OID. (The remote-tracking ref content is
    agent-mutable and read-only/offline here by design — merging forged content
    into the agent's own feature branch is not an escalation beyond an ordinary
    commit; it never reaches a protected branch, which the destination gate
    guards independently.)

Every ambiguity — malformed hook input, unparseable command, detached HEAD,
unreadable worktree, unresolvable source, configured branch mergeOptions —
fails closed (deny).

Known residual (issue #1397): the repository-state probes below run the
PATH-resolved `git` (`subprocess.run(["git", ...])`), while an authorized
unqualified `git merge origin/dev` is resolved by Bash through shell functions
and aliases first. A `git` function, alias, or PATH shim can therefore answer
the probes with one implementation and run the merge with another (a
probe/execution TOCTOU) — e.g. report a feature branch to the probe, then
`checkout main` and merge on the real invocation. This gap is accepted, not
closed: an agent able to install such a shim already bypasses any lexical hook,
and the authorized merge only ever targets the agent's own feature branch. This
hook is a pre-execution lexical policy control, NOT an OS sandbox; the required
control layer for this residual is host filesystem permissions and process
isolation, which prevent installing the shim in the first place. See
architecture/notes/merge-guard-base-to-feature-preflight.md
§ "Trusted Git Execution Disposition (issue #1397)".

Parsing notes:

  - The raw command string is normalized so shell control operators (`;`,
    `&&`, `||`, `|`, `&`, `( )`) become standalone tokens even when the bash
    form is `a;b` or `a&&b`. Tokenization then uses `shlex` and segments are
    inspected one at a time.
  - For each segment we strip leading `VAR=value` assignments and command
    wrappers (`env`, `sudo`, …), then for `git`/`gh` we also strip global
    options that consume a value (`git -C <dir>`, `gh --repo <o/r>`, …)
    before identifying the subcommand.
  - Repository state is queried with fixed argv arrays and a bounded timeout,
    never `shell=True`. Denials name only the source/destination reason, never
    the full command or environment (unrelated argv may carry secrets).

Exit codes:
  0 = allow command
  2 = block command (message goes to the user on stderr)
"""
import json
import shlex
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


if __name__ == "__main__":
    main()
