---
stage_id: transition_reconcile
step: "Step 17"
tier: medium
---

# Step 17: Verify Ground Control State Landed

1. For each UID in `in_scope_requirements[]`:
   - Re-fetch with `gc_get_requirement` and confirm status is `ACTIVE` for materially-implemented requirements (DRAFT for forward-looking ones, with DOCUMENTS links instead of IMPLEMENTS).
   - Re-fetch with `gc_get_traceability` and confirm the expected links are present.
2. Re-run the deleted/renamed/modified audit from Step 16: every file in the diff should either have up-to-date links or be intentionally un-linked.
3. If anything is missing or drifted, loop back to fix.
4. **Never declare success on silent failures.** If any `gc_create_traceability_link` / `gc_delete_traceability_link` / `gc_transition_status` call returned non-2xx, treat as failure, surface to the user, loop back.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "verified": true,
    "drifted_requirements": []
  }
}
```

`drifted_requirements` non-empty returns `status: "error"` so the orchestrator loops back to Step 16.
