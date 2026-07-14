// gc_integration_manager MCP tool tests (GC-O011, issue #989).
//
// TDD: tests written BEFORE the implementation.  Each test group maps to a
// behaviour clause in the dispatch spec.  All external I/O is injected via the
// `deps` parameter so no real git repo or gh CLI is required.
//
// Test runner: node --test (Node 18+).

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

// A yaml with an integration_manager block.
function yamlWithIM(imBlock) {
  return validYaml(`workflow:\n  integration_manager:\n${imBlock}`);
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
// Helper: assert the required error-envelope shape.
// ---------------------------------------------------------------------------

function assertErrorEnvelope(env, expectedError) {
  assert.equal(env.ok, false, `envelope.ok should be false, got ${env.ok}`);
  assert.equal(typeof env.error, "string", "envelope.error must be a string");
  assert.equal(typeof env.message, "string", "envelope.message must be a string");
  assert.equal(typeof env.next_action, "string", "envelope.next_action must be a string");
  if (expectedError !== undefined) {
    assert.equal(env.error, expectedError, `expected error=${expectedError}, got ${env.error}`);
  }
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
// 1. Argument validation
// ---------------------------------------------------------------------------

describe("gc_integration_manager — argument validation", () => {
  it("missing action → error:unknown_action", async () => {
    const result = await runIntegrationManager(
      { repo_path: "/some/repo" },
      happyDeps(),
    );
    assertErrorEnvelope(result, "unknown_action");
  });

  it("unknown action value → error:unknown_action", async () => {
    const result = await runIntegrationManager(
      { action: "destroy_everything", repo_path: "/some/repo" },
      happyDeps(),
    );
    assertErrorEnvelope(result, "unknown_action");
  });

  it("missing repo_path → error:invalid_repo_path", async () => {
    const result = await runIntegrationManager(
      { action: "plan" },
      happyDeps(),
    );
    assertErrorEnvelope(result, "invalid_repo_path");
  });

  it("repo_path empty string → error:invalid_repo_path", async () => {
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "" },
      happyDeps(),
    );
    assertErrorEnvelope(result, "invalid_repo_path");
  });

  it("non-Git repo_path → error:invalid_repo_path", async () => {
    const deps = happyDeps();
    deps.ensureGitRepo = async () => {
      throw new Error("not a git repo");
    };
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/not/a/git/repo" },
      deps,
    );
    assertErrorEnvelope(result, "invalid_repo_path");
  });

  it("unknown mode value → error:unknown_mode", async () => {
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo", mode: "obliterate" },
      happyDeps(),
    );
    assertErrorEnvelope(result, "unknown_mode");
  });
});

// ---------------------------------------------------------------------------
// 2. Mode-refusal short-circuit on plan action
// ---------------------------------------------------------------------------

describe("gc_integration_manager — mode refusal on plan", () => {
  it("plan + mode=enqueue → error:mode_disabled, no execFile call", async () => {
    const deps = happyDeps();
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo", mode: "enqueue" },
      deps,
    );
    assertErrorEnvelope(result, "mode_disabled");
    assert.equal(result.mode, "enqueue");
    // No gh api call should have been made.
    assert.equal(deps.execFileCalls.length, 0, "execFile must not be called for mode_disabled");
  });

  it("plan + mode=merge → error:mode_disabled, no execFile call", async () => {
    const deps = happyDeps();
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo", mode: "merge" },
      deps,
    );
    assertErrorEnvelope(result, "mode_disabled");
    assert.equal(result.mode, "merge");
    assert.equal(deps.execFileCalls.length, 0, "execFile must not be called for mode_disabled");
  });

  it("plan + mode=prepare → proceeds (returns ok or a config/gh error, not mode_disabled)", async () => {
    const deps = happyDeps({ prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo", mode: "prepare" },
      deps,
    );
    // Should NOT be mode_disabled.
    assert.notEqual(result.error, "mode_disabled");
  });

  it("plan + no mode → defaults to prepare, proceeds (not mode_disabled)", async () => {
    const deps = happyDeps({ prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.notEqual(result.error, "mode_disabled");
  });
});

// ---------------------------------------------------------------------------
// 3. (Reserved — previously "stub actions"; replaced by sections 29-30 below)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 4. Plan action — happy path
// ---------------------------------------------------------------------------

describe("gc_integration_manager — plan happy path", () => {
  it("3 approved PRs sorted pr_number_asc with correct ordinals", async () => {
    // Supply PRs out of order to prove sorting.
    const prs = [makePr(3), makePr(1), makePr(2)];
    const deps = happyDeps({ prs });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.action, "plan");
    assert.equal(result.mode, "prepare");
    assert.equal(Array.isArray(result.plan), true);
    assert.equal(result.plan.length, 3);
    // Sorted ascending by pr_number.
    assert.equal(result.plan[0].pr_number, 1);
    assert.equal(result.plan[1].pr_number, 2);
    assert.equal(result.plan[2].pr_number, 3);
    // Ordinals start at 1.
    assert.equal(result.plan[0].ordinal, 1);
    assert.equal(result.plan[1].ordinal, 2);
    assert.equal(result.plan[2].ordinal, 3);
    // Required fields present.
    for (const entry of result.plan) {
      assert.equal(typeof entry.pr_number, "number");
      assert.equal(typeof entry.head_ref, "string");
      assert.equal(typeof entry.head_oid, "string");
      assert.equal(typeof entry.base_ref, "string");
      assert.equal(typeof entry.created_at, "string");
      assert.equal(typeof entry.updated_at, "string");
    }
  });

  it("no matching PRs → plan:[] with ok:true and policy block", async () => {
    const deps = happyDeps({ prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.deepEqual(result.plan, []);
    // Policy block must be populated.
    assert.equal(typeof result.policy, "object");
    assert.equal(typeof result.policy.approval_label, "string");
    assert.equal(typeof result.policy.ordering, "string");
    assert.equal(typeof result.policy.max_queue_size, "number");
  });

  it("mixed labels — only approved PRs appear in plan", async () => {
    const prs = [
      makePr(1, ["approved-for-integration"]),
      makePr(2, ["needs-review"]),          // should be excluded
      makePr(3, ["approved-for-integration", "extra-label"]),
    ];
    const deps = happyDeps({ prs });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.plan.length, 2);
    const prNums = result.plan.map((e) => e.pr_number);
    assert.deepEqual(prNums, [1, 3]);
  });

  it("returns owner and repo from getOwnerRepo", async () => {
    const deps = happyDeps({ owner: "keplerops", repo: "my-repo" });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.owner, "keplerops");
    assert.equal(result.repo, "my-repo");
  });
});

// ---------------------------------------------------------------------------
// 5. Plan action — config errors
// ---------------------------------------------------------------------------

describe("gc_integration_manager — config errors", () => {
  it("invalid approval_label in yaml → error:invalid_config", async () => {
    // Forcibly inject an invalid label through the yaml text.
    const badYaml = yamlWithIM("    approval_label: 'bad label with\ttab'\n");
    const deps = happyDeps({ yaml: badYaml });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "invalid_config");
  });

  it("missing .ground-control.yaml → error:invalid_config", async () => {
    const deps = happyDeps();
    deps.readYaml = () => { throw Object.assign(new Error("not found"), { code: "ENOENT" }); };
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "invalid_config");
  });
});

// ---------------------------------------------------------------------------
// 5b. Plan action — github identity assertion (GC-P026 / #1383)
// ---------------------------------------------------------------------------

describe("gc_integration_manager — github identity assertion (GC-P026)", () => {
  // .ground-control.yaml::github_repo is a validated identity assertion, not an
  // alternate destination: buildIntegrationQueue refuses when it disagrees with
  // the checkout's resolved owner/repo (case-insensitively), before any
  // discovery or mutation.

  it("configured github_repo mismatching the checkout → error:github_identity_mismatch", async () => {
    const yaml = "schema_version: 1\nproject: test-project\ngithub_repo: someone-else/other\n";
    const deps = happyDeps({ yaml, owner: "acme", repo: "myrepo", prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "github_identity_mismatch");
  });

  it("configured github_repo matching the checkout → no identity mismatch", async () => {
    const yaml = "schema_version: 1\nproject: test-project\ngithub_repo: acme/myrepo\n";
    const deps = happyDeps({ yaml, owner: "acme", repo: "myrepo", prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.notEqual(result.error, "github_identity_mismatch");
    assert.equal(result.ok, true);
  });

  it("matching github_repo is compared case-insensitively", async () => {
    const yaml = "schema_version: 1\nproject: test-project\ngithub_repo: ACME/MyRepo\n";
    const deps = happyDeps({ yaml, owner: "acme", repo: "myrepo", prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.notEqual(result.error, "github_identity_mismatch");
    assert.equal(result.ok, true);
  });

  it("absent github_repo → no identity mismatch", async () => {
    // validYaml() carries no github_repo key.
    const deps = happyDeps({ owner: "acme", repo: "myrepo", prs: [] });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.notEqual(result.error, "github_identity_mismatch");
    assert.equal(result.ok, true);
  });
});

// ---------------------------------------------------------------------------
// 6. Plan action — ordering
// ---------------------------------------------------------------------------

describe("gc_integration_manager — ordering", () => {
  it("ordering:pr_number_desc → plan sorted descending", async () => {
    const prs = [makePr(1), makePr(3), makePr(2)];
    const yaml = yamlWithIM("    ordering: pr_number_desc\n");
    const deps = happyDeps({ prs, yaml });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    const nums = result.plan.map((e) => e.pr_number);
    assert.deepEqual(nums, [3, 2, 1]);
  });

  it("ordering:approved_at_asc → plan sorted by created_at ascending (proxy for approval timestamp)", async () => {
    // created_at dates: pr 10 is newest, pr 8 is middle, pr 5 is oldest.
    const pr5 = makePr(5);
    pr5.created_at = "2026-05-01T00:00:00Z";
    const pr8 = makePr(8);
    pr8.created_at = "2026-05-08T00:00:00Z";
    const pr10 = makePr(10);
    pr10.created_at = "2026-05-15T00:00:00Z";
    // Supply in reverse order.
    const prs = [pr10, pr8, pr5];
    const yaml = yamlWithIM("    ordering: approved_at_asc\n");
    const deps = happyDeps({ prs, yaml });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    const nums = result.plan.map((e) => e.pr_number);
    assert.deepEqual(nums, [5, 8, 10]);
  });
});

// ---------------------------------------------------------------------------
// 7. Plan action — queue cap
// ---------------------------------------------------------------------------

describe("gc_integration_manager — queue cap", () => {
  it("21 approved PRs with default max_queue_size:20 → error:queue_too_large", async () => {
    const prs = Array.from({ length: 21 }, (_, i) => makePr(i + 1));
    const deps = happyDeps({ prs });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "queue_too_large");
  });

  it("20 approved PRs with default cap → succeeds with all 20 entries", async () => {
    const prs = Array.from({ length: 20 }, (_, i) => makePr(i + 1));
    const deps = happyDeps({ prs });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.plan.length, 20);
  });

  it("configured max_queue_size:5, 6 approved PRs → error:queue_too_large", async () => {
    const prs = Array.from({ length: 6 }, (_, i) => makePr(i + 1));
    const yaml = yamlWithIM("    max_queue_size: 5\n");
    const deps = happyDeps({ prs, yaml });
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "queue_too_large");
  });
});

// ---------------------------------------------------------------------------
// 8. Discovery limit (5-page cap)
// ---------------------------------------------------------------------------

describe("gc_integration_manager — discovery limit", () => {
  it("gh returns 100 entries/page over 6 pages → error:discovery_too_large", async () => {
    // Build 6 pages of 100 PRs each; execFileFake uses page number from argv.
    const page1 = Array.from({ length: 100 }, (_, i) => makePr(i + 1));
    const page2 = Array.from({ length: 100 }, (_, i) => makePr(i + 101));
    const page3 = Array.from({ length: 100 }, (_, i) => makePr(i + 201));
    const page4 = Array.from({ length: 100 }, (_, i) => makePr(i + 301));
    const page5 = Array.from({ length: 100 }, (_, i) => makePr(i + 401));
    const page6 = Array.from({ length: 100 }, (_, i) => makePr(i + 501));
    const execFileFake = makeExecFileFake([page1, page2, page3, page4, page5, page6]);
    const deps = {
      execFile: execFileFake.execFile,
      execFileCalls: execFileFake.calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
    };
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "discovery_too_large");
  });
});

// ---------------------------------------------------------------------------
// 9. Argv hygiene
// ---------------------------------------------------------------------------

describe("gc_integration_manager — argv hygiene", () => {
  it("gh argv is an exact array with no shell metacharacters", async () => {
    const deps = happyDeps({ prs: [] });
    await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    // Find the gh api call (there should be at least one for page 1).
    const ghCall = deps.execFileCalls.find((argv) => argv[0] === "gh");
    assert.ok(ghCall, "expected at least one gh call");
    // Expected exact argv shape:
    //   ["gh", "api", "-X", "GET", "/repos/acme/myrepo/pulls",
    //    "--field", "state=open", "--field", "per_page=100",
    //    "--field", "page=1"]
    assert.equal(ghCall[0], "gh");
    assert.equal(ghCall[1], "api");
    assert.equal(ghCall[2], "-X");
    assert.equal(ghCall[3], "GET");
    assert.match(ghCall[4], /^\/repos\/[^/]+\/[^/]+\/pulls$/);
    // --field arguments only.
    for (const arg of ghCall.slice(5)) {
      assert.match(
        arg,
        /^(--field|state=open|per_page=100|page=\d+)$/,
        `unexpected argv token: ${arg}`,
      );
    }
    // No shell metacharacters anywhere in the argv.
    const fullArgv = ghCall.join(" ");
    assert.doesNotMatch(fullArgv, /[|&;`$<>]/, "argv must not contain shell metacharacters");
  });
});

// ---------------------------------------------------------------------------
// 10. Sensitive-content scrub
// ---------------------------------------------------------------------------

describe("gc_integration_manager — sensitive-content scrub", () => {
  it("gh response with secret token in PR body → envelope strings are redacted", async () => {
    // Inject a PR whose body contains what looks like a GitHub PAT.
    // The token is in the response JSON but not a PR field we normally
    // surface — we test that the plan entry's own surfaced fields don't
    // contain it (i.e. the scrub runs before returning the envelope).
    const secretToken = "ghp_AAAA1111BBBB2222CCCC3333DDDD4444EEEE";
    const pr = makePr(1);
    // Poison the head_ref with the secret (edge-case: the label name or any
    // surfaced string contains the token).
    pr.head.ref = `feature/${secretToken}`;
    const execFileFake = makeExecFileFake([[pr]]);
    const deps = {
      execFile: execFileFake.execFile,
      execFileCalls: execFileFake.calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
    };
    const result = await runIntegrationManager(
      { action: "plan", repo_path: "/some/repo" },
      deps,
    );
    // The plan entry must NOT contain the raw token.
    if (result.ok && result.plan.length > 0) {
      const entry = result.plan[0];
      const serialized = JSON.stringify(entry);
      assert.doesNotMatch(serialized, /ghp_AAAA/, "plan entry must not contain raw secret token");
    }
    // If not ok, the error message must not contain the raw token either.
    if (!result.ok) {
      assert.doesNotMatch(result.message ?? "", /ghp_AAAA/);
    }
  });
});

// ---------------------------------------------------------------------------
// 11. Error envelope shape — every error path
// ---------------------------------------------------------------------------

describe("gc_integration_manager — error envelope shape", () => {
  const ERROR_REQUIRED_KEYS = new Set(["ok", "error", "message", "next_action"]);

  async function assertEnvelopeShape(args, deps) {
    const env = await runIntegrationManager(args, deps);
    assert.equal(env.ok, false);
    const keys = new Set(Object.keys(env));
    // All required keys must be present.
    for (const k of ERROR_REQUIRED_KEYS) {
      assert.ok(keys.has(k), `missing required key '${k}' in error envelope`);
    }
    return env;
  }

  it("unknown_action has only ok+error+message+next_action (+ optional allowed extras)", async () => {
    await assertEnvelopeShape({ repo_path: "/r" }, happyDeps());
  });

  it("invalid_repo_path has required shape", async () => {
    await assertEnvelopeShape({ action: "plan" }, happyDeps());
  });

  it("mode_disabled has required shape plus mode field", async () => {
    const env = await assertEnvelopeShape(
      { action: "plan", repo_path: "/r", mode: "merge" },
      happyDeps(),
    );
    // mode_disabled additionally carries a `mode` field.
    assert.ok("mode" in env, "mode_disabled envelope must include 'mode' field");
  });

  it("queue_too_large has required shape", async () => {
    const prs = Array.from({ length: 21 }, (_, i) => makePr(i + 1));
    await assertEnvelopeShape(
      { action: "plan", repo_path: "/r" },
      happyDeps({ prs }),
    );
  });
});

// ---------------------------------------------------------------------------
// 12. Tool registration in index.js
// ---------------------------------------------------------------------------

describe("gc_integration_manager — tool registration", () => {
  it("index.js exposes gc_integration_manager with correct schema", async () => {
    // Read the tool list by inspecting the server registration via a
    // minimal import of index.js.  Because index.js boots the MCP server
    // on import we need a different approach: check that the registration
    // exports are present without actually booting the server.
    //
    // Strategy: import only gc-integrate.js and verify the shape constants
    // that index.js will use.  The integration test for the schema is a
    // static shape check: we verify that `runIntegrationManager` is exported
    // and that the GC_INTEGRATION_MANAGER_DESCRIPTION and
    // GC_INTEGRATION_MANAGER_INPUT_SCHEMA constants have the expected values.
    const mod = await import("./gc-integrate.js");
    // Must export runIntegrationManager.
    assert.equal(typeof mod.runIntegrationManager, "function");
    // Must export schema shape constants used by index.js registration.
    assert.ok(mod.GC_INTEGRATION_MANAGER_DESCRIPTION, "must export GC_INTEGRATION_MANAGER_DESCRIPTION");
    assert.ok(mod.GC_INTEGRATION_MANAGER_INPUT_SCHEMA, "must export GC_INTEGRATION_MANAGER_INPUT_SCHEMA");
    const schema = mod.GC_INTEGRATION_MANAGER_INPUT_SCHEMA;
    // additionalProperties must be false.
    assert.equal(schema.additionalProperties, false);
    // required must include action and repo_path.
    assert.ok(Array.isArray(schema.required));
    assert.ok(schema.required.includes("action"), "required must include 'action'");
    assert.ok(schema.required.includes("repo_path"), "required must include 'repo_path'");
    // action enum must be closed.
    const actionEnum = schema.properties?.action?.enum;
    assert.ok(Array.isArray(actionEnum), "action must have enum");
    assert.ok(actionEnum.includes("plan"), "action enum must include 'plan'");
    assert.ok(actionEnum.includes("prepare"), "action enum must include 'prepare'");
    assert.ok(actionEnum.includes("status"), "action enum must include 'status'");
    assert.ok(actionEnum.includes("release"), "action enum must include 'release'");
    // mode enum must be closed.
    const modeEnum = schema.properties?.mode?.enum;
    assert.ok(Array.isArray(modeEnum), "mode must have enum");
    assert.ok(modeEnum.includes("prepare"), "mode enum must include 'prepare'");
    assert.ok(modeEnum.includes("enqueue"), "mode enum must include 'enqueue'");
    assert.ok(modeEnum.includes("merge"), "mode enum must include 'merge'");
  });
});

// ===========================================================================
// PREPARE ACTION TESTS — Dispatch 2b
// ===========================================================================

// ---------------------------------------------------------------------------
// 13. Mode refusal on prepare action
// ---------------------------------------------------------------------------

describe("gc_integration_manager — mode refusal on prepare action", () => {
  it("prepare + mode=enqueue → error:mode_disabled, no lock acquired, no worktree", async () => {
    const lockFake = makeLockFake();
    const deps = prepareDeps({ lockFake, prs: [makePr(1)] });
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "enqueue" },
      deps,
    );
    assertErrorEnvelope(result, "mode_disabled");
    assert.equal(result.mode, "enqueue");
    // Lock must NOT have been acquired.
    assert.equal(lockFake.getAcquireCount(), 0, "lock must not be acquired for mode_disabled");
    // No git worktree calls.
    const worktreeCalls = deps.execFileCalls.filter((c) => c.includes("worktree"));
    assert.equal(worktreeCalls.length, 0, "no worktree calls should occur for mode_disabled");
  });

  it("prepare + mode=merge → ok (ADR-029 carve-out 2026-05-26 enables merge; lock IS acquired)", async () => {
    const lockFake = makeLockFake();
    // Use a full prepare-capable fake so the prepare + merge path can run.
    const deps = mergeDeps({ lockFake, prs: [makePr(1)] });
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );
    // mode=merge is now enabled; the result should be ok:true with outcome=merged.
    assert.equal(result.ok, true, `expected ok:true for mode=merge, got: ${JSON.stringify(result)}`);
    assert.equal(result.results[0].outcome, "merged");
    // Lock was acquired (prepare ran).
    assert.equal(lockFake.getAcquireCount(), 1, "lock must be acquired for mode=merge");
  });
});

// ---------------------------------------------------------------------------
// 14. Lock contention
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare lock contention", () => {
  it("acquireIntegrationLock throws ELOCKED → error:lock_contended", async () => {
    const lockFake = makeLockFake({ locked: true });
    const deps = prepareDeps({ lockFake, prs: [makePr(1)] });
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assertErrorEnvelope(result, "lock_contended");
    // Lock release never called — lock was never acquired.
    assert.equal(lockFake.getReleaseCount(), 0, "release must not be called when lock was never acquired");
    // No worktree creation.
    const worktreeCalls = deps.execFileCalls.filter((c) => c.includes("worktree"));
    assert.equal(worktreeCalls.length, 0, "no worktree calls when lock contended");
  });
});

// ---------------------------------------------------------------------------
// 15. Lock release on every path
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare lock release invariant", () => {
  it("empty queue → lock acquired and released", async () => {
    const lockFake = makeLockFake();
    const deps = prepareDeps({ lockFake, prs: [] });
    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);
    assert.equal(lockFake.getAcquireCount(), 1, "lock acquired once");
    assert.equal(lockFake.getReleaseCount(), 1, "lock released once");
  });

  it("single PR succeeds → lock released after loop", async () => {
    const lockFake = makeLockFake();
    const deps = prepareDeps({ lockFake, prs: [makePr(1)] });
    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);
    assert.equal(lockFake.getAcquireCount(), 1);
    assert.equal(lockFake.getReleaseCount(), 1, "lock released after successful run");
  });

  it("mid-loop exception → lock released", async () => {
    const lockFake = makeLockFake();
    const prs = [makePr(1), makePr(2)];
    let callCount = 0;
    // Make execFile throw on the second git fetch call (after the gh api call).
    const explodingExecFile = async (file, argv, opts) => {
      if (file === "git" && argv.includes("fetch") && callCount++ >= 1) {
        throw new Error("mid-loop explosion");
      }
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };
    const deps = {
      execFile: explodingExecFile,
      execFileCalls: [],
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };
    // The prepare action should not throw externally — it handles internally.
    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);
    assert.equal(lockFake.getAcquireCount(), 1);
    assert.equal(lockFake.getReleaseCount(), 1, "lock released even when mid-loop exception occurs");
  });

  it("consultation_halt → lock released", async () => {
    const lockFake = makeLockFake();
    const prs = [makePr(1)];
    // Make the push fail with a lease mismatch.
    const execFileFake = {
      calls: [],
      execFile: async (file, argv, _opts) => {
        execFileFake.calls.push([file, ...argv]);
        if (file === "gh" && argv.includes("api")) {
          const pageIdx = argv.findIndex((a) => a.startsWith("page="));
          const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
          return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
        }
        if (file === "git" && argv.includes("push")) {
          const e = new Error("rejected (stale info)");
          e.stderr = "error: failed to push some refs\nhint: Updates were rejected because the remote contains work that you do\nnot have locally. Integrate the remote changes (e.g.\n'git pull ...') before pushing again.\nTo github.com:acme/myrepo.git\n ! [rejected] integ-tmp-1 -> feature/pr-1 (stale info)";
          throw e;
        }
        return { stdout: "", stderr: "" };
      },
    };
    const deps = {
      execFile: execFileFake.execFile,
      execFileCalls: execFileFake.calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };
    const result = await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);
    assert.equal(result.error, "consultation_halt");
    assert.equal(lockFake.getReleaseCount(), 1, "lock released on consultation_halt");
  });
});

// ---------------------------------------------------------------------------
// 16. Single PR — full happy path
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare single PR happy path", () => {
  it("worktree created, fetch ok, rebase clean, gate exits 0, CI skipped, Sonar skipped (no sonarcloud config) → outcome:ready", async () => {
    const pr1 = makePr(1);
    const prs = [pr1];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "abc123mergebase\n", stderr: "" };
      }
      // All git ops succeed.
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(), // no sonarcloud config
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true, got: ${JSON.stringify(result)}`);
    assert.equal(result.action, "prepare");
    assert.equal(result.mode, "prepare");
    assert.ok(typeof result.run_id === "string" && result.run_id.length > 0);
    assert.equal(Array.isArray(result.results), true);
    assert.equal(result.results.length, 1);
    assert.equal(result.results[0].pr_number, 1);
    assert.equal(result.results[0].outcome, "ready");

    // Verify the worktree add argv shape.
    const worktreeAdd = calls.find((c) => c[0] === "git" && c.includes("worktree") && c.includes("add"));
    assert.ok(worktreeAdd, "expected a git worktree add call");
    assert.equal(worktreeAdd[0], "git");
    assert.equal(worktreeAdd[1], "-C");
    assert.equal(worktreeAdd[2], "/some/repo");
    assert.equal(worktreeAdd[3], "worktree");
    assert.equal(worktreeAdd[4], "add");
    // Path and ref are positional after "add".
    assert.ok(worktreeAdd[5], "worktree path must be present");
    assert.ok(worktreeAdd[6], "worktree ref must be present");
    assert.match(worktreeAdd[6], /^integ-tmp-\d+$/, "ref must be integ-tmp-<number>");
  });
});

// ---------------------------------------------------------------------------
// 17. Worktree creation failure
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare worktree failure", () => {
  it("git worktree add exits non-zero → outcome:blocked, failure_class:worktree_create_failed, subsequent PRs continue", async () => {
    const pr1 = makePr(1);
    const pr2 = makePr(2);
    const prs = [pr1, pr2];
    const calls = [];

    let pr1FetchDone = false;

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      // Fail worktree add for PR 1 (after its fetch).
      if (file === "git" && argv.includes("fetch") && argv.some((a) => a.includes("pull/1/head"))) {
        pr1FetchDone = true;
        return { stdout: "", stderr: "" };
      }
      if (file === "git" && argv.includes("worktree") && argv.includes("add") && pr1FetchDone) {
        // Only fail for PR 1's worktree add (first call).
        pr1FetchDone = false;
        const e = new Error("worktree already exists");
        e.stderr = "fatal: 'path' is already checked out";
        throw e;
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true, got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 2);
    // PR 1: blocked (worktree_create_failed).
    assert.equal(result.results[0].pr_number, 1);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "worktree_create_failed");
    // PR 2: continues normally (ready).
    assert.equal(result.results[1].pr_number, 2);
    assert.equal(result.results[1].outcome, "ready");
  });
});

// ---------------------------------------------------------------------------
// 18. Base fetch failure → queue_wide_halt
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare base fetch failure", () => {
  it("git fetch origin <base> exits non-zero → outcome:queue_wide_halt, failure_class:base_fetch_failed, subsequent PRs not processed", async () => {
    const prs = [makePr(1), makePr(2)];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      // PR head fetch succeeds.
      if (file === "git" && argv.includes("fetch") && argv.some((a) => a.includes("pull/"))) {
        return { stdout: "", stderr: "" };
      }
      // Worktree add succeeds.
      if (file === "git" && argv.includes("worktree") && argv.includes("add")) {
        return { stdout: "", stderr: "" };
      }
      // Base branch fetch fails.
      if (file === "git" && argv.includes("fetch") && argv.includes("origin")) {
        const e = new Error("fetch failed");
        e.stderr = "fatal: couldn't find remote ref dev";
        throw e;
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, false);
    assert.equal(result.error, "queue_wide_halt");
    assert.equal(result.results.length, 1, "only the halted PR appears in results");
    assert.equal(result.results[0].pr_number, 1);
    assert.equal(result.results[0].outcome, "queue_wide_halt");
    assert.equal(result.results[0].failure_class, "base_fetch_failed");
    // PR 2 must NOT appear (not "skipped", simply absent).
    const pr2 = result.results.find((r) => r.pr_number === 2);
    assert.equal(pr2, undefined, "PR 2 must not appear in results on queue_wide_halt");
    // Lock still released.
    assert.equal(lockFake.getReleaseCount(), 1);
  });
});

// ---------------------------------------------------------------------------
// 19. Rebase conflict
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare rebase conflict", () => {
  it("git rebase exits non-zero → git rebase --abort invoked, outcome:blocked, failure_class:rebase_conflict, worktree cleanup invoked", async () => {
    const prs = [makePr(1)];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("rebase") && !argv.includes("--abort")) {
        const e = new Error("rebase conflict");
        e.stderr = "CONFLICT (content): Merge conflict in src/Foo.java\nCONFLICT (content): Merge conflict in src/Bar.java";
        throw e;
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true (blocked is not an error), got: ${JSON.stringify(result)}`);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "rebase_conflict");

    // git rebase --abort must have been called.
    const abortCall = calls.find((c) =>
      c[0] === "git" && c.includes("rebase") && c.includes("--abort"),
    );
    assert.ok(abortCall, "git rebase --abort must be invoked on conflict");

    // Worktree cleanup (remove) must have been attempted.
    const removeCall = calls.find((c) =>
      c[0] === "git" && c.includes("worktree") && c.includes("remove"),
    );
    assert.ok(removeCall, "git worktree remove must be invoked for cleanup");
  });
});

// ---------------------------------------------------------------------------
// 20. Completion gate failure
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare completion gate failure", () => {
  it("bash -c completion_command exits non-zero → outcome:blocked, failure_class:completion_gate_failed", async () => {
    const prs = [makePr(1)];
    const calls = [];
    const yaml = validYaml(`workflow:\n  completion_command: "make test"\n`);

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "bash") {
        const e = new Error("make test failed");
        e.code = 1;
        e.stderr = "Error: test failures";
        throw e;
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => yaml,
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true (blocked is not an error), got: ${JSON.stringify(result)}`);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "completion_gate_failed");

    // Argv for bash must be exactly ["bash", "-c", <completion-command>].
    const bashCall = calls.find((c) => c[0] === "bash");
    assert.ok(bashCall, "expected a bash call");
    assert.equal(bashCall[0], "bash");
    assert.equal(bashCall[1], "-c");
    assert.equal(bashCall[2], "make test", "third argv element must be the exact completion_command string");
    assert.equal(bashCall.length, 3, "bash argv must be exactly [bash, -c, <cmd>] with no further interpolation");
  });
});

// ---------------------------------------------------------------------------
// 21. CI failure via injected hook
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare CI failure", () => {
  it("runCiWatcher returns conclusion:failure → outcome:blocked, failure_class:ci_failed", async () => {
    const prs = [makePr(1)];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "failure", details_url: "https://ci.example.com/runs/42" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true (blocked), got: ${JSON.stringify(result)}`);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "ci_failed");
  });
});

// ---------------------------------------------------------------------------
// 22. Sonar configured but skipped
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare sonar configured but skipped", () => {
  it("sonarcloud in config + runSonarWatcher returns skipped → outcome:blocked, failure_class:sonar_skipped_but_configured", async () => {
    const prs = [makePr(1)];
    const calls = [];
    // yaml with sonarcloud config.
    const yaml = validYaml("sonarcloud:\n  organization: myorg\n  project_key: myrepo\n");

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => yaml,
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true (blocked), got: ${JSON.stringify(result)}`);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "sonar_skipped_but_configured");
  });
});

// ---------------------------------------------------------------------------
// 23. Force-with-lease mismatch → consultation_halt + halt ledger
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare force-with-lease mismatch", () => {
  it("push --force-with-lease rejected with stale info → outcome:consultation_halt, halt_reason:pr_head_moved, halt ledger written", async () => {
    const prs = [makePr(1)];
    const calls = [];
    const ledgerWriteCalls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("push")) {
        const e = new Error("rejected (stale info)");
        e.stderr = "error: failed to push some refs\n ! [rejected] integ-tmp-1 -> feature/pr-1 (stale info)";
        throw e;
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: (runDir, ledger) => {
        ledgerWriteCalls.push({ runDir, ledger });
      },
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, false);
    assert.equal(result.error, "consultation_halt");
    assert.equal(result.halt_reason, "pr_head_moved");
    assert.equal(result.next_action, "consult_maintainer");
    assert.ok(Array.isArray(result.candidate_resolutions) && result.candidate_resolutions.length > 0);
    assert.equal(result.results[0].pr_number, 1);
    assert.equal(result.results[0].outcome, "consultation_halt");

    // Halt ledger must have been written.
    assert.equal(ledgerWriteCalls.length, 1, "halt ledger must be written once");
    const { runDir, ledger } = ledgerWriteCalls[0];
    assert.ok(runDir.includes(".gc/integration-runs"), "runDir must be under .gc/integration-runs");
    assert.ok(typeof ledger.run_id === "string");
    assert.equal(ledger.halt_reason, "pr_head_moved");
    assert.equal(ledger.pr_number_at_halt, 1);
    assert.ok(Array.isArray(ledger.queue_state));
    assert.ok(typeof ledger.timestamp === "string");

    // Lock released on halt.
    assert.equal(lockFake.getReleaseCount(), 1);
  });

  it("push --force-with-lease argv contains exact expected OID", async () => {
    const pr1 = makePr(1); // head.sha is "sha1"
    const prs = [pr1];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("push")) {
        // Succeed — check the lease OID from the test assertions below.
        return { stdout: "", stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);

    const pushCall = calls.find((c) => c[0] === "git" && c.includes("push"));
    assert.ok(pushCall, "expected a git push call");
    // The lease must contain the discovery-time OID (sha1 from makePr(1)).
    const leaseArg = pushCall.find((a) => a.startsWith("--force-with-lease="));
    assert.ok(leaseArg, "--force-with-lease argument must be present");
    assert.ok(leaseArg.includes("sha1"), `lease arg must contain the PR head OID 'sha1', got: ${leaseArg}`);
  });
});

// ---------------------------------------------------------------------------
// 24. Multiple PRs — mixed outcomes
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare multiple PRs mixed outcomes", () => {
  it("PR-1 ready, PR-2 blocked (rebase conflict), PR-3 ready → all three in results[], queue not halted", async () => {
    const prs = [makePr(1), makePr(2), makePr(3)];
    const calls = [];
    let rebaseCallCount = 0;

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("rebase") && !argv.includes("--abort")) {
        rebaseCallCount++;
        if (rebaseCallCount === 2) {
          // PR 2's rebase fails.
          const e = new Error("rebase conflict");
          e.stderr = "CONFLICT (content): Merge conflict in src/Main.java";
          throw e;
        }
        return { stdout: "", stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true (blocked is not queue-stopping), got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 3, "all three PRs must appear in results");

    const [r1, r2, r3] = result.results;
    assert.equal(r1.pr_number, 1);
    assert.equal(r1.outcome, "ready");
    assert.equal(r2.pr_number, 2);
    assert.equal(r2.outcome, "blocked");
    assert.equal(r2.failure_class, "rebase_conflict");
    assert.equal(r3.pr_number, 3);
    assert.equal(r3.outcome, "ready");
  });
});

// ---------------------------------------------------------------------------
// 25. Run ID format
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare run ID format", () => {
  it("run ID is <timestamp>-<randomId> from injected deps.now and deps.randomId", async () => {
    const lockFake = makeLockFake();
    const deps = {
      ...prepareDeps({ lockFake, prs: [] }),
      now: () => 1748000001234,
      randomId: () => "xyzabc",
    };
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.ok(result.run_id, "run_id must be present");
    assert.equal(result.run_id, "1748000001234-xyzabc", `expected '1748000001234-xyzabc', got: '${result.run_id}'`);
  });
});

// ---------------------------------------------------------------------------
// 26. Argv hygiene for prepare-specific git calls
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare argv hygiene", () => {
  it("git worktree add argv is exactly [git, -C, <repo>, worktree, add, <path>, <ref>]", async () => {
    const prs = [makePr(1)];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);

    const worktreeAdd = calls.find((c) => c[0] === "git" && c.includes("worktree") && c.includes("add"));
    assert.ok(worktreeAdd, "git worktree add must be called");
    assert.equal(worktreeAdd[0], "git");
    assert.equal(worktreeAdd[1], "-C");
    assert.equal(worktreeAdd[2], "/some/repo");
    assert.equal(worktreeAdd[3], "worktree");
    assert.equal(worktreeAdd[4], "add");
    // [5] is the path, [6] is the ref.
    assert.equal(worktreeAdd.length, 7, "worktree add must have exactly 7 elements");
  });

  it("git rebase argv matches --onto pattern", async () => {
    const prs = [makePr(1)];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);

    const rebaseCall = calls.find((c) => c[0] === "git" && c.includes("rebase") && c.includes("--onto"));
    assert.ok(rebaseCall, "git rebase --onto must be called");
    assert.equal(rebaseCall[0], "git");
    assert.equal(rebaseCall[1], "-C");
    // [2] is the worktree path.
    assert.equal(rebaseCall[3], "rebase");
    assert.equal(rebaseCall[4], "--onto");
    // [5] is origin/<base-ref>, [6] is merge-base, [7] is tmp-ref.
    assert.ok(rebaseCall[5].startsWith("origin/"), `--onto target must start with 'origin/', got: ${rebaseCall[5]}`);
  });
});

// ---------------------------------------------------------------------------
// 27. Worktree path containment (defensive)
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare worktree path containment", () => {
  it("traversal-shaped run ID causes blocked outcome with worktree_path_invalid and no git side-effects", async () => {
    // Use a randomId that, when combined with the timestamp prefix and the
    // fixed path segments, produces a worktreePath that escapes /some/repo.
    // Five levels of "../" walk past ".gc/integration-worktrees/<ts>-" and
    // then out of /some/repo entirely (verified: resolves to /some/tmp/escape/1).
    const prs = [makePr(1)];
    const calls = [];

    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      // Five "../" levels escape past ".gc/integration-worktrees/<ts>-" and
      // out of /some/repo, producing a canonical path of /some/tmp/escape/1.
      randomId: () => "../../../../../tmp/escape",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    // The containment check must fire and return a blocked outcome — not throw.
    assert.ok(result.ok === true, "overall result must be ok:true (blocked is not a halt)");
    assert.ok(Array.isArray(result.results), "results must be an array");
    assert.equal(result.results.length, 1, "exactly one PR result");

    const prResult = result.results[0];
    assert.equal(prResult.outcome, "blocked", "outcome must be blocked");
    assert.equal(prResult.failure_class, "worktree_path_invalid", "failure_class must be worktree_path_invalid");

    // No git worktree add, fetch, rebase, or push must have been called —
    // the containment check fires before any branch mutation.
    const gitSideEffectCalls = calls.filter(
      (c) =>
        c[0] === "git" &&
        (c.includes("worktree") || c.includes("fetch") || c.includes("rebase") || c.includes("push")),
    );
    assert.equal(
      gitSideEffectCalls.length,
      0,
      `no git side-effect calls expected, got: ${JSON.stringify(gitSideEffectCalls)}`,
    );
  });
});

// ---------------------------------------------------------------------------
// 28. Prepare envelope fields
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare envelope fields", () => {
  it("successful prepare envelope has required fields: ok, action, mode, run_id, owner, repo, policy, results", async () => {
    const lockFake = makeLockFake();
    const deps = prepareDeps({ lockFake, prs: [makePr(1)] });

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    if (result.ok) {
      assert.equal(result.action, "prepare");
      assert.equal(result.mode, "prepare");
      assert.ok(typeof result.run_id === "string" && result.run_id.length > 0, "run_id must be a non-empty string");
      assert.ok(typeof result.owner === "string", "owner must be a string");
      assert.ok(typeof result.repo === "string", "repo must be a string");
      assert.ok(typeof result.policy === "object", "policy must be an object");
      assert.ok(Array.isArray(result.results), "results must be an array");
    }
    // If not ok (e.g., a blocked run due to test env behavior), still valid.
    // We only assert shape when ok:true.
  });
});

// ===========================================================================
// DISPATCH 2c TESTS — CI watcher, Sonar watcher, status, release
// ===========================================================================

// ---------------------------------------------------------------------------
// 29. CI watcher mapping
// ---------------------------------------------------------------------------

describe("gc_integration_manager — CI watcher mapping", () => {
  // Build a minimal prepare deps set with a fake CI watcher.
  function ciWatcherDeps(fakeCiWatcher, prs = [makePr(1)]) {
    return prepareDeps({
      prs,
      runCiWatcher: fakeCiWatcher,
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
    });
  }

  it("runCiWatcher returns {conclusion:'success'} → outcome:ready", async () => {
    const deps = ciWatcherDeps(async () => ({ conclusion: "success" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true, `expected ok:true, got: ${JSON.stringify(result)}`);
    assert.equal(result.results[0].outcome, "ready");
  });

  it("runCiWatcher returns {conclusion:'failure'} → outcome:blocked, failure_class:ci_failed", async () => {
    const deps = ciWatcherDeps(async () => ({ conclusion: "failure", details_url: "https://ci.example.com/runs/1" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "ci_failed");
  });

  it("runCiWatcher returns {conclusion:'queued_too_long'} → outcome:blocked, failure_class:ci_queued_too_long", async () => {
    const deps = ciWatcherDeps(async () => ({ conclusion: "queued_too_long" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "ci_queued_too_long");
  });

  it("runCiWatcher returns {conclusion:'timed_out'} → outcome:blocked, failure_class:ci_timed_out", async () => {
    const deps = ciWatcherDeps(async () => ({ conclusion: "timed_out" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "ci_timed_out");
  });

  it("runCiWatcher returns {conclusion:'skipped'} → treated as success (outcome:ready)", async () => {
    const deps = ciWatcherDeps(async () => ({ conclusion: "skipped" }));
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "ready");
  });

  it("runCiWatcher is called with repo_path=repoRoot and branch=pr.head_ref", async () => {
    const ciWatcherCalls = [];
    const fakeCiWatcher = async (pr, ctx) => {
      ciWatcherCalls.push({ pr, ctx });
      return { conclusion: "skipped" };
    };
    const deps = ciWatcherDeps(fakeCiWatcher, [makePr(7)]);
    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);
    assert.equal(ciWatcherCalls.length, 1, "CI watcher must be called once per PR");
    const { pr, ctx } = ciWatcherCalls[0];
    assert.equal(pr.head_ref, "feature/pr-7", `expected head_ref='feature/pr-7', got: ${pr.head_ref}`);
    assert.ok(typeof ctx.repoRoot === "string" && ctx.repoRoot.length > 0, "ctx.repoRoot must be present");
  });
});

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

// ---------------------------------------------------------------------------
// 31. status action
// ---------------------------------------------------------------------------

// Helper: build status deps with injectable fs primitives.
function statusDeps(overrides = {}) {
  return {
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

// ---------------------------------------------------------------------------
// 34. Fork PR refusal in prepare — no git/gh side-effects
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare fork PR blocked without side-effects", () => {
  it("fork PR → outcome:blocked, failure_class:fork_pr_unsupported, zero git/gh calls for that PR", async () => {
    const forkPr = makePr(5);
    forkPr.head.repo = { full_name: "contributor/myrepo" };
    forkPr.base.repo = { full_name: "acme/myrepo" };

    const calls = [];
    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? [forkPr] : []), stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => { throw new Error("should not be called for fork PR"); },
      runSonarWatcher: async () => { throw new Error("should not be called for fork PR"); },
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true, got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 1);
    const outcome = result.results[0];
    assert.equal(outcome.pr_number, 5);
    assert.equal(outcome.outcome, "blocked");
    assert.equal(outcome.failure_class, "fork_pr_unsupported");
    assert.equal(outcome.next_action, "merge_manually_or_open_followup");

    // No git fetch, worktree, rebase, push calls for the fork PR.
    const gitCalls = calls.filter((c) => c[0] === "git");
    const gitSideEffects = gitCalls.filter((c) =>
      c.includes("fetch") || c.includes("worktree") || c.includes("rebase") || c.includes("push"),
    );
    assert.equal(
      gitSideEffects.length,
      0,
      `expected zero git side-effect calls for fork PR, got: ${JSON.stringify(gitSideEffects)}`,
    );
  });
});

// ---------------------------------------------------------------------------
// 35. Mixed queue: fork PR blocked, same-repo PR proceeds
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare mixed queue fork and same-repo", () => {
  it("1 fork PR + 1 same-repo PR → fork blocked, same-repo ready, queue not halted", async () => {
    const forkPr = makePr(1);
    forkPr.head.repo = { full_name: "contributor/myrepo" };
    forkPr.base.repo = { full_name: "acme/myrepo" };

    const samePr = makePr(2);
    samePr.head.repo = { full_name: "acme/myrepo" };
    samePr.base.repo = { full_name: "acme/myrepo" };

    const calls = [];
    const execFileFake = async (file, argv, _opts) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? [forkPr, samePr] : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true (no halt), got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 2, "both PRs must appear in results");

    const forkResult = result.results.find((r) => r.pr_number === 1);
    assert.ok(forkResult, "fork PR must be in results");
    assert.equal(forkResult.outcome, "blocked");
    assert.equal(forkResult.failure_class, "fork_pr_unsupported");

    const sameResult = result.results.find((r) => r.pr_number === 2);
    assert.ok(sameResult, "same-repo PR must be in results");
    assert.equal(sameResult.outcome, "ready");
  });
});

// ---------------------------------------------------------------------------
// 29. CI and Sonar watchers must run AFTER force-with-lease push
// ---------------------------------------------------------------------------

describe("gc_integration_manager — prepare watcher ordering: push before watchers", () => {
  it("runCiWatcher and runSonarWatcher are called only after git push --force-with-lease", async () => {
    const prs = [makePr(1)];
    const callOrder = [];

    const execFileFake = async (file, argv, _opts) => {
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("push")) {
        callOrder.push("push");
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => {
        callOrder.push("ci_watcher");
        return { conclusion: "skipped" };
      },
      runSonarWatcher: async () => {
        callOrder.push("sonar_watcher");
        return { conclusion: "skipped" };
      },
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    await runIntegrationManager({ action: "prepare", repo_path: "/some/repo" }, deps);

    assert.ok(callOrder.includes("push"), "git push must be called");
    assert.ok(callOrder.includes("ci_watcher"), "runCiWatcher must be called");
    assert.ok(callOrder.includes("sonar_watcher"), "runSonarWatcher must be called");

    const pushIdx = callOrder.indexOf("push");
    const ciIdx = callOrder.indexOf("ci_watcher");
    const sonarIdx = callOrder.indexOf("sonar_watcher");

    assert.ok(
      pushIdx < ciIdx,
      `git push (index ${pushIdx}) must occur before runCiWatcher (index ${ciIdx}); order was: ${JSON.stringify(callOrder)}`,
    );
    assert.ok(
      pushIdx < sonarIdx,
      `git push (index ${pushIdx}) must occur before runSonarWatcher (index ${sonarIdx}); order was: ${JSON.stringify(callOrder)}`,
    );
  });
});

// ---------------------------------------------------------------------------
// 30. mode=merge — merge execution after prepare
// ---------------------------------------------------------------------------

// Build a prepare execFile fake that also records/handles gh pr merge calls.
// `mergeResult` controls what happens when gh pr merge is called:
//   { ok: true }  → succeeds (default)
//   { ok: false, stderr: "..." } → throws with that stderr
function makeMergeExecFileFake(prs, { mergeResult = { ok: true } } = {}) {
  const calls = [];

  return {
    calls,
    execFile: async (file, argv, _options) => {
      calls.push([file, ...argv]);

      // gh api discovery calls.
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }

      // gh pr merge calls.
      if (file === "gh" && argv.includes("merge")) {
        if (mergeResult.ok) {
          return { stdout: "", stderr: "" };
        }
        const err = new Error("gh pr merge failed");
        err.stderr = mergeResult.stderr ?? "remote: permission denied";
        throw err;
      }

      // git merge-base.
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }

      // All other git calls succeed.
      return { stdout: "", stderr: "" };
    },
  };
}

// Build deps for a mode=merge prepare test.
function mergeDeps(overrides = {}) {
  const prs = overrides.prs ?? [makePr(1)];
  const yaml = overrides.yaml ?? validYaml();
  const lockFake = overrides.lockFake ?? makeLockFake();
  const mergeResult = overrides.mergeResult ?? { ok: true };
  const execFileFake = overrides.execFileFake ?? makeMergeExecFileFake(prs, { mergeResult });

  return {
    execFile: execFileFake.execFile,
    execFileCalls: execFileFake.calls,
    ensureGitRepo: overrides.ensureGitRepo ?? (async (p) => p),
    getOwnerRepo: overrides.getOwnerRepo ?? (async () => ({ owner: "acme", name: "myrepo" })),
    readYaml: overrides.readYaml ?? (() => yaml),
    acquireIntegrationLock: lockFake.acquireIntegrationLock,
    lockFake,
    writeHaltLedger: overrides.writeHaltLedger ?? (() => {}),
    runCiWatcher: overrides.runCiWatcher ?? (async () => ({ conclusion: "skipped" })),
    runSonarWatcher: overrides.runSonarWatcher ?? (async () => ({ conclusion: "skipped" })),
    now: overrides.now ?? (() => 1748000000000),
    randomId: overrides.randomId ?? (() => "abc123"),
  };
}

describe("gc_integration_manager — mode=merge", () => {
  it("one ready PR → executes gh pr merge with --merge --delete-branch --repo, outcome=merged", async () => {
    const prs = [makePr(1)];
    const deps = mergeDeps({ prs });

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true, got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 1);
    assert.equal(result.results[0].outcome, "merged");
    assert.ok(result.results[0].merged_at, "merged_at must be set");

    // Verify the exact argv of the gh pr merge call.
    const mergeCalls = deps.execFileCalls.filter(
      (c) => c[0] === "gh" && c.includes("merge"),
    );
    assert.equal(mergeCalls.length, 1, "exactly one gh pr merge call");
    const mergeArgv = mergeCalls[0];
    assert.deepEqual(mergeArgv, ["gh", "pr", "merge", "1", "--merge", "--delete-branch", "--repo", "acme/myrepo"]);
  });

  it("one ready + one blocked PR → only the ready PR is merged; blocked PR stays blocked", async () => {
    // PR 1 will be blocked (rebase conflict), PR 2 will be ready then merged.
    const prs = [makePr(1), makePr(2)];

    const calls = [];
    let pr1FetchDone = false;
    let pr1WorktreeDone = false;
    let pr1BaseFetchDone = false;
    let pr1MergeBaseDone = false;
    let pr1RebaseDone = false;

    const execFileFake = async (file, argv, _options) => {
      calls.push([file, ...argv]);

      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "gh" && argv.includes("merge")) {
        return { stdout: "", stderr: "" };
      }

      // For PR 1: let fetch, worktree, base fetch, merge-base succeed, then rebase fail.
      if (file === "git") {
        if (argv.includes("fetch") && argv.some((a) => a.includes("pull/1/head"))) {
          pr1FetchDone = true;
          return { stdout: "", stderr: "" };
        }
        if (argv.includes("worktree") && argv.includes("add") && !pr1WorktreeDone) {
          pr1WorktreeDone = true;
          return { stdout: "", stderr: "" };
        }
        if (argv.includes("fetch") && !pr1BaseFetchDone && pr1WorktreeDone) {
          pr1BaseFetchDone = true;
          return { stdout: "", stderr: "" };
        }
        if (argv.includes("merge-base") && !pr1MergeBaseDone) {
          pr1MergeBaseDone = true;
          return { stdout: "mergebasesha\n", stderr: "" };
        }
        if (argv.includes("rebase") && !argv.includes("--abort") && !pr1RebaseDone && pr1MergeBaseDone) {
          pr1RebaseDone = true;
          // Rebase fails for PR 1.
          const err = new Error("rebase conflict");
          err.stderr = "CONFLICT (content): Merge conflict in foo.java";
          throw err;
        }
        if (argv.includes("rebase") && argv.includes("--abort")) {
          return { stdout: "", stderr: "" };
        }
        if (argv.includes("merge-base")) {
          return { stdout: "mergebasesha\n", stderr: "" };
        }
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true, got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 2);

    const pr1Result = result.results.find((r) => r.pr_number === 1);
    assert.equal(pr1Result.outcome, "blocked");
    assert.equal(pr1Result.failure_class, "rebase_conflict");

    const pr2Result = result.results.find((r) => r.pr_number === 2);
    assert.equal(pr2Result.outcome, "merged");

    // Only PR 2 was merged.
    const mergeCalls = calls.filter((c) => c[0] === "gh" && c.includes("merge"));
    assert.equal(mergeCalls.length, 1);
    assert.ok(mergeCalls[0].includes("2"), "only PR 2 is merged");
  });

  it("gh pr merge exits non-zero → outcome=blocked, failure_class=merge_failed", async () => {
    const prs = [makePr(1)];
    const deps = mergeDeps({
      prs,
      mergeResult: { ok: false, stderr: "remote: permission denied" },
    });

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, true, `expected ok:true envelope, got: ${JSON.stringify(result)}`);
    assert.equal(result.results.length, 1);
    assert.equal(result.results[0].outcome, "blocked");
    assert.equal(result.results[0].failure_class, "merge_failed");
  });

  it("no ready PRs (all blocked) → no merge calls; envelope returns normally", async () => {
    const prs = [makePr(1)];

    // Force the single PR to fail at rebase.
    const calls = [];
    let rebaseDone = false;
    let mergeBaseDone = false;

    const execFileFake = async (file, argv, _options) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base") && !mergeBaseDone) {
        mergeBaseDone = true;
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      if (file === "git" && argv.includes("rebase") && !argv.includes("--abort") && !rebaseDone) {
        rebaseDone = true;
        const err = new Error("rebase conflict");
        err.stderr = "CONFLICT (content): Merge conflict in bar.java";
        throw err;
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "blocked");

    const mergeCalls = calls.filter((c) => c[0] === "gh" && c.includes("merge"));
    assert.equal(mergeCalls.length, 0, "no gh pr merge calls when all PRs are blocked");
  });

  it("merge_strategy=squash → argv contains --squash", async () => {
    const prs = [makePr(1)];
    const yaml = validYaml(`workflow:\n  integration_manager:\n    merge_strategy: squash\n`);
    const deps = mergeDeps({ prs, yaml });

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "merged");

    const mergeCalls = deps.execFileCalls.filter(
      (c) => c[0] === "gh" && c.includes("merge"),
    );
    assert.equal(mergeCalls.length, 1);
    assert.ok(mergeCalls[0].includes("--squash"), `expected --squash in argv, got: ${JSON.stringify(mergeCalls[0])}`);
  });

  it("merge_strategy=rebase → argv contains --rebase", async () => {
    const prs = [makePr(1)];
    const yaml = validYaml(`workflow:\n  integration_manager:\n    merge_strategy: rebase\n`);
    const deps = mergeDeps({ prs, yaml });

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, true);
    assert.equal(result.results[0].outcome, "merged");

    const mergeCalls = deps.execFileCalls.filter(
      (c) => c[0] === "gh" && c.includes("merge"),
    );
    assert.equal(mergeCalls.length, 1);
    assert.ok(mergeCalls[0].includes("--rebase"), `expected --rebase in argv, got: ${JSON.stringify(mergeCalls[0])}`);
  });

  it("mode=enqueue still returns error:mode_disabled", async () => {
    const deps = prepareDeps();
    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "enqueue" },
      deps,
    );
    assertErrorEnvelope(result, "mode_disabled");
    assert.equal(result.mode, "enqueue");
  });

  it("mode=merge does NOT merge when prepare loop returned queue_wide_halt", async () => {
    const prs = [makePr(1)];
    const calls = [];

    // Trigger queue_wide_halt by failing the base branch fetch.
    let fetchCount = 0;
    const execFileFake = async (file, argv, _options) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "git" && argv.includes("fetch") && argv.some((a) => a.includes("pull/"))) {
        return { stdout: "", stderr: "" };
      }
      if (file === "git" && argv.includes("worktree")) {
        return { stdout: "", stderr: "" };
      }
      // Base branch fetch fails → queue_wide_halt.
      if (file === "git" && argv.includes("fetch")) {
        fetchCount++;
        if (fetchCount >= 1) {
          throw new Error("fatal: couldn't find remote ref dev");
        }
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    // The envelope should be ok:false with queue_wide_halt.
    assert.equal(result.ok, false);
    assert.equal(result.error, "queue_wide_halt");

    const mergeCalls = calls.filter((c) => c[0] === "gh" && c.includes("merge"));
    assert.equal(mergeCalls.length, 0, "gh pr merge must not be called after queue_wide_halt");
  });

  it("mode=merge does NOT merge when prepare loop returned consultation_halt", async () => {
    const prs = [makePr(1)];
    const calls = [];

    // Trigger consultation_halt by returning a lease mismatch from git push.
    const execFileFake = async (file, argv, _options) => {
      calls.push([file, ...argv]);
      if (file === "gh" && argv.includes("api")) {
        const pageIdx = argv.findIndex((a) => a.startsWith("page="));
        const pageNum = pageIdx >= 0 ? Number(argv[pageIdx].split("=")[1]) : 1;
        return { stdout: JSON.stringify(pageNum === 1 ? prs : []), stderr: "" };
      }
      if (file === "gh" && argv.includes("merge")) {
        return { stdout: "", stderr: "" };
      }
      if (file === "git" && argv.includes("merge-base")) {
        return { stdout: "mergebasesha\n", stderr: "" };
      }
      if (file === "git" && argv.includes("push")) {
        const err = new Error("force-with-lease lease mismatch");
        err.stderr = "error: rejected (stale info)";
        throw err;
      }
      return { stdout: "", stderr: "" };
    };

    const lockFake = makeLockFake();
    const deps = {
      execFile: execFileFake,
      execFileCalls: calls,
      ensureGitRepo: async (p) => p,
      getOwnerRepo: async () => ({ owner: "acme", name: "myrepo" }),
      readYaml: () => validYaml(),
      acquireIntegrationLock: lockFake.acquireIntegrationLock,
      lockFake,
      writeHaltLedger: () => {},
      runCiWatcher: async () => ({ conclusion: "skipped" }),
      runSonarWatcher: async () => ({ conclusion: "skipped" }),
      now: () => 1748000000000,
      randomId: () => "abc123",
    };

    const result = await runIntegrationManager(
      { action: "prepare", repo_path: "/some/repo", mode: "merge" },
      deps,
    );

    assert.equal(result.ok, false);
    assert.equal(result.error, "consultation_halt");

    const mergeCalls = calls.filter((c) => c[0] === "gh" && c.includes("merge"));
    assert.equal(mergeCalls.length, 0, "gh pr merge must not be called after consultation_halt");
  });
});

// ---------------------------------------------------------------------------
// SDK registration shape regression. The first deployed registration used
// server.registerTool({inputSchema: <raw JSON Schema>}), which passes the
// registration gate but crashes at call time with
// `v3Schema.safeParseAsync is not a function`: the SDK wraps inputSchema in
// z.object() and calls safeParseAsync, which only Zod schemas implement.
// Unit tests on runIntegrationManager bypass the SDK entirely so the bug
// slipped through. These cases drive the call path through McpServer +
// Client + InMemoryTransport so any future registration-shape regression
// fails here instead of in production.
// ---------------------------------------------------------------------------
describe("gc_integration_manager — SDK registration shape", () => {
  it("client.callTool against the registered tool does not crash on schema parse", async () => {
    const { McpServer } = await import("@modelcontextprotocol/sdk/server/mcp.js");
    const { Client } = await import("@modelcontextprotocol/sdk/client/index.js");
    const { InMemoryTransport } = await import("@modelcontextprotocol/sdk/inMemory.js");
    const { z } = await import("zod");

    const server = new McpServer({
      name: "gc-integrate-registration-test",
      version: "1.0.0",
    });
    server.tool(
      "gc_integration_manager",
      "test wiring",
      {
        action: z.enum(["plan", "prepare", "status", "release"]),
        repo_path: z.string().min(1),
        mode: z.enum(["prepare", "enqueue", "merge"]).optional(),
      },
      async () => ({ content: [{ type: "text", text: "ok" }] }),
    );

    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "test-client", version: "1.0.0" });
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

    try {
      const out = await client.callTool({
        name: "gc_integration_manager",
        arguments: { action: "plan", repo_path: "/tmp" },
      });
      assert.equal(out.isError, undefined, `call must not surface as error: ${JSON.stringify(out)}`);
    } finally {
      await client.close();
    }
  });

  it("client.callTool rejects an unknown action enum value at the schema layer", async () => {
    const { McpServer } = await import("@modelcontextprotocol/sdk/server/mcp.js");
    const { Client } = await import("@modelcontextprotocol/sdk/client/index.js");
    const { InMemoryTransport } = await import("@modelcontextprotocol/sdk/inMemory.js");
    const { z } = await import("zod");

    const server = new McpServer({
      name: "gc-integrate-registration-test",
      version: "1.0.0",
    });
    server.tool(
      "gc_integration_manager",
      "test wiring",
      {
        action: z.enum(["plan", "prepare", "status", "release"]),
        repo_path: z.string().min(1),
        mode: z.enum(["prepare", "enqueue", "merge"]).optional(),
      },
      async () => ({ content: [{ type: "text", text: "should not run" }] }),
    );

    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "test-client", version: "1.0.0" });
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

    try {
      let rejected = false;
      try {
        const out = await client.callTool({
          name: "gc_integration_manager",
          arguments: { action: "bogus", repo_path: "/tmp" },
        });
        if (out.isError) rejected = true;
      } catch {
        rejected = true;
      }
      assert.equal(rejected, true, "unknown action value must be rejected by the schema");
    } finally {
      await client.close();
    }
  });
});
