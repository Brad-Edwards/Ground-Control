#!/usr/bin/env node
// Thin executable entry point for the detached ingest subprocess spawned
// by `gc_remember`. Parses a structured argv, delegates to `runIngest`
// in knowledge_ingest.js, and exits with a non-zero status on failure so
// the parent process (or a later sweep) can observe the outcome.
//
// This file is intentionally minimal: all the interesting logic lives in
// knowledge_ingest.js and is covered by the unit test suite that injects
// a stub `codexInvoker`. The CLI is just argv plumbing.
//
// Expected argv (all required, all absolute or repo-relative as noted):
//   --repo <absolute-repo-root>
//   --inbox-file <absolute-path-to-inbox-item>
//   --knowledge-dir <repo-relative>
//   --knowledge-schema <repo-relative>
//   --knowledge-inbox <repo-relative>

import { runIngest } from "./knowledge_ingest.js";

function parseArgv(argv) {
  const out = {};
  const expected = new Map([
    ["--repo", "repoRoot"],
    ["--inbox-file", "inboxFilePath"],
    ["--knowledge-dir", "dir"],
    ["--knowledge-schema", "schema"],
    ["--knowledge-inbox", "inbox"],
  ]);
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const key = expected.get(flag);
    if (!key) {
      throw new Error(`knowledge_ingest_cli: unknown argument '${flag}'`);
    }
    const value = argv[i + 1];
    if (value == null || value.startsWith("--")) {
      throw new Error(`knowledge_ingest_cli: missing value for '${flag}'`);
    }
    out[key] = value;
    i += 1;
  }
  for (const [flag, key] of expected) {
    if (out[key] == null) {
      throw new Error(`knowledge_ingest_cli: missing required argument '${flag}'`);
    }
  }
  return out;
}

export async function main(argv = process.argv.slice(2)) {
  let parsed;
  try {
    parsed = parseArgv(argv);
  } catch (error) {
    process.stderr.write(
      JSON.stringify({ event: "ingest_cli_argv_error", error: error.message }) + "\n",
    );
    return 2;
  }
  try {
    const result = await runIngest({
      repoRoot: parsed.repoRoot,
      inboxFilePath: parsed.inboxFilePath,
      knowledge: {
        dir: parsed.dir,
        schema: parsed.schema,
        inbox: parsed.inbox,
      },
    });
    // Success is already observable via the structured `ingest_commit`
    // log line that `runIngest` emits. Nothing more to say here.
    return result.ok ? 0 : 1;
  } catch (error) {
    process.stderr.write(
      JSON.stringify({
        event: "ingest_cli_failure",
        inbox_path: parsed.inboxFilePath,
        error: error.message,
      }) + "\n",
    );
    return 1;
  }
}

// Only run main() when this file is invoked directly, not when it is
// imported by the unit test. The import.meta.url check is the standard
// ESM pattern for "if __name__ == '__main__'".
if (import.meta.url === `file://${process.argv[1]}`) {
  main().then((code) => process.exit(code));
}
