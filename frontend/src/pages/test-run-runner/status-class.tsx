import {
  useTransitionTestRun,
  useUpdateTestRunStepResult,
} from "@/hooks/use-test-runs";
import type {
  TestRunCaseResultResponse,
  TestRunCaseResultStatus,
  TestRunStatus,
  TestRunStepResultResponse,
} from "@/types/api";
import { useCallback, useState } from "react";
import { Link } from "react-router";

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

export function RunHeader({
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

export function CaseSidebar({
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

export function CaseStatusControls({
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

// ----- Step viewport ---------------------------------------------------

export function StepViewport({
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
  // Re-initializes on remount; the parent keys this viewport by step id.
  const [commentDraft, setCommentDraft] = useState(step.comment ?? "");

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
