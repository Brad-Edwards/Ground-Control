---
stage_id: final_report
step: "Step 19"
tier: low
---

# Step 19: Report (DO NOT MERGE)

**You MUST NOT merge the PR. You MUST NOT run `gh pr merge`. The user reviews and merges.**

Per ADR-036, the final summary is posted via the deterministic **`gc_post_final_report`** MCP tool, not free-form `gh issue comment` prose. Pass:

- `repo_path`, `issue_number`, `pr_number`
- `requirements`: array of `{ uid, title, status, note? }` — one entry per UID in `in_scope_requirements[]`; `status` is the new status (`ACTIVE` for implemented, `DRAFT` for forward-looking with a `note` like `"forward-looking"`).
- `files`: `{ added: [...], modified: [...], renamed: [...], deleted: [...] }` (any key may be omitted).
- `reviews`: array of `{ reviewer, summary }` — one per reviewer (`codex`, `test-quality`, `sonarcloud`, etc.) with a one-line summary like `"3 cycles, all fix, 0 remaining"`.
- `traceability`: `{ added: [...], updated: [...], deleted: [...], notes? }` — short identifier strings describing the reconciliation outcome.
- `ci_status`: `"green"` (or `"red"`; never `"skipped"` for a real PR).
- `sonar_status`: `"passed"`, `"failed"`, or `"skipped"` (when `cfg.sonarcloud` is null).
- `plan_comment_url`: the URL cached in Step 4 from `gc_post_implementation_plan`.
- `summary` (optional): one extra paragraph if there is something the structured fields don't cover.

The tool renders the canonical final-report Markdown, filters sensitive content, posts to the issue thread under a `gc:final-report` marker, and returns `{ ok, comment_url, comment_id }`. Cache the URL.

Tier for this step: `low` — the tool does the rendering; the agent just collects structured input. Do NOT post the final report as free-form `gh issue comment` — the deterministic tool is now the only canonical surface.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "final_report_comment_url": "<URL>",
    "final_report_comment_id": <int>
  }
}
```

This is the last step of the workflow. Do NOT proceed to merge; the user reviews and merges the PR.
