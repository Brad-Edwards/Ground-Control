#!/usr/bin/env node
import { spawnSync } from "node:child_process";

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2).replace(/-/g, "_");
    const next = argv[i + 1];
    if (next == null || next.startsWith("--")) {
      out[key] = "true";
    } else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
const base = args.base ?? process.env.BASE_REF ?? "origin/dev";
const head = args.head ?? process.env.HEAD_REF ?? "HEAD";
const repoRoot = args.repo ?? "..";

const diff = spawnSync("git", ["diff", "--name-only", "--diff-filter=ACMR", `${base}...${head}`], {
  cwd: repoRoot,
  encoding: "utf8",
  shell: false,
});
if (diff.status !== 0) {
  console.error(diff.stderr || diff.stdout);
  process.exit(diff.status ?? 1);
}

const targets = diff.stdout
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter((line) => line.startsWith("backend/src/main/java/") && line.endsWith(".java"))
  .filter((line) => !line.endsWith("/package-info.java"))
  .map((line) =>
    line
      .replace(/^backend\/src\/main\/java\//, "")
      .replace(/\.java$/, "")
      .replace(/\//g, "."),
  );

if (targets.length === 0) {
  console.log("pitest changed-class ratchet skipped: no changed backend production Java classes");
  process.exit(0);
}

const result = spawnSync("./gradlew", ["pitest", `-PpitestTargetClasses=${targets.join(",")}`], {
  cwd: `${repoRoot}/backend`,
  stdio: "inherit",
  shell: false,
});
process.exit(result.status ?? 1);
