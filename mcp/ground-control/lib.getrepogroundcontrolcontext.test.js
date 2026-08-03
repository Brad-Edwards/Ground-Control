// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { getRepoGroundControlContext } from "./lib.js";

describe("getRepoGroundControlContext", () => {
  function makeTempRepo() {
    const dir = mkdtempSync(join(tmpdir(), "gc-yaml-test-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    return dir;
  }

  // Writes `.ground-control.yaml` (from an array of YAML lines) into a
  // test-controlled temp repo. Centralises the repeated writeFileSync + the
  // eslint-disable that every case needed for the non-literal path.
  function writeYamlConfig(dir, lines) {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    writeFileSync(join(dir, ".ground-control.yaml"), lines.join("\n"));
  }

  function makeKnowledgeRepo({ extraYamlLines = [] } = {}) {
    const dir = makeTempRepo();
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    mkdirSync(join(dir, "docs", "knowledge"), { recursive: true });
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
    writeFileSync(join(dir, "docs", "knowledge", "SCHEMA.md"), "# schema\n");
    writeYamlConfig(dir, [
        "schema_version: 1",
        "project: test-project",
        "knowledge:",
        "  dir: docs/knowledge",
        ...extraYamlLines,
        "",
      ]);
    return dir;
  }


  it("returns missing_ground_control_yaml when the file is absent", async () => {
    const dir = makeTempRepo();
    try {
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "missing_ground_control_yaml");
      assert.equal(result.project, null);
      assert.ok(result.errors[0].includes(".ground-control.yaml"));
      assert.ok(result.suggested_ground_control_yaml.includes("schema_version"));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns ok for a valid .ground-control.yaml", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(
        join(dir, ".ground-control.yaml"),
        "schema_version: 1\nproject: test-project\n",
      );
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "ok");
      assert.equal(result.project, "test-project");
      assert.equal(result.rules.plan_rules_path, null);
      assert.equal(result.rules.plan_rules_content, null);
      assert.equal(result.knowledge, null);
      // ADR-027 schema additions are returned even when absent (null-shaped defaults)
      assert.deepEqual(result.docs, {
        adr_dir: null,
        architecture_overview: null,
        coding_standards: null,
        workflow_reference: null,
        knowledge_base: null,
      });
      assert.deepEqual(result.example_paths, { source: null, test: null });
      assert.deepEqual(result.requirements, { uid_examples: [] });
      assert.deepEqual(result.cross_cutting_concerns, { description: null });
      assert.equal(result.grc, undefined);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns the docs/example_paths/requirements/cross_cutting_concerns blocks when present", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "docs:",
          "  adr_dir: architecture/adrs/",
          "  coding_standards: docs/CODING_STANDARDS.md",
          "example_paths:",
          "  source: src/",
          "  test: tests/",
          "requirements:",
          "  uid_examples: [\"X-001\", \"Y-002\"]",
          "cross_cutting_concerns:",
          "  description: Logger via pino",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "ok");
      assert.equal(result.docs.adr_dir, "architecture/adrs/");
      assert.equal(result.docs.coding_standards, "docs/CODING_STANDARDS.md");
      assert.equal(result.example_paths.source, "src/");
      assert.deepEqual(result.requirements.uid_examples, ["X-001", "Y-002"]);
      assert.equal(result.cross_cutting_concerns.description, "Logger via pino");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  // ADR-089 §4: a legacy grc.* block (including one whose boundary paths
  // would have escaped the repo root under the retired GC-GRC-004 check) is
  // tolerated — no path-containment validation runs over it, no error is
  // raised, and it is never returned in the context.
  it("tolerates a legacy grc block with escaping paths without validating or returning it", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "grc:",
          "  boundaries:",
          "    - key: escape",
          "      name: Escape",
          "      paths:",
          "        - ../outside/**",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "ok");
      assert.equal(result.grc, undefined);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });








  it("inlines plan_rules file content when referenced", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, ".gc"));
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(dir, ".gc", "plan-rules.md"), "- rule one\n- rule two\n");
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "rules:",
          "  plan_rules: .gc/plan-rules.md",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "ok");
      assert.equal(result.rules.plan_rules_path, ".gc/plan-rules.md");
      assert.ok(result.rules.plan_rules_content.includes("rule one"));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when plan_rules file is missing", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "rules:",
          "  plan_rules: .gc/missing.md",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors[0].includes(".gc/missing.md"));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when the yaml is malformed", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(
        join(dir, ".ground-control.yaml"),
        "schema_version: 1\nproject: ACES_SDL\n",
      );
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("lowercase identifier")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns a resolved knowledge block when dir exists and defaults apply", async () => {
    const dir = makeKnowledgeRepo();
    try {
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "ok");
      assert.deepEqual(result.knowledge, {
        dir: "docs/knowledge",
        schema: "docs/knowledge/SCHEMA.md",
        inbox: "docs/knowledge/inbox",
      });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("honors explicit knowledge.schema and knowledge.inbox overrides", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, "wiki"), { recursive: true });
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(dir, "wiki", "custom-schema.md"), "# schema\n");
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: wiki",
          "  schema: wiki/custom-schema.md",
          "  inbox: wiki/capture",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "ok");
      assert.deepEqual(result.knowledge, {
        dir: "wiki",
        schema: "wiki/custom-schema.md",
        inbox: "wiki/capture",
      });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when knowledge.dir does not exist", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: docs/knowledge",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("knowledge.dir")));
      assert.ok(result.errors.some((e) => e.includes("docs/knowledge")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when knowledge.schema file does not exist", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, "docs", "knowledge"), { recursive: true });
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: docs/knowledge",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("knowledge.schema")));
      assert.ok(result.errors.some((e) => e.includes("SCHEMA.md")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });













  it("reads a plan_rules file that stays inside the repository", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, ".gc"), { recursive: true });
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(dir, ".gc", "plan-rules.md"), "# Plan rules\n");
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(
        join(dir, ".ground-control.yaml"),
        "schema_version: 1\nproject: test-project\nrules:\n  plan_rules: .gc/plan-rules.md\n",
      );

      const result = await getRepoGroundControlContext(dir);

      assert.equal(result.status, "ok");
      assert.match(result.rules.plan_rules_content, /Plan rules/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
