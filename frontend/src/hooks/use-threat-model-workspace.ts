import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type {
  StrideCategory,
  ThreatModelStatus,
  ThreatModelWorkspaceResponse,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface ThreatModelWorkspaceFilters {
  assetId?: string;
  stride?: StrideCategory;
  status?: ThreatModelStatus;
  asOf?: string;
  freshnessWindowDays?: number;
}

export function useThreatModelWorkspace(
  filters: ThreatModelWorkspaceFilters = {},
) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "threat-model-workspace",
      activeProject?.identifier,
      filters.assetId,
      filters.stride,
      filters.status,
      filters.asOf,
      filters.freshnessWindowDays,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.assetId) params.assetId = filters.assetId;
      if (filters.stride) params.stride = filters.stride;
      if (filters.status) params.status = filters.status;
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      return apiFetch<ThreatModelWorkspaceResponse>(
        "/threat-models/workspace",
        { params },
      );
    },
    enabled: !!activeProject,
  });
}
