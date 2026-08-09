// Extracted from runtime-primitives.js (issue #1518) to stay under the
// repo's 500-LOC file gate. runtime-primitives.js re-exports execFileWithInput
// and the timeout constants, so every existing caller's import path is
// unchanged.

import { spawn } from "node:child_process";
import { isProcessGroupAlive, isPosixProcessGroupCapable, signalProcessGroup } from "./process-group.js";

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
    ...options
  } = {},
) {
  // Ground Control and its CI run on Linux; POSIX process groups are the
  // supported tree-termination contract. Fail closed rather than silently
  // degrade to a direct-child-only kill on a platform that can't provide it
  // (issue #1518).
  if (!isPosixProcessGroupCapable()) {
    throw new Error(
      `execFileWithInput requires POSIX process-group support to bound ${file}'s subprocess tree; `
      + "this platform has no tested tree-termination equivalent (issue #1518)",
    );
  }
  return await new Promise((resolve, reject) => {
    let timedOut = false;
    let aborted = false;
    let maxBufferExceeded = null;
    let killTimer = null;
    let graceTimer = null;
    let settled = false;
    let pendingCleanup = null;

    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      if (killTimer) clearTimeout(killTimer);
      fn(value);
    };

    // Detached so child.pid is also its POSIX process-group id: signalling
    // -pid reaches every subprocess the child spawns (e.g. a shell tool
    // call), not just the direct child. A signal to the direct pid alone let
    // a codex-spawned `ugrep` outlive its leader and run orphaned for 10+
    // days once reparented to init (issue #1518).
    const child = spawn(file, args, { ...options, detached: true });

    const killGroup = (sig) => {
      if (!child.pid) return;
      const result = signalProcessGroup(child.pid, sig);
      if (!result.ok) {
        // eslint-disable-next-line no-console
        console.error(`[execFileWithInput] failed to signal ${file}'s process group with ${sig}: ${result.error.message}`);
      }
    };

    // TERM the group, then SIGKILL anything still alive after the grace
    // period, confirming the group is actually empty before resolving.
    // Returns a promise that resolves only once that full escalation
    // completes — settlement awaits it (see the `close` handler) so a
    // SIGTERM-ignoring descendant can't outlive a call that already resolved
    // or rejected. Polls rather than waiting out a single fixed delay: most
    // signalled processes die well inside the grace window, and death is
    // never instantaneous with the signal call returning (true even for
    // SIGKILL — the kernel needs a moment to actually reap the group), so a
    // one-shot check-then-settle races both directions. A no-op re-signal on
    // an already-empty group is safe, so concurrent triggers (timeout,
    // abort, maxBuffer, leader-exit) collapse onto this one in-flight
    // escalation rather than racing separate ones.
    const POLL_INTERVAL_MS = 20;
    const POST_SIGKILL_CONFIRM_MS = 2000; // bounded; SIGKILL cannot be blocked, only delayed by the kernel
    const ensureGroupEmpty = () => {
      if (pendingCleanup) return pendingCleanup;
      if (!child.pid || !isProcessGroupAlive(child.pid)) return Promise.resolve();
      const pgid = child.pid;
      pendingCleanup = new Promise((settleCleanup) => {
        killGroup(killSignal);
        const termDeadline = Date.now() + killGraceMs;
        let killSent = false;
        let killDeadline = null;
        const poll = () => {
          if (!isProcessGroupAlive(pgid)) {
            settleCleanup();
            return;
          }
          const now = Date.now();
          if (!killSent && now >= termDeadline) {
            killGroup("SIGKILL");
            killSent = true;
            killDeadline = now + POST_SIGKILL_CONFIRM_MS;
          } else if (killSent && now >= killDeadline) {
            // eslint-disable-next-line no-console
            console.error(`[execFileWithInput] ${file}'s process group survived SIGKILL (pgid ${pgid})`);
            settleCleanup();
            return;
          }
          graceTimer = setTimeout(poll, POLL_INTERVAL_MS);
        };
        graceTimer = setTimeout(poll, POLL_INTERVAL_MS);
      });
      return pendingCleanup;
    };

    const stdoutChunks = [];
    const stderrChunks = [];
    let stdoutLen = 0;
    let stderrLen = 0;
    // Saturates currentLen to maxBuffer on overflow so every later chunk for
    // this stream is dropped outright, instead of re-appending the same
    // stale "remaining allowance" on every subsequent data event.
    const trackChunk = (chunks, chunk, which, currentLen) => {
      if (currentLen >= maxBuffer) return currentLen;
      const length = Buffer.byteLength(chunk);
      if (currentLen + length > maxBuffer) {
        const allowed = maxBuffer - currentLen;
        if (allowed > 0) chunks.push(chunk.slice(0, allowed));
        if (!maxBufferExceeded) {
          maxBufferExceeded = which;
          ensureGroupEmpty();
        }
        return maxBuffer;
      }
      chunks.push(chunk);
      return currentLen + length;
    };

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdoutLen = trackChunk(stdoutChunks, chunk, "stdout", stdoutLen);
    });
    child.stderr.on("data", (chunk) => {
      stderrLen = trackChunk(stderrChunks, chunk, "stderr", stderrLen);
    });

    child.on("error", (error) => {
      finish(reject, error);
    });

    child.on("close", (code, closeSignal) => {
      // Await any in-flight group cleanup before settling — a straggler
      // descendant must be confirmed gone (or given its full TERM-then-KILL
      // treatment) before the caller sees a terminal result, not just before
      // the leader's own stdio has drained.
      Promise.resolve(pendingCleanup).then(() => {
        const stdout = stdoutChunks.join("");
        const stderr = stderrChunks.join("");
        if (timedOut || aborted) {
          const e = new Error(
            timedOut
              ? `${file} did not exit within ${timeoutMs}ms (sent ${killSignal}, then SIGKILL after ${killGraceMs}ms grace)`
              : `${file} aborted via AbortSignal`,
          );
          e.code = timedOut ? "ETIMEDOUT" : "ABORT_ERR";
          if (aborted && !timedOut) e.name = "AbortError";
          e.killed = true;
          e.stdout = stdout;
          e.stderr = stderr;
          finish(reject, e);
          return;
        }
        if (maxBufferExceeded) {
          const e = new Error(`${file} exceeded maxBuffer on ${maxBufferExceeded}`);
          e.code = "ERR_CHILD_PROCESS_STDIO_MAXBUFFER";
          e.stdout = stdout;
          e.stderr = stderr;
          finish(reject, e);
          return;
        }
        if (code !== 0 || closeSignal !== null) {
          const e = new Error(`Command failed: ${file} ${args.join(" ")}\n${stderr}`);
          e.code = code;
          e.signal = closeSignal;
          e.stdout = stdout;
          e.stderr = stderr;
          finish(reject, e);
          return;
        }
        finish(resolve, { stdout, stderr });
      });
    });

    // The leader may exit while a background descendant it spawned (and did
    // not wait on) is still running. `close` — which the handler above waits
    // for — doesn't fire until every process sharing the child's stdio pipes
    // exits, so a live straggler would otherwise hang this call forever.
    // `exit` fires as soon as the leader itself terminates, independent of
    // its descendants, which is what actually lets this reap them.
    child.on("exit", () => {
      ensureGroupEmpty();
    });

    if (timeoutMs && timeoutMs > 0) {
      killTimer = setTimeout(() => {
        timedOut = true;
        ensureGroupEmpty();
      }, timeoutMs);
    }

    if (signal) {
      const onAbort = () => {
        aborted = true;
        ensureGroupEmpty();
      };
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    }

    if (input != null) {
      child.stdin.end(input);
    }
  });
}
