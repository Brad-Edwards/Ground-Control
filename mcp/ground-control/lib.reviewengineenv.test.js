// Review-engine auth is declared in the launch directory's .env, or the review
// does not run (issue #1562).
//
// The engine used to load `~/.config/ground-control/review-env` whenever the
// inherited environment carried no Claude auth. That is a user-level file
// serving a per-checkout process: it silently supplied a global credential to a
// repository that deliberately has none, and it hid a provisioning fault behind
// an expired default profile. Those are just variables and they belong in
// `.env` like every other variable, so the fallback is gone and its absence is
// a refusal that names what to set and where.
//
// The conflict rule survives: ANTHROPIC_API_KEY is stripped only when another
// auth path exists, so the key can still be the sole auth when it is all that
// is declared (issue #1500).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { REVIEW_ENGINE_AUTH_VARS, assertReviewEngineAuth, reviewEngineEnv } from "./lib/runtime-primitives.js";

describe("reviewEngineEnv — review-engine auth follows the declared mode", () => {
  it("strips ANTHROPIC_API_KEY when Vertex is the auth mode", () => {
    const env = reviewEngineEnv({ CLAUDE_CODE_USE_VERTEX: "1", GOOGLE_CLOUD_PROJECT: "p", ANTHROPIC_API_KEY: "sk-x" });
    assert.equal(env.ANTHROPIC_API_KEY, undefined);
    assert.equal(env.CLAUDE_CODE_USE_VERTEX, "1");
  });

  it("strips ANTHROPIC_API_KEY when Bedrock is the auth mode", () => {
    const env = reviewEngineEnv({ CLAUDE_CODE_USE_BEDROCK: "1", ANTHROPIC_API_KEY: "sk-x" });
    assert.equal(env.ANTHROPIC_API_KEY, undefined);
  });

  it("strips ANTHROPIC_API_KEY when a dedicated CLAUDE_CONFIG_DIR profile is present", () => {
    const env = reviewEngineEnv({ CLAUDE_CONFIG_DIR: "/home/u/.claude-personal", ANTHROPIC_API_KEY: "sk-x" });
    assert.equal(env.ANTHROPIC_API_KEY, undefined);
    assert.equal(env.CLAUDE_CONFIG_DIR, "/home/u/.claude-personal");
  });

  it("KEEPS ANTHROPIC_API_KEY when it is the only auth present", () => {
    const env = reviewEngineEnv({ ANTHROPIC_API_KEY: "sk-x", PATH: "/usr/bin" });
    assert.equal(env.ANTHROPIC_API_KEY, "sk-x");
  });

  it("passes OS execution state through to the child", () => {
    const env = reviewEngineEnv({ CLAUDE_CODE_USE_VERTEX: "1", PATH: "/usr/bin", HOME: "/home/u" });
    assert.equal(env.PATH, "/usr/bin");
    assert.equal(env.HOME, "/home/u");
  });

  it("does not mutate the input environment", () => {
    const base = { CLAUDE_CODE_USE_VERTEX: "1", ANTHROPIC_API_KEY: "sk-x" };
    reviewEngineEnv(base);
    assert.equal(base.ANTHROPIC_API_KEY, "sk-x");
  });

  it("refuses when no auth mode is declared, rather than falling through to ambient auth", () => {
    assert.throws(
      () => reviewEngineEnv({ PATH: "/usr/bin", HOME: "/home/u" }),
      (err) => err.code === "review_engine_auth_missing",
    );
  });

  it("treats an empty declared value as no auth", () => {
    assert.throws(
      () => assertReviewEngineAuth({ CLAUDE_CONFIG_DIR: "" }),
      (err) => err.code === "review_engine_auth_missing",
    );
  });

  it("names every accepted variable and the launch-directory .env in the refusal", () => {
    try {
      assertReviewEngineAuth({ PATH: "/usr/bin" });
      assert.fail("expected a refusal");
    } catch (err) {
      for (const name of REVIEW_ENGINE_AUTH_VARS) {
        assert.ok(err.message.includes(name), `message must name ${name}`);
      }
      assert.ok(err.message.includes(".env"), "message must name the file to fix");
    }
  });

  it("never puts a declared value in the refusal message", () => {
    try {
      assertReviewEngineAuth({ ANTHROPIC_API_KEY: "", SONAR_TOKEN: "squirrel-token" });
      assert.fail("expected a refusal");
    } catch (err) {
      assert.equal(err.message.includes("squirrel-token"), false);
    }
  });
});
