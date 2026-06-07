---
stage_id: transition_reconcile
step: "Step 15"
tier: medium
---

# Step 15: Transition and Reconcile Traceability

This step replaces the old manual Steps 15/16/17 flow. Do the status transition, run the diff-derived traceability reconcile worklist, apply confirmed link changes, then assert reconciliation server-side.

1. For each UID in `in_scope_requirements[]`, classify it:
   - **Materially implemented**: transition `DRAFT -> ACTIVE` with `gc_transition_status` if needed.
   - **Forward-looking**: leave `DRAFT`, add or preserve a DOCUMENTS relationship, and note why it is not implemented in this PR.
   - **Invalid/missing implementation**: STOP and ask the user. Do not transition a requirement whose implementing artifact cannot be identified.

2. Call `gc_reconcile_traceability` with `repo_path`, `issue_number`, `base_ref`, `head_ref`, and the in-scope requirements. The tool computes `git diff --name-status` itself, calls `gc_get_traceability_by_artifact` for each changed path, and returns:
   - `worklist[]`: `{path, current_links, suggested_action}`
   - `gap_set[]`: in-scope requirements lacking IMPLEMENTS coverage
   - `diff_hash`: the live diff hash that the assertion must bind

3. Confirm and apply the worklist:
   - Add missing IMPLEMENTS links for changed executable artifacts that satisfy a requirement.
   - Add TESTS links for automated tests that verify a requirement.
   - Move/delete stale links for renamed or deleted artifacts.
   - Keep incidental files unlinked only when the worklist action and requirement mapping make that explicit.
   - Do not fabricate a link to satisfy the gate; if a requirement has no real artifact of record, STOP.

4. Re-run `gc_reconcile_traceability` after edits until `worklist` and `gap_set` reflect the intended graph.

5. Call `gc_assert_traceability_reconciled` with the same `base_ref` and `head_ref`, plus the in-scope requirements as `[{uid, status_intent}]`. The tool recomputes the live diff, re-fetches requirements and artifact links, and writes the `traceability_reconciled` marker bound to the diff hash. If HEAD moves after the marker, `gc_post_final_report` will treat it as stale.

No deferral language is allowed. Missing implementation is fix-or-escalate, not a follow-up.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "transitions": [
      { "uid": "<UID>", "from": "DRAFT", "to": "ACTIVE" }
    ],
    "forward_looking": [ "<UID>" ],
    "links_added": [ "<UID> <- <path> (<link_type>)" ],
    "links_updated": [ "<UID> <- <old_path> -> <new_path>" ],
    "links_deleted": [ "<UID> <- <path>" ],
    "traceability_reconciled_marker_url": "<URL from gc_assert_traceability_reconciled>",
    "traceability_diff_hash": "<diff_hash>"
  }
}
```
