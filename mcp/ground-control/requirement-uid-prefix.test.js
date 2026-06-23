// Tests for the MCP requirement uid_prefix shape contract (#532).
//
// The lib.js wrappers:
//   - createRequirement → POST /api/v1/requirements
//
// The index.js tool:
//   - gc_requirement (create) validates exactly-one uid|uid_prefix
//
// Each test stubs globalThis.fetch and asserts the outbound HTTP shape.

import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  createRequirement,
} from "./lib.js";

function initGitRepo(dir) {
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  return dir;
}

const BASE_URL = "http://gc-test:8000";

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

describe("createRequirement — uid_prefix body serialisation", () => {
  it("sends uidPrefix in camelCase when uid_prefix is provided", async () => {
    setNextResponse({
      body: { id: "R1", uid: "PLAT-1", title: "Platform req" },
    });

    await createRequirement({ uid_prefix: "PLAT", title: "Platform req", statement: "A statement" });

    assert.equal(fetchCalls.length, 1);
    const { opts } = fetchCalls[0];
    const body = JSON.parse(opts.body);
    // snake_case uid_prefix must arrive at the backend as camelCase uidPrefix
    assert.equal(body.uidPrefix, "PLAT");
    // no uid field when prefix is used
    assert.equal(body.uid, undefined);
  });

  it("sends uid in camelCase when uid is provided (unchanged path)", async () => {
    setNextResponse({
      body: { id: "R2", uid: "REQ-001", title: "Explicit UID req" },
    });

    await createRequirement({ uid: "REQ-001", title: "Explicit UID req", statement: "A statement" });

    assert.equal(fetchCalls.length, 1);
    const { opts } = fetchCalls[0];
    const body = JSON.parse(opts.body);
    assert.equal(body.uid, "REQ-001");
    assert.equal(body.uidPrefix, undefined);
  });

  it("POSTs to /api/v1/requirements with a project query param", async () => {
    setNextResponse({ body: { id: "R3", uid: "PLAT-2" } });

    await createRequirement({ uid_prefix: "PLAT", title: "T", statement: "S" }, "my-project");

    const parsed = new URL(fetchCalls[0].url);
    assert.equal(parsed.pathname, "/api/v1/requirements");
    assert.equal(parsed.searchParams.get("project"), "my-project");
  });
});

describe("checkOrphanedIssueLinks — project param forwarded", () => {
  // checkOrphanedIssueLinks is not exported; test it indirectly via
  // runAssertTraceabilityReconciled with empty requirements[].
  it("passes project to getTraceabilityByArtifact when checking orphaned links", async () => {
    // The function calls getTraceabilityByArtifact("GITHUB_ISSUE", issueNumber, project).
    // Return an orphaned IMPLEMENTS link so the reconciliation gate fails early
    // (before reaching postPhaseMarker → gh), allowing us to assert the outbound URL shape.
    const dir = initGitRepo(mkdtempSync(join(tmpdir(), "gc-uid-test-")));
    try {
      setNextResponse({
        body: [{ id: "L1", link_type: "IMPLEMENTS", artifact_type: "GITHUB_ISSUE", artifact_identifier: "42" }],
      });

      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const result = await runAssertTraceabilityReconciled({
        repoPath: dir,
        issueNumber: 42,
        requirements: [],
        project: "ground-control",
      });

      // Gate should report orphaned link failure (not a gh error)
      assert.equal(result.ok, false);
      assert.equal(result.error, "traceability_not_reconciled");

      assert.equal(fetchCalls.length >= 1, true);
      const byArtifactCall = fetchCalls.find((c) => c.url.includes("traceability/by-artifact"));
      assert.ok(byArtifactCall, "expected a by-artifact call");
      const parsed = new URL(byArtifactCall.url);
      assert.equal(parsed.searchParams.get("artifactType"), "GITHUB_ISSUE");
      assert.equal(parsed.searchParams.get("artifactIdentifier"), "42");
      assert.equal(parsed.searchParams.get("project"), "ground-control");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
