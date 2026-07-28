"""Tests for the file-size limit gate (issue #1467).

The gate exists because a limit nothing enforces is not a limit. These cover the
two directions that make the grandfather list shrink-only, plus the exclusions,
because a size gate that quietly ignores files is the same silently-green gate
the issue was opened over.
"""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.policy.file_size import (
    GRANDFATHER_PATH,
    MAX_LINES,
    _is_source,
    load_grandfather,
    run_file_size_limit_check,
)


def write(root: Path, rel: str, lines: int) -> None:
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(f"// line {i}" for i in range(lines)) + "\n", encoding="utf-8")


def make_repo(tmp: str) -> Path:
    root = Path(tmp)
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "t@example.com"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
    return root


def track(root: Path) -> None:
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)


def set_grandfather(root: Path, entries: dict[str, str]) -> None:
    target = root / GRANDFATHER_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"entries": entries}), encoding="utf-8")


class FileSizeLimitTest(unittest.TestCase):
    def test_flags_an_unlisted_oversized_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/big.js", MAX_LINES + 1)
            track(root)
            codes = [v.code for v in run_file_size_limit_check(root)]
            self.assertIn("file-size-limit", codes)

    def test_accepts_a_file_exactly_at_the_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/exact.js", MAX_LINES)
            track(root)
            self.assertEqual([], run_file_size_limit_check(root))

    def test_a_listed_oversized_file_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/big.js", MAX_LINES + 50)
            set_grandfather(root, {"src/big.js": "being decomposed on another branch"})
            track(root)
            self.assertEqual([], run_file_size_limit_check(root))

    def test_a_listed_file_that_shrank_is_a_violation(self):
        """The list can only shrink; a stale entry must not be parkable forever."""
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/small.js", 10)
            set_grandfather(root, {"src/small.js": "stale"})
            track(root)
            codes = [v.code for v in run_file_size_limit_check(root)]
            self.assertIn("file-size-grandfather-stale", codes)

    def test_a_listed_file_that_was_deleted_is_a_violation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/other.js", 10)
            set_grandfather(root, {"src/gone.js": "stale"})
            track(root)
            codes = [v.code for v in run_file_size_limit_check(root)]
            self.assertIn("file-size-grandfather-stale", codes)

    def test_an_entry_without_a_reason_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/big.js", MAX_LINES + 1)
            set_grandfather(root, {"src/big.js": "  "})
            track(root)
            codes = [v.code for v in run_file_size_limit_check(root)]
            self.assertIn("file-size-grandfather-unreadable", codes)
            self.assertIn("file-size-limit", codes)

    def test_malformed_grandfather_file_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            (root / GRANDFATHER_PATH).parent.mkdir(parents=True, exist_ok=True)
            (root / GRANDFATHER_PATH).write_text("{not json", encoding="utf-8")
            track(root)
            entries, violations = load_grandfather(root)
            self.assertEqual({}, entries)
            self.assertEqual(["file-size-grandfather-unreadable"], [v.code for v in violations])

    def test_untracked_files_are_ignored(self):
        """Build output and node_modules are not this repo's size to answer for."""
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "src/kept.js", 10)
            track(root)
            write(root, "src/untracked.js", MAX_LINES + 1)
            self.assertEqual([], run_file_size_limit_check(root))

    def test_generated_output_is_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "contracts/gen/typescript/api.ts", MAX_LINES + 500)
            track(root)
            self.assertEqual([], run_file_size_limit_check(root))

    def test_non_source_files_are_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            write(root, "docs/long.md", MAX_LINES + 100)
            write(root, "backend/src/main/resources/db/migration/V1__big.sql", MAX_LINES + 100)
            track(root)
            self.assertEqual([], run_file_size_limit_check(root))

    def test_source_classification(self):
        self.assertTrue(_is_source("backend/src/main/java/A.java"))
        self.assertTrue(_is_source("frontend/src/pages/a.tsx"))
        self.assertTrue(_is_source("tools/policy/checks.py"))
        self.assertFalse(_is_source("contracts/gen/typescript/api.ts"))
        self.assertFalse(_is_source("README.md"))


class RepositoryGrandfatherTest(unittest.TestCase):
    def test_repository_list_is_accurate(self):
        """Guards the gate against the repo it actually protects."""
        self.assertEqual([], run_file_size_limit_check())

    def test_every_entry_states_a_reason(self):
        entries, violations = load_grandfather()
        self.assertEqual([], violations)
        for path, reason in entries.items():
            self.assertGreater(len(reason), 40, f"{path} needs a real reason, not a label")


if __name__ == "__main__":
    unittest.main()
