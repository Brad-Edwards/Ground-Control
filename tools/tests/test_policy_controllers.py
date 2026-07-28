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

class ControllersChecksTest(unittest.TestCase):
    def _implement_contract_root(self, tmp_dir):
        """Copy the surfaces run_implement_execution_contract reads into a temp root."""
        root = Path(tmp_dir)
        for rel in (
            "skills/implement/SKILL.md",
            "skills/implement/_development-principles.md",
            "skills/implement/steps",
            ".cursor/skills/implement/SKILL.md",
            "mcp/ground-control/lib.js",
            "mcp/ground-control/index.js",
        ):
            source = REPO_ROOT / rel
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
        return root
    def _render_pr_body_via_js(self, input_dict):
        """Invoke `tools/render_pr_body_fixture.mjs` against the JS renderer in
        `mcp/ground-control/lib.js::buildPrBody`. Pipes JSON in, returns the
        rendered body. Skips the test if `node` is unavailable on PATH.
        The fixture script imports the actual lib.js, so this binds the
        compose contract to the real renderer instead of a copied Python
        string."""
        import shutil
        import subprocess

        if shutil.which("node") is None:
            self.skipTest("node not available on PATH; renderer compose check needs Node")
        fixture = REPO_ROOT / "tools" / "render_pr_body_fixture.mjs"
        proc = subprocess.run(
            ["node", str(fixture)],
            input=json.dumps(input_dict),
            capture_output=True,
            text=True,
            cwd=str(REPO_ROOT),
        )
        self.assertEqual(
            proc.returncode,
            0,
            f"renderer fixture exited {proc.returncode}: stderr={proc.stderr}",
        )
        return proc.stdout
    def _workflow_guardrail_rule(self):
        policy_path = REPO_ROOT / "architecture/policies/adr-policy.json"
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
        for pol in policy.get("policies", []):
            for r in pol.get("rules", []):
                if r.get("id") == "workflow-guardrail-sync":
                    return r
        return None
    def _module_graph_registry(self, *, edges=None):
        modules = [
            {
                "id": "mcp-tools",
                "name": "MCP tools",
                "surface": "mcp",
                "owner": "@Brad-Edwards",
                "lock_level": "guarded",
                "risk_score": 2,
                "selectors": ["mcp/ground-control/gc-*.js"],
            },
            {
                "id": "mcp-lib",
                "name": "MCP lib",
                "surface": "mcp",
                "owner": "@Brad-Edwards",
                "lock_level": "locked",
                "risk_score": 4,
                "selectors": ["mcp/ground-control/lib.js"],
            },
        ]
        return {
            "schema_version": 1,
            "risk_model": "cld-v1",
            "modules": modules,
            "allowed_edges": edges if edges is not None else [{"from": "mcp-tools", "to": "mcp-lib"}],
        }
    def _write_module_graph_fixture(self, root, *, registry=None, protect=True):
        self._write_file(
            root, "architecture/registry/module-graph.json", json.dumps(registry or self._module_graph_registry())
        )
        if protect:
            protected = {
                "schema_version": 1,
                "categories": [
                    {
                        "id": "architecture-registry",
                        "name": "Architecture registry",
                        "selectors": ["architecture/registry/**"],
                        "approval_mode": "design_authority",
                        "codeowners": ["@Brad-Edwards"],
                        "weakening_detectors": [],
                        "freeze_inputs": ["architecture/registry/**"],
                    }
                ],
            }
            self._write_file(root, "architecture/registry/protected-paths.json", json.dumps(protected))
    @staticmethod
    def _write_file(root: Path, rel: str, content: str) -> str:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return rel
    @staticmethod
    def _init_migration_repo(tmp_dir, baseline_content):
        """Create a git repo with one migration committed on a `baseline` ref."""
        import subprocess

        root = Path(tmp_dir)
        rel = "backend/src/main/resources/db/migration/V100__baseline.sql"
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(baseline_content, encoding="utf-8")

        def git(*args):
            subprocess.run(["git", *args], cwd=str(root), check=True, capture_output=True, text=True)

        git("init", "-q")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        git("add", "-A")
        git("commit", "-q", "-m", "baseline")
        git("branch", "baseline")
        return root, rel, path
    @staticmethod
    def _run_main_for_event(base_ref, head_ref, body):
        import contextlib
        import io

        with tempfile.TemporaryDirectory() as tmp_dir:
            event = Path(tmp_dir) / "event.json"
            event.write_text(
                json.dumps(
                    {"pull_request": {"body": body, "base": {"ref": base_ref}, "head": {"ref": head_ref}}}
                )
            )
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                main(["--event-path", str(event), "--files", "README.md"])
            return buf.getvalue()
    def _body_with_uid_section(self, section_body):
        return (
            "## Summary\n\nFix.\n"
            f"## Requirement UIDs\n\n{section_body}\n"
            "## ADR Impact\n\nNo ADR required.\n"
            "## Ground Control Checks\n\n"
            "- [x] Configured repository policy command passes\n"
            "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change\n"
            "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
            "## Traceability\n\n- IMPLEMENTS: foo\n- TESTS: bar\n"
        )
    def _write_valid_deploy_tree(self, root: Path) -> None:
        """Write a minimal internally-consistent deploy artifact set.

        Mirrors the invariants run_deploy_artifact_consistency enforces so a
        test can introduce exactly one defect and assert the matching code.
        """
        ddir = root / "deploy/docker"
        ddir.mkdir(parents=True, exist_ok=True)
        (ddir / ".env.example").write_text(
            "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:main\n", encoding="utf-8"
        )
        (ddir / "env.schema").write_text(
            "REQUIRED GC_IMAGE\n"
            "RELEASE_PIN GC_IMAGE\n"
            "REQUIRED GC_DATABASE_URL\n"
            "REQUIRED GC_BIND_IP\n",
            encoding="utf-8",
        )
        (ddir / "docker-compose.prod.yml").write_text(
            "services:\n"
            "  backend:\n"
            "    image: ${GC_IMAGE}\n"
            "    environment:\n"
            "      - GC_DATABASE_URL=${GC_DATABASE_URL}\n"
            "    ports:\n"
            "      - \"${GC_BIND_IP}:8000:8000\"\n",
            encoding="utf-8",
        )
        (ddir / "deploy.sh").write_text("#!/bin/bash\ndocker compose --env-file .env up -d\n", encoding="utf-8")
        (ddir / "validate-env.sh").write_text("#!/bin/bash\nexit 0\n", encoding="utf-8")
        # Manifest must match the four canonical artifacts byte-for-byte.
        manifest_lines = []
        for name in ("deploy.sh", "docker-compose.prod.yml", "validate-env.sh", "env.schema"):
            digest = hashlib.sha256((ddir / name).read_bytes()).hexdigest()
            manifest_lines.append(f"{digest}  {name}")
        (ddir / "MANIFEST.sha256").write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")
        wrapper = root / "scripts/deploy.sh"
        wrapper.parent.mkdir(parents=True, exist_ok=True)
        wrapper.write_text("#!/bin/bash\nssh gc-deploy@red-dragon\n", encoding="utf-8")
    def _rewrite_manifest(self, root: Path) -> None:
        ddir = root / "deploy/docker"
        lines = []
        for name in ("deploy.sh", "docker-compose.prod.yml", "validate-env.sh", "env.schema"):
            digest = hashlib.sha256((ddir / name).read_bytes()).hexdigest()
            lines.append(f"{digest}  {name}")
        (ddir / "MANIFEST.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")
    def _write_spring_properties(self, root: Path) -> None:
        # Mirrors the real SecurityProperties: the indexed credential slots bind
        # through a nested POJO, so the fixture must carry that POJO's leaf
        # fields — the binding path is resolved to the leaf, not to `credentials`.
        props = root / "backend/src/main/java/com/example/SecurityProperties.java"
        props.parent.mkdir(parents=True, exist_ok=True)
        props.write_text(
            '@ConfigurationProperties(prefix = "groundcontrol.security", '
            "ignoreUnknownFields = false)\n"
            "public class SecurityProperties {\n"
            "    private boolean enabled = true;\n"
            "    private List<ApiCredential> credentials = new ArrayList<>();\n"
            "    private List<String> ipAllowlist = new ArrayList<>();\n"
            "\n"
            "    public static class ApiCredential {\n"
            "        private String principalName;\n"
            "        private String token;\n"
            "        private Role role;\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
    def _copy_enum_sources(self, root: Path) -> None:
        rels = [FRONTEND_API_TYPES_PATH, MCP_LIB_PATH, *[c.java_path for c in ENUM_CONTRACT_INVENTORY]]
        for rel in rels:
            src = REPO_ROOT / rel
            dst = root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
    def _write_minimal_ontology_fixture(self, root: Path) -> None:
        java_root = root / "backend" / "src" / "main" / "java" / "example"
        java_root.mkdir(parents=True)
        (java_root / "GraphEntityType.java").write_text(
            "package example; public enum GraphEntityType { REQUIREMENT }\n",
            encoding="utf-8",
        )
        (java_root / "RequirementGraphProjectionContributor.java").write_text(
            """package example;
public class RequirementGraphProjectionContributor implements GraphProjectionContributor {
    Object edge() { return new GraphEdge("id", "RELATES", null, null, null, null, null); }
}
""",
            encoding="utf-8",
        )
        ontology = root / "contracts" / "ontology"
        ontology.mkdir(parents=True)
        (ontology / "gc-concept-families-v1.json").write_text(
            json.dumps(
                {
                    "schema_version": "gc-concept-families/v1",
                    "owners": ["ground-control"],
                    "families": {
                        "requirements-and-traceability": {
                            "title": "Requirements and traceability",
                            "description": "Requirement identity and relationships.",
                            "provenance": "native",
                            "owner": "ground-control",
                            "extension_scope": "Requirement and traceability concepts.",
                            "relation_rules": ["Relations preserve declared direction."],
                            "non_ambiguity_constraints": ["Requirements are not evidence artifacts."],
                        }
                    },
                }
            ),
            encoding="utf-8",
        )
        (ontology / "gc-controlled-vocabularies-v1.json").write_text(
            json.dumps(
                {
                    "schema_version": "gc-controlled-vocabularies/v1",
                    "terms": {
                        "node.requirement": {
                            "kind": "classification",
                            "title": "Requirement",
                            "description": "A governed requirement.",
                            "family": "requirements-and-traceability",
                            "owner": "ground-control",
                        },
                        "edge.relates": {
                            "kind": "edge",
                            "title": "Relates",
                            "description": "A directed relation.",
                            "family": "requirements-and-traceability",
                            "owner": "ground-control",
                            "direction": "source-to-target",
                            "source_roles": ["source"],
                            "target_roles": ["target"],
                        },
                    },
                }
            ),
            encoding="utf-8",
        )
        (ontology / "gc-artifact-bindings-v1.json").write_text(
            json.dumps(
                {
                    "schema_version": "gc-artifact-bindings/v1",
                    "surfaces": [
                        {
                            "id": "example.GraphEntityType",
                            "kind": "java-enum",
                            "path": "backend/src/main/java/example/GraphEntityType.java",
                            "bindings": [{"local_value": "REQUIREMENT", "term": "node.requirement"}],
                        },
                        {
                            "id": "example.RequirementGraphProjectionContributor",
                            "kind": "graph-contributor",
                            "path": "backend/src/main/java/example/RequirementGraphProjectionContributor.java",
                            "bindings": [{"local_value": "RELATES", "term": "edge.relates"}],
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )
    def _read_ontology_fixture_json(self, root: Path, filename: str) -> tuple[Path, dict]:
        path = root / "contracts" / "ontology" / filename
        return path, json.loads(path.read_text(encoding="utf-8"))
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
