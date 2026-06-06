---
stage_id: read_issue_context
step: "Step 2"
tier: low
---

# Step 2: Read the Issue and Gather Context

The issue thread was fetched in Step 1 via `gc_get_issue_thread` and cached behind a content hash. Re-read the cached body, labels, and comments for any user discussion that affects the plan.

If the orchestrator forwarded `issue_thread_hash` from Step 1, call `gc_get_issue_thread` again with `expected_hash=<that hash>`. If the cache hit returns `{unchanged: true}`, use the prior cached state directly — no re-fetch needed. On hash mismatch the tool refetches; pass the new hash forward.

The issue thread is the durable record (per ADR-029) — including this skill's own plan and decision comments — so historical context lives there. Anchor the plan, clause verification, and review scope on the issue.

Before leaving this step, call `gc_get_implementation_context` with `repo_path`, `issue_number`, and any resolved `requirement_uid`. This server-side bundle loads the binding ADRs, the repo's cross-cutting-concern incumbents from `.ground-control.yaml`, existing IMPLEMENTS artifacts, and the related-requirement neighbourhood. The tool writes the `context_loaded` phase marker. Downstream `gc_post_interface_contract` and `gc_post_implementation_plan` refuse without it; do not substitute manual ADR reading for this marker.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "issue_thread_hash": "<latest hash>",
    "implementation_context_hash": "<context_hash from gc_get_implementation_context>",
    "context_loaded_marker_written": true,
    "comment_count": <int>,
    "labels": [ "<label name>" ],
    "discussion_notes": [ "<short string summarizing key user-comment context>" ]
  }
}
```

Do NOT return the raw comment bodies to the parent; they're already cached server-side by `gc_get_issue_thread`. If specific markers (preflight, plan, decision-record) need to be checked, return a short list of marker ids — not their bodies.
