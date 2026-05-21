---
stage_id: close_issue
step: "Step 18"
tier: low
---

# Step 18: Close the Issue

Close the GitHub issue now via `gh issue close <issue-number>`, then clear the in-progress flag set in Step 1: `gh issue edit <issue-number> --remove-label in-progress`. The work is done, the PR records it, GitHub's auto-close on merge is unreliable. If the label removal fails, surface it in the Step 19 report rather than claiming the issue was closed cleanly.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "issue_closed": true,
    "in_progress_label_removed": true
  }
}
```
