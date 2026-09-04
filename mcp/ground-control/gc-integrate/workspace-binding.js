// Repository binding for the /integrate lane (GC-O011 clause (a)).
//
// Every action is bound to the checkout the MCP server was launched against.
// The lane fetches, rebases, force-with-lease pushes, and in merge mode merges,
// so an unbound `repo_path` would let a caller aim those writes at any other
// checkout the server process can reach, using the server's credentials. This
// is the binding issue #1535 put on the read-only review lane, applied where
// the operations are writes.
//
// `repo_path` is checked against the launch workspace and then discarded: the
// caller's value never becomes a filesystem path, so a crafted path cannot
// reach a filesystem call at all.

import { isAbsolute, resolve } from "node:path";
import { realpathSync } from "node:fs";
import { resolveMcpLaunchWorkspaceAuthorization } from "../lib.js";
import { errorEnvelope } from "./exec-file-async.js";

// The MCP launch workspace root. Injectable so tests can bind a fixture root.
export async function defaultResolveWorkspaceRoot() {
  const authorization = await resolveMcpLaunchWorkspaceAuthorization();
  return realpathSync(authorization.workspaceRoot);
}

// Resolve the workspace this run may touch, refusing a `repo_path` that names
// anything else. Returns {ok:true, workspaceRoot} or an error envelope.
export async function authorizedWorkspaceRoot(args, deps) {
  const resolveRoot = deps.resolveWorkspaceRoot ?? defaultResolveWorkspaceRoot;
  let workspaceRoot;
  try {
    workspaceRoot = await resolveRoot();
  } catch (e) {
    return errorEnvelope(
      "workspace_unavailable",
      `The MCP launch workspace could not be resolved: ${e.message ?? String(e)}`,
      "restart_mcp_server",
    );
  }

  const requested = (args.repo_path ?? "").trim();
  let requestedReal = requested;
  if (isAbsolute(requested)) {
    try {
      requestedReal = realpathSync(requested);
    } catch {
      requestedReal = resolve(requested);
    }
  }
  if (requestedReal !== workspaceRoot) {
    return errorEnvelope(
      "repo_not_authorized",
      "repo_path is outside the MCP launch workspace authorized for this run",
      "run_against_the_launch_workspace",
    );
  }
  return { ok: true, workspaceRoot };
}
