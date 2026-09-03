// Coverage for the error/guard branches of the config normalizers in
// lib/repo-context-2.js and lib/repo-context-3.js (issue #1540 Sonar cleanup).
//
// These normalizers validate untrusted ground-control config blocks and return
// a structured { ok: false, errors: [...] } instead of throwing. The happy
// paths are exercised elsewhere; this file drives the invalid-input branches so
// every guard clause and its exact error message is covered.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  normalizeDevStartGateConfig,
  normalizeReviewDispositionConfig,
  normalizeRoutingConfig,
  normalizeWorkflowConfig,
} from "./lib.js";

describe("normalizeDevStartGateConfig invalid inputs", () => {
  it("rejects a non-boolean enabled", () => {
    const r = normalizeDevStartGateConfig({ enabled: "yes" });
    assert.equal(r.ok, false);
    assert.ok(r.errors.includes("workflow.dev_start_gate.enabled must be a boolean when set"));
  });

  it("accepts a boolean enabled (valid path)", () => {
    const r = normalizeDevStartGateConfig({ enabled: true });
    assert.equal(r.ok, true);
    assert.equal(r.value.enabled, true);
  });

  it("rejects an unknown required_for value", () => {
    const r = normalizeDevStartGateConfig({ required_for: "everything" });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes("workflow.dev_start_gate.required_for must be one of: source-bearing"),
    );
  });

  it("accepts the allowed required_for value (valid path)", () => {
    const r = normalizeDevStartGateConfig({ required_for: "source-bearing" });
    assert.equal(r.ok, true);
    assert.equal(r.value.required_for, "source-bearing");
  });

  it("rejects a non-string / blank plan_section", () => {
    const nonString = normalizeDevStartGateConfig({ plan_section: 123 });
    assert.equal(nonString.ok, false);
    assert.ok(
      nonString.errors.includes(
        "workflow.dev_start_gate.plan_section must be a non-empty string when set",
      ),
    );

    const blank = normalizeDevStartGateConfig({ plan_section: "   " });
    assert.equal(blank.ok, false);
    assert.ok(
      blank.errors.includes(
        "workflow.dev_start_gate.plan_section must be a non-empty string when set",
      ),
    );
  });

  it("rejects a multi-line plan_section", () => {
    const r = normalizeDevStartGateConfig({ plan_section: "first\nsecond" });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes("workflow.dev_start_gate.plan_section must be a single-line string"),
    );

    const cr = normalizeDevStartGateConfig({ plan_section: "first\rsecond" });
    assert.equal(cr.ok, false);
    assert.ok(
      cr.errors.includes("workflow.dev_start_gate.plan_section must be a single-line string"),
    );
  });

  it("accepts and trims a single-line plan_section (valid path)", () => {
    const r = normalizeDevStartGateConfig({ plan_section: "  Dev Start  " });
    assert.equal(r.ok, true);
    assert.equal(r.value.plan_section, "Dev Start");
  });
});

describe("normalizeWorkflowConfig invalid shape", () => {
  it("rejects a list-shaped workflow block", () => {
    const r = normalizeWorkflowConfig([]);
    assert.equal(r.ok, false);
    assert.ok(r.errors.includes("workflow must be a mapping, not a list"));
  });
});

describe("normalizeRoutingConfig invalid stages", () => {
  it("rejects a non-mapping stages value (string)", () => {
    const r = normalizeRoutingConfig({ stages: "nope" });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes("routing.stages must be a mapping from stage name to route config"),
    );
  });

  it("rejects a list-shaped stages value", () => {
    const r = normalizeRoutingConfig({ stages: [] });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes("routing.stages must be a mapping from stage name to route config"),
    );
  });

  it("accepts a well-formed stages mapping (valid path)", () => {
    const r = normalizeRoutingConfig({
      enabled: true,
      stages: { codebase_assessment: { tier: "medium" } },
    });
    assert.equal(r.ok, true);
    assert.equal(r.value.enabled, true);
    assert.deepEqual(r.value.stages.codebase_assessment, {
      tier: "medium",
      provider: "claude",
      model: "claude-sonnet-5",
    });
  });
});

describe("normalizeReviewDispositionConfig invalid inputs", () => {
  it("rejects a non-mapping block (scalar and list)", () => {
    const scalar = normalizeReviewDispositionConfig("on");
    assert.equal(scalar.ok, false);
    assert.ok(scalar.errors.includes("workflow.review_disposition must be a mapping when set"));

    const list = normalizeReviewDispositionConfig([]);
    assert.equal(list.ok, false);
    assert.ok(list.errors.includes("workflow.review_disposition must be a mapping when set"));
  });

  it("rejects a non-integer max_auto_overrides", () => {
    const str = normalizeReviewDispositionConfig({ max_auto_overrides: "3" });
    assert.equal(str.ok, false);
    assert.ok(
      str.errors.includes("workflow.review_disposition.max_auto_overrides must be an integer"),
    );

    const fractional = normalizeReviewDispositionConfig({ max_auto_overrides: 2.5 });
    assert.equal(fractional.ok, false);
    assert.ok(
      fractional.errors.includes(
        "workflow.review_disposition.max_auto_overrides must be an integer",
      ),
    );
  });

  it("rejects an out-of-range max_auto_overrides", () => {
    const r = normalizeReviewDispositionConfig({ max_auto_overrides: 6 });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes(
        "workflow.review_disposition.max_auto_overrides must be between 0 and 5 inclusive",
      ),
    );
  });

  it("accepts an in-range integer max_auto_overrides (valid path)", () => {
    const r = normalizeReviewDispositionConfig({ max_auto_overrides: 3 });
    assert.equal(r.ok, true);
    assert.equal(r.value.max_auto_overrides, 3);
  });

  it("rejects a non-mapping judge block (scalar and list)", () => {
    const scalar = normalizeReviewDispositionConfig({ judge: "on" });
    assert.equal(scalar.ok, false);
    assert.ok(
      scalar.errors.includes("workflow.review_disposition.judge must be a mapping when set"),
    );

    const list = normalizeReviewDispositionConfig({ judge: [] });
    assert.equal(list.ok, false);
    assert.ok(
      list.errors.includes("workflow.review_disposition.judge must be a mapping when set"),
    );
  });

  it("rejects an unknown key inside judge", () => {
    const r = normalizeReviewDispositionConfig({ judge: { bogus: 1 } });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes("workflow.review_disposition.judge has unknown key 'bogus'"),
    );
  });

  it("rejects a non-boolean judge.enabled", () => {
    const r = normalizeReviewDispositionConfig({ judge: { enabled: "yes" } });
    assert.equal(r.ok, false);
    assert.ok(
      r.errors.includes("workflow.review_disposition.judge.enabled must be a boolean when set"),
    );
  });

  it("rejects a non-string / blank judge.model", () => {
    const nonString = normalizeReviewDispositionConfig({ judge: { model: 5 } });
    assert.equal(nonString.ok, false);
    assert.ok(
      nonString.errors.includes(
        "workflow.review_disposition.judge.model must be a non-empty string when set",
      ),
    );

    const blank = normalizeReviewDispositionConfig({ judge: { model: "   " } });
    assert.equal(blank.ok, false);
    assert.ok(
      blank.errors.includes(
        "workflow.review_disposition.judge.model must be a non-empty string when set",
      ),
    );
  });

  it("accepts a well-formed judge block (valid path)", () => {
    const r = normalizeReviewDispositionConfig({
      enabled: true,
      mode: "authoritative",
      judge: { enabled: true, model: "  claude-opus-4-8  " },
    });
    assert.equal(r.ok, true);
    assert.equal(r.value.enabled, true);
    assert.equal(r.value.mode, "authoritative");
    assert.deepEqual(r.value.judge, { enabled: true, model: "claude-opus-4-8" });
  });
});
