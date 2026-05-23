import { useProjectContext } from "@/contexts/project-context";
import {
  useTestRun,
  useTestRunCaseResults,
  useTestRunStepResults,
  useTransitionTestRun,
  useUpdateTestRunCaseResult,
  useUpdateTestRunCursor,
  useUpdateTestRunStepResult,
} from "@/hooks/use-test-runs";
import type {
  TestRunCaseResultResponse,
  TestRunCaseResultStatus,
  TestRunStatus,
  TestRunStepResultResponse,
} from "@/types/api";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";

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
            <ActiveCasePanel run={run} caseResult={activeCase} />
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

// ----- Header ----------------------------------------------------------

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

function RunHeader({
  run,
  projectId,
  cases,
}: {
  run: { id: string; uid: string; name: string; status: TestRunStatus };
  projectId: string;
  cases: TestRunCaseResultResponse[];
}) {
  const transition = useTransitionTestRun(run.id);
  const totalCases = cases.length;
  const observed = cases.filter((c) => c.status !== "NOT_RUN").length;

  const canStart = run.status === "PLANNED";
  const canComplete = run.status === "IN_PROGRESS";
  const canAbort = run.status === "PLANNED" || run.status === "IN_PROGRESS";

  return (
    <header className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-card px-4 py-3">
      <div className="flex items-center gap-3">
        <Link
          to={`/p/${projectId}/test-runs`}
          className="text-sm text-muted-foreground underline-offset-2 hover:underline"
        >
          ← All runs
        </Link>
        <div>
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs text-muted-foreground">
              {run.uid}
            </span>
            <h1 className="text-lg font-semibold">{run.name}</h1>
            <span
              className={`rounded px-2 py-0.5 text-xs font-medium ${statusClass(run.status)}`}
            >
              {run.status}
            </span>
          </div>
          <p className="text-xs text-muted-foreground">
            {observed} of {totalCases} case{totalCases === 1 ? "" : "s"}{" "}
            observed
          </p>
        </div>
      </div>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={!canStart || transition.isPending}
          onClick={() => transition.mutate("IN_PROGRESS")}
          className="rounded border border-border bg-background px-3 py-1.5 text-sm hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
        >
          Start
        </button>
        <button
          type="button"
          disabled={!canComplete || transition.isPending}
          onClick={() => transition.mutate("COMPLETED")}
          className="rounded border border-green-600/50 bg-green-50 px-3 py-1.5 text-sm text-green-800 hover:bg-green-100 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-green-900/30 dark:text-green-200"
        >
          Complete
        </button>
        <button
          type="button"
          disabled={!canAbort || transition.isPending}
          onClick={() => transition.mutate("ABORTED")}
          className="rounded border border-red-600/50 bg-red-50 px-3 py-1.5 text-sm text-red-800 hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-red-900/30 dark:text-red-200"
        >
          Abort
        </button>
      </div>
    </header>
  );
}

// ----- Case sidebar ----------------------------------------------------

function resultStatusClass(status: TestRunCaseResultStatus): string {
  switch (status) {
    case "NOT_RUN":
      return "bg-muted text-muted-foreground";
    case "PASSED":
      return "bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200";
    case "FAILED":
      return "bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200";
    case "BLOCKED":
      return "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-200";
    case "SKIPPED":
      return "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300";
    default:
      return "bg-muted text-muted-foreground";
  }
}

function CaseSidebar({
  cases,
  activeCaseResultId,
  onSelect,
}: {
  cases: TestRunCaseResultResponse[];
  activeCaseResultId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <aside className="overflow-hidden rounded-lg border border-border bg-card">
      <div className="border-b border-border px-3 py-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
        Cases
      </div>
      <ul>
        {cases.map((c) => {
          const active = c.id === activeCaseResultId;
          return (
            <li key={c.id}>
              <button
                type="button"
                onClick={() => onSelect(c.id)}
                className={`flex w-full items-start justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-muted/40 ${
                  active ? "bg-muted/60" : ""
                }`}
              >
                <span className="flex flex-col">
                  <span className="font-mono text-xs text-muted-foreground">
                    {c.testCaseUid}
                  </span>
                  <span className="truncate">{c.testCaseTitle}</span>
                </span>
                <span
                  className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${resultStatusClass(c.status)}`}
                >
                  {c.status}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </aside>
  );
}

// ----- Active case panel ----------------------------------------------

function ActiveCasePanel({
  run,
  caseResult,
}: {
  run: {
    id: string;
    status: TestRunStatus;
    currentStepResultId: string | null;
  };
  caseResult: TestRunCaseResultResponse;
}) {
  const { data: stepResults, isLoading } = useTestRunStepResults(
    run.id,
    caseResult.id,
  );
  const updateCaseResult = useUpdateTestRunCaseResult(
    run.id,
    caseResult.testCaseId,
  );
  const updateCursor = useUpdateTestRunCursor(run.id);

  const [notesDraft, setNotesDraft] = useState(caseResult.notes ?? "");
  useEffect(
    () => setNotesDraft(caseResult.notes ?? ""),
    [caseResult.id, caseResult.notes],
  );

  const steps = stepResults ?? [];
  const activeStepIndex = useMemo(() => {
    if (!steps.length) return -1;
    const idx = steps.findIndex((s) => s.id === run.currentStepResultId);
    return idx >= 0 ? idx : 0;
  }, [steps, run.currentStepResultId]);
  const activeStep = activeStepIndex >= 0 ? steps[activeStepIndex] : null;

  // Persist cursor whenever the active case or step changes. Zero-step
  // cases also persist the cursor (case_result_id only, step null) so that
  // resume after pause lands on the selected case even if it has no steps
  // — codex review cycle 1 "Selecting a zero-step case never persists the
  // resume cursor". The mutation is fire-and-forget; transient failures
  // don't block the runner UI. Wait until the step-results query has
  // resolved (isLoading=false) before persisting a step-null cursor, so a
  // mid-fetch render isn't misinterpreted as "no steps".
  useEffect(() => {
    if (isLoading) return;
    const desiredStepId = activeStep?.id ?? null;
    const currentCase = (
      run as unknown as { currentCaseResultId: string | null }
    ).currentCaseResultId;
    if (
      currentCase === caseResult.id &&
      run.currentStepResultId === desiredStepId
    ) {
      return;
    }
    updateCursor.mutate({
      currentCaseResultId: caseResult.id,
      currentStepResultId: desiredStepId,
    });
    // updateCursor.mutate is stable; intentionally excluding to avoid loops.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseResult.id, activeStep?.id, isLoading]);

  const handleSetCaseStatus = useCallback(
    (status: TestRunCaseResultStatus) => {
      updateCaseResult.mutate({ status });
    },
    [updateCaseResult],
  );

  const handleSaveNotes = useCallback(() => {
    const trimmed = notesDraft.trim();
    if (trimmed === (caseResult.notes ?? "").trim()) return;
    // Status is intentionally omitted (codex review cycle 1) — sending the
    // current `caseResult.status` would race with a concurrent flip that
    // hasn't yet propagated to this component. The backend preserves the
    // existing value when status is absent.
    updateCaseResult.mutate({
      notes: trimmed.length === 0 ? null : trimmed,
      clearNotes: trimmed.length === 0,
    });
  }, [caseResult.notes, notesDraft, updateCaseResult]);

  return (
    <section className="space-y-4">
      <div className="rounded-lg border border-border bg-card p-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-base font-semibold">
            <span className="mr-2 font-mono text-xs text-muted-foreground">
              {caseResult.testCaseUid}
            </span>
            {caseResult.testCaseTitle}
          </h2>
          <CaseStatusControls
            status={caseResult.status}
            onSelect={handleSetCaseStatus}
            disabled={updateCaseResult.isPending}
          />
        </div>
        <label className="mt-3 block text-xs font-medium uppercase tracking-wider text-muted-foreground">
          Case notes
        </label>
        <textarea
          value={notesDraft}
          onChange={(e) => setNotesDraft(e.target.value)}
          onBlur={handleSaveNotes}
          rows={3}
          maxLength={8192}
          placeholder="Notes about the overall case…"
          className="mt-1 w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
        />
      </div>

      {isLoading ? (
        <div className="flex min-h-[12rem] items-center justify-center rounded-lg border border-border bg-card">
          <div className="h-6 w-6 animate-spin rounded-full border-4 border-muted border-t-primary" />
        </div>
      ) : !activeStep ? (
        // steps.length is 0 or the cursor index resolved to nothing; either
        // way we have no step to render. The first branch covers the
        // documented "case with no authored steps" path; the second is a
        // defensive fallback for the transient render before the cursor
        // effect resolves.
        <div className="rounded-lg border border-dashed border-muted-foreground/30 py-8 text-center text-sm text-muted-foreground">
          This case has no authored steps.
        </div>
      ) : (
        <StepViewport
          runId={run.id}
          caseResultId={caseResult.id}
          step={activeStep}
          steps={steps}
          activeStepIndex={activeStepIndex}
          onSelectStep={(stepId) =>
            updateCursor.mutate({
              currentCaseResultId: caseResult.id,
              currentStepResultId: stepId,
            })
          }
        />
      )}
    </section>
  );
}

function CaseStatusControls({
  status,
  onSelect,
  disabled,
}: {
  status: TestRunCaseResultStatus;
  onSelect: (s: TestRunCaseResultStatus) => void;
  disabled: boolean;
}) {
  const options: TestRunCaseResultStatus[] = [
    "NOT_RUN",
    "PASSED",
    "FAILED",
    "BLOCKED",
    "SKIPPED",
  ];
  return (
    <div className="flex gap-1">
      {options.map((opt) => {
        const active = opt === status;
        return (
          <button
            key={opt}
            type="button"
            disabled={disabled}
            onClick={() => onSelect(opt)}
            className={`rounded px-2 py-1 text-xs font-medium transition ${
              active
                ? resultStatusClass(opt)
                : "bg-muted text-muted-foreground hover:bg-muted/70"
            } disabled:cursor-not-allowed disabled:opacity-50`}
          >
            {opt.replace("_", " ")}
          </button>
        );
      })}
    </div>
  );
}

// ----- Step viewport ---------------------------------------------------

function StepViewport({
  runId,
  caseResultId,
  step,
  steps,
  activeStepIndex,
  onSelectStep,
}: {
  runId: string;
  caseResultId: string;
  step: TestRunStepResultResponse;
  steps: TestRunStepResultResponse[];
  activeStepIndex: number;
  onSelectStep: (stepResultId: string) => void;
}) {
  const updateStep = useUpdateTestRunStepResult(runId, caseResultId, step.id);
  const [commentDraft, setCommentDraft] = useState(step.comment ?? "");
  useEffect(() => setCommentDraft(step.comment ?? ""), [step.id, step.comment]);

  const handleSetStatus = useCallback(
    (status: TestRunCaseResultStatus) => {
      updateStep.mutate({
        status,
        executedAt: new Date().toISOString(),
      });
    },
    [updateStep],
  );

  const handleSaveComment = useCallback(() => {
    const trimmed = commentDraft.trim();
    if (trimmed === (step.comment ?? "").trim()) return;
    // Same protocol as handleSaveNotes: omit status so a concurrent flip
    // isn't reverted by this comment-only autosave.
    updateStep.mutate({
      comment: trimmed.length === 0 ? null : trimmed,
      clearComment: trimmed.length === 0,
    });
  }, [commentDraft, step.comment, updateStep]);

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold">
          Step {step.stepNumberSnapshot}{" "}
          <span className="text-muted-foreground">of {steps.length}</span>
        </h3>
        <StepStatusControls
          status={step.status}
          onSelect={handleSetStatus}
          disabled={updateStep.isPending}
        />
      </div>

      <div className="mt-3 grid gap-3 text-sm md:grid-cols-2">
        <div>
          <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            Action
          </div>
          <p className="mt-1 whitespace-pre-wrap rounded bg-muted/30 p-2">
            {step.actionSnapshot}
          </p>
        </div>
        <div>
          <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            Expected result
          </div>
          <p className="mt-1 whitespace-pre-wrap rounded bg-muted/30 p-2">
            {step.expectedResultSnapshot}
          </p>
        </div>
      </div>

      <div className="mt-4">
        <label className="block text-xs font-medium uppercase tracking-wider text-muted-foreground">
          Step comment
        </label>
        <textarea
          value={commentDraft}
          onChange={(e) => setCommentDraft(e.target.value)}
          onBlur={handleSaveComment}
          rows={3}
          maxLength={8192}
          placeholder="What did you observe?"
          className="mt-1 w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
        />
      </div>

      <div className="mt-4 flex items-center justify-between text-xs text-muted-foreground">
        <span>
          {step.executedAt
            ? `Executed at ${step.executedAt}`
            : "Not executed yet"}
        </span>
        <span className="flex gap-2">
          <button
            type="button"
            disabled={activeStepIndex <= 0 || !steps[activeStepIndex - 1]}
            onClick={() => {
              const prev = steps[activeStepIndex - 1];
              if (prev) onSelectStep(prev.id);
            }}
            className="rounded border border-border px-2 py-1 hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
          >
            ← Prev
          </button>
          <button
            type="button"
            disabled={
              activeStepIndex >= steps.length - 1 || !steps[activeStepIndex + 1]
            }
            onClick={() => {
              const next = steps[activeStepIndex + 1];
              if (next) onSelectStep(next.id);
            }}
            className="rounded border border-border px-2 py-1 hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
          >
            Next →
          </button>
        </span>
      </div>
    </div>
  );
}

function StepStatusControls({
  status,
  onSelect,
  disabled,
}: {
  status: TestRunCaseResultStatus;
  onSelect: (s: TestRunCaseResultStatus) => void;
  disabled: boolean;
}) {
  // Codex review cycle 1: include NOT_RUN so a tester who recorded a status
  // by accident can return the step to its initial state from the browser.
  // ADR-050 §2 names step-status flips as unconstrained — the runner must
  // expose every value the backend accepts.
  const options: TestRunCaseResultStatus[] = [
    "NOT_RUN",
    "PASSED",
    "FAILED",
    "BLOCKED",
    "SKIPPED",
  ];
  return (
    <div className="flex gap-1">
      {options.map((opt) => {
        const active = opt === status;
        return (
          <button
            key={opt}
            type="button"
            disabled={disabled}
            onClick={() => onSelect(opt)}
            className={`rounded px-2 py-1 text-xs font-medium transition ${
              active
                ? resultStatusClass(opt)
                : "bg-muted text-muted-foreground hover:bg-muted/70"
            } disabled:cursor-not-allowed disabled:opacity-50`}
          >
            {opt.replace("_", " ")}
          </button>
        );
      })}
    </div>
  );
}
