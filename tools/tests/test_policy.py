import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.policy.checks import (
    DEFERRAL_CASES_PATH,
    ENUM_CONTRACT_INVENTORY,
    FRONTEND_API_TYPES_PATH,
    MCP_LIB_PATH,
    REPO_ROOT,
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


class PolicyChecksTest(unittest.TestCase):

    def test_adr_guard_requires_workflow_docs(self):
        violations = run_adr_guard([".claude/skills/implement/SKILL.md"])
        self.assertTrue(any(item.code == "workflow-guardrail-sync" for item in violations))

    def test_adr_guard_fires_on_canonical_implement_skill_path(self):
        violations = run_adr_guard(["skills/implement/SKILL.md"])
        self.assertTrue(any(item.code == "workflow-guardrail-sync" for item in violations))

    def test_adr_guard_fires_on_delegated_implement_step_and_principles(self):
        for path in (
            "skills/implement/steps/step-04.4-tdd.md",
            "skills/implement/_development-principles.md",
        ):
            with self.subTest(path=path):
                violations = run_adr_guard([path])
                self.assertTrue(any(item.code == "workflow-guardrail-sync" for item in violations))

    def test_adr_guard_fires_on_measurement_emitter_surfaces(self):
        # ADR-090 defines one measurement model shared by every process-telemetry
        # emitter. An emitter that changes its record shape without updating the
        # model re-creates the divergence the ADR exists to close, so each emitter
        # surface must require the ADR in the same diff (issue #1304).
        for path in (
            "backend/src/main/java/com/keplerops/groundcontrol/domain/workflowtelemetry/WorkflowPhaseEvent.java",
            "backend/src/main/java/com/keplerops/groundcontrol/api/workflowtelemetry/PhaseEventResponse.java",
            "backend/src/main/java/com/keplerops/groundcontrol/domain/mcptelemetry/McpToolEvent.java",
            "backend/src/main/java/com/keplerops/groundcontrol/api/mcptelemetry/McpTelemetryController.java",
            "mcp/ground-control/gc-workflow-run-ingest.js",
            "mcp/ground-control/gc-workflow-run.js",
            "mcp/ground-control/telemetry.js",
        ):
            with self.subTest(path=path):
                violations = run_adr_guard([path])
                self.assertTrue(
                    any(item.code == "measurement-model-sync" for item in violations),
                    f"{path} must require an ADR-090 update in the same diff",
                )

    def test_adr_guard_measurement_rule_satisfied_when_adr_090_included(self):
        violations = run_adr_guard(
            [
                "mcp/ground-control/telemetry.js",
                "architecture/adrs/090-production-line-measurement-model.md",
            ]
        )
        self.assertFalse(
            any(item.code == "measurement-model-sync" for item in violations),
            "including ADR-090 must satisfy the measurement-model-sync rule",
        )

    def test_implement_execution_contract_is_structurally_complete(self):
        self.assertEqual(run_implement_execution_contract(root=REPO_ROOT), [])

    def test_implement_execution_contract_rejects_direct_pr_creation(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
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
            step9 = root / "skills/implement/steps/step-09-pr-body.md"
            step9.write_text(
                step9.read_text(encoding="utf-8") + "\n`gh pr create`\n",
                encoding="utf-8",
            )
            violations = run_implement_execution_contract(root=root)
            self.assertIn(
                "implement-direct-pr-create",
                {item.code for item in violations},
            )

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

    def test_implement_execution_contract_rejects_dropped_policy_command_token(self):
        # Negative path for the issue #1429 token requirements. Without this,
        # deleting the `cfg.workflow.policy_command` anchors from
        # run_implement_execution_contract would be invisible to the suite, and
        # the skill prose could silently drift back to a hardcoded command.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._implement_contract_root(tmp_dir)
            step6 = root / "skills/implement/steps/step-06-completion-gate.md"
            step6.write_text(
                step6.read_text(encoding="utf-8").replace(
                    "`cfg.workflow.policy_command`", "`make policy`"
                ),
                encoding="utf-8",
            )
            violations = run_implement_execution_contract(root=root)
            self.assertIn(
                "implement-verification-boundary-drift",
                {item.code for item in violations},
            )

    def test_implement_execution_contract_rejects_dropped_sync_policy_command_token(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._implement_contract_root(tmp_dir)
            step8_5 = root / "skills/implement/steps/step-08.5-sync-base.md"
            step8_5.write_text(
                step8_5.read_text(encoding="utf-8").replace(
                    "cfg.workflow.policy_command", "make policy"
                ),
                encoding="utf-8",
            )
            violations = run_implement_execution_contract(root=root)
            self.assertIn(
                "implement-pre-pr-sync-contract",
                {item.code for item in violations},
            )

    def test_implement_execution_contract_rejects_dropped_precommit_command_token(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._implement_contract_root(tmp_dir)
            step7 = root / "skills/implement/steps/step-07-stage-precommit.md"
            step7.write_text(
                step7.read_text(encoding="utf-8").replace(
                    "cfg.workflow.precommit_command", "pre-commit run --all-files"
                ),
                encoding="utf-8",
            )
            violations = run_implement_execution_contract(root=root)
            self.assertIn(
                "implement-verification-boundary-drift",
                {item.code for item in violations},
            )

    def test_implement_execution_contract_rejects_dropped_review_batching_token(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._implement_contract_root(tmp_dir)
            rules = root / "skills/implement/steps/_review-loop-rules.md"
            rules.write_text(
                rules.read_text(encoding="utf-8").replace(
                    "`cfg.workflow.policy_command` after every small fix",
                    "the policy gate after every small fix",
                ),
                encoding="utf-8",
            )
            violations = run_implement_execution_contract(root=root)
            self.assertIn(
                "implement-verification-boundary-drift",
                {item.code for item in violations},
            )

    def test_implement_verification_contract_is_proportionate_and_mandatory(self):
        principles = (
            REPO_ROOT / "skills/implement/_development-principles.md"
        ).read_text(encoding="utf-8")
        review_rules = (
            REPO_ROOT / "skills/implement/steps/_review-loop-rules.md"
        ).read_text(encoding="utf-8")
        step5 = (
            REPO_ROOT / "skills/implement/steps/step-05-quality-assurance.md"
        ).read_text(encoding="utf-8")
        step7 = (
            REPO_ROOT / "skills/implement/steps/step-07-stage-precommit.md"
        ).read_text(encoding="utf-8")
        self.assertIn("narrowest tests", principles)
        self.assertIn("repository-wide completion and policy suites once", principles)
        self.assertIn("never waives the mandatory", review_rules)
        self.assertIn("Do not run `pre-commit` here", step5)
        self.assertIn("single mandatory pre-publish", step7)

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

    def test_gc_render_pr_body_output_passes_check_pr_body(self):
        # Compose contract (ADR-036): the JS renderer in
        # `mcp/ground-control/lib.js::buildPrBody` produces a PR body that
        # MUST pass `check_pr_body`. Codex cycle 1 (F3) flagged the previous
        # version: a copied Python string fixture means a JS renderer change
        # cannot break this test. Fixed by invoking the actual renderer via
        # `tools/render_pr_body_fixture.mjs` and feeding stdout through the
        # Python policy predicate. Drift now breaks the test.
        body = self._render_pr_body_via_js({
            "issueNumber": 868,
            "changeClass": "source",
            "requirementUids": ["GC-O007", "GC-O009"],
            "adrRefs": ["ADR-036", "ADR-021 (amended)"],
            "summary": "Per-step routing + tool surfaces + telemetry.",
            "changes": ["Added gc_post_decision_record"],
            "traceability": {
                "implements": ["GC-O007 ← skills/implement/SKILL.md"],
                "tests": ["GC-O007 ← mcp/ground-control/lib.test.js"],
            },
            "changelogFragment": "changelog.d/868.changed.md",
        })
        violations = check_pr_body(body)
        codes = [v.code for v in violations]
        self.assertEqual(
            violations,
            [],
            f"buildPrBody (source) output rejected by check_pr_body: {codes}",
        )

    def test_gc_render_pr_body_doc_only_output_passes_check_pr_body(self):
        # Same compose test but for change_class='doc-only': integration tests
        # marked N/A, changelog fragment marked N/A. Per F3 (codex cycle 1),
        # this invokes the actual renderer rather than a copied fixture, so
        # the compose contract holds even when the renderer changes.
        #
        # Per F1 (codex cycle 2), the renderer no longer fabricates a synthetic
        # `GC-O007` placeholder for requirement-free runs — honest traceability.
        # The PR-body policy gate (PR_REQUIREMENT_RE) still requires a UID-
        # shaped token anywhere in the body; doc-only runs satisfy it by
        # citing the ADR they document (this PR cites ADR-036). A doc-only
        # run with NEITHER a requirement nor an ADR ref is refused by
        # `runRenderPrBody`'s checkPrBodyShape gate (see lib.test.js).
        body = self._render_pr_body_via_js({
            "issueNumber": 999,
            "changeClass": "doc-only",
            "requirementUids": [],
            "adrRefs": ["ADR-036"],
            "summary": "Documentation update only.",
            "changes": ["Clarified workflow doc wording"],
            "traceability": {"implements": [], "tests": []},
        })
        violations = check_pr_body(body)
        codes = [v.code for v in violations]
        self.assertEqual(
            violations,
            [],
            f"buildPrBody (doc-only) output rejected by check_pr_body: {codes}",
        )

    def test_workflow_guardrail_sync_requires_adr_036(self):
        # ADR-036 amends ADR-021. workflow-guardrail-sync must keep ADR-036 in
        # the requireAll list so that future SKILL changes have to update both
        # ADR-021 (the original) AND ADR-036 (the routing/tools/telemetry
        # amendment). Pinned here so a future edit to the policy that drops
        # ADR-036 breaks this test.
        policy_path = REPO_ROOT / "architecture/policies/adr-policy.json"
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
        rule = None
        for pol in policy.get("policies", []):
            for r in pol.get("rules", []):
                if r.get("id") == "workflow-guardrail-sync":
                    rule = r
                    break
            if rule is not None:
                break
        self.assertIsNotNone(rule, "workflow-guardrail-sync rule must exist")
        self.assertIn(
            "architecture/adrs/036-per-step-routing-tool-surfaces-telemetry.md",
            rule.get("requireAll", []),
            "ADR-036 must be in workflow-guardrail-sync.requireAll",
        )







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

    @staticmethod
    def _write_file(root: Path, rel: str, content: str) -> str:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return rel

    def test_controller_webmvctest_no_false_positive_on_same_name_collision(self):
        """A controller and its real companion (resolved by FQCN) satisfy the check
        even when another package has a same-named controller + test (issue #1167)."""
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
            audit_trail_test = self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditTrailControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audit.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditTrailControllerTest {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audits.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditControllerTest {}\n",
            )
            violations = run_controller_contracts(
                [audit_controller, audit_trail_test],
                root=root,
            )
            codes = {item.code for item in violations}
            self.assertNotIn(
                "controller-webmvctest-update",
                codes,
                "real companion AuditTrailControllerTest must satisfy api/audit/AuditController",
            )

    def test_controller_webmvctest_update_still_fires_without_companion_change(self):
        """Changing a controller without touching its real companion still fails
        (no regression in the real signal) despite a same-named test in another package."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audit/AuditController.java",
                "package com.keplerops.groundcontrol.api.audit;\nclass AuditController {}\n",
            )
            audits_controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audits/AuditController.java",
                "package com.keplerops.groundcontrol.api.audits;\nclass AuditController {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditTrailControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audit.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditTrailControllerTest {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audits.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditControllerTest {}\n",
            )
            violations = run_controller_contracts([audits_controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-update", codes)

    def test_controller_webmvctest_missing_when_no_slice_anywhere(self):
        """A controller with no @WebMvcTest slice referencing it raises -missing."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                "package com.keplerops.groundcontrol.api.foo;\nclass FooController {}\n",
            )
            violations = run_controller_contracts([controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-missing", codes)

    def test_controller_webmvctest_annotation_when_stem_test_is_not_a_slice(self):
        """A same-stem <Controller>Test.java that is not a @WebMvcTest raises -annotation."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                "package com.keplerops.groundcontrol.api.foo;\nclass FooController {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\nclass FooControllerTest {}\n",
            )
            violations = run_controller_contracts([controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-annotation", codes)

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

    def test_check_pr_body_passes_for_well_formed_body(self):
        body = (
            "## Summary\n\nFix.\n"
            "## Requirement UIDs\n\n- GC-X001\n"
            "## ADR Impact\n\nADR-026 added.\n"
            "## Ground Control Checks\n\n"
            "- [x] Configured repository policy command passes\n"
            "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change\n"
            "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
            "## Traceability\n\n- IMPLEMENTS: foo\n- TESTS: bar\n"
        )
        self.assertEqual(check_pr_body(body), [])

    def test_check_pr_body_accepts_allocator_minted_short_uid(self):
        # RequirementUidAllocator.allocate() returns `${prefix}-${n}` unpadded,
        # so a project's first nine requirements carry a single-digit suffix.
        # A PR whose only requirement is APP-2 must satisfy pr-requirement-uid
        # (issue #1425).
        body = (
            "## Summary\n\nFix.\n"
            "## Requirement UIDs\n\n- APP-2\n"
            "## ADR Impact\n\nNo ADR required.\n"
            "## Ground Control Checks\n\n"
            "- [x] Configured repository policy command passes\n"
            "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change\n"
            "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
            "## Traceability\n\n- IMPLEMENTS: foo\n- TESTS: bar\n"
        )
        self.assertEqual(check_pr_body(body), [])

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

    def test_pr_body_gate_accepts_the_same_corpus_the_renderer_accepts(self):
        # Parity with mcp/ground-control/lib.js: a UID the renderer accepts must
        # satisfy this gate, otherwise a requirement that reconciles and reports
        # could never be rendered into the mandatory PR body (issue #1425).
        for uid in ["APP-2", "A-1", "GC-O007", "GC-O-007", "OBS-042", "GC-OOPS", "lowercase-001"]:
            body = self._body_with_uid_section(f"- {uid}")
            self.assertEqual(check_pr_body(body), [], msg=f"should accept {uid}")

    def test_pr_body_gate_rejects_prose_and_over_bound_values(self):
        for section in ["- (no real UID here)", "- " + "A" * 49 + "-1", "", "- "]:
            body = self._body_with_uid_section(section)
            codes = {item.code for item in check_pr_body(body)}
            self.assertIn("pr-requirement-uid", codes, msg=f"should reject section {section!r}")

    def test_pr_body_gate_accepts_the_explicit_requirement_free_marker(self):
        body = self._body_with_uid_section("- (none — requirement-free change)")
        self.assertEqual(check_pr_body(body), [])

    def test_pr_body_gate_is_scoped_to_the_requirement_uids_section(self):
        # An ADR reference elsewhere must not satisfy the requirement-UID gate.
        body = self._body_with_uid_section("- (no real UID here)").replace(
            "No ADR required.", "ADR-036 updated."
        )
        codes = {item.code for item in check_pr_body(body)}
        self.assertIn("pr-requirement-uid", codes)

    def test_ci_strictness_contract_passes_on_repo(self):
        violations = run_ci_strictness_contract(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")

    def test_ci_strictness_contract_rejects_non_strict_branch_protection(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            for rel in [
                ".github/workflows/ci.yml",
                ".pre-commit-config.yaml",
                "tools/sonar/assert_no_new_issues.py",
                ".github/branch-protection-baseline.json",
            ]:
                src = REPO_ROOT / rel
                dst = root / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

            baseline_path = root / ".github/branch-protection-baseline.json"
            baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
            baseline["branches"]["dev"]["required_status_checks"]["strict"] = False
            baseline["branches"]["dev"]["required_status_checks"]["contexts"].remove("policy")
            baseline_path.write_text(json.dumps(baseline), encoding="utf-8")

            violations = run_ci_strictness_contract(root=root)
            codes = {item.code for item in violations}
            self.assertIn("ci-strictness-branch-protection-strict", codes)
            self.assertIn("ci-strictness-branch-protection-contexts", codes)


    @staticmethod
    def _copy_mutation_gate_contract_fixture(root: Path) -> None:
        for rel in [
            ".github/workflows/ci.yml",
            ".github/branch-protection-baseline.json",
            "architecture/registry/mutation-boundaries.json",
            "tools/mutation/run_boundary_mutation.py",
        ]:
            src = REPO_ROOT / rel
            dst = root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")



    def test_workflow_routing_contract_passes_on_repo(self):
        violations = run_workflow_routing_contract(root=REPO_ROOT)
        self.assertEqual(
            violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
        )

    def test_workflow_routing_contract_rejects_retired_executor_fields(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            (root / ".ground-control.yaml").write_text(
                "routing:\n"
                "  enabled: true\n"
                "  stages:\n"
                "    base_sync:\n"
                "      tier: low\n"
                "      agent: subagent\n",
                encoding="utf-8",
            )
            violations = run_workflow_routing_contract(root=root)
            codes = {item.code for item in violations}
            self.assertIn("workflow-routing-execution-control-retired", codes)

    def test_workflow_routing_contract_flags_missing_base_sync_stage(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            (root / ".ground-control.yaml").write_text(
                "routing:\n"
                "  enabled: true\n"
                "  stages:\n"
                "    codebase_assessment:\n"
                "      tier: medium\n",
                encoding="utf-8",
            )
            violations = run_workflow_routing_contract(root=root)
            codes = {item.code for item in violations}
            self.assertIn("workflow-routing-base-sync-stage-missing", codes)

    def test_workflow_routing_contract_reports_missing_config(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            violations = run_workflow_routing_contract(root=Path(tmp_dir))
            codes = {item.code for item in violations}
            self.assertIn("workflow-routing-config-missing", codes)

    def test_contract_surface_check_passes_on_repo(self):
        violations = run_contract_surface_check(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")

    def test_contract_invariant_enforcement_rejects_missing_enforcement_file(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            schema_dir = root / "contracts" / "schemas" / "records"
            schema_dir.mkdir(parents=True)
            (schema_dir / "sample.schema.json").write_text(
                json.dumps(
                    {
                        "$id": "gc.test.sample.v1",
                        "type": "object",
                        "x-ground-control-invariants": [
                            {"id": "gc.test.sample.required", "enforcedBy": ["missing/Test.java::testRequired"]}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            violations = run_contract_invariant_enforcement_check(root=root)
        self.assertTrue(any(v.code == "contract-invariant-enforcement-missing-file" for v in violations))

    def test_contract_invariant_enforcement_rejects_file_only_target(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            schema_dir = root / "contracts" / "schemas" / "records"
            schema_dir.mkdir(parents=True)
            test_file = root / "tools" / "tests" / "test_policy.py"
            test_file.parent.mkdir(parents=True)
            test_file.write_text("def test_sample(): pass\n", encoding="utf-8")
            (schema_dir / "sample.schema.json").write_text(
                json.dumps(
                    {
                        "$id": "gc.test.sample.v1",
                        "type": "object",
                        "x-ground-control-invariants": [
                            {"id": "gc.test.sample.required", "enforcedBy": ["tools/tests/test_policy.py"]}
                        ],
                    }
                ),
                encoding="utf-8",
            )

            violations = run_contract_invariant_enforcement_check(root=root)

        self.assertTrue(any(v.code == "contract-invariant-enforcement-anchor-missing" for v in violations))

    def test_contract_invariant_enforcement_rejects_missing_anchor(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            schema_dir = root / "contracts" / "schemas" / "records"
            schema_dir.mkdir(parents=True)
            test_file = root / "tools" / "tests" / "test_policy.py"
            test_file.parent.mkdir(parents=True)
            test_file.write_text("def test_sample(): pass\n", encoding="utf-8")
            (schema_dir / "sample.schema.json").write_text(
                json.dumps(
                    {
                        "$id": "gc.test.sample.v1",
                        "type": "object",
                        "x-ground-control-invariants": [
                            {
                                "id": "gc.test.sample.required",
                                "enforcedBy": ["tools/tests/test_policy.py::test_missing"],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            violations = run_contract_invariant_enforcement_check(root=root)

        self.assertTrue(any(v.code == "contract-invariant-enforcement-anchor-missing-file" for v in violations))

    def test_implement_final_report_schema_requires_review_evidence_invariant(self):
        schema = json.loads(
            (REPO_ROOT / "contracts/schemas/records/implement-final-report.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )

        invariant_ids = {entry["id"] for entry in schema["x-ground-control-invariants"]}

        self.assertIn("gc.implement.final-report.review-evidence-present", invariant_ids)
        self.assertIn("reviews", schema["required"])
        self.assertEqual(schema["properties"]["reviews"]["type"], "array")
        self.assertEqual(schema["properties"]["reviews"]["items"]["minLength"], 1)

    def test_workflow_run_record_schema_has_closed_state_vocabulary_invariant(self):
        schema = json.loads(
            (REPO_ROOT / "contracts/schemas/workflow/workflow-run-record.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )

        invariant_ids = {entry["id"] for entry in schema["x-ground-control-invariants"]}

        self.assertIn("gc.workflow.run-record.phase-state-closed-set", invariant_ids)
        self.assertEqual(
            schema["properties"]["finalState"]["enum"],
            ["RUNNING", "READY_FOR_REVIEW", "MERGED", "CLOSED", "ESCALATED", "ABANDONED", "SUPERSEDED"],
        )
        self.assertEqual(schema["properties"]["provenance"]["enum"], ["ISSUE_THREAD", "TEMPORAL_VISIBILITY", "MANUAL_IMPORT"])

    def test_authz_matrix_sync_detects_missing_contract_row(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            matrix = root / "contracts" / "authz" / "path-matrix.yaml"
            matrix.parent.mkdir(parents=True)
            matrix.write_text(
                "rules:\n"
                '  - id: admin\n'
                '    method: "*"\n'
                '    path: "/api/v1/admin/**"\n'
                '    access: "ROLE_ADMIN"\n',
                encoding="utf-8",
            )
            java = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "com"
                / "keplerops"
                / "groundcontrol"
                / "shared"
                / "security"
                / "ApiPathMatrix.java"
            )
            java.parent.mkdir(parents=True)
            java.write_text(
                'final class ApiPathMatrix { private static final String ROLE_ADMIN = "ADMIN"; '
                'void apply(Object auth) { auth.requestMatchers("/api/v1/admin/**").hasRole(ROLE_ADMIN)'
                '.requestMatchers("/api/v1/pack-registry/**").hasRole(ROLE_ADMIN); }}',
                encoding="utf-8",
            )
            violations = run_authz_matrix_sync_check(root=root)
        self.assertTrue(any(v.code == "authz-matrix-drift" for v in violations))

    def test_deploy_compose_credential_passthrough_passes_on_committed_file(self):
        # The committed deploy/docker/docker-compose.prod.yml must enumerate the
        # ADR-026 credential and IP-allowlist env vars on the backend service so
        # an operator who fills .env still has them propagate into the container.
        # #828 was triggered by exactly this gap. Run the check against the live
        # repo file as the post-condition assertion.
        violations = run_deploy_compose_credential_passthrough(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")

    def test_deploy_compose_credential_passthrough_fires_when_keys_missing(self):
        # If the backend service's environment block stops enumerating the
        # ADR-026 keys, the check must fail loudly so the regression is caught
        # in `make policy` before it ships.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            compose_path = root / "deploy/docker/docker-compose.prod.yml"
            compose_path.parent.mkdir(parents=True, exist_ok=True)
            compose_path.write_text(
                "services:\n"
                "  backend:\n"
                "    image: ghcr.io/autarchy-ai/ground-control:latest\n"
                "    environment:\n"
                "      - GC_DATABASE_URL=${GC_DATABASE_URL}\n",
                encoding="utf-8",
            )
            violations = run_deploy_compose_credential_passthrough(root=root)
            codes = {item.code for item in violations}
            self.assertIn("deploy-compose-adr026-passthrough", codes)
            # The violation must enumerate the missing keys so the operator's
            # fix is unambiguous.
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("GC_SECURITY_ENABLED", details)
            self.assertIn("GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN", details)
            self.assertIn("GROUNDCONTROL_SECURITY_IP_ALLOWLIST_0", details)

    def test_deploy_compose_credential_passthrough_requires_all_five_slots(self):
        # The runbook tells operators they have five reserved slots; the policy
        # gate must enforce all five so a future diff stripping slot 4 fails
        # `make policy` rather than silently regressing the documented headroom.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            compose_path = root / "deploy/docker/docker-compose.prod.yml"
            compose_path.parent.mkdir(parents=True, exist_ok=True)
            compose_path.write_text(
                "services:\n"
                "  backend:\n"
                "    environment:\n"
                "      - GC_SECURITY_ENABLED=${GC_SECURITY_ENABLED:-true}\n"
                "      - GC_SECURITY_OPENAPI_PUBLIC=${GC_SECURITY_OPENAPI_PUBLIC:-false}\n"
                # Only slots 0..3 declared — slot 4 missing.
                + "".join(
                    f"      - GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_PRINCIPAL_NAME\n"
                    f"      - GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_TOKEN\n"
                    f"      - GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_ROLE\n"
                    for i in range(4)
                )
                + "".join(
                    f"      - GROUNDCONTROL_SECURITY_IP_ALLOWLIST_{i}\n"
                    for i in range(4)
                ),
                encoding="utf-8",
            )
            violations = run_deploy_compose_credential_passthrough(root=root)
            codes = {item.code for item in violations}
            self.assertIn("deploy-compose-adr026-passthrough", codes)
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("GROUNDCONTROL_SECURITY_CREDENTIALS_4_TOKEN", details)
            self.assertIn("GROUNDCONTROL_SECURITY_IP_ALLOWLIST_4", details)

    def test_deploy_compose_credential_passthrough_rejects_map_form_for_indexed_slots(self):
        # Map form with `${VAR:-}` defaults injects the variable into the
        # container as an empty string when the host variable is unset, which
        # SecurityProperties.validate() rejects (the failure mode that
        # codex flagged in #828 cycle 2). The policy gate must reject any
        # indexed slot in map form, even if the typed config keys remain in
        # map form (they have non-empty defaults so they are safe either way).
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            compose_path = root / "deploy/docker/docker-compose.prod.yml"
            compose_path.parent.mkdir(parents=True, exist_ok=True)
            body = "services:\n  backend:\n    environment:\n"
            body += "      GC_SECURITY_ENABLED: ${GC_SECURITY_ENABLED:-true}\n"
            body += "      GC_SECURITY_OPENAPI_PUBLIC: ${GC_SECURITY_OPENAPI_PUBLIC:-false}\n"
            # Indexed slots in map form — NOT acceptable.
            for i in range(5):
                body += f"      GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_PRINCIPAL_NAME: ${{GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_PRINCIPAL_NAME:-}}\n"
                body += f"      GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_TOKEN: ${{GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_TOKEN:-}}\n"
                body += f"      GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_ROLE: ${{GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_ROLE:-}}\n"
            for i in range(5):
                body += f"      GROUNDCONTROL_SECURITY_IP_ALLOWLIST_{i}: ${{GROUNDCONTROL_SECURITY_IP_ALLOWLIST_{i}:-}}\n"
            compose_path.write_text(body, encoding="utf-8")
            violations = run_deploy_compose_credential_passthrough(root=root)
            codes = {item.code for item in violations}
            self.assertIn("deploy-compose-adr026-inherit-only", codes)
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN", details)
            self.assertIn("GROUNDCONTROL_SECURITY_IP_ALLOWLIST_4", details)

    def test_deploy_compose_credential_passthrough_rejects_list_form_with_default(self):
        # `- KEY=${VAR:-}` is also unsafe: it injects empty string when the
        # host variable is unset. Only bare `- KEY` (inherit-only) form is
        # acceptable for the indexed slots.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            compose_path = root / "deploy/docker/docker-compose.prod.yml"
            compose_path.parent.mkdir(parents=True, exist_ok=True)
            body = "services:\n  backend:\n    environment:\n"
            body += "      - GC_SECURITY_ENABLED=${GC_SECURITY_ENABLED:-true}\n"
            body += "      - GC_SECURITY_OPENAPI_PUBLIC=${GC_SECURITY_OPENAPI_PUBLIC:-false}\n"
            for i in range(5):
                body += f"      - GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_PRINCIPAL_NAME=${{GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_PRINCIPAL_NAME:-}}\n"
                body += f"      - GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_TOKEN=${{GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_TOKEN:-}}\n"
                body += f"      - GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_ROLE=${{GROUNDCONTROL_SECURITY_CREDENTIALS_{i}_ROLE:-}}\n"
            for i in range(5):
                body += f"      - GROUNDCONTROL_SECURITY_IP_ALLOWLIST_{i}=${{GROUNDCONTROL_SECURITY_IP_ALLOWLIST_{i}:-}}\n"
            compose_path.write_text(body, encoding="utf-8")
            violations = run_deploy_compose_credential_passthrough(root=root)
            codes = {item.code for item in violations}
            self.assertIn("deploy-compose-adr026-inherit-only", codes)

    def test_deploy_compose_credential_passthrough_handles_missing_file(self):
        # If the canonical compose file disappears entirely the check must fail
        # rather than silently pass — the absence is itself a regression worth
        # surfacing in `make policy`.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            violations = run_deploy_compose_credential_passthrough(root=root)
            codes = {item.code for item in violations}
            self.assertIn("deploy-compose-missing", codes)

    # ------------------------------------------------------------------
    # GHCR namespace drift check (issue #953, GC-P022)
    # ------------------------------------------------------------------

    def test_ghcr_namespace_drift_passes_on_committed_files(self):
        # After #953 every inventoried deploy/CI/doc artifact must name the
        # single canonical GHCR namespace. Run the check against the live repo
        # as the post-condition assertion — a leftover keplerops/brad-edwards
        # ref is exactly the drift that silently froze red-dragon's deploy for
        # ~10 days.
        violations = run_ghcr_namespace_drift(root=REPO_ROOT)
        self.assertEqual(
            violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
        )

    def test_ghcr_namespace_drift_fires_on_noncanonical_namespace(self):
        # A non-canonical namespace in any inventoried file must fail loudly,
        # naming the file, line, and offending namespace so the fix is
        # unambiguous.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            env_path = root / "deploy/docker/.env.example"
            env_path.parent.mkdir(parents=True, exist_ok=True)
            env_path.write_text(
                "GC_IMAGE=ghcr.io/keplerops/ground-control:main\n",
                encoding="utf-8",
            )
            violations = run_ghcr_namespace_drift(root=root)
            codes = {item.code for item in violations}
            self.assertIn("ghcr-namespace-drift", codes)
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("keplerops", details)
            self.assertIn("deploy/docker/.env.example", details)

    def test_ghcr_namespace_drift_accepts_canonical_namespace(self):
        # The canonical namespace must not trip the gate, and files outside the
        # inventory (and absent files) are simply skipped.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            env_path = root / "deploy/docker/.env.example"
            env_path.parent.mkdir(parents=True, exist_ok=True)
            env_path.write_text(
                "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:main\n",
                encoding="utf-8",
            )
            violations = run_ghcr_namespace_drift(root=root)
            self.assertEqual(
                violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
            )

    # ------------------------------------------------------------------
    # Repository identity drift check (issue #1383, GC-P026)
    # ------------------------------------------------------------------

    def test_repo_identity_drift_passes_on_committed_files(self):
        # After #1383 every inventoried active surface must name the single
        # canonical owner. Run against the live repo as the post-condition
        # assertion — a leftover KeplerOps/Brad-Edwards slug in an active
        # config/workflow/script/doc is exactly the stale identity that routed
        # defaulted operations at an inaccessible repository.
        violations = run_repo_identity_drift(root=REPO_ROOT)
        self.assertEqual(
            violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
        )

    def test_repo_identity_drift_fires_on_noncanonical_owner(self):
        # A non-canonical owner in any inventoried file must fail loudly, naming
        # the file, line, and offending owner so the fix is unambiguous.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            cfg = root / ".ground-control.yaml"
            cfg.write_text("github_repo: KeplerOps/Ground-Control\n", encoding="utf-8")
            violations = run_repo_identity_drift(root=root)
            codes = {item.code for item in violations}
            self.assertIn("repo-identity-drift", codes)
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("KeplerOps", details)
            self.assertIn(".ground-control.yaml", details)

    def test_repo_identity_drift_fires_on_noncanonical_url(self):
        # The URL form (badges, clone URLs, raw-content URLs) is caught too.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            wf = root / ".github/workflows/pack-registry-sync.yml"
            wf.parent.mkdir(parents=True, exist_ok=True)
            wf.write_text(
                'RAW_BASE_URL="https://raw.githubusercontent.com/Brad-Edwards/Ground-Control/x"\n',
                encoding="utf-8",
            )
            violations = run_repo_identity_drift(root=root)
            self.assertIn("repo-identity-drift", {v.code for v in violations})
            details = " ".join(detail for v in violations for detail in v.details)
            self.assertIn("Brad-Edwards", details)

    def test_repo_identity_drift_accepts_canonical_owner(self):
        # The canonical owner must not trip the gate, and files outside the
        # inventory (and absent files) are simply skipped.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            cfg = root / ".ground-control.yaml"
            cfg.write_text("github_repo: autarchy-ai/Ground-Control\n", encoding="utf-8")
            violations = run_repo_identity_drift(root=root)
            self.assertEqual(
                violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}"
            )

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

    # ------------------------------------------------------------------
    # Deploy artifact consistency check (issue #855, GC-P023)
    # ------------------------------------------------------------------

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

    def _rewrite_manifest(self, root: Path) -> None:
        ddir = root / "deploy/docker"
        lines = []
        for name in ("deploy.sh", "docker-compose.prod.yml", "validate-env.sh", "env.schema"):
            digest = hashlib.sha256((ddir / name).read_bytes()).hexdigest()
            lines.append(f"{digest}  {name}")
        (ddir / "MANIFEST.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # ------------------------------------------------------------------
    # Env-template orphan-key check (issue #1384, GC-P023)
    # ------------------------------------------------------------------

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

    def test_env_template_orphan_key_accepts_node_process_env_read(self):
        # The MCP client reads its bearer token from the repo-root .env via
        # process.env; that is a real consumer of a key no compose forwards.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            mcp = root / "mcp/ground-control/index.js"
            mcp.parent.mkdir(parents=True, exist_ok=True)
            mcp.write_text(
                "const token = process.env.GROUND_CONTROL_API_TOKEN;\n", encoding="utf-8"
            )
            (root / ".env.example").write_text(
                "GROUND_CONTROL_API_TOKEN=x\n", encoding="utf-8"
            )
            codes = {v.code for v in run_deploy_artifact_consistency(root=root)}
            self.assertNotIn("deploy-env-template-orphan-key", codes)

    def test_env_template_orphan_key_ignores_docs_and_tests_as_consumers(self):
        # History is not an input. A key mentioned only in a doc, a test, or a
        # superseded ADR has no runtime consumer and must still be flagged.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_valid_deploy_tree(root)
            adr = root / "architecture/adrs/028-retired.md"
            adr.parent.mkdir(parents=True, exist_ok=True)
            adr.write_text("Superseded. Once read TEMPORAL_NAMESPACE.\n", encoding="utf-8")
            env_example = root / "deploy/docker/.env.example"
            env_example.write_text(
                env_example.read_text(encoding="utf-8") + "TEMPORAL_NAMESPACE=ground-control\n",
                encoding="utf-8",
            )
            violations = run_deploy_artifact_consistency(root=root)
            codes = {v.code for v in violations}
            self.assertIn("deploy-env-template-orphan-key", codes)
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("TEMPORAL_NAMESPACE", details)

    # ------------------------------------------------------------------
    # Enum contract check (issue #433)
    # ------------------------------------------------------------------

    def test_parse_java_enum_constants(self):
        text = (
            "package com.keplerops.groundcontrol.domain.requirements.state;\n\n"
            "public enum ArtifactType {\n"
            "    GITHUB_ISSUE,\n"
            "    PULL_REQUEST, // a pull request\n"
            "    CODE_FILE,\n"
            "}\n"
        )
        self.assertEqual(
            parse_java_enum_constants(text),
            ["GITHUB_ISSUE", "PULL_REQUEST", "CODE_FILE"],
        )
        # No enum declaration -> empty list (caller treats as parse error).
        self.assertEqual(parse_java_enum_constants("class Foo {}"), [])

    def test_parse_java_enum_constants_with_methods_and_args(self):
        # An enum with a `;`-terminated constant list followed by methods (the
        # shape of `Status`): the parser must stop at the `;`, not wander into
        # the method bodies and pick up case labels.
        text = (
            "public enum Status {\n"
            "    DRAFT,\n"
            "    ACTIVE,\n"
            "    DEPRECATED,\n"
            "    ARCHIVED;\n\n"
            "    public Set<Status> validTargets() {\n"
            "        return switch (this) {\n"
            "            case DRAFT -> Set.of(ACTIVE);\n"
            "            case ACTIVE -> Set.of(DEPRECATED, ARCHIVED);\n"
            "            default -> Set.of();\n"
            "        };\n"
            "    }\n"
            "}\n"
        )
        self.assertEqual(
            parse_java_enum_constants(text),
            ["DRAFT", "ACTIVE", "DEPRECATED", "ARCHIVED"],
        )
        # Constructor-argument groups are stripped.
        self.assertEqual(
            parse_java_enum_constants('enum E { A("a"), B("b"); E(String s) {} }'),
            ["A", "B"],
        )
        # A constant that exists only inside a comment is not counted.
        self.assertEqual(
            parse_java_enum_constants("enum E {\n    A,\n    // REMOVED_VALUE,\n    B\n}"),
            ["A", "B"],
        )

    def test_parse_const_string_array_ts_and_js(self):
        ts = (
            'export type ArtifactType = "GITHUB_ISSUE" | "CODE_FILE";\n'
            "export const ARTIFACT_TYPES: ArtifactType[] = [\n"
            '  "GITHUB_ISSUE",\n'
            '  "CODE_FILE",\n'
            "];\n"
            "export const OTHER_TYPES: string[] = [];\n"
        )
        self.assertEqual(
            parse_const_string_array(ts, "ARTIFACT_TYPES"),
            ["GITHUB_ISSUE", "CODE_FILE"],
        )
        self.assertEqual(parse_const_string_array(ts, "OTHER_TYPES"), [])
        self.assertIsNone(parse_const_string_array(ts, "NOPE_TYPES"))
        # The lookup must not be fooled by a longer name with the same prefix.
        js = 'export const LINK_TYPES = ["IMPLEMENTS", "TESTS"];\n'
        self.assertEqual(parse_const_string_array(js, "LINK_TYPES"), ["IMPLEMENTS", "TESTS"])
        self.assertIsNone(parse_const_string_array(js, "LINK"))

    def test_parse_const_string_array_ignores_comments(self):
        # A commented-out element — or a `]` inside a comment — must not be
        # counted: a mirror cannot pass the contract check by commenting a value
        # out instead of removing it.
        ts = (
            "export const ARTIFACT_TYPES: ArtifactType[] = [\n"
            '  "GITHUB_ISSUE",\n'
            '  // "PULL_REQUEST",   see [issue #1]\n'
            "  /* \"RISK_SCENARIO\", */\n"
            '  "CODE_FILE",\n'
            "];\n"
        )
        self.assertEqual(
            parse_const_string_array(ts, "ARTIFACT_TYPES"),
            ["GITHUB_ISSUE", "CODE_FILE"],
        )

    def test_parse_ts_union_literals(self):
        ts = (
            "export type RelationType =\n"
            '  | "DEPENDS_ON"\n'
            '  | "PARENT"\n'
            '  | "RELATED";\n'
            "export interface RelationRequest { relationType: RelationType; }\n"
        )
        self.assertEqual(
            parse_ts_union_literals(ts, "RelationType"),
            {"DEPENDS_ON", "PARENT", "RELATED"},
        )
        self.assertIsNone(parse_ts_union_literals(ts, "MissingType"))

    def test_parse_ts_union_literals_ignores_comments(self):
        ts = (
            "export type SyncStatus =\n"
            '  | "SYNCED"\n'
            '  // | "NOT_SYNCED"  legacy; the backend has STALE/BROKEN\n'
            '  | "STALE"\n'
            '  | "BROKEN";\n'
        )
        self.assertEqual(
            parse_ts_union_literals(ts, "SyncStatus"),
            {"SYNCED", "STALE", "BROKEN"},
        )

    def test_enum_contract_inventory_shape(self):
        # ThreatEventKind, ThreatSourceRelevance, NistLikelihoodBand,
        # NistImpactBand, NormalizedConcept, CrosswalkVocabularySurface, and
        # MethodologyFamily were retired with the composed GRC product
        # surface (ADR-089, issue #1346); their backend enums are deleted, so
        # they must not remain in the inventory. GraphEntityType joins the
        # inventory under ADR-034/#1308 so generated graph UI mirrors cannot
        # drift. VerificationStatus and
        # AssuranceLevel are unaffected (domain/verification/state, not part
        # of the retired GRC surface) and stay.
        labels = {c.label for c in ENUM_CONTRACT_INVENTORY}
        self.assertEqual(
            labels,
            {
                "GraphEntityType",
                "RequirementType",
                "RelationType",
                "ArtifactType",
                "LinkType",
                "Status",
                "Priority",
                "SyncStatus",
                "ChangeCategory",
                "AuditType",
                "AuditStatus",
                "VerificationStatus",
                "AssuranceLevel",
            },
        )
        for contract in ENUM_CONTRACT_INVENTORY:
            self.assertTrue((REPO_ROOT / contract.java_path).exists(), contract.java_path)
            self.assertTrue(contract.ts_union)

    def test_enum_contract_check_passes_on_repo(self):
        # Post-condition assertion against the live repo: backend Java enums,
        # frontend api.ts constants/unions, and MCP lib.js constants must all
        # agree. Any drift fails `make policy` (the `policy` CI job runs
        # `bin/policy` on every PR).
        violations = run_enum_contract_check(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")

    def _copy_enum_sources(self, root: Path) -> None:
        rels = [FRONTEND_API_TYPES_PATH, MCP_LIB_PATH, *[c.java_path for c in ENUM_CONTRACT_INVENTORY]]
        for rel in rels:
            src = REPO_ROOT / rel
            dst = root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

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

    def test_ontology_binding_check_covers_contract_and_vocabulary_validation_branches(self):
        cases = (
            ("missing-contract", "ontology-contract-missing"),
            ("non-object-contract", "ontology-contract-shape-invalid"),
            ("unsupported-version", "ontology-contract-version-invalid"),
            ("unknown-owner", "ontology-owner-invalid"),
            ("invalid-family", "ontology-family-invalid"),
            ("invalid-provenance", "ontology-provenance-invalid"),
            ("missing-native-rules", "ontology-native-family-rules-missing"),
            ("invalid-term", "ontology-term-invalid"),
            ("invalid-term-kind", "ontology-term-kind-invalid"),
            ("unknown-family", "ontology-family-reference-missing"),
            ("invalid-edge-semantics", "ontology-edge-semantics-invalid"),
        )
        for case, expected_code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp_dir:
                root = Path(tmp_dir)
                self._write_minimal_ontology_fixture(root)
                families_path, families = self._read_ontology_fixture_json(
                    root, "gc-concept-families-v1.json"
                )
                terms_path, terms = self._read_ontology_fixture_json(
                    root, "gc-controlled-vocabularies-v1.json"
                )
                if case == "missing-contract":
                    families_path.unlink()
                elif case == "non-object-contract":
                    families_path.write_text("[]", encoding="utf-8")
                elif case == "unsupported-version":
                    families["schema_version"] = "gc-concept-families/v2"
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "unknown-owner":
                    families["owners"] = ["unknown-owner"]
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "invalid-family":
                    family = families["families"].pop("requirements-and-traceability")
                    families["families"][""] = family
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "invalid-provenance":
                    families["families"]["requirements-and-traceability"]["provenance"] = "invented"
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "missing-native-rules":
                    families["families"]["requirements-and-traceability"].pop("extension_scope")
                    families_path.write_text(json.dumps(families), encoding="utf-8")
                elif case == "invalid-term":
                    terms["terms"]["node.requirement"]["title"] = ""
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                elif case == "invalid-term-kind":
                    terms["terms"]["node.requirement"]["kind"] = "unknown"
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                elif case == "unknown-family":
                    terms["terms"]["node.requirement"]["family"] = "missing-family"
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                elif case == "invalid-edge-semantics":
                    terms["terms"]["edge.relates"]["direction"] = "backward"
                    terms_path.write_text(json.dumps(terms), encoding="utf-8")
                violations = run_ontology_binding_check(root=root)
                self.assertIn(expected_code, {violation.code for violation in violations})

    def test_ontology_binding_check_covers_source_surface_and_binding_validation_branches(self):
        cases = (
            ("missing-source-root", "ontology-source-root-missing"),
            ("unparseable-enum", "ontology-source-parse-error"),
            ("duplicate-surface", "ontology-surface-duplicate"),
            ("invalid-surface", "ontology-surface-invalid"),
            ("surface-kind-mismatch", "ontology-surface-kind-mismatch"),
            ("surface-path-mismatch", "ontology-surface-path-mismatch"),
            ("missing-surface", "ontology-surface-missing"),
            ("stale-surface", "ontology-surface-stale"),
            ("invalid-binding", "ontology-binding-invalid"),
            ("binding-kind-mismatch", "ontology-binding-kind-mismatch"),
            ("invalid-enum-source-shape", "ontology-edge-enum-source-invalid"),
            ("stale-enum-source", "ontology-edge-enum-source-stale"),
        )
        for case, expected_code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp_dir:
                root = Path(tmp_dir)
                self._write_minimal_ontology_fixture(root)
                bindings_path, bindings = self._read_ontology_fixture_json(
                    root, "gc-artifact-bindings-v1.json"
                )
                java_root = root / "backend" / "src" / "main" / "java"
                graph_enum = java_root / "example" / "GraphEntityType.java"
                if case == "missing-source-root":
                    shutil.rmtree(java_root)
                elif case == "unparseable-enum":
                    graph_enum.write_text(
                        "package example; public enum GraphEntityType {}\n", encoding="utf-8"
                    )
                elif case == "duplicate-surface":
                    bindings["surfaces"].append(dict(bindings["surfaces"][0]))
                elif case == "invalid-surface":
                    bindings["surfaces"][0]["bindings"] = None
                elif case == "surface-kind-mismatch":
                    bindings["surfaces"][0]["kind"] = "graph-contributor"
                elif case == "surface-path-mismatch":
                    bindings["surfaces"][0]["path"] = bindings["surfaces"][1]["path"]
                elif case == "missing-surface":
                    bindings["surfaces"].pop()
                elif case == "stale-surface":
                    bindings["surfaces"].append(
                        {
                            "id": "example.VanishedGraphProjectionContributor",
                            "kind": "graph-contributor",
                            "path": "backend/src/main/java/example/VanishedGraphProjectionContributor.java",
                            "bindings": [],
                        }
                    )
                elif case == "invalid-binding":
                    bindings["surfaces"][0]["bindings"] = [None]
                elif case == "binding-kind-mismatch":
                    bindings["surfaces"][0]["bindings"][0]["term"] = "edge.relates"
                elif case == "invalid-enum-source-shape":
                    bindings["surfaces"][1]["edge_enum_sources"] = []
                elif case == "stale-enum-source":
                    bindings["surfaces"][1]["edge_enum_sources"] = {
                        "getLinkType": "example.GraphEntityType"
                    }
                if case not in {"missing-source-root", "unparseable-enum"}:
                    bindings_path.write_text(json.dumps(bindings), encoding="utf-8")
                violations = run_ontology_binding_check(root=root)
                self.assertIn(expected_code, {violation.code for violation in violations})

    def test_ontology_binding_check_reports_unreadable_discovered_source(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            unreadable = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            original_read_text = Path.read_text

            def read_text_or_fail(path: Path, *args, **kwargs):
                if path == unreadable:
                    raise OSError("fixture source is unreadable")
                return original_read_text(path, *args, **kwargs)

            with patch.object(Path, "read_text", read_text_or_fail):
                violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-source-unreadable", {violation.code for violation in violations})

    def test_ontology_binding_check_passes_minimal_complete_inventory(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            violations = run_ontology_binding_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])

    def test_ontology_binding_check_detects_unbound_source_value(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            path = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            path.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-binding-missing", {v.code for v in violations})

    def test_ontology_binding_check_detects_binding_to_vanished_value(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            path = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["surfaces"][0]["bindings"].append(
                {"local_value": "VANISHED", "term": "node.requirement"}
            )
            path.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-binding-stale", {v.code for v in violations})

    def test_ontology_binding_check_fails_closed_on_malformed_json(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            path = root / "contracts" / "ontology" / "gc-concept-families-v1.json"
            path.write_text("{", encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contract-invalid-json", {v.code for v in violations})

    def test_ontology_binding_check_rejects_duplicate_json_keys(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            path = root / "contracts" / "ontology" / "gc-controlled-vocabularies-v1.json"
            payload = path.read_text(encoding="utf-8")
            duplicate = payload.replace(
                '"terms": {',
                '"terms": {"node.requirement": {"kind": "classification"},',
                1,
            )
            path.write_text(duplicate, encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contract-invalid-json", {v.code for v in violations})

    def test_ontology_binding_check_rejects_unsafe_surface_path_and_kind(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            path = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["surfaces"][0]["path"] = "../outside.java"
            payload["surfaces"][1]["kind"] = "shell-command"
            path.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        codes = {v.code for v in violations}
        self.assertIn("ontology-surface-path-invalid", codes)
        self.assertIn("ontology-surface-kind-invalid", codes)

    def test_ontology_binding_check_rejects_unresolved_contributor_edge_expression(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace('"RELATES"', "computeEdgeType()"),
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contributor-edge-unresolved", {v.code for v in violations})

    def test_ontology_binding_check_does_not_accept_unrelated_enum_forwarding(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                """package example;
public class RequirementGraphProjectionContributor implements GraphProjectionContributor {
    String unrelated(Link link) { return link.getLinkType().name(); }
    Object edge(String edgeType) {
        return new GraphEdge("id", edgeType, null, null, null, null, null);
    }
    Object use() { return edge(computeEdgeType()); }
}
""",
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contributor-edge-unresolved", {v.code for v in violations})

    def test_ontology_binding_check_rejects_unknown_name_expression(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace(
                    '"RELATES"', "computeRelation().name()"
                ),
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contributor-edge-unresolved", {v.code for v in violations})

    def test_ontology_binding_check_rejects_unsupported_contributor_declaration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            candidate = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "OddGraphProjectionContributor.java"
            )
            candidate.write_text(
                """package example;
public class OddGraphProjectionContributor extends ContributorBase {
    Object edge() { return new GraphEdge("id", "ODD", null, null, null, null, null); }
}
""",
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contributor-declaration-unresolved", {v.code for v in violations})

    def test_ontology_binding_check_parses_spaced_graph_edge_constructor(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace("new GraphEdge(", "new GraphEdge ("),
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])

    def test_ontology_binding_check_rejects_graph_edge_factory_form(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace("new GraphEdge(", "GraphEdge.of("),
                encoding="utf-8",
            )
            bindings = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(bindings.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            bindings.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-contributor-edge-unresolved", {v.code for v in violations})

    def test_ontology_binding_check_requires_discovered_enum_for_direct_name_expression(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace(
                    '"RELATES"', "link.getLinkType().name()"
                ),
                encoding="utf-8",
            )
            bindings = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(bindings.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            bindings.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-edge-enum-source-missing", {v.code for v in violations})

    def test_ontology_binding_check_requires_discovered_enum_for_forwarded_name_expression(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                """package example;
public class RequirementGraphProjectionContributor implements GraphProjectionContributor {
    Object edge(String edgeType) {
        return new GraphEdge("id", edgeType, null, null, null, null, null);
    }
    Object use(Link link) { return edge(link.getLinkType().name()); }
}
""",
                encoding="utf-8",
            )
            bindings = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(bindings.read_text(encoding="utf-8"))
            payload["surfaces"][1]["bindings"] = []
            bindings.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        self.assertIn("ontology-edge-enum-source-missing", {v.code for v in violations})

    def test_ontology_binding_check_supports_record_contributor_declaration(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace(
                    "public class RequirementGraphProjectionContributor",
                    "public record RequirementGraphProjectionContributor()",
                ),
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])

    def test_ontology_binding_check_identifies_contributor_after_helper_type(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            contributor = (
                root
                / "backend"
                / "src"
                / "main"
                / "java"
                / "example"
                / "RequirementGraphProjectionContributor.java"
            )
            contributor.write_text(
                contributor.read_text(encoding="utf-8").replace(
                    "public class RequirementGraphProjectionContributor",
                    "class Helper {}\npublic class RequirementGraphProjectionContributor",
                ),
                encoding="utf-8",
            )
            violations = run_ontology_binding_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])

    def test_ontology_binding_check_rejects_duplicate_and_unresolved_contract_references(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_minimal_ontology_fixture(root)
            path = root / "contracts" / "ontology" / "gc-artifact-bindings-v1.json"
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["surfaces"][0]["bindings"].append(
                {"local_value": "REQUIREMENT", "term": "node.missing"}
            )
            path.write_text(json.dumps(payload), encoding="utf-8")
            violations = run_ontology_binding_check(root=root)
        codes = {v.code for v in violations}
        self.assertIn("ontology-binding-duplicate", codes)
        self.assertIn("ontology-term-reference-missing", codes)

    def test_ontology_binding_check_passes_on_repo(self):
        violations = run_ontology_binding_check(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=f"unexpected violations: {[v.render() for v in violations]}")

    def test_deferral_classifier_matches_golden_cases(self):
        # The shared golden-case file is the single source of truth for what
        # text, on what surface, gets flagged. The hook test
        # (tools/tests/test_block_defer_language.py) loads the same file, so
        # the hook's standalone classifier and this one cannot drift without
        # one of the two suites failing.
        cases = json.loads(DEFERRAL_CASES_PATH.read_text(encoding="utf-8"))["cases"]
        self.assertGreater(len(cases), 10, "deferral_cases.json should have a substantive case set")
        failures = []
        for case in cases:
            decision, pattern = classify_deferral_language(case["text"], case["surface"])
            if decision != case["expect"]:
                failures.append(
                    f"{case['id']}: surface={case['surface']} expected {case['expect']} "
                    f"got {decision} (pattern={pattern}) — {case['why']}"
                )
        self.assertEqual(failures, [], "\n".join(failures))

    def test_no_deferral_disposition_check_flags_tier1_in_pr_body(self):
        violations = run_no_deferral_disposition_check(
            pr_body="## Summary\n\nFixed the gate. SonarCloud findings deferred to a follow-up PR.\n"
        )
        codes = {v.code for v in violations}
        self.assertIn("pr-body-deferral-disposition", codes)
        details = " ".join(d for v in violations for d in v.details)
        self.assertIn("tier1:", details)

    def test_no_deferral_disposition_check_allows_out_of_scope_section(self):
        # A PR body legitimately scope-bounds its own work; bare "out of scope"
        # with no deferral verb is not flagged on the pr-body surface.
        body = (
            "## Summary\n\nImplements the gate.\n\n"
            "## Out of scope\n\n- Retroactive cleanup of past issues.\n"
            "- Changing the existing hard cap behavior.\n"
        )
        self.assertEqual(run_no_deferral_disposition_check(pr_body=body), [])

    def test_no_deferral_disposition_check_allows_amended_gc_run_sweep_line(self):
        # After the A4 wording fix, the Ground Control Checks line no longer
        # carries "deferred"; the scanner must not flag the template line.
        body = "## Summary\n\nx\n- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
        self.assertEqual(run_no_deferral_disposition_check(pr_body=body), [])

    def test_no_deferral_disposition_check_noop_when_no_body(self):
        self.assertEqual(run_no_deferral_disposition_check(pr_body=None), [])

    def test_parse_args_accepts_pre_commit_positional_files(self):
        args = parse_args(["--skip-pr-body", "docs/WORKFLOW.md", "mcp/ground-control/lib.js"])
        self.assertEqual(args.paths, ["docs/WORKFLOW.md", "mcp/ground-control/lib.js"])
        self.assertIsNone(args.files)

    def test_parse_args_accepts_pr_body_file(self):
        args = parse_args(["--pr-body-file", "/tmp/pr-body.md"])
        self.assertEqual(args.pr_body_file, "/tmp/pr-body.md")

    def test_parse_args_accepts_pr_number(self):
        args = parse_args(["--pr-number", "790"])
        self.assertEqual(args.pr_number, 790)
        self.assertIsNone(parse_args([]).pr_number)

    def test_parse_args_accepts_pr_comments_json(self):
        args = parse_args(["--pr-comments-json", "/tmp/pr-comments.jsonl"])
        self.assertEqual(args.pr_comments_json, "/tmp/pr-comments.jsonl")





# ---------------------------------------------------------------------------
# Version-mirror consistency (issue #1399, GC-P027).
#
# Release Please owns the product version; `.release-please-manifest.json` records
# it and `release-please-config.json` extra-files bump the mirrors on the release
# PR. `run_version_mirror_consistency_check` fails when a mirror drifts from the
# manifest, driven by the config's extra-files as the declarative inventory (not a
# second hard-coded list). These tests exercise the JSON updater (including the
# empty-root-package-key package-lock path) and the generic annotation updater
# (build.gradle.kts), and assert the real repo is self-consistent.
# ---------------------------------------------------------------------------


class OntologyCrosswalkChecksTest(unittest.TestCase):
    """Fixture-driven coverage for run_ontology_crosswalk_check (ADR-084 §4)."""

    SNAPSHOT_REL = "contracts/ontology/external/aces-sdl/0.23.0/concept-families-v1.json"

    def _aces_snapshot(self) -> dict:
        return {
            "schema_version": "concept-families/v1",
            "families": {
                "assets": {"title": "Assets", "description": "Nodes and infra."},
                "observables": {"title": "Observables", "description": "Telemetry."},
                "time-and-apparatus": {"title": "Time and Apparatus", "description": "Clocks."},
            },
        }

    def _gc_catalog(self) -> dict:
        return {
            "schema_version": "gc-concept-families/v1",
            "owners": ["ground-control"],
            "families": {
                "architecture-and-boundaries": {
                    "title": "Architecture and Boundaries",
                    "description": "Assets and boundaries.",
                    "provenance": "native",
                    "owner": "ground-control",
                    "extension_scope": "Architecture participants.",
                    "relation_rules": ["Structural relations are directed."],
                    "non_ambiguity_constraints": ["An asset is not an observation."],
                },
                "evidence-and-observation": {
                    "title": "Evidence and Observation",
                    "description": "Evidence and observations.",
                    "provenance": "native",
                    "owner": "ground-control",
                    "extension_scope": "Observations and evidence.",
                    "relation_rules": ["Evidence points from subject to evidence."],
                    "non_ambiguity_constraints": ["The subject of evidence is not evidence."],
                },
            },
        }

    def _write_crosswalk_fixture(self, root: Path) -> Path:
        ontology = root / "contracts" / "ontology"
        (ontology / "crosswalks").mkdir(parents=True)
        (ontology / "gc-concept-families-v1.json").write_text(
            json.dumps(self._gc_catalog()), encoding="utf-8"
        )
        snapshot_path = root / self.SNAPSHOT_REL
        snapshot_path.parent.mkdir(parents=True)
        snapshot_bytes = json.dumps(self._aces_snapshot()).encode("utf-8")
        snapshot_path.write_bytes(snapshot_bytes)
        digest = hashlib.sha256(snapshot_bytes).hexdigest()
        crosswalk = {
            "schema_version": "aces-concept-families-crosswalk/v1",
            "gc_catalog": "contracts/ontology/gc-concept-families-v1.json",
            "gc_catalog_schema_version": "gc-concept-families/v1",
            "external_pin": {
                "authority": "aces-sdl",
                "distribution": "aces-sdl",
                "release_version": "0.23.0",
                "artifact_path": "aces_contracts/_corpus/concept-authority/concept-families-v1.json",
                "catalog_schema_version": "concept-families/v1",
                "sha256": digest,
                "reference_snapshot": self.SNAPSHOT_REL,
            },
            "review_scope": "Directional review; effect states the ACES family's effect on GC meaning.",
            "alignments": [
                {
                    "gc_family": "architecture-and-boundaries",
                    "aces_family": "assets",
                    "effect": "refines",
                    "rationale": "ACES assets is the narrower node/infrastructure slice.",
                    "divergences": ["GC folds operational assets into architecture-and-boundaries."],
                },
                {
                    "gc_family": "evidence-and-observation",
                    "aces_family": "observables",
                    "effect": "refines",
                    "rationale": "ACES observables is the telemetry slice.",
                    "divergences": ["GC also spans evidence artifacts and supersession."],
                },
            ],
            "omissions": [
                {
                    "topic": "time",
                    "aces_family": "time-and-apparatus",
                    "reason": "Ground Control has no time concept family; ADR-084 §5 makes time the Envers spine.",
                }
            ],
        }
        crosswalk_path = ontology / "crosswalks" / "aces-concept-families-v1.json"
        crosswalk_path.write_text(json.dumps(crosswalk), encoding="utf-8")
        return crosswalk_path

    def _read_crosswalk(self, root: Path) -> tuple[Path, dict]:
        path = root / "contracts" / "ontology" / "crosswalks" / "aces-concept-families-v1.json"
        return path, json.loads(path.read_text(encoding="utf-8"))

    def test_passes_minimal_complete_crosswalk(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_crosswalk_fixture(root)
            violations = run_ontology_crosswalk_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])

    def test_missing_crosswalk_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            violations = run_ontology_crosswalk_check(root=Path(tmp_dir))
        self.assertIn("ontology-crosswalk-missing", {v.code for v in violations})

    def test_fails_closed_on_malformed_json(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            path = self._write_crosswalk_fixture(root)
            path.write_text("{", encoding="utf-8")
            violations = run_ontology_crosswalk_check(root=root)
        self.assertIn("ontology-crosswalk-invalid-json", {v.code for v in violations})

    def test_rejects_duplicate_json_keys(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            path = self._write_crosswalk_fixture(root)
            payload = path.read_text(encoding="utf-8")
            duplicate = payload.replace(
                '"review_scope"', '"schema_version": "aces-concept-families-crosswalk/v1", "review_scope"', 1
            )
            path.write_text(duplicate, encoding="utf-8")
            violations = run_ontology_crosswalk_check(root=root)
        self.assertIn("ontology-crosswalk-invalid-json", {v.code for v in violations})

    def test_covers_validation_branches(self):
        cases = (
            ("bad-schema-version", "ontology-crosswalk-version-invalid"),
            ("hash-drift", "ontology-crosswalk-hash-drift"),
            ("missing-snapshot", "ontology-crosswalk-snapshot-missing"),
            ("snapshot-duplicate-keys", "ontology-crosswalk-snapshot-invalid-json"),
            ("unsafe-snapshot-path", "ontology-crosswalk-pin-invalid"),
            ("snapshot-outside-external-root", "ontology-crosswalk-pin-invalid"),
            ("layout-mismatch", "ontology-crosswalk-pin-invalid"),
            ("catalog-schema-mismatch", "ontology-crosswalk-pin-invalid"),
            ("gc-catalog-not-canonical", "ontology-crosswalk-gc-catalog-invalid"),
            ("gc-catalog-schema-mismatch", "ontology-crosswalk-gc-catalog-invalid"),
            ("gc-catalog-schema-drift", "ontology-crosswalk-gc-catalog-invalid"),
            ("time-alignment-forbidden", "ontology-crosswalk-time-alignment-forbidden"),
            ("time-omission-missing", "ontology-crosswalk-time-omission-required"),
            ("dangling-gc-family", "ontology-crosswalk-gc-family-missing"),
            ("dangling-aces-family", "ontology-crosswalk-aces-family-missing"),
            ("effect-outside-vocabulary", "ontology-crosswalk-effect-invalid"),
            ("aligns-with-divergences", "ontology-crosswalk-effect-divergence-mismatch"),
            ("refines-without-divergences", "ontology-crosswalk-effect-divergence-mismatch"),
            ("missing-rationale", "ontology-crosswalk-rationale-missing"),
            ("duplicate-alignment", "ontology-crosswalk-alignment-duplicate"),
            ("empty-alignments", "ontology-crosswalk-shape-invalid"),
            ("omission-dangling-family", "ontology-crosswalk-omission-family-missing"),
            ("omission-missing-reason", "ontology-crosswalk-omission-invalid"),
        )
        for case, expected_code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp_dir:
                root = Path(tmp_dir)
                self._write_crosswalk_fixture(root)
                path, crosswalk = self._read_crosswalk(root)
                if case == "bad-schema-version":
                    crosswalk["schema_version"] = "aces-concept-families-crosswalk/v2"
                elif case == "hash-drift":
                    crosswalk["external_pin"]["sha256"] = "0" * 64
                elif case == "missing-snapshot":
                    (root / self.SNAPSHOT_REL).unlink()
                elif case == "snapshot-duplicate-keys":
                    snap = root / self.SNAPSHOT_REL
                    dup = snap.read_text(encoding="utf-8").replace(
                        '{"schema_version"',
                        '{"schema_version": "concept-families/v1", "schema_version"',
                        1,
                    )
                    snap.write_text(dup, encoding="utf-8")
                    # Re-pin the hash so only the duplicate-key parse violation fires.
                    crosswalk["external_pin"]["sha256"] = hashlib.sha256(
                        dup.encode("utf-8")
                    ).hexdigest()
                elif case == "unsafe-snapshot-path":
                    crosswalk["external_pin"]["reference_snapshot"] = "../outside.json"
                elif case == "snapshot-outside-external-root":
                    crosswalk["external_pin"]["reference_snapshot"] = "contracts/ontology/gc-concept-families-v1.json"
                elif case == "layout-mismatch":
                    crosswalk["external_pin"]["release_version"] = "9.9.9"
                elif case == "catalog-schema-mismatch":
                    crosswalk["external_pin"]["catalog_schema_version"] = "concept-families/v2"
                elif case == "gc-catalog-not-canonical":
                    alt = root / "contracts" / "ontology" / "alt-families.json"
                    alt.write_text(json.dumps(self._gc_catalog()), encoding="utf-8")
                    crosswalk["gc_catalog"] = "contracts/ontology/alt-families.json"
                elif case == "gc-catalog-schema-mismatch":
                    crosswalk["gc_catalog_schema_version"] = "gc-concept-families/v2"
                elif case == "gc-catalog-schema-drift":
                    gc_path = root / "contracts" / "ontology" / "gc-concept-families-v1.json"
                    drifted = self._gc_catalog()
                    drifted["schema_version"] = "gc-concept-families/v2"
                    gc_path.write_text(json.dumps(drifted), encoding="utf-8")
                elif case == "time-alignment-forbidden":
                    crosswalk["alignments"].append(
                        {
                            "gc_family": "architecture-and-boundaries",
                            "aces_family": "time-and-apparatus",
                            "effect": "refines",
                            "rationale": "invalid: time has no GC family.",
                            "divergences": ["should be rejected"],
                        }
                    )
                elif case == "time-omission-missing":
                    crosswalk["omissions"] = []
                elif case == "dangling-gc-family":
                    crosswalk["alignments"][0]["gc_family"] = "no-such-family"
                elif case == "dangling-aces-family":
                    crosswalk["alignments"][0]["aces_family"] = "no-such-family"
                elif case == "effect-outside-vocabulary":
                    crosswalk["alignments"][0]["effect"] = "supersedes"
                elif case == "aligns-with-divergences":
                    crosswalk["alignments"][0]["effect"] = "aligns"
                elif case == "refines-without-divergences":
                    crosswalk["alignments"][0]["divergences"] = []
                elif case == "missing-rationale":
                    crosswalk["alignments"][0]["rationale"] = "  "
                elif case == "duplicate-alignment":
                    crosswalk["alignments"].append(dict(crosswalk["alignments"][0]))
                elif case == "empty-alignments":
                    crosswalk["alignments"] = []
                elif case == "omission-dangling-family":
                    crosswalk["omissions"][0]["aces_family"] = "no-such-family"
                elif case == "omission-missing-reason":
                    crosswalk["omissions"][0]["reason"] = ""
                path.write_text(json.dumps(crosswalk), encoding="utf-8")
                violations = run_ontology_crosswalk_check(root=root)
                self.assertIn(expected_code, {v.code for v in violations})

    def test_real_committed_crosswalk_passes(self):
        violations = run_ontology_crosswalk_check(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])


class VersionMirrorConsistencyChecksTest(unittest.TestCase):
    def _write(self, root: Path, rel: str, text: str) -> Path:
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def _canonical_repo(self, root: Path, version: str = "1.0.1") -> None:
        self._write(root, ".release-please-manifest.json", json.dumps({".": version}) + "\n")
        self._write(
            root,
            "release-please-config.json",
            json.dumps(
                {
                    "include-component-in-tag": False,
                    "packages": {
                        ".": {
                            "release-type": "simple",
                            "extra-files": [
                                {"type": "json", "path": "frontend/package.json", "jsonpath": "$.version"},
                                {"type": "json", "path": "frontend/package-lock.json", "jsonpath": "$.version"},
                                {
                                    "type": "json",
                                    "path": "frontend/package-lock.json",
                                    "jsonpath": "$.packages[''].version",
                                },
                                "backend/build.gradle.kts",
                            ],
                        }
                    },
                }
            )
            + "\n",
        )
        self._write(root, "frontend/package.json", json.dumps({"name": "x", "version": version}) + "\n")
        self._write(
            root,
            "frontend/package-lock.json",
            json.dumps(
                {"name": "x", "version": version, "packages": {"": {"name": "x", "version": version}}}
            )
            + "\n",
        )
        self._write(
            root,
            "backend/build.gradle.kts",
            f'group = "com.keplerops"\nversion = "{version}" // x-release-please-version\n',
        )

    def test_noop_when_no_release_please(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(run_version_mirror_consistency_check(root=Path(tmp)), [])

    def test_passes_when_all_mirrors_match_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._canonical_repo(root, "1.0.1")
            self.assertEqual([v.code for v in run_version_mirror_consistency_check(root=root)], [])

    def test_drift_when_json_mirror_diverges(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._canonical_repo(root, "1.0.1")
            self._write(root, "frontend/package.json", json.dumps({"name": "x", "version": "0.76.0"}) + "\n")
            codes = {v.code for v in run_version_mirror_consistency_check(root=root)}
            self.assertIn("version-mirror-drift", codes)

    def test_drift_when_lockfile_root_package_key_diverges(self):
        # Exercises the empty-root-package-key jsonpath $.packages[''].version.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._canonical_repo(root, "1.0.1")
            self._write(
                root,
                "frontend/package-lock.json",
                json.dumps(
                    {"name": "x", "version": "1.0.1", "packages": {"": {"name": "x", "version": "0.76.0"}}}
                )
                + "\n",
            )
            codes = {v.code for v in run_version_mirror_consistency_check(root=root)}
            self.assertIn("version-mirror-drift", codes)

    def test_drift_when_generic_gradle_mirror_diverges(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._canonical_repo(root, "1.0.1")
            self._write(root, "backend/build.gradle.kts", 'version = "0.20.1" // x-release-please-version\n')
            codes = {v.code for v in run_version_mirror_consistency_check(root=root)}
            self.assertIn("version-mirror-drift", codes)

    def test_drift_when_mirror_file_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._canonical_repo(root, "1.0.1")
            (root / "backend" / "build.gradle.kts").unlink()
            codes = {v.code for v in run_version_mirror_consistency_check(root=root)}
            self.assertIn("version-mirror-drift", codes)

    def test_config_missing_when_only_manifest_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, ".release-please-manifest.json", json.dumps({".": "1.0.1"}) + "\n")
            codes = {v.code for v in run_version_mirror_consistency_check(root=root)}
            self.assertIn("version-mirror-config-missing", codes)

    def test_config_invalid_when_manifest_has_no_root_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._canonical_repo(root, "1.0.1")
            self._write(root, ".release-please-manifest.json", json.dumps({"other": "1.0.1"}) + "\n")
            codes = {v.code for v in run_version_mirror_consistency_check(root=root)}
            self.assertIn("version-mirror-config-invalid", codes)

    def test_jsonpath_keys_supports_empty_root_package_key(self):
        self.assertEqual(_jsonpath_keys("$.version"), ["version"])
        self.assertEqual(_jsonpath_keys("$.packages[''].version"), ["packages", "", "version"])
        self.assertEqual(_jsonpath_keys('$.packages[""].version'), ["packages", "", "version"])

    def test_repo_release_please_mirrors_are_consistent(self):
        # The real repo must be self-consistent: manifest version == every mirror.
        violations = run_version_mirror_consistency_check(root=REPO_ROOT)
        self.assertEqual(
            [v.code for v in violations],
            [],
            msg=f"real-repo version mirrors drifted: {[v.message for v in violations]}",
        )


# ---------------------------------------------------------------------------
# Test-quality decision-record contract (issue #884; step moved by #906).
#
# `/implement` test-quality review halted after a clean `review-tests` cycle
# because the workflow contract was prose-only — there was no structured
# signal the parent could branch on to advance without a user turn. The fix
# (per the architecture preflight under
# `architecture/notes/test-quality-clean-continuation-preflight.md`) is to
# reuse the existing `gc_post_decision_record` contract: every test-quality
# cycle ends with a decision-record post carrying `reviewer: "test-quality"`
# and the findings list (empty for a clean cycle). A clean record IS the
# advance signal.
#
# `run_test_quality_decision_record_contract` is the structural gate that
# prevents the contract from silently disappearing. It is a parser over the
# test-quality section structure (Step 6.6 per #906; formerly Step 13), not
# a snapshot of specific prose — the section must reference the canonical
# tool, the test-quality reviewer enum, the empty-findings clean cycle case,
# and a continuation signal.
# Following the same "parser-over-fixed-grammar" structural-gate pattern as
# `checks.py::run_version_mirror_consistency_check`.
# ---------------------------------------------------------------------------


class TestQualityDecisionRecordContractTest(unittest.TestCase):
    """Structural gate for the test-quality decision-record contract
    (issue #884 originally; step moved pre-push to Step 6.6 by #906)."""

    _CONTRACT_PROSE = (
        "### Step 6.6: Pre-push Test-Quality Review\n"
        "\n"
        "The whole point of the review is to fix the tests. When the\n"
        "skill returns findings, fix them in the same turn — do not stop,\n"
        "do not echo the findings to the user as if reporting completed\n"
        "work.\n"
        "\n"
        "1. Call `gc_test_quality_review` MCP tool; the parent reads `next_action`.\n"
        "2. Case A — findings returned. The parent MUST fix the findings\n"
        "   in the same turn. Do not stop. Apply the named fix, re-stage,\n"
        "   call `gc_post_decision_record` with\n"
        "   `reviewer: \"test-quality\"` and the findings list, confirm\n"
        "   `ok: true`, and re-invoke `gc_test_quality_review`.\n"
        "3. Case B — zero findings. Call `gc_post_decision_record` with\n"
        "   `reviewer: \"test-quality\"` and `findings: []` (renders as\n"
        "   `0 (clean run)`).\n"
        "4. Advance to Phase C only after `gc_post_decision_record`\n"
        "   returns `ok: true` with a posted comment id/url; on\n"
        "   `ok: false`, fix the underlying tooling issue and retry the\n"
        "   post before entering Phase C.\n"
        "5. A successfully posted clean decision record IS the structured\n"
        "   advance signal — proceed to Phase C in the same\n"
        "   turn, no user acknowledgment turn.\n"
        "6. Cycle cap: 1 iteration default (issue #906; configurable per repo).\n"
        "\n"
        "### Step 7: Stage & Pre-commit Loop\n"
    )

    _CONTRACT_MISSING_PROSE = (
        "### Step 6.6: Pre-push Test-Quality Review\n"
        "\n"
        "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
        "2. Apply the Review loop rules: fix every finding.\n"
        "3. Cycle cap: 5 iterations. After the fifth, escalate.\n"
        "\n"
        "### Step 7: Stage & Pre-commit Loop\n"
    )

    # --- section extractor -------------------------------------------------

    def test_extract_step_section_returns_section_text(self):
        body = "intro\n\n### Step 6.6: Pre-push Test-Quality Review\n\nbody line\n\n### Step 7: Next\n"
        section = extract_step_section(body, "Step 6.6")
        self.assertIsNotNone(section)
        self.assertIn("body line", section)
        self.assertNotIn("Step 7", section)

    def test_extract_step_section_returns_none_when_missing(self):
        body = "### Step 12: Other\n\nbody\n"
        self.assertIsNone(extract_step_section(body, "Step 6.6"))

    # --- contract present / absent on raw text -----------------------------

    def test_check_passes_when_contract_present(self):
        violations = run_test_quality_decision_record_contract(text=self._CONTRACT_PROSE)
        self.assertEqual(violations, [])

    def test_check_flags_missing_gc_test_quality_review_invocation(self):
        # Per #884 v2: Step 13 must call the MCP tool, not the legacy Skill.
        # If the section drops the gc_test_quality_review mention, the
        # policy gate must flag it.
        no_mcp_tool = (
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Invoke a review skill.\n"
            "2. After every cycle, call `gc_post_decision_record` with\n"
            "   `reviewer: \"test-quality\"` and the findings list. Clean cycle\n"
            "   posts `findings: []`. Advance to Step 14 after `ok: true` —\n"
            "   proceed in the same turn. Fix findings in the same turn; do\n"
            "   not stop. next_action is the dispatch field.\n"
            "\n"
            "### Step 7: Next\n"
        )
        violations = run_test_quality_decision_record_contract(text=no_mcp_tool)
        self.assertTrue(violations)
        message = "\n".join(v.render() for v in violations)
        self.assertIn("gc_test_quality_review", message)

    def test_check_flags_missing_next_action_dispatch_field(self):
        # The `next_action` field is the directive the parent reads.
        # Without it the Step 13 prose is back to free-form findings
        # handoff.
        no_dispatch = (
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []`. Advance to Step 14 after `ok: true`. Fix\n"
            "   findings in the same turn; do not stop.\n"
            "\n"
            "### Step 7: Next\n"
        )
        violations = run_test_quality_decision_record_contract(text=no_dispatch)
        self.assertTrue(violations)
        message = "\n".join(v.render() for v in violations)
        self.assertIn("next_action", message)

    def test_check_flags_missing_decision_record_call(self):
        violations = run_test_quality_decision_record_contract(
            text=self._CONTRACT_MISSING_PROSE
        )
        self.assertTrue(violations, "missing contract must surface a violation")
        codes = {v.code for v in violations}
        self.assertIn("test-quality-decision-record-contract", codes)

    def test_check_flags_each_missing_token_individually(self):
        # If Step 13 is present but only some tokens are missing, the
        # violation message must name each missing element so the agent
        # editing the SKILL can fix all of them in one pass instead of
        # cycling.
        partial = (
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call gc_post_decision_record after each cycle.\n"
            "\n"
            "### Step 7: Next\n"
        )
        violations = run_test_quality_decision_record_contract(text=partial)
        self.assertTrue(violations)
        message = "\n".join(v.render() for v in violations)
        # `findings: []` clean-case marker, test-quality reviewer literal,
        # and the explicit continuation phrasing must all be flagged.
        self.assertIn("findings: []", message)
        self.assertIn("test-quality", message)
        # Continuation phrase: any of "advance", "proceed", "continue" with
        # "Step 14" nearby satisfies; partial fixture has none.
        self.assertRegex(message, r"(?i)step\s*14|continuation|advance")

    def test_check_flags_missing_step13_section_entirely(self):
        body = "### Step 12: Other\n\nbody\n### Step 14: Next\n"
        violations = run_test_quality_decision_record_contract(text=body)
        self.assertTrue(violations)
        codes = {v.code for v in violations}
        self.assertIn("test-quality-section-missing", codes)

    # --- contract present in the real SKILL --------------------------------

    # --- success precondition (`ok: true`) ---------------------------------

    def test_check_flags_missing_ok_true_precondition(self):
        # Contract present but the `ok: true` success precondition is not
        # mentioned. Step 13 must require the durable post to succeed before
        # advancing — otherwise an `ok: false` envelope from
        # `gc_post_decision_record` (sensitive content, body size, posting
        # failure) re-opens the silent-advance failure mode in a different
        # shape.
        no_precondition = (
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. After every cycle, call `gc_post_decision_record` with\n"
            "   `reviewer: \"test-quality\"` and the full findings list.\n"
            "   A clean cycle posts `findings: []`.\n"
            "3. A clean decision record IS the advance-to-Step-14 signal —\n"
            "   proceed to Step 14 in the same turn, no acknowledgment.\n"
            "\n"
            "### Step 7: Next\n"
        )
        violations = run_test_quality_decision_record_contract(text=no_precondition)
        self.assertTrue(violations)
        message = "\n".join(v.render() for v in violations)
        self.assertRegex(message, r"(?i)ok\s*:\s*true|success precondition")

    # --- findings-fix-in-same-turn directive -------------------------------

    def test_check_flags_missing_fix_findings_in_same_turn_directive(self):
        # Contract is otherwise present (records record, reviewer, clean
        # case, ok:true precondition, continuation) but the Case A
        # findings-fix-in-same-turn instruction is missing. That is the
        # exact failure mode the user reported after #884's first fix
        # shipped: when review-tests returns findings, the parent echoes
        # them to the user and stops instead of fixing them in the same
        # turn.
        no_fix_directive = (
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. After every cycle, call `gc_post_decision_record` with\n"
            "   `reviewer: \"test-quality\"` and the full findings list.\n"
            "   A clean cycle posts `findings: []`.\n"
            "3. Advance to Step 14 only after `gc_post_decision_record`\n"
            "   returns `ok: true`. Proceed to Step 14 in the same turn.\n"
            "\n"
            "### Step 7: Next\n"
        )
        violations = run_test_quality_decision_record_contract(text=no_fix_directive)
        self.assertTrue(violations)
        message = "\n".join(v.render() for v in violations)
        self.assertRegex(
            message,
            r"(?i)fix[^.]*finding|same\s+turn|do\s+not\s+stop",
            "violation must name the missing findings-fix-in-same-turn directive",
        )

    # --- anti-contract negation patterns -----------------------------------

    # Each case pins one anti-pattern shape. The fixture is the failing
    # Step 13 prose; `expected_code_substring` is asserted to appear in the
    # detail of a `test-quality-anti-contract-prose` violation, so a regression in
    # any single pattern surfaces with the pattern name in the failure
    # message rather than a generic "anti-contract" miss.
    _ANTI_CONTRACT_FIXTURES: tuple[tuple[str, str, str], ...] = (
        (
            "skip-decision-record",
            "skip-decision-record",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []` for clean cycles. Advance to Step 14 after\n"
            "   `ok: true`.\n"
            "3. Note: drivers may skip the decision record on clean cycles\n"
            "   to save a network round-trip.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        (
            "do-not-call-post-record",
            "do-not-call-post-record",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Invoke the `review-tests` skill. Do not call\n"
            "   `gc_post_decision_record` on a clean cycle — the skill\n"
            "   return is sufficient.\n"
            "2. `reviewer: \"test-quality\"`, `findings: []`, advance to\n"
            "   Step 14 after `ok: true`.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        (
            "do-not-proceed-step14",
            "do-not-proceed-step14",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []`. Do not proceed to Step 14 automatically —\n"
            "   wait for the user to acknowledge the clean cycle.\n"
            "3. Advance after the user confirms with `ok: true`.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        (
            "findings-empty-not-enough",
            "findings-empty-not-enough",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`,\n"
            "   `findings: []`. Note: `findings: []` is not enough — also\n"
            "   require manual user sign-off before Step 14.\n"
            "3. Advance after `ok: true` AND user sign-off.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        # The user-observed failure mode: parent echoes findings back to
        # the user as a status report and stops, instead of fixing them in
        # the same turn. The whole point of the review is to fix the tests.
        # Both fixtures pin the same anti-pattern code (the regex covers
        # multiple verb shapes); the labels differ so a regression in
        # one variant names the variant in the failure message.
        (
            "echo-them-to-user",  # pronoun case ("echo them to the user")
            "findings-routed-to-user",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []` for clean cycles. Fix findings in the\n"
            "   same turn after `ok: true`. Proceed to Step 14.\n"
            "3. When findings are returned, echo them to the user as a\n"
            "   review report and wait for the user's go-ahead.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        (
            "return-findings-to-user",  # literal noun case
            "findings-routed-to-user",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []`. Fix findings in the same turn. Advance\n"
            "   to Step 14 after `ok: true`.\n"
            "3. If findings are returned, return findings to the user and\n"
            "   stop the workflow for human review.\n"
            "\n"
            "### Step 7: Next\n",
        ),
    )

    def test_check_flags_anti_contract_patterns(self):
        for label, expected_code, fixture in self._ANTI_CONTRACT_FIXTURES:
            with self.subTest(pattern=label):
                violations = run_test_quality_decision_record_contract(text=fixture)
                self.assertTrue(
                    violations,
                    f"{label}: must surface an anti-contract violation",
                )
                codes = {v.code for v in violations}
                self.assertIn(
                    "test-quality-anti-contract-prose",
                    codes,
                    f"{label}: missing test-quality-anti-contract-prose code",
                )
                # The violation's details name the specific pattern that
                # matched, so a regression in pattern N is named in the
                # failure message rather than collapsed into a generic
                # "anti-contract" miss.
                detail_text = "\n".join(
                    "\n".join(v.details)
                    for v in violations
                    if v.code == "test-quality-anti-contract-prose"
                )
                self.assertIn(
                    expected_code,
                    detail_text,
                    f"{label}: violation detail must name the matched pattern code",
                )

    # --- allowed-negative fixtures: negated anti-patterns are OK -----------

    # The check must distinguish the bad imperative ("Skip the decision
    # record") from the correct guardrail ("Do not skip the decision
    # record"). Each fixture below carries the full contract plus a
    # negated anti-pattern; none should surface any violations.
    _ALLOWED_NEGATIVE_FIXTURES: tuple[tuple[str, str], ...] = (
        (
            "do-not-skip",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []`. Do not skip the decision record on a\n"
            "   clean cycle — the durable marker is the workflow signal.\n"
            "   Fix findings in the same turn; do not stop.\n"
            "3. Advance to Step 14 after `ok: true`. Proceed to Step 14\n"
            "   in the same turn, no acknowledgment.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        (
            "on-ok-false-do-not-advance",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. Call `gc_post_decision_record` with `reviewer: \"test-quality\"`\n"
            "   and `findings: []`. Fix findings in the same turn; do not\n"
            "   stop.\n"
            "3. On `ok: false`, do not advance to Step 14 — fix the\n"
            "   underlying tooling issue and retry the post. Proceed only\n"
            "   after `ok: true`.\n"
            "\n"
            "### Step 7: Next\n",
        ),
        (
            "must-never-skip",
            "### Step 6.6: Pre-push Test-Quality Review\n"
            "\n"
            "1. Call `gc_test_quality_review`; parent reads `next_action`.\n"
            "2. The agent must never skip the decision-record post on a\n"
            "   clean cycle. Call `gc_post_decision_record` with\n"
            "   `reviewer: \"test-quality\"` and `findings: []`. Fix\n"
            "   findings in the same turn; do not stop.\n"
            "3. Advance to Step 14 after `ok: true`. Proceed to Step 14\n"
            "   in the same turn.\n"
            "\n"
            "### Step 7: Next\n",
        ),
    )

    def test_check_accepts_negated_anti_patterns(self):
        for label, fixture in self._ALLOWED_NEGATIVE_FIXTURES:
            with self.subTest(negator=label):
                violations = run_test_quality_decision_record_contract(text=fixture)
                self.assertEqual(
                    violations,
                    [],
                    f"{label}: negated anti-pattern must not false-positive — "
                    "this is correct guardrail prose",
                )

    def test_real_skill_passes_contract(self):
        # The repo's test-quality contract source MUST satisfy the
        # gc_post_decision_record contract (regression target for issue
        # #884). After issue #934 split the monolithic SKILL.md into a
        # thin orchestrator + per-step files, the Step 6.6 section lives
        # at skills/implement/steps/step-06.6-test-quality-review.md.
        # Passing no `text=` argument lets run_test_quality_decision_record_contract
        # discover the right source path itself (step file first, with a
        # fallback to SKILL.md for backward compatibility).
        violations = run_test_quality_decision_record_contract()
        self.assertEqual(
            violations,
            [],
            "The test-quality contract source must mandate the "
            "gc_post_decision_record contract for test-quality cycles "
            "(issue #884 regression target).",
        )


    # ------------------------------------------------------------------
    # Documentation coverage check (issue #896, ADR-054)
    # ------------------------------------------------------------------

    def test_doc_coverage_passes_when_outcome_present(self):
        """A PR body with ## Documentation passes even for classified surfaces."""
        pr_body = (
            "## Summary\nAdded classifier.\n\n"
            "## Requirement UIDs\n- ADR-054\n\n"
            "## Related Issues\nCloses #896\n\n"
            "## ADR Impact\n- ADR-054\n\n"
            "## Changes\n- Added classifyChangedSurface\n\n"
            "## Documentation\n\nUpdated: see diff.\n"
        )
        # mcp/ground-control/lib.js is a config_parser surface → outcome_required
        violations = run_documentation_coverage_check(
            ["mcp/ground-control/lib.js"],
            pr_body=pr_body,
        )
        codes = [v.code for v in violations]
        self.assertNotIn("doc-coverage-outcome-missing", codes)

    def test_doc_coverage_fails_when_outcome_missing_and_surface_classified(self):
        """A PR body without ## Documentation fails for classified surfaces."""
        pr_body = (
            "## Summary\nAdded classifier.\n\n"
            "## Requirement UIDs\n- ADR-054\n\n"
            "## Related Issues\nCloses #896\n\n"
            "## ADR Impact\n- ADR-054\n\n"
            "## Changes\n- Added classifyChangedSurface\n"
        )
        # mcp/ground-control/lib.js is a config_parser surface → outcome_required
        violations = run_documentation_coverage_check(
            ["mcp/ground-control/lib.js"],
            pr_body=pr_body,
        )
        codes = [v.code for v in violations]
        self.assertIn("doc-coverage-outcome-missing", codes)

    def test_doc_coverage_docs_only_diff_passes_without_outcome(self):
        """A docs-only diff does not require a ## Documentation section."""
        pr_body = (
            "## Summary\nDoc update.\n\n"
            "## Requirement UIDs\n- ADR-054\n\n"
            "## Related Issues\nCloses #896\n\n"
            "## ADR Impact\n- ADR-054\n\n"
            "## Changes\n- Updated DOC_STYLE.md\n"
        )
        # docs/ paths are doc surface → outcome_required=false
        violations = run_documentation_coverage_check(
            ["docs/DOC_STYLE.md"],
            pr_body=pr_body,
        )
        codes = [v.code for v in violations]
        self.assertNotIn("doc-coverage-outcome-missing", codes)

    def test_doc_coverage_skips_gracefully_when_pr_body_unavailable(self):
        """When pr_body is None the check skips without raising."""
        violations = run_documentation_coverage_check(
            ["mcp/ground-control/lib.js"],
            pr_body=None,
        )
        # Graceful skip — no hard fail, no fixture-error either
        codes = [v.code for v in violations]
        self.assertNotIn("doc-coverage-outcome-missing", codes)


# ---------------------------------------------------------------------------
# Traceability-reconciliation gate contract (issue #1058).
#
# Tests that the prose surfaces required by the gate stay in sync with the
# MCP-tool enforcement. The check reads four files; tests use a temp REPO_ROOT
# overlay so the gate doesn't fight the real repo state during test runs.
# ---------------------------------------------------------------------------


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


# ---------------------------------------------------------------------------
# Vale prose-lint regression tests (issue #987, ADR-054).
#
# These tests prove that the GoogleProject.EmDashDensity rule fires for
# paragraphs with more than one em-dash and stays quiet for paragraphs with
# exactly one em-dash.  The vale binary is expected at
# `.tools/vale/current/vale`; the test is skipped if the binary is absent so
# CI on a bare checkout is not broken.
# ---------------------------------------------------------------------------


class ValeEmDashDensityTest(unittest.TestCase):
    """Regression tests for the GoogleProject.EmDashDensity Vale rule."""

    _VALE_BIN = REPO_ROOT / ".tools" / "vale" / "current" / "vale"
    _VALE_INI = REPO_ROOT / ".vale.ini"
    _RULE_CHECK = "GoogleProject.EmDashDensity"

    def _run_vale(self, fixture_path: Path) -> list[dict]:
        """Run vale --output=JSON against *fixture_path* and return the alerts list."""
        import subprocess

        # No --minAlertLevel override: vale inherits MinAlertLevel from .vale.ini
        # (error). The rule is error-level, so the positive assertion below sees
        # it. Removing the override means a future regression that downgrades
        # the rule to warning makes the positive test fail (no error-level alert
        # emitted) instead of silently passing under a relaxed test threshold.
        proc = subprocess.run(
            [
                str(self._VALE_BIN),
                f"--config={self._VALE_INI}",
                "--output=JSON",
                "--no-exit",
                str(fixture_path),
            ],
            capture_output=True,
            text=True,
            cwd=str(REPO_ROOT),
        )
        # Vale JSON output is a mapping of file-path -> list-of-alerts.
        # We flatten all alerts across all files into a single list.
        try:
            data = json.loads(proc.stdout)
        except json.JSONDecodeError as exc:
            self.fail(
                f"vale produced non-JSON output (rc={proc.returncode}): "
                f"{proc.stdout!r} stderr={proc.stderr!r} — {exc}"
            )
        alerts: list[dict] = []
        for file_alerts in data.values():
            alerts.extend(file_alerts)
        return alerts

    def setUp(self) -> None:
        if not self._VALE_BIN.exists():
            self.skipTest(
                f"Vale binary not found at {self._VALE_BIN}; "
                "skipping EmDashDensity regression test."
            )

    def test_emdash_density_fires_for_two_emdashes(self) -> None:
        """A paragraph with two em-dashes must produce at least one EmDashDensity alert."""
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".md", delete=False, encoding="utf-8"
        ) as fh:
            # Two em-dashes (U+2014) in a single paragraph.
            fh.write(
                "This sentence—which has an aside—goes on too long.\n"
            )
            fixture = Path(fh.name)
        try:
            alerts = self._run_vale(fixture)
            matching = [a for a in alerts if a.get("Check") == self._RULE_CHECK]
            self.assertGreater(
                len(matching),
                0,
                f"Expected at least one {self._RULE_CHECK} alert for a paragraph "
                f"with two em-dashes, but got none.  All alerts: {alerts}",
            )
        finally:
            fixture.unlink(missing_ok=True)

    def test_emdash_density_silent_for_one_emdash(self) -> None:
        """A paragraph with exactly one em-dash must produce zero EmDashDensity alerts."""
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".md", delete=False, encoding="utf-8"
        ) as fh:
            # One em-dash (U+2014) — well within the soft budget.
            fh.write(
                "This sentence—which has an aside is perfectly fine.\n"
            )
            fixture = Path(fh.name)
        try:
            alerts = self._run_vale(fixture)
            matching = [a for a in alerts if a.get("Check") == self._RULE_CHECK]
            self.assertEqual(
                matching,
                [],
                f"Expected zero {self._RULE_CHECK} alerts for a paragraph with "
                f"one em-dash, but got: {matching}",
            )
        finally:
            fixture.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
