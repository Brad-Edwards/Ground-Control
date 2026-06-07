import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  ENGINE_CAPABILITIES,
  GATE_CATALOG_DEFAULT_PATH,
  GATE_MANIFEST_JSON_SCHEMA,
  buildBoundPhaseMarker,
  buildPhaseMarker,
  buildTestQualityReviewPrompt,
  classifyFixRisk,
  classifyAssuranceSurfacesFromDiff,
  dispatchReviewConvergence,
  computeGatePackDirectoryChecksum,
  computeWorkflowArtifactChecksum,
  evaluateBoundPhaseMarkerFreshness,
  evaluateGateThreshold,
  evaluateRemoteQualitySubstance,
  evaluateRequiredStatuses,
  installWorkflowAssets,
  parseGroundControlYaml,
  runGateTelemetrySummary,
  runGetImplementationContext,
  runReconcileTraceability,
  runAssertImplGreen,
  runAssertTestRed,
  runGates,
  runPostImplementationPlan,
  runPostInterfaceContract,
  runWatchRequiredStatuses,
  selectApplicableGates,
  synthesizeLegacyGateManifest,
  validateGateManifest,
  validateWorkflowLock,
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

function testWorkflowLock(packEntries = [{ id: "test-pack", version: "1.0.0" }]) {
  const installedAt = "2026-06-06T00:00:00.000Z";
  return {
    schema_version: 1,
    engine: {
      version: "1.0.0",
      checksum: `sha256:${"1".repeat(64)}`,
      source_url: "workflow/releases/gc-engine-1.0.0.tgz",
      compatible: ">=1.0.0 <2.0.0",
      signer: "TODO: release signer",
      trust_policy: "checksum-only-development",
      installed_at: installedAt,
    },
    packs: packEntries.map((entry, index) => ({
      id: entry.id,
      version: entry.version,
      checksum: `sha256:${String(index + 2).repeat(64)}`,
      source_url: `workflow/releases/gc-gate-pack-${entry.id}-${entry.version}.tgz`,
      compatible_engine: ">=1.0.0 <2.0.0",
      signer: "TODO: release signer",
      trust_policy: "checksum-only-development",
      installed_at: installedAt,
    })),
  };
}

function writeManifestRepo(repo, manifestYaml, lockJson = null) {
  mkdirSync(join(repo, ".gc"), { recursive: true });
  writeFileSync(join(repo, ".gc/gates.yaml"), manifestYaml);
  const base = lockJson ?? testWorkflowLock();
  const hydrated = testWorkflowLock(base.packs ?? [{ id: "test-pack", version: "1.0.0" }]);
  writeFileSync(join(repo, ".gc/workflow-lock.json"), JSON.stringify({
    ...hydrated,
    ...base,
    engine: { ...hydrated.engine, ...(base.engine ?? {}) },
    packs: (base.packs ?? hydrated.packs).map((pack, i) => ({ ...hydrated.packs[i], ...pack })),
  }, null, 2));
}

function withMockFetch(routesByUrl, fn) {
  const originalFetch = globalThis.fetch;
  const originalBase = process.env.GC_BASE_URL;
  process.env.GC_BASE_URL = "http://test.invalid";
  globalThis.fetch = async (url) => {
    const value = url.toString();
    for (const [pattern, handler] of routesByUrl) {
      if (value.includes(pattern)) {
        const response = await handler(value);
        return {
          status: response.status ?? 200,
          ok: (response.status ?? 200) < 400,
          text: async () => JSON.stringify(response.body ?? null),
          json: async () => response.body ?? null,
        };
      }
    }
    return {
      status: 404,
      ok: false,
      text: async () => JSON.stringify({ error: { message: `no route for ${value}` } }),
      json: async () => ({ error: { message: `no route for ${value}` } }),
    };
  };
  return Promise.resolve()
    .then(fn)
    .finally(() => {
      globalThis.fetch = originalFetch;
      if (originalBase === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = originalBase;
    });
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

describe("gate pack catalog and installer", () => {
  it("catalog checksums match the materialized workflow pack sources", () => {
    const catalog = JSON.parse(readFileSync(GATE_CATALOG_DEFAULT_PATH, "utf8"));
    const repoRoot = join(dirname(GATE_CATALOG_DEFAULT_PATH), "..");
    assert.match(catalog.engine.artifact, /^workflow\/releases\/gc-engine-1\.0\.0\.tgz$/);
    assert.equal(
      computeWorkflowArtifactChecksum(join(repoRoot, catalog.engine.artifact)),
      catalog.engine.sha256,
      "engine release artifact checksum drifted",
    );
    assert.deepEqual(catalog.packs.map((entry) => entry.id), [
      "rust-cargo",
      "python",
      "jvm-gradle",
      "jvm-maven",
      "node-ts",
      "cpp-cmake",
      "docs-generic",
    ]);
    for (const entry of catalog.packs) {
      assert.equal(
        computeWorkflowArtifactChecksum(join(repoRoot, entry.artifact)),
        entry.sha256,
        `${entry.id} release artifact checksum drifted`,
      );
      assert.equal(
        computeGatePackDirectoryChecksum(join(repoRoot, entry.source_url)),
        entry.source_sha256,
        `${entry.id} source checksum drifted`,
      );
    }
  });

  it("installs a cataloged pack by checksum, vendors it, and writes manifest/config/lock surfaces", async () => {
    await withTempRepo(async (repo) => {
      writeFileSync(join(repo, "README.md"), "# Fixture\n\nDocs are present.\n");
      const result = await installWorkflowAssets({
        repoPath: repo,
        packId: "docs-generic",
        versionConstraint: "^1.0.0",
        scope: ".",
        profile: "docs",
        installDependencies: false,
        runSelftest: false,
      });
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.pack.version, "1.0.0");
      assert.equal(result.gates_written, ENGINE_CAPABILITIES.length);
      assert.equal(result.selftest.status, "skipped");
      assert.match(result.engine_vendor_path, /^\.gc\/vendor\/ground-control\/engine\/1\.0\.0/);
      assert.match(result.vendor_path, /^\.gc\/vendor\/ground-control\/packs\/docs-generic\/1\.0\.0/);

      const parsedConfig = parseGroundControlYaml(readFileSync(join(repo, ".ground-control.yaml"), "utf8"));
      assert.equal(parsedConfig.ok, true);
      assert.equal(parsedConfig.value.workflow.engine.version, "^1.0.0");
      assert.equal(parsedConfig.value.workflow.gate_manifest, ".gc/gates.yaml");
      assert.equal(parsedConfig.value.workflow.packs[0].id, "docs-generic");
      assert.equal(parsedConfig.value.workflow.packs[0].version, "^1.0.0");

      const manifest = readFileSync(join(repo, ".gc/gates.yaml"), "utf8");
      assert.match(manifest, /docs-generic\.root\.docs_policy/);
      assert.match(manifest, /provider_missing: not_applicable/);
      const lock = JSON.parse(readFileSync(join(repo, ".gc/workflow-lock.json"), "utf8"));
      assert.equal(lock.engine.checksum.startsWith("sha256:"), true);
      assert.match(lock.engine.source_url, /^workflow\/releases\/gc-engine-1\.0\.0\.tgz$/);
      assert.equal(lock.packs[0].checksum.startsWith("sha256:"), true);
      assert.match(lock.packs[0].source_url, /^workflow\/releases\/gc-gate-pack-docs-generic-1\.0\.0\.tgz$/);
      assert.equal(typeof lock.packs[0].installed_at, "string");

      const gateResult = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["README.md"], diff_hash: "diff1" },
        postMarker: false,
        capabilities: ["docs_policy"],
      });
      assert.equal(gateResult.ok, true);
      assert.equal(gateResult.status, "passed");
    });
  });

  it("refuses to install a pack when the catalog checksum does not match", async () => {
    await withTempRepo(async (repo) => {
      const catalog = JSON.parse(readFileSync(GATE_CATALOG_DEFAULT_PATH, "utf8"));
      catalog.packs = catalog.packs.map((entry) => entry.id === "docs-generic" ? { ...entry, sha256: "0".repeat(64) } : entry);
      const catalogPath = join(repo, "bad-gate-catalog.json");
      writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`);
      const result = await installWorkflowAssets({
        repoPath: repo,
        packId: "docs-generic",
        versionConstraint: "1.0.0",
        catalogPath,
        installDependencies: false,
        runSelftest: false,
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "gate_pack_checksum_mismatch");
    });
  });

  it("upgrades a cataloged pack to the highest matching semver and rewrites the lock", async () => {
    await withTempRepo(async (repo) => {
      writeFileSync(join(repo, "README.md"), "# Fixture\n\nDocs are present.\n");
      const initial = await installWorkflowAssets({
        repoPath: repo,
        packId: "docs-generic",
        versionConstraint: "1.0.0",
        scope: ".",
        installDependencies: false,
        runSelftest: false,
      });
      assert.equal(initial.ok, true, JSON.stringify(initial));

      const catalog = JSON.parse(readFileSync(GATE_CATALOG_DEFAULT_PATH, "utf8"));
      const existing = catalog.packs.find((entry) => entry.id === "docs-generic");
      catalog.packs.push({ ...existing, version: "1.0.1" });
      const catalogPath = join(repo, "upgrade-gate-catalog.json");
      writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`);

      const upgraded = await installWorkflowAssets({
        repoPath: repo,
        packId: "docs-generic",
        versionConstraint: "^1.0.0",
        scope: ".",
        catalogPath,
        installDependencies: false,
        runSelftest: false,
        mode: "upgrade",
      });
      assert.equal(upgraded.ok, true, JSON.stringify(upgraded));
      assert.equal(upgraded.operation, "upgrade");
      assert.equal(upgraded.pack.version, "1.0.1");
      assert.match(upgraded.vendor_path, /docs-generic\/1\.0\.1$/);

      const lock = JSON.parse(readFileSync(join(repo, ".gc/workflow-lock.json"), "utf8"));
      assert.equal(lock.packs.find((entry) => entry.id === "docs-generic").version, "1.0.1");
      assert.equal(lock.packs.find((entry) => entry.id === "docs-generic").checksum.startsWith("sha256:"), true);
      const parsedConfig = parseGroundControlYaml(readFileSync(join(repo, ".ground-control.yaml"), "utf8"));
      assert.equal(parsedConfig.value.workflow.packs[0].version, "^1.0.0");
    });
  });

  it("validates the release lockfile shape, including required checksums", () => {
    const manifest = { packs: [{ id: "docs-generic", version: "1.0.0", scope: "." }] };
    const invalid = validateWorkflowLock({
      schema_version: 1,
      engine: { version: "1.0.0" },
      packs: [{ id: "docs-generic", version: "1.0.0" }],
    }, { manifest });
    assert.equal(invalid.ok, false);
    assert.match(invalid.errors.join("\n"), /engine\.checksum/);
    assert.match(invalid.errors.join("\n"), /packs\[0\]\.checksum/);

    const valid = validateWorkflowLock(testWorkflowLock([{ id: "docs-generic", version: "1.0.0" }]), { manifest });
    assert.equal(valid.ok, true, JSON.stringify(valid));
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

function classifierDiff(fileDiffs, diffHash = "diff1") {
  return {
    base_ref: "main",
    head_ref: "HEAD",
    changed_files: Object.keys(fileDiffs),
    diff_hash: diffHash,
    file_diffs: fileDiffs,
    diff_text: Object.entries(fileDiffs).map(([path, diff]) => `diff --git a/${path} b/${path}\n${diff}`).join("\n"),
  };
}

function packManifest(packId, scope = ".") {
  return {
    packs: [{ id: packId, version: "1.0.0", scope }],
    gates: [],
  };
}

function contractMarker(issueNumber = 1075) {
  return buildBoundPhaseMarker({
    phase: "contract",
    issueNumber,
    binding: { diff_hash: "diff1", contract_hash: "contract1" },
    body: "Public contract: validate inputs, preserve invariants, and expose explicit errors.",
  });
}

describe("ADR-057 assurance classifier", () => {
  it("detects security, state-machine, DAG, and corruption-prone mutator surfaces with required artifacts", async () => {
    await withTempRepo(async (repo) => {
      const cases = [
        {
          type: "security_boundary",
          files: {
            "src/main/java/example/FooController.java": [
              "+class FooController {",
              "+  @PreAuthorize(\"hasRole('ADMIN')\")",
              "+  void update(FooRequest request) { validate(request); }",
              "+}",
            ].join("\n"),
            "src/test/java/example/FooControllerTest.java": [
              "+@WithMockUser(roles = \"USER\")",
              "+void rejectsUnauthorizedUser() { assertThrows(AccessDeniedException.class, () -> call()); }",
            ].join("\n"),
          },
        },
        {
          type: "state_machine",
          files: {
            "src/main/java/example/StatusFlow.java": [
              "+class StatusFlow {",
              "+  boolean canTransitionTo(Status next) {",
              "+    if (!allowedTransitions.contains(next)) throw new IllegalStateException(\"invalid_status_transition\");",
              "+    return true;",
              "+  }",
              "+}",
            ].join("\n"),
            "src/test/java/example/StatusPropertyTest.java": [
              "+@Property",
              "+void transition_matrix_rejects_invalid_edges() {",
              "+  Arbitraries.of(\"DRAFT\", \"ACTIVE\");",
              "+  assertThrows(IllegalStateException.class, () -> transition());",
              "+}",
            ].join("\n"),
          },
        },
        {
          type: "dag_graph",
          files: {
            "src/main/java/example/GraphPlanner.java": [
              "+class GraphPlanner {",
              "+  List<Node> topologicalSort(Graph graph) {",
              "+    if (detectCycle(graph)) throw new IllegalArgumentException(\"cycle\");",
              "+    // acyclic invariant: depth and visited are bounded by path length",
              "+    return List.of();",
              "+  }",
              "+}",
            ].join("\n"),
            "src/test/java/example/GraphPropertyTest.java": [
              "+@Property",
              "+void topological_path_property_rejects_cycles() {",
              "+  // cycle path topological",
              "+  Arbitraries.integers();",
              "+  assertThrows(IllegalArgumentException.class, () -> graphWithCycle());",
              "+}",
            ].join("\n"),
          },
        },
        {
          type: "corruption_prone_mutator",
          files: {
            "src/main/java/example/RequirementService.java": [
              "+class RequirementService {",
              "+  @Transactional",
              "+  void archiveRequirement(String externalId) {",
              "+    validate(externalId);",
              "+    repository.save(entity);",
              "+    audit.record(\"archive\");",
              "+  }",
              "+}",
            ].join("\n"),
            "src/test/java/example/RequirementServiceTest.java": [
              "+void archive_rejects_bad_externalId_and_saves_audit() {",
              "+  assertThrows(IllegalArgumentException.class, () -> archive());",
              "+  assertThat(repository.save(entity)).isNotNull();",
              "+}",
            ].join("\n"),
          },
        },
      ];

      for (const item of cases) {
        const result = classifyAssuranceSurfacesFromDiff({
          repoRoot: repo,
          manifest: packManifest("jvm-gradle"),
          diffInfo: classifierDiff(item.files),
          issueNumber: 1075,
          commentBodies: [contractMarker()],
        });
        assert.equal(result.ok, true, `${item.type} should have all mandated artifacts`);
        assert.ok(result.surfaces.some((surface) => surface.surface_type === item.type), `${item.type} should be classified`);
      }
    });
  });

  it("auto-excludes trivial enums/DTOs and no-ops for docs-generic docs-only diffs", async () => {
    await withTempRepo(async (repo) => {
      const trivial = classifyAssuranceSurfacesFromDiff({
        repoRoot: repo,
        manifest: packManifest("jvm-gradle"),
        diffInfo: classifierDiff({
          "src/main/java/example/Status.java": "+public enum Status { DRAFT, ACTIVE }\n",
        }),
        issueNumber: 1075,
        commentBodies: [contractMarker()],
      });
      assert.equal(trivial.ok, true);
      assert.deepEqual(trivial.surfaces, []);

      const docs = classifyAssuranceSurfacesFromDiff({
        repoRoot: repo,
        manifest: packManifest("docs-generic"),
        diffInfo: classifierDiff({ "docs/workflow.md": "+# Workflow\n+Text only.\n" }),
        issueNumber: 1075,
        commentBodies: [],
      });
      assert.equal(docs.ok, true);
      assert.deepEqual(docs.surfaces, []);
    });
  });
});

describe("contract-first marker tools", () => {
  it("enforces contract, plan, test_red, and impl_green prerequisites with re-run evidence", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      const diffInfo = classifierDiff({ "README.md": "+# Contract marker fixture\n" });
      const contextLoaded = buildBoundPhaseMarker({
        phase: "context_loaded",
        issueNumber: 1075,
        binding: { context_hash: "ctx1" },
      });
      const preflight = buildPhaseMarker({ phase: "preflight", issueNumber: 1075 });
      const plan = buildPhaseMarker({ phase: "plan", issueNumber: 1075 });
      const contract = contractMarker();
      const testRed = buildBoundPhaseMarker({
        phase: "test_red",
        issueNumber: 1075,
        binding: { red_diff_hash: "diff1", evidence_mode: "red_command" },
      });
      const markerCalls = [];
      const markerPoster = async (payload) => {
        markerCalls.push(payload);
        return { html_url: `https://example.test/comment/${markerCalls.length}`, id: markerCalls.length };
      };

      const contractMissing = await runPostInterfaceContract({
        repoPath: repo,
        issueNumber: 1075,
        contractBody: "Interface: GET /things returns ThingDto or ErrorEnvelope.",
        phaseCommentBodies: [],
        markerPoster,
        diffInfo,
      });
      assert.equal(contractMissing.ok, false);
      assert.deepEqual(contractMissing.missing, ["context_loaded", "preflight"]);

      const contractPosted = await runPostInterfaceContract({
        repoPath: repo,
        issueNumber: 1075,
        contractBody: "Interface: GET /things returns ThingDto or ErrorEnvelope.",
        phaseCommentBodies: [contextLoaded, preflight],
        markerPoster,
        diffInfo,
      });
      assert.equal(contractPosted.ok, true);
      assert.equal(markerCalls.at(-1).phase, "contract");
      assert.ok(contractPosted.engineering_contract.some((entry) => entry.property === "Interface-first"));

      const planMissing = await runPostImplementationPlan({
        repoPath: repo,
        issueNumber: 1075,
        planBody: "Plan: implement the declared interface.",
        phaseCommentBodies: [preflight],
        markerPoster,
      });
      assert.equal(planMissing.ok, false);
      assert.deepEqual(planMissing.missing, ["context_loaded", "contract"]);

      const planPosted = await runPostImplementationPlan({
        repoPath: repo,
        issueNumber: 1075,
        planBody: "Plan: implement the declared interface.",
        phaseCommentBodies: [contextLoaded, contract],
        markerPoster,
      });
      assert.equal(planPosted.ok, true);
      assert.equal(markerCalls.at(-1).phase, "plan");

      const redMissing = await runAssertTestRed({
        repoPath: repo,
        issueNumber: 1075,
        testCommand: "npm test -- Thing",
        phaseCommentBodies: [contract],
        markerPoster,
        diffInfo,
        commandRunner: async () => ({ exit_code: 1, stdout: "", stderr: "", timed_out: false, duration_ms: 5 }),
      });
      assert.equal(redMissing.ok, false);
      assert.deepEqual(redMissing.missing, ["plan"]);

      const redNotRed = await runAssertTestRed({
        repoPath: repo,
        issueNumber: 1075,
        testCommand: "npm test -- Thing",
        phaseCommentBodies: [plan],
        markerPoster,
        diffInfo,
        commandRunner: async () => ({ exit_code: 0, stdout: "passed", stderr: "", timed_out: false, duration_ms: 5 }),
      });
      assert.equal(redNotRed.ok, false);
      assert.equal(redNotRed.error, "test_red_not_red");

      const redPosted = await runAssertTestRed({
        repoPath: repo,
        issueNumber: 1075,
        testCommand: "npm test -- Thing",
        phaseCommentBodies: [plan],
        markerPoster,
        diffInfo,
        commandRunner: async () => ({ exit_code: 1, stdout: "", stderr: "expected failure", timed_out: false, duration_ms: 5 }),
      });
      assert.equal(redPosted.ok, true);
      assert.equal(markerCalls.at(-1).phase, "test_red");

      const greenMissing = await runAssertImplGreen({
        repoPath: repo,
        issueNumber: 1075,
        testCommand: "npm test -- Thing",
        phaseCommentBodies: [plan],
        markerPoster,
        diffInfo,
        commandRunner: async () => ({ exit_code: 0, stdout: "passed", stderr: "", timed_out: false, duration_ms: 5 }),
      });
      assert.equal(greenMissing.ok, false);
      assert.deepEqual(greenMissing.missing, ["test_red"]);

      const greenPosted = await runAssertImplGreen({
        repoPath: repo,
        issueNumber: 1075,
        testCommand: "npm test -- Thing",
        phaseCommentBodies: [testRed],
        markerPoster,
        diffInfo,
        commandRunner: async () => ({ exit_code: 0, stdout: "passed", stderr: "", timed_out: false, duration_ms: 5 }),
      });
      assert.equal(greenPosted.ok, true);
      assert.equal(markerCalls.at(-1).phase, "impl_green");
    });
  });

  it("injects the posted contract oracle and ADR-059 engineering contract into the test-strength lens", () => {
    const prompt = buildTestQualityReviewPrompt({
      baseBranch: "origin/dev",
      changedTestFiles: ["src/test/java/example/FooTest.java"],
      interfaceContract: "Interface: POST /foo returns 201 or ErrorEnvelope.",
    });
    assert.match(prompt, /Posted interface contract oracle/);
    assert.match(prompt, /POST \/foo returns 201/);
    assert.match(prompt, /Interface-first/);
    assert.match(prompt, /Secure from the gate/);
  });
});

describe("implementation context and diff-derived traceability", () => {
  it("loads binding ADR context and writes context_loaded before contract posting", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo, [
        "docs:",
        "  adr_dir: architecture/adrs/",
        "cross_cutting_concerns:",
        "  description: ErrorResponse envelope; Spring Security auth boundary.",
      ].join("\n"));
      mkdirSync(join(repo, "architecture/adrs"), { recursive: true });
      for (const id of ["031", "036", "061", "062"]) {
        writeFileSync(join(repo, `architecture/adrs/${id}-binding.md`), `# ADR-${id}\n\nBinding design ${id}.\n`);
      }
      const markerCalls = [];
      const result = await withMockFetch([
        ["/api/v1/requirements/traceability/by-artifact", async () => ({ body: [] })],
      ], () => runGetImplementationContext({
        repoPath: repo,
        issueNumber: 1075,
        issueContext: { body: "## Requirements\n\nNo formal requirements for this fixture." },
        markerPoster: async (payload) => {
          markerCalls.push(payload);
          return { html_url: "https://example.test/comment/ctx", id: 77 };
        },
      }));
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.phase_marker.phase, "context_loaded");
      assert.equal(markerCalls[0].phase, "context_loaded");
      assert.equal(result.binding_adrs.filter((adr) => adr.error == null).length, 4);
      assert.match(result.ground_control_context.cross_cutting_concerns.description, /ErrorResponse/);

      const contract = await runPostInterfaceContract({
        repoPath: repo,
        issueNumber: 1075,
        contractBody: "Interface: deterministic context fixture.",
        phaseCommentBodies: [buildPhaseMarker({ phase: "preflight", issueNumber: 1075 })],
        diffInfo: classifierDiff({ "README.md": "+x\n" }),
        markerPoster: async () => ({ html_url: "https://example.test/comment/contract", id: 78 }),
      });
      assert.equal(contract.ok, false);
      assert.deepEqual(contract.missing, ["context_loaded"]);
    });
  });

  it("reconciles traceability from the live git name-status diff and reports gap sets", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      mkdirSync(join(repo, "mcp/ground-control"), { recursive: true });
      writeFileSync(join(repo, "mcp/ground-control/lib.js"), "export const before = 1;\n");
      execFileSync("git", ["add", "."], { cwd: repo });
      execFileSync("git", ["commit", "-m", "base"], { cwd: repo, stdio: "ignore" });
      execFileSync("git", ["branch", "base"], { cwd: repo });
      writeFileSync(join(repo, "mcp/ground-control/lib.js"), "export const after = 2;\n");
      execFileSync("git", ["add", "."], { cwd: repo });
      execFileSync("git", ["commit", "-m", "change"], { cwd: repo, stdio: "ignore" });

      const result = await withMockFetch([
        ["/api/v1/requirements/traceability/by-artifact", async (url) => {
          if (url.includes("artifactType=FILE")) return { body: [] };
          return { body: [] };
        }],
        ["/api/v1/requirements/uid/GC-X001", async () => ({ body: { id: "uuid-1", uid: "GC-X001", status: "ACTIVE" } })],
        ["/api/v1/requirements/uuid-1/traceability", async () => ({ body: [] })],
      ], () => runReconcileTraceability({
        repoPath: repo,
        issueNumber: 1075,
        baseRef: "base",
        headRef: "HEAD",
        issueContext: { body: "## Requirements\n\n- GC-X001 - Traceability fixture" },
        requirements: [{ uid: "GC-X001", statusIntent: "ACTIVE" }],
      }));
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.name_status[0].path, "mcp/ground-control/lib.js");
      assert.equal(result.worklist[0].suggested_action, "add_implements_link_or_mark_requirement_free");
      assert.deepEqual(result.gap_set, [{ uid: "GC-X001", reason: "implements_missing", status: "ACTIVE" }]);
      assert.match(result.diff_hash, /^[a-f0-9]{64}$/);
    });
  });

  it("treats a traceability_reconciled marker as stale when the bound diff hash changes", () => {
    const marker = buildBoundPhaseMarker({
      phase: "traceability_reconciled",
      issueNumber: 1075,
      binding: { diff_hash: "old-diff", requirements_hash: "reqs" },
    });
    const result = evaluateBoundPhaseMarkerFreshness({
      commentBodies: [marker],
      issueNumber: 1075,
      phase: "traceability_reconciled",
      expected: { diff_hash: "new-diff" },
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "stale_phase_marker");
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

describe("gc_run_gates contract-first completion gate", () => {
  it("refuses to write gates_green without a fresh impl_green marker bound to the current diff", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      writeManifestRepo(repo, `
schema_version: 1
packs:
  - id: docs-generic
    version: "1.0.0"
    scope: .
gates:
  - id: docs.policy
    capability: docs_policy
    pack: docs-generic
    command: echo ok
    blocking: true
    scope: changed
    applies_when:
      paths: ["**/*.md"]
`, {
        schema_version: 1,
        engine: { version: "1.0.0" },
        packs: [{ id: "docs-generic", version: "1.0.0" }],
      });
      let commandRan = false;
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: classifierDiff({ "README.md": "+# Docs\n" }),
        postMarker: true,
        phaseCommentBodies: [],
        markerPoster: async () => {
          throw new Error("marker poster should not be reached");
        },
        commandRunner: async () => {
          commandRan = true;
          return { exit_code: 0, stdout: "", stderr: "", timed_out: false, duration_ms: 5 };
        },
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "missing_phase_marker");
      assert.deepEqual(result.missing, ["impl_green"]);
      assert.equal(commandRan, false);
    });
  });

  it("completion gate refuses an L1 security boundary without contract and test artifacts", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo);
      writeManifestRepo(repo, `
schema_version: 1
packs:
  - id: jvm-gradle
    version: "1.0.0"
    scope: .
gates: []
`, {
        schema_version: 1,
        engine: { version: "1.0.0" },
        packs: [{ id: "jvm-gradle", version: "1.0.0" }],
      });
      const result = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        diffInfo: classifierDiff({
          "src/main/java/example/FooController.java": [
            "+class FooController {",
            "+  @PreAuthorize(\"hasRole('ADMIN')\")",
            "+  void update(FooRequest request) { validate(request); }",
            "+}",
          ].join("\n"),
        }),
        postMarker: false,
        commandRunner: async () => {
          throw new Error("gates should not run before assurance artifacts are present");
        },
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "assurance_artifacts_missing");
      assert.equal(result.classifier.missing[0].surface_type, "security_boundary");
      assert.equal(result.classifier.missing[0].assurance_level, "L1");
      assert.deepEqual(result.classifier.missing[0].missing.sort(), [
        "contract_artifact",
        "contract_phase_marker",
        "test_artifact",
      ]);
    });
  });
});

describe("required remote statuses", () => {
  function fullRemoteQuality(overrides = {}) {
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
      coverage: { overall: 91, new: 92 },
      duplications: { overall: 1.2, new: 0 },
      ...overrides,
    };
  }

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

  it("evaluates remote-quality substance separately from provider checkmarks", () => {
    const platform = evaluateRemoteQualitySubstance({
      providerResults: [fullRemoteQuality({
        issues: {
          new: { total: 0, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 } },
          overall: { total: 1, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 1, MINOR: 0, INFO: 0 } },
        },
      })],
      policy: { tier: "platform_minimum" },
    });
    assert.equal(platform.ok, true);

    const ratcheted = evaluateRemoteQualitySubstance({
      providerResults: [fullRemoteQuality({
        issues: {
          new: { total: 0, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 } },
          overall: { total: 1, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 1, MINOR: 0, INFO: 0 } },
        },
      })],
      policy: { tier: "zero_overall_issues" },
    });
    assert.equal(ratcheted.ok, false);
    assert.ok(ratcheted.failures.some((failure) => failure.reason === "overall_issues_not_zero"));
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

  it("refuses remote_gates_green when a green-checkmark PR still has open Sonar issues", async () => {
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
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["mcp/ground-control/lib.js"], diff_hash: "diff1" },
        remoteQualityPolicy: { tier: "zero_overall_issues" },
        remoteQualityResults: [fullRemoteQuality({
          issues: {
            new: { total: 0, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 } },
            overall: { total: 1, by_severity: { BLOCKER: 0, CRITICAL: 0, MAJOR: 1, MINOR: 0, INFO: 0 } },
          },
        })],
        markerPoster: async (payload) => {
          markerCalls.push(payload);
          return { html_url: "https://example.test/comment/1", id: 1 };
        },
      });
      assert.equal(result.ok, false);
      assert.equal(result.error, "remote_quality_substance_failed");
      assert.equal(result.evaluation.ok, true);
      assert.ok(result.remote_quality.failures.some((failure) => failure.reason === "overall_issues_not_zero"));
      assert.equal(markerCalls.length, 0);
    });
  });
});

describe("fix-risk escalation and gate telemetry summary", () => {
  it("classifies low, medium, and high fix risks deterministically", () => {
    const low = classifyFixRisk({ changedFiles: ["docs/DEVELOPMENT_WORKFLOW.md"] });
    assert.equal(low.risk, "low");
    assert.equal(low.automatic, true);

    const medium = classifyFixRisk({
      changedFiles: ["frontend/src/App.jsx", "frontend/src/App.test.jsx"],
      behaviorChange: true,
    });
    assert.equal(medium.risk, "medium");
    assert.equal(medium.automatic, true);

    const high = classifyFixRisk({
      changedFiles: ["mcp/ground-control/lib.js"],
      behaviorChange: true,
      testCoverage: false,
    });
    assert.equal(high.risk, "high");
    assert.equal(high.automatic, false);
  });

  it("dispatches high_risk_fix as a distinct owner escalation", () => {
    const result = dispatchReviewConvergence({
      currentCycle: 1,
      cap: 2,
      lensEnvelopes: [{
        verdict: "ship-with-fixes",
        reviewer_lens: "correctness",
        findings: [{
          id: "F1",
          severity: "Major",
          location: "mcp/ground-control/lib.js:10",
          title: "Remote-quality bug",
          evidence: "Provider substance can be bypassed.",
          classification: "one-off",
          sweep: "No analogue found.",
        }],
        blocking: [{
          id: "F1",
          severity: "Major",
          location: "mcp/ground-control/lib.js:10",
          title: "Remote-quality bug",
          evidence: "Provider substance can be bypassed.",
          classification: "one-off",
          sweep: "No analogue found.",
        }],
      }],
      proposedFixRisk: { risk: "high", factors: ["critical_behavior_change"] },
    });
    assert.equal(result.next_action, "post_high_risk_fix_and_escalate");
    assert.equal(result.escalation_reason, "high_risk_fix");
    assert.equal(result.terminal, true);
    assert.equal(result.decision_aid.high_risk_fix.risk, "high");
  });

  it("summarizes gate-effectiveness telemetry by gate", async () => {
    await withTempRepo(async (repo) => {
      writeConfig(repo, [
        "workflow:",
        "  completion_command: make policy",
      ].join("\n"));
      const gateResult = await runGates({
        repoPath: repo,
        issueNumber: 1075,
        capabilities: ["policy"],
        diffInfo: { base_ref: "main", head_ref: "HEAD", changed_files: ["README.md"], diff_hash: "diff1" },
        postMarker: false,
        commandRunner: async () => ({ exit_code: 0, stdout: "", stderr: "", timed_out: false, duration_ms: 11 }),
      });
      assert.equal(gateResult.ok, true);
      const summary = await runGateTelemetrySummary({ repoPath: repo, issueNumber: 1075 });
      assert.equal(summary.ok, true);
      assert.equal(summary.summary.total_records, 1);
      assert.equal(summary.summary.gates[0].gate_id, "legacy.policy");
      assert.equal(summary.summary.gates[0].fire_rate, 1);
      assert.equal(summary.summary.gates[0].outcomes.passed, 1);
      assert.equal(summary.summary.gates[0].duration_ms.avg_ms, 11);
    });
  });
});
