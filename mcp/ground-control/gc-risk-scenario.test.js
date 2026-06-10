// Adapter-level tests for the gc_risk_scenario MCP handler. Exercise the full
// path Zod-parsed args → handler dispatch → backend HTTP call (mocked fetch)
// → wire body shape. Mirrors gc-threat-model.test.js (issue #875) so the same
// regression scaffolding catches the same defect class: the original bug in
// issue #876 would resurface here if the Zod shape stopped exposing the
// backend body fields, the body allowlists drifted to include `description`
// / `status` / `metadata`, or a required-field check was skipped.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_RISK_SCENARIO_ACTIONS,
  GC_RISK_SCENARIO_CREATE_BODY_FIELDS,
  GC_RISK_SCENARIO_UPDATE_BODY_FIELDS,
  GC_RISK_SCENARIO_CREATE_REQUIRED_FIELDS,
  gcRiskScenarioZodShape,
  gcRiskScenarioToolHandler,
} from "./gc-risk-scenario.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 200, body = { id: "rs-uuid" } } = {}) {
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

describe("gcRiskScenarioZodShape (issue #876)", () => {
  const schema = z.object(gcRiskScenarioZodShape);

  it("preserves every backend create body field through Zod parse", () => {
    const parsed = schema.parse({
      action: "create",
      uid: "RS-1",
      title: "T",
      threat: "src",
      method: "evt",
      asset: "aff",
      effect: "loss",
      time_horizon: "12 months",
    });
    for (const k of GC_RISK_SCENARIO_CREATE_BODY_FIELDS) {
      assert.ok(k in parsed, `Zod stripped '${k}' — the create body would be missing it on the wire`);
    }
  });

  it("strips 'description' (backend has no such field on risk-scenario DTOs)", () => {
    const parsed = schema.parse({
      action: "create",
      uid: "RS-1",
      title: "T",
      threat: "src",
      method: "evt",
      asset: "aff",
      effect: "loss",
      time_horizon: "12 months",
      description: "should-be-stripped",
    });
    assert.ok(!("description" in parsed));
  });

  it("strips 'metadata' and 'methodology_profile_id' (not on Create/Update DTOs)", () => {
    const parsed = schema.parse({
      action: "create",
      uid: "RS-1",
      title: "T",
      metadata: { foo: "bar" },
      methodology_profile_id: "11111111-1111-1111-1111-111111111111",
    });
    assert.ok(!("metadata" in parsed));
    assert.ok(!("methodology_profile_id" in parsed));
  });

  it("rejects an unknown action enum value", () => {
    assert.throws(() => schema.parse({ action: "merge" }));
  });

  it("strips deprecated 'vulnerability' field (no longer on RiskScenarioRequest)", () => {
    const parsed = schema.parse({
      action: "create",
      uid: "RS-1",
      title: "T",
      threat: "src",
      vulnerability: "should-not-appear",
    });
    assert.ok(!("vulnerability" in parsed));
  });
});

describe("GC_RISK_SCENARIO_CREATE_BODY_FIELDS (issue #876)", () => {
  it("contains every backend FAIR-CRST @NotBlank create field", () => {
    for (const required of [
      "uid", "title", "threat", "method", "asset", "effect", "time_horizon",
    ]) {
      assert.ok(
        GC_RISK_SCENARIO_CREATE_BODY_FIELDS.includes(required),
        `${required} missing from create body allowlist`,
      );
    }
  });

  it("does NOT contain deprecated 'vulnerability' (dropped in FAIR-CRST rename)", () => {
    assert.ok(!GC_RISK_SCENARIO_CREATE_BODY_FIELDS.includes("vulnerability"));
  });

  it("does NOT contain 'description' (backend has no such field)", () => {
    assert.ok(!GC_RISK_SCENARIO_CREATE_BODY_FIELDS.includes("description"));
  });

  it("does NOT contain 'status' (transition is its own action)", () => {
    assert.ok(!GC_RISK_SCENARIO_CREATE_BODY_FIELDS.includes("status"));
  });

  it("does NOT contain 'metadata' or 'methodology_profile_id' (not on RiskScenarioRequest)", () => {
    assert.ok(!GC_RISK_SCENARIO_CREATE_BODY_FIELDS.includes("metadata"));
    assert.ok(!GC_RISK_SCENARIO_CREATE_BODY_FIELDS.includes("methodology_profile_id"));
  });
});

describe("GC_RISK_SCENARIO_UPDATE_BODY_FIELDS (issue #876)", () => {
  it("contains every backend FAIR-CRST Update DTO field", () => {
    for (const f of [
      "title", "threat", "method", "asset", "effect", "time_horizon",
    ]) {
      assert.ok(GC_RISK_SCENARIO_UPDATE_BODY_FIELDS.includes(f));
    }
  });

  it("does NOT contain create-only 'uid' (UpdateRiskScenarioRequest has no uid)", () => {
    assert.ok(!GC_RISK_SCENARIO_UPDATE_BODY_FIELDS.includes("uid"));
  });

  it("does NOT contain deprecated 'vulnerability'", () => {
    assert.ok(!GC_RISK_SCENARIO_UPDATE_BODY_FIELDS.includes("vulnerability"));
  });

  it("does NOT contain 'description', 'status', 'metadata', 'methodology_profile_id'", () => {
    for (const f of ["description", "status", "metadata", "methodology_profile_id"]) {
      assert.ok(!GC_RISK_SCENARIO_UPDATE_BODY_FIELDS.includes(f));
    }
  });
});

describe("GC_RISK_SCENARIO_ACTIONS", () => {
  it("matches the action verbs the handler dispatches", () => {
    assert.deepEqual(
      [...GC_RISK_SCENARIO_ACTIONS].sort(),
      ["create", "delete", "link_create", "link_delete", "requirements", "trace", "transition", "update"],
    );
  });
});

describe("gcRiskScenarioToolHandler create (issue #876)", () => {
  it("sends every required FAIR-CRST field to /api/v1/risk-scenarios as camelCase", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "create",
      uid: "RS-1",
      title: "Title",
      threat: "Source",
      method: "Event",
      asset: "AffObject",
      effect: "Loss",
      time_horizon: "12 months",
      project: "proj-a",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/risk-scenarios\b/);
    assert.match(calls[0].url, /project=proj-a/);
    assert.deepEqual(calls[0].body, {
      uid: "RS-1",
      title: "Title",
      threat: "Source",
      method: "Event",
      asset: "AffObject",
      effect: "Loss",
      timeHorizon: "12 months",
    });
  });

  it("does NOT forward 'description', 'status', 'metadata', or unknown fields", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "create",
      uid: "RS-1",
      title: "Title",
      threat: "Source",
      method: "Event",
      asset: "AffObject",
      effect: "Loss",
      time_horizon: "12 months",
      description: "ignored",
      status: "ACTIVE",
      metadata: { foo: "bar" },
      methodology_profile_id: "11111111-1111-1111-1111-111111111111",
    });
    assert.equal(calls.length, 1);
    const sent = Object.keys(calls[0].body).sort();
    assert.deepEqual(sent, [
      "asset", "effect", "method", "threat",
      "timeHorizon", "title", "uid",
    ]);
  });

  it("does NOT forward deprecated 'vulnerability' field", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "create",
      uid: "RS-1",
      title: "Title",
      threat: "Source",
      method: "Event",
      asset: "AffObject",
      effect: "Loss",
      time_horizon: "12 months",
      vulnerability: "should-be-dropped",
    });
    assert.equal(calls.length, 1);
    assert.ok(!("vulnerability" in calls[0].body));
  });

  for (const missing of GC_RISK_SCENARIO_CREATE_REQUIRED_FIELDS) {
    it(`rejects create before any HTTP call when '${missing}' is missing`, async () => {
      const calls = makeFetchSpy();
      const args = {
        action: "create",
        uid: "RS-1",
        title: "T",
        threat: "s",
        method: "e",
        asset: "a",
        effect: "c",
        time_horizon: "h",
      };
      delete args[missing];
      await assert.rejects(
        () => gcRiskScenarioToolHandler(args),
        (e) => e.message.includes(`'${missing}' is required`),
      );
      assert.equal(calls.length, 0);
    });
  }
});

describe("gcRiskScenarioToolHandler update (issue #876)", () => {
  const ID = "22222222-2222-2222-2222-222222222222";

  it("PUTs to /api/v1/risk-scenarios/<id> with the camelCase FAIR-CRST update body", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "update",
      id: ID,
      title: "New title",
      threat: "src2",
      method: "evt2",
      asset: "aff2",
      effect: "loss2",
      time_horizon: "6 months",
      project: "proj-a",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}\\b`));
    assert.deepEqual(calls[0].body, {
      title: "New title",
      threat: "src2",
      method: "evt2",
      asset: "aff2",
      effect: "loss2",
      timeHorizon: "6 months",
    });
  });

  it("never forwards create-only 'uid' on update (UpdateRiskScenarioRequest has no uid)", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "update",
      id: ID,
      uid: "RS-LEAK",
      title: "T",
    });
    assert.equal(calls.length, 1);
    assert.ok(!("uid" in calls[0].body));
    assert.equal(calls[0].body.title, "T");
  });

  it("rejects update with no id before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcRiskScenarioToolHandler({ action: "update" }),
      (e) => e.message.includes("'id' is required"),
    );
    assert.equal(calls.length, 0);
  });
});

describe("gcRiskScenarioToolHandler other actions", () => {
  const ID = "33333333-3333-3333-3333-333333333333";

  it("delete returns null and issues a DELETE", async () => {
    const calls = [];
    globalThis.fetch = async (url, opts) => {
      calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: null });
      return new Response(null, { status: 204 });
    };
    const result = await gcRiskScenarioToolHandler({ action: "delete", id: ID });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}\\b`));
  });

  it("transition PUTs to the status sub-resource with the status body", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({ action: "transition", id: ID, status: "ACTIVE" });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}/status`));
    assert.deepEqual(calls[0].body, { status: "ACTIVE" });
  });

  it("requirements GETs the requirements sub-resource", async () => {
    const calls = makeFetchSpy({ body: [] });
    await gcRiskScenarioToolHandler({ action: "requirements", id: ID });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}/requirements`));
  });

  it("link_create with target_entity_id POSTs internal-target link body without leaking entity fields", async () => {
    const TARGET_ID = "55555555-5555-5555-5555-555555555555";
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "link_create",
      scenario_id: ID,
      target_type: "REQUIREMENT",
      target_entity_id: TARGET_ID,
      link_type: "AFFECTS",
      target_title: "GC-X001",
      // these must not leak into the link body
      title: "should-not-leak",
      threat: "should-not-leak",
      asset: "should-not-leak",
      effect: "should-not-leak",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}/links`));
    assert.deepEqual(calls[0].body, {
      targetType: "REQUIREMENT",
      targetEntityId: TARGET_ID,
      linkType: "AFFECTS",
      targetTitle: "GC-X001",
    });
  });

  it("link_create with target_identifier POSTs external-target link body", async () => {
    const calls = makeFetchSpy();
    await gcRiskScenarioToolHandler({
      action: "link_create",
      scenario_id: ID,
      target_type: "EXTERNAL",
      target_identifier: "ref://external/finding/42",
      link_type: "EVIDENCED_BY",
      target_url: "https://example.test/finding/42",
      target_title: "External finding",
    });
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0].body, {
      targetType: "EXTERNAL",
      targetIdentifier: "ref://external/finding/42",
      linkType: "EVIDENCED_BY",
      targetUrl: "https://example.test/finding/42",
      targetTitle: "External finding",
    });
  });

  it("link_create rejects missing target_type before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcRiskScenarioToolHandler({
        action: "link_create",
        scenario_id: ID,
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
      () => gcRiskScenarioToolHandler({
        action: "link_create",
        scenario_id: ID,
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
    const result = await gcRiskScenarioToolHandler({
      action: "link_delete",
      scenario_id: ID,
      link_id: LINK_ID,
    });
    assert.equal(result, null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}/links/${LINK_ID}`));
  });

  it("trace GETs the trace sub-resource", async () => {
    const calls = makeFetchSpy({ body: {} });
    await gcRiskScenarioToolHandler({ action: "trace", id: ID });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-scenarios/${ID}/trace`));
  });

  it("trace rejects missing id before any HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcRiskScenarioToolHandler({ action: "trace" }),
      (e) => e.message.includes("'id' is required"),
    );
    assert.equal(calls.length, 0);
  });

  it("throws on an unknown action without issuing an HTTP call", async () => {
    const calls = makeFetchSpy();
    await assert.rejects(
      () => gcRiskScenarioToolHandler({ action: "merge" }),
      (e) => e.message.includes("Unknown action: merge"),
    );
    assert.equal(calls.length, 0);
  });
});
