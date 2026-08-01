// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  TEST_QUALITY_REVIEW_HARD_CAP,
  TEST_QUALITY_REVIEW_MARKER_PREFIX,
  buildCodexReviewPrePushCycleMarker,
  buildTestQualityReviewCycleMarker,
  buildTestQualityReviewPrompt,
  evaluateTestQualityReviewCycleCap,
  parseCodexReviewPrePushCycleMarkers,
  parseTestQualityReviewCycleMarkers,
  runResolveWorkflowRoute,
} from "./lib.js";

describe("runResolveWorkflowRoute", () => {
  it("reads .ground-control.yaml and resolves configured stage routing", async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-routing-test-"));
    try {
      execFileSync("git", ["init"], { cwd: dir, stdio: "ignore" });
      writeFileSync(join(dir, ".ground-control.yaml"), [
        "schema_version: 1",
        "project: gc",
        "routing:",
        "  enabled: true",
        "  stages:",
        "    implementation:",
        "      tier: medium",
        "      model: claude-sonnet-4-6",
        "",
      ].join("\n"));
      const r = await runResolveWorkflowRoute({ repoPath: dir, stage: "implementation" });
      assert.equal(r.ok, true);
      assert.equal(r.enabled, true);
      assert.equal(r.model, "claude-sonnet-4-6");
      assert.equal(r.source, "config");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// gc_test_quality_review cycle cap (issue #884 follow-up)
// ---------------------------------------------------------------------------

describe("parseTestQualityReviewCycleMarkers", () => {
  it("returns 0 when no comments contain markers", () => {
    assert.equal(parseTestQualityReviewCycleMarkers(["a", "b"], 884), 0);
  });

  it("counts markers for the matching issue regardless of branch", () => {
    const bodies = [
      '<!-- gc:test-quality-review-cycle issue="884" branch="884-foo" cycle="1" -->',
      "unrelated",
      '<!-- gc:test-quality-review-cycle issue="884" branch="884-bar" cycle="2" -->',
    ];
    assert.equal(parseTestQualityReviewCycleMarkers(bodies, 884), 2);
  });

  it("ignores markers for other issues", () => {
    const bodies = [
      '<!-- gc:test-quality-review-cycle issue="100" branch="884-x" cycle="1" -->',
      '<!-- gc:test-quality-review-cycle issue="884" branch="884-x" cycle="1" -->',
    ];
    assert.equal(parseTestQualityReviewCycleMarkers(bodies, 884), 1);
  });

  it("does not cross-count codex pre-push markers (different family)", () => {
    const bodies = [
      '<!-- gc:codex-prepush-cycle issue="884" branch="884-x" cycle="1" -->',
    ];
    assert.equal(parseTestQualityReviewCycleMarkers(bodies, 884), 0);
  });

  it("does not cross-count decision-record markers (different family)", () => {
    const bodies = [
      '<!-- gc:decision-record reviewer="test-quality" cycle="1" issue="884" -->',
    ];
    assert.equal(parseTestQualityReviewCycleMarkers(bodies, 884), 0);
  });

  it("ignores malformed markers", () => {
    const bodies = [
      "<!-- gc:test-quality-review-cycle -->",
      '<!-- gc:test-quality-review-cycle issue="884" branch="884-x" -->',
      '<!-- gc:test-quality-review-cycle issue="884" cycle="1" -->',
      '<!-- gc:test-quality-review-cycle branch="884-x" cycle="1" -->',
      "<!-- gc:test-quality-review-cycle issue=884 branch=884-x cycle=1 -->",
    ];
    assert.equal(parseTestQualityReviewCycleMarkers(bodies, 884), 0);
  });

  it("tolerates non-string entries and non-array input", () => {
    assert.equal(parseTestQualityReviewCycleMarkers(["a", 42, null], 1), 0);
    assert.equal(parseTestQualityReviewCycleMarkers(null, 1), 0);
    assert.equal(parseTestQualityReviewCycleMarkers("not array", 1), 0);
  });
});

describe("evaluateTestQualityReviewCycleCap", () => {
  // Default (no hardCap) — cap dropped from 3 → 1 by issue #906. Cycle 1 is
  // therefore the only allowed in-cap cycle and its next_action is the
  // "last in-cap cycle" disposition.
  it("allows cycle 1 under the cap-1 default with the summarize-and-escalate disposition", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 0,
      issueNumber: 884,
      branchName: "884-x",
    });
    assert.equal(r.ok, true);
    assert.equal(r.nextCycle, 1);
    assert.equal(r.cap, TEST_QUALITY_REVIEW_HARD_CAP);
    assert.equal(r.cap, 1);
    assert.equal(r.next_action, "fix_findings_then_summarize_and_escalate");
  });

  it("refuses cycle 2 under the cap-1 default with test_quality_review_cap_reached", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 1,
      issueNumber: 884,
      branchName: "884-x",
    });
    assert.equal(r.ok, false);
    assert.equal(r.error, "test_quality_review_cap_reached");
    assert.equal(r.cap, 1);
    assert.equal(r.next_action, "post_summary_and_escalate_to_user");
  });

  // Explicit cap-3 — historical default (issue #884 follow-up). Repos restore
  // it by setting `workflow.test_quality_review.pre_push_cap: 3`.
  it("allows cycle 1 under explicit cap-3 with fix_findings_and_reinvoke next_action", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 0,
      issueNumber: 884,
      branchName: "884-x",
      hardCap: 3,
    });
    assert.equal(r.ok, true);
    assert.equal(r.nextCycle, 1);
    assert.equal(r.cap, 3);
    assert.equal(r.next_action, "fix_findings_and_reinvoke");
  });

  it("returns escalate next_action for cycle 3 (last in-cap) under explicit cap-3", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 2,
      issueNumber: 884,
      branchName: "884-x",
      hardCap: 3,
    });
    assert.equal(r.ok, true);
    assert.equal(r.nextCycle, 3);
    assert.equal(r.next_action, "fix_findings_then_summarize_and_escalate");
  });

  it("refuses cycle 4 under explicit cap-3 without override", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 3,
      issueNumber: 884,
      branchName: "884-x",
      hardCap: 3,
    });
    assert.equal(r.ok, false);
    assert.equal(r.error, "test_quality_review_cap_reached");
    assert.equal(r.prior_cycles, 3);
    assert.equal(r.cap, 3);
    assert.equal(r.next_action, "post_summary_and_escalate_to_user");
  });

  it("requires override_reason when overrideCap=true", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 3,
      issueNumber: 884,
      branchName: "884-x",
      overrideCap: true,
      overrideReason: "",
    });
    assert.equal(r.ok, false);
    assert.equal(r.error, "test_quality_review_override_missing_reason");
  });

  it("allows cycle 4 with overrideCap=true and a non-empty reason", () => {
    const r = evaluateTestQualityReviewCycleCap({
      priorCount: 3,
      issueNumber: 884,
      branchName: "884-x",
      overrideCap: true,
      overrideReason: "user: yes run cycle 4",
    });
    assert.equal(r.ok, true);
    assert.equal(r.nextCycle, 4);
    assert.equal(r.override, true);
    assert.equal(r.override_reason, "user: yes run cycle 4");
  });

  it("throws on invalid priorCount", () => {
    assert.throws(() =>
      evaluateTestQualityReviewCycleCap({
        priorCount: -1,
        issueNumber: 884,
        branchName: "884-x",
      }),
    );
    assert.throws(() =>
      evaluateTestQualityReviewCycleCap({
        priorCount: "two",
        issueNumber: 884,
        branchName: "884-x",
      }),
    );
  });
});

describe("buildTestQualityReviewCycleMarker", () => {
  it("round-trips through parseTestQualityReviewCycleMarkers", () => {
    const m = buildTestQualityReviewCycleMarker({
      issueNumber: 884,
      branchName: "884-foo",
      cycleNumber: 1,
    });
    assert.ok(m.startsWith(TEST_QUALITY_REVIEW_MARKER_PREFIX));
    assert.equal(parseTestQualityReviewCycleMarkers([m], 884), 1);
  });

  it("renders human-readable body with cycle / cap / issue / branch", () => {
    // Pass explicit hardCap so this test documents the marker's "cycle N
    // of M" shape independent of the module default (which dropped to 1 in
    // issue #906).
    const m = buildTestQualityReviewCycleMarker({
      issueNumber: 884,
      branchName: "884-foo",
      cycleNumber: 2,
      hardCap: 3,
    });
    assert.match(m, /cycle 2 of 3/);
    assert.match(m, /issue #884/);
    assert.match(m, /884-foo/);
    assert.match(m, /#884/);
  });

  it("does not cross-count with codex pre-push markers", () => {
    const tq = buildTestQualityReviewCycleMarker({
      issueNumber: 884,
      branchName: "884-x",
      cycleNumber: 1,
    });
    const codex = buildCodexReviewPrePushCycleMarker({
      issueNumber: 884,
      branchName: "884-x",
      cycleNumber: 1,
    });
    assert.equal(parseTestQualityReviewCycleMarkers([tq, codex], 884), 1);
    assert.equal(parseCodexReviewPrePushCycleMarkers([tq, codex], 884), 1);
  });

  it("renders an override marker with reason", () => {
    const reason = "user authorized cycle 4 to verify cycle-3 fixes";
    const m = buildTestQualityReviewCycleMarker({
      issueNumber: 884,
      branchName: "884-x",
      cycleNumber: 4,
      override: true,
      overrideReason: reason,
    });
    assert.match(m, /override="true"/);
    assert.match(m, /USER-AUTHORIZED OVERRIDE/);
    assert.match(m, new RegExp(reason));
    assert.equal(parseTestQualityReviewCycleMarkers([m], 884), 1);
  });

  it("escapes quotes in override reason", () => {
    const tricky = 'user said "yes go ahead"';
    const m = buildTestQualityReviewCycleMarker({
      issueNumber: 1,
      branchName: "1-x",
      cycleNumber: 4,
      override: true,
      overrideReason: tricky,
    });
    assert.match(m, /reason="user said \\"yes go ahead\\""/);
    assert.equal(parseTestQualityReviewCycleMarkers([m], 1), 1);
  });
});

describe("buildTestQualityReviewPrompt", () => {
  it("includes the base branch and every changed test file in the listing", () => {
    const prompt = buildTestQualityReviewPrompt({
      baseBranch: "dev",
      changedTestFiles: ["tools/tests/test_policy.py", "backend/src/test/Foo.java"],
    });
    assert.match(prompt, /base branch `dev`/);
    assert.match(prompt, /- tools\/tests\/test_policy\.py/);
    assert.match(prompt, /- backend\/src\/test\/Foo\.java/);
  });

  it("embeds the canonical rubric — critical + warning categories", () => {
    const prompt = buildTestQualityReviewPrompt({
      baseBranch: "dev",
      changedTestFiles: ["a_test.py"],
    });
    assert.match(prompt, /Assertion-free tests/);
    assert.match(prompt, /Mock-only assertions/);
    assert.match(prompt, /Integration masquerading as unit/);
    assert.match(prompt, /Tests that can't detect regressions/);
    assert.match(prompt, /Missing parameterization/);
    assert.match(prompt, /No negative test cases/);
  });

  it("flags security-enforcing behavior tested only by existence", () => {
    const prompt = buildTestQualityReviewPrompt({
      baseBranch: "dev",
      changedTestFiles: ["AuthorizationFilterTest.java"],
    });
    assert.match(prompt, /Security-enforcing behavior tested only by existence/);
    // The rubric must direct the reviewer at the protected behavior, not the bookkeeping.
    assert.match(prompt, /removed, bypassed, or materially weakened/);
    assert.match(prompt, /if I removed the enforcement, would this test still pass/);
    // ADR-089 retired the GRC screening surface: the rule must key off the diff, never
    // off a screening record, a control row/link/status, or a GC-GRC disposition route.
    assert.doesNotMatch(prompt, /GC-GRC-/);
    assert.doesNotMatch(prompt, /ControlTest|ControlLink/);
    assert.doesNotMatch(prompt, /IMPLEMENTED\/OPERATIONAL/);
  });

  it("instructs verdict-envelope output (#931)", () => {
    const prompt = buildTestQualityReviewPrompt({
      baseBranch: "main",
      changedTestFiles: ["x_test.py"],
    });
    // The verdict envelope is the contract; severity/location/problem/fix
    // are the per-finding fields inside `blocking`.
    assert.match(prompt, /===REVIEW===/);
    assert.match(prompt, /verdict/);
    assert.match(prompt, /architectural_read/);
    assert.match(prompt, /blocking/);
    assert.match(prompt, /severity/);
    assert.match(prompt, /location/);
    assert.match(prompt, /classification/);
    assert.match(prompt, /sweep_evidence/);
    assert.match(prompt, /test-visible implementation special-casing/);
    assert.match(prompt, /fixture or oracle edits/);
  });

  it("throws on empty changedTestFiles", () => {
    assert.throws(() => buildTestQualityReviewPrompt({ baseBranch: "dev", changedTestFiles: [] }));
  });

  it("throws on missing baseBranch", () => {
    assert.throws(() =>
      buildTestQualityReviewPrompt({ baseBranch: "", changedTestFiles: ["a.py"] }),
    );
  });

  it("throws on non-string file entries", () => {
    assert.throws(() =>
      buildTestQualityReviewPrompt({
        baseBranch: "dev",
        changedTestFiles: ["a.py", 42, null],
      }),
    );
  });
});
