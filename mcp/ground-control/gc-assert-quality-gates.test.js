import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { buildQualityGateAssertion, runAssertQualityGates } from "./lib.js";

// buildQualityGateAssertion is the pure transform from a QualityGateService
// evaluation result (snake_cased by request()) into the gc_assert_quality_gates
// envelope. Testing it directly exercises the pass and fail envelopes with no
// REST I/O (issue #1101).

describe("buildQualityGateAssertion", () => {
  const passingEvaluation = {
    project_identifier: "demo",
    passed: true,
    total_gates: 2,
    passed_count: 2,
    failed_count: 0,
    gates: [
      {
        gate_name: "Active IMPLEMENTS Coverage",
        metric_type: "COVERAGE",
        metric_param: "IMPLEMENTS",
        scope_status: "ACTIVE",
        operator: "GTE",
        threshold: 100,
        actual_value: 100,
        passed: true,
      },
      {
        gate_name: "Active Orphan Count",
        metric_type: "ORPHAN_COUNT",
        metric_param: null,
        scope_status: "ACTIVE",
        operator: "LTE",
        threshold: 0,
        actual_value: 0,
        passed: true,
      },
    ],
  };

  it("returns the pass envelope with all gates echoed when every gate passes", () => {
    const result = buildQualityGateAssertion(passingEvaluation, "demo");

    assert.equal(result.ok, true);
    assert.equal(result.project, "demo");
    assert.equal(result.total_gates, 2);
    assert.equal(result.passed_count, 2);
    assert.equal(result.evaluated.length, 2);
    // No failure-only fields leak into the pass envelope.
    assert.equal(result.failing_gates, undefined);
    assert.equal(result.error, undefined);
    assert.equal(result.next_action, undefined);
  });

  it("returns the fail envelope listing ONLY failing gates as {name, metric_type, threshold, actual}", () => {
    const failingEvaluation = {
      passed: false,
      total_gates: 2,
      passed_count: 1,
      failed_count: 1,
      gates: [
        {
          gate_name: "Active IMPLEMENTS Coverage",
          metric_type: "COVERAGE",
          operator: "GTE",
          threshold: 100,
          actual_value: 100,
          passed: true,
        },
        {
          gate_name: "Active TESTS Coverage",
          metric_type: "COVERAGE",
          operator: "GTE",
          threshold: 90.8,
          actual_value: 84.9,
          passed: false,
        },
      ],
    };

    const result = buildQualityGateAssertion(failingEvaluation, "demo");

    assert.equal(result.ok, false);
    assert.equal(result.error, "quality_gates_failed");
    assert.equal(result.project, "demo");
    assert.equal(result.next_action, "fix_failing_quality_gates_and_retry");

    // Only the failing gate is reported — the passing one is omitted.
    assert.equal(result.failing_gates.length, 1);
    const failing = result.failing_gates[0];
    assert.equal(failing.name, "Active TESTS Coverage");
    assert.equal(failing.metric_type, "COVERAGE");
    assert.equal(failing.threshold, 90.8);
    assert.equal(failing.actual, 84.9);
    assert.equal(failing.operator, "GTE");

    // The message is self-describing: names the gate and its actual/threshold.
    assert.match(result.message, /Active TESTS Coverage/);
    assert.match(result.message, /actual=84\.9/);
    assert.match(result.message, /expected GTE 90\.8/);
  });

  it("treats a missing `passed` flag as a failure (fail-closed)", () => {
    const evaluation = {
      gates: [
        { gate_name: "Indeterminate", metric_type: "COVERAGE", operator: "GTE", threshold: 50, actual_value: 50 },
      ],
    };

    const result = buildQualityGateAssertion(evaluation, "demo");

    assert.equal(result.ok, false);
    assert.equal(result.failing_gates.length, 1);
    assert.equal(result.failing_gates[0].name, "Indeterminate");
  });

  it("treats an empty gate set as passing (no gates configured)", () => {
    const result = buildQualityGateAssertion({ passed: true, gates: [] }, "demo");

    assert.equal(result.ok, true);
    assert.equal(result.evaluated.length, 0);
    assert.equal(result.total_gates, 0);
  });

  it("falls back to the gate count when total/passed counts are absent", () => {
    const result = buildQualityGateAssertion(
      { gates: [{ gate_name: "G", metric_type: "COMPLETENESS", operator: "LTE", threshold: 0, actual_value: 0, passed: true }] },
      "demo",
    );

    assert.equal(result.ok, true);
    assert.equal(result.total_gates, 1);
    assert.equal(result.passed_count, 1);
  });
});

describe("runAssertQualityGates input validation", () => {
  it("rejects an empty project without touching the REST API", async () => {
    await assert.rejects(() => runAssertQualityGates({ project: "" }), /non-empty project/);
  });

  it("rejects a missing project without touching the REST API", async () => {
    await assert.rejects(() => runAssertQualityGates({}), /non-empty project/);
  });

  it("requires callers to explicitly pass requirements[]", async () => {
    await assert.rejects(
      () => runAssertQualityGates({ project: "demo" }),
      /requires requirements\[\]/,
    );
  });
});

function mockFetch(routesByUrl) {
  const originalFetch = globalThis.fetch;
  const originalBase = process.env.GC_BASE_URL;
  process.env.GC_BASE_URL = "http://test.invalid";
  globalThis.fetch = async (url) => {
    const u = String(url);
    for (const [pattern, handler] of routesByUrl) {
      if (u.includes(pattern)) {
        const r = await handler(u);
        return {
          status: r.status ?? 200,
          ok: (r.status ?? 200) < 400,
          text: async () => JSON.stringify(r.body ?? null),
          json: async () => r.body ?? null,
        };
      }
    }
    return {
      status: 404,
      ok: false,
      text: async () => JSON.stringify({ error: { code: "NOT_FOUND", message: `no route for ${u}` } }),
    };
  };
  return () => {
    globalThis.fetch = originalFetch;
    if (originalBase === undefined) delete process.env.GC_BASE_URL;
    else process.env.GC_BASE_URL = originalBase;
  };
}

describe("runAssertQualityGates in-scope DOCUMENTS coverage", () => {
  const activeDocumentsGateEvaluation = {
    passed: true,
    total_gates: 1,
    passed_count: 1,
    failed_count: 0,
    gates: [
      {
        gate_name: "Active DOCUMENTS Coverage",
        metric_type: "COVERAGE",
        metric_param: "DOCUMENTS",
        scope_status: "ACTIVE",
        operator: "GTE",
        threshold: 100,
        actual_value: 100,
        passed: true,
      },
    ],
  };

  it("checks in-scope DRAFT requirements when the active DOCUMENTS gate exists", async () => {
    const restore = mockFetch([
      ["/api/v1/quality-gates/evaluate", async () => ({ body: activeDocumentsGateEvaluation })],
      ["/api/v1/requirements/uid/GC-X001", async () => ({ body: { id: "uuid-1", uid: "GC-X001", status: "DRAFT" } })],
      ["/api/v1/requirements/uuid-1/traceability", async () => ({ body: [] })],
    ]);
    try {
      const result = await runAssertQualityGates({
        project: "demo",
        requirements: [{ uid: "GC-X001", statusIntent: "DRAFT" }],
      });

      assert.equal(result.ok, false);
      assert.equal(result.error, "in_scope_documentation_coverage_failed");
      assert.deepEqual(result.missing_documents, [
        { uid: "GC-X001", status: "DRAFT", missing_link_type: "DOCUMENTS" },
      ]);
    } finally {
      restore();
    }
  });

  it("does not require in-scope DOCUMENTS links when the DOCUMENTS gate is inactive", async () => {
    const restore = mockFetch([
      ["/api/v1/quality-gates/evaluate", async () => ({
        body: {
          passed: true,
          total_gates: 1,
          passed_count: 1,
          failed_count: 0,
          gates: [
            {
              gate_name: "Active TESTS Coverage",
              metric_type: "COVERAGE",
              metric_param: "TESTS",
              scope_status: "ACTIVE",
              operator: "GTE",
              threshold: 100,
              actual_value: 100,
              passed: true,
            },
          ],
        },
      })],
    ]);
    try {
      const result = await runAssertQualityGates({
        project: "demo",
        requirements: [{ uid: "GC-X001", statusIntent: "DRAFT" }],
      });

      assert.equal(result.ok, true);
      assert.equal(result.in_scope_documents_checked, false);
    } finally {
      restore();
    }
  });
});
