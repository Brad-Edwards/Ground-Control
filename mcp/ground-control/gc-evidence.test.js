// Adapter-level tests for the gc_evidence MCP handler. Exercise Zod-parsed
// args → handler dispatch → backend HTTP call (mocked fetch) → wire body
// shape. Lock in the snake_case → camelCase remapping at the adapter boundary
// (GC-M016 / ADR-045) and the append-only contract: create + supersede only,
// no update / no delete action.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_EVIDENCE_ACTIONS,
  GC_EVIDENCE_CREATE_REQUIRED_FIELDS,
  gcEvidenceZodShape,
  gcEvidenceToolHandler,
} from "./gc-evidence.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

function makeFetchSpy({ status = 201, body = { id: "evd-uuid", uid: "EVD-0001" } } = {}) {
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

describe("gcEvidenceZodShape", () => {
  it("rejects an unknown action (append-only contract: no update / delete)", () => {
    const schema = z.object(gcEvidenceZodShape);
    const result = schema.safeParse({ action: "update" });
    assert.equal(result.success, false);
  });

  it("only allows create / supersede actions", () => {
    assert.deepEqual([...GC_EVIDENCE_ACTIONS].sort(), ["create", "supersede"]);
  });
});

describe("gcEvidenceToolHandler", () => {
  it("create POSTs /evidence-artifacts with camelCase body and project param", async () => {
    const calls = makeFetchSpy();
    await gcEvidenceToolHandler({
      action: "create",
      project: "ground-control",
      uid: "EVD-0001",
      title: "Q2 assurance summary",
      summary: "Control X operated effectively across Q2.",
      evidence_type: "ASSURANCE_CONCLUSION",
      derivation_method: "manual-rollup-v1",
      derived_at: "2026-05-01T12:00:00Z",
      sources: [
        {
          sourceKind: "OBSERVATION",
          sourceEntityId: "11111111-1111-1111-1111-111111111111",
        },
      ],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/evidence-artifacts(\?|$)/);
    assert.match(calls[0].url, /project=ground-control/);
    assert.deepEqual(calls[0].body, {
      uid: "EVD-0001",
      title: "Q2 assurance summary",
      summary: "Control X operated effectively across Q2.",
      evidenceType: "ASSURANCE_CONCLUSION",
      derivationMethod: "manual-rollup-v1",
      derivedAt: "2026-05-01T12:00:00Z",
      sources: [
        {
          sourceKind: "OBSERVATION",
          sourceEntityId: "11111111-1111-1111-1111-111111111111",
        },
      ],
    });
  });

  it("create rejects when a required body field is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcEvidenceToolHandler({
        action: "create",
        project: "ground-control",
        uid: "EVD-0001",
        // title intentionally omitted
        summary: "x",
        evidence_type: "ATTESTATION",
        derivation_method: "m",
        derived_at: "2026-05-01T12:00:00Z",
        sources: [{ sourceKind: "ATTESTATION", sourceIdentifier: "ext-1" }],
      }),
      /title/,
    );
  });

  it("supersede POSTs /evidence-artifacts/{id}/supersede with camelCase body", async () => {
    const calls = makeFetchSpy();
    await gcEvidenceToolHandler({
      action: "supersede",
      id: "22222222-2222-2222-2222-222222222222",
      project: "ground-control",
      uid: "EVD-0002",
      title: "revised",
      summary: "y",
      evidence_type: "ASSURANCE_CONCLUSION",
      derivation_method: "manual-rollup-v2",
      derived_at: "2026-05-15T17:00:00Z",
      sources: [
        {
          sourceKind: "ATTESTATION",
          sourceIdentifier: "vendor-soc2-2026",
        },
      ],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(
      calls[0].url,
      /\/api\/v1\/evidence-artifacts\/22222222-2222-2222-2222-222222222222\/supersede(\?|$)/,
    );
    // Full body deepEqual mirrors the create test. Spot-check assertions on
    // two fields previously left uid/title/derivedAt/sources unverified, so a
    // regression in toCreateBody that dropped any of those fields would pass
    // here while breaking the backend wire contract.
    assert.deepEqual(calls[0].body, {
      uid: "EVD-0002",
      title: "revised",
      summary: "y",
      evidenceType: "ASSURANCE_CONCLUSION",
      derivationMethod: "manual-rollup-v2",
      derivedAt: "2026-05-15T17:00:00Z",
      sources: [
        {
          sourceKind: "ATTESTATION",
          sourceIdentifier: "vendor-soc2-2026",
        },
      ],
    });
  });

  it("supersede rejects when id is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcEvidenceToolHandler({
        action: "supersede",
        // id omitted
        project: "ground-control",
        uid: "EVD-0002",
        title: "x",
        summary: "y",
        evidence_type: "ATTESTATION",
        derivation_method: "m",
        derived_at: "2026-05-15T17:00:00Z",
        sources: [{ sourceKind: "ATTESTATION", sourceIdentifier: "ext-1" }],
      }),
      /id/,
    );
  });

  it("required-fields lists exactly the create contract", () => {
    assert.deepEqual(GC_EVIDENCE_CREATE_REQUIRED_FIELDS, [
      "uid",
      "title",
      "summary",
      "evidence_type",
      "derivation_method",
      "derived_at",
      "sources",
    ]);
  });
});
