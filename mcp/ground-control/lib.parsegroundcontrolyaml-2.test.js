// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseGroundControlYaml } from "./lib.js";

describe("parseGroundControlYaml", () => {
  // Most cases build a YAML document from an array of lines and parse it.
  // `parseYamlLines` removes the repeated `[...].join("\n")` + parse scaffold,
  // and `expectYamlError` additionally asserts the standard "invalid, with an
  // error message containing <substr>" shape used by the rejection cases.
  function parseYamlLines(lines) {
    return parseGroundControlYaml(lines.join("\n"));
  }

  function expectYamlError(lines, substr) {
    const result = parseYamlLines(lines);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes(substr)));
    return result;
  }


  it("rejects an empty knowledge.schema override", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "knowledge:",
      "  dir: docs/knowledge",
      "  schema: ''",
      "",
    ], "knowledge.schema must be a non-empty string");
  });


  // -------------------------------------------------------------------------
  // ADR-027 schema additions: docs, example_paths, requirements,
  // cross_cutting_concerns. All four are optional; absent block returns a
  // null-shaped default so the canonical SKILL.md can fall back via
  // {cfg.X|default Y} placeholders.
  // -------------------------------------------------------------------------

  it("returns null-shaped defaults when docs/example_paths/requirements/cross_cutting_concerns are absent", () => {
    const result = parseGroundControlYaml("schema_version: 1\nproject: ground-control\n");
    assert.equal(result.ok, true);
    assert.deepEqual(result.value.docs, {
      adr_dir: null,
      architecture_overview: null,
      coding_standards: null,
      workflow_reference: null,
      knowledge_base: null,
    });
    assert.deepEqual(result.value.example_paths, { source: null, test: null });
    assert.deepEqual(result.value.requirements, { uid_examples: [] });
    assert.deepEqual(result.value.cross_cutting_concerns, { description: null });
  });


  it("parses a fully populated docs block", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: ground-control",
      "docs:",
      "  adr_dir: architecture/adrs/",
      "  architecture_overview: docs/architecture/ARCHITECTURE.md",
      "  coding_standards: docs/CODING_STANDARDS.md",
      "  workflow_reference: docs/DEVELOPMENT_WORKFLOW.md",
      "  knowledge_base: docs/knowledge/",
      "",
    ]);
    assert.equal(result.ok, true);
    assert.deepEqual(result.value.docs, {
      adr_dir: "architecture/adrs/",
      architecture_overview: "docs/architecture/ARCHITECTURE.md",
      coding_standards: "docs/CODING_STANDARDS.md",
      workflow_reference: "docs/DEVELOPMENT_WORKFLOW.md",
      knowledge_base: "docs/knowledge/",
    });
  });


  it("rejects unknown keys inside docs", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "docs:",
      "  bogus: nope",
      "",
    ], "docs has unknown key 'bogus'");
  });


  it("rejects docs when it is not a mapping", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "docs:",
      "  - not-a-mapping",
      "",
    ], "docs must be a mapping");
  });


  it("rejects an empty string for docs.adr_dir", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "docs:",
      "  adr_dir: ''",
      "",
    ], "docs.adr_dir must be a non-empty string");
  });


  it("parses a fully populated example_paths block", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: ground-control",
      "example_paths:",
      "  source: backend/src/main/java/com/keplerops/groundcontrol/",
      "  test: backend/src/test/java/com/keplerops/groundcontrol/",
      "",
    ]);
    assert.equal(result.ok, true);
    assert.deepEqual(result.value.example_paths, {
      source: "backend/src/main/java/com/keplerops/groundcontrol/",
      test: "backend/src/test/java/com/keplerops/groundcontrol/",
    });
  });


  it("rejects unknown keys inside example_paths", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "example_paths:",
      "  source: src/",
      "  bogus: src/",
      "",
    ], "example_paths has unknown key 'bogus'");
  });


  it("rejects example_paths when it is not a mapping", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "example_paths: not-a-mapping",
      "",
    ], "example_paths must be a mapping");
  });


  it("parses a requirements block with uid_examples", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: ground-control",
      "requirements:",
      "  uid_examples:",
      "    - GC-X001",
      "    - OBS-042",
      "",
    ]);
    assert.equal(result.ok, true);
    assert.deepEqual(result.value.requirements.uid_examples, ["GC-X001", "OBS-042"]);
  });


  it("rejects requirements.uid_examples when it is not a list", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "requirements:",
      "  uid_examples: GC-X001",
      "",
    ], "requirements.uid_examples must be a list");
  });


  it("rejects non-string entries in requirements.uid_examples", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "requirements:",
      "  uid_examples:",
      "    - GC-X001",
      "    - 42",
      "",
    ], "requirements.uid_examples");
  });


  it("rejects unknown keys inside requirements", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "requirements:",
      "  uid_examples: []",
      "  bogus: true",
      "",
    ], "requirements has unknown key 'bogus'");
  });


  it("parses a cross_cutting_concerns description", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: ground-control",
      "cross_cutting_concerns:",
      "  description: |",
      "    Logger: SLF4J via @Slf4j",
      "    Validation: Bean Validation + Zod",
      "",
    ]);
    assert.equal(result.ok, true);
    assert.ok(result.value.cross_cutting_concerns.description.includes("SLF4J"));
    assert.ok(result.value.cross_cutting_concerns.description.includes("Bean Validation"));
  });


  it("rejects unknown keys inside cross_cutting_concerns", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "cross_cutting_concerns:",
      "  description: x",
      "  bogus: y",
      "",
    ], "cross_cutting_concerns has unknown key 'bogus'");
  });


  it("rejects cross_cutting_concerns.description when empty", () => {
    expectYamlError([
      "schema_version: 1",
      "project: ground-control",
      "cross_cutting_concerns:",
      "  description: ''",
      "",
    ], "cross_cutting_concerns.description must be a non-empty string");
  });


  // ---------------------------------------------------------------------
  // architecture.vocabulary (#931)
  // ---------------------------------------------------------------------

  it("defaults architecture to null when the block is absent", () => {
    const result = parseGroundControlYaml("schema_version: 1\nproject: x\n");
    assert.equal(result.ok, true);
    assert.equal(result.value.architecture, null);
  });


  it("accepts an empty architecture.vocabulary mapping", () => {
    const yaml = "schema_version: 1\nproject: x\narchitecture:\n  vocabulary: {}\n";
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.value.architecture, {
      vocabulary: {
        patterns: [],
        canonical_helpers: [],
        boundary_contract: null,
        binding_adrs: [],
        anti_recommendations: [],
      },
    });
  });


  it("parses a fully populated architecture.vocabulary block", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    patterns:",
      "      - name: Repository",
      "        applies_to: data access",
      "        example_path: backend/src/main/java/FooRepository.java",
      "    canonical_helpers:",
      "      - name: ErrorResponse",
      "        path: backend/src/main/java/ErrorResponse.java",
      "        purpose: standard error envelope",
      "    boundary_contract:",
      "      description: api/ -> domain/ <- infrastructure/ (ArchUnit-enforced)",
      "    binding_adrs:",
      "      - id: ADR-027",
      "        one_liner: agent-neutral context contract",
      "    anti_recommendations:",
      "      - Do not introduce new abstractions below 3 call-sites",
      "",
    ]);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const v = result.value.architecture.vocabulary;
    assert.deepEqual(v.patterns, [{ name: "Repository", applies_to: "data access", example_path: "backend/src/main/java/FooRepository.java" }]);
    assert.deepEqual(v.canonical_helpers, [{ name: "ErrorResponse", purpose: "standard error envelope", path: "backend/src/main/java/ErrorResponse.java" }]);
    assert.deepEqual(v.boundary_contract, { description: "api/ -> domain/ <- infrastructure/ (ArchUnit-enforced)" });
    assert.deepEqual(v.binding_adrs, [{ id: "ADR-027", one_liner: "agent-neutral context contract" }]);
    assert.deepEqual(v.anti_recommendations, ["Do not introduce new abstractions below 3 call-sites"]);
  });


  it("rejects unknown keys under architecture.vocabulary", () => {
    const yaml = "schema_version: 1\nproject: x\narchitecture:\n  vocabulary:\n    bogus: nope\n";
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("architecture.vocabulary has unknown key 'bogus'")));
  });


  it("rejects unknown keys under architecture itself", () => {
    const yaml = "schema_version: 1\nproject: x\narchitecture:\n  bogus: nope\n";
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("architecture has unknown key 'bogus'")));
  });


  it("rejects unknown keys inside architecture.vocabulary.patterns entries", () => {
    expectYamlError([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    patterns:",
      "      - name: Foo",
      "        applies_to: bar",
      "        bogus: nope",
      "",
    ], "patterns[0] has unknown key 'bogus'");
  });


  it("requires patterns[].name and applies_to", () => {
    expectYamlError([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    patterns:",
      "      - applies_to: bar",
      "",
    ], "patterns[0].name must be a non-empty string");
  });


  it("requires canonical_helpers[].name and purpose", () => {
    expectYamlError([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    canonical_helpers:",
      "      - name: Foo",
      "",
    ], "canonical_helpers[0].purpose must be a non-empty string");
  });


  it("requires binding_adrs[].id to match ADR-NNN", () => {
    expectYamlError([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    binding_adrs:",
      "      - id: ADR-27",
      "        one_liner: oops",
      "",
    ], "binding_adrs[0].id");
  });


  it("requires anti_recommendations[] entries to be non-empty strings", () => {
    expectYamlError([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    anti_recommendations:",
      "      - \"\"",
      "",
    ], "anti_recommendations[0]");
  });


  it("rejects boundary_contract.description that is empty", () => {
    expectYamlError([
      "schema_version: 1",
      "project: x",
      "architecture:",
      "  vocabulary:",
      "    boundary_contract:",
      "      description: \"\"",
      "",
    ], "boundary_contract.description must be a non-empty string");
  });


  // ---------------------------------------------------------------------
  // Legacy grc.* block (ADR-089 §4): tolerated and ignored, not validated,
  // not returned. Formerly grc.boundaries (GC-GRC-004) / grc.data_classification
  // (GC-GRC-006), both retired as active config surfaces by ADR-089.
  // ---------------------------------------------------------------------

  it("tolerates a legacy grc block (any shape) without validating or returning it", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: x",
      "grc:",
      "  boundaries:",
      "    - key: Policy", // would have failed the retired key-pattern check
      "      name: Policy",
      "      paths:",
      "        - tools/*/policy", // would have failed the retired selector check
      "  data_classification:",
      "    labels: []", // would have failed the retired non-empty-list check
      "",
    ]);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.equal(result.value.grc, undefined, "grc must not be returned in the parsed config");
  });
});
