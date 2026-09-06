// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { after, describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  acquireIntegrationLock,
  acquireKnowledgeLock,
  classifyChangedSurface,
  parseGroundControlYaml,
  validateDocumentationOutcome,
} from "./lib.js";

describe("acquireKnowledgeLock", () => {
  function makeLockTempDir() {
    return mkdtempSync(join(tmpdir(), "gc-lock-test-"));
  }

  it("acquires a fresh lock, returns a release handle, and releases cleanly", async () => {
    const dir = makeLockTempDir();
    try {
      const release = await acquireKnowledgeLock(dir);
      assert.equal(typeof release, "function");
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses to acquire a currently-held lock", async () => {
    const dir = makeLockTempDir();
    try {
      const release = await acquireKnowledgeLock(dir);
      await assert.rejects(
        () => acquireKnowledgeLock(dir),
        /held|locked/i,
      );
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("allows re-acquisition after release", async () => {
    const dir = makeLockTempDir();
    try {
      const r1 = await acquireKnowledgeLock(dir);
      await r1();
      const r2 = await acquireKnowledgeLock(dir);
      await r2();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("runs locks on different knowledge bases in parallel", async () => {
    const dirA = makeLockTempDir();
    const dirB = makeLockTempDir();
    try {
      const [rA, rB] = await Promise.all([
        acquireKnowledgeLock(dirA),
        acquireKnowledgeLock(dirB),
      ]);
      // Both held at once — no contention.
      assert.equal(typeof rA, "function");
      assert.equal(typeof rB, "function");
      await rA();
      await rB();
    } finally {
      rmSync(dirA, { recursive: true, force: true });
      rmSync(dirB, { recursive: true, force: true });
    }
  });

  it("treats a symlinked path and its realpath as the same lock identity", async () => {
    const realDir = makeLockTempDir();
    const symRoot = mkdtempSync(join(tmpdir(), "gc-lock-sym-"));
    const symlinked = join(symRoot, "kb");
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dirs
      symlinkSync(realDir, symlinked);
      const release = await acquireKnowledgeLock(realDir);
      // Symlinked path should observe the same held lock.
      await assert.rejects(
        () => acquireKnowledgeLock(symlinked),
        /held|locked/i,
      );
      await release();
      // After release, the symlinked path can now acquire.
      const r2 = await acquireKnowledgeLock(symlinked);
      await r2();
    } finally {
      rmSync(realDir, { recursive: true, force: true });
      rmSync(symRoot, { recursive: true, force: true });
    }
  });

  it("rejects non-absolute and nonexistent paths", async () => {
    await assert.rejects(
      () => acquireKnowledgeLock("relative/path"),
      /absolute/i,
    );
    const fakeAbs = join(tmpdir(), "gc-lock-does-not-exist-" + Math.random());
    await assert.rejects(
      () => acquireKnowledgeLock(fakeAbs),
      /exist/i,
    );
  });
});

// ---------------------------------------------------------------------------
// acquireIntegrationLock (GC-O011, issue #989) — refactor regression tests.
// Same behavioral shape as acquireKnowledgeLock but uses .gc-integration-lock
// placed AT the repo root (not inside a knowledge subdirectory).
// ---------------------------------------------------------------------------

describe("acquireIntegrationLock", () => {
  function makeIntegLockTempDir() {
    return mkdtempSync(join(tmpdir(), "gc-integ-lock-test-"));
  }

  it("acquires a fresh lock, returns a release handle, and releases cleanly", async () => {
    const dir = makeIntegLockTempDir();
    try {
      const release = await acquireIntegrationLock(dir);
      assert.equal(typeof release, "function");
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses to acquire a currently-held lock (ELOCKED)", async () => {
    const dir = makeIntegLockTempDir();
    try {
      const release = await acquireIntegrationLock(dir);
      // Second acquire must fail because the lock is already held.
      await assert.rejects(
        () => acquireIntegrationLock(dir),
        /held|locked|in progress/i,
      );
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("allows re-acquisition after release", async () => {
    const dir = makeIntegLockTempDir();
    try {
      const r1 = await acquireIntegrationLock(dir);
      await r1();
      const r2 = await acquireIntegrationLock(dir);
      await r2();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("runs locks on different directories in parallel", async () => {
    const dirA = makeIntegLockTempDir();
    const dirB = makeIntegLockTempDir();
    try {
      const [rA, rB] = await Promise.all([
        acquireIntegrationLock(dirA),
        acquireIntegrationLock(dirB),
      ]);
      assert.equal(typeof rA, "function");
      assert.equal(typeof rB, "function");
      await rA();
      await rB();
    } finally {
      rmSync(dirA, { recursive: true, force: true });
      rmSync(dirB, { recursive: true, force: true });
    }
  });

  it("rejects non-absolute and nonexistent paths", async () => {
    await assert.rejects(
      () => acquireIntegrationLock("relative/path"),
      /absolute/i,
    );
    const fakeAbs = join(tmpdir(), "gc-integ-lock-does-not-exist-" + Math.random());
    await assert.rejects(
      () => acquireIntegrationLock(fakeAbs),
      /exist/i,
    );
  });

  it("error on contention carries code ELOCKED", async () => {
    const dir = makeIntegLockTempDir();
    try {
      const release = await acquireIntegrationLock(dir);
      let caughtError;
      try {
        await acquireIntegrationLock(dir);
      } catch (e) {
        caughtError = e;
      }
      assert.ok(caughtError, "must throw on contention");
      assert.equal(caughtError.code, "ELOCKED", `expected code ELOCKED, got: ${caughtError.code}`);
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// Phase 2: workflow.pr_title parser (issue #896)
// ---------------------------------------------------------------------------

describe("parseGroundControlYaml workflow.pr_title", () => {
  it("accepts a fully populated workflow.pr_title block", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  pr_title:",
      "    types: [security, added, changed, deprecated, removed, fixed,",
      "            feat, fix, chore, docs, refactor, test, ci, build, perf, revert]",
      "    subject_pattern: \"^[a-z].*$\"",
      "    require_scope: false",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const pt = result.value.workflow.pr_title;
    assert.ok(Array.isArray(pt.types), "pr_title.types must be an array");
    assert.ok(pt.types.includes("feat"), "pr_title.types must include 'feat'");
    assert.ok(pt.types.includes("security"), "pr_title.types must include 'security'");
    assert.equal(pt.subject_pattern, "^[a-z].*$");
    assert.equal(pt.require_scope, false);
  });

  it("defaults workflow.pr_title to null when absent", () => {
    const yaml = ["schema_version: 1", "project: x", ""].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true);
    assert.equal(result.value.workflow.pr_title, null);
  });

  it("rejects workflow.pr_title with an unknown key (strict mode)", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  pr_title:",
      "    types: [feat, fix]",
      "    bogus_key: true",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes("workflow.pr_title") && e.includes("unknown key")),
      `expected unknown-key error, got: ${JSON.stringify(result.errors)}`,
    );
  });

  it("rejects workflow.pr_title.types that is not an array", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  pr_title:",
      "    types: feat",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes("pr_title.types")),
      `expected pr_title.types error, got: ${JSON.stringify(result.errors)}`,
    );
  });

  it("rejects workflow.pr_title.require_scope that is not a boolean", () => {
    const yaml = [
      "schema_version: 1",
      "project: x",
      "workflow:",
      "  pr_title:",
      "    require_scope: maybe",
      "",
    ].join("\n");
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes("require_scope")),
      `expected require_scope error, got: ${JSON.stringify(result.errors)}`,
    );
  });
});

// ---------------------------------------------------------------------------
// Phase 3: validateDocumentationOutcome (issue #896)
// ---------------------------------------------------------------------------

describe("validateDocumentationOutcome", () => {
  it("accepts outcome=updated with no rationale", () => {
    const result = validateDocumentationOutcome({ outcome: "updated" });
    assert.equal(result.ok, true);
    assert.equal(result.value.outcome, "updated");
  });

  it("accepts outcome=verified_unchanged with no rationale", () => {
    const result = validateDocumentationOutcome({ outcome: "verified_unchanged" });
    assert.equal(result.ok, true);
    assert.equal(result.value.outcome, "verified_unchanged");
  });

  it("accepts outcome=not_updated_authorized with a rationale", () => {
    const result = validateDocumentationOutcome({ outcome: "not_updated_authorized", rationale: "No docs touched by this change." });
    assert.equal(result.ok, true);
    assert.equal(result.value.outcome, "not_updated_authorized");
    assert.equal(result.value.rationale, "No docs touched by this change.");
  });

  it("rejects not_updated_authorized with missing rationale", () => {
    const result = validateDocumentationOutcome({ outcome: "not_updated_authorized" });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("rationale")));
  });

  it("rejects not_updated_authorized with empty rationale", () => {
    const result = validateDocumentationOutcome({ outcome: "not_updated_authorized", rationale: "" });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("rationale")));
  });

  it("rejects not_updated_authorized with rationale exceeding 2000 chars", () => {
    const result = validateDocumentationOutcome({ outcome: "not_updated_authorized", rationale: "x".repeat(2001) });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("rationale")));
  });

  it("rejects updated with a rationale (strict)", () => {
    const result = validateDocumentationOutcome({ outcome: "updated", rationale: "something" });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("rationale")));
  });

  it("rejects verified_unchanged with a rationale (strict)", () => {
    const result = validateDocumentationOutcome({ outcome: "verified_unchanged", rationale: "something" });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("rationale")));
  });

  it("rejects an unknown outcome value", () => {
    const result = validateDocumentationOutcome({ outcome: "skipped" });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("outcome")));
  });

  it("rejects null input", () => {
    const result = validateDocumentationOutcome(null);
    assert.equal(result.ok, false);
  });

  it("rejects missing outcome field", () => {
    const result = validateDocumentationOutcome({ rationale: "some reason" });
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("outcome")));
  });
});

// ---------------------------------------------------------------------------
// Phase 3: classifyChangedSurface (issue #896)
// ---------------------------------------------------------------------------

describe("classifyChangedSurface", () => {
  const REPO = "/fake/repo";

  it("classifies skills/implement/ paths as workflow surface", () => {
    const result = classifyChangedSurface(["skills/implement/SKILL.md"], REPO);
    assert.ok(result.classifications.length > 0);
    const cls = result.classifications.find((c) => c.path === "skills/implement/SKILL.md");
    assert.equal(cls.surface_class, "workflow");
    assert.ok(cls.doc_targets.length > 0);
  });

  it("classifies mcp/ground-control/index.js as mcp_tool surface", () => {
    const result = classifyChangedSurface(["mcp/ground-control/index.js"], REPO);
    const cls = result.classifications.find((c) => c.path === "mcp/ground-control/index.js");
    assert.equal(cls.surface_class, "mcp_tool");
  });

  // The tool registrations moved out of index.js when the entry point became an
  // environment bootstrap (issue #1562). Anchoring only on index.js would leave
  // the surface matching a file the contract had left, retiring the
  // documentation gate for every future tool change.
  it("classifies mcp/ground-control/server-runtime.js as mcp_tool surface", () => {
    const result = classifyChangedSurface(["mcp/ground-control/server-runtime.js"], REPO);
    const cls = result.classifications.find((c) => c.path === "mcp/ground-control/server-runtime.js");
    assert.equal(cls.surface_class, "mcp_tool");
    assert.ok(cls.doc_targets.includes("docs/DEVELOPMENT_WORKFLOW.md"));
  });

  it("classifies mcp/ground-control/lib.js as config_parser surface", () => {
    const result = classifyChangedSurface(["mcp/ground-control/lib.js"], REPO);
    const cls = result.classifications.find((c) => c.path === "mcp/ground-control/lib.js");
    assert.equal(cls.surface_class, "config_parser");
  });

  it("classifies tools/policy/checks.py as policy surface", () => {
    const result = classifyChangedSurface(["tools/policy/checks.py"], REPO);
    const cls = result.classifications.find((c) => c.path === "tools/policy/checks.py");
    assert.equal(cls.surface_class, "policy");
  });

  it("classifies architecture/adrs/ paths as adr surface", () => {
    const result = classifyChangedSurface(["architecture/adrs/054-foo.md"], REPO);
    const cls = result.classifications.find((c) => c.path === "architecture/adrs/054-foo.md");
    assert.equal(cls.surface_class, "adr");
  });

  it("classifies docs/ paths as doc surface with outcome_required=false", () => {
    const result = classifyChangedSurface(["docs/DEVELOPMENT_WORKFLOW.md"], REPO);
    const cls = result.classifications.find((c) => c.path === "docs/DEVELOPMENT_WORKFLOW.md");
    assert.equal(cls.surface_class, "doc");
    assert.equal(result.outcome_required, false);
  });

  it("classifies architecture/ paths as doc surface", () => {
    const result = classifyChangedSurface(["architecture/notes/foo.md"], REPO);
    const cls = result.classifications.find((c) => c.path === "architecture/notes/foo.md");
    assert.equal(cls.surface_class, "doc");
  });

  it("classifies unknown paths as unclassified", () => {
    const result = classifyChangedSurface(["some/random/file.txt"], REPO);
    const cls = result.classifications.find((c) => c.path === "some/random/file.txt");
    assert.equal(cls.surface_class, "unclassified");
    assert.equal(result.outcome_required, false);
  });

  it("sets outcome_required=true when any classified non-doc surface is present", () => {
    const result = classifyChangedSurface(["skills/implement/SKILL.md", "docs/DEVELOPMENT_WORKFLOW.md"], REPO);
    assert.equal(result.outcome_required, true);
  });

  it("sets outcome_required=false for docs-only diff", () => {
    const result = classifyChangedSurface(["docs/DEVELOPMENT_WORKFLOW.md", "architecture/adrs/054-foo.md"], REPO);
    // adr surface has outcome_required based on its classification
    const adrCls = result.classifications.find((c) => c.path === "architecture/adrs/054-foo.md");
    assert.equal(adrCls.surface_class, "adr");
    // adr is an outcome_required surface
    assert.equal(result.outcome_required, true);
  });

  it("rejects absolute paths (path-containment rejection)", () => {
    assert.throws(() => classifyChangedSurface(["/etc/passwd"], REPO), /absolute|containment|escape/i);
  });

  it("rejects path-traversal attempts (.. escape)", () => {
    assert.throws(() => classifyChangedSurface(["../../etc/passwd"], REPO), /absolute|containment|escape|traversal|inside|root/i);
  });

  // A doc_target naming a file that does not exist can never be satisfied, so the
  // surface's documentation requirement silently stops being enforceable. Issue #650
  // found the config_parser class pointing at an ADR filename that was never in the
  // tree. Directory targets (trailing "/") are prefixes, not files, and are excluded.
  it("resolves every file-shaped doc_target against the real repository tree", () => {
    const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
    const representativePaths = [
      "skills/implement/SKILL.md",
      "mcp/ground-control/index.js",
      "mcp/ground-control/lib.js",
      "mcp/ground-control/lib/ground-control-config.js",
      "tools/policy/checks.py",
      "architecture/adrs/054-documentation-coverage-gate.md",
      "docs/DOC_STYLE.md",
    ];
    const result = classifyChangedSurface(representativePaths, repoRoot);
    const targets = new Set(result.classifications.flatMap((c) => c.doc_targets));
    assert.ok(targets.size > 0, "expected the representative paths to carry doc targets");
    const missing = [...targets]
      .filter((target) => !target.endsWith("/"))
      .filter((target) => !existsSync(join(repoRoot, target)));
    assert.deepEqual(missing, [], `doc_targets naming files absent from the tree: ${missing.join(", ")}`);
  });
});
