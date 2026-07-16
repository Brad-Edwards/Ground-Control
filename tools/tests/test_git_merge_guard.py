"""Tests for .claude/hooks/git-merge-guard.py.

Drives the hook as a subprocess with synthetic Claude PreToolUse JSON on
stdin and asserts the exit code: 2 = blocked, 0 = allowed. The hook tokenizes
the command line with shlex and inspects parsed argv (force flags, push
refspecs, merge operands) plus real repository state rather than matching
substrings.

Merge policy (issue #1382): the user still owns protected-branch and
pull-request merges, but the guard permits ONE narrow local maintenance merge —
incorporating the integration branch (`origin/dev`) into a non-protected
feature branch — so an open PR can be kept current. A merge is allowed only
when it is a single, directly inspectable `git merge` invocation run through the
unqualified PATH `git` (no shell operators, no shell expansion/substitution/glob
such as `$(...)` / backticks / `*`, no wrappers, no git global options, no
explicit-path binary), carries only the closed base-update option set, has no
`branch.<dest>.mergeOptions` configured, names exactly one source that resolves
to the `refs/remotes/origin/dev` ref, and runs while checked out on a branch
that is NOT in the hardcoded protected set (`main`, `dev`). The destination is
read from the FULL symbolic ref so a colliding tag cannot disguise a protected
branch, and the protected set is a hardcoded constant rather than an
agent-writable ref/file. Every ambiguity fails closed.

Every merge case is exercised against a temporary real Git repository checked
out on a NON-protected feature branch with a valid `refs/remotes/origin/dev`.
That isolation is deliberate: the denial under test must be attributable to the
specific check, not to the destination-protection check or an ambient checkout.
A "denied before repository probe" case (shell operators/wrappers/expansion,
git global options, explicit-path git, closed-option/source-count) run on a
non-protected feature branch would fall through to a real ALLOW (exit 0) if its
check regressed, so exit 2 pins that check. Cases with no repository dependence
at all (PR merge, reset, force push, unparseable/malformed input) stay as fast
string cases.
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
HOOK = REPO_ROOT / ".claude" / "hooks" / "git-merge-guard.py"

# (description, command) — no repository dependence: the denial (or allowance) is
# decided without probing repository state, so these need no controlled repo.
ALLOW_CASES = [
    ("plain push", "git push"),
    ("push -u feature branch", "git push -u origin 823-verify-gc-t001"),
    ("commit", "git commit -m 'x'"),
    ("status then log", "git status && git log --oneline"),
    ("commit message that mentions merge", "git commit -m 'tidy git merge guard'"),
    ("lease-force to a feature branch", "git push --force-with-lease origin 823-verify-gc-t001"),
    ("lease-force to a feature/* branch containing 'dev'", "git push --force-with-lease origin feature/dev-rebase"),
    ("lease-force with =<ref> plus an explicit refspec", "git push --force-with-lease=origin/823 origin 823"),
    ("lease-force after a push option that takes a value", "git push -o ci.skip --force-with-lease origin 823"),
    ("gh pr view (not merge)", "gh pr view 123 --json number"),
    ("git -C with a non-blocked subcommand", "git -C /tmp status"),
    ("git -c key=value with a non-blocked subcommand", "git -c color.ui=true log --oneline -1"),
    ("gh --repo with a non-blocked subcommand", "gh --repo o/r pr view 1"),
]

# Blocked without any repository probe: PR merge, destructive reset, and force
# pushes are denied purely from the parsed command line, independent of the
# checked-out branch, so no controlled repo is needed to attribute the denial.
BLOCK_CASES = [
    ("gh pr merge", "gh pr merge 123 --squash"),
    ("gh --repo o/r pr merge", "gh --repo owner/repo pr merge 123 --squash"),
    ("gh -R o/r pr merge", "gh -R owner/repo pr merge 123"),
    ("no-space ; chain to gh pr merge", "git status;gh pr merge 123 --squash"),
    ("git reset --hard", "git reset --hard HEAD~1"),
    ("git reset --hard in a pipeline", "echo x | git reset --hard"),
    ("no-space pipe to reset --hard", "git status|git reset --hard"),
    ("plain --force", "git push --force origin 823"),
    ("plain -f", "git push -f origin 823"),
    ("plain --force alongside --force-with-lease", "git push --force --force-with-lease origin 823"),
    ("-f alongside --force-with-lease", "git push -f --force-with-lease origin 823"),
    ("lease-force with no explicit refspec", "git push --force-with-lease"),
    ("lease-force with only a remote", "git push --force-with-lease origin"),
    ("lease-force destination main", "git push --force-with-lease origin main"),
    ("lease-force destination HEAD:dev", "git push --force-with-lease origin HEAD:dev"),
    ("lease-force destination +main refspec", "git push --force-with-lease origin +main"),
    ("short-option cluster -fu", "git push -fu origin 823"),
    ("short-option cluster -uf", "git push -uf origin 823"),
    ("--force=value form", "git push --force=origin/main origin 823"),
    ("lease-force wildcard refspec", "git push --force-with-lease origin 'refs/heads/*:refs/heads/*'"),
    ("lease-force bare HEAD refspec", "git push --force-with-lease origin HEAD"),
    ("lease-force refs/heads/main", "git push --force-with-lease origin refs/heads/main"),
]


def _run(command, cwd=None):
    payload = json.dumps({"tool_input": {"command": command}})
    proc = subprocess.run(
        ["python3", str(HOOK)],
        input=payload,
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
        cwd=cwd,
    )
    return proc.returncode


def _run_raw(stdin_text):
    proc = subprocess.run(
        ["python3", str(HOOK)],
        input=stdin_text,
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    return proc.returncode


def _git(repo, *args):
    """Run git in `repo` with an isolated, deterministic environment."""
    env = {
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_SYSTEM": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_AUTHOR_NAME": "test",
        "GIT_AUTHOR_EMAIL": "test@example.com",
        "GIT_COMMITTER_NAME": "test",
        "GIT_COMMITTER_EMAIL": "test@example.com",
        "PATH": os.environ.get("PATH", ""),
        "HOME": os.environ.get("HOME", ""),
    }
    return subprocess.run(
        ["git", "-C", repo, *args],
        check=True,
        capture_output=True,
        text=True,
        env=env,
    )


class _RepoTestCase(unittest.TestCase):
    def _make_repo(self, checkout="823-feature", *, detached=False, with_origin_dev=True):
        repo = tempfile.mkdtemp(prefix="mergeguard-test-")
        self.addCleanup(shutil.rmtree, repo, ignore_errors=True)
        _git(repo, "init", "-q")
        _git(repo, "commit", "-q", "-m", "init", "--allow-empty")
        sha = _git(repo, "rev-parse", "HEAD").stdout.strip()
        for branch in ("main", "dev", "release/1.0", "823-feature"):
            _git(repo, "branch", "-f", branch, sha)
        if with_origin_dev:
            _git(repo, "update-ref", "refs/remotes/origin/dev", sha)
            _git(repo, "update-ref", "refs/remotes/origin/main", sha)
        if detached:
            _git(repo, "checkout", "-q", "--detach", sha)
        else:
            _git(repo, "checkout", "-q", checkout)
        return repo


class GitMergeGuardStringTest(unittest.TestCase):
    def test_allowed_commands_exit_zero(self):
        for desc, command in ALLOW_CASES:
            with self.subTest(case=desc):
                self.assertEqual(_run(command), 0, f"{desc!r} should be allowed: {command!r}")

    def test_blocked_commands_exit_two(self):
        for desc, command in BLOCK_CASES:
            with self.subTest(case=desc):
                self.assertEqual(_run(command), 2, f"{desc!r} should be blocked: {command!r}")

    def test_unparseable_command_with_dangerous_substring_is_blocked(self):
        # Unbalanced quote -> shlex.split raises -> conservative substring fallback,
        # decided lexically without any repository probe.
        self.assertEqual(_run("git merge 'oops"), 2)

    def test_unparseable_command_without_dangerous_substring_is_allowed(self):
        self.assertEqual(_run("echo 'oops"), 0)

    def test_malformed_json_input_is_blocked(self):
        # A candidate command whose hook payload is not valid JSON fails closed.
        self.assertEqual(_run_raw("{ not json"), 2)

    def test_non_string_command_is_blocked(self):
        payload = json.dumps({"tool_input": {"command": 123}})
        self.assertEqual(_run_raw(payload), 2)


class GitMergeGuardRepoTest(_RepoTestCase):
    # --- Positive: origin/dev into a non-protected branch. ---
    def test_merge_origin_dev_into_feature_is_allowed(self):
        repo = self._make_repo("823-feature")
        self.assertEqual(_run("git merge origin/dev", cwd=repo), 0)

    def test_merge_origin_dev_with_supported_flags_is_allowed(self):
        repo = self._make_repo("823-feature")
        self.assertEqual(_run("git merge --no-edit --no-ff origin/dev", cwd=repo), 0)
        self.assertEqual(_run("git merge origin/dev -m 'keep current with base'", cwd=repo), 0)
        self.assertEqual(_run("git merge --ff-only origin/dev", cwd=repo), 0)

    def test_merge_on_non_protected_release_branch_is_allowed(self):
        # release/1.0 is not in the hardcoded protected set, so it is a valid
        # (non-protected) merge destination.
        repo = self._make_repo("release/1.0")
        self.assertEqual(_run("git merge origin/dev", cwd=repo), 0)

    # --- Isolated "denied before repository probe" cases. ---
    # Each runs on a non-protected feature branch with a valid origin/dev, so a
    # regression in the specific check under test would fall through to a real
    # ALLOW (exit 0); exit 2 is therefore attributable to that check and is not
    # masked by the destination-protection check or an ambient checkout.
    def test_merge_denials_are_isolated_on_a_non_protected_branch(self):
        repo = self._make_repo("823-feature")
        git_bin = shutil.which("git") or "/usr/bin/git"
        cases = [
            # shell operators / chaining
            ("fetch chain", "git fetch && git merge origin/dev"),
            ("semicolon chain", "echo ok;git merge origin/dev"),
            ("&& no-space chain", "true&&git merge origin/dev"),
            ("subshell", "(git merge origin/dev)"),
            # command wrappers
            ("env wrapper", "env GIT_PAGER=cat git merge origin/dev"),
            ("sudo wrapper", "sudo git merge origin/dev"),
            # git global options (targeting this repo, so a regression would ALLOW)
            ("-C option", f"git -C {repo} merge origin/dev"),
            ("-c option", "git -c advice.detachedHead=false merge origin/dev"),
            ("--git-dir option", f"git --git-dir {repo}/.git merge origin/dev"),
            # shell expansion / substitution / globbing inside an operand
            ("command substitution", 'git merge -m "$(touch pwned)" origin/dev'),
            ("backtick substitution", 'git merge -m "`touch pwned`" origin/dev'),
            ("--message= substitution", 'git merge --message="$(id)" origin/dev'),
            ("variable expansion", 'git merge -m "${HOME}" origin/dev'),
            ("output redirection", "git merge origin/dev > out.txt"),
            ("glob star message", "git merge -m * origin/dev"),
            ("glob question source", "git merge origin/de?"),
            ("glob bracket message", "git merge -m '[wip]' origin/dev"),
            # explicit-path git (probe binary could differ from executed binary)
            ("relative-path git", "./git merge origin/dev"),
            ("absolute-path git", f"{git_bin} merge origin/dev"),
            # closed option set
            ("--squash", "git merge --squash origin/dev"),
            ("-s ours", "git merge -s ours origin/dev"),
            ("--strategy=ours", "git merge --strategy=ours origin/dev"),
            ("-X theirs", "git merge -X theirs origin/dev"),
            ("--allow-unrelated-histories", "git merge --allow-unrelated-histories origin/dev"),
            ("--no-verify", "git merge --no-verify origin/dev"),
            ("--continue", "git merge --continue"),
            ("--abort", "git merge --abort"),
            ("--quit", "git merge --quit"),
            ("option-shaped ref", "git merge --evil"),
            # source count
            ("no source", "git merge"),
            ("only options, no source", "git merge --no-ff"),
            ("multiple sources", "git merge origin/dev dev"),
        ]
        for desc, command in cases:
            with self.subTest(case=desc):
                self.assertEqual(_run(command, cwd=repo), 2, f"{desc!r} should be blocked: {command!r}")

    # --- Negative: protected destinations (hardcoded main + dev). ---
    def test_merge_on_protected_destination_is_blocked(self):
        for branch in ("main", "dev"):
            with self.subTest(branch=branch):
                repo = self._make_repo(branch)
                self.assertEqual(_run("git merge origin/dev", cwd=repo), 2)

    def test_merge_on_protected_destination_with_colliding_tag_is_blocked(self):
        # A tag colliding with the branch name would make `symbolic-ref --short`
        # return an abbreviation like `heads/main`; the full-ref resolution must
        # still recognize the protected destination.
        repo = self._make_repo("main")
        _git(repo, "tag", "main", "refs/heads/main")
        self.assertEqual(_run("git merge origin/dev", cwd=repo), 2)

    # --- Negative: ambiguous / undeterminable destination. ---
    def test_merge_in_detached_head_is_blocked(self):
        repo = self._make_repo(detached=True)
        self.assertEqual(_run("git merge origin/dev", cwd=repo), 2)

    # --- Negative: configured branch mergeOptions bypass the closed set. ---
    def test_merge_with_branch_merge_options_configured_is_blocked(self):
        repo = self._make_repo("823-feature")
        _git(repo, "config", "branch.823-feature.mergeOptions", "--squash")
        self.assertEqual(_run("git merge origin/dev", cwd=repo), 2)

    # --- Negative: source must be exactly refs/remotes/origin/dev. ---
    def test_merge_local_dev_source_is_blocked(self):
        repo = self._make_repo("823-feature")
        self.assertEqual(_run("git merge dev", cwd=repo), 2)

    def test_merge_origin_main_source_is_blocked(self):
        repo = self._make_repo("823-feature")
        self.assertEqual(_run("git merge origin/main", cwd=repo), 2)

    def test_merge_feature_branch_named_dev_is_blocked(self):
        repo = self._make_repo("823-feature")
        _git(repo, "branch", "-f", "feature/dev")
        self.assertEqual(_run("git merge feature/dev", cwd=repo), 2)

    def test_merge_same_oid_alias_is_blocked(self):
        repo = self._make_repo("823-feature")
        _git(repo, "branch", "-f", "mirror", "refs/remotes/origin/dev")
        self.assertEqual(_run("git merge mirror", cwd=repo), 2)

    def test_merge_tag_alias_is_blocked(self):
        repo = self._make_repo("823-feature")
        _git(repo, "tag", "v1", "refs/remotes/origin/dev")
        self.assertEqual(_run("git merge v1", cwd=repo), 2)

    def test_merge_raw_sha_source_is_blocked(self):
        repo = self._make_repo("823-feature")
        sha = _git(repo, "rev-parse", "HEAD").stdout.strip()
        self.assertEqual(_run(f"git merge {sha}", cwd=repo), 2)

    def test_merge_unresolvable_source_is_blocked(self):
        repo = self._make_repo("823-feature")
        self.assertEqual(_run("git merge does-not-exist-branch", cwd=repo), 2)

    def test_merge_missing_origin_dev_is_blocked(self):
        repo = self._make_repo("823-feature", with_origin_dev=False)
        self.assertEqual(_run("git merge origin/dev", cwd=repo), 2)


if __name__ == "__main__":
    unittest.main()
