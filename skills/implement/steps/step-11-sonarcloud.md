---
stage_id: sonarcloud
step: "Step 11"
tier: low
---

# Step 11: SonarCloud

Replaces the previous "wait 60s + curl quality gate + paginated curl through issues/hotspots" inline shell flow with a single MCP call. The agent makes one tool call; the MCP server performs propagation wait, quality-gate polling, and the paginated REST scrape server-side, then returns one compact envelope. (Issue #934 item 4.)

This step runs AFTER Step 10 (CI Monitor) reports green. A green CI run does not imply a clean SonarCloud - the quality gate and the issue list are separate from CI conclusions and must be checked independently.

**Skip behavior**: if the repo's `.ground-control.yaml` has no `sonarcloud` block, the tool returns `{ok: true, skipped: true, quality_gate: "NONE"}` immediately. Log "SonarCloud skipped - no sonarcloud block in .ground-control.yaml" and proceed to Step 15.

1. Call the `gc_watch_sonar_analysis` MCP tool with:
   - `repo_path`: absolute path from Step 1
   - `pr_number`: from Step 9
   - Defaults are appropriate: initial wait 60s, total cap 30 min, poll every 30 second.

2. Read the returned envelope:
   - `skipped: true` → no sonarcloud block. Advance to Step 15.
   - `quality_gate: "OK"` AND `issues_summary.open_count == 0` AND `hotspots_summary.open_count == 0` → all clean. Advance to Step 15.
   - `ok: false`, or `ok: true` with `timed_out: true` → **the gate was not evaluated.** See sub-step 2a; do NOT read this as findings.
   - Otherwise → there are findings. The envelope's `issues_summary` (counts by severity / type + `top_issues[]`) and `hotspots_summary` (counts + `top_hotspots[]`) tell you what to fix. For drill-down on the full issue list, the envelope's `full_issue_export_path` points at a server-side JSON file (`.gc/sonar/<pr>-<ts>.json`, gitignored) - read it on demand; do NOT bring its raw contents into parent context.

2a. **Unevaluable gate: an infrastructure blocker, not findings.** When the watcher could not read an analysis, no finding exists to repair and re-running is deterministic until something outside the diff changes. `gc_implement_mechanical action="monitor"` reports this as `sonar_gate: "not_evaluable"` and never returns the findings-fix action. Route by `error`:

   - `sonar_watch_token_missing` → the MCP host has no `SONAR_TOKEN`. This is an **operator provisioning fault**, not a code defect: a launcher may spawn the server with a core-only environment (a Codex-spawned host carries eight variables and none of them is this token), so the credential must come from a declared source the server reads itself. Recovery: set `SONAR_TOKEN` in the launch directory's `.env` - the only source the server reads (issue #1562) - and restart the MCP server; the file is read at startup. Escalate under the hard-external-dependency pause class with that concrete request, and keep the obligation open. Do not paste, echo, or log the token value anywhere.
   - `timed_out: true` → SonarCloud published no analysis for the pull request within the watch window. Confirm the analysis job actually ran for this PR, then re-run Step 10 and this step.
   - any other `ok: false` (`sonar_watch_repo_not_found`, `sonar_watch_input_invalid`, `sonar_watch_quality_gate_failed`, `sonar_watch_issues_fetch_failed`, `sonar_watch_hotspots_fetch_failed`) → diagnose the named condition and repair it, then re-run this step.

   None of these consume a sub-step 5 fix cycle: the cycle cap counts fix → re-analyze rounds against real findings, and an unevaluable gate produced none. None of them is a `not-applicable` disposition either - the condition is real, it is just not a defect in the diff. **An unevaluable gate is never satisfied by the PR's `SonarCloud Code Analysis` or `sonar` check-run.** A green check means the hosted quality gate did not fail; it does not mean `issues_summary.open_count == 0` and `hotspots_summary.open_count == 0`, which is what sub-step 6 requires. Substituting it would certify the PR with INFO-through-BLOCKER findings unread. Fix the watcher's input; do not route around it.

3. **Fix every open issue the tool returns - code-smell, bug, vulnerability, and security hotspot, every severity from INFO to BLOCKER, regardless of provenance.** If repair requires significant architecture/security judgment or external authority, record an escalated execution obligation with evidence and a concrete decision request. A false positive may be `not-applicable` only when the condition is factually false for this codebase. Wait for required direction; the obligation remains open and current work.

4. For each fix cycle:
   - Apply the fixes.
   - Re-run the local completion gate to confirm nothing regressed locally.
   - `git add`, `git commit` with message `Fix SonarCloud findings (cycle <N>)`, `git push`.
   - Re-run Step 10 (CI Monitor) so SonarCloud re-analyzes the PR.
   - After CI is green, re-invoke this step.

5. **Cycle cap: 5 iterations for SonarCloud.** If findings remain after the fifth fix→re-analyze cycle, record them as open execution obligations and escalate under the enforced-cycle-cap class with a concrete decision request. The cap pauses analysis; it does not defer or discard the repairs.

6. Proceed to Step 15 only when: the quality gate is `OK` AND the issues summary's `open_count` is 0 AND the hotspots summary's `open_count` is 0. (Steps 13–14 were merged out in #906: test-quality review moved pre-push to Step 6.6, and there is no separate "final CI re-verify" because there is no post-push fix loop after Sonar clean.)

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "sonar_status": "passed" | "failed" | "skipped",
    "quality_gate": "OK" | "ERROR" | "WARN" | "NONE",
    "open_issues_count": <int>,
    "open_hotspots_count": <int>,
    "fix_cycles_run": <int>
  }
}
```

An unevaluable gate (sub-step 2a) does not return this contract: the step escalates with the obligation open and no `sonar_status` is produced. `sonar_status` carries only an evaluated outcome, so a later record can never attest a gate that was never read.
