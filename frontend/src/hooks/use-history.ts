import { apiFetch } from "@/lib/api-client";
import type {
  ChangeCategory,
  RelationHistoryResponse,
  RequirementHistoryResponse,
  TimelineEntryResponse,
  TraceabilityLinkHistoryResponse,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export function useRequirementHistory(requirementId: string | undefined) {
  return useQuery({
    queryKey: ["history", "requirement", requirementId],
    queryFn: () =>
      apiFetch<RequirementHistoryResponse[]>(
        `/requirements/${requirementId}/history`,
      ),
    enabled: !!requirementId,
  });
}

export function useRelationHistory(
  requirementId: string | undefined,
  relationId: string | undefined,
) {
  return useQuery({
    queryKey: ["history", "relation", requirementId, relationId],
    queryFn: () =>
      apiFetch<RelationHistoryResponse[]>(
        `/requirements/${requirementId}/relations/${relationId}/history`,
      ),
    enabled: !!requirementId && !!relationId,
  });
}

export function useTraceabilityLinkHistory(
  requirementId: string | undefined,
  linkId: string | undefined,
) {
  return useQuery({
    queryKey: ["history", "traceability", requirementId, linkId],
    queryFn: () =>
      apiFetch<TraceabilityLinkHistoryResponse[]>(
        `/requirements/${requirementId}/traceability/${linkId}/history`,
      ),
    enabled: !!requirementId && !!linkId,
  });
}

export interface TimelineFilters {
  changeCategory?: ChangeCategory;
  from?: string;
  to?: string;
}

export function useRequirementTimeline(
  requirementId: string | undefined,
  filters?: TimelineFilters,
  expand?: boolean,
) {
  return useQuery({
    queryKey: ["timeline", requirementId, filters, expand],
    queryFn: () =>
      apiFetch<TimelineEntryResponse[]>(
        `/requirements/${requirementId}/timeline`,
        {
          params: {
            changeCategory: filters?.changeCategory,
            from: filters?.from,
            to: filters?.to,
            expand: expand ? "true" : undefined,
          },
        },
      ),
    enabled: !!requirementId,
  });
}
