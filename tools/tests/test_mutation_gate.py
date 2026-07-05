from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from importlib import util
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "tools" / "mutation" / "run_boundary_mutation.py"
BACKEND_BOUNDARY_PATH = "backend/src/main/java/com/keplerops/groundcontrol/domain/derivation/service/BoundaryModelService.java"
BACKEND_TEST_PATH = "backend/src/test/java/com/keplerops/groundcontrol/unit/domain/derivation/BoundaryModelServiceTest.java"

spec = util.spec_from_file_location("mutation_runner", RUNNER)
assert spec is not None
mutation_runner = util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = mutation_runner
spec.loader.exec_module(mutation_runner)


class MutationGateRunnerTest(unittest.TestCase):
    def test_interior_only_change_is_successful_noop(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)

            result = run_gate(root, registry, "--changed-file", "docs/README.md", "--dry-run")

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("no changed mutation-contract boundaries", result.stdout)
            self.assertNotIn("dry-run boundary", result.stdout)

    def test_changed_backend_boundary_plans_pitest_with_registry_threshold(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)

            result = run_gate(
                root,
                registry,
                "--changed-file",
                BACKEND_BOUNDARY_PATH,
                "--dry-run",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("dry-run boundary backend-boundary-model", result.stdout)
            self.assertIn("-PmutationThreshold=61", result.stdout)
            self.assertIn("-PmutationTargetClasses=com.keplerops.groundcontrol.domain.derivation.service.BoundaryModelService", result.stdout)
            self.assertNotIn("frontend-oracle-battery", result.stdout)

    def test_changed_frontend_boundary_plans_stryker_with_registry_threshold(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)

            result = run_gate(
                root,
                registry,
                "--changed-file",
                "frontend/src/test/oracle-battery.ts",
                "--dry-run",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("dry-run boundary frontend-oracle-battery", result.stdout)
            self.assertIn("STRYKER_THRESHOLD=80", result.stdout)
            self.assertIn("STRYKER_MUTATE=src/test/oracle-battery.ts", result.stdout)
            self.assertNotIn("backend-boundary-model", result.stdout)

    def test_registry_change_runs_every_enabled_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)

            result = run_gate(
                root,
                registry,
                "--changed-file",
                "architecture/registry/mutation-boundaries.json",
                "--dry-run",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("dry-run boundary backend-boundary-model", result.stdout)
            self.assertIn("dry-run boundary frontend-oracle-battery", result.stdout)

    def test_registry_without_baseline_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)
            data = json.loads(registry.read_text(encoding="utf-8"))
            del data["boundaries"][0]["mutation"]["baseline"]
            registry.write_text(json.dumps(data), encoding="utf-8")

            result = run_gate(root, registry, "--changed-file", "backend/src/main/java/Foo.java", "--dry-run")

            self.assertEqual(result.returncode, 2)
            self.assertIn("baseline", result.stderr)

    def test_registry_validation_rejects_independent_config_errors(self) -> None:
        def duplicate_id(data: dict) -> None:
            data["boundaries"][1]["id"] = "backend-boundary-model"

        def invalid_tool(data: dict) -> None:
            data["boundaries"][0]["mutation"]["tool"] = "mutmut"

        def escaped_selector(data: dict) -> None:
            data["boundaries"][0]["paths"] = ["../outside.java"]

        def invalid_threshold(data: dict) -> None:
            data["boundaries"][0]["mutation"]["threshold"] = 101

        def missing_pitest_targets(data: dict) -> None:
            del data["boundaries"][0]["mutation"]["pitest"]["target_tests"]

        cases = [
            ("duplicate-id", duplicate_id, "duplicates"),
            ("invalid-tool", invalid_tool, "mutation.tool"),
            ("escaped-selector", escaped_selector, "selector must stay inside the repo"),
            ("invalid-threshold", invalid_threshold, "threshold"),
            ("missing-pitest-targets", missing_pitest_targets, "missing target_tests"),
        ]

        for name, mutate, expected in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                registry = write_registry(root)
                data = json.loads(registry.read_text(encoding="utf-8"))
                mutate(data)
                registry.write_text(json.dumps(data), encoding="utf-8")

                result = run_gate(root, registry, "--changed-file", BACKEND_BOUNDARY_PATH, "--dry-run")

                self.assertEqual(result.returncode, 2)
                self.assertIn(expected, result.stderr)

    def test_deleted_registered_boundary_path_selects_boundary_from_git_diff(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)
            write_file(root, BACKEND_BOUNDARY_PATH, "class BoundaryModelService {}\n")
            write_file(root, BACKEND_TEST_PATH, "class BoundaryModelServiceTest {}\n")
            init_git_repo(root)
            (root / BACKEND_TEST_PATH).unlink()
            commit_all(root, "delete boundary test")

            result = run_gate(root, registry, "--base", "HEAD~1", "--dry-run")

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("dry-run boundary backend-boundary-model", result.stdout)

    def test_renamed_away_registered_boundary_path_selects_boundary_from_old_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = write_registry(root)
            write_file(root, BACKEND_BOUNDARY_PATH, "class BoundaryModelService {}\n")
            write_file(root, BACKEND_TEST_PATH, "class BoundaryModelServiceTest {}\n")
            init_git_repo(root)
            renamed = "backend/src/test/java/com/keplerops/groundcontrol/unit/other/RenamedBoundaryModelServiceTest.java"
            (root / renamed).parent.mkdir(parents=True, exist_ok=True)
            os.replace(root / BACKEND_TEST_PATH, root / renamed)
            commit_all(root, "rename boundary test away")

            result = run_gate(root, registry, "--base", "HEAD~1", "--dry-run")

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("dry-run boundary backend-boundary-model", result.stdout)

    def test_name_status_parser_preserves_rename_old_and_new_paths(self) -> None:
        output = f"R100\t{BACKEND_TEST_PATH}\tfrontend/src/test/RenamedBoundaryModelServiceTest.ts\n"

        self.assertEqual(
            mutation_runner.parse_changed_paths(output),
            [BACKEND_TEST_PATH, "frontend/src/test/RenamedBoundaryModelServiceTest.ts"],
        )


def run_gate(root: Path, registry: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(RUNNER),
            "--repo-root",
            str(root),
            "--registry",
            str(registry),
            *args,
        ],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )


def write_registry(root: Path) -> Path:
    registry = root / "architecture" / "registry" / "mutation-boundaries.json"
    registry.parent.mkdir(parents=True, exist_ok=True)
    registry.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "boundaries": [
                    {
                        "id": "backend-boundary-model",
                        "name": "Backend boundary model",
                        "lock_level": "guarded",
                        "paths": [
                            BACKEND_BOUNDARY_PATH,
                            BACKEND_TEST_PATH,
                        ],
                        "mutation": {
                            "enabled": True,
                            "tool": "pitest",
                            "threshold": 61,
                            "time_budget_minutes": 15,
                            "baseline": {
                                "score": 61.0,
                                "killed": 19,
                                "survived": 12,
                                "total": 31,
                                "measured_at": "2026-07-04",
                                "tool_version": "pitest 1.17.0",
                            },
                            "pitest": {
                                "target_classes": [
                                    "com.keplerops.groundcontrol.domain.derivation.service.BoundaryModelService",
                                ],
                                "target_tests": [
                                    "com.keplerops.groundcontrol.unit.domain.derivation.BoundaryModelServiceTest",
                                ],
                            },
                        },
                    },
                    {
                        "id": "frontend-oracle-battery",
                        "name": "Frontend oracle battery scaffold",
                        "lock_level": "guarded",
                        "paths": [
                            "frontend/src/test/oracle-battery.ts",
                            "frontend/src/test/oracle-battery.test.ts",
                        ],
                        "mutation": {
                            "enabled": True,
                            "tool": "stryker",
                            "threshold": 80,
                            "time_budget_minutes": 10,
                            "baseline": {
                                "score": 80.0,
                                "killed": 8,
                                "survived": 2,
                                "total": 10,
                                "measured_at": "2026-07-04",
                                "tool_version": "stryker-js",
                            },
                            "stryker": {
                                "mutate": ["src/test/oracle-battery.ts"],
                                "test_files": ["src/test/oracle-battery.test.ts"],
                            },
                        },
                    },
                ],
            }
        ),
        encoding="utf-8",
    )
    return registry


def write_file(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def init_git_repo(root: Path) -> None:
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "Test User"], cwd=root, check=True)
    commit_all(root, "initial")


def commit_all(root: Path, message: str) -> None:
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    subprocess.run(["git", "commit", "-q", "-m", message], cwd=root, check=True)


if __name__ == "__main__":
    unittest.main()
