import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";

import { gcWorkflowRunToolHandler } from "./gc-workflow-run.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

beforeEach(() => {
  process.env.GC_BASE_URL = "https://gc.test";
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
});

describe("gcWorkflowRunToolHandler — activity action", () => {
  it("reads the bounded snapshot for the requested project", async () => {
    const calls = [];
    globalThis.fetch = async (url, options) => {
      calls.push({ url: url.toString(), method: options?.method ?? "GET" });
      return new Response(JSON.stringify({ observedAt: "2026-07-30T12:00:00Z", openRuns: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    };

    const result = await gcWorkflowRunToolHandler({
      action: "activity",
      project: "ground-control",
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/workflow-runs/activity");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(result.openRuns, []);
  });

  it("requires an explicit project", async () => {
    await assert.rejects(
      () => gcWorkflowRunToolHandler({ action: "activity" }),
      /project.*activity/i,
    );
  });
});
