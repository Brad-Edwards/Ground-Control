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

class MigrationPolicyChecksTest(PolicyChecksFixture):
    def test_controller_webmvctest_wrong_companion_in_diff_still_fails(self):
        """Adversarial guard for issue #1167: a same-named companion from the WRONG
        package, present in the diff, must NOT satisfy coverage for the other
        package's controller. Passes under FQCN resolution; fails under
        simple-name matching, so it detects a regression to the old heuristic."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            audit_controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audit/AuditController.java",
                "package com.keplerops.groundcontrol.api.audit;\nclass AuditController {}\n",
            )
            self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audits/AuditController.java",
                "package com.keplerops.groundcontrol.api.audits;\nclass AuditController {}\n",
            )
            # Wrong companion: same simple name, but imports the OTHER package's controller.
            wrong_companion = self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audits.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditControllerTest {}\n",
            )
            # Correct companion exists on disk but is deliberately NOT in the diff.
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditTrailControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audit.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditTrailControllerTest {}\n",
            )
            violations = run_controller_contracts([audit_controller, wrong_companion], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-update", codes)
    def test_migration_policy_requires_smoke_and_e2e_updates(self):
        violations = run_migration_policy(
            ["backend/src/main/resources/db/migration/V999__example.sql"],
            root=REPO_ROOT,
        )
        self.assertTrue(any(item.code == "migration-smoke-sync" for item in violations))
    def test_migration_immutability_flags_edited_applied_migration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root, rel, path = self._init_migration_repo(tmp_dir, "SELECT 1;\n")
            path.write_text("SELECT 2;\n", encoding="utf-8")  # edit an already-applied migration
            violations = run_migration_policy([rel], root=root, base="baseline")
            self.assertTrue(any(item.code == "migration-immutability" for item in violations))
    def test_migration_immutability_flags_removed_applied_migration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root, rel, path = self._init_migration_repo(tmp_dir, "SELECT 1;\n")
            path.unlink()  # deleting a released migration is also a violation
            violations = run_migration_policy([rel], root=root, base="baseline")
            self.assertTrue(any(item.code == "migration-immutability" for item in violations))
    def test_migration_immutability_allows_unchanged_baseline_migration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root, rel, _ = self._init_migration_repo(tmp_dir, "SELECT 1;\n")
            violations = run_migration_policy([rel], root=root, base="baseline")
            self.assertFalse(any(item.code == "migration-immutability" for item in violations))
    def test_migration_immutability_allows_new_forward_migration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root, _, _ = self._init_migration_repo(tmp_dir, "SELECT 1;\n")
            new_rel = "backend/src/main/resources/db/migration/V101__forward.sql"
            (root / new_rel).write_text("SELECT 3;\n", encoding="utf-8")
            violations = run_migration_policy([new_rel], root=root, base="baseline")
            self.assertFalse(any(item.code == "migration-immutability" for item in violations))
    def test_is_release_pr(self):
        self.assertTrue(_is_release_pr("main", "dev"))  # dev -> main promotion
        self.assertTrue(_is_release_pr("main", "release-please--branches--main"))  # release PR
        self.assertTrue(_is_release_pr("dev", "sync/main-to-dev"))  # main -> dev back-merge
        self.assertFalse(_is_release_pr("dev", "feature-x"))  # feature -> dev
        self.assertFalse(_is_release_pr("main", "hotfix"))  # direct hotfix -> main
        self.assertFalse(_is_release_pr("main", "sync/main-to-dev"))  # sync only targets dev
        self.assertFalse(_is_release_pr(None, None))  # refs unknown (local run)
    def test_resolve_pr_refs_from_event_payload(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            event = Path(tmp_dir) / "event.json"
            event.write_text(json.dumps({"pull_request": {"base": {"ref": "main"}, "head": {"ref": "dev"}}}))
            args = parse_args(["--event-path", str(event)])
            self.assertEqual(_resolve_pr_refs(args), ("main", "dev"))
    def test_release_pr_skips_body_contract(self):
        # A dev -> main release PR with an empty/default body must not fail on the
        # per-PR body contract (the failure that hit every "Dev" release PR).
        output = self._run_main_for_event("main", "dev", "garbage body with no sections")
        self.assertNotIn("pr-template-sections", output)
        self.assertNotIn("pr-requirement-uid", output)
    def test_non_release_pr_still_enforces_body_contract(self):
        output = self._run_main_for_event("dev", "feature-x", "garbage body with no sections")
        self.assertIn("pr-template-sections", output)
    def test_pr_body_requires_new_sections(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            event_path = Path(tmp_dir) / "event.json"
            event_path.write_text(json.dumps({"pull_request": {"body": "## Summary\n\nMissing policy sections"}}))
            violations = run_pr_body_check(event_path)
            self.assertTrue(any(item.code == "pr-template-sections" for item in violations))
    def test_check_pr_body_accepts_string_directly(self):
        violations = check_pr_body("## Summary\n\nMissing policy sections")
        codes = {item.code for item in violations}
        self.assertIn("pr-template-sections", codes)
