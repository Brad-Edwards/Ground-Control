// Adapter-level tests for the gc_asset MCP handler. Exercises Zod-parsed args
// → handler dispatch → backend HTTP call (mocked fetch) → wire body shape.
// Guards Defect-2 (relation_create missing fields) and Defect-3 (relation_update
// missing action).

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_ASSET_ACTIONS,
  GC_RELATION_CREATE_FIELDS,
  GC_RELATION_UPDATE_FIELDS,
  gcAssetZodShape,
  gcAssetToolHandler,
} from "./gc-asset.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 201, body = { id: "asset-uuid" } } = {}) {
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

describe("gcAssetZodShape", () => {
  it("rejects an unknown action", () => {
    const schema = z.object(gcAssetZodShape);
    const result = schema.safeParse({ action: "purge" });
    assert.equal(result.success, false);
  });

  it("includes relation_update in the action enum (Defect-3)", () => {
    assert.equal(GC_ASSET_ACTIONS.includes("relation_update"), true);
  });

  it("includes new relation Zod fields: source_system, external_source_id, collected_at, confidence", () => {
    const schema = z.object(gcAssetZodShape);
    const result = schema.safeParse({
      action: "relation_create",
      source_id: "11111111-1111-1111-1111-111111111111",
      target_id: "22222222-2222-2222-2222-222222222222",
      relation_type: "DEPENDS_ON",
      source_system: "cmdb",
      external_source_id: "ext-123",
      collected_at: "2026-05-01T00:00:00Z",
      confidence: "HIGH",
    });
    assert.equal(result.success, true);
    assert.equal(result.data.source_system, "cmdb");
    assert.equal(result.data.external_source_id, "ext-123");
    assert.equal(result.data.collected_at, "2026-05-01T00:00:00Z");
    assert.equal(result.data.confidence, "HIGH");
  });
});

// ── create action ─────────────────────────────────────────────────────────────

describe("gcAssetToolHandler — create", () => {
  it("POSTs /assets with correct body and project param", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "a1" } });
    await gcAssetToolHandler({
      action: "create",
      project: "test-proj",
      uid: "ASSET-001",
      name: "Web Server",
      asset_type: "APPLICATION",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/assets(\?|$)/);
    assert.match(calls[0].url, /project=test-proj/);
    assert.equal(calls[0].body.uid, "ASSET-001");
    assert.equal(calls[0].body.name, "Web Server");
    assert.equal(calls[0].body.assetType, "APPLICATION");
  });

  it("create rejects when uid is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "create", name: "N", asset_type: "APPLICATION" }),
      /uid/,
    );
  });

  it("create rejects when name is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "create", uid: "U", asset_type: "APPLICATION" }),
      /name/,
    );
  });

  it("create rejects when asset_type is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "create", uid: "U", name: "N" }),
      /asset_type/,
    );
  });
});

// ── update action ─────────────────────────────────────────────────────────────

describe("gcAssetToolHandler — update", () => {
  it("PUTs /assets/{id} with body", async () => {
    const calls = makeFetchSpy({ status: 200, body: { id: "a1" } });
    await gcAssetToolHandler({
      action: "update",
      id: "11111111-1111-1111-1111-111111111111",
      name: "Renamed",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, /\/api\/v1\/assets\/11111111-1111-1111-1111-111111111111/);
    assert.equal(calls[0].body.name, "Renamed");
  });

  it("update rejects when id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "update", name: "N" }),
      /id/,
    );
  });
});

// ── delete action ─────────────────────────────────────────────────────────────

describe("gcAssetToolHandler — delete", () => {
  it("DELETEs /assets/{id} and returns null", async () => {
    const calls = makeFetchSpy({ status: 200, body: {} });
    const result = await gcAssetToolHandler({
      action: "delete",
      id: "22222222-2222-2222-2222-222222222222",
    });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, /\/api\/v1\/assets\/22222222-2222-2222-2222-222222222222/);
  });

  it("delete rejects when id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(gcAssetToolHandler({ action: "delete" }), /id/);
  });
});

// ── archive action ────────────────────────────────────────────────────────────

describe("gcAssetToolHandler — archive", () => {
  it("POSTs /assets/{id}/archive", async () => {
    const calls = makeFetchSpy({ status: 200, body: { id: "a1", archived: true } });
    await gcAssetToolHandler({
      action: "archive",
      id: "33333333-3333-3333-3333-333333333333",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/assets\/33333333-3333-3333-3333-333333333333\/archive/);
  });

  it("archive rejects when id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(gcAssetToolHandler({ action: "archive" }), /id/);
  });
});

// ── relation_create action (Defect-2) ─────────────────────────────────────────

describe("gcAssetToolHandler — relation_create", () => {
  it("POSTs /assets/{sourceId}/relations with full body incl. description, source_system, etc. (Defect-2)", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "rel-1" } });
    const sourceId = "44444444-4444-4444-4444-444444444444";
    const targetId = "55555555-5555-5555-5555-555555555555";
    await gcAssetToolHandler({
      action: "relation_create",
      source_id: sourceId,
      target_id: targetId,
      relation_type: "DEPENDS_ON",
      description: "network hop",
      source_system: "cmdb",
      external_source_id: "ext-123",
      collected_at: "2026-05-01T00:00:00Z",
      confidence: "HIGH",
      knowledge_state: "CONFIRMED",
      project: "p",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${sourceId}/relations`));
    // Defect-2: all body fields must reach the wire
    assert.equal(calls[0].body.targetId, targetId);
    assert.equal(calls[0].body.relationType, "DEPENDS_ON");
    assert.equal(calls[0].body.description, "network hop");
    assert.equal(calls[0].body.sourceSystem, "cmdb");
    assert.equal(calls[0].body.externalSourceId, "ext-123");
    assert.equal(calls[0].body.collectedAt, "2026-05-01T00:00:00Z");
    assert.equal(calls[0].body.confidence, "HIGH");
    assert.equal(calls[0].body.knowledgeState, "CONFIRMED");
    // source_id is the path arg, not a body field
    assert.equal(calls[0].body.sourceId, undefined);
  });

  it("relation_create rejects when source_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({
        action: "relation_create",
        target_id: "55555555-5555-5555-5555-555555555555",
        relation_type: "DEPENDS_ON",
      }),
      /source_id/,
    );
  });

  it("relation_create rejects when target_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({
        action: "relation_create",
        source_id: "44444444-4444-4444-4444-444444444444",
        relation_type: "DEPENDS_ON",
      }),
      /target_id/,
    );
  });

  it("relation_create rejects when relation_type is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({
        action: "relation_create",
        source_id: "44444444-4444-4444-4444-444444444444",
        target_id: "55555555-5555-5555-5555-555555555555",
      }),
      /relation_type/,
    );
  });

  it("RELATION_CREATE_FIELDS contains all backend AssetRelationRequest body fields", () => {
    for (const f of ["target_id", "relation_type", "description", "source_system",
      "external_source_id", "collected_at", "confidence", "knowledge_state"]) {
      assert.equal(GC_RELATION_CREATE_FIELDS.includes(f), true,
        `GC_RELATION_CREATE_FIELDS must contain: ${f}`);
    }
    // source_id is a path arg, NOT a body field
    assert.equal(GC_RELATION_CREATE_FIELDS.includes("source_id"), false,
      "source_id must NOT be in the relation create body fields");
  });
});

// ── relation_delete action ────────────────────────────────────────────────────

describe("gcAssetToolHandler — relation_delete", () => {
  it("DELETEs /assets/{assetId}/relations/{relationId} and returns null", async () => {
    const calls = makeFetchSpy({ status: 200, body: {} });
    const assetId = "66666666-6666-6666-6666-666666666666";
    const relId = "77777777-7777-7777-7777-777777777777";
    const result = await gcAssetToolHandler({
      action: "relation_delete",
      asset_id: assetId,
      relation_id: relId,
    });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/relations/${relId}`));
  });

  it("relation_delete rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "relation_delete", relation_id: "77777777-7777-7777-7777-777777777777" }),
      /asset_id/,
    );
  });

  it("relation_delete rejects when relation_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "relation_delete", asset_id: "66666666-6666-6666-6666-666666666666" }),
      /relation_id/,
    );
  });
});

// ── relation_update action (Defect-3) ─────────────────────────────────────────

describe("gcAssetToolHandler — relation_update", () => {
  it("PUTs /assets/{assetId}/relations/{relationId} with update allowlist (Defect-3)", async () => {
    const calls = makeFetchSpy({ status: 200, body: { id: "rel-1" } });
    const assetId = "88888888-8888-8888-8888-888888888888";
    const relId = "99999999-9999-9999-9999-999999999999";
    await gcAssetToolHandler({
      action: "relation_update",
      asset_id: assetId,
      relation_id: relId,
      description: "updated desc",
      source_system: "cmdb-v2",
      external_source_id: "ext-456",
      collected_at: "2026-06-01T00:00:00Z",
      confidence: "MEDIUM",
      knowledge_state: "INFERRED",
      project: "p",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/relations/${relId}`));
    assert.equal(calls[0].body.description, "updated desc");
    assert.equal(calls[0].body.sourceSystem, "cmdb-v2");
    assert.equal(calls[0].body.externalSourceId, "ext-456");
    assert.equal(calls[0].body.collectedAt, "2026-06-01T00:00:00Z");
    assert.equal(calls[0].body.confidence, "MEDIUM");
    assert.equal(calls[0].body.knowledgeState, "INFERRED");
  });

  it("relation_update rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "relation_update", relation_id: "99999999-9999-9999-9999-999999999999" }),
      /asset_id/,
    );
  });

  it("relation_update rejects when relation_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "relation_update", asset_id: "88888888-8888-8888-8888-888888888888" }),
      /relation_id/,
    );
  });

  it("RELATION_UPDATE_FIELDS contains update-allowed fields and not create-only fields", () => {
    for (const f of ["description", "source_system", "external_source_id",
      "collected_at", "confidence", "knowledge_state"]) {
      assert.equal(GC_RELATION_UPDATE_FIELDS.includes(f), true,
        `GC_RELATION_UPDATE_FIELDS must contain: ${f}`);
    }
    // target_id and relation_type are create-only, not updatable
    assert.equal(GC_RELATION_UPDATE_FIELDS.includes("target_id"), false);
    assert.equal(GC_RELATION_UPDATE_FIELDS.includes("relation_type"), false);
  });
});

// ── link_delete action ────────────────────────────────────────────────────────

describe("gcAssetToolHandler — link_delete", () => {
  it("DELETEs /assets/{assetId}/links/{linkId} and returns null", async () => {
    const calls = makeFetchSpy({ status: 200, body: {} });
    const assetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    const linkId = "ffffffff-0000-1111-2222-333333333333";
    const result = await gcAssetToolHandler({
      action: "link_delete",
      asset_id: assetId,
      link_id: linkId,
    });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/links/${linkId}`));
  });

  it("link_delete rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "link_delete", link_id: "ffffffff-0000-1111-2222-333333333333" }),
      /asset_id/,
    );
  });

  it("link_delete rejects when link_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "link_delete", asset_id: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" }),
      /link_id/,
    );
  });
});

// ── external_id_create action ─────────────────────────────────────────────────

describe("gcAssetToolHandler — external_id_create", () => {
  it("POSTs /assets/{assetId}/external-ids with namespace + external_id", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "eid-1" } });
    const assetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    await gcAssetToolHandler({
      action: "external_id_create",
      asset_id: assetId,
      namespace: "cmdb",
      external_id: "cmdb-001",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/external-ids`));
    assert.equal(calls[0].body.namespace, "cmdb");
    // external_id has no TO_CAMEL entry so it passes through as-is to the backend
    assert.equal(calls[0].body.external_id, "cmdb-001");
  });

  it("external_id_create rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "external_id_create", namespace: "ns", external_id: "id" }),
      /asset_id/,
    );
  });
});

// ── external_id_delete action ─────────────────────────────────────────────────

describe("gcAssetToolHandler — external_id_delete", () => {
  it("DELETEs /assets/{assetId}/external-ids/{extIdId} and returns null", async () => {
    const calls = makeFetchSpy({ status: 200, body: {} });
    const assetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    const extIdId = "44444444-5555-6666-7777-888888888888";
    const result = await gcAssetToolHandler({
      action: "external_id_delete",
      asset_id: assetId,
      external_id_record_id: extIdId,
    });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/assets/${assetId}/external-ids/${extIdId}`));
  });

  it("external_id_delete rejects when asset_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "external_id_delete", external_id_record_id: "44444444-5555-6666-7777-888888888888" }),
      /asset_id/,
    );
  });

  it("external_id_delete rejects when external_id_record_id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcAssetToolHandler({ action: "external_id_delete", asset_id: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" }),
      /external_id_record_id/,
    );
  });
});
