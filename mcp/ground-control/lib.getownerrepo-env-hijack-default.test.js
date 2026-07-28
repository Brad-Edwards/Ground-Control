// Split from lib.test.js, which exceeded the repo's 500-LOC limit (issue #1467).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { getOwnerRepo } from "./lib.js";

describe("getOwnerRepo env-hijack default (issue #1355)", () => {
  it("refuses the GH_REPO-honouring fallback unless a caller opts in", async () => {
    // The fallback resolves the repo through `gh repo view`, which honours GH_REPO. It used to be
    // the default, so every one of the twenty-odd call sites was hijackable unless it had
    // remembered to opt out, and reviewing which had is exactly the audit a safe default removes.
    const dir = mkdtempSync(join(tmpdir(), "gc-noremote-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);

    await assert.rejects(
      () => getOwnerRepo(dir),
      /refusing to fall back to GH_REPO-sensitive resolution/,
    );
  });

  it("still resolves from the git remote, which git reads without consulting GH_REPO", async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-remote-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/owner/name.git"]);

    assert.deepEqual(await getOwnerRepo(dir), { owner: "owner", name: "name" });
  });
});
