#!/usr/bin/env node
import { readFileSync } from "node:fs";
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
if (!args.baseline) {
  console.error("--baseline is required");
  process.exit(2);
}

const baseline = JSON.parse(readFileSync(args.baseline, "utf8"));
const cwd = args.package ?? ".";
const audit = spawnSync("npm", ["audit", "--json"], {
  cwd,
  encoding: "utf8",
  shell: false,
});

let parsed;
try {
  parsed = JSON.parse(audit.stdout || "{}");
} catch (error) {
  console.error(`npm audit did not emit valid JSON: ${error.message}`);
  process.exit(1);
}

const current = parsed.metadata?.vulnerabilities ?? {};
const severities = ["critical", "high", "moderate", "low"];
const increases = [];
for (const severity of severities) {
  const now = Number(current[severity] ?? 0);
  const allowed = Number(baseline.counts?.[severity] ?? 0);
  if (now > allowed) {
    increases.push(`${severity}: ${now} > ${allowed}`);
  }
}

if (increases.length > 0) {
  console.error(`npm audit ratchet failed for ${baseline.package}: ${increases.join(", ")}`);
  process.exit(1);
}

console.log(
  `npm audit ratchet passed for ${baseline.package}: ` +
    severities.map((severity) => `${severity}=${Number(current[severity] ?? 0)}`).join(", "),
);
