// Split from gc-assert-completion.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { runAssertCompletion, runPostFinalReport, validateFinalReportInput } from "./lib.js";

// ---------------------------------------------------------------------------
// Helpers (mirrored from gc-grc-reconciled.test.js)
// ---------------------------------------------------------------------------

const GH_NAME_WITH_OWNER = "nameWithOwner";

function initGitRepo(dir) {
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  // Real origin so owner/repo resolves from the git remote, as production does. git ignores
  // GH_REPO; the `gh repo view` fallback honours it.
  execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/fake/repo.git"]);
  return dir;
}

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

// `gh api --paginate --slurp` wraps each page's comments array in an outer array.
function slurpComments(comments) {
  return JSON.stringify([comments]);
}

// Hermetic fetch mock (mirrors mockFetchForGrc from gc-grc-reconciled.test.js)
function mockFetchForGrc(routesByUrl) {
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

// Make a shim repo for runAssertCompletion tests. Handles MULTIPLE POST calls
// (traceability_reconciled marker, grc_reconciled marker, final report comment).
// All POST routes return the same response since makeRouteShimRepo uses
// first-match — the shim finds the "api --method POST" route and uses it
// for every POST call.
function makeCompletionShimRepo({
  comments = [],
  commentIdSeq = [9500, 9501, 9502],
  prNumber = 42,
  prMerged = true,
  permissions = {
    fake: "write",
    "other-collaborator": "write",
    automation: "write",
    "repository-owner": "admin",
  },
} = {}) {
  // We need to handle multiple POSTs. Use a counter in a wrapper script.
  // Build a shim that cycles through commentIdSeq for each POST call.
  const repoDir = initGitRepo(mkdtempSync(join(tmpdir(), "gc-completion-shim-")));
  const binDir = mkdtempSync(join(tmpdir(), "gc-completion-bin-"));
  const counterPath = join(binDir, "counter.json");
  writeFileSync(counterPath, JSON.stringify({ index: 0, ids: commentIdSeq }));

  // The post_merge completion path (issue #963) resolves the linked PR via
  // `gh api graphql` and gates on it being merged. Mock the issue→PR timeline
  // lookup so the merge gate can be satisfied (prMerged=true) or exercised
  // (prMerged=false → state OPEN, mergedAt null).
  const prNode = {
    __typename: "PullRequest",
    number: prNumber,
    state: prMerged ? "MERGED" : "OPEN",
    mergedAt: prMerged ? "2026-06-22T02:00:00Z" : null,
    url: `https://github.com/fake/repo/pull/${prNumber}`,
  };
  const graphqlPayload = {
    data: { repository: { issue: { timelineItems: { nodes: [
      { __typename: "CrossReferencedEvent", source: prNode },
    ] } } } },
  };

  const configPath = join(binDir, "config.json");
  const ghHandler = {
    routes: [
      {
        argv_prefix: ["api", "user", "--jq", ".login"],
        stdout: "fake\n",
      },
      {
        argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER],
        stdout: JSON.stringify({ nameWithOwner: "fake/repo" }),
      },
      {
        argv_prefix: ["api", "graphql"],
        stdout: JSON.stringify(graphqlPayload),
      },
      {
        // Phase markers are believed only from an author with repository permission.
        argv_prefix: ["api", "--method", "GET", "/repos/fake/repo/collaborators/tester/permission"],
        stdout: "write\n",
      },
      {
        argv_prefix: ["api", "--method", "GET", "--paginate", "--slurp"],
        stdout: slurpComments(comments),
      },
    ],
  };
  writeFileSync(configPath, JSON.stringify(ghHandler));

  // Custom gh shim that handles multiple POSTs with sequential comment IDs
  const shimSource = `#!/usr/bin/env node
const fs = require("node:fs");
const cfg = JSON.parse(fs.readFileSync(${JSON.stringify(configPath)}, "utf8"));
const counterData = JSON.parse(fs.readFileSync(${JSON.stringify(counterPath)}, "utf8"));
const permissions = ${JSON.stringify(permissions)};
const argv = process.argv.slice(2);
function match(prefix) { return prefix.every((p, i) => argv[i] === p); }

// Handle POST specially with counter
if (argv[0] === "api" && argv[1] === "--method" && argv[2] === "POST") {
  const idx = counterData.index;
  const id = counterData.ids[idx] ?? (9500 + idx);
  counterData.index = idx + 1;
  fs.writeFileSync(${JSON.stringify(counterPath)}, JSON.stringify(counterData));
  process.stdout.write(JSON.stringify({ id, html_url: "https://github.com/fake/repo/issues/1103#issuecomment-" + id }));
  process.exit(0);
}
const permissionEndpoint = argv.find((arg) => arg.includes("/collaborators/") && arg.endsWith("/permission"));
if (permissionEndpoint) {
  const login = decodeURIComponent(permissionEndpoint.split("/collaborators/")[1].split("/permission")[0]);
  if (permissions[login]) {
    process.stdout.write(permissions[login] + "\\n");
    process.exit(0);
  }
  process.stderr.write("HTTP 404");
  process.exit(1);
}

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
process.stderr.write("gh shim: unhandled argv: " + JSON.stringify(argv) + "\\n");
process.exit(2);
`;
  writeFileSync(join(binDir, "gh"), shimSource, { mode: 0o755 });
  return {
    repoDir, binDir,
    cleanup() {
      rmSync(repoDir, { recursive: true, force: true });
      rmSync(binDir, { recursive: true, force: true });
    },
  };
}

// Shim that always fails for gh calls (used for fail-fast tests)
function makeFailShimRepo() {
  const repoDir = initGitRepo(mkdtempSync(join(tmpdir(), "gc-completion-fail-shim-")));
  const binDir = mkdtempSync(join(tmpdir(), "gc-completion-fail-bin-"));
  const shimSource = `#!/usr/bin/env node
process.stderr.write("gh shim: unexpected call in fail-fast test: " + JSON.stringify(process.argv.slice(2)) + "\\n");
process.exit(1);
`;
  writeFileSync(join(binDir, "gh"), shimSource, { mode: 0o755 });
  return {
    repoDir, binDir,
    cleanup() {
      rmSync(repoDir, { recursive: true, force: true });
      rmSync(binDir, { recursive: true, force: true });
    },
  };
}

// ---------------------------------------------------------------------------
// Test 1: traceability assertion fails → ok:false, final_report null, no marker post
// ---------------------------------------------------------------------------

describe("runAssertCompletion — traceability assertion fails", () => {
  it("returns ok:false, final_report null, assertions has traceability entry with ok:false", async () => {
    // Mock REST API 404s for the requirement UID.
    const shim = makeCompletionShimRepo({ comments: [] });
    const restore = mockFetchForGrc([
      // GC-TEST-001 not found
      ["/api/v1/requirements/uid/GC-TEST-001", async () => ({ status: 404, body: null })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertCompletion({
          repoPath: shim.repoDir,
          issueNumber: 1103,
          prNumber: 42,
          requirements: [{ uid: "GC-TEST-001", statusIntent: "ACTIVE" }],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green",
          sonarStatus: "skipped",
          plainEnglishOutcome: "Consolidates Phase D completion into a single tool call.",
        }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.final_report, null);
      assert.ok(Array.isArray(r.assertions));
      const traceEntry = r.assertions.find((a) => a.name === "traceability_reconciled");
      assert.ok(traceEntry, `expected traceability_reconciled in assertions; got: ${JSON.stringify(r.assertions)}`);
      assert.equal(traceEntry.ok, false);
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("propagates project_required detail from traceability lookup through completion (issue #1462)", async () => {
    const shim = makeCompletionShimRepo({ comments: [], prMerged: true });
    const restore = mockFetchForGrc([
      ["/api/v1/requirements/traceability/by-artifact", async (url) => {
        assert.ok(!url.includes("project="), `expected unqualified lookup when config lacks project: ${url}`);
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
      const r = await withShimPath(shim.binDir, () =>
        runAssertCompletion({
          repoPath: shim.repoDir,
          issueNumber: 1103,
          prNumber: 42,
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green",
          sonarStatus: "skipped",
          plainEnglishOutcome: "Phase E completion resolves project from repo config.",
        }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "project_required");
      assert.deepEqual(r.detail, { project_count: 22 });
      assert.equal(r.final_report, null);
      const traceEntry = r.assertions.find((a) => a.name === "traceability_reconciled");
      assert.equal(traceEntry?.ok, false);
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// Test 3: happy path — no in-scope requirements, ci green/sonar skipped,
// codex review present → ok:true (ADR-089 §2: no GRC assertion remains)
// ---------------------------------------------------------------------------

describe("runAssertCompletion — happy path", () => {
  it("returns ok:true, 1 assertion ok, final_report with comment_url", async () => {
    const shim = makeCompletionShimRepo({
      comments: [],
      commentIdSeq: [9500, 9501, 9502],
    });
    // For empty requirements, traceability calls the orphan check via
    // getTraceabilityByArtifact("/api/v1/requirements/traceability/by-artifact").
    // Mock it to return empty (no orphaned IMPLEMENTS links).
    const restore = mockFetchForGrc([
      ["/api/v1/requirements/traceability/by-artifact", async () => ({ body: [] })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertCompletion({
          repoPath: shim.repoDir,
          issueNumber: 1103,
          prNumber: 42,
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green",
          sonarStatus: "skipped",
          plainEnglishOutcome: "Consolidates Phase D completion into a single tool call.",
        }),
      );
      assert.equal(r.ok, true, `expected ok:true; got: ${JSON.stringify(r)}`);
      assert.ok(Array.isArray(r.assertions));
      assert.equal(r.assertions.length, 1);
      assert.equal(r.assertions[0].name, "traceability_reconciled");
      assert.equal(r.assertions[0].ok, true);
      assert.ok(r.final_report != null);
      assert.ok(typeof r.final_report.comment_url === "string");
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// Test 4: internalVerifiedPhases union bypasses phase-prereq read-after-write race
// ---------------------------------------------------------------------------

describe("runPostFinalReport — internalVerifiedPhases union", () => {
  it("passes prereq check even when GET paginate returns NO markers (read-after-write bypass)", async () => {
    // Shim returns empty comments (no markers on thread). With internalVerifiedPhases
    // the prereq check should succeed.
    const repoDir = initGitRepo(mkdtempSync(join(tmpdir(), "gc-completion-rawrace-")));
    const binDir = mkdtempSync(join(tmpdir(), "gc-completion-rawrace-bin-"));
    const configPath = join(binDir, "config.json");
    const ghHandler = {
      routes: [
        { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
        // Phase markers are believed only from an author with repository permission.
        { argv_prefix: ["api", "--method", "GET", "/repos/fake/repo/collaborators/tester/permission"], stdout: "write\n" },
        { argv_prefix: ["api", "--method", "GET", "--paginate", "--slurp"], stdout: slurpComments([]) },
        { argv_prefix: ["api", "--method", "POST"], stdout: JSON.stringify({ id: 9510, html_url: "https://github.com/fake/repo/issues/1103#issuecomment-9510" }) },
      ],
    };
    writeFileSync(configPath, JSON.stringify(ghHandler));
    writeFileSync(join(binDir, "gh"), buildGhRouteShimSource(configPath), { mode: 0o755 });
    const cleanup = () => {
      rmSync(repoDir, { recursive: true, force: true });
      rmSync(binDir, { recursive: true, force: true });
    };
    try {
      const r = await withShimPath(binDir, () =>
        runPostFinalReport({
          repoPath: repoDir,
          issueNumber: 1103,
          prNumber: 42,
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green",
          sonarStatus: "skipped",
          plainEnglishOutcome: "Consolidates Phase D completion into a single tool call.",
          internalVerifiedPhases: ["traceability_reconciled"],
        }),
      );
      assert.equal(r.ok, true, `expected ok:true with internalVerifiedPhases; got: ${JSON.stringify(r)}`);
    } finally {
      cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// Test 5: malformed final-report input → early ok:false BEFORE any side effects
// ---------------------------------------------------------------------------

describe("runAssertCompletion — malformed input early rejection", () => {
  it("returns ok:false with completion_final_report_input_invalid before any gh call", async () => {
    // ciStatus: "red" fails final report validation (runner refuses non-green CI).
    // Actually validateFinalReportInput accepts "red" (it's in the enum).
    // The runner (runPostFinalReport) rejects it but we want the validate-before-effects check.
    // Let's use a missing reviews for /implement (null reviews → validation error).
    // Actually reviews:[] also fails validation for implement lane (no codex entry).
    // The simplest approach: pass reviews:null which fails array check.
    // But to trigger completion_final_report_input_invalid specifically, we need
    // validateFinalReportInput to return ok:false. Let's use prNumber:-1.
    const shim = makeFailShimRepo();
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertCompletion({
          repoPath: shim.repoDir,
          issueNumber: 1103,
          prNumber: -1, // invalid → validateFinalReportInput returns ok:false
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green",
          sonarStatus: "skipped",
          plainEnglishOutcome: "Consolidates Phase D completion into a single tool call.",
        }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "completion_final_report_input_invalid");
      assert.ok(Array.isArray(r.assertions));
      assert.equal(r.assertions.length, 0);
      assert.equal(r.final_report, null);
    } finally {
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// Test 6: regression — external runPostFinalReport WITHOUT internalVerifiedPhases
// refuses when markers absent
// ---------------------------------------------------------------------------

describe("runPostFinalReport — no internalVerifiedPhases refuses when markers absent", () => {
  it("returns ok:false with phase_prerequisite_missing when called without internalVerifiedPhases and no markers", async () => {
    // Shim returns empty comments (no markers). Without internalVerifiedPhases
    // the prereq check reads GitHub and finds nothing → refuses.
    const repoDir = initGitRepo(mkdtempSync(join(tmpdir(), "gc-completion-noivp-")));
    const binDir = mkdtempSync(join(tmpdir(), "gc-completion-noivp-bin-"));
    const configPath = join(binDir, "config.json");
    const ghHandler = {
      routes: [
        { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
        // Phase markers are believed only from an author with repository permission.
        { argv_prefix: ["api", "--method", "GET", "/repos/fake/repo/collaborators/tester/permission"], stdout: "write\n" },
        { argv_prefix: ["api", "--method", "GET", "--paginate", "--slurp"], stdout: slurpComments([]) },
      ],
    };
    writeFileSync(configPath, JSON.stringify(ghHandler));
    writeFileSync(join(binDir, "gh"), buildGhRouteShimSource(configPath), { mode: 0o755 });
    const cleanup = () => {
      rmSync(repoDir, { recursive: true, force: true });
      rmSync(binDir, { recursive: true, force: true });
    };
    try {
      const r = await withShimPath(binDir, () =>
        runPostFinalReport({
          repoPath: repoDir,
          issueNumber: 1103,
          prNumber: 42,
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green",
          sonarStatus: "skipped",
          plainEnglishOutcome: "Consolidates Phase D completion into a single tool call.",
          // NO internalVerifiedPhases
        }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "phase_prerequisite_missing");
    } finally {
      cleanup();
    }
  });
});
