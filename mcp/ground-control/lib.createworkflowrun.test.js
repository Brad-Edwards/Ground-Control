// Split from lib.closeissue-and-workflowrun.test.js under the 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). createWorkflowRun telemetry is a distinct
// concern from the close-issue gate that shared the file; test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { createWorkflowRun } from "./lib.js";

// ---------------------------------------------------------------------------
// Workflow-run telemetry lib helpers (issue #859)
// ---------------------------------------------------------------------------

const WORKFLOW_RUN_BASE_URL = "https://gc.test";

const WORKFLOW_RUN_ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

const WORKFLOW_RUN_ORIGINAL_FETCH = globalThis.fetch;

function withWorkflowRunEnv(fn) {
  return async () => {
    process.env.GC_BASE_URL = WORKFLOW_RUN_BASE_URL;
    delete process.env.GROUND_CONTROL_API_TOKEN;
    try {
      await fn();
    } finally {
      if (WORKFLOW_RUN_ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = WORKFLOW_RUN_ORIGINAL_BASE_URL;
      globalThis.fetch = WORKFLOW_RUN_ORIGINAL_FETCH;
    }
  };
}

function makeWorkflowRunFetchSpy({ status = 201, body = {} } = {}) {
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

describe("createWorkflowRun", () => {
  it(
    "POSTs to /api/v1/workflow-runs with project as query param",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: { id: "wrun-1" } });
      await createWorkflowRun(
        { workflow_type: "IMPLEMENT", provenance: "ISSUE_THREAD" },
        "proj-a",
      );
      assert.equal(calls.length, 1);
      assert.equal(calls[0].method, "POST");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs");
      assert.equal(url.searchParams.get("project"), "proj-a");
    }),
  );

  it(
    "sends camelCase body to the backend",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: { id: "x" } });
      await createWorkflowRun({
        workflow_type: "IMPLEMENT",
        provenance: "ISSUE_THREAD",
        issue_number: 42,
        requirement_uids: ["GC-O007"],
      });
      assert.equal(calls[0].body.workflowType, "IMPLEMENT");
      assert.equal(calls[0].body.provenance, "ISSUE_THREAD");
      assert.equal(calls[0].body.issueNumber, 42);
      assert.deepEqual(calls[0].body.requirementUids, ["GC-O007"]);
    }),
  );

  it(
    "omits the project query param when not provided",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: {} });
      await createWorkflowRun({ workflow_type: "IMPLEMENT", provenance: "MANUAL_IMPORT" });
      const url = new URL(calls[0].url);
      assert.equal(url.searchParams.get("project"), null);
    }),
  );
});
