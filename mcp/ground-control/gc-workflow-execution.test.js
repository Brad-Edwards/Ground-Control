// Adapter-level tests for the gc_workflow_execution MCP handler (GC-O009 #1278).
// Exercises Zod-parsed args → handler dispatch → backend HTTP call (mocked
// fetch) → wire shape. Guards that only the closed field set is forwarded, that
// snake_case fields land as the camelCase backend DTO names, and that required
// args are enforced per action.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_WORKFLOW_EXECUTION_ACTIONS,
  WORKFLOW_SIGNAL_TYPES,
  gcWorkflowExecutionZodShape,
  gcWorkflowExecutionToolHandler,
} from "./gc-workflow-execution.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 200, body = { workflowId: "gc-implement-p-1" } } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    const parsedBody = opts && opts.body ? JSON.parse(opts.body) : null;
    calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: parsedBody });
    return new Response(status === 202 ? "" : JSON.stringify(body), {
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

describe("gcWorkflowExecutionZodShape", () => {
  it("only allows start / get / list / signal actions", () => {
    assert.deepEqual([...GC_WORKFLOW_EXECUTION_ACTIONS].sort(), ["get", "list", "signal", "start"]);
  });

  it("rejects an unknown action", () => {
    const schema = z.object(gcWorkflowExecutionZodShape);
    assert.equal(schema.safeParse({ action: "cancel" }).success, false);
  });

  it("rejects an unknown signal_type", () => {
    const schema = z.object(gcWorkflowExecutionZodShape);
    assert.equal(schema.safeParse({ action: "signal", signal_type: "PAUSE" }).success, false);
    assert.deepEqual([...WORKFLOW_SIGNAL_TYPES].sort(), [
      "CANCEL",
      "RETRY_FROM",
      "REVIEW_CAP_DISPOSITION",
    ]);
  });
});

describe("start", () => {
  it("POSTs the camelCase start body with the project query param", async () => {
    const calls = makeFetchSpy({ status: 201, body: { workflowId: "gc-implement-p-1", runId: "r1" } });
    await gcWorkflowExecutionToolHandler({
      action: "start",
      project: "p",
      workflow_type: "IMPLEMENT",
      issue_number: 1278,
      requirement_uids: ["GC-O009"],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/workflow-executions\?project=p$/);
    assert.deepEqual(calls[0].body, {
      workflowType: "IMPLEMENT",
      issueNumber: 1278,
      requirementUids: ["GC-O009"],
    });
  });

  it("does not forward fields outside the closed allowlist", async () => {
    const calls = makeFetchSpy({ status: 201 });
    await gcWorkflowExecutionToolHandler({
      action: "start",
      project: "p",
      workflow_type: "IMPLEMENT",
      issue_number: 1,
      namespace: "should-not-forward",
      task_queue: "should-not-forward",
    });
    assert.equal(calls[0].body.namespace, undefined);
    assert.equal(calls[0].body.taskQueue, undefined);
  });

  it("requires project, workflow_type, issue_number", async () => {
    await assert.rejects(() => gcWorkflowExecutionToolHandler({ action: "start", workflow_type: "IMPLEMENT" }));
  });
});

describe("get / list", () => {
  it("GETs one execution by workflow_id", async () => {
    const calls = makeFetchSpy();
    await gcWorkflowExecutionToolHandler({ action: "get", project: "p", workflow_id: "gc-implement-p-1" });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/workflow-executions\/gc-implement-p-1\?project=p$/);
  });

  it("GETs the project's executions with limit", async () => {
    const calls = makeFetchSpy({ body: [] });
    await gcWorkflowExecutionToolHandler({ action: "list", project: "p", limit: 10 });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/workflow-executions\?/);
    assert.match(calls[0].url, /project=p/);
    assert.match(calls[0].url, /limit=10/);
  });
});

describe("signal", () => {
  it("POSTs the camelCase signal body to the signals sub-resource", async () => {
    const calls = makeFetchSpy({ status: 202 });
    await gcWorkflowExecutionToolHandler({
      action: "signal",
      project: "p",
      workflow_id: "gc-implement-p-1",
      signal_type: "REVIEW_CAP_DISPOSITION",
      reviewer: "TEST_QUALITY",
      disposition: "ONE_MORE_CYCLE",
    });
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/workflow-executions\/gc-implement-p-1\/signals\?project=p$/);
    assert.deepEqual(calls[0].body, {
      signalType: "REVIEW_CAP_DISPOSITION",
      reviewer: "TEST_QUALITY",
      disposition: "ONE_MORE_CYCLE",
    });
  });

  it("requires project, workflow_id, signal_type", async () => {
    await assert.rejects(() =>
      gcWorkflowExecutionToolHandler({ action: "signal", project: "p", workflow_id: "gc-implement-p-1" }),
    );
  });
});
