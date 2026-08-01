// @vitest-environment jsdom

import type {
  WorkflowRunAggregateResponse,
  WorkflowRunResponse,
} from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-workflow-runs", () => ({
  useWorkflowRunAggregate: vi.fn(),
  useWorkflowRuns: vi.fn(),
}));

vi.mock("@/hooks/use-workflow-run-stream", () => ({
  useWorkflowRunStream: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
  }),
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
import {
  useWorkflowRunAggregate,
  useWorkflowRuns,
} from "@/hooks/use-workflow-runs";
import { WorkflowRuns } from "../workflow-runs";

const mockUseAggregate = vi.mocked(useWorkflowRunAggregate);
const mockUseRuns = vi.mocked(useWorkflowRuns);
const mockUseStream = vi.mocked(useWorkflowRunStream);

const emptyAggregate: WorkflowRunAggregateResponse = {
  from: "2026-06-01T00:00:00Z",
  to: "2026-06-24T00:00:00Z",
  totalRuns: 0,
  mergedRuns: 0,
  closedRuns: 0,
  activeRuns: 0,
  escalatedRuns: 0,
  abandonedRuns: 0,
  supersededRuns: 0,
  cycleTimeP50Min: null,
  cycleTimeP95Min: null,
  cycleTimeP99Min: null,
  totalCostProxy: 0,
  mergedCostProxy: 0,
  closedCostProxy: 0,
  costProxyPerMergedRun: null,
  costProxyPerClosedRun: null,
  totalModelInvocations: 0,
  totalWallClockMinutes: 0,
  totalTokenUsage: 0,
  phaseHotspots: [],
};

const composedAggregate: WorkflowRunAggregateResponse = {
  from: "2026-06-01T00:00:00Z",
  to: "2026-06-24T00:00:00Z",
  totalRuns: 42,
  mergedRuns: 28,
  closedRuns: 8,
  activeRuns: 4,
  escalatedRuns: 1,
  abandonedRuns: 0,
  supersededRuns: 1,
  cycleTimeP50Min: 95,
  cycleTimeP95Min: 320,
  cycleTimeP99Min: 480,
  totalCostProxy: 12.5,
  mergedCostProxy: 9.1,
  closedCostProxy: 2.4,
  costProxyPerMergedRun: 0.325,
  costProxyPerClosedRun: 0.3,
  totalModelInvocations: 840,
  totalWallClockMinutes: 3200,
  totalTokenUsage: 1_200_000,
  phaseHotspots: [
    {
      phase: "CODEX_REVIEW",
      eventCount: 42,
      failedCount: 3,
      escalatedCount: 1,
      p50Ms: 4500,
      p95Ms: 12000,
      maxCycleIndex: 5,
    },
    {
      phase: "MERGE_GUARD",
      eventCount: 28,
      failedCount: 0,
      escalatedCount: 0,
      p50Ms: 800,
      p95Ms: 2000,
      maxCycleIndex: null,
    },
  ],
};

const activeRun: WorkflowRunResponse = {
  id: "run-1",
  graphNodeId: "WORKFLOW_RUN:run-1",
  project: "ground-control",
  repo: "autarchy-ai/Ground-Control",
  issueNumber: 859,
  prNumber: null,
  branch: "feature/telemetry",
  workflowType: "CODEX_JOB",
  runtimeDriver: "codex",
  requirementUids: ["GC-T001"],
  startedAt: "2026-06-24T08:00:00Z",
  endedAt: null,
  finalState: "RUNNING",
  outcome: "NONE",
  provenance: "ISSUE_THREAD",
  provider: "openai",
  model: "codex-mini",
  modelInvocationCount: 12,
  wallClockMinutes: 45,
  costProxy: 0.18,
  costCurrency: "USD",
  tokenUsage: 48000,
  createdAt: "2026-06-24T08:00:00Z",
  updatedAt: "2026-06-24T08:45:00Z",
};

const readyRun: WorkflowRunResponse = {
  ...activeRun,
  id: "run-2",
  graphNodeId: "WORKFLOW_RUN:run-2",
  finalState: "READY_FOR_REVIEW",
  outcome: "NONE",
  branch: "feature/ready",
  prNumber: 1234,
};

const mergedRun: WorkflowRunResponse = {
  ...activeRun,
  id: "run-3",
  graphNodeId: "WORKFLOW_RUN:run-3",
  finalState: "MERGED",
  outcome: "MERGED",
  branch: "feature/done",
  endedAt: "2026-06-23T10:00:00Z",
};

const failedRun: WorkflowRunResponse = {
  ...activeRun,
  id: "run-4",
  graphNodeId: "WORKFLOW_RUN:run-4",
  finalState: "FAILED",
  outcome: "CLOSED_WITHOUT_MERGE",
  branch: "feature/failed",
  endedAt: "2026-06-23T11:00:00Z",
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

beforeEach(() => {
  // Default every page test to a connected stream; the transport-state tests override it.
  mockUseStream.mockReturnValue({ status: "live" });
});

describe("WorkflowRuns — data loaded", () => {
  beforeEach(() => {
    mockUseAggregate.mockReturnValue({
      data: composedAggregate,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRunAggregate>);

    mockUseRuns.mockReturnValue({
      data: [activeRun, readyRun, mergedRun],
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRuns>);
  });

  it("renders the page heading", () => {
    render(<WorkflowRuns />);
    expect(screen.getByText("Workflow Runs")).toBeTruthy();
  });

  it("renders headline throughput metrics", () => {
    render(<WorkflowRuns />);

    // Total runs metric card label is unique
    expect(screen.getByText("Total runs")).toBeTruthy();
    // 42 appears in the metric card value and detail text — use getAllByText
    expect(screen.getAllByText("42").length).toBeGreaterThan(0);

    // Merged and closed counts
    expect(screen.getAllByText("28").length).toBeGreaterThan(0);
    expect(screen.getAllByText("8").length).toBeGreaterThan(0);
  });

  it("renders cycle-time distribution bars", () => {
    render(<WorkflowRuns />);

    expect(
      screen.getAllByText("Cycle-time distribution").length,
    ).toBeGreaterThan(0);
    // P50 = 95m → "1h 35m"
    expect(screen.getByText("1h 35m")).toBeTruthy();
    // P95 = 320m → "5h 20m"
    expect(screen.getByText("5h 20m")).toBeTruthy();
    // P99 = 480m → "8h"
    expect(screen.getByText("8h")).toBeTruthy();
  });

  it("renders phase hotspots table with correct columns", () => {
    render(<WorkflowRuns />);

    expect(
      screen.getAllByText("Review / gate hot spots").length,
    ).toBeGreaterThan(0);
    expect(screen.getByText("CODEX_REVIEW")).toBeTruthy();
    expect(screen.getByText("MERGE_GUARD")).toBeTruthy();

    // failedCount for CODEX_REVIEW — "3" should appear at least once
    expect(screen.getAllByText("3").length).toBeGreaterThan(0);
    // escalatedCount for CODEX_REVIEW — "1" appears multiple times (also escalatedRuns metric)
    expect(screen.getAllByText("1").length).toBeGreaterThan(0);
  });

  it("renders cost proxies section with per-run metrics", () => {
    render(<WorkflowRuns />);

    expect(screen.getAllByText("Cost proxies").length).toBeGreaterThan(0);
    expect(screen.getByText("Total cost proxy")).toBeTruthy();
    expect(screen.getByText("Cost / merged run")).toBeTruthy();
    expect(screen.getByText("Cost / closed run")).toBeTruthy();
  });

  it("keeps open and terminal records in the historical table", () => {
    render(<WorkflowRuns />);

    expect(screen.getAllByText("Recent run records").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Running").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Ready For Review").length).toBeGreaterThan(0);
    expect(screen.getAllByText("feature/telemetry").length).toBeGreaterThan(0);
    expect(screen.getAllByText("feature/ready").length).toBeGreaterThan(0);
    expect(screen.getByText("feature/done")).toBeTruthy();
  });

  it("keeps a failed run reachable in history with its terminal badge", () => {
    mockUseRuns.mockReturnValue({
      data: [activeRun, failedRun],
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRuns>);

    render(<WorkflowRuns />);

    expect(screen.getByText("feature/failed")).toBeTruthy();
    expect(screen.getByLabelText("Final state: FAILED")).toBeTruthy();
  });

  it("renders filters panel with all filter fields", () => {
    render(<WorkflowRuns />);

    expect(screen.getByLabelText("From")).toBeTruthy();
    expect(screen.getByLabelText("To")).toBeTruthy();
    expect(screen.getByLabelText("Repo")).toBeTruthy();
    expect(screen.getByLabelText("Runtime / agent")).toBeTruthy();
    expect(screen.getByLabelText("Requirement UID")).toBeTruthy();
    expect(screen.getByLabelText("Workflow type")).toBeTruthy();
    expect(screen.getByLabelText("Outcome")).toBeTruthy();
  });
});

describe("WorkflowRuns — transport state", () => {
  beforeEach(() => {
    mockUseAggregate.mockReturnValue({
      data: composedAggregate,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRunAggregate>);
    mockUseRuns.mockReturnValue({
      data: [],
      isLoading: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorkflowRuns>);
  });

  it("says Live and suppresses polling while the stream is connected", () => {
    mockUseStream.mockReturnValue({ status: "live" });

    render(<WorkflowRuns />);

    expect(screen.getByText("Live")).toBeTruthy();
    expect(mockUseRuns).toHaveBeenCalledWith("ground-control", { live: true });
  });

  it("says Polling and re-arms the fallback when the stream drops", () => {
    // Stream loss must be visible (issue #1436 AC-4): silently showing the last pushed values as
    // current is the failure this state exists to prevent.
    mockUseStream.mockReturnValue({ status: "degraded" });

    render(<WorkflowRuns />);

    expect(screen.getByText("Polling")).toBeTruthy();
    expect(screen.getByText(/refreshing every 30 seconds/i)).toBeTruthy();
    expect(mockUseRuns).toHaveBeenCalledWith("ground-control", { live: false });
  });

  it("reports the connecting state before the stream is established", () => {
    mockUseStream.mockReturnValue({ status: "connecting" });

    render(<WorkflowRuns />);

    expect(screen.getByText("Connecting")).toBeTruthy();
  });
});

describe("WorkflowRuns — loading state", () => {
  it("shows loading message while data is fetching", () => {
    mockUseAggregate.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRunAggregate>);

    mockUseRuns.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRuns>);

    render(<WorkflowRuns />);

    expect(screen.getByText(/loading workflow run data/i)).toBeTruthy();
  });
});

describe("WorkflowRuns — error state", () => {
  it("shows error message on aggregate failure", () => {
    mockUseAggregate.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("aggregate fetch failed"),
    } as ReturnType<typeof useWorkflowRunAggregate>);

    mockUseRuns.mockReturnValue({
      data: [] as WorkflowRunResponse[],
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRuns>);

    render(<WorkflowRuns />);

    expect(screen.getByText("aggregate fetch failed")).toBeTruthy();
  });
});

describe("WorkflowRuns — empty state", () => {
  it("shows empty message when aggregate has zero runs", () => {
    mockUseAggregate.mockReturnValue({
      data: emptyAggregate,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRunAggregate>);

    mockUseRuns.mockReturnValue({
      data: [] as WorkflowRunResponse[],
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRuns>);

    render(<WorkflowRuns />);

    // 0-run aggregate still renders (totalRuns: 0)
    expect(screen.getByText("Throughput")).toBeTruthy();
    expect(screen.getByText(/no cycle-time data available/i)).toBeTruthy();
    expect(screen.getByText(/no phase data available/i)).toBeTruthy();
    expect(
      screen.getByText(/no workflow runs have been recorded/i),
    ).toBeTruthy();
  });
});

describe("WorkflowRuns — hook contract", () => {
  it("calls useWorkflowRunAggregate with the project identifier", () => {
    mockUseAggregate.mockReturnValue({
      data: composedAggregate,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRunAggregate>);

    mockUseRuns.mockReturnValue({
      data: [] as WorkflowRunResponse[],
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useWorkflowRuns>);

    render(<WorkflowRuns />);

    expect(mockUseAggregate).toHaveBeenCalledWith(
      "ground-control",
      expect.any(Object),
      expect.any(Object),
    );
    expect(mockUseRuns).toHaveBeenCalledWith(
      "ground-control",
      expect.any(Object),
    );
  });
});
