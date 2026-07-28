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
// runIngest — serialization (GC-X008)
// ---------------------------------------------------------------------------

describe("runIngest — serialization", () => {
  it("serializes concurrent ingest against the same knowledge base", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const inboxA = writeInboxFile(dir, defaultInboxPayload({ source: "pr:1" }), {
        filename: "2026-04-13T01-00-00-aaaa-a.md",
      });
      const inboxB = writeInboxFile(dir, defaultInboxPayload({ source: "pr:2" }), {
        filename: "2026-04-13T01-00-00-bbbb-b.md",
      });
      // Track the order in which the agent invocations start and finish.
      const events = [];
      const agentFor = (label, delayMs, pagePath) =>
        async function ({ repoRoot }) {
          events.push(`start:${label}`);
          await new Promise((r) => setTimeout(r, delayMs));
          const abs = join(repoRoot, pagePath);
          // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
          mkdirSync(join(abs, ".."), { recursive: true });
          // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
          writeFileSync(abs, `---\ntitle: ${label}\n---\nbody\n`);
          // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
          const existingLog = readFileSync(
            join(repoRoot, "docs", "knowledge", "log.md"),
            "utf8",
          );
          // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
          writeFileSync(
            join(repoRoot, "docs", "knowledge", "log.md"),
            existingLog + `- ${label}\n`,
          );
          const inbox = label === "A" ? inboxA : inboxB;
          const processedDir = join(repoRoot, "docs/knowledge/inbox/processed");
          // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
          mkdirSync(processedDir, { recursive: true });
          // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
          renameSync(inbox, join(processedDir, basenameOf(inbox)));
          events.push(`end:${label}`);
          return {
            stdout:
              `INGEST_RESULT={"action":"create","page":"${pagePath}","citations_added":1}\n`,
            stderr: "",
          };
        };
      const [resA, resB] = await Promise.all([
        runIngest({
          repoRoot: dir,
          inboxFilePath: inboxA,
          knowledge: knowledgePaths(),
          ingestAgent: agentFor("A", 50, "docs/knowledge/gotchas/a.md"),
        }),
        runIngest({
          repoRoot: dir,
          inboxFilePath: inboxB,
          knowledge: knowledgePaths(),
          ingestAgent: agentFor("B", 50, "docs/knowledge/gotchas/b.md"),
        }),
      ]);
      assert.equal(resA.ok, true);
      assert.equal(resB.ok, true);
      // The two runs must not interleave: one full start→end, then the
      // other. Serialization is the core invariant — either order is fine.
      const starts = events.filter((e) => e.startsWith("start:"));
      const ends = events.filter((e) => e.startsWith("end:"));
      assert.equal(starts.length, 2);
      assert.equal(ends.length, 2);
      // events should look like [start:X, end:X, start:Y, end:Y]
      assert.equal(events[1].replace("end:", "start:"), events[0]);
      assert.equal(events[3].replace("end:", "start:"), events[2]);
      // And two commits exist.
      const commits = execFileSync("git", ["-C", dir, "log", "--oneline"])
        .toString()
        .trim()
        .split("\n");
      assert.equal(commits.length, 3, `expected 3 commits (seed + 2 ingests), got ${commits.length}`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// runIngest — failure semantics (GC-X009)
// ---------------------------------------------------------------------------

describe("runIngest — failure retains source", () => {
  it("leaves the inbox file untouched when the ingest agent throws", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      const bytesBefore = readFileSync(inbox, "utf8");
      const agent = makeStubAgent([{ type: "throw", message: "boom" }]);
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      }).catch((e) => ({ ok: false, error: e.message }));
      assert.equal(result.ok, false);
      assert.match(result.error, /boom|agent/i);
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      assert.equal(readFileSync(inbox, "utf8"), bytesBefore);
      assertNoCommitSinceSeed(dir);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("leaves the inbox file untouched when the ingest agent produces no result tail", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      const bytesBefore = readFileSync(inbox, "utf8");
      const agent = async () => ({ stdout: "no tail here\n", stderr: "" });
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      }).catch((e) => ({ ok: false, error: e.message }));
      assert.equal(result.ok, false);
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      assert.equal(readFileSync(inbox, "utf8"), bytesBefore);
      assertNoCommitSinceSeed(dir);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// runIngest — latency measurement (GC-X011)
// ---------------------------------------------------------------------------

describe("runIngest — latency", () => {
  it("records latency in milliseconds for a successful ingest", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const inbox = writeInboxFile(dir, defaultInboxPayload());
      const agent = makeStubAgent(
        [
          {
            type: "write_file",
            path: "docs/knowledge/gotchas/ok.md",
            content: "---\ntitle: ok\n---\nbody\n",
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
        { tail: 'INGEST_RESULT={"action":"create","page":"docs/knowledge/gotchas/ok.md","citations_added":1}' },
      );
      const result = await runIngest({
        repoRoot: dir,
        inboxFilePath: inbox,
        knowledge: knowledgePaths(),
        ingestAgent: agent,
      });
      assert.equal(result.ok, true);
      assert.ok(typeof result.latency_ms === "number");
      assert.ok(result.latency_ms >= 0);
      // Unit tests with a fast stub should always run in well under 30s.
      assert.ok(result.latency_ms < 30_000);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
