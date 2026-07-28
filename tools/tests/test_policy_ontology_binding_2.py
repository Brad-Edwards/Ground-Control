import copy
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.policy_fixtures import PolicyChecksFixture
from pathlib import Path
from unittest import mock
from unittest.mock import patch

from tools.policy.checks import (
    DEFERRAL_CASES_PATH,
    ENUM_CONTRACT_INVENTORY,
    FRONTEND_API_TYPES_PATH,
    MCP_LIB_PATH,
    REPO_ROOT,
    Violation,
    _is_release_pr,
    _jsonpath_keys,
    _resolve_pr_refs,
    check_pr_body,
    classify_deferral_language,
    extract_step_section,
    main,
    parse_args,
    parse_const_string_array,
    parse_java_enum_constants,
    parse_ts_union_literals,
    read_changed_files,
    run_adr_guard,
    _trigger_is_in_scope,
    run_ci_strictness_contract,
    run_authz_matrix_sync_check,
    run_contract_invariant_enforcement_check,
    run_contract_surface_check,
    run_controller_contracts,
    run_deploy_artifact_consistency,
    run_deploy_compose_credential_passthrough,
    run_documentation_coverage_check,
    run_enum_contract_check,
    run_ghcr_namespace_drift,
    run_repo_identity_drift,
    run_measurement_catalogue_check,
    run_migration_policy,
    run_no_deferral_disposition_check,
    run_ontology_binding_check,
    run_ontology_crosswalk_check,
    run_pr_body_check,
    run_test_quality_decision_record_contract,
    run_traceability_reconciliation_gate_contract,
    run_version_mirror_consistency_check,
    run_workflow_routing_contract,
    run_implement_execution_contract,
)

if __name__ == "__main__":
    unittest.main()

class OntologyBinding2ChecksTest(PolicyChecksFixture):
    def test_ontology_binding_check_covers_source_surface_and_binding_validation_branches(self):
        cases = (
            ("missing-source-root", "ontology-source-root-missing"),
            ("unparseable-enum", "ontology-source-parse-error"),
            ("duplicate-surface", "ontology-surface-duplicate"),
            ("invalid-surface", "ontology-surface-invalid"),
            ("surface-kind-mismatch", "ontology-surface-kind-mismatch"),
            ("surface-path-mismatch", "ontology-surface-path-mismatch"),
            ("missing-surface", "ontology-surface-missing"),
            ("stale-surface", "ontology-surface-stale"),
            ("invalid-binding", "ontology-binding-invalid"),
            ("binding-kind-mismatch", "ontology-binding-kind-mismatch"),
            ("invalid-enum-source-shape", "ontology-edge-enum-source-invalid"),
            ("stale-enum-source", "ontology-edge-enum-source-stale"),
        )
        for case, expected_code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp_dir:
                root = Path(tmp_dir)
                self._write_minimal_ontology_fixture(root)
                bindings_path, bindings = self._read_ontology_fixture_json(
                    root, "gc-artifact-bindings-v1.json"
                )
                java_root = root / "backend" / "src" / "main" / "java"
                graph_enum = java_root / "example" / "GraphEntityType.java"
                if case == "missing-source-root":
                    shutil.rmtree(java_root)
                elif case == "unparseable-enum":
                    graph_enum.write_text(
                        "package example; public enum GraphEntityType {}\n", encoding="utf-8"
                    )
                elif case == "duplicate-surface":
                    bindings["surfaces"].append(dict(bindings["surfaces"][0]))
                elif case == "invalid-surface":
                    bindings["surfaces"][0]["bindings"] = None
                elif case == "surface-kind-mismatch":
                    bindings["surfaces"][0]["kind"] = "graph-contributor"
                elif case == "surface-path-mismatch":
                    bindings["surfaces"][0]["path"] = bindings["surfaces"][1]["path"]
                elif case == "missing-surface":
                    bindings["surfaces"].pop()
                elif case == "stale-surface":
                    bindings["surfaces"].append(
                        {
                            "id": "example.VanishedGraphProjectionContributor",
                            "kind": "graph-contributor",
                            "path": "backend/src/main/java/example/VanishedGraphProjectionContributor.java",
                            "bindings": [],
                        }
                    )
                elif case == "invalid-binding":
                    bindings["surfaces"][0]["bindings"] = [None]
                elif case == "binding-kind-mismatch":
                    bindings["surfaces"][0]["bindings"][0]["term"] = "edge.relates"
                elif case == "invalid-enum-source-shape":
                    bindings["surfaces"][1]["edge_enum_sources"] = []
                elif case == "stale-enum-source":
                    bindings["surfaces"][1]["edge_enum_sources"] = {
                        "getLinkType": "example.GraphEntityType"
                    }
                if case not in {"missing-source-root", "unparseable-enum"}:
                    bindings_path.write_text(json.dumps(bindings), encoding="utf-8")
                violations = run_ontology_binding_check(root=root)
                self.assertIn(expected_code, {violation.code for violation in violations})
    def test_ontology_binding_check_reports_unreadable_discovered_source(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            unreadable = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            original_read_text = Path.read_text

            def read_text_or_fail(path: Path, *args, **kwargs):
                if path == unreadable:
                    raise OSError("fixture source is unreadable")
                return original_read_text(path, *args, **kwargs)

            with patch.object(Path, "read_text", read_text_or_fail):
                violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-source-unreadable", {violation.code for violation in violations})
    def test_ontology_binding_check_passes_minimal_complete_inventory(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            violations = run_ontology_binding_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])
