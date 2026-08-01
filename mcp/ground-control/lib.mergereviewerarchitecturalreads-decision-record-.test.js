// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { KNOWLEDGE_SOURCE_TYPES, formatSourceCitation, writeKnowledgeInbox } from "./lib.js";

// Local existence helper so tests don't have to pass test-controlled
// temp paths through an eslint-disabled call everywhere.
function existsSyncHelper(p) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled path
  return existsSync(p);
}

describe("mergeReviewerArchitecturalReads — decision-record read (issue #966)", () => {
  it("merges both reviewers' reads with labels", async () => {
    const { mergeReviewerArchitecturalReads } = await import("./lib.js");
    const out = mergeReviewerArchitecturalReads(
      { envelope: { architectural_read: "core says ok" } },
      { envelope: { architectural_read: "security flags a token" } },
    );
    assert.match(out, /\*\*Core reviewer:\*\* core says ok/);
    assert.match(out, /\*\*Security reviewer:\*\* security flags a token/);
  });

  it("returns just the present reviewer when one envelope is missing", async () => {
    const { mergeReviewerArchitecturalReads } = await import("./lib.js");
    const out = mergeReviewerArchitecturalReads(
      { envelope: { architectural_read: "core only" } },
      { body: "parse failed" },
    );
    assert.match(out, /\*\*Core reviewer:\*\* core only/);
    assert.doesNotMatch(out, /Security reviewer/);
  });

  it("returns undefined when neither envelope parsed", async () => {
    const { mergeReviewerArchitecturalReads } = await import("./lib.js");
    assert.equal(mergeReviewerArchitecturalReads({ body: "x" }, { body: "y" }), undefined);
    assert.equal(mergeReviewerArchitecturalReads(null, null), undefined);
  });
});

// ---------------------------------------------------------------------------
// Knowledge base capture / ingest — GC-X006..GC-X011
// ---------------------------------------------------------------------------

describe("KNOWLEDGE_SOURCE_TYPES", () => {
  it("matches the source-citation vocabulary documented in docs/knowledge/SCHEMA.md", () => {
    // This list is the single source of truth for gc_remember's Zod enum,
    // the ingest engine's validation, and the citation strings written into
    // page frontmatter, log.md, and git commit messages. Keep it synced
    // with docs/knowledge/SCHEMA.md §"Source citation rule".
    assert.deepEqual(
      [...KNOWLEDGE_SOURCE_TYPES].sort(),
      ["ci", "commit", "file", "issue", "pr", "review", "user-correction"].sort(),
    );
  });
});

describe("formatSourceCitation", () => {
  it("formats commit SHAs as commit:<sha>", () => {
    const r = formatSourceCitation({ sourceType: "commit", sourceRef: "abc123d" });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "commit:abc123d");
  });

  it("accepts full 40-char SHAs", () => {
    const sha = "abcdef0123456789abcdef0123456789abcdef01";
    const r = formatSourceCitation({ sourceType: "commit", sourceRef: sha });
    assert.equal(r.ok, true);
    assert.equal(r.citation, `commit:${sha}`);
  });

  it("accepts 7-char short SHAs", () => {
    const r = formatSourceCitation({ sourceType: "commit", sourceRef: "abcdef0" });
    assert.equal(r.ok, true);
  });

  it("rejects non-hex commit refs", () => {
    const r = formatSourceCitation({ sourceType: "commit", sourceRef: "not-a-sha" });
    assert.equal(r.ok, false);
    assert.match(r.error, /commit.*hex/i);
  });

  it("rejects commit refs shorter than 7 chars", () => {
    const r = formatSourceCitation({ sourceType: "commit", sourceRef: "abc12" });
    assert.equal(r.ok, false);
    assert.match(r.error, /commit/i);
  });

  it("formats PR numbers as pr:<number>", () => {
    const r = formatSourceCitation({ sourceType: "pr", sourceRef: "528" });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "pr:528");
  });

  it("formats PR numbers with a # prefix by stripping the prefix", () => {
    const r = formatSourceCitation({ sourceType: "pr", sourceRef: "#528" });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "pr:528");
  });

  it("rejects non-numeric PR refs", () => {
    const r = formatSourceCitation({ sourceType: "pr", sourceRef: "not-a-number" });
    assert.equal(r.ok, false);
    assert.match(r.error, /pr/i);
  });

  it("formats review comment ids as review:<id>", () => {
    const r = formatSourceCitation({ sourceType: "review", sourceRef: "1234567890" });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "review:1234567890");
  });

  it("rejects empty review ids", () => {
    const r = formatSourceCitation({ sourceType: "review", sourceRef: "" });
    assert.equal(r.ok, false);
  });

  it("formats issue numbers as issue:<number>", () => {
    const r = formatSourceCitation({ sourceType: "issue", sourceRef: "523" });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "issue:523");
  });

  it("formats CI run ids as ci:<id>", () => {
    const r = formatSourceCitation({ sourceType: "ci", sourceRef: "24319887139" });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "ci:24319887139");
  });

  it("formats user corrections as user-correction:<desc>", () => {
    const r = formatSourceCitation({
      sourceType: "user-correction",
      sourceRef: "dont skip review cycles",
    });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "user-correction:dont skip review cycles");
  });

  it("formats file references as file:<path>", () => {
    const r = formatSourceCitation({
      sourceType: "file",
      sourceRef: "mcp/ground-control/lib.js",
    });
    assert.equal(r.ok, true);
    assert.equal(r.citation, "file:mcp/ground-control/lib.js");
  });

  it("rejects file references that are absolute paths", () => {
    const r = formatSourceCitation({ sourceType: "file", sourceRef: "/etc/passwd" });
    assert.equal(r.ok, false);
    assert.match(r.error, /file.*relative/i);
  });

  it("rejects file references that escape with ..", () => {
    const r = formatSourceCitation({ sourceType: "file", sourceRef: "../secret" });
    assert.equal(r.ok, false);
    assert.match(r.error, /file/i);
  });

  it("rejects unknown source types", () => {
    const r = formatSourceCitation({ sourceType: "bogus", sourceRef: "x" });
    assert.equal(r.ok, false);
    assert.match(r.error, /source_type/);
  });

  it("rejects empty source_ref", () => {
    const r = formatSourceCitation({ sourceType: "pr", sourceRef: "" });
    assert.equal(r.ok, false);
  });

  it("rejects missing source_type", () => {
    const r = formatSourceCitation({ sourceRef: "abc" });
    assert.equal(r.ok, false);
  });

  it("rejects null / missing input object", () => {
    const r = formatSourceCitation();
    assert.equal(r.ok, false);
  });

  it("collapses newlines and tab runs in user-correction descriptions to single spaces", () => {
    // Citations appear in git commit message subjects and log.md bullets,
    // both of which are single-line contexts. A multiline description would
    // break both. YAML frontmatter safety is handled at serialization time
    // via js-yaml's auto-quoting, not here, so this check only asserts the
    // "single line" property — inline `- something` substrings after
    // collapsing are harmless in commit subjects and in log.md bullets
    // (markdown only starts a new list item on a real newline).
    const r = formatSourceCitation({
      sourceType: "user-correction",
      sourceRef: "line one\n\tmore\r\nstill more",
    });
    assert.equal(r.ok, true);
    assert.ok(!r.citation.includes("\n"));
    assert.ok(!r.citation.includes("\r"));
    assert.ok(!r.citation.includes("\t"));
    assert.equal(r.citation, "user-correction:line one more still more");
  });
});

describe("writeKnowledgeInbox", () => {
  // A helper that builds a git repo ready for ingest tests: initialized,
  // on a symbolic branch (`main`), with one committed file so HEAD exists,
  // and with a `docs/knowledge/` skeleton plus `.ground-control.yaml`
  // declaring the knowledge block. Returns the absolute path.
  function makeKnowledgeReadyRepo() {
    const dir = mkdtempSync(join(tmpdir(), "gc-knowledge-test-"));
    execFileSync("git", ["-C", dir, "init", "-q", "-b", "main"]);
    execFileSync("git", ["-C", dir, "config", "user.email", "test@example.com"]);
    execFileSync("git", ["-C", dir, "config", "user.name", "Test"]);
    execFileSync("git", ["-C", dir, "config", "commit.gpgsign", "false"]);
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    mkdirSync(join(dir, "docs", "knowledge"), { recursive: true });
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    writeFileSync(
      join(dir, "docs", "knowledge", "SCHEMA.md"),
      "---\ntitle: test schema\n---\n# test schema\n",
    );
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    writeFileSync(
      join(dir, "docs", "knowledge", "index.md"),
      "---\ntitle: Index\n---\n# Index\n",
    );
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    writeFileSync(
      join(dir, "docs", "knowledge", "log.md"),
      "---\ntitle: Log\n---\n# Log\n",
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
    execFileSync("git", ["-C", dir, "add", "-A"]);
    execFileSync("git", ["-C", dir, "commit", "-q", "-m", "seed"]);
    return dir;
  }

  it("writes an inbox file, returns a structured receipt, and does NOT spawn when spawnIngest is stubbed", async () => {
    const dir = makeKnowledgeReadyRepo();
    const calls = [];
    const spawnStub = (args) => {
      calls.push(args);
    };
    try {
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "ingest engine drops commits on detached HEAD — always check symbolic ref before committing",
        sourceType: "pr",
        sourceRef: "523",
        tags: ["knowledge", "ingest"],
        spawnIngest: spawnStub,
      });
      assert.equal(result.ok, true);
      assert.equal(result.citation, "pr:523");
      assert.equal(typeof result.inbox_path, "string");
      assert.ok(result.inbox_path.startsWith("docs/knowledge/inbox/"));
      assert.ok(result.inbox_path.endsWith(".md"));
      // The file exists on disk.
      const absInbox = join(dir, result.inbox_path);
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      const body = readFileSync(absInbox, "utf8");
      // Frontmatter carries the canonical citation, ISO timestamp, and tags.
      assert.match(body, /^---\n/);
      assert.match(body, /captured_at: /);
      assert.match(body, /source: 'pr:523'|source: pr:523|source: "pr:523"/);
      assert.match(body, /tags:\n\s+- knowledge\n\s+- ingest|tags: \[knowledge, ingest\]/);
      // Body text follows the frontmatter.
      assert.match(body, /ingest engine drops commits on detached HEAD/);
      // The spawn stub was called once with structured argv.
      assert.equal(calls.length, 1);
      assert.equal(calls[0].inboxFilePath, absInbox);
      assert.equal(calls[0].repoRoot, dir);
      assert.ok(calls[0].knowledge);
      assert.equal(calls[0].knowledge.dir, "docs/knowledge");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("creates the inbox directory lazily if it does not exist yet", async () => {
    const dir = makeKnowledgeReadyRepo();
    try {
      // The skeleton from issue #522 deliberately does NOT commit inbox/.
      // Writing the first inbox file must succeed anyway.
      assert.ok(!existsSyncHelper(join(dir, "docs", "knowledge", "inbox")));
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "first capture",
        sourceType: "issue",
        sourceRef: "523",
        spawnIngest: () => {},
      });
      assert.equal(result.ok, true);
      assert.ok(existsSyncHelper(join(dir, "docs", "knowledge", "inbox")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("returns {ok:false} and does not spawn when the repo has no knowledge block", async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-knowledge-no-kb-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    let spawned = false;
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(
        join(dir, ".ground-control.yaml"),
        "schema_version: 1\nproject: no-kb\n",
      );
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "anything",
        sourceType: "pr",
        sourceRef: "1",
        spawnIngest: () => {
          spawned = true;
        },
      });
      assert.equal(result.ok, false);
      assert.match(result.error, /knowledge/i);
      assert.equal(spawned, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("returns {ok:false} and does not spawn when the citation is invalid", async () => {
    const dir = makeKnowledgeReadyRepo();
    let spawned = false;
    try {
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "hmm",
        sourceType: "commit",
        sourceRef: "not-a-sha",
        spawnIngest: () => {
          spawned = true;
        },
      });
      assert.equal(result.ok, false);
      assert.match(result.error, /commit/i);
      assert.equal(spawned, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects empty notes", async () => {
    const dir = makeKnowledgeReadyRepo();
    try {
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "",
        sourceType: "pr",
        sourceRef: "1",
        spawnIngest: () => {},
      });
      assert.equal(result.ok, false);
      assert.match(result.error, /note/i);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("returns success with a warning when spawnIngest throws (inbox bytes are durable)", async () => {
    const dir = makeKnowledgeReadyRepo();
    try {
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "bang",
        sourceType: "pr",
        sourceRef: "999",
        spawnIngest: () => {
          throw new Error("simulated spawn failure");
        },
      });
      // GC-X006 says the synchronous MCP call succeeds as long as the
      // inbox entry is durably written. Spawn failures must not rewrite
      // or delete the source file; a later sweep picks it up.
      assert.equal(result.ok, true);
      assert.ok(result.warning);
      assert.match(result.warning, /ingest_spawn_failed|simulated spawn/);
      // The inbox file is still on disk.
      const absInbox = join(dir, result.inbox_path);
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      assert.ok(readFileSync(absInbox, "utf8").includes("bang"));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("produces unique filenames for rapid concurrent captures", async () => {
    const dir = makeKnowledgeReadyRepo();
    try {
      const N = 30;
      const results = await Promise.all(
        Array.from({ length: N }, (_, i) =>
          writeKnowledgeInbox({
            repoPath: dir,
            note: `race ${i}`,
            sourceType: "pr",
            sourceRef: String(100 + i),
            spawnIngest: () => {},
          }),
        ),
      );
      for (const r of results) assert.equal(r.ok, true);
      const paths = new Set(results.map((r) => r.inbox_path));
      assert.equal(paths.size, N, `expected ${N} unique inbox paths, got ${paths.size}`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("uses a timestamp-first filename with a slug derived from the note", async () => {
    const dir = makeKnowledgeReadyRepo();
    try {
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "Race condition in Checkout flow: requires mutex around cart read",
        sourceType: "pr",
        sourceRef: "42",
        spawnIngest: () => {},
      });
      assert.equal(result.ok, true);
      const basename = result.inbox_path.split("/").pop();
      // ISO timestamp prefix: YYYY-MM-DDTHH-MM-SS (colons replaced with -)
      assert.match(basename, /^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}/);
      // Slug is kebab-cased and present in the filename.
      assert.match(basename, /race-condition/);
      assert.ok(basename.endsWith(".md"));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects repo_path that is not an absolute path", async () => {
    const result = await writeKnowledgeInbox({
      repoPath: "not-absolute",
      note: "x",
      sourceType: "pr",
      sourceRef: "1",
      spawnIngest: () => {},
    });
    assert.equal(result.ok, false);
    assert.match(result.error, /absolute/i);
  });

  it("rejects repo_path that is not a git repo", async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-no-git-"));
    try {
      const result = await writeKnowledgeInbox({
        repoPath: dir,
        note: "x",
        sourceType: "pr",
        sourceRef: "1",
        spawnIngest: () => {},
      });
      assert.equal(result.ok, false);
      assert.match(result.error, /git/i);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
