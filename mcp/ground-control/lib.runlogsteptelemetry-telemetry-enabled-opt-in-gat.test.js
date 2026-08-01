// Durable ADR-036 step observations (ADR-090 amendment, issue #1354). runLogStepTelemetry no longer
// writes a gitignored JSONL file: it upserts the ADR-061 run and records a step phase-event, keyed on
// the measurement model, strictly fail-open. The backend and repository-identity collaborators are
// injected so these run without a live server.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { runLogStepTelemetry, buildStepObservationEvent } from "./lib.js";

/** A git repo is the one collaborator runLogStepTelemetry resolves itself (ensureGitRepo). */
function makeGitRepo() {
  const dir = mkdtempSync(join(tmpdir(), "gc-tel-durable-"));
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  return dir;
}

const baseArgs = {
  issueNumber: 1354,
  branch: "1354-durable-telemetry-sink",
  stage: "completion_gate",
  step: "Step 6",
  attempt: 0,
  tier: "low",
  model: "claude-haiku-4-5",
  wallTimeMs: 1200,
  inputTokens: 8421,
  outputTokens: 612,
  outcome: "ok",
};

/** Injected collaborators that record what runLogStepTelemetry sent them. */
function stubDeps({ contextOverride, ownerRepo = "autarchy-ai/Ground-Control", createRun, recordEvent } = {}) {
  const calls = { createRun: [], recordEvent: [] };
  return {
    calls,
    deps: {
      getContext: async () =>
        contextOverride ?? { status: "ok", project: "ground-control", telemetry: { enabled: true } },
      getOwnerRepo: async () => ownerRepo,
      createRun:
        createRun ??
        (async (body, project) => {
          calls.createRun.push({ body, project });
          return { id: "11111111-1111-1111-1111-111111111111" };
        }),
      recordEvent:
        recordEvent ??
        (async (runId, body, project) => {
          calls.recordEvent.push({ runId, body, project });
          return { id: "22222222-2222-2222-2222-222222222222", station_id: "completion_gate" };
        }),
    },
  };
}

describe("runLogStepTelemetry (durable ADR-036 step observation — issue #1354)", () => {
  it("refuses with telemetry_disabled when the opt-in knob is false, and writes nothing", async () => {
    const dir = makeGitRepo();
    const { deps, calls } = stubDeps({
      contextOverride: { status: "ok", project: "ground-control", telemetry: { enabled: false } },
    });
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseArgs }, deps);
      assert.equal(r.ok, false);
      assert.equal(r.error, "telemetry_disabled");
      assert.equal(calls.createRun.length, 0);
      assert.equal(calls.recordEvent.length, 0);
      // The retired forward path wrote a JSONL file here; nothing does now.
      assert.equal(existsSync(join(dir, ".gc/telemetry")), false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses with telemetry_config_invalid when the repository context does not resolve", async () => {
    const dir = makeGitRepo();
    const { deps } = stubDeps({ contextOverride: { status: "missing_config" } });
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseArgs }, deps);
      assert.equal(r.ok, false);
      assert.equal(r.error, "telemetry_config_invalid");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("upserts the run and records a durable step observation on the phase-event path", async () => {
    const dir = makeGitRepo();
    const { deps, calls } = stubDeps();
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseArgs }, deps);
      assert.equal(r.ok, true);
      assert.equal(r.run_id, "11111111-1111-1111-1111-111111111111");
      assert.equal(r.event_id, "22222222-2222-2222-2222-222222222222");
      assert.equal(r.emitter, "ADR036_STEP_JSONL");

      // The run is upserted by the RAW branch natural key, as IMPLEMENT, with no final_state so an
      // open observation cannot overwrite a terminal one.
      assert.equal(calls.createRun.length, 1);
      const runBody = calls.createRun[0].body;
      assert.equal(runBody.repo, "autarchy-ai/Ground-Control");
      assert.equal(runBody.issue_number, 1354);
      assert.equal(runBody.branch, "1354-durable-telemetry-sink");
      assert.equal(runBody.workflow_type, "IMPLEMENT");
      assert.equal(runBody.provenance, "LIVE_EMISSION");
      assert.equal(runBody.final_state, undefined);
      assert.equal(calls.createRun[0].project, "ground-control");

      // The event carries the ADR-036 facts: stage as phase, no station id (backend resolves it),
      // UNOBSERVED implicitly (no station_result sent), namespaced source id, uppercased tier.
      const evtBody = calls.recordEvent[0].body;
      assert.equal(evtBody.phase, "completion_gate");
      assert.equal(evtBody.emitter, "ADR036_STEP_JSONL");
      assert.equal(evtBody.station_id, undefined);
      assert.equal(evtBody.station_result, undefined);
      assert.equal(evtBody.source_id, "adr036_step:completion_gate:0");
      assert.equal(evtBody.tier, "LOW");
      assert.equal(evtBody.step_alias, "Step 6");
      assert.equal(evtBody.measurement_version, "gc.measurement/v1");
      assert.equal(evtBody.provenance, "LIVE_EMISSION");
      assert.equal(evtBody.duration_ms, 1200);
      assert.equal(evtBody.outcome, "ok");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("is fail-open: a backend write failure returns a bounded diagnostic, never throws", async () => {
    const dir = makeGitRepo();
    const boom = Object.assign(new Error("connect ECONNREFUSED 127.0.0.1:8000"), { code: "ECONNREFUSED" });
    const { deps } = stubDeps({
      createRun: async () => {
        throw boom;
      },
    });
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseArgs }, deps);
      assert.equal(r.ok, false);
      assert.equal(r.error, "telemetry_durable_write_failed");
      // Only the stable failure class is surfaced — never the message, which carries the address.
      assert.equal(r.failure_class, "ECONNREFUSED");
      assert.ok(!("message" in r));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects an invalid record structurally before any backend call", async () => {
    const dir = makeGitRepo();
    const { deps, calls } = stubDeps();
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseArgs, tier: "ultra" }, deps);
      assert.equal(r.ok, false);
      assert.equal(r.error, "telemetry_input_invalid");
      assert.equal(calls.createRun.length, 0);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("buildStepObservationEvent (issue #1354)", () => {
  const base = { stage: "planning", step: "Step 4", tier: "high", model: "claude-opus-4-8", wallTimeMs: 5000, outcome: "ok", attempt: 2 };

  it("maps the ADR-036 facts onto the measurement-keyed event body", () => {
    const e = buildStepObservationEvent({ ...base, ts: "2026-07-29T10:00:00Z", inputTokens: 10, outputTokens: 20 });
    assert.equal(e.phase, "planning");
    assert.equal(e.event_type, "COMPLETED");
    assert.equal(e.occurred_at, "2026-07-29T10:00:00Z");
    assert.equal(e.emitter, "ADR036_STEP_JSONL");
    assert.equal(e.measurement_version, "gc.measurement/v1");
    assert.equal(e.provenance, "LIVE_EMISSION");
    assert.equal(e.cycle_index, 2);
    // Namespaced so it never collides with a live station attempt's phase:eventType:cycleIndex.
    assert.equal(e.source_id, "adr036_step:planning:2");
    assert.equal(e.tier, "HIGH");
    assert.equal(e.step_alias, "Step 4");
    assert.equal(e.input_tokens, 10);
    assert.equal(e.output_tokens, 20);
  });

  it("derives the tier/model consistency assertion", () => {
    const match = buildStepObservationEvent({ ...base, model: "claude-opus-4-8" });
    assert.equal(match.expected_model, "claude-opus-4-8");
    assert.equal(match.model_matches_expected, true);
    const mismatch = buildStepObservationEvent({ ...base, model: "claude-haiku-4-5" });
    assert.equal(mismatch.model_matches_expected, false);
  });

  it("defaults occurred_at and nulls the optional fields", () => {
    const e = buildStepObservationEvent({ ...base, step: undefined });
    assert.equal(typeof e.occurred_at, "string");
    assert.equal(e.step_alias, null);
    assert.equal(e.input_tokens, null);
    assert.equal(e.output_tokens, null);
  });

  it("rejects an unknown tier, a negative attempt, and a bad outcome", () => {
    assert.throws(() => buildStepObservationEvent({ ...base, tier: "ultra" }), /tier must be one of/);
    assert.throws(() => buildStepObservationEvent({ ...base, attempt: -1 }), /attempt must be non-negative/);
    assert.throws(() => buildStepObservationEvent({ ...base, outcome: "warned" }), /outcome must be one of/);
  });
});
