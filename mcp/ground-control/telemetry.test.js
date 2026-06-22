// Tests for mcp/ground-control/telemetry.js (ADR-059, issue #1104).
// Proves: exactly one event on success, exactly one event on handler error,
// fail-open when POST rejects, and no leakage of nested args.

import { describe, it, beforeEach } from "node:test";
import assert from "node:assert/strict";
import {
  buildToolEvent,
  recordToolEvent,
  installToolTelemetry,
  _resetTelemetryInstallForTest,
} from "./telemetry.js";

// ---------------------------------------------------------------------------
// buildToolEvent
// ---------------------------------------------------------------------------

describe("buildToolEvent", () => {
  it("returns exactly the closed-shape keys", () => {
    const event = buildToolEvent({
      tool: "gc_query",
      action: "list",
      outcome: "ok",
      durationMs: 42,
      project: "myproject",
    });
    assert.deepEqual(Object.keys(event).sort(), [
      "action",
      "duration_ms",
      "outcome",
      "project",
      "tool",
      "ts",
    ]);
    assert.equal(event.tool, "gc_query");
    assert.equal(event.action, "list");
    assert.equal(event.outcome, "ok");
    assert.equal(event.duration_ms, 42);
    assert.equal(event.project, "myproject");
    assert.match(event.ts, /^\d{4}-\d{2}-\d{2}T/);
  });

  it("uses null for absent action and project", () => {
    const event = buildToolEvent({ tool: "gc_requirement", outcome: "ok", durationMs: 10 });
    assert.equal(event.action, null);
    assert.equal(event.project, null);
  });
});

// ---------------------------------------------------------------------------
// recordToolEvent — fail-open
// ---------------------------------------------------------------------------

describe("recordToolEvent", () => {
  const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

  function withBaseUrl(url, fn) {
    return async () => {
      process.env.GC_BASE_URL = url;
      try {
        await fn();
      } finally {
        if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
        else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
      }
    };
  }

  it(
    "posts to /api/v1/mcp-tool-usage/events and does not throw on 201",
    withBaseUrl("http://localhost:8000", async () => {
      let called = false;
      const fakeOk = async () => {
        called = true;
        return { ok: true, status: 201, text: async () => "" };
      };
      const event = buildToolEvent({ tool: "gc_query", outcome: "ok", durationMs: 5 });
      await recordToolEvent(event, fakeOk);
      assert.ok(called, "fetch should have been called");
    }),
  );

  it(
    "fails open when the fetch rejects",
    withBaseUrl("http://localhost:8000", async () => {
      const fakeFail = async () => {
        throw new Error("network error");
      };
      const event = buildToolEvent({ tool: "gc_query", outcome: "ok", durationMs: 5 });
      // Must not throw.
      await assert.doesNotReject(() => recordToolEvent(event, fakeFail));
    }),
  );

  it(
    "fails open when the server returns a non-2xx status",
    withBaseUrl("http://localhost:8000", async () => {
      const fake500 = async () => ({
        ok: false,
        status: 500,
        text: async () => '{"error":{"code":"internal_error","message":"boom"}}',
      });
      const event = buildToolEvent({ tool: "gc_query", outcome: "ok", durationMs: 5 });
      await assert.doesNotReject(() => recordToolEvent(event, fake500));
    }),
  );

  it("does nothing when GC_BASE_URL is unset", async () => {
    const saved = process.env.GC_BASE_URL;
    delete process.env.GC_BASE_URL;
    let called = false;
    const shouldNotBeCalled = async () => {
      called = true;
      return { ok: true, status: 201, text: async () => "" };
    };
    try {
      const event = buildToolEvent({ tool: "gc_query", outcome: "ok", durationMs: 5 });
      await assert.doesNotReject(() => recordToolEvent(event, shouldNotBeCalled));
      assert.ok(!called, "fetch should not be called when GC_BASE_URL is unset");
    } finally {
      if (saved === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = saved;
    }
  });
});

// ---------------------------------------------------------------------------
// installToolTelemetry — handler wrapper
// ---------------------------------------------------------------------------

describe("installToolTelemetry", () => {
  beforeEach(() => {
    _resetTelemetryInstallForTest();
  });

  function makeFakeServer() {
    return {
      tool: function (name, desc, schema, cb) {
        // Store the registered callback for test retrieval
        this._registeredTools = this._registeredTools ?? {};
        const handler = typeof cb === "function" ? cb : typeof schema === "function" ? schema : desc;
        this._registeredTools[name] = handler;
      },
      registerTool: function (name, config, cb) {
        this._registeredTools = this._registeredTools ?? {};
        if (typeof cb === "function") this._registeredTools[name] = cb;
      },
      _registeredTools: {},
    };
  }

  it("records exactly ONE event on a successful tool call with outcome ok", async () => {
    const events = [];
    const fakeFetch = async () => {
      return { ok: true, status: 201, text: async () => "" };
    };

    process.env.GC_BASE_URL = "http://localhost:8000";
    try {
      const server = makeFakeServer();
      installToolTelemetry(server, fakeFetch);

      let capturedBody;
      const capturingFetch = async (url, opts) => {
        capturedBody = JSON.parse(opts.body);
        events.push(capturedBody);
        return { ok: true, status: 201, text: async () => "" };
      };

      // Re-install with capture fetch (reset first)
      _resetTelemetryInstallForTest();
      installToolTelemetry(server, capturingFetch);

      // Register a tool with a successful handler
      server.tool("gc_test_tool", "desc", { action: Object }, async (args) => {
        return { content: [{ type: "text", text: "hello" }] };
      });

      const wrapped = server._registeredTools["gc_test_tool"];
      const result = await wrapped({ action: "create", project: "proj1" });

      // Wait for async fire-and-forget
      await new Promise((r) => setTimeout(r, 10));

      assert.equal(events.length, 1, "exactly one event should be recorded");
      assert.equal(events[0].tool, "gc_test_tool");
      assert.equal(events[0].outcome, "ok");
      assert.ok(events[0].durationMs >= 0, "durationMs should be non-negative");
      assert.equal(result.content[0].text, "hello");
    } finally {
      delete process.env.GC_BASE_URL;
      _resetTelemetryInstallForTest();
    }
  });

  it("records exactly ONE event on handler error result with the outcomeCode from _meta", async () => {
    const events = [];
    process.env.GC_BASE_URL = "http://localhost:8000";
    try {
      const server = makeFakeServer();
      const capturingFetch = async (url, opts) => {
        events.push(JSON.parse(opts.body));
        return { ok: true, status: 201, text: async () => "" };
      };
      installToolTelemetry(server, capturingFetch);

      // Register a tool that returns isError=true with a specific outcome code
      server.tool("gc_err_tool", "desc", {}, async (_args) => {
        return {
          content: [{ type: "text", text: "not found" }],
          isError: true,
          _meta: { "groundcontrol/outcomeCode": "not_found" },
        };
      });

      const wrapped = server._registeredTools["gc_err_tool"];
      const result = await wrapped({});

      await new Promise((r) => setTimeout(r, 10));

      assert.equal(events.length, 1, "exactly one event on handler error");
      assert.equal(events[0].tool, "gc_err_tool");
      assert.equal(events[0].outcome, "not_found");
      // Original result is unchanged
      assert.equal(result.isError, true);
      assert.equal(result.content[0].text, "not found");
    } finally {
      delete process.env.GC_BASE_URL;
      _resetTelemetryInstallForTest();
    }
  });

  it("fails open when recordToolEvent POST rejects — original result is still returned", async () => {
    process.env.GC_BASE_URL = "http://localhost:8000";
    try {
      const server = makeFakeServer();
      const rejectingFetch = async () => {
        throw new Error("network down");
      };
      installToolTelemetry(server, rejectingFetch);

      server.tool("gc_failopen_tool", "desc", {}, async (_args) => {
        return { content: [{ type: "text", text: "original result" }] };
      });

      const wrapped = server._registeredTools["gc_failopen_tool"];
      let result;
      // Must not throw
      await assert.doesNotReject(async () => {
        result = await wrapped({});
      });

      await new Promise((r) => setTimeout(r, 10));

      assert.equal(result.content[0].text, "original result");
    } finally {
      delete process.env.GC_BASE_URL;
      _resetTelemetryInstallForTest();
    }
  });

  it("closed-shape: recorded event has exactly {tool, action, outcome, duration_ms, project, ts} and no nested args", async () => {
    const events = [];
    process.env.GC_BASE_URL = "http://localhost:8000";
    try {
      const server = makeFakeServer();
      const capturingFetch = async (url, opts) => {
        events.push(JSON.parse(opts.body));
        return { ok: true, status: 201, text: async () => "" };
      };
      installToolTelemetry(server, capturingFetch);

      server.tool("gc_shape_tool", "desc", {}, async (_args) => {
        return { content: [{ type: "text", text: "ok" }] };
      });

      const wrapped = server._registeredTools["gc_shape_tool"];
      // Pass args with deeply nested content that must NOT appear in the event
      await wrapped({
        action: "run",
        project: "proj",
        prompt: "super secret prompt",
        body: { nested: { deeply: "sensitive" } },
      });

      await new Promise((r) => setTimeout(r, 10));

      assert.equal(events.length, 1);
      const sentBody = events[0];
      // Only the camelCase versions of closed-shape keys should be present
      // (the controller uses camelCase DTO fields: tool, action, outcome, durationMs, project, ts)
      const keys = Object.keys(sentBody).sort();
      assert.deepEqual(keys, ["action", "durationMs", "outcome", "project", "tool", "ts"]);
      // No nested payload leakage
      assert.equal(sentBody.prompt, undefined);
      assert.equal(sentBody.body, undefined);
    } finally {
      delete process.env.GC_BASE_URL;
      _resetTelemetryInstallForTest();
    }
  });
});
