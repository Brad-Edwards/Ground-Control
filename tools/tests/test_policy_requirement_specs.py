import tempfile
import unittest
from pathlib import Path

from tools.policy.requirement_specs import run_requirement_specs_frontmatter_check

VALID = """---
id: GC-X001
title: "Test requirement"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 3
created_at: 2026-01-01T00:00:00Z
updated_at: 2026-01-02T00:00:00Z
---

# GC-X001 — Test requirement

## Statement

A statement.
"""


class RequirementSpecsFrontmatterCheckTest(unittest.TestCase):
    def _write(self, root, folder, content):
        directory = Path(root) / folder
        directory.mkdir(parents=True)
        (directory / "requirement.md").write_text(content, encoding="utf-8")

    def test_valid_spec_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X001", VALID)
            self.assertEqual(run_requirement_specs_frontmatter_check(Path(tmp)), [])

    def test_missing_required_key_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X001", VALID.replace("priority: MUST\n", ""))
            violations = run_requirement_specs_frontmatter_check(Path(tmp))
            self.assertTrue(violations)
            self.assertIn("priority", violations[0].render())

    def test_invalid_status_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X001", VALID.replace("status: ACTIVE", "status: BOGUS"))
            violations = run_requirement_specs_frontmatter_check(Path(tmp))
            self.assertTrue(violations)
            self.assertIn("status", violations[0].render())

    def test_id_folder_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X999", VALID)  # frontmatter id is GC-X001
            violations = run_requirement_specs_frontmatter_check(Path(tmp))
            self.assertTrue(violations)
            self.assertIn("does not match folder", violations[0].render())

    def test_missing_frontmatter_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X002", "# no frontmatter here\n")
            self.assertTrue(run_requirement_specs_frontmatter_check(Path(tmp)))

    def test_invalid_type_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X001", VALID.replace("type: FUNCTIONAL", "type: BOGUS"))
            violations = run_requirement_specs_frontmatter_check(Path(tmp))
            self.assertTrue(violations)
            self.assertIn("type", violations[0].render())

    def test_invalid_priority_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X001", VALID.replace("priority: MUST", "priority: BOGUS"))
            violations = run_requirement_specs_frontmatter_check(Path(tmp))
            self.assertTrue(violations)
            self.assertIn("priority", violations[0].render())

    def test_non_integer_wave_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "GC-X001", VALID.replace("wave: 3", "wave: soon"))
            violations = run_requirement_specs_frontmatter_check(Path(tmp))
            self.assertTrue(violations)
            self.assertIn("wave", violations[0].render())

    def test_absent_directory_is_noop(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(run_requirement_specs_frontmatter_check(Path(tmp) / "nope"), [])


if __name__ == "__main__":
    unittest.main()
