---
stage_id: transition_reconcile
step: "Step 16"
tier: medium
---

# Step 16: Tombstone - Reconcile Happens in Step 15

This numbered file is retained so ADRs, policy checks, and external references do not churn. The manual traceability-reconciliation procedure has been collapsed into Step 15.

Do not hand-discover changed artifacts here. If reached, return to Step 15 and run `gc_reconcile_traceability`, apply the returned worklist, then run `gc_assert_traceability_reconciled` with the live base/head refs.

## Return contract

```json
{
  "status": "redirect",
  "cached_for_next_step": {
    "next_step": "Step 15",
    "reason": "traceability reconciliation is diff-driven and asserted in Step 15"
  }
}
```
