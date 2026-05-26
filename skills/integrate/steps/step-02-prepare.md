---
stage_id: integration_prepare
step: "Step 02"
tier: low
---

# Step 02: Prepare

## Goal

Prepare each PR in the queue and produce a readiness ledger. For every PR: rebase onto the current base branch, run the configured completion gate, push with `--force-with-lease`, then watch CI and Sonar. Surface the outcome of each PR to Step 03 for formatting.

## MCP call

Call `gc_integration_manager` with:

```json
{
  "action": "prepare",
  "repo_path": "<resolved-target-path>",
  "mode": "prepare"
}
```

The tool acquires the integration lock before any branch mutation and releases it on every exit path, including errors. The lock identity is derived from the canonical repo root, so two path spellings for the same repo contend on the same lock.

## Lock contention

If another `prepare` run is already holding the lock, the tool returns:

```json
{
  "ok": false,
  "error": "lock_contended",
  "message": "Another prepare run is active for this repository.",
  "next_action": "wait_or_release"
}
```

Recommend that the maintainer wait for the other run to finish. If the maintainer knows the lock is stale (the prior run crashed without releasing), they can call:

```json
{ "action": "release", "repo_path": "<path>" }
```

Then re-invoke `/integrate`.

## Result handling

### Success: `ok: true` with `results[]`

The tool returns:

```json
{
  "ok": true,
  "action": "prepare",
  "mode": "prepare",
  "run_id": "<uuid>",
  "owner": "<owner>",
  "repo": "<repo>",
  "policy": {
    "ordering": "pr_number_asc",
    "approval_label": "approved-for-integration",
    "max_queue_size": 20
  },
  "results": [
    {
      "pr_number": 42,
      "outcome": "ready",
      "summary": "Rebased cleanly; CI green; Sonar green."
    },
    {
      "pr_number": 51,
      "outcome": "blocked",
      "summary": "Rebase conflict on src/Foo.java.",
      "failure_class": "rebase_conflict",
      "next_action": "resolve_conflict_and_reapprove"
    },
    {
      "pr_number": 77,
      "outcome": "blocked",
      "failure_class": "fork_pr_unsupported",
      "summary": "PR from fork contributor/myrepo is not supported in the prepare-only lane (GC-O011 first slice).",
      "next_action": "merge_manually_or_open_followup"
    }
  ]
}
```

Pass `results` and `run_id` to Step 03 for rendering.

### Error: `queue_wide_halt`

The tool returns:

```json
{
  "ok": false,
  "error": "queue_wide_halt",
  "message": "Base branch 'dev' not found on remote.",
  "next_action": "check_base_branch",
  "run_id": "<uuid>",
  "owner": "<owner>",
  "repo": "<repo>",
  "policy": {"ordering": "pr_number_asc", "approval_label": "approved-for-integration", "max_queue_size": 20},
  "results": [...]
}
```

Render the partial ledger (Step 03), then surface the halt reason to the maintainer: "The prepare run stopped because: `<reason>`. Fix the condition and re-invoke `/integrate`."

### Error: `consultation_halt`

The tool returns:

```json
{
  "ok": false,
  "error": "consultation_halt",
  "run_id": "<uuid>",
  "halt_reason": "<machine-readable reason code>",
  "candidate_resolutions": ["<option A>", "<option B>"],
  "next_action": "consult_maintainer",
  "owner": "<owner>",
  "repo": "<repo>",
  "policy": {"ordering": "pr_number_asc", "approval_label": "approved-for-integration", "max_queue_size": 20},
  "results": [...]
}
```

Surface the halt reason and candidate resolutions to the maintainer through the invoking interface. STOP. Do NOT continue the queue automatically after the maintainer responds. If the maintainer wants to proceed after resolving the condition, they re-invoke `/integrate`.

The consultation halt triggers are (verbatim from GC-O011 clause h): ambiguity in the authoritative input; conflicting authoritative input; apparent error or oversight in the authoritative input; or a branch where the correct resolution would silence a test, remove a documentation requirement, or violate a coding standard.

## Return contract

On success or `queue_wide_halt` (partial results available):

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "results": [...],
    "run_id": "<uuid>",
    "owner": "<owner>",
    "repo": "<repo>",
    "halt": {
      "type": "queue_wide_halt",
      "message": "<message>"
    }
  }
}
```

The `halt` field is omitted when the run completed without halting.

On `consultation_halt`:

```json
{
  "status": "consultation_halt",
  "cached_for_next_step": {
    "results": [...],
    "run_id": "<uuid>",
    "owner": "<owner>",
    "repo": "<repo>",
    "halt": {
      "type": "consultation_halt",
      "halt_reason": "<halt_reason>",
      "candidate_resolutions": [...]
    }
  }
}
```

Step 03 receives the `halt` field and renders it before the partial ledger.
