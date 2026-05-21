// Adapter-level tests for the gc_threat_model MCP handler. Exercise the full
// path Zod-parsed args → handler dispatch → backend HTTP call (mocked fetch)
// → wire body shape. Together with lib.test.js's toCamelCase shape tests
// they cover the regression in issue #875 end-to-end: the original bug would
// resurface here if the handler stopped using the body allowlists, leaked
// create-only fields into update bodies, or skipped a required-field check.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_THREAT_MODEL_ACTIONS,
  GC_THREAT_MODEL_CREATE_BODY_FIELDS,
  GC_THREAT_MODEL_UPDATE_BODY_FIELDS,
  GC_THREAT_MODEL_CREATE_REQUIRED_FIELDS,
  gcThreatModelZodShape,
  gcThreatModelToolHandler,
} from "./gc-threat-model.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 200, body = { id: "tm-uuid" } } = {}) {
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

describe("gcThreatModelZodShape (issue #875)", () => {
  const schema = z.object(gcThreatModelZodShape);

  it("preserves every backend create body field through Zod parse", () => {
    const parsed = schema.parse({
      action: "create",
      uid: "TM-1",
      title: "T",
      threat_source: "src",
      threat_event: "evt",
      effect: "eff",
      stride_category: "TAMPERING",
      narrative: "n",
    });
    for (const k of GC_THREAT_MODEL_CREATE_BODY_FIELDS) {
      assert.ok(k in parsed, `Zod stripped '${k}' — the create body would be missing it on the wire`);
    }
  });

  it("preserves the update-only clear_* flags through Zod parse", () => {
    const parsed = schema.parse({
      action: "update",
      id: "11111111-1111-1111-1111-111111111111",
      clear_stride: true,
      clear_narrative: false,
    });
    assert.equal(parsed.clear_stride, true);
    assert.equal(parsed.clear_narrative, false);
  });

  it("strips 'description' (backend has no such field on threat-model DTOs)", () => {
    const parsed = schema.parse({
      action: "create",
      uid: "TM-1",
      title: "T",
      threat_source: "src",
      threat_event: "evt",
      effect: "eff",
      description: "should-be-stripped",
    });
    assert.ok(!("description" in parsed));
  });

  it("rejects an unknown action enum value", () => {
    assert.throws(() => schema.parse({ action: "merge" }));
  });

  it("accepts FINDING as a target_type via the Zod enum (GC-H009)", () => {
    // Regression guard for the MCP/backend enum mirror. If FINDING is removed
    // from THREAT_MODEL_LINK_TARGET_TYPES in lib.js, this parse would throw
    // and real MCP link_create calls would fail before reaching the backend.
    const parsed = schema.parse({
      action: "link_create",
      threat_model_id: "11111111-1111-1111-1111-111111111111",
      target_type: "FINDING",
      target_entity_id: "22222222-2222-2222-2222-222222222222",
      link_type: "OBSERVED_IN",
    });
    assert.equal(parsed.target_type, "FINDING");
    assert.equal(parsed.link_type, "OBSERVED_IN");
  });
});

describe("GC_THREAT_MODEL_CREATE_BODY_FIELDS (issue #875)", () => {
  it("contains every backend @NotBlank create field", () => {
    for (const required of ["uid", "title", "threat_source", "threat_event", "effect"]) {
      assert.ok(GC_THREAT_MODEL_CREATE_BODY_FIELDS.includes(required));
    }
  });

  it("does NOT contain update-only clear_* flags", () => {
    assert.ok(!GC_THREAT_MODEL_CREATE_BODY_FIELDS.includes("clear_stride"));
    assert.ok(!GC_THREAT_MODEL_CREATE_BODY_FIELDS.includes("clear_narrative"));
  });

  it("does NOT contain 'description' (backend has no such field)", () => {
    assert.ok(!GC_THREAT_MODEL_CREATE_BODY_FIELDS.includes("description"));
  });
});

describe("GC_THREAT_MODEL_UPDATE_BODY_FIELDS (issue #875)", () => {
  it("contains every backend Update DTO field", () => {
    for (const f of ["title", "threat_source", "threat_event", "effect", "stride_category", "narrative", "clear_stride", "clear_narrative"]) {
      assert.ok(GC_THREAT_MODEL_UPDATE_BODY_FIELDS.includes(f));
    }
  });

  it("does NOT contain create-only 'uid' (UpdateThreatModelRequest has no uid)", () => {
    assert.ok(!GC_THREAT_MODEL_UPDATE_BODY_FIELDS.includes("uid"));
  });
});

describe("GC_THREAT_MODEL_ACTIONS", () => {
  it("matches the action verbs the handler dispatches", () => {
    assert.deepEqual(
      [...GC_THREAT_MODEL_ACTIONS].sort(),
      ["create", "delete", "link_create", "link_delete", "requirements", "trace", "transition", "update"],
    );
  });
});

describe("gcThreatModelToolHandler create (issue #875)", () => {
  it("sends every required field to /api/v1/threat-models as camelCase", async () => {
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "create",
      uid: "TM-1",
      title: "Title",
      threat_source: "Source",
      threat_event: "Event",
      effect: "Effect",
      stride_category: "TAMPERING",
      narrative: "Note",
      project: "proj-a",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/threat-models\b/);
    assert.match(calls[0].url, /project=proj-a/);
    assert.deepEqual(calls[0].body, {
      uid: "TM-1",
      title: "Title",
      threatSource: "Source",
      threatEvent: "Event",
      effect: "Effect",
      stride: "TAMPERING",
      narrative: "Note",
    });
  });

  it("does NOT forward update-only clear_* flags or unknown fields", async () => {
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "create",
      uid: "TM-1",
      title: "Title",
      threat_source: "Source",
      threat_event: "Event",
      effect: "Effect",
      clear_stride: true,
      clear_narrative: true,
      description: "ignored",
      metadata: { foo: "bar" },
    });
    assert.equal(calls.length, 1);
    const sent = Object.keys(calls[0].body).sort();
    assert.deepEqual(sent, ["effect", "threatEvent", "threatSource", "title", "uid"]);
  });

  for (const missing of GC_THREAT_MODEL_CREATE_REQUIRED_FIELDS) {
    it(`rejects create before any HTTP call when '${missing}' is missing`, async () => {
      const calls = makeFetchSpy();
      const args = {
        action: "create",
        uid: "TM-1",
        title: "T",
        threat_source: "s",
        threat_event: "e",
        effect: "f",
      };
      delete args[missing];
      await assert.rejects(
        () => gcThreatModelToolHandler(args),
        (e) => e.message.includes(`'${missing}' is required`),
      );
      assert.equal(calls.length, 0);
    });
  }
});

describe("gcThreatModelToolHandler update (issue #875)", () => {
  const ID = "22222222-2222-2222-2222-222222222222";

  it("PUTs to /api/v1/threat-models/<id> with the camelCase update body", async () => {
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "update",
      id: ID,
      title: "New title",
      threat_source: "src2",
      threat_event: "evt2",
      effect: "eff2",
      stride_category: "SPOOFING",
      narrative: "n2",
      clear_stride: false,
      clear_narrative: true,
      project: "proj-a",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}\\b`));
    assert.deepEqual(calls[0].body, {
      title: "New title",
      threatSource: "src2",
      threatEvent: "evt2",
      effect: "eff2",
      stride: "SPOOFING",
      narrative: "n2",
      clearStride: false,
      clearNarrative: true,
    });
  });

  it("never forwards create-only 'uid' on update (UpdateThreatModelRequest has no uid)", async () => {
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "update",
      id: ID,
      uid: "TM-LEAK",
      title: "T",
    });
    assert.equal(calls.length, 1);
    assert.ok(!("uid" in calls[0].body));
    assert.equal(calls[0].body.title, "T");
  });

  it("rejects update with no id before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcThreatModelToolHandler({ action: "update" }),
      (e) => e.message.includes("'id' is required"),
    );
    assert.equal(calls.length, 0);
  });
});

describe("gcThreatModelToolHandler other actions", () => {
  const ID = "33333333-3333-3333-3333-333333333333";

  it("delete returns null and issues a DELETE", async () => {
    const calls = makeFetchSpy({ status: 204, body: null });
    globalThis.fetch = async (url, opts) => {
      calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: null });
      return new Response(null, { status: 204 });
    };
    const result = await gcThreatModelToolHandler({ action: "delete", id: ID });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}\\b`));
  });

  it("transition PUTs to the status sub-resource with the status body", async () => {
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({ action: "transition", id: ID, status: "ACTIVE" });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}/status`));
    assert.deepEqual(calls[0].body, { status: "ACTIVE" });
  });

  it("link_create with target_entity_id POSTs internal-target link body", async () => {
    const TARGET_ID = "55555555-5555-5555-5555-555555555555";
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "link_create",
      threat_model_id: ID,
      target_type: "REQUIREMENT",
      target_entity_id: TARGET_ID,
      link_type: "AFFECTS",
      target_title: "GC-X001",
      title: "should-not-leak",
      threat_source: "should-not-leak",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}/links`));
    assert.deepEqual(calls[0].body, {
      targetType: "REQUIREMENT",
      targetEntityId: TARGET_ID,
      linkType: "AFFECTS",
      targetTitle: "GC-X001",
    });
  });

  it("link_create accepts FINDING as an internal target type (GC-H009)", async () => {
    const FINDING_ID = "77777777-7777-7777-7777-777777777777";
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "link_create",
      threat_model_id: ID,
      target_type: "FINDING",
      target_entity_id: FINDING_ID,
      link_type: "OBSERVED_IN",
      target_title: "Vulnerability finding",
    });
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0].body, {
      targetType: "FINDING",
      targetEntityId: FINDING_ID,
      linkType: "OBSERVED_IN",
      targetTitle: "Vulnerability finding",
    });
  });

  it("link_create with target_identifier POSTs external-target link body", async () => {
    const calls = makeFetchSpy();
    await gcThreatModelToolHandler({
      action: "link_create",
      threat_model_id: ID,
      target_type: "EXTERNAL",
      target_identifier: "ref://external/finding/42",
      link_type: "DOCUMENTED_IN",
      target_url: "https://example.test/finding/42",
      target_title: "External finding",
    });
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0].body, {
      targetType: "EXTERNAL",
      targetIdentifier: "ref://external/finding/42",
      linkType: "DOCUMENTED_IN",
      targetUrl: "https://example.test/finding/42",
      targetTitle: "External finding",
    });
  });

  it("link_create rejects missing target_type before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcThreatModelToolHandler({
        action: "link_create",
        threat_model_id: ID,
        link_type: "AFFECTS",
        target_entity_id: "66666666-6666-6666-6666-666666666666",
      }),
      (e) => e.message.includes("'target_type' is required"),
    );
    assert.equal(calls.length, 0);
  });

  it("link_create rejects missing link_type before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcThreatModelToolHandler({
        action: "link_create",
        threat_model_id: ID,
        target_type: "REQUIREMENT",
        target_entity_id: "66666666-6666-6666-6666-666666666666",
      }),
      (e) => e.message.includes("'link_type' is required"),
    );
    assert.equal(calls.length, 0);
  });

  it("link_delete issues a DELETE on the link sub-resource", async () => {
    const LINK_ID = "44444444-4444-4444-4444-444444444444";
    const calls = [];
    globalThis.fetch = async (url, opts) => {
      calls.push({ url: url.toString(), method: opts?.method ?? "GET" });
      return new Response(null, { status: 204 });
    };
    const result = await gcThreatModelToolHandler({
      action: "link_delete",
      threat_model_id: ID,
      link_id: LINK_ID,
    });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}/links/${LINK_ID}`));
  });

  it("requirements GETs the requirements sub-resource", async () => {
    const calls = makeFetchSpy({ body: [] });
    await gcThreatModelToolHandler({ action: "requirements", id: ID });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}/requirements`));
  });

  it("requirements rejects missing id before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcThreatModelToolHandler({ action: "requirements" }),
      (e) => e.message.includes("'id' is required"),
    );
    assert.equal(calls.length, 0);
  });

  it("trace GETs the trace sub-resource", async () => {
    const calls = makeFetchSpy({ body: {} });
    await gcThreatModelToolHandler({ action: "trace", id: ID });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, new RegExp(`/api/v1/threat-models/${ID}/trace`));
  });

  it("trace rejects missing id before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcThreatModelToolHandler({ action: "trace" }),
      (e) => e.message.includes("'id' is required"),
    );
    assert.equal(calls.length, 0);
  });

  it("throws on an unknown action without issuing an HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcThreatModelToolHandler({ action: "merge" }),
      (e) => e.message.includes("Unknown action: merge"),
    );
    assert.equal(calls.length, 0);
  });
});
