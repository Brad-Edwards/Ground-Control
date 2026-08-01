import { apiFetch } from "@/lib/api-client";
import type { SessionResponse } from "@/types/api";
import { useQuery } from "@tanstack/react-query";

export type { SessionResponse };

/**
 * Reads the authenticated principal (GC-Q015 clause (a)) from {@code GET /api/v1/session}. The
 * shared query client already declines to retry a 401/403 and the api-client redirects a 401 to
 * the login bundle, so this hook never papers over an expired session. Identity changes rarely
 * within a session, so it is cached for a few minutes.
 */
export function useSession() {
  return useQuery({
    queryKey: ["session"],
    queryFn: () => apiFetch<SessionResponse>("/session"),
    staleTime: 5 * 60_000,
  });
}
