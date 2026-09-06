// gc-integrate.js -- barrel.
//
// The implementation lives in ./gc-integrate/*, split under issue #1467 for the
// 500-LOC limit (docs/CODING_STANDARDS.md). Modules are packed from the
// declaration dependency graph in topological order, so the module graph is
// acyclic by construction. Every name this file exported before is still
// exported here, so callers and tests keep importing one place.

export * from "./gc-integrate/exec-file-async.js";
export * from "./gc-integrate/workspace-binding.js";
export * from "./gc-integrate/run-plan-action.js";
export * from "./gc-integrate/run-prepare-action.js";
export * from "./gc-integrate/run-integration-manager.js";
