// Live workflow-run lifecycle observation (issue #1435, ADR-061 amendment, ADR-090).
//
// Before this, the only writer of the ADR-061 read-model was gc_workflow_run_ingest, which
// reconstructs a run from issue-thread markers after the fact and has to be invoked deliberately.
// Nothing invoked it, so the reporting model held no runs at all. This module lets the MCP tool
// layer record a run as it happens: opened when the run starts, one event per gate boundary while
// the run is still open, and a terminal state when the tool layer can observe the run ending.
//
// Boundaries this module must respect:
//   - It records; it never authorizes, advances, retries, or fails a workflow phase. ADR-061's
//     read-model is not an executor.
//   - It is strictly fail-open. The workflow result is determined before anything is emitted, and
//     no backend outage, rejection, timeout, or malformed response may change that result. A caller
//     that can be broken by telemetry is a caller that has made telemetry a gate.
//   - It records only the boundary that just occurred. It never calls the issue-thread ingest path,
//     which would re-append every historical event on every boundary.
//   - Only the closed field set reaches the backend: no prompts, payloads, reviewer prose, response
//     bodies, credentials, or stack traces, in the request or in a diagnostic.

import { createWorkflowRun, recordWorkflowRunEvent } from "./lib.js";

/**
 * Provenance for a fact the tool layer observed directly as a phase transitioned. Distinct from
 * ISSUE_THREAD, which names a fact reconstructed from the durable issue record: the two have
 * different freshness and different reconciliation semantics, and conflating them would make the
 * bridge seam meaningless (ADR-061 §2).
 */
export const LIFECYCLE_PROVENANCE = "LIVE_EMISSION";

/** Per-call bound on a telemetry write, so a hung backend cannot stall a workflow phase. */
const EMIT_TIMEOUT_MS = 2000;

/** Longest stable error code forwarded as an event outcome. */
const MAX_OUTCOME_LENGTH = 100;

/**
 * Failure class for a bounded diagnostic. Only a stable code or error name is ever used — never the
 * message, which routinely carries URLs, response bodies, or credential material.
 */
function failureClass(error) {
  const code = error?.code ?? error?.name;
  return typeof code === "string" && /^[A-Za-z0-9_.-]{1,60}$/.test(code) ? code : "unknown";
}

/** Stable, bounded outcome code for a station result. Free-form prose is never forwarded. */
function outcomeCode(value) {
  if (typeof value !== "string" || value === "") return undefined;
  return value.length <= MAX_OUTCOME_LENGTH ? value : value.slice(0, MAX_OUTCOME_LENGTH);
}

const defaultDeps = {
  createRun: createWorkflowRun,
  recordEvent: recordWorkflowRunEvent,
  baseUrlPresent: () => typeof process.env.GC_BASE_URL === "string" && process.env.GC_BASE_URL.trim() !== "",
  now: () => new Date(),
  monotonic: () => Date.now(),
  log: (line) => console.error(line),
};

/**
 * Build a lifecycle emitter bound to one run identity.
 *
 * The identity is the ADR-061 natural key `(project, repo, issueNumber, branch)` plus the run
 * dimensions the tool layer can authoritatively supply. Workflow type and station are parameters
 * rather than constants so a later lane (quickfix, review) reuses this seam instead of copying it.
 *
 * @param {object} p
 * @param {string} p.project           Ground Control project identifier
 * @param {string} [p.repo]            owner/name of the GitHub repository
 * @param {number} p.issueNumber       GitHub issue number — the work item
 * @param {string} p.branch            feature branch for this attempt
 * @param {string} p.workflowType      closed WorkflowType vocabulary value
 * @param {string} [p.runtimeDriver]   agent/runtime that is executing the run
 * @param {string[]} [p.requirementUids] in-scope requirement UIDs
 * @param {number} [p.prNumber]        pull request carrying this attempt, once one exists
 * @param {object} [p.deps]            injected collaborators (tests and alternate transports)
 */
export function createWorkflowRunLifecycleEmitter({
  project,
  repo,
  issueNumber,
  branch,
  workflowType,
  runtimeDriver,
  requirementUids,
  prNumber,
  deps = {},
} = {}) {
  const d = { ...defaultDeps, ...deps };

  let runId = null;
  // One outage should cost one bounded wait, not one per boundary: after the first failure the
  // emitter stays quiet for the rest of the run and reconciliation picks the run up later.
  let disabled = false;

  // Transport runs on this chain, never on the workflow's control path. Every public method
  // timestamps the transition immediately, appends the write, and returns — so a hung backend can
  // delay neither the start of a phase nor the delivery of its result, which a synchronous await
  // would do even with a timeout. The chain is FIFO, so events still reach the backend in the order
  // they occurred and a later task can read what an earlier one resolved (the attempt ordinal).
  let queue = Promise.resolve();

  function enqueue(task) {
    queue = queue.then(task).catch(() => {});
    return queue;
  }

  function diagnose(operation, error) {
    d.log(
      `[workflow-run-lifecycle] ${operation} failed: project=${project} issue=${issueNumber} ` +
        `failure=${failureClass(error)}`,
    );
  }

  /**
   * Run one backend write under the fail-open contract: skipped when there is nothing to write to,
   * bounded by a timeout, and swallowed on failure with a bounded diagnostic.
   */
  async function safe(operation, fn) {
    if (disabled || !d.baseUrlPresent()) return null;
    let timer;
    try {
      return await Promise.race([
        fn(AbortSignal.timeout(EMIT_TIMEOUT_MS)),
        // Backstop for a transport that ignores the abort signal, so a hung socket still resolves
        // the wait. Carries a code so the diagnostic names the timeout rather than a bare Error.
        // Deliberately not unref'd: an unref'd timer lets the event loop drain while this promise is
        // still pending, so the bound would never fire and the caller would wait forever — the exact
        // failure this exists to prevent. The finally below is what keeps it from outliving the race.
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            const timeout = new Error("emit timed out");
            timeout.code = "emit_timeout";
            reject(timeout);
          }, EMIT_TIMEOUT_MS);
        }),
      ]);
    } catch (error) {
      disabled = true;
      diagnose(operation, error);
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  function identityBody() {
    return {
      repo,
      issue_number: issueNumber,
      branch,
      workflow_type: workflowType,
      runtime_driver: runtimeDriver,
      provenance: LIFECYCLE_PROVENANCE,
      ...(requirementUids?.length ? { requirement_uids: requirementUids } : {}),
      // Omitted rather than sent as null until a PR exists: the early boundaries upsert the same row
      // the later ones do, so an explicit null would let a re-run erase a PR a later boundary
      // recorded. Without this the run holds no link to the pull request that carried it, and only a
      // deliberate gc_workflow_run_ingest backfill — which does record one — could ever supply it.
      ...(Number.isInteger(prNumber) && prNumber > 0 ? { pr_number: prNumber } : {}),
    };
  }

  function upsert(operation, extra) {
    enqueue(async () => {
      const run = await safe(operation, (signal) =>
        d.createRun({ ...identityBody(), ...extra }, project, { signal }),
      );
      if (run?.id) runId = run.id;
    });
  }

  /**
   * Append one event. Runs on the chain, so by the time it executes the upsert that resolved
   * `runId` has already completed. `attempt` (when given) carries the ordinal between the two halves
   * of one station attempt, read at execution time rather than captured at call time.
   */
  function emit(body, attempt) {
    enqueue(async () => {
      if (runId == null) return;
      const recorded = await safe("record_event", (signal) =>
        d.recordEvent(
          runId,
          { ...body, cycle_index: attempt?.cycleIndex, provenance: LIFECYCLE_PROVENANCE },
          project,
          { signal },
        ),
      );
      if (attempt && typeof recorded?.cycle_index === "number") {
        attempt.cycleIndex = recorded.cycle_index;
      }
    });
  }

  return {
    /** The run id once the run has been opened or resolved; null when emission is unavailable. */
    get runId() {
      return runId;
    },

    /**
     * Settle the pending transport. Test affordance and shutdown hook only — the workflow path must
     * never await this, or the decoupling above is undone.
     */
    async flush() {
      await queue;
    },

    /** Open the run: RUNNING with a start time. Idempotent — re-running the same branch refines it. */
    openRun() {
      upsert("open_run", {
        final_state: "RUNNING",
        started_at: d.now().toISOString(),
      });
    },

    /**
     * Resolve the run id for a mid-run boundary without asserting lifecycle state. Sending RUNNING
     * here would be an open observation racing whatever terminal state the run may already hold.
     */
    ensureRun() {
      upsert("ensure_run", {});
    },

    /**
     * Attach the in-scope requirement UIDs once the run has resolved them. They are not known when
     * the run is opened, and without them the reporting aggregate's requirement filter would never
     * match a live run.
     */
    recordRequirementUids(uids) {
      if (!Array.isArray(uids) || uids.length === 0) return;
      requirementUids = uids;
      upsert("attach_requirements", {});
    },

    /**
     * Record a non-terminal state change (READY_FOR_REVIEW). Deliberately does not set an end time:
     * a run paused for a human merge has not ended, and dating it would corrupt cycle time.
     */
    markState(finalState) {
      upsert("mark_state", { final_state: finalState });
    },

    /** Record a terminal state with the end time the run actually reached it. */
    closeRun({ finalState, outcome }) {
      upsert("close_run", {
        final_state: finalState,
        outcome,
        ended_at: d.now().toISOString(),
      });
    },

    /**
     * Bracket one station attempt around `fn`: STARTED before it, COMPLETED or FAILED after, with
     * the measured duration. Both events are enqueued, so the station function starts immediately
     * and its result is returned without waiting on transport. The shared `attempt` object carries
     * the backend-assigned ordinal from the STARTED write to the terminal one, resolved on the chain
     * rather than awaited here.
     *
     * `fn`'s result — including a thrown error — is returned untouched.
     */
    async station(phase, fn) {
      const attempt = { cycleIndex: undefined };
      emit({ phase, event_type: "STARTED", occurred_at: d.now().toISOString() }, attempt);
      const startedAtMs = d.monotonic();

      let result;
      try {
        result = await fn();
      } catch (error) {
        emit(
          {
            phase,
            event_type: "FAILED",
            occurred_at: d.now().toISOString(),
            duration_ms: d.monotonic() - startedAtMs,
            outcome: outcomeCode(failureClass(error)),
          },
          attempt,
        );
        throw error;
      }

      // A phase attempt that failed is not a failed run: the caller repairs and retries, and the
      // run stays open. Only the attempt is recorded as FAILED here.
      emit(
        {
          phase,
          event_type: result?.ok === false ? "FAILED" : "COMPLETED",
          occurred_at: d.now().toISOString(),
          duration_ms: d.monotonic() - startedAtMs,
          outcome: result?.ok === false ? outcomeCode(result.error) : undefined,
        },
        attempt,
      );
      return result;
    },
  };
}
