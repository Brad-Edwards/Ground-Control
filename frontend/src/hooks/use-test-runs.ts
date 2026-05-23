import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import { queryClient } from "@/lib/query-client";
import type {
  TestRunCaseResultResponse,
  TestRunResponse,
  TestRunStatus,
  TestRunStepResultResponse,
  UpdateTestRunCaseResultRequest,
  UpdateTestRunCursorRequest,
  UpdateTestRunStepResultRequest,
} from "@/types/api";
import { useMutation, useQuery } from "@tanstack/react-query";

/**
 * TC-008 / ADR-049 + TC-009 / ADR-050 — Test-run runner data hooks.
 *
 * All reads are scoped to the active project (defense in depth — the
 * backend rejects cross-project access with 404 concealment). Mutations
 * invalidate the affected query keys so the runner UI re-fetches the
 * minimum it needs after a status flip / cursor advance.
 */

export function useTestRuns() {
  const { activeProject } = useProjectContext();
  return useQuery({
    queryKey: ["test-runs", activeProject?.identifier],
    queryFn: () =>
      apiFetch<TestRunResponse[]>("/test-runs", {
        params: { project: activeProject?.identifier },
      }),
    enabled: !!activeProject,
  });
}

export function useTestRun(id: string | undefined) {
  const { activeProject } = useProjectContext();
  return useQuery({
    queryKey: ["test-run", id, activeProject?.identifier],
    queryFn: () =>
      apiFetch<TestRunResponse>(`/test-runs/${id}`, {
        params: { project: activeProject?.identifier },
      }),
    enabled: !!id && !!activeProject,
  });
}

export function useTestRunCaseResults(runId: string | undefined) {
  const { activeProject } = useProjectContext();
  return useQuery({
    queryKey: ["test-run-results", runId, activeProject?.identifier],
    queryFn: () =>
      apiFetch<TestRunCaseResultResponse[]>(`/test-runs/${runId}/results`, {
        params: { project: activeProject?.identifier },
      }),
    enabled: !!runId && !!activeProject,
  });
}

export function useTestRunStepResults(
  runId: string | undefined,
  caseResultId: string | undefined,
) {
  const { activeProject } = useProjectContext();
  return useQuery({
    queryKey: [
      "test-run-step-results",
      runId,
      caseResultId,
      activeProject?.identifier,
    ],
    queryFn: () =>
      apiFetch<TestRunStepResultResponse[]>(
        `/test-runs/${runId}/results/${caseResultId}/steps`,
        { params: { project: activeProject?.identifier } },
      ),
    enabled: !!runId && !!caseResultId && !!activeProject,
  });
}

export function useTransitionTestRun(id: string) {
  const { activeProject } = useProjectContext();
  return useMutation({
    mutationFn: (status: TestRunStatus) =>
      apiFetch<TestRunResponse>(`/test-runs/${id}/status`, {
        method: "PUT",
        body: { status },
        params: { project: activeProject?.identifier },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["test-run", id] });
      queryClient.invalidateQueries({ queryKey: ["test-runs"] });
    },
  });
}

export function useUpdateTestRunCursor(id: string) {
  const { activeProject } = useProjectContext();
  return useMutation({
    mutationFn: (data: UpdateTestRunCursorRequest) =>
      apiFetch<TestRunResponse>(`/test-runs/${id}/cursor`, {
        method: "PUT",
        body: data,
        params: { project: activeProject?.identifier },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["test-run", id] });
    },
  });
}

export function useUpdateTestRunCaseResult(runId: string, testCaseId: string) {
  const { activeProject } = useProjectContext();
  return useMutation({
    mutationFn: (data: UpdateTestRunCaseResultRequest) =>
      apiFetch<TestRunCaseResultResponse>(
        `/test-runs/${runId}/results/${testCaseId}`,
        {
          method: "PUT",
          body: data,
          params: { project: activeProject?.identifier },
        },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["test-run-results", runId] });
    },
  });
}

export function useUpdateTestRunStepResult(
  runId: string,
  caseResultId: string,
  stepResultId: string,
) {
  const { activeProject } = useProjectContext();
  return useMutation({
    mutationFn: (data: UpdateTestRunStepResultRequest) =>
      apiFetch<TestRunStepResultResponse>(
        `/test-runs/${runId}/results/${caseResultId}/steps/${stepResultId}`,
        {
          method: "PUT",
          body: data,
          params: { project: activeProject?.identifier },
        },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["test-run-step-results", runId, caseResultId],
      });
      // Per-case rollup status is independent (preflight decision); no need to
      // invalidate the case-results query on every step flip.
    },
  });
}
