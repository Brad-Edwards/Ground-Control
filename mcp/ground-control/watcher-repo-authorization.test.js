// The remote-gate watchers' authorization boundary (issue #1559).
//
// `runWatchCiRun` and `runWatchSonarAnalysis` derived their GitHub destination
// from the caller-selected checkout's git origin and then spent the MCP host's
// credentials on it, so a caller could aim a privileged pull-request read at any
// local checkout — or retarget a writable one's origin at a private repository
// the host's token can reach — and get that repository's metadata back.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { authorizeWatcherRepoRead } from "./lib/watcher-repo-authorization.js";

describe("authorizeWatcherRepoRead", () => {
  it("refuses a checkout outside the authorized launch workspace", async () => {
    const result = await authorizeWatcherRepoRead({
      repoRoot: "/some/other/checkout",
      errorPrefix: "sonar_watch",
      // No launch identity was captured, which is how the real resolver reports
      // a server that cannot vouch for any workspace.
      workspaceAuthorizationResolver: async () => null,
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "sonar_watch_repo_not_authorized");
    assert.equal(result.repoSlug, undefined);
    assert.match(result.message, /MCP host's GitHub credentials/);
  });

  it("namespaces the refusal per watcher so each can route its own repair", async () => {
    const result = await authorizeWatcherRepoRead({
      repoRoot: "/some/other/checkout",
      errorPrefix: "ci_watch",
      workspaceAuthorizationResolver: async () => null,
    });
    assert.equal(result.error, "ci_watch_repo_not_authorized");
  });

  it("survives a resolver that throws rather than admitting an unauthorized read", async () => {
    const result = await authorizeWatcherRepoRead({
      repoRoot: "/some/other/checkout",
      errorPrefix: "sonar_watch",
      workspaceAuthorizationResolver: async () => { throw new Error("git exploded"); },
    });
    assert.equal(result.ok, false);
    assert.equal(result.error, "sonar_watch_repo_not_authorized");
  });
});
