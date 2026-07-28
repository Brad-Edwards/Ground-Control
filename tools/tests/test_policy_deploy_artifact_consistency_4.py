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

class DeployArtifactConsistency4ChecksTest(PolicyChecksFixture):
    def test_env_template_orphan_key_accepts_real_shell_read(self):
        # The reads that DO count: $VAR / ${VAR} expansion and the ENV_VALUES[VAR]
        # associative lookup validate-env.sh uses on the parsed env file.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            validator = root / "deploy/docker/validate-env.sh"
            validator.write_text(
                '#!/bin/bash\necho "${GC_EXPANDED}"\n'
                'if [ "${ENV_VALUES[GC_LOOKED_UP]:-}" = "1" ]; then :; fi\n',
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8")
                + "GC_EXPANDED=a\nGC_LOOKED_UP=1\n",
                encoding="utf-8",
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertNotIn("deploy-env-template-orphan-key", codes)
    def test_env_template_orphan_key_rejects_bare_list_item_outside_environment(self):
        # `- FOO` only forwards a variable inside an `environment:` block. In a
        # ports/volumes/command list it is an ordinary list entry, and counting it
        # would bless a key nothing reads.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            compose = root / "deploy/docker/docker-compose.prod.yml"
            compose.write_text(
                compose.read_text(encoding="utf-8")
                + "    command:\n      - GC_NOT_A_VAR\n",
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "GC_NOT_A_VAR=x\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_NOT_A_VAR", details)
    def test_env_template_orphan_key_accepts_spring_relaxed_binding(self):
        # The dev template's indexed slots bind via Spring relaxed binding and
        # appear literally in no yaml: GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN
        # -> groundcontrol.security.credentials[0].token. Resolving that
        # structurally is what keeps the check free of per-key exceptions.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            self._write_spring_properties(root)
            (root / ".env.example").write_text(
                "GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN=x\n"
                "GROUNDCONTROL_SECURITY_IP_ALLOWLIST_0=10.0.0.0/8\n",
                encoding="utf-8",
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertNotIn("deploy-env-template-orphan-key", codes)
    def test_env_template_orphan_key_rejects_unknown_spring_child(self):
        # Binding resolves to a DECLARED field, not merely the prefix. A key under
        # a real prefix that maps to no property binds to nothing — prefix-only
        # matching would bless every unknown child of a live prefix.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            self._write_spring_properties(root)
            (root / ".env.example").write_text(
                "GROUNDCONTROL_SECURITY_BOGUS_KNOB=x\n", encoding="utf-8"
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GROUNDCONTROL_SECURITY_BOGUS_KNOB", details)
    def test_env_template_orphan_key_accepts_spring_placeholder_default_syntax(self):
        # Spring defaults with a single colon (${GC_SERVER_PORT:8000}), unlike
        # compose's ${VAR:-default}. Reusing the compose pattern here would miss
        # every defaulted placeholder and flag live keys as orphans.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            app_yml = root / "backend/src/main/resources/application.yml"
            app_yml.parent.mkdir(parents=True, exist_ok=True)
            app_yml.write_text(
                "server:\n  port: ${GC_SERVER_PORT:8000}\n"
                "spring:\n  datasource:\n    url: ${GC_DATABASE_URL}\n",
                encoding="utf-8",
            )
            (root / ".env.example").write_text(
                "GC_SERVER_PORT=8000\nGC_DATABASE_URL=jdbc:postgresql://localhost/x\n",
                encoding="utf-8",
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertNotIn("deploy-env-template-orphan-key", codes)
