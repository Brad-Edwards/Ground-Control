import { apiFetch } from "@/lib/api-client";
import type { ProjectResponse } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export type { ProjectResponse };

export function useProjects() {
  return useQuery({
    queryKey: ["projects"],
    queryFn: () => apiFetch<ProjectResponse[]>("/projects"),
  });
}
