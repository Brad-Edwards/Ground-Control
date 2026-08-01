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

class DeployArtifactConsistency2ChecksTest(PolicyChecksFixture):
    def test_deploy_artifact_consistency_flags_missing_release_pin(self):
        # Dropping RELEASE_PIN GC_IMAGE would let a floating branch tag (:main)
        # pass the deploy-time validator unchallenged, re-conflating release and
        # deploy (ADR-063 / #1222).
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            schema = root / "deploy/docker/env.schema"
            schema.write_text("REQUIRED GC_IMAGE\nREQUIRED GC_DATABASE_URL\n", encoding="utf-8")
            # Regenerate the manifest so only the release-pin invariant trips.
            self._rewrite_manifest(root)
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertIn("deploy-env-schema-release-pin", codes)
    def test_deploy_artifact_consistency_flags_wrapper_duplicating_logic(self):
        # The operator wrapper must not reimplement the rollout primitives that
        # live only in the canonical deploy.sh (single-source invariant).
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            (root / "scripts/deploy.sh").write_text(
                "#!/bin/bash\ndocker compose --env-file .env pull\n", encoding="utf-8"
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertIn("deploy-wrapper-duplicates-logic", codes)
    def test_deploy_artifact_consistency_flags_dead_wrapper_duplicate(self):
        # The dead divergent duplicate at deploy/scripts/deploy.sh must not come
        # back — it was the broken curl-health-check copy #855 removed.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            dead = root / "deploy/scripts/deploy.sh"
            dead.parent.mkdir(parents=True, exist_ok=True)
            dead.write_text("#!/bin/bash\necho stale duplicate\n", encoding="utf-8")
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertIn("deploy-wrapper-duplicate", codes)
    def test_env_template_orphan_key_flags_key_with_no_consumer(self):
        # The #1359 residue: a template advertising a worker/namespace/task-queue
        # for a service that no longer exists. Nothing reads it, so an operator
        # setting it gets silence — the exact drift #1384 exists to stop.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8")
                + "TEMPORAL_TASK_QUEUE=ground-control-implement\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("TEMPORAL_TASK_QUEUE", details)
    def test_env_template_orphan_key_rejects_compose_literal_as_consumer(self):
        # `- GC_SERVER_PORT=8000` PINS the value; it does not read the operator's.
        # Counting it as a consumer would let the template advertise control the
        # operator does not have — a quieter version of the same lie.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            compose = root / "deploy/docker/docker-compose.prod.yml"
            compose.write_text(
                compose.read_text(encoding="utf-8") + "      - GC_SERVER_PORT=8000\n",
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "GC_SERVER_PORT=9000\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_SERVER_PORT", details)
