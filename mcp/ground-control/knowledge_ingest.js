// knowledge_ingest.js -- barrel.
//
// The implementation lives in ./knowledge_ingest/*, split under issue #1467 for the
// 500-LOC limit (docs/CODING_STANDARDS.md). Modules are packed from the
// declaration dependency graph in topological order, so the module graph is
// acyclic by construction. Every name this file exported before is still
// exported here, so callers and tests keep importing one place.

export * from "./knowledge_ingest/exec-file.js";
export * from "./knowledge_ingest/run-ingest.js";
