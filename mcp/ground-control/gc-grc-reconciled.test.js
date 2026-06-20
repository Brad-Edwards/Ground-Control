import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  serializeGrcScreeningData,
  parseGrcScreeningData,
  buildGrcScreeningRecord,
  validateGrcScreeningInput,
  runPostGrcScreening,
  GRC_ENTITY_TYPES,
  runAssertGrcReconciled,
} from "./lib.js";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const GH_NAME_WITH_OWNER = "nameWithOwner";

function initGitRepo(dir) {
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  return dir;
}

function makeTempRepo() {
  return initGitRepo(mkdtempSync(join(tmpdir(), "gc-grc-reconciled-test-")));
}

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

// `gh api --paginate --slurp` wraps each page's comments array in an outer array.
function slurpComments(comments) {
  return JSON.stringify([comments]);
}

// Hermetic fetch mock (mirrors mockFetchForRequirements from lib.test.js)
function mockFetchForGrc(routesByUrl) {
  const originalFetch = globalThis.fetch;
  const originalBase = process.env.GC_BASE_URL;
  process.env.GC_BASE_URL = "http://test.invalid";
  globalThis.fetch = async (url) => {
    const u = String(url);
    for (const [pattern, handler] of routesByUrl) {
      if (u.includes(pattern)) {
        const r = await handler(u);
        return {
          status: r.status ?? 200,
          ok: (r.status ?? 200) < 400,
          text: async () => JSON.stringify(r.body ?? null),
          json: async () => r.body ?? null,
        };
      }
    }
    return {
      status: 404, ok: false,
      text: async () => JSON.stringify({ error: { code: "NOT_FOUND", message: `no route for ${u}` } }),
    };
  };
  return () => {
    globalThis.fetch = originalFetch;
    if (originalBase === undefined) delete process.env.GC_BASE_URL;
    else process.env.GC_BASE_URL = originalBase;
  };
}

// Shim repo that handles the gh calls inside runAssertGrcReconciled:
//  1. repo view → nameWithOwner
//  2. api --method GET --paginate --slurp → issue comments (for readIssueCommentBodies)
//  3. api --method POST → phase marker post
function makeGrcShimRepo({ comments = [], commentId = 9100 } = {}) {
  return makeRouteShimRepo({
    repoPrefix: "gc-grc-shim-",
    binPrefix: "gc-grc-bin-",
    ghHandler: {
      routes: [
        { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
        { argv_prefix: ["api", "--method", "GET", "--paginate", "--slurp"], stdout: slurpComments(comments) },
        { argv_prefix: ["api", "--method", "POST"], stdout: JSON.stringify({ id: commentId, html_url: `https://github.com/fake/repo/issues/1100#issuecomment-${commentId}` }) },
      ],
    },
  });
}

// Minimum valid security_relevant screening input
function screeningInput(overrides = {}) {
  return {
    issueNumber: 1100,
    verdict: "security_relevant",
    rationale: "Changes authentication flow.",
    entities_created: [{ type: "threat_model", uid: "TM-001" }],
    entities_updated: [],
    entities_confirmed: [],
    code_links: [{ owner_type: "threat_model", owner_uid: "TM-001", target_identifier: "backend/src/main/java/Foo.java" }],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// GRC_ENTITY_TYPES
// ---------------------------------------------------------------------------

describe("GRC_ENTITY_TYPES", () => {
  it("exports the three canonical entity types", () => {
    assert.ok(Array.isArray(GRC_ENTITY_TYPES));
    assert.ok(GRC_ENTITY_TYPES.includes("threat_model"));
    assert.ok(GRC_ENTITY_TYPES.includes("risk_scenario"));
    assert.ok(GRC_ENTITY_TYPES.includes("control"));
    assert.equal(GRC_ENTITY_TYPES.length, 3);
  });

  it("is frozen", () => {
    assert.ok(Object.isFrozen(GRC_ENTITY_TYPES));
  });
});

// ---------------------------------------------------------------------------
// serializeGrcScreeningData / parseGrcScreeningData — round-trip
// ---------------------------------------------------------------------------

describe("serializeGrcScreeningData", () => {
  it("returns an HTML comment with the gc:grc-screening-data tag", () => {
    const payload = { schema: "gc.implement.grc-screening/v1", verdict: "not_security_relevant", entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [] };
    const out = serializeGrcScreeningData(payload);
    assert.ok(typeof out === "string");
    assert.ok(out.startsWith("<!-- gc:grc-screening-data "), `expected data comment; got: ${out.slice(0, 60)}`);
    assert.ok(out.endsWith(" -->"), `expected closing -->; got: ${out.slice(-20)}`);
  });

  it("embeds JSON-stringified payload", () => {
    const payload = { schema: "gc.implement.grc-screening/v1", verdict: "security_relevant", entities_created: [{ uid: "TM-001", type: "threat_model" }], entities_updated: [], entities_confirmed: [], code_links: [{ owner_type: "threat_model", owner_uid: "TM-001", target_identifier: "src/Foo.java" }] };
    const out = serializeGrcScreeningData(payload);
    // Extract JSON from the comment
    const json = out.slice("<!-- gc:grc-screening-data ".length, out.length - " -->".length);
    const parsed = JSON.parse(json);
    assert.equal(parsed.verdict, "security_relevant");
    assert.equal(parsed.entities_created[0].uid, "TM-001");
  });
});

describe("parseGrcScreeningData", () => {
  it("round-trips a not_security_relevant record", () => {
    const input = {
      issueNumber: 1100,
      verdict: "not_security_relevant",
      rationale: "Doc-only change.",
      entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [],
    };
    const body = buildGrcScreeningRecord(input);
    const result = parseGrcScreeningData([body], 1100);
    assert.ok(result !== null, "expected non-null parse result");
    assert.equal(result.verdict, "not_security_relevant");
    assert.deepEqual(result.entities_created, []);
    assert.deepEqual(result.code_links, []);
  });

  it("round-trips a security_relevant record", () => {
    const input = screeningInput();
    const body = buildGrcScreeningRecord(input);
    const result = parseGrcScreeningData([body], 1100);
    assert.ok(result !== null, "expected non-null parse result");
    assert.equal(result.verdict, "security_relevant");
    assert.equal(result.entities_created[0].uid, "TM-001");
    assert.equal(result.code_links[0].target_identifier, "backend/src/main/java/Foo.java");
  });

  it("round-trips a no_baseline record", () => {
    const input = {
      issueNumber: 1100, verdict: "no_baseline",
      rationale: "No baseline.",
      entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [],
    };
    const body = buildGrcScreeningRecord(input);
    const result = parseGrcScreeningData([body], 1100);
    assert.ok(result !== null);
    assert.equal(result.verdict, "no_baseline");
  });

  it("returns null when no screening record is present", () => {
    const result = parseGrcScreeningData(["some unrelated comment body"], 1100);
    assert.equal(result, null);
  });

  it("returns null when body array is empty", () => {
    assert.equal(parseGrcScreeningData([], 1100), null);
  });

  it("returns null when the data block is missing (marker present but no data block)", () => {
    // Only the marker, no data comment following it
    const markerOnly = `<!-- gc:grc-screening issue="1100" schema="gc.implement.grc-screening/v1" verdict="security_relevant" -->`;
    assert.equal(parseGrcScreeningData([markerOnly], 1100), null);
  });

  it("ignores records for a different issue number", () => {
    const input = {
      issueNumber: 999,
      verdict: "not_security_relevant",
      rationale: "Doc-only.",
      entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [],
    };
    const body = buildGrcScreeningRecord(input);
    // Asking for issue 1100 should not return a record from issue 999
    assert.equal(parseGrcScreeningData([body], 1100), null);
  });

  it("tolerates malformed JSON in data block (skips, returns null)", () => {
    const badData = `<!-- gc:grc-screening issue="1100" schema="gc.implement.grc-screening/v1" verdict="no_baseline" -->\n<!-- gc:grc-screening-data {broken json -->`;
    assert.equal(parseGrcScreeningData([badData], 1100), null);
  });

  it("returns the LATEST data block when multiple records are present", () => {
    const first = buildGrcScreeningRecord({ issueNumber: 1100, verdict: "no_baseline", rationale: "No baseline.", entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [] });
    const second = buildGrcScreeningRecord({ issueNumber: 1100, verdict: "not_security_relevant", rationale: "Updated verdict.", entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [] });
    const result = parseGrcScreeningData([first, second], 1100);
    assert.equal(result.verdict, "not_security_relevant");
  });
});

// ---------------------------------------------------------------------------
// validateGrcScreeningInput — canonical entity type enforcement
// ---------------------------------------------------------------------------

describe("validateGrcScreeningInput — canonical entity type enforcement", () => {
  it("accepts canonical entity types (threat_model, risk_scenario, control)", () => {
    for (const type of ["threat_model", "risk_scenario", "control"]) {
      const r = validateGrcScreeningInput(screeningInput({
        entities_created: [{ type, uid: "X-001" }],
        code_links: [{ owner_type: type, owner_uid: "X-001", target_identifier: "src/Foo.java" }],
      }));
      assert.equal(r.ok, true, `${type} should be valid; errors: ${r.errors?.join("; ")}`);
    }
  });

  it("rejects unknown entity type in entities_created for security_relevant", () => {
    const r = validateGrcScreeningInput(screeningInput({
      entities_created: [{ type: "vulnerability", uid: "V-001" }],
    }));
    assert.equal(r.ok, false);
    assert.ok(r.errors.some((e) => /type|entity/i.test(e)), `expected type error; got: ${r.errors.join("; ")}`);
  });

  it("rejects unknown owner_type in code_links for security_relevant", () => {
    const r = validateGrcScreeningInput(screeningInput({
      code_links: [{ owner_type: "vulnerability", owner_uid: "V-001", target_identifier: "src/Foo.java" }],
    }));
    assert.equal(r.ok, false);
    assert.ok(r.errors.some((e) => /owner_type|type/i.test(e)), `expected owner_type error; got: ${r.errors.join("; ")}`);
  });

  it("normalizes type with dashes and spaces (threat-model → threat_model)", () => {
    const r = validateGrcScreeningInput(screeningInput({
      entities_created: [{ type: "threat-model", uid: "TM-001" }],
    }));
    assert.equal(r.ok, true, `threat-model should normalize; errors: ${r.errors?.join("; ")}`);
  });

  it("normalizes type case (Threat_Model → threat_model)", () => {
    const r = validateGrcScreeningInput(screeningInput({
      entities_created: [{ type: "Threat_Model", uid: "TM-001" }],
    }));
    assert.equal(r.ok, true, `Threat_Model should normalize; errors: ${r.errors?.join("; ")}`);
  });

  it("does NOT reject unknown type for not_security_relevant (type optional there)", () => {
    const r = validateGrcScreeningInput({
      issueNumber: 1100,
      verdict: "not_security_relevant",
      rationale: "Doc only.",
      entities_created: [],
      entities_updated: [],
      entities_confirmed: [],
      code_links: [],
    });
    assert.equal(r.ok, true, `not_security_relevant with empty arrays should pass; errors: ${r.errors?.join("; ")}`);
  });
});

// ---------------------------------------------------------------------------
// HTML comment delimiter injection check
// ---------------------------------------------------------------------------

describe("runPostGrcScreening — HTML comment delimiter rejection", () => {
  it("rejects <!-- in rationale", async () => {
    const dir = makeTempRepo();
    try {
      const r = await runPostGrcScreening({
        repoPath: dir,
        ...screeningInput({ verdict: "not_security_relevant", entities_created: [], code_links: [], rationale: "<!-- some injection" }),
      });
      assert.equal(r.ok, false);
      assert.match(r.error, /grc_screening_reserved_marker/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects --> in entity uid", async () => {
    const dir = makeTempRepo();
    try {
      const r = await runPostGrcScreening({
        repoPath: dir,
        ...screeningInput({ entities_created: [{ type: "threat_model", uid: "TM-001 -->" }] }),
      });
      assert.equal(r.ok, false);
      assert.match(r.error, /grc_screening_reserved_marker|grc_screening_html_comment/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — input validation
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — input validation", () => {
  it("throws on non-positive issue_number", async () => {
    const dir = makeTempRepo();
    try {
      await assert.rejects(
        runAssertGrcReconciled({ repoPath: dir, issueNumber: 0 }),
        /positive integer issue_number/,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — grc_screening_record_missing
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — screening record missing", () => {
  it("returns grc_screening_record_missing when no screening record exists on issue", async () => {
    const shim = makeGrcShimRepo({ comments: [] });
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_screening_record_missing");
      assert.equal(r.issue_number, 1100);
      assert.ok(typeof r.next_action === "string");
    } finally {
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — no tool-level override bypass (codex cycle-1 security finding)
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — no override bypass", () => {
  it("ignores override-like arguments and still verifies a security_relevant record", async () => {
    // The override path was removed: a free-text reason is not authorization, so
    // override-ish args must NOT mint the marker — a security_relevant record
    // with a missing entity still fails the gate.
    const screeningBody = buildGrcScreeningRecord(screeningInput());
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9200 });
    const restore = mockFetchForGrc([
      ["/api/v1/threat-models/uid/TM-001", async () => ({ status: 404, body: null })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100, override: true, overrideReason: "attacker-supplied reason" }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_not_reconciled");
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — not_security_relevant / no_baseline paths
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — not_security_relevant verdict", () => {
  it("passes and posts grc_reconciled marker for not_security_relevant verdict", async () => {
    const screeningBody = buildGrcScreeningRecord({
      issueNumber: 1100, verdict: "not_security_relevant",
      rationale: "Doc only.",
      entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [],
    });
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9201 });
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.verdict, "not_security_relevant");
      assert.equal(r.comment_id, 9201);
      assert.deepEqual(r.phase_marker, { phase: "grc_reconciled", issue_number: 1100 });
      assert.deepEqual(r.missing, []);
    } finally {
      shim.cleanup();
    }
  });
});

describe("runAssertGrcReconciled — no_baseline verdict", () => {
  it("passes and posts grc_reconciled marker for no_baseline verdict", async () => {
    const screeningBody = buildGrcScreeningRecord({
      issueNumber: 1100, verdict: "no_baseline",
      rationale: "No threat model baseline.",
      entities_created: [], entities_updated: [], entities_confirmed: [], code_links: [],
    });
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9202 });
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.verdict, "no_baseline");
      assert.deepEqual(r.missing, []);
    } finally {
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — security_relevant: entity missing
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — security_relevant entity missing", () => {
  it("returns ok=false with missing entity when entity 404s", async () => {
    const screeningBody = buildGrcScreeningRecord(screeningInput());
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9203 });
    const restore = mockFetchForGrc([
      // TM-001 not found (404)
      ["/api/v1/threat-models/uid/TM-001", async () => ({ status: 404, body: null })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_not_reconciled");
      assert.ok(Array.isArray(r.missing));
      const missing = r.missing.find((m) => m.uid === "TM-001" || m.owner_uid === "TM-001");
      assert.ok(missing, `expected missing entry for TM-001; got: ${JSON.stringify(r.missing)}`);
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("returns ok=false with missing for unknown entity type", async () => {
    // Construct a fake screening record with an unknown entity type by patching the data
    const validBody = buildGrcScreeningRecord({
      issueNumber: 1100, verdict: "security_relevant",
      rationale: "Test.",
      entities_created: [{ type: "threat_model", uid: "TM-001" }],
      entities_updated: [], entities_confirmed: [],
      code_links: [{ owner_type: "threat_model", owner_uid: "TM-001", target_identifier: "src/Foo.java" }],
    });
    // Manually replace the data payload to use an unknown type
    const dataPayload = { schema: "gc.implement.grc-screening/v1", verdict: "security_relevant", entities_created: [{ type: "unknown_thing", uid: "X-001" }], entities_updated: [], entities_confirmed: [], code_links: [] };
    const marker = `<!-- gc:grc-screening issue="1100" schema="gc.implement.grc-screening/v1" verdict="security_relevant" -->`;
    const dataBlock = `<!-- gc:grc-screening-data ${JSON.stringify(dataPayload)} -->`;
    const fakeBody = `${marker}\n${dataBlock}\n## tail`;
    const shim = makeGrcShimRepo({ comments: [{ body: fakeBody }], commentId: 9204 });
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_not_reconciled");
      const missing = r.missing.find((m) => m.reason === "unknown_entity_type");
      assert.ok(missing, `expected unknown_entity_type in missing; got: ${JSON.stringify(r.missing)}`);
    } finally {
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — security_relevant: CODE link missing
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — security_relevant code_link missing", () => {
  it("returns ok=false with missing code_link when no CODE link matches target_identifier", async () => {
    const screeningBody = buildGrcScreeningRecord(screeningInput());
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9205 });
    const restore = mockFetchForGrc([
      // TM-001 exists
      ["/api/v1/threat-models/uid/TM-001", async () => ({ body: { id: "tm-uuid-1", uid: "TM-001" } })],
      // Links for TM-001: no CODE link matching the target
      ["/api/v1/threat-models/tm-uuid-1/links", async () => ({
        body: [{ id: "link-1", target_type: "ASSET", target_identifier: "asset-uuid-1", link_type: "MITIGATES" }],
      })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_not_reconciled");
      const missing = r.missing.find((m) => m.kind === "code_link");
      assert.ok(missing, `expected code_link in missing; got: ${JSON.stringify(r.missing)}`);
      assert.equal(missing.target_identifier, "backend/src/main/java/Foo.java");
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — security_relevant: all present → ok
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — security_relevant all present", () => {
  it("returns ok=true and posts grc_reconciled marker when all entities and CODE links are present", async () => {
    const screeningBody = buildGrcScreeningRecord(screeningInput());
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9206 });
    const restore = mockFetchForGrc([
      ["/api/v1/threat-models/uid/TM-001", async () => ({ body: { id: "tm-uuid-1", uid: "TM-001" } })],
      ["/api/v1/threat-models/tm-uuid-1/links", async () => ({
        body: [{ id: "link-2", target_type: "CODE", target_identifier: "backend/src/main/java/Foo.java", link_type: "IMPLEMENTS" }],
      })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, true);
      assert.equal(r.verdict, "security_relevant");
      assert.deepEqual(r.missing, []);
      assert.equal(r.comment_id, 9206);
      assert.deepEqual(r.phase_marker, { phase: "grc_reconciled", issue_number: 1100 });
      assert.ok(typeof r.checked === "number" && r.checked >= 0);
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — code_link owner not among listed entities
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — code_link owner not among listed entities", () => {
  it("resolves an unlisted code_link owner directly and fails when that owner is absent (regression: not silently skipped)", async () => {
    // The screening record lists TM-001 as an entity, but the code_link's owner
    // is TM-999, which is NOT in any entity array. A correct gate resolves the
    // owner directly; a buggy one keys only off the entity map and skips it,
    // passing an unverified link.
    const input = screeningInput({
      entities_created: [{ type: "threat_model", uid: "TM-001" }],
      code_links: [{ owner_type: "threat_model", owner_uid: "TM-999", target_identifier: "backend/src/main/java/Bar.java" }],
    });
    const screeningBody = buildGrcScreeningRecord(input);
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9210 });
    const restore = mockFetchForGrc([
      ["/api/v1/threat-models/uid/TM-001", async () => ({ body: { id: "tm-uuid-1", uid: "TM-001" } })],
      ["/api/v1/threat-models/uid/TM-999", async () => ({ status: 404, body: null })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_not_reconciled");
      const missing = r.missing.find((m) => m.kind === "code_link" && m.owner_uid === "TM-999");
      assert.ok(missing, `expected owner_missing code_link for TM-999; got: ${JSON.stringify(r.missing)}`);
      assert.equal(missing.reason, "owner_missing");
    } finally {
      restore();
      shim.cleanup();
    }
  });

  it("passes when an unlisted code_link owner resolves directly and has the CODE link", async () => {
    const input = screeningInput({
      entities_created: [{ type: "threat_model", uid: "TM-001" }],
      code_links: [{ owner_type: "threat_model", owner_uid: "TM-999", target_identifier: "backend/src/main/java/Bar.java" }],
    });
    const screeningBody = buildGrcScreeningRecord(input);
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9211 });
    const restore = mockFetchForGrc([
      ["/api/v1/threat-models/uid/TM-001", async () => ({ body: { id: "tm-uuid-1", uid: "TM-001" } })],
      ["/api/v1/threat-models/uid/TM-999", async () => ({ body: { id: "tm-uuid-999", uid: "TM-999" } })],
      ["/api/v1/threat-models/tm-uuid-999/links", async () => ({
        body: [{ id: "link-9", target_type: "CODE", target_identifier: "backend/src/main/java/Bar.java", link_type: "IMPLEMENTS" }],
      })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, true);
      assert.deepEqual(r.missing, []);
      assert.deepEqual(r.phase_marker, { phase: "grc_reconciled", issue_number: 1100 });
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runAssertGrcReconciled — cross-type UID collision (codex cycle-1 core finding)
// ---------------------------------------------------------------------------

describe("runAssertGrcReconciled — cross-type UID collision", () => {
  it("resolves a code_link against the correct aggregate type when an entity of a different type shares the UID", async () => {
    // A threat_model and a risk_scenario both use UID "SHARED-1". The code_link
    // owner is the risk_scenario. With uid-only cache keys the verifier would
    // reuse the threat_model id and query the wrong aggregate; type-aware keys
    // resolve the risk_scenario independently and find its CODE link.
    const input = screeningInput({
      entities_created: [{ type: "threat_model", uid: "SHARED-1" }],
      code_links: [{ owner_type: "risk_scenario", owner_uid: "SHARED-1", target_identifier: "backend/src/main/java/X.java" }],
    });
    const screeningBody = buildGrcScreeningRecord(input);
    const shim = makeGrcShimRepo({ comments: [{ body: screeningBody }], commentId: 9220 });
    const restore = mockFetchForGrc([
      ["/api/v1/threat-models/uid/SHARED-1", async () => ({ body: { id: "tm-uuid-shared", uid: "SHARED-1" } })],
      ["/api/v1/risk-scenarios/uid/SHARED-1", async () => ({ body: { id: "rs-uuid-shared", uid: "SHARED-1" } })],
      ["/api/v1/risk-scenarios/rs-uuid-shared/links", async () => ({
        body: [{ id: "link-x", target_type: "CODE", target_identifier: "backend/src/main/java/X.java", link_type: "MITIGATES" }],
      })],
    ]);
    try {
      const r = await withShimPath(shim.binDir, () =>
        runAssertGrcReconciled({ repoPath: shim.repoDir, issueNumber: 1100 }),
      );
      assert.equal(r.ok, true, `expected ok; got: ${JSON.stringify(r)}`);
      assert.deepEqual(r.missing, []);
      assert.deepEqual(r.phase_marker, { phase: "grc_reconciled", issue_number: 1100 });
    } finally {
      restore();
      shim.cleanup();
    }
  });
});

// ---------------------------------------------------------------------------
// runPostFinalReport — grc_reconciled prerequisite (issue #1100)
// ---------------------------------------------------------------------------

describe("runPostFinalReport grc_reconciled prerequisite (issue #1100)", () => {
  function makeFinalReportShimRepo({ ghHandler }) {
    return makeRouteShimRepo({ ghHandler, repoPrefix: "gc-final-grc-", binPrefix: "gc-final-grc-bin-" });
  }

  it("refuses with phase_prerequisite_missing when no grc_reconciled marker exists (traceability_reconciled present)", async () => {
    // Provide traceability_reconciled marker but NOT grc_reconciled
    const traceabilityMarker = `<!-- gc:phase phase="traceability_reconciled" issue="1100" -->`;
    const shim = makeFinalReportShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "--method", "GET", "--paginate", "--slurp"], stdout: slurpComments([{ body: traceabilityMarker }]) },
        ],
      },
    });
    try {
      await withShimPath(shim.binDir, async () => {
        const { runPostFinalReport } = await import("./lib.js");
        const r = await runPostFinalReport({
          repoPath: shim.repoDir,
          issueNumber: 1100, prNumber: 42,
          plainEnglishOutcome: "Maintainers get a clear outcome before the reconciled evidence.",
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green", sonarStatus: "passed",
        });
        assert.equal(r.ok, false);
        assert.equal(r.error, "phase_prerequisite_missing");
        assert.ok(r.missing.includes("grc_reconciled"), `expected grc_reconciled in missing; got: ${JSON.stringify(r.missing)}`);
      });
    } finally {
      shim.cleanup();
    }
  });

  it("passes when BOTH traceability_reconciled AND grc_reconciled markers are present", async () => {
    const traceabilityMarker = `<!-- gc:phase phase="traceability_reconciled" issue="1100" -->`;
    const grcMarker = `<!-- gc:phase phase="grc_reconciled" issue="1100" -->`;
    const shim = makeFinalReportShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "--method", "GET", "--paginate", "--slurp"], stdout: slurpComments([{ body: traceabilityMarker }, { body: grcMarker }]) },
          { argv_prefix: ["api", "--method", "POST"], stdout: JSON.stringify({ id: 9300, html_url: "https://github.com/fake/repo/issues/1100#issuecomment-9300" }) },
        ],
      },
    });
    try {
      await withShimPath(shim.binDir, async () => {
        const { runPostFinalReport } = await import("./lib.js");
        const r = await runPostFinalReport({
          repoPath: shim.repoDir,
          issueNumber: 1100, prNumber: 42,
          plainEnglishOutcome: "Maintainers get a clear outcome before the reconciled evidence.",
          requirements: [],
          reviews: [{ reviewer: "codex", summary: "1 cycle, clean" }],
          ciStatus: "green", sonarStatus: "passed",
        });
        assert.equal(r.ok, true);
        assert.equal(r.comment_id, 9300);
      });
    } finally {
      shim.cleanup();
    }
  });

  it("quickfix lane bypasses grc_reconciled prerequisite", async () => {
    const shim = makeFinalReportShimRepo({
      ghHandler: {
        routes: [
          { argv_prefix: ["repo", "view", "--json", GH_NAME_WITH_OWNER], stdout: JSON.stringify({ nameWithOwner: "fake/repo" }) },
          { argv_prefix: ["api", "--method", "POST"], stdout: JSON.stringify({ id: 9301, html_url: "https://github.com/fake/repo/issues/1100#issuecomment-9301" }) },
        ],
      },
    });
    try {
      await withShimPath(shim.binDir, async () => {
        const { runPostFinalReport } = await import("./lib.js");
        const r = await runPostFinalReport({
          repoPath: shim.repoDir,
          issueNumber: 1100, prNumber: 42,
          lane: "quickfix",
          requirements: [],
          reviews: [],
          ciStatus: "green", sonarStatus: "passed",
        });
        assert.equal(r.ok, true);
        assert.equal(r.comment_id, 9301);
      });
    } finally {
      shim.cleanup();
    }
  });
});
