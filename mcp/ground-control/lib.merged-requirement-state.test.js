// Tests for the immutable-revision requirement reader and the merged-state verifier
// (issue #1541). These validate requirement state at a real Git commit object id —
// never the working tree — so the post-merge completion assertion cannot be fooled by
// caller-supplied status or by uncommitted edits in the active checkout.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { readRequirementAtRevision } from "./lib/requirement-files.js";
import { verifyMergedRequirementState } from "./lib/merged-requirement-state.js";

const SECRET = "SECRET-STATEMENT-BODY-do-not-leak";

function reqFile({ id, status, traceability = [] }) {
  const trace = traceability.length > 0 ? ["", "## Traceability", "", ...traceability] : [];
  return [
    "---",
    `id: ${id}`,
    `title: "Title of ${id}"`,
    `status: ${status}`,
    "type: FUNCTIONAL",
    "priority: MUST",
    "wave: 1",
    "---",
    "",
    `# ${id} — Title`,
    "",
    "## Statement",
    "",
    `${SECRET} the system shall do the thing.`,
    ...trace,
    "",
  ].join("\n");
}

// Build a git repo containing the given requirement files, commit once, return the
// commit OID plus the repo dir. `files` maps a directory UID → file content (the file
// UID may deliberately differ from the frontmatter id to exercise id-mismatch).
function commitRepo(files) {
  const dir = mkdtempSync(join(tmpdir(), "gc-mrs-"));
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  for (const [uidDir, content] of Object.entries(files)) {
    const d = join(dir, "docs", "requirements", uidDir);
    mkdirSync(d, { recursive: true });
    writeFileSync(join(d, "requirement.md"), content);
  }
  execFileSync("git", ["-C", dir, "add", "-A"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "reqs"]);
  const oid = execFileSync("git", ["-C", dir, "rev-parse", "HEAD"]).toString().trim();
  return { dir, oid, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

const ACTIVE_COMPLETE = {
  "GC-X001": reqFile({
    id: "GC-X001",
    status: "ACTIVE",
    traceability: [
      "- IMPLEMENTS → CODE `mcp/ground-control/lib/foo.js` (foo)",
      "- TESTS → TEST `mcp/ground-control/foo.test.js` (foo test)",
    ],
  }),
};

describe("readRequirementAtRevision", () => {
  it("reads a requirement at a commit OID from the immutable tree, not the worktree", async () => {
    const repo = commitRepo(ACTIVE_COMPLETE);
    try {
      // Mutate the working tree AFTER the commit; the revision read must ignore it.
      writeFileSync(
        join(repo.dir, "docs", "requirements", "GC-X001", "requirement.md"),
        reqFile({ id: "GC-X001", status: "DRAFT" }),
      );
      const r = await readRequirementAtRevision(repo.dir, "GC-X001", repo.oid);
      assert.equal(r.found, true);
      assert.equal(r.malformed, false);
      assert.equal(r.frontmatterId, "GC-X001");
      assert.equal(r.requirement.status, "ACTIVE", "must read committed ACTIVE, not worktree DRAFT");
    } finally {
      repo.cleanup();
    }
  });

  it("returns found:false for an absent path at the revision", async () => {
    const repo = commitRepo(ACTIVE_COMPLETE);
    try {
      const r = await readRequirementAtRevision(repo.dir, "GC-Z999", repo.oid);
      assert.equal(r.found, false);
    } finally {
      repo.cleanup();
    }
  });

  it("returns malformed:true when frontmatter is missing", async () => {
    const repo = commitRepo({ "GC-X001": "no frontmatter here\n" });
    try {
      const r = await readRequirementAtRevision(repo.dir, "GC-X001", repo.oid);
      assert.equal(r.found, true);
      assert.equal(r.malformed, true);
    } finally {
      repo.cleanup();
    }
  });

  it("rejects a non-OID revision (a branch name or HEAD) rather than reading a mutable ref", async () => {
    const repo = commitRepo(ACTIVE_COMPLETE);
    try {
      for (const rev of ["HEAD", "main", "dev", repo.oid.slice(0, 12)]) {
        const r = await readRequirementAtRevision(repo.dir, "GC-X001", rev);
        assert.equal(r.found, false, `revision ${rev} must be rejected`);
      }
    } finally {
      repo.cleanup();
    }
  });
});

describe("verifyMergedRequirementState", () => {
  it("passes when the merged file matches the ACTIVE intent with complete traceability", async () => {
    const repo = commitRepo(ACTIVE_COMPLETE);
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, true, JSON.stringify(r));
      assert.equal(r.results[0].observed_status, "ACTIVE");
      assert.equal(r.results[0].observed_title, "Title of GC-X001");
    } finally {
      repo.cleanup();
    }
  });

  it("REGRESSION: a merged DRAFT file cannot satisfy an ACTIVE intent", async () => {
    const repo = commitRepo({
      "GC-X001": reqFile({
        id: "GC-X001",
        status: "DRAFT",
        traceability: ["- IMPLEMENTS → CODE `mcp/ground-control/lib/foo.js` (foo)"],
      }),
    });
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.failures[0].code, "requirement_status_mismatch");
      assert.equal(r.failures[0].observed_status, "DRAFT");
      assert.equal(r.failures[0].expected_status, "ACTIVE");
    } finally {
      repo.cleanup();
    }
  });

  it("fails on a frontmatter id that does not equal the folder UID", async () => {
    const repo = commitRepo({ "GC-X001": reqFile({ id: "GC-OTHER", status: "ACTIVE" }) });
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.failures[0].code, "requirement_id_mismatch");
    } finally {
      repo.cleanup();
    }
  });

  it("fails an ACTIVE requirement with no IMPLEMENTS link", async () => {
    const repo = commitRepo({ "GC-X001": reqFile({ id: "GC-X001", status: "ACTIVE" }) });
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.failures[0].code, "requirement_missing_implements");
    } finally {
      repo.cleanup();
    }
  });

  it("requires TESTS when an IMPLEMENTS target is on a testable surface", async () => {
    const repo = commitRepo({
      "GC-X001": reqFile({
        id: "GC-X001",
        status: "ACTIVE",
        traceability: ["- IMPLEMENTS → CODE `mcp/ground-control/lib/foo.js` (foo)"],
      }),
    });
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.failures[0].code, "requirement_missing_tests");
    } finally {
      repo.cleanup();
    }
  });

  it("fails a file absent at the revision", async () => {
    const repo = commitRepo(ACTIVE_COMPLETE);
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-ABSENT", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.equal(r.failures[0].code, "requirement_file_absent");
    } finally {
      repo.cleanup();
    }
  });

  it("collects failures for EVERY UID rather than stopping at the first", async () => {
    const repo = commitRepo({
      "GC-X001": reqFile({ id: "GC-X001", status: "DRAFT" }),
      "GC-X002": reqFile({ id: "GC-X002", status: "DRAFT" }),
    });
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [
          { uid: "GC-X001", statusIntent: "ACTIVE" },
          { uid: "GC-X002", statusIntent: "ACTIVE" },
        ],
      });
      assert.equal(r.ok, false);
      assert.equal(r.failures.length, 2);
    } finally {
      repo.cleanup();
    }
  });

  it("never leaks requirement body/statement content into the failure result", async () => {
    const repo = commitRepo({ "GC-X001": reqFile({ id: "GC-X001", status: "DRAFT" }) });
    try {
      const r = await verifyMergedRequirementState({
        repoRoot: repo.dir,
        revision: repo.oid,
        expectations: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      });
      assert.equal(r.ok, false);
      assert.ok(!JSON.stringify(r).includes(SECRET), "verifier result must not carry statement text");
    } finally {
      repo.cleanup();
    }
  });

  it("passes an empty expectation set (requirement-free run)", async () => {
    const repo = commitRepo(ACTIVE_COMPLETE);
    try {
      const r = await verifyMergedRequirementState({ repoRoot: repo.dir, revision: repo.oid, expectations: [] });
      assert.equal(r.ok, true);
      assert.equal(r.results.length, 0);
    } finally {
      repo.cleanup();
    }
  });
});
