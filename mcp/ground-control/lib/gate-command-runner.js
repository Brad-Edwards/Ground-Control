// A size-safe runner for the repository gate commands (completion, policy, and
// pre-commit) that the implement/quickfix workflow runs on a checkout (issue
// #1501).
//
// Those gates are invoked through the same execFile-shaped seam the git calls
// use, which means Node's `child_process.execFile` and its 1 MiB `maxBuffer`
// default. A gate command's console output is not a bounded value like a git
// SHA: a full test suite's stdout on a large merged tree routinely exceeds a
// megabyte. execFile treats that overflow as fatal — it kills the child and
// rejects with ERR_CHILD_PROCESS_STDIO_MAXBUFFER *before* the child exits — so
// the gate's own pass/fail exit status is never observed and the completion
// boundary can never be satisfied. The failure is output-size driven, so a
// retry re-hits it deterministically.
//
// The gate boundary only needs the exit status; the full transcript is
// discarded on success. This runner therefore streams stdout/stderr and keeps
// only a bounded tail for a failure diagnostic, so it never holds the whole
// transcript in memory and never overflows regardless of how verbose the gate
// command is. Its resolve/reject shape mirrors execFile (`{stdout, stderr}` on
// success; an Error carrying `code`, `signal`, `stdout`, and `stderr` on a
// non-zero exit or a terminating signal) so it is a drop-in substitute at the
// existing call sites and the shared failure formatters keep working.

import { spawn } from "node:child_process";

// 64 KiB per stream is far more than a failure diagnostic needs while staying a
// hard ceiling on retained memory. A test suite's actionable failure output
// lives at the tail, which is exactly what is kept.
const GATE_TAIL_BYTES = 64 * 1024;

// Retains only the last `limitBytes` of everything pushed. Concatenating then
// slicing on each chunk keeps the live buffer at or below the cap; a truncated
// leading multibyte character can only ever land in a diagnostic tail, never in
// the exit status the boundary actually reads.
function boundedTail(limitBytes) {
  let buffer = Buffer.alloc(0);
  return {
    push(chunk) {
      const combined = Buffer.concat([buffer, chunk]);
      buffer = combined.length > limitBytes ? combined.subarray(combined.length - limitBytes) : combined;
    },
    toString() {
      return buffer.toString("utf8");
    },
  };
}

export async function runGateCommand(file, args, options = {}) {
  // onActivity is a progress hook, not a spawn option: pull it out so spawn only
  // ever sees real child_process options. It fires on every drained chunk so a
  // caller can prove the child is still producing output — the signal that tells
  // a slow-but-healthy sweep from a dead job (issue #1497).
  const { onActivity, ...spawnOptions } = options;
  return await new Promise((resolve, reject) => {
    const child = spawn(file, args, spawnOptions);
    const stdoutTail = boundedTail(GATE_TAIL_BYTES);
    const stderrTail = boundedTail(GATE_TAIL_BYTES);
    // Draining both pipes as data arrives is also what prevents the child from
    // blocking on a full OS pipe buffer once its output passes ~64 KiB — the
    // failure mode a "discard the output" runner that stopped reading would
    // reintroduce.
    child.stdout.on("data", (chunk) => {
      stdoutTail.push(chunk);
      if (typeof onActivity === "function") onActivity("stdout", chunk.length);
    });
    child.stderr.on("data", (chunk) => {
      stderrTail.push(chunk);
      if (typeof onActivity === "function") onActivity("stderr", chunk.length);
    });
    child.on("error", (error) => reject(error));
    child.on("close", (code, signal) => {
      const stdout = stdoutTail.toString();
      const stderr = stderrTail.toString();
      if (code === 0 && signal == null) {
        resolve({ stdout, stderr });
        return;
      }
      const error = new Error(`Command failed: ${file} ${args.join(" ")}\n${stderr}`);
      error.code = code ?? undefined;
      error.signal = signal ?? undefined;
      error.stdout = stdout;
      error.stderr = stderr;
      reject(error);
    });
  });
}
