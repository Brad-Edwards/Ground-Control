---
stage_id: integration_discover
step: "Step 01"
tier: low
---

# Step 01: Discover

## Goal

Render the ordered integration plan from the target repository so the maintainer can review it before any branch is mutated.

## MCP call

Call `gc_integration_manager` with:

```json
{
  "action": "plan",
  "repo_path": "<resolved-target-path>",
  "mode": "prepare"
}
```

The tool validates the repo path, resolves the GitHub remote, reads the `.ground-control.yaml` config (or uses defaults), and discovers every PR carrying the configured approval label. It checks that the integration lock is acquirable but does not acquire it. No worktree is created; no branch is mutated.

Expected response envelope on success:

```json
{
  "ok": true,
  "action": "plan",
  "mode": "prepare",
  "owner": "<owner>",
  "repo": "<repo>",
  "plan": [
    {
      "ordinal": 1,
      "pr_number": 42,
      "head_ref": "feature/my-branch",
      "head_repo_owner": "acme",
      "head_repo_name": "myrepo",
      "head_is_fork": false,
      "base_ref": "dev",
      "head_oid": "<sha>",
      "created_at": "<ISO-8601>",
      "updated_at": "<ISO-8601>"
    }
  ],
  "policy": {
    "ordering": "pr_number_asc",
    "approval_label": "approved-for-integration",
    "max_queue_size": 20
  }
}
```

An empty `plan: []` is valid when no PRs carry the approval label. Surface it to the maintainer: "No approved PRs found for this repository."

## Failure paths

| Error code | User-facing message | Next action |
|---|---|---|
| `invalid_repo_path` | "The path `<path>` could not be resolved to a git repository root. Check the path and re-run." | Fix the path argument and re-invoke. |
| `invalid_config` | "`.ground-control.yaml` has a validation error: `<message>`. Fix the config and re-run." | Fix the config field named in the message. |
| `github_remote_not_resolved` | "Could not resolve a GitHub remote from the repository at `<path>`. Ensure `origin` points to a GitHub remote and `gh auth status` passes." | Verify remote and auth, then re-run. |
| `queue_too_large` | "The approved PR queue has `<N>` entries, which exceeds `max_queue_size` (`<cap>`). Reduce the queue or raise the cap in `.ground-control.yaml`." | Merge or de-label some PRs, or raise `max_queue_size`. |
| `discovery_too_large` | "The GitHub API returned too many open PRs to scan safely. Reduce open-PR count or contact the maintainer." | Reduce open PRs in the repository. |

On any error, return `{status: "error", error: "<code>", message: "<user-facing text>"}` and stop. Do not proceed to Step 02.

## Rendering the plan

Surface the plan to the maintainer through the invoking interface (terminal output). Do NOT post to a GitHub issue thread; integration runs are repo-scoped.

Render as a table:

```
Ordinal | PR    | Head Branch         | Fork? | Base  | Updated At
--------|-------|---------------------|-------|-------|---------------------
1       | #42   | feature/my-branch   | no    | dev   | 2026-05-25T14:00Z
2       | #51   | fix/another-branch  | yes   | dev   | 2026-05-25T15:30Z
```

A `head_is_fork: true` entry will be listed in the plan but will be refused by Step 02 with `failure_class: fork_pr_unsupported`.

Follow with one line: "Policy: ordering=`<ordering>`, approval label=`<label>`, max queue size=`<max_queue_size>`."

## Return contract

On success:

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "plan": [...],
    "policy": {...},
    "owner": "<owner>",
    "repo": "<repo>"
  }
}
```

On failure:

```json
{
  "status": "error",
  "error": "<code>",
  "message": "<user-facing text>"
}
```
