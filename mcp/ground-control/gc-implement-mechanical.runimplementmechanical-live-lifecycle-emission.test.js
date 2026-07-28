// Split from gc-implement-mechanical.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { runImplementMechanical } from "./gc-implement-mechanical.js";
import { requestedRequirementUidAuthorization } from "./lib.js";

const RECORD_ID = "c".repeat(32);

function context() {
  return {
    status: "ok",
    project: "ground-control",
    workflow: { base_branch: "dev", completion_command: "make check" },
  };
}

function baseDeps(overrides = {}) {
  const deps = {
    authorizeRepo: async (path) => ({ ok: true, repoRoot: path }),
    getContext: async () => context(),
    prepareBranch: async () => ({
      ok: true,
      repo_path: "/repo",
      branch: "1426-script-phases",
    }),
    getIssueThread: async () => ({
      ok: true,
      title: "Script phases",
      body: "## Requirements\n- GC-O007\n",
      labels: ["enhancement"],
      comments: [],
      url: "https://github.test/issues/1426",
      hash: "thread-hash",
    }),
    getRequirement: async (uid) => ({
      id: `id-${uid}`,
      uid,
      title: "Requirement",
      statement: "The system shall work.",
      status: "DRAFT",
      wave: 1,
    }),
    getTraceabilityByArtifact: async () => [{ id: "link-1" }],
    markPickedUp: async () => ({ ok: true, comment_url: "https://github.test/pickup" }),
    assertQuality: async () => ({ ok: true, passed_count: 2 }),
    synchronize: async () => ({ ok: true, status: "complete", recordId: RECORD_ID }),
    watchCi: async () => ({ ok: true, conclusion: "success" }),
    watchSonar: async () => ({
      ok: true,
      quality_gate: "OK",
      issues_summary: { open_count: 0 },
      hotspots_summary: { open_count: 0 },
    }),
    assertCompletion: async ({ phase }) => ({
      ok: true,
      phase,
      readiness_report: phase === "pre_merge" ? "ready" : undefined,
    }),
    closeIssue: async () => ({ ok: true, closed: true }),
    execFile: async () => ({ stdout: "", stderr: "" }),
  };
  Object.assign(deps, overrides);
  // Mirrors the production wiring: the authorizer binds the requested UID to
  // the same issue thread the rest of the run reads.
  deps.authorizeRequirementUid ??= async ({ requestedRequirementUid }) => {
    const thread = await deps.getIssueThread({});
    return requestedRequirementUidAuthorization(thread.body, requestedRequirementUid);
  };
  deps.runGit ??= async (repoRoot, argv, commandRunner) =>
    commandRunner("git", ["-C", repoRoot, ...argv], { cwd: repoRoot });
  deps.preCommit ??= async (repoRoot, commandRunner, context) =>
    commandRunner(
      "bash",
      ["-c", context?.workflow?.precommit_command ?? "pre-commit run --all-files"],
      { cwd: repoRoot },
    );
  return deps;
}

function publishExec({ paths = ["src/change.js"] } = {}) {
  const calls = [];
  return {
    calls,
    execFile: async (file, argv) => {
      calls.push([file, ...argv]);
      if (file === "git" && argv.includes("--show-current")) {
        return { stdout: "1426-script-phases\n", stderr: "" };
      }
      if (file === "git" && argv.includes("-z")) {
        if (argv.includes("--cached") || argv.includes("--others")) {
          return { stdout: "", stderr: "" };
        }
        return { stdout: paths.map((path) => `${path}\0`).join(""), stderr: "" };
      }
      if (file === "git" && argv.includes("--cached") && argv.includes("--name-only")) {
        return { stdout: paths.join("\n"), stderr: "" };
      }
      return { stdout: "", stderr: "" };
    },
  };
}

function completionInput() {
  return {
    requirements: [],
    files: { modified: ["src/change.js"] },
    reviews: [{ reviewer: "review-cycle", summary: "passed" }],
    ci_status: "green",
    sonar_status: "passed",
    plain_english_outcome: "Mechanical workflow stages now run without model turns.",
  };
}

// ---------------------------------------------------------------------------
// Live workflow-run lifecycle emission (issue #1435)
// ---------------------------------------------------------------------------

/**
 * A recording stand-in for the lifecycle emitter. It captures what the mechanical layer asked to be
 * recorded without touching a backend, so these tests assert emission rather than transport.
 */
function lifecycleSpy({ fail = false } = {}) {
  const calls = [];
  const factory = (identity) => {
    calls.push(["create", identity]);
    const record = (name) => async (...args) => {
      if (fail) throw new Error("emitter exploded");
      calls.push([name, ...args]);
      return null;
    };
    return {
      openRun: record("openRun"),
      ensureRun: record("ensureRun"),
      markState: record("markState"),
      closeRun: record("closeRun"),
      recordRequirementUids: record("recordRequirementUids"),
      async station(phase, fn) {
        if (fail) throw new Error("emitter exploded");
        calls.push(["station:start", phase]);
        const result = await fn();
        calls.push(["station:end", phase, result?.ok !== false]);
        return result;
      },
    };
  };
  return { calls, factory };
}

function namesOf(calls) {
  return calls.map((call) => (call[0] === "station:start" || call[0] === "station:end" ? `${call[0]}:${call[1]}` : call[0]));
}

describe("runImplementMechanical live lifecycle emission", () => {
  it("opens the run before bootstrap runs, so it is queryable while still in progress", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "bootstrap",
      repoPath: "/repo",
      invocationRoot: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      driver: "codex",
    }, baseDeps({ createLifecycle: spy.factory }));

    const names = namesOf(spy.calls);
    assert.deepEqual(names.slice(0, 3), ["create", "openRun", "station:start:issue_branch_resolution"]);
    assert.ok(names.includes("recordRequirementUids"));
  });

  it("keys the run on the canonical project, repo, issue, and branch identity", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "bootstrap",
      repoPath: "/repo",
      invocationRoot: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      driver: "codex",
    }, baseDeps({
      createLifecycle: spy.factory,
      getContext: async () => ({
        status: "ok",
        project: "ground-control",
        github_repo: "autarchy-ai/Ground-Control",
        workflow: { base_branch: "dev", completion_command: "make check" },
      }),
    }));

    const identity = spy.calls[0][1];
    assert.equal(identity.project, "ground-control");
    assert.equal(identity.repo, "autarchy-ai/Ground-Control");
    assert.equal(identity.issueNumber, 1426);
    assert.equal(identity.branch, "1426-script-phases");
    assert.equal(identity.workflowType, "IMPLEMENT");
    assert.equal(identity.runtimeDriver, "codex");
  });

  it("passes the PR number to the emitter on the actions that know one", async () => {
    // monitor, readiness, and finalize are the boundaries where the tool layer holds the PR. If the
    // identity drops it, the run row keeps pr_number null for its whole life and only a deliberate
    // issue-thread backfill can ever supply it.
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "monitor",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
    }, baseDeps({ createLifecycle: spy.factory }));

    assert.equal(spy.calls[0][1].prNumber, 99);
  });

  it("resolves the run without re-asserting RUNNING on a mid-run boundary", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
    }, baseDeps({ createLifecycle: spy.factory }));

    const names = namesOf(spy.calls);
    assert.deepEqual(names, ["create", "ensureRun", "station:start:completion_gate", "station:end:completion_gate"]);
  });

  it("records the publish action under the git_publish station", async () => {
    const spy = lifecycleSpy();
    const exec = publishExec();
    await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      commitMessage: "feat: publish the change",
    }, baseDeps({ createLifecycle: spy.factory, execFile: exec.execFile }));

    assert.deepEqual(namesOf(spy.calls), [
      "create",
      "ensureRun",
      "station:start:git_publish",
      "station:end:git_publish",
    ]);
  });

  it("records CI and SonarCloud as separate station attempts", async () => {
    // They are distinct gates with distinct rework profiles; collapsing them into one station would
    // make per-gate first-pass yield meaningless.
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "monitor",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
    }, baseDeps({ createLifecycle: spy.factory }));

    assert.deepEqual(namesOf(spy.calls), [
      "create",
      "ensureRun",
      "station:start:ci",
      "station:end:ci",
      "station:start:sonarcloud",
      "station:end:sonarcloud",
    ]);
  });

  it("records a passing CI gate and a failing Sonar gate distinctly", async () => {
    const spy = lifecycleSpy();
    const result = await runImplementMechanical({
      action: "monitor",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
    }, baseDeps({
      createLifecycle: spy.factory,
      watchSonar: async () => ({
        ok: true,
        quality_gate: "ERROR",
        issues_summary: { open_count: 2 },
        hotspots_summary: { open_count: 0 },
      }),
    }));

    assert.equal(result.ok, false);
    const ends = spy.calls.filter((call) => call[0] === "station:end");
    assert.deepEqual(ends, [["station:end", "ci", true], ["station:end", "sonarcloud", false]]);
  });

  it("does not attempt the Sonar station when CI failed", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "monitor",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
    }, baseDeps({
      createLifecycle: spy.factory,
      watchCi: async () => ({ ok: true, conclusion: "failure" }),
    }));

    assert.ok(!namesOf(spy.calls).includes("station:start:sonarcloud"));
  });

  it("marks the run ready for review without ending it", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "readiness",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
      completion: completionInput(),
    }, baseDeps({ createLifecycle: spy.factory }));

    const marked = spy.calls.find((call) => call[0] === "markState");
    assert.deepEqual(marked, ["markState", "READY_FOR_REVIEW"]);
    assert.ok(!namesOf(spy.calls).includes("closeRun"));
  });

  it("closes the run as merged when the post-merge phase completes", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "finalize",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
      completion: completionInput(),
    }, baseDeps({ createLifecycle: spy.factory }));

    const closed = spy.calls.find((call) => call[0] === "closeRun");
    assert.deepEqual(closed, ["closeRun", { finalState: "MERGED", outcome: "MERGED" }]);
  });

  it("closes the run as closed-without-merge when the PR was closed unmerged", async () => {
    // A PR closed without merging is a real terminal observation: that run is over and did not ship.
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "finalize",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
      completion: completionInput(),
    }, baseDeps({
      createLifecycle: spy.factory,
      closeIssue: async () => ({
        ok: false,
        error: "close_pr_not_merged",
        message: "not merged",
        pr_state: "CLOSED",
      }),
    }));

    const closed = spy.calls.find((call) => call[0] === "closeRun");
    assert.deepEqual(closed, ["closeRun", { finalState: "CLOSED", outcome: "CLOSED_WITHOUT_MERGE" }]);
  });

  it("leaves the run open when the PR simply is not merged yet", async () => {
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "finalize",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
      completion: completionInput(),
    }, baseDeps({
      createLifecycle: spy.factory,
      closeIssue: async () => ({
        ok: false,
        error: "close_pr_not_merged",
        message: "not merged",
        pr_state: "OPEN",
      }),
    }));

    assert.ok(!namesOf(spy.calls).includes("closeRun"));
  });

  it("returns the unchanged workflow result for every action when the emitter throws", async () => {
    // Telemetry must never be able to fail a phase. Each action is compared against the same action
    // run with emission disabled, so a regression in the fail-open wrapper goes red here.
    const invocations = [
      { action: "bootstrap", invocationRoot: "/repo", branchName: "1426-script-phases", driver: "codex" },
      { action: "verify", branchName: "1426-script-phases" },
      { action: "publish", branchName: "1426-script-phases", commitMessage: "feat: publish" },
      { action: "monitor", branchName: "1426-script-phases", prNumber: 99 },
      { action: "readiness", branchName: "1426-script-phases", prNumber: 99, completion: completionInput() },
      { action: "finalize", branchName: "1426-script-phases", prNumber: 99, completion: completionInput() },
    ];

    for (const invocation of invocations) {
      const args = { repoPath: "/repo", issueNumber: 1426, ...invocation };
      const withoutEmission = await runImplementMechanical({ ...args }, baseDeps());
      const withBrokenEmitter = await runImplementMechanical(
        { ...args },
        baseDeps({ createLifecycle: lifecycleSpy({ fail: true }).factory }),
      );
      assert.deepEqual(withBrokenEmitter, withoutEmission, `action ${invocation.action} changed under a broken emitter`);
    }
  });

  it("resolves the same run identity from bootstrap through readiness and finalize", async () => {
    // readiness and finalize do not take a branch, and the branch is half the run's natural key.
    // If they resolved a different identity they would mark a phantom run merged while the real one
    // stayed RUNNING, which is worse than not recording at all.
    const spy = lifecycleSpy();
    const deps = baseDeps({
      createLifecycle: spy.factory,
      execFile: async (file, argv) => {
        if (file === "git" && argv.includes("--show-current")) {
          return { stdout: "1426-script-phases\n", stderr: "" };
        }
        return { stdout: "", stderr: "" };
      },
    });

    await runImplementMechanical({
      action: "bootstrap",
      repoPath: "/repo",
      invocationRoot: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      driver: "codex",
    }, deps);
    await runImplementMechanical({
      action: "readiness",
      repoPath: "/repo",
      issueNumber: 1426,
      prNumber: 99,
      completion: completionInput(),
    }, deps);
    await runImplementMechanical({
      action: "finalize",
      repoPath: "/repo",
      issueNumber: 1426,
      prNumber: 99,
      completion: completionInput(),
    }, deps);

    const identities = spy.calls
      .filter((call) => call[0] === "create")
      .map((call) => [call[1].project, call[1].repo, call[1].issueNumber, call[1].branch]);
    assert.equal(identities.length, 3);
    assert.deepEqual(identities[1], identities[0]);
    assert.deepEqual(identities[2], identities[0]);
    assert.equal(identities[0][3], "1426-script-phases");
  });

  it("emits nothing when the branch half of the run key cannot be resolved", async () => {
    // A nullable-branch upsert would create a second run for the same work item rather than refine
    // the real one.
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "readiness",
      repoPath: "/repo",
      issueNumber: 1426,
      prNumber: 99,
      completion: completionInput(),
    }, baseDeps({ createLifecycle: spy.factory }));

    assert.deepEqual(spy.calls, []);
  });

  it("emits nothing when the repository context cannot be resolved", async () => {
    // Without a project there is no key to record against, and inventing one would fabricate a run.
    const spy = lifecycleSpy();
    await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
    }, baseDeps({
      createLifecycle: spy.factory,
      getContext: async () => ({ status: "error", errors: ["missing .ground-control.yaml"] }),
    }));

    assert.deepEqual(spy.calls, []);
  });
});
