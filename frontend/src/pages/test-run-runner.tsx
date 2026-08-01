import { useProjectContext } from "@/contexts/project-context";
import { useTestRun, useTestRunCaseResults } from "@/hooks/use-test-runs";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { ActiveCasePanel } from "./test-run-runner/active-case-panel";
import { CaseSidebar, RunHeader } from "./test-run-runner/status-class";

/**
 * TC-009 / ADR-050 — Browser-based manual test execution runner.
 *
 * Mounts on `/p/:projectId/test-runs/:runId/run`. Responsibilities:
 *
 * - Render the run header with status pill + lifecycle transition buttons
 *   (Start / Complete / Abort) driven by `TestRunStatus.canTransitionTo`.
 * - Render a left sidebar of per-case results with status badges; clicking
 *   one selects the active case and loads its step results.
 * - Render a main viewport for the active case showing the snapshotted
 *   authored content (action / expected) and the runtime fields the tester
 *   updates (status, comment, executed-at).
 * - Persist the cursor (`currentCaseResultId` + `currentStepResultId`) so
 *   pause-then-reload lands the tester back where they were.
 *
 * Pause is implicit (the tester closes the tab; the cursor is already
 * persisted by the latest interaction). Resume reads the cursor on mount
 * and selects the corresponding case + step. No client-only state is
 * required for auditability — every step, comment, and timestamp is
 * server-side.
 */
export function TestRunRunner() {
  const { runId } = useParams<{ runId: string }>();
  const { activeProject } = useProjectContext();

  const {
    data: run,
    isLoading: runLoading,
    isError: runError,
  } = useTestRun(runId);
  const { data: caseResults, isLoading: casesLoading } =
    useTestRunCaseResults(runId);

  const [activeCaseResultId, setActiveCaseResultId] = useState<string | null>(
    null,
  );

  // Initial cursor resolution. Once both the run and its case results have
  // loaded, pick the active case from the persisted cursor, falling back to
  // the first snapshot row so a fresh run lands on case 1 instead of a
  // blank viewport.
  useEffect(() => {
    if (activeCaseResultId || !caseResults?.length) return;
    const fromCursor = caseResults.find(
      (c) => c.id === run?.currentCaseResultId,
    );
    const target = fromCursor ?? caseResults[0];
    if (target) setActiveCaseResultId(target.id);
  }, [activeCaseResultId, caseResults, run?.currentCaseResultId]);

  if (!activeProject) {
    return (
      <div className="py-12 text-center text-muted-foreground">
        Select a project to open this test run.
      </div>
    );
  }

  if (runLoading || casesLoading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary" />
      </div>
    );
  }

  if (runError || !run) {
    return (
      <div className="py-12 text-center text-destructive">
        Failed to load test run.{" "}
        <Link
          to={`/p/${activeProject.identifier}/test-runs`}
          className="text-primary underline"
        >
          Back to test runs
        </Link>
      </div>
    );
  }

  const cases = caseResults ?? [];
  const activeCase = cases.find((c) => c.id === activeCaseResultId) ?? null;

  return (
    <div className="space-y-4">
      <RunHeader run={run} projectId={activeProject.identifier} cases={cases} />

      {cases.length === 0 ? (
        <div className="rounded-lg border border-dashed border-muted-foreground/30 py-12 text-center text-muted-foreground">
          This run has no snapshotted cases.
        </div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
          <CaseSidebar
            cases={cases}
            activeCaseResultId={activeCaseResultId}
            onSelect={setActiveCaseResultId}
          />
          {activeCase ? (
            // Keyed on the case id so switching cases remounts the panel and
            // its draft state re-initializes from the new case. Without this an
            // unsaved note typed against one case would survive a switch to a
            // case whose persisted notes happen to be identical.
            <ActiveCasePanel
              key={activeCase.id}
              run={run}
              caseResult={activeCase}
            />
          ) : (
            <div className="rounded-lg border border-dashed border-muted-foreground/30 py-12 text-center text-muted-foreground">
              Select a case from the sidebar.
            </div>
          )}
        </div>
      )}
    </div>
  );
}
