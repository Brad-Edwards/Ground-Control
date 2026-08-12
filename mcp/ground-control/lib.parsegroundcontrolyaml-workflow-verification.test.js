// workflow.verification.toolchain_fingerprint_command is the single narrow
// config seam for binding non-tree inputs into the verification attestation
// (issue #1497). It parses through the canonical strict YAML path and fails
// closed on any malformed shape; a repo without the block keeps current
// behavior and earns no reuse.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseGroundControlYaml } from "./lib.js";

function parse(lines) {
  return parseGroundControlYaml(["schema_version: 1", "project: x", ...lines, ""].join("\n"));
}

describe("parseGroundControlYaml workflow.verification", () => {
  it("flows a valid toolchain_fingerprint_command through to value.verification", () => {
    const result = parse([
      "workflow:",
      "  verification:",
      "    toolchain_fingerprint_command: node --version | shasum -a 256",
    ]);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.value.workflow.verification, {
      toolchain_fingerprint_command: "node --version | shasum -a 256",
    });
  });

  it("defaults to a null command when the block is absent", () => {
    const result = parse(["workflow:", "  base_branch: dev"]);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.value.workflow.verification, { toolchain_fingerprint_command: null });
  });

  it("rejects an unknown key inside verification", () => {
    const result = parse(["workflow:", "  verification:", "    bogus: true"]);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("workflow.verification has unknown key 'bogus'")));
  });

  it("rejects a scalar in place of the verification mapping", () => {
    const result = parse(["workflow:", "  verification: nope"]);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("workflow.verification must be a mapping")));
  });

  it("rejects an empty toolchain_fingerprint_command", () => {
    const result = parse(["workflow:", "  verification:", '    toolchain_fingerprint_command: ""']);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("toolchain_fingerprint_command must be a non-empty string")));
  });
});
