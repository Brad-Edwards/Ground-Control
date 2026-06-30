import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_DATA_CLASSIFICATION_ACTIONS,
  gcDataClassificationToolHandler,
  gcDataClassificationZodShape,
} from "./gc-data-classification.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

function makeFetchSpy({ status = 200, body = { source: "DEFAULT" } } = {}) {
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
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
});

describe("gcDataClassificationZodShape", () => {
  const schema = z.object(gcDataClassificationZodShape);

  it("accepts a set_lattice payload", () => {
    const parsed = schema.parse({
      action: "set_lattice",
      project: "ground-control",
      labels: [
        { key: "PUBLIC", display_name: "Public" },
        { key: "SECRET", display_name: "Secret", rank: 1 },
      ],
      permitted_flows: [{ from: "PUBLIC", to: "SECRET" }],
    });
    assert.equal(parsed.labels.length, 2);
    assert.equal(parsed.permitted_flows.length, 1);
  });

  it("rejects a malformed label key", () => {
    assert.throws(() =>
      schema.parse({
        action: "set_lattice",
        labels: [{ key: "bad key!", display_name: "Bad" }],
      }),
    );
  });

  it("exposes the four lattice actions", () => {
    assert.deepEqual([...GC_DATA_CLASSIFICATION_ACTIONS].sort(), [
      "evaluate",
      "get_lattice",
      "reset_lattice",
      "set_lattice",
    ]);
  });
});

describe("gcDataClassificationToolHandler", () => {
  it("routes get_lattice as a project-scoped GET", async () => {
    const calls = makeFetchSpy();
    await gcDataClassificationToolHandler({ action: "get_lattice", project: "ground-control" });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/data-classification\/lattice\b/);
    assert.match(calls[0].url, /project=ground-control/);
  });

  it("sends set_lattice body as camelCase via PUT", async () => {
    const calls = makeFetchSpy();
    await gcDataClassificationToolHandler({
      action: "set_lattice",
      project: "ground-control",
      labels: [
        { key: "PUBLIC", display_name: "Public" },
        { key: "SECRET", display_name: "Secret", rank: 1 },
      ],
      permitted_flows: [{ from: "PUBLIC", to: "SECRET" }],
    });
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, /\/api\/v1\/data-classification\/lattice\b/);
    assert.deepEqual(calls[0].body, {
      labels: [
        { key: "PUBLIC", displayName: "Public" },
        { key: "SECRET", displayName: "Secret", rank: 1 },
      ],
      permittedFlows: [{ from: "PUBLIC", to: "SECRET" }],
    });
  });

  it("routes reset_lattice as a DELETE", async () => {
    const calls = makeFetchSpy();
    await gcDataClassificationToolHandler({ action: "reset_lattice", project: "ground-control" });
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, /\/api\/v1\/data-classification\/lattice\b/);
  });

  it("routes evaluate as a GET with the snapshot id param", async () => {
    const calls = makeFetchSpy({ body: { violations: [] } });
    await gcDataClassificationToolHandler({
      action: "evaluate",
      project: "ground-control",
      snapshot_id: "11111111-1111-1111-1111-111111111111",
    });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/data-classification\/evaluation\b/);
    assert.match(calls[0].url, /snapshotId=11111111-1111-1111-1111-111111111111/);
  });
});
