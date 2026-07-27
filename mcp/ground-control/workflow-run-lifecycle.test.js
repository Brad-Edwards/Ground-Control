import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { createWorkflowRunLifecycleEmitter, LIFECYCLE_PROVENANCE } from "./workflow-run-lifecycle.js";

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

describe("workflow-run lifecycle emitter", () => {
  it("opens the run as RUNNING with a start time on the canonical identity", async () => {
    const { runs, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, requirementUids: ["GC-O007"], deps });

    emitter.openRun();
    await emitter.flush();

    assert.equal(emitter.runId, "run-uuid");
    assert.equal(runs.length, 1);
    assert.deepEqual(
      {
        issue_number: runs[0].issue_number,
        branch: runs[0].branch,
        repo: runs[0].repo,
        workflow_type: runs[0].workflow_type,
        final_state: runs[0].final_state,
        provenance: runs[0].provenance,
        started_at: runs[0].started_at,
        requirement_uids: runs[0].requirement_uids,
        runtime_driver: runs[0].runtime_driver,
      },
      {
        issue_number: 1435,
        branch: "1435-live-workflow-run-emission",
        repo: "autarchy-ai/Ground-Control",
        workflow_type: "IMPLEMENT",
        final_state: "RUNNING",
        provenance: LIFECYCLE_PROVENANCE,
        started_at: "2026-07-26T12:00:00.000Z",
        requirement_uids: ["GC-O007"],
        runtime_driver: "claude",
      },
    );
    assert.equal(runs[0].ended_at, undefined);
  });

  it("does not send lifecycle state when only ensuring the run identity", async () => {
    // A mid-run action must be able to resolve its run id without re-asserting RUNNING, which would
    // otherwise be an open observation racing a terminal one.
    const { runs, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.ensureRun();
    await emitter.flush();

    assert.equal(runs[0].final_state, undefined);
    assert.equal(runs[0].started_at, undefined);
  });

  it("carries the PR number on every write once the tool layer knows it", async () => {
    // A run's PR only becomes knowable at the monitor boundary. Dropping it leaves the read-model
    // with no join from a live-emitted run to the pull request that carried it, while the
    // issue-thread backfill records one — the two writers would disagree about the same row.
    const { runs, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, prNumber: 1452, deps });

    emitter.ensureRun();
    emitter.markState("READY_FOR_REVIEW");
    emitter.closeRun({ finalState: "MERGED", outcome: "MERGED" });
    await emitter.flush();

    assert.deepEqual(runs.map((run) => run.pr_number), [1452, 1452, 1452]);
  });

  it("omits the PR number before one is known, so an earlier link is never cleared", async () => {
    // The early boundaries upsert the same row the later ones do. Sending an absent PR as an
    // explicit null would let a bootstrap retry erase a PR number a later boundary had recorded.
    const { runs, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.openRun();
    await emitter.flush();

    assert.equal("pr_number" in runs[0], false);
  });

  it("brackets a station with STARTED then COMPLETED carrying the measured duration", async () => {
    const { events, deps } = recorder();
    let clock = 1000;
    const emitter = createWorkflowRunLifecycleEmitter({
      ...IDENTITY,
      deps: { ...deps, monotonic: () => clock },
    });
    emitter.openRun();

    const result = await emitter.station("completion_gate", async () => {
      clock += 4200;
      return { ok: true };
    });
    await emitter.flush();

    assert.deepEqual(result, { ok: true });
    assert.deepEqual(
      events.map((e) => [e.phase, e.event_type]),
      [
        ["completion_gate", "STARTED"],
        ["completion_gate", "COMPLETED"],
      ],
    );
    assert.equal(events[1].duration_ms, 4200);
    assert.equal(events[0].duration_ms, undefined);
  });

  it("threads the backend-assigned attempt ordinal onto the terminal event", async () => {
    // Both halves of one attempt must share an ordinal, otherwise the STARTED and its outcome look
    // like two different attempts and iterations-to-green is wrong.
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    emitter.openRun();

    await emitter.station("ci", async () => ({ ok: true }));
    await emitter.flush();

    assert.equal(events[0].cycle_index, undefined);
    assert.equal(events[1].cycle_index, 3);
  });

  it("records a station that returned a failure as FAILED with its stable error code", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    emitter.openRun();

    await emitter.station("ci", async () => ({ ok: false, error: "ci_failure", agent_required: true }));
    await emitter.flush();

    assert.equal(events[1].event_type, "FAILED");
    assert.equal(events[1].outcome, "ci_failure");
  });

  it("records a station that threw as FAILED and rethrows the original error", async () => {
    const { events, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    emitter.openRun();

    const boom = new Error("exploded");
    await assert.rejects(
      () => emitter.station("git_publish", async () => { throw boom; }),
      (thrown) => thrown === boom,
    );
    await emitter.flush();
    assert.equal(events[1].event_type, "FAILED");
  });

  it("closes the run with a terminal state and an end time", async () => {
    const { runs, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    emitter.openRun();

    emitter.closeRun({ finalState: "MERGED", outcome: "MERGED" });
    await emitter.flush();

    const closing = runs.at(-1);
    assert.equal(closing.final_state, "MERGED");
    assert.equal(closing.outcome, "MERGED");
    assert.equal(closing.ended_at, "2026-07-26T12:00:00.000Z");
  });

  it("marks a paused run READY_FOR_REVIEW without ending it", async () => {
    // READY_FOR_REVIEW is an open, paused state: giving it an end time would make a run awaiting a
    // human merge indistinguishable from a finished one and would corrupt cycle time.
    const { runs, deps } = recorder();
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });
    emitter.openRun();

    emitter.markState("READY_FOR_REVIEW");
    await emitter.flush();

    assert.equal(runs.at(-1).final_state, "READY_FOR_REVIEW");
    assert.equal(runs.at(-1).ended_at, undefined);
  });

  // ---- fail-open ---------------------------------------------------------------------------

  it("never throws when the backend rejects a write", async () => {
    const emitter = createWorkflowRunLifecycleEmitter({
      ...IDENTITY,
      deps: {
        baseUrlPresent: () => true,
        now: () => new Date("2026-07-26T12:00:00.000Z"),
        log: () => {},
        createRun: async () => {
          throw new Error("backend down");
        },
        recordEvent: async () => {
          throw new Error("backend down");
        },
      },
    });

    emitter.openRun();
    assert.deepEqual(await emitter.station("ci", async () => ({ ok: true })), { ok: true });
    emitter.closeRun({ finalState: "MERGED", outcome: "MERGED" });
    emitter.markState("READY_FOR_REVIEW");
    await emitter.flush();
    assert.equal(emitter.runId, null);
  });

  it("stops emitting after the first failure so one outage costs one timeout, not one per boundary", async () => {
    let attempts = 0;
    const emitter = createWorkflowRunLifecycleEmitter({
      ...IDENTITY,
      deps: {
        baseUrlPresent: () => true,
        now: () => new Date("2026-07-26T12:00:00.000Z"),
        log: () => {},
        createRun: async () => {
          attempts += 1;
          throw new Error("backend down");
        },
        recordEvent: async () => {
          attempts += 1;
          throw new Error("backend down");
        },
      },
    });

    emitter.openRun();
    await emitter.station("ci", async () => ({ ok: true }));
    await emitter.station("sonarcloud", async () => ({ ok: true }));
    await emitter.flush();

    assert.equal(attempts, 1);
  });

  it("logs only bounded identifiers and a failure class, never payloads or messages", async () => {
    const logged = [];
    const emitter = createWorkflowRunLifecycleEmitter({
      ...IDENTITY,
      deps: {
        baseUrlPresent: () => true,
        now: () => new Date("2026-07-26T12:00:00.000Z"),
        log: (line) => logged.push(line),
        createRun: async () => {
          const e = new Error("bearer sk-secret-token rejected by https://internal.example");
          e.code = "unauthorized";
          throw e;
        },
        recordEvent: async () => ({}),
      },
    });

    emitter.openRun();
    await emitter.flush();

    assert.equal(logged.length, 1);
    assert.match(logged[0], /issue=1435/);
    assert.match(logged[0], /failure=unauthorized/);
    assert.ok(!logged[0].includes("sk-secret-token"));
    assert.ok(!logged[0].includes("internal.example"));
  });

  it("does not attempt any write when no backend is configured", async () => {
    let called = false;
    const emitter = createWorkflowRunLifecycleEmitter({
      ...IDENTITY,
      deps: {
        baseUrlPresent: () => false,
        now: () => new Date("2026-07-26T12:00:00.000Z"),
        log: () => {},
        createRun: async () => {
          called = true;
          return { id: "x" };
        },
        recordEvent: async () => {
          called = true;
          return {};
        },
      },
    });

    emitter.openRun();
    await emitter.station("ci", async () => ({ ok: true }));
    await emitter.flush();

    assert.equal(called, false);
  });

  it("emits no event when the run could not be opened", async () => {
    // Without a run id an event has nothing to attach to; inventing one would fabricate a run.
    const { events, deps } = recorder({ createRun: async () => ({}) });
    const emitter = createWorkflowRunLifecycleEmitter({ ...IDENTITY, deps });

    emitter.openRun();
    await emitter.station("ci", async () => ({ ok: true }));
    await emitter.flush();

    assert.equal(events.length, 0);
  });
});

describe("workflow-run lifecycle emitter — bounded waits", () => {
  it("gives up on a hung backend and names the timeout in the diagnostic", async () => {
    const logged = [];
    const emitter = createWorkflowRunLifecycleEmitter({
      ...IDENTITY,
      deps: {
        baseUrlPresent: () => true,
        now: () => new Date("2026-07-26T12:00:00.000Z"),
        log: (line) => logged.push(line),
        // A transport that ignores the abort signal entirely.
        createRun: () => new Promise(() => {}),
        recordEvent: () => new Promise(() => {}),
      },
    });

    emitter.openRun();
    await emitter.flush();
    assert.equal(emitter.runId, null);
    assert.equal(logged.length, 1);
    assert.match(logged[0], /failure=emit_timeout/);
  });
});

// ---------------------------------------------------------------------------
// Transport contract
// ---------------------------------------------------------------------------

describe("workflow-run lifecycle emitter — wire contract", () => {
  /**
   * The emitter names fields in the repo's snake_case MCP convention and the shared `request()`
   * helper renames them to the camelCase the Java request records expect. That rename is an
   * allowlist, so a field missing from it would reach the backend snake_cased, be rejected as a
   * missing required property, and — because emission is fail-open — disappear silently. This pins
   * the actual bytes rather than trusting the allowlist to stay complete.
   */
  it("serializes every lifecycle field to the camelCase names the REST DTOs declare", async () => {
    const { createWorkflowRun, recordWorkflowRunEvent } = await import("./lib.js");
    const originalFetch = globalThis.fetch;
    const originalBaseUrl = process.env.GC_BASE_URL;
    const sent = [];
    process.env.GC_BASE_URL = "http://backend.test";
    globalThis.fetch = async (_url, options) => {
      sent.push(JSON.parse(options.body));
      return { ok: true, status: 200, text: async () => JSON.stringify({ id: "run-uuid" }) };
    };

    try {
      const emitter = createWorkflowRunLifecycleEmitter({
        ...IDENTITY,
        requirementUids: ["GC-O007"],
        prNumber: 1452,
        deps: {
          createRun: createWorkflowRun,
          recordEvent: recordWorkflowRunEvent,
          now: () => new Date("2026-07-26T12:00:00.000Z"),
        },
      });
      emitter.openRun();
      await emitter.station("ci", async () => ({ ok: false, error: "ci_failure" }));
      emitter.closeRun({ finalState: "MERGED", outcome: "MERGED" });
      await emitter.flush();
    } finally {
      globalThis.fetch = originalFetch;
      if (originalBaseUrl === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = originalBaseUrl;
    }

    const keys = new Set(sent.flatMap((body) => Object.keys(body)));
    for (const expected of [
      "issueNumber",
      "prNumber",
      "workflowType",
      "runtimeDriver",
      "requirementUids",
      "finalState",
      "startedAt",
      "endedAt",
      "provenance",
      "outcome",
      "phase",
      "eventType",
      "occurredAt",
      "durationMs",
    ]) {
      assert.ok(keys.has(expected), `expected wire key '${expected}', got ${[...keys].join(", ")}`);
    }
    // No snake_case leaked through the rename allowlist.
    const leaked = [...keys].filter((key) => key.includes("_"));
    assert.deepEqual(leaked, [], `snake_case keys reached the backend: ${leaked.join(", ")}`);
  });
});
