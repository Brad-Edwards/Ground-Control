#!/usr/bin/env node
// Test fixture for `mcp/ground-control/lib.js::classifyChangedSurface`.
//
// Reads { repo_path: string, changed_paths: string[] } as JSON on stdin.
// Prints { ok, classifications, outcome_required, suggested_doc_targets } to
// stdout as JSON.  Used by `tools/policy/checks.py::run_documentation_coverage_check`
// to classify the diff without requiring the full MCP server stack.
//
// Exit codes:
//   0  — classification JSON written to stdout
//   1  — runtime error (message on stderr)

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const libPath = resolve(__dirname, "..", "mcp", "ground-control", "lib.js");

const lib = await import(libPath);
const { classifyChangedSurface } = lib;

let raw;
try {
  raw = readFileSync(0, "utf8");
} catch (e) {
  process.stderr.write(`fixture: stdin read failed: ${e.message}\n`);
  process.exit(1);
}

let input;
try {
  input = JSON.parse(raw);
} catch (e) {
  process.stderr.write(`fixture: stdin is not valid JSON: ${e.message}\n`);
  process.exit(1);
}

const { repo_path, changed_paths } = input;
if (typeof repo_path !== "string" || !Array.isArray(changed_paths)) {
  process.stderr.write("fixture: input must have repo_path (string) and changed_paths (array)\n");
  process.exit(1);
}

try {
  const result = classifyChangedSurface(changed_paths, repo_path);
  const allTargets = result.classifications.flatMap((c) => c.doc_targets);
  const suggested_doc_targets = [...new Set(allTargets)];
  process.stdout.write(JSON.stringify({ ok: true, ...result, suggested_doc_targets }, null, 2));
} catch (e) {
  process.stderr.write(`fixture: classifyChangedSurface failed: ${e.message}\n`);
  process.exit(1);
}
