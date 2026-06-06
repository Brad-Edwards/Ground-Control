import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  ENGINE_CAPABILITIES,
  GATE_MANIFEST_JSON_SCHEMA,
  buildBoundPhaseMarker,
  evaluateBoundPhaseMarkerFreshness,
  evaluateGateThreshold,
  evaluateRequiredStatuses,
  parseGroundControlYaml,
  runGates,
  runWatchRequiredStatuses,
  selectApplicableGates,
  synthesizeLegacyGateManifest,
  validateGateManifest,
} from "./lib.js";

async function withTempRepo(fn) {
  const dir = mkdtempSync(join(tmpdir(), "gc-gate-engine-"));
  try {
    execFileSync("git", ["init"], { cwd: dir, stdio: "ignore" });
    execFileSync("git", ["config", "user.email", "test@example.com"], { cwd: dir });
    execFileSync("git", ["config", "user.name", "Test User"], { cwd: dir });
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

function writeManifestRepo(repo, manifestYaml, lockJson = null) {
  mkdirSync(join(repo, ".gc"), { recursive: true });
  writeFileSync(join(repo, ".gc/gates.yaml"), manifestYaml);
  writeFileSync(join(repo, ".gc/workflow-lock.json"), JSON.stringify(lockJson ?? {
    schema_version: 1,
    engine: { version: "1.0.0" },
    packs: [{ id: "test-pack", version: "1.0.0" }],
  }, null, 2));
}

describe("gate manifest schema and validation", () => {
  it("exports a strict JSON Schema rooted on the ADR-062 capability vocabulary", () => {
    assert.equal(GATE_MANIFEST_JSON_SCHEMA.additionalProperties, false);
    assert.deepEqual(GATE_MANIFEST_JSON_SCHEMA.properties.gates.items.properties.capability.enum, ENGINE_CAPABILITIES);
  });

  it("rejects unknown keys, duplicate ids, unknown capabilities, path escapes, and malformed thresholds", async () => {
    await withTempRepo(async (repo) => {
      const result = validateGateManifest({
        schema_version: 1,
        extra: true,
        gates: [
          {
            id: "dup",
            capability: "unit_tests",
            cwd: ".",
            command: "echo ok",
            threshold: { metric: "score", min: "90" },
          },
          {
            id: "dup",
            capability: "made_up",
            cwd: "../outside",
            command: "echo no",
          },
        ],
      }, { repoRoot: repo });
      assert.equal(result.ok, false);
      assert.match(result.errors.join("\n"), /unknown key 'extra'/);
      assert.match(result.errors.join("\n"), /duplicated/);
      assert.match(result.errors.join("\n"), /capability must be one of/);
      assert.match(result.errors.join("\n"), /must stay inside the repository root/);
      assert.match(result.errors.join("\n"), /threshold\.min must be a number/);
    });
  });
});

describe(".ground-control.yaml workflow engine additions", () => {
  it("parses engine, manifest, packs, and gate overrides while preserving legacy commands", () => {
    const parsed = parseGroundControlYaml(`
schema_version: 1
project: ground-control
workflow:
  completion_command: make policy
  engine:
    version: "^1.0.0"
  gate_manifest: .gc/gates.yaml
  packs:
    - id: test-pack
      version: "^1.0.0"
      scope: .
      profile: default
  gate_overrides:
    test.threshold.min: 80
`);
    assert.equal(parsed.ok, true);
    assert.equal(parsed.value.workflow.completion_command, "make policy");
    assert.equal(parsed.value.workflow.engine.version, "^1.0.0");
    assert.equal(parsed.value.workflow.gate_manifest, ".gc/gates.yaml");
    assert.equal(parsed.value.workflow.packs[0].scope, ".");
    assert.equal(parsed.value.workflow.gate_overrides["test.threshold.min"], 80);
  });
});

describe("gate selection and thresholds", () => {
  it("selects gates by capability, scope, and applies_when path patterns", () => {
    const manifest = {
      gates: [
        { id: "a", capability: "unit_tests", scope: "changed", applies_when: { paths: ["src/**/*.js"] } },
        { id: "b", capability: "lint", scope: "changed", applies_when: { paths: ["docs/**"] } },
        { id: "c", capability: "policy", scope: "repo", applies_when: { paths: [] } },
      ],
    };
    const selected = selectApplicableGates(manifest, {
      changedFiles: ["src/lib/foo.js"],
      capabilities: ["unit_tests", "policy"],
    });
    assert.deepEqual(selected.map((gate) => gate.id), ["a", "c"]);
  });

  it("evaluates numeric, severity, and policy thresholds", () => {
    assert.equal(evaluateGateThreshold({ metric: "score", min: 80 }, { score: 90 }).ok, true);
    assert.equal(evaluateGateThreshold({ metric: "score", max: 0 }, { score: 1 }).ok, false);
    assert.equal(evaluateGateThreshold({ metric: "severity", severity: "medium" }, { severity: "high" }).ok, false);
    assert.equal(evaluateGateThreshold({ metric: "policy", policy: "clean" }, { policy: "clean" }).ok, true);
  });
});

describe("gate marker binding", () => {
  it("detects fresh and stale bound gates_green markers", () => {
    const marker = buildBoundPhaseMarker({
      phase: "gates_green",
      issueNumber: 1075,
      binding: {
        manifest_hash: "m1",
        diff_hash: "d1",
        pack_versions_hash: "p1",
        envelope_id: "e1",
      },
    });
    assert.equal(evaluateBoundPhaseMarkerFreshness({
      commentBodies: [marker],
      issueNumber: 1075,
      phase: "gates_green",
      expected: { manifest_hash: "m1", diff_hash: "d1", pack_versions_hash: "p1" },
    }).ok, true);
    const stale = evaluateBoundPhaseMarkerFreshness({
      commentBodies: [marker],
      issueNumber: 1075,
      phase: "gates_green",
      expected: { manifest_hash: "m2", diff_hash: "d1", pack_versions_hash: "p1" },
    });
    assert.equal(stale.ok, false);
    assert.equal(stale.error, "stale_phase_marker");
    assert.deepEqual(stale.stale_keys, ["manifest_hash"]);
  });
});

describe("legacy adapter and gc_run_gates dispatch", () => {
  it("synthesizes temporary gates from legacy commands without pack coverage", () => {
    const manifest = synthesizeLegacyGateManifest({
      completion_command: "make policy",
      test_command: "npm test",
      lint_command: "npm run lint",
      format_command: null,
    });
    assert.deepEqual(manifest.gates.map((gate) => [gate.id, gate.capability]), [
      ["legacy.policy", "policy"],
      ["legacy.unit_tests", "unit_tests"],
      ["legacy.lint", "lint"],
    ]);
    assert.deepEqual(manifest.packs, []);
  });

  it("runs applicable legacy gates through a mocked command runner and records legacy telemetry", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo, [
        "workflow:",
        "  completion_command: make policy",
        "  test_command: npm test",
      ].join("\n"));
      const commands = [];
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        capabilities: ["policy"],
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["src/a.js"], diff_hash: "diff1" },
        postMarker: false,
        commandRunner: async ({ command }) => {
          commands.push(command);
          return { exit_code: 0, stdout: "", stderr: "", timed_out: false, duration_ms: 5 };
        },
      });
      assert.equal(result.ok, true);
      assert.equal(result.legacy_mode, true);
      assert.deepEqual(commands, ["make policy"]);
      const telemetry = readFileSync(join(repo, ".gc/telemetry/gate-effectiveness-1075.jsonl"), "utf8");
      assert.match(telemetry, /"legacy_mode":true/);
    });
  });

  it("returns blocking_gate_failed when a typed threshold fails", async () => {
    await withTempRepo(async (repo) => {
      mkdirSync(join(repo, "src"), { recursive: true });
      writeConfig(repo);
      writeManifestRepo(repo, `
schema_version: 1
packs:
  - id: test-pack
    version: "1.0.0"
    scope: src
gates:
  - id: test.mutation
    capability: mutation
    pack: test-pack
    command: check mutation
    blocking: true
    scope: changed
    applies_when:
      paths: ["src/**"]
    output:
      type: json
      metrics:
        mutation_score: score
    threshold:
      metric: mutation_score
      min: 60
`);
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["src/a.js"], diff_hash: "diff1" },
        postMarker: false,
        commandRunner: async () => ({ exit_code: 0, stdout: "{\"score\": 42}", stderr: "", timed_out: false, duration_ms: 5 }),
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "blocking_gate_failed");
      assert.equal(result.gate_id, "test.mutation");
      assert.equal(result.threshold.actual, 42);
    });
  });

  it("records provider_missing and reviewer fallback distinctly", async () => {
    await withTempRepo(async (repo) => {
      mkdirSync(join(repo, "src"), { recursive: true });
      writeConfig(repo);
      writeManifestRepo(repo, `
schema_version: 1
packs:
  - id: test-pack
    version: "1.0.0"
    scope: src
gates:
  - id: test.property
    capability: property_verification
    pack: test-pack
    blocking: true
    provider_missing: reviewer_fallback
    scope: changed
    applies_when:
      paths: ["src/**"]
`);
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["src/a.js"], diff_hash: "diff1" },
        postMarker: false,
      });
      assert.equal(result.ok, true);
      assert.equal(result.status, "degraded");
      assert.equal(result.gates[0].provider_missing, true);
      assert.equal(result.gates[0].reviewer_fallback_used, true);
      const telemetry = readFileSync(join(repo, ".gc/telemetry/gate-effectiveness-1075.jsonl"), "utf8");
      assert.match(telemetry, /"provider_missing":true/);
      assert.match(telemetry, /"reviewer_fallback_used":true/);
    });
  });
});

describe("required remote statuses", () => {
  it("evaluates arbitrary required status names without provider-specific assumptions", () => {
    const result = evaluateRequiredStatuses({
      requiredStatuses: ["ci", "quality"],
      statusSnapshot: [
        { name: "ci", conclusion: "success" },
        { name: "quality", conclusion: "failure" },
      ],
    });
    assert.equal(result.ok, false);
    assert.equal(result.state, "failed");
    assert.equal(result.failed[0].name, "quality");
  });

  it("writes a remote_gates_green marker when supplied statuses pass", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      const markerCalls = [];
      const result = await runWatchRequiredStatuses({
        repoPath: repo,
        issueNumber: 1075,
        prNumber: 12,
        requiredStatuses: ["ci"],
        statusSnapshot: [{ name: "ci", conclusion: "success", id: "check-1" }],
        headSha: "abc123",
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["src/a.js"], diff_hash: "diff1" },
        markerPoster: async (payload) => {
          markerCalls.push(payload);
          return { html_url: "https://example.test/comment/1", id: 1 };
        },
      });
      assert.equal(result.ok, true);
      assert.equal(result.status, "passed");
      assert.equal(markerCalls[0].phase, "remote_gates_green");
      assert.equal(markerCalls[0].binding.head_sha, "abc123");
    });
  });
});
