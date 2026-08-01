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

class EnumContract2ChecksTest(PolicyChecksFixture):
    def test_enum_contract_check_detects_frontend_missing_value(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._copy_enum_sources(root)
            api_ts = root / FRONTEND_API_TYPES_PATH
            text = api_ts.read_text(encoding="utf-8")
            # Drop PULL_REQUEST from both the union and the constant array.
            text = text.replace('"PULL_REQUEST" | ', "")
            text = text.replace('"PULL_REQUEST",', "")
            api_ts.write_text(text, encoding="utf-8")
            violations = run_enum_contract_check(root=root)
            codes = {v.code for v in violations}
            self.assertIn("enum-contract-drift", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("ArtifactType", details)
            self.assertIn("PULL_REQUEST", details)
    def test_enum_contract_check_detects_frontend_extra_value(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._copy_enum_sources(root)
            api_ts = root / FRONTEND_API_TYPES_PATH
            text = api_ts.read_text(encoding="utf-8")
            text = text.replace(
                "export const LINK_TYPES: LinkType[] = [",
                'export const LINK_TYPES: LinkType[] = ["BOGUS",',
            )
            api_ts.write_text(text, encoding="utf-8")
            violations = run_enum_contract_check(root=root)
            codes = {v.code for v in violations}
            self.assertIn("enum-contract-drift", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("LinkType", details)
    def test_enum_contract_check_detects_unmirrored_java_change(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._copy_enum_sources(root)
            req_java = next(c.java_path for c in ENUM_CONTRACT_INVENTORY if c.label == "RequirementType")
            java_file = root / req_java
            text = java_file.read_text(encoding="utf-8")
            text = text.replace("    INTERFACE\n}", "    INTERFACE,\n    SECURITY\n}")
            java_file.write_text(text, encoding="utf-8")
            violations = run_enum_contract_check(root=root)
            codes = {v.code for v in violations}
            self.assertIn("enum-contract-drift", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("RequirementType", details)
            self.assertIn("SECURITY", details)
    def test_enum_contract_check_detects_missing_source_file(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            # Empty tree -> every source file is missing.
            violations = run_enum_contract_check(root=root)
            codes = {v.code for v in violations}
            self.assertIn("enum-contract-source-missing", codes)
