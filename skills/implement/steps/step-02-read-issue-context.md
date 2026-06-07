---
stage_id: read_issue_context
step: "Step 2"
tier: low
---

# Step 2: Read the Issue and Gather Context

The issue thread was fetched in Step 1 via `gc_get_issue_thread` and cached behind a content hash. Re-read the cached body, labels, and comments for any user discussion that affects the plan.

If the orchestrator forwarded `issue_thread_hash` from Step 1, call `gc_get_issue_thread` again with `expected_hash=<that hash>`. If the cache hit returns `{unchanged: true}`, use the prior cached state directly — no re-fetch needed. On hash mismatch the tool refetches; pass the new hash forward.

The issue thread is the durable record (per ADR-029) — including this skill's own plan and decision comments — so historical context lives there. Anchor the plan, clause verification, and review scope on the issue.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "issue_thread_hash": "<latest hash>",
    "comment_count": <int>,
    "labels": [ "<label name>" ],
    "discussion_notes": [ "<short string summarizing key user-comment context>" ]
  }
}
```

Do NOT return the raw comment bodies to the parent; they're already cached server-side by `gc_get_issue_thread`. If specific markers (preflight, plan, decision-record) need to be checked, return a short list of marker ids — not their bodies.
