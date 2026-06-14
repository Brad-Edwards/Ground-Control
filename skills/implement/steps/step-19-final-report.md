---
stage_id: final_report
step: "Step 19"
tier: low
---

# Step 19: Report (DO NOT MERGE)

**You MUST NOT merge the PR. You MUST NOT run `gh pr merge`. The user reviews and merges.**

**Precondition: traceability reconciliation must already be done.** Steps 15 (`gc_transition_status`), 16 (`gc_create_traceability_link` / `gc_delete_traceability_link`), and 17 (verify + `gc_assert_traceability_reconciled`) run BEFORE this step. The final report is the user-facing "this is done; review and merge" signal; it MUST reflect the reconciled state of the Ground Control graph, not the pre-reconciliation state. Do not fire any earlier user-facing "complete" message; every prior step that escalates to the user is escalating because something needs the user's input, not because work is finished.

**Enforcement at the tool layer (issue #1058).** `gc_post_final_report` refuses to publish the Step 19 record unless a `traceability_reconciled` phase marker exists on the issue thread for this issue. That marker is written by `gc_assert_traceability_reconciled` in Step 17. If you skipped Step 17, the tool returns `ok: false, error: "phase_prerequisite_missing", missing: ["traceability_reconciled"], next_action: "run_gc_assert_traceability_reconciled_first"`. Do not work around the refusal by `gh issue comment` directly - re-run Step 17 instead. The `/quickfix` lane (`lane: "quickfix"`) is exempt because /quickfix runs are requirement-free by precondition. A bounded `override_traceability_gate=true` + `override_traceability_reason` escape exists for the rare case the user authorizes a skip; the override does NOT change behavior of any other gate.

Per ADR-036, the final summary is posted via the deterministic **`gc_post_final_report`** MCP tool, not free-form `gh issue comment` prose. Pass:

- `repo_path`, `issue_number`, `pr_number`
- `plain_english_outcome`: required for `/implement`; 1-3 short sentences explaining what the change lets product users, operators, or maintainers do now. Use product/operator language where possible. Do not duplicate the files/tests/traceability checklist.
- `requirements`: array of `{ uid, title, status, note? }` - one entry per UID in `in_scope_requirements[]`; `status` is the new status (`ACTIVE` for implemented, `DRAFT` for forward-looking with a `note` like `"forward-looking"`).
- `files`: `{ added: [...], modified: [...], renamed: [...], deleted: [...] }` (any key may be omitted).
- `reviews`: array of `{ reviewer, summary }` - one per reviewer (`codex`, `test-quality`, `sonarcloud`, etc.) with a one-line summary like `"3 cycles, all fix, 0 remaining"`.
- `traceability`: `{ added: [...], updated: [...], deleted: [...], notes? }` - short identifier strings describing the reconciliation outcome.
- `ci_status`: `"green"` (or `"red"`; never `"skipped"` for a real PR).
- `sonar_status`: `"passed"`, `"failed"`, or `"skipped"` (when `cfg.sonarcloud` is null).
- `plan_comment_url`: the URL cached in Step 4 from `gc_post_implementation_plan`.
- `summary` (optional): one extra paragraph if there is something the structured fields and `plain_english_outcome` don't cover. Update length follows the canonical succinctness rule in `skills/implement/steps/_review-loop-rules.md`.
- `documentation_outcome` (optional): same `{ outcome, rationale? }` shape as Step 9. Pass when the diff touched a classified surface (when `gc_documentation_coverage` returned `outcome_required: true`).

The tool renders the canonical final-report Markdown, filters sensitive content, posts to the issue thread under a `gc:final-report` marker, and returns `{ ok, comment_url, comment_id }`. Cache the URL.

Tier for this step: `low`. The tool does the rendering; the agent just collects structured input. Do NOT post the final report as free-form `gh issue comment`; the deterministic tool is now the only canonical surface.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "final_report_comment_url": "<URL>",
    "final_report_comment_id": <int>
  }
}
```

This is the last step of **Phase D**. Do NOT proceed to merge; the user reviews and merges the PR. After the merge, the workflow re-enters at **Phase E (Step 20)**: the agent calls `gc_close_issue_after_merge`, which verifies `merged_at` non-null before closing the issue. The PR body's `Closes #<issue-number>` (rendered by `gc_render_pr_body` in Step 9) remains the GitHub UI cross-link and may auto-close the issue at merge time; Step 20 is the idempotent backup that runs from the agent side and no-ops when the issue is already closed.
