// Split from gc-implement-mechanical.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { extractInScopeRequirementUids, runImplementMechanical } from "./gc-implement-mechanical.js";
import { REQUIREMENT_UID_GATE_ENV_VAR, requestedRequirementUidAuthorization } from "./lib.js";

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
