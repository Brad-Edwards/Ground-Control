import tempfile
import unittest
from pathlib import Path

from tools.policy.checks import (
    BACKEND_METHODOLOGY_CATALOG_PATH,
    REPO_ROOT,
    SKILL_METHODOLOGY_CATALOG_PATH,
    run_methodology_catalog_drift,
)

SKILL_CATALOG = """\
methods:
  - key: scoping
    label: Scoping review
    primary_sources:
      - zotero_key: AAA111
        title: "First"
      - zotero_key: BBB222
        title: "Second"
  - key: systematic
    label: Systematic review
    primary_sources:
      - zotero_key: CCC333
        title: "Third"
"""

BACKEND_CATALOG = """\
catalog_version: "1"
methods:
  - key: scoping
    label: Scoping review
    version: "1"
    required_sources:
      - ref: AAA111
        title: "First"
      - ref: BBB222
        title: "Second"
  - key: systematic
    label: Systematic review
    version: "1"
    required_sources:
      - ref: CCC333
        title: "Third"
"""


def _write_catalogs(root: Path, skill: str, backend: str) -> None:
    skill_path = root / SKILL_METHODOLOGY_CATALOG_PATH
    backend_path = root / BACKEND_METHODOLOGY_CATALOG_PATH
    skill_path.parent.mkdir(parents=True, exist_ok=True)
    backend_path.parent.mkdir(parents=True, exist_ok=True)
    skill_path.write_text(skill, encoding="utf-8")
    backend_path.write_text(backend, encoding="utf-8")


class MethodologyCatalogDriftTest(unittest.TestCase):
    def test_real_repo_catalogs_are_in_sync(self):
        """The shipped skill + backend catalogs must already agree (ADR-077)."""
        self.assertEqual(run_methodology_catalog_drift(REPO_ROOT), [])

    def test_in_sync_fixture_passes(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            _write_catalogs(root, SKILL_CATALOG, BACKEND_CATALOG)
            self.assertEqual(run_methodology_catalog_drift(root), [])

    def test_method_key_drift_flagged(self):
        backend = BACKEND_CATALOG.replace("key: systematic", "key: mapping")
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            _write_catalogs(root, SKILL_CATALOG, backend)
            violations = run_methodology_catalog_drift(root)
            self.assertEqual([v.code for v in violations], ["methodology-catalog-drift"])
            joined = "\n".join(violations[0].details)
            self.assertIn("systematic", joined)
            self.assertIn("mapping", joined)

    def test_source_identifier_drift_flagged(self):
        backend = BACKEND_CATALOG.replace("ref: CCC333", "ref: ZZZ999")
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            _write_catalogs(root, SKILL_CATALOG, backend)
            violations = run_methodology_catalog_drift(root)
            self.assertEqual([v.code for v in violations], ["methodology-catalog-drift"])
            joined = "\n".join(violations[0].details)
            self.assertIn("systematic", joined)
            self.assertIn("CCC333", joined)
            self.assertIn("ZZZ999", joined)

    def test_missing_backend_catalog_flagged(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            skill_path = root / SKILL_METHODOLOGY_CATALOG_PATH
            skill_path.parent.mkdir(parents=True, exist_ok=True)
            skill_path.write_text(SKILL_CATALOG, encoding="utf-8")
            violations = run_methodology_catalog_drift(root)
            self.assertEqual([v.code for v in violations], ["methodology-catalog-drift"])
            self.assertTrue(
                any("missing catalog file" in d for d in violations[0].details)
            )


if __name__ == "__main__":
    unittest.main()
