// Extracted from runtime-primitives.js (issue #1518) to stay under the
// repo's 500-LOC file gate. runtime-primitives.js re-exports execFileWithInput
// and the timeout constants, so every existing caller's import path is
// unchanged.

import { spawn } from "node:child_process";
import { isProcessGroupAlive, isPosixProcessGroupCapable, terminateProcessGroup } from "./process-group.js";

export const CODEX_TIMEOUT_MS_MIN = 1000; // 1 second floor
export const CODEX_TIMEOUT_MS_MAX = 3600000; // 1 hour ceiling
export const CODEX_TIMEOUT_MS_DEFAULT = 1200000; // 20 minutes
// GC_CODEX_TIMEOUT_MS is host configuration, not repository policy (issue
// #1518). A zero, negative, malformed, or excessive value must fall back to
// the finite default rather than disabling or effectively removing the wall
// cap that bounds every codex/claude subprocess this server spawns.
export function parseCodexTimeoutMs(raw) {
  if (typeof raw !== "string" || raw.trim() === "") return CODEX_TIMEOUT_MS_DEFAULT;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isInteger(parsed) || parsed < CODEX_TIMEOUT_MS_MIN || parsed > CODEX_TIMEOUT_MS_MAX) {
    return CODEX_TIMEOUT_MS_DEFAULT;
  }
  return parsed;
}
// Resolved on every call, not once at module-import time (issue #1521): the
// import graph that reaches this module executes before index.js's
// loadDotenvFromCwd() (or any other startup env-config loader) runs, so a
// module-level constant would permanently miss a GC_CODEX_TIMEOUT_MS value
// that only lives in a .env/host-config file rather than the ambient shell.
export function getDefaultCodexTimeoutMs() {
  return parseCodexTimeoutMs(process.env.GC_CODEX_TIMEOUT_MS);
}
const KILL_GRACE_MS_DEFAULT = 5000;
const MAX_BUFFER_DEFAULT = 1024 * 1024; // 1 MiB, matches Node's own execFile default
// child_process.execFile() silently drops `detached` before it reaches the
// real spawn() call (it forwards only an explicit options allowlist), so it
// can never produce a real process-group leader — confirmed by reading
// Node's own execFile source. execFileWithInput therefore builds directly on
// spawn(), reimplementing the small slice of execFile's behavior (buffered
// stdout/stderr, maxBuffer enforcement, exit-code/signal error shape) that
// every caller here relies on (issue #1518).
class BufferedProcessExecution {
  constructor(file, args, config, resolve, reject) {
    this.file = file;
    this.args = args;
    this.config = config;
    this.resolve = resolve;
    this.reject = reject;
    this.timedOut = false;
    this.aborted = false;
    this.maxBufferExceeded = null;
    this.killTimer = null;
    this.settled = false;
    this.pendingCleanup = null;
    this.stdoutChunks = [];
    this.stderrChunks = [];
    this.stdoutLen = 0;
    this.stderrLen = 0;
    this.stdoutDone = false;
    this.stderrDone = false;
    this.closeResult = null;
  }

  start() {
    const { file, args, config } = this;
    // Detached so child.pid is also its POSIX process-group id: signalling
    // -pid reaches every subprocess the child spawns (e.g. a shell tool
    // call), not just the direct child. A signal to the direct pid alone let
    // a codex-spawned `ugrep` outlive its leader and run orphaned for 10+
    // days once reparented to init (issue #1518).
    this.child = this.config.spawnImpl(file, args, { ...config.options, detached: true });
    this.attachOutputHandlers();
    this.attachLifecycleHandlers();
    this.armTerminationTriggers();
    if (config.input != null) this.child.stdin.end(config.input);
  }

  ensureGroupEmpty() {
    // TERM the group, then SIGKILL anything still alive after the grace period,
    // confirming the group is actually empty before resolving. Settlement awaits
    // this (see the `close` handler) so a SIGTERM-ignoring descendant can't
    // outlive a call that already resolved or rejected. Single-flight via
    // `pendingCleanup`: concurrent triggers (timeout, abort, maxBuffer,
    // leader-exit) collapse onto one in-flight escalation. The escalation itself
    // is the shared terminateProcessGroup primitive (issue #1495).
    if (this.pendingCleanup !== null) return this.pendingCleanup;
    if (!this.child.pid || !isProcessGroupAlive(this.child.pid)) return Promise.resolve();
    this.pendingCleanup = terminateProcessGroup(this.child.pid, {
      killSignal: this.config.killSignal,
      killGraceMs: this.config.killGraceMs,
      label: this.file,
    });
    return this.pendingCleanup;
  }

  trackChunk(chunks, chunk, which, currentLen) {
    const { maxBuffer } = this.config;
    // Saturates currentLen to maxBuffer on overflow so every later chunk for
    // this stream is dropped outright, instead of re-appending the same
    // stale "remaining allowance" on every subsequent data event.
    if (currentLen >= maxBuffer) return currentLen;
    const length = Buffer.byteLength(chunk);
    if (currentLen + length > maxBuffer) {
      const allowed = maxBuffer - currentLen;
      if (allowed > 0) chunks.push(chunk.slice(0, allowed));
      if (!this.maxBufferExceeded && !this.timedOut && !this.aborted) {
        this.maxBufferExceeded = which;
        // maxBuffer is the first terminal cause. Cleanup can legitimately
        // outlive timeoutMs, but that later timer must not rewrite the result.
        if (this.killTimer) {
          clearTimeout(this.killTimer);
          this.killTimer = null;
        }
        this.ensureGroupEmpty();
      }
      return maxBuffer;
    }
    chunks.push(chunk);
    return currentLen + length;
  }

  attachOutputHandlers() {
    this.child.stdout.setEncoding("utf8");
    this.child.stderr.setEncoding("utf8");
    this.child.stdout.on("data", (chunk) => {
      this.stdoutLen = this.trackChunk(this.stdoutChunks, chunk, "stdout", this.stdoutLen);
    });
    this.child.stderr.on("data", (chunk) => {
      this.stderrLen = this.trackChunk(this.stderrChunks, chunk, "stderr", this.stderrLen);
    });
  }

  markStreamDone(which) {
    if (which === "stdout") this.stdoutDone = true;
    else this.stderrDone = true;
    this.maybeFinalize();
  }

  attachLifecycleHandlers() {
    this.child.on("error", (error) => this.finish(this.reject, error));
    // `close` is documented to fire only after the child's stdio streams
    // have closed, but that ordering guarantee is not airtight in practice —
    // Node has long-standing reports of a fast-exiting child's buffered
    // stdout being reported as fully consumed before every 'data' event for
    // it has actually been delivered (nodejs/node#9633, #7184, #4236).
    // Waiting on each stream's own `end` (or `error`, so a stream fault
    // can't hang this call forever) makes "every byte the child wrote before
    // exiting was read" an explicit, per-stream guarantee instead of an
    // inference from the child's own close event.
    this.child.stdout.on("end", () => this.markStreamDone("stdout"));
    this.child.stdout.on("error", () => this.markStreamDone("stdout"));
    this.child.stderr.on("end", () => this.markStreamDone("stderr"));
    this.child.stderr.on("error", () => this.markStreamDone("stderr"));
    this.child.on("close", (code, closeSignal) => {
      this.closeResult = { code, closeSignal };
      this.maybeFinalize();
    });
    // The leader may exit while a background descendant it spawned (and did
    // not wait on) is still running. `close` — which the handler above waits
    // for — doesn't fire until every process sharing the child's stdio pipes
    // exits, so a live straggler would otherwise hang this call forever.
    // `exit` fires as soon as the leader itself terminates, independent of
    // its descendants, which is what actually lets this reap them.
    this.child.on("exit", () => this.ensureGroupEmpty());
  }

  maybeFinalize() {
    if (!this.closeResult || !this.stdoutDone || !this.stderrDone) return;
    // Await in-flight cleanup before settling so descendants cannot outlive the call.
    Promise.resolve(this.pendingCleanup).then(() => this.settleFromClose());
  }

  settleFromClose() {
    const { code, closeSignal } = this.closeResult;
    const { timeoutMs, killSignal, killGraceMs } = this.config;
    const stdout = this.stdoutChunks.join("");
    const stderr = this.stderrChunks.join("");
    if (this.timedOut || this.aborted) {
      const error = new Error(
        this.timedOut
          ? `${this.file} did not exit within ${timeoutMs}ms (sent ${killSignal}, then SIGKILL after ${killGraceMs}ms grace)`
          : `${this.file} aborted via AbortSignal`,
      );
      error.code = this.timedOut ? "ETIMEDOUT" : "ABORT_ERR";
      if (this.aborted && !this.timedOut) error.name = "AbortError";
      error.killed = true;
      error.stdout = stdout;
      error.stderr = stderr;
      this.finish(this.reject, error);
      return;
    }
    if (this.maxBufferExceeded) {
      const error = new Error(`${this.file} exceeded maxBuffer on ${this.maxBufferExceeded}`);
      error.code = "ERR_CHILD_PROCESS_STDIO_MAXBUFFER";
      error.stdout = stdout;
      error.stderr = stderr;
      this.finish(this.reject, error);
      return;
    }
    if (code !== 0 || closeSignal !== null) {
      const error = new Error(`Command failed: ${this.file} ${this.args.join(" ")}\n${stderr}`);
      error.code = code;
      error.signal = closeSignal;
      error.stdout = stdout;
      error.stderr = stderr;
      this.finish(this.reject, error);
      return;
    }
    this.finish(this.resolve, { stdout, stderr });
  }

  finish(fn, value) {
    if (this.settled) return;
    this.settled = true;
    if (this.killTimer) clearTimeout(this.killTimer);
    fn(value);
  }

  armTerminationTriggers() {
    const { timeoutMs, signal } = this.config;
    if (timeoutMs && timeoutMs > 0) {
      this.killTimer = setTimeout(() => {
        if (this.maxBufferExceeded || this.aborted) return;
        this.timedOut = true;
        this.ensureGroupEmpty();
      }, timeoutMs);
    }
    if (signal) {
      const onAbort = () => {
        if (this.timedOut || this.maxBufferExceeded || this.aborted) return;
        this.aborted = true;
        if (this.killTimer) {
          clearTimeout(this.killTimer);
          this.killTimer = null;
        }
        this.ensureGroupEmpty();
      };
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    }
  }
}

export async function execFileWithInput(
  file,
  args,
  {
    input,
    timeoutMs,
    killSignal = "SIGTERM",
    killGraceMs = KILL_GRACE_MS_DEFAULT,
    signal,
    maxBuffer = MAX_BUFFER_DEFAULT,
    // Test seam only: lets a unit test drive the buffering/settle state machine
    // with a fake child (a controllable stream) so the maxBuffer-bounding and
    // maxBuffer-vs-abort-precedence contracts are asserted deterministically
    // instead of racing a real child's scheduling under load (issue #1532).
    // Production callers never pass this; it defaults to the real spawn().
    spawnImpl = spawn,
    ...options
  } = {},
) {
  // Ground Control and its CI run on Linux; fail closed when the supported
  // POSIX process-group termination contract is unavailable (issue #1518).
  if (!isPosixProcessGroupCapable()) {
    throw new Error(
      `execFileWithInput requires POSIX process-group support to bound ${file}'s subprocess tree; `
      + "this platform has no tested tree-termination equivalent (issue #1518)",
    );
  }
  return await new Promise((resolve, reject) => {
    new BufferedProcessExecution(file, args, {
      input,
      timeoutMs,
      killSignal,
      killGraceMs,
      signal,
      maxBuffer,
      spawnImpl,
      options,
    }, resolve, reject).start();
  });
}
