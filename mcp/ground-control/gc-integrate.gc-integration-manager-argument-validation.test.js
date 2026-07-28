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
