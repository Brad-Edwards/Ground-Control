import copy
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import unittest
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

class TraceabilityReconciliationGateContractTest(unittest.TestCase):
    """Structural gate for the issue #1058/#1103 traceability + post-merge close gate."""
    _FILES = {
        "skills/implement/SKILL.md": (
            "## Phase boundaries\n\nPhase E is the post-merge close phase.\n"
            "Phase E calls `gc_close_issue_after_merge` after the user merges.\n"
        ),
        "skills/implement/steps/step-17-completion.md": (
            "# Step 17: Phase D Completion\n\n"
            "Call `gc_assert_completion` to assert `traceability_reconciled` and post with `plain_english_outcome`.\n"
        ),
        "skills/implement/steps/step-20-close-issue-on-merge.md": (
            "# Step 20: Close the Issue (Phase E, Post-Merge)\n\n"
            "Calls `gc_close_issue_after_merge` to verify merged_at and close.\n"
        ),
    }
    def _populate(self, root: Path, overrides: dict[str, str | None] | None = None) -> None:
        """Write each fixture file under root; overrides=None to skip a file or replace its body."""
        overrides = overrides or {}
        for rel_path, body in self._FILES.items():
            if rel_path in overrides:
                override = overrides[rel_path]
                if override is None:
                    # file intentionally missing
                    continue
                body = override
            path = root / rel_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")
    def test_check_passes_when_all_four_surfaces_carry_required_tokens(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root)
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertEqual(violations, [])
    def test_check_flags_step17_completion_missing_tokens(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/steps/step-17-completion.md":
                    "# Step 17: Phase D Completion\n\nNo MCP call.\n",
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertTrue(violations)
            codes = {v.code for v in violations}
            self.assertIn("traceability-gate-step17-missing", codes)
    def test_check_flags_step17_missing_plain_english_outcome(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/steps/step-17-completion.md":
                    "# Step 17: Phase D Completion\n\n"
                    "Call `gc_assert_completion` to assert `traceability_reconciled`.\n",
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertTrue(violations)
            codes = {v.code for v in violations}
            self.assertIn("traceability-gate-step17-missing", codes)
    def test_check_flags_step20_missing_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/steps/step-20-close-issue-on-merge.md": None,
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertTrue(violations)
            codes = {v.code for v in violations}
            self.assertIn("traceability-gate-step20-missing", codes)
    def test_check_passes_step20_with_only_close_tool_mention(self) -> None:
        # ADR-089 (issue #1346): the close-path gate no longer requires a
        # next_issue_recommendation anchor — the feature is retired, and the
        # gate must not demand prose for a removed field.
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/steps/step-20-close-issue-on-merge.md":
                    "# Step 20: Close the Issue\n\nCalls `gc_close_issue_after_merge` only.\n",
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertEqual(violations, [])
    def test_check_flags_skill_missing_phase_e(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/SKILL.md":
                    "## Phase boundaries\n\nPhases A-D run in order; call `gc_close_issue_after_merge` at the end.\n",
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertTrue(violations)
            codes = {v.code for v in violations}
            self.assertIn("traceability-gate-skill-missing", codes)
    def test_check_flags_skill_missing_close_tool_name_or_recommendation(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/SKILL.md":
                    "## Phase boundaries\n\nPhase E runs after merge; the user authorizes the close.\n",
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertTrue(violations)
            codes = {v.code for v in violations}
            self.assertIn("traceability-gate-skill-missing", codes)
    def test_check_passes_skill_with_only_phase_e_and_close_tool(self) -> None:
        # ADR-089 (issue #1346): SKILL.md no longer needs to mention
        # next_issue_recommendation — Phase E + the close tool name are
        # sufficient now that the recommendation feature is retired.
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            self._populate(root, overrides={
                "skills/implement/SKILL.md":
                    "## Phase boundaries\n\n"
                    "Phase E runs after merge; call `gc_close_issue_after_merge`.\n",
            })
            violations = run_traceability_reconciliation_gate_contract(root=root)
            self.assertEqual(violations, [])
