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


  describe("short_code", () => {
    it("parses short_code: GC", () => {
      const result = parseYamlLines([
        "schema_version: 1",
        "project: x",
        "short_code: GC",
        "",
      ]);
      assert.equal(result.ok, true);
      assert.equal(result.value.short_code, "GC");
    });

    it("parses short_code: GC1", () => {
      const result = parseYamlLines([
        "schema_version: 1",
        "project: x",
        "short_code: GC1",
        "",
      ]);
      assert.equal(result.ok, true);
      assert.equal(result.value.short_code, "GC1");
    });

    it("parses short_code: ABCD1234 (8 chars)", () => {
      const result = parseYamlLines([
        "schema_version: 1",
        "project: x",
        "short_code: ABCD1234",
        "",
      ]);
      assert.equal(result.ok, true);
      assert.equal(result.value.short_code, "ABCD1234");
    });

    it("parses short_code: A (single uppercase letter)", () => {
      const result = parseYamlLines([
        "schema_version: 1",
        "project: x",
        "short_code: A",
        "",
      ]);
      assert.equal(result.ok, true);
      assert.equal(result.value.short_code, "A");
    });

    it("returns short_code: null when short_code is absent", () => {
      const result = parseYamlLines([
        "schema_version: 1",
        "project: x",
        "",
      ]);
      assert.equal(result.ok, true);
      assert.equal(result.value.short_code, null);
    });

    it("rejects short_code: empty string", () => {
      expectYamlError([
        "schema_version: 1",
        "project: x",
        'short_code: ""',
        "",
      ], "short_code");
    });

    it("rejects short_code: gc (lowercase)", () => {
      expectYamlError([
        "schema_version: 1",
        "project: x",
        "short_code: gc",
        "",
      ], "short_code");
    });

    it("rejects short_code with embedded space", () => {
      expectYamlError([
        "schema_version: 1",
        "project: x",
        'short_code: "GC 1"',
        "",
      ], "short_code");
    });

    it("rejects short_code with special character", () => {
      expectYamlError([
        "schema_version: 1",
        "project: x",
        'short_code: "GC!"',
        "",
      ], "short_code");
    });

    it("rejects short_code: ABCDE1234 (9 chars — too long)", () => {
      expectYamlError([
        "schema_version: 1",
        "project: x",
        "short_code: ABCDE1234",
        "",
      ], "short_code");
    });
    it("rejects short_code: 1GC (starts with digit)", () => {
      expectYamlError([
        "schema_version: 1",
        "project: x",
        "short_code: 1GC",
        "",
      ], "short_code");
    });
  });
});
