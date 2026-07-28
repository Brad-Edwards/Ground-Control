// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { runLogStepTelemetry } from "./lib.js";

describe("runLogStepTelemetry (telemetry.enabled opt-in gate — F4 fix)", () => {
  function makeTempRepo({ telemetryEnabled }) {
    const dir = mkdtempSync(join(tmpdir(), "gc-tel-gate-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
    execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
    writeFileSync(join(dir, "README"), "x\n");
    execFileSync("git", ["-C", dir, "add", "README"]);
    execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
    // Real origin so owner/repo resolves from the git remote, as production does. git ignores
    // GH_REPO; the `gh repo view` fallback honours it.
    execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/fake/repo.git"]);
    const yaml = [
      "schema_version: 1",
      "project: gc",
      "telemetry:",
      `  enabled: ${telemetryEnabled}`,
      "",
    ].join("\n");
    writeFileSync(join(dir, ".ground-control.yaml"), yaml);
    return dir;
  }
  const baseRecord = {
    issueNumber: 1, branch: "x", step: "1", tier: "low",
    model: "haiku", wallTimeMs: 100, outcome: "ok",
  };
  it("refuses with telemetry_disabled when the knob is false", async () => {
    const dir = makeTempRepo({ telemetryEnabled: "false" });
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseRecord });
      assert.equal(r.ok, false);
      assert.equal(r.error, "telemetry_disabled");
      // Ensure NO file was created.
      assert.equal(existsSync(join(dir, ".gc/telemetry/1-x.jsonl")), false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
  it("writes the record when the knob is true", async () => {
    const dir = makeTempRepo({ telemetryEnabled: "true" });
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseRecord });
      assert.equal(r.ok, true);
      assert.ok(existsSync(join(dir, ".gc/telemetry/1-x.jsonl")));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
  it("refuses with telemetry_no_ground_control_yaml when the config file is missing", async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-tel-no-cfg-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
    execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
    writeFileSync(join(dir, "README"), "x\n");
    execFileSync("git", ["-C", dir, "add", "README"]);
    execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
    // Real origin so owner/repo resolves from the git remote, as production does. git ignores
    // GH_REPO; the `gh repo view` fallback honours it.
    execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/fake/repo.git"]);
    try {
      const r = await runLogStepTelemetry({ repoPath: dir, ...baseRecord });
      assert.equal(r.ok, false);
      assert.equal(r.error, "telemetry_no_ground_control_yaml");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
