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

class DeployArtifactConsistencyChecksTest(PolicyChecksFixture):
    def test_repo_identity_drift_ignores_filesystem_paths(self):
        # A local checkout path like `/home/user/src/Ground-Control/...` is NOT
        # a repository-identity slug; the negative lookbehind must exclude it so
        # .mcp.json's node args path does not read as owner 'src'.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            mcp = root / ".mcp.json"
            mcp.write_text(
                '{"args": ["/home/user/src/Ground-Control/mcp/ground-control/index.js"]}\n',
                encoding="utf-8",
            )
            violations = run_repo_identity_drift(root=root)
            self.assertEqual(
                violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
            )
    def test_pack_registry_workflow_raw_urls_use_runtime_repo(self):
        # The pack-registry workflow must build raw-content URLs from the trusted
        # runtime repository + exact SHA, never a hardcoded owner (#1383).
        wf = REPO_ROOT / ".github/workflows/pack-registry-sync.yml"
        text = wf.read_text(encoding="utf-8")
        raw_lines = [ln for ln in text.splitlines() if "RAW_BASE_URL=" in ln]
        self.assertTrue(raw_lines, msg="expected RAW_BASE_URL assignments in the workflow")
        for ln in raw_lines:
            self.assertIn("GITHUB_REPOSITORY", ln, msg=f"raw URL not runtime-derived: {ln}")
            self.assertIn("GITHUB_SHA", ln, msg=f"raw URL not SHA-pinned: {ln}")
    def test_repo_identity_drift_fires_on_wrong_repo_name(self):
        # A well-formed but wrong repository NAME (correct owner, wrong repo) in
        # an identity-declaration field must fail too — not just a wrong owner.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            cfg = root / ".ground-control.yaml"
            cfg.write_text("github_repo: autarchy-ai/Other\n", encoding="utf-8")
            violations = run_repo_identity_drift(root=root)
            self.assertIn("repo-identity-drift", {v.code for v in violations})
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("autarchy-ai/Other", details)
    def test_repo_identity_drift_fires_on_stale_owner_in_admin_surface(self):
        # The admin console owner placeholder carries only the owner, not a full
        # owner/repo slug; a regression to a stale owner literal must still fail.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            admin = root / "frontend/src/pages/admin.tsx"
            admin.parent.mkdir(parents=True, exist_ok=True)
            admin.write_text('<input placeholder="KeplerOps" />\n', encoding="utf-8")
            violations = run_repo_identity_drift(root=root)
            self.assertIn("repo-identity-drift", {v.code for v in violations})
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("KeplerOps", details)
            self.assertIn("frontend/src/pages/admin.tsx", details)
    def test_deploy_artifact_consistency_passes_on_committed_repo(self):
        # The committed deploy artifacts must satisfy every GC-P023 invariant;
        # run against the live tree as the post-condition assertion.
        violations = run_deploy_artifact_consistency(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")
    def test_deploy_artifact_consistency_passes_on_minimal_valid_tree(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            violations = run_deploy_artifact_consistency(root=root)
            self.assertEqual(violations, [], msg=f"unexpected: {[v.render() for v in violations]}")
    def test_deploy_artifact_consistency_flags_env_template_duplicate(self):
        # A reintroduced .env.template is the contradictory second template
        # #855 removed; the gate must fail so it cannot drift back in.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            (root / "deploy/docker/.env.template").write_text(
                "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:latest\n", encoding="utf-8"
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertIn("deploy-env-template-duplicate", codes)
    def test_deploy_artifact_consistency_flags_manifest_drift(self):
        # Editing a canonical artifact without regenerating MANIFEST.sha256 must
        # fail: the deploy-time drift guard verifies /opt/gc against the manifest.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            (root / "deploy/docker/deploy.sh").write_text(
                "#!/bin/bash\ndocker compose --env-file .env up -d\necho changed\n", encoding="utf-8"
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-manifest-stale", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("deploy.sh", details)
    def test_deploy_artifact_consistency_flags_schema_incomplete(self):
        # A compose variable absent from env.schema is schema/compose drift.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            compose = root / "deploy/docker/docker-compose.prod.yml"
            compose.write_text(
                compose.read_text(encoding="utf-8") + "      - GC_NEW_KNOB=${GC_NEW_KNOB}\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-schema-incomplete", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_NEW_KNOB", details)
