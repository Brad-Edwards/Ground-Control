import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { createWorkflowRunLifecycleEmitter } from "./workflow-run-lifecycle.js";

const IDENTITY = {
  project: "ground-control",
  repo: "autarchy-ai/Ground-Control",
  issueNumber: 1435,
  branch: "1435-live-workflow-run-emission",
  workflowType: "IMPLEMENT",
  runtimeDriver: "claude",
};

function recorder(overrides = {}) {
  const runs = [];
  const events = [];
  return {
    runs,
    events,
    deps: {
      baseUrlPresent: () => true,
      now: () => new Date("2026-07-26T12:00:00.000Z"),
      log: () => {},
      createRun: async (body) => {
        runs.push(body);
        return { id: "run-uuid" };
      },
      recordEvent: async (runId, body) => {
        events.push({ runId, ...body });
        // The backend assigns the attempt ordinal for an unordered STARTED event.
        return { ...body, cycle_index: body.cycle_index ?? 3 };
      },
      ...overrides,
    },
  };
}

describe("workflow-run lifecycle emitter — station result axis (issue #1355)", () => {
  it("records the station result the gate stated, not the lifecycle event type", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    await emitter.station("completion_gate", async () => ({ ok: true, stationResult: "pass" }));
    await emitter.flush();

    const terminal = events.find((e) => e.event_type === "COMPLETED");
    // ADR-090 section 3: COMPLETED means the phase finished, not that its inspection
    // passed. The verdict has to be stated, so the two axes stay independent.
    assert.equal(terminal.station_result, "pass");
    assert.equal(terminal.event_type, "COMPLETED");
  });

  it("records unobserved when a gate states no verdict", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    await emitter.station("git_publish", async () => ({ ok: true }));
    await emitter.flush();

    const terminal = events.find((e) => e.event_type === "COMPLETED");
    // Deriving `pass` from ok would be exactly the conflation the model forbids;
    // unobserved keeps the attempt out of the yield denominator instead.
    assert.equal(terminal.station_result, "unobserved");
  });

  it("classifies a thrown station as not_evaluable rather than a failed gate", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    const boom = new Error("transport died");
    boom.code = "emit_timeout";

    emitter.ensureRun();
    await assert.rejects(() => emitter.station("ci", async () => { throw boom; }));
    await emitter.flush();

    const terminal = events.find((e) => e.event_type === "FAILED");
    // A parser error or outage is an operation failure. Calling it a failed quality
    // gate would inject phantom defects into the rework signal.
    assert.equal(terminal.station_result, "not_evaluable");
  });

  it("rejects a station result outside the closed vocabulary", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    await emitter.station("policy", async () => ({ ok: false, stationResult: "ok" }));
    await emitter.flush();

    const terminal = events.find((e) => e.event_type === "FAILED");
    // `ok` belongs to the operation axis. Letting it through here would silently
    // merge the two vocabularies the contract keeps disjoint.
    assert.equal(terminal.station_result, "unobserved");
  });

  it("carries the finding batch on the terminal event only", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    const findings = [
      { findingKey: "k1", sourceKind: "detector", sourceId: "policy", category: "adr-guard", disposition: "open" },
    ];

    emitter.ensureRun();
    await emitter.station("policy", async () => ({ ok: false, stationResult: "fail", findings }));
    await emitter.flush();

    const started = events.find((e) => e.event_type === "STARTED");
    const terminal = events.find((e) => e.event_type === "FAILED");
    // The batch belongs to the attempt's verdict; attaching it to STARTED would
    // describe findings before the gate had produced any.
    assert.equal(started.findings, undefined);
    assert.deepEqual(terminal.findings, findings);
  });

  it("sends an explicit empty batch for a passing gate", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    await emitter.station("vale", async () => ({ ok: true, stationResult: "pass", findings: [] }));
    await emitter.flush();

    const terminal = events.find((e) => e.event_type === "COMPLETED");
    // "Zero findings" and "nobody looked" are different facts; an absent batch
    // would make a clean gate indistinguishable from an unmeasured one.
    assert.deepEqual(terminal.findings, []);
  });

  it("returns the station's own result untouched", async () => {
    const { deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    const result = await emitter.station("ci", async () => ({ ok: true, stationResult: "pass", ci: "green" }));
    await emitter.flush();

    assert.deepEqual(result, { ok: true, stationResult: "pass", ci: "green" });
  });

  it("keeps recording a marker transition off the station channel", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    emitter.markerTransition("ready_for_review");
    await emitter.flush();

    // ready_for_review records a transition and inspects nothing, so it can never
    // carry pass/fail — the catalogue declares it a marker, not a station.
    const marker = events.find((e) => e.phase === "ready_for_review");
    assert.equal(marker.station_result, undefined);
    assert.equal(marker.event_type, "COMPLETED");
  });
});

describe("workflow-run lifecycle emitter — already-executed child gates (issue #1355)", () => {
  it("records the child gate's own duration, not the time spent reading its report", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    emitter.recordStationAttempt({
      stationId: "spotbugs",
      startedAt: new Date("2026-07-28T10:00:00.000Z"),
      endedAt: new Date("2026-07-28T10:00:42.000Z"),
      durationMs: 42_000,
      stationResult: "pass",
      findings: [],
    });
    await emitter.flush();

    const terminal = events.find((e) => e.phase === "spotbugs" && e.event_type === "COMPLETED");
    assert.equal(terminal.duration_ms, 42_000);
    assert.equal(terminal.occurred_at, "2026-07-28T10:00:42.000Z");
    assert.equal(terminal.station_result, "pass");
  });

  it("omits duration when the child gate cannot attest one", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    emitter.recordStationAttempt({ stationId: "vale", stationResult: "fail", findings: [] });
    await emitter.flush();

    const terminal = events.find((e) => e.phase === "vale" && e.event_type === "FAILED");
    // Substituting the parent command's duration would bill the whole `make policy` run
    // to Vale and make per-gate cost meaningless.
    assert.equal(terminal.duration_ms, undefined);
  });

  it("opens a STARTED event so repeated attempts can be ordered", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    emitter.recordStationAttempt({ stationId: "policy", stationResult: "fail" });
    await emitter.flush();

    // Without STARTED every retry of a child gate collapses onto attempt 0 and
    // iterations-to-green becomes underivable for exactly these stations.
    assert.ok(events.some((e) => e.phase === "policy" && e.event_type === "STARTED"));
  });

  it("marks a failing child gate FAILED on the lifecycle axis too", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    emitter.recordStationAttempt({ stationId: "policy", stationResult: "fail", outcome: "policy_violations" });
    await emitter.flush();

    const terminal = events.find((e) => e.phase === "policy" && e.event_type !== "STARTED");
    assert.equal(terminal.event_type, "FAILED");
    assert.equal(terminal.outcome, "policy_violations");
  });
});
