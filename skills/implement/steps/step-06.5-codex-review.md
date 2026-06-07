---
stage_id: review_cycle_1_consume
step: "Step 6.5"
tier: high
---

# Step 6.5: Pre-push Codex Review (single subagent invocation)

This step is driven by **one subagent invocation** that owns the entire codex review loop end-to-end. The parent never sees verbatim review prose or per-finding bodies — only a short envelope from the subagent.

Per issue #934 item 2, the parent dispatches a single subagent for this step. The subagent runs the convergence loop in [_review-loop-rules.md](_review-loop-rules.md) against the cycle tool (`gc_codex_review_cycle`, issue #934 item 3) until the MCP dispatcher returns clean advance, structured escalation, or terminal escalation, then returns the envelope below.

The codex review is THE review pass for the PR — there is no second post-push codex review (see issue #804). Merge-commit drift relative to the target branch is the responsibility of CI (compile/tests/integration) and provider-neutral remote-quality gates, not a separate codex pass.

## Subagent prompt template

The orchestrator spawns the subagent with this prompt (substituting `{issue_number}`, `{repo_path}`):

> Drive the **codex pre-push review** for issue {issue_number} to completion. Apply the canonical review loop rules at `skills/implement/steps/_review-loop-rules.md`.
>
> Loop:
> 1. Stage everything with `git add -A`.
> 2. Call the `gc_codex_review_cycle` MCP tool with `repo_path={repo_path}`, `issue_number={issue_number}`, `uncommitted=true`, `async=true`. It returns immediately with `{ok:true, status:"running", job_id}` — the correctness, security, and architecture lenses run in fresh contexts with edit tools removed, in a background job so the multi-minute review never trips the MCP client tool-call timeout (issue #937).
> 3. Poll the job: call `gc_codex_job` with `action="poll"`, `job_id=<the job_id from step 2>`. While it returns `status:"running"`, sleep ~60s and poll again (a codex review legitimately runs several minutes). When it returns `status:"done"`, the cycle envelope is in the response's `result` field. If a poll returns `error:"job_not_found"` (job expired or MCP server restarted), restart from step 2. To abandon a stuck job, call `gc_codex_job` with `action="cancel"`.
> 4. Read the cycle envelope from the poll response's `result` (`{ok, reviewer, cycle, configured_cap, cap, status, next_action, dispatcher, findings_summary, findings_record_url, decision_record_url}`). Do NOT echo verbatim review prose — that stays server-side in the underlying review's findings record. Dispatch on the dispatcher-computed `next_action`:
>    - `advance_to_next_phase` → return `status: "clean"`. The decision record was auto-posted.
>    - `fix_findings_and_reinvoke` → classify findings (one-off vs class), fix them per the loop rules, self-verify locally (`cfg.workflow.completion_command`, `make policy`, the relevant test suite), `git add -A`, then re-invoke the cycle tool.
>    - `post_structured_decision_aid_and_escalate` → return `status: "escalated"` with the dispatcher decision aid surfaced; do not re-invoke unless the owner authorizes `override_cap=true` + `override_reason`.
>    - `record_terminal_escalation` → return `status: "terminal"` with the terminal reason.
> 5. `wontfix` / `not-applicable` overrides: the cycle wrapper auto-posts `decision: "fix"`. If user authorization for a wontfix is obtained mid-loop, call `gc_post_decision_record` directly with the override AFTER the cycle, not through the wrapper.
>
> Return ONLY this envelope (no verbatim findings, no command output):
>
> ```json
> {
>   "status": "clean" | "escalated" | "terminal",
>   "cycles_run": <int>,
>   "summary": "<one-line summary of what was found and fixed>",
>   "commit_shas": [],
>   "decision_record_urls": [ "<URL per cycle>" ],
>   "escalation_reason": null,
>   "decision_aid_url": null
> }
> ```

## Parent-side handling of the envelope

When the subagent returns:
- `status: "clean"` → advance to Step 6.6.
- `status: "escalated"` → summarize the structured decision aid to the user and wait. Do NOT push commits while waiting.
- `status: "terminal"` → summarize the terminal escalation and stop.

## Return contract (from this step file's perspective)

This step file IS the subagent's instruction set. The orchestrator receives the envelope above directly from the subagent's return value. There is no separate "wrap the envelope" step.

## Notes

- **Cap source**: the cycle tool reads `workflow.codex_review.pre_push_cap` from `.ground-control.yaml`; configured values below 2 run with the ADR-031 effective cap of 2. The cap is enforced at the MCP layer (issue #794 / #796), NOT in subagent prose.
- **Findings record**: every successful cycle posts a verbatim findings comment to the resolved issue thread (per ADR-029). The comment carries the cycle/cap/mode header and the correctness, security, and architecture reviewers' verbatim text. The cycle wrapper's envelope includes `findings_record_url` so the subagent can hand it back if needed — but the parent does not need to read it.
- **Skip predicate**: skip this step only if the diff is so trivial (one-liner typo fix) that codex would have nothing to find. When in doubt, run it.
