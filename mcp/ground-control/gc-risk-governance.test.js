// Adapter-level tests for the gc_risk_governance MCP handler. Drives the
// FULL path raw args → Zod parse → gcRiskGovernanceToolHandler → lib.js
// dispatch → mocked fetch, so the handler's own pick(args, ...) gate is the
// thing being exercised — not a test-side pre-filter.
//
// ADR-089 §1/§3: methodology_profile, risk_register_record,
// risk_assessment_result, treatment_plan, and risk_appetite_profile were
// retired composed-GRC entities (formerly locked by issues #878/#879/#880/
// #1173/GC-T012/GC-T005 here). Only verification_result — an independently
// owned aggregate — remains behind this tool.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_RISK_GOVERNANCE_ACTIONS,
  GC_RISK_GOVERNANCE_ENTITIES,
  gcRiskGovernanceZodShape,
  gcRiskGovernanceToolHandler,
} from "./gc-risk-governance.js";
import { GOVERNANCE_FIELDS } from "./lib.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

const SCHEMA = z.object(gcRiskGovernanceZodShape);

// Reused fixed UUID fixtures. Extracted to named constants so the same literal
// is not duplicated across unrelated test blocks (Sonar S1192).
const UUID_ONES = "11111111-1111-1111-1111-111111111111";
const UUID_AS = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function makeFetchSpy({ status = 200, body = { id: "ent-uuid" } } = {}) {
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

// Drive a single handler invocation through Zod parse first, then dispatch.
// Returns the raw value the handler produced (handler returns null for
// delete-style 204s; the index.js registration wraps that in `ok()`).
async function callHandler(args) {
  const parsed = SCHEMA.parse(args);
  return gcRiskGovernanceToolHandler(parsed);
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
// Shape: GOVERNANCE_FIELDS[entity][action] + handler enums
// ---------------------------------------------------------------------------

describe("GOVERNANCE_FIELDS per-action shape", () => {
  it("indexes by entity then action for every entity in the handler", () => {
    for (const entity of GC_RISK_GOVERNANCE_ENTITIES) {
      assert.ok(GOVERNANCE_FIELDS[entity], `missing entity '${entity}'`);
      assert.ok(Array.isArray(GOVERNANCE_FIELDS[entity].create), `${entity}.create not an array`);
      assert.ok(Array.isArray(GOVERNANCE_FIELDS[entity].update), `${entity}.update not an array`);
    }
  });
});

describe("GC_RISK_GOVERNANCE_ACTIONS", () => {
  it("exposes the canonical action verbs the handler dispatches on", () => {
    assert.deepEqual(
      [...GC_RISK_GOVERNANCE_ACTIONS].sort(),
      ["create", "delete", "update"],
    );
  });
});

// ---------------------------------------------------------------------------
// verification_result create reqArg guards (#1173)
// ---------------------------------------------------------------------------

describe("verification_result create reqArg guards (#1173)", () => {
  const BASE = {
    entity: "verification_result",
    action: "create",
    prover: "agent-x",
    result: "PROVEN",
    assurance_level: "L1",
    verified_at: "2026-06-15T00:00:00Z",
  };

  it("rejects when prover is missing", async () => {
    makeFetchSpy();
    const { prover: _drop, ...args } = BASE;
    await assert.rejects(
      () => callHandler(args),
      (err) => {
        assert.match(err.message, /'prover' is required/);
        return true;
      },
    );
  });

  it("rejects when result is missing", async () => {
    makeFetchSpy();
    const { result: _drop, ...args } = BASE;
    await assert.rejects(
      () => callHandler(args),
      (err) => {
        assert.match(err.message, /'result' is required/);
        return true;
      },
    );
  });

  it("rejects when assurance_level is missing", async () => {
    makeFetchSpy();
    const { assurance_level: _drop, ...args } = BASE;
    await assert.rejects(
      () => callHandler(args),
      (err) => {
        assert.match(err.message, /'assurance_level' is required/);
        return true;
      },
    );
  });

  it("rejects when verified_at is missing", async () => {
    makeFetchSpy();
    const { verified_at: _drop, ...args } = BASE;
    await assert.rejects(
      () => callHandler(args),
      (err) => {
        assert.match(err.message, /'verified_at' is required/);
        return true;
      },
    );
  });

  it("create with all four required fields POSTs camelCase prover, result, assuranceLevel, verifiedAt to /api/v1/verification-results without stale keys", async () => {
    const calls = makeFetchSpy({ body: { id: "vr-uuid" } });
    await callHandler({
      entity: "verification_result",
      action: "create",
      project: "proj-a",
      prover: "agent-x",
      result: "PROVEN",
      assurance_level: "L1",
      verified_at: "2026-06-15T00:00:00Z",
      target_id: UUID_ONES,
      requirement_id: UUID_AS,
      property: "confidentiality",
      evidence: { ref: "audit-log-ref" },
      expires_at: "2027-06-15T00:00:00Z",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/verification-results\b/);
    assert.equal(calls[0].body.prover, "agent-x");
    assert.equal(calls[0].body.result, "PROVEN");
    assert.equal(calls[0].body.assuranceLevel, "L1");
    assert.equal(calls[0].body.verifiedAt, "2026-06-15T00:00:00Z");
    assert.equal(calls[0].body.targetId, UUID_ONES);
    assert.equal(calls[0].body.requirementId, UUID_AS);
    assert.equal(calls[0].body.property, "confidentiality");
    assert.deepEqual(calls[0].body.evidence, { ref: "audit-log-ref" });
    assert.equal(calls[0].body.expiresAt, "2027-06-15T00:00:00Z");
    // Must NOT contain the old bogus keys.
    for (const stale of ["uid", "title", "outcome", "status"]) {
      assert.ok(!(stale in calls[0].body), `stale key '${stale}' leaked onto the wire`);
    }
  });
});
