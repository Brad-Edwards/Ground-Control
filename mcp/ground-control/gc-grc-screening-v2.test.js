import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  GRC_SCREENING_SCHEMA_VERSION_V2,
  GRC_SCREENING_GAP_REASONS,
  GRC_SCREENING_STALE_REASONS,
  isNonSecuritySurfacePath,
  classifyGrcScreening,
  serializeGrcScreeningDataV2,
  buildGrcScreeningRecordV2,
  parseGrcScreeningData,
  runComputeGrcScreening,
} from "./lib.js";

function makeTempRepoWithRemote() {
  const dir = mkdtempSync(join(tmpdir(), "gc-grc-screening-v2-test-"));
  execFileSync("git", ["-C", dir, "init", "-q"]);
  execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
  execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
  execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/acme/widgets.git"]);
  writeFileSync(join(dir, "README"), "x\n");
  execFileSync("git", ["-C", dir, "add", "README"]);
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  return dir;
}

const FORGED_MARKER = `<!-- gc:phase phase="preflight" issue="1" -->`;

// ---------------------------------------------------------------------------
// GC-GRC-009 — derivation-backed change screening (v2)
//
// v2 replaces the agent-asserted verdict with a computed classification:
// impact_set / gap_set / stale_set derived from the diff, the existing GRC
// CODE-link graph, and (when present) derived facts. There is no passing
// no_baseline verdict — an empty/absent baseline yields a gap_set over the
// touched security-relevant surface.
// ---------------------------------------------------------------------------

describe("GRC_SCREENING_SCHEMA_VERSION_V2", () => {
  it("is the v2 schema id, distinct from v1", () => {
    assert.equal(GRC_SCREENING_SCHEMA_VERSION_V2, "gc.implement.grc-screening/v2");
  });
});

describe("GRC_SCREENING_GAP_REASONS / STALE_REASONS", () => {
  it("expose frozen reason vocabularies including the derivation-coverage gap", () => {
    assert.ok(Object.isFrozen(GRC_SCREENING_GAP_REASONS));
    assert.ok(GRC_SCREENING_GAP_REASONS.includes("no_derivation_coverage"));
    assert.ok(Object.isFrozen(GRC_SCREENING_STALE_REASONS));
    assert.ok(GRC_SCREENING_STALE_REASONS.includes("linked_code_changed"));
  });
});

describe("isNonSecuritySurfacePath", () => {
  it("classifies docs, ADRs, skills prose, changelog fragments, and tests as non-source", () => {
    for (const p of [
      "docs/WORKFLOW.md",
      "architecture/adrs/058-derivation-first-continuous-grc.md",
      "skills/implement/steps/step-03.5-grc-screening.md",
      "changelog.d/1122.changed.md",
      "README.md",
      ".gc/plan-rules.md",
      "mcp/ground-control/lib.test.js",
      "mcp/ground-control/gc-grc-screening-v2.test.js",
      "backend/src/test/java/com/keplerops/groundcontrol/Foo.java",
      "tools/policy/checks.py",
    ]) {
      assert.equal(isNonSecuritySurfacePath(p), true, `${p} should be non-source`);
    }
  });

  it("classifies application source as a security-relevant surface", () => {
    for (const p of [
      "mcp/ground-control/lib.js",
      "mcp/ground-control/index.js",
      "backend/src/main/java/com/keplerops/groundcontrol/shared/security/Auth.java",
      "frontend/src/App.tsx",
    ]) {
      assert.equal(isNonSecuritySurfacePath(p), false, `${p} should be a security surface`);
    }
  });
});

describe("classifyGrcScreening — impact_set", () => {
  it("places existing entities whose CODE links overlap touched paths in impact_set", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["mcp/ground-control/lib.js", "mcp/ground-control/index.js"],
      entities: [
        { type: "threat_model", uid: "GC-TM-002", status: "ACTIVE", codeLinks: ["mcp/ground-control/lib.js", "mcp/ground-control/index.js"] },
        { type: "threat_model", uid: "GC-TM-999", status: "ACTIVE", codeLinks: ["backend/src/main/java/Unrelated.java"] },
      ],
      derivation: null,
    });
    const impactUids = r.impact_set.map((e) => e.uid);
    assert.deepEqual(impactUids, ["GC-TM-002"]);
    assert.deepEqual(r.impact_set[0].matched_paths.sort(), ["mcp/ground-control/index.js", "mcp/ground-control/lib.js"]);
    assert.equal(r.derived_verdict, "security_relevant");
  });

  it("matches a directory-prefix CODE link (e.g. mcp/) against a touched file under it", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["mcp/ground-control/lib.js"],
      entities: [{ type: "threat_model", uid: "GC-TM-002", status: "ACTIVE", codeLinks: ["mcp/"] }],
      derivation: null,
    });
    assert.equal(r.impact_set.length, 1);
    assert.deepEqual(r.impact_set[0].matched_paths, ["mcp/ground-control/lib.js"]);
  });
});

describe("classifyGrcScreening — gap_set (kill the no_baseline pass)", () => {
  it("empty baseline + touched source => gap_set over the source surface, never a pass", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["mcp/ground-control/lib.js", "docs/WORKFLOW.md"],
      entities: [],
      derivation: null,
    });
    assert.equal(r.gap_set.length, 1);
    assert.equal(r.gap_set[0].surface, "mcp/ground-control/lib.js");
    assert.equal(r.gap_set[0].reason, "no_derivation_coverage");
    assert.equal(r.derived_verdict, "security_relevant");
  });

  it("touched source modeled by derivation but with no threat coverage => no_threat_coverage gap", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["backend/src/main/java/Svc.java"],
      entities: [],
      derivation: { coveredPaths: ["backend/src/main/java/Svc.java"] },
    });
    assert.equal(r.gap_set.length, 1);
    assert.equal(r.gap_set[0].reason, "no_threat_coverage");
  });

  it("non-source-only change => empty gap_set and not_security_relevant", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["docs/WORKFLOW.md", "changelog.d/1.changed.md", "mcp/ground-control/lib.test.js"],
      entities: [],
      derivation: null,
    });
    assert.deepEqual(r.gap_set, []);
    assert.deepEqual(r.impact_set, []);
    assert.equal(r.derived_verdict, "not_security_relevant");
  });

  it("covered source path is not a gap", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["mcp/ground-control/lib.js"],
      entities: [{ type: "threat_model", uid: "GC-TM-002", status: "ACTIVE", codeLinks: ["mcp/ground-control/lib.js"] }],
      derivation: null,
    });
    assert.deepEqual(r.gap_set, []);
  });
});

describe("classifyGrcScreening — stale_set", () => {
  it("flags ACTIVE impacted entities as stale (linked code changed), not DRAFT ones", () => {
    const r = classifyGrcScreening({
      touchedPaths: ["mcp/ground-control/lib.js"],
      entities: [
        { type: "threat_model", uid: "GC-TM-002", status: "ACTIVE", codeLinks: ["mcp/ground-control/lib.js"] },
        { type: "threat_model", uid: "GC-TM-DRAFT", status: "DRAFT", codeLinks: ["mcp/ground-control/lib.js"] },
      ],
      derivation: null,
    });
    const staleUids = r.stale_set.map((e) => e.uid);
    assert.deepEqual(staleUids, ["GC-TM-002"]);
    assert.equal(r.stale_set[0].reason, "linked_code_changed");
    // both are impacted
    assert.equal(r.impact_set.length, 2);
  });
});

describe("buildGrcScreeningRecordV2", () => {
  const classification = {
    impact_set: [{ type: "threat_model", uid: "GC-TM-002", matched_paths: ["mcp/ground-control/lib.js"] }],
    gap_set: [{ surface: "mcp/ground-control/newthing.js", reason: "no_derivation_coverage", boundary: null }],
    stale_set: [{ type: "threat_model", uid: "GC-TM-002", reason: "linked_code_changed", changed_paths: ["mcp/ground-control/lib.js"] }],
    derived_verdict: "security_relevant",
  };
  const provenance = {
    base_commit_sha: "abc1234",
    commit_sha: "def5678",
    derivation_run_id: null,
    architecture_model_snapshot_id: null,
    threat_pack_id: null,
    threat_pack_version: null,
    control_ruleset_version: null,
    pack_checksums: {},
    capture_limits: [{ reason: "no_derivation_run", detail: "no derivation run for project", surface: "mcp/ground-control/newthing.js" }],
  };

  it("emits the v2 marker with schema and derived verdict, and a machine-parseable data block", () => {
    const body = buildGrcScreeningRecordV2({
      issueNumber: 1122,
      rationale: "Reworks the screening gate.",
      classification,
      candidate_threats: [],
      candidate_controls: [],
      provenance,
    });
    const firstLine = body.split("\n")[0];
    assert.ok(firstLine.startsWith("<!-- gc:grc-screening "), `first line must be main marker; got ${firstLine}`);
    assert.ok(firstLine.includes('schema="gc.implement.grc-screening/v2"'));
    assert.ok(firstLine.includes('verdict="security_relevant"'));
    assert.ok(body.includes("<!-- gc:grc-screening-data "));
    assert.ok(body.includes("no_derivation_coverage"));
  });

  it("round-trips through parseGrcScreeningData with the computed sets and provenance (reproducible)", () => {
    const body = buildGrcScreeningRecordV2({
      issueNumber: 1122,
      rationale: "Reworks the screening gate.",
      classification,
      candidate_threats: [{ producing_rule_id: "R1", stride_category: "TAMPERING", element_stable_key: "e1" }],
      candidate_controls: [],
      provenance,
    });
    const parsed = parseGrcScreeningData([body], 1122);
    assert.equal(parsed.schema, "gc.implement.grc-screening/v2");
    assert.equal(parsed.derived_verdict, "security_relevant");
    assert.deepEqual(parsed.gap_set, classification.gap_set);
    assert.deepEqual(parsed.impact_set, classification.impact_set);
    assert.deepEqual(parsed.stale_set, classification.stale_set);
    assert.equal(parsed.provenance.base_commit_sha, "abc1234");
    assert.equal(parsed.candidate_threats.length, 1);
  });
});

describe("runComputeGrcScreening — pre-network refusals", () => {
  it("rejects a non-positive issue number before any I/O", async () => {
    const r = await runComputeGrcScreening({ repoPath: "/nonexistent", issueNumber: 0 });
    assert.equal(r.ok, false);
    assert.equal(r.error, "grc_screening_input_invalid");
  });

  it("rejects a forged reserved marker in rationale before any I/O", async () => {
    const r = await runComputeGrcScreening({ repoPath: "/nonexistent", issueNumber: 5, rationale: FORGED_MARKER });
    assert.equal(r.ok, false);
    assert.equal(r.error, "grc_screening_reserved_marker");
  });
});

describe("runComputeGrcScreening — computes and posts a v2 record (injected deps, offline)", () => {
  it("empty baseline + touched source => posts a v2 record with a blocking gap_set (no free pass)", async () => {
    const dir = makeTempRepoWithRemote();
    let captured = null;
    try {
      const r = await runComputeGrcScreening({
        repoPath: dir,
        issueNumber: 1122,
        project: "acme",
        rationale: "Reworks the screening gate.",
        deps: {
          computeTouchedPaths: async () => ({ touchedPaths: ["mcp/x/thing.js", "docs/z.md"], base: "aaaaaaa", head: "bbbbbbb" }),
          fetchGrcGraph: async () => [],
          fetchDerivationState: async () => null,
          enumerateCandidates: async () => ({ candidate_threats: [], candidate_controls: [], control_ruleset_version: null, pack_checksums: {} }),
          postComment: async ({ body }) => {
            captured = body;
            return { ok: true, comment_url: "u", comment_id: 1, phase_marker_posted: true };
          },
        },
      });
      assert.equal(r.ok, true);
      assert.equal(r.derived_verdict, "security_relevant");
      assert.equal(r.gap_count, 1);
      assert.ok(captured.includes("gc.implement.grc-screening/v2"));
      assert.ok(captured.includes("no_derivation_coverage"));
      assert.ok(captured.includes("no_derivation_run"), "expected a recorded capture limit for the absent derivation run");
      const parsed = parseGrcScreeningData([captured], 1122);
      assert.equal(parsed.gap_set[0].surface, "mcp/x/thing.js");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("propagates a body-refusal envelope from the post tail", async () => {
    const dir = makeTempRepoWithRemote();
    try {
      const r = await runComputeGrcScreening({
        repoPath: dir,
        issueNumber: 1122,
        project: "acme",
        deps: {
          computeTouchedPaths: async () => ({ touchedPaths: ["mcp/x/thing.js"], base: "a", head: "b" }),
          fetchGrcGraph: async () => [],
          fetchDerivationState: async () => null,
          enumerateCandidates: async () => ({ candidate_threats: [], candidate_controls: [] }),
          postComment: async () => ({ ok: false, error: "grc_screening_body_too_large", issue_number: 1122 }),
        },
      });
      assert.equal(r.ok, false);
      assert.equal(r.error, "grc_screening_body_too_large");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("serializeGrcScreeningDataV2", () => {
  it("serializes a v2 data-block comment carrying the schema", () => {
    const s = serializeGrcScreeningDataV2({
      schema: GRC_SCREENING_SCHEMA_VERSION_V2,
      derived_verdict: "not_security_relevant",
      impact_set: [],
      gap_set: [],
      stale_set: [],
      candidate_threats: [],
      candidate_controls: [],
      provenance: { base_commit_sha: "a", commit_sha: "b", capture_limits: [] },
    });
    assert.ok(s.startsWith("<!-- gc:grc-screening-data "));
    assert.ok(s.endsWith("-->"));
    assert.ok(s.includes("gc.implement.grc-screening/v2"));
  });
});
