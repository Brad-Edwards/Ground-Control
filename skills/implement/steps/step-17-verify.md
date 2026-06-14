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
5. **Assert the reconciliation at the MCP tool layer (issue #1058).** Once steps 1–4 pass, call `gc_assert_traceability_reconciled` with `repo_path`, `issue_number`, and the in-scope requirements as `[{uid, status_intent}]` (`status_intent` is `ACTIVE` for materially-implemented requirements, `DRAFT` for forward-looking ones). The tool re-fetches each requirement and its links from the Ground Control REST API (so the gate is enforced server-side, not by the agent's claim) and posts the `traceability_reconciled` phase marker on the issue thread. If the tool returns `ok: false` with `error: "traceability_not_reconciled"`, return `status: "error"` so the orchestrator loops back to Step 16; do NOT post the marker manually. The marker is one of two prerequisites that downstream `gc_post_final_report` (Step 19) enforces - without it, Step 19 refuses to publish.
6. **Assert GRC reconciliation at the MCP tool layer (issue #1100).** After `gc_assert_traceability_reconciled` succeeds, call `gc_assert_grc_reconciled` with `repo_path`, `issue_number`, and optionally `project`. The tool reads the GRC screening record written by `gc_post_grc_screening` (Step 3.5) from the issue thread and verifies it against the Ground Control REST API: for `security_relevant` verdicts, every entity ref must resolve and every claimed `CODE` link must exist on the owner entity; for `not_security_relevant` / `no_baseline` verdicts the tool passes immediately and echoes the verdict. On success it posts the `grc_reconciled` phase marker. If the tool returns `ok: false` with `error: "grc_screening_record_missing"`, loop back to Step 3.5 to run the GRC screening; if `error: "grc_not_reconciled"`, fix the missing entity/link registrations in Ground Control and retry. The assertion has no per-tool override; if a user-authorized skip of the completion-gate prerequisite is genuinely needed, take it at `gc_post_final_report` (its phase override bypasses both `traceability_reconciled` and `grc_reconciled` together).

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "verified": true,
    "drifted_requirements": [],
    "traceability_reconciled_marker_url": "<URL from gc_assert_traceability_reconciled>",
    "grc_reconciled_marker_url": "<URL from gc_assert_grc_reconciled>"
  }
}
```

`drifted_requirements` non-empty returns `status: "error"` so the orchestrator loops back to Step 16. A `gc_assert_traceability_reconciled` or `gc_assert_grc_reconciled` failure also returns `status: "error"` with the tool's envelope so the orchestrator can surface the gap.
