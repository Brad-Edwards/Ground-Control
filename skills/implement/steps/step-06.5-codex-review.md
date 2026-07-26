---
stage_id: review_cycle_1_consume
step: "Step 6.5"
tier: high
---

# Step 6.5: Pre-push Codex Review

The primary invocation session owns this review loop end-to-end. Ground Control
does not spawn or require a subagent for routine review work. The primary runs
the loop in [_review-loop-rules.md](_review-loop-rules.md) against
`gc_codex_review_cycle` until clean or cap-reached. The review remains a
server-side background job; that process boundary is not agent delegation.

The codex review is THE review pass for the PR - there is no second post-push codex review (see issue #804). Merge-commit drift relative to the target branch is the responsibility of CI (compile/tests/integration) and SonarCloud (quality), not a separate codex pass.

## Primary-session procedure

1. Stage everything with `git add -A`.
2. Call `gc_codex_review_cycle` with `repo_path`, `issue_number`,
   `uncommitted=true`, and `async=true`.
3. Poll `gc_codex_job` until the background review returns its terminal
   envelope. Restart a missing/expired job; cancel only a genuinely stuck job.
4. Dispatch on `next_action` exactly as specified by
   [_review-loop-rules.md](_review-loop-rules.md). Fix real findings in this
   session, run proportionate targeted tests while iterating, and do not echo
   findings instead of fixing them.
5. Keep verbatim findings in the server-posted durable record. Cache only the
   compact status, cycle count, summary, and decision-record URLs.

When the cycle finishes:
- `status: "clean"` → advance to Step 6.6.
- `status: "post_failed"` with `error: "review_coverage_incomplete"` → the
  review did not cover the whole diff (issue #1414). No findings record,
  decision record, or cycle marker was written and no cycle was consumed, so
  re-invoke this step. Do not treat it as clean and do not escalate a cap that
  was never spent.
- `status: "escalated"` → if `workflow.review_disposition.enabled` is true, run the automated cap disposition (`gc_review_cap_disposition`) per [_review-loop-rules.md](_review-loop-rules.md) § "Automated cap disposition" before escalating: `proceed` advances to Step 6.6, `one_more_cycle` re-invokes this step with `override_cap=true` + `auto_grant=true`, `escalate_to_human` (or `shadow` mode) summarizes to the user and waits. With the knob off, summarize to the user and wait. Do NOT push commits while waiting.
- `status: "capped"` → summarize to the user. They may authorize an over-cap cycle (rerun this step with `override_cap=true` + `override_reason`); otherwise treat as terminal.

## Return contract

Return the compact `{status, cycles_run, summary, commit_shas,
decision_record_urls, escalation_reason}` envelope to the next workflow step.

## Notes

- **Cap source**: the cycle tool reads `workflow.codex_review.pre_push_cap` from `.ground-control.yaml`; default 1 per issue #906. The cap is enforced at the MCP layer (issue #794 / #796), not in agent prose.
- **Findings record**: every successful cycle posts a verbatim findings comment to the resolved issue thread (per ADR-029). The comment carries the cycle/cap/mode header, the `Diff mode` line describing how the diff reached the reviewers, and both reviewers' verbatim text. The primary session needs only the compact cycle envelope.
- **Oversized diffs**: a diff larger than one prompt is split server-side into bounded inline slices that both reviewers read within this single cycle (issue #1414). Expect a longer wall-clock for a large diff and a `diff_mode: "manifest"` envelope; that is not a degraded review and needs no caller action.
- **Skip predicate**: skip this step only if the diff is so trivial (one-liner typo fix) that codex would have nothing to find. When in doubt, run it.
