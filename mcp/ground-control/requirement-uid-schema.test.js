// Schema-level acceptance for requirement UIDs (issue #1425).
//
// The reported failure was an MCP `-32602` raised by the published Zod/JSON
// schema BEFORE any lib.js code ran, so a lib.js-only test could not have
// caught it. These tests spawn the real MCP server, read the published
// inputSchema for every tool that takes a structured requirement UID, and
// assert the shared contract accepts an allocator-minted short UID such as
// `APP-2` while still refusing unbounded or multi-value input.

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const DIR = fileURLToPath(new URL(".", import.meta.url));

// Tool → JSON-Pointer-ish path to the schema node holding the UID string.
// These are the surfaces the issue named plus the direct read and the
// mechanical workflow entry point, which share one input contract.
const UID_SCHEMA_SITES = {
  gc_assert_traceability_reconciled: ["requirements", "items", "properties", "uid"],
  gc_post_final_report: ["requirements", "items", "properties", "uid"],
  gc_assert_completion: ["requirements", "items", "properties", "uid"],
  gc_render_pr_body: ["requirement_uids", "items"],
  gc_get_requirement: ["uid"],
  gc_implement_mechanical: ["requested_requirement_uid"],
};

// Shared regression corpus. Accepted values are legal *inputs*; whether they
// identify a requirement is the project-scoped backend lookup's decision.
const ACCEPTED = ["APP-2", "APP-9", "A-1", "PLAT-10", "GC-O007", "GC-O-007", "OBS-042"];

// A UID-shaped token that is over the backend's 50-character bound. It must be
// rejected for LENGTH, not for shape — a negative case that fails on syntax
// (say, 51 bare letters) would pass whether or not the bound is enforced and so
// proves nothing about boundedness.
const OVER_BOUND_UID_SHAPED = `${"A".repeat(49)}-1`; // 51 characters
const AT_BOUND_UID_SHAPED = `${"A".repeat(48)}-1`; // exactly 50 characters

const REJECTED = [
  "",
  " APP-2 ",
  "not really GC-O007",
  "APP-2 APP-3",
  OVER_BOUND_UID_SHAPED,
  "A".repeat(51),
  "<!-- gc:final-report -->",
];

/** Walk a JSON Schema down a property path, unwrapping array `items` nodes. */
function resolveSchemaNode(schema, path) {
  let node = schema?.properties ?? {};
  let current = node;
  for (const segment of path) {
    if (current == null) return null;
    current = current[segment];
  }
  return current ?? null;
}

/**
 * Build a predicate from the published schema node. A UID field is a bounded
 * string: `pattern` carries the shape and `maxLength` the bound. Both are
 * enforced by the MCP layer before the tool body runs.
 */
function schemaAccepts(node, value) {
  if (typeof value !== "string") return false;
  if (node.minLength != null && value.length < node.minLength) return false;
  if (node.maxLength != null && value.length > node.maxLength) return false;
  // The pattern comes from this server's own published inputSchema, which is
  // exactly what is under test; it is not caller- or network-supplied.
  // eslint-disable-next-line security/detect-non-literal-regexp
  if (node.pattern != null && !new RegExp(node.pattern).test(value)) return false;
  // A string schema with neither a pattern nor a bound accepts anything —
  // that is the divergence issue #1425 reported for gc_get_requirement.
  return node.pattern != null || node.maxLength != null;
}

describe("requirement UID schema contract (issue #1425)", { timeout: 30000 }, () => {
  let client;
  let transport;
  let schemaMap;

  before(async () => {
    transport = new StdioClientTransport({
      command: process.execPath,
      args: ["index.js"],
      cwd: DIR,
      stderr: "ignore",
    });
    client = new Client({ name: "uid-schema-test", version: "1.0.0" });
    await client.connect(transport);
    const { tools } = await client.listTools();
    schemaMap = Object.fromEntries(tools.map((t) => [t.name, t.inputSchema]));
  });

  after(async () => {
    if (client) await client.close();
  });

  for (const [toolName, path] of Object.entries(UID_SCHEMA_SITES)) {
    describe(toolName, () => {
      it("publishes a bounded UID schema node", () => {
        const schema = schemaMap[toolName];
        assert.ok(schema, `tool '${toolName}' not found in listTools()`);
        const node = resolveSchemaNode(schema, path);
        assert.ok(node, `could not resolve UID schema node at ${path.join(".")}`);
        assert.equal(node.type, "string", "UID field must be a string");
        // Boundedness is proven by rejecting an over-bound value, not by the
        // mere presence of a `pattern` key — a pattern with no length limit
        // would satisfy that weaker check while leaving the field unbounded.
        assert.ok(
          !schemaAccepts(node, OVER_BOUND_UID_SHAPED),
          "UID field must reject a value past the backend's 50-character bound",
        );
      });

      it("accepts allocator-minted and explicit UIDs", () => {
        const node = resolveSchemaNode(schemaMap[toolName], path);
        for (const uid of ACCEPTED) {
          assert.ok(schemaAccepts(node, uid), `${toolName} should accept '${uid}'`);
        }
      });

      it("rejects unbounded, empty, and multi-value input", () => {
        const node = resolveSchemaNode(schemaMap[toolName], path);
        for (const bad of REJECTED) {
          assert.ok(!schemaAccepts(node, bad), `${toolName} should reject '${bad}'`);
        }
      });
    });
  }

  it("every UID site publishes the identical contract", () => {
    // One shared corpus, not per-tool copies. A divergent site is how the
    // reported inconsistency between gc_get_requirement and the completion
    // tools arose, and a renderer-only narrowing would mean a UID that
    // reconciles and reports cannot be rendered into the mandatory PR body.
    const contracts = Object.entries(UID_SCHEMA_SITES).map(([toolName, path]) => {
      const node = resolveSchemaNode(schemaMap[toolName], path);
      return `${node?.pattern ?? "<none>"}|${node?.maxLength ?? "<none>"}`;
    });
    assert.equal(new Set(contracts).size, 1, `UID contracts diverge: ${contracts.join(" , ")}`);
  });

  it("enforces the 50-character bound on a UID-shaped value at every site", () => {
    // The bound has to be exercised by a value that is otherwise well-formed;
    // a negative case that fails on shape would pass with or without it.
    for (const [toolName, path] of Object.entries(UID_SCHEMA_SITES)) {
      const node = resolveSchemaNode(schemaMap[toolName], path);
      assert.ok(
        schemaAccepts(node, AT_BOUND_UID_SHAPED),
        `${toolName} should accept a 50-character UID`,
      );
      assert.ok(
        !schemaAccepts(node, OVER_BOUND_UID_SHAPED),
        `${toolName} should reject a 51-character UID`,
      );
    }
  });

  it("accepts backend-storable identifiers at every site", () => {
    // Identity is the project-scoped lookup's decision, so these are legal
    // inputs everywhere or nowhere; a site that refuses them would foreclose a
    // requirement that Ground Control can store and resolve.
    for (const [toolName, path] of Object.entries(UID_SCHEMA_SITES)) {
      const node = resolveSchemaNode(schemaMap[toolName], path);
      for (const uid of ["GC-OOPS", "lowercase-001", "GC_O007"]) {
        assert.ok(schemaAccepts(node, uid), `${toolName} should accept '${uid}'`);
      }
    }
  });
});
