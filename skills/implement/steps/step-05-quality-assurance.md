---
stage_id: precommit
step: "Step 5"
tier: low
---

# Step 5: Quality Assurance

Run `pre-commit run --all-files` to ensure the codebase is in a healthy state before the completion gate.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "precommit_passed": true,
    "fixed_files": [ "<files auto-fixed by hooks, if any>" ]
  }
}
```

On hook failure, fix the issue, re-stage, re-run. If the failure cannot be resolved automatically, return `status: "error"` with a short `message`.
