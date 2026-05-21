import json
import tempfile
import unittest
from pathlib import Path

from tools.policy.checks import (
    CHANGELOG_FRAGMENT_TYPES,
    DEFERRAL_CASES_PATH,
    ENUM_CONTRACT_INVENTORY,
    FRONTEND_API_TYPES_PATH,
    MCP_LIB_PATH,
    REPO_ROOT,
    check_pr_body,
    classify_deferral_language,
    extract_step_section,
    parse_args,
    parse_const_string_array,
    parse_fragment_filename,
    parse_java_enum_constants,
    parse_ts_union_literals,
    read_changed_files,
    run_adr_guard,
    run_changelog_fragment_check,
    run_controller_contracts,
    run_deploy_compose_credential_passthrough,
    run_enum_contract_check,
    run_migration_policy,
    run_no_deferral_disposition_check,
    run_pr_body_check,
    run_test_quality_decision_record_contract,
)


class PolicyChecksTest(unittest.TestCase):
    def test_adr_guard_requires_workflow_docs(self):
        violations = run_adr_guard([".claude/skills/implement/SKILL.md"])
        self.assertTrue(any(item.code == "workflow-guardrail-sync" for item in violations))

    def test_adr_guard_fires_on_canonical_implement_skill_path(self):
        violations = run_adr_guard(["skills/implement/SKILL.md"])
        self.assertTrue(any(item.code == "workflow-guardrail-sync" for item in violations))

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

    def test_controller_contracts_require_docs_mcp_and_webmvctest(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            test_file = root / "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java"
            test_file.parent.mkdir(parents=True, exist_ok=True)
            test_file.write_text(
                "@WebMvcTest(FooController.class)\nclass FooControllerTest {}\n",
                encoding="utf-8",
            )
            violations = run_controller_contracts(
                ["backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java"],
                root=root,
            )
            codes = {item.code for item in violations}
            self.assertIn("controller-parity", codes)
            self.assertIn("controller-webmvctest-update", codes)

    def test_migration_policy_requires_smoke_and_e2e_updates(self):
        violations = run_migration_policy(
            ["backend/src/main/resources/db/migration/V999__example.sql"],
            root=REPO_ROOT,
        )
        self.assertTrue(any(item.code == "migration-smoke-sync" for item in violations))

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
            "- [x] `make policy` passes\n"
            "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change\n"
            "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale\n"
            "## Traceability\n\n- IMPLEMENTS: foo\n- TESTS: bar\n"
        )
        self.assertEqual(check_pr_body(body), [])

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
                "    image: ghcr.io/keplerops/ground-control:latest\n"
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
        labels = {c.label for c in ENUM_CONTRACT_INVENTORY}
        self.assertEqual(
            labels,
            {
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
            text = text.replace('  | "PULL_REQUEST"\n', "")
            text = text.replace('  "PULL_REQUEST",\n', "")
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
                'export const LINK_TYPES: LinkType[] = [\n',
                'export const LINK_TYPES: LinkType[] = [\n  "BOGUS",\n',
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


# ---------------------------------------------------------------------------
# Changelog-fragment workflow (issue #848).
#
# Ground-Control adopts towncrier-style fragments under `changelog.d/` to
# eliminate per-PR `CHANGELOG.md` rebase storms. Two structural gates back
# the convention:
#
#   1. Fragment-filename parser — closed Keep-a-Changelog type vocabulary,
#      `<digits>.<type>.md` (issue-anchored) or `+<slug>.<type>.md`
#      (issue-free). Substring tests against fragment prose are not gates;
#      the parser over a fixed vocabulary is.
#   2. Source-changing diff MUST carry a valid fragment under
#      `changelog.d/`. A direct `CHANGELOG.md` edit does NOT substitute
#      for a source diff — that would re-open the rebase-storm pathology
#      the convention exists to prevent. Direct edits are reserved for
#      release-collation commits, which by definition have no source
#      changes and fall through the source predicate. CI-only and
#      docs-only diffs are also outside the predicate and need no
#      signal; there is no "pure refactor" carve-out because the
#      enforcement is path-based.
#
# The same vocabulary AND source predicate are encoded in
# `.claude/hooks/verify-implementation.sh` (host-local Stop hook) so both
# enforcement layers agree.
# ---------------------------------------------------------------------------


class ChangelogFragmentChecksTest(unittest.TestCase):
    # --- parse_fragment_filename ---------------------------------------------

    def test_fragment_filename_accepts_issue_form(self):
        # Each row runs as an independent subTest so a regression that
        # breaks two inputs surfaces both, not just the first.
        cases = [
            ("848.added.md", ("848", "added")),
            ("123.security.md", ("123", "security")),
            ("42.fixed.md", ("42", "fixed")),
            ("7.removed.md", ("7", "removed")),
            ("999.deprecated.md", ("999", "deprecated")),
            ("1.changed.md", ("1", "changed")),
        ]
        for name, expected in cases:
            with self.subTest(name=name):
                self.assertEqual(parse_fragment_filename(name), expected)

    def test_fragment_filename_accepts_slug_form(self):
        cases = [
            ("+towncrier-adoption.added.md", ("+towncrier-adoption", "added")),
            ("+release-notes.changed.md", ("+release-notes", "changed")),
        ]
        for name, expected in cases:
            with self.subTest(name=name):
                self.assertEqual(parse_fragment_filename(name), expected)

    def test_fragment_filename_rejects_unknown_type(self):
        for name in ("848.bogus.md", "848.misc.md", "+slug.unknown.md"):
            with self.subTest(name=name):
                self.assertIsNone(parse_fragment_filename(name))

    def test_fragment_filename_rejects_missing_type(self):
        for name in (
            "848.md",
            "848.added",
            "README.md",
            "_template.md.jinja",
        ):
            with self.subTest(name=name):
                self.assertIsNone(parse_fragment_filename(name))

    def test_fragment_filename_rejects_wrong_extension(self):
        for name in ("848.added.txt", "848.added.rst"):
            with self.subTest(name=name):
                self.assertIsNone(parse_fragment_filename(name))

    def test_fragment_filename_rejects_empty_stem(self):
        for name in (".added.md", "+.added.md"):
            with self.subTest(name=name):
                self.assertIsNone(parse_fragment_filename(name))

    def test_fragment_filename_rejects_non_numeric_issue_stem(self):
        # Issue-anchored fragments must be plain digits; slug fragments must
        # carry the explicit `+` prefix.
        for name in ("issue848.added.md", "abc.added.md"):
            with self.subTest(name=name):
                self.assertIsNone(parse_fragment_filename(name))

    def test_fragment_types_vocabulary_is_keep_a_changelog_set(self):
        self.assertEqual(
            set(CHANGELOG_FRAGMENT_TYPES),
            {"security", "added", "changed", "deprecated", "removed", "fixed"},
        )

    # --- together-ness: the canonical infrastructure files ship together ----

    def _make_temp_repo(self, tmp_dir: str) -> Path:
        root = Path(tmp_dir)
        return root

    def _write_canonical_fragment_infrastructure(self, root: Path) -> None:
        (root / "changelog.d").mkdir(parents=True, exist_ok=True)
        (root / "changelog.d" / "_template.md.jinja").write_text("template\n", encoding="utf-8")
        (root / "changelog.d" / "README.md").write_text("docs\n", encoding="utf-8")
        (root / "towncrier.toml").write_text(
            '[tool.towncrier]\ndirectory = "changelog.d"\n', encoding="utf-8"
        )

    def test_fragment_infrastructure_passes_with_canonical_layout(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(changed_files=[], root=root)
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-fragment-infrastructure", codes)

    def test_fragment_infrastructure_violation_when_template_missing(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            (root / "changelog.d").mkdir()
            (root / "changelog.d" / "README.md").write_text("docs\n", encoding="utf-8")
            (root / "towncrier.toml").write_text(
                '[tool.towncrier]\ndirectory = "changelog.d"\n', encoding="utf-8"
            )
            violations = run_changelog_fragment_check(changed_files=[], root=root)
            codes = {v.code for v in violations}
            self.assertIn("changelog-fragment-infrastructure", codes)

    def test_fragment_infrastructure_violation_when_towncrier_toml_missing(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            (root / "changelog.d").mkdir()
            (root / "changelog.d" / "_template.md.jinja").write_text("t\n", encoding="utf-8")
            (root / "changelog.d" / "README.md").write_text("d\n", encoding="utf-8")
            violations = run_changelog_fragment_check(changed_files=[], root=root)
            codes = {v.code for v in violations}
            self.assertIn("changelog-fragment-infrastructure", codes)

    def test_fragment_infrastructure_silent_when_no_changelog_d(self):
        # Repos that haven't adopted fragments yet must not get a violation
        # merely for the absence of `changelog.d/`.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            violations = run_changelog_fragment_check(changed_files=[], root=root)
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-fragment-infrastructure", codes)

    # --- diff-signal: source-changing diff must have fragment OR CHANGELOG ---

    def test_changelog_signal_missing_when_source_changed_and_no_fragment(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java"
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-signal-missing", codes)

    def test_changelog_signal_accepts_fragment(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            (root / "changelog.d" / "848.added.md").write_text("note\n", encoding="utf-8")
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "changelog.d/848.added.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_accepts_slug_fragment(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            (root / "changelog.d" / "+towncrier-adoption.added.md").write_text(
                "note\n", encoding="utf-8"
            )
            violations = run_changelog_fragment_check(
                changed_files=[
                    "frontend/src/components/Foo.tsx",
                    "changelog.d/+towncrier-adoption.added.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_does_not_accept_deleted_fragment_for_source_diff(self):
        # Codex cycle 3 finding #1 (class): a source-changing diff whose
        # only changelog.d/ entry is a DELETION (e.g. release-collation
        # is consuming an old fragment) must still require a freshly-added
        # fragment that names the change. The signal predicate is
        # "fragment file exists in the working tree after the diff", not
        # "any valid-looking fragment path is named anywhere in the diff".
        # The fixture creates no file at the candidate path — that
        # represents the deletion case.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "changelog.d/848.added.md",  # listed but NOT on disk
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-signal-missing", codes)

    def test_changelog_signal_accepts_mixed_added_and_deleted_fragments(self):
        # If the diff DELETES one fragment (release collation) but ADDS
        # another (the new PR's note), the added one should satisfy the
        # signal.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            (root / "changelog.d" / "848.added.md").write_text("note\n", encoding="utf-8")
            # 123.fixed.md is "deleted" — not on disk.
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "changelog.d/123.fixed.md",
                    "changelog.d/848.added.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_rejects_direct_changelog_edit_for_source_diff(self):
        # Codex review finding #1 (class): if `CHANGELOG.md` alone counts as a
        # signal for a source-changing diff, the rebase-storm pathology this
        # change exists to prevent is still reachable — every normal source PR
        # can hand-edit `CHANGELOG.md` and conflict with every other one. Direct
        # `CHANGELOG.md` edits are reserved for release-collation commits, which
        # by definition have no source changes.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "CHANGELOG.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-signal-missing", codes)

    def test_changelog_signal_accepts_changelog_edit_for_release_collation(self):
        # A release-collation commit modifies `CHANGELOG.md` and deletes the
        # fragments it consumed — neither path is application source, so the
        # source predicate is false and no signal is required. This branch
        # MUST stay green for `towncrier build` to land cleanly.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "CHANGELOG.md",
                    "changelog.d/848.added.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_skips_docs_only(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "docs/DEVELOPMENT_WORKFLOW.md",
                    "README.md",
                    "architecture/adrs/021-gated-agentic-development-loop.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_skips_ci_only_diff(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[".github/workflows/quality.yml"],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_skips_skills_only_diff(self):
        # Skill prose edits with no application source change are doc-only
        # for the purposes of the changelog gate.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=["skills/implement/SKILL.md"],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertNotIn("changelog-signal-missing", codes)

    def test_changelog_signal_flags_invalid_fragment_filename(self):
        # A fragment that lands in `changelog.d/` but doesn't match the
        # convention must be flagged so reviewers don't ship a fragment that
        # towncrier will silently ignore.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "changelog.d/848.bogus.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-fragment-invalid-name", codes)

    def test_read_changed_files_default_walks_full_working_tree(self):
        # Codex cycle 2 finding #1: Step 6 runs before staging/commit, so
        # the changelog gate MUST see staged, unstaged, untracked, AND
        # committed paths. Build a real git repo and exercise every
        # branch; deletions count too, since deleting source IS a change
        # that requires a release-notes signal.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir).resolve()
            import subprocess

            def git(*args: str) -> str:
                return subprocess.run(
                    ["git", "-c", "user.email=t@e", "-c", "user.name=t", *args],
                    cwd=root,
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout

            git("init", "-q")
            git("commit", "--allow-empty", "-m", "init")
            # Modified path: commit v1, then leave an unstaged edit.
            (root / "modified.txt").write_text("v1\n")
            git("add", "modified.txt")
            git("commit", "-m", "add modified.txt")
            (root / "modified.txt").write_text("v2\n")
            # Deleted path: commit, then remove from working tree.
            (root / "deleted.txt").write_text("d\n")
            git("add", "deleted.txt")
            git("commit", "-m", "add deleted.txt")
            (root / "deleted.txt").unlink()
            # Staged-only path: add AFTER the last commit so it stays
            # in the index but not in HEAD.
            (root / "staged.txt").write_text("s\n")
            git("add", "staged.txt")
            # Untracked path: never staged.
            (root / "untracked.txt").write_text("u\n")

            paths = read_changed_files(root=root)
            self.assertIn("modified.txt", paths)
            self.assertIn("staged.txt", paths)
            self.assertIn("untracked.txt", paths)
            self.assertIn(
                "deleted.txt",
                paths,
                "read_changed_files must include deleted files; deleting an "
                "application-source file is still a change that requires a "
                "changelog fragment.",
            )

    def test_changelog_signal_flags_pure_deletion_of_source(self):
        # The fragment gate must apply even when the entire diff is a
        # deletion of application source — the user-visible behavior
        # changed even if no new code was added.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/RemovedController.java",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-signal-missing", codes)

    def test_hook_walks_full_working_tree(self):
        # Codex cycle 2 finding #1 (hook side): the Stop hook ran only
        # `git diff ${BASE}...HEAD` (committed work). Step 6 runs BEFORE
        # `git commit`, so the hook must also see staged, unstaged, and
        # untracked changes to enforce the same contract as the policy.
        # Substring tests on operative verbs/flags — order and other flags
        # may legitimately vary.
        hook_path = REPO_ROOT / ".claude" / "hooks" / "verify-implementation.sh"
        text = hook_path.read_text(encoding="utf-8")
        # Committed work against the base branch.
        self.assertIn("${BASE}...HEAD", text)
        # Unstaged working-tree changes — `git diff ... HEAD` (no triple-dot).
        self.assertRegex(
            text,
            r"git diff[^\n]*\bHEAD\b(?![./])",
            "Hook must walk unstaged working-tree changes via `git diff HEAD`.",
        )
        # Staged-but-uncommitted changes.
        self.assertIn("--cached", text)
        # Untracked-but-not-ignored paths.
        self.assertIn("ls-files --others --exclude-standard", text)
        # Deletions must be included — application-source deletions are
        # changes that still require a fragment.
        self.assertIn("ACDMRTUXB", text)

    def test_hook_handles_pipefail_safely(self):
        # Codex cycle 2 finding #2: under `set -euo pipefail`, a plain
        # `grep -E` returns 1 when no match, which aborts the pipeline
        # before the hook can reach its "no source, no fragment needed"
        # path. Use `grep -c ... || true` (count form with guard) or
        # `awk` so a no-match diff cannot kill the hook. Asserting the
        # vocabulary keeps the pattern from regressing to a raw grep
        # pipeline.
        hook_path = REPO_ROOT / ".claude" / "hooks" / "verify-implementation.sh"
        text = hook_path.read_text(encoding="utf-8")
        # The hook still runs under pipefail (don't relax that).
        self.assertIn("set -euo pipefail", text)
        # And every pipeline that might match zero lines is guarded.
        # Either `grep -c ... || true` or `awk` is acceptable. The forbidden
        # form is a bare `grep -E ... | wc -l` chain with no `|| true`.
        self.assertNotRegex(
            text,
            r"\|\s*grep\s+-E\b[^|\n]*\|\s*wc\s+-l[^|\n]*\)",
            "Stop hook contains a bare `grep -E ... | wc -l` chain — under "
            "pipefail this aborts on no-match diffs. Use `grep -c ... || true` "
            "or `awk`.",
        )

    def test_changelog_signal_flags_nested_fragment_path(self):
        # Codex review finding #4 (one-off): a path like
        # `changelog.d/foo/848.added.md` must be flagged as invalid, not
        # silently skipped — towncrier will not consume nested paths, and a
        # contributor would think they had filed a fragment that never makes
        # it into the changelog.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "changelog.d/foo/848.added.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-fragment-invalid-name", codes)
            # And the nested path must not count as a signal.
            self.assertIn("changelog-signal-missing", codes)

    def test_changelog_signal_ignores_changelog_d_readme_and_template(self):
        # `changelog.d/README.md` and `changelog.d/_template.md.jinja` are
        # infrastructure, not fragments — they must not be parsed as
        # fragments and must not satisfy the signal alone.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._make_temp_repo(tmp_dir)
            self._write_canonical_fragment_infrastructure(root)
            violations = run_changelog_fragment_check(
                changed_files=[
                    "backend/src/main/java/com/keplerops/groundcontrol/api/Foo.java",
                    "changelog.d/README.md",
                ],
                root=root,
            )
            codes = {v.code for v in violations}
            self.assertIn("changelog-signal-missing", codes)
            self.assertNotIn("changelog-fragment-invalid-name", codes)

    # --- the canonical Ground-Control repo passes its own check --------------

    def test_hook_checks_fragment_existence(self):
        # Codex cycle 3 finding #1 (hook side): the host-local Stop hook
        # must apply the same "fragment file exists on disk" predicate as
        # the Python check — otherwise a release-collation diff that
        # deletes an old fragment counts as a signal for an unrelated
        # source change happening in the same PR. Look for the fragment-
        # specific existence check (the SKILL_LOG check uses `[ -f` too,
        # so we anchor on the `changelog.d` / `REPO_ROOT` context the
        # fragment check must establish).
        hook_path = REPO_ROOT / ".claude" / "hooks" / "verify-implementation.sh"
        text = hook_path.read_text(encoding="utf-8")
        self.assertRegex(
            text,
            r"\[\s*-f\s+\"\$REPO_ROOT/\$",
            "Stop hook must verify each candidate fragment exists in the "
            "working tree (e.g. `[ -f \"$REPO_ROOT/$fragment\" ]`) — "
            "otherwise a deleted fragment path counts as a signal.",
        )

    def test_towncrier_uses_repo_local_name_not_python_package(self):
        # Codex cycle 3 finding #2 (one-off): the `package` key puts
        # towncrier into Python-package metadata-discovery mode, which
        # this repo (a Java/Spring backend + React frontend) does not
        # satisfy. `name` is the non-Python-project equivalent.
        import tomllib

        with (REPO_ROOT / "towncrier.toml").open("rb") as handle:
            data = tomllib.load(handle)
        tc = data.get("tool", {}).get("towncrier", {})
        self.assertNotIn(
            "package",
            tc,
            "towncrier.toml must not declare `package` — this is a "
            "non-Python project; use `name` instead.",
        )

    def test_towncrier_toml_in_repo_loads_and_has_required_keys(self):
        # tomllib is stdlib on 3.11+.
        import tomllib

        toml_path = REPO_ROOT / "towncrier.toml"
        self.assertTrue(toml_path.exists(), "towncrier.toml must exist at repo root")
        with toml_path.open("rb") as handle:
            data = tomllib.load(handle)

        tool = data.get("tool", {})
        towncrier_section = tool.get("towncrier", {})
        self.assertEqual(towncrier_section.get("directory"), "changelog.d")
        self.assertEqual(towncrier_section.get("filename"), "CHANGELOG.md")
        self.assertEqual(
            towncrier_section.get("template"), "changelog.d/_template.md.jinja"
        )
        self.assertIn("towncrier", str(towncrier_section.get("start_string", "")))
        # Issue format must produce GitHub-style `(#NNN)` so collated entries
        # link back to issues consistently with prior `[0.116.x]` history.
        self.assertEqual(towncrier_section.get("issue_format"), "(#{issue})")

        type_section = towncrier_section.get("type", [])
        type_names = {entry.get("name", "").lower() for entry in type_section}
        expected = {"security", "added", "changed", "deprecated", "removed", "fixed"}
        self.assertTrue(
            expected.issubset(type_names),
            f"towncrier.toml [tool.towncrier.type] missing required names: {expected - type_names}",
        )

    def test_changelog_marker_present_in_repo_changelog(self):
        changelog_text = (REPO_ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        self.assertIn("<!-- towncrier release notes start -->", changelog_text)

    def test_gitattributes_contains_changelog_union_merge(self):
        path = REPO_ROOT / ".gitattributes"
        self.assertTrue(path.exists(), ".gitattributes must exist at repo root")
        contents = path.read_text(encoding="utf-8")
        self.assertIn("CHANGELOG.md", contents)
        self.assertIn("merge=union", contents)

    def test_hook_gates_on_application_source_predicate(self):
        # Codex review finding #2 (class): the host-local Stop hook must
        # apply the same `_diff_touches_application_source` predicate the
        # repo-native policy uses — otherwise it blocks docs-only / CI-only
        # / metadata diffs that the policy explicitly permits.
        hook_path = REPO_ROOT / ".claude" / "hooks" / "verify-implementation.sh"
        text = hook_path.read_text(encoding="utf-8")
        # The hook must reference every source path prefix the policy
        # gates on. If a prefix is added to the policy, this test forces
        # the hook to learn about it too.
        for prefix in (
            "backend/src/main/",
            "backend/src/test/",
            "frontend/src/",
            "mcp/",
        ):
            self.assertIn(
                prefix,
                text,
                f"Stop hook missing source prefix '{prefix}' — drifted from "
                "tools/policy/checks.py::_SOURCE_PATH_PREFIXES.",
            )
        # `tools/` is application source EXCEPT for `tools/policy/` and
        # `tools/tests/`; the hook must encode the same carve-out.
        self.assertIn("tools/policy/", text)
        self.assertIn("tools/tests/", text)

    def test_hook_regex_matches_policy_vocabulary(self):
        # `.claude/hooks/verify-implementation.sh` and the Python policy
        # check are two enforcement layers for the same convention — the
        # vocabulary MUST stay in sync, or one layer would accept a
        # fragment the other rejects.
        hook_path = REPO_ROOT / ".claude" / "hooks" / "verify-implementation.sh"
        self.assertTrue(hook_path.exists(), "Stop hook must exist")
        text = hook_path.read_text(encoding="utf-8")
        for ftype in CHANGELOG_FRAGMENT_TYPES:
            self.assertIn(
                ftype,
                text,
                f"Stop hook regex missing fragment type '{ftype}' — drifted from "
                f"tools/policy/checks.py::CHANGELOG_FRAGMENT_TYPES.",
            )
        self.assertIn(
            r"^changelog\.d/",
            text,
            "Stop hook regex must anchor fragment paths under changelog.d/.",
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
# Following the same "parser-over-fixed-grammar" pattern the changelog
# fragment check uses for its doc-only carve-out justification at
# `checks.py::run_changelog_fragment_check`.
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


if __name__ == "__main__":
    unittest.main()
