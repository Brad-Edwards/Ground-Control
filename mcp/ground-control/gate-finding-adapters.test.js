import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  MAX_FINDINGS_PER_ATTEMPT,
  ciGateFindings,
  policyGateFindings,
  reviewGateFindings,
  sonarGateFindings,
  spotbugsGateFindings,
  valeGateFindings,
} from "./gate-finding-adapters.js";

/** Fields that would turn the measurement projection into a second copy of the issue record. */
const PROSE_FIELDS = [
  "title",
  "body",
  "message",
  "path",
  "file",
  "line",
  "component",
  "details",
  "sweep_evidence",
  "instances",
];

function assertCarriesNoProse(findings) {
  for (const finding of findings) {
    for (const field of PROSE_FIELDS) {
      assert.ok(
        !(field in finding),
        `finding leaked '${field}' into the measurement projection: ${JSON.stringify(finding)}`,
      );
    }
  }
}

describe("gate finding adapters — reviewers", () => {
  it("attributes each finding to the reviewer that produced it", () => {
    const { findings } = reviewGateFindings(
      [
        { reviewer: "core", classification: "one-off", title: "a", path: "A.java", line: 1 },
        { reviewer: "security", classification: "one-off", title: "b", path: "B.java", line: 2 },
      ],
      "codex",
    );

    assert.deepEqual(
      findings.map((f) => f.sourceId),
      ["core", "security"],
    );
    assert.ok(findings.every((f) => f.sourceKind === "reviewer"));
  });

  it("falls back to the cycle reviewer when a finding carries no label", () => {
    const { findings } = reviewGateFindings(
      [{ classification: "one-off", title: "a", path: "A.java", line: 1 }],
      "test-quality",
    );

    assert.equal(findings[0].sourceId, "test-quality");
  });

  it("records no severity, because the review envelope has none", () => {
    // ADR-031's proposed severity is not an implemented source contract. Guessing one
    // would fabricate a distribution that looks like a measurement.
    const { findings } = reviewGateFindings(
      [{ reviewer: "core", classification: "class", category: { shape: "x" }, title: "t" }],
      "codex",
    );

    assert.ok(!("severity" in findings[0]));
  });

  it("keeps the category shape for class findings and omits it for one-offs", () => {
    const { findings } = reviewGateFindings(
      [
        { reviewer: "core", classification: "class", category: { shape: "hand-rolled envelope" }, title: "a" },
        { reviewer: "core", classification: "one-off", title: "b", path: "B.java", line: 9 },
      ],
      "codex",
    );

    assert.equal(findings[0].category, "hand-rolled envelope");
    assert.equal(findings[0].classification, "class");
    // A one-off has no recurring shape; a synthetic "uncategorized" would invent a
    // category that does not exist and pollute recurrence aggregates.
    assert.ok(!("category" in findings[1]));
    assert.equal(findings[1].classification, "one-off");
  });

  it("carries no prose into the projection", () => {
    const { findings } = reviewGateFindings(
      [
        {
          reviewer: "core",
          classification: "one-off",
          title: "Bypasses the canonical envelope",
          body: "Long remediation prose that must never be persisted.",
          path: "backend/src/main/java/Foo.java",
          line: 42,
          sweep_evidence: "grepped everything",
        },
      ],
      "codex",
    );

    assertCarriesNoProse(findings);
  });

  it("detects, and never disposes", () => {
    // The review wrapper posts `decision: fix` before the agent has repaired anything.
    // That is intent; projecting it as `fixed` would report a repair that never happened.
    const { findings } = reviewGateFindings(
      [{ reviewer: "core", classification: "one-off", title: "a", decision: "fix" }],
      "codex",
    );

    assert.equal(findings[0].disposition, "open");
  });
});

describe("gate finding adapters — identity", () => {
  it("derives the same key for the same finding across attempts", () => {
    const finding = { reviewer: "core", classification: "one-off", title: "t", path: "A.java", line: 7 };

    const first = reviewGateFindings([finding], "codex").findings[0].findingKey;
    const second = reviewGateFindings([{ ...finding }], "codex").findings[0].findingKey;

    // A live observation and its reconciliation must converge to one row rather than
    // counting as two station attempts' worth of findings.
    assert.equal(first, second);
  });

  it("separates key components so adjacent fields cannot collide", () => {
    // Without a separator between digest parts, ("ab","c") and ("a","bc") hash
    // identically and two distinct findings silently collapse into one row.
    const left = reviewGateFindings(
      [{ reviewer: "core", classification: "one-off", title: "ab", path: "c" }],
      "codex",
    ).findings[0].findingKey;
    const right = reviewGateFindings(
      [{ reviewer: "core", classification: "one-off", title: "a", path: "bc" }],
      "codex",
    ).findings[0].findingKey;

    assert.notEqual(left, right);
  });

  it("distinguishes the same rule at different sites", () => {
    const { findings } = reviewGateFindings(
      [
        { reviewer: "core", classification: "one-off", title: "t", path: "A.java", line: 1 },
        { reviewer: "core", classification: "one-off", title: "t", path: "B.java", line: 1 },
      ],
      "codex",
    );

    assert.equal(findings.length, 2);
    assert.notEqual(findings[0].findingKey, findings[1].findingKey);
  });

  it("collapses an exact duplicate rather than double-counting it", () => {
    const finding = { reviewer: "core", classification: "one-off", title: "t", path: "A.java", line: 1 };

    const { findings } = reviewGateFindings([finding, { ...finding }], "codex");

    assert.equal(findings.length, 1);
  });

  it("bounds the batch and reports the overflow instead of dropping it silently", () => {
    const many = Array.from({ length: MAX_FINDINGS_PER_ATTEMPT + 5 }, (_unused, index) => ({
      reviewer: "core",
      classification: "one-off",
      title: `t${index}`,
      path: `F${index}.java`,
      line: index,
    }));

    const { findings, dropped } = reviewGateFindings(many, "codex");

    assert.equal(findings.length, MAX_FINDINGS_PER_ATTEMPT);
    assert.equal(dropped, 5);
  });
});

describe("gate finding adapters — detectors", () => {
  it("takes SonarCloud's own issue key as identity and preserves its severity", () => {
    const { findings } = sonarGateFindings(
      [{ key: "AY-123", rule: "java:S1192", severity: "MAJOR", type: "CODE_SMELL", component: "c", line: 4 }],
      [],
    );

    assert.equal(findings[0].findingKey, "AY-123");
    assert.equal(findings[0].category, "java:S1192");
    assert.equal(findings[0].severity, "MAJOR");
    assert.equal(findings[0].sourceKind, "detector");
    assert.equal(findings[0].sourceId, "sonarcloud");
    assertCarriesNoProse(findings);
  });

  it("keeps hotspot probability out of the issue severity scale", () => {
    // Probability and severity are different ordinal systems; merging them would
    // produce a distribution that means nothing.
    const { findings } = sonarGateFindings(
      [{ key: "i1", rule: "java:S1", severity: "MINOR" }],
      [{ key: "h1", securityCategory: "auth", vulnerabilityProbability: "HIGH" }],
    );

    const hotspot = findings.find((f) => f.findingKey === "h1");
    assert.equal(hotspot.category, "auth");
    assert.equal(hotspot.severity, "HIGH");
  });

  it("prefers the SpotBugs instance hash over a positional key", () => {
    const xml = `<BugCollection><BugInstance type="NP_NULL_ON_SOME_PATH" rank="9" instanceHash="abc123">
      <SourceLine sourcepath="com/x/Foo.java" start="12"/></BugInstance></BugCollection>`;

    const { findings } = spotbugsGateFindings(xml);

    // The hash survives reformatting that would move a line number, so the same bug
    // keeps one identity across attempts.
    assert.equal(findings[0].findingKey, "abc123");
    assert.equal(findings[0].category, "NP_NULL_ON_SOME_PATH");
    assert.equal(findings[0].severity, "9");
    assertCarriesNoProse(findings);
  });

  it("reads Vale check names as the category and keeps Vale's severity", () => {
    const { findings } = valeGateFindings({
      "docs/a.md": [{ Check: "GoogleProject.EmDashDensity", Severity: "error", Line: 3, Span: [1, 2] }],
    });

    assert.equal(findings[0].category, "GoogleProject.EmDashDensity");
    assert.equal(findings[0].severity, "error");
    assert.equal(findings[0].sourceId, "vale");
    assertCarriesNoProse(findings);
  });

  it("records no severity for policy, which does not express one", () => {
    const { findings } = policyGateFindings({
      violations: [{ code: "version-mirror-drift", message: "m", details: ["backend/build.gradle.kts"] }],
    });

    assert.equal(findings[0].category, "version-mirror-drift");
    // Every policy violation is blocking; inventing a level would be a fabricated axis.
    assert.ok(!("severity" in findings[0]));
    assertCarriesNoProse(findings);
  });

  it("keeps two violations of one policy code at different sites distinct", () => {
    const { findings } = policyGateFindings({
      violations: [
        { code: "adr-guard", details: ["a.java"] },
        { code: "adr-guard", details: ["b.java"] },
      ],
    });

    assert.equal(findings.length, 2);
  });

  it("reads CI's own failed-step export as job/step categories", () => {
    const { findings } = ciGateFindings({
      conclusion: "failure",
      failed_steps: [
        { job_name: "backend", step_name: "Run tests" },
        { job_name: "frontend", step_name: "Lint" },
      ],
    });

    assert.deepEqual(
      findings.map((f) => f.category).sort(),
      ["backend/Run tests", "frontend/Lint"],
    );
    assert.ok(findings.every((f) => f.sourceId === "ci" && f.severity === "failure"));
  });

  it("records the run conclusion when a failure produced no extractable steps", () => {
    // A startup failure or timeout still rendered a verdict. Without this the gate
    // would report `fail` with zero findings and read as unexplained.
    const { findings } = ciGateFindings({ conclusion: "timed_out", failed_steps: [] });

    assert.equal(findings.length, 1);
    assert.equal(findings[0].category, "timed_out");
  });

  it("produces no findings for a green run", () => {
    // A passing run is coverage, not a defect. Counting it would make every green
    // run look like it produced findings.
    assert.deepEqual(ciGateFindings({ conclusion: "success", failed_steps: [] }).findings, []);
  });

  it("returns an empty batch for a gate that produced nothing", () => {
    // A pass is an explicit zero-finding batch, not an absent one: the two mean
    // different things to a coverage denominator.
    assert.deepEqual(spotbugsGateFindings("").findings, []);
    assert.deepEqual(valeGateFindings({}).findings, []);
    assert.deepEqual(policyGateFindings({ violations: [] }).findings, []);
    assert.deepEqual(ciGateFindings({ conclusion: "success" }).findings, []);
  });

  it("survives malformed source output without throwing", () => {
    // A parser error must become not_evaluable at the emission site, never an
    // exception that takes down the gate it was only observing.
    assert.doesNotThrow(() => spotbugsGateFindings("<not-xml"));
    assert.doesNotThrow(() => valeGateFindings(null));
    assert.doesNotThrow(() => policyGateFindings(undefined));
    assert.doesNotThrow(() => ciGateFindings("nonsense"));
    assert.doesNotThrow(() => sonarGateFindings(null, null));
    assert.doesNotThrow(() => reviewGateFindings(null, "codex"));
  });
});

describe("gate finding adapters — child gate verdicts (issue #1355)", () => {
  it("reads the policy artifact's own duration rather than the parent command's", () => {
    // `make policy` wraps several gates. Billing its whole duration to the policy child
    // would make per-gate cost meaningless, so policy reports its own.
    const artifact = { station_id: "policy", duration_ms: 832, violations: [{ code: "adr-guard", details: ["a"] }] };

    const { findings } = policyGateFindings(artifact);

    assert.equal(artifact.duration_ms, 832);
    assert.equal(findings.length, 1);
    assert.equal(findings[0].category, "adr-guard");
  });

  it("treats a clean child gate as a pass with zero findings, not as unmeasured", () => {
    assert.deepEqual(policyGateFindings({ station_id: "policy", duration_ms: 10, violations: [] }).findings, []);
    assert.deepEqual(valeGateFindings({}).findings, []);
  });

  it("parses a real SpotBugs report shape", () => {
    const xml = [
      '<BugCollection version="4.8">',
      '<BugInstance type="EI_EXPOSE_REP" priority="2" rank="14" category="MALICIOUS_CODE" instanceHash="deadbeef">',
      '<SourceLine classname="com.x.Foo" sourcepath="com/x/Foo.java" start="88" end="88"/>',
      "</BugInstance></BugCollection>",
    ].join("\n");

    const { findings } = spotbugsGateFindings(xml);

    assert.equal(findings.length, 1);
    assert.equal(findings[0].category, "EI_EXPOSE_REP");
    assert.equal(findings[0].severity, "14");
    assert.equal(findings[0].findingKey, "deadbeef");
  });
});

describe("child gate artifact paths (issue #1355)", () => {
  it("creates the artifact directory so a gate's write cannot be silently swallowed", async () => {
    const { mkdtempSync, existsSync, rmSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { join } = await import("node:path");
    const { childGateArtifactPaths } = await import("./implement/gate-helpers.js");

    const root = mkdtempSync(join(tmpdir(), "gc-artifacts-"));
    try {
      const paths = childGateArtifactPaths(root);

      // bin/policy and the vale target both write fail-open. Without the directory their
      // writes are swallowed and the policy and vale stations are never recorded at all —
      // measurement missing in the way that is hardest to notice, because nothing fails.
      assert.ok(existsSync(paths.dir), "artifact directory should exist after resolution");
      assert.ok(paths.policy.endsWith("policy.json"));
      assert.ok(paths.vale.endsWith("vale.json"));
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
