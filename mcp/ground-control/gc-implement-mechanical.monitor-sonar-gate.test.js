// runMonitor's SonarCloud branch (issue #946).
//
// A Codex-spawned MCP host carries no SONAR_TOKEN, so gc_watch_sonar_analysis
// returned ok:false. The monitor folded that into the open-findings branch and
// told the driver to fix SonarCloud findings that had never been read, which is
// guidance no driver can act on: there is no defect to repair and re-running is
// deterministic. An unevaluable gate must reach the driver as an infrastructure
// blocker instead.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { runMonitor } from "./implement/publish.js";

const ISSUE_BRANCH = "946-sonar-token-resolution";
const stubEmitter = { station: async (_name, fn) => await fn() };

async function monitorWithSonar(sonar) {
  const stations = [];
  return {
    result: await runMonitor(
      { action: "monitor", repoPath: "/repo", issueNumber: 946, prNumber: 42, branchName: ISSUE_BRANCH },
      {
        runGit: async () => ({ stdout: ISSUE_BRANCH }),
        execFile: async () => ({ stdout: ISSUE_BRANCH }),
        emitter: {
          station: async (name, fn) => {
            stations.push({ name, outcome: await fn() });
          },
        },
        watchCi: async () => ({ ok: true, conclusion: "success" }),
        watchSonar: async () => sonar,
      },
    ),
    stations,
  };
}

describe("runMonitor — SonarCloud gate classification", () => {
  it("advances when the gate is clean", async () => {
    const { result } = await monitorWithSonar({
      ok: true,
      skipped: false,
      quality_gate: "OK",
      issues_summary: { open_count: 0 },
      hotspots_summary: { open_count: 0 },
    });
    assert.equal(result.ok, true);
    assert.equal(result.next_action, "post_pre_merge_readiness");
  });

  it("reports a missing MCP-host token as an infrastructure blocker, not open findings", async () => {
    const { result, stations } = await monitorWithSonar({
      ok: false,
      error: "sonar_watch_token_missing",
      message: "SONAR_TOKEN is not set on the MCP host",
      pr_number: 42,
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "sonar_watch_token_missing");
    assert.equal(result.sonar_gate, "not_evaluable");
    assert.equal(result.next_action, "provision_sonar_token_on_mcp_host_then_rerun_monitor");
    assert.notEqual(result.error, "sonar_findings_open");
    assert.equal(stations.at(-1).outcome.stationResult, "not_evaluable");
  });

  it("reports an analysis that never appeared as unevaluable rather than a defect", async () => {
    const { result, stations } = await monitorWithSonar({
      ok: true,
      skipped: false,
      quality_gate: "NONE",
      timed_out: true,
      issues_summary: { open_count: 0 },
      hotspots_summary: { open_count: 0 },
    });
    assert.equal(result.ok, false);
    assert.equal(result.sonar_gate, "not_evaluable");
    assert.equal(result.next_action, "rerun_monitor_after_sonar_analysis_completes");
    assert.equal(stations.at(-1).outcome.stationResult, "not_evaluable");
  });

  it("still routes genuinely open findings to the fix loop", async () => {
    const { result, stations } = await monitorWithSonar({
      ok: true,
      skipped: false,
      quality_gate: "ERROR",
      issues_summary: { open_count: 4 },
      hotspots_summary: { open_count: 0 },
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "sonar_findings_open");
    assert.equal(result.sonar_gate, "findings_open");
    assert.equal(result.next_action, "fix_sonar_findings_then_rerun_publish_and_monitor");
    assert.equal(stations.at(-1).outcome.stationResult, "fail");
  });
});
