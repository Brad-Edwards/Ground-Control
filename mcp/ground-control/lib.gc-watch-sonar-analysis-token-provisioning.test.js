// The watcher's token-missing envelope (issues #946, #1562).
//
// The operator, not the agent, repairs this state, and the message is the only
// place the workflow tells them where the server looks. It used to name a
// per-host `~/.config/ground-control/env` alongside the launch root; that file
// is gone, and naming it would send an operator to provision a location nothing
// reads (issue #1562). One source, named once.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runWatchSonarAnalysis } from "./lib/sonar-watcher.js";

function makeSonarRepo() {
  const dir = mkdtempSync(join(tmpdir(), "gc-sonar-token-"));
  execFileSync("git", ["-C", dir, "init", "-q"]);
  writeFileSync(
    join(dir, ".ground-control.yaml"),
    "schema_version: 1\nproject: test\nsonarcloud:\n  project_key: test_key\n  organization: test_org\n",
  );
  return dir;
}

async function tokenMissingEnvelope() {
  const dir = makeSonarRepo();
  const original = process.env.SONAR_TOKEN;
  delete process.env.SONAR_TOKEN;
  try {
    return await runWatchSonarAnalysis({ repoPath: dir, prNumber: 7, initialWaitSeconds: 0 });
  } finally {
    if (original === undefined) delete process.env.SONAR_TOKEN;
    else process.env.SONAR_TOKEN = original;
    rmSync(dir, { recursive: true, force: true });
  }
}

describe("runWatchSonarAnalysis - missing host credential", () => {
  it("names the variable, the launch directory's .env, and the restart", async () => {
    const result = await tokenMissingEnvelope();
    assert.equal(result.ok, false);
    assert.equal(result.error, "sonar_watch_token_missing");
    assert.match(result.message, /SONAR_TOKEN/);
    assert.match(result.message, /\.env/);
    assert.match(result.message, /restart/i);
  });

  it("does not send the operator to a machine-level file that nothing reads", async () => {
    const result = await tokenMissingEnvelope();
    assert.equal(result.message.includes(".config/ground-control"), false);
  });
});
