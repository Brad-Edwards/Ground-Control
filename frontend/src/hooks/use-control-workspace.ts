import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type {
  ControlFunction,
  ControlStatus,
  ControlWorkspaceResponse,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface ControlWorkspaceFilters {
  status?: ControlStatus;
  controlFunction?: ControlFunction;
  owner?: string;
  assetId?: string;
  asOf?: string;
  freshnessWindowDays?: number;
}

export function useControlWorkspace(filters: ControlWorkspaceFilters = {}) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "control-workspace",
      activeProject?.identifier,
      filters.status,
      filters.controlFunction,
      filters.owner,
      filters.assetId,
      filters.asOf,
      filters.freshnessWindowDays,
    ],
    queryFn: () => {
      const params: Record<string, string> = {
        project: activeProject?.identifier ?? "",
      };
      if (filters.status) params.status = filters.status;
      if (filters.controlFunction)
        params.controlFunction = filters.controlFunction;
      if (filters.owner) params.owner = filters.owner;
      if (filters.assetId) params.assetId = filters.assetId;
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      return apiFetch<ControlWorkspaceResponse>("/controls/workspace", {
        params,
      });
    },
    enabled: !!activeProject,
  });
}
