// Split from test-case-tools.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  RequestError,
  copyTestCase,
  createTestCase,
  createTestCaseFolder,
  createTestCaseStep,
  deleteTestCaseFolder,
  moveTestCase,
  moveTestCaseFolder,
  reorderTestCaseFolders,
  reorderTestCases,
  transitionTestCaseStatus,
  updateTestCase,
  updateTestCaseFolder,
} from "./lib.js";

const BASE_URL = "http://gc-test:8000";

const TEST_CASE_ID = "11111111-1111-1111-1111-111111111111";

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

describe("Error envelope propagation", () => {
  it("createTestCase surfaces a 422 as RequestError with structured detail", async () => {
    setNextResponse({
      ok: false,
      status: 422,
      body: {
        error: {
          code: "validation_failed",
          message: "estimatedDurationSeconds must be >= 0",
          detail: { field: "estimatedDurationSeconds" },
        },
      },
    });

    await assert.rejects(
      () =>
        createTestCase(
          { uid: "TC-001", title: "x", type: "MANUAL", priority: "LOW", estimated_duration_seconds: -1 },
          "ground-control",
        ),
      (e) =>
        e instanceof RequestError
        && e.status === 422
        && e.code === "validation_failed"
        && e.detail?.field === "estimatedDurationSeconds",
    );
  });

  it("createTestCaseStep surfaces a 409 duplicate_step_number envelope", async () => {
    setNextResponse({
      ok: false,
      status: 409,
      body: {
        error: {
          code: "conflict",
          message: "Step number 1 already exists in test case TC-001",
        },
      },
    });

    await assert.rejects(
      () =>
        createTestCaseStep(
          TEST_CASE_ID,
          { step_number: 1, action: "act", expected_result: "exp" },
          "ground-control",
        ),
      // Pin the code too: TC-004 adds a second 409 path from the same
      // adapter (step-create against a GHERKIN parent → format mismatch).
      // Without the code check this assertion would also pass for the
      // GHERKIN-parent conflict, hiding a regression where the wrong code
      // is surfaced to callers branching on e.code.
      (e) => e instanceof RequestError && e.status === 409 && e.code === "conflict",
    );
  });

  it("transition surfaces a 422 invalid_status_transition envelope", async () => {
    setNextResponse({
      ok: false,
      status: 422,
      body: {
        error: {
          code: "invalid_status_transition",
          message: "Cannot transition test case status from APPROVED to DRAFT",
          detail: { current: "APPROVED", requested: "DRAFT" },
        },
      },
    });

    await assert.rejects(
      () => transitionTestCaseStatus(TEST_CASE_ID, "DRAFT", "ground-control"),
      (e) => e instanceof RequestError && e.status === 422 && e.code === "invalid_status_transition",
    );
  });
});

// TC-005 / ADR-043 — folder + move/copy/reorder wrappers.
describe("TestCaseFolder wrappers (gc_test_case TC-005)", () => {
  const FOLDER_ID = "33333333-3333-3333-3333-333333333333";
  const NEW_FOLDER_ID = "44444444-4444-4444-4444-444444444444";

  it("createTestCaseFolder POSTs /api/v1/test-cases/folders with camelCase body", async () => {
    setNextResponse({ body: { id: FOLDER_ID, title: "Smoke", parentFolderId: null, sortOrder: 0 } });
    await createTestCaseFolder(
      { title: "Smoke", description: "set", parentFolderId: null, sortOrder: 0 },
      "ground-control",
    );
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "POST");
    assert.equal(url.pathname, "/api/v1/test-cases/folders");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), {
      title: "Smoke",
      description: "set",
      parentFolderId: null,
      sortOrder: 0,
    });
  });

  it("updateTestCaseFolder PUTs /api/v1/test-cases/folders/{id}", async () => {
    setNextResponse({ body: { id: FOLDER_ID, title: "Renamed" } });
    await updateTestCaseFolder(FOLDER_ID, { title: "Renamed" }, "ground-control");
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "PUT");
    assert.equal(url.pathname, `/api/v1/test-cases/folders/${FOLDER_ID}`);
    // Project param + JSON body: parity with TC-001 updateTestCase test —
    // catches a regression that drops the body or sends wrong field names
    // (test-quality cycle 2).
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), { title: "Renamed" });
  });

  it("deleteTestCaseFolder DELETEs /api/v1/test-cases/folders/{id}", async () => {
    setNextResponse({ ok: true, status: 204 });
    await deleteTestCaseFolder(FOLDER_ID, "ground-control");
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "DELETE");
    assert.equal(url.pathname, `/api/v1/test-cases/folders/${FOLDER_ID}`);
    assert.equal(url.searchParams.get("project"), "ground-control");
  });

  it("moveTestCaseFolder PUTs /api/v1/test-cases/folders/{id}/move", async () => {
    setNextResponse({ body: { id: FOLDER_ID, parentFolderId: NEW_FOLDER_ID, sortOrder: 2 } });
    await moveTestCaseFolder(
      FOLDER_ID,
      { parentFolderId: NEW_FOLDER_ID, sortOrder: 2 },
      "ground-control",
    );
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "PUT");
    assert.equal(url.pathname, `/api/v1/test-cases/folders/${FOLDER_ID}/move`);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), {
      parentFolderId: NEW_FOLDER_ID,
      sortOrder: 2,
    });
  });

  it("reorderTestCaseFolders PUTs /api/v1/test-cases/folders/reorder", async () => {
    setNextResponse({ ok: true, status: 204 });
    await reorderTestCaseFolders(
      { parentFolderId: null, orderedFolderIds: [FOLDER_ID, NEW_FOLDER_ID] },
      "ground-control",
    );
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "PUT");
    assert.equal(url.pathname, "/api/v1/test-cases/folders/reorder");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), {
      parentFolderId: null,
      orderedFolderIds: [FOLDER_ID, NEW_FOLDER_ID],
    });
  });
});

describe("TestCase move/copy/reorder wrappers (gc_test_case TC-005)", () => {
  it("moveTestCase PUTs /api/v1/test-cases/{id}/move", async () => {
    setNextResponse({ body: { id: TEST_CASE_ID } });
    await moveTestCase(
      TEST_CASE_ID,
      { parentFolderId: null, sortOrder: 0 },
      "ground-control",
    );
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "PUT");
    assert.equal(url.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/move`);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), { parentFolderId: null, sortOrder: 0 });
  });

  it("copyTestCase POSTs /api/v1/test-cases/{id}/copy with newUid", async () => {
    setNextResponse({ body: { id: "99999999-9999-9999-9999-999999999999", uid: "TC-002" } });
    await copyTestCase(
      TEST_CASE_ID,
      { newUid: "TC-002", parentFolderId: null, sortOrder: null },
      "ground-control",
    );
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "POST");
    assert.equal(url.pathname, `/api/v1/test-cases/${TEST_CASE_ID}/copy`);
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), {
      newUid: "TC-002",
      parentFolderId: null,
      sortOrder: null,
    });
  });

  it("reorderTestCases PUTs /api/v1/test-cases/reorder", async () => {
    setNextResponse({ ok: true, status: 204 });
    await reorderTestCases(
      { parentFolderId: null, orderedTestCaseIds: [TEST_CASE_ID] },
      "ground-control",
    );
    const call = fetchCalls[0];
    const url = parseUrl(call);
    assert.equal(call.opts.method, "PUT");
    assert.equal(url.pathname, "/api/v1/test-cases/reorder");
    assert.equal(url.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(call.opts.body), {
      parentFolderId: null,
      orderedTestCaseIds: [TEST_CASE_ID],
    });
  });
});
