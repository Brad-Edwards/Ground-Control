// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { after, describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";

// ---------------------------------------------------------------------------
// gc_assert_traceability_reconciled (issue #1058)
// ---------------------------------------------------------------------------

// Shared module-scope helpers for the traceability/final-report/close-issue
// suites below. Hoisted out of the individual `describe` callbacks so the
// route-replaying gh shim source, the git-init boilerplate, and the
// PATH-wrapping runner are defined exactly once (Sonar S7721/S4144/S138).
const GH_NAME_WITH_OWNER = "nameWithOwner";

function initGitRepo(dir) {
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  return dir;
}

// Source for a hermetic `gh` shim that replays cfg.routes by argv prefix.
// String.raw keeps the `\n` in the unhandled-argv diagnostic literal (S7780).
function buildGhRouteShimSource(configPath) {
  return String.raw`#!/usr/bin/env node
const fs = require("node:fs");
const cfg = JSON.parse(fs.readFileSync(${JSON.stringify(configPath)}, "utf8"));
const argv = process.argv.slice(2);
function match(prefix) { return prefix.every((p, i) => argv[i] === p); }
for (const route of cfg.routes) {
  if (match(route.argv_prefix)) {
    if (route.exit_code != null && route.exit_code !== 0) {
      process.stderr.write(route.stderr || "");
      process.exit(route.exit_code);
    }
    process.stdout.write(route.stdout || "");
    process.exit(0);
  }
}
process.stderr.write("gh shim: unhandled argv: " + JSON.stringify(argv) + "\n");
process.exit(2);
`;
}

// Materializes a git repo + a bin dir holding a `gh` shim that replays
// `ghHandler.routes`. Returns { repoDir, binDir, cleanup }.
function makeRouteShimRepo({ ghHandler, repoPrefix, binPrefix }) {
  const repoDir = initGitRepo(mkdtempSync(join(tmpdir(), repoPrefix)));
  const binDir = mkdtempSync(join(tmpdir(), binPrefix));
  const configPath = join(binDir, "config.json");
  writeFileSync(configPath, JSON.stringify(ghHandler));
  writeFileSync(join(binDir, "gh"), buildGhRouteShimSource(configPath), { mode: 0o755 });
  return {
    repoDir, binDir,
    cleanup() { rmSync(repoDir, { recursive: true, force: true }); rmSync(binDir, { recursive: true, force: true }); },
  };
}

async function withShimPath(binDir, fn) {
  const oldPath = process.env.PATH;
  process.env.PATH = `${binDir}:${oldPath}`;
  try { return await fn(); } finally { process.env.PATH = oldPath; }
}

function makeTraceabilityTempRepo() {
  return initGitRepo(mkdtempSync(join(tmpdir(), "gc-trc-test-")));
}

// Hermetic gh shim for happy-path tests that reach the postPhaseMarker
// step. The shim returns canned responses for `gh repo view --json
// nameWithOwner` and the issue-comment POST so the marker post succeeds,
// and the test can assert the real success envelope (r.ok=true, r.comment_id)
// rather than using a throw-from-gh as a proxy for "the gate passed."
// Test-quality review cycle 1 (issue #1058) flagged the prior proxy-assertion
// pattern as a class finding; this helper closes the category by giving
// every happy-path test in this suite a real return value to assert against.
function makeShimRepoForAssert({ commentId = 9001 } = {}) {
  return makeRouteShimRepo({
    repoPrefix: "gc-trc-shim-",
    binPrefix: "gc-trc-bin-",
    ghHandler: {
      routes: [
        { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
        { argv_prefix: ["api", "--method", "POST"], stdout: JSON.stringify({ id: commentId, html_url: `https://github.com/fake/repo/issues/1058#issuecomment-${commentId}` }) },
      ],
    },
  });
}

// The runner calls Ground Control REST via global fetch() (getRequirementByUid +
// getTraceabilityLinks + getTraceabilityByArtifact). Mock fetch to drive
// each test's response shape without needing a live backend. Failure paths
// (status_mismatch, implements_missing, tests_missing, orphaned_issue_link)
// short-circuit BEFORE postPhaseMarker so they need no gh shim. Happy-path
// tests use makeShimRepoForAssert / withShimPath above.
function mockFetchForRequirements(routesByUrl) {
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
      status: 404, ok: false,
      text: async () => JSON.stringify({ error: { code: "NOT_FOUND", message: `no route for ${u}` } }),
    };
  };
  return () => {
    globalThis.fetch = originalFetch;
    if (originalBase === undefined) delete process.env.GC_BASE_URL;
    else process.env.GC_BASE_URL = originalBase;
  };
}

describe("runAssertTraceabilityReconciled", () => {
  it("refuses when override=true but override_reason is empty (input validation)", async () => {
    const dir = makeTraceabilityTempRepo();
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1,
        requirements: [], override: true, overrideReason: "",
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "traceability_override_missing_reason");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("throws on invalid issue_number", async () => {
    const dir = makeTraceabilityTempRepo();
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      await assert.rejects(
        runAssertTraceabilityReconciled({
          repoPath: dir, issueNumber: 0, requirements: [],
        }),
        /positive integer issue_number/,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses with status_mismatch when requirement is DRAFT but statusIntent='ACTIVE'", async () => {
    const dir = makeTraceabilityTempRepo();
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/uid/GC-X001", async () => ({ body: { id: "uuid-1", status: "DRAFT" } })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058,
        requirements: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "traceability_not_reconciled");
      assert.ok(r.failures.some((f) => f.reason === "status_mismatch" && f.uid === "GC-X001"));
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses with implements_missing when ACTIVE requirement has no IMPLEMENTS link", async () => {
    const dir = makeTraceabilityTempRepo();
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/uid/GC-X002", async () => ({ body: { id: "uuid-2", status: "ACTIVE" } })],
      ["/api/v1/requirements/uuid-2/traceability", async () => ({
        body: [{ link_type: "DOCUMENTS", artifact_type: "ADR", artifact_identifier: "ADR-001" }],
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058,
        requirements: [{ uid: "GC-X002", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "traceability_not_reconciled");
      assert.ok(r.failures.some((f) => f.reason === "implements_missing" && f.uid === "GC-X002"));
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses with tests_missing when IMPLEMENTS points at executable surface but no TESTS link exists", async () => {
    const dir = makeTraceabilityTempRepo();
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/uid/GC-X003", async () => ({ body: { id: "uuid-3", status: "ACTIVE" } })],
      ["/api/v1/requirements/uuid-3/traceability", async () => ({
        body: [{ link_type: "IMPLEMENTS", artifact_type: "FILE", artifact_identifier: "backend/src/main/java/Foo.java" }],
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058,
        requirements: [{ uid: "GC-X003", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "traceability_not_reconciled");
      assert.ok(r.failures.some((f) => f.reason === "tests_missing" && f.uid === "GC-X003"));
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("DRAFT requirement passes WITHOUT TESTS link (forward-looking exemption)", async () => {
    const shim = makeShimRepoForAssert({ commentId: 9004 });
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/uid/GC-X004", async () => ({ body: { id: "uuid-4", status: "DRAFT" } })],
      ["/api/v1/requirements/uuid-4/traceability", async () => ({
        body: [{ link_type: "DOCUMENTS", artifact_type: "ADR", artifact_identifier: "ADR-002" }],
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await withShimPath(shim.binDir, () =>
        runAssertTraceabilityReconciled({
          repoPath: shim.repoDir, issueNumber: 1058,
          requirements: [{ uid: "GC-X004", statusIntent: "DRAFT" }],
        }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.comment_id, 9004);
      assert.deepEqual(r.phase_marker, { phase: "traceability_reconciled", issue_number: 1058 });
      assert.equal(r.checked[0].uid, "GC-X004");
      assert.equal(r.checked[0].status, "DRAFT");
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("ACTIVE requirement with IMPLEMENTS link pointing at NON-executable surface passes WITHOUT TESTS", async () => {
    // The testable-surface heuristic: links pointing at docs/, architecture/,
    // skills/, changelog.d/, .github/workflows/ are not testable behavior.
    const shim = makeShimRepoForAssert({ commentId: 9005 });
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/uid/GC-X005", async () => ({ body: { id: "uuid-5", status: "ACTIVE" } })],
      ["/api/v1/requirements/uuid-5/traceability", async () => ({
        body: [{ link_type: "IMPLEMENTS", artifact_type: "FILE", artifact_identifier: "docs/some.md" }],
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await withShimPath(shim.binDir, () =>
        runAssertTraceabilityReconciled({
          repoPath: shim.repoDir, issueNumber: 1058,
          requirements: [{ uid: "GC-X005", statusIntent: "ACTIVE" }],
        }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.comment_id, 9005);
      assert.equal(r.checked[0].implements_count, 1);
      assert.equal(r.checked[0].tests_count, 0);
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("empty requirements[] + orphaned GITHUB_ISSUE link refuses with orphaned_issue_link", async () => {
    const dir = makeTraceabilityTempRepo();
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/traceability/by-artifact", async () => ({
        body: [{ link_type: "IMPLEMENTS", artifact_type: "GITHUB_ISSUE", artifact_identifier: "1058" }],
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058,
        requirements: [],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "traceability_not_reconciled");
      assert.ok(r.failures.some((f) => f.reason === "orphaned_issue_link"));
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("override=true with non-empty reason bypasses the per-requirement checks", async () => {
    const shim = makeShimRepoForAssert({ commentId: 9006 });
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      // No fetch mocking — override skips REST entirely. With the gh shim
      // in place, the marker post succeeds and we can assert the real
      // success envelope.
      const r = await withShimPath(shim.binDir, () =>
        runAssertTraceabilityReconciled({
          repoPath: shim.repoDir, issueNumber: 1058,
          requirements: [{ uid: "GC-X999", statusIntent: "ACTIVE" }],
          override: true, overrideReason: "user authorized: doc-only diff after merge freeze",
        }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.override, true);
      assert.equal(r.override_reason, "user authorized: doc-only diff after merge freeze");
      assert.equal(r.comment_id, 9006);
    } finally {
      shim.cleanup();
    }
  });

  it("requirement lookup error returns traceability_requirement_lookup_failed envelope", async () => {
    const dir = makeTraceabilityTempRepo();
    writeFileSync(join(dir, ".ground-control.yaml"), "schema_version: 1\nproject: ground-control\n");
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/uid/GC-X007", async () => ({
        status: 500, body: { error: { code: "GC_X007", message: "backend error" } },
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058,
        requirements: [{ uid: "GC-X007", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "traceability_requirement_lookup_failed");
      assert.equal(r.uid, "GC-X007");
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("infers project from .ground-control.yaml when project parameter is omitted (issue #1462)", async () => {
    const shim = makeShimRepoForAssert({ commentId: 9010 });
    writeFileSync(join(shim.repoDir, ".ground-control.yaml"), "schema_version: 1\nproject: shifter\n");
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/traceability/by-artifact", async (url) => {
        assert.ok(url.includes("project=shifter"), `expected project=shifter in ${url}`);
        return { body: [] };
      }],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await withShimPath(shim.binDir, () =>
        runAssertTraceabilityReconciled({
          repoPath: shim.repoDir, issueNumber: 1058, requirements: [],
        }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.comment_id, 9010);
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("explicit project overrides .ground-control.yaml inference (issue #1462)", async () => {
    const shim = makeShimRepoForAssert({ commentId: 9011 });
    writeFileSync(join(shim.repoDir, ".ground-control.yaml"), "schema_version: 1\nproject: shifter\n");
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/traceability/by-artifact", async (url) => {
        assert.ok(url.includes("project=ground-control"), `expected project=ground-control in ${url}`);
        assert.ok(!url.includes("project=shifter"), `explicit project must override config: ${url}`);
        return { body: [] };
      }],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await withShimPath(shim.binDir, () =>
        runAssertTraceabilityReconciled({
          repoPath: shim.repoDir, issueNumber: 1058, requirements: [],
          project: "ground-control",
        }),
      );
      assert.equal(r.ok, true);
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("falls back to backend project_required when config lacks project (issue #1462)", async () => {
    const dir = makeTraceabilityTempRepo();
    writeFileSync(join(dir, ".ground-control.yaml"), "schema_version: 1\n");
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/traceability/by-artifact", async () => ({
        status: 422,
        body: {
          error: {
            code: "project_required",
            message: "Multiple projects exist. Specify a 'project' parameter.",
            detail: { project_count: 22 },
          },
        },
      })],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058, requirements: [],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "project_required");
      assert.deepEqual(r.detail, { project_count: 22 });
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("propagates project_required from traceability lookup with detail intact (issue #1462)", async () => {
    const dir = makeTraceabilityTempRepo();
    writeFileSync(join(dir, ".ground-control.yaml"), "schema_version: 1\nproject: shifter\n");
    const restore = mockFetchForRequirements([
      ["/api/v1/requirements/traceability/by-artifact", async (url) => {
        assert.ok(url.includes("project=shifter"), `expected resolved project in request: ${url}`);
        return {
          status: 422,
          body: {
            error: {
              code: "project_required",
              message: "Multiple projects exist. Specify a 'project' parameter.",
              detail: { project_count: 22 },
            },
          },
        };
      }],
    ]);
    try {
      const { runAssertTraceabilityReconciled } = await import("./lib.js");
      const r = await runAssertTraceabilityReconciled({
        repoPath: dir, issueNumber: 1058, requirements: [],
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "project_required");
      assert.deepEqual(r.detail, { project_count: 22 });
    } finally {
      restore();
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
