import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type {
  RiskAssessmentApprovalStatus,
  RiskScenarioStatus,
  RiskScenarioWorkspaceResponse,
  TreatmentPlanStatus,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface RiskScenarioWorkspaceFilters {
  assetId?: string;
  status?: RiskScenarioStatus;
  methodologyProfileId?: string;
  approvalState?: RiskAssessmentApprovalStatus;
  treatmentStatus?: TreatmentPlanStatus;
  asOf?: string;
  freshnessWindowDays?: number;
  compare?: string[];
}

export function useRiskScenarioWorkspace(
  filters: RiskScenarioWorkspaceFilters = {},
) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "risk-scenario-workspace",
      activeProject?.identifier,
      filters.assetId,
      filters.status,
      filters.methodologyProfileId,
      filters.approvalState,
      filters.treatmentStatus,
      filters.asOf,
      filters.freshnessWindowDays,
      filters.compare,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.assetId) params.assetId = filters.assetId;
      if (filters.status) params.status = filters.status;
      if (filters.methodologyProfileId)
        params.methodologyProfileId = filters.methodologyProfileId;
      if (filters.approvalState) params.approvalState = filters.approvalState;
      if (filters.treatmentStatus)
        params.treatmentStatus = filters.treatmentStatus;
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      if (filters.compare && filters.compare.length > 0) {
        params.compare = filters.compare.join(",");
      }
      return apiFetch<RiskScenarioWorkspaceResponse>(
        "/risk-scenarios/workspace",
        { params },
      );
    },
    enabled: !!activeProject,
  });
}
