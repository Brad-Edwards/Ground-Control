import { workflowRunKeys } from "@/hooks/workflow-run-keys";
import { apiFetch } from "@/lib/api-client";
import type {
  WorkflowRunAggregateResponse,
  WorkflowRunResponse,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface WorkflowRunFilters {
  repo?: string;
  runtime?: string;
  requirement?: string;
  workflowType?: string;
  outcome?: string;
  from?: string;
  to?: string;
}

/**
 * Fallback poll cadence. Runs advance while the page is open (issue #1435), so a snapshot taken at
 * mount goes stale within a phase.
 *
 * Since #1436 this is the *degraded* path: while the live stream is connected it pushes changes as
 * they commit and polling is switched off. It re-arms the moment the stream drops, so losing the
 * push transport can never make the page staler than it was before the stream existed.
 */
const FALLBACK_POLL_MS = 30_000;

export interface LiveOptions {
  /** True while the live stream is connected. Suppresses interval polling; see {@link FALLBACK_POLL_MS}. */
  live?: boolean;
}

function fallbackInterval(live: boolean | undefined): number | false {
  return live ? false : FALLBACK_POLL_MS;
}

export function useWorkflowRuns(
  projectIdentifier: string,
  { live }: LiveOptions = {},
) {
  return useQuery({
    queryKey: workflowRunKeys.runs(projectIdentifier),
    queryFn: () =>
      apiFetch<WorkflowRunResponse[]>("/workflow-runs", {
        params: { project: projectIdentifier, limit: "50" },
      }),
    enabled: !!projectIdentifier,
    refetchInterval: fallbackInterval(live),
  });
}

export function useWorkflowRunAggregate(
  projectIdentifier: string,
  filters: WorkflowRunFilters = {},
  { live }: LiveOptions = {},
) {
  return useQuery({
    queryKey: workflowRunKeys.aggregate(projectIdentifier, filters),
    queryFn: () => {
      const params: Record<string, string> = {
        project: projectIdentifier,
      };
      if (filters.repo) params.repo = filters.repo;
      if (filters.runtime) params.runtime = filters.runtime;
      if (filters.requirement) params.requirement = filters.requirement;
      if (filters.workflowType) params.workflowType = filters.workflowType;
      if (filters.outcome) params.outcome = filters.outcome;
      if (filters.from) params.from = filters.from;
      if (filters.to) params.to = filters.to;
      return apiFetch<WorkflowRunAggregateResponse>(
        "/workflow-runs/aggregate",
        { params },
      );
    },
    enabled: !!projectIdentifier,
    refetchInterval: fallbackInterval(live),
  });
}
