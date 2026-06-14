---
stage_id: final_report
step: "Step 17"
tier: medium
---

# Step 17: Phase D Completion (Assertions + Final Report)

This step consolidates the Phase D tail: traceability reconciliation assertion, GRC reconciliation assertion, and the final report, all in one deterministic MCP tool call. Steps 17–19 from the original workflow are collapsed into this single step per issue #1103.

**Precondition**: Steps 15 (`gc_transition_status`) and 16 (`gc_create_traceability_link` / `gc_delete_traceability_link`) must have run successfully. The final report is the user-facing "this is done; review and merge" signal; it must reflect the reconciled state of the Ground Control graph.

**You MUST NOT merge the PR. You MUST NOT run `gh pr merge`. The user reviews and merges.**

## What gc_assert_completion does

Call `gc_assert_completion` once with the final-report inputs and in-scope requirements. The tool runs the following assertions in sequence, then posts the final report:

1. **Traceability assertion** (`gc_assert_traceability_reconciled`): re-fetches each in-scope requirement and its IMPLEMENTS/TESTS links from the Ground Control REST API. Posts the `traceability_reconciled` phase marker on success. Returns `ok:false` with `error: "traceability_not_reconciled"` on failure — loop back to Step 16 to fix.

2. **GRC reconciliation assertion** (`gc_assert_grc_reconciled`): reads the GRC screening record written by `gc_post_grc_screening` (Step 3.5) from the issue thread and verifies it server-side. For `security_relevant` verdicts, every entity ref and CODE link must resolve. Posts the `grc_reconciled` phase marker on success. On failure: if `error: "grc_screening_record_missing"`, loop back to Step 3.5; if `error: "grc_not_reconciled"`, fix the entity/link registrations in Ground Control.

3. **Final report** (`gc_post_final_report`): renders the structured final-report Markdown and posts it as a comment. The `internalVerifiedPhases` union avoids a GitHub read-after-write race on the markers just posted.

The tool returns `{ok, assertions[], final_report}`. The `assertions` array contains one entry per assertion: `{name, ok, comment_url, comment_id}`.

## Required inputs

Pass to `gc_assert_completion`:

- `repo_path`, `issue_number`, `pr_number`
- `plain_english_outcome`: required; 1–3 short sentences explaining what the change lets product users, operators, or maintainers do now.
- `requirements`: array of `{uid, title, status, note?}` — one entry per UID in `in_scope_requirements[]`
- `files`: `{added, modified, renamed, deleted}` (any key may be omitted)
- `reviews`: array of `{reviewer, summary}` — one per reviewer
- `traceability`: `{added, updated, deleted, notes?}` — short strings describing reconciliation outcome
- `ci_status`: `"green"` (never `"skipped"` for a real PR)
- `sonar_status`: `"passed"`, `"failed"`, or `"skipped"` (when `cfg.sonarcloud` is null)
- `plan_comment_url`: URL cached in Step 4
- `touched_files` (optional): list of files touched, for the traceability assertion
- `project` (optional): Ground Control project key
- `override` / `override_reason` (optional): traceability override if user-authorized

## Label removal (optional best-effort)

The `in-progress` label removal is **no longer a mandatory gate**. After `gc_assert_completion` returns `ok: true`, you MAY remove the label as best-effort:

```
gh issue edit <issue-number> --remove-label in-progress
```

If this fails, skip it — do not block on it. The label lifecycle is operational-only; the issue is closed at Phase E by `gc_close_issue_after_merge`.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "assertions": [
      {"name": "traceability_reconciled", "ok": true, "comment_url": "<URL>"},
      {"name": "grc_reconciled", "ok": true, "comment_url": "<URL>"}
    ],
    "final_report": {
      "comment_url": "<URL>",
      "comment_id": "<int>"
    }
  }
}
```

This is the last step of **Phase D**. Do NOT proceed to merge; the user reviews and merges the PR. After the merge, the workflow re-enters at **Phase E (Step 20)**: the agent calls `gc_close_issue_after_merge`, which verifies `merged_at` non-null before closing the issue.
