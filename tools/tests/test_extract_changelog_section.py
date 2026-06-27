"""Tests for tools/release/extract_changelog_section.py.

The extractor pulls the notes for a single version out of the already-collated
``CHANGELOG.md`` so the CI ``release`` job can publish them as the GitHub Release
body (issue #1224, ADR-063 §4 step 7). The load-bearing behaviors are:

- it returns exactly the section for the requested version,
- it stops at the next ``## [`` release heading,
- it matches the bracketed version exactly (``1.2`` must not match ``1.20``),
- it fails loudly when the section is missing, so CI never publishes an empty
  or hand-copied release.
"""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "tools" / "release" / "extract_changelog_section.py"

_spec = importlib.util.spec_from_file_location("extract_changelog_section", SCRIPT)
extractor = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(extractor)


SAMPLE = """\
# Changelog

Intro prose that must never leak into a release body.

<!-- towncrier release notes start -->

## [1.20.0] - 2026-06-26

### Added

- A twentieth-minor feature.

## [1.2.0] - 2026-06-01

### Fixed

- A patch in the 1.2 line.

### Changed

- Something else.

## [1.1.0] - 2026-05-01

### Added

- Older feature that must not be included.
"""


class ExtractSectionTests(unittest.TestCase):
    def test_extracts_requested_section_only(self):
        section = extractor.extract_section(SAMPLE, "1.2.0")
        self.assertIn("A patch in the 1.2 line.", section)
        self.assertIn("Something else.", section)
        # Must not bleed into adjacent releases.
        self.assertNotIn("twentieth-minor", section)
        self.assertNotIn("Older feature", section)

    def test_stops_at_next_release_heading(self):
        section = extractor.extract_section(SAMPLE, "1.2.0")
        self.assertNotIn("## [1.1.0]", section)
        self.assertNotIn("## [1.20.0]", section)

    def test_does_not_include_its_own_heading_or_intro(self):
        section = extractor.extract_section(SAMPLE, "1.2.0")
        self.assertNotIn("## [1.2.0]", section)
        self.assertNotIn("Intro prose", section)

    def test_exact_version_match_not_prefix(self):
        # Requesting 1.2 must NOT match the 1.20.0 or 1.2.0 headings.
        with self.assertRaises(KeyError):
            extractor.extract_section(SAMPLE, "1.2")

    def test_top_most_section_extracts(self):
        section = extractor.extract_section(SAMPLE, "1.20.0")
        self.assertIn("twentieth-minor feature", section)
        self.assertNotIn("A patch in the 1.2 line.", section)

    def test_strips_surrounding_blank_lines(self):
        section = extractor.extract_section(SAMPLE, "1.20.0")
        self.assertFalse(section.startswith("\n"))
        self.assertFalse(section.endswith("\n"))
        self.assertTrue(section.strip())

    def test_missing_version_raises(self):
        with self.assertRaises(KeyError):
            extractor.extract_section(SAMPLE, "9.9.9")

    def test_prerelease_heading_supported(self):
        text = SAMPLE + "\n## [2.0.0-rc.1] - 2026-07-01\n\n### Added\n\n- RC line.\n"
        section = extractor.extract_section(text, "2.0.0-rc.1")
        self.assertIn("RC line.", section)


class CliTests(unittest.TestCase):
    def _run(self, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            capture_output=True,
            text=True,
        )

    def test_cli_writes_section_to_stdout(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(SAMPLE)
            changelog = fh.name
        proc = self._run("--version", "1.2.0", "--changelog", changelog)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("A patch in the 1.2 line.", proc.stdout)
        self.assertNotIn("twentieth-minor", proc.stdout)

    def test_cli_writes_to_output_file(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(SAMPLE)
            changelog = fh.name
        with tempfile.NamedTemporaryFile("r", suffix=".md", delete=False) as out:
            out_path = out.name
        proc = self._run(
            "--version", "1.20.0", "--changelog", changelog, "--output", out_path
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("twentieth-minor feature", Path(out_path).read_text())

    def test_cli_missing_version_exits_nonzero(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(SAMPLE)
            changelog = fh.name
        proc = self._run("--version", "9.9.9", "--changelog", changelog)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("9.9.9", proc.stderr)

    def test_cli_strips_leading_v_is_callers_job(self):
        # The script takes a bare semver; the workflow strips the leading `v`.
        # Passing `v1.2.0` must therefore NOT match `## [1.2.0]`.
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(SAMPLE)
            changelog = fh.name
        proc = self._run("--version", "v1.2.0", "--changelog", changelog)
        self.assertNotEqual(proc.returncode, 0)


if __name__ == "__main__":
    unittest.main()
