// Split from gc-integrate.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { happyDeps, makePr, validYaml } from "./gc-integrate.test-helpers.js";

// ---------------------------------------------------------------------------
// Import the module under test.  If gc-integrate.js does not exist yet the
// dynamic import below will throw, which surfaces as a failing test — that is
// the TDD "red" state we want.
// ---------------------------------------------------------------------------

let runIntegrationManager;

try {
  ({ runIntegrationManager } = await import("./gc-integrate.js"));
} catch (e) {
  // Not yet implemented; define a stub that always throws so every test fails
  // with a meaningful message.
  runIntegrationManager = async () => {
    throw new Error("gc-integrate.js not yet implemented");
  };
}

// ---------------------------------------------------------------------------
// Prepare-action test helpers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 31. status action
// ---------------------------------------------------------------------------

// Helper: build status deps with injectable fs primitives.
function statusDeps(overrides = {}) {
  return {
    // The status/release actions bind to the MCP launch workspace; tests supply
    // a fixture root so they exercise the authorized path rather than the
    // real checkout.
    resolveWorkspaceRoot: overrides.resolveWorkspaceRoot ?? (() => "/some/repo"),
    statFile: overrides.statFile ?? (() => ({ ok: false })),
    readdir: overrides.readdir ?? (() => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); }),
    readFile: overrides.readFile ?? (() => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); }),
    rmFile: overrides.rmFile ?? (() => {}),
  };
}

describe("gc_integration_manager — status action", () => {
  it("empty repo (no lock, no runs dir) → lock_held:false, last_run:null", async () => {
    const deps = statusDeps({
      statFile: () => ({ ok: false }), // no lock, no dirs
      readdir: () => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); },
    });
    const result = await runIntegrationManager(
      { action: "status", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.action, "status");
    assert.equal(result.lock_held, false);
    assert.equal(result.last_run, null);
    assert.ok(typeof result.lock_path === "string", "lock_path must be present");
  });

  it("lockfile present → lock_held:true", async () => {
    const deps = statusDeps({
      statFile: (p) => {
        // Return ok for the lock path, false for others.
        if (p.endsWith(".gc-integration-lock")) return { ok: true, mtimeMs: Date.now() };
        return { ok: false };
      },
      readdir: () => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); },
    });
    const result = await runIntegrationManager(
      { action: "status", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.lock_held, true);
  });

  it("runs dir with halt.json → last_run populated with run_id, started_at, halt_reason", async () => {
    const haltJson = JSON.stringify({
      run_id: "1748000000000-abc123",
      halt_reason: "pr_head_moved",
      pr_number_at_halt: 5,
      queue_state: [],
      timestamp: "2026-05-25T12:00:00.000Z",
    });
    const deps = statusDeps({
      statFile: (p) => {
        // Lock absent; run dir has mtime.
        if (p.endsWith("1748000000000-abc123")) return { ok: true, mtimeMs: 1748000000000 };
        return { ok: false };
      },
      readdir: () => ["1748000000000-abc123"],
      readFile: (p) => {
        if (p.endsWith("halt.json")) return haltJson;
        throw Object.assign(new Error("ENOENT"), { code: "ENOENT" });
      },
    });
    const result = await runIntegrationManager(
      { action: "status", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.ok(result.last_run !== null, "last_run must be populated");
    assert.equal(result.last_run.run_id, "1748000000000-abc123");
    assert.equal(result.last_run.started_at, "2026-05-25T12:00:00.000Z");
    assert.equal(result.last_run.halt_reason, "pr_head_moved");
  });

  it("multiple run dirs → picks most-recent by mtime", async () => {
    const olderHalt = JSON.stringify({
      run_id: "1000-older",
      halt_reason: "pr_head_moved",
      timestamp: "2026-01-01T00:00:00.000Z",
    });
    const newerHalt = JSON.stringify({
      run_id: "2000-newer",
      halt_reason: null,
      timestamp: "2026-05-25T00:00:00.000Z",
    });
    const deps = statusDeps({
      statFile: (p) => {
        if (p.endsWith("1000-older")) return { ok: true, mtimeMs: 1000 };
        if (p.endsWith("2000-newer")) return { ok: true, mtimeMs: 2000 };
        return { ok: false };
      },
      readdir: () => ["1000-older", "2000-newer"],
      readFile: (p) => {
        if (p.includes("2000-newer")) return newerHalt;
        if (p.includes("1000-older")) return olderHalt;
        throw Object.assign(new Error("ENOENT"), { code: "ENOENT" });
      },
    });
    const result = await runIntegrationManager(
      { action: "status", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.ok(result.last_run !== null, "last_run must be populated");
    assert.equal(result.last_run.run_id, "2000-newer", "should pick the most-recent run by mtime");
  });
});

// ---------------------------------------------------------------------------
// 32. release action
// ---------------------------------------------------------------------------

describe("gc_integration_manager — release action", () => {
  it("no lockfile present → released:false, reason:no_lock_held", async () => {
    const deps = statusDeps({
      statFile: () => ({ ok: false }),
    });
    const result = await runIntegrationManager(
      { action: "release", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.action, "release");
    assert.equal(result.released, false);
    assert.equal(result.reason, "no_lock_held");
  });

  it("lockfile present → removes it, returns released:true", async () => {
    const removed = [];
    const deps = statusDeps({
      statFile: (p) => {
        // Lock present initially; after removal it's gone (but statFile mock stays simple).
        if (p.endsWith(".gc-integration-lock")) return { ok: true, mtimeMs: Date.now() };
        return { ok: false };
      },
      rmFile: (p) => { removed.push(p); },
    });
    const result = await runIntegrationManager(
      { action: "release", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.released, true);
    assert.ok(typeof result.lock_path === "string", "lock_path must be present");
    // Verify rmFile was called.
    assert.equal(removed.length, 1, "rmFile must be called once");
    assert.ok(removed[0].endsWith(".gc-integration-lock"), `expected lock path to end with .gc-integration-lock, got: ${removed[0]}`);
  });

  it("rmFile throws → {ok:false, error:'release_failed', next_action:'manual_remove_lockfile'}", async () => {
    const deps = statusDeps({
      statFile: (p) => {
        if (p.endsWith(".gc-integration-lock")) return { ok: true, mtimeMs: Date.now() };
        return { ok: false };
      },
      rmFile: () => { throw new Error("Permission denied"); },
    });
    const result = await runIntegrationManager(
      { action: "release", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "release_failed");
    assert.equal(result.next_action, "manual_remove_lockfile");
    assert.ok(typeof result.message === "string", "message must be present");
  });
});

// ---------------------------------------------------------------------------
// 33. buildIntegrationQueue — fork identity fields
// ---------------------------------------------------------------------------

describe("gc_integration_manager — plan fork identity fields", () => {
  it("same-repo PR → head_is_fork:false, head_repo_owner/head_repo_name match base repo", async () => {
    const pr = makePr(1);
    pr.head.repo = { full_name: "acme/myrepo" };
    pr.base.repo = { full_name: "acme/myrepo" };
    const deps = happyDeps({ prs: [pr], owner: "acme", repo: "myrepo" });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.plan.length, 1);
    const entry = result.plan[0];
    assert.equal(entry.head_is_fork, false, "same-repo PR must not be marked as fork");
    assert.equal(entry.head_repo_owner, "acme");
    assert.equal(entry.head_repo_name, "myrepo");
  });

  it("fork PR → head_is_fork:true, head_repo_owner/head_repo_name reflect fork repo", async () => {
    const pr = makePr(2);
    pr.head.repo = { full_name: "contributor/myrepo" };
    pr.base.repo = { full_name: "acme/myrepo" };
    const deps = happyDeps({ prs: [pr], owner: "acme", repo: "myrepo" });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.plan.length, 1);
    const entry = result.plan[0];
    assert.equal(entry.head_is_fork, true, "fork PR must be marked as fork");
    assert.equal(entry.head_repo_owner, "contributor");
    assert.equal(entry.head_repo_name, "myrepo");
  });

  it("null head.repo → head_is_fork:false (null-safe fallback)", async () => {
    const pr = makePr(3);
    pr.head.repo = null;
    pr.base.repo = { full_name: "acme/myrepo" };
    const deps = happyDeps({ prs: [pr], owner: "acme", repo: "myrepo" });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.plan.length, 1);
    assert.equal(result.plan[0].head_is_fork, false, "null head.repo must not throw and must default to non-fork");
  });
});
