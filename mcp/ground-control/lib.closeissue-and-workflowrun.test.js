// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { createWorkflowRun } from "./lib.js";

// ---------------------------------------------------------------------------
// gc_assert_traceability_reconciled (issue #1058)
// ---------------------------------------------------------------------------

// Shared module-scope helpers for the traceability/final-report/close-issue
// suites below. Hoisted out of the individual `describe` callbacks so the
// route-replaying gh shim source, the git-init boilerplate, and the
// PATH-wrapping runner are defined exactly once (Sonar S7721/S4144/S138).
const GH_NAME_WITH_OWNER = "nameWithOwner";

function initGitRepo(dir) {
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  // Real origin so owner/repo resolves from the git remote, as production does. git ignores
  // GH_REPO; the `gh repo view` fallback honours it.
  execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/fake/repo.git"]);
  return dir;
}

// Source for a hermetic `gh` shim that replays cfg.routes by argv prefix.
// String.raw keeps the `\n` in the unhandled-argv diagnostic literal (S7780).
function buildGhRouteShimSource(configPath) {
  return String.raw`#!/usr/bin/env node
const fs = require("node:fs");
const cfg = JSON.parse(fs.readFileSync(${JSON.stringify(configPath)}, "utf8"));
const argv = process.argv.slice(2);
function match(prefix) { return prefix.every((p, i) => argv[i] === p); }
for (const route of cfg.routes) {
  if (match(route.argv_prefix)) {
    if (route.exit_code != null && route.exit_code !== 0) {
      process.stderr.write(route.stderr || "");
      process.exit(route.exit_code);
    }
    process.stdout.write(route.stdout || "");
    process.exit(0);
  }
}
process.stderr.write("gh shim: unhandled argv: " + JSON.stringify(argv) + "\n");
process.exit(2);
`;
}

// Materializes a git repo + a bin dir holding a `gh` shim that replays
// `ghHandler.routes`. Returns { repoDir, binDir, cleanup }.
function makeRouteShimRepo({ ghHandler, repoPrefix, binPrefix }) {
  const repoDir = initGitRepo(mkdtempSync(join(tmpdir(), repoPrefix)));
  const binDir = mkdtempSync(join(tmpdir(), binPrefix));
  const configPath = join(binDir, "config.json");
  writeFileSync(configPath, JSON.stringify(ghHandler));
  writeFileSync(join(binDir, "gh"), buildGhRouteShimSource(configPath), { mode: 0o755 });
  return {
    repoDir, binDir,
    cleanup() { rmSync(repoDir, { recursive: true, force: true }); rmSync(binDir, { recursive: true, force: true }); },
  };
}

async function withShimPath(binDir, fn) {
  const oldPath = process.env.PATH;
  process.env.PATH = `${binDir}:${oldPath}`;
  try { return await fn(); } finally { process.env.PATH = oldPath; }
}

// ---------------------------------------------------------------------------
// Workflow-run telemetry lib helpers (issue #859)
// ---------------------------------------------------------------------------

const WORKFLOW_RUN_BASE_URL = "https://gc.test";

const WORKFLOW_RUN_ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

const WORKFLOW_RUN_ORIGINAL_FETCH = globalThis.fetch;

function withWorkflowRunEnv(fn) {
  return async () => {
    process.env.GC_BASE_URL = WORKFLOW_RUN_BASE_URL;
    delete process.env.GROUND_CONTROL_API_TOKEN;
    try {
      await fn();
    } finally {
      if (WORKFLOW_RUN_ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = WORKFLOW_RUN_ORIGINAL_BASE_URL;
      globalThis.fetch = WORKFLOW_RUN_ORIGINAL_FETCH;
    }
  };
}

function makeWorkflowRunFetchSpy({ status = 201, body = {} } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    const parsedBody = opts && opts.body ? JSON.parse(opts.body) : null;
    calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: parsedBody });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

// ---------------------------------------------------------------------------
// gc_close_issue_after_merge (issue #1058)
// ---------------------------------------------------------------------------

describe("runCloseIssueAfterMerge", () => {
  // Repeated fixture literals, hoisted to named constants (Sonar S1192).
  const PR_MERGED_AT = "2026-05-30T10:00:00Z";
  const LINKED_PR_URL = "https://github.com/fake/repo/pull/42";
  const ISSUE_API_PATH = "/repos/fake/repo/issues/1058";

  function makeShimRepo({ ghHandler }) {
    return makeRouteShimRepo({ ghHandler, repoPrefix: "gc-close-test-", binPrefix: "gc-close-bin-" });
  }

  // Runs runCloseIssueAfterMerge against `shim` (on the shimmed PATH) and hands
  // the structured result to `assertResult`, then cleans the shim up. Removes
  // the import + path-wrap + try/finally cleanup boilerplate repeated by the
  // result-asserting cases.
  async function withCloseResult(shim, issueNumber, assertResult) {
    try {
      await withShimPath(shim.binDir, async () => {
        const { runCloseIssueAfterMerge } = await import("./lib.js");
        const r = await runCloseIssueAfterMerge({ repoPath: shim.repoDir, issueNumber });
        assertResult(r);
      });
    } finally {
      shim.cleanup();
    }
  }

  it("throws on invalid issue_number (input validation)", async () => {
    const shim = makeShimRepo({ ghHandler: { routes: [] } });
    try {
      const { runCloseIssueAfterMerge } = await import("./lib.js");
      await assert.rejects(
        runCloseIssueAfterMerge({ repoPath: shim.repoDir, issueNumber: 0 }),
        /positive integer issue_number/,
      );
    } finally {
      shim.cleanup();
    }
  });

  it("refuses with close_no_linked_pr when no PR is linked to the issue", async () => {
    const shim = makeShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", "nameWithOwner"], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          // GraphQL timeline returns no PR cross-references.
          { argv_prefix: ["api", "graphql"], stdout: JSON.stringify({ data: { repository: { issue: { timelineItems: { nodes: [] } } } } }) },
        ],
      },
    });
    await withCloseResult(shim, 1058, (r) => {
        assert.equal(r.ok, false);
        assert.equal(r.error, "close_no_linked_pr");
    });
  });

  it("refuses with close_pr_not_merged when linked PR has merged_at=null and state=open", async () => {
    const shim = makeShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", "nameWithOwner"], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "graphql"], stdout: JSON.stringify({
            data: { repository: { issue: { timelineItems: { nodes: [
              { __typename: "CrossReferencedEvent", source: { __typename: "PullRequest", number: 42, state: "OPEN", mergedAt: null, url: LINKED_PR_URL } },
            ] } } } },
          }) },
        ],
      },
    });
    await withCloseResult(shim, 1058, (r) => {
        assert.equal(r.ok, false);
        assert.equal(r.error, "close_pr_not_merged");
        assert.equal(r.pr_state, "OPEN");
        assert.equal(r.pr_merged_at, null);
    });
  });

  it("closes open issue when linked PR is merged", async () => {
    const shim = makeShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", "nameWithOwner"], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "graphql"], stdout: JSON.stringify({
            data: { repository: { issue: { timelineItems: { nodes: [
              { __typename: "CrossReferencedEvent", source: { __typename: "PullRequest", number: 42, state: "MERGED", mergedAt: PR_MERGED_AT, url: LINKED_PR_URL } },
            ] } } } },
          }) },
          // Issue lookup — current state=open.
          { argv_prefix: ["api", ISSUE_API_PATH], stdout: JSON.stringify({ number: 1058, state: "open" }) },
          // PATCH close.
          { argv_prefix: ["api", "--method", "PATCH"], stdout: JSON.stringify({ number: 1058, state: "closed" }) },
        ],
      },
    });
    // ADR-089 §5: the close path performs ONLY linked-PR resolution,
    // merge-state verification, and idempotent close — no issue listing, no
    // ranking, and no recommendation field (not even null; a null field would
    // still advertise the retired feature).
    await withCloseResult(shim, 1058, (r) => {
        assert.equal(r.ok, true);
        assert.equal(r.already_closed, false);
        assert.equal(r.pr_number, 42);
        assert.equal(r.pr_merged_at, PR_MERGED_AT);
        assert.ok(!("next_issue_recommendation" in r), "next_issue_recommendation must not be present");
        assert.ok(!("next_issue_recommendation_reason" in r), "next_issue_recommendation_reason must not be present");
        assert.ok(!("next_issue_recommendation_source" in r), "next_issue_recommendation_source must not be present");
        assert.ok(!("next_issue_recommendation_error" in r), "next_issue_recommendation_error must not be present");
    });
  });

  it("idempotent no-op when issue is already closed", async () => {
    const shim = makeShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", "nameWithOwner"], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "graphql"], stdout: JSON.stringify({
            data: { repository: { issue: { timelineItems: { nodes: [
              { __typename: "CrossReferencedEvent", source: { __typename: "PullRequest", number: 42, state: "MERGED", mergedAt: PR_MERGED_AT, url: LINKED_PR_URL } },
            ] } } } },
          }) },
          // Issue is already closed.
          { argv_prefix: ["api", ISSUE_API_PATH], stdout: JSON.stringify({ number: 1058, state: "closed" }) },
        ],
      },
    });
    await withCloseResult(shim, 1058, (r) => {
        assert.equal(r.ok, true);
        assert.equal(r.already_closed, true);
        assert.equal(r.pr_number, 42);
    });
  });

  // Codex review cycle 1 (issue #1058): a caller-supplied pr_number must be
  // verified as linked to the issue before it gates the close. Without this
  // check, a caller could pass any merged PR + an unrelated issue number and
  // cause the wrong issue to close. The runner now resolves the issue's
  // timeline first and refuses if the supplied PR is not present.
  it("refuses with close_pr_not_linked_to_issue when supplied pr_number is not in the issue's timeline-linked PR set", async () => {
    const shim = makeShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", "nameWithOwner"], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          // Issue 1058's timeline links PR 42 (merged).
          { argv_prefix: ["api", "graphql"], stdout: JSON.stringify({
            data: { repository: { issue: { timelineItems: { nodes: [
              { __typename: "CrossReferencedEvent", source: { __typename: "PullRequest", number: 42, state: "MERGED", mergedAt: PR_MERGED_AT, url: LINKED_PR_URL } },
            ] } } } },
          }) },
        ],
      },
    });
    try {
      await withShimPath(shim.binDir, async () => {
        const { runCloseIssueAfterMerge } = await import("./lib.js");
        // Caller passes PR #99, which is NOT one of issue 1058's linked PRs.
        const r = await runCloseIssueAfterMerge({ repoPath: shim.repoDir, issueNumber: 1058, prNumber: 99 });
        assert.equal(r.ok, false);
        assert.equal(r.error, "close_pr_not_linked_to_issue");
        assert.deepEqual(r.linked_pr_numbers, [42]);
      });
    } finally {
      shim.cleanup();
    }
  });

  it("uses the supplied pr_number when it IS in the issue's timeline-linked PR set", async () => {
    const shim = makeShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", "nameWithOwner"], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "graphql"], stdout: JSON.stringify({
            data: { repository: { issue: { timelineItems: { nodes: [
              { __typename: "CrossReferencedEvent", source: { __typename: "PullRequest", number: 42, state: "MERGED", mergedAt: PR_MERGED_AT, url: LINKED_PR_URL } },
            ] } } } },
          }) },
          { argv_prefix: ["api", ISSUE_API_PATH], stdout: JSON.stringify({ number: 1058, state: "open" }) },
          { argv_prefix: ["api", "--method", "PATCH"], stdout: JSON.stringify({ number: 1058, state: "closed" }) },
        ],
      },
    });
    try {
      await withShimPath(shim.binDir, async () => {
        const { runCloseIssueAfterMerge } = await import("./lib.js");
        const r = await runCloseIssueAfterMerge({ repoPath: shim.repoDir, issueNumber: 1058, prNumber: 42 });
        assert.equal(r.ok, true);
        assert.equal(r.already_closed, false);
        assert.equal(r.pr_number, 42);
      });
    } finally {
      shim.cleanup();
    }
  });
});

describe("createWorkflowRun", () => {
  it(
    "POSTs to /api/v1/workflow-runs with project as query param",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: { id: "wrun-1" } });
      await createWorkflowRun(
        { workflow_type: "IMPLEMENT", provenance: "ISSUE_THREAD" },
        "proj-a",
      );
      assert.equal(calls.length, 1);
      assert.equal(calls[0].method, "POST");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs");
      assert.equal(url.searchParams.get("project"), "proj-a");
    }),
  );

  it(
    "sends camelCase body to the backend",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: { id: "x" } });
      await createWorkflowRun({
        workflow_type: "IMPLEMENT",
        provenance: "ISSUE_THREAD",
        issue_number: 42,
        requirement_uids: ["GC-O007"],
      });
      assert.equal(calls[0].body.workflowType, "IMPLEMENT");
      assert.equal(calls[0].body.provenance, "ISSUE_THREAD");
      assert.equal(calls[0].body.issueNumber, 42);
      assert.deepEqual(calls[0].body.requirementUids, ["GC-O007"]);
    }),
  );

  it(
    "omits the project query param when not provided",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: {} });
      await createWorkflowRun({ workflow_type: "IMPLEMENT", provenance: "MANUAL_IMPORT" });
      const url = new URL(calls[0].url);
      assert.equal(url.searchParams.get("project"), null);
    }),
  );
});
