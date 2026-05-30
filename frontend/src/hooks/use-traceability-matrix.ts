import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type { LinkType, Status, TraceabilityMatrixResponse } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface TraceabilityMatrixFilters {
  wave?: number;
  status?: Status;
  linkType?: LinkType;
}

export function useTraceabilityMatrix(filters: TraceabilityMatrixFilters = {}) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "traceability-matrix",
      activeProject?.identifier,
      filters.wave,
      filters.status,
      filters.linkType,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.wave != null) params.wave = String(filters.wave);
      if (filters.status) params.status = filters.status;
      if (filters.linkType) params.linkType = filters.linkType;
      return apiFetch<TraceabilityMatrixResponse>(
        "/requirements/traceability/matrix",
        { params },
      );
    },
    enabled: !!activeProject,
  });
}
