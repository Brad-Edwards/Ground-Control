// SonarCloud watcher envelope to station result and driver next action (issue #946).
//
// The monitor folded every non-passing envelope into one branch, so a missing
// MCP-host credential reached the driver as `sonar_findings_open` with
// "fix the findings" guidance for findings that were never read.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { classifySonarGateFailure, sonarGatePassed, sonarStationResult } from "./lib/sonar-gate.js";

const CLEAN = {
  ok: true,
  skipped: false,
  quality_gate: "OK",
  issues_summary: { open_count: 0 },
  hotspots_summary: { open_count: 0 },
};

describe("sonarGatePassed", () => {
  it("passes a clean gate with no open issues or hotspots", () => {
    assert.equal(sonarGatePassed(CLEAN), true);
  });

  it("passes a repo with no sonarcloud block", () => {
    assert.equal(sonarGatePassed({ ok: true, skipped: true }), true);
  });

  it("does not pass a clean gate that still has an open hotspot", () => {
    assert.equal(sonarGatePassed({ ...CLEAN, hotspots_summary: { open_count: 1 } }), false);
  });

  it("does not pass an envelope the watcher could not produce", () => {
    assert.equal(sonarGatePassed({ ok: false, error: "sonar_watch_token_missing" }), false);
  });
});

describe("sonarStationResult", () => {
  it("records a clean gate as a pass", () => {
    assert.equal(sonarStationResult(CLEAN), "pass");
  });

  it("records a repo with no sonarcloud block as coverage, not a pass", () => {
    assert.equal(sonarStationResult({ ok: true, skipped: true }), "skipped_station");
  });

  it("records open findings as a rejecting verdict", () => {
    assert.equal(sonarStationResult({ ...CLEAN, issues_summary: { open_count: 3 } }), "fail");
  });

  it("records a missing host credential as not evaluable", () => {
    assert.equal(sonarStationResult({ ok: false, error: "sonar_watch_token_missing" }), "not_evaluable");
  });

  it("records an analysis that never appeared as not evaluable, not a defect", () => {
    assert.equal(
      sonarStationResult({ ok: true, skipped: false, quality_gate: "NONE", timed_out: true }),
      "not_evaluable",
    );
  });
});

describe("classifySonarGateFailure", () => {
  it("names a missing MCP-host token an infrastructure blocker with an operator action", () => {
    const c = classifySonarGateFailure({
      ok: false,
      error: "sonar_watch_token_missing",
      message: "SONAR_TOKEN is not set on the MCP host",
    });
    assert.equal(c.sonar_gate, "not_evaluable");
    assert.equal(c.error, "sonar_watch_token_missing");
    assert.equal(c.next_action, "provision_sonar_token_on_mcp_host_then_rerun_monitor");
    assert.notEqual(c.next_action, "fix_sonar_findings_then_rerun_publish_and_monitor");
  });

  it("routes any other unevaluable envelope to diagnosis, never to fixing findings", () => {
    for (const error of [
      "sonar_watch_repo_not_found",
      "sonar_watch_input_invalid",
      "sonar_watch_quality_gate_failed",
      "sonar_watch_issues_fetch_failed",
      "sonar_watch_hotspots_fetch_failed",
    ]) {
      const c = classifySonarGateFailure({ ok: false, error });
      assert.equal(c.sonar_gate, "not_evaluable", error);
      assert.equal(c.error, error);
      assert.equal(c.next_action, "diagnose_sonar_watch_failure_then_rerun_monitor", error);
    }
  });

  it("keeps a stable error code when the watcher supplied none", () => {
    const c = classifySonarGateFailure({ ok: false });
    assert.equal(c.error, "sonar_watch_unavailable");
    assert.equal(c.sonar_gate, "not_evaluable");
  });

  it("tells the driver to wait when the analysis never appeared within the cap", () => {
    const c = classifySonarGateFailure({ ok: true, skipped: false, quality_gate: "NONE", timed_out: true });
    assert.equal(c.sonar_gate, "not_evaluable");
    assert.equal(c.error, "sonar_watch_analysis_timed_out");
    assert.equal(c.next_action, "rerun_monitor_after_sonar_analysis_completes");
  });

  it("keeps the findings branch for a gate that actually read open findings", () => {
    const c = classifySonarGateFailure({ ...CLEAN, quality_gate: "ERROR", issues_summary: { open_count: 2 } });
    assert.equal(c.sonar_gate, "findings_open");
    assert.equal(c.error, "sonar_findings_open");
    assert.equal(c.next_action, "fix_sonar_findings_then_rerun_publish_and_monitor");
  });
});
