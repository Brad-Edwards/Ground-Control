// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  GOVERNANCE_STATUS_ENUMS,
  TEST_QUALITY_REVIEW_FINDINGS_SCHEMA,
  parseTestQualityReviewFindings,
  validateGovernanceStatus,
} from "./lib.js";

describe("parseTestQualityReviewFindings (verdict envelope, #931)", () => {
  const SWEEP = "scanned the test file; no other instances.";
  function bareEnvelope(blocking, overrides = {}) {
    return JSON.stringify({
      verdict: overrides.verdict ?? (blocking.length === 0 ? "ship" : "ship-with-fixes"),
      architectural_read: overrides.architectural_read ?? "Reviewed the test file.",
      blocking,
    });
  }

  it("parses a wrapped claude --output-format json envelope", () => {
    const inner = bareEnvelope([
      {
        severity: "critical",
        location: "tools/tests/test_policy.py::Foo::test_bar",
        problem: "no assertions",
        why_it_matters: "would not catch a regression",
        fix: "assert on the return value",
        classification: "one-off",
        sweep_evidence: SWEEP,
      },
    ]);
    const stdout = JSON.stringify({ type: "result", result: inner });
    const r = parseTestQualityReviewFindings(stdout);
    assert.equal(r.findings.length, 1);
    assert.equal(r.findings[0].severity, "critical");
    assert.equal(r.findings[0].location, "tools/tests/test_policy.py::Foo::test_bar");
    assert.equal(r.findings[0].fix, "assert on the return value");
    assert.equal(r.envelope.verdict, "ship-with-fixes");
  });

  it("parses a bare envelope payload (no claude wrapper)", () => {
    const stdout = bareEnvelope([]);
    const r = parseTestQualityReviewFindings(stdout);
    assert.deepEqual(r.findings, []);
    assert.equal(r.envelope.verdict, "ship");
  });

  it("truncates an over-long note instead of discarding the whole review (aptl #293)", () => {
    // Regression: a test-quality review whose advisory note overran
    // the char cap was thrown away wholesale, blocking the /implement
    // workflow on a parse error despite a completed review.
    const stdout = JSON.stringify({
      verdict: "ship",
      architectural_read: "Reviewed the test file.",
      blocking: [],
      notes: [{ text: "x".repeat(450) }],
    });
    const r = parseTestQualityReviewFindings(stdout);
    assert.equal(r.envelope.notes.length, 1);
    assert.equal(r.envelope.notes[0].text.length, 300);
    assert.ok(r.envelope.notes[0].text.endsWith("…"));
  });

  it("truncates an over-long finding sweep_evidence instead of discarding the review (aptl #293)", () => {
    const stdout = JSON.stringify({
      verdict: "ship-with-fixes",
      architectural_read: "Reviewed the test file.",
      blocking: [
        {
          severity: "warning",
          location: "tests/test_x.py::test_y",
          problem: "weak assertion",
          why_it_matters: "would not catch a regression",
          fix: "assert on the value",
          classification: "one-off",
          sweep_evidence: "S".repeat(900),
        },
      ],
    });
    const r = parseTestQualityReviewFindings(stdout);
    assert.equal(r.findings.length, 1);
    assert.equal(r.findings[0].sweep_evidence.length, 500);
    assert.ok(r.findings[0].sweep_evidence.endsWith("…"));
  });

  it("parses a warning severity finding", () => {
    const stdout = bareEnvelope([
      {
        severity: "warning",
        location: "test_x.py:10",
        problem: "no parameterization",
        fix: "parameterize with subTest",
        classification: "one-off",
        sweep_evidence: SWEEP,
      },
    ]);
    const r = parseTestQualityReviewFindings(stdout);
    assert.equal(r.findings[0].severity, "warning");
    assert.equal(r.findings[0].why_it_matters, "");
  });

  it("throws on missing verdict (no envelope shape)", () => {
    assert.throws(() => parseTestQualityReviewFindings('{"other":[]}'));
  });

  it("throws on empty input", () => {
    assert.throws(() => parseTestQualityReviewFindings(""));
    assert.throws(() => parseTestQualityReviewFindings("   "));
  });

  it("throws on invalid JSON", () => {
    assert.throws(() => parseTestQualityReviewFindings("not json"));
  });

  it("throws on a malformed .result field", () => {
    const stdout = JSON.stringify({ type: "result", result: "not json" });
    assert.throws(() => parseTestQualityReviewFindings(stdout));
  });

  it("prefers structured_output over .result when verdict is present (issue #904 / #931)", () => {
    const stdout = JSON.stringify({
      type: "result",
      result: "",
      structured_output: JSON.parse(bareEnvelope([
        {
          severity: "critical",
          location: "backend/.../FooTest.java::Foo::test_bar",
          problem: "Assertion-free test.",
          why_it_matters: "Trivially passes.",
          fix: "Add an assertion on the return value.",
          classification: "one-off",
          sweep_evidence: SWEEP,
        },
      ])),
    });
    const r = parseTestQualityReviewFindings(stdout);
    assert.equal(r.findings.length, 1);
    assert.equal(r.findings[0].severity, "critical");
    assert.equal(r.findings[0].fix, "Add an assertion on the return value.");
  });

  it("uses structured_output even when .result is populated", () => {
    const stdout = JSON.stringify({
      type: "result",
      result: "(human-readable summary that is not JSON)",
      structured_output: JSON.parse(bareEnvelope([])),
    });
    const r = parseTestQualityReviewFindings(stdout);
    assert.deepEqual(r.findings, []);
  });

  it("throws on .result empty AND no structured_output.verdict", () => {
    const stdout = JSON.stringify({ type: "result", result: "" });
    assert.throws(() => parseTestQualityReviewFindings(stdout), /empty/);
  });

  it("throws on a bad severity value", () => {
    const stdout = bareEnvelope([
      { severity: "INFO", location: "x.py", problem: "p", fix: "f", classification: "one-off", sweep_evidence: SWEEP },
    ]);
    assert.throws(() => parseTestQualityReviewFindings(stdout));
  });

  it("throws when a required field is missing", () => {
    const stdout = bareEnvelope([
      { severity: "critical", location: "x.py", problem: "p", classification: "one-off", sweep_evidence: SWEEP },
    ]);
    assert.throws(() => parseTestQualityReviewFindings(stdout));
  });

  it("requires sweep_evidence on one-off findings", () => {
    // Note: deliberately omits sweep_evidence to exercise the required-field check.
    const stdout = bareEnvelope([
      { severity: "warning", location: "x.py:1", problem: "p", fix: "f", classification: "one-off" },
    ]);
    assert.throws(() => parseTestQualityReviewFindings(stdout), /sweep_evidence/);
  });

  it("requires category on class findings", () => {
    const stdout = bareEnvelope([
      { severity: "critical", location: "x.py:1", problem: "p", fix: "f", classification: "class" },
    ]);
    assert.throws(() => parseTestQualityReviewFindings(stdout), /category/);
  });
});

describe("TEST_QUALITY_REVIEW_FINDINGS_SCHEMA (verdict envelope, #931)", () => {
  it("is a verdict-envelope JSON schema compatible with claude --json-schema", () => {
    assert.equal(TEST_QUALITY_REVIEW_FINDINGS_SCHEMA.type, "object");
    assert.ok(TEST_QUALITY_REVIEW_FINDINGS_SCHEMA.required.includes("verdict"));
    assert.ok(TEST_QUALITY_REVIEW_FINDINGS_SCHEMA.required.includes("architectural_read"));
    assert.ok(TEST_QUALITY_REVIEW_FINDINGS_SCHEMA.required.includes("blocking"));
    assert.deepEqual(TEST_QUALITY_REVIEW_FINDINGS_SCHEMA.properties.verdict.enum, ["ship", "ship-with-fixes", "don't-ship"]);
    const item = TEST_QUALITY_REVIEW_FINDINGS_SCHEMA.properties.blocking.items;
    assert.deepEqual(item.properties.severity.enum, ["critical", "warning"]);
    assert.ok(item.required.includes("severity"));
    assert.ok(item.required.includes("location"));
    assert.ok(item.required.includes("problem"));
    assert.ok(item.required.includes("fix"));
    assert.ok(item.required.includes("classification"));
  });
});

// ---------------------------------------------------------------------------
// validateGovernanceStatus (gc_risk_governance per-entity status check)
// ---------------------------------------------------------------------------

describe("validateGovernanceStatus", () => {
  // ADR-089 §1/§3: methodology_profile, risk_register_record,
  // risk_assessment_result, treatment_plan, and risk_appetite_profile were
  // retired composed-GRC entities. Only verification_result remains.
  it("is a no-op when status is omitted", () => {
    assert.doesNotThrow(() => validateGovernanceStatus("verification_result", undefined));
    assert.doesNotThrow(() => validateGovernanceStatus("verification_result", null));
    assert.doesNotThrow(() => validateGovernanceStatus("verification_result", ""));
  });

  it("accepts a status that is valid for the given entity", () => {
    assert.doesNotThrow(() => validateGovernanceStatus("verification_result", "PROVEN"));
  });

  it("rejects a completely unknown status string with the valid-values hint", () => {
    assert.throws(
      () => validateGovernanceStatus("verification_result", "BOGUS"),
      (e) =>
        /'status'='BOGUS' is not valid for entity='verification_result'/.test(e.message) &&
        /Valid values: /.test(e.message),
    );
  });

  it("rejects status on an entity that is not in GOVERNANCE_STATUS_ENUMS", () => {
    assert.throws(
      () => validateGovernanceStatus("risk_scenario", "DRAFT"),
      (e) => /'status' is not valid for entity='risk_scenario'/.test(e.message),
    );
  });

  it("GOVERNANCE_STATUS_ENUMS keys cover every status-bearing entity", () => {
    // Lock in the entity set so a new gc_risk_governance entity with its own
    // status vocabulary cannot silently inherit the "no status" rejection.
    assert.deepEqual(
      Object.keys(GOVERNANCE_STATUS_ENUMS).sort(),
      ["verification_result"],
    );
  });
});
