---
stage_id: test_quality_review
step: "Step 6.6"
tier: medium
---

# Step 6.6: Pre-push Test-Quality Review (single subagent invocation)

This step is driven by **one subagent invocation** that owns the entire test-quality review loop end-to-end. The parent never sees verbatim review prose or per-finding bodies — only a short envelope from the subagent.

Per issue #934 item 2, the parent dispatches a single subagent for this step. The subagent runs the loop in [_review-loop-rules.md](_review-loop-rules.md) against the cycle tool (`gc_test_quality_review_cycle`, issue #934 item 3) until clean or cap-reached, then returns the envelope below.

This step moved from post-PR (former Step 13) to pre-push by issue #906 so the PR opens with **both** AI-assisted reviewers clean. Without the move, a reviewer scanning the PR sees a stale picture (codex clean, test-quality pending), and any test-quality fix costs an extra commit + push + CI run + SonarCloud re-analyze cycle. Pre-push, it's just re-stage + re-run.

## Subagent prompt template

The orchestrator spawns the subagent with this prompt (substituting `{issue_number}`, `{repo_path}`):

> Drive the **test-quality pre-push review** for issue {issue_number} to completion. Apply the canonical review loop rules at `skills/implement/steps/_review-loop-rules.md`.
>
> Loop:
> 1. Stage everything with `git add -A`.
> 2. Call the `gc_test_quality_review_cycle` MCP tool with `repo_path={repo_path}`, `issue_number={issue_number}`. The tool runs the test-quality reviewer AND auto-posts the per-cycle decision record under `reviewer: "test-quality"`.
> 3. Read the returned envelope (`{ok, reviewer, cycle, cap, status, next_action, findings_summary, findings_record_url, decision_record_url}`). Do NOT echo verbatim review prose.
> 4. Dispatch on `next_action`:
>    - `post_clean_decision_record_and_advance_to_phase_c` → return `status: "clean"`. The decision record was auto-posted.
>    - `fix_findings_and_reinvoke` → classify findings (one-off vs class) — test-quality findings often arrive without a classification, so classify yourself first. Fix them per the loop rules in the same turn (do NOT stop and echo findings to the user as a status report — that is the #884 regression in a different shape). Self-verify locally (`cfg.workflow.completion_command`, `make policy`, the relevant test suite), `git add -A`, then re-invoke the cycle tool.
>    - `fix_findings_then_summarize_and_escalate` (last-in-cap) → fix and self-verify, but do NOT re-invoke; return `status: "escalated"`.
>    - `post_summary_and_escalate_to_user` (cap-refused) → no fixes; return `status: "capped"`.
> 5. The cycle wrapper auto-posts `decision: "fix"` per finding. Advance ONLY after the cycle envelope's `decision_record_url` is non-null on a clean cycle — the durable record per ADR-029 / issue #884 must be in place before the workflow proceeds.
>
> Return ONLY this envelope:
>
> ```json
> {
>   "status": "clean" | "escalated" | "capped",
>   "cycles_run": <int>,
>   "summary": "<one-line summary of what was found and fixed>",
>   "commit_shas": [],
>   "decision_record_urls": [ "<URL per cycle>" ],
>   "escalation_reason": null
> }
> ```

## Parent-side handling of the envelope

When the subagent returns:
- `status: "clean"` → advance to Phase C (Step 7).
- `status: "escalated"` → summarize to the user and wait. Do NOT push commits while waiting.
- `status: "capped"` → summarize to the user. They may authorize an over-cap cycle; otherwise treat as terminal.

## Return contract (from this step file's perspective)

This step file IS the subagent's instruction set. The orchestrator receives the envelope above directly from the subagent.

## Underlying contract (issue #884 / #906)

The cycle wrapper (`gc_test_quality_review_cycle`) wraps the underlying `gc_test_quality_review` tool AND `gc_post_decision_record`. The contract this step is required to honor — enforced by `tools/policy/checks.py::run_test_quality_decision_record_contract`, regression target for issue #884 — is preserved verbatim because the cycle wrapper implements it internally:

- The underlying `gc_test_quality_review` tool returns an envelope including `next_action`. The cycle wrapper forwards `next_action` through its envelope so dispatch is structured, not free-prose.
- After every cycle the cycle wrapper auto-posts the canonical durable-record marker via `gc_post_decision_record` with `reviewer: "test-quality"` (the enum literal that disjoint marker families anchor on).
- On a clean cycle the wrapper posts `gc_post_decision_record` with `findings: []` — a clean cycle MUST still post a decision record (issue #884: silent advance is the regression target). The `decision_record_url` in the wrapper's return envelope MUST be non-null before the workflow proceeds.
- **Success precondition.** Advance to Phase C ONLY after the cycle wrapper's envelope reports the decision-record post returned `ok: true`. On `ok: false` (sensitive-content rejection, body-size cap, `gh` posting failure, network), fix the underlying tooling issue and retry the post — do not advance with the durable marker missing.
- **Fix findings in the same turn.** When the cycle envelope returns `fix_findings_and_reinvoke` (the subagent reads this as a directive, not as prose to echo), the agent MUST fix the findings in the same agent turn — do not stop, do not echo the findings back as a status report. The whole point of the review is to fix the tests; agents MUST NOT echo findings to the user without fixing them — that is the issue #884 regression in a different shape.

## Notes

- **Cap source**: the cycle tool reads `workflow.test_quality_review.pre_push_cap` from `.ground-control.yaml`; default 1. The cap is enforced at the MCP layer.
- **Authentication**: the underlying `gc_test_quality_review` shells out to `claude --print`; the MCP tool's exec wrapper strips `ANTHROPIC_API_KEY` from the subprocess env so the CLI uses the host's OAuth session. See `docs/DEVELOPMENT_WORKFLOW.md` § "Test-quality review engine" for the full mechanism.
- **Skip predicate**: skip this step only if the diff has no test files (no `**/test/**`, `**/*Test.java`, `**/*.test.js`, etc.). When in doubt, run it.
- **Cross-cycle marker family**: the test-quality cycle counter is anchored to the issue thread via the `gc:test-quality-review-cycle` marker family — disjoint from the codex pre-push markers (`gc:codex-review-cycle`) so the two reviewers never cross-count.
