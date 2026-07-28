// Split from test-case-tools.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  TEST_CASE_FORMATS,
  TEST_CASE_PRIORITIES,
  TEST_CASE_STATUSES,
  TEST_CASE_TYPES,
  createTestCase,
  createTestCaseGherkin,
  createTestCaseStep,
  deleteTestCase,
  deleteTestCaseGherkin,
  deleteTestCaseStep,
  getTestCase,
  getTestCaseByUid,
  getTestCaseGherkin,
  listTestCases,
  transitionTestCaseStatus,
  updateTestCase,
  updateTestCaseGherkin,
  updateTestCaseStep,
} from "./lib.js";

const BASE_URL = "http://gc-test:8000";

const TEST_CASE_ID = "11111111-1111-1111-1111-111111111111";

const STEP_ID = "22222222-2222-2222-2222-222222222222";

let fetchCalls;

let originalFetch;

let originalBaseUrl;

function setNextResponse({ ok = true, status = 200, body = null } = {}) {
  globalThis.fetch = async (url, opts) => {
    fetchCalls.push({ url: typeof url === "string" ? url : url.toString(), opts });
    return {
      ok,
      status,
      text: async () => (body === null ? "" : typeof body === "string" ? body : JSON.stringify(body)),
    };
  };
}

beforeEach(() => {
  fetchCalls = [];
  originalFetch = globalThis.fetch;
  originalBaseUrl = process.env.GC_BASE_URL;
  process.env.GC_BASE_URL = BASE_URL;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  if (originalBaseUrl === undefined) {
    delete process.env.GC_BASE_URL;
  } else {
    process.env.GC_BASE_URL = originalBaseUrl;
  }
});

function parseUrl(call) {
  return new URL(call.url);
}

describe("TestCase enum mirrors", () => {
  it("exposes the four lifecycle states in order (matches TestCaseStatus.java)", () => {
    assert.deepEqual([...TEST_CASE_STATUSES], ["DRAFT", "APPROVED", "DEPRECATED", "ARCHIVED"]);
  });

  it("exposes the three type classifications in order (matches TestCaseType.java)", () => {
    assert.deepEqual([...TEST_CASE_TYPES], ["MANUAL", "AUTOMATED", "HYBRID"]);
  });

  it("exposes the four priority severities in order (matches TestCasePriority.java)", () => {
    assert.deepEqual([...TEST_CASE_PRIORITIES], ["CRITICAL", "HIGH", "MEDIUM", "LOW"]);
  });

  it("exposes both authored formats in order (matches TestCaseFormat.java, TC-004 / ADR-042)", () => {
    assert.deepEqual([...TEST_CASE_FORMATS], ["STEP_BASED", "GHERKIN"]);
  });
});

describe("createTestCase (gc_test_case action=create)", () => {
  it("POSTs /api/v1/test-cases with camelCase body and forwards the project param", async () => {
    setNextResponse({
      body: { id: TEST_CASE_ID, uid: "TC-001", title: "Login flow", status: "DRAFT" },
    });

    const result = await createTestCase(
      {
        uid: "TC-001",
        title: "Login flow",
        type: "MANUAL",
        priority: "HIGH",
        description: "# desc",
        preconditions: "pre",
        postconditions: "post",
        estimated_duration_seconds: 300,
      },
      "ground-control",
    );

    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "POST");
    assert.equal(parsed.pathname, "/api/v1/test-cases");
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    // Critical contract — these field names must reach Spring's
    // TestCaseRequest. A regression here causes Jackson to silently drop the
    // field (issue #875 / codex cycle 1 finding).
    assert.deepEqual(JSON.parse(opts.body), {
      uid: "TC-001",
      title: "Login flow",
      type: "MANUAL",
      priority: "HIGH",
      description: "# desc",
      preconditions: "pre",
      postconditions: "post",
      estimatedDurationSeconds: 300,
    });
    assert.equal(result.id, TEST_CASE_ID);
  });
});

describe("createTestCase format axis (TC-004 / ADR-042)", () => {
  it("forwards format as a top-level body field (no snake mapping needed)", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID, uid: "TC-G01", format: "GHERKIN" } });

    await createTestCase(
      {
        uid: "TC-G01",
        title: "Sign in",
        type: "MANUAL",
        priority: "HIGH",
        format: "GHERKIN",
      },
      "ground-control",
    );

    const body = JSON.parse(fetchCalls[0].opts.body);
    assert.equal(body.format, "GHERKIN");
  });

  it("omits format when caller doesn't set one (service-side default kicks in)", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID, uid: "TC-001", format: "STEP_BASED" } });

    await createTestCase(
      {
        uid: "TC-001",
        title: "Step-based",
        type: "MANUAL",
        priority: "HIGH",
      },
      "ground-control",
    );

    const body = JSON.parse(fetchCalls[0].opts.body);
    assert.ok(!("format" in body), "format must be absent so server applies STEP_BASED default");
  });
});

describe("updateTestCase (gc_test_case action=update)", () => {
  it("PUTs /api/v1/test-cases/{id} forwarding every clear flag in camelCase", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID, uid: "TC-001" } });

    const result = await updateTestCase(
      TEST_CASE_ID,
      {
        title: "New Title",
        type: "AUTOMATED",
        priority: "CRITICAL",
        estimated_duration_seconds: 900,
        clear_description: true,
        clear_preconditions: false,
        clear_postconditions: true,
        clear_estimated_duration: false,
      },
      "ground-control",
    );

    // Return value passes through so callers can read the updated record without
    // a follow-up GET. A `request()` regression that returned null / raw text
    // for PUT paths would have been invisible without this assertion.
    assert.equal(result.id, TEST_CASE_ID);
    assert.equal(result.uid, "TC-001");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "PUT");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    // Every clear flag must reach UpdateTestCaseRequest. If TO_CAMEL is missing
    // an entry, Spring receives `clear_description` (unknown field) and the
    // wipe never happens — visible-via-no-effect failure mode.
    assert.deepEqual(JSON.parse(opts.body), {
      title: "New Title",
      type: "AUTOMATED",
      priority: "CRITICAL",
      estimatedDurationSeconds: 900,
      clearDescription: true,
      clearPreconditions: false,
      clearPostconditions: true,
      clearEstimatedDuration: false,
    });
  });
});

describe("deleteTestCase (gc_test_case action=delete)", () => {
  it("DELETEs /api/v1/test-cases/{id}", async () => {
    setNextResponse({ ok: true, status: 204, body: null });

    await deleteTestCase(TEST_CASE_ID, "ground-control");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "DELETE");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
  });
});

describe("transitionTestCaseStatus (gc_test_case action=transition)", () => {
  it("PUTs /api/v1/test-cases/{id}/status with {status} body", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID, status: "APPROVED" } });

    const result = await transitionTestCaseStatus(TEST_CASE_ID, "APPROVED", "ground-control");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "PUT");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/status`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(opts.body), { status: "APPROVED" });
    assert.equal(result.status, "APPROVED");
  });
});

describe("createTestCaseStep (gc_test_case action=step-create)", () => {
  it("POSTs /api/v1/test-cases/{tc}/steps with camelCase body (TC-002)", async () => {
    setNextResponse({
      body: {
        id: STEP_ID,
        testCaseId: TEST_CASE_ID,
        stepNumber: 1,
        action: "Open login page",
        expectedResult: "Page renders",
      },
    });

    const result = await createTestCaseStep(
      TEST_CASE_ID,
      {
        step_number: 1,
        action: "Open login page",
        expected_result: "Page renders",
        actual_result: null,
      },
      "ground-control",
    );

    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "POST");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/steps`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    // Critical: snake → camel mapping must reach Spring's TestCaseStepRequest.
    // A missing TO_CAMEL entry would have Jackson silently drop the field —
    // same failure mode as TC-001 codex cycle 1.
    assert.deepEqual(JSON.parse(opts.body), {
      stepNumber: 1,
      action: "Open login page",
      expectedResult: "Page renders",
      actualResult: null,
    });
    assert.equal(result.id, STEP_ID);
  });
});

describe("updateTestCaseStep (gc_test_case action=step-update)", () => {
  it("PUTs /api/v1/test-cases/{tc}/steps/{s} with clear_actual_result mapped to camelCase", async () => {
    setNextResponse({ body: { id: STEP_ID, stepNumber: 1, actualResult: null } });

    const result = await updateTestCaseStep(
      TEST_CASE_ID,
      STEP_ID,
      {
        step_number: 2,
        action: "## Updated\n![img](https://example.com/x.png)",
        expected_result: "Updated result",
        clear_actual_result: true,
      },
      "ground-control",
    );

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "PUT");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/steps/${STEP_ID}`);
    // Match every other write-test in this file: assert the `project` query
    // param is forwarded. A regression that dropped `params: { project }` from
    // updateTestCaseStep's request() call would route the update to whichever
    // project the server defaults to instead of the caller's intended one.
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(opts.body), {
      stepNumber: 2,
      action: "## Updated\n![img](https://example.com/x.png)",
      expectedResult: "Updated result",
      clearActualResult: true,
    });
    assert.equal(result.id, STEP_ID);
  });
});

describe("deleteTestCaseStep (gc_test_case action=step-delete)", () => {
  it("DELETEs /api/v1/test-cases/{tc}/steps/{s}", async () => {
    setNextResponse({ ok: true, status: 204, body: null });

    await deleteTestCaseStep(TEST_CASE_ID, STEP_ID, "ground-control");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "DELETE");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/steps/${STEP_ID}`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
  });
});

describe("createTestCaseGherkin (gc_test_case action=gherkin-create)", () => {
  const SOURCE = "Feature: Sign in\n\n  Scenario: ok\n    Given a user\n    Then they sign in\n";

  it("POSTs /api/v1/test-cases/{tc}/gherkin with {source}", async () => {
    setNextResponse({
      body: {
        id: "33333333-3333-3333-3333-333333333333",
        testCaseId: TEST_CASE_ID,
        source: SOURCE,
      },
    });

    const result = await createTestCaseGherkin(TEST_CASE_ID, { source: SOURCE }, "ground-control");

    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "POST");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/gherkin`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    // Body shape mirrors backend TestCaseGherkinRequest — single field, no
    // camel translation needed. A regression that wrapped this in
    // `{ gherkin_source: ... }` (the MCP arg name) would have Jackson reject
    // the body.
    assert.deepEqual(JSON.parse(opts.body), { source: SOURCE });
    // Response keys normalise through toSnakeCase (testCaseId is in TO_CAMEL
    // since TC-007 added the test-suite member camelCase mapping).
    assert.equal(result.test_case_id, TEST_CASE_ID);
  });
});

describe("updateTestCaseGherkin (gc_test_case action=gherkin-update)", () => {
  const SOURCE = "Feature: Sign in\n\n  Scenario: updated\n    Given a user\n    Then they sign in\n";

  it("PUTs /api/v1/test-cases/{tc}/gherkin with {source} and round-trips the body", async () => {
    setNextResponse({ body: { id: "x", testCaseId: TEST_CASE_ID, source: SOURCE } });

    const result = await updateTestCaseGherkin(TEST_CASE_ID, { source: SOURCE }, "ground-control");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "PUT");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/gherkin`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(opts.body), { source: SOURCE });
    // Return value pass-through — guards against a `request()` regression that
    // returned null for PUT paths, the same shape every other write test in
    // this file pins. Response key normalises to snake_case (TC-007 added
    // testCaseId to TO_CAMEL for the test-suite member round-trip).
    assert.equal(result.test_case_id, TEST_CASE_ID);
    assert.equal(result.source, SOURCE);
  });
});

describe("deleteTestCaseGherkin (gc_test_case action=gherkin-delete)", () => {
  it("DELETEs /api/v1/test-cases/{tc}/gherkin", async () => {
    setNextResponse({ ok: true, status: 204, body: null });

    await deleteTestCaseGherkin(TEST_CASE_ID, "ground-control");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "DELETE");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/gherkin`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
  });
});

describe("getTestCaseGherkin (gc_test_case read via gc_query)", () => {
  it("GETs /api/v1/test-cases/{tc}/gherkin and round-trips the body", async () => {
    const SOURCE = "Feature: read\n  Scenario: r\n    Given x\n";
    setNextResponse({ body: { id: "y", testCaseId: TEST_CASE_ID, source: SOURCE } });

    const result = await getTestCaseGherkin(TEST_CASE_ID, "ground-control");

    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "GET");
    assert.equal(parsed.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/gherkin`);
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    assert.equal(result.source, SOURCE);
  });
});

describe("Read paths (gc_query routing)", () => {
  it("listTestCases GETs /api/v1/test-cases with project param and returns the array", async () => {
    setNextResponse({ body: [{ id: TEST_CASE_ID, uid: "TC-001" }] });
    const result = await listTestCases("ground-control");

    const { url, opts } = fetchCalls[0];
    assert.equal(opts.method, "GET");
    assert.equal(parseUrl(fetchCalls[0]).pathname, "/api/v1/test-cases");
    assert.equal(parseUrl(fetchCalls[0]).searchParams.get("project"), "ground-control");
    // Return value must round-trip — a `request()` regression that returned
    // null for GET paths would silently break every caller of this wrapper.
    assert.ok(Array.isArray(result));
    assert.equal(result.length, 1);
    assert.equal(result[0].id, TEST_CASE_ID);
  });

  it("getTestCase GETs /api/v1/test-cases/{id} and returns the test case body", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID, uid: "TC-001" } });
    const result = await getTestCase(TEST_CASE_ID, "ground-control");

    assert.equal(parseUrl(fetchCalls[0]).pathname, `/api/v1/test-cases/${TEST_CASE_ID}`);
    assert.equal(result.id, TEST_CASE_ID);
    assert.equal(result.uid, "TC-001");
  });

  it("getTestCaseByUid URI-encodes the uid segment and returns the test case body", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID, uid: "TC INT 001" } });
    const result = await getTestCaseByUid("TC INT 001", "ground-control");

    assert.equal(parseUrl(fetchCalls[0]).pathname, "/api/v1/test-cases/uid/TC%20INT%20001");
    assert.equal(result.id, TEST_CASE_ID);
  });
});
