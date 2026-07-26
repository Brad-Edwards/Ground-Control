---
stage_id: final_report
step: "Step 17"
tier: medium
---

# Step 17: Completion (Readiness pre-merge, Assertions + Final Report post-merge)

Use `gc_implement_mechanical action="readiness"` for the pre-merge invocation.
After Steps 15–16 have run post-merge, use
`gc_implement_mechanical action="finalize"`; that action performs the
post-merge completion assertion and the idempotent Step 20 issue close in one
deterministic call.

This step calls `gc_assert_completion`, and it is invoked **twice across the run** with a different `phase` (issue #963):

- **Phase D terminal - `phase="pre_merge"`.** Runs after Step 11 (SonarCloud) once all automated gates are green. It posts a **readiness record** (a "Ready for review" comment carrying a `ready_for_review` phase marker - *not* a `gc:final-report` marker). It does **not** run the traceability assertion, because the requirement transition and traceability reconciliation have not happened yet. Then the run **STOPS** for the user to review and merge the PR. This is the single human touchpoint.
- **Phase E completion - `phase="post_merge"` (default).** Runs after the user merges, following Step 15 (transition) and Step 16 (reconcile). It is **merge-gated**: it refuses with `completion_pr_not_merged` unless the linked PR is merged, then runs the traceability assertion and posts the reconciled final report. Steps 17–19 from the original workflow are collapsed into this single tool call per issue #1103.

The pre-merge/post-merge split (issue #963) exists so the requirement `DRAFT→ACTIVE` transition, traceability links, and the durable final report never land ahead of shipped code - the same coherence the #1058 post-merge close already enforces.

For both phases, `gc_assert_completion` re-reads the trusted
`gc:execution-obligation` ledger from the issue thread. It refuses with
`completion_open_execution_obligations` while any real problem remains open.
Caller summaries or cached arrays cannot override this gate. Repair and verify
every obligation, record its resolution, then retry completion.

**Precondition (post_merge only)**: Steps 15 (`gc_transition_status`) and 16 (`gc_create_traceability_link` / `gc_delete_traceability_link`) must have run successfully in Phase E. The post-merge final report must reflect the reconciled state of the Ground Control graph; the pre-merge readiness record must not claim a reconciliation that has not happened.

**You MUST NOT merge the PR. You MUST NOT run `gh pr merge`. The user reviews and merges.**

## What gc_assert_completion does (`phase="post_merge"`)

Call `gc_assert_completion` with `phase="post_merge"` (the default) once Step 15/16 have run in Phase E. The tool first verifies the linked PR is merged (`merged_at` non-null AND state `MERGED`) - refusing with `completion_pr_not_merged` / `next_action:"wait_for_user_to_merge_the_pr"` otherwise - then runs the following assertions in sequence and posts the final report:

1. **Traceability assertion** (`gc_assert_traceability_reconciled`): re-fetches each in-scope requirement and its IMPLEMENTS/TESTS links from the Ground Control REST API. Posts the `traceability_reconciled` phase marker on success. Returns `ok:false` with `error: "traceability_not_reconciled"` on failure - loop back to Step 16 to fix.

2. **Final report** (`gc_post_final_report`): renders the structured final-report Markdown and posts it as a comment. The `internalVerifiedPhases` union avoids a GitHub read-after-write race on the marker just posted.

The tool returns `{ok, assertions[], final_report}`. The `assertions` array contains one entry per assertion: `{name, ok, comment_url, comment_id}`.

## Required inputs

Pass to `gc_assert_completion`:

- `repo_path`, `issue_number`, `pr_number`
- `plain_english_outcome`: required; 1–3 short sentences explaining what the change lets product users, operators, or maintainers do now.
- `requirements`: array of `{uid, title, status, note?}` - one entry per UID in `in_scope_requirements[]`
- `files`: `{added, modified, renamed, deleted}` (any key may be omitted)
- `reviews`: array of `{reviewer, summary}` - one per reviewer
- `traceability`: `{added, updated, deleted, notes?}` - short strings describing reconciliation outcome
- `ci_status`: `"green"` (never `"skipped"` for a real PR)
- `sonar_status`: `"passed"`, `"failed"`, or `"skipped"` (when `cfg.sonarcloud` is null)
- `plan_comment_url`: URL cached in Step 4
- `touched_files` (optional): list of files touched, for the traceability assertion
- `project` (optional): Ground Control project key
- `override` / `override_reason` (optional): traceability override if user-authorized
- `phase`: `"post_merge"` (default) for the Phase E completion; `"pre_merge"` for the Phase D readiness call.

## The pre-merge readiness call (`phase="pre_merge"`)

At the end of Phase D (after Step 11), call `gc_assert_completion` with `phase="pre_merge"` and the same inputs as above **except** there is no reconciliation yet - pass `requirements` (their current `DRAFT`/`ACTIVE` status), `files`, `reviews`, `ci_status`, `sonar_status`, `plan_comment_url`, and `plain_english_outcome`. The tool skips the traceability assertion and the merge gate, still enforces every input gate (CI green, Sonar pass/legit-skip, codex review present, sensitive/reserved/defer scrubs, body size), and posts the readiness record. It returns `{ok, phase:"pre_merge", readiness_report:{comment_url, comment_id}, assertions:[]}`. **Then STOP** - the run is paused for the user to review and merge. Do not run Steps 15/16/20 in this invocation.

## Label removal (optional best-effort)

The `in-progress` label removal is **no longer a mandatory gate**. After `gc_assert_completion` returns `ok: true`, you MAY remove the label as best-effort:

```
gh issue edit <issue-number> --remove-label in-progress
```

If this fails, skip it - do not block on it. The label lifecycle is operational-only; the issue is closed at Phase E by `gc_close_issue_after_merge`.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "assertions": [
      {"name": "traceability_reconciled", "ok": true, "comment_url": "<URL>"}
    ],
    "final_report": {
      "comment_url": "<URL>",
      "comment_id": "<int>"
    }
  }
}
```

The `phase="pre_merge"` call is the **last step of Phase D**: do NOT proceed to merge; the user reviews and merges the PR. After the merge, the workflow re-enters at **Phase E** (re-invoke `/implement <issue>`): Step 15 (transition) → Step 16 (reconcile) → this step with `phase="post_merge"` (the merge-gated reconciled report above) → **Step 20** (`gc_close_issue_after_merge`, which also verifies `merged_at` non-null before closing the issue). The `phase="post_merge"` call is the last step before the close.
