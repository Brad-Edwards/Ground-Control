#!/usr/bin/env node
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  buildBoundPhaseMarker,
  buildPhaseMarker,
  dispatchReviewConvergence,
  evaluateBoundPhaseMarkerFreshness,
  evaluateRemoteQualitySubstance,
  evaluateRequiredStatuses,
  runGates,
  runPostInterfaceContract,
  synthesizeLegacyGateManifest,
  validateWorkflowLock,
} from "../../mcp/ground-control/lib.js";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const defaultSuitePath = join(repoRoot, "workflow/evals/engine-behavior/v1/scenarios.json");

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2).replace(/-/g, "_");
    const next = argv[i + 1];
    if (next == null || next.startsWith("--")) out[key] = "true";
    else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

async function withTempRepo(fn) {
  const dir = mkdtempSync(join(tmpdir(), "gc-engine-eval-"));
  try {
    execFileSync("git", ["init"], { cwd: dir, stdio: "ignore" });
    execFileSync("git", ["config", "user.email", "eval@example.com"], { cwd: dir, stdio: "ignore" });
    execFileSync("git", ["config", "user.name", "Workflow Engine Eval"], { cwd: dir, stdio: "ignore" });
    return await fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function writeConfig(repo, workflowYaml = "") {
  writeFileSync(join(repo, ".ground-control.yaml"), [
    "schema_version: 1",
    "project: ground-control",
    "github_repo: example/repo",
    workflowYaml,
    "",
  ].filter(Boolean).join("\n"));
}

function releaseLock(packEntries) {
  return {
    schema_version: 1,
    engine: {
      version: "1.0.0",
      checksum: `sha256:${"a".repeat(64)}`,
      source_url: "workflow/releases/gc-engine-1.0.0.tgz",
      compatible: ">=1.0.0 <2.0.0",
      signer: "TODO: release signer",
      trust_policy: "checksum-only-development",
      installed_at: "2026-06-06T00:00:00.000Z",
    },
    packs: packEntries.map((entry, index) => ({
      id: entry.id,
      version: entry.version,
      checksum: `sha256:${String(index + 1).repeat(64)}`,
      source_url: `workflow/releases/gc-gate-pack-${entry.id}-${entry.version}.tgz`,
      compatible_engine: ">=1.0.0 <2.0.0",
      signer: "TODO: release signer",
      trust_policy: "checksum-only-development",
      installed_at: "2026-06-06T00:00:00.000Z",
    })),
  };
}

function writeManifestRepo(repo, manifestYaml, packs) {
  mkdirSync(join(repo, ".gc"), { recursive: true });
  writeFileSync(join(repo, ".gc/gates.yaml"), manifestYaml);
  writeFileSync(join(repo, ".gc/workflow-lock.json"), `${JSON.stringify(releaseLock(packs), null, 2)}\n`);
}

function oneFinding() {
  return {
    id: "F1",
    severity: "Major",
    title: "Regression fixture",
    location: "mcp/ground-control/lib.js:1",
    evidence: "Deterministic eval fixture.",
    classification: "one-off",
  };
}

function remoteQuality(overrides = {}) {
  return {
    provider: "sonarcloud",
    ok: true,
    quality_gate: "OK",
    issues: {
      new: { total: 0, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 } },
      overall: { total: 0, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 } },
    },
    ratings: {
      reliability: "A",
      security: "A",
      maintainability: "A",
      new_reliability: "A",
      new_security: "A",
      new_maintainability: "A",
    },
    security_hotspots: {
      new: { to_review: 0, reviewed: true },
      overall: { to_review: 0, reviewed: true },
    },
    coverage: { overall: 90, new: 90 },
    duplications: { overall: 0, new: 0 },
    ...overrides,
  };
}

const scenarioChecks = {
  "cycle-1-no-collapse": async () => {
    const result = dispatchReviewConvergence({
      currentCycle: 1,
      cap: 1,
      lensEnvelopes: [{ verdict: "ship-with-fixes", findings: [oneFinding()], blocking: [oneFinding()] }],
    });
    assert.equal(result.cap, 2);
    assert.equal(result.next_action, "fix_findings_and_reinvoke");
    assert.equal(result.early_stop_allowed, false);
  },
  "clean-review-advances": async () => {
    const result = dispatchReviewConvergence({ lensEnvelopes: [{ verdict: "ship", findings: [], blocking: [] }] });
    assert.equal(result.next_action, "advance_to_next_phase");
    assert.equal(result.clean, true);
  },
  "cap-reached-escalates": async () => {
    const result = dispatchReviewConvergence({
      currentCycle: 2,
      cap: 2,
      lensEnvelopes: [{ verdict: "ship-with-fixes", findings: [oneFinding()], blocking: [oneFinding()] }],
    });
    assert.equal(result.next_action, "post_structured_decision_aid_and_escalate");
    assert.equal(result.early_stop_allowed, false);
    assert.ok(result.decision_aid);
  },
  "provider-missing-reviewer-fallback-degraded": async () => {
    const result = dispatchReviewConvergence({
      gateResults: [{
        gate_id: "missing.mutation",
        capability: "mutation",
        blocking: true,
        provider_missing: true,
        reviewer_fallback_used: true,
        ok: true,
      }],
      lensEnvelopes: [{ verdict: "ship", findings: [], blocking: [] }],
    });
    assert.equal(result.degraded, true);
    assert.equal(result.gate_summary.reviewer_fallback_used, 1);
    assert.equal(result.next_action, "advance_to_next_phase");
  },
  "provider-missing-blocking-refuses-without-fallback": async () => {
    const result = dispatchReviewConvergence({
      gateResults: [{
        gate_id: "missing.mutation",
        capability: "mutation",
        blocking: true,
        provider_missing: true,
        reviewer_fallback_used: false,
        ok: false,
      }],
      lensEnvelopes: [{ verdict: "ship", findings: [], blocking: [] }],
    });
    assert.equal(result.clean, false);
    assert.ok(result.exit_gate_failures.includes("blocking_gates_satisfied"));
  },
  "completion-gate-refuses-l1-without-contract": async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      writeManifestRepo(repo, `
schema_version: 1
packs:
  - id: jvm-gradle
    version: "1.0.0"
    scope: .
gates: []
`, [{ id: "jvm-gradle", version: "1.0.0" }]);
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: {
          base_ref: "main",
          head_ref: "HEAD",
          changed_files: ["src/main/java/example/FooController.java"],
          diff_hash: "diff1",
          file_diffs: {
            "src/main/java/example/FooController.java": [
              "+class FooController {",
              "+  @PreAuthorize(\"hasRole('ADMIN')\")",
              "+  void update(FooRequest request) { validate(request); }",
              "+}",
            ].join("\n"),
          },
        },
        postMarker: false,
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "assurance_artifacts_missing");
    });
  },
  "context-loaded-gates-contract": async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      const result = await runPostInterfaceContract({
        repoPath: repo,
        issueNumber: 1075,
        contractBody: "Interface: eval fixture.",
        phaseCommentBodies: [buildPhaseMarker({ phase: "preflight", issueNumber: 1075 })],
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["README.md"], diff_hash: "diff1" },
        markerPoster: async () => ({ html_url: "https://example.test/comment", id: 1 }),
      });
      assert.equal(result.ok, false);
      assert.deepEqual(result.missing, ["context_loaded"]);
    });
  },
  "traceability-live-diff-staleness": async () => {
    const marker = buildBoundPhaseMarker({
      phase: "traceability_reconciled",
      issueNumber: 1075,
      binding: { diff_hash: "old-diff" },
    });
    const result = evaluateBoundPhaseMarkerFreshness({
      commentBodies: [marker],
      issueNumber: 1075,
      phase: "traceability_reconciled",
      expected: { diff_hash: "new-diff" },
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "stale_phase_marker");
  },
  "remote-gates-green-refuses-open-issues": async () => {
    const statuses = evaluateRequiredStatuses({
      requiredStatuses: ["ci"],
      statusSnapshot: [{ name: "ci", conclusion: "success" }],
    });
    assert.equal(statuses.ok, true);
    const substance = evaluateRemoteQualitySubstance({
      providerResults: [remoteQuality({
        issues: {
          new: { total: 0, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 } },
          overall: { total: 1, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 1, MINOR: 0, INFO: 0 } },
        },
      })],
      policy: { tier: "zero_overall_issues" },
    });
    assert.equal(substance.ok, false);
    assert.ok(substance.failures.some((failure) => failure.reason === "overall_issues_not_zero"));
  },
  "required-status-missing-is-pending": async () => {
    const result = evaluateRequiredStatuses({
      requiredStatuses: ["ci", "security"],
      statusSnapshot: [{ name: "ci", conclusion: "success" }],
    });
    assert.equal(result.ok, false);
    assert.equal(result.state, "pending");
    assert.deepEqual(result.missing, ["security"]);
  },
  "legacy-config-compat-all-commands": async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo, [
        "workflow:",
        "  completion_command: make policy",
        "  test_command: npm test",
        "  lint_command: npm run lint",
      ].join("\n"));
      const commands = [];
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["README.md"], diff_hash: "diff1" },
        postMarker: false,
        commandRunner: async ({ command }) => {
          commands.push(command);
          return { exit_code: 0, stdout: "", stderr: "", timed_out: false, duration_ms: 1 };
        },
      });
      assert.equal(result.ok, true);
      assert.equal(result.legacy_mode, true);
      assert.deepEqual(commands, ["make policy", "npm test", "npm run lint"]);
    });
  },
  "legacy-no-pack-installed-no-coverage-claim": async () => {
    const manifest = synthesizeLegacyGateManifest({
      completion_command: "make policy",
      test_command: "npm test",
      lint_command: "npm run lint",
    });
    assert.equal(manifest.legacy_mode, true);
    assert.deepEqual(manifest.packs, []);
    assert.deepEqual(manifest.gates.map((gate) => gate.pack), [null, null, null]);
  },
  "no-manifest-no-legacy-degrades": async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["README.md"], diff_hash: "diff1" },
        postMarker: false,
      });
      assert.equal(result.ok, true);
      assert.equal(result.status, "degraded");
      assert.equal(result.error, "provider_missing");
    });
  },
  "workflow-lock-refuses-missing-checksums": async () => {
    const result = validateWorkflowLock({
      schema_version: 1,
      engine: { version: "1.0.0" },
      packs: [{ id: "docs-generic", version: "1.0.0" }],
    }, { manifest: { packs: [{ id: "docs-generic", version: "1.0.0" }] } });
    assert.equal(result.ok, false);
    assert.match(result.errors.join("\n"), /checksum/);
  },
  "workflow-lock-accepts-release-shape": async () => {
    const result = validateWorkflowLock(releaseLock([{ id: "docs-generic", version: "1.0.0" }]), {
      manifest: { packs: [{ id: "docs-generic", version: "1.0.0" }] },
    });
    assert.equal(result.ok, true, JSON.stringify(result));
  },
};

export async function runWorkflowEngineEvalSuite({ suitePath = defaultSuitePath } = {}) {
  const suite = JSON.parse(readFileSync(suitePath, "utf8"));
  const results = [];
  for (const scenario of suite.scenarios) {
    const started = Date.now();
    try {
      const check = scenarioChecks[scenario.id];
      if (typeof check !== "function") throw new Error(`No deterministic check registered for ${scenario.id}`);
      await check();
      results.push({ id: scenario.id, ok: true, duration_ms: Date.now() - started });
    } catch (error) {
      results.push({ id: scenario.id, ok: false, duration_ms: Date.now() - started, error: error.message });
    }
  }
  return {
    ok: results.every((result) => result.ok),
    suite: suite.suite,
    version: suite.version,
    total: results.length,
    passed: results.filter((result) => result.ok).length,
    failed: results.filter((result) => !result.ok).length,
    results,
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = parseArgs(process.argv.slice(2));
  const result = await runWorkflowEngineEvalSuite({ suitePath: resolve(args.suite ?? defaultSuitePath) });
  if (args.json === "true") {
    console.log(JSON.stringify(result, null, 2));
  } else {
    for (const item of result.results) {
      console.log(`${item.ok ? "PASS" : "FAIL"} ${item.id}${item.error ? ` - ${item.error}` : ""}`);
    }
    console.log(`${result.passed}/${result.total} workflow engine evals passed`);
  }
  if (result.ok !== true) process.exitCode = 1;
}
