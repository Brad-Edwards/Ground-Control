// Adapter-level tests for the gc_audit MCP handler. Exercise the full path
// Zod-parsed args → handler dispatch → backend HTTP call (mocked fetch) →
// wire body shape. Locks in the snake_case → camelCase mapping for audit
// fields (GC-U001) so the same field-drop bug that affected gc_finding
// (issue #875 / #279) cannot regress for the audit tool.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_AUDIT_ACTIONS,
  GC_AUDIT_CREATE_BODY_FIELDS,
  GC_AUDIT_UPDATE_BODY_FIELDS,
  GC_AUDIT_CREATE_REQUIRED_FIELDS,
  gcAuditZodShape,
  gcAuditToolHandler,
} from "./gc-audit.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 200, body = { id: "a-uuid" } } = {}) {
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

describe("gcAuditZodShape", () => {
  const schema = z.object(gcAuditZodShape);

  it("preserves every backend create body field through Zod parse", () => {
    // Supply every backend-recognized field. The assertion is that none get
    // silently stripped — if the Zod shape lacks a key, parse drops the
    // caller's value and the wire body is missing it (issue #875 for the
    // threat-model variant). Optional fields absent from the input are
    // dropped by Zod by design, so this test must include every field it
    // expects to round-trip.
    const parsed = schema.parse({
      action: "create",
      uid: "AUDIT-1",
      title: "Annual compliance",
      audit_type: "INTERNAL",
      scope_description: "All prod systems.",
      objectives: ["Assess controls"],
      phases: [{ kind: "PLANNING" }],
      team_members: ["alice"],
    });
    for (const k of GC_AUDIT_CREATE_BODY_FIELDS) {
      assert.ok(k in parsed, `Zod stripped '${k}' — the create body would be missing it on the wire`);
    }
  });

  it("preserves the update-only clear_* flags through Zod parse", () => {
    const parsed = schema.parse({
      action: "update",
      id: "11111111-1111-1111-1111-111111111111",
      clear_objectives: true,
      clear_phases: false,
      clear_team_members: true,
    });
    assert.equal(parsed.clear_objectives, true);
    assert.equal(parsed.clear_phases, false);
    assert.equal(parsed.clear_team_members, true);
  });

  it("rejects an unknown action enum value", () => {
    assert.throws(() => schema.parse({ action: "merge" }));
  });

  it("rejects an unknown audit_type enum value", () => {
    assert.throws(() => schema.parse({ action: "create", audit_type: "FAKE" }));
  });

  it("rejects an unknown status enum value", () => {
    assert.throws(() => schema.parse({ action: "transition", status: "NOT_A_STATUS" }));
  });
});

describe("GC_AUDIT_CREATE_BODY_FIELDS", () => {
  it("contains every backend @NotBlank / @NotNull create field", () => {
    for (const required of ["uid", "title", "audit_type", "scope_description"]) {
      assert.ok(GC_AUDIT_CREATE_BODY_FIELDS.includes(required));
    }
  });

  it("does NOT contain update-only clear_* flags", () => {
    assert.ok(!GC_AUDIT_CREATE_BODY_FIELDS.includes("clear_objectives"));
    assert.ok(!GC_AUDIT_CREATE_BODY_FIELDS.includes("clear_phases"));
    assert.ok(!GC_AUDIT_CREATE_BODY_FIELDS.includes("clear_team_members"));
  });
});

describe("GC_AUDIT_UPDATE_BODY_FIELDS", () => {
  it("contains every backend UpdateAuditRequest field", () => {
    for (const f of [
      "title", "audit_type", "scope_description",
      "objectives", "phases", "team_members",
      "clear_objectives", "clear_phases", "clear_team_members",
    ]) {
      assert.ok(GC_AUDIT_UPDATE_BODY_FIELDS.includes(f));
    }
  });

  it("does NOT contain create-only 'uid'", () => {
    assert.ok(!GC_AUDIT_UPDATE_BODY_FIELDS.includes("uid"));
  });
});

describe("GC_AUDIT_ACTIONS", () => {
  it("matches the action verbs the handler dispatches", () => {
    assert.deepEqual(
      [...GC_AUDIT_ACTIONS].sort(),
      ["create", "delete", "link_create", "link_delete", "transition", "update"],
    );
  });
});

describe("gcAuditToolHandler create", () => {
  it("sends every required field to /api/v1/audits as camelCase", async () => {
    const calls = makeFetchSpy();
    await gcAuditToolHandler({
      action: "create",
      uid: "AUDIT-1",
      title: "Annual compliance",
      audit_type: "INTERNAL",
      scope_description: "All prod systems.",
      objectives: ["Assess controls", "Review policies"],
      team_members: ["alice", "bob"],
      project: "proj-a",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/audits\b/);
    assert.match(calls[0].url, /project=proj-a/);
    assert.deepEqual(calls[0].body, {
      uid: "AUDIT-1",
      title: "Annual compliance",
      auditType: "INTERNAL",
      scopeDescription: "All prod systems.",
      objectives: ["Assess controls", "Review policies"],
      teamMembers: ["alice", "bob"],
    });
  });

  it("rejects when a required field is missing", async () => {
    makeFetchSpy();
    for (const required of GC_AUDIT_CREATE_REQUIRED_FIELDS) {
      await assert.rejects(
        () =>
          gcAuditToolHandler({
            action: "create",
            uid: required === "uid" ? undefined : "AUDIT-X",
            title: required === "title" ? undefined : "T",
            audit_type: required === "audit_type" ? undefined : "INTERNAL",
            scope_description: required === "scope_description" ? undefined : "x",
          }),
        new RegExp(required, "i"),
      );
    }
  });
});

describe("gcAuditToolHandler update", () => {
  it("sends clear_* flags as camelCase to PUT /api/v1/audits/{id}", async () => {
    const calls = makeFetchSpy();
    await gcAuditToolHandler({
      action: "update",
      id: "11111111-1111-1111-1111-111111111111",
      title: "Updated",
      clear_objectives: true,
      clear_team_members: false,
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, /\/api\/v1\/audits\/11111111/);
    assert.deepEqual(calls[0].body, {
      title: "Updated",
      clearObjectives: true,
      clearTeamMembers: false,
    });
  });

  it("does NOT forward create-only fields", async () => {
    const calls = makeFetchSpy();
    await gcAuditToolHandler({
      action: "update",
      id: "11111111-1111-1111-1111-111111111111",
      uid: "should-be-stripped",
      title: "T",
    });
    assert.deepEqual(calls[0].body, { title: "T" });
  });
});

describe("gcAuditToolHandler transition", () => {
  it("sends camelCase status to PUT /api/v1/audits/{id}/status", async () => {
    const calls = makeFetchSpy();
    await gcAuditToolHandler({
      action: "transition",
      id: "22222222-2222-2222-2222-222222222222",
      status: "IN_PROGRESS",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, /\/api\/v1\/audits\/22222222.*\/status/);
    assert.deepEqual(calls[0].body, { status: "IN_PROGRESS" });
  });
});

describe("gcAuditToolHandler link_create", () => {
  it("sends internal-target link as camelCase to /api/v1/audits/{auditId}/links", async () => {
    const calls = makeFetchSpy({ body: { id: "link-uuid" } });
    await gcAuditToolHandler({
      action: "link_create",
      audit_id: "33333333-3333-3333-3333-333333333333",
      target_type: "CONTROL",
      target_entity_id: "44444444-4444-4444-4444-444444444444",
      link_type: "ASSESSES",
      target_title: "Access control policy",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/audits\/33333333.*\/links/);
    assert.deepEqual(calls[0].body, {
      targetType: "CONTROL",
      targetEntityId: "44444444-4444-4444-4444-444444444444",
      linkType: "ASSESSES",
      targetTitle: "Access control policy",
    });
  });

  it("sends external-target link to /api/v1/audits/{auditId}/links", async () => {
    const calls = makeFetchSpy({ body: { id: "link-uuid" } });
    await gcAuditToolHandler({
      action: "link_create",
      audit_id: "33333333-3333-3333-3333-333333333333",
      target_type: "FRAMEWORK",
      target_identifier: "ISO-27001",
      link_type: "SCOPES",
      target_url: "https://iso.org/27001",
      target_title: "ISO 27001:2022",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/audits\/33333333.*\/links/);
    assert.deepEqual(calls[0].body, {
      targetType: "FRAMEWORK",
      targetIdentifier: "ISO-27001",
      linkType: "SCOPES",
      targetUrl: "https://iso.org/27001",
      targetTitle: "ISO 27001:2022",
    });
  });
});

describe("gcAuditToolHandler link_delete", () => {
  it("calls DELETE /api/v1/audits/{auditId}/links/{linkId}", async () => {
    const calls = makeFetchSpy({ status: 200, body: {} });
    await gcAuditToolHandler({
      action: "link_delete",
      audit_id: "33333333-3333-3333-3333-333333333333",
      link_id: "55555555-5555-5555-5555-555555555555",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "DELETE");
    assert.match(
      calls[0].url,
      /\/api\/v1\/audits\/33333333.*\/links\/55555555/,
    );
  });
});
