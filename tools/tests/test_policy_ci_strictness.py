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

class CiStrictnessChecksTest(PolicyChecksFixture):
    def test_check_pr_body_passes_for_well_formed_body(self):
        body = (
            "## Summary\n\nFix.\n"
            "## Requirement UIDs\n\n- GC-X001\n"
            "## ADR Impact\n\nADR-026 added.\n"
            "## Ground Control Checks\n\n"
            "- [x] Configured repository policy command passes\n"
            "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change\n"
            "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
            "## Traceability\n\n- IMPLEMENTS: foo\n- TESTS: bar\n"
        )
        self.assertEqual(check_pr_body(body), [])
    def test_check_pr_body_accepts_allocator_minted_short_uid(self):
        # RequirementUidAllocator.allocate() returns `${prefix}-${n}` unpadded,
        # so a project's first nine requirements carry a single-digit suffix.
        # A PR whose only requirement is APP-2 must satisfy pr-requirement-uid
        # (issue #1425).
        body = (
            "## Summary\n\nFix.\n"
            "## Requirement UIDs\n\n- APP-2\n"
            "## ADR Impact\n\nNo ADR required.\n"
            "## Ground Control Checks\n\n"
            "- [x] Configured repository policy command passes\n"
            "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change\n"
            "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
            "## Traceability\n\n- IMPLEMENTS: foo\n- TESTS: bar\n"
        )
        self.assertEqual(check_pr_body(body), [])
    def test_pr_body_gate_accepts_the_same_corpus_the_renderer_accepts(self):
        # Parity with mcp/ground-control/lib.js: a UID the renderer accepts must
        # satisfy this gate, otherwise a requirement that reconciles and reports
        # could never be rendered into the mandatory PR body (issue #1425).
        for uid in ["APP-2", "A-1", "GC-O007", "GC-O-007", "OBS-042", "GC-OOPS", "lowercase-001"]:
            body = self._body_with_uid_section(f"- {uid}")
            self.assertEqual(check_pr_body(body), [], msg=f"should accept {uid}")
    def test_pr_body_gate_rejects_prose_and_over_bound_values(self):
        for section in ["- (no real UID here)", "- " + "A" * 49 + "-1", "", "- "]:
            body = self._body_with_uid_section(section)
            codes = {item.code for item in check_pr_body(body)}
            self.assertIn("pr-requirement-uid", codes, msg=f"should reject section {section!r}")
    def test_pr_body_gate_accepts_the_explicit_requirement_free_marker(self):
        body = self._body_with_uid_section("- (none — requirement-free change)")
        self.assertEqual(check_pr_body(body), [])
    def test_pr_body_gate_is_scoped_to_the_requirement_uids_section(self):
        # An ADR reference elsewhere must not satisfy the requirement-UID gate.
        body = self._body_with_uid_section("- (no real UID here)").replace(
            "No ADR required.", "ADR-036 updated."
        )
        codes = {item.code for item in check_pr_body(body)}
        self.assertIn("pr-requirement-uid", codes)
    def test_ci_strictness_contract_passes_on_repo(self):
        violations = run_ci_strictness_contract(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")
    def test_ci_strictness_contract_rejects_non_strict_branch_protection(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            for rel in [
                ".github/workflows/ci.yml",
                ".pre-commit-config.yaml",
                "tools/sonar/assert_no_new_issues.py",
                ".github/branch-protection-baseline.json",
            ]:
                src = REPO_ROOT / rel
                dst = root / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

            baseline_path = root / ".github/branch-protection-baseline.json"
            baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
            baseline["branches"]["dev"]["required_status_checks"]["strict"] = False
            baseline["branches"]["dev"]["required_status_checks"]["contexts"].remove("policy")
            baseline_path.write_text(json.dumps(baseline), encoding="utf-8")

            violations = run_ci_strictness_contract(root=root)
            codes = {item.code for item in violations}
            self.assertIn("ci-strictness-branch-protection-strict", codes)
            self.assertIn("ci-strictness-branch-protection-contexts", codes)
    def test_workflow_routing_contract_passes_on_repo(self):
        violations = run_workflow_routing_contract(root=REPO_ROOT)
        self.assertEqual(
            violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
        )
    def test_workflow_routing_contract_rejects_retired_executor_fields(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            (root / ".ground-control.yaml").write_text(
                "routing:\n"
                "  enabled: true\n"
                "  stages:\n"
                "    base_sync:\n"
                "      tier: low\n"
                "      agent: subagent\n",
                encoding="utf-8",
            )
            violations = run_workflow_routing_contract(root=root)
            codes = {item.code for item in violations}
            self.assertIn("workflow-routing-execution-control-retired", codes)
