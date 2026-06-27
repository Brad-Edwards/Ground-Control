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

export function useWorkflowRuns(projectIdentifier: string) {
  return useQuery({
    queryKey: ["workflow-runs", projectIdentifier],
    queryFn: () =>
      apiFetch<WorkflowRunResponse[]>("/workflow-runs", {
        params: { project: projectIdentifier, limit: "50" },
      }),
    enabled: !!projectIdentifier,
  });
}

export function useWorkflowRunAggregate(
  projectIdentifier: string,
  filters: WorkflowRunFilters = {},
) {
  return useQuery({
    queryKey: [
      "workflow-run-aggregate",
      projectIdentifier,
      filters.repo,
      filters.runtime,
      filters.requirement,
      filters.workflowType,
      filters.outcome,
      filters.from,
      filters.to,
    ],
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
  });
}
