---
stage_id: remote_quality
step: "Step 11"
tier: low
---

# Step 11: Remote Quality

This step runs AFTER Step 10 reports green. A green PR checkmark is not enough. The MCP server must re-verify required remote statuses and the full provider-quality result before it writes `remote_gates_green`.

1. Call `gc_watch_required_statuses` with:
   - `repo_path`: absolute path from Step 1
   - `issue_number`: issue number from Step 1
   - `pr_number`: PR number from Step 9
   - `base_ref` / `head_ref`: the same base/head refs used for local gates

2. The tool watches `remote_status` gates from the manifest and, when a provider is configured, verifies provider substance server-side. For SonarCloud that means quality-gate status, new and overall issues by severity, reliability/security/maintainability ratings, security hotspots, coverage, and duplications. Provider-specific tools such as `gc_watch_sonar_analysis` are adapters only; they are not the gate.

3. If the tool returns `ok: false`:
   - `required_status_*` errors: wait, fix CI, or fix remote status configuration as directed by `next_action`.
   - `remote_quality_substance_failed`: fix every provider finding needed by the configured bar, including pre-existing findings when the repo ratchet requires zero overall issues.
   - Missing full provider data is a failure. Do not claim "green" from a PR checkmark.

4. For each remote-quality fix cycle:
   - Classify fix risk before editing: blast radius, behavior change, critical-path touch, and area test coverage.
   - Low/medium-risk fixes are applied automatically, locally verified, committed, pushed, then Step 10 and Step 11 are re-run.
   - High-risk fixes trigger the dispatcher escalation reason `high_risk_fix`: STOP, post the proposed fix plus risk rationale to the issue thread, and wait for the user sanity-check. This stop is separate from non-convergence and is the only routine user stop in this loop.

5. Cycle cap: 5 remote-quality iterations. If findings still do not converge after 5 fix and re-analyze cycles, stop with the remaining provider failures and ask the user.

Proceed to Step 15 only when `gc_watch_required_statuses` returns `ok: true` and a `remote_gates_green` marker is written.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "remote_gates_green": true,
    "remote_quality_status": "passed" | "skipped",
    "fix_cycles_run": <int>
  }
}
```
