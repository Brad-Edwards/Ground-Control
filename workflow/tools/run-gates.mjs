#!/usr/bin/env node
import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { runGates } from "../../mcp/ground-control/lib.js";

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
const capabilities = args.capabilities
  ? args.capabilities.split(",").map((item) => item.trim()).filter(Boolean)
  : null;
const repoPath = resolve(args.repo ?? args.repo_path ?? process.cwd());
const changedFiles = (args.changed_files ?? process.env.GC_GATE_CHANGED_FILES ?? "")
  .split(/[,\n]/)
  .map((item) => item.trim())
  .filter(Boolean)
  .sort();
const diffInfo = changedFiles.length > 0
  ? {
      base_ref: args.base ?? args.base_ref ?? "origin/dev",
      head_ref: args.head ?? args.head_ref ?? "HEAD",
      changed_files: changedFiles,
      name_status: changedFiles.map((path) => ({
        status: "M",
        path,
        old_path: null,
        score: null,
        raw: `M\t${path}`,
      })),
      diff_hash: createHash("sha256").update(changedFiles.join("\n")).digest("hex"),
      diff_text: "",
      file_diffs: {},
    }
  : null;

const result = await runGates({
  repoPath,
  issueNumber: Number.parseInt(args.issue ?? args.issue_number ?? "1075", 10),
  baseRef: args.base ?? args.base_ref ?? "origin/dev",
  headRef: args.head ?? args.head_ref ?? "HEAD",
  capabilities,
  diffInfo,
  postMarker: false,
});

console.log(JSON.stringify(result, null, 2));
if (result.ok !== true) process.exitCode = 1;
