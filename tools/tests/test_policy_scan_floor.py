import tempfile
import unittest
from pathlib import Path

from tools.policy.checks import run_scan_floor_contract


class ScanFloorContractTest(unittest.TestCase):
    """A source-scanning test must prove its extraction found something (issue #1355).

    Four gates failed open during the #1355 split, each because its subject had moved and the
    check reported clean rather than reporting that it had found nothing to check. This closes
    the class: an extraction that matches nothing now fails instead of passing vacuously.
    """

    def _mirror(self, tmp: str, test_source: str) -> Path:
        root = Path(tmp)
        (root / "tools" / "tests").mkdir(parents=True)
        (root / "tools" / "tests" / "test_example.py").write_text(test_source, encoding="utf-8")
        return root

    UNGUARDED = '''
import re
from pathlib import Path


class ExampleTest:
    def test_scans_without_bounding(self):
        text = Path("some/source.js").read_text(encoding="utf-8")
        found = set(re.findall(r"x", text))
        self.assertEqual(found - {"x"}, set())
'''

    def test_fires_on_a_scan_with_no_floor(self):
        with tempfile.TemporaryDirectory() as tmp:
            violations = run_scan_floor_contract(self._mirror(tmp, self.UNGUARDED))

        # Without this the assertion above passes whenever the regex matches nothing, which is
        # exactly how a moved subject turns a gate green.
        self.assertEqual([v.code for v in violations], ["scan-without-floor"])
        self.assertIn("test_scans_without_bounding", violations[0].details[0])

    def test_accepts_a_length_floor(self):
        guarded = self.UNGUARDED.replace(
            "        self.assertEqual(found - {\"x\"}, set())",
            "        self.assertGreaterEqual(len(found), 1)\n        self.assertEqual(found - {\"x\"}, set())",
        )
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(run_scan_floor_contract(self._mirror(tmp, guarded)), [])

    def test_accepts_a_truthiness_floor_on_the_extracted_collection(self):
        """Any spelling that fails on an empty extraction counts.

        Demanding a `len()` floor specifically would push tests toward a shape their author did
        not mean, which is a worse outcome than the bug being closed.
        """
        guarded = self.UNGUARDED.replace(
            "        self.assertEqual(found - {\"x\"}, set())",
            "        self.assertTrue(found)\n        self.assertEqual(found - {\"x\"}, set())",
        )
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(run_scan_floor_contract(self._mirror(tmp, guarded)), [])

    def test_ignores_a_test_that_does_not_scan_source(self):
        # Reading a fixture it just wrote is not a scan: it fails loudly when absent.
        unrelated = '''
class ExampleTest:
    def test_reads_nothing(self):
        self.assertEqual(1, 1)
'''
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(run_scan_floor_contract(self._mirror(tmp, unrelated)), [])

    def test_fails_when_it_resolves_no_tests_at_all(self):
        """The check must not exempt itself from the rule it enforces.

        A scan that finds no test files has not proven anything, so reporting clean would be the
        same fail-open shape one level up.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "tools" / "tests").mkdir(parents=True)
            violations = run_scan_floor_contract(root)

        self.assertEqual([v.code for v in violations], ["scan-resolved-nothing"])

    def test_the_repo_itself_satisfies_the_contract(self):
        self.assertEqual(run_scan_floor_contract(), [])
