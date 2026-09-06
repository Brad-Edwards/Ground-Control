// Review-size defaults resolve per call, not at module-import time (issue #1562).
//
// Both were module-level IIFEs reading process.env. ESM hoists static imports,
// so that read completed before the entry point bound `<launch dir>/.env`, and a
// value declared only in that file was permanently invisible. index.js now loads
// the environment before dynamically importing the runtime, and these follow the
// per-call pattern lib/model-subprocess.js adopted for GC_CODEX_TIMEOUT_MS
// (issue #1521) so no future import edge can reintroduce the hazard.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { getDefaultCodexReviewParallel } from "./lib/api-controls.js";
import { getDefaultCodexReviewMaxDiffBytes } from "./lib/grc-legacy-compat.js";

function withEnv(name, value, run) {
  const had = Object.hasOwn(process.env, name);
  const previous = process.env[name];
  if (value === null) delete process.env[name];
  else process.env[name] = value;
  try {
    run();
  } finally {
    if (had) process.env[name] = previous;
    else delete process.env[name];
  }
}

describe("codex review defaults — resolved after the environment is bound", () => {
  it("observes a GC_CODEX_REVIEW_PARALLEL set after this module was imported", () => {
    withEnv("GC_CODEX_REVIEW_PARALLEL", "2", () => {
      assert.equal(getDefaultCodexReviewParallel(), 2);
    });
  });

  it("keeps serial review as the default and ignores an unsupported value", () => {
    withEnv("GC_CODEX_REVIEW_PARALLEL", null, () => {
      assert.equal(getDefaultCodexReviewParallel(), 1);
    });
    withEnv("GC_CODEX_REVIEW_PARALLEL", "7", () => {
      assert.equal(getDefaultCodexReviewParallel(), 1);
    });
  });

  it("observes a GC_CODEX_REVIEW_MAX_DIFF_BYTES set after this module was imported", () => {
    withEnv("GC_CODEX_REVIEW_MAX_DIFF_BYTES", "4096", () => {
      assert.equal(getDefaultCodexReviewMaxDiffBytes(), 4096);
    });
  });

  it("falls back to 256 KiB when the value is absent or not an integer", () => {
    withEnv("GC_CODEX_REVIEW_MAX_DIFF_BYTES", null, () => {
      assert.equal(getDefaultCodexReviewMaxDiffBytes(), 256 * 1024);
    });
    withEnv("GC_CODEX_REVIEW_MAX_DIFF_BYTES", "not-a-number", () => {
      assert.equal(getDefaultCodexReviewMaxDiffBytes(), 256 * 1024);
    });
  });
});
