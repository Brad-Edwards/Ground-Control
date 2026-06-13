import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type { EvidenceStateWorkspaceResponse } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface EvidenceStateWorkspaceFilters {
  assetId?: string;
  controlId?: string;
  asOf?: string;
  freshnessWindowDays?: number;
  includeSuperseded?: boolean;
}

export function useEvidenceStateWorkspace(
  filters: EvidenceStateWorkspaceFilters = {},
) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "evidence-state-workspace",
      activeProject?.identifier,
      filters.assetId,
      filters.controlId,
      filters.asOf,
      filters.freshnessWindowDays,
      filters.includeSuperseded,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.assetId) params.assetId = filters.assetId;
      if (filters.controlId) params.controlId = filters.controlId;
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      if (filters.includeSuperseded != null) {
        params.includeSuperseded = String(filters.includeSuperseded);
      }
      return apiFetch<EvidenceStateWorkspaceResponse>(
        "/evidence-state/workspace",
        { params },
      );
    },
    enabled: !!activeProject,
  });
}
