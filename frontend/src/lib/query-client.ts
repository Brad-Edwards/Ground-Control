import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./api-client";

/**
 * Never retry authentication/authorization failures (GC-Q015 preflight guardrail). A 401 has
 * already triggered the redirect to {@code /login}; a 403 is a stable authorization decision.
 * Retrying either wastes requests and, for a 401, races the redirect. Other errors keep a single
 * retry.
 */
function retry(failureCount: number, error: unknown): boolean {
  if (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 403)
  ) {
    return false;
  }
  return failureCount < 1;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry,
    },
  },
});
