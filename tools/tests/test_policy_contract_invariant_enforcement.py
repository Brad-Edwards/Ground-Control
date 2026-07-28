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

class ContractInvariantEnforcementChecksTest(PolicyChecksFixture):
    def test_workflow_routing_contract_flags_missing_base_sync_stage(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            (root / ".ground-control.yaml").write_text(
                "routing:\n"
                "  enabled: true\n"
                "  stages:\n"
                "    codebase_assessment:\n"
                "      tier: medium\n",
                encoding="utf-8",
            )
            violations = run_workflow_routing_contract(root=root)
            codes = {item.code for item in violations}
            self.assertIn("workflow-routing-base-sync-stage-missing", codes)
    def test_workflow_routing_contract_reports_missing_config(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            violations = run_workflow_routing_contract(root=Path(tmp_dir))
            codes = {item.code for item in violations}
            self.assertIn("workflow-routing-config-missing", codes)
    def test_contract_surface_check_passes_on_repo(self):
        violations = run_contract_surface_check(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")
    def test_contract_invariant_enforcement_rejects_missing_enforcement_file(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            schema_dir = root / "contracts" / "schemas" / "records"
            schema_dir.mkdir(parents=True)
            (schema_dir / "sample.schema.json").write_text(
                json.dumps(
                    {
                        "$id": "gc.test.sample.v1",
                        "type": "object",
                        "x-ground-control-invariants": [
                            {"id": "gc.test.sample.required", "enforcedBy": ["missing/Test.java::testRequired"]}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            violations = run_contract_invariant_enforcement_check(root=root)
        self.assertTrue(any(v.code == "contract-invariant-enforcement-missing-file" for v in violations))
    def test_contract_invariant_enforcement_rejects_file_only_target(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            schema_dir = root / "contracts" / "schemas" / "records"
            schema_dir.mkdir(parents=True)
            test_file = root / "tools" / "tests" / "test_policy.py"
            test_file.parent.mkdir(parents=True)
            test_file.write_text("def test_sample(): pass\n", encoding="utf-8")
            (schema_dir / "sample.schema.json").write_text(
                json.dumps(
                    {
                        "$id": "gc.test.sample.v1",
                        "type": "object",
                        "x-ground-control-invariants": [
                            {"id": "gc.test.sample.required", "enforcedBy": ["tools/tests/test_policy.py"]}
                        ],
                    }
                ),
                encoding="utf-8",
            )

            violations = run_contract_invariant_enforcement_check(root=root)

        self.assertTrue(any(v.code == "contract-invariant-enforcement-anchor-missing" for v in violations))
    def test_contract_invariant_enforcement_rejects_missing_anchor(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            schema_dir = root / "contracts" / "schemas" / "records"
            schema_dir.mkdir(parents=True)
            test_file = root / "tools" / "tests" / "test_policy.py"
            test_file.parent.mkdir(parents=True)
            test_file.write_text("def test_sample(): pass\n", encoding="utf-8")
            (schema_dir / "sample.schema.json").write_text(
                json.dumps(
                    {
                        "$id": "gc.test.sample.v1",
                        "type": "object",
                        "x-ground-control-invariants": [
                            {
                                "id": "gc.test.sample.required",
                                "enforcedBy": ["tools/tests/test_policy.py::test_missing"],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            violations = run_contract_invariant_enforcement_check(root=root)

        self.assertTrue(any(v.code == "contract-invariant-enforcement-anchor-missing-file" for v in violations))
