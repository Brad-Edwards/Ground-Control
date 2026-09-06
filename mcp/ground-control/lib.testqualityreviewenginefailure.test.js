// A review-engine auth fault is a provisioning fault, not a non-verdict retry
// (issue #1562).
//
// lib/review-reattempt.js spends one free retry on a station that produced no
// verdict — a timed-out engine, a dead child, an unparseable payload — and says
// in as many words that input and authorization failures are excluded, because
// a retry cannot change them. Undeclared auth is exactly that: `.env` will not
// have grown a variable between two attempts one second apart. It gets its own
// stable code so the classifier leaves it alone and the operator reads a
// message that names what to set.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { NON_VERDICT_FAILURE_CLASSES, classifyStationAttempt } from "./lib/review-reattempt.js";
import { REVIEW_ENGINE_AUTH_MISSING } from "./lib/runtime-primitives.js";
import { testQualityReviewEngineFailure } from "./lib/test-quality-runner-2.js";

function authError(message = "No review-engine auth is declared. Set one of ... in the launch directory's .env") {
  const err = new Error(message);
  err.code = REVIEW_ENGINE_AUTH_MISSING;
  return err;
}

describe("testQualityReviewEngineFailure — auth faults are separated from engine faults", () => {
  it("returns the auth code and the operator-directed recovery for a missing declaration", () => {
    const fields = testQualityReviewEngineFailure(authError());
    assert.equal(fields.error, "test_quality_review_auth_missing");
    assert.equal(fields.next_action, "provision_review_engine_auth_in_launch_root_env");
    assert.ok(fields.message.includes(".env"));
  });

  it("passes the refusal message through without a misleading CLI-failure prefix", () => {
    const fields = testQualityReviewEngineFailure(authError("set CLAUDE_CONFIG_DIR in .env"));
    assert.equal(fields.message, "set CLAUDE_CONFIG_DIR in .env");
  });

  it("keeps the engine-failure code for every other invocation failure", () => {
    const fields = testQualityReviewEngineFailure(new Error("spawn ENOENT"));
    assert.equal(fields.error, "test_quality_review_engine_failed");
    assert.equal(fields.next_action, "fix_engine_issue_and_retry");
    assert.ok(fields.message.includes("spawn ENOENT"));
  });

  it("is not classified as a free non-verdict retry", () => {
    const { error } = testQualityReviewEngineFailure(authError());
    assert.equal(error in NON_VERDICT_FAILURE_CLASSES, false);
    const verdict = classifyStationAttempt({ ok: false, error });
    assert.equal(verdict.retryable, false);
    assert.equal(verdict.evaluable, false);
  });

  it("still grants the free retry to a genuine engine failure", () => {
    const { error } = testQualityReviewEngineFailure(new Error("timed out"));
    assert.equal(classifyStationAttempt({ ok: false, error }).retryable, true);
  });
});
