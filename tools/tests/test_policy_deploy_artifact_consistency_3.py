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

class DeployArtifactConsistency3ChecksTest(PolicyChecksFixture):
    def test_env_template_orphan_key_accepts_compose_interpolation_and_inherit(self):
        # The two forms that DO read the operator's value: ${VAR} interpolation
        # and list-form inherit (`- VAR`, which forwards only when set). Both must
        # sit inside the `environment:` block to count.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            compose = root / "deploy/docker/docker-compose.prod.yml"
            compose.write_text(
                "services:\n"
                "  backend:\n"
                "    image: ${GC_IMAGE}\n"
                "    environment:\n"
                "      - GC_DATABASE_URL=${GC_DATABASE_URL}\n"
                "      - GC_INTERPOLATED=${GC_INTERPOLATED}\n"
                "      - GC_INHERITED\n"
                "    ports:\n"
                '      - "${GC_BIND_IP}:8000:8000"\n',
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8")
                + "GC_INTERPOLATED=a\nGC_INHERITED=b\n",
                encoding="utf-8",
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertNotIn("deploy-env-template-orphan-key", codes)
    def test_env_template_orphan_key_flags_commented_assignment(self):
        # A commented `# KEY=...` still advertises the key — uncommenting it is
        # the documented way to use it — so a dead one misleads just as much.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "# GC_DEAD_KNOB=somevalue\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_DEAD_KNOB", details)
    def test_env_template_orphan_key_rejects_env_schema_declaration_as_consumer(self):
        # env.schema says a key must be PRESENT and well-formed; it never reads
        # it. If a schema declaration counted as consumption, a key left stale in
        # BOTH the template and the schema would certify itself — a false negative
        # exactly where drift hides.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            schema = root / "deploy/docker/env.schema"
            schema.write_text(
                schema.read_text(encoding="utf-8") + "REQUIRED GC_SCHEMA_ONLY\n",
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "GC_SCHEMA_ONLY=x\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_SCHEMA_ONLY", details)
    def test_env_template_orphan_key_rejects_mention_in_comment_or_message(self):
        # A key named in a comment or an error-message string is a mention, not a
        # read. Matching bare textual occurrence would let any script that merely
        # *talks about* a dead key certify it as live.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            validator = root / "deploy/docker/validate-env.sh"
            validator.write_text(
                "#!/bin/bash\n"
                "# GC_MENTIONED is validated elsewhere\n"
                'echo "please set GC_MENTIONED before deploying"\n',
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "GC_MENTIONED=x\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_MENTIONED", details)
