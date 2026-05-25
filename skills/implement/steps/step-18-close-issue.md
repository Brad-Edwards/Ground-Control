---
stage_id: close_issue
step: "Step 18"
tier: low
---

# Step 18: Clear the In-Progress Label

The GitHub issue is closed **by `Closes #<issue-number>` in the PR body** when the user merges the PR. That is the only canonical close mechanism: the issue closes atomically with the merge that ships the work, the close event carries the merging commit and PR number, and there is exactly one timeline event tying the issue to the code. Do NOT run `gh issue close` here. Closing the issue from the agent decouples the close from the merge; an unmerged or rolled-back PR then leaves a closed issue with no shipped code behind it, and GitHub does not re-open issues on PR revert.

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
    "close_mechanism": "pr_body_closes_keyword"
  }
}
```

`issue_closed` is `false` because the issue stays open until the PR merges. `close_mechanism` records the contract (`pr_body_closes_keyword`) so downstream readers know the close path is not the agent.
