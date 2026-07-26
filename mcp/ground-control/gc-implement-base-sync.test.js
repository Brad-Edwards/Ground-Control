import { execFile as execFileCb } from "node:child_process";
import { realpathSync } from "node:fs";
import { promisify } from "node:util";
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  buildImplementBaseSyncMarker,
  isDefaultImplementHooksPath,
  parseImplementBaseSyncMarkers,
  REQUIREMENT_UID_GATE_ENV_VAR,
  resolveWorkflowRouteFromConfig,
  runCreateSynchronizedImplementPr,
  runSynchronizeImplementBranch,
  validateImplementPrTitle,
} from "./lib.js";

const execFile = promisify(execFileCb);
const REPO_ROOT = realpathSync(new URL("../..", import.meta.url).pathname);
const ISSUE = 1421;
const BRANCH = "1421-merge-dev-before-pr";
const PRE = "1".repeat(40);
const BASE = "2".repeat(40);
const RESULT = "3".repeat(40);
const RECORD = "4".repeat(32);
const TREE = "5".repeat(40);

function renderedPrBody() {
  return [
    "## Summary", "", "summary", "",
    "## Requirement UIDs", "", "- `GC-O007`", "",
    "## Related Issues", "", "Closes #1421", "",
    "## ADR Impact", "", "- ADR-021", "",
    "## Changes", "", "- change", "",
    "## Test Plan", "", "- tests", "",
    "## Ground Control Checks", "",
    "- [x] Configured repository policy command passes",
    "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change",
    "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale",
    "", "## Traceability", "", "- IMPLEMENTS: GC-O007", "- TESTS: test", "",
    "## Checklist", "", "- [x] done",
  ].join("\n");
}

async function workspaceAuthorization() {
  const [gitDir, origin] = await Promise.all([
    execFile("git", ["-C", REPO_ROOT, "rev-parse", "--absolute-git-dir"]),
    execFile("git", ["-C", REPO_ROOT, "remote", "get-url", "origin"]),
  ]);
  return {
    workspaceRoot: REPO_ROOT,
    gitDir: realpathSync(gitDir.stdout.trim()),
    origin: origin.stdout.trim(),
    owner: "autarchy-ai",
    name: "ground-control",
  };
}

function context(baseBranch = "dev", workflowOverrides = {}) {
  return {
    status: "ok",
    workflow: {
      base_branch: baseBranch,
      completion_command: "make check",
      policy_command: "make policy",
      pr_title: null,
      ...workflowOverrides,
    },
  };
}

// Drives `action=complete` down the fresh-merge path (MERGE_HEAD present,
// dirty tree) so a test can observe exactly which repository commands the
// final-tree boundary executes.
function completeRunner({ failCommand = null } = {}) {
  const calls = [];
  let committed = false;
  const runner = async (command, args, options) => {
    calls.push([command, args, options]);
    if (command === "bash") {
      if (failCommand != null && args[1] === failCommand) {
        const error = new Error(`${failCommand} failed`);
        error.stderr = "gate failed";
        throw error;
      }
      return { stdout: "" };
    }
    if (command === "make") return { stdout: "" };
    if (command === "gh") {
      return {
        stdout: JSON.stringify({
          id: 101,
          html_url: "https://github.com/autarchy-ai/Ground-Control/issues/1421#issuecomment-101",
        }),
      };
    }
    const op = gitOperation(args);
    if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
    if (op[0] === "status") return { stdout: "M  file.txt\n" };
    if (op[0] === "rev-parse") {
      const ref = op[op.length - 1];
      if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
      if (ref.startsWith("MERGE_HEAD")) return { stdout: `${BASE}\n` };
      return { stdout: `${committed ? RESULT : PRE}\n` };
    }
    if (op[0] === "write-tree") return { stdout: `${TREE}\n` };
    if (op[0] === "ls-files") return { stdout: "" };
    if (op[0] === "commit") {
      committed = true;
      return { stdout: "" };
    }
    if (op[0] === "push") return { stdout: "" };
    if (op[0] === "show") return { stdout: `${PRE} ${BASE}\n` };
    if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
    throw new Error(`unexpected git operation: ${op.join(" ")}`);
  };
  return { calls, runner };
}

// The issue Requirements section is the server-side binding for a requested
// requirement identity (issue #1434).
function requirementsThreadReader(body = "## Requirements\n- DSL-437\n") {
  return async () => ({ ok: true, body });
}

function completeInput() {
  return {
    repoPath: REPO_ROOT,
    issueNumber: ISSUE,
    branchName: BRANCH,
    action: "complete",
    recordId: RECORD,
    preSyncSha: PRE,
    fetchedBaseSha: BASE,
    outcome: "merged_clean",
  };
}

function shellCommands(calls) {
  return calls.filter(([command]) => command === "bash").map(([, args]) => args[1]);
}

function gitOperation(args) {
  const marker = args.indexOf("-C");
  return args.slice(marker + 2);
}

function startRunner({ current = false, conflict = false, fetchFailure = false } = {}) {
  const calls = [];
  const runner = async (command, args) => {
    calls.push([command, args]);
    if (command === "gh") {
      return {
        stdout: JSON.stringify({
          id: 99,
          html_url: "https://github.com/autarchy-ai/Ground-Control/issues/1421#issuecomment-99",
        }),
      };
    }
    const op = gitOperation(args);
    if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
    if (op[0] === "status") return { stdout: "" };
    if (op[0] === "fetch") {
      if (fetchFailure) {
        const error = new Error("fetch failed");
        error.stderr = "remote unavailable";
        throw error;
      }
      return { stdout: "" };
    }
    if (op[0] === "rev-parse") {
      const ref = op[op.length - 1];
      if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
      if (ref.startsWith("refs/remotes/origin/dev")) return { stdout: `${BASE}\n` };
      if (ref.startsWith("MERGE_HEAD")) return { stdout: `${BASE}\n` };
      return { stdout: `${PRE}\n` };
    }
    if (op[0] === "merge-base") {
      if (current) return { stdout: "" };
      const error = new Error("not ancestor");
      error.code = 1;
      throw error;
    }
    if (op[0] === "ls-remote") return { stdout: `${PRE}\trefs/heads/${BRANCH}\n` };
    if (op[0] === "merge") {
      if (conflict) {
        const error = new Error("merge conflict");
        error.code = 1;
        throw error;
      }
      return { stdout: "" };
    }
    if (op[0] === "ls-files") return { stdout: conflict ? "100644 a 1\tfile.txt\n" : "" };
    throw new Error(`unexpected git operation: ${op.join(" ")}`);
  };
  return { calls, runner };
}

describe("pre-PR implement synchronization", () => {
  it("accepts the canonical common hooks directory for a linked worktree", () => {
    assert.equal(isDefaultImplementHooksPath({
      repoRoot: "/repo/worktree",
      hooksPath: "/repo/main/.git/hooks",
      gitDir: "/repo/main/.git/worktrees/worktree",
      gitCommonDir: "/repo/main/.git",
    }), true);
    assert.equal(isDefaultImplementHooksPath({
      repoRoot: "/repo/worktree",
      hooksPath: "/tmp/caller-hooks",
      gitDir: "/repo/main/.git/worktrees/worktree",
      gitCommonDir: "/repo/main/.git",
    }), false);
  });

  it("renders and parses the complete versioned attestation", () => {
    const record = {
      recordId: RECORD,
      issueNumber: ISSUE,
      branchName: BRANCH,
      baseBranch: "dev",
      remoteRef: "refs/remotes/origin/dev",
      preSyncSha: PRE,
      fetchedBaseSha: BASE,
      outcome: "merged_clean",
      resultingFeatureSha: RESULT,
      verifiedTreeSha: TREE,
    };
    const parsed = parseImplementBaseSyncMarkers([buildImplementBaseSyncMarker(record)], ISSUE);
    assert.deepEqual(parsed, [{ valid: true, ...record }]);
  });

  it("rejects malformed and wrong-source attestation markers", () => {
    const marker = buildImplementBaseSyncMarker({
      recordId: RECORD,
      issueNumber: ISSUE,
      branchName: BRANCH,
      baseBranch: "dev",
      remoteRef: "refs/remotes/origin/dev",
      preSyncSha: PRE,
      fetchedBaseSha: BASE,
      outcome: "already_current",
      resultingFeatureSha: PRE,
    }).replace('source="refs/remotes/origin/dev"', 'source="refs/heads/dev"');
    assert.equal(parseImplementBaseSyncMarkers([marker], ISSUE)[0].valid, false);
  });

  it("records an already-current published feature without creating a merge", async () => {
    const { calls, runner } = startRunner({ current: true });
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "start",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.status, "complete");
    assert.equal(result.outcome, "already_current");
    assert.equal(calls.some(([, args]) => gitOperation(args)[0] === "merge"), false);
  });

  it("uses an explicit remote-tracking refspec and leaves a clean merge ready for final gates", async () => {
    const { calls, runner } = startRunner();
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "start",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.status, "merge_ready");
    assert.equal(result.outcome, "merged_clean");
    const fetch = calls.find(([, args]) => gitOperation(args)[0] === "fetch");
    assert.ok(fetch);
    assert.deepEqual(
      gitOperation(fetch[1]),
      ["fetch", "--no-tags", "origin", "+refs/heads/dev:refs/remotes/origin/dev"],
    );
    const merge = calls.find(([, args]) => gitOperation(args)[0] === "merge");
    assert.deepEqual(
      gitOperation(merge[1]),
      ["merge", "--no-ff", "--no-commit", "refs/remotes/origin/dev"],
    );
  });

  it("preserves conflict state and requires resolution in the feature checkout", async () => {
    const { runner } = startRunner({ conflict: true });
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "start",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.status, "conflicts");
    assert.equal(result.outcome, "merged_conflicts_resolved");
  });

  it("fails closed when the remote fetch fails even if local refs exist", async () => {
    const { runner } = startRunner({ fetchFailure: true });
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "start",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_base_sync_fetch_failed");
  });

  it("verifies both merge parents before an ordinary push and durable record", async () => {
    const calls = [];
    let committed = false;
    const runner = async (command, args) => {
      calls.push([command, args]);
      if (command === "bash" || command === "make") return { stdout: "" };
      if (command === "gh") {
        return {
          stdout: JSON.stringify({
            id: 100,
            html_url: "https://github.com/autarchy-ai/Ground-Control/issues/1421#issuecomment-100",
          }),
        };
      }
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "M  file.txt\n" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        if (ref.startsWith("MERGE_HEAD")) return { stdout: `${BASE}\n` };
        return { stdout: `${committed ? RESULT : PRE}\n` };
      }
      if (op[0] === "write-tree") return { stdout: `${TREE}\n` };
      if (op[0] === "ls-files") return { stdout: "" };
      if (op[0] === "commit") {
        committed = true;
        return { stdout: "" };
      }
      if (op[0] === "push") return { stdout: "" };
      if (op[0] === "show") return { stdout: `${PRE} ${BASE}\n` };
      if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
      throw new Error(`unexpected git operation: ${op.join(" ")}`);
    };
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "complete",
      recordId: RECORD,
      preSyncSha: PRE,
      fetchedBaseSha: BASE,
      outcome: "merged_conflicts_resolved",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      syncRecordReader: async () => ({
        ok: false,
        error: "implement_pr_sync_record_missing",
      }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.resultingFeatureSha, RESULT);
    assert.ok(calls.some(([, args]) => gitOperation(args)[0] === "push"));
    assert.ok(calls.some(([command]) => command === "gh"));
  });

  it("does not commit or push when the mechanically enforced final gates fail", async () => {
    const calls = [];
    const runner = async (command, args) => {
      calls.push([command, args]);
      if (command === "bash") throw new Error("completion failed");
      if (command === "make") return { stdout: "" };
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "M  file.txt\n" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.startsWith("MERGE_HEAD")) return { stdout: `${BASE}\n` };
        return { stdout: `${PRE}\n` };
      }
      if (op[0] === "ls-files") return { stdout: "" };
      if (op[0] === "write-tree") return { stdout: `${TREE}\n` };
      throw new Error(`unexpected operation: ${command} ${args.join(" ")}`);
    };
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "complete",
      recordId: RECORD,
      preSyncSha: PRE,
      fetchedBaseSha: BASE,
      outcome: "merged_clean",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_base_sync_gate_failed");
    assert.equal(calls.some(([, args]) => gitOperation(args)[0] === "commit"), false);
    assert.equal(calls.some(([, args]) => gitOperation(args)[0] === "push"), false);
  });

  it("runs the repository's configured policy command at the final-tree boundary (#1429)", async () => {
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch(completeInput(), {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context("dev", {
        policy_command: "python3 scripts/adr_guard/adr_guard.py --all --level ci",
      }),
      syncRecordReader: async () => ({ ok: false, error: "implement_pr_sync_record_missing" }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    // The envelope names the gate that actually ran, so a substituted policy
    // command is visible to the caller rather than hidden behind a generic
    // "policy passed". The durable issue-thread marker is unchanged.
    assert.equal(result.policyCommand, "python3 scripts/adr_guard/adr_guard.py --all --level ci");
    assert.deepEqual(shellCommands(calls), [
      "make check",
      "python3 scripts/adr_guard/adr_guard.py --all --level ci",
    ]);
    assert.equal(
      calls.some(([command, args]) => command === "make" && args.includes("policy")),
      false,
      "the boundary must not fall back to a hardcoded make target",
    );
  });

  it("defaults the policy gate to `make policy` when the repo configures none", async () => {
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch(completeInput(), {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context("dev", { policy_command: null }),
      syncRecordReader: async () => ({ ok: false, error: "implement_pr_sync_record_missing" }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.deepEqual(shellCommands(calls), ["make check", "make policy"]);
  });

  it("carries the requested requirement UID to both final-tree gates (#1434)", async () => {
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch({
      ...completeInput(),
      requestedRequirementUid: "DSL-437",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
      syncRecordReader: async () => ({ ok: false, error: "implement_pr_sync_record_missing" }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    const gateEnvs = calls
      .filter(([command]) => command === "bash")
      .map(([, , options]) => options?.env?.[REQUIREMENT_UID_GATE_ENV_VAR]);
    assert.deepEqual(gateEnvs, ["DSL-437", "DSL-437"]);
    assert.equal(
      calls.some(([, args]) => args.some((arg) => String(arg).includes("DSL-437"))),
      false,
      "the UID must reach the gate through the environment, never through argv",
    );
  });

  it("injects no requirement UID override when none is requested (#1434)", async () => {
    // The branch already carries its UID in this case, so the repository gate
    // must keep deriving requirement context exactly as it did before.
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch(completeInput(), {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      syncRecordReader: async () => ({ ok: false, error: "implement_pr_sync_record_missing" }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    for (const [command, , options] of calls.filter(([command]) => command === "bash")) {
      assert.equal(
        REQUIREMENT_UID_GATE_ENV_VAR in (options?.env ?? {}),
        false,
        `${command} gate must not receive an unrequested requirement override`,
      );
    }
  });

  it("refuses an invalid requested requirement UID before running any gate (#1434)", async () => {
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch({
      ...completeInput(),
      requestedRequirementUid: "DSL-437; rm -rf /",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_requested_requirement_uid_invalid");
    assert.equal(calls.length, 0);
  });

  it("authorizes the workspace before reading the issue thread (#1434)", async () => {
    // Reading the thread first would let a caller who is not authorized for
    // this workspace make the server query an arbitrary repository's issue,
    // and the authorized/out-of-scope split would then leak whether a guessed
    // UID appears in a private issue.
    let threadReads = 0;
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch({
      ...completeInput(),
      requestedRequirementUid: "DSL-437",
    }, {
      workspaceAuthorizationResolver: async () => {
        throw new Error("workspace not authorized");
      },
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: async () => {
        threadReads += 1;
        return { ok: true, body: "## Requirements\n- DSL-437\n" };
      },
    });

    assert.equal(result.ok, false);
    assert.equal(threadReads, 0, "no issue read may precede workspace authorization");
    assert.equal(calls.some(([command]) => command === "bash"), false);
  });

  it("refuses a requirement UID the target issue does not list (#1434)", async () => {
    // This tool is directly callable, so it cannot rely on bootstrap having
    // bound the UID to the issue. A valid-looking UID from another issue or
    // project must not become the gate's requirement identity.
    const { calls, runner } = completeRunner();
    const result = await runSynchronizeImplementBranch({
      ...completeInput(),
      requestedRequirementUid: "OTHER-999",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_requested_requirement_uid_out_of_scope");
    assert.equal(calls.length, 0);
  });

  it("does not commit, push, or attest when the configured policy command fails", async () => {
    const { calls, runner } = completeRunner({ failCommand: "bin/policy-gate" });
    const result = await runSynchronizeImplementBranch(completeInput(), {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context("dev", { policy_command: "bin/policy-gate" }),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_base_sync_gate_failed");
    assert.equal(calls.some(([, args]) => args[0] === "-C" && gitOperation(args)[0] === "commit"), false);
    assert.equal(calls.some(([, args]) => args[0] === "-C" && gitOperation(args)[0] === "push"), false);
    assert.equal(calls.some(([command]) => command === "gh"), false);
  });

  it("refuses to bind a verified tree while unstaged work is on disk (#1429)", async () => {
    // The gates execute against the working tree; the merge commit is built
    // from the index. An unstaged edit means the content that passed the gates
    // is not the content that ships, which silently voids the attestation's
    // verified-tree guarantee.
    const calls = [];
    const runner = async (command, args) => {
      calls.push([command, args]);
      if (command === "bash" || command === "make") return { stdout: "" };
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "M  staged.txt\n M unstaged.txt\n" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        if (ref.startsWith("MERGE_HEAD")) return { stdout: `${BASE}\n` };
        return { stdout: `${PRE}\n` };
      }
      if (op[0] === "write-tree") return { stdout: `${TREE}\n` };
      if (op[0] === "ls-files") return { stdout: "" };
      throw new Error(`unexpected git operation: ${op.join(" ")}`);
    };
    const result = await runSynchronizeImplementBranch(completeInput(), {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_base_sync_worktree_not_staged");
    assert.equal(
      calls.some(([command]) => command === "bash"),
      false,
      "the gates must not run against a tree that will not be committed",
    );
    assert.equal(calls.some(([, args]) => gitOperation(args)[0] === "commit"), false);
  });

  it("refuses an invalid repository context before touching the checkout (#1429)", async () => {
    const calls = [];
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "start",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: async (command, args) => {
        calls.push([command, args]);
        return { stdout: "" };
      },
      contextResolver: async () => ({
        status: "invalid",
        errors: ["workflow.policy_command must be a non-empty string when set"],
      }),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_base_sync_context_invalid");
    // A broken config must never fall through to the default base branch or
    // the default policy command.
    assert.equal(calls.length, 0);
  });

  it("resumes a valid committed merge and reuses its durable record", async () => {
    const calls = [];
    const runner = async (command, args) => {
      calls.push([command, args]);
      if (command === "bash" || command === "make") return { stdout: "" };
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.startsWith("MERGE_HEAD")) {
          const error = new Error("missing MERGE_HEAD");
          error.code = 128;
          throw error;
        }
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        return { stdout: `${RESULT}\n` };
      }
      if (op[0] === "write-tree") return { stdout: `${TREE}\n` };
      if (op[0] === "show") return { stdout: `${PRE} ${BASE}\n` };
      if (op[0] === "push") return { stdout: "" };
      if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
      throw new Error(`unexpected operation: ${command} ${args.join(" ")}`);
    };
    const record = {
      valid: true,
      recordId: RECORD,
      issueNumber: ISSUE,
      branchName: BRANCH,
      baseBranch: "dev",
      remoteRef: "refs/remotes/origin/dev",
      preSyncSha: PRE,
      fetchedBaseSha: BASE,
      outcome: "merged_clean",
      resultingFeatureSha: RESULT,
      verifiedTreeSha: TREE,
    };
    const result = await runSynchronizeImplementBranch({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      action: "complete",
      recordId: RECORD,
      preSyncSha: PRE,
      fetchedBaseSha: BASE,
      outcome: "merged_clean",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      syncRecordReader: async () => ({
        ok: true,
        record,
        commentId: 100,
        commentUrl: "https://github.com/autarchy-ai/Ground-Control/issues/1421#issuecomment-100",
      }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.resultingFeatureSha, RESULT);
    assert.equal(calls.some(([, args]) => gitOperation(args)[0] === "commit"), false);
    assert.equal(calls.some(([command]) => command === "gh"), false);
  });

  it("carries the requested requirement UID through the committed-retry gates (#1434)", async () => {
    const calls = [];
    const runner = async (command, args, options) => {
      calls.push([command, args, options]);
      if (command === "bash" || command === "make") return { stdout: "" };
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.startsWith("MERGE_HEAD")) {
          const error = new Error("missing MERGE_HEAD");
          error.code = 128;
          throw error;
        }
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        return { stdout: `${RESULT}\n` };
      }
      if (op[0] === "write-tree") return { stdout: `${TREE}\n` };
      if (op[0] === "show") return { stdout: `${PRE} ${BASE}\n` };
      if (op[0] === "push") return { stdout: "" };
      if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
      throw new Error(`unexpected operation: ${command} ${args.join(" ")}`);
    };
    const result = await runSynchronizeImplementBranch({
      ...completeInput(),
      requestedRequirementUid: "DSL-437",
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
      syncRecordReader: async () => ({
        ok: true,
        record: {
          valid: true,
          recordId: RECORD,
          issueNumber: ISSUE,
          branchName: BRANCH,
          baseBranch: "dev",
          remoteRef: "refs/remotes/origin/dev",
          preSyncSha: PRE,
          fetchedBaseSha: BASE,
          outcome: "merged_clean",
          resultingFeatureSha: RESULT,
          verifiedTreeSha: TREE,
        },
        commentId: 100,
        commentUrl: "https://github.com/autarchy-ai/Ground-Control/issues/1421#issuecomment-100",
      }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    const gateEnvs = calls
      .filter(([command]) => command === "bash")
      .map(([, , options]) => options?.env?.[REQUIREMENT_UID_GATE_ENV_VAR]);
    assert.deepEqual(gateEnvs, ["DSL-437", "DSL-437"]);
  });
});

describe("synchronized PR gate", () => {
  it("treats routing metadata as advisory and exposes no execution-control field", () => {
    const result = resolveWorkflowRouteFromConfig({
      routing: {
        enabled: true,
        default_provider: "claude",
        stages: {
          implementation: {
            tier: "medium",
            provider: "claude",
            model: "claude-sonnet-5",
          },
        },
      },
      stage: "implementation",
    });
    assert.equal(result.ok, true);
    assert.equal(result.model, "claude-sonnet-5");
    assert.equal("agent" in result, false);
    assert.equal("fallback" in result, false);
  });

  it("validates the configured Conventional Commit title shape", () => {
    assert.equal(validateImplementPrTitle("feat: merge dev before PR").ok, true);
    assert.equal(validateImplementPrTitle("feat: Merge dev before PR").ok, false);
    assert.equal(validateImplementPrTitle("fix/refactor: merge dev").ok, false);
  });

  it("refuses PR creation on an invalid repository context (#1429)", async () => {
    const calls = [];
    const result = await runCreateSynchronizedImplementPr({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      recordId: RECORD,
      title: "feat: require synchronized implement PRs",
      body: renderedPrBody(),
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: async (command, args) => {
        calls.push([command, args]);
        return { stdout: "" };
      },
      contextResolver: async () => ({
        status: "invalid",
        errors: ["workflow.policy_command must be a non-empty string when set"],
      }),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_pr_context_invalid");
    assert.equal(calls.length, 0);
  });

  it("refuses PR creation when the integration branch advances after attestation", async () => {
    let prCreateCalled = false;
    const runner = async (command, args) => {
      if (command === "gh") {
        if (args[0] === "pr" && args[1] === "create") prCreateCalled = true;
        throw new Error("unexpected GitHub write");
      }
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "" };
      if (op[0] === "fetch") return { stdout: "" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        if (ref.startsWith("refs/remotes/origin/dev")) return { stdout: `${"5".repeat(40)}\n` };
        return { stdout: `${RESULT}\n` };
      }
      if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
      if (op[0] === "merge-base") return { stdout: "" };
      throw new Error(`unexpected git operation: ${op.join(" ")}`);
    };
    const result = await runCreateSynchronizedImplementPr({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      recordId: RECORD,
      title: "feat: require synchronized implement PRs",
      body: renderedPrBody(),
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
      syncRecordReader: async () => ({
        ok: true,
        record: {
          valid: true,
          recordId: RECORD,
          issueNumber: ISSUE,
          branchName: BRANCH,
          baseBranch: "dev",
          remoteRef: "refs/remotes/origin/dev",
          preSyncSha: PRE,
          fetchedBaseSha: BASE,
          outcome: "merged_clean",
          resultingFeatureSha: RESULT,
          verifiedTreeSha: TREE,
        },
      }),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_pr_sync_stale");
    assert.equal(result.next_action, "return_to_the_synchronization_boundary");
    assert.equal(prCreateCalled, false);
  });

  it("pins PR lookup and creation to the authorized repository", async () => {
    const calls = [];
    const runner = async (command, args) => {
      calls.push([command, args]);
      if (command === "gh") {
        if (args[1] === "list") return { stdout: "[]\n" };
        if (args[1] === "create") {
          return { stdout: "https://github.com/autarchy-ai/Ground-Control/pull/200\n" };
        }
      }
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "" };
      if (op[0] === "fetch" || op[0] === "merge-base") return { stdout: "" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        if (ref.startsWith("refs/remotes/origin/dev")) return { stdout: `${BASE}\n` };
        return { stdout: `${RESULT}\n` };
      }
      if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
      throw new Error(`unexpected operation: ${command} ${args.join(" ")}`);
    };
    const result = await runCreateSynchronizedImplementPr({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      recordId: RECORD,
      title: "feat: require synchronized implement PRs",
      body: renderedPrBody(),
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
      syncRecordReader: async () => ({
        ok: true,
        record: {
          recordId: RECORD,
          issueNumber: ISSUE,
          branchName: BRANCH,
          baseBranch: "dev",
          remoteRef: "refs/remotes/origin/dev",
          preSyncSha: PRE,
          fetchedBaseSha: BASE,
          outcome: "merged_clean",
          resultingFeatureSha: RESULT,
          verifiedTreeSha: TREE,
        },
      }),
    });
    assert.equal(result.ok, true, JSON.stringify(result));
    const ghCalls = calls.filter(([command]) => command === "gh");
    assert.equal(ghCalls.length, 2);
    for (const [, args] of ghCalls) {
      assert.deepEqual(args.slice(args.indexOf("--repo"), args.indexOf("--repo") + 2), [
        "--repo",
        "autarchy-ai/ground-control",
      ]);
    }
  });

  it("refuses an existing PR whose base or rendered content does not match", async () => {
    let createCalled = false;
    const runner = async (command, args) => {
      if (command === "gh") {
        if (args[1] === "create") createCalled = true;
        return {
          stdout: JSON.stringify([{
            number: 201,
            url: "https://github.com/autarchy-ai/Ground-Control/pull/201",
            baseRefName: "main",
            headRefName: BRANCH,
            headRefOid: RESULT,
            headRepository: { name: "Ground-Control" },
            headRepositoryOwner: { login: "autarchy-ai" },
            isCrossRepository: false,
            title: "feat: require synchronized implement PRs",
            body: "attacker-controlled body",
          }]),
        };
      }
      const op = gitOperation(args);
      if (op[0] === "symbolic-ref") return { stdout: `${BRANCH}\n` };
      if (op[0] === "status") return { stdout: "" };
      if (op[0] === "fetch" || op[0] === "merge-base") return { stdout: "" };
      if (op[0] === "rev-parse") {
        const ref = op[op.length - 1];
        if (ref.endsWith("^{tree}")) return { stdout: `${TREE}\n` };
        if (ref.startsWith("refs/remotes/origin/dev")) return { stdout: `${BASE}\n` };
        return { stdout: `${RESULT}\n` };
      }
      if (op[0] === "ls-remote") return { stdout: `${RESULT}\trefs/heads/${BRANCH}\n` };
      throw new Error(`unexpected operation: ${command} ${args.join(" ")}`);
    };
    const result = await runCreateSynchronizedImplementPr({
      repoPath: REPO_ROOT,
      issueNumber: ISSUE,
      branchName: BRANCH,
      recordId: RECORD,
      title: "feat: require synchronized implement PRs",
      body: renderedPrBody(),
    }, {
      workspaceAuthorizationResolver: workspaceAuthorization,
      commandRunner: runner,
      contextResolver: async () => context(),
      issueThreadReader: requirementsThreadReader(),
      syncRecordReader: async () => ({
        ok: true,
        record: {
          recordId: RECORD,
          issueNumber: ISSUE,
          branchName: BRANCH,
          baseBranch: "dev",
          remoteRef: "refs/remotes/origin/dev",
          preSyncSha: PRE,
          fetchedBaseSha: BASE,
          outcome: "merged_clean",
          resultingFeatureSha: RESULT,
          verifiedTreeSha: TREE,
        },
      }),
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_pr_existing_identity_mismatch");
    assert.equal(createCalled, false);
  });
});
