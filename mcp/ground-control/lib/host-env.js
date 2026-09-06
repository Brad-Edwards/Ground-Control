// Startup environment provisioning for the MCP server (issue #946).
//
// The server used to depend on the launching agent process passing its
// environment down. That made a tool's correctness a property of which runtime
// happened to host it: a Claude Code-spawned server inherits ~70 variables, a
// Codex-spawned one exactly eight, and neither the token gc_watch_sonar_analysis
// needs nor GROUND_CONTROL_DIR survives the second. The server therefore reads
// its own declared sources and only falls back to what it inherited.
//
// Two locations, most specific first:
//
//   1. `<launch root>/.env`  - per-repository, the existing contract.
//   2. `~/.config/ground-control/env` - per-host, the Ground Control host-config
//      directory that already holds `review-env`.
//
// The host file exists because the launch root alone cannot cover two real
// cases: a launcher may start the server with a working directory that is not a
// repository root (so there is no `.env` to find), and provisioning one live
// credential per repository multiplies it across every checkout on the machine.
// Only HOME is needed to resolve it, and HOME is in every launcher's core
// inherit set.
//
// Both files are read once, at startup. Provisioning or rotating either one
// takes effect on the next server start, which is what the operator-facing
// error messages tell the reader.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { parseEnvFileLine } from "./runtime-primitives.js";

/** Absolute path of the per-host env file, given a home directory. */
export function hostEnvFilePath(homeDir = homedir()) {
  return join(homeDir, ".config", "ground-control", "env");
}

// Assign only what is genuinely absent, so a more specific source already
// consulted keeps its value. An empty string counts as absent: a launcher that
// forwards a variable it does not have supplies "" rather than omitting it, and
// treating that as provisioned reintroduces the failure this module exists to
// remove.
function applyEnvFile(env, path) {
  let body;
  try {
    body = readFileSync(path, "utf8");
  } catch {
    // Absent or unreadable. Neither file is required; the server starts and
    // every registered tool works with neither present.
    return;
  }
  for (const line of body.split(/\r?\n/)) {
    const parsed = parseEnvFileLine(line);
    if (parsed && (env[parsed[0]] === undefined || env[parsed[0]] === "")) {
      env[parsed[0]] = parsed[1];
    }
  }
}

/**
 * Fill `env` in place from the server's declared configuration sources.
 *
 * Precedence, highest first: an inherited non-empty value, the launch root's
 * `.env`, then the per-host env file.
 */
export function loadServerEnvFiles(env = process.env, { cwd = process.cwd(), homeDir = homedir() } = {}) {
  applyEnvFile(env, join(cwd, ".env"));
  applyEnvFile(env, hostEnvFilePath(homeDir));
  return env;
}
