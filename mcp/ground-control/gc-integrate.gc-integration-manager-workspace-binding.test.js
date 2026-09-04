// Workspace binding for the /integrate lane (issue #633 follow-up).
//
// The lane pushes and can merge, so `repo_path` must not be able to point the
// server at another checkout it can reach. The status and release actions bind
// to the MCP launch workspace and check `repo_path` against it before any
// filesystem access — the binding #1535 put on the read-only review lane, which
// matters more here because this lane writes.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { runIntegrationManager } from "./gc-integrate.js";

// Status/release deps with a fixture workspace root, so these tests exercise the
// authorization path rather than the real checkout.
function statusDeps(overrides = {}) {
  return {
    resolveWorkspaceRoot: overrides.resolveWorkspaceRoot ?? (() => "/some/repo"),
    statFile: overrides.statFile ?? (() => ({ ok: false })),
    readdir: overrides.readdir ?? (() => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); }),
    readFile: overrides.readFile ?? (() => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); }),
    rmFile: overrides.rmFile ?? (() => {}),
  };
}

describe("gc_integration_manager — workspace binding", () => {
  // The lane pushes and can merge, so a repo_path naming another checkout the
  // server process can reach must be refused before any filesystem access —
  // the binding #1535 put on the review lane.
  for (const action of ["status", "release"]) {
    it(`${action}: refuses a repo_path outside the MCP launch workspace`, async () => {
      let touched = false;
      const deps = statusDeps({
        resolveWorkspaceRoot: () => "/authorized/workspace",
        statFile: () => { touched = true; return { ok: true, mtimeMs: 1 }; },
        rmFile: () => { touched = true; },
      });
      const result = await runIntegrationManager(
        { action, repo_path: "/some/other/checkout" },
        deps,
      );
      assert.equal(result.ok, false);
      assert.equal(result.error, "repo_not_authorized");
      assert.equal(touched, false, "must refuse before touching the filesystem");
    });
  }

  it("status: reports an unresolvable launch workspace rather than falling back", async () => {
    const deps = statusDeps({
      resolveWorkspaceRoot: () => { throw new Error("no workspace"); },
    });
    const result = await runIntegrationManager(
      { action: "status", repo_path: "/some/repo" },
      deps,
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "workspace_unavailable");
  });
});
