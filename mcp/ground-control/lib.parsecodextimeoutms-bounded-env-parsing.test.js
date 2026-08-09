// GC_CODEX_TIMEOUT_MS is host configuration, not repository policy. A zero,
// negative, malformed, or excessive value must never disable or effectively
// remove the wall-clock cap every codex/claude subprocess runs under
// (issue #1518).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  CODEX_TIMEOUT_MS_DEFAULT,
  CODEX_TIMEOUT_MS_MAX,
  CODEX_TIMEOUT_MS_MIN,
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
