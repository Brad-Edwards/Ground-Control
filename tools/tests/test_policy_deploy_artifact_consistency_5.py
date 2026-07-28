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

class DeployArtifactConsistency5ChecksTest(PolicyChecksFixture):
    def test_env_template_orphan_key_rejects_typo_in_spring_leaf_property(self):
        # Binding is resolved through the nested POJO to its leaf: CREDENTIALS is a
        # List<ApiCredential>, so `_0_TOKEN` binds but `_0_TOKNE` binds to nothing.
        # Accepting any tail under a known field would certify a misspelled key
        # that Spring itself would never bind.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            self._write_spring_properties(root)
            (root / ".env.example").write_text(
                "GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKNE=x\n", encoding="utf-8"
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKNE", details)
    def test_env_template_orphan_key_rejects_node_mention_in_comment_or_string(self):
        # `// process.env.GC_DEAD` and `"process.env.GC_DEAD"` are mentions. Only
        # an executing read counts, or any commented-out reference would keep a
        # dead key alive forever.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            mcp = root / "mcp/ground-control/index.js"
            mcp.parent.mkdir(parents=True, exist_ok=True)
            mcp.write_text(
                "// process.env.GC_COMMENTED is no longer read\n"
                'const doc = "process.env.GC_STRINGED";\n',
                encoding="utf-8",
            )
            (root / ".env.example").write_text(
                "GC_COMMENTED=a\nGC_STRINGED=b\n", encoding="utf-8"
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_COMMENTED", details)
            self.assertIn("GC_STRINGED", details)
    def test_env_template_orphan_key_rejects_trailing_comment_mention(self):
        # A key named in a compose or shell TRAILING comment is not a read either.
        # Only whole-line comments were dropped before; `- FOO  # ${GC_DEAD}` would
        # otherwise certify GC_DEAD.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            compose = root / "deploy/docker/docker-compose.prod.yml"
            compose.write_text(
                compose.read_text(encoding="utf-8").replace(
                    "      - GC_DATABASE_URL=${GC_DATABASE_URL}\n",
                    "      - GC_DATABASE_URL=${GC_DATABASE_URL}  # was ${GC_TRAILING}\n",
                ),
                encoding="utf-8",
            )
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "GC_TRAILING=x\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_TRAILING", details)
    def test_env_template_orphan_key_rejects_single_quoted_shell_mention(self):
        # The shell does not expand inside single quotes, so '$GC_DEAD' is literal
        # text, not a read.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            validator = root / "deploy/docker/validate-env.sh"
            validator.write_text("#!/bin/bash\necho 'set $GC_QUOTED first'\n", encoding="utf-8")
            self._rewrite_manifest(root)
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "GC_QUOTED=x\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("GC_QUOTED", details)
    def test_env_template_orphan_key_accepts_node_bracket_read(self):
        # process.env["VAR"] keeps its key inside a string legitimately, so the
        # string-strip that kills mentions must not kill this real read.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            mcp = root / "mcp/ground-control/index.js"
            mcp.parent.mkdir(parents=True, exist_ok=True)
            mcp.write_text('const t = process.env["GC_BRACKET"];\n', encoding="utf-8")
            (root / ".env.example").write_text("GC_BRACKET=x\n", encoding="utf-8")
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertNotIn("deploy-env-template-orphan-key", codes)
