#!/usr/bin/env node
import { installWorkflowAssets } from "../../mcp/ground-control/lib.js";

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

export async function runInstallWorkflowAssetsCli({ defaultPackId = null } = {}) {
  const args = parseArgs(process.argv.slice(2));
  const repoPath = args.repo ?? args.repo_path ?? process.cwd();
  const packId = args.pack ?? args.pack_id ?? defaultPackId;
  const result = await installWorkflowAssets({
    repoPath,
    packId,
    versionConstraint: args.version ?? "1.0.0",
    engineVersionConstraint: args.engine_version ?? args.engine ?? "^1.0.0",
    scope: args.scope ?? ".",
    profile: args.profile ?? null,
    catalogPath: args.catalog ?? undefined,
    runSelftest: args.selftest !== "false",
    installDependencies: args.install_dependencies !== "false",
    mode: args.mode ?? (args.upgrade === "true" ? "upgrade" : "install"),
  });
  console.log(JSON.stringify(result, null, 2));
  if (result.ok !== true) process.exitCode = 1;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await runInstallWorkflowAssetsCli();
}
