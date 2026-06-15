// Adapter-level tests for the gc_risk_governance MCP handler. Drives the
// FULL path raw args → Zod parse → gcRiskGovernanceToolHandler → lib.js
// dispatch → mocked fetch, so the handler's own pick(args, ...) gate is the
// thing being exercised — not a test-side pre-filter. Locks in the fixes for
// issues #878 (risk_assessment_result), #879 (risk_register_record), and
// #880 (treatment_plan) against the authoritative backend Request records
// under backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/.

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
      ["create", "delete", "transition", "transition_approval", "update"],
    );
  });
});

// ---------------------------------------------------------------------------
// risk_assessment_result allowlist (#878)
// ---------------------------------------------------------------------------

describe("risk_assessment_result create allowlist (#878)", () => {
  const list = () => GOVERNANCE_FIELDS.risk_assessment_result.create;

  it("contains every backend RiskAssessmentResultRequest field", () => {
    for (const f of [
      "risk_scenario_id", "risk_register_record_id", "methodology_profile_id",
      "analyst_identity", "assumptions", "input_factors",
      "observation_date", "assessment_at", "time_horizon", "confidence",
      "uncertainty_metadata", "computed_outputs",
      "evidence_refs", "notes", "observation_ids",
    ]) {
      assert.ok(list().includes(f), `${f} missing from risk_assessment_result.create`);
    }
  });

  it("does NOT contain stale fields that the backend DTO does not accept", () => {
    for (const f of [
      "uid", "title", "description",
      "quantitative_value", "qualitative_value",
      "scenario_id", "approval_state", "metadata", "status",
    ]) {
      assert.ok(!list().includes(f), `${f} should not be on risk_assessment_result.create`);
    }
  });
});

describe("risk_assessment_result update allowlist (#878)", () => {
  const list = () => GOVERNANCE_FIELDS.risk_assessment_result.update;

  it("contains every UpdateRiskAssessmentResultRequest field", () => {
    for (const f of [
      "risk_register_record_id", "methodology_profile_id",
      "analyst_identity", "assumptions", "input_factors",
      "observation_date", "assessment_at", "time_horizon", "confidence",
      "uncertainty_metadata", "computed_outputs",
      "evidence_refs", "notes", "observation_ids",
    ]) {
      assert.ok(list().includes(f), `${f} missing from risk_assessment_result.update`);
    }
  });

  it("does NOT contain create-only 'risk_scenario_id' (scenario is immutable after create)", () => {
    assert.ok(!list().includes("risk_scenario_id"));
  });

  it("does NOT contain stale fields", () => {
    for (const f of ["uid", "title", "description", "quantitative_value", "qualitative_value", "scenario_id", "approval_state", "metadata", "status"]) {
      assert.ok(!list().includes(f), `${f} should not be on risk_assessment_result.update`);
    }
  });
});

describe("risk_assessment_result wire body (#878)", () => {
  const SCENARIO = UUID_ONES;
  const REGISTER = "22222222-2222-2222-2222-222222222222";
  const PROFILE = "33333333-3333-3333-3333-333333333333";

  it("handler dispatches create with a camelCased body containing every backend create field", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "risk_assessment_result",
      action: "create",
      project: "proj-a",
      risk_scenario_id: SCENARIO,
      risk_register_record_id: REGISTER,
      methodology_profile_id: PROFILE,
      analyst_identity: "agent-a",
      assumptions: "stable inputs",
      input_factors: { ale: 100 },
      observation_date: "2026-01-15T00:00:00Z",
      assessment_at: "2026-01-16T00:00:00Z",
      time_horizon: "12 months",
      confidence: "HIGH",
      uncertainty_metadata: { variance: 0.05 },
      computed_outputs: { ale: 485000 },
      evidence_refs: ["EV-1", "EV-2"],
      notes: "Phase A smoke",
      observation_ids: ["44444444-4444-4444-4444-444444444444"],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/risk-assessment-results\b/);
    assert.match(calls[0].url, /project=proj-a/);
    assert.deepEqual(calls[0].body, {
      riskScenarioId: SCENARIO,
      riskRegisterRecordId: REGISTER,
      methodologyProfileId: PROFILE,
      analystIdentity: "agent-a",
      assumptions: "stable inputs",
      inputFactors: { ale: 100 },
      observationDate: "2026-01-15T00:00:00Z",
      assessmentAt: "2026-01-16T00:00:00Z",
      timeHorizon: "12 months",
      confidence: "HIGH",
      uncertaintyMetadata: { variance: 0.05 },
      computedOutputs: { ale: 485000 },
      evidenceRefs: ["EV-1", "EV-2"],
      notes: "Phase A smoke",
      observationIds: ["44444444-4444-4444-4444-444444444444"],
    });
  });

  it("handler drops stale create-args before reaching the wire (handler-side pick)", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "risk_assessment_result",
      action: "create",
      project: "proj-a",
      risk_scenario_id: SCENARIO,
      methodology_profile_id: PROFILE,
      // Stale fields the bug report shipped with — handler must drop these
      // via its own pick(args, GOVERNANCE_FIELDS.risk_assessment_result.create).
      uid: "RAR-1",
      title: "drop me",
      description: "drop me",
      approval_state: "DRAFT",
      metadata: { stale: true },
      // 'status' is intentionally omitted: validateGovernanceStatus rejects
      // any status on risk_assessment_result (the entity uses approval_state,
      // and the rejection is exercised by lib.test.js validateGovernanceStatus
      // tests). This test scope is the handler's pick(), not the validator.
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    // Positive: valid fields survived the handler's pick().
    assert.equal(calls[0].body.riskScenarioId, SCENARIO);
    assert.equal(calls[0].body.methodologyProfileId, PROFILE);
    // Negative: stale fields did not reach the wire.
    for (const stale of [
      "uid", "title", "description", "approvalState", "metadata",
    ]) {
      assert.ok(!(stale in calls[0].body), `${stale} leaked onto the wire`);
    }
  });

  it("handler dispatches update without create-only 'risk_scenario_id' (scenario immutable after create)", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "risk_assessment_result",
      action: "update",
      id: UUID_AS,
      project: "proj-a",
      risk_scenario_id: SCENARIO, // must NOT reach the wire
      methodology_profile_id: PROFILE,
      analyst_identity: "agent-b",
      notes: "second-pass",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, new RegExp(`/api/v1/risk-assessment-results/${UUID_AS}\\b`));
    assert.ok(!("riskScenarioId" in calls[0].body));
    assert.equal(calls[0].body.methodologyProfileId, PROFILE);
    assert.equal(calls[0].body.analystIdentity, "agent-b");
    assert.equal(calls[0].body.notes, "second-pass");
  });
});

// ---------------------------------------------------------------------------
// risk_register_record allowlist (#879)
// ---------------------------------------------------------------------------

describe("risk_register_record create allowlist (#879)", () => {
  const list = () => GOVERNANCE_FIELDS.risk_register_record.create;

  it("contains every backend RiskRegisterRecordRequest field", () => {
    for (const f of [
      "uid", "title", "owner", "review_cadence", "next_review_at",
      "category_tags", "decision_metadata", "asset_scope_summary",
      "risk_scenario_ids",
    ]) {
      assert.ok(list().includes(f), `${f} missing from risk_register_record.create`);
    }
  });

  it("does NOT contain singular 'scenario_id' (backend is List<UUID> riskScenarioIds)", () => {
    assert.ok(!list().includes("scenario_id"));
  });

  it("does NOT contain 'description' or 'status' (not on the Create DTO; status is transition-only)", () => {
    assert.ok(!list().includes("description"));
    assert.ok(!list().includes("status"));
  });

  it("does NOT contain 'metadata' (typed fields must not tunnel through metadata)", () => {
    assert.ok(!list().includes("metadata"));
  });
});

describe("risk_register_record update allowlist (#879)", () => {
  const list = () => GOVERNANCE_FIELDS.risk_register_record.update;

  it("contains every UpdateRiskRegisterRecordRequest field", () => {
    for (const f of [
      "title", "owner", "review_cadence", "next_review_at",
      "category_tags", "decision_metadata", "asset_scope_summary",
      "risk_scenario_ids",
    ]) {
      assert.ok(list().includes(f), `${f} missing from risk_register_record.update`);
    }
  });

  it("does NOT contain create-only 'uid'", () => {
    assert.ok(!list().includes("uid"));
  });

  it("does NOT contain stale 'scenario_id', 'description', 'status', 'metadata'", () => {
    for (const f of ["scenario_id", "description", "status", "metadata"]) {
      assert.ok(!list().includes(f), `${f} should not be on risk_register_record.update`);
    }
  });
});

describe("risk_register_record wire body (#879)", () => {
  const SC1 = "55555555-5555-5555-5555-555555555555";
  const SC2 = "66666666-6666-6666-6666-666666666666";

  it("handler routes multi-scenario via risk_scenario_ids as List<UUID>", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "risk_register_record",
      action: "create",
      project: "proj-a",
      uid: "REG-1",
      title: "Portfolio record",
      owner: "team-x",
      review_cadence: "quarterly",
      next_review_at: "2026-04-01T00:00:00Z",
      category_tags: ["regulatory", "supplier"],
      decision_metadata: { rationale: "merged after retro" },
      asset_scope_summary: "covers PCI-DSS assets in prod",
      risk_scenario_ids: [SC1, SC2],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/risk-register-records\b/);
    assert.deepEqual(calls[0].body, {
      uid: "REG-1",
      title: "Portfolio record",
      owner: "team-x",
      reviewCadence: "quarterly",
      nextReviewAt: "2026-04-01T00:00:00Z",
      categoryTags: ["regulatory", "supplier"],
      decisionMetadata: { rationale: "merged after retro" },
      assetScopeSummary: "covers PCI-DSS assets in prod",
      riskScenarioIds: [SC1, SC2],
    });
  });

  it("handler drops 'description', 'status', 'metadata' on create (handler-side pick)", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "risk_register_record",
      action: "create",
      project: "proj-a",
      uid: "REG-2",
      title: "T",
      description: "drop me",
      status: "ACCEPTED",
      metadata: { stale: true },
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    // Positive: valid fields survived.
    assert.equal(calls[0].body.uid, "REG-2");
    assert.equal(calls[0].body.title, "T");
    // Negative: stale fields did not reach the wire.
    for (const stale of ["scenarioId", "description", "status", "metadata"]) {
      assert.ok(!(stale in calls[0].body), `${stale} leaked onto the wire`);
    }
  });

  it("handler strips create-only 'uid' on update", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "risk_register_record",
      action: "update",
      id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      project: "proj-a",
      uid: "REG-LEAK",
      title: "rename",
      risk_scenario_ids: [SC1],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, /\/api\/v1\/risk-register-records\/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\b/);
    assert.ok(!("uid" in calls[0].body));
    assert.equal(calls[0].body.title, "rename");
    assert.deepEqual(calls[0].body.riskScenarioIds, [SC1]);
  });
});

// ---------------------------------------------------------------------------
// treatment_plan allowlist (#880)
// ---------------------------------------------------------------------------

describe("treatment_plan create allowlist (#880)", () => {
  const list = () => GOVERNANCE_FIELDS.treatment_plan.create;

  it("contains every backend TreatmentPlanRequest field", () => {
    for (const f of [
      "uid", "title", "risk_scenario_id", "risk_register_record_id",
      "strategy", "owner", "rationale", "due_date", "status",
      "action_items", "reassessment_triggers",
      "methodology_profile_id", "methodology_strategy_key",
    ]) {
      assert.ok(list().includes(f), `${f} missing from treatment_plan.create`);
    }
  });

  it("does NOT contain 'description' (backend has 'rationale', not 'description')", () => {
    assert.ok(!list().includes("description"));
  });

  it("does NOT contain misnamed 'due_at' (backend field is 'dueDate' / 'due_date')", () => {
    assert.ok(!list().includes("due_at"));
  });

  it("does NOT contain singular 'scenario_id' (backend field is 'riskScenarioId' / 'risk_scenario_id')", () => {
    assert.ok(!list().includes("scenario_id"));
  });

  it("does NOT contain 'metadata' (typed fields must not tunnel through metadata)", () => {
    assert.ok(!list().includes("metadata"));
  });
});

describe("treatment_plan update allowlist (#880)", () => {
  const list = () => GOVERNANCE_FIELDS.treatment_plan.update;

  it("contains every UpdateTreatmentPlanRequest field", () => {
    for (const f of [
      "title", "risk_scenario_id", "strategy", "owner",
      "rationale", "due_date", "action_items", "reassessment_triggers",
      "methodology_profile_id", "methodology_strategy_key",
    ]) {
      assert.ok(list().includes(f), `${f} missing from treatment_plan.update`);
    }
  });

  it("does NOT contain create-only 'uid', 'risk_register_record_id', 'status'", () => {
    for (const f of ["uid", "risk_register_record_id", "status"]) {
      assert.ok(!list().includes(f), `${f} should not be on treatment_plan.update`);
    }
  });

  it("does NOT contain stale 'scenario_id', 'due_at', 'description', 'metadata'", () => {
    for (const f of ["scenario_id", "due_at", "description", "metadata"]) {
      assert.ok(!list().includes(f), `${f} should not be on treatment_plan.update`);
    }
  });
});

describe("treatment_plan wire body (#880)", () => {
  const SCEN = "77777777-7777-7777-7777-777777777777";
  const REG = "88888888-8888-8888-8888-888888888888";

  it("handler dispatches create with a camelCased body containing every backend create field", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "treatment_plan",
      action: "create",
      project: "proj-a",
      uid: "TP-1",
      title: "Mitigate scenario X",
      risk_scenario_id: SCEN,
      risk_register_record_id: REG,
      strategy: "MITIGATE",
      owner: "team-y",
      rationale: "Cost-benefit favors mitigation",
      due_date: "2026-06-30T00:00:00Z",
      status: "PLANNED",
      action_items: [{ owner: "team-y", due_date: "2026-07-15T00:00:00Z", status: "PLANNED" }],
      reassessment_triggers: [
        { category: "ASSESSMENT_REFRESH", note: "new evidence" },
        { category: "CONTROL_STATE_CHANGED", target_type: "CONTROL",
          target_entity_id: UUID_ONES },
      ],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/treatment-plans\b/);
    assert.deepEqual(calls[0].body, {
      uid: "TP-1",
      title: "Mitigate scenario X",
      riskScenarioId: SCEN,
      riskRegisterRecordId: REG,
      strategy: "MITIGATE",
      owner: "team-y",
      rationale: "Cost-benefit favors mitigation",
      dueDate: "2026-06-30T00:00:00Z",
      status: "PLANNED",
      actionItems: [{ owner: "team-y", dueDate: "2026-07-15T00:00:00Z", status: "PLANNED" }],
      reassessmentTriggers: [
        { category: "ASSESSMENT_REFRESH", note: "new evidence" },
        { category: "CONTROL_STATE_CHANGED", targetType: "CONTROL",
          targetEntityId: UUID_ONES },
      ],
    });
  });

  it("handler drops 'description', 'metadata' on create (handler-side pick); 'due_at' is not a Zod field and is rejected at parse", async () => {
    // 'due_at' is not in gcRiskGovernanceZodShape (the Zod schema dropped
    // 'due_at' alongside 'scenario_id' when issue #880 renamed them). A
    // strict caller can't pass 'due_at' through Zod parse. Confirm the
    // handler's own pick() drops everything else even if the caller sends
    // a Zod-accepted shape.
    const calls = makeFetchSpy();
    await callHandler({
      entity: "treatment_plan",
      action: "create",
      project: "proj-a",
      uid: "TP-2",
      title: "T",
      strategy: "ACCEPT",
      risk_register_record_id: REG,
      description: "drop me",
      metadata: { stale: true },
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    // Positive: valid fields survived.
    assert.equal(calls[0].body.uid, "TP-2");
    assert.equal(calls[0].body.title, "T");
    assert.equal(calls[0].body.strategy, "ACCEPT");
    assert.equal(calls[0].body.riskRegisterRecordId, REG);
    // Negative: stale fields did not reach the wire.
    for (const stale of ["dueAt", "description", "metadata"]) {
      assert.ok(!(stale in calls[0].body), `${stale} leaked onto the wire`);
    }
    // And dueDate is not present either (caller didn't supply due_date).
    assert.ok(!("dueDate" in calls[0].body));
  });

  it("handler strips create-only 'uid', 'risk_register_record_id', 'status' on update", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "treatment_plan",
      action: "update",
      id: "cccccccc-cccc-cccc-cccc-cccccccccccc",
      project: "proj-a",
      uid: "TP-LEAK",
      risk_register_record_id: REG,
      status: "IN_PROGRESS", // must go through transition action, not update
      title: "rename",
      due_date: "2026-07-01T00:00:00Z",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].url, /\/api\/v1\/treatment-plans\/cccccccc-cccc-cccc-cccc-cccccccccccc\b/);
    for (const stale of ["uid", "riskRegisterRecordId", "status"]) {
      assert.ok(!(stale in calls[0].body), `${stale} leaked onto the wire on update`);
    }
    assert.equal(calls[0].body.title, "rename");
    assert.equal(calls[0].body.dueDate, "2026-07-01T00:00:00Z");
  });

  it("handler sends methodologyProfileId and methodologyStrategyKey on create with strategy OTHER", async () => {
    const calls = makeFetchSpy();
    const PROF = UUID_AS;
    await callHandler({
      entity: "treatment_plan",
      action: "create",
      project: "proj-a",
      uid: "TP-OTHER",
      title: "Custom strategy",
      risk_register_record_id: REG,
      strategy: "OTHER",
      methodology_profile_id: PROF,
      methodology_strategy_key: "RESIDUAL_TRANSFER",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.equal(calls[0].body.methodologyProfileId, PROF);
    assert.equal(calls[0].body.methodologyStrategyKey, "RESIDUAL_TRANSFER");
  });

  it("handler sends methodologyProfileId and methodologyStrategyKey on update", async () => {
    const calls = makeFetchSpy();
    const PROF = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    await callHandler({
      entity: "treatment_plan",
      action: "update",
      id: "dddddddd-dddd-dddd-dddd-dddddddddddd",
      project: "proj-a",
      strategy: "OTHER",
      methodology_profile_id: PROF,
      methodology_strategy_key: "UPDATED_KEY",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.equal(calls[0].body.methodologyProfileId, PROF);
    assert.equal(calls[0].body.methodologyStrategyKey, "UPDATED_KEY");
  });

  it("nested due_date in action_item maps to dueDate via recursive toCamelCase", async () => {
    const calls = makeFetchSpy();
    await callHandler({
      entity: "treatment_plan",
      action: "create",
      project: "proj-a",
      uid: "TP-CAMEL",
      title: "Camel test",
      risk_register_record_id: REG,
      strategy: "MITIGATE",
      action_items: [
        { owner: "bob", due_date: "2026-08-01T00:00:00Z", status: "IN_PROGRESS", description: "Patch firewall" },
      ],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    // Verify recursive toCamelCase converted due_date → dueDate inside the nested object
    const item = calls[0].body.actionItems[0];
    assert.equal(item.dueDate, "2026-08-01T00:00:00Z", "due_date should be recursively camelCased to dueDate");
    assert.ok(!("due_date" in item), "due_date (snake_case) must not appear in the wire body");
    assert.equal(item.owner, "bob");
    assert.equal(item.status, "IN_PROGRESS");
    assert.equal(item.description, "Patch firewall");
  });
});

// ---------------------------------------------------------------------------
// methodology_profile create reqArg guards (#1173)
// ---------------------------------------------------------------------------

describe("methodology_profile create reqArg guards (#1173)", () => {
  it("rejects when profile_key is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      () => callHandler({
        entity: "methodology_profile",
        action: "create",
        name: "MyProfile",
        version: "1.0",
        family: "FAIR",
      }),
      (err) => {
        assert.match(err.message, /'profile_key' is required/);
        return true;
      },
    );
  });

  it("rejects when name is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      () => callHandler({
        entity: "methodology_profile",
        action: "create",
        profile_key: "MY_PROFILE",
        version: "1.0",
        family: "FAIR",
      }),
      (err) => {
        assert.match(err.message, /'name' is required/);
        return true;
      },
    );
  });

  it("rejects when version is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      () => callHandler({
        entity: "methodology_profile",
        action: "create",
        profile_key: "MY_PROFILE",
        name: "MyProfile",
        family: "FAIR",
      }),
      (err) => {
        assert.match(err.message, /'version' is required/);
        return true;
      },
    );
  });

  it("rejects when family is missing", async () => {
    makeFetchSpy();
    await assert.rejects(
      () => callHandler({
        entity: "methodology_profile",
        action: "create",
        profile_key: "MY_PROFILE",
        name: "MyProfile",
        version: "1.0",
      }),
      (err) => {
        assert.match(err.message, /'family' is required/);
        return true;
      },
    );
  });

  it("create with all four required fields POSTs camelCase profileKey, name, version, family to /api/v1/methodology-profiles", async () => {
    const calls = makeFetchSpy({ body: { id: "prof-uuid" } });
    await callHandler({
      entity: "methodology_profile",
      action: "create",
      project: "proj-a",
      profile_key: "MY_PROFILE",
      name: "MyProfile",
      version: "1.0",
      family: "FAIR",
      description: "Test profile",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/methodology-profiles\b/);
    assert.equal(calls[0].body.profileKey, "MY_PROFILE");
    assert.equal(calls[0].body.name, "MyProfile");
    assert.equal(calls[0].body.version, "1.0");
    assert.equal(calls[0].body.family, "FAIR");
    assert.equal(calls[0].body.description, "Test profile");
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
      evidence: "audit-log-ref",
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
    assert.equal(calls[0].body.evidence, "audit-log-ref");
    assert.equal(calls[0].body.expiresAt, "2027-06-15T00:00:00Z");
    // Must NOT contain the old bogus keys.
    for (const stale of ["uid", "title", "outcome", "status"]) {
      assert.ok(!(stale in calls[0].body), `stale key '${stale}' leaked onto the wire`);
    }
  });
});

// ---------------------------------------------------------------------------
// GC-T012 methodology_profile crosswalk entries round-trip
// ---------------------------------------------------------------------------

describe("methodology_profile crosswalkEntries round-trip (GC-T012)", () => {
  it("GOVERNANCE_FIELDS.methodology_profile.create includes crosswalk_entries", () => {
    assert.ok(
      GOVERNANCE_FIELDS.methodology_profile.create.includes("crosswalk_entries"),
      "crosswalk_entries missing from methodology_profile.create allowlist",
    );
  });

  it("GOVERNANCE_FIELDS.methodology_profile.update includes crosswalk_entries", () => {
    assert.ok(
      GOVERNANCE_FIELDS.methodology_profile.update.includes("crosswalk_entries"),
      "crosswalk_entries missing from methodology_profile.update allowlist",
    );
  });

  it("create passes crosswalk_entries verbatim to the REST API (camelCase key)", async () => {
    const calls = makeFetchSpy({ body: { id: "prof-uuid" } });
    const entry = {
      normalizedConcept: "THREAT_SOURCE",
      vocabularySurface: "INPUT_SCHEMA",
      sourceFieldPath: "threat_source",
      sourceTermLabel: "Threat Source",
      limitations: "5-level ordinal, no continuous frequency",
    };

    await callHandler({
      entity: "methodology_profile",
      action: "create",
      project: "proj-a",
      profile_key: "NIST_SP800_30_R1",
      name: "NIST SP 800-30 Rev. 1",
      version: "1.0",
      description: "NIST qualitative",
      family: "NIST_SP800_30_R1",
      crosswalk_entries: [entry],
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.ok(Array.isArray(calls[0].body.crosswalkEntries), "crosswalkEntries must be an array in the wire body");
    assert.equal(calls[0].body.crosswalkEntries.length, 1);
    assert.equal(calls[0].body.crosswalkEntries[0].normalizedConcept, "THREAT_SOURCE");
    assert.equal(calls[0].body.crosswalkEntries[0].vocabularySurface, "INPUT_SCHEMA");
    assert.equal(calls[0].body.crosswalkEntries[0].sourceFieldPath, "threat_source");
    assert.equal(calls[0].body.crosswalkEntries[0].limitations, "5-level ordinal, no continuous frequency");
  });

  it("update passes crosswalk_entries verbatim to the REST API (camelCase key)", async () => {
    const calls = makeFetchSpy({ body: { id: "prof-uuid" } });
    const entry = {
      normalizedConcept: "LIKELIHOOD_OR_FREQUENCY",
      vocabularySurface: "INPUT_SCHEMA",
      sourceFieldPath: "loss_event_frequency",
      scale: "continuous",
      units: "annual events",
      conversionRule: "LEF = TEF × Vulnerability",
    };

    await callHandler({
      entity: "methodology_profile",
      action: "update",
      id: UUID_AS,
      project: "proj-a",
      crosswalk_entries: [entry],
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.ok(Array.isArray(calls[0].body.crosswalkEntries), "crosswalkEntries must be an array in the wire body");
    assert.equal(calls[0].body.crosswalkEntries[0].normalizedConcept, "LIKELIHOOD_OR_FREQUENCY");
    assert.equal(calls[0].body.crosswalkEntries[0].conversionRule, "LEF = TEF × Vulnerability");
    assert.equal(calls[0].body.crosswalkEntries[0].scale, "continuous");
  });

  it("Zod validates normalizedConcept enum values (rejects unknown)", async () => {
    assert.throws(
      () => SCHEMA.parse({
        entity: "methodology_profile",
        action: "create",
        project: "proj-a",
        name: "x",
        family: "FAIR",
        crosswalk_entries: [{
          normalizedConcept: "NOT_A_REAL_CONCEPT",
          vocabularySurface: "INPUT_SCHEMA",
          sourceFieldPath: "f1",
        }],
      }),
      /invalid_enum_value|ZodError|Invalid/,
    );
  });

  it("Zod validates vocabularySurface enum values (rejects unknown)", async () => {
    assert.throws(
      () => SCHEMA.parse({
        entity: "methodology_profile",
        action: "create",
        project: "proj-a",
        name: "x",
        family: "FAIR",
        crosswalk_entries: [{
          normalizedConcept: "THREAT_SOURCE",
          vocabularySurface: "NOT_A_SURFACE",
          sourceFieldPath: "f1",
        }],
      }),
      /invalid_enum_value|ZodError|Invalid/,
    );
  });
});
