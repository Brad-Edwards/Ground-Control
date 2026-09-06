// Startup environment provisioning for the MCP server (issue #946).
//
// A launcher may hand the server a core-only environment: a Codex-spawned host
// carries eight variables, none of them SONAR_TOKEN, so gc_watch_sonar_analysis
// could not run at all in a repo that declares a `sonarcloud:` block. The server
// therefore reads its own declared sources instead of depending on what the
// parent process chose to pass down.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { hostEnvFilePath, loadServerEnvFiles } from "./lib/host-env.js";

function makeHome(body) {
  const home = mkdtempSync(join(tmpdir(), "gc-host-env-home-"));
  if (body !== null) {
    mkdirSync(join(home, ".config", "ground-control"), { recursive: true });
    writeFileSync(join(home, ".config", "ground-control", "env"), body, { mode: 0o600 });
  }
  return home;
}

function makeCwd(body) {
  const cwd = mkdtempSync(join(tmpdir(), "gc-host-env-cwd-"));
  if (body !== null) writeFileSync(join(cwd, ".env"), body, { mode: 0o600 });
  return cwd;
}

describe("loadServerEnvFiles — the server resolves its own configuration", () => {
  it("supplies a variable the launcher stripped, from the host env file", () => {
    const home = makeHome("SONAR_TOKEN=from-host-file\n");
    const cwd = makeCwd(null);
    try {
      const env = {};
      loadServerEnvFiles(env, { cwd, homeDir: home });
      assert.equal(env.SONAR_TOKEN, "from-host-file");
    } finally {
      rmSync(home, { recursive: true, force: true });
      rmSync(cwd, { recursive: true, force: true });
    }
  });

  it("prefers the launch-root .env over the host env file", () => {
    const home = makeHome("SONAR_TOKEN=from-host-file\n");
    const cwd = makeCwd("SONAR_TOKEN=from-launch-root\n");
    try {
      const env = {};
      loadServerEnvFiles(env, { cwd, homeDir: home });
      assert.equal(env.SONAR_TOKEN, "from-launch-root");
    } finally {
      rmSync(home, { recursive: true, force: true });
      rmSync(cwd, { recursive: true, force: true });
    }
  });

  it("keeps an inherited value over both files", () => {
    const home = makeHome("SONAR_TOKEN=from-host-file\n");
    const cwd = makeCwd("SONAR_TOKEN=from-launch-root\n");
    try {
      const env = { SONAR_TOKEN: "from-shell" };
      loadServerEnvFiles(env, { cwd, homeDir: home });
      assert.equal(env.SONAR_TOKEN, "from-shell");
    } finally {
      rmSync(home, { recursive: true, force: true });
      rmSync(cwd, { recursive: true, force: true });
    }
  });

  it("treats an empty inherited value as absent", () => {
    const home = makeHome("SONAR_TOKEN=from-host-file\n");
    const cwd = makeCwd(null);
    try {
      const env = { SONAR_TOKEN: "" };
      loadServerEnvFiles(env, { cwd, homeDir: home });
      assert.equal(env.SONAR_TOKEN, "from-host-file");
    } finally {
      rmSync(home, { recursive: true, force: true });
      rmSync(cwd, { recursive: true, force: true });
    }
  });

  it("is a no-op when neither file exists", () => {
    const home = makeHome(null);
    const cwd = makeCwd(null);
    try {
      const env = { PATH: "/usr/bin" };
      loadServerEnvFiles(env, { cwd, homeDir: home });
      assert.deepEqual(env, { PATH: "/usr/bin" });
    } finally {
      rmSync(home, { recursive: true, force: true });
      rmSync(cwd, { recursive: true, force: true });
    }
  });

  it("skips comments and blank lines and strips one matching quote pair", () => {
    const home = makeHome("# a comment\n\nGC_BASE_URL='https://example.invalid'\nGC_CODEX_REVIEW_PARALLEL=\"1\"\nnot-a-pair\n");
    const cwd = makeCwd(null);
    try {
      const env = {};
      loadServerEnvFiles(env, { cwd, homeDir: home });
      assert.equal(env.GC_BASE_URL, "https://example.invalid");
      assert.equal(env.GC_CODEX_REVIEW_PARALLEL, "1");
      assert.equal(Object.keys(env).length, 2);
    } finally {
      rmSync(home, { recursive: true, force: true });
      rmSync(cwd, { recursive: true, force: true });
    }
  });

  it("resolves the host env file under the Ground Control host-config directory", () => {
    assert.equal(hostEnvFilePath("/home/u"), join("/home/u", ".config", "ground-control", "env"));
  });
});
