// Adapter-level tests for the gc_observation MCP handler. Exercises Zod-parsed
// args → handler dispatch → backend HTTP call (mocked fetch) → wire body
// shape. Guards the Defect-1 regression: observation field names must match the
// backend ObservationRequest / UpdateObservationRequest DTOs — observationKey,
// observationValue, source, observedAt, expiresAt, confidence, evidenceRef —
// and must NOT forward the old field names: title, statement, valid_until, metadata.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_OBSERVATION_ACTIONS,
  GC_OBSERVATION_CREATE_REQUIRED_FIELDS,
  GC_OBSERVATION_UPDATE_FIELDS,
  gcObservationZodShape,
  gcObservationToolHandler,
} from "./gc-observation.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 201, body = { id: "obs-uuid" } } = {}) {
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

describe("gcObservationZodShape", () => {
  it("rejects an unknown action", () => {
    const schema = z.object(gcObservationZodShape);
    const result = schema.safeParse({ action: "patch" });
    assert.equal(result.success, false);
  });

  it("only allows create / update / delete / latest actions", () => {
    assert.deepEqual([...GC_OBSERVATION_ACTIONS].sort(), ["create", "delete", "latest", "update"]);
  });

  it("accepts all correct fields (no title/statement/valid_until/metadata)", () => {
    const schema = z.object(gcObservationZodShape);
    const result = schema.safeParse({
      action: "create",
      project: "test-proj",
      asset_id: "11111111-1111-1111-1111-111111111111",
      category: "CONFIGURATION",
      observation_key: "cis.1.1",
      observation_value: "passing",
      source: "scanner-v1",
      observed_at: "2026-05-01T00:00:00Z",
      expires_at: "2026-11-01T00:00:00Z",
      confidence: "HIGH",
      evidence_ref: "evd-001",
    });
    assert.equal(result.success, true);
  });

  it("does NOT have title/statement/valid_until/metadata as accepted Zod fields", () => {
    // Zod 'strip' (default) silently drops unknown keys, so this test
    // confirms the shape doesn't declare them by checking the parsed output.
    const schema = z.object(gcObservationZodShape);
    const result = schema.safeParse({
      action: "create",
      title: "old-field",
      statement: "old-field",
      valid_until: "old-field",
      metadata: { key: "val" },
    });
    // Strip mode means safeParse succeeds but the old fields are absent.
    assert.equal(result.success, true);
    assert.equal(result.data.title, undefined);
    assert.equal(result.data.statement, undefined);
    assert.equal(result.data.valid_until, undefined);
    assert.equal(result.data.metadata, undefined);
  });
});

// ── create action ─────────────────────────────────────────────────────────────

describe("gcObservationToolHandler — create", () => {
  it("POSTs /assets/{assetId}/observations with correct camelCase body (Defect-1 regression guard)", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "obs-1" } });
    const assetId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    await gcObservationToolHandler({
      action: "create",
      project: "test-proj",
      asset_id: assetId,
      category: "CONFIGURATION",
      observation_key: "cis.1.1",
      observation_value: "passing",
      source: "scanner-v1",
      observed_at: "2026-05-01T00:00:00Z",
      expires_at: "2026-11-01T00:00:00Z",
      confidence: "HIGH",
      evidence_ref: "evd-001",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/observations`));
    assert.match(calls[0].url, /project=test-proj/);
    // Defect-1 assertion: camelCase field names must reach the wire
    assert.equal(calls[0].body.observationKey, "cis.1.1");
    assert.equal(calls[0].body.observationValue, "passing");
    assert.equal(calls[0].body.source, "scanner-v1");
    assert.equal(calls[0].body.observedAt, "2026-05-01T00:00:00Z");
    assert.equal(calls[0].body.expiresAt, "2026-11-01T00:00:00Z");
    assert.equal(calls[0].body.confidence, "HIGH");
    assert.equal(calls[0].body.evidenceRef, "evd-001");
    assert.equal(calls[0].body.category, "CONFIGURATION");
    // Old field names must NOT be present on the wire
    assert.equal(calls[0].body.title, undefined);
    assert.equal(calls[0].body.statement, undefined);
    assert.equal(calls[0].body.valid_until, undefined);
    assert.equal(calls[0].body.metadata, undefined);
  });

  it("create rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "create",
        category: "CONFIGURATION",
        observation_key: "k",
        observation_value: "v",
        source: "s",
        observed_at: "2026-05-01T00:00:00Z",
      }),
      /asset_id/,
    );
  });

  it("create rejects when category is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "create",
        asset_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        observation_key: "k",
        observation_value: "v",
        source: "s",
        observed_at: "2026-05-01T00:00:00Z",
      }),
      /category/,
    );
  });

  it("create rejects when observation_key is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "create",
        asset_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        category: "CONFIGURATION",
        observation_value: "v",
        source: "s",
        observed_at: "2026-05-01T00:00:00Z",
      }),
      /observation_key/,
    );
  });

  it("create rejects when observation_value is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "create",
        asset_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        category: "CONFIGURATION",
        observation_key: "k",
        source: "s",
        observed_at: "2026-05-01T00:00:00Z",
      }),
      /observation_value/,
    );
  });

  it("create rejects when source is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "create",
        asset_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        category: "CONFIGURATION",
        observation_key: "k",
        observation_value: "v",
        observed_at: "2026-05-01T00:00:00Z",
      }),
      /source/,
    );
  });

  it("create rejects when observed_at is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "create",
        asset_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        category: "CONFIGURATION",
        observation_key: "k",
        observation_value: "v",
        source: "s",
      }),
      /observed_at/,
    );
  });

  it("create does NOT forward title/statement/valid_until/metadata fields", async () => {
    const calls = makeFetchSpy({ status: 201 });
    await gcObservationToolHandler({
      action: "create",
      asset_id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      category: "CONFIGURATION",
      observation_key: "k",
      observation_value: "v",
      source: "s",
      observed_at: "2026-05-01T00:00:00Z",
      // old field names that must be silently ignored
      title: "must-be-dropped",
      statement: "must-be-dropped",
      valid_until: "must-be-dropped",
      metadata: { bad: "field" },
    });
    assert.equal(calls[0].body.title, undefined);
    assert.equal(calls[0].body.statement, undefined);
    assert.equal(calls[0].body.valid_until, undefined);
    assert.equal(calls[0].body.metadata, undefined);
  });

  it("required-fields list exactly matches the create contract", () => {
    assert.deepEqual(GC_OBSERVATION_CREATE_REQUIRED_FIELDS, [
      "asset_id", "category", "observation_key", "observation_value", "source", "observed_at",
    ]);
  });
});

// ── update action ─────────────────────────────────────────────────────────────

describe("gcObservationToolHandler — update", () => {
  it("PUTs /assets/{assetId}/observations/{id} with update-only allowlist", async () => {
    const calls = makeFetchSpy({ status: 200, body: { id: "obs-1" } });
    const assetId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    const obsId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    await gcObservationToolHandler({
      action: "update",
      asset_id: assetId,
      id: obsId,
      project: "proj",
      observation_value: "updated-value",
      expires_at: "2027-01-01T00:00:00Z",
      confidence: "MEDIUM",
      evidence_ref: "evd-002",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/observations/${obsId}`));
    assert.equal(calls[0].body.observationValue, "updated-value");
    assert.equal(calls[0].body.expiresAt, "2027-01-01T00:00:00Z");
    assert.equal(calls[0].body.confidence, "MEDIUM");
    assert.equal(calls[0].body.evidenceRef, "evd-002");
    // create-only fields must NOT appear in update body
    assert.equal(calls[0].body.observationKey, undefined);
    assert.equal(calls[0].body.source, undefined);
    assert.equal(calls[0].body.observedAt, undefined);
    assert.equal(calls[0].body.category, undefined);
  });

  it("update rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({ action: "update", id: "cccccccc-cccc-cccc-cccc-cccccccccccc" }),
      /asset_id/,
    );
  });

  it("update rejects when id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({
        action: "update",
        asset_id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      }),
      /id/,
    );
  });

  it("update-fields list is separate from create-fields (Defect-1 separation guard)", () => {
    // update fields must not contain create-only fields
    for (const f of ["observation_key", "source", "observed_at", "category"]) {
      assert.equal(GC_OBSERVATION_UPDATE_FIELDS.includes(f), false,
        `update allowlist must not contain create-only field: ${f}`);
    }
    // update fields must contain the update-allowed fields
    for (const f of ["observation_value", "expires_at", "confidence", "evidence_ref"]) {
      assert.equal(GC_OBSERVATION_UPDATE_FIELDS.includes(f), true,
        `update allowlist must contain: ${f}`);
    }
  });
});

// ── delete action ─────────────────────────────────────────────────────────────

describe("gcObservationToolHandler — delete", () => {
  it("DELETEs /assets/{assetId}/observations/{id} and returns null", async () => {
    // Use status 200 with empty body in the spy — lib.js interprets the real
    // backend 204 as null internally; the test just verifies method + URL + null
    // return value from the handler.
    const calls = makeFetchSpy({ status: 200, body: {} });
    const assetId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    const obsId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    const result = await gcObservationToolHandler({
      action: "delete",
      asset_id: assetId,
      id: obsId,
      project: "proj",
    });
    // handler returns null after awaiting delete (regardless of spy body)
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/observations/${obsId}`));
  });

  it("delete rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({ action: "delete", id: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee" }),
      /asset_id/,
    );
  });

  it("delete rejects when id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({ action: "delete", asset_id: "dddddddd-dddd-dddd-dddd-dddddddddddd" }),
      /id/,
    );
  });
});

// ── latest action ─────────────────────────────────────────────────────────────

describe("gcObservationToolHandler — latest", () => {
  it("GETs /assets/{assetId}/observations/latest and returns data", async () => {
    const calls = makeFetchSpy({ status: 200, body: [{ id: "obs-latest" }] });
    const assetId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    const result = await gcObservationToolHandler({
      action: "latest",
      asset_id: assetId,
      project: "proj",
    });
    assert.deepEqual(result, [{ id: "obs-latest" }]);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/observations/latest`));
  });

  it("latest rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcObservationToolHandler({ action: "latest" }),
      /asset_id/,
    );
  });
});
