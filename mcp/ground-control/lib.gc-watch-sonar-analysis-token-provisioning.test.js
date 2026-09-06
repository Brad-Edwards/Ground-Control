// The watcher's token-missing envelope (issue #946).
//
// The operator, not the agent, repairs this state, and the message is the only
// place the workflow tells them where the server looks. Naming just the variable
// left the recovery action undiscoverable on a host whose launcher strips it.

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

describe("runWatchSonarAnalysis - missing host credential", () => {
  it("names both declared sources and the restart so an operator can act on the envelope", async () => {
    const dir = makeSonarRepo();
    const original = process.env.SONAR_TOKEN;
    delete process.env.SONAR_TOKEN;
    try {
      const result = await runWatchSonarAnalysis({ repoPath: dir, prNumber: 7, initialWaitSeconds: 0 });
      assert.equal(result.ok, false);
      assert.equal(result.error, "sonar_watch_token_missing");
      assert.match(result.message, /SONAR_TOKEN/);
      assert.match(result.message, /\.env/);
      assert.match(result.message, /\.config\/ground-control\/env/);
      assert.match(result.message, /restart/i);
    } finally {
      if (original === undefined) delete process.env.SONAR_TOKEN;
      else process.env.SONAR_TOKEN = original;
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
