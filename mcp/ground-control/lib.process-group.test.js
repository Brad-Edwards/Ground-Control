// POSIX process-group primitives that let a caller terminate a whole
// subprocess tree, not just the process it spawned directly (issue #1518).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { isPosixProcessGroupCapable, isProcessGroupAlive, signalProcessGroup } from "./lib/process-group.js";

function spawnDetachedGroup(script) {
  return spawn("bash", ["-c", script], { detached: true, stdio: "ignore" });
}

describe("isPosixProcessGroupCapable", () => {
  it("is true on the current (Linux) platform", () => {
    assert.equal(isPosixProcessGroupCapable(), process.platform !== "win32");
  });
});

describe("isProcessGroupAlive / signalProcessGroup", () => {
  it("reports a freshly spawned group as alive, then dead after SIGKILL", async () => {
    const child = spawnDetachedGroup("sleep 300");
    try {
      assert.equal(isProcessGroupAlive(child.pid), true);
      const result = signalProcessGroup(child.pid, "SIGKILL");
      assert.equal(result.ok, true);
      await new Promise((resolve) => child.once("exit", resolve));
      assert.equal(isProcessGroupAlive(child.pid), false);
    } finally {
      try {
        process.kill(-child.pid, "SIGKILL");
      } catch {
        // Already gone.
      }
    }
  });

  it("signalling an already-empty group returns ok (ESRCH is success, not failure)", async () => {
    const child = spawnDetachedGroup("true");
    await new Promise((resolve) => child.once("exit", resolve));
    // Give the kernel a moment to fully reap the group after exit.
    await new Promise((resolve) => setTimeout(resolve, 50));
    const result = signalProcessGroup(child.pid, "SIGTERM");
    assert.equal(result.ok, true);
    assert.equal(result.error, undefined);
  });
});
