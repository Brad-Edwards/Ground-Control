// The codex child inherited the FULL parent environment ({...process.env}),
// forwarding unrelated GitHub, Sonar, cloud, and Claude credentials into a
// sandbox whose reads are not confined (issue #1518 preflight guardrail).
// codexEngineEnv() allowlists only what `codex exec` needs.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { codexEngineEnv } from "./lib/codex-engine-env.js";

describe("codexEngineEnv", () => {
  it("strips unrelated host credentials", () => {
    const env = codexEngineEnv({
      HOME: "/home/u",
      PATH: "/usr/bin",
      GH_TOKEN: "gh-secret",
      SONAR_TOKEN: "sonar-secret",
      AWS_SECRET_ACCESS_KEY: "aws-secret",
      ANTHROPIC_API_KEY: "claude-secret",
      CLAUDE_CONFIG_DIR: "/home/u/.claude-personal",
    });
    assert.equal(env.GH_TOKEN, undefined);
    assert.equal(env.SONAR_TOKEN, undefined);
    assert.equal(env.AWS_SECRET_ACCESS_KEY, undefined);
    assert.equal(env.ANTHROPIC_API_KEY, undefined);
    assert.equal(env.CLAUDE_CONFIG_DIR, undefined);
  });

  it("preserves the runtime and auth inputs codex needs", () => {
    const env = codexEngineEnv({
      HOME: "/home/u",
      PATH: "/usr/bin:/usr/local/bin",
      OPENAI_API_KEY: "sk-x",
      CODEX_HOME: "/home/u/.codex",
    });
    assert.equal(env.HOME, "/home/u");
    assert.equal(env.PATH, "/usr/bin:/usr/local/bin");
    assert.equal(env.OPENAI_API_KEY, "sk-x");
    assert.equal(env.CODEX_HOME, "/home/u/.codex");
  });

  it("omits an allowlisted key entirely when absent from the source env, rather than forwarding undefined", () => {
    const env = codexEngineEnv({ HOME: "/home/u", PATH: "/usr/bin" });
    assert.equal("OPENAI_API_KEY" in env, false);
    assert.equal("CODEX_HOME" in env, false);
  });

  it("always sets NO_COLOR, overriding any inherited value", () => {
    const env = codexEngineEnv({ HOME: "/home/u", PATH: "/usr/bin", NO_COLOR: "0" });
    assert.equal(env.NO_COLOR, "1");
  });

  it("defaults to process.env when no source is given", () => {
    const env = codexEngineEnv();
    assert.equal(env.NO_COLOR, "1");
  });
});
