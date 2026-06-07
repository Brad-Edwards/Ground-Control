---
stage_id: sonarcloud
step: "Step 11"
tier: low
---

# Step 11: SonarCloud

Replaces the previous "wait 60s + curl quality gate + paginated curl through issues/hotspots" inline shell flow with a single MCP call. The agent makes one tool call; the MCP server performs propagation wait, quality-gate polling, and the paginated REST scrape server-side, then returns one compact envelope. (Issue #934 item 4.)

This step runs AFTER Step 10 (CI Monitor) reports green. A green CI run does not imply a clean SonarCloud — the quality gate and the issue list are separate from CI conclusions and must be checked independently.

**Skip behavior**: if the repo's `.ground-control.yaml` has no `sonarcloud` block, the tool returns `{ok: true, skipped: true, quality_gate: "NONE"}` immediately. Log "SonarCloud skipped — no sonarcloud block in .ground-control.yaml" and proceed to Step 15.

1. Call the `gc_watch_sonar_analysis` MCP tool with:
   - `repo_path`: absolute path from Step 1
   - `pr_number`: from Step 9
   - Defaults are appropriate: initial wait 60s, total cap 30 min, poll every 30s.

2. Read the returned envelope:
   - `skipped: true` → no sonarcloud block. Advance to Step 15.
   - `quality_gate: "OK"` AND `issues_summary.open_count == 0` AND `hotspots_summary.open_count == 0` → all clean. Advance to Step 15.
   - Otherwise → there are findings. The envelope's `issues_summary` (counts by severity / type + `top_issues[]`) and `hotspots_summary` (counts + `top_hotspots[]`) tell you what to fix. For drill-down on the full issue list, the envelope's `full_issue_export_path` points at a server-side JSON file (`.gc/sonar/<pr>-<ts>.json`, gitignored) — read it on demand; do NOT bring its raw contents into parent context.

3. **Fix every open issue the tool returns — code-smell, bug, vulnerability, and security hotspot, every severity from INFO to BLOCKER, pre-existing or not.** If you think a finding is dangerous to fix, unwise in context, or a false positive, STOP, post your reasoning as an issue comment with `decision: <fix|wontfix|not-applicable>` and the rationale, and ask the user. Wait for their answer; do not push commits while the question is open.

4. For each fix cycle:
   - Apply the fixes.
   - Re-run the local completion gate to confirm nothing regressed locally.
   - `git add`, `git commit` with message `Fix SonarCloud findings (cycle <N>)`, `git push`.
   - Re-run Step 10 (CI Monitor) so SonarCloud re-analyzes the PR.
   - After CI is green, re-invoke this step.

5. **Cycle cap: 5 iterations for SonarCloud.** If the issue list is still non-empty after 5 fix→re-analyze cycles, STOP, post the remaining findings as an issue comment, and escalate to the user.

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
