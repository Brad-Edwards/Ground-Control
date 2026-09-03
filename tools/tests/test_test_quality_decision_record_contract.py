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
    read_changed_files,
    run_adr_guard,
    _trigger_is_in_scope,
    run_documentation_coverage_check,
    run_repo_identity_drift,
    run_no_deferral_disposition_check,
    run_pr_body_check,
    run_test_quality_decision_record_contract,
    run_version_mirror_consistency_check,
    run_workflow_routing_contract,
    run_implement_execution_contract,
)

if __name__ == "__main__":
    unittest.main()

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
    def test_extract_step_section_returns_section_text(self):
        body = "intro\n\n### Step 6.6: Pre-push Test-Quality Review\n\nbody line\n\n### Step 7: Next\n"
        section = extract_step_section(body, "Step 6.6")
        self.assertIsNotNone(section)
        self.assertIn("body line", section)
        self.assertNotIn("Step 7", section)
    def test_extract_step_section_returns_none_when_missing(self):
        body = "### Step 12: Other\n\nbody\n"
        self.assertIsNone(extract_step_section(body, "Step 6.6"))
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
