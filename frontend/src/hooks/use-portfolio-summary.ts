import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type { PortfolioSummaryResponse } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface PortfolioSummaryFilters {
  asOf?: string;
  freshnessWindowDays?: number;
}

export function usePortfolioSummary(filters: PortfolioSummaryFilters = {}) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "portfolio-summary",
      activeProject?.identifier,
      filters.asOf,
      filters.freshnessWindowDays,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      return apiFetch<PortfolioSummaryResponse>("/analysis/grc/portfolio", {
        params,
      });
    },
    enabled: !!activeProject,
  });
}
