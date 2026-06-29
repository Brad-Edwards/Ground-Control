import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_ARCHITECTURE_MODEL_ACTIONS,
  GC_ARCHITECTURE_MODEL_CREATE_SNAPSHOT_FIELDS,
  gcArchitectureModelToolHandler,
  gcArchitectureModelZodShape,
} from "./gc-architecture-model.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

function makeFetchSpy({ status = 200, body = { id: "snapshot-id" } } = {}) {
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

describe("gcArchitectureModelZodShape", () => {
  const schema = z.object(gcArchitectureModelZodShape);

  it("preserves every create_snapshot field through Zod parse", () => {
    const parsed = schema.parse({
      action: "create_snapshot",
      project: "ground-control",
      model_version: "architecture-model/v1",
      commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
      source: "MANUAL",
      elements: [
        {
          stable_key: "component:api",
          element_kind: "COMPONENT",
          label: "API",
          provenance_source: "DECLARATION",
          provenance_key: "manual:api",
          commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
        },
      ],
    });
    for (const field of GC_ARCHITECTURE_MODEL_CREATE_SNAPSHOT_FIELDS) {
      assert.ok(field in parsed, `Zod stripped '${field}'`);
    }
  });

  it("matches the handler action verbs", () => {
    assert.deepEqual(
      [...GC_ARCHITECTURE_MODEL_ACTIONS].sort(),
      ["create_snapshot", "diff_snapshots", "get_element", "get_snapshot", "list_elements", "list_snapshots"],
    );
  });
});

describe("gcArchitectureModelToolHandler", () => {
  it("sends create_snapshot body as camelCase to /api/v1/architecture-models/snapshots", async () => {
    const calls = makeFetchSpy();
    await gcArchitectureModelToolHandler({
      action: "create_snapshot",
      project: "ground-control",
      model_version: "architecture-model/v1",
      commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
      source: "MANUAL",
      elements: [
        {
          stable_key: "component:api",
          element_kind: "COMPONENT",
          label: "API",
          provenance_source: "DECLARATION",
          provenance_key: "manual:api",
          commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
        },
      ],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/architecture-models\/snapshots\b/);
    assert.match(calls[0].url, /project=ground-control/);
    assert.deepEqual(calls[0].body, {
      modelVersion: "architecture-model/v1",
      commitSha: "25c991231cf2a1464792846b083d1bd885299b3c",
      source: "MANUAL",
      elements: [
        {
          stableKey: "component:api",
          elementKind: "COMPONENT",
          label: "API",
          provenanceSource: "DECLARATION",
          provenanceKey: "manual:api",
          commitSha: "25c991231cf2a1464792846b083d1bd885299b3c",
        },
      ],
    });
  });

  it("routes diff_snapshots as a bounded GET with query params", async () => {
    const calls = makeFetchSpy({ body: { entries: [] } });
    await gcArchitectureModelToolHandler({
      action: "diff_snapshots",
      project: "ground-control",
      from_snapshot_id: "11111111-1111-1111-1111-111111111111",
      to_snapshot_id: "22222222-2222-2222-2222-222222222222",
    });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/architecture-models\/diff\b/);
    assert.match(calls[0].url, /fromSnapshotId=11111111-1111-1111-1111-111111111111/);
    assert.match(calls[0].url, /toSnapshotId=22222222-2222-2222-2222-222222222222/);
  });
});
