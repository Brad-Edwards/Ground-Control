import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type {
  ControlAssuranceWorkspaceResponse,
  ControlFunction,
  ControlStatus,
  ControlWorkspaceQueueReason,
} from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export interface ControlAssuranceWorkspaceFilters {
  status?: ControlStatus;
  controlFunction?: ControlFunction;
  owner?: string;
  queue?: ControlWorkspaceQueueReason;
  asOf?: string;
  freshnessWindowDays?: number;
}

export function useControlAssuranceWorkspace(
  filters: ControlAssuranceWorkspaceFilters = {},
) {
  const { activeProject } = useProjectContext();

  return useQuery({
    queryKey: [
      "control-assurance-workspace",
      activeProject?.identifier,
      filters.status,
      filters.controlFunction,
      filters.owner,
      filters.queue,
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
      if (filters.queue) params.queue = filters.queue;
      if (filters.asOf) params.asOf = filters.asOf;
      if (filters.freshnessWindowDays != null) {
        params.freshnessWindowDays = String(filters.freshnessWindowDays);
      }
      return apiFetch<ControlAssuranceWorkspaceResponse>(
        "/controls/workspace",
        { params },
      );
    },
    enabled: !!activeProject,
  });
}
