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

class TestQualityDecisionRecordContractTestPart2(unittest.TestCase):
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
    def test_doc_coverage_skips_gracefully_when_pr_body_unavailable(self):
        """When pr_body is None the check skips without raising."""
        violations = run_documentation_coverage_check(
            ["mcp/ground-control/lib.js"],
            pr_body=None,
        )
        # Graceful skip — no hard fail, no fixture-error either
        codes = [v.code for v in violations]
        self.assertNotIn("doc-coverage-outcome-missing", codes)
