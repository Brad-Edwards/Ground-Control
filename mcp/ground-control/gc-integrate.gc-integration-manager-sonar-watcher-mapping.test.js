// Split from gc-integrate.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";

// ---------------------------------------------------------------------------
// Helpers — minimal dep factories
// ---------------------------------------------------------------------------

// A ground-control.yaml that passes the parser (schema_version + project are
// the minimum required keys).
function validYaml(integrationManagerBlock = "") {
  return `schema_version: 1
project: test-project
${integrationManagerBlock}`;
}

// Build a fake PR entry as the GitHub API would return.
function makePr(n, labels = ["approved-for-integration"]) {
  return {
    number: n,
    head: { ref: `feature/pr-${n}`, sha: `sha${n}` },
    base: { ref: "dev" },
    created_at: `2026-05-0${n}T00:00:00Z`,
    updated_at: `2026-05-0${n}T01:00:00Z`,
    labels: labels.map((name) => ({ name })),
  };
}

// Build an execFile fake that returns one page with the given PRs and empty
// pages after that.  The fake records every argv array it receives.
function makeExecFileFake(pages) {
  const calls = [];
  return {
    calls,
    execFile: async (file, argv) => {
      calls.push([file, ...argv]);
      // Detect the page number from the --field page=N argument.
      const pageIdx = argv.findIndex((a) => a.startsWith("page="));
      const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
      const pageData = pages[pageNum - 1] ?? [];
      return { stdout: JSON.stringify(pageData), stderr: "" };
    },
  };
}

// Build deps that make the plan action succeed with the given PRs.
function happyDeps({ prs = [], yaml = validYaml(), owner = "acme", repo = "myrepo" } = {}) {
  const execFileFake = makeExecFileFake([prs]);
  return {
    execFile: execFileFake.execFile,
    execFileCalls: execFileFake.calls,
    resolveWorkspaceRoot: () => "/some/repo",
    ensureGitRepo: async (p) => p,
    getOwnerRepo: async () => ({ owner, name: repo }),
    readYaml: () => yaml,
  };
}

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

// Build a lock that tracks acquire/release counts, and can be forced to ELOCKED.
function makeLockFake({ locked = false } = {}) {
  let acquireCount = 0;
  let releaseCount = 0;

  return {
    getAcquireCount: () => acquireCount,
    getReleaseCount: () => releaseCount,
    acquireIntegrationLock: async (_repoRoot) => {
      if (locked) {
        const e = new Error("integration run is already in progress");
        e.code = "ELOCKED";
        throw e;
      }
      acquireCount++;
      return async () => {
        releaseCount++;
      };
    },
  };
}

// Build a worktree-capable execFile fake for prepare tests.
// `stepHandlers` is an array of functions `(file, argv) => result|throws`.
// Falls back to the default (gh API page 1) if no handler matches.
function makePrepareExecFileFake(prs, stepHandlers = []) {
  const calls = [];
  let handlerIdx = 0;

  return {
    calls,
    execFile: async (file, argv, _options) => {
      calls.push([file, ...argv]);

      // First check step handlers in order.
      if (handlerIdx < stepHandlers.length) {
        const handler = stepHandlers[handlerIdx];
        handlerIdx++;
        return handler(file, argv);
      }

      // Default: gh api calls return the prs list on page 1, empty after.
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        const pageData = pageNum === 1 ? prs : [];
        return { stdout: JSON.stringify(pageData), stderr: "" };
      }

      // Default: all git calls succeed.
      return { stdout: "", stderr: "" };
    },
  };
}

// Build the deps object for a prepare test.
// `overrides` let individual tests replace specific deps.
function prepareDeps(overrides = {}) {
  const prs = overrides.prs ?? [makePr(1)];
  const yaml = overrides.yaml ?? validYaml();
  const lockFake = overrides.lockFake ?? makeLockFake();
  const execFileFake = overrides.execFileFake ?? makePrepareExecFileFake(prs, overrides.stepHandlers ?? []);

  return {
    execFile: execFileFake.execFile,
    execFileCalls: execFileFake.calls,
    resolveWorkspaceRoot: overrides.resolveWorkspaceRoot ?? (() => "/some/repo"),
    ensureGitRepo: overrides.ensureGitRepo ?? (async (p) => p),
    getOwnerRepo: overrides.getOwnerRepo ?? (async () => ({ owner: "acme", name: "myrepo" })),
    readYaml: overrides.readYaml ?? (() => yaml),
    acquireIntegrationLock: lockFake.acquireIntegrationLock,
    lockFake,
    writeHaltLedger: overrides.writeHaltLedger ?? (() => {}),
    runCiWatcher: overrides.runCiWatcher ?? (async () => ({ conclusion: "skipped" })),
    runSonarWatcher: overrides.runSonarWatcher ?? (async () => ({ conclusion: "skipped" })),
    // Deterministic run ID.
    now: overrides.now ?? (() => 1748000000000),
    randomId: overrides.randomId ?? (() => "abc123"),
  };
}

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

// ---------------------------------------------------------------------------
// 30. Sonar watcher mapping
// ---------------------------------------------------------------------------

describe("gc_integration_manager — Sonar watcher mapping", () => {
  function sonarWatcherDeps(fakeSonarWatcher, { yaml, prs } = {}) {
    return prepareDeps({
      prs: prs ?? [makePr(1)],
      yaml: yaml ?? validYaml(),
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: fakeSonarWatcher,
    });
  }

  it("runSonarWatcher returns {quality_gate:'OK'} → outcome:ready", async () => {
    const deps = sonarWatcherDeps(async () => ({ conclusion: "success" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "ready");
  });

  it("runSonarWatcher returns {conclusion:'failure'} → outcome:blocked, failure_class:sonar_gate_red", async () => {
    const deps = sonarWatcherDeps(async () => ({ conclusion: "failure" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "sonar_gate_red");
  });

  it("runSonarWatcher returns {conclusion:'skipped'} + no sonarcloud config → outcome:ready", async () => {
    // No sonarcloud block in yaml.
    const deps = sonarWatcherDeps(async () => ({ conclusion: "skipped" }), {
      yaml: validYaml(), // no sonarcloud block
    });
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "ready");
  });

  it("runSonarWatcher returns {conclusion:'skipped'} + sonarcloud config present → outcome:blocked, failure_class:sonar_skipped_but_configured", async () => {
    const yaml = validYaml("sonarcloud:\n  organization: myorg\n  project_key: myrepo\n");
    const deps = sonarWatcherDeps(async () => ({ conclusion: "skipped" }), { yaml });
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "sonar_skipped_but_configured");
  });

  it("runSonarWatcher is called with pr_number and repo_path", async () => {
    const sonarWatcherCalls = [];
    const fakeSonarWatcher = async (pr, ctx) => {
      sonarWatcherCalls.push({ pr, ctx });
      return { conclusion: "skipped" };
    };
    const deps = sonarWatcherDeps(fakeSonarWatcher, { prs: [makePr(42)] });
    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);
    assert.equal(sonarWatcherCalls.length, 1, "Sonar watcher must be called once per PR");
    const { pr, ctx } = sonarWatcherCalls[0];
    assert.equal(pr.pr_number, 42, `expected pr_number=42, got: ${pr.pr_number}`);
    assert.ok(typeof ctx.repoRoot === "string" && ctx.repoRoot.length > 0, "ctx.repoRoot must be present");
  });
});

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
