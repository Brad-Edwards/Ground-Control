import unittest
from pathlib import Path

from tools.policy.workflow_contracts import run_doc_coverage_anchor_contract


class DocCoverageAnchorContractTest(unittest.TestCase):
    """ADR-054 surface anchors must keep naming real files (issue #1355)."""

    CATALOGUE = Path("mcp/ground-control/lib/doc-coverage.js")

    def test_repository_anchors_all_resolve(self):
        self.assertEqual(run_doc_coverage_anchor_contract(), [])

    def test_an_anchor_that_stopped_naming_a_file_is_reported(self):
        # The drift this exists for: the config_parser surface anchored on the module the parser
        # used to live in. After the parser moved, the surface matched a file that no longer held
        # it, matched nothing, and quietly stopped requiring documentation for the parser.
        original = self.CATALOGUE.read_text(encoding="utf-8")
        mutated = original.replace(
            '"mcp/ground-control/lib/ground-control-config.js"',
            '"mcp/ground-control/lib/relocated-elsewhere.js"',
        )
        self.assertNotEqual(mutated, original, "the anchor under test is no longer in the catalogue")
        self.CATALOGUE.write_text(mutated, encoding="utf-8")
        try:
            violations = run_doc_coverage_anchor_contract()
        finally:
            self.CATALOGUE.write_text(original, encoding="utf-8")
        self.assertEqual([v.code for v in violations], ["doc-coverage-anchor-dangling"])

    def test_a_missing_catalogue_fails_rather_than_passing_vacuously(self):
        violations = run_doc_coverage_anchor_contract(Path("/nonexistent-repo-root"))
        self.assertEqual([v.code for v in violations], ["doc-coverage-catalogue-missing"])

    def test_the_config_parser_surface_names_the_module_defining_the_parser(self):
        # A path anchor is only as good as its target. This pins the specific relationship the
        # surface exists to enforce, so moving the parser without moving the anchor fails here too.
        catalogue = self.CATALOGUE.read_text(encoding="utf-8")
        self.assertIn('"mcp/ground-control/lib/ground-control-config.js"', catalogue)
        parser = Path("mcp/ground-control/lib/ground-control-config.js").read_text(encoding="utf-8")
        self.assertIn("export function parseGroundControlYaml", parser)


if __name__ == "__main__":
    unittest.main()
