// Split from knowledge_ingest.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { parseIngestResultTail, runIngest } from "./knowledge_ingest.js";

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

function makeKnowledgeRepo({ extraFiles = {} } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "gc-ingest-test-"));
  execFileSync("git", ["-C", dir, "init", "-q", "-b", "main"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "test@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "Test"]);
  execFileSync("git", ["-C", dir, "config", "commit.gpgsign", "false"]);

  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  mkdirSync(join(dir, "docs", "knowledge", "inbox"), { recursive: true });
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  writeFileSync(
    join(dir, "docs", "knowledge", "SCHEMA.md"),
    "---\ntitle: schema\n---\n# schema\n",
  );
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  writeFileSync(
    join(dir, "docs", "knowledge", "index.md"),
    "---\ntitle: Index\n---\n# Knowledge Base Index\n\n## Topics\n\n_No pages yet._\n",
  );
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  writeFileSync(
    join(dir, "docs", "knowledge", "log.md"),
    "---\ntitle: Log\n---\n# Log\n\n## Entries\n",
  );
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  writeFileSync(
    join(dir, ".ground-control.yaml"),
    [
      "schema_version: 1",
      "project: test-project",
      "knowledge:",
      "  dir: docs/knowledge",
      "",
    ].join("\n"),
  );
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  writeFileSync(join(dir, "README.md"), "# test repo\n");

  for (const [relPath, content] of Object.entries(extraFiles)) {
    const abs = join(dir, relPath);
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    mkdirSync(join(abs, ".."), { recursive: true });
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    writeFileSync(abs, content);
  }

  execFileSync("git", ["-C", dir, "add", "-A"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "seed"]);
  return dir;
}

function writeInboxFile(repoRoot, content, { filename } = {}) {
  const name =
    filename ||
    `2026-04-13T01-00-00-${Math.floor(Math.random() * 10000)
      .toString()
      .padStart(4, "0")}-test.md`;
  const abs = join(repoRoot, "docs", "knowledge", "inbox", name);
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
  writeFileSync(abs, content);
  return abs;
}

function defaultInboxPayload({
  source = "pr:523",
  body = "test observation",
  capturedAt = new Date().toISOString(),
} = {}) {
  return [
    "---",
    `captured_at: '${capturedAt}'`,
    `source: '${source}'`,
    "---",
    "",
    body,
    "",
  ].join("\n");
}

// A stub `ingestAgent` that interprets a scripted action map and applies
// it to the filesystem. Each action represents what the real Claude Code
// ingest agent would produce via its Read/Edit/Write/Bash tools. The
// stub also emits the required INGEST_RESULT tail line so
// parseIngestResultTail can parse the output the same way it parses
// real ingest-agent output.
function makeStubAgent(actions, { tail = null } = {}) {
  return async function stubAgent({ repoRoot, prompt: _prompt }) {
    for (const action of actions) {
      if (action.type === "write_file") {
        const abs = join(repoRoot, action.path);
        // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
        mkdirSync(join(abs, ".."), { recursive: true });
        // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
        writeFileSync(abs, action.content);
      } else if (action.type === "append_file") {
        const abs = join(repoRoot, action.path);
        // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
        const prev = existsSync(abs) ? readFileSync(abs, "utf8") : "";
        // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
        writeFileSync(abs, prev + action.content);
      } else if (action.type === "rename") {
        const from = join(repoRoot, action.from);
        const to = join(repoRoot, action.to);
        // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
        mkdirSync(join(to, ".."), { recursive: true });
        // Use a plain filesystem rename because inbox files are untracked
        // at ingest time (gc_remember writes them post-commit) and
        // `git mv` requires the source to be tracked.
        // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
        renameSync(from, to);
      } else if (action.type === "throw") {
        throw new Error(action.message || "stub ingest agent error");
      }
    }
    const resolvedTail =
      tail ??
      `INGEST_RESULT={"action":"create","page":"docs/knowledge/gotchas/stub.md","citations_added":1}`;
    return { stdout: resolvedTail + "\n", stderr: "" };
  };
}

function assertNoCommitSinceSeed(repoRoot) {
  const out = execFileSync("git", ["-C", repoRoot, "log", "--oneline"])
    .toString()
    .trim()
    .split("\n");
  assert.equal(out.length, 1, `expected only the seed commit, got ${out.length}: ${out.join(" | ")}`);
  assert.match(out[0], /seed$/);
}

function commitMessage(repoRoot, ref = "HEAD") {
  return execFileSync("git", ["-C", repoRoot, "log", "-1", "--format=%B", ref]).toString().trim();
}

function commitFiles(repoRoot, ref = "HEAD") {
  return execFileSync("git", ["-C", repoRoot, "show", "--name-only", "--format=", ref])
    .toString()
    .trim()
    .split("\n")
    .filter(Boolean)
    .sort();
}

function knowledgePaths() {
  return {
    dir: "docs/knowledge",
    schema: "docs/knowledge/SCHEMA.md",
    inbox: "docs/knowledge/inbox",
  };
}

// Tiny utility helpers used by the scripted stubs.
function basenameOf(abs) {
  return abs.split("/").pop();
}

function relPathUnder(repoRoot, abs) {
  return abs.startsWith(repoRoot + "/") ? abs.slice(repoRoot.length + 1) : abs;
}

// ---------------------------------------------------------------------------
// parseIngestResultTail
// ---------------------------------------------------------------------------

describe("parseIngestResultTail", () => {
  it("parses a valid INGEST_RESULT tail", () => {
    const out = 'some prose\nINGEST_RESULT={"action":"create","page":"docs/knowledge/topics/foo.md","citations_added":1}\n';
    const r = parseIngestResultTail(out);
    assert.equal(r.action, "create");
    assert.equal(r.page, "docs/knowledge/topics/foo.md");
    assert.equal(r.citations_added, 1);
  });

  it("throws when the tail line is missing", () => {
    assert.throws(() => parseIngestResultTail("no tail here"), /INGEST_RESULT/);
  });

  it("throws on malformed JSON", () => {
    assert.throws(() => parseIngestResultTail("INGEST_RESULT={bad json}"), /JSON/);
  });

  it("throws on unknown action", () => {
    assert.throws(
      () => parseIngestResultTail('INGEST_RESULT={"action":"delete","page":"x","citations_added":1}'),
      /action/,
    );
  });
});

// ---------------------------------------------------------------------------
// runIngest — happy paths
// ---------------------------------------------------------------------------

describe("runIngest — create path (GC-X007 new page)", () => {
  it("produces exactly one commit with only the knowledge tree + inbox item staged", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      const agent = makeStubAgent(
        [
          {
            type: "write_file",
            path: "docs/knowledge/gotchas/race-condition.md",
            content:
              "---\ntitle: Race Condition\nsources: ['pr:523']\n---\n# Race Condition\n\nBody.\n",
          },
          {
            type: "write_file",
            path: "docs/knowledge/index.md",
            content:
              "---\ntitle: Index\n---\n# Knowledge Base Index\n\n## Gotchas\n\n- [Race Condition](gotchas/race-condition.md) — latent race. Tags: concurrency\n",
          },
          {
            type: "append_file",
            path: "docs/knowledge/log.md",
            content: "- `2026-04-13` — ingested pr:523 into gotchas/race-condition.md.\n",
          },
          // Move inbox file into processed/.
          {
            type: "rename",
            from: relPathUnder(dir, inbox),
            to: `docs/knowledge/inbox/processed/${basenameOf(inbox)}`,
          },
        ],
        {
          tail:
            'INGEST_RESULT={"action":"create","page":"docs/knowledge/gotchas/race-condition.md","citations_added":1}',
        },
      );
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      });
      assert.equal(result.ok, true);
      assert.equal(result.action, "create");
      assert.ok(typeof result.commit_sha === "string" && result.commit_sha.length >= 7);
      assert.ok(typeof result.latency_ms === "number" && result.latency_ms >= 0);

      // One new commit since seed.
      const commits = execFileSync("git", ["-C", dir, "log", "--oneline"])
        .toString()
        .trim()
        .split("\n");
      assert.equal(commits.length, 2, `expected 2 commits, got ${commits.length}`);

      // Commit message includes the canonical citation.
      const msg = commitMessage(dir);
      assert.match(msg, /pr:523/);

      // Commit staged exactly: new page, index.md, log.md, inbox-move (as rename).
      const files = commitFiles(dir);
      assert.ok(
        files.includes("docs/knowledge/gotchas/race-condition.md"),
        `missing new page in commit: ${files.join(", ")}`,
      );
      assert.ok(files.includes("docs/knowledge/index.md"));
      assert.ok(files.includes("docs/knowledge/log.md"));
      // inbox file appears either as the original or processed path depending
      // on how git records the rename; both are under docs/knowledge/inbox/.
      assert.ok(files.some((f) => f.startsWith("docs/knowledge/inbox/")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("runIngest — update-in-place path (GC-X007 existing page)", () => {
  it("updates an existing page without creating a duplicate", async () => {
    const existingPageRel = "docs/knowledge/gotchas/race-condition.md";
    const dir = makeKnowledgeRepo({
      extraFiles: {
        [existingPageRel]:
          "---\ntitle: Race Condition\nsources: ['pr:520']\n---\n# Race Condition\n\nOriginal body.\n",
      },
    });
    try {
      const inbox = writeInboxFile(dir, defaultInboxPayload({ source: "pr:530" }));
      const agent = makeStubAgent(
        [
          {
            // Update the existing file in place — add a new source to frontmatter.
            type: "write_file",
            path: existingPageRel,
            content:
              "---\ntitle: Race Condition\nsources: ['pr:520', 'pr:530']\n---\n# Race Condition\n\nOriginal body.\n\nNew note: also affects checkout.\n",
          },
          {
            type: "append_file",
            path: "docs/knowledge/log.md",
            content: "- `2026-04-13` — updated gotchas/race-condition.md with pr:530.\n",
          },
          {
            type: "rename",
            from: relPathUnder(dir, inbox),
            to: `docs/knowledge/inbox/processed/${basenameOf(inbox)}`,
          },
        ],
        {
          tail: `INGEST_RESULT={"action":"update","page":"${existingPageRel}","citations_added":1}`,
        },
      );
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      });
      assert.equal(result.ok, true);
      assert.equal(result.action, "update");
      const files = commitFiles(dir);
      // The existing page should appear in the commit, NOT a second new
      // gotcha file with a different slug.
      assert.ok(files.includes(existingPageRel));
      const extraPages = files.filter(
        (f) => f.startsWith("docs/knowledge/gotchas/") && f !== existingPageRel,
      );
      assert.deepEqual(
        extraPages,
        [],
        `ingest duplicated the page instead of updating in place: ${extraPages.join(", ")}`,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// runIngest — commit isolation (GC-X010)
// ---------------------------------------------------------------------------

describe("runIngest — commit isolation", () => {
  it("does not include unrelated pre-existing dirty files in the commit", async () => {
    const dir = makeKnowledgeRepo();
    try {
      // Plant a dirty file outside the knowledge tree BEFORE ingest runs.
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(dir, "unrelated.txt"), "unrelated change\n");
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      const agent = makeStubAgent(
        [
          {
            type: "write_file",
            path: "docs/knowledge/gotchas/foo.md",
            content: "---\ntitle: foo\n---\n# foo\n",
          },
          {
            type: "append_file",
            path: "docs/knowledge/log.md",
            content: "- entry\n",
          },
          {
            type: "rename",
            from: relPathUnder(dir, inbox),
            to: `docs/knowledge/inbox/processed/${basenameOf(inbox)}`,
          },
        ],
        {
          tail: `INGEST_RESULT={"action":"create","page":"docs/knowledge/gotchas/foo.md","citations_added":1}`,
        },
      );
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      });
      assert.equal(result.ok, true);
      const files = commitFiles(dir);
      assert.ok(!files.includes("unrelated.txt"), `unrelated file leaked into commit: ${files.join(", ")}`);
      // And the unrelated file is STILL dirty in the worktree after ingest.
      const status = execFileSync("git", ["-C", dir, "status", "--porcelain"]).toString();
      assert.match(status, /unrelated\.txt/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("aborts and reverts when the ingest agent writes outside the knowledge tree", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      const inboxBytesBefore = readFileSync(inbox, "utf8");
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      const readmeBefore = readFileSync(join(dir, "README.md"), "utf8");
      const agent = makeStubAgent(
        [
          // Legitimate knowledge-tree write.
          {
            type: "write_file",
            path: "docs/knowledge/gotchas/foo.md",
            content: "---\ntitle: foo\n---\nbody\n",
          },
          // Malicious / buggy write OUTSIDE the knowledge tree.
          {
            type: "write_file",
            path: "README.md",
            content: "clobbered by the ingest agent\n",
          },
        ],
        { tail: 'INGEST_RESULT={"action":"create","page":"docs/knowledge/gotchas/foo.md","citations_added":1}' },
      );
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      }).catch((e) => ({ ok: false, error: e.message }));
      assert.equal(result.ok, false);
      assert.match(result.error, /outside|isolation|unexpected/i);
      // No commit was created.
      assertNoCommitSinceSeed(dir);
      // Inbox file is unchanged.
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      assert.equal(readFileSync(inbox, "utf8"), inboxBytesBefore);
      // README was reverted to its committed state.
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      assert.equal(readFileSync(join(dir, "README.md"), "utf8"), readmeBefore);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses to run on detached HEAD", async () => {
    const dir = makeKnowledgeRepo();
    try {
      // Detach HEAD.
      const sha = execFileSync("git", ["-C", dir, "rev-parse", "HEAD"]).toString().trim();
      execFileSync("git", ["-C", dir, "checkout", "-q", "--detach", sha]);
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      const agent = makeStubAgent([]);
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      }).catch((e) => ({ ok: false, error: e.message }));
      assert.equal(result.ok, false);
      assert.match(result.error, /detached|branch/i);
      // Inbox file untouched on disk.
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      assert.ok(existsSync(inbox));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
