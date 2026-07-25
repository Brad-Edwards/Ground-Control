---
stage_id: git_publish
step: "Step 7"
tier: low
---

# Step 7: Stage & Pre-commit Loop

1. `git add` all relevant changed files. Do NOT stage .env files, credentials, secrets, or large binaries.
2. Run the workflow's single mandatory pre-publish
   `pre-commit run --all-files` invocation. Do not duplicate this successful
   boundary elsewhere.
3. If pre-commit fails:
   - Read the failure output.
   - Fix the issues.
   - Re-stage any modified files with `git add`.
   - Re-run `pre-commit run --all-files`. A failure retry is repair of this
     same mandatory boundary, not a redundant second gate.
   - Repeat up to 5 times. If still failing after 5 attempts, escalate to the user with the failure details (post as an issue comment).
4. When pre-commit passes, proceed.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "precommit_iterations": <int>,
    "staged_paths_count": <int>
  }
}
```
