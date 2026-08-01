---
stage_id: test_quality_review
step: "Step 6.6"
tier: medium
---

# Step 6.6: Pre-push Test-Quality Review

The primary invocation session owns this loop end-to-end. Ground Control does
not spawn or require a subagent for routine review work. The primary runs
[_review-loop-rules.md](_review-loop-rules.md) against
`gc_test_quality_review_cycle` until clean or cap-reached. The reviewer remains
a server-side background job; that process boundary is not agent delegation.

This step moved from post-PR (former Step 13) to pre-push by issue #906 so the PR opens with **both** AI-assisted reviewers clean. Without the move, a reviewer scanning the PR sees a stale picture (codex clean, test-quality pending), and any test-quality fix costs an extra commit + push + CI run + SonarCloud re-analyze cycle. Pre-push, it's just re-stage + re-run.

## Primary-session procedure

1. Stage everything with `git add -A`.
2. Generate one bounded `idempotency_key` for this logical cycle attempt. Call
   `gc_test_quality_review_cycle` with the key, `repo_path`, `issue_number`,
   and `async=true`. Reuse that key only when the start response was lost; use
   a new key after a terminal attempt and intentional tree change.
3. Poll `gc_codex_job` until the background reviewer returns its terminal
   envelope. A missing/expired handle requires an issue-thread refresh and
   durable-record reconciliation before selecting a new key. Cycle jobs are
   non-cancellable because cancellation cannot roll back GitHub records.
4. Dispatch on `next_action` exactly as specified by
   [_review-loop-rules.md](_review-loop-rules.md). Fix real findings in this
   session, run proportionate targeted tests while iterating, and do not echo
   findings instead of fixing them.
5. Advance only after the durable decision-record post succeeds.

When the cycle finishes:
- `status: "clean"` → advance to Phase C (Step 7).
- `status: "escalated"` → if `workflow.review_disposition.enabled` is true, run the automated cap disposition (`gc_review_cap_disposition` with `reviewer="test-quality"`) per [_review-loop-rules.md](_review-loop-rules.md) § "Automated cap disposition" before escalating: `proceed` advances to Phase C, `one_more_cycle` re-invokes this step with `override_cap=true` + `auto_grant=true`, `escalate_to_human` (or `shadow` mode) summarizes to the user and waits. With the knob off, summarize to the user and wait. Do NOT push commits while waiting.
- `status: "capped"` → summarize to the user. They may authorize an over-cap cycle; otherwise treat as terminal.

## Return contract

Return the compact `{status, cycles_run, summary, commit_shas,
decision_record_urls, escalation_reason}` envelope to the next workflow step.

## Underlying contract (issue #884 / #906)

The cycle wrapper (`gc_test_quality_review_cycle`) wraps the underlying `gc_test_quality_review` tool AND `gc_post_decision_record`. The contract this step is required to honor - enforced by `tools/policy/checks.py::run_test_quality_decision_record_contract`, regression target for issue #884 - is preserved verbatim because the cycle wrapper implements it internally:

- The underlying `gc_test_quality_review` tool returns an envelope including `next_action`. The cycle wrapper forwards `next_action` through its envelope so dispatch is structured, not free-prose.
- After every cycle the cycle wrapper auto-posts the canonical durable-record marker via `gc_post_decision_record` with `reviewer: "test-quality"` (the enum literal that disjoint marker families anchor on).
- On a clean cycle the wrapper posts `gc_post_decision_record` with `findings: []` - a clean cycle MUST still post a decision record (issue #884: silent advance is the regression target). The `decision_record_url` in the wrapper's return envelope MUST be non-null before the workflow proceeds.
- **Success precondition.** Advance to Phase C ONLY after the cycle wrapper's envelope reports the decision-record post returned `ok: true`. On `ok: false` (sensitive-content rejection, body-size cap, `gh` posting failure, network), fix the underlying tooling issue and retry the post - do not advance with the durable marker missing.
- **Fix findings in the same turn.** When the cycle envelope returns `fix_findings_and_reinvoke`, the primary reads it as a directive, fixes the findings in the same turn, and does not stop to echo them back as a status report.

## Notes

- **Cap source**: the cycle tool reads `workflow.test_quality_review.pre_push_cap` from `.ground-control.yaml`; default 1. The cap is enforced at the MCP layer.
- **Authentication**: the underlying `gc_test_quality_review` shells out to `claude --print`; the MCP tool's exec wrapper strips `ANTHROPIC_API_KEY` from the subprocess env so the CLI uses the host's OAuth session. See `docs/DEVELOPMENT_WORKFLOW.md` § "Test-quality review engine" for the full mechanism.
- **Skip predicate**: skip this step only if the diff has no test files (no `**/test/**`, `**/*Test.java`, `**/*.test.js`, etc.). When in doubt, run it.
- **Cross-cycle marker family**: the test-quality cycle counter is anchored to the issue thread via the `gc:test-quality-review-cycle` marker family - disjoint from the codex pre-push markers (`gc:codex-review-cycle`) so the two reviewers never cross-count.
