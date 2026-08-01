// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { before, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_DEV_START_GATE_REQUIRED_FIELDS,
  normalizeDevStartGateConfig,
  parseGroundControlYaml,
  validateDevStartPlanGate,
} from "./lib.js";

// ---------------------------------------------------------------------------
// normalizeWorkflowConfig integration — integration_manager (issue #989)
// ---------------------------------------------------------------------------

describe("parseGroundControlYaml workflow.integration_manager", () => {
  it("valid integration_manager block flows through to value.integration_manager", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  integration_manager:",
      "    approval_label: approved-for-integration",
      "    ordering: pr_number_asc",
      "    max_queue_size: 20",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.value.workflow.integration_manager, {
      approval_label: "approved-for-integration",
      ordering: "pr_number_asc",
      max_queue_size: 20,
      merge_strategy: null,
    });
  });

  it("merge_strategy flows through to value.integration_manager", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  integration_manager:",
      "    merge_strategy: squash",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.equal(result.value.workflow.integration_manager.merge_strategy, "squash");
  });

  it("invalid integration_manager block surfaces errors via parent errors[]", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  integration_manager:",
      "    bogus_key: true",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes("integration_manager") && e.includes("unknown key")),
      `expected integration_manager unknown-key error, got: ${JSON.stringify(result.errors)}`,
    );
  });

  it("absent integration_manager key still returns all-null value in emptyWorkflowConfig", () => {
    const yaml = ["schema_version: 1", "project: x", ""].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true);
    assert.deepEqual(result.value.workflow.integration_manager, {
      approval_label: null,
      ordering: null,
      max_queue_size: null,
      merge_strategy: null,
    });
  });

  it("minimal valid yaml includes integration_manager in workflow shape", () => {
    const result = parseGroundControlYaml("schema_version: 1\nproject: aces-sdl\n");
    assert.equal(result.ok, true);
    assert.deepEqual(result.value.workflow.integration_manager, {
      approval_label: null,
      ordering: null,
      max_queue_size: null,
      merge_strategy: null,
    });
  });
});

// ---------------------------------------------------------------------------
// workflow.dev_start_gate parser and plan validator (issue #1194)
// ---------------------------------------------------------------------------

describe("parseGroundControlYaml workflow.dev_start_gate", () => {
  it("defaults workflow.dev_start_gate to disabled when absent", () => {
    const result = parseGroundControlYaml("schema_version: 1\nproject: x\n");
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.value.workflow.dev_start_gate, {
      enabled: false,
      required_for: "source-bearing",
      plan_section: "Dev-Start Gate",
      blocker_uids: [],
      required_fields: [...DEFAULT_DEV_START_GATE_REQUIRED_FIELDS],
    });
  });

  it("accepts a fully populated workflow.dev_start_gate block", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  dev_start_gate:",
      "    enabled: true",
      "    required_for: source-bearing",
      "    plan_section: Dev-Start Gate",
      "    blocker_uids: [GC-O007, PC-NFR-0015]",
      "    required_fields:",
      "      - Requirement wave or gate",
      "      - Boundary owner",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.value.workflow.dev_start_gate, {
      enabled: true,
      required_for: "source-bearing",
      plan_section: "Dev-Start Gate",
      blocker_uids: ["GC-O007", "PC-NFR-0015"],
      required_fields: ["Requirement wave or gate", "Boundary owner"],
    });
  });

  it("rejects unknown workflow.dev_start_gate keys", () => {
    const result = parseGroundControlYaml([
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  dev_start_gate:",
      "    enabled: true",
      "    surprise: yes",
      "",
    ].join("\n"));
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes("workflow.dev_start_gate") && e.includes("unknown key")),
      JSON.stringify(result.errors),
    );
  });

  it("rejects blocker UIDs that are not a single bounded identifier", () => {
    // Config UID lists share the structured identity contract (issue #1425):
    // a bounded scalar, with existence left to the project-scoped lookup. So
    // the refusal is for values that cannot be one UID at all, not for values
    // that merely fail an allocator-shaped grammar.
    // Surrounding whitespace is not in this list: the config reader trims each
    // YAML entry before validating, which is correct for a config surface.
    for (const bad of ["GC-O007 GC-O008", "GC-O007\nGC-O008", "A".repeat(51), ""]) {
      const r = normalizeDevStartGateConfig({ enabled: true, blocker_uids: [bad] });
      assert.equal(r.ok, false, `should reject '${bad}'`);
      assert.ok(r.errors.some((e) => e.includes("blocker_uids[0]")), JSON.stringify(r.errors));
    }
  });
  it("accepts allocator-minted short blocker UIDs", () => {
    const r = normalizeDevStartGateConfig({ enabled: true, blocker_uids: ["APP-2"] });
    assert.equal(r.ok, true, JSON.stringify(r.errors));
  });
});

describe("validateDevStartPlanGate", () => {
  function enabledGate(overrides = {}) {
    return {
      enabled: true,
      required_for: "source-bearing",
      plan_section: "Dev-Start Gate",
      blocker_uids: ["GC-O007"],
      required_fields: [
        "Requirement wave or gate",
        "Boundary owner",
        "Contract or seam",
        "Tenant/principal/authz/audit/evidence/provenance context",
        "Connectivity/offline behavior",
        "Security relevance decision",
        "Framework/control-family impact",
        "Verification risk score",
        "Verification plan",
        "Supply chain/provenance impact",
        "Sovereignty/FOCI impact",
        "Quality-gate readiness",
        "Dev-start gate satisfied",
      ],
      ...overrides,
    };
  }

  function sourcePlan(extra = []) {
    return [
      "## Plan",
      "",
      "Do the work.",
      "",
      "## Dev-Start Gate",
      "",
      "- Source-bearing: yes",
      "- Requirement wave or gate: wave 0 readiness",
      "- Boundary owner: contracts/",
      "- Contract or seam: source-bearing issue intake and plan marker boundary",
      "- Tenant/principal/authz/audit/evidence/provenance context: audit and provenance fields are explicit in the gate",
      "- Connectivity/offline behavior: no runtime connectivity behavior changes",
      "- Security relevance decision: security-relevant",
      "- Framework/control-family impact: AC-1 and CM-3 mapped through policy fields",
      "- Verification risk score: auth=1 isolation=1 orchestration=0 supply=1 total=3",
      "- Verification plan: node tests and policy checks",
      "- Supply chain/provenance impact: policy-only helper, no dependency change",
      "- Sovereignty/FOCI impact: not applicable because no hosted control plane changes",
      "- Quality-gate readiness: mcp tests and make policy",
      "- Dev-start gate satisfied: yes",
      "- GC-O007 applicability: applies - this implements the gated development loop",
      ...extra,
      "",
    ].join("\n");
  }

  it("does nothing when the gate is disabled", () => {
    const r = validateDevStartPlanGate("no section", { enabled: false });
    assert.equal(r.ok, true);
    assert.equal(r.checked, false);
  });

  it("fails when an enabled gate section is missing", () => {
    const r = validateDevStartPlanGate("## Plan\n\nNo gate.", enabledGate());
    assert.equal(r.ok, false);
    assert.equal(r.error, "dev_start_gate_invalid");
    assert.ok(r.missing.includes("## Dev-Start Gate"));
  });

  it("accepts non-source-bearing plans with a concrete rationale", () => {
    const r = validateDevStartPlanGate([
      "## Dev-Start Gate",
      "",
      "- Source-bearing: no",
      "- Non-source rationale: docs and design only; no application source begins here",
      "",
    ].join("\n"), enabledGate());
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.source_bearing, false);
  });

  it("accepts source-bearing plans with configured fields and blocker applicability records", () => {
    const r = validateDevStartPlanGate(sourcePlan(), enabledGate());
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.source_bearing, true);
    assert.equal(r.risk_score_total, 3);
  });

  it("fails source-bearing plans that omit a configured field", () => {
    const r = validateDevStartPlanGate(
      sourcePlan().replace("- Boundary owner: contracts/\n", ""),
      enabledGate(),
    );
    assert.equal(r.ok, false);
    assert.ok(r.missing.includes("Boundary owner"), JSON.stringify(r));
  });

  it("requires high-risk verification evidence when total>=4", () => {
    const r = validateDevStartPlanGate(
      sourcePlan().replace("total=3", "total=4"),
      enabledGate(),
    );
    assert.equal(r.ok, false);
    assert.ok(r.missing.includes("High-risk verification evidence"), JSON.stringify(r));
  });
});
