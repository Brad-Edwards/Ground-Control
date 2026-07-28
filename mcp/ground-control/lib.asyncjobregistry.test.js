// Re-homed from lib.test.js under issue #1473 (async job registry) atop the
// issue #1467 500-LOC split (docs/CODING_STANDARDS.md). The former async
// review-job registry generalized into the shared async job registry; these
// tests moved into their own module to keep every split file under the limit.
// Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { TEST_QUALITY_REVIEW_TIMEOUT_MS } from "./lib.js";

describe("test-quality reviewer process lifetime", () => {
  it("allows repository-scale reviews to outlive the former ten-minute ceiling", () => {
    assert.equal(TEST_QUALITY_REVIEW_TIMEOUT_MS, 30 * 60 * 1000);
    assert.ok(TEST_QUALITY_REVIEW_TIMEOUT_MS > 600_000);
  });
});

// ---------------------------------------------------------------------------
// Async job registry (gc_codex_job, issues #937 and #1473)
// ---------------------------------------------------------------------------

describe("async job registry (gc_codex_job, issues #937 and #1473)", () => {
  const flush = () => new Promise((r) => setImmediate(r));

  it("startAsyncJob returns a bounded opaque handle and echoes kind", async () => {
    const { startAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    let resolveRun;
    const start = startAsyncJob("codex_review", () => new Promise((r) => { resolveRun = r; }));
    assert.equal(start.ok, true);
    assert.equal(start.status, "running");
    assert.equal(start.kind, "codex_review");
    assert.match(start.job_id, /^job-[a-z0-9]+-[a-z0-9]+$/);
    assert.ok(start.job_id.length <= 80);
    await flush();
    resolveRun({ ok: true });
  });

  it("pollAsyncJob reports running, then repeatably returns the exact result envelope", async () => {
    const { startAsyncJob, pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    let resolveRun;
    const start = startAsyncJob(
      "codex_review_cycle",
      () => new Promise((r) => { resolveRun = r; }),
    );
    // runFn runs one microtask after start; flush so its executor binds resolveRun.
    await flush();

    const running = pollAsyncJob(start.job_id);
    assert.equal(running.ok, true);
    assert.equal(running.status, "running");
    assert.equal(running.job_id, start.job_id);

    const envelope = { ok: true, next_action: "post_clean_decision_record_and_advance_to_phase_c" };
    resolveRun(envelope);
    await flush();

    const done = pollAsyncJob(start.job_id);
    assert.equal(done.ok, true);
    assert.equal(done.status, "done");
    assert.deepEqual(done.result, envelope);
    assert.deepEqual(pollAsyncJob(start.job_id), done);
  });

  it("pollAsyncJob rejects an unknown id without echoing caller input", async () => {
    const { pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const unknown = "job-does-not-exist-1";
    const r = pollAsyncJob(unknown);
    assert.equal(r.ok, false);
    assert.equal(r.error, "job_not_found");
    assert.doesNotMatch(r.message, new RegExp(unknown));
  });

  it("a runFn that rejects surfaces as a failed job", async () => {
    const { startAsyncJob, pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const start = startAsyncJob(
      "test_quality_review",
      () => Promise.reject(new Error("codex exec blew up")),
    );
    await flush();
    const done = pollAsyncJob(start.job_id);
    assert.equal(done.ok, false);
    assert.equal(done.status, "failed");
    assert.equal(done.error, "job_failed");
    assert.match(done.message, /codex exec blew up/);
    assert.equal(done.stack, undefined);
  });

  it("bounds and scrubs an unexpected job rejection", async () => {
    const { startAsyncJob, pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const sensitiveError = new Error(`token=${"ghp_" + "a".repeat(36)}`);
    const oversizedError = new Error("x".repeat(2000));
    const sensitive = startAsyncJob(
      "codex_review",
      () => Promise.reject(sensitiveError),
    );
    const oversized = startAsyncJob(
      "test_quality_review",
      () => Promise.reject(oversizedError),
    );
    await flush();
    sensitiveError.message = "mutated after rejection";
    oversizedError.message = "mutated after rejection";
    assert.equal(pollAsyncJob(sensitive.job_id).message, "<redacted>");
    const oversizedMessage = pollAsyncJob(oversized.job_id).message;
    assert.equal(oversizedMessage.length, 600);
    assert.match(oversizedMessage, /…$/);
  });

  it("cancelAsyncJob aborts cancellable review work and the job ends cancelled", async () => {
    const { startAsyncJob, pollAsyncJob, cancelAsyncJob, _resetAsyncJobsForTest } =
      await import("./lib.js");
    _resetAsyncJobsForTest();
    let sawAbort = false;
    const start = startAsyncJob("codex_review", (signal) => new Promise((resolve, reject) => {
      const onAbort = () => { sawAbort = true; reject(new Error("aborted")); };
      if (signal.aborted) { onAbort(); return; }
      signal.addEventListener("abort", onAbort);
    }));
    // Let runFn run and register its abort listener before cancelling.
    await flush();
    const cancel = cancelAsyncJob(start.job_id);
    assert.equal(cancel.ok, true);
    assert.equal(cancel.status, "cancelling");
    await flush();
    assert.equal(sawAbort, true, "runFn must observe the abort signal so the child is killed");
    const done = pollAsyncJob(start.job_id);
    assert.equal(done.ok, false);
    assert.equal(done.status, "cancelled");
    assert.equal(done.error, "job_cancelled");
  });

  it("refuses cancellation when the job cannot honestly honor abort", async () => {
    const { startAsyncJob, pollAsyncJob, cancelAsyncJob, _resetAsyncJobsForTest } =
      await import("./lib.js");
    _resetAsyncJobsForTest();
    let resolveRun;
    const start = startAsyncJob(
      "implement_mechanical_verify",
      () => new Promise((resolve) => { resolveRun = resolve; }),
      { cancellable: false },
    );
    await flush();
    const cancel = cancelAsyncJob(start.job_id);
    assert.equal(cancel.ok, false);
    assert.equal(cancel.status, "running");
    assert.equal(cancel.error, "job_not_cancellable");
    assert.equal(pollAsyncJob(start.job_id).status, "running");
    resolveRun({ ok: true, action: "verify" });
    await flush();
  });

  it("reuses the same job for an identical idempotent start, including after completion", async () => {
    const { startAsyncJob, pollAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    let resolveRun;
    let runCount = 0;
    const run = () => {
      runCount += 1;
      return new Promise((resolve) => { resolveRun = resolve; });
    };
    const options = {
      idempotencyKey: "attempt-1473-verify-1",
      idempotencyNamespace: "repo:/repo:issue:1473:action:verify",
      fingerprint: "a".repeat(64),
    };
    const first = startAsyncJob("implement_mechanical_verify", run, options);
    const duplicate = startAsyncJob("implement_mechanical_verify", run, options);
    assert.equal(duplicate.job_id, first.job_id);
    assert.equal(duplicate.status, "running");
    await flush();
    assert.equal(runCount, 1);

    const envelope = { ok: false, action: "verify", agent_required: true };
    resolveRun(envelope);
    await flush();
    const terminalDuplicate = startAsyncJob("implement_mechanical_verify", run, options);
    assert.equal(terminalDuplicate.job_id, first.job_id);
    assert.equal(terminalDuplicate.status, "done");
    assert.deepEqual(terminalDuplicate.result, envelope);
    assert.deepEqual(pollAsyncJob(first.job_id).result, envelope);
    assert.equal(runCount, 1);
  });

  it("rejects reuse of one idempotency key for different normalized input", async () => {
    const { startAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const common = {
      idempotencyKey: "attempt-1473-verify-1",
      idempotencyNamespace: "repo:/repo:issue:1473:action:verify",
    };
    const first = startAsyncJob(
      "implement_mechanical_verify",
      () => new Promise(() => {}),
      { ...common, fingerprint: "a".repeat(64) },
    );
    const conflict = startAsyncJob(
      "implement_mechanical_verify",
      () => Promise.resolve({ ok: true }),
      { ...common, fingerprint: "b".repeat(64) },
    );
    assert.equal(first.ok, true);
    assert.equal(conflict.ok, false);
    assert.equal(conflict.error, "job_idempotency_conflict");
    assert.equal(conflict.job_id, undefined);
  });

  it("enforces single flight for distinct checkout-bound mutation attempts", async () => {
    const { startAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    const first = startAsyncJob(
      "implement_mechanical_verify",
      () => new Promise(() => {}),
      {
        idempotencyKey: "attempt-verify-1",
        idempotencyNamespace: "repo:/repo:issue:1473:action:verify",
        fingerprint: "a".repeat(64),
        executionScope: "mechanical-checkout:/repo",
        singleFlight: true,
      },
    );
    const contended = startAsyncJob(
      "implement_mechanical_publish",
      () => Promise.resolve({ ok: true }),
      {
        idempotencyKey: "attempt-publish-1",
        idempotencyNamespace: "repo:/repo:issue:1473:action:publish",
        fingerprint: "b".repeat(64),
        executionScope: "mechanical-checkout:/repo",
        singleFlight: true,
      },
    );
    assert.equal(first.ok, true);
    assert.equal(contended.ok, false);
    assert.equal(contended.error, "job_execution_contended");
    assert.equal(contended.job_id, undefined);
  });

  it("refuses new work at capacity without evicting a running job", async () => {
    const {
      startAsyncJob,
      pollAsyncJob,
      _resetAsyncJobsForTest,
      _setAsyncJobCapacityForTest,
    } = await import("./lib.js");
    _resetAsyncJobsForTest();
    _setAsyncJobCapacityForTest(1);
    const running = startAsyncJob("codex_review", () => new Promise(() => {}));
    const refused = startAsyncJob("test_quality_review", () => Promise.resolve({ ok: true }));
    assert.equal(refused.ok, false);
    assert.equal(refused.error, "job_capacity_exhausted");
    assert.equal(pollAsyncJob(running.job_id).status, "running");
    _resetAsyncJobsForTest();
  });

  it("reaps terminal jobs only after the bounded retention TTL", async () => {
    const {
      ASYNC_JOB_TTL_MS,
      cancelAsyncJob,
      startAsyncJob,
      pollAsyncJob,
      _resetAsyncJobsForTest,
      _setAsyncJobClockForTest,
    } = await import("./lib.js");
    _resetAsyncJobsForTest();
    let now = 1000;
    _setAsyncJobClockForTest(() => now);
    const start = startAsyncJob("codex_review", () => Promise.resolve({ ok: true }));
    await flush();
    assert.equal(pollAsyncJob(start.job_id).status, "done");
    now += ASYNC_JOB_TTL_MS + 1;
    assert.equal(pollAsyncJob(start.job_id).error, "job_not_found");
    assert.equal(cancelAsyncJob(start.job_id).error, "job_not_found");
    _resetAsyncJobsForTest();
  });

  it("cancelAsyncJob is idempotent on a terminal job and 404s an unknown id", async () => {
    const { startAsyncJob, cancelAsyncJob, _resetAsyncJobsForTest } =
      await import("./lib.js");
    _resetAsyncJobsForTest();
    const start = startAsyncJob("codex_review", () => Promise.resolve({ ok: true }));
    await flush();
    const cancelTerminal = cancelAsyncJob(start.job_id);
    assert.equal(cancelTerminal.ok, true);
    assert.equal(cancelTerminal.status, "done");
    const cancelMissing = cancelAsyncJob("job-nope-1");
    assert.equal(cancelMissing.ok, false);
    assert.equal(cancelMissing.error, "job_not_found");
  });

  it("startAsyncJob rejects a non-function runFn", async () => {
    const { startAsyncJob, _resetAsyncJobsForTest } = await import("./lib.js");
    _resetAsyncJobsForTest();
    assert.throws(() => startAsyncJob("codex_review", null), /runFn must be a function/);
  });
});
