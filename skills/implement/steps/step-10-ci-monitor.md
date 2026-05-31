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

3. On failure, diagnose and fix. For a **code** failure: `git add`, `git commit`, `git push`. Pushing a new commit to an open PR re-runs CI through the `pull_request` `synchronize` event; after it lands, re-invoke `gc_watch_ci_run` to watch the new run.

   **PR-body failures recover differently — a body edit alone does not re-run CI.** The `policy` job re-runs the full `bin/policy` against the *live PR*, including the PR-body checks the local `make policy` skips (it passes `--skip-pr-body`): `check_pr_body`, `pr-ground-control-checks` (wants the verbatim `gc_evaluate_quality_gates` / `gc_run_sweep` checklist lines), and `pr-traceability-summary` (wants explicit `IMPLEMENTS:` / `TESTS:` lines). These surface only in CI, never locally. When one fails, the fix is the PR **description**, not the diff: re-render with `gc_render_pr_body` (it emits exactly the markers these checks require) and apply it via `gh pr edit <n> --body-file <file>`. But `ci.yml`'s `pull_request` trigger fires on `opened` / `reopened` / `synchronize` (GitHub's defaults) and **never on `edited`**, so a body-only change triggers nothing. To re-validate it, **close and reopen the PR** (`gh pr close <n> && gh pr reopen <n>`) — that fires a fresh `reopened` event against the unchanged head commit.

   **A branch push only runs CI through an open PR.** `ci.yml` runs `push` builds for `main` / `dev` only; a feature-branch push runs CI solely through that branch's open PR's `synchronize`. If the PR has already merged or closed, pushing the branch triggers nothing — open a new PR for the follow-up commit instead of expecting the push alone to re-run CI.

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
