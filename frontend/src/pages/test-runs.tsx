import { useProjectContext } from "@/contexts/project-context";
import { useTestRuns } from "@/hooks/use-test-runs";
import type { TestRunResponse, TestRunStatus } from "@/types/api";
import { Link } from "react-router-dom";

/**
 * TC-008 / ADR-049 + TC-009 / ADR-050 — Minimal read-only index of test
 * runs in the active project. The runner page is the primary consumer; this
 * page exists to make the runner navigable from the SPA. Full CRUD for
 * test runs (create / edit / delete) is out of TC-009 scope; runs are
 * created today via MCP `gc_test_run` action=create or direct REST call.
 */
export function TestRuns() {
  const { activeProject } = useProjectContext();
  const { data: runs, isLoading, isError, error } = useTestRuns();

  if (!activeProject) {
    return (
      <div className="py-12 text-center text-muted-foreground">
        Select a project to view test runs.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="py-12 text-center text-destructive">
        Failed to load test runs: {(error as Error)?.message ?? "Unknown error"}
      </div>
    );
  }

  const items = runs ?? [];

  return (
    <div className="space-y-6">
      <header className="flex items-baseline justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Test Runs</h1>
          <p className="text-sm text-muted-foreground">
            Open a run to execute it step by step. Run creation is currently
            exposed via API and MCP.
          </p>
        </div>
        <span className="text-sm text-muted-foreground">
          {items.length} run{items.length === 1 ? "" : "s"}
        </span>
      </header>

      {items.length === 0 ? (
        <div className="rounded-lg border border-dashed border-muted-foreground/30 py-12 text-center text-muted-foreground">
          No test runs in this project yet.
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs uppercase tracking-wider text-muted-foreground">
              <tr>
                <th className="px-4 py-2 font-medium">UID</th>
                <th className="px-4 py-2 font-medium">Name</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2 font-medium">Plan / Suite</th>
                <th className="px-4 py-2 font-medium">Window</th>
                <th className="px-4 py-2 font-medium" aria-label="Actions" />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {items.map((run) => (
                <TestRunRow
                  key={run.id}
                  run={run}
                  projectId={activeProject.identifier}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function statusClass(status: TestRunStatus): string {
  switch (status) {
    case "PLANNED":
      return "bg-muted text-muted-foreground";
    case "IN_PROGRESS":
      return "bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-200";
    case "COMPLETED":
      return "bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200";
    case "ABORTED":
      return "bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200";
    case "ARCHIVED":
      return "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300";
    default:
      return "bg-muted text-muted-foreground";
  }
}

function TestRunRow({
  run,
  projectId,
}: { run: TestRunResponse; projectId: string }) {
  const window =
    run.startAt && run.endAt
      ? `${run.startAt.slice(0, 10)} → ${run.endAt.slice(0, 10)}`
      : run.startAt
        ? `from ${run.startAt.slice(0, 10)}`
        : run.endAt
          ? `until ${run.endAt.slice(0, 10)}`
          : "—";
  return (
    <tr className="hover:bg-muted/30">
      <td className="px-4 py-3 font-mono text-xs">{run.uid}</td>
      <td className="px-4 py-3">{run.name}</td>
      <td className="px-4 py-3">
        <span
          className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${statusClass(run.status)}`}
        >
          {run.status}
        </span>
      </td>
      <td className="px-4 py-3 text-xs text-muted-foreground">
        {run.testPlanUid} · {run.testSuiteUid}
      </td>
      <td className="px-4 py-3 text-xs text-muted-foreground">{window}</td>
      <td className="px-4 py-3 text-right">
        <Link
          to={`/p/${projectId}/test-runs/${run.id}/run`}
          className="text-primary underline-offset-2 hover:underline"
        >
          Open runner →
        </Link>
      </td>
    </tr>
  );
}
