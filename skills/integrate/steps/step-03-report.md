---
stage_id: integration_report
step: "Step 03"
tier: low
---

# Step 03: Report

## Goal

Render the human-readable readiness summary for the maintainer. This is a pure formatting step; no MCP tools are called and no side effects occur.

## Summary line

Render one summary line at the top:

```
Ready: <N>   Blocked: <M>   Halt: <yes/no>
```

Where `N` is the count of `outcome: "ready"` records, `M` is the count of `outcome: "blocked"` records, and `Halt` reflects whether a `halt` field is present in the cached state.

## Per-PR table

Render a table with one row per PR record:

| PR | Outcome | Summary | Failure class | Next action |
|----|---------|---------|---------------|-------------|
| #42 | ready | Rebased cleanly; CI green; Sonar green. | | merge_when_ready |
| #51 | blocked | Rebase conflict on src/Foo.java. | rebase_conflict | resolve_conflict_and_reapprove |
| #77 | blocked | PR from fork contributor/myrepo is not supported. | fork_pr_unsupported | merge_manually_or_open_followup |

When `failure_class` is `fork_pr_unsupported`, surface a note to the maintainer: "PR #N comes from a fork and must be merged manually or via a separate workflow."

Omit `failure_class` and `next_action` columns if all records are `ready` with no halt. Include them whenever any record is `blocked` or a halt is present.

## Halt section

If `halt` is present in the cached state from Step 02, render a halt section before the ledger table:

```
HALT: <type>
Reason: <reason>
```

For `consultation_halt`, also render:

```
Candidate resolutions:
  1. <option A>
  2. <option B>
```

Render the halt section verbatim from the envelope; do not paraphrase or redact. Then render the partial ledger.

For `queue_wide_halt`, follow the halt section with: "Fix the condition above and re-invoke `/integrate` to continue."

For `consultation_halt`, follow with: "Resolve the condition above, then re-invoke `/integrate` if you want to proceed. The run will not continue automatically."

## Return contract

```json
{
  "status": "ok"
}
```

Terminal step; no cached state to forward.
