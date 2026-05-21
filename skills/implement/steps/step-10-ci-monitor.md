---
stage_id: ci_monitor
step: "Step 10"
tier: low
---

# Step 10: CI Monitor

Replaces the previous "poll `gh run view` every 15 seconds for up to 45 minutes" inline loop with a single MCP call. The agent makes one tool call; the MCP server holds the connection while polling server-side; the agent's context is not burned by per-poll turns. (Issue #934 item 4.)

1. Call the `gc_watch_ci_run` MCP tool with:
   - `repo_path`: absolute path from Step 1
   - `branch`: the current feature branch (cached in Step 1)
   - Defaults are appropriate: queued cap 5 min, total cap 45 min, poll every 15s. Override only when the repo has a non-default CI shape.

2. Read the returned envelope:
   - `conclusion: "success"` → CI passed. Advance to Step 11.
   - `conclusion: "queued_too_long"` → no runner accepted the job within 5 minutes. STOP and report to the user. For self-hosted runner pools, suggest checking the pool (`gh api /repos/<owner>/<repo>/actions/runners`).
   - `conclusion: "timed_out"` → run did not finish within 45 minutes. STOP and surface the run URL to the user.
   - `conclusion: "failure"` (or `"cancelled"` / `"action_required"` / `"startup_failure"`) → CI failed. The envelope's `failed_steps[]` and `log_summary` (bounded UTF-8, from the tail of `gh run view --log-failed`) tell you which step + what to look at. Raw logs stay server-side; if you need to drill in, the `run_id` lets a separate `gh run view --log-failed` call retrieve them later.

3. On failure, diagnose and fix, then `git add`, `git commit`, `git push`. After the new commit lands, re-invoke `gc_watch_ci_run` to watch the new run.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "ci_conclusion": "success" | "queued_too_long" | "timed_out" | "failure",
    "ci_run_id": <int>,
    "ci_url": "<URL>",
    "failed_steps_count": <int>
  }
}
```

When `ci_conclusion` is not `"success"` and the agent was unable to recover, return `status: "escalated"` with `escalation_reason` set.
