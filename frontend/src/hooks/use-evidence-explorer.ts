import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type { EvidenceExplorerResponse, EvidenceType } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface EvidenceExplorerFilters {
  assetId?: string;
  evidenceType?: EvidenceType;
  asOf?: string;
  freshnessWindowDays?: number;
  includeSuperseded?: boolean;
}

export function useEvidenceExplorer(filters: EvidenceExplorerFilters = {}) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "evidence-explorer",
      activeProject?.identifier,
      filters.assetId,
      filters.evidenceType,
      filters.asOf,
      filters.freshnessWindowDays,
      filters.includeSuperseded,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.assetId) params.assetId = filters.assetId;
      if (filters.evidenceType) params.evidenceType = filters.evidenceType;
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      if (filters.includeSuperseded != null) {
        params.includeSuperseded = String(filters.includeSuperseded);
      }
      return apiFetch<EvidenceExplorerResponse>(
        "/evidence-artifacts/explorer",
        { params },
      );
    },
    enabled: !!activeProject,
  });
}
