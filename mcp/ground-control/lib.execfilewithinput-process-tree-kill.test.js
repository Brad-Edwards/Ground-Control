// execFileWithInput must bound the WHOLE process tree it starts, not just
// the direct child. A codex-spawned `ugrep` outlived a direct-child-only
// timeout kill and ran orphaned for 10+ days at ~11 cores (issue #1518).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileWithInput } from "./lib/runtime-primitives.js";

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function waitUntil(predicate, { timeoutMs = 2000, intervalMs = 20 } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (predicate()) return true;
    if (Date.now() >= deadline) return predicate();
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

function backgroundedGrandchildScript(pidFile) {
  // Backgrounds a long-lived grandchild under the SAME process group as the
  // direct child (bash), writes its pid, then blocks on it — mirroring how
  // a shell tool call under codex backgrounds work without detaching.
  // Redirected off the inherited stdout/stderr pipes: a background process
  // that still holds those pipes open blocks Node's exec callback from ever
  // firing (it waits for the pipes to fully close), independent of the
  // process-tree bug this suite exercises.
  return ["-c", "sleep 300 >/dev/null 2>&1 & echo $! > \"$1\"; wait", "_", pidFile];
}

describe("execFileWithInput — process-tree cleanup (issue #1518)", () => {
  it("still resolves normally for a fast, well-behaved command", async () => {
    const { stdout } = await execFileWithInput("bash", ["-c", "cat"], { input: "hello\n", timeoutMs: 5000 });
    assert.equal(stdout, "hello\n");
  });

  it("still rejects with the underlying error for a nonzero exit", async () => {
    await assert.rejects(
      execFileWithInput("bash", ["-c", "exit 3"], { timeoutMs: 5000 }),
      (err) => err.code === 3,
    );
  });

  it("kills a backgrounded grandchild when the timeout fires, not just the direct child", { timeout: 5000 }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-exec-tree-"));
    const pidFile = join(dir, "grandchild.pid");
    try {
      await assert.rejects(
        execFileWithInput("bash", backgroundedGrandchildScript(pidFile), {
          timeoutMs: 150,
          killGraceMs: 150,
        }),
        (err) => err.code === "ETIMEDOUT",
      );
      const grandchildPid = Number(readFileSync(pidFile, "utf8").trim());
      assert.ok(Number.isInteger(grandchildPid) && grandchildPid > 0);
      const dead = await waitUntil(() => !isAlive(grandchildPid));
      assert.equal(dead, true, `grandchild pid ${grandchildPid} survived the timeout kill`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("kills the same backgrounded grandchild when the caller aborts via AbortSignal", { timeout: 5000 }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-exec-tree-"));
    const pidFile = join(dir, "grandchild.pid");
    const controller = new AbortController();
    try {
      const promise = execFileWithInput("bash", backgroundedGrandchildScript(pidFile), {
        signal: controller.signal,
        killGraceMs: 150,
      });
      await waitUntil(() => {
        try {
          return readFileSync(pidFile, "utf8").trim() !== "";
        } catch {
          return false;
        }
      });
      controller.abort();
      await assert.rejects(promise);
      const grandchildPid = Number(readFileSync(pidFile, "utf8").trim());
      const dead = await waitUntil(() => !isAlive(grandchildPid));
      assert.equal(dead, true, `grandchild pid ${grandchildPid} survived the abort kill`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("reaps a background descendant even when the leader exits successfully", { timeout: 5000 }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-exec-tree-"));
    const pidFile = join(dir, "grandchild.pid");
    try {
      // The leader backgrounds a grandchild, disowns it (so it survives the
      // leader's own exit), and exits 0 immediately without waiting.
      const { stdout } = await execFileWithInput(
        "bash",
        ["-c", "sleep 300 >/dev/null 2>&1 & echo $! > \"$1\"; disown; echo done", "_", pidFile],
        { timeoutMs: 5000 },
      );
      assert.equal(stdout, "done\n");
      const grandchildPid = Number(readFileSync(pidFile, "utf8").trim());
      const dead = await waitUntil(() => !isAlive(grandchildPid));
      assert.equal(dead, true, `grandchild pid ${grandchildPid} survived a successful leader exit`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("reaps a background descendant even when the leader exits with an error", { timeout: 5000 }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-exec-tree-"));
    const pidFile = join(dir, "grandchild.pid");
    try {
      await assert.rejects(
        execFileWithInput(
          "bash",
          ["-c", "sleep 300 >/dev/null 2>&1 & echo $! > \"$1\"; disown; exit 1", "_", pidFile],
          { timeoutMs: 5000 },
        ),
      );
      const grandchildPid = Number(readFileSync(pidFile, "utf8").trim());
      const dead = await waitUntil(() => !isAlive(grandchildPid));
      assert.equal(dead, true, `grandchild pid ${grandchildPid} survived a failed leader exit`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("does not settle until a SIGTERM-ignoring descendant has actually been reaped via SIGKILL", { timeout: 5000 }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-exec-tree-"));
    const pidFile = join(dir, "grandchild.pid");
    // SIG_IGN survives exec (POSIX), so this grandchild is immune to SIGTERM
    // and only SIGKILL can end it — exercising the TERM-then-grace-SIGKILL
    // escalation for real, unlike `sleep`, which dies on plain SIGTERM.
    const script = "sh -c 'trap \"\" TERM; exec sleep 300' >/dev/null 2>&1 & echo $! > \"$1\"; wait";
    try {
      await assert.rejects(
        execFileWithInput("bash", ["-c", script, "_", pidFile], {
          timeoutMs: 150,
          killGraceMs: 200,
        }),
        (err) => err.code === "ETIMEDOUT",
      );
      // By the time the call has settled, the grandchild must already be
      // dead — not "will die eventually." Settlement racing ahead of cleanup
      // is the bug: the caller sees a terminal result while the descendant
      // lives on.
      const grandchildPid = Number(readFileSync(pidFile, "utf8").trim());
      assert.equal(isAlive(grandchildPid), false, `grandchild pid ${grandchildPid} was still alive when the call settled`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("stops growing buffered output once maxBuffer is exceeded, instead of appending forever", { timeout: 15000 }, async () => {
    // Emit well past maxBuffer across many small chunks so a saturation bug
    // (re-appending the same stale "remaining allowance" on every later
    // chunk) would blow the cap many times over instead of stopping near it.
    //
    // The emitter is an unbounded loop of shell builtins (`while`/`printf`, no
    // fork per iteration) rather than a finite `for i in $(seq …)` burst. The
    // finite form was flaky under the full suite's fork pressure in CI: the
    // `$(seq …)` command substitution could come back empty (zero-iteration
    // loop → zero output → clean exit), or the fast-exiting child could hit the
    // close/end-before-data stdio ordering gap (nodejs/node#9633) — either way
    // maxBuffer was never tripped and the call resolved. An unbounded emitter
    // trips the cap deterministically while the child is still running, so it is
    // reliably reported as a maxBuffer rejection.
    const maxBuffer = 1024;
    await assert.rejects(
      execFileWithInput(
        "bash",
        ["-c", "while :; do printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; done"],
        { timeoutMs: 10000, maxBuffer },
      ),
      (err) => {
        assert.equal(err.code, "ERR_CHILD_PROCESS_STDIO_MAXBUFFER");
        assert.ok(
          err.stdout.length <= maxBuffer * 2,
          `stdout grew to ${err.stdout.length} bytes, far past the ${maxBuffer}-byte cap — maxBuffer is not bounding output`,
        );
        return true;
      },
    );
  });

  it("keeps maxBuffer as the terminal cause when a later abort arrives during cleanup", { timeout: 10000 }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gc-exec-cause-"));
    const markerFile = join(dir, "wrote-past-cap");
    const releaseFile = join(dir, "release");
    const controller = new AbortController();
    // The child must fill more than a pipe buffer before writing the marker,
    // which proves Node drained enough stdout to trip maxBuffer. It then waits
    // with SIGTERM ignored so a caller abort can deterministically race the
    // already-active cleanup without relying on scheduler-sensitive timers.
    const script = [
      "trap '' TERM",
      "for ((i=0; i<4096; i++)); do printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; done",
      ": > \"$1\"",
      "while [ ! -e \"$2\" ]; do :; done",
    ].join("; ");
    try {
      const rejection = assert.rejects(
        execFileWithInput("bash", ["-c", script, "_", markerFile, releaseFile], {
          signal: controller.signal,
          killGraceMs: 5000,
          maxBuffer: 32,
        }),
        (err) => err.code === "ERR_CHILD_PROCESS_STDIO_MAXBUFFER",
      );
      const wrotePastCap = await waitUntil(() => {
        try {
          readFileSync(markerFile);
          return true;
        } catch {
          return false;
        }
      }, { timeoutMs: 5000 });
      controller.abort();
      writeFileSync(releaseFile, "");
      assert.equal(wrotePastCap, true, "child never proved it wrote beyond maxBuffer");
      await rejection;
    } finally {
      controller.abort();
      writeFileSync(releaseFile, "");
      rmSync(dir, { recursive: true, force: true });
    }
  });

  // Regression guard for issue #1521's CI investigation: Node's `close`
  // event is documented to fire only after a child's stdio streams have
  // closed, but that ordering has known gaps for a fast-exiting process with
  // a lot of output (nodejs/node#9633, #7184, #4236). A process that writes
  // well past a single pipe-buffer's worth of data and exits immediately
  // must still resolve with every byte it wrote — not a result truncated by
  // a `close` that outran the last `data` events.
  it("delivers the full output of a process that writes a lot then exits immediately", async () => {
    const byteCount = 300_000; // comfortably past the pipe buffer and any single read chunk
    const { stdout } = await execFileWithInput(
      "bash",
      ["-c", `head -c ${byteCount} /dev/zero | tr '\\0' 'a'`],
      { timeoutMs: 5000 },
    );
    assert.equal(stdout.length, byteCount, `expected ${byteCount} bytes, got ${stdout.length} — output was truncated`);
    assert.ok(/^a+$/.test(stdout), "delivered output was not the expected repeated byte");
  });

  it("fails closed on a non-POSIX platform instead of silently killing only the direct child", async () => {
    const original = Object.getOwnPropertyDescriptor(process, "platform");
    Object.defineProperty(process, "platform", { value: "win32", configurable: true });
    try {
      await assert.rejects(
        execFileWithInput("bash", ["-c", "true"], { timeoutMs: 1000 }),
        /POSIX process-group/,
      );
    } finally {
      Object.defineProperty(process, "platform", original);
    }
  });
});
