#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { computeWorkflowArtifactChecksum } from "../../mcp/ground-control/lib.js";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const defaultCatalogPath = join(repoRoot, "workflow/gate-catalog.json");

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

function normalizeSha256(raw) {
  if (typeof raw !== "string") return null;
  const value = raw.trim().replace(/^sha256:/i, "");
  return /^[a-f0-9]{64}$/i.test(value) ? value.toLowerCase() : null;
}

function artifactPath(catalogPath, rawPath) {
  if (typeof rawPath !== "string" || rawPath.trim() === "") {
    throw new Error("artifact path must be a non-empty string");
  }
  if (rawPath.startsWith("/")) return rawPath;
  return resolve(rawPath.startsWith("workflow/") ? repoRoot : dirname(catalogPath), rawPath);
}

function extractTgz(path) {
  const dir = mkdtempSync(join(tmpdir(), "gc-release-verify-"));
  try {
    execFileSync("tar", ["-xzf", path, "-C", dir], { stdio: "ignore" });
    return dir;
  } catch (error) {
    rmSync(dir, { recursive: true, force: true });
    throw error;
  }
}

function verifyChecksum({ catalogPath, entry, label }) {
  const expected = normalizeSha256(entry.sha256 ?? entry.checksum);
  if (expected == null) throw new Error(`${label} is missing a valid SHA-256 checksum`);
  const path = artifactPath(catalogPath, entry.artifact ?? entry.source_url);
  statSync(path);
  const actual = computeWorkflowArtifactChecksum(path);
  if (actual !== expected) {
    throw new Error(`${label} checksum mismatch: expected ${expected}, got ${actual}`);
  }
  return { path, sha256: actual };
}

function verifyEngine({ catalogPath, engine }) {
  const checksum = verifyChecksum({ catalogPath, entry: engine, label: `engine@${engine.version}` });
  let extracted = null;
  try {
    extracted = extractTgz(checksum.path);
    const metadata = readFileSync(join(extracted, "workflow/engine/engine.yaml"), "utf8");
    if (!metadata.includes("ground-control-implement-engine")) {
      throw new Error("engine artifact metadata does not identify the Ground Control implement engine");
    }
    return { version: engine.version, artifact: engine.artifact, sha256: checksum.sha256 };
  } finally {
    if (extracted) rmSync(extracted, { recursive: true, force: true });
  }
}

function verifyPack({ catalogPath, pack }) {
  const checksum = verifyChecksum({ catalogPath, entry: pack, label: `${pack.id}@${pack.version}` });
  let extracted = null;
  try {
    extracted = extractTgz(checksum.path);
    for (const file of ["pack.yaml", "capabilities.yaml", "classifier.yaml", "selftest/config.json", "selftest/run.mjs"]) {
      statSync(join(extracted, file));
    }
    const packYaml = readFileSync(join(extracted, "pack.yaml"), "utf8");
    if (!packYaml.includes(`id: ${pack.id}`)) {
      throw new Error(`${pack.id} artifact pack.yaml id does not match catalog entry`);
    }
    return { id: pack.id, version: pack.version, artifact: pack.artifact, sha256: checksum.sha256 };
  } finally {
    if (extracted) rmSync(extracted, { recursive: true, force: true });
  }
}

export function verifyWorkflowReleaseCatalog(catalogPath = defaultCatalogPath) {
  const catalog = JSON.parse(readFileSync(catalogPath, "utf8"));
  const errors = [];
  const result = { ok: true, catalog_path: catalogPath, engine: null, packs: [] };
  try {
    if (catalog.schema_version !== 1 || catalog.kind !== "ground-control-gate-catalog") {
      throw new Error("catalog must be kind=ground-control-gate-catalog schema_version=1");
    }
    if (!catalog.engine || typeof catalog.engine !== "object") throw new Error("catalog engine entry is required");
    result.engine = verifyEngine({ catalogPath, engine: catalog.engine });
    if (!Array.isArray(catalog.packs) || catalog.packs.length !== 7) {
      throw new Error("catalog must contain exactly the 7 supported initial packs");
    }
    result.packs = catalog.packs.map((pack) => verifyPack({ catalogPath, pack }));
  } catch (error) {
    errors.push(error.message);
  }
  if (errors.length > 0) return { ok: false, catalog_path: catalogPath, errors };
  return result;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = parseArgs(process.argv.slice(2));
  const result = verifyWorkflowReleaseCatalog(resolve(args.catalog ?? defaultCatalogPath));
  console.log(JSON.stringify(result, null, 2));
  if (result.ok !== true) process.exitCode = 1;
}
