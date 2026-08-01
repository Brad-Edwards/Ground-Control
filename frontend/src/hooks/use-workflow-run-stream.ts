import { workflowRunKeys } from "@/hooks/workflow-run-keys";
import type { PhaseEventResponse, WorkflowRunResponse } from "@/types/api";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

/**
 * Transport health, deliberately *not* workflow liveness.
 *
 * `live` means the push transport is connected, not that any agent process is running — ADR-061
 * keeps `RUNNING` meaning "no terminal observation has been recorded". Rendering push health as
 * process health is precisely what GC-Q016(d) forbids.
 */
export type StreamStatus = "connecting" | "live" | "degraded";

const STREAM_PATH = "/api/v1/workflow-runs/stream";

/** Narrow runtime guard for a run frame. The generated types are compile-time only. */
function isWorkflowRun(
  value: unknown,
  projectIdentifier: string,
): value is WorkflowRunResponse {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Partial<WorkflowRunResponse>;
  return (
    typeof candidate.id === "string" &&
    candidate.project === projectIdentifier &&
    typeof candidate.finalState === "string"
  );
}

/** Narrow runtime guard for a phase-event frame. */
function isPhaseEvent(
  value: unknown,
  projectIdentifier: string,
): value is PhaseEventResponse {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Partial<PhaseEventResponse>;
  return (
    typeof candidate.id === "string" &&
    typeof candidate.runId === "string" &&
    candidate.project === projectIdentifier &&
    typeof candidate.phase === "string"
  );
}

function replaceById<T extends { id: string }>(
  existing: T[],
  incoming: T,
): T[] | null {
  const index = existing.findIndex((item) => item.id === incoming.id);
  if (index === -1) return null;
  const next = [...existing];
  next[index] = incoming;
  return next;
}

/**
 * Run list: `GET /workflow-runs` returns newest-first, so a newly observed run belongs at the front.
 */
function upsertNewestFirst<T extends { id: string }>(
  existing: T[] | undefined,
  incoming: T,
): T[] {
  if (!existing) return [incoming];
  return replaceById(existing, incoming) ?? [incoming, ...existing];
}

/**
 * Phase events: `GET /workflow-runs/{runId}/events` returns oldest-first, and a live append is by
 * definition the newest event, so it belongs at the end. Prepending would make enabling the stream
 * silently reverse the ordering contract of the very cache the REST read populates.
 */
function upsertOldestFirst<T extends { id: string }>(
  existing: T[] | undefined,
  incoming: T,
): T[] {
  if (!existing) return [incoming];
  return replaceById(existing, incoming) ?? [...existing, incoming];
}

/**
 * Subscribe to committed workflow-run telemetry for one project (issue #1436).
 *
 * Owns exactly one `EventSource` for the mounted project and closes it on project change or
 * unmount. It is same-origin, so the existing `GC_SESSION` cookie authenticates the request and no
 * token is ever placed in a URL or in browser storage.
 *
 * Updates reconcile into the *existing* React Query cache by entity id rather than into a parallel
 * store, so live views and the polling pages read one cache. Aggregates are invalidated rather than
 * recomputed: percentiles and window filtering belong in the database.
 *
 * Delivery is best-effort, so `onopen` invalidates the project snapshots — that closes both the
 * subscribe/fetch race on first connect and whatever was missed while disconnected.
 */
export function useWorkflowRunStream(projectIdentifier: string): {
  status: StreamStatus;
} {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<StreamStatus>("connecting");

  useEffect(() => {
    if (!projectIdentifier) {
      setStatus("degraded");
      return;
    }
    if (typeof EventSource === "undefined") {
      // No push transport available in this environment; the caller stays on interval polling.
      setStatus("degraded");
      return;
    }

    setStatus("connecting");
    const source = new EventSource(
      `${STREAM_PATH}?project=${encodeURIComponent(projectIdentifier)}`,
      { withCredentials: true },
    );

    const invalidateAggregates = () => {
      void queryClient.invalidateQueries({
        queryKey: workflowRunKeys.aggregatePrefix(projectIdentifier),
      });
    };
    const invalidateActivity = () => {
      void queryClient.invalidateQueries({
        queryKey: workflowRunKeys.activity(projectIdentifier),
      });
    };

    /** A frame we cannot trust is a reason to resynchronize over REST, never to write to the cache. */
    const rejectFrame = () => {
      source.close();
      setStatus("degraded");
      void queryClient.invalidateQueries({
        queryKey: workflowRunKeys.runs(projectIdentifier),
      });
      invalidateAggregates();
      invalidateActivity();
    };

    const handleRun = (event: MessageEvent<string>) => {
      let payload: unknown;
      try {
        payload = JSON.parse(event.data);
      } catch {
        rejectFrame();
        return;
      }
      if (!isWorkflowRun(payload, projectIdentifier)) {
        rejectFrame();
        return;
      }
      queryClient.setQueryData<WorkflowRunResponse[]>(
        workflowRunKeys.runs(projectIdentifier),
        (existing) => upsertNewestFirst(existing, payload),
      );
      invalidateAggregates();
      invalidateActivity();
    };

    const handlePhaseEvent = (event: MessageEvent<string>) => {
      let payload: unknown;
      try {
        payload = JSON.parse(event.data);
      } catch {
        rejectFrame();
        return;
      }
      if (!isPhaseEvent(payload, projectIdentifier)) {
        rejectFrame();
        return;
      }
      // Only reconcile a per-run event list that is actually mounted; seeding one here would
      // fabricate a partial history that never went through the bounded REST read.
      const key = workflowRunKeys.runEvents(projectIdentifier, payload.runId);
      if (queryClient.getQueryData<PhaseEventResponse[]>(key)) {
        queryClient.setQueryData<PhaseEventResponse[]>(key, (existing) =>
          upsertOldestFirst(existing, payload),
        );
      }
      invalidateAggregates();
      invalidateActivity();
    };

    source.addEventListener("workflow-run", handleRun as EventListener);
    source.addEventListener("phase-event", handlePhaseEvent as EventListener);
    source.onopen = () => {
      setStatus("live");
      // Delivery is best-effort: resynchronize whatever committed before this connection existed.
      void queryClient.invalidateQueries({
        queryKey: workflowRunKeys.runs(projectIdentifier),
      });
      invalidateAggregates();
      invalidateActivity();
    };
    source.onerror = () => {
      // EventSource retries on its own; report degraded so the caller re-arms interval polling
      // instead of presenting the last pushed values as current.
      setStatus("degraded");
    };

    return () => {
      source.removeEventListener("workflow-run", handleRun as EventListener);
      source.removeEventListener(
        "phase-event",
        handlePhaseEvent as EventListener,
      );
      source.close();
    };
  }, [projectIdentifier, queryClient]);

  return { status };
}
