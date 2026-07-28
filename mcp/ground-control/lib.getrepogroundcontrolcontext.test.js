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


  it("rejects an absolute docs.knowledge_base path", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "docs:",
          "  knowledge_base: /etc/passwd",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("docs.knowledge_base")));
      assert.ok(result.errors.some((e) => e.includes("absolute path")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("rejects a docs path that escapes the repo root via ..", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "docs:",
          "  architecture_overview: ../../../etc/secrets",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("docs.architecture_overview")));
      assert.ok(result.errors.some((e) => e.includes("inside the repository root")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("rejects an absolute example_paths.source path", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "example_paths:",
          "  source: /usr/bin",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("example_paths.source")));
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


  it("returns invalid_ground_control_yaml when knowledge.dir is an absolute path", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: /etc/passwd",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("knowledge.dir")));
      assert.ok(result.errors.some((e) => /repo[- ]relative|absolute/.test(e)));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when knowledge.dir escapes the repository root", async () => {
    const dir = makeTempRepo();
    try {
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: ../escape",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("knowledge.dir")));
      assert.ok(result.errors.some((e) => e.includes("repository root")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when knowledge.schema override escapes the repository root", async () => {
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, "docs", "knowledge"), { recursive: true });
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(dir, "docs", "knowledge", "SCHEMA.md"), "# schema\n");
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: docs/knowledge",
          "  schema: ../../etc/passwd",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("knowledge.schema")));
      assert.ok(result.errors.some((e) => e.includes("repository root")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when knowledge.dir is a symlink to an out-of-repo directory", async () => {
    const dir = makeTempRepo();
    const outside = mkdtempSync(join(tmpdir(), "gc-yaml-outside-"));
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(outside, "SCHEMA.md"), "# schema\n");
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      symlinkSync(outside, join(dir, "sneaky"));
      writeYamlConfig(dir, [
          "schema_version: 1",
          "project: test-project",
          "knowledge:",
          "  dir: sneaky",
          "",
        ]);
      const result = await getRepoGroundControlContext(dir);
      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.ok(result.errors.some((e) => e.includes("knowledge.dir")));
      assert.ok(result.errors.some((e) => /symlink|outside the repository/.test(e)));
    } finally {
      rmSync(dir, { recursive: true, force: true });
      rmSync(outside, { recursive: true, force: true });
    }
  });


  it("returns invalid_ground_control_yaml when knowledge.schema is a symlink to an out-of-repo file", async () => {
    const dir = makeTempRepo();
    const outside = mkdtempSync(join(tmpdir(), "gc-yaml-outside-"));
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, "docs", "knowledge"), { recursive: true });
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(join(outside, "secret.md"), "stolen\n");
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      symlinkSync(join(outside, "secret.md"), join(dir, "docs", "knowledge", "SCHEMA.md"));
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
      assert.ok(result.errors.some((e) => /symlink|outside the repository/.test(e)));
    } finally {
      rmSync(dir, { recursive: true, force: true });
      rmSync(outside, { recursive: true, force: true });
    }
  });
});
