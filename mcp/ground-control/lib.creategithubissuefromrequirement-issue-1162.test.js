// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { createGitHubIssueFromRequirement, toSnakeCase } from "./lib.js";

// ---------------------------------------------------------------------------
// createGitHubIssueFromRequirement (issue #1162)
// ---------------------------------------------------------------------------

describe("createGitHubIssueFromRequirement (issue #1162)", () => {
  // The requirement lookup and traceability link go through request()/fetch;
  // the issue creation shells out to `gh` via execFile. Mock global.fetch for
  // the REST calls and PATH-shim a fake `gh` that records its argv and prints
  // an issue URL. This is the regression guard for the original defect: the tool
  // ran `gh issue create --title undefined --body undefined`.

  function makeGhShim(number) {
    const binDir = mkdtempSync(join(tmpdir(), "gc-cgi-bin-"));
    const argvLog = join(binDir, "argv.json");
    const ghShim = `#!/usr/bin/env node
const fs = require("node:fs");
fs.writeFileSync(${JSON.stringify(argvLog)}, JSON.stringify(process.argv.slice(2)));
process.stdout.write("https://github.com/o/r/issues/${number}\\n");
process.exit(0);
`;
    writeFileSync(join(binDir, "gh"), ghShim, { mode: 0o755 });
    return {
      binDir,
      argvLog,
      ghCalled() { return existsSync(argvLog); },
      ghArgv() { return JSON.parse(readFileSync(argvLog, "utf8")); },
      cleanup() { rmSync(binDir, { recursive: true, force: true }); },
    };
  }

  // createGitHubIssue now derives the target slug from the checkout's git
  // origin remote (GC-P026, #1383) and fails closed without one. Give each
  // test a real throwaway git repo whose origin is https://github.com/<slug>.git
  // so the derived slug matches the asserted `repo` and the existing
  // `--repo <slug>` argv assertions stay valid.
  function makeGitRepoWithOrigin(slug) {
    const dir = mkdtempSync(join(tmpdir(), "gc-cgi-repo-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    execFileSync("git", ["-C", dir, "remote", "add", "origin", `https://github.com/${slug}.git`]);
    return dir;
  }

  function makeFetchMock({ requirement, requirementStatus = 200, traceabilityFails = false }) {
    const calls = [];
    const fn = async (url, opts = {}) => {
      const u = String(url);
      calls.push({ url: u, method: opts.method, body: opts.body });
      if (u.includes("/api/v1/requirements/uid/")) {
        if (requirementStatus !== 200) {
          return {
            status: requirementStatus, ok: false,
            text: async () => JSON.stringify({ code: "not_found", message: "no such requirement" }),
          };
        }
        return { status: 200, ok: true, text: async () => JSON.stringify(requirement) };
      }
      if (u.includes("/traceability")) {
        if (traceabilityFails) {
          return {
            status: 500, ok: false,
            text: async () => JSON.stringify({ code: "internal_error", message: "link boom" }),
          };
        }
        return { status: 201, ok: true, text: async () => JSON.stringify({ id: "link-1" }) };
      }
      return { status: 404, ok: false, text: async () => "{}" };
    };
    return { calls, fn };
  }

  async function withEnv(binDir, fetchFn, run) {
    const oldPath = process.env.PATH;
    const oldFetch = globalThis.fetch;
    const oldBaseUrl = process.env.GC_BASE_URL;
    process.env.PATH = `${binDir}:${oldPath}`;
    process.env.GC_BASE_URL = "http://gc.test";
    globalThis.fetch = fetchFn;
    try { return await run(); } finally {
      process.env.PATH = oldPath;
      globalThis.fetch = oldFetch;
      if (oldBaseUrl === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = oldBaseUrl;
    }
  }

  const ACTIVE_REQ = {
    id: "11111111-1111-1111-1111-111111111111",
    uid: "AGT-001",
    title: "Agent Orchestration / ReAct Planning Layer",
    requirement_type: "FUNCTIONAL",
    priority: "SHOULD",
    wave: 1,
    status: "ACTIVE",
    statement: "The system shall orchestrate agents.",
    rationale: "Needed for planning.",
  };

  it("renders a real title and body and creates an IMPLEMENTS link for an ACTIVE requirement", async () => {
    const shim = makeGhShim(431);
    const repoDir = makeGitRepoWithOrigin("o/r");
    const mock = makeFetchMock({ requirement: ACTIVE_REQ });
    try {
      const result = await withEnv(shim.binDir, mock.fn, () =>
        createGitHubIssueFromRequirement({
          uid: "AGT-001",
          project: "aptl",
          repo: "o/r",
          repoRoot: repoDir,
          labels: ["requirement", "wave-1"],
          extraBody: "## Notes\n\nextra context",
        }),
      );

      assert.equal(result.url, "https://github.com/o/r/issues/431");
      assert.equal(result.number, 431);
      assert.equal(result.requirement_uid, "AGT-001");
      assert.equal(result.link_type, "IMPLEMENTS");
      assert.ok(result.traceability_link, "traceability link should be returned");
      assert.equal(result.traceability_error, undefined);

      // gh was invoked with a derived title/body — never the literal "undefined".
      assert.ok(shim.ghCalled(), "gh should have been called");
      const argv = shim.ghArgv();
      const title = argv[argv.indexOf("--title") + 1];
      const body = argv[argv.indexOf("--body") + 1];
      assert.equal(title, "AGT-001 — Agent Orchestration / ReAct Planning Layer");
      assert.notEqual(title, "undefined");
      assert.notEqual(body, "undefined");
      assert.ok(body.includes("## Requirements"));
      assert.ok(body.includes("- AGT-001 — Agent Orchestration / ReAct Planning Layer"));
      assert.ok(body.includes("## Notes"));
      assert.deepEqual(argv.slice(argv.indexOf("--repo"), argv.indexOf("--repo") + 2), ["--repo", "o/r"]);
      assert.equal(argv[argv.indexOf("--label") + 1], "requirement,wave-1");

      // The traceability link uses the raw issue number as artifact_identifier.
      const linkCall = mock.calls.find((c) => c.url.includes("/traceability"));
      const linkBody = JSON.parse(linkCall.body);
      assert.equal(linkBody.artifactType, "GITHUB_ISSUE");
      assert.equal(linkBody.artifactIdentifier, "431");
      assert.equal(linkBody.linkType, "IMPLEMENTS");
      assert.ok(linkCall.url.includes(`/requirements/${ACTIVE_REQ.id}/traceability`));
    } finally {
      shim.cleanup();
      rmSync(repoDir, { recursive: true, force: true });
    }
  });

  it("renders the title from an API-response-shaped requirement (folder_title, no title)", async () => {
    // The production path is getRequirementByUid -> request() -> toSnakeCase,
    // which renames `title` -> `folder_title`, so a real requirement arrives
    // with folder_title set and title absent. This fixture mirrors that exact
    // shape (no `title` key at all) so the folder_title branch is exercised
    // explicitly, independent of toSnakeCase normalization — guarding against a
    // regression that dropped the folder_title read and reintroduced the
    // undefined-title defect.
    const API_REQ = {
      id: "22222222-2222-2222-2222-222222222222",
      uid: "AGT-002",
      folder_title: "Memory Subsystem",
      requirement_type: "FUNCTIONAL",
      priority: "SHOULD",
      wave: 1,
      status: "ACTIVE",
      statement: "The system shall remember.",
    };
    const shim = makeGhShim(512);
    const repoDir = makeGitRepoWithOrigin("o/r");
    const mock = makeFetchMock({ requirement: API_REQ });
    try {
      const result = await withEnv(shim.binDir, mock.fn, () =>
        createGitHubIssueFromRequirement({ uid: "AGT-002", project: "aptl", repo: "o/r", repoRoot: repoDir }),
      );
      assert.equal(result.number, 512);
      const argv = shim.ghArgv();
      const title = argv[argv.indexOf("--title") + 1];
      const body = argv[argv.indexOf("--body") + 1];
      assert.equal(title, "AGT-002 — Memory Subsystem");
      assert.notEqual(title, "undefined");
      assert.ok(body.includes("- AGT-002 — Memory Subsystem"));
    } finally {
      shim.cleanup();
      rmSync(repoDir, { recursive: true, force: true });
    }
  });

  it("uses a DOCUMENTS link for a non-ACTIVE (DRAFT) requirement", async () => {
    const shim = makeGhShim(99);
    const repoDir = makeGitRepoWithOrigin("o/r");
    const mock = makeFetchMock({
      requirement: { ...ACTIVE_REQ, status: "DRAFT" },
    });
    try {
      const result = await withEnv(shim.binDir, mock.fn, () =>
        createGitHubIssueFromRequirement({ uid: "AGT-001", project: "aptl", repo: "o/r", repoRoot: repoDir }),
      );
      assert.equal(result.link_type, "DOCUMENTS");
      const linkBody = JSON.parse(mock.calls.find((c) => c.url.includes("/traceability")).body);
      assert.equal(linkBody.linkType, "DOCUMENTS");
    } finally {
      shim.cleanup();
      rmSync(repoDir, { recursive: true, force: true });
    }
  });

  it("throws before creating an issue when the requirement does not exist", async () => {
    const shim = makeGhShim(1);
    const mock = makeFetchMock({ requirement: null, requirementStatus: 404 });
    try {
      await withEnv(shim.binDir, mock.fn, async () => {
        await assert.rejects(
          () => createGitHubIssueFromRequirement({ uid: "NOPE-001", project: "aptl" }),
        );
      });
      assert.equal(shim.ghCalled(), false, "gh must not be called for a missing requirement");
    } finally {
      shim.cleanup();
    }
  });

  it("rejects a blank uid before any network or gh call", async () => {
    const shim = makeGhShim(1);
    const mock = makeFetchMock({ requirement: ACTIVE_REQ });
    try {
      await withEnv(shim.binDir, mock.fn, async () => {
        await assert.rejects(
          () => createGitHubIssueFromRequirement({ uid: "  " }),
          /'uid' is required/,
        );
      });
      assert.equal(mock.calls.length, 0);
      assert.equal(shim.ghCalled(), false);
    } finally {
      shim.cleanup();
    }
  });

  it("surfaces a traceability failure without discarding the created issue", async () => {
    const shim = makeGhShim(777);
    const repoDir = makeGitRepoWithOrigin("o/r");
    const mock = makeFetchMock({ requirement: ACTIVE_REQ, traceabilityFails: true });
    try {
      const result = await withEnv(shim.binDir, mock.fn, () =>
        createGitHubIssueFromRequirement({ uid: "AGT-001", project: "aptl", repo: "o/r", repoRoot: repoDir }),
      );
      // Issue still returned; link failure is visible, not swallowed.
      assert.equal(result.number, 777);
      assert.equal(result.url, "https://github.com/o/r/issues/777");
      assert.equal(result.traceability_link, undefined);
      assert.ok(result.traceability_error, "traceability_error must be set on link failure");
    } finally {
      shim.cleanup();
      rmSync(repoDir, { recursive: true, force: true });
    }
  });
});
