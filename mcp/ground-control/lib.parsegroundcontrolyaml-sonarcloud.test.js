// The sonarcloud block of parseGroundControlYaml, split out of
// lib.parsegroundcontrolyaml.test.js under issue #1559 for the 500-LOC limit
// (docs/CODING_STANDARDS.md, ADR-092). Test bodies are unchanged apart from the
// analysis_check cases this issue adds.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseGroundControlYaml } from "./lib.js";

describe("parseGroundControlYaml \u2014 sonarcloud", () => {
  function parseYamlLines(lines) {
    return parseGroundControlYaml(lines.join("\n"));
  }


  it("requires both sonarcloud fields when sonarcloud is set", () => {
    const yaml = "schema_version: 1\nproject: x\nsonarcloud:\n  project_key: foo\n";
    const result = parseGroundControlYaml(yaml);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("organization")));
  });


  it("accepts optional sonarcloud.quality_gate (issue #948 / shifter aces-strict)", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: shifter",
      "sonarcloud:",
      "  project_key: Brad-Edwards_shifter",
      "  organization: brad-edwards",
      "  quality_gate: aces-strict",
      "",
    ]);
    assert.equal(result.ok, true);
    assert.equal(result.value.sonarcloud.project_key, "Brad-Edwards_shifter");
    assert.equal(result.value.sonarcloud.organization, "brad-edwards");
    assert.equal(result.value.sonarcloud.quality_gate, "aces-strict");
  });


  it("rejects sonarcloud unknown keys after quality_gate is allowlisted", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: shifter",
      "sonarcloud:",
      "  project_key: foo",
      "  organization: bar",
      "  bogus: true",
      "",
    ]);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("sonarcloud has unknown key 'bogus'")));
  });


  it("accepts optional sonarcloud.analysis_check (issue #1559 producer selector)", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: shifter",
      "sonarcloud:",
      "  project_key: foo",
      "  organization: bar",
      "  analysis_check: Quality / SonarCloud",
      "",
    ]);
    assert.equal(result.ok, true);
    assert.equal(result.value.sonarcloud.analysis_check, "Quality / SonarCloud");
  });


  it("rejects a non-string sonarcloud.analysis_check", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: shifter",
      "sonarcloud:",
      "  project_key: foo",
      "  organization: bar",
      "  analysis_check: true",
      "",
    ]);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("sonarcloud.analysis_check")));
  });


  it("rejects empty sonarcloud.quality_gate", () => {
    const result = parseYamlLines([
      "schema_version: 1",
      "project: shifter",
      "sonarcloud:",
      "  project_key: foo",
      "  organization: bar",
      "  quality_gate: ''",
      "",
    ]);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("sonarcloud.quality_gate")));
  });
});
