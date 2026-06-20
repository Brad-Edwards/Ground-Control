import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type {
  LinkType,
  PagedResponse,
  RequirementWithLinksResponse,
  Status,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface TraceabilityMatrixFilters {
  status?: Status;
  wave?: number;
  linkType?: LinkType;
  page: number;
  size: number;
}

export function useTraceabilityMatrix(filters: TraceabilityMatrixFilters) {
  const { activeProject } = useProjectContext();
  const { status, wave, linkType, page, size } = filters;

  return useQuery({
    queryKey: ["traceability-matrix", activeProject?.identifier, filters],
    queryFn: () =>
      apiFetch<PagedResponse<RequirementWithLinksResponse>>(
        "/requirements/matrix",
        {
          params: {
            project: activeProject?.identifier,
            status,
            wave: wave !== undefined ? String(wave) : undefined,
            linkType,
            page: String(page),
            size: String(size),
          },
        },
      ),
    enabled: !!activeProject,
  });
}
