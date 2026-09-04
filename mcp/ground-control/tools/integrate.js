// `/integrate` lane tool registration (GC-O011).
//
// `gc_integration_manager` backs `skills/integrate/SKILL.md`. Its implementation
// survived the #1500 re-platform intact — it reaches GitHub and git through
// `gh`/`git` argv from this server (ADR-027) and never touched the deleted
// backend — but #1506 removed it as dead code because the sweep looked for JS
// callers and the only caller is skill prose. That left an ACTIVE MUST
// requirement with no entry point and a lane that could not run; this
// registration restores it. Handler stays thin: validate shape with zod,
// delegate to the lib, wrap with ok/err.

import { z } from "zod";
// Imported from the module directly rather than the lib.js barrel: gc-integrate.js
// imports named helpers *from* lib.js, so re-exporting it through the barrel would
// close a module cycle. This is the same direction the pre-#1506 tree used.
import { GC_INTEGRATION_MANAGER_DESCRIPTION, VALID_ACTIONS, VALID_MODES, runIntegrationManager } from "../gc-integrate.js";
import { ok, err } from "./respond.js";

export function registerIntegrate(server) {
  server.tool(
    "gc_integration_manager",
    GC_INTEGRATION_MANAGER_DESCRIPTION,
    {
      action: z.enum(VALID_ACTIONS).describe("plan (discover + ordered queue), prepare (rebase, gates, push), status (read-only lock/queue state), release (idempotent lock release)"),
      repo_path: z.string().min(1).describe("Absolute path to the target repository checkout"),
      mode: z.enum(VALID_MODES).optional().describe("prepare (default) never merges; merge requires the ADR-029 carve-out and a configured workflow.integration_manager.merge_strategy; enqueue is reserved and refuses at runtime"),
    },
    async ({ action, repo_path, mode }) => {
      try {
        return ok(JSON.stringify(await runIntegrationManager({ action, repo_path, mode }), null, 2));
      } catch (e) { return err(e); }
    },
  );
}
