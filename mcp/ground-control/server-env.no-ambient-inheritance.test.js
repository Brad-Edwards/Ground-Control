// A launched server does not inherit a Ground Control credential (issue #1562).
//
// The loader's unit tests act on a plain object. This one spawns the real
// server over stdio with SONAR_TOKEN set in its ambient environment and a launch
// directory that does not declare it, then asks a tool that needs the token.
// Before this change the tool would have used the inherited value, which is the
// whole defect: a repository that deliberately holds no credential was handed a
// global one. gc_watch_sonar_analysis checks the token before any network call,
// so the assertion stays offline.

import { describe, it, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const ENTRY = fileURLToPath(new URL("./index.js", import.meta.url));
const AMBIENT_TOKEN = "ambient-token-that-must-not-be-used";
const created = [];

function makeLaunchDir(envFileBody) {
  const dir = mkdtempSync(join(tmpdir(), "gc-launch-"));
  created.push(dir);
  execFileSync("git", ["init", "--quiet", dir]);
  writeFileSync(
    join(dir, ".ground-control.yaml"),
    "schema_version: 1\nproject: launch-dir-fixture\nsonarcloud:\n  project_key: fixture_key\n  organization: fixture-org\n",
  );
  if (envFileBody !== null) writeFileSync(join(dir, ".env"), envFileBody, { mode: 0o600 });
  return dir;
}

async function watchSonarFrom(dir) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [ENTRY],
    cwd: dir,
    env: { PATH: process.env.PATH, HOME: process.env.HOME, SONAR_TOKEN: AMBIENT_TOKEN },
    stderr: "ignore",
  });
  const client = new Client({ name: "no-ambient-inheritance-test", version: "1.0.0" });
  await client.connect(transport);
  try {
    const result = await client.callTool({
      name: "gc_watch_sonar_analysis",
      arguments: { repo_path: dir, pr_number: 1, initial_wait_seconds: 0, total_timeout_seconds: 0 },
    });
    return JSON.parse(result.content[0].text);
  } finally {
    await client.close();
  }
}

after(() => {
  for (const dir of created) rmSync(dir, { recursive: true, force: true });
});

describe("a launched server binds its variables to the launch directory", { timeout: 60000 }, () => {
  it("refuses rather than using an inherited SONAR_TOKEN when no .env declares it", async () => {
    const envelope = await watchSonarFrom(makeLaunchDir(null));
    assert.equal(envelope.ok, false);
    assert.equal(envelope.error, "sonar_watch_token_missing");
  });

  it("refuses when a .env exists but does not declare the variable", async () => {
    const envelope = await watchSonarFrom(makeLaunchDir("GC_CODEX_REVIEW_PARALLEL=1\n"));
    assert.equal(envelope.ok, false);
    assert.equal(envelope.error, "sonar_watch_token_missing");
  });

  it("names the launch directory's .env as the one place to repair it", async () => {
    const envelope = await watchSonarFrom(makeLaunchDir(null));
    assert.ok(envelope.message.includes(".env"));
    assert.equal(envelope.message.includes(".config/ground-control"), false);
    assert.equal(envelope.message.includes(AMBIENT_TOKEN), false);
  });
});
