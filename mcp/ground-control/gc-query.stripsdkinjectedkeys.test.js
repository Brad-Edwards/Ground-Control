// Split from gc-query.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  GC_QUERY_BODY_BYTE_CAP,
  GC_QUERY_PATH_ALLOWLIST,
  GC_QUERY_PATH_DENYLIST,
  GC_QUERY_SDK_INJECTED_KEYS,
  gcQuerySchema,
  gcQueryToolHandler,
  stripSdkInjectedKeys,
  truncateBody,
  validateGcQueryParams,
  validateGcQueryPath,
} from "./gc-query.js";
import { RequestError } from "./lib.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

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

describe("stripSdkInjectedKeys", () => {
  it("exposes a deliberately tiny runtime-key set (currently just 'signal')", () => {
    // This test exists so growing the runtime-key set is a deliberate change.
    // Adding a key requires updating this assertion (and the ADR-035
    // "Public argument boundary" prose).
    assert.deepEqual([...GC_QUERY_SDK_INJECTED_KEYS].sort(), ["signal"]);
  });

  it("returns a shallow copy without the documented runtime keys when the value shape matches", () => {
    const ac = new AbortController();
    const input = { path: "/api/v1/requirements", signal: ac.signal, params: { a: 1 } };
    const out = stripSdkInjectedKeys(input);
    assert.deepEqual(Object.keys(out).sort(), ["params", "path"]);
    assert.notStrictEqual(out, input, "must return a new object, not mutate input");
    assert.ok("signal" in input, "must not mutate input");
  });

  it("is a no-op on undefined / null / empty", () => {
    assert.deepEqual(stripSdkInjectedKeys(undefined), {});
    assert.deepEqual(stripSdkInjectedKeys(null), {});
    assert.deepEqual(stripSdkInjectedKeys({}), {});
  });

  it("does not drop other unknown keys (only the documented runtime set)", () => {
    // 'headers' and 'method' are caller-facing unknowns; they survive the
    // strip and are rejected later by the handler allowlist. Stripping them
    // here would mask the rejection.
    const out = stripSdkInjectedKeys({
      path: "/api/v1/x",
      headers: { x: 1 },
      method: "DELETE",
    });
    assert.deepEqual(Object.keys(out).sort(), ["headers", "method", "path"]);
  });

  it("does NOT drop a 'signal' key whose value is not an AbortSignal (preserves the public contract)", () => {
    // A caller-supplied plain object under the name `signal` would otherwise
    // bypass the public-argument allowlist silently. The shape predicate is
    // what keeps the handler's defense in depth intact for arbitrary user
    // input — the name alone is not enough.
    for (const bogus of [
      { aborted: false }, // looks like a stub but missing addEventListener
      "not a signal",
      42,
      true,
      null,
    ]) {
      const out = stripSdkInjectedKeys({ path: "/api/v1/x", signal: bogus });
      assert.deepEqual(
        Object.keys(out).sort(),
        ["path", "signal"],
        `bogus signal value ${JSON.stringify(bogus)} must survive the strip`,
      );
    }
  });

  it("accepts a cross-realm AbortSignal-shaped object via the duck-type fallback", () => {
    // The duck-type fallback exists so an AbortSignal coming from a worker
    // or a different module realm (where `instanceof AbortSignal` fails)
    // still matches. The contract is: same surface as the built-in
    // AbortSignal (boolean `aborted`, `addEventListener`,
    // `removeEventListener`).
    const fakeSignal = {
      aborted: false,
      addEventListener: () => {},
      removeEventListener: () => {},
    };
    const out = stripSdkInjectedKeys({ path: "/api/v1/x", signal: fakeSignal });
    assert.deepEqual(Object.keys(out), ["path"]);
  });
});

describe("gcQueryToolHandler still rejects non-AbortSignal 'signal' values", () => {
  // Regression for the cycle-1 codex finding on #874: the SDK-signal strip
  // must not weaken the public-argument allowlist for non-runtime values
  // under the same key name.
  it(
    "rejects a plain-object 'signal' (not an AbortSignal) with invalid_query_args",
    withBaseUrl("http://localhost:8000", async () => {
      await assert.rejects(
        () =>
          gcQueryToolHandler(
            { path: "/api/v1/requirements", signal: { aborted: false } },
            { fetchImpl: async () => fakeResponse({ body: "{}" }) },
          ),
        (err) => err instanceof RequestError && err.code === "invalid_query_args",
      );
    }),
  );

  it(
    "rejects a string 'signal' with invalid_query_args",
    withBaseUrl("http://localhost:8000", async () => {
      await assert.rejects(
        () =>
          gcQueryToolHandler(
            { path: "/api/v1/requirements", signal: "not-a-signal" },
            { fetchImpl: async () => fakeResponse({ body: "{}" }) },
          ),
        (err) => err instanceof RequestError && err.code === "invalid_query_args",
      );
    }),
  );
});

describe("gcQuerySchema (Zod-level strict rejection)", () => {
  // The MCP SDK validates a tool's input against its Zod schema before calling
  // the handler. If we register with a non-strict object, unknown keys are
  // silently stripped — the handler never sees them. The schema MUST be a
  // strict ZodObject so the SDK rejects unknown keys at the protocol layer.
  it("is a Zod object (so the SDK can run schema validation against the args)", () => {
    assert.equal(typeof gcQuerySchema?.safeParse, "function");
  });

  it("accepts the documented shape", () => {
    const r = gcQuerySchema.safeParse({
      path: "/api/v1/requirements",
      params: { status: "ACTIVE" },
    });
    assert.equal(r.success, true);
  });

  it("rejects an unknown 'headers' key at the schema level", () => {
    const r = gcQuerySchema.safeParse({
      path: "/api/v1/requirements",
      headers: { Authorization: "Bearer evil" },
    });
    assert.equal(r.success, false, "schema must reject unknown 'headers' key");
  });

  it("rejects an unknown 'method' key at the schema level", () => {
    const r = gcQuerySchema.safeParse({
      path: "/api/v1/requirements",
      method: "DELETE",
    });
    assert.equal(r.success, false, "schema must reject unknown 'method' key");
  });
});

// ---------------------------------------------------------------------------
// Documentation/constant drift catch
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// gc_query path validation
// ---------------------------------------------------------------------------

describe("validateGcQueryPath", () => {
  it("accepts allowlisted /api/v1 read prefixes", () => {
    for (const prefix of GC_QUERY_PATH_ALLOWLIST) {
      assert.doesNotThrow(() => validateGcQueryPath(prefix));
    }
  });

  it("accepts a sub-path under an allowlisted prefix", () => {
    assert.doesNotThrow(() => validateGcQueryPath("/api/v1/requirements/abc-123"));
    assert.doesNotThrow(() => validateGcQueryPath("/api/v1/traceability/by-artifact"));
  });

  it("rejects an absolute URL", () => {
    assert.throws(() => validateGcQueryPath("http://evil/api/v1/requirements"), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath("https://evil/api/v1/requirements"), /invalid_query_path/);
  });

  it("rejects a protocol-relative URL", () => {
    assert.throws(() => validateGcQueryPath("//evil/api/v1/requirements"), /invalid_query_path/);
  });

  it("rejects path traversal", () => {
    assert.throws(() => validateGcQueryPath("/api/v1/requirements/../admin/users"), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath("/api/v1/../etc/passwd"), /invalid_query_path/);
  });

  it("rejects paths outside /api/v1/", () => {
    assert.throws(() => validateGcQueryPath("/actuator/health"), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath("/api/v2/requirements"), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath("/swagger-ui/index.html"), /invalid_query_path/);
  });

  it("rejects denied admin prefixes even though they live under /api/v1/", () => {
    for (const denied of GC_QUERY_PATH_DENYLIST) {
      assert.throws(() => validateGcQueryPath(`${denied}/whatever`), /invalid_query_path/);
    }
  });

  it("rejects an embedded query string", () => {
    assert.throws(() => validateGcQueryPath("/api/v1/requirements?status=ACTIVE"), /invalid_query_path/);
  });

  it("rejects percent-encoded segments (security: encoded-dot denylist bypass)", () => {
    assert.throws(
      () => validateGcQueryPath("/api/v1/requirements/%2e%2e/admin/users"),
      /invalid_query_path/,
    );
    assert.throws(() => validateGcQueryPath("/api/v1/requirements/%41"), /invalid_query_path/);
  });

  it("rejects backslash and other non-path characters", () => {
    assert.throws(() => validateGcQueryPath("/api/v1/requirements\\admin"), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath("/api/v1/requirements@admin"), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath("/api/v1/requirements;admin"), /invalid_query_path/);
  });

  it("rejects a fragment", () => {
    assert.throws(() => validateGcQueryPath("/api/v1/requirements#x"), /invalid_query_path/);
  });

  it("rejects a path that is not in the allowlist", () => {
    assert.throws(() => validateGcQueryPath("/api/v1/some-future-endpoint"), /invalid_query_path/);
  });

  it("rejects empty / non-string path", () => {
    assert.throws(() => validateGcQueryPath(""), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath(null), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath(undefined), /invalid_query_path/);
    assert.throws(() => validateGcQueryPath(42), /invalid_query_path/);
  });

  it("denylist takes precedence over allowlist regardless of prefix overlap", () => {
    assert.doesNotThrow(() => validateGcQueryPath("/api/v1/analysis/coverage-gaps"));
    assert.throws(() => validateGcQueryPath("/api/v1/analysis/sweep/run"), /invalid_query_path/);
  });

  it("allows project-scoped workflow-run reads but rejects the admin cross-project rollup", () => {
    // The project-scoped reads are allowlisted by prefix...
    assert.doesNotThrow(() => validateGcQueryPath("/api/v1/workflow-runs"));
    assert.doesNotThrow(() => validateGcQueryPath("/api/v1/workflow-runs/aggregate"));
    // ...but the ROLE_ADMIN cross-project rollup must not be reachable through the prefix, or the
    // gc_query escape hatch would route it with the admin token (issue #859 security review).
    assert.throws(
      () => validateGcQueryPath("/api/v1/workflow-runs/cross-project-aggregate"),
      /invalid_query_path/,
    );
  });

  it("rejects the live workflow-run stream, which the prefix would otherwise admit", () => {
    // The SSE stream sits under the allowlisted /api/v1/workflow-runs prefix but never ends
    // (issue #1436). Without this denylist entry gc_query opens it and blocks reading a response
    // that heartbeats forever, burning the full 30s timeout to return nothing.
    assert.throws(() => validateGcQueryPath("/api/v1/workflow-runs/stream"), /invalid_query_path/);
  });
});

describe("validateGcQueryParams", () => {
  it("accepts undefined / null / {} as 'no params'", () => {
    assert.doesNotThrow(() => validateGcQueryParams(undefined));
    assert.doesNotThrow(() => validateGcQueryParams(null));
    assert.doesNotThrow(() => validateGcQueryParams({}));
  });

  it("accepts string, number, boolean, null primitive values", () => {
    assert.doesNotThrow(() => validateGcQueryParams({ a: "x", b: 1, c: true, d: null }));
  });

  it("rejects array / nested / function values", () => {
    assert.throws(() => validateGcQueryParams({ a: [1, 2] }), /invalid_query_params/);
    assert.throws(() => validateGcQueryParams({ a: { b: 1 } }), /invalid_query_params/);
    assert.throws(() => validateGcQueryParams({ a: () => 1 }), /invalid_query_params/);
  });

  it("rejects non-object params", () => {
    assert.throws(() => validateGcQueryParams("a=1"), /invalid_query_params/);
    assert.throws(() => validateGcQueryParams([1, 2]), /invalid_query_params/);
  });
});

describe("truncateBody", () => {
  it("returns body unchanged when under the cap", () => {
    const body = "x".repeat(1024);
    const out = truncateBody(body);
    assert.equal(out.body, body);
    assert.equal(out.truncated, false);
    assert.equal(out.original_byte_length, 1024);
  });

  it("truncates body above the cap and marks it", () => {
    const body = "y".repeat(GC_QUERY_BODY_BYTE_CAP + 100);
    const out = truncateBody(body);
    assert.equal(out.truncated, true);
    assert.equal(out.original_byte_length, GC_QUERY_BODY_BYTE_CAP + 100);
    assert.match(out.body, /truncated/i);
  });

  it("uses byte length for the cap (multi-byte safety)", () => {
    const ch = "💥";
    const body = ch.repeat(Math.ceil(GC_QUERY_BODY_BYTE_CAP / 4) + 10);
    const out = truncateBody(body);
    assert.equal(out.truncated, true);
    assert.equal(out.original_byte_length, Buffer.byteLength(body, "utf8"));
  });
});

describe("gc_query MCP registration (issue #874 end-to-end wiring)", () => {
  // The cycle-1 codex fix only touched the handler. Cycle 2 found that the
  // root cause is upstream: `server.tool(name, desc, gcQuerySchema, cb)`
  // routes a constructed `.strict()` ZodObject into the `annotations` slot
  // (not `inputSchema`), so the SDK calls the registered callback with its
  // `extra` object (which carries `signal`) in the args position. This
  // suite exercises the real `McpServer` + `Client` + `InMemoryTransport`
  // wiring so a future regression of the registration shape fails here,
  // not in production.
  async function registeredServerCallingItself({ handler }) {
    const server = new McpServer({ name: "gc-query-registration-test", version: "1.0.0" });
    server.registerTool(
      "gc_query",
      { description: "test wiring", inputSchema: gcQuerySchema },
      handler,
    );
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "test-client", version: "1.0.0" });
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
    return { server, client };
  }

  it(
    "tools/list reports gcQuerySchema as the inputSchema (not as annotations)",
    withBaseUrl("http://localhost:8000", async () => {
      const { client } = await registeredServerCallingItself({
        handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
      });
      try {
        const list = await client.listTools();
        const tool = list.tools.find((t) => t.name === "gc_query");
        assert.ok(tool, "gc_query must be present in tools/list");
        // The strict schema must surface as a proper JSON Schema with the
        // documented properties + additionalProperties: false; an empty
        // `{type: "object"}` would mean the SDK treated the schema as
        // annotations (the issue #874 failure mode).
        assert.equal(tool.inputSchema.type, "object");
        assert.deepEqual(Object.keys(tool.inputSchema.properties).sort(), ["params", "path"]);
        assert.equal(tool.inputSchema.additionalProperties, false);
        assert.deepEqual(tool.inputSchema.required, ["path"]);
      } finally {
        await client.close();
      }
    }),
  );

  it(
    "tools/call with {path} succeeds and the handler receives args without an SDK-injected signal",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedArgs;
      const { client } = await registeredServerCallingItself({
        handler: async (args) => {
          capturedArgs = args;
          return { content: [{ type: "text", text: JSON.stringify(args) }] };
        },
      });
      try {
        const out = await client.callTool({
          name: "gc_query",
          arguments: { path: "/api/v1/requirements" },
        });
        assert.equal(out.isError, undefined);
        assert.deepEqual(Object.keys(capturedArgs).sort(), ["path"]);
        assert.equal(
          Object.prototype.hasOwnProperty.call(capturedArgs, "signal"),
          false,
          "the SDK-injected AbortSignal must remain in `extra`, never in `args`",
        );
      } finally {
        await client.close();
      }
    }),
  );

  it(
    "tools/call rejects an extra key from the client (schema-level enforcement)",
    withBaseUrl("http://localhost:8000", async () => {
      const { client } = await registeredServerCallingItself({
        handler: async () => ({ content: [{ type: "text", text: "should not run" }] }),
      });
      try {
        // The MCP client surfaces validation errors as McpError; either it
        // throws or the SDK returns isError. Either is acceptable — what
        // matters is that an unknown key from the client is REJECTED, not
        // silently dropped.
        let rejected = false;
        try {
          const out = await client.callTool({
            name: "gc_query",
            arguments: { path: "/api/v1/requirements", headers: { Authorization: "Bearer evil" } },
          });
          if (out.isError) rejected = true;
        } catch {
          rejected = true;
        }
        assert.equal(rejected, true, "unknown caller key must be rejected by schema or transport");
      } finally {
        await client.close();
      }
    }),
  );

  it(
    "tools/call with {path, params} routes both to the handler unchanged",
    withBaseUrl("http://localhost:8000", async () => {
      let capturedArgs;
      const { client } = await registeredServerCallingItself({
        handler: async (args) => {
          capturedArgs = args;
          return { content: [{ type: "text", text: "ok" }] };
        },
      });
      try {
        await client.callTool({
          name: "gc_query",
          arguments: { path: "/api/v1/requirements", params: { status: "ACTIVE" } },
        });
        assert.deepEqual(capturedArgs, {
          path: "/api/v1/requirements",
          params: { status: "ACTIVE" },
        });
      } finally {
        await client.close();
      }
    }),
  );
});

describe("gc_query allowlist docs match the implemented constant", () => {
  // These tests guard against the recurring shape "doc text states a count or
  // list of allowed gc_query prefixes that omits prefixes present in
  // GC_QUERY_PATH_ALLOWLIST." Codex flagged it on cycle 3 of issue #628.
  function readRepoFile(relPath) {
    return readFileSync(join(__dirname, "..", "..", relPath), "utf8");
  }

  it("README.md mentions every allowed prefix from GC_QUERY_PATH_ALLOWLIST", () => {
    const readme = readFileSync(join(__dirname, "README.md"), "utf8");
    const missing = GC_QUERY_PATH_ALLOWLIST.filter((p) => !readme.includes(p));
    assert.deepEqual(
      missing,
      [],
      `prefixes missing from mcp/ground-control/README.md: ${missing.join(", ")}`,
    );
  });

  it("ADR-035 mentions every allowed prefix from GC_QUERY_PATH_ALLOWLIST", () => {
    const adr = readRepoFile("architecture/adrs/035-mcp-tool-catalog-curation.md");
    const missing = GC_QUERY_PATH_ALLOWLIST.filter((p) => !adr.includes(p));
    assert.deepEqual(
      missing,
      [],
      `prefixes missing from architecture/adrs/035-mcp-tool-catalog-curation.md: ${missing.join(", ")}`,
    );
  });
});
