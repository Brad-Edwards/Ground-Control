// Split from gc-implement-contract.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { runPrepareImplementBranch, validateImplementBranchName } from "./lib.js";

function initRepo() {
  const repo = mkdtempSync(join(tmpdir(), "gc-implement-contract-"));
  execFileSync("git", ["-C", repo, "init", "-q"]);
  execFileSync("git", ["-C", repo, "config", "user.email", "test@example.com"]);
  execFileSync("git", ["-C", repo, "config", "user.name", "Test"]);
  writeFileSync(join(repo, "README.md"), "test\n");
  execFileSync("git", ["-C", repo, "add", "README.md"]);
  execFileSync("git", ["-C", repo, "commit", "-q", "-m", "initial"]);
  execFileSync("git", ["-C", repo, "branch", "-M", "dev"]);
  execFileSync("git", ["-C", repo, "remote", "add", "origin", "https://github.com/example/repo.git"]);
  return repo;
}

function authorizationForRepo(repo) {
  // A freshly `git init`'d repo is a main worktree, so --absolute-git-dir and
  // --git-common-dir resolve to the same `.git` (issue #1502).
  const gitDir = realpathSync(
    execFileSync("git", ["-C", repo, "rev-parse", "--absolute-git-dir"], { encoding: "utf8" }).trim(),
  );
  return {
    workspaceRoot: realpathSync(repo),
    gitDir,
    gitCommonDir: gitDir,
    origin: execFileSync(
      "git", ["-C", repo, "remote", "get-url", "origin"], { encoding: "utf8" },
    ).trim(),
    owner: "example",
    name: "repo",
  };
}

function installGhDevelopShim(bin, logPath) {
  const body = `#!/usr/bin/env node
const fs = require("node:fs");
const cp = require("node:child_process");
const argv = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(logPath)}, JSON.stringify(argv) + "\\n");
const nameAt = argv.indexOf("--name");
if (argv[0] !== "issue" || argv[1] !== "develop" || nameAt < 0) process.exit(2);
const branch = argv[nameAt + 1];
let result = cp.spawnSync("/usr/bin/git", ["-C", process.cwd(), "switch", branch], {stdio:"ignore"});
if (result.status !== 0) {
  result = cp.spawnSync("/usr/bin/git", ["-C", process.cwd(), "switch", "-c", branch], {stdio:"inherit"});
}
process.exit(result.status ?? 1);
`;
  writeFileSync(join(bin, "gh"), body, { mode: 0o755 });
}

async function withPath(bin, fn) {
  const old = process.env.PATH;
  process.env.PATH = `${bin}:${old}`;
  try {
    return await fn();
  } finally {
    process.env.PATH = old;
  }
}

describe("same-checkout /implement branch operation", () => {
  it("creates the issue branch in the invocation checkout without a worktree command", async () => {
    const repo = initRepo();
    const bin = mkdtempSync(join(tmpdir(), "gc-implement-bin-"));
    const log = join(bin, "gh.log");
    writeFileSync(log, "");
    installGhDevelopShim(bin, log);
    try {
      const result = await withPath(bin, () =>
        runPrepareImplementBranch({
          repoPath: repo,
          invocationRoot: repo,
          issueNumber: 1416,
          branchName: "1416-implement-principles",
          baseBranch: "dev",
          checkoutMode: "same_checkout",
        }, { workspaceAuthorizationResolver: async () => authorizationForRepo(repo) }),
      );
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.repo_path, realpathSync(repo));
      assert.equal(result.branch, "1416-implement-principles");
      assert.equal(result.origin, undefined);
      assert.equal(
        execFileSync("git", ["-C", repo, "rev-parse", "--show-toplevel"], { encoding: "utf8" }).trim(),
        realpathSync(repo),
      );
      assert.doesNotMatch(readFileSync(log, "utf8"), /worktree/);
    } finally {
      rmSync(repo, { recursive: true, force: true });
      rmSync(bin, { recursive: true, force: true });
    }
  });

  it("authorizes when only the per-worktree Git dir diverges (issue #1502)", async () => {
    const repo = initRepo();
    const bin = mkdtempSync(join(tmpdir(), "gc-implement-bin-"));
    const log = join(bin, "gh.log");
    writeFileSync(log, "");
    installGhDevelopShim(bin, log);
    try {
      // A concurrent /implement in a sibling linked worktree (or an MCP relaunch) can
      // shift the captured per-worktree --absolute-git-dir while the shared repository
      // store, origin, and owner/name are unchanged. The guard pins the common dir, so
      // this no longer fails with implement_repo_identity_changed.
      const authorization = {
        ...authorizationForRepo(repo),
        gitDir: join(repo, ".git", "worktrees", "stale-pointer"),
      };
      const result = await withPath(bin, () =>
        runPrepareImplementBranch({
          repoPath: repo,
          invocationRoot: repo,
          issueNumber: 1502,
          branchName: "1502-worktree-identity-guard",
          baseBranch: "dev",
          checkoutMode: "same_checkout",
        }, { workspaceAuthorizationResolver: async () => authorization }),
      );
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.branch, "1502-worktree-identity-guard");
    } finally {
      rmSync(repo, { recursive: true, force: true });
      rmSync(bin, { recursive: true, force: true });
    }
  });

  it("fails closed when the invocation root is not the supplied checkout", async () => {
    const repo = initRepo();
    const other = mkdtempSync(join(tmpdir(), "gc-other-checkout-"));
    try {
      const result = await runPrepareImplementBranch({
        repoPath: repo,
        invocationRoot: other,
        issueNumber: 1416,
        branchName: "1416-implement-principles",
        baseBranch: "dev",
        checkoutMode: "same_checkout",
      }, { workspaceAuthorizationResolver: async () => authorizationForRepo(repo) });
      assert.equal(result.ok, false);
      assert.equal(result.error, "implement_invocation_root_mismatch");
    } finally {
      rmSync(repo, { recursive: true, force: true });
      rmSync(other, { recursive: true, force: true });
    }
  });

  it("never executes a caller-controlled post-checkout hook", async () => {
    const repo = initRepo();
    const bin = mkdtempSync(join(tmpdir(), "gc-implement-bin-"));
    const log = join(bin, "gh.log");
    const hookResult = join(bin, "hook-ran");
    writeFileSync(log, "");
    installGhDevelopShim(bin, log);
    const hooks = join(repo, ".git", "hooks");
    mkdirSync(hooks, { recursive: true });
    writeFileSync(
      join(hooks, "post-checkout"),
      `#!/bin/sh\nprintf ran > ${JSON.stringify(hookResult)}\n`,
      { mode: 0o755 },
    );
    try {
      const authorization = authorizationForRepo(repo);
      const result = await withPath(bin, () =>
        runPrepareImplementBranch({
          repoPath: repo,
          invocationRoot: repo,
          issueNumber: 1416,
          branchName: "1416-implement-principles",
          checkoutMode: "same_checkout",
        }, { workspaceAuthorizationResolver: async () => authorization }),
      );
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(readFileSync(log, "utf8").includes("issue"), true);
      assert.throws(() => readFileSync(hookResult));
    } finally {
      rmSync(repo, { recursive: true, force: true });
      rmSync(bin, { recursive: true, force: true });
    }
  });

  it("rejects a mutable origin retarget before branch mutation", async () => {
    const repo = initRepo();
    try {
      const authorization = authorizationForRepo(repo);
      execFileSync("git", [
        "-C", repo, "remote", "set-url", "origin", "https://github.com/example/other.git",
      ]);
      const result = await runPrepareImplementBranch({
        repoPath: repo,
        invocationRoot: repo,
        issueNumber: 1416,
        branchName: "1416-implement-principles",
      }, { workspaceAuthorizationResolver: async () => authorization });
      assert.equal(result.ok, false);
      assert.equal(result.error, "implement_repo_identity_changed");
    } finally {
      rmSync(repo, { recursive: true, force: true });
    }
  });

  it("rejects branch mutation outside the MCP launch workspace", async () => {
    const repo = initRepo();
    try {
      const result = await runPrepareImplementBranch({
        repoPath: repo,
        invocationRoot: repo,
        issueNumber: 1416,
        branchName: "1416-implement-principles",
        checkoutMode: "same_checkout",
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "implement_repo_not_authorized");
    } finally {
      rmSync(repo, { recursive: true, force: true });
    }
  });

  it("enforces the existing issue branch shape", () => {
    assert.deepEqual(validateImplementBranchName("1416-fix-branch", 1416), { ok: true });
    assert.equal(validateImplementBranchName("feature/1416-fix", 1416).ok, false);
    assert.equal(validateImplementBranchName(`1416-${"x".repeat(50)}`, 1416).ok, false);
  });
});
