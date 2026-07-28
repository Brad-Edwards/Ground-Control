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

class AuthzMatrixSyncChecksTest(PolicyChecksFixture):
    def test_implement_final_report_schema_requires_review_evidence_invariant(self):
        schema = json.loads(
            (REPO_ROOT / "contracts/schemas/records/implement-final-report.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )

        invariant_ids = {entry["id"] for entry in schema["x-ground-control-invariants"]}

        self.assertIn("gc.implement.final-report.review-evidence-present", invariant_ids)
        self.assertIn("reviews", schema["required"])
        self.assertEqual(schema["properties"]["reviews"]["type"], "array")
        self.assertEqual(schema["properties"]["reviews"]["items"]["minLength"], 1)
    def test_workflow_run_record_schema_has_closed_state_vocabulary_invariant(self):
        schema = json.loads(
            (REPO_ROOT / "contracts/schemas/workflow/workflow-run-record.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )

        invariant_ids = {entry["id"] for entry in schema["x-ground-control-invariants"]}

        self.assertIn("gc.workflow.run-record.phase-state-closed-set", invariant_ids)
        self.assertEqual(
            schema["properties"]["finalState"]["enum"],
            [
                "RUNNING",
                "READY_FOR_REVIEW",
                "MERGED",
                "CLOSED",
                "ESCALATED",
                "ABANDONED",
                "SUPERSEDED",
                "FAILED",
            ],
        )
        self.assertEqual(
            schema["properties"]["provenance"]["enum"],
            ["ISSUE_THREAD", "TEMPORAL_VISIBILITY", "MANUAL_IMPORT", "LIVE_EMISSION"],
        )
    def test_authz_matrix_sync_detects_missing_contract_row(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            matrix = root / "contracts" / "authz" / "path-matrix.yaml"
            matrix.parent.mkdir(parents=True)
            matrix.write_text(
                "rules:\n"
                '  - id: admin\n'
                '    method: "*"\n'
                '    path: "/api/v1/admin/**"\n'
                '    access: "ROLE_ADMIN"\n',
                encoding="utf-8",
            )
            java = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "com"
                / "keplerops"
                / "groundcontrol"
                / "shared"
                / "security"
                / "ApiPathMatrix.java"
            )
            java.parent.mkdir(parents=True)
            java.write_text(
                'final class ApiPathMatrix { private static final String ROLE_ADMIN = "ADMIN"; '
                'void apply(Object auth) { auth.requestMatchers("/api/v1/admin/**").hasRole(ROLE_ADMIN)'
                '.requestMatchers("/api/v1/pack-registry/**").hasRole(ROLE_ADMIN); }}',
                encoding="utf-8",
            )
            violations = run_authz_matrix_sync_check(root=root)
        self.assertTrue(any(v.code == "authz-matrix-drift" for v in violations))
