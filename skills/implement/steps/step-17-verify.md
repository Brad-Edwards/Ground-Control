---
stage_id: transition_reconcile
step: "Step 17"
tier: medium
---

# Step 17: Tombstone - Assertion Happens in Step 15

This numbered file is retained for compatibility. The old "verify Ground Control state landed" step is now part of Step 15.

Do not perform a separate manual audit here. Step 15 must call `gc_assert_traceability_reconciled` after applying the `gc_reconcile_traceability` worklist. That tool re-fetches requirements and artifact links server-side, recomputes the live diff, writes the `traceability_reconciled` marker, and binds it to the diff hash.

If this step is invoked directly, return `status: "redirect"` and run Step 15.

## Return contract

```json
{
  "status": "redirect",
  "cached_for_next_step": {
    "next_step": "Step 15",
    "reason": "traceability assertion is performed by gc_assert_traceability_reconciled in Step 15"
  }
}
```
