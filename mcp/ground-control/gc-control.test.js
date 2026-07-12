// Adapter-level tests for the gc_control MCP handler. Drives the full path
// raw args → Zod parse → gcControlToolHandler → lib.js → mocked fetch so the
// CONTROL_FIELDS per-entity allowlist (control_test for GC-I012) is the gate
// being exercised — not a test-side pre-filter. Also locks in the entity
// discriminator's back-compat default ("control") so existing callers that
// omit `entity` keep hitting the original control surface.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_CONTROL_ACTIONS,
  GC_CONTROL_ENTITIES,
  gcControlZodShape,
  gcControlToolHandler,
  CONTROL_FIELDS,
} from "./gc-control.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

const SCHEMA = z.object(gcControlZodShape);

function makeFetchSpy({ status = 200, body = { id: "ent-uuid" } } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    const parsedBody = opts && opts.body ? JSON.parse(opts.body) : null;
    calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: parsedBody });
    // The Response constructor rejects a non-null body on a 204 (no-content)
    // status code per the Fetch spec, so emit a null body when the test asked
    // for a 204 and didn't override the body explicitly.
    const responseBody = status === 204 ? null : JSON.stringify(body);
    return new Response(responseBody, {
      status,
      headers: status === 204 ? {} : { "Content-Type": "application/json" },
    });
  };
  return calls;
}

async function callHandler(args) {
  const parsed = SCHEMA.parse(args);
  return gcControlToolHandler(parsed);
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

// ---------------------------------------------------------------------------
// Shape: CONTROL_FIELDS per entity, GC_CONTROL_ACTIONS, GC_CONTROL_ENTITIES
// ---------------------------------------------------------------------------

describe("CONTROL_FIELDS per-action shape", () => {
  it("indexes by entity then action for every entity", () => {
    for (const entity of GC_CONTROL_ENTITIES) {
      assert.ok(CONTROL_FIELDS[entity], `missing entity '${entity}'`);
      assert.ok(Array.isArray(CONTROL_FIELDS[entity].create), `${entity}.create not an array`);
      assert.ok(Array.isArray(CONTROL_FIELDS[entity].update), `${entity}.update not an array`);
    }
  });
});

describe("GC_CONTROL_ACTIONS", () => {
  it("exposes the canonical action verbs", () => {
    assert.deepEqual(
      [...GC_CONTROL_ACTIONS].sort(),
      ["create", "delete", "link_create", "link_delete", "transition", "update"],
    );
  });
});

describe("GC_CONTROL_ENTITIES", () => {
  it("contains exactly control + the GC-I012 entity", () => {
    assert.deepEqual(
      [...GC_CONTROL_ENTITIES].sort(),
      ["control", "control_test"],
    );
  });
});

// ---------------------------------------------------------------------------
// control entity (back-compat) — entity defaults to "control" on omission
// ---------------------------------------------------------------------------

describe("control entity (back-compat)", () => {
  it("defaults entity to 'control' when omitted", async () => {
    const calls = makeFetchSpy({ body: { id: "c-1", uid: "CTRL-001" } });
    await callHandler({
      action: "create",
      title: "Access Control",
      uid: "CTRL-001",
      control_function: "PREVENTIVE",
      project: "ground-control",
    });
    assert.equal(calls.length, 1);
    assert.match(calls[0].url, /\/api\/v1\/controls/);
    assert.equal(calls[0].method, "POST");
    assert.equal(calls[0].body.title, "Access Control");
  });
});

// ---------------------------------------------------------------------------
// control_test (GC-I012)
// ---------------------------------------------------------------------------

describe("control_test create (GC-I012)", () => {
  const list = () => CONTROL_FIELDS.control_test.create;

  it("contains every backend ControlTestRequest field", () => {
    for (const f of [
      "control_id", "uid", "methodology", "test_steps", "expected_results",
      "actual_results", "conclusion", "tester_identity", "test_date", "notes",
    ]) {
      assert.ok(list().includes(f), `${f} missing from control_test.create`);
    }
  });

  it("does NOT contain stale fields that the backend DTO does not accept", () => {
    for (const f of [
      "title", "description", "design_effectiveness", "operating_effectiveness",
      "assessed_at", "assessor", "status", "control_function", "rationale",
    ]) {
      assert.ok(!list().includes(f), `${f} should not be on control_test.create`);
    }
  });

  it("POSTs to /api/v1/control-tests with the per-entity allowlist", async () => {
    const calls = makeFetchSpy({ body: { id: "ct-1" } });
    await callHandler({
      entity: "control_test",
      action: "create",
      control_id: "00000000-0000-0000-0000-000000000500",
      uid: "CT-001",
      methodology: "INSPECTION",
      test_steps: "Inspect access logs.",
      expected_results: "None.",
      actual_results: "None.",
      conclusion: "EFFECTIVE",
      tester_identity: "auditor@example.com",
      test_date: "2026-05-01",
      project: "ground-control",
      // Stale fields that should be dropped by pick():
      title: "should be dropped",
      design_effectiveness: "EFFECTIVE",
    });
    assert.equal(calls.length, 1);
    assert.match(calls[0].url, /\/api\/v1\/control-tests/);
    assert.equal(calls[0].method, "POST");
    assert.equal(calls[0].body.uid, "CT-001");
    assert.equal(calls[0].body.methodology, "INSPECTION");
    assert.equal(calls[0].body.conclusion, "EFFECTIVE");
    // The MCP request() helper converts snake_case allowlist field names to
    // camelCase via lib.js TO_CAMEL before sending to the backend. The backend
    // DTOs are camelCase (Spring Jackson default), so if these mappings drop
    // the request silently fails validation. Lock in the converted field
    // names here so a regression in TO_CAMEL is caught at this boundary.
    assert.equal(calls[0].body.controlId, "00000000-0000-0000-0000-000000000500");
    assert.equal(calls[0].body.testSteps, "Inspect access logs.");
    assert.equal(calls[0].body.expectedResults, "None.");
    assert.equal(calls[0].body.actualResults, "None.");
    assert.equal(calls[0].body.testerIdentity, "auditor@example.com");
    assert.equal(calls[0].body.testDate, "2026-05-01");
    // pick() dropped the stale fields:
    assert.equal(calls[0].body.title, undefined);
    assert.equal(calls[0].body.designEffectiveness, undefined);
    assert.equal(calls[0].body.design_effectiveness, undefined);
    // Snake-case names must NOT leak through:
    assert.equal(calls[0].body.control_id, undefined);
    assert.equal(calls[0].body.test_steps, undefined);
    assert.equal(calls[0].body.tester_identity, undefined);
  });

  it("rejects missing control_id with a helpful error", async () => {
    await assert.rejects(
      () =>
          callHandler({
            entity: "control_test",
            action: "create",
            uid: "CT-001",
            methodology: "INSPECTION",
            test_steps: "x",
            expected_results: "x",
            actual_results: "x",
            conclusion: "EFFECTIVE",
            tester_identity: "auditor",
            test_date: "2026-05-01",
          }),
      /control_id.*create/i,
    );
  });

  it("rejects an unknown methodology at Zod parse", () => {
    assert.throws(
      () =>
          SCHEMA.parse({
            entity: "control_test",
            action: "create",
            control_id: "00000000-0000-0000-0000-000000000500",
            uid: "CT-001",
            methodology: "NOT_A_METHOD",
            test_steps: "x",
            expected_results: "x",
            actual_results: "x",
            conclusion: "EFFECTIVE",
            tester_identity: "auditor",
            test_date: "2026-05-01",
          }),
    );
  });
});

describe("control_test update (GC-I012)", () => {
  it("PUTs to /api/v1/control-tests/{id} and drops control_id/uid", async () => {
    const calls = makeFetchSpy({ body: { id: "ct-1" } });
    await callHandler({
      entity: "control_test",
      action: "update",
      id: "00000000-0000-0000-0000-000000000600",
      conclusion: "INEFFECTIVE",
      notes: "Re-tested",
      // control_id and uid are intentionally not in the update allowlist:
      control_id: "00000000-0000-0000-0000-000000000500",
      uid: "CT-001",
    });
    assert.equal(calls.length, 1);
    assert.match(calls[0].url, /\/api\/v1\/control-tests\//);
    assert.equal(calls[0].method, "PUT");
    assert.equal(calls[0].body.conclusion, "INEFFECTIVE");
    assert.equal(calls[0].body.notes, "Re-tested");
    assert.equal(calls[0].body.control_id, undefined);
    assert.equal(calls[0].body.uid, undefined);
  });
});

describe("control_test delete (GC-I012)", () => {
  it("DELETEs /api/v1/control-tests/{id}", async () => {
    const calls = makeFetchSpy({ status: 204, body: null });
    const result = await callHandler({
      entity: "control_test",
      action: "delete",
      id: "00000000-0000-0000-0000-000000000600",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.equal(result, null);
  });
});

describe("control_test rejects non-supported actions", () => {
  it("throws on transition (sub-entities have no lifecycle endpoint)", async () => {
    await assert.rejects(
      () =>
          callHandler({
            entity: "control_test",
            action: "transition",
            id: "00000000-0000-0000-0000-000000000600",
          }),
      /not valid for control_test/,
    );
  });

  it("throws on link_create", async () => {
    await assert.rejects(
      () =>
          callHandler({
            entity: "control_test",
            action: "link_create",
            id: "00000000-0000-0000-0000-000000000600",
            target_type: "ASSET",
            link_type: "ASSOCIATED",
          }),
      /not valid for control_test/,
    );
  });
});
