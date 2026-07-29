import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { recordWorkflowRunEvent } from "./lib.js";

const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_FETCH = globalThis.fetch;

function withWorkflowRunEnv(fn) {
  return async () => {
    process.env.GC_BASE_URL = "https://gc.test";
    delete process.env.GROUND_CONTROL_API_TOKEN;
    try {
      await fn();
    } finally {
      if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
      globalThis.fetch = ORIGINAL_FETCH;
    }
  };
}

function makeFetchSpy() {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({
      url: url.toString(),
      method: opts?.method ?? "GET",
      body: opts?.body ? JSON.parse(opts.body) : null,
    });
    return new Response(JSON.stringify({ id: "evt-1" }), {
      status: 201,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

describe("recordWorkflowRunEvent station-result representation", () => {
  it(
    "maps the contract vocabulary to the REST enum representation",
    withWorkflowRunEnv(async () => {
      const expected = {
        pass: "PASS",
        fail: "FAIL",
        skipped_station: "SKIPPED_STATION",
        cancelled: "CANCELLED",
        not_evaluable: "NOT_EVALUABLE",
        unobserved: "UNOBSERVED",
      };

      for (const [contractResult, restResult] of Object.entries(expected)) {
        const calls = makeFetchSpy();
        await recordWorkflowRunEvent(
          "run-abc",
          {
            phase: "ci",
            event_type: "COMPLETED",
            occurred_at: "2026-01-01T12:00:00Z",
            provenance: "LIVE_EMISSION",
            station_result: contractResult,
          },
          "proj-a",
        );
        assert.equal(calls[0].body.stationResult, restResult);
      }
    }),
  );

  it(
    "rejects an unknown station result before transport",
    withWorkflowRunEnv(async () => {
      const calls = makeFetchSpy();

      await assert.rejects(
        () =>
          recordWorkflowRunEvent(
            "run-abc",
            {
              phase: "ci",
              event_type: "COMPLETED",
              occurred_at: "2026-01-01T12:00:00Z",
              provenance: "LIVE_EMISSION",
              station_result: "ok",
            },
            "proj-a",
          ),
        /Unknown station_result/,
      );
      assert.equal(calls.length, 0);
    }),
  );
});
