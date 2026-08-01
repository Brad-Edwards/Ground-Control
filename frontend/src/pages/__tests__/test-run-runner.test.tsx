// @vitest-environment jsdom

/**
 * TC-009 / ADR-050 — runner draft-reset and cursor-persistence behavior.
 *
 * These cover the entity-bound draft semantics that the `caseResult.id` and
 * `step.id` effect dependencies used to carry (issue #1468). The reset only
 * observably matters when the two entities hold identical persisted text: with
 * differing text a stale draft would be masked by the new value.
 */

import type {
  TestRunCaseResultResponse,
  TestRunStepResultResponse,
} from "@/types/api";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-test-runs", () => ({
  useTestRun: vi.fn(),
  useTestRunCaseResults: vi.fn(),
  useTestRunStepResults: vi.fn(),
  useTransitionTestRun: vi.fn(),
  useUpdateTestRunCaseResult: vi.fn(),
  useUpdateTestRunCursor: vi.fn(),
  useUpdateTestRunStepResult: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
  }),
}));

vi.mock("react-router", async () => {
  const actual =
    await vi.importActual<typeof import("react-router")>("react-router");
  return { ...actual, useParams: () => ({ runId: "run-1" }) };
});

import {
  useTestRun,
  useTestRunCaseResults,
  useTestRunStepResults,
  useTransitionTestRun,
  useUpdateTestRunCaseResult,
  useUpdateTestRunCursor,
  useUpdateTestRunStepResult,
} from "@/hooks/use-test-runs";
import { TestRunRunner } from "../test-run-runner";

const mockRun = vi.mocked(useTestRun);
const mockCases = vi.mocked(useTestRunCaseResults);
const mockSteps = vi.mocked(useTestRunStepResults);
const mockTransition = vi.mocked(useTransitionTestRun);
const mockUpdateCase = vi.mocked(useUpdateTestRunCaseResult);
const mockCursor = vi.mocked(useUpdateTestRunCursor);
const mockUpdateStep = vi.mocked(useUpdateTestRunStepResult);

const cursorMutate = vi.fn();

/** The two cases share notes text, so only an identity-triggered reset shows. */
function caseResult(
  id: string,
  notes: string | null,
): TestRunCaseResultResponse {
  return {
    id,
    testCaseId: `tc-${id}`,
    testCaseUid: `TC-${id}`,
    testCaseTitle: `Case ${id}`,
    status: "NOT_RUN",
    notes,
  } as unknown as TestRunCaseResultResponse;
}

function stepResult(
  id: string,
  comment: string | null,
): TestRunStepResultResponse {
  return {
    id,
    stepOrder: 1,
    action: `Action ${id}`,
    expectedResult: `Expected ${id}`,
    status: "NOT_RUN",
    comment,
    executedAt: null,
  } as unknown as TestRunStepResultResponse;
}

function runPayload(overrides: Record<string, unknown> = {}) {
  return {
    id: "run-1",
    uid: "TR-1",
    name: "Run 1",
    status: "IN_PROGRESS",
    currentCaseResultId: null,
    currentStepResultId: null,
    ...overrides,
  };
}

/** The page renders <Link>, so every case needs a router context. */
function renderRunner() {
  return render(
    <MemoryRouter>
      <TestRunRunner />
    </MemoryRouter>,
  );
}

function rerenderRunner(rerender: (ui: React.ReactElement) => void) {
  rerender(
    <MemoryRouter>
      <TestRunRunner />
    </MemoryRouter>,
  );
}

function mutationStub() {
  return { mutate: vi.fn(), isPending: false } as never;
}

beforeEach(() => {
  vi.clearAllMocks();
  cursorMutate.mockReset();
  mockTransition.mockReturnValue(mutationStub());
  mockUpdateCase.mockReturnValue(mutationStub());
  mockUpdateStep.mockReturnValue(mutationStub());
  mockCursor.mockReturnValue({
    mutate: cursorMutate,
    isPending: false,
  } as never);
  mockSteps.mockReturnValue({ data: [], isLoading: false } as never);
});

afterEach(() => cleanup());

describe("TestRunRunner draft resets", () => {
  it("resets the notes draft when switching to a case with identical notes", () => {
    const caseA = caseResult("case-a", "same text");
    const caseB = caseResult("case-b", "same text");
    mockRun.mockReturnValue({
      data: runPayload({ currentCaseResultId: "case-a" }),
      isLoading: false,
      isError: false,
    } as never);
    mockCases.mockReturnValue({
      data: [caseA, caseB],
      isLoading: false,
    } as never);

    renderRunner();

    const notes = screen.getByPlaceholderText(
      /Notes about the overall case/i,
    ) as HTMLTextAreaElement;
    fireEvent.change(notes, { target: { value: "unsaved local edit" } });
    expect(notes.value).toBe("unsaved local edit");

    fireEvent.click(screen.getByRole("button", { name: /TC-case-b/i }));

    const notesAfter = screen.getByPlaceholderText(
      /Notes about the overall case/i,
    ) as HTMLTextAreaElement;
    expect(notesAfter.value).toBe("same text");
  });

  it("resets the comment draft when switching to a step with an identical comment", () => {
    const only = caseResult("case-a", null);
    mockRun.mockReturnValue({
      data: runPayload({
        currentCaseResultId: "case-a",
        currentStepResultId: "step-1",
      }),
      isLoading: false,
      isError: false,
    } as never);
    mockCases.mockReturnValue({ data: [only], isLoading: false } as never);
    mockSteps.mockReturnValue({
      data: [
        stepResult("step-1", "same note"),
        stepResult("step-2", "same note"),
      ],
      isLoading: false,
    } as never);

    const { rerender } = renderRunner();

    const comment = screen.getByPlaceholderText(
      /What did you observe/i,
    ) as HTMLTextAreaElement;
    fireEvent.change(comment, { target: { value: "unsaved local edit" } });
    expect(comment.value).toBe("unsaved local edit");

    mockRun.mockReturnValue({
      data: runPayload({
        currentCaseResultId: "case-a",
        currentStepResultId: "step-2",
      }),
      isLoading: false,
      isError: false,
    } as never);
    rerenderRunner(rerender);

    const commentAfter = screen.getByPlaceholderText(
      /What did you observe/i,
    ) as HTMLTextAreaElement;
    expect(commentAfter.value).toBe("same note");
  });
});

describe("TestRunRunner cursor persistence", () => {
  it("persists a zero-step case with a null step cursor", () => {
    mockRun.mockReturnValue({
      data: runPayload(),
      isLoading: false,
      isError: false,
    } as never);
    mockCases.mockReturnValue({
      data: [caseResult("case-a", null)],
      isLoading: false,
    } as never);
    mockSteps.mockReturnValue({ data: [], isLoading: false } as never);

    renderRunner();

    expect(cursorMutate).toHaveBeenCalledWith({
      currentCaseResultId: "case-a",
      currentStepResultId: null,
    });
  });

  it("does not re-fire once the run reflects the written cursor", () => {
    mockRun.mockReturnValue({
      data: runPayload(),
      isLoading: false,
      isError: false,
    } as never);
    mockCases.mockReturnValue({
      data: [caseResult("case-a", null)],
      isLoading: false,
    } as never);

    const { rerender } = renderRunner();
    expect(cursorMutate).toHaveBeenCalledTimes(1);

    // The mutation settles and the run now carries the written cursor. The
    // equality guard must swallow the re-run instead of looping.
    mockRun.mockReturnValue({
      data: runPayload({
        currentCaseResultId: "case-a",
        currentStepResultId: null,
      }),
      isLoading: false,
      isError: false,
    } as never);
    rerenderRunner(rerender);
    rerenderRunner(rerender);

    expect(cursorMutate).toHaveBeenCalledTimes(1);
  });

  it("waits for the step query before persisting a step-null cursor", () => {
    mockRun.mockReturnValue({
      data: runPayload(),
      isLoading: false,
      isError: false,
    } as never);
    mockCases.mockReturnValue({
      data: [caseResult("case-a", null)],
      isLoading: false,
    } as never);
    mockSteps.mockReturnValue({ data: undefined, isLoading: true } as never);

    renderRunner();

    expect(cursorMutate).not.toHaveBeenCalled();
  });
});
