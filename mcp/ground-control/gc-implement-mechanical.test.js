import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  extractInScopeRequirementUids,
  runImplementMechanical,
} from "./gc-implement-mechanical.js";

const SHA_A = "a".repeat(40);
const SHA_B = "b".repeat(40);
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
  deps.runGit ??= async (repoRoot, argv, commandRunner) =>
    commandRunner("git", ["-C", repoRoot, ...argv], { cwd: repoRoot });
  deps.preCommit ??= async (repoRoot, commandRunner) =>
    commandRunner("pre-commit", ["run", "--all-files"], { cwd: repoRoot });
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

describe("extractInScopeRequirementUids", () => {
  it("reads only valid UID bullets from a level 2-4 Requirements section", () => {
    const body = [
      "GC-OUTSIDE1",
      "### Requirements",
      "- `GC-O007`",
      "* GC-O-008, GC-O007",
      "- prose GC-O009 prose",
      "#### Detail",
      "+ GC-T010",
      "### Later",
      "- GC-OUTSIDE2",
    ].join("\n");
    assert.deepEqual(
      extractInScopeRequirementUids(body),
      ["GC-O007", "GC-O-008", "GC-T010"],
    );
  });

  it("returns an empty set when the authoritative section is absent or empty", () => {
    assert.deepEqual(extractInScopeRequirementUids("Fix noted in GC-O007."), []);
    assert.deepEqual(extractInScopeRequirementUids("## Requirements\n\n## Notes\n- GC-O007"), []);
  });
});

describe("runImplementMechanical bootstrap", () => {
  it("prepares the branch, records pickup, and returns issue context in one call", async () => {
    let pickupCalls = 0;
    const result = await runImplementMechanical({
      action: "bootstrap",
      repoPath: "/repo",
      invocationRoot: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      driver: "codex",
    }, baseDeps({
      markPickedUp: async () => {
        pickupCalls += 1;
        return { ok: true };
      },
    }));

    assert.equal(result.ok, true);
    assert.equal(result.phase, "bootstrap_complete");
    assert.deepEqual(result.requirement_uids, ["GC-O007"]);
    assert.equal(result.in_scope_requirements[0].id, "id-GC-O007");
    assert.deepEqual(result.issue_traceability_links, [{ id: "link-1" }]);
    assert.equal(pickupCalls, 1);
  });

  it("does not duplicate an existing pickup record for the same branch", async () => {
    let pickupCalls = 0;
    const deps = baseDeps({
      getIssueThread: async () => ({
        ok: true,
        title: "Script phases",
        body: "",
        comments: [{
          body: "Picked up by /implement on branch `1426-script-phases`",
        }],
      }),
      markPickedUp: async () => {
        pickupCalls += 1;
        return { ok: true };
      },
    });
    const result = await runImplementMechanical({
      action: "bootstrap",
      repoPath: "/repo",
      invocationRoot: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      driver: "codex",
    }, deps);

    assert.equal(result.ok, true);
    assert.equal(result.pickup.reused, true);
    assert.equal(pickupCalls, 0);
  });
});

describe("runImplementMechanical verify", () => {
  it("runs completion, policy, tree-integrity, and quality gates without a handoff", async () => {
    const commands = [];
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1426,
      requirements: [],
    }, baseDeps({
      execFile: async (file, argv) => {
        commands.push([file, ...argv]);
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, true);
    assert.equal(result.phase, "verification_complete");
    assert.ok(commands.some(([file, ...argv]) => file === "bash" && argv.includes("make check")));
    assert.ok(commands.some(([file, ...argv]) => file === "make" && argv.includes("policy")));
  });

  it("hands off only the actionable failed gate", async () => {
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1426,
    }, baseDeps({
      execFile: async (file, argv) => {
        if (file === "bash") {
          const error = new Error("tests failed");
          error.stderr = "one test failed";
          throw error;
        }
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, false);
    assert.equal(result.agent_required, true);
    assert.equal(result.failed_stage, "completion_gate");
    assert.match(result.message, /one test failed/);
  });
});

describe("runImplementMechanical publish", () => {
  it("refuses an unauthorized checkout before any Git or hook command", async () => {
    let commandCalls = 0;
    const result = await runImplementMechanical({
      action: "publish",
      repoPath: "/other-repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      commitMessage: "feat: automate implement phases",
    }, baseDeps({
      authorizeRepo: async () => ({
        ok: false,
        error: "implement_repo_not_authorized",
        message: "outside the launch workspace",
      }),
      execFile: async () => {
        commandCalls += 1;
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_repo_not_authorized");
    assert.equal(commandCalls, 0);
  });

  it("stages, checks, commits, pushes, and completes a clean synchronization", async () => {
    const git = publishExec();
    const syncCalls = [];
    const result = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      commitMessage: "feat: automate implement phases",
    }, baseDeps({
      execFile: git.execFile,
      synchronize: async (input) => {
        syncCalls.push(input);
        if (input.action === "start") {
          return {
            ok: true,
            status: "merge_ready",
            recordId: RECORD_ID,
            preSyncSha: SHA_A,
            fetchedBaseSha: SHA_B,
            outcome: "merged_clean",
          };
        }
        return { ok: true, status: "complete", recordId: RECORD_ID };
      },
    }));

    assert.equal(result.ok, true);
    assert.equal(result.phase, "publish_complete");
    assert.deepEqual(syncCalls.map(({ action }) => action), ["start", "complete"]);
    assert.ok(git.calls.some(([file, ...argv]) => file === "pre-commit" && argv.includes("--all-files")));
    assert.ok(git.calls.some(([file, ...argv]) => file === "git" && argv.includes("commit")));
    assert.ok(git.calls.some(([file, ...argv]) => file === "git" && argv.includes("push")));
  });

  it("refuses a sensitive path before staging it", async () => {
    const git = publishExec({ paths: [".env.local"] });
    const result = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      commitMessage: "fix: safe change",
    }, baseDeps({ execFile: git.execFile }));

    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_mechanical_sensitive_path_present");
    assert.equal(
      git.calls.some(([file, ...argv]) => file === "git" && argv.includes("add")),
      false,
    );
  });

  it("allows a non-secret environment template to publish", async () => {
    const git = publishExec({ paths: [".env.example"] });
    const result = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      commitMessage: "test: cover environment templates",
    }, baseDeps({ execFile: git.execFile }));

    assert.equal(result.ok, true);
    assert.ok(git.calls.some(([file, ...argv]) => file === "git" && argv.includes("add")));
  });

  it("returns durable retry input on conflict and completes from it after resolution", async () => {
    const git = publishExec();
    const conflict = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      commitMessage: "feat: automate implement phases",
    }, baseDeps({
      execFile: git.execFile,
      synchronize: async () => ({
        ok: true,
        status: "conflicts",
        recordId: RECORD_ID,
        preSyncSha: SHA_A,
        fetchedBaseSha: SHA_B,
        outcome: "merged_conflicts_resolved",
      }),
    }));

    assert.equal(conflict.agent_required, true);
    assert.deepEqual(conflict.retry_input, {
      record_id: RECORD_ID,
      pre_sync_sha: SHA_A,
      fetched_base_sha: SHA_B,
      outcome: "merged_conflicts_resolved",
    });

    let completionCall;
    const resumed = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      synchronization: conflict.retry_input,
    }, baseDeps({
      execFile: git.execFile,
      synchronize: async (input) => {
        completionCall = input;
        return { ok: true, status: "complete", recordId: RECORD_ID };
      },
    }));

    assert.equal(resumed.ok, true);
    assert.equal(completionCall.action, "complete");
    assert.equal(completionCall.recordId, RECORD_ID);
  });
});

describe("runImplementMechanical monitor and completion", () => {
  it("waits for CI and Sonar and returns compact successful status", async () => {
    const result = await runImplementMechanical({
      action: "monitor",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
    }, baseDeps());

    assert.equal(result.ok, true);
    assert.equal(result.ci_status, "green");
    assert.equal(result.sonar_status, "passed");
  });

  it("does not run Sonar after an actionable CI failure", async () => {
    let sonarCalls = 0;
    const result = await runImplementMechanical({
      action: "monitor",
      repoPath: "/repo",
      issueNumber: 1426,
      branchName: "1426-script-phases",
      prNumber: 99,
    }, baseDeps({
      watchCi: async () => ({ ok: true, conclusion: "failure", log_summary: "lint failed" }),
      watchSonar: async () => {
        sonarCalls += 1;
        return { ok: true, skipped: true };
      },
    }));

    assert.equal(result.agent_required, true);
    assert.equal(result.failed_stage, "ci");
    assert.equal(sonarCalls, 0);
  });

  it("runs pre-merge readiness and post-merge close in the required order", async () => {
    const calls = [];
    const deps = baseDeps({
      assertCompletion: async ({ phase }) => {
        calls.push(phase);
        return { ok: true, readiness_report: "ready" };
      },
      closeIssue: async () => {
        calls.push("close");
        return { ok: true, closed: true };
      },
    });
    const readiness = await runImplementMechanical({
      action: "readiness",
      repoPath: "/repo",
      issueNumber: 1426,
      prNumber: 99,
      completion: completionInput(),
    }, deps);
    const finalized = await runImplementMechanical({
      action: "finalize",
      repoPath: "/repo",
      issueNumber: 1426,
      prNumber: 99,
      completion: completionInput(),
    }, deps);

    assert.equal(readiness.ok, true);
    assert.equal(finalized.ok, true);
    assert.deepEqual(calls, ["pre_merge", "post_merge", "close"]);
  });
});
