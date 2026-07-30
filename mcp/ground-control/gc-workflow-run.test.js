// Adapter-level tests for the gc_workflow_run MCP handler and the lib.js
// workflow-run helper functions (issue #859). Exercises Zod-parsed args →
// handler dispatch → backend HTTP call (mocked fetch) → wire body shape.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_WORKFLOW_RUN_ACTIONS,
  WORKFLOW_RUN_WORKFLOW_TYPES,
  WORKFLOW_RUN_FINAL_STATES,
  WORKFLOW_RUN_OUTCOMES,
  WORKFLOW_RUN_PROVENANCES,
  WORKFLOW_RUN_EVENT_TYPES,
  gcWorkflowRunZodShape,
  gcWorkflowRunToolHandler,
  WORKFLOW_RUN_CREATE_FIELDS,
  WORKFLOW_RUN_EVENT_FIELDS,
  WORKFLOW_RUN_COST_FIELDS,
} from "./gc-workflow-run.js";
import { listWorkflowRuns } from "./lib.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 201, body = {} } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    const parsedBody = opts && opts.body ? JSON.parse(opts.body) : null;
    calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: parsedBody });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

beforeEach(() => {
  process.env.GC_BASE_URL = "https://gc.test";
  delete process.env.GROUND_CONTROL_API_TOKEN;
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
  if (ORIGINAL_API_TOKEN === undefined) delete process.env.GROUND_CONTROL_API_TOKEN;
  else process.env.GROUND_CONTROL_API_TOKEN = ORIGINAL_API_TOKEN;
});

// ── Zod shape ────────────────────────────────────────────────────────────────

describe("gcWorkflowRunZodShape", () => {
  const schema = z.object(gcWorkflowRunZodShape);

  it("rejects an unknown action", () => {
    const result = schema.safeParse({ action: "purge" });
    assert.equal(result.success, false);
  });

  it("accepts all defined actions", () => {
    for (const action of GC_WORKFLOW_RUN_ACTIONS) {
      const r = schema.safeParse({ action });
      assert.equal(r.success, true, `action '${action}' should be accepted`);
    }
  });

  it("accepts a full record payload", () => {
    const result = schema.safeParse({
      action: "record",
      project: "test-proj",
      workflow_type: "IMPLEMENT",
      provenance: "ISSUE_THREAD",
      issue_number: 42,
      branch: "feature/xyz",
      requirement_uids: ["GC-O007"],
      started_at: "2026-01-01T00:00:00Z",
      final_state: "MERGED",
      outcome: "MERGED",
      model: "claude-sonnet-4-5",
      model_invocation_count: 12,
      wall_clock_minutes: 25,
      cost_proxy: 1.5,
      cost_currency: "USD",
      token_usage: 50000,
    });
    assert.equal(result.success, true);
  });

  it("rejects a negative model_invocation_count", () => {
    const result = schema.safeParse({ action: "record", model_invocation_count: -1 });
    assert.equal(result.success, false);
  });

  it("rejects a negative cost_proxy", () => {
    const result = schema.safeParse({ action: "record", cost_proxy: -0.01 });
    assert.equal(result.success, false);
  });

  it("requires run_id to be a UUID when provided", () => {
    const result = schema.safeParse({ action: "record_event", run_id: "not-a-uuid" });
    assert.equal(result.success, false);
  });

  it("accepts a valid run_id UUID", () => {
    const result = schema.safeParse({
      action: "record_event",
      run_id: "11111111-1111-1111-1111-111111111111",
    });
    assert.equal(result.success, true);
  });
});

describe("GC_WORKFLOW_RUN_ACTIONS constant", () => {
  it("contains exactly the documented actions", () => {
    assert.deepEqual(
      [...GC_WORKFLOW_RUN_ACTIONS].sort(),
      [
        "activity",
        "aggregate",
        "cross_project_aggregate",
        "import_cost",
        "list",
        "list_events",
        "measurement",
        "record",
        "record_event",
        "record_finding_disposition",
      ],
    );
  });
});

describe("closed vocabulary constants", () => {
  it("WORKFLOW_RUN_WORKFLOW_TYPES contains IMPLEMENT and QUICKFIX", () => {
    assert.ok(WORKFLOW_RUN_WORKFLOW_TYPES.includes("IMPLEMENT"));
    assert.ok(WORKFLOW_RUN_WORKFLOW_TYPES.includes("QUICKFIX"));
  });

  it("WORKFLOW_RUN_FINAL_STATES contains MERGED and RUNNING", () => {
    assert.ok(WORKFLOW_RUN_FINAL_STATES.includes("MERGED"));
    assert.ok(WORKFLOW_RUN_FINAL_STATES.includes("RUNNING"));
  });

  it("WORKFLOW_RUN_OUTCOMES are MERGED, CLOSED_WITHOUT_MERGE, NONE", () => {
    assert.deepEqual([...WORKFLOW_RUN_OUTCOMES].sort(), ["CLOSED_WITHOUT_MERGE", "MERGED", "NONE"]);
  });

  it("WORKFLOW_RUN_PROVENANCES are the documented values", () => {
    // LIVE_EMISSION (issue #1435) names a fact the tool layer observed as a phase transitioned,
    // which is a different freshness and reconciliation contract from a reconstructed one.
    assert.deepEqual(
      [...WORKFLOW_RUN_PROVENANCES].sort(),
      ["ISSUE_THREAD", "LIVE_EMISSION", "MANUAL_IMPORT", "TEMPORAL_VISIBILITY"],
    );
  });

  it("WORKFLOW_RUN_FINAL_STATES carries the FAILED terminal state", () => {
    // Without it a non-recoverable failure is indistinguishable from an abandonment or a pause for
    // a human decision, and all three collapse into one bucket.
    assert.ok(WORKFLOW_RUN_FINAL_STATES.includes("FAILED"));
  });

  it("WORKFLOW_RUN_EVENT_TYPES contains STARTED and FAILED", () => {
    assert.ok(WORKFLOW_RUN_EVENT_TYPES.includes("STARTED"));
    assert.ok(WORKFLOW_RUN_EVENT_TYPES.includes("FAILED"));
  });
});

// ── Handler dispatch ─────────────────────────────────────────────────────────

describe("gcWorkflowRunToolHandler — record action", () => {
  it("POSTs to /api/v1/workflow-runs with project as query param", async () => {
    const calls = makeFetchSpy({
      status: 201,
      body: { id: "run-uuid", workflowType: "IMPLEMENT" },
    });
    await gcWorkflowRunToolHandler({
      action: "record",
      project: "my-proj",
      workflow_type: "IMPLEMENT",
      provenance: "ISSUE_THREAD",
      issue_number: 99,
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs");
    assert.equal(url.searchParams.get("project"), "my-proj");
    assert.equal(calls[0].body.workflowType, "IMPLEMENT");
    assert.equal(calls[0].body.provenance, "ISSUE_THREAD");
    assert.equal(calls[0].body.issueNumber, 99);
  });

  it("throws when workflow_type is missing", async () => {
    await assert.rejects(
      () => gcWorkflowRunToolHandler({ action: "record", provenance: "ISSUE_THREAD" }),
      /workflow_type.*record/i,
    );
  });

  it("throws when provenance is missing", async () => {
    await assert.rejects(
      () => gcWorkflowRunToolHandler({ action: "record", workflow_type: "IMPLEMENT" }),
      /provenance.*record/i,
    );
  });

  it("forwards requirement_uids as an array", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "x" } });
    await gcWorkflowRunToolHandler({
      action: "record",
      workflow_type: "IMPLEMENT",
      provenance: "ISSUE_THREAD",
      requirement_uids: ["GC-O007", "GC-S001"],
    });
    assert.deepEqual(calls[0].body.requirementUids, ["GC-O007", "GC-S001"]);
  });
});

describe("gcWorkflowRunToolHandler — record_event action", () => {
  it("POSTs to /api/v1/workflow-runs/{runId}/events", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "evt-uuid" } });
    await gcWorkflowRunToolHandler({
      action: "record_event",
      run_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      project: "test-proj",
      phase: "plan",
      event_type: "COMPLETED",
      occurred_at: "2026-01-01T12:00:00Z",
      provenance: "ISSUE_THREAD",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/events");
    // project must scope the run lookup (issue #859 security review).
    assert.equal(url.searchParams.get("project"), "test-proj");
    assert.equal(calls[0].body.phase, "plan");
    assert.equal(calls[0].body.eventType, "COMPLETED");
    assert.equal(calls[0].body.provenance, "ISSUE_THREAD");
  });

  it("forwards an emitter-supplied source_id as sourceId", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "evt-uuid" } });
    await gcWorkflowRunToolHandler({
      action: "record_event",
      run_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      project: "test-proj",
      phase: "ci",
      event_type: "COMPLETED",
      cycle_index: 2,
      occurred_at: "2026-01-01T12:00:00Z",
      provenance: "LIVE_EMISSION",
      source_id: "ci:COMPLETED:2",
    });
    assert.equal(calls[0].body.sourceId, "ci:COMPLETED:2");
    assert.equal(calls[0].body.cycleIndex, 2);
  });

  it("throws when run_id is missing", async () => {
    await assert.rejects(
      () =>
        gcWorkflowRunToolHandler({
          action: "record_event",
          project: "test-proj",
          phase: "plan",
          event_type: "COMPLETED",
          occurred_at: "2026-01-01T12:00:00Z",
          provenance: "ISSUE_THREAD",
        }),
      /run_id.*record_event/i,
    );
  });

  it("throws when project is missing", async () => {
    await assert.rejects(
      () =>
        gcWorkflowRunToolHandler({
          action: "record_event",
          run_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          phase: "plan",
          event_type: "COMPLETED",
          occurred_at: "2026-01-01T12:00:00Z",
          provenance: "ISSUE_THREAD",
        }),
      /project.*record_event/i,
    );
  });

  it("throws when occurred_at is missing", async () => {
    await assert.rejects(
      () =>
        gcWorkflowRunToolHandler({
          action: "record_event",
          run_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          project: "test-proj",
          phase: "plan",
          event_type: "COMPLETED",
          provenance: "ISSUE_THREAD",
        }),
      /occurred_at.*record_event/i,
    );
  });
});

describe("gcWorkflowRunToolHandler — import_cost action", () => {
  it("POSTs to /api/v1/workflow-runs/{runId}/cost", async () => {
    const calls = makeFetchSpy({
      status: 200,
      body: { id: "run-uuid", costProxy: 2.5 },
    });
    await gcWorkflowRunToolHandler({
      action: "import_cost",
      run_id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      project: "test-proj",
      cost_proxy: 2.5,
      cost_currency: "USD",
      model_invocation_count: 10,
    });
    assert.equal(calls[0].method, "POST");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/cost");
    assert.equal(url.searchParams.get("project"), "test-proj");
    assert.equal(calls[0].body.costProxy, 2.5);
  });

  it("throws when run_id is missing", async () => {
    await assert.rejects(
      () => gcWorkflowRunToolHandler({ action: "import_cost", project: "test-proj", cost_proxy: 1.0 }),
      /run_id.*import_cost/i,
    );
  });

  it("throws when project is missing", async () => {
    await assert.rejects(
      () =>
        gcWorkflowRunToolHandler({
          action: "import_cost",
          run_id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          cost_proxy: 1.0,
        }),
      /project.*import_cost/i,
    );
  });
});

describe("gcWorkflowRunToolHandler — list action", () => {
  it("GETs /api/v1/workflow-runs with project and limit params", async () => {
    const calls = makeFetchSpy({ status: 200, body: [] });
    await gcWorkflowRunToolHandler({ action: "list", project: "p1", limit: 10 });
    assert.equal(calls[0].method, "GET");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs");
    assert.equal(url.searchParams.get("project"), "p1");
    assert.equal(url.searchParams.get("limit"), "10");
  });
});

describe("gcWorkflowRunToolHandler — aggregate action", () => {
  it("GETs /api/v1/workflow-runs/aggregate with project and filters", async () => {
    const calls = makeFetchSpy({ status: 200, body: { totalRuns: 5 } });
    await gcWorkflowRunToolHandler({
      action: "aggregate",
      project: "proj-a",
      workflow_type: "IMPLEMENT",
      from: "2026-01-01",
      to: "2026-06-01",
    });
    assert.equal(calls[0].method, "GET");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs/aggregate");
    assert.equal(url.searchParams.get("project"), "proj-a");
    assert.equal(url.searchParams.get("workflowType"), "IMPLEMENT");
    assert.equal(url.searchParams.get("from"), "2026-01-01");
    assert.equal(url.searchParams.get("to"), "2026-06-01");
  });
});

describe("gcWorkflowRunToolHandler — cross_project_aggregate action", () => {
  it("GETs /api/v1/workflow-runs/cross-project-aggregate without project param when admin enabled", async () => {
    const calls = makeFetchSpy({ status: 200, body: { totalRuns: 100 } });
    await gcWorkflowRunToolHandler(
      {
        action: "cross_project_aggregate",
        workflow_type: "IMPLEMENT",
      },
      { adminEnabled: true },
    );
    assert.equal(calls[0].method, "GET");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs/cross-project-aggregate");
    // No project param on cross-project endpoint
    assert.equal(url.searchParams.get("project"), null);
    assert.equal(url.searchParams.get("workflowType"), "IMPLEMENT");
  });

  it("refuses cross_project_aggregate when admin tools are not enabled (default)", async () => {
    // A default MCP session (GC_MCP_ADMIN unset) must not reach cross-project telemetry through
    // this always-registered tool (issue #859 security review).
    await assert.rejects(
      () => gcWorkflowRunToolHandler({ action: "cross_project_aggregate", workflow_type: "IMPLEMENT" }),
      /GC_MCP_ADMIN/,
    );
  });
});

describe("gcWorkflowRunToolHandler — unknown action", () => {
  it("throws for an unrecognized action", async () => {
    await assert.rejects(
      () => gcWorkflowRunToolHandler({ action: "delete" }),
      /Unknown action/,
    );
  });
});

// ── Field allowlist guards ────────────────────────────────────────────────────

describe("WORKFLOW_RUN_CREATE_FIELDS", () => {
  it("includes workflow_type and provenance (required fields)", () => {
    assert.ok(WORKFLOW_RUN_CREATE_FIELDS.includes("workflow_type"));
    assert.ok(WORKFLOW_RUN_CREATE_FIELDS.includes("provenance"));
  });

  it("does NOT include action or project (routing fields, not body fields)", () => {
    assert.ok(!WORKFLOW_RUN_CREATE_FIELDS.includes("action"));
    assert.ok(!WORKFLOW_RUN_CREATE_FIELDS.includes("project"));
  });
});

describe("WORKFLOW_RUN_EVENT_FIELDS", () => {
  it("includes phase, event_type, occurred_at, provenance", () => {
    assert.ok(WORKFLOW_RUN_EVENT_FIELDS.includes("phase"));
    assert.ok(WORKFLOW_RUN_EVENT_FIELDS.includes("event_type"));
    assert.ok(WORKFLOW_RUN_EVENT_FIELDS.includes("occurred_at"));
    assert.ok(WORKFLOW_RUN_EVENT_FIELDS.includes("provenance"));
  });

  it("includes source_id so an emitter can attest the logical fact's identity", () => {
    // The backend derives phase:eventType:cycleIndex when this is absent. Dropping it from the pick
    // list would silently discard an emitter-supplied identity and fall back to the derived one.
    assert.ok(WORKFLOW_RUN_EVENT_FIELDS.includes("source_id"));
  });

  it("does NOT include run_id (path param, not body field)", () => {
    assert.ok(!WORKFLOW_RUN_EVENT_FIELDS.includes("run_id"));
  });
});

describe("WORKFLOW_RUN_COST_FIELDS", () => {
  it("includes cost_proxy, model, provider", () => {
    assert.ok(WORKFLOW_RUN_COST_FIELDS.includes("cost_proxy"));
    assert.ok(WORKFLOW_RUN_COST_FIELDS.includes("model"));
    assert.ok(WORKFLOW_RUN_COST_FIELDS.includes("provider"));
  });
});

// ── Event-stream guard on the shared HTTP client (issue #1436) ───────────────

describe("request() event-stream guard", () => {
  it("refuses a text/event-stream response instead of hanging on it", async () => {
    // res.text() on a live SSE response never resolves — the connection stays open by design and
    // heartbeats keep it from failing idle — so without this guard a streaming endpoint reached
    // through the shared client would hang the MCP server outright rather than erroring.
    globalThis.fetch = async () =>
      new Response("event: workflow-run\ndata: {}\n\n", {
        status: 200,
        headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
      });

    await assert.rejects(() => listWorkflowRuns("ground-control", 50), (error) => {
      assert.equal(error.code, "unsupported_media_type");
      return true;
    });
  });

  it("still reads an ordinary JSON response", async () => {
    globalThis.fetch = async () =>
      new Response(JSON.stringify([{ id: "run-1" }]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });

    const runs = await listWorkflowRuns("ground-control", 50);

    assert.deepEqual(runs, [{ id: "run-1" }]);
  });
});
