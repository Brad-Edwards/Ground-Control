---
stage_id: close_issue
step: "Step 18"
tier: low
---

# Step 18: Clear the In-Progress Label

The GitHub issue is **not** closed at Step 18. Two paths drive the actual close: (a) the `Closes #<issue-number>` keyword in the PR body fires GitHub's auto-close when the user merges, and (b) the agent re-enters at **Phase E (Step 20)** after the merge and calls `gc_close_issue_after_merge`, which verifies `merged_at` non-null before running the close (issue #1058). The tool is idempotent - if GitHub already auto-closed via the keyword, Step 20 sees `already_closed: true` and no-ops. Do NOT run `gh issue close` from Step 18; closing the issue from the agent before merge decouples the close from the merge, and an unmerged or rolled-back PR would then leave a closed issue with no shipped code behind it (GitHub does not re-open issues on PR revert).

Step 18 only clears the `in-progress` flag set in Step 1:

```
gh issue edit <issue-number> --remove-label in-progress
```

The label says, "an agent is actively working this issue." By the time Step 18 runs, the agent has handed the PR to the user and traceability is reconciled (Steps 15–17). Active work is done; the label has to go even though the issue is still open. If the label removal fails, surface it in the Step 19 report rather than swallowing it.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "issue_closed": false,
    "in_progress_label_removed": true,
    "close_mechanism": "phase_e_gc_close_issue_after_merge"
  }
}
```

`issue_closed` is `false` because the issue stays open until the PR merges and Phase E (Step 20) verifies-and-closes. `close_mechanism` records the contract (`phase_e_gc_close_issue_after_merge`) so downstream readers know the close path is a structured MCP-tool gate, not free-form `gh issue close`.
