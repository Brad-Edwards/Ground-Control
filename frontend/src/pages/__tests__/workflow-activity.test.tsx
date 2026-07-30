// @vitest-environment jsdom

import type { WorkflowActivityResponse } from "@/types/api";
import { act, cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-workflow-runs", () => ({
  useWorkflowActivity: vi.fn(),
}));

vi.mock("@/hooks/use-workflow-run-stream", () => ({
  useWorkflowRunStream: vi.fn(),
}));

vi.mock("react-router", async () => {
  const actual =
    await vi.importActual<typeof import("react-router")>("react-router");
  return {
    ...actual,
    useParams: () => ({ projectId: "ground-control" }),
  };
});

import { useWorkflowRunStream } from "@/hooks/use-workflow-run-stream";
import { useWorkflowActivity } from "@/hooks/use-workflow-runs";
import { WorkflowActivity } from "../workflow-activity";

const mockUseActivity = vi.mocked(useWorkflowActivity);
const mockUseStream = vi.mocked(useWorkflowRunStream);

const snapshot: WorkflowActivityResponse = {
  asOf: "2026-07-30T10:20:00Z",
  openRunTotal: 1,
  openRunsTruncated: false,
  openRuns: [
    {
      run: {
        id: "run-open",
        project: "ground-control",
        repo: "autarchy-ai/Ground-Control",
        issueNumber: 1437,
        prNumber: null,
        branch: "1437-live-activity-view",
        workflowType: "implement",
        runtimeDriver: "codex",
        startedAt: "2026-07-30T09:30:00Z",
        endedAt: null,
        finalState: "RUNNING",
        outcome: "NONE",
        costProxy: 0.42,
        costCurrency: "USD",
        tokenUsage: 12000,
      },
      currentPhase: "completion_gate",
      currentPhaseTitle: "Completion gate",
      currentPhaseSince: "2026-07-30T10:00:00Z",
      currentCycle: 2,
      stallThresholdMs: 30 * 60 * 1000,
      routing: {
        stage: "implementation",
        stepAlias: "05",
        tier: "HIGH",
        model: "claude-opus",
        expectedModel: "claude-opus",
        modelMatchesExpected: true,
        occurredAt: "2026-07-30T09:55:00Z",
      },
      gates: [
        {
          stationId: "codex_review",
          stationTitle: "Codex review",
          eventType: "FAILED",
          stationResult: "FAIL",
          cycleIndex: 9,
          occurredAt: "2026-07-30T10:10:00Z",
          durationMs: 90_000,
          findingCount: 4,
          findingsDropped: 2,
        },
        {
          stationId: "ci",
          stationTitle: "CI",
          eventType: "STARTED",
          stationResult: "UNOBSERVED",
          cycleIndex: 2,
          occurredAt: "2026-07-30T10:15:00Z",
          durationMs: null,
          findingCount: 0,
          findingsDropped: 0,
        },
        {
          stationId: "sonarcloud",
          stationTitle: "SonarCloud",
          eventType: null,
          stationResult: "UNOBSERVED",
          cycleIndex: null,
          occurredAt: null,
          durationMs: null,
          findingCount: 0,
          findingsDropped: 0,
        },
      ],
    },
  ],
  recentlyFinished: [
    {
      id: "run-terminal",
      project: "ground-control",
      repo: "autarchy-ai/Ground-Control",
      issueNumber: 1436,
      prNumber: 1458,
      branch: "1436-live-stream",
      workflowType: "implement",
      runtimeDriver: "codex",
      startedAt: "2026-07-30T08:00:00Z",
      endedAt: "2026-07-30T09:00:00Z",
      finalState: "MERGED",
      outcome: "MERGED",
      costProxy: 0.25,
      costCurrency: "USD",
      tokenUsage: 9000,
    },
  ],
};

function renderPage() {
  return render(
    <MemoryRouter>
      <WorkflowActivity />
    </MemoryRouter>,
  );
}

function firstOpenRun() {
  const openRun = snapshot.openRuns[0];
  if (!openRun) throw new Error("test snapshot must contain an open run");
  return openRun;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-07-30T10:20:00Z"));
  mockUseStream.mockReturnValue({ status: "live" });
  mockUseActivity.mockReturnValue({
    data: snapshot,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useWorkflowActivity>);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe("WorkflowActivity", () => {
  it("renders live run identity, current phase, routing, and gate progress", () => {
    renderPage();

    expect(screen.getByText("Live Activity")).toBeTruthy();
    expect(screen.getByText("#1437")).toBeTruthy();
    expect(screen.getByText("1437-live-activity-view")).toBeTruthy();
    expect(screen.getByText("Completion gate")).toBeTruthy();
    expect(screen.getByText("Cycle").parentElement?.textContent).toContain("2");
    expect(screen.getByText("claude-opus · HIGH")).toBeTruthy();
    expect(screen.getByText("Codex review")).toBeTruthy();
    expect(screen.getByText("Failed")).toBeTruthy();
    expect(screen.getByText("CI")).toBeTruthy();
    expect(screen.getAllByText("Running")).toHaveLength(2);
    expect(screen.getByText("SonarCloud")).toBeTruthy();
    expect(screen.getByText("Unobserved")).toBeTruthy();
    expect(screen.getByText(/4 persisted findings · 2 dropped/i)).toBeTruthy();
    expect(mockUseActivity).toHaveBeenCalledWith("ground-control", {
      live: true,
    });
  });

  it("moves terminal runs to the recent band and links to history", () => {
    renderPage();

    expect(screen.getByText("Recently finished")).toBeTruthy();
    expect(screen.getByText("#1436")).toBeTruthy();
    expect(screen.getByText("1436-live-stream")).toBeTruthy();
    expect(
      screen
        .getByRole("link", { name: "View run history" })
        .getAttribute("href"),
    ).toBe("/p/ground-control/workflow-runs");
  });

  it("raises an attention flag when the server-anchored threshold passes", async () => {
    renderPage();
    expect(screen.queryByText(/Possibly stalled/)).toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(11 * 60 * 1000);
    });

    expect(screen.getByText(/Possibly stalled/)).toBeTruthy();
    expect(screen.getByText(/31m without a lifecycle transition/)).toBeTruthy();
  });

  it("uses paused-state wording instead of claiming process liveness", () => {
    const openRun = firstOpenRun();
    mockUseActivity.mockReturnValue({
      data: {
        ...snapshot,
        asOf: "2026-07-30T10:40:00Z",
        openRuns: [
          {
            ...openRun,
            run: {
              ...openRun.run,
              finalState: "READY_FOR_REVIEW",
            },
          },
        ],
      },
      isLoading: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorkflowActivity>);

    renderPage();

    expect(screen.getByText(/Waiting beyond threshold/)).toBeTruthy();
    expect(screen.queryByText(/Possibly stalled/)).toBeNull();
  });

  it("labels missing phase and routing observations without inventing state", () => {
    const openRun = firstOpenRun();
    mockUseActivity.mockReturnValue({
      data: {
        ...snapshot,
        openRuns: [
          {
            ...openRun,
            currentPhase: null,
            currentPhaseTitle: null,
            currentPhaseSince: null,
            currentCycle: null,
            routing: null,
            gates: [],
          },
        ],
      },
      isLoading: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorkflowActivity>);

    renderPage();

    expect(screen.getAllByText("Unobserved").length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText("No station attempts observed yet.")).toBeTruthy();
  });
});
