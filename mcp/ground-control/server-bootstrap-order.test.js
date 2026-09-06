// The entry point must bind the environment before the runtime graph evaluates
// (issue #1562).
//
// ESM hoists static imports, so every module in the graph evaluates before the
// importing module's own body runs. While index.js both statically imported the
// runtime and called the loader in its body, any module-level `process.env`
// read ran first — which is exactly how DEFAULT_CODEX_REVIEW_PARALLEL and
// DEFAULT_CODEX_REVIEW_MAX_DIFF_BYTES came to permanently miss a value that
// lived only in `.env`. lib/model-subprocess.js records the same hazard for
// GC_CODEX_TIMEOUT_MS (issue #1521).
//
// Source order below the imports is not an ordering contract; a dynamic import
// is. These cases pin that seam, because a loader unit test cannot see it.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const ENTRY = fileURLToPath(new URL("./index.js", import.meta.url));
const source = readFileSync(ENTRY, "utf8");

const STATIC_IMPORT_RE = /^import\s[^;]*?from\s+["']([^"']+)["']/gm;

function staticImportSpecifiers(text) {
  return [...text.matchAll(STATIC_IMPORT_RE)].map((m) => m[1]);
}

describe("index.js — environment binding precedes the runtime graph", () => {
  it("statically imports only the leaf loader and node builtins", () => {
    for (const specifier of staticImportSpecifiers(source)) {
      const allowed = specifier === "./lib/server-env.js" || specifier.startsWith("node:");
      assert.ok(
        allowed,
        `index.js must not statically import ${specifier}: it would evaluate before loadServerEnv runs`,
      );
    }
  });

  it("loads the launch-directory .env before dynamically importing the runtime", () => {
    const loaderCall = source.indexOf("loadServerEnv(process.env)");
    const runtimeImport = source.indexOf('import("./server-runtime.js")');
    assert.ok(loaderCall !== -1, "index.js must call loadServerEnv(process.env)");
    assert.ok(runtimeImport !== -1, "index.js must import ./server-runtime.js dynamically");
    assert.ok(loaderCall < runtimeImport, "loadServerEnv must run before the runtime is imported");
  });

  it("keeps the tool registrations in the runtime module the loader guards", () => {
    const runtime = readFileSync(fileURLToPath(new URL("./server-runtime.js", import.meta.url)), "utf8");
    for (const register of [
      "registerQuery",
      "registerPostDecisionRecord",
      "registerReviewCapDisposition",
      "registerPrReview",
      "registerIntegrate",
    ]) {
      assert.ok(runtime.includes(`${register}(server)`), `${register} must be called in server-runtime.js`);
      assert.ok(!source.includes(`${register}(server)`), `${register} must not be called in index.js`);
    }
  });
});
