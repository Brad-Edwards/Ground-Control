// Adapter-level tests for the GC-L007 GRC analysis kinds added to `gc_analyze`.
// Drives lib.js helpers (analyzeEvidenceFreshness, analyzeObservationProjection,
// aggregateVendorRisk) through a mocked fetch so the URL + query-parameter shape
// is locked. Per the GC-L007 codex preflight, MCP helper signatures, Zod field
// names, and backend query parameter names must be pinned by adapter tests.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  analyzeEvidenceFreshness,
  analyzeObservationProjection,
  aggregateVendorRisk,
  analyzeNistAssessment,
  analyzeFairQuantitative,
  analyzeComplianceMonitoring,
  toCamelCase,
  toSnakeCase,
} from "./lib.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;

const OMITS_UNDEFINED_PARAMS = "omits undefined params";
const RETURNS_JSON_BODY = "returns the JSON body";

function makeFetchSpy({ status = 200, body = {} } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({ url: url.toString(), method: opts?.method ?? "GET" });
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

describe("analyzeEvidenceFreshness (GC-L007)", () => {
  it("hits /api/v1/analysis/grc/evidence-freshness with camelCase params", async () => {
    const calls = makeFetchSpy({ body: { analysisKind: "evidence_freshness" } });

    await analyzeEvidenceFreshness({
      project: "ground-control",
      asOf: "2026-05-18T12:00:00Z",
      freshnessWindowDays: 30,
      includeSuperseded: true,
      assetId: "00000000-0000-0000-0000-000000000001",
      controlId: "00000000-0000-0000-0000-000000000002",
    });

    assert.equal(calls.length, 1);
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/analysis/grc/evidence-freshness");
    assert.equal(calls[0].method, "GET");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), "2026-05-18T12:00:00Z");
    assert.equal(url.searchParams.get("freshnessWindowDays"), "30");
    assert.equal(url.searchParams.get("includeSuperseded"), "true");
    assert.equal(url.searchParams.get("assetId"), "00000000-0000-0000-0000-000000000001");
    assert.equal(url.searchParams.get("controlId"), "00000000-0000-0000-0000-000000000002");
  });

  it(OMITS_UNDEFINED_PARAMS, async () => {
    const calls = makeFetchSpy();

    await analyzeEvidenceFreshness({ project: "ground-control" });

    const url = new URL(calls[0].url);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), null);
    assert.equal(url.searchParams.get("freshnessWindowDays"), null);
    assert.equal(url.searchParams.get("includeSuperseded"), null);
    assert.equal(url.searchParams.get("assetId"), null);
    assert.equal(url.searchParams.get("controlId"), null);
  });

  it(RETURNS_JSON_BODY, async () => {
    makeFetchSpy({ body: { analysisKind: "evidence_freshness", counts: { fresh: 3 } } });

    const result = await analyzeEvidenceFreshness({ project: "ground-control" });

    assert.equal(result.analysisKind, "evidence_freshness");
    assert.equal(result.counts.fresh, 3);
  });
});

describe("analyzeObservationProjection (GC-L007)", () => {
  it("hits /api/v1/analysis/grc/observation-projection with mode=ASSET_EXPOSURE", async () => {
    const calls = makeFetchSpy({ body: { analysisKind: "observation_exposure" } });

    await analyzeObservationProjection({
      project: "ground-control",
      asOf: "2026-05-18T12:00:00Z",
      mode: "ASSET_EXPOSURE",
      assetId: "00000000-0000-0000-0000-000000000001",
    });

    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/analysis/grc/observation-projection");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), "2026-05-18T12:00:00Z");
    assert.equal(url.searchParams.get("mode"), "ASSET_EXPOSURE");
    assert.equal(url.searchParams.get("assetId"), "00000000-0000-0000-0000-000000000001");
    assert.equal(url.searchParams.get("controlId"), null);
  });

  it("supports CONTROL_STATE mode with control filter", async () => {
    const calls = makeFetchSpy();

    await analyzeObservationProjection({
      project: "ground-control",
      mode: "CONTROL_STATE",
      controlId: "00000000-0000-0000-0000-000000000003",
    });

    const url = new URL(calls[0].url);
    assert.equal(url.searchParams.get("mode"), "CONTROL_STATE");
    assert.equal(url.searchParams.get("controlId"), "00000000-0000-0000-0000-000000000003");
  });

  it(RETURNS_JSON_BODY, async () => {
    // Mirrors the evidence_freshness "returns the JSON body" test — locks in
    // that the helper actually parses the response, not just dispatches the
    // request. lib.js's request() applies toSnakeCase to the response, but
    // toSnakeCase only renames keys that are in the TO_CAMEL/TO_SNAKE table
    // (e.g. controlUid → control_uid). Keys not in the table pass through
    // unchanged, so analysisKind stays camelCase.
    makeFetchSpy({ body: { analysisKind: "control_state", controlStates: [{ controlUid: "CTRL-1" }] } });

    const result = await analyzeObservationProjection({
      project: "ground-control",
      mode: "CONTROL_STATE",
    });

    assert.equal(result.analysisKind, "control_state");
    // controlUid is in the snake-case mapping (see lib.js TO_CAMEL); the
    // outer controlStates key is not.
    assert.equal(result.controlStates[0].control_uid, "CTRL-1");
  });
});

describe("aggregateVendorRisk (GC-L007)", () => {
  it("hits /api/v1/analysis/grc/vendor-risk with camelCase params", async () => {
    const calls = makeFetchSpy({ body: { analysisKind: "vendor_risk_aggregation" } });

    await aggregateVendorRisk({
      project: "ground-control",
      asOf: "2026-05-18T12:00:00Z",
      freshnessWindowDays: 60,
      vendorAssetId: "00000000-0000-0000-0000-00000000000a",
    });

    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/analysis/grc/vendor-risk");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), "2026-05-18T12:00:00Z");
    assert.equal(url.searchParams.get("freshnessWindowDays"), "60");
    assert.equal(url.searchParams.get("vendorAssetId"), "00000000-0000-0000-0000-00000000000a");
  });

  it(OMITS_UNDEFINED_PARAMS, async () => {
    const calls = makeFetchSpy();

    await aggregateVendorRisk({ project: "ground-control" });

    const url = new URL(calls[0].url);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), null);
    assert.equal(url.searchParams.get("freshnessWindowDays"), null);
    assert.equal(url.searchParams.get("vendorAssetId"), null);
  });

  it(RETURNS_JSON_BODY, async () => {
    makeFetchSpy({
      body: { analysisKind: "vendor_risk_aggregation", vendors: [] },
    });

    const result = await aggregateVendorRisk({ project: "ground-control" });

    assert.equal(result.analysisKind, "vendor_risk_aggregation");
    assert.deepEqual(result.vendors, []);
  });
});

describe("analyzeNistAssessment (GC-T014)", () => {
  it("hits /api/v1/analysis/grc/nist-sp-800-30 with camelCase params", async () => {
    const calls = makeFetchSpy({ body: { analysisKind: "nist_assessment" } });

    await analyzeNistAssessment({
      project: "ground-control",
      asOf: "2026-05-29T00:00:00Z",
      riskAssessmentResultId: "00000000-0000-0000-0000-000000000010",
      riskScenarioId: "00000000-0000-0000-0000-000000000020",
    });

    assert.equal(calls.length, 1);
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/analysis/grc/nist-sp-800-30");
    assert.equal(calls[0].method, "GET");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), "2026-05-29T00:00:00Z");
    assert.equal(
      url.searchParams.get("riskAssessmentResultId"),
      "00000000-0000-0000-0000-000000000010",
    );
    assert.equal(
      url.searchParams.get("riskScenarioId"),
      "00000000-0000-0000-0000-000000000020",
    );
  });

  it(OMITS_UNDEFINED_PARAMS, async () => {
    const calls = makeFetchSpy();

    await analyzeNistAssessment({ project: "ground-control" });

    const url = new URL(calls[0].url);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), null);
    assert.equal(url.searchParams.get("riskAssessmentResultId"), null);
    assert.equal(url.searchParams.get("riskScenarioId"), null);
  });

  it("returns the JSON body verbatim", async () => {
    makeFetchSpy({
      body: {
        analysisKind: "nist_assessment",
        scale: "ordinal",
        units: "qualitative ordinal levels",
        counts: { total: 2, byRiskLevel: { HIGH: 1, LOW: 1 } },
      },
    });

    const result = await analyzeNistAssessment({ project: "ground-control" });

    assert.equal(result.analysisKind, "nist_assessment");
    assert.equal(result.scale, "ordinal");
    assert.equal(result.counts.total, 2);
    assert.equal(result.counts.byRiskLevel.HIGH, 1);
  });

  it("preserves methodology-defined inner keys through case conversion (opaque guard)", () => {
    // GC-T014 / preflight: methodology-defined keys inside inputFactors /
    // computedOutputs must NOT be camel/snake-rewritten. This locks in that the
    // opaque-value-keys guard in lib.js covers the keys the NIST assessment uses.
    const payload = {
      input_factors: {
        threat_source_relevance: "EXPECTED",
        likelihood_initiation: "HIGH",
        likelihood_adverse_impact: "MODERATE",
        likelihood_overall: "MODERATE",
        impact_level: "HIGH",
        assessment_timeframe: { from: "2026-01-01", to: "2026-12-31" },
      },
      computed_outputs: {
        risk_level: "HIGH",
        matrix_cell: "L3-I4",
      },
    };

    const camel = toCamelCase(payload);
    // The outer keys are renamed but the inner methodology-defined keys are NOT.
    assert.deepEqual(Object.keys(camel.inputFactors).sort((a, b) => a.localeCompare(b)), [
      "assessment_timeframe",
      "impact_level",
      "likelihood_adverse_impact",
      "likelihood_initiation",
      "likelihood_overall",
      "threat_source_relevance",
    ]);
    assert.deepEqual(
      Object.keys(camel.computedOutputs).sort((a, b) => a.localeCompare(b)),
      ["matrix_cell", "risk_level"],
    );

    const snake = toSnakeCase(camel);
    assert.deepEqual(Object.keys(snake.input_factors).sort((a, b) => a.localeCompare(b)), [
      "assessment_timeframe",
      "impact_level",
      "likelihood_adverse_impact",
      "likelihood_initiation",
      "likelihood_overall",
      "threat_source_relevance",
    ]);
  });
});

describe("analyzeFairQuantitative (GC-T011)", () => {
  it("hits /api/v1/analysis/grc/fair-quantitative with camelCase params", async () => {
    const calls = makeFetchSpy({ body: { analysisKind: "fair_quantitative" } });

    await analyzeFairQuantitative({
      project: "ground-control",
      asOf: "2026-05-29T00:00:00Z",
      riskAssessmentResultId: "00000000-0000-0000-0000-000000000010",
      riskScenarioId: "00000000-0000-0000-0000-000000000020",
    });

    assert.equal(calls.length, 1);
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/analysis/grc/fair-quantitative");
    assert.equal(calls[0].method, "GET");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), "2026-05-29T00:00:00Z");
    assert.equal(
      url.searchParams.get("riskAssessmentResultId"),
      "00000000-0000-0000-0000-000000000010",
    );
    assert.equal(
      url.searchParams.get("riskScenarioId"),
      "00000000-0000-0000-0000-000000000020",
    );
  });

  it(OMITS_UNDEFINED_PARAMS, async () => {
    const calls = makeFetchSpy();

    await analyzeFairQuantitative({ project: "ground-control" });

    const url = new URL(calls[0].url);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), null);
    assert.equal(url.searchParams.get("riskAssessmentResultId"), null);
    assert.equal(url.searchParams.get("riskScenarioId"), null);
  });

  it("returns the JSON body verbatim", async () => {
    makeFetchSpy({
      body: {
        analysisKind: "fair_quantitative",
        scale: "continuous",
        counts: { total: 1, byRiskLevel: { HIGH: 1 } },
      },
    });

    const result = await analyzeFairQuantitative({ project: "ground-control" });

    assert.equal(result.analysisKind, "fair_quantitative");
    assert.equal(result.scale, "continuous");
    assert.equal(result.counts.total, 1);
    assert.equal(result.counts.byRiskLevel.HIGH, 1);
  });

  it("preserves methodology-defined inner keys through case conversion (opaque guard)", () => {
    // GC-T011 / preflight: methodology-defined keys inside inputFactors /
    // computedOutputs must NOT be camel/snake-rewritten. This locks in that the
    // opaque-value-keys guard in lib.js covers the FAIR factor map keys.
    const payload = {
      input_factors: {
        threat_event_frequency: { low: 1.0, likely: 2.0, high: 4.0 },
        primary_loss_magnitude: { low: 1000.0, likely: 5000.0, high: 20000.0, currency: "USD" },
      },
      computed_outputs: {
        loss_event_frequency: { low: 0.1, likely: 0.4, high: 1.6 },
        annualized_loss_expectancy: { low: 100.0, likely: 2000.0, high: 32000.0 },
      },
    };

    const camel = toCamelCase(payload);
    // Outer keys renamed; inner methodology-defined keys are NOT renamed.
    assert.deepEqual(
      Object.keys(camel.inputFactors).sort((a, b) => a.localeCompare(b)),
      ["primary_loss_magnitude", "threat_event_frequency"],
    );
    assert.deepEqual(
      Object.keys(camel.computedOutputs).sort((a, b) => a.localeCompare(b)),
      ["annualized_loss_expectancy", "loss_event_frequency"],
    );

    const snake = toSnakeCase(camel);
    assert.deepEqual(
      Object.keys(snake.input_factors).sort((a, b) => a.localeCompare(b)),
      ["primary_loss_magnitude", "threat_event_frequency"],
    );
  });
});

describe("analyzeComplianceMonitoring (GC-I004)", () => {
  it("hits /api/v1/analysis/grc/compliance-monitoring with camelCase params", async () => {
    const calls = makeFetchSpy({ body: { analysisKind: "continuous_compliance_monitoring" } });

    await analyzeComplianceMonitoring({
      project: "ground-control",
      asOf: "2026-06-20T00:00:00Z",
      freshnessWindowDays: 90,
    });

    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/analysis/grc/compliance-monitoring");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("asOf"), "2026-06-20T00:00:00Z");
    assert.equal(url.searchParams.get("freshnessWindowDays"), "90");
  });
});
