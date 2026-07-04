import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_GRC_ASSESS_ACTIONS,
  GC_GRC_ASSESS_MODES,
  GC_GRC_ASSESS_SCOPE_TYPES,
  gcGrcAssessToolHandler,
  gcGrcAssessZodShape,
} from "./gc-grc-assess.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

function makeFetchSpy({ status = 200, body = { id: "run-1" } } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({
      url: url.toString(),
      method: opts?.method ?? "GET",
      body: opts?.body ? JSON.parse(opts.body) : null,
    });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

beforeEach(() => {
  process.env.GC_BASE_URL = "https://gc.test";
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
});

describe("gcGrcAssessZodShape", () => {
  const schema = z.object(gcGrcAssessZodShape);

  it("exposes the action, mode, and scope vocabularies", () => {
    assert.deepEqual([...GC_GRC_ASSESS_ACTIONS].sort(), ["get", "list", "review", "run"]);
    assert.deepEqual([...GC_GRC_ASSESS_MODES].sort(), ["model", "re_screen", "reassess"]);
    assert.ok(GC_GRC_ASSESS_SCOPE_TYPES.includes("whole_project"));
    assert.ok(GC_GRC_ASSESS_SCOPE_TYPES.includes("boundary"));
    assert.ok(GC_GRC_ASSESS_SCOPE_TYPES.includes("stale_drift_set"));
  });

  it("rejects an unknown action", () => {
    assert.equal(schema.safeParse({ action: "shell" }).success, false);
  });

  it("accepts a boundary model run", () => {
    const result = schema.safeParse({
      action: "run",
      project: "ground-control",
      mode: "model",
      scope_type: "boundary",
      scope_values: ["payments"],
      commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
      languages: ["java"],
      surfaces: ["application"],
      review_policy: "required",
      review_decision: "request_review",
      idempotency_key: "gc-1129-payments",
    });
    assert.equal(result.success, true);
  });
});

describe("gcGrcAssessToolHandler", () => {
  const runDefaults = {
    action: "run",
    mode: "model",
    scope_type: "whole_project",
    commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
    languages: ["java"],
    surfaces: ["application"],
    review_policy: "required",
    review_decision: "request_review",
  };

  it("run POSTs a fixed grc-assessment endpoint with camelCase body", async () => {
    const calls = makeFetchSpy({
      status: 201,
      body: { id: "run-1", state: "READY_FOR_REVIEW" },
    });

    const result = await gcGrcAssessToolHandler({
      action: "run",
      project: "ground-control",
      mode: "model",
      scope_type: "boundary",
      scope_values: ["payments"],
      commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
      languages: ["java"],
      surfaces: ["application"],
      review_policy: "required",
      review_decision: "request_review",
      idempotency_key: "gc-1129-payments",
      declared_boundaries: [
        {
          key: "payments",
          name: "Payments",
          path_selectors: ["backend/payments/**"],
          surfaces: ["application"],
        },
      ],
    });

    assert.equal(result.state, "READY_FOR_REVIEW");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/grc-assessment-runs");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(calls[0].body.mode, "MODEL");
    assert.equal(calls[0].body.scopeType, "BOUNDARY");
    assert.deepEqual(calls[0].body.scopeValues, ["payments"]);
    assert.equal(calls[0].body.reviewDecision, "REQUEST_REVIEW");
    assert.equal(calls[0].body.idempotencyKey, "gc-1129-payments");
    assert.deepEqual(calls[0].body.declaredBoundaries[0].pathSelectors, ["backend/payments/**"]);
  });

  it("maps every run vocabulary to backend enum values", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "run-1" } });

    for (const [mode, expected] of [
      ["model", "MODEL"],
      ["reassess", "REASSESS"],
      ["re_screen", "RE_SCREEN"],
    ]) {
      await gcGrcAssessToolHandler({ ...runDefaults, mode });
      assert.equal(calls.at(-1).body.mode, expected);
    }

    for (const [scope_type, expected] of [
      ["whole_project", "WHOLE_PROJECT"],
      ["package_path_set", "PACKAGE_PATH_SET"],
      ["boundary", "BOUNDARY"],
      ["asset", "ASSET"],
      ["named_threat_set", "NAMED_THREAT_SET"],
      ["named_risk_set", "NAMED_RISK_SET"],
      ["stale_drift_set", "STALE_DRIFT_SET"],
    ]) {
      await gcGrcAssessToolHandler({
        ...runDefaults,
        scope_type,
        scope_values: ["selected-scope"],
      });
      assert.equal(calls.at(-1).body.scopeType, expected);
    }

    for (const [review_policy, expected] of [
      ["required", "REQUIRED"],
      ["optional", "OPTIONAL"],
      ["disabled", "DISABLED"],
    ]) {
      await gcGrcAssessToolHandler({ ...runDefaults, review_policy });
      assert.equal(calls.at(-1).body.reviewPolicy, expected);
    }

    for (const [review_decision, expected] of [
      ["request_review", "REQUEST_REVIEW"],
      ["approved", "APPROVED"],
      ["rejected", "REJECTED"],
    ]) {
      await gcGrcAssessToolHandler({ ...runDefaults, review_decision });
      assert.equal(calls.at(-1).body.reviewDecision, expected);
    }
  });

  it("review POSTs the run review endpoint", async () => {
    const calls = makeFetchSpy({ body: { id: "run-1", state: "COMMITTED" } });
    await gcGrcAssessToolHandler({
      action: "review",
      project: "ground-control",
      id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      review_decision: "approved",
      reviewed_by: "alice",
      review_rationale: "Approved bootstrap.",
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/grc-assessment-runs/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/review");
    assert.equal(calls[0].body.reviewDecision, "APPROVED");
    assert.equal(calls[0].body.reviewedBy, "alice");
  });

  it("get GETs a project-scoped run record", async () => {
    const calls = makeFetchSpy({ body: { id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" } });
    const result = await gcGrcAssessToolHandler({
      action: "get",
      project: "ground-control",
      id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    });

    assert.equal(result.id, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/grc-assessment-runs/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    assert.equal(url.searchParams.get("project"), "ground-control");
  });

  it("list GETs project-scoped run records", async () => {
    const calls = makeFetchSpy({ body: [] });
    const result = await gcGrcAssessToolHandler({
      action: "list",
      project: "ground-control",
      limit: 10,
    });

    assert.deepEqual(result, []);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    const url = new URL(calls[0].url);
    assert.equal(url.pathname, "/api/v1/grc-assessment-runs");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.equal(url.searchParams.get("limit"), "10");
  });

  it("rejects missing required arguments before dispatch", async () => {
    await assert.rejects(
      () => gcGrcAssessToolHandler({ action: "run", scope_type: "whole_project" }),
      /'mode' is required for action='run'/,
    );
    await assert.rejects(
      () => gcGrcAssessToolHandler({ action: "run", mode: "model" }),
      /'scope_type' is required for action='run'/,
    );
    await assert.rejects(
      () => gcGrcAssessToolHandler({ action: "review", review_decision: "approved" }),
      /'id' is required for action='review'/,
    );
    await assert.rejects(
      () => gcGrcAssessToolHandler({ action: "review", id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" }),
      /'review_decision' is required for action='review'/,
    );
    await assert.rejects(
      () => gcGrcAssessToolHandler({ action: "get" }),
      /'id' is required for action='get'/,
    );
  });
});
