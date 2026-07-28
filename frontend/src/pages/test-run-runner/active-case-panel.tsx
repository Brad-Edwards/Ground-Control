import {
  useTestRunStepResults,
  useUpdateTestRunCaseResult,
  useUpdateTestRunCursor,
} from "@/hooks/use-test-runs";
import type {
  TestRunCaseResultResponse,
  TestRunCaseResultStatus,
  TestRunStatus,
} from "@/types/api";
import { useCallback, useEffect, useMemo, useState } from "react";
import { CaseStatusControls, StepViewport } from "./status-class";

// ----- Active case panel ----------------------------------------------

export function ActiveCasePanel({
  run,
  caseResult,
}: {
  run: {
    id: string;
    status: TestRunStatus;
    currentCaseResultId: string | null;
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

  // Re-initializes on remount; the parent keys this panel by case id.
  const [notesDraft, setNotesDraft] = useState(caseResult.notes ?? "");

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
  // The cursor fields are read as an equality guard, so they belong in the
  // dependency list: the mutation settles `run`, this re-runs, the guard
  // matches, and it returns. Omitting them compared against a `run` captured on
  // an earlier render, which could re-issue a write that had already landed.
  useEffect(() => {
    if (isLoading) return;
    const desiredStepId = activeStep?.id ?? null;
    if (
      run.currentCaseResultId === caseResult.id &&
      run.currentStepResultId === desiredStepId
    ) {
      return;
    }
    updateCursor.mutate({
      currentCaseResultId: caseResult.id,
      currentStepResultId: desiredStepId,
    });
  }, [
    caseResult.id,
    activeStep?.id,
    isLoading,
    run.currentCaseResultId,
    run.currentStepResultId,
    updateCursor.mutate,
  ]);

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
        // Keyed on the step id for the same reason as the case panel above: an
        // unsaved comment must not survive a switch to a step whose persisted
        // comment is identical.
        <StepViewport
          key={activeStep.id}
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
