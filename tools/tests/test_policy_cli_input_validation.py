"""Tests for the agentic-workflow CLI input validators (issue #1505).

bin/policy is invoked inside an agentic workflow (Claude / Codex / CI), so a
git-ref or file-path CLI argument is data an agent could be manipulated into
supplying. `validate_git_ref` and `safe_cli_path` sanitize those values at the
boundary before they reach a git subprocess or a filesystem call
(pythonsecurity:S8705 / S8707).
"""

import os
import tempfile
import unittest
from pathlib import Path

from tools.policy.checks import REPO_ROOT, safe_cli_path, validate_git_ref


class ValidateGitRefTest(unittest.TestCase):
    def test_accepts_real_refs(self):
        for ref in ("HEAD", "origin/dev", "main", "HEAD~1", "HEAD^", "release/1.2.3", "a1b2c3d4"):
            self.assertEqual(validate_git_ref(ref), ref)

    def test_rejects_option_injection_and_metacharacters(self):
        # A leading '-' would be parsed by git as an option, not a ref; shell
        # metacharacters and whitespace are rejected defensively.
        for ref in ("--upload-pack=/tmp/x", "-x", "", "a b", "a;rm -rf /", "$(id)", "a\nb"):
            with self.assertRaises(ValueError):
                validate_git_ref(ref)


class SafeCliPathTest(unittest.TestCase):
    def test_accepts_path_inside_the_repo(self):
        target = REPO_ROOT / "README.md"
        resolved = safe_cli_path(str(target))
        self.assertEqual(resolved, Path(os.path.realpath(target)))

    def test_accepts_path_inside_the_system_temp_dir(self):
        with tempfile.NamedTemporaryFile(suffix=".json") as handle:
            self.assertEqual(safe_cli_path(handle.name), Path(os.path.realpath(handle.name)))

    def test_accepts_ci_runner_temp_path(self):
        with tempfile.TemporaryDirectory() as runner_temp:
            target = os.path.join(runner_temp, "event.json")
            Path(target).write_text("{}", encoding="utf-8")
            os.environ["RUNNER_TEMP"] = runner_temp
            try:
                self.assertEqual(safe_cli_path(target), Path(os.path.realpath(target)))
            finally:
                del os.environ["RUNNER_TEMP"]

    def test_rejects_path_outside_the_allowed_roots(self):
        # /etc/passwd is not under the repo, cwd, temp, or any CI root.
        with self.assertRaises(ValueError):
            safe_cli_path("/etc/passwd")

    def test_rejects_traversal_escape(self):
        with self.assertRaises(ValueError):
            safe_cli_path(str(REPO_ROOT / ".." / ".." / ".." / "etc" / "shadow"))


if __name__ == "__main__":
    unittest.main()
