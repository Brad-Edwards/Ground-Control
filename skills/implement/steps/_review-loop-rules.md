# Review loop rules (canonical)

This document is the single source of truth for how the pre-push reviewers (codex at Step 6.5, test-quality at Step 6.6) drive their fix loops. Step 6.5 and Step 6.6 reference this file by path; the orchestrator does as well. Do not restate these rules elsewhere — keep them here.

Both AI-assisted reviews run **pre-push**: codex review at Step 6.5, test-quality review at Step 6.6. There is no post-PR review step (former Steps 12/13 were merged out by issues #804 and #906). Both follow the **same convergence loop**, driven by the cycle wrapper tools (`gc_codex_review_cycle` and `gc_test_quality_review_cycle`, issue #934) and the deterministic ADR-031 dispatcher in the MCP layer.

The review set has four lenses:

- **correctness** (Codex core): requirement, contract, plan, and expected behavior.
- **security** (Codex security): trust boundaries, authorization, injection, secrets, dependency risk, and attacker-model obligations.
- **architecture** (Codex architecture): binding ADRs, package boundaries, shared helpers, and workflow contracts.
- **test-strength** (test-quality): whether tests exercise the public contract, using `gc_run_gates` mutation / diff-coverage results when the gate manifest supplies them.

Reviewer prose never decides whether the workflow advances. Each lens returns a verdict envelope (`verdict`, `reviewer_lens`, `findings[]`, `blocking[]`, `notes[]`); the MCP dispatcher computes `next_action` from those envelopes, gate results, provider-missing telemetry, confirmation state, and cap state.

## The loop

1. **Invoke the cycle tool as a background job, then poll.** Call the cycle wrapper (`gc_codex_review_cycle` / `gc_test_quality_review_cycle`) with `async=true`; it returns `{ok:true, status:"running", job_id}` immediately. A review legitimately runs for several minutes, so it runs in a background job rather than blocking the MCP call past the client's tool-call timeout (issue #937). Poll `gc_codex_job` with `action="poll"` and the `job_id` every ~60s until it returns `status:"done"`; the cycle envelope is then in the poll response's `result` field — `{ok, reviewer, cycle, configured_cap, cap, status, next_action, dispatcher, findings_summary, findings_record_url, decision_record_url}`. The cycle wrapper still runs the underlying review AND auto-posts the per-cycle decision record; `async` only changes how the agent waits for the result. A poll returning `error:"job_not_found"` means the job expired or the MCP server restarted — start a fresh job. `gc_codex_job` with `action="cancel"` abandons a stuck job and kills its child process. Verbatim review prose stays server-side in the underlying review's findings record.
2. **Read the FULL envelope.** Do not stop after the first field. `configured_cap` records the repo setting; `cap` is the effective ADR-031 cap and is never below 2. `dispatcher` carries the normalized lens verdicts, gate state, confirmation state, and any structured decision aid. `findings_summary` carries `one_off_count`, `class_count`, and `top_categories[]` (grouped by `category.shape`, summed instance counts).
3. **Classify each finding before touching anything: `one-off` or `class`.** Codex review supplies `classification` (and, for `class` findings, `category = {shape, instances}`) on each finding object. The cycle wrapper preserves the classification when auto-building the decision record entries. If a finding arrived without a classification (e.g. test-quality review, which does not yet emit it consistently), classify it yourself first.
   - **`one-off`** — this exact site, no analogues. Apply the named fix to the named site. This is the existing path.
   - **`class`** — this site is one instance of a recurring pattern (the same brittle construction, the same missing pre-condition, the same bypassed helper). **STOP. Do not apply the named fix to the named site yet.** Instead:
     1. Re-read the category's `shape` — what makes a site an instance? What pre-condition fails? What invariant is violated?
     2. Sweep the diff **and adjacent repo code where the category plausibly extends** for every instance — the ones codex listed in `category.instances` *and* any it missed.
     3. Design the fix to address the **category**, not the symptom: a structural gate, a shared helper, a parameterization, a single point of repair, an API change. The fix should be one place, not N.
     4. Apply that single design to every instance at once.
     5. Only then re-run the cycle tool.

     A `class` finding that you fixed only on the codex-named site is a process violation in the same shape as silent deferral — it leaves the category un-addressed, and the next review cycle surfaces another instance, burning a cycle the cap is not meant to absorb. If a category genuinely spans 5+ files outside the current feature's scope, that is the architectural-change escalation point — STOP, post the category + the affected files as an issue comment, and ask the user.

4. **Fix every finding, pre-existing or not.** The zero-deferral rule applies: there is no `defer` decision — not "out of scope for this PR", not "follow-up issue to track it", not "addressed in a subsequent PR", not "deferred to a later iteration", not "TBD later" in a closing comment. Filing a tracking issue does **not** convert a deferral into a valid disposition. The PreToolUse hook (`.claude/hooks/block-defer-language.py`) and `bin/policy` enforce this mechanically; the contract is fix-or-escalate. If you think a finding is dangerous to fix, unwise in context, or a false positive, STOP, post your reasoning as an issue comment with `decision: <fix|wontfix|not-applicable>` and rationale, and ask the user. Wait for their answer; do not push commits while the question is open. `wontfix` requires explicit user approval; `not-applicable` is for findings that don't actually apply (false positive on this codebase, finding outside the diff's scope, etc.).

5. **Decision records are auto-posted by the cycle tool.** The cycle wrapper (`gc_codex_review_cycle` / `gc_test_quality_review_cycle`) posts the per-cycle decision record automatically when the cycle ran. Every finding gets `decision: "fix"` with an auto-rationale — that is the only decision the cycle tool can record without user authorization. The `decision_record_url` is in the returned envelope.

   - **`wontfix` / `not-applicable` overrides.** If the agent obtains user authorization for a `wontfix` or marks a finding `not-applicable` with rationale, call `gc_post_decision_record` directly (with `user_authorization` for wontfix) AFTER the cycle, not through the wrapper. The wrapper handles only the auto-fix common path.
   - **`class` finding rationale.** When fixing a class, the `wontfix`/`not-applicable` decision record (if any override is filed) should explain how the category was closed, not just that the named site was patched. The auto-fix decision record's auto-rationale is acceptable for `class` findings because the next cycle's reviewer-clean is what proves the category was closed.

6. **Self-verify the fix locally before re-invoking the cycle tool.** Re-run the relevant test suite, the completion gate (`cfg.workflow.completion_command`), and `make policy` after every fix. Local verification proves the fix does the agent's intended thing — but the reviewer's re-read is what catches what the agent didn't intend, including defects in the fix code itself.

7. **Dispatch on `next_action`, do not infer from prose.** The dispatcher returns exactly one of these actions:

   - `advance_to_next_phase` — all blocking gates are satisfied, all blocking lenses report `ship`, and the durable decision record is present. Advance in the same turn.
   - `fix_findings_and_reinvoke` — fix every finding, self-verify, re-stage (`git add -A`), and re-invoke the cycle tool. This is the only dirty-loop action. **Cycle 1 must never collapse into escalation because findings exist.**
   - `post_structured_decision_aid_and_escalate` — a real cap has been reached with unresolved findings or gates. Post/surface the dispatcher decision aid (cycle history, unresolved findings, severity trend, new categories, confirmation status, gate state, and recommendation) and wait for owner authorization. Do not keep looping unless the owner authorizes `override_cap=true` + `override_reason`.
   - `record_terminal_escalation` — a terminal condition exists before another review can run. Record the terminal escalation and stop.

   The status field mirrors the dispatcher decision for coarse branching (`clean`, `findings`, `escalated`, `terminal`, or tool failure states), but `next_action` is the authoritative directive. Caps bound review depth; they do not replace the fix-and-re-review convergence loop. Severity-weighted early-stop is allowed only when every blocking gate is satisfied and the loop is clean; it can never advance a dirty loop.

## Update succinctness (canonical)

A GitHub update gives exactly what's needed — not more, not less. No restating context the reader already has, no padding sections, no hedging prose.

This rule is mechanically enforced by byte caps on the three renderer tools in `mcp/ground-control/lib.js` (`PR_BODY_SUMMARY_MAX`, `FINAL_REPORT_SUMMARY_MAX`, `FINAL_REPORT_REVIEW_SUMMARY_MAX`). Prose elsewhere that references this rule does so by path pointer — do not duplicate the rule body.

## Local-only iteration

For every cycle, after applying fixes the agent must update the tree the reviewer sees BEFORE re-running:

- **Step 6.5 (pre-push codex review)** is local-only. Re-stage with `git add -A` and re-invoke; do NOT commit or push between cycles. The pre-push codex review reads the staged + unstaged diff against the base branch.
- **Step 6.6 (pre-push test-quality review)** is also local-only — moved pre-push by issue #906. Same re-stage-then-re-invoke loop as Step 6.5; do NOT commit or push between cycles.

Decision records (`gc_post_decision_record`) are posted per cycle for both reviewers via the cycle wrapper — that's the durable record per ADR-029 — but neither review needs a fix-commit since iteration is local.

## Subagent envelope

The orchestrator drives each reviewer through a single subagent invocation (issue #934, item 2). The subagent runs the loop above to completion and returns a compact envelope:

```json
{
  "status": "clean" | "escalated" | "terminal",
  "cycles_run": <int>,
  "summary": "<one-line summary of what was found and fixed>",
  "commit_shas": [],
  "decision_record_urls": [ "<URL per cycle>" ],
  "escalation_reason": null,
  "decision_aid_url": null
}
```

`commit_shas` is empty pre-push (no commits between cycles). `escalation_reason` is null when `status: "clean"`; a string when `status: "escalated"` or `status: "terminal"`. `decision_aid_url` is non-null when the dispatcher produced a structured cap decision aid and it was posted.

The parent sees only this envelope — never verbatim review prose, never per-finding bodies, never the cycle tool's full output.
