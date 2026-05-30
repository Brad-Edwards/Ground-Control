---
stage_id: close_issue_after_merge
step: "Step 20"
tier: low
---

# Step 20: Close the Issue (Phase E, Post-Merge)

This step runs in **Phase E**, AFTER the user merges the PR. The /implement orchestrator detects the post-merge state at Step 1 — Phase A–D markers (`preflight`, `plan`, `traceability_reconciled`, `gc:final-report`) are present, a linked PR is merged, and the issue is still open — and short-circuits straight to this step. There is no Phase A–D work to redo.

The canonical close path is the MCP tool `gc_close_issue_after_merge`, NOT the agent shelling out to `gh issue close`. The tool gates the close on `merged_at` non-null AND PR state `MERGED`; it refuses otherwise. This is the structural enforcement: the `Closes #<issue-number>` keyword in the PR body (rendered by `gc_render_pr_body` in Step 9) remains as the GitHub UI cross-link but is no longer load-bearing for the close — when the user merges, GitHub still fires the auto-close via that keyword, and this step is the idempotent backup that runs from the agent side after the merge is observed.

## What this step does

1. Call `gc_close_issue_after_merge` with `repo_path` and `issue_number`.
   - Optional: pass `pr_number` to skip the PR-resolution timeline lookup; the tool fetches the PR directly. When omitted, the tool resolves the merged PR from the issue's GitHub timeline.
2. Read the returned envelope:
   - `{ ok: true, already_closed: false, pr_number, pr_merged_at, pr_url }` — the tool closed the issue. Continue to the orchestrator's wrap-up.
   - `{ ok: true, already_closed: true, pr_number }` — GitHub already auto-closed the issue via `Closes #<n>`. Idempotent no-op success; continue.
   - `{ ok: false, error: "close_pr_not_merged", pr_state, pr_merged_at, next_action: "wait_for_user_to_merge_the_pr" }` — the linked PR is open or closed-without-merge. Surface to the user; do not retry until the user has actually merged. This is the gate doing its job: an open or force-closed PR must not trigger an issue close.
   - `{ ok: false, error: "close_no_linked_pr" }` — no PR is linked to the issue. Surface to the user. This typically means the agent is being invoked on the wrong issue, or `/implement` was used as a dry-run that never opened a PR.
   - Other `ok: false` envelopes (gh failures, lookup failures): surface to the user with the tool's `message`.

The tool is idempotent and safe to retry. Re-running on an already-closed issue returns `already_closed: true` with `ok: true`.

## Why this step exists (issue #1058)

Before issue #1058, the only enforcement that the issue closes coherently with the merge was the `Closes #<n>` keyword in the PR body and skill prose at Step 18 saying "do not run `gh issue close` here." That left the close path entirely keyword-dependent: any agent or hook running `gh issue close` between merge and the orchestrator's final report could close an issue against a PR that hadn't merged. Issue #1058 adds the MCP-tool gate so the structural enforcement matches the prose: the close is verified against `merged_at` before it runs, refusal is structured, and re-runs are idempotent.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "issue_closed": true,
    "already_closed": false,
    "pr_number": <int>,
    "pr_merged_at": "<ISO-8601>",
    "pr_url": "<URL>"
  }
}
```

On `close_pr_not_merged` or `close_no_linked_pr`: return `status: "error"` with the tool's envelope verbatim so the orchestrator can surface it to the user. The orchestrator must not retry these errors until the user has changed the PR's state.

This is the last step of the workflow. After this step, the `/implement` run is complete: the issue is closed, the PR is merged, the Ground Control graph is reconciled, and the durable record on the issue thread is sealed.
