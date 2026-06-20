import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type {
  AssetResponse,
  ControlAssuranceWorkspaceResponse,
  EvidenceStateWorkspaceResponse,
  FindingResponse,
  RiskScenarioWorkspaceResponse,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface GrcPortfolioFilters {
  asOf?: string;
  freshnessWindowDays?: number;
  includeSuperseded?: boolean;
}

export interface GrcPortfolioData {
  risk: RiskScenarioWorkspaceResponse;
  controls: ControlAssuranceWorkspaceResponse;
  evidence: EvidenceStateWorkspaceResponse;
  findings: FindingResponse[];
  assets: AssetResponse[];
}

function sharedWorkspaceParams(
  project: string,
  filters: GrcPortfolioFilters,
): Record<string, string> {
  const params: Record<string, string> = { project };
  if (filters.asOf) params.asOf = filters.asOf;
  if (filters.freshnessWindowDays != null) {
    params.freshnessWindowDays = String(filters.freshnessWindowDays);
  }
  return params;
}

export function useGrcPortfolio(filters: GrcPortfolioFilters = {}) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "grc-portfolio",
      activeProject?.identifier,
      filters.asOf,
      filters.freshnessWindowDays,
      filters.includeSuperseded,
    ],
    queryFn: async (): Promise<GrcPortfolioData> => {
      const project = activeProject?.identifier ?? "";
      const workspaceParams = sharedWorkspaceParams(project, filters);
      const evidenceParams = { ...workspaceParams };
      if (filters.includeSuperseded != null) {
        evidenceParams.includeSuperseded = String(filters.includeSuperseded);
      }

      const [risk, controls, evidence, findings, assets] = await Promise.all([
        apiFetch<RiskScenarioWorkspaceResponse>("/risk-scenarios/workspace", {
          params: workspaceParams,
        }),
        apiFetch<ControlAssuranceWorkspaceResponse>("/controls/workspace", {
          params: workspaceParams,
        }),
        apiFetch<EvidenceStateWorkspaceResponse>("/evidence-state/workspace", {
          params: evidenceParams,
        }),
        apiFetch<FindingResponse[]>("/findings", {
          params: { project },
        }),
        apiFetch<AssetResponse[]>("/assets", {
          params: { project },
        }),
      ]);

      return { risk, controls, evidence, findings, assets };
    },
    enabled: !!activeProject,
  });
}
