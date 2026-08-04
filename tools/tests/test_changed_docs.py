"""Tests for tools/changed-docs.sh.

The /implement completion gate runs `make vale-lint` on the WORKING TREE before
the change is committed (Step 6 `verify`). The previous detector diffed only the
committed range (`$BASE_REF...HEAD`), so staged / unstaged / brand-new docs were
invisible and prose lint false-greened locally — CI was the first place a real
Vale error surfaced (the #1507 shakeout hit exactly this).

`changed-docs.sh` must therefore report the union of what CI will lint after the
commit: committed-vs-base, staged, unstaged edits to tracked files, and new
untracked (non-ignored) files — deduped, Markdown only, existing on disk.

Each case is exercised against a temporary real Git repository so the detection
is attributable to the script, not to ambient repo state.
"""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "tools" / "changed-docs.sh"


def _git(cwd, *args):
    subprocess.run(
        ["git", *args],
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
    )


def _write(cwd, rel, text="content\n"):
    path = Path(cwd) / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _rev_parse(cwd, ref="HEAD"):
    proc = subprocess.run(
        ["git", "rev-parse", ref],
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
    )
    return proc.stdout.strip()


def _changed(cwd, base_ref):
    proc = subprocess.run(
        ["bash", str(SCRIPT), base_ref],
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    return set(line for line in proc.stdout.splitlines() if line)


class ChangedDocsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = self._tmp.name
        _git(self.repo, "init", "-q")
        _git(self.repo, "config", "user.email", "t@example.com")
        _git(self.repo, "config", "user.name", "Test")
        _git(self.repo, "config", "commit.gpgsign", "false")
        # Base commit: one tracked doc plus a .gitignore that ignores build/.
        _write(self.repo, "base.md", "# base\n")
        _write(self.repo, ".gitignore", "ignored/\n")
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-qm", "base")
        self.base = _rev_parse(self.repo)

    def tearDown(self):
        self._tmp.cleanup()

    def test_unions_committed_staged_unstaged_and_untracked(self):
        # committed-vs-base
        _write(self.repo, "committed.md")
        _git(self.repo, "add", "committed.md")
        _git(self.repo, "commit", "-qm", "committed change")
        # staged, not committed
        _write(self.repo, "staged.md")
        _git(self.repo, "add", "staged.md")
        # unstaged edit to a tracked file
        _write(self.repo, "base.md", "# base edited\n")
        # new, untracked, not ignored
        _write(self.repo, "untracked.md")

        found = _changed(self.repo, self.base)
        self.assertEqual(
            found,
            {"committed.md", "staged.md", "base.md", "untracked.md"},
        )

    def test_excludes_non_markdown_deleted_and_ignored(self):
        # non-markdown staged change — excluded by the extension filter
        _write(self.repo, "script.py", "print(1)\n")
        _git(self.repo, "add", "script.py")
        # deleted tracked doc — excluded by --diff-filter=ACMR and the -f check
        _git(self.repo, "rm", "-q", "base.md")
        # ignored new doc — excluded by --exclude-standard
        _write(self.repo, "ignored/notes.md")

        self.assertEqual(_changed(self.repo, self.base), set())

    def test_committed_doc_removed_from_worktree_is_not_reported(self):
        # A doc added in a commit above base, then deleted from the working tree,
        # must not be handed to Vale (it no longer exists on disk).
        _write(self.repo, "gone.md")
        _git(self.repo, "add", "gone.md")
        _git(self.repo, "commit", "-qm", "add gone.md")
        os.remove(Path(self.repo) / "gone.md")

        self.assertNotIn("gone.md", _changed(self.repo, self.base))

    def test_no_changes_prints_nothing(self):
        self.assertEqual(_changed(self.repo, self.base), set())


if __name__ == "__main__":
    unittest.main()
