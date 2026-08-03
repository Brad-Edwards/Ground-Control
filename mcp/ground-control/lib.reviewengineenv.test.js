// The review engine (`claude`) inherits the launcher's environment minus
// ANTHROPIC_API_KEY — but only when another auth path survives (Vertex/Bedrock
// or a dedicated CLAUDE_CONFIG_DIR profile). If the key is the only auth, it is
// kept, so the review runs across any repo/folder/tmux session and whichever
// auth mode (Vertex or a personal profile) launched the MCP, instead of falling
// through to an expired default OAuth profile (issue #1500).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { reviewEngineEnv } from "./lib/runtime-primitives.js";

describe("reviewEngineEnv — review-engine auth follows the launch mode", () => {
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

  it("is a no-op on the key when there is no key at all", () => {
    const env = reviewEngineEnv({ CLAUDE_CODE_USE_VERTEX: "1" });
    assert.equal("ANTHROPIC_API_KEY" in env, false);
  });

  it("does not mutate the input environment", () => {
    const base = { CLAUDE_CODE_USE_VERTEX: "1", ANTHROPIC_API_KEY: "sk-x" };
    reviewEngineEnv(base);
    assert.equal(base.ANTHROPIC_API_KEY, "sk-x");
  });
});
