// Process-group containment for long-running model subprocesses (issue #1518).
//
// POSIX-only: Ground Control and its CI run on Linux. There is no tested
// Windows tree-termination equivalent, so a caller that needs bounded
// containment must fail closed on other platforms rather than silently
// degrade to signalling only the direct child.

export function isPosixProcessGroupCapable() {
  return process.platform !== "win32";
}

export function isProcessGroupAlive(pgid) {
  try {
    process.kill(-pgid, 0);
    return true;
  } catch {
    return false;
  }
}

export function signalProcessGroup(pgid, sig) {
  try {
    process.kill(-pgid, sig);
    return { ok: true };
  } catch (error) {
    if (error.code === "ESRCH") return { ok: true };
    return { ok: false, error };
  }
}
