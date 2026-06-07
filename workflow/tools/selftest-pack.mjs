#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import {
  installWorkflowAssets,
  parseGateManifestYaml,
  runGates,
} from "../../mcp/ground-control/lib.js";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2).replace(/-/g, "_");
    const next = argv[i + 1];
    if (next == null || next.startsWith("--")) out[key] = "true";
    else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function commandAvailable(tool) {
  try {
    execFileSync(tool.command, tool.args ?? ["--version"], { stdio: "ignore" });
    return true;
  } catch (error) {
    return false;
  }
}

function writeFixtureFiles(root, files) {
  for (const [rel, content] of Object.entries(files)) {
    const abs = join(root, rel);
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, content);
  }
}

// Remove language bytecode / test caches between gate phases. The fail fixture
// can be the same byte length as the pass fixture (e.g. `== 4` vs `== 5`), so
// Python's mtime+size .pyc validation can otherwise serve the pass-phase
// compiled module during the fail phase, making the self-test pass when it
// should fail. Clearing these caches makes the fail phase deterministic.
function clearBuildCaches(root) {
  let entries;
  try {
    entries = readdirSync(root, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const abs = join(root, entry.name);
    if (entry.name === "__pycache__" || entry.name === ".pytest_cache") {
      rmSync(abs, { recursive: true, force: true });
    } else if (entry.name !== ".git") {
      clearBuildCaches(abs);
    }
  }
}

async function runPackSelftest({ packId, catalogPath }) {
  const configPath = join(repoRoot, "workflow/packs", packId, "selftest/config.json");
  const config = readJson(configPath);
  const missing = [];
  for (const tool of config.required_tools) {
    if (!commandAvailable(tool)) missing.push(tool.name);
  }
  if (missing.length > 0) {
    return {
      ok: true,
      status: "skipped",
      pack: packId,
      reason: `missing toolchain: ${missing.join(", ")}`,
      missing_tools: missing,
    };
  }

  const fixtureRoot = mkdtempSync(join(tmpdir(), `gc-${packId}-selftest-`));
  try {
    execFileSync("git", ["init"], { cwd: fixtureRoot, stdio: "ignore" });
    execFileSync("git", ["config", "user.email", "selftest@example.com"], { cwd: fixtureRoot, stdio: "ignore" });
    execFileSync("git", ["config", "user.name", "Gate Pack Selftest"], { cwd: fixtureRoot, stdio: "ignore" });
    writeFixtureFiles(fixtureRoot, config.fixture.files);

    const install = await installWorkflowAssets({
      repoPath: fixtureRoot,
      packId,
      versionConstraint: "1.0.0",
      scope: ".",
      profile: "default",
      catalogPath,
      runSelftest: false,
      installDependencies: false,
    });
    if (install.ok !== true) {
      return { ok: false, status: "failed", pack: packId, phase: "install", install };
    }

    const manifestText = execFileSync("node", ["-e", "process.stdout.write(require('fs').readFileSync('.gc/gates.yaml', 'utf8'))"], {
      cwd: fixtureRoot,
      encoding: "utf8",
    });
    const manifestValidation = parseGateManifestYaml(manifestText, { repoRoot: fixtureRoot });
    if (manifestValidation.ok !== true) {
      return { ok: false, status: "failed", pack: packId, phase: "manifest_validation", errors: manifestValidation.errors };
    }

    const diffInfo = {
      base_ref: "selftest-base",
      head_ref: "selftest-head",
      changed_files: config.fixture.changedFiles,
      diff_hash: `${packId}-selftest-diff`,
    };
    const pass = await runGates({
      repoPath: fixtureRoot,
      issueNumber: 1075,
      diffInfo,
      postMarker: false,
      capabilities: [config.fixture.passCapability],
    });
    if (pass.ok !== true || pass.status !== "passed") {
      return { ok: false, status: "failed", pack: packId, phase: "passing_gate", result: pass };
    }

    writeFixtureFiles(fixtureRoot, config.fixture.failFiles);
    clearBuildCaches(fixtureRoot);
    const fail = await runGates({
      repoPath: fixtureRoot,
      issueNumber: 1075,
      diffInfo,
      postMarker: false,
      capabilities: [config.fixture.failCapability],
    });
    if (fail.ok !== false || fail.error !== "blocking_gate_failed") {
      return { ok: false, status: "failed", pack: packId, phase: "failing_gate", result: fail };
    }

    const missingResult = await runGates({
      repoPath: fixtureRoot,
      issueNumber: 1075,
      diffInfo,
      postMarker: false,
      capabilities: [config.fixture.missingCapability],
    });
    const missingGate = missingResult.gates?.[0];
    if (missingResult.ok !== true || missingGate?.provider_missing !== true) {
      return { ok: false, status: "failed", pack: packId, phase: "provider_missing_or_not_applicable", result: missingResult };
    }

    return {
      ok: true,
      status: "passed",
      pack: packId,
      install: "passed",
      manifest_validation: "passed",
      passing_gate: pass.gates[0]?.gate_id ?? config.fixture.passCapability,
      failing_gate: fail.gate_id,
      provider_missing_or_not_applicable: {
        gate_id: missingGate.gate_id,
        fallback: missingGate.fallback,
        telemetry: missingGate.telemetry,
      },
    };
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true });
  }
}

export async function runPackSelftestCli({ defaultPackId = null } = {}) {
  const args = parseArgs(process.argv.slice(2));
  const packId = args.pack ?? args.pack_id ?? defaultPackId;
  const catalogPath = args.catalog ?? join(repoRoot, "workflow/gate-catalog.json");
  const result = await runPackSelftest({ packId, catalogPath });
  console.log(JSON.stringify(result));
  if (result.ok !== true) process.exitCode = 1;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await runPackSelftestCli();
}
