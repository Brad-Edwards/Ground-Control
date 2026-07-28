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

class ControllersChecksTest(PolicyChecksFixture):
    def test_controller_contracts_require_docs_mcp_and_webmvctest(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            controller = "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java"
            controller_file = root / controller
            controller_file.parent.mkdir(parents=True, exist_ok=True)
            controller_file.write_text(
                "package com.keplerops.groundcontrol.api.foo;\nclass FooController {}\n",
                encoding="utf-8",
            )
            test_file = root / "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java"
            test_file.parent.mkdir(parents=True, exist_ok=True)
            test_file.write_text(
                "@WebMvcTest(FooController.class)\nclass FooControllerTest {}\n",
                encoding="utf-8",
            )
            violations = run_controller_contracts([controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-parity", codes)
            self.assertIn("controller-webmvctest-update", codes)
    def test_controller_contracts_skip_deleted_controllers(self):
        """A controller deleted in the diff has no mapping left to slice-test.

        Its @WebMvcTest companion is deleted with it, so demanding one would make
        route removal (e.g. the ADR-089 GRC retirement) unshippable.
        """
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            violations = run_controller_contracts(
                [
                    "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                    "docs/API.md",
                    "mcp/ground-control/lib.js",
                    "mcp/ground-control/index.js",
                ],
                root=root,
            )
            self.assertEqual([], violations)
    def test_controller_contracts_accept_gc_risk_governance_as_mcp_adapter(self):
        """gc-risk-governance.js satisfies the MCP-adapter companion (in lieu of index.js)."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            test_file = (
                root
                / "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java"
            )
            test_file.parent.mkdir(parents=True, exist_ok=True)
            test_file.write_text(
                "@WebMvcTest(FooController.class)\nclass FooControllerTest {}\n",
                encoding="utf-8",
            )
            violations = run_controller_contracts(
                [
                    "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                    "docs/API.md",
                    "mcp/ground-control/lib.js",
                    "mcp/ground-control/gc-risk-governance.js",
                    "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java",
                ],
                root=root,
            )
            codes = {item.code for item in violations}
            self.assertNotIn("controller-parity", codes)
    def test_controller_contracts_accept_gc_workflow_run_as_mcp_adapter(self):
        """gc-workflow-run.js satisfies the MCP-adapter companion (in lieu of index.js)."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            test_file = (
                root
                / "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java"
            )
            test_file.parent.mkdir(parents=True, exist_ok=True)
            test_file.write_text(
                "@WebMvcTest(FooController.class)\nclass FooControllerTest {}\n",
                encoding="utf-8",
            )
            violations = run_controller_contracts(
                [
                    "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                    "docs/API.md",
                    "mcp/ground-control/lib.js",
                    "mcp/ground-control/gc-workflow-run.js",
                    "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java",
                ],
                root=root,
            )
            codes = {item.code for item in violations}
            self.assertNotIn("controller-parity", codes)
