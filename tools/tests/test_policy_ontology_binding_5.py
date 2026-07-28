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

class OntologyBinding5ChecksTest(PolicyChecksFixture):
    def test_ontology_binding_check_rejects_graph_edge_factory_form(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace("new GraphEdge(", "GraphEdge.of("),
                encoding="utf-8",
            )
            bindings = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(bindings.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            bindings.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contributor-edge-unresolved", {v.code for v in violations})
    def test_ontology_binding_check_requires_discovered_enum_for_direct_name_expression(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace(
                    '"RELATES"', "link.getLinkType().name()"
                ),
                encoding="utf-8",
            )
            bindings = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(bindings.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            bindings.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-edge-enum-source-missing", {v.code for v in violations})
    def test_ontology_binding_check_requires_discovered_enum_for_forwarded_name_expression(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                """package example;
public class RequirementGraphProjectionContributor implements GraphProjectionContributor {
    Object edge(String edgeType) {
        return new GraphEdge("id", edgeType, null, null, null, null, null);
    }
    Object use(Link link) { return edge(link.getLinkType().name()); }
}
""",
                encoding="utf-8",
            )
            bindings = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(bindings.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            bindings.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-edge-enum-source-missing", {v.code for v in violations})
    def test_ontology_binding_check_supports_record_contributor_declaration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace(
                    "public class RequirementGraphProjectionContributor",
                    "public record RequirementGraphProjectionContributor()",
                ),
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])
