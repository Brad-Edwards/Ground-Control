import type { WorkflowRunFilters } from "@/hooks/use-workflow-runs";

/**
 * Query-key identity shared by the workflow-run fetch hooks and the live-stream hook (issue #1436).
 *
 * <p>The stream reconciles into the same React Query cache the polling reads populate, so both must
 * build their keys here. Two call sites assembling raw arrays independently is exactly how a live
 * update ends up written to a key nothing is reading.
 */
export const workflowRunKeys = {
  runs: (projectIdentifier: string) =>
    ["workflow-runs", projectIdentifier] as const,

  /** Per-run phase events. Reconciled by the stream only when such a query is mounted. */
  runEvents: (projectIdentifier: string, runId: string) =>
    ["workflow-run-events", projectIdentifier, runId] as const,

  activity: (projectIdentifier: string) =>
    ["workflow-run-activity", projectIdentifier] as const,

  aggregate: (projectIdentifier: string, filters: WorkflowRunFilters = {}) =>
    [
      "workflow-run-aggregate",
      projectIdentifier,
      filters.repo,
      filters.runtime,
      filters.requirement,
      filters.workflowType,
      filters.outcome,
      filters.from,
      filters.to,
    ] as const,

  /**
   * Prefix matching every aggregate query for a project, whatever its filters. A live event changes
   * the underlying population, and the percentiles and window maths belong in the database — so the
   * stream invalidates these and lets them refetch rather than recomputing anything in the browser.
   */
  aggregatePrefix: (projectIdentifier: string) =>
    ["workflow-run-aggregate", projectIdentifier] as const,
};
