import tempfile
import unittest
from pathlib import Path

from tools.policy.repo_map import (
    DRIFT_CODE,
    LINK_CODE,
    SECTION_CODE,
    run_repository_map_freshness_check,
)

CLEAN_README = """# Test

## Repository map

| Path | What lives here |
|------|-----------------|
| `architecture/` | Decisions, see [ADRs](architecture/adrs/). |
| `mcp/` | The server. |

## Something else

Not part of the map: `unlisted/` should be ignored here.
"""


def _write_readme(root: Path, body: str) -> Path:
    readme = root / "README.md"
    readme.write_text(body, encoding="utf-8")
    return readme


class RepositoryMapFreshnessCheckTest(unittest.TestCase):
    def test_clean_injected_map_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "architecture" / "adrs").mkdir(parents=True)  # link target exists
            readme = _write_readme(root, CLEAN_README)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"architecture", "mcp"},
                excluded={},
            )
            self.assertEqual(violations, [])

    def test_missing_directory_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "architecture" / "adrs").mkdir(parents=True)
            readme = _write_readme(root, CLEAN_README)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"architecture", "mcp", "tools"},
                excluded={},
            )
            rendered = "\n".join(v.render() for v in violations)
            self.assertTrue(violations)
            self.assertEqual(violations[0].code, DRIFT_CODE)
            self.assertIn("tools/", rendered)

    def test_phantom_directory_fails(self):
        phantom = CLEAN_README.replace("| `mcp/` | The server. |", "| `ghost/` | Nope. |")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "architecture" / "adrs").mkdir(parents=True)
            readme = _write_readme(root, phantom)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"architecture"},
                excluded={},
            )
            rendered = "\n".join(v.render() for v in violations)
            self.assertTrue(violations)
            self.assertIn("ghost/", rendered)

    def test_excluded_directory_not_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "architecture" / "adrs").mkdir(parents=True)
            readme = _write_readme(root, CLEAN_README)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"architecture", "mcp", ".vale"},
                excluded={".vale": "prose-lint config, not a source surface"},
            )
            self.assertEqual(violations, [])

    def test_stale_exclusion_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "architecture" / "adrs").mkdir(parents=True)
            readme = _write_readme(root, CLEAN_README)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"architecture", "mcp"},
                excluded={".gone": "removed long ago"},
            )
            rendered = "\n".join(v.render() for v in violations)
            self.assertTrue(violations)
            self.assertIn(".gone", rendered)

    def test_broken_link_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # deliberately do NOT create architecture/adrs -> link is broken
            readme = _write_readme(root, CLEAN_README)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"architecture", "mcp"},
                excluded={},
            )
            rendered = "\n".join(v.render() for v in violations)
            self.assertTrue(any(v.code == LINK_CODE for v in violations))
            self.assertIn("architecture/adrs/", rendered)

    def test_external_and_anchor_links_are_ignored(self):
        body = (
            "# Test\n\n## Repository map\n\n"
            "| `mcp/` | See [site](https://example.com) and [top](#test). |\n\n## End\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            readme = _write_readme(root, body)
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"mcp"},
                excluded={},
            )
            self.assertEqual(violations, [])

    def test_missing_section_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            readme = _write_readme(root, "# Test\n\nNo map here.\n")
            violations = run_repository_map_freshness_check(
                readme_path=readme,
                repo_root=root,
                top_level_dirs={"mcp"},
                excluded={},
            )
            self.assertTrue(violations)
            self.assertEqual(violations[0].code, SECTION_CODE)

    def test_missing_readme_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            violations = run_repository_map_freshness_check(
                readme_path=root / "nope.md",
                repo_root=root,
                top_level_dirs={"mcp"},
                excluded={},
            )
            self.assertTrue(violations)
            self.assertEqual(violations[0].code, SECTION_CODE)

    def test_real_repository_map_is_clean(self):
        # Drift canary: the committed README map must match the real tracked
        # top-level directories and every link in it must resolve.
        self.assertEqual(run_repository_map_freshness_check(), [])


if __name__ == "__main__":
    unittest.main()
