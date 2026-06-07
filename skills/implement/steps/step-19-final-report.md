---
stage_id: final_report
step: "Step 19"
tier: low
---

# Step 19: Report (DO NOT MERGE)

**You MUST NOT merge the PR. You MUST NOT run `gh pr merge`. The user reviews and merges.**

**Precondition: traceability reconciliation must already be done.** Step 15 runs `gc_transition_status`, `gc_reconcile_traceability`, link creation/deletion, and `gc_assert_traceability_reconciled` BEFORE this step. The final report is the user-facing "this is done; review and merge" signal; it MUST reflect the reconciled state of the Ground Control graph, not the pre-reconciliation state. Do not fire any earlier user-facing "complete" message; every prior step that escalates to the user is escalating because something needs the user's input, not because work is finished.

**Enforcement at the tool layer.** `gc_post_final_report` refuses to publish unless a `traceability_reconciled` phase marker exists and, when `base_ref` / `head_ref` are supplied, is fresh against the live diff. If the marker is missing or stale, run Step 15 again; do not post the report manually. The `/quickfix` lane (`lane: "quickfix"`) is exempt because /quickfix runs are requirement-free by precondition. A bounded `override_traceability_gate=true` + `override_traceability_reason` escape exists for the rare case the user authorizes a skip; the override does NOT change behavior of any other gate.

Per ADR-036, the final summary is posted via the deterministic **`gc_post_final_report`** MCP tool, not free-form `gh issue comment` prose. Pass:

- `repo_path`, `issue_number`, `pr_number`, `base_ref`, `head_ref`
- `requirements`: array of `{ uid, title, status, note? }`—one entry per UID in `in_scope_requirements[]`; `status` is the new status (`ACTIVE` for implemented, `DRAFT` for forward-looking with a `note` like `"forward-looking"`).
- `files`: `{ added: [...], modified: [...], renamed: [...], deleted: [...] }` (any key may be omitted).
- `reviews`: array of `{ reviewer, summary }`—one per reviewer (`codex`, `test-quality`, `remote-quality`, provider adapters, etc.) with a one-line summary like `"3 cycles, all fix, 0 remaining"`.
- `traceability`: `{ added: [...], updated: [...], deleted: [...], notes? }`—short identifier strings describing the reconciliation outcome.
- `ci_status`: `"green"` (or `"red"`; never `"skipped"` for a real PR).
- `sonar_status`: legacy renderer field; pass `"passed"` only after Step 11 returned `remote_quality_status: "passed"`, `"failed"` for a failed remote-quality gate, or `"skipped"` only when no provider is configured.
- `plan_comment_url`: the URL cached in Step 4 from `gc_post_implementation_plan`.
- `summary` (optional): one extra paragraph if there is something the structured fields don't cover. Update length follows the canonical succinctness rule in `skills/implement/steps/_review-loop-rules.md`.
- `documentation_outcome` (optional): same `{ outcome, rationale? }` shape as Step 9. Pass when the diff touched a classified surface (when `gc_documentation_coverage` returned `outcome_required: true`).

The tool renders the canonical final-report Markdown, filters sensitive content, posts to the issue thread under a `gc:final-report` marker, and returns `{ ok, comment_url, comment_id }`. Cache the URL.

Tier for this step: `low`. The tool does the rendering; the agent just collects structured input. Do NOT post the final report as free-form `gh issue comment`; the deterministic tool is now the only canonical surface.

After posting the final report, call `gc_gate_telemetry_summary` for this issue and capture a process observation derived from that summary via `gc_remember` (or `gc_capture_process_lessons`, which writes through the same inbox path). The note should say which gates fired/escalated and how many cycles ran. Do not rely on memory or free-form recollection.

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
