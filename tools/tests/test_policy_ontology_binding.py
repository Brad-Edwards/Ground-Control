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

class OntologyBindingChecksTest(PolicyChecksFixture):
    def test_ontology_binding_check_covers_contract_and_vocabulary_validation_branches(self):
        cases = (
            ("missing-contract", "ontology-contract-missing"),
            ("non-object-contract", "ontology-contract-shape-invalid"),
            ("unsupported-version", "ontology-contract-version-invalid"),
            ("unknown-owner", "ontology-owner-invalid"),
            ("invalid-family", "ontology-family-invalid"),
            ("invalid-provenance", "ontology-provenance-invalid"),
            ("missing-native-rules", "ontology-native-family-rules-missing"),
            ("invalid-term", "ontology-term-invalid"),
            ("invalid-term-kind", "ontology-term-kind-invalid"),
            ("unknown-family", "ontology-family-reference-missing"),
            ("invalid-edge-semantics", "ontology-edge-semantics-invalid"),
        )
        for case, expected_code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp_dir:
                root = Path(tmp_dir)
                self._write_minimal_ontology_fixture(root)
                families_path, families = self._read_ontology_fixture_json(
                    root, "gc-concept-families-v1.json"
                )
                terms_path, terms = self._read_ontology_fixture_json(
                    root, "gc-controlled-vocabularies-v1.json"
                )
                if case == "missing-contract":
                    families_path.unlink()
                elif case == "non-object-contract":
                    families_path.write_text("[]", encoding="utf-8")
                elif case == "unsupported-version":
                    families["schema_version"] = "gc-concept-families/v2"
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "unknown-owner":
                    families["owners"] = ["unknown-owner"]
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "invalid-family":
                    family = families["families"].pop("requirements-and-traceability")
                    families["families"][""] = family
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "invalid-provenance":
                    families["families"]["requirements-and-traceability"]["provenance"] = "invented"
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "missing-native-rules":
                    families["families"]["requirements-and-traceability"].pop("extension_scope")
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "invalid-term":
                    terms["terms"]["node.requirement"]["title"] = ""
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                elif case == "invalid-term-kind":
                    terms["terms"]["node.requirement"]["kind"] = "unknown"
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                elif case == "unknown-family":
                    terms["terms"]["node.requirement"]["family"] = "missing-family"
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                elif case == "invalid-edge-semantics":
                    terms["terms"]["edge.relates"]["direction"] = "backward"
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                violations = run_ontology_binding_check(root=root)
                self.assertIn(expected_code, {violation.code for violation in violations})
