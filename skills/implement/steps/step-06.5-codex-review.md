---
stage_id: review_cycle_1_consume
step: "Step 6.5"
tier: high
---

# Step 6.5: Pre-push Codex Review (single subagent invocation)

This step is driven by **one subagent invocation** that owns the entire codex review loop end-to-end. The parent never sees verbatim review prose or per-finding bodies — only a short envelope from the subagent.

Per issue #934 item 2, the parent dispatches a single subagent for this step. The subagent runs the loop in [_review-loop-rules.md](_review-loop-rules.md) against the cycle tool (`gc_codex_review_cycle`, issue #934 item 3) until clean or cap-reached, then returns the envelope below.

The codex review is THE review pass for the PR — there is no second post-push codex review (see issue #804). Merge-commit drift relative to the target branch is the responsibility of CI (compile/tests/integration) and SonarCloud (quality), not a separate codex pass.

## Subagent prompt template

The orchestrator spawns the subagent with this prompt (substituting `{issue_number}`, `{repo_path}`):

> Drive the **codex pre-push review** for issue {issue_number} to completion. Apply the canonical review loop rules at `skills/implement/steps/_review-loop-rules.md`.
>
> Loop:
> 1. Stage everything with `git add -A`.
> 2. Call the `gc_codex_review_cycle` MCP tool with `repo_path={repo_path}`, `issue_number={issue_number}`, `uncommitted=true`. The tool runs the dual reviewers (core + security) AND auto-posts the per-cycle decision record.
> 3. Read the returned envelope (`{ok, reviewer, cycle, cap, status, next_action, findings_summary, findings_record_url, decision_record_url}`). Do NOT echo verbatim review prose — that stays server-side in the underlying review's findings record.
> 4. Dispatch on `next_action`:
>    - `post_clean_decision_record_and_advance_to_phase_c` → return `status: "clean"`. The decision record was auto-posted.
>    - `fix_findings_and_reinvoke` → classify findings (one-off vs class), fix them per the loop rules, self-verify locally (`cfg.workflow.completion_command`, `make policy`, the relevant test suite), `git add -A`, then re-invoke the cycle tool.
>    - `fix_findings_then_summarize_and_escalate` (last-in-cap) → fix and self-verify, but do NOT re-invoke; return `status: "escalated"`.
>    - `post_summary_and_escalate_to_user` (cap-refused) → no fixes; return `status: "capped"`.
> 5. `wontfix` / `not-applicable` overrides: the cycle wrapper auto-posts `decision: "fix"`. If user authorization for a wontfix is obtained mid-loop, call `gc_post_decision_record` directly with the override AFTER the cycle, not through the wrapper.
>
> Return ONLY this envelope (no verbatim findings, no command output):
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
- `status: "clean"` → advance to Step 6.6.
- `status: "escalated"` → summarize to the user and wait. Do NOT push commits while waiting.
- `status: "capped"` → summarize to the user. They may authorize an over-cap cycle (rerun this step with `override_cap=true` + `override_reason`); otherwise treat as terminal.

## Return contract (from this step file's perspective)

This step file IS the subagent's instruction set. The orchestrator receives the envelope above directly from the subagent's return value. There is no separate "wrap the envelope" step.

## Notes

- **Cap source**: the cycle tool reads `workflow.codex_review.pre_push_cap` from `.ground-control.yaml`; default 1 per issue #906. The cap is enforced at the MCP layer (issue #794 / #796), NOT in subagent prose.
- **Findings record**: every successful cycle posts a verbatim findings comment to the resolved issue thread (per ADR-029). The comment carries the cycle/cap/mode header and both reviewers' verbatim text. The cycle wrapper's envelope includes `findings_record_url` so the subagent can hand it back if needed — but the parent does not need to read it.
- **Skip predicate**: skip this step only if the diff is so trivial (one-liner typo fix) that codex would have nothing to find. When in doubt, run it.
