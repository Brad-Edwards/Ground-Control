import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  extractInScopeRequirementUids,
  gcImplementMechanicalToolHandler,
  runImplementMechanical,
} from "./gc-implement-mechanical.js";
import {
  REQUIREMENT_UID_GATE_ENV_VAR,
  requestedRequirementUidAuthorization,
} from "./lib.js";

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

  it("extracts allocator-minted short UIDs (issue #1425)", () => {
    // The failure this guards is the one the issue describes: dropping APP-2
    // here turns a requirement-backed run into a requirement-free one, which
    // then trips the orphaned-link audit on a correct link.
    assert.deepEqual(
      extractInScopeRequirementUids("## Requirements\n- APP-2\n- `A-1`\n- PLAT-10"),
      ["APP-2", "A-1", "PLAT-10"],
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

  for (const [label, uid, expected] of [
    ["an invalid", "DSL-437; rm -rf /", "implement_requested_requirement_uid_invalid"],
    ["an out-of-scope", "OTHER-999", "implement_requested_requirement_uid_out_of_scope"],
  ]) {
    it(`refuses ${label} requirement UID before recording pickup (#1434)`, async () => {
      let pickupCalls = 0;
      const result = await runImplementMechanical({
        action: "bootstrap",
        repoPath: "/repo",
        invocationRoot: "/repo",
        issueNumber: 1434,
        branchName: "1426-script-phases",
        driver: "claude",
        requestedRequirementUid: uid,
      }, baseDeps({
        markPickedUp: async () => {
          pickupCalls += 1;
          return { ok: true };
        },
      }));

      assert.equal(result.ok, false);
      assert.equal(result.agent_required, true);
      assert.equal(result.error, expected);
      assert.equal(pickupCalls, 0);
    });
  }
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
    assert.ok(commands.some(([file, ...argv]) => file === "bash" && argv.includes("make policy")));
  });

  it("runs the repository's configured policy command rather than a hardcoded target (#1429)", async () => {
    const commands = [];
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1429,
      requirements: [],
    }, baseDeps({
      getContext: async () => ({
        status: "ok",
        project: "ground-control",
        workflow: {
          base_branch: "dev",
          completion_command: "make check",
          policy_command: "python3 scripts/adr_guard/adr_guard.py --all --level ci",
        },
      }),
      execFile: async (file, argv) => {
        commands.push([file, ...argv]);
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.policy_command, "python3 scripts/adr_guard/adr_guard.py --all --level ci");
    assert.deepEqual(
      commands.filter(([file]) => file === "bash").map(([, , command]) => command),
      ["make check", "python3 scripts/adr_guard/adr_guard.py --all --level ci"],
    );
    assert.equal(commands.some(([file]) => file === "make"), false);
  });

  it("hands off the policy gate by name when the configured policy command fails", async () => {
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1429,
    }, baseDeps({
      getContext: async () => ({
        status: "ok",
        project: "ground-control",
        workflow: {
          base_branch: "dev",
          completion_command: "make check",
          policy_command: "bin/policy-gate",
        },
      }),
      execFile: async (file, argv) => {
        if (file === "bash" && argv[1] === "bin/policy-gate") {
          const error = new Error("policy failed");
          error.stderr = "one policy rule failed";
          throw error;
        }
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, false);
    assert.equal(result.agent_required, true);
    assert.equal(result.failed_stage, "policy_gate");
    assert.equal(result.error, "implement_mechanical_policy_gate_failed");
  });

  it("carries the requested requirement UID to the completion and policy gates (#1434)", async () => {
    const commands = [];
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1434,
      requestedRequirementUid: "GC-O007",
      requirements: [],
    }, baseDeps({
      execFile: async (file, argv, options) => {
        commands.push([file, argv, options]);
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, true, JSON.stringify(result));
    const gateEnvs = commands
      .filter(([file]) => file === "bash")
      .map(([, , options]) => options?.env?.[REQUIREMENT_UID_GATE_ENV_VAR]);
    assert.deepEqual(gateEnvs, ["GC-O007", "GC-O007"]);
    assert.equal(
      commands.some(([, argv]) => argv.some((arg) => String(arg).includes("GC-O007"))),
      false,
      "the UID must reach the gate through the environment, never through argv",
    );
  });

  it("injects no requirement UID override when none is requested (#1434)", async () => {
    // The issue branch already carries its UID here, so the repository gate
    // keeps deriving requirement context the way it always has.
    const commands = [];
    await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1434,
      requirements: [],
    }, baseDeps({
      execFile: async (file, argv, options) => {
        commands.push([file, argv, options]);
        return { stdout: "", stderr: "" };
      },
    }));

    const gates = commands.filter(([file]) => file === "bash");
    assert.equal(gates.length, 2);
    for (const [, , options] of gates) {
      assert.equal(REQUIREMENT_UID_GATE_ENV_VAR in (options?.env ?? {}), false);
    }
  });

  it("refuses an invalid requested requirement UID before running any gate (#1434)", async () => {
    const commands = [];
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1434,
      requestedRequirementUid: "DSL-437; rm -rf /",
      requirements: [],
    }, baseDeps({
      execFile: async (file, argv, options) => {
        commands.push([file, argv, options]);
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, false);
    assert.equal(result.agent_required, true);
    assert.equal(result.error, "implement_requested_requirement_uid_invalid");
    assert.equal(commands.some(([file]) => file === "bash"), false);
  });

  it("refuses a requirement UID the target issue does not list, before any gate (#1434)", async () => {
    // verify is independently callable, so bootstrap's membership check is not
    // an enforcement seam for it. Syntax alone must not become gate authority.
    const commands = [];
    const result = await runImplementMechanical({
      action: "verify",
      repoPath: "/repo",
      issueNumber: 1434,
      requestedRequirementUid: "OTHER-999",
      requirements: [],
    }, baseDeps({
      execFile: async (file, argv, options) => {
        commands.push([file, argv, options]);
        return { stdout: "", stderr: "" };
      },
    }));

    assert.equal(result.ok, false);
    assert.equal(result.agent_required, true);
    assert.equal(result.error, "implement_requested_requirement_uid_out_of_scope");
    assert.equal(commands.some(([file]) => file === "bash"), false);
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

describe("gcImplementMechanicalToolHandler async transport", () => {
  const flush = () => new Promise((resolve) => setImmediate(resolve));

  it("starts verify in the shared job registry and preserves its exact terminal envelope", async () => {
    const { pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const start = await gcImplementMechanicalToolHandler({
      action: "verify",
      repo_path: "/repo",
      issue_number: 1473,
      requirements: [],
      async: true,
      idempotency_key: "issue-1473-verify-attempt-1",
    }, {
      ...baseDeps(),
      canonicalizeRepoPath: () => "/repo",
    });

    assert.equal(start.ok, true);
    assert.equal(start.status, "running");
    assert.equal(start.kind, "implement_mechanical_verify");
    await flush();
    const done = pollAsyncJob(start.job_id);
    assert.equal(done.status, "done");
    assert.deepEqual(done.result, {
      ok: true,
      action: "verify",
      phase: "verification_complete",
      completion_command: "make check",
      policy_command: "make policy",
      policy: "passed",
      quality: { ok: true, passed_count: 2 },
      next_action: "run_required_agent_reviews_or_publish",
    });
  });

  it("keeps an expected mechanical gate failure under a completed job result", async () => {
    const { pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const start = await gcImplementMechanicalToolHandler({
      action: "verify",
      repo_path: "/repo",
      issue_number: 1473,
      requirements: [],
      async: true,
      idempotency_key: "issue-1473-verify-attempt-2",
    }, {
      ...baseDeps({
        execFile: async (file) => {
          if (file === "bash") {
            const error = new Error("completion failed");
            error.stderr = "one test failed";
            throw error;
          }
          return { stdout: "", stderr: "" };
        },
      }),
      canonicalizeRepoPath: () => "/repo",
    });

    await flush();
    const done = pollAsyncJob(start.job_id);
    assert.equal(done.ok, true);
    assert.equal(done.status, "done");
    assert.equal(done.result.ok, false);
    assert.equal(done.result.action, "verify");
    assert.equal(done.result.agent_required, true);
    assert.equal(done.result.failed_stage, "completion_gate");
  });

  for (const action of ["verify", "publish", "monitor"]) {
    it(`reuses one ${action} job for a repeated idempotent start`, async () => {
      const { _resetAsyncJobsForTest } = await import("./lib.js");
      _resetAsyncJobsForTest();
      const input = {
        action,
        repo_path: "/repo",
        issue_number: 1473,
        async: true,
        idempotency_key: `issue-1473-${action}-attempt-1`,
        ...(action === "publish"
          ? { branch_name: "1426-script-phases", commit_message: "fix: make mechanical work asynchronous" }
          : {}),
        ...(action === "monitor"
          ? { branch_name: "1426-script-phases", pr_number: 99 }
          : {}),
      };
      const deps = {
        ...baseDeps(),
        canonicalizeRepoPath: () => "/repo",
      };
      const first = await gcImplementMechanicalToolHandler(input, deps);
      const duplicate = await gcImplementMechanicalToolHandler(input, deps);
      assert.equal(first.ok, true);
      assert.equal(duplicate.ok, true);
      assert.match(first.job_id, /^job-/);
      assert.equal(duplicate.job_id, first.job_id);
    });
  }

  it("rejects changed mechanical input under one idempotency key", async () => {
    const { _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const common = {
      action: "verify",
      repo_path: "/repo",
      issue_number: 1473,
      async: true,
      idempotency_key: "issue-1473-verify-conflict",
    };
    const deps = {
      ...baseDeps(),
      canonicalizeRepoPath: () => "/repo",
    };
    const first = await gcImplementMechanicalToolHandler(
      { ...common, requirements: [] },
      deps,
    );
    const conflict = await gcImplementMechanicalToolHandler(
      { ...common, requirements: [{ uid: "GC-1473" }] },
      deps,
    );
    assert.equal(first.ok, true);
    assert.equal(conflict.ok, false);
    assert.equal(conflict.error, "job_idempotency_conflict");
  });

  it("prevents verify and publish from racing on the same canonical checkout", async () => {
    const { _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const never = new Promise(() => {});
    const deps = {
      ...baseDeps({
        getContext: async () => never,
      }),
      canonicalizeRepoPath: () => "/canonical/repo",
    };
    const verify = await gcImplementMechanicalToolHandler({
      action: "verify",
      repo_path: "/repo-via-symlink",
      issue_number: 1473,
      async: true,
      idempotency_key: "issue-1473-verify-running",
    }, deps);
    const publish = await gcImplementMechanicalToolHandler({
      action: "publish",
      repo_path: "/canonical/repo",
      issue_number: 1473,
      branch_name: "1473-mechanical-async",
      commit_message: "fix: make mechanical actions asynchronous",
      async: true,
      idempotency_key: "issue-1473-publish-contended",
    }, deps);
    assert.equal(verify.ok, true);
    assert.equal(publish.ok, false);
    assert.equal(publish.error, "job_execution_contended");
    _resetAsyncJobsForTest();
  });

  for (const action of ["bootstrap", "readiness", "finalize"]) {
    it(`refuses async mode for short action ${action}`, async () => {
      const result = await gcImplementMechanicalToolHandler({
        action,
        repo_path: "/repo",
        issue_number: 1473,
        async: true,
        idempotency_key: `issue-1473-${action}-attempt-1`,
      }, {
        ...baseDeps(),
        canonicalizeRepoPath: () => "/repo",
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "implement_mechanical_async_action_invalid");
      assert.equal(result.agent_required, false);
    });
  }

  it("requires an idempotency key for a background mechanical start", async () => {
    const result = await gcImplementMechanicalToolHandler({
      action: "verify",
      repo_path: "/repo",
      issue_number: 1473,
      async: true,
    }, {
      ...baseDeps(),
      canonicalizeRepoPath: () => "/repo",
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_mechanical_idempotency_key_required");
  });

  it("returns a bounded transport failure when the repository path cannot be canonicalized", async () => {
    const result = await gcImplementMechanicalToolHandler({
      action: "verify",
      repo_path: "/missing/repo",
      issue_number: 1473,
      async: true,
      idempotency_key: "issue-1473-invalid-repo",
    }, {
      ...baseDeps(),
      canonicalizeRepoPath: () => {
        throw new Error("missing checkout");
      },
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_mechanical_async_repo_invalid");
    assert.equal(result.agent_required, false);
  });

  it("keeps omitted async mode synchronous for direct callers", async () => {
    const result = await gcImplementMechanicalToolHandler({
      action: "verify",
      repo_path: "/repo",
      issue_number: 1473,
      requirements: [],
    }, baseDeps());
    assert.equal(result.ok, true);
    assert.equal(result.phase, "verification_complete");
    assert.equal(result.status, undefined);
    assert.equal(result.job_id, undefined);
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

  it("refuses an invalid repository context before staging or hooking (#1429)", async () => {
    // The pre-publish hook command comes from .ground-control.yaml, so a
    // broken config must refuse rather than fall through to the default
    // boundary command and publish anyway.
    let commandCalls = 0;
    let preCommitCalls = 0;
    const result = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1429,
      branchName: "1426-script-phases",
      commitMessage: "fix: derive the implement policy gate from repository configuration",
    }, baseDeps({
      getContext: async () => ({
        status: "invalid_ground_control_yaml",
        errors: ["workflow.precommit_command must be a non-empty string when set"],
      }),
      execFile: async () => {
        commandCalls += 1;
        return { stdout: "", stderr: "" };
      },
      preCommit: async () => {
        preCommitCalls += 1;
        return { stdout: "" };
      },
    }));

    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_mechanical_context_invalid");
    assert.equal(commandCalls, 0);
    assert.equal(preCommitCalls, 0);
  });

  it("passes the repository context to the pre-commit boundary so its command is configurable (#1429)", async () => {
    const git = publishExec();
    const preCommitArgs = [];
    await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1429,
      branchName: "1426-script-phases",
      commitMessage: "fix: derive the implement policy gate from repository configuration",
    }, baseDeps({
      execFile: git.execFile,
      getContext: async () => ({
        status: "ok",
        project: "ground-control",
        workflow: {
          base_branch: "dev",
          completion_command: "make check",
          precommit_command: "lefthook run pre-commit",
        },
      }),
      preCommit: async (repoRoot, commandRunner, context) => {
        preCommitArgs.push([repoRoot, context?.workflow?.precommit_command]);
        return { stdout: "" };
      },
      synchronize: async () => ({ ok: true, status: "complete", recordId: RECORD_ID }),
    }));

    assert.deepEqual(preCommitArgs, [["/repo", "lefthook run pre-commit"]]);
  });

  it("carries the requested requirement UID to the pre-commit and synchronization gates (#1434)", async () => {
    const git = publishExec();
    const preCommitUids = [];
    const syncUids = [];
    const result = await runImplementMechanical({
      action: "publish",
      repoPath: "/repo",
      issueNumber: 1434,
      branchName: "1426-script-phases",
      requestedRequirementUid: "GC-O007",
      commitMessage: "fix: carry requirement identity into repository gates",
    }, baseDeps({
      execFile: git.execFile,
      preCommit: async (repoRoot, commandRunner, context, requestedRequirementUid) => {
        preCommitUids.push(requestedRequirementUid);
        return { stdout: "" };
      },
      synchronize: async (input) => {
        syncUids.push(input.requestedRequirementUid);
        return { ok: true, status: "complete", recordId: RECORD_ID };
      },
    }));

    assert.equal(result.ok, true, JSON.stringify(result));
    assert.deepEqual(preCommitUids, ["GC-O007"]);
    // Publish reaches synchronization through `start`, whose completion runs the
    // final-tree gates that need the same requirement identity.
    assert.deepEqual(syncUids, ["GC-O007"]);
  });

  for (const [label, uid, expected] of [
    ["an invalid", "DSL-437; rm -rf /", "implement_requested_requirement_uid_invalid"],
    ["an out-of-scope", "OTHER-999", "implement_requested_requirement_uid_out_of_scope"],
  ]) {
    it(`refuses ${label} requirement UID before staging, hooks, or synchronization (#1434)`, async () => {
      const git = publishExec();
      let preCommitCalls = 0;
      let syncCalls = 0;
      const result = await runImplementMechanical({
        action: "publish",
        repoPath: "/repo",
        issueNumber: 1434,
        branchName: "1426-script-phases",
        requestedRequirementUid: uid,
        commitMessage: "fix: carry requirement identity into repository gates",
      }, baseDeps({
        execFile: git.execFile,
        preCommit: async () => {
          preCommitCalls += 1;
          return { stdout: "" };
        },
        synchronize: async () => {
          syncCalls += 1;
          return { ok: true, status: "complete", recordId: RECORD_ID };
        },
      }));

      assert.equal(result.ok, false);
      assert.equal(result.agent_required, true);
      assert.equal(result.error, expected);
      assert.equal(git.calls.length, 0, "no Git command may run before the UID is authorized");
      assert.equal(preCommitCalls, 0);
      assert.equal(syncCalls, 0);
    });
  }

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
    assert.ok(git.calls.some(([file, ...argv]) => file === "bash" && argv.includes("pre-commit run --all-files")));
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
