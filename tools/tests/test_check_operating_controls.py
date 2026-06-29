"""Tests for the source-gate operating-controls durable-record guard.

The guard (`tools/policy/check_operating_controls.py`) is the structural gate
behind the `compliance-requirement-traceability` operating control whose durable
home is Ground Control (penumbra-cell#34 / issue #1198). It fails when the
operating-controls docs are missing or have drifted away from the control
objective and its four named sources of truth, so the durable record cannot
silently rot.
"""

import tempfile
import unittest
from pathlib import Path

from tools.policy.check_operating_controls import (
    CONTROL_ID,
    DOC_DIR,
    REQUIRED_OBJECTIVE_TERMS,
    REQUIRED_SOURCES_OF_TRUTH,
    TIMING_GATE,
    main,
    validate_operating_controls,
)


def _valid_source(omit_term: str | None = None) -> str:
    sources = "\n".join(f"- {phrase}: mapped." for phrase in REQUIRED_SOURCES_OF_TRUTH)
    # Comma-join so the objective line does not accidentally form a multi-word
    # source-of-truth phrase (e.g. "test evidence") that the per-phrase removal
    # test relies on being absent everywhere except its own bullet. `omit_term`
    # drops one objective term so the objective-terms guard can be exercised.
    objective = ", ".join(t for t in REQUIRED_OBJECTIVE_TERMS if t != omit_term)
    return (
        "# Source-Gate Operating Controls\n\n"
        f"Gate: {TIMING_GATE}.\n\n"
        f"Control `{CONTROL_ID}` objective: product behavior must stay "
        f"{objective}.\n\n"
        "## Sources of Truth\n\n"
        f"{sources}\n"
    )


def _valid_readme() -> str:
    return (
        "# Autarchy Operating Control Routing\n\n"
        "Ground Control is the durable home for source-gate controls.\n\n"
        "- [Before source](source.md)\n"
    )


def _write(dir_path: Path, readme: str | None, source: str | None) -> Path:
    if readme is not None:
        (dir_path / "README.md").write_text(readme, encoding="utf-8")
    if source is not None:
        (dir_path / "source.md").write_text(source, encoding="utf-8")
    return dir_path


class ValidateOperatingControlsTest(unittest.TestCase):
    def test_valid_docs_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            doc_dir = _write(Path(tmp), _valid_readme(), _valid_source())
            self.assertEqual(validate_operating_controls(doc_dir), [])

    def test_missing_readme_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            doc_dir = _write(Path(tmp), None, _valid_source())
            failures = validate_operating_controls(doc_dir)
            self.assertTrue(any("README.md" in f for f in failures))

    def test_missing_source_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            doc_dir = _write(Path(tmp), _valid_readme(), None)
            failures = validate_operating_controls(doc_dir)
            self.assertTrue(any("source.md" in f for f in failures))

    def test_readme_without_source_link_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            readme = "# Routing\n\nNo link here.\n"
            doc_dir = _write(Path(tmp), readme, _valid_source())
            failures = validate_operating_controls(doc_dir)
            self.assertTrue(any("source.md" in f and "link" in f.lower() for f in failures))

    def test_source_missing_control_id_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = _valid_source().replace(CONTROL_ID, "some-other-control")
            doc_dir = _write(Path(tmp), _valid_readme(), source)
            failures = validate_operating_controls(doc_dir)
            self.assertTrue(any(CONTROL_ID in f for f in failures))

    def test_source_missing_timing_gate_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = _valid_source().replace(TIMING_GATE, "after-source")
            doc_dir = _write(Path(tmp), _valid_readme(), source)
            failures = validate_operating_controls(doc_dir)
            self.assertTrue(any(TIMING_GATE in f for f in failures))

    def test_source_missing_each_objective_term_fails(self):
        for term in REQUIRED_OBJECTIVE_TERMS:
            with self.subTest(term=term):
                with tempfile.TemporaryDirectory() as tmp:
                    doc_dir = _write(Path(tmp), _valid_readme(), _valid_source(omit_term=term))
                    failures = validate_operating_controls(doc_dir)
                    self.assertTrue(
                        any(term in f for f in failures),
                        msg=f"expected a failure naming missing objective term: {term}",
                    )

    def test_source_missing_each_source_of_truth_fails(self):
        for phrase in REQUIRED_SOURCES_OF_TRUTH:
            with tempfile.TemporaryDirectory() as tmp:
                source = _valid_source().replace(f"- {phrase}: mapped.", "- removed.")
                doc_dir = _write(Path(tmp), _valid_readme(), source)
                failures = validate_operating_controls(doc_dir)
                self.assertTrue(
                    any(phrase in f for f in failures),
                    msg=f"expected a failure naming missing source of truth: {phrase}",
                )

    def test_real_repo_docs_pass(self):
        """The committed operating-controls docs must satisfy the guard."""
        self.assertEqual(validate_operating_controls(DOC_DIR), [])

    def test_main_returns_zero_for_real_repo_docs(self):
        self.assertEqual(main(), 0)


if __name__ == "__main__":
    unittest.main()
