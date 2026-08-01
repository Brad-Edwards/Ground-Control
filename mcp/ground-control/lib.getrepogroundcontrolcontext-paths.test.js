// Split from lib.getrepogroundcontrolcontext.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { getRepoGroundControlContext } from "./lib.js";

describe("getRepoGroundControlContext path containment (ADR-027)", () => {
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

  it("refuses a plan_rules path that escapes the repository root (issue #1355)", async () => {
    // The field went straight into join() with only a comment claiming containment, and the file it
    // names is read and returned in the response. Repository content is attacker-controlled whenever
    // the workflow runs against an untrusted branch, so this was an arbitrary host-file read.
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(
        join(dir, ".ground-control.yaml"),
        "schema_version: 1\nproject: test-project\nrules:\n  plan_rules: ../../../../etc/passwd\n",
      );

      const result = await getRepoGroundControlContext(dir);

      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.match(result.errors[0], /rules\.plan_rules/);
      assert.equal(result.rules, undefined);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses a plan_rules path that escapes through a symlink", async () => {
    // Lexical containment alone passes a path that stays inside the tree and then leaves it via a
    // symlink, which is why the sibling docs fields do both checks.
    const dir = makeTempRepo();
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      mkdirSync(join(dir, ".gc"), { recursive: true });
      symlinkSync("/etc/passwd", join(dir, ".gc", "plan-rules.md"));
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- test-controlled temp dir
      writeFileSync(
        join(dir, ".ground-control.yaml"),
        "schema_version: 1\nproject: test-project\nrules:\n  plan_rules: .gc/plan-rules.md\n",
      );

      const result = await getRepoGroundControlContext(dir);

      assert.equal(result.status, "invalid_ground_control_yaml");
      assert.match(result.errors[0], /rules\.plan_rules/);
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
