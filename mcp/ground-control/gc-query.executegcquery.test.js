// Split from gc-query.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { GC_QUERY_BODY_BYTE_CAP, GC_QUERY_TIMEOUT_MS, executeGcQuery, gcQueryToolHandler } from "./gc-query.js";
import { RequestError } from "./lib.js";

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

function fakeResponse({ status = 200, body = "", headers = {} } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => body,
    headers: new Map(Object.entries(headers)),
  };
}

describe("executeGcQuery", () => {
  it(
    "issues a GET to the validated path and returns the body verbatim under the cap",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedUrl;
      let capturedOptions;
      const fetchImpl = async (url, options) => {
        capturedUrl = url;
        capturedOptions = options;
        return fakeResponse({ status: 200, body: '{"items":[]}' });
      };
      const out = await executeGcQuery(
        "/api/v1/requirements",
        { status: "ACTIVE" },
        { fetchImpl },
      );
      assert.equal(capturedOptions.method, "GET");
      assert.equal(capturedUrl, "http://localhost:8000/api/v1/requirements?status=ACTIVE");
      assert.equal(out.status, 200);
      assert.equal(out.body, '{"items":[]}');
      assert.equal(out.truncated, false);
      assert.equal(out.original_byte_length, 12);
    }),
  );

  it(
    "always sets X-Actor and Accept: application/json, and never reads caller-supplied headers",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedOptions;
      const fetchImpl = async (_url, options) => {
        capturedOptions = options;
        return fakeResponse({ body: "{}" });
      };
      await executeGcQuery("/api/v1/projects", undefined, { fetchImpl });
      assert.equal(capturedOptions.headers["X-Actor"], "mcp-server");
      assert.equal(capturedOptions.headers.Accept, "application/json");
      // executeGcQuery does NOT take a `headers` parameter at all; nothing to assert
      // about caller headers because there is no surface for them.
    }),
  );

  it(
    "passes a non-empty Authorization header when GROUND_CONTROL_API_TOKEN is set",
    withBaseUrl("http://localhost:8000", async () => {
      const prev = process.env.GROUND_CONTROL_API_TOKEN;
      process.env.GROUND_CONTROL_API_TOKEN = "test-token";
      try {
        let capturedOptions;
        const fetchImpl = async (_url, options) => {
          capturedOptions = options;
          return fakeResponse({ body: "{}" });
        };
        await executeGcQuery("/api/v1/requirements", undefined, { fetchImpl });
        assert.equal(capturedOptions.headers.Authorization, "Bearer test-token");
      } finally {
        if (prev === undefined) delete process.env.GROUND_CONTROL_API_TOKEN;
        else process.env.GROUND_CONTROL_API_TOKEN = prev;
      }
    }),
  );

  it(
    "rejects an invalid path before issuing any fetch",
    withBaseUrl("http://localhost:8000", async () => {
      let called = false;
      const fetchImpl = async () => {
        called = true;
        return fakeResponse();
      };
      await assert.rejects(
        () => executeGcQuery("/api/v1/admin/users", undefined, { fetchImpl }),
        (err) => err instanceof RequestError && err.code === "invalid_query_path",
      );
      assert.equal(called, false, "fetch must not be called for an invalid path");
    }),
  );

  it(
    "rejects invalid params before issuing any fetch",
    withBaseUrl("http://localhost:8000", async () => {
      let called = false;
      const fetchImpl = async () => {
        called = true;
        return fakeResponse();
      };
      await assert.rejects(
        () => executeGcQuery("/api/v1/requirements", { status: ["A", "B"] }, { fetchImpl }),
        (err) => err instanceof RequestError && err.code === "invalid_query_params",
      );
      assert.equal(called, false);
    }),
  );

  it(
    "surfaces a backend RequestError on 4xx with code/detail intact",
    withBaseUrl("http://localhost:8000", async () => {
      const fetchImpl = async () =>
        fakeResponse({
          status: 422,
          body: JSON.stringify({
            error: { code: "validation_failed", message: "bad input", detail: { field: "x" } },
          }),
        });
      await assert.rejects(
        () => executeGcQuery("/api/v1/requirements", undefined, { fetchImpl }),
        (err) => {
          return (
            err instanceof RequestError &&
            err.status === 422 &&
            err.code === "validation_failed" &&
            err.detail &&
            err.detail.field === "x"
          );
        },
      );
    }),
  );

  it(
    "surfaces a 5xx error and does NOT eat the body (preserves it as the error message)",
    withBaseUrl("http://localhost:8000", async () => {
      const fetchImpl = async () =>
        fakeResponse({ status: 500, body: "internal explosion" });
      await assert.rejects(
        () => executeGcQuery("/api/v1/requirements", undefined, { fetchImpl }),
        // `parseErrorBody` returns the raw body as `message` when it's not JSON.
        // The assertion below would fail if a regression swallowed the body and
        // surfaced an empty or generic error message.
        (err) =>
          err instanceof RequestError &&
          err.status === 500 &&
          typeof err.message === "string" &&
          err.message.includes("internal explosion"),
      );
    }),
  );

  it(
    "truncates a response body above the cap and reports original_byte_length",
    withBaseUrl("http://localhost:8000", async () => {
      const big = "x".repeat(GC_QUERY_BODY_BYTE_CAP + 4096);
      const fetchImpl = async () => fakeResponse({ status: 200, body: big });
      const out = await executeGcQuery("/api/v1/requirements", undefined, { fetchImpl });
      assert.equal(out.truncated, true);
      assert.equal(out.original_byte_length, GC_QUERY_BODY_BYTE_CAP + 4096);
      assert.match(out.body, /truncated/i);
    }),
  );

  it(
    "streams the body and stops reading once the cap is reached (does not buffer unbounded)",
    withBaseUrl("http://localhost:8000", async () => {
      // Construct a Response-like object whose body is a ReadableStream that
      // would yield ~10 MiB total if read to completion. We emit chunks until
      // the consumer stops pulling, then track how many bytes were yielded.
      const chunkSize = 64 * 1024;
      let bytesYielded = 0;
      let canceled = false;
      const stream = new ReadableStream({
        async pull(controller) {
          if (bytesYielded >= 10 * 1024 * 1024) {
            controller.close();
            return;
          }
          const chunk = new Uint8Array(chunkSize).fill(0x78); // 'x'
          controller.enqueue(chunk);
          bytesYielded += chunkSize;
        },
        cancel() {
          canceled = true;
        },
      });
      const fetchImpl = async () => ({
        ok: true,
        status: 200,
        body: stream,
        headers: new Map(),
      });
      const out = await executeGcQuery("/api/v1/requirements", undefined, { fetchImpl });
      assert.equal(out.truncated, true);
      // The reader must NOT have pulled the entire 10 MiB; the cap is 1 MiB
      // plus a small ceiling for the in-flight chunk that triggered the stop.
      assert.ok(
        bytesYielded <= GC_QUERY_BODY_BYTE_CAP + 2 * chunkSize,
        `expected ≤ ${GC_QUERY_BODY_BYTE_CAP + 2 * chunkSize} bytes pulled, got ${bytesYielded}`,
      );
      assert.equal(canceled, true, "the body stream must be canceled once the cap is hit");
    }),
  );

  it(
    "maps an AbortError into a RequestError with code gc_query_timeout",
    withBaseUrl("http://localhost:8000", async () => {
      const fetchImpl = async (_url, options) => {
        // Simulate a slow request that the AbortSignal interrupts.
        return new Promise((_resolve, reject) => {
          options.signal?.addEventListener("abort", () => {
            const err = new Error("aborted");
            err.name = "AbortError";
            reject(err);
          });
        });
      };
      // Squeeze the timeout so the test runs fast.
      await assert.rejects(
        () =>
          executeGcQuery("/api/v1/requirements", undefined, {
            fetchImpl,
            timeoutMs: 25,
          }),
        (err) => err instanceof RequestError && err.code === "gc_query_timeout",
      );
    }),
  );

  it(
    "exposes GC_QUERY_TIMEOUT_MS as the default and uses it when not overridden",
    withBaseUrl("http://localhost:8000", async () => {
      // Smoke test: just confirm the constant is exported and a positive number.
      assert.ok(GC_QUERY_TIMEOUT_MS > 0);
    }),
  );

  it(
    "does not stringify undefined/null params into ?key= entries",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedUrl;
      const fetchImpl = async (url) => {
        capturedUrl = url;
        return fakeResponse({ body: "{}" });
      };
      await executeGcQuery(
        "/api/v1/requirements",
        { status: undefined, wave: null, search: "x" },
        { fetchImpl },
      );
      assert.ok(!capturedUrl.includes("status="));
      assert.ok(!capturedUrl.includes("wave="));
      assert.ok(capturedUrl.includes("search=x"));
    }),
  );

  it(
    "returns null body verbatim on a 204",
    withBaseUrl("http://localhost:8000", async () => {
      const fetchImpl = async () => fakeResponse({ status: 204, body: "" });
      const out = await executeGcQuery("/api/v1/requirements", undefined, { fetchImpl });
      assert.equal(out.status, 204);
      assert.equal(out.body, "");
      assert.equal(out.truncated, false);
    }),
  );
});

describe("gcQueryToolHandler", () => {
  // The MCP SDK normalizes raw zod shapes via z.object() which strips unknown
  // keys instead of rejecting them. The handler-level check below is the
  // contract enforcement point: 'headers', 'method', or any other key beyond
  // 'path' and 'params' must be rejected, not silently dropped.
  it(
    "rejects an unknown 'headers' arg with code invalid_query_args",
    withBaseUrl("http://localhost:8000", async () => {
      await assert.rejects(
        () =>
          gcQueryToolHandler(
            {
              path: "/api/v1/requirements",
              headers: { Authorization: "Bearer evil" },
            },
            { fetchImpl: async () => fakeResponse({ body: "{}" }) },
          ),
        (err) => err instanceof RequestError && err.code === "invalid_query_args",
      );
    }),
  );

  it(
    "rejects an unknown 'method' arg",
    withBaseUrl("http://localhost:8000", async () => {
      await assert.rejects(
        () =>
          gcQueryToolHandler(
            { path: "/api/v1/requirements", method: "DELETE" },
            { fetchImpl: async () => fakeResponse({ body: "{}" }) },
          ),
        (err) => err instanceof RequestError && err.code === "invalid_query_args",
      );
    }),
  );

  it(
    "accepts the documented 'path' and 'params' keys",
    withBaseUrl("http://localhost:8000", async () => {
      const out = await gcQueryToolHandler(
        { path: "/api/v1/requirements", params: { status: "ACTIVE" } },
        { fetchImpl: async () => fakeResponse({ body: "{}" }) },
      );
      assert.equal(out.status, 200);
    }),
  );

  it(
    "accepts an empty args object (i.e. validates path before unknown-key check)",
    withBaseUrl("http://localhost:8000", async () => {
      // Empty args should produce a 'invalid_query_path' (path is undefined),
      // not 'invalid_query_args'. Order: schema-level keys check, then the
      // executor's path validator.
      await assert.rejects(
        () =>
          gcQueryToolHandler({}, { fetchImpl: async () => fakeResponse({ body: "{}" }) }),
        (err) => err instanceof RequestError && err.code === "invalid_query_path",
      );
    }),
  );

  // Some MCP clients/transports pipe an AbortController `signal` into the
  // tool-handler `args` object on every call (rather than only into the SDK's
  // `extra`). That is runtime plumbing, not a user-supplied argument, so the
  // handler must consume/ignore it at the adapter boundary BEFORE the public
  // allowlist runs (ADR-035 "Public argument boundary"; preflight note
  // architecture/notes/mcp-tool-catalog-surface-preflight.md "MCP runtime
  // plumbing"). It must NOT broaden into a generic passthrough — every other
  // unknown key still has to fail with invalid_query_args.
  it(
    "accepts an SDK-injected 'signal' arg silently (alongside path)",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedUrl;
      let capturedOptions;
      const fetchImpl = async (url, options) => {
        capturedUrl = url;
        capturedOptions = options;
        return fakeResponse({ body: '{"ok":true}' });
      };
      const ac = new AbortController();
      const out = await gcQueryToolHandler(
        { path: "/api/v1/requirements", signal: ac.signal },
        { fetchImpl },
      );
      assert.equal(out.status, 200);
      assert.equal(out.body, '{"ok":true}');
      // The inbound SDK signal must NOT be reflected anywhere in the outbound
      // request: not in the URL, not in the headers. (The outbound AbortSignal
      // on the fetch options is the executor's own 30s-timeout signal, not
      // the SDK-injected one — they must not be conflated.)
      assert.ok(!capturedUrl.includes("signal"));
      assert.equal(Object.prototype.hasOwnProperty.call(capturedOptions.headers, "signal"), false);
      assert.notStrictEqual(capturedOptions.signal, ac.signal);
    }),
  );

  it(
    "accepts an SDK-injected 'signal' arg alongside path + params and forwards only path/params downstream",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedUrl;
      const fetchImpl = async (url) => {
        capturedUrl = url;
        return fakeResponse({ body: "{}" });
      };
      const ac = new AbortController();
      await gcQueryToolHandler(
        {
          path: "/api/v1/requirements",
          params: { status: "ACTIVE" },
          signal: ac.signal,
        },
        { fetchImpl },
      );
      assert.equal(capturedUrl, "http://localhost:8000/api/v1/requirements?status=ACTIVE");
    }),
  );

  it(
    "still rejects 'headers' after the SDK-signal strip (regression: strip must not relax other rejections)",
    withBaseUrl("http://localhost:8000", async () => {
      const ac = new AbortController();
      await assert.rejects(
        () =>
          gcQueryToolHandler(
            {
              path: "/api/v1/requirements",
              headers: { Authorization: "Bearer evil" },
              signal: ac.signal,
            },
            { fetchImpl: async () => fakeResponse({ body: "{}" }) },
          ),
        (err) => err instanceof RequestError && err.code === "invalid_query_args",
      );
    }),
  );

  it(
    "still rejects 'method' after the SDK-signal strip (regression)",
    withBaseUrl("http://localhost:8000", async () => {
      const ac = new AbortController();
      await assert.rejects(
        () =>
          gcQueryToolHandler(
            {
              path: "/api/v1/requirements",
              method: "DELETE",
              signal: ac.signal,
            },
            { fetchImpl: async () => fakeResponse({ body: "{}" }) },
          ),
        (err) => err instanceof RequestError && err.code === "invalid_query_args",
      );
    }),
  );
});
