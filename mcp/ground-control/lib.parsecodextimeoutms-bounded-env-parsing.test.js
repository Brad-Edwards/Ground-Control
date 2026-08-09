// GC_CODEX_TIMEOUT_MS is host configuration, not repository policy. A zero,
// negative, malformed, or excessive value must never disable or effectively
// remove the wall-clock cap every codex/claude subprocess runs under
// (issue #1518).

import { afterEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  CODEX_TIMEOUT_MS_DEFAULT,
  CODEX_TIMEOUT_MS_MAX,
  CODEX_TIMEOUT_MS_MIN,
  getDefaultCodexTimeoutMs,
  parseCodexTimeoutMs,
} from "./lib/runtime-primitives.js";

describe("parseCodexTimeoutMs", () => {
  it("falls back to the default when unset", () => {
    assert.equal(parseCodexTimeoutMs(undefined), CODEX_TIMEOUT_MS_DEFAULT);
    assert.equal(parseCodexTimeoutMs(""), CODEX_TIMEOUT_MS_DEFAULT);
    assert.equal(parseCodexTimeoutMs("   "), CODEX_TIMEOUT_MS_DEFAULT);
  });

  it("uses a valid in-range value as-is", () => {
    assert.equal(parseCodexTimeoutMs("60000"), 60000);
  });

  it("falls back to the default on malformed input", () => {
    assert.equal(parseCodexTimeoutMs("abc"), CODEX_TIMEOUT_MS_DEFAULT);
    assert.equal(parseCodexTimeoutMs("12.5"), CODEX_TIMEOUT_MS_DEFAULT);
  });

  it("falls back to the default on zero (does not disable the cap)", () => {
    assert.equal(parseCodexTimeoutMs("0"), CODEX_TIMEOUT_MS_DEFAULT);
  });

  it("falls back to the default on a negative value (does not disable the cap)", () => {
    assert.equal(parseCodexTimeoutMs("-5000"), CODEX_TIMEOUT_MS_DEFAULT);
  });

  it("falls back to the default on an excessive value", () => {
    assert.equal(parseCodexTimeoutMs(String(CODEX_TIMEOUT_MS_MAX + 1)), CODEX_TIMEOUT_MS_DEFAULT);
    assert.equal(parseCodexTimeoutMs("99999999999"), CODEX_TIMEOUT_MS_DEFAULT);
  });

  it("accepts the exact MIN and MAX bounds", () => {
    assert.equal(parseCodexTimeoutMs(String(CODEX_TIMEOUT_MS_MIN)), CODEX_TIMEOUT_MS_MIN);
    assert.equal(parseCodexTimeoutMs(String(CODEX_TIMEOUT_MS_MAX)), CODEX_TIMEOUT_MS_MAX);
  });
});

describe("getDefaultCodexTimeoutMs", () => {
  const ORIGINAL = process.env.GC_CODEX_TIMEOUT_MS;

  afterEach(() => {
    if (ORIGINAL === undefined) delete process.env.GC_CODEX_TIMEOUT_MS;
    else process.env.GC_CODEX_TIMEOUT_MS = ORIGINAL;
  });

  // Regression guard for issue #1521: the value must be resolved on every
  // call, not captured once at module-import time — an import-time capture
  // would run before index.js's loadDotenvFromCwd() (or any other startup
  // env-config loader) has a chance to populate GC_CODEX_TIMEOUT_MS, so a
  // .env-only value would be silently ignored forever.
  it("reflects a GC_CODEX_TIMEOUT_MS value set after this module was already imported", () => {
    delete process.env.GC_CODEX_TIMEOUT_MS;
    assert.equal(getDefaultCodexTimeoutMs(), CODEX_TIMEOUT_MS_DEFAULT);

    process.env.GC_CODEX_TIMEOUT_MS = "60000";
    assert.equal(getDefaultCodexTimeoutMs(), 60000);

    process.env.GC_CODEX_TIMEOUT_MS = "120000";
    assert.equal(getDefaultCodexTimeoutMs(), 120000);
  });
});
