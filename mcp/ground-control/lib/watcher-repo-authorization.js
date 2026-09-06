// One authorization boundary for the remote-gate watchers' privileged GitHub
// reads (issue #1559).
//
// `runWatchCiRun` and `runWatchSonarAnalysis` resolved their GitHub destination
// from the caller-selected checkout's git origin and then spent the MCP host's
// credentials on it. Pinning `--repo` to that origin stops a rogue `GH_REPO`
// from retargeting the read, but it does not establish that the checkout is one
// this server is allowed to act on: a caller could pass any local path, or
// retarget a writable checkout's origin at a private repository the host's
// token can reach, and receive that repository's pull-request metadata back in
// the envelope — without any Sonar credential.
//
// The repository store already has the boundary these reads were missing.
// `authorizeImplementRepoRoot` pins the run to the immutable workspace identity
// captured at MCP launch, and returns that authorized owner/name — so the
// destination is derived from what was authorized rather than from what the
// caller supplied.

import { authorizeImplementRepoRoot, resolveMcpLaunchWorkspaceAuthorization } from "./grc-legacy-compat-4.js";

/**
 * Authorize a watcher's repository read and return its pinned `owner/name` slug.
 *
 * @param {object} args
 * @param {string} args.repoRoot canonical checkout the caller asked to watch
 * @param {string} args.errorPrefix watcher-stable error namespace (`sonar_watch` / `ci_watch`)
 * @param {Function} [args.workspaceAuthorizationResolver] injected for tests
 * @returns {Promise<{ok: true, repoSlug: string} | {ok: false, error: string, message: string}>}
 */
export async function authorizeWatcherRepoRead({
  repoRoot,
  errorPrefix,
  workspaceAuthorizationResolver = resolveMcpLaunchWorkspaceAuthorization,
}) {
  const authorization = await authorizeImplementRepoRoot(repoRoot, workspaceAuthorizationResolver);
  if (!authorization.ok) {
    return {
      ok: false,
      error: `${errorPrefix}_repo_not_authorized`,
      message:
        `${authorization.message} (${authorization.error}). `
        + "The watcher reads pull-request metadata with the MCP host's GitHub credentials, "
        + "so it acts only on the workspace this server was launched in.",
    };
  }
  return { ok: true, repoSlug: `${authorization.owner}/${authorization.name}` };
}
