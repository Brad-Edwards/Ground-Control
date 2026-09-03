// acquireImplementPublishLock — the mechanical publish base-sync mutation lease
// (issue #1495). The lease is per-worktree Git metadata and must be a distinct
// namespace from the integration lock so the two lanes never serialize on each
// other.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  acquireImplementPublishLock,
  acquireIntegrationLock,
} from "./lib.js";

function makeLockTempDir() {
  return mkdtempSync(join(tmpdir(), "gc-publish-lock-test-"));
}

describe("acquireImplementPublishLock", () => {
  it("acquires a fresh lease, returns a release handle, and releases cleanly", async () => {
    const dir = makeLockTempDir();
    try {
      const release = await acquireImplementPublishLock(dir);
      assert.equal(typeof release, "function");
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses to acquire a currently-held lease", async () => {
    const dir = makeLockTempDir();
    try {
      const release = await acquireImplementPublishLock(dir);
      await assert.rejects(() => acquireImplementPublishLock(dir), /progress|held|locked/i);
      await release();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("allows re-acquisition after release", async () => {
    const dir = makeLockTempDir();
    try {
      const r1 = await acquireImplementPublishLock(dir);
      await r1();
      const r2 = await acquireImplementPublishLock(dir);
      assert.equal(typeof r2, "function");
      await r2();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("release is idempotent", async () => {
    const dir = makeLockTempDir();
    try {
      const release = await acquireImplementPublishLock(dir);
      assert.equal(typeof release, "function");
      await release();
      await assert.doesNotReject(release(), "a second release must be a no-op, not throw");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("does not serialize against the integration lock on the same directory", async () => {
    // A distinct lockfile namespace: /integrate's repo-wide lock and the publish
    // lease must be able to co-exist rather than block each other.
    const dir = makeLockTempDir();
    try {
      const integration = await acquireIntegrationLock(dir);
      const publish = await acquireImplementPublishLock(dir);
      assert.equal(typeof publish, "function");
      await publish();
      await integration();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects a relative path and a non-existent absolute path", async () => {
    await assert.rejects(() => acquireImplementPublishLock("relative/path"), /absolute/i);
    await assert.rejects(
      () => acquireImplementPublishLock(join(tmpdir(), "gc-publish-lock-does-not-exist-xyz")),
      /does not exist/i,
    );
  });
});
