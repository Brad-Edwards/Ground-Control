// Test support for gc-implement-mechanical.runimplementmechanical-live-lifecycle-emission.test.js
// (kept out of the test file to stay under the 500-line file gate, issue #1532).
//
// runImplementMechanical results carry wall-clock timing: `duration_ms` on each
// gate in `timings`, and a top-level `dominant_gate` derived purely from those
// durations. Those values differ run-to-run and, because `dominant_gate` is just
// the highest-duration gate, it flips on sub-millisecond scheduler jitter under
// CPU load — which made the emitter-fail-open test (which deepEquals two
// independently-timed runs) flaky. Normalizing the timing fields before the
// comparison keeps the real contract — a broken emitter must not change the
// SUBSTANTIVE result — while removing the timing noise that was never part of it.
// Every other field is left untouched, so a genuine regression still goes red.
export function stripVolatileTimings(result) {
  const walk = (node) => {
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    if (!node || typeof node !== "object") return;
    if ("duration_ms" in node) node.duration_ms = 0;
    if ("dominant_gate" in node) node.dominant_gate = "<normalized>";
    Object.values(node).forEach(walk);
  };
  const clone = structuredClone(result);
  walk(clone);
  return clone;
}
