// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Mock apiFetch before importing the hook so the module-level import is replaced.
vi.mock("@/lib/api-client", () => ({
  apiFetch: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
  }),
}));

import { apiFetch } from "@/lib/api-client";
import type {
  WorkflowActivityResponse,
  WorkflowRunAggregateResponse,
  WorkflowRunResponse,
} from "@/types/api";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { createElement } from "react";
import {
  useWorkflowActivity,
  useWorkflowRunAggregate,
  useWorkflowRuns,
} from "../use-workflow-runs";

const mockApiFetch = vi.mocked(apiFetch);

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const mockRun: WorkflowRunResponse = {
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

const mockAggregate: WorkflowRunAggregateResponse = {
  from: "2026-06-01T00:00:00Z",
  to: "2026-06-24T00:00:00Z",
  totalRuns: 10,
  mergedRuns: 7,
  closedRuns: 2,
  activeRuns: 1,
  escalatedRuns: 0,
  abandonedRuns: 0,
  supersededRuns: 0,
  cycleTimeP50Min: 60,
  cycleTimeP95Min: 180,
  cycleTimeP99Min: 240,
  totalCostProxy: 3.2,
  mergedCostProxy: 2.5,
  closedCostProxy: 0.7,
  costProxyPerMergedRun: 0.357,
  costProxyPerClosedRun: 0.35,
  totalModelInvocations: 200,
  totalWallClockMinutes: 800,
  totalTokenUsage: 300_000,
  phaseHotspots: [
    {
      phase: "CODEX_REVIEW",
      eventCount: 10,
      failedCount: 1,
      escalatedCount: 0,
      p50Ms: 3000,
      p95Ms: 8000,
      maxCycleIndex: 3,
    },
  ],
};

const mockActivity: WorkflowActivityResponse = {
  asOf: "2026-07-30T10:00:00Z",
  openRunTotal: 0,
  openRunsTruncated: false,
  openRuns: [],
  recentlyFinished: [],
};

afterEach(() => {
  vi.clearAllMocks();
});

describe("useWorkflowRuns", () => {
  beforeEach(() => {
    mockApiFetch.mockResolvedValue([mockRun]);
  });

  it("fetches workflow runs and returns them", async () => {
    const { result } = renderHook(() => useWorkflowRuns("ground-control"), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual([mockRun]);
    expect(mockApiFetch).toHaveBeenCalledWith("/workflow-runs", {
      params: { project: "ground-control", limit: "50" },
    });
  });

  it("is disabled when projectIdentifier is empty", async () => {
    const { result } = renderHook(() => useWorkflowRuns(""), {
      wrapper: createWrapper(),
    });

    // Should stay in pending/idle since enabled:false
    expect(result.current.isPending).toBe(true);
    expect(mockApiFetch).not.toHaveBeenCalled();
  });
});

describe("useWorkflowActivity", () => {
  it("fetches the project-scoped bounded snapshot", async () => {
    mockApiFetch.mockResolvedValue(mockActivity);
    const { result } = renderHook(() => useWorkflowActivity("ground-control"), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(mockActivity);
    expect(mockApiFetch).toHaveBeenCalledWith("/workflow-runs/activity", {
      params: { project: "ground-control" },
    });
  });
});

describe("useWorkflowRunAggregate", () => {
  beforeEach(() => {
    mockApiFetch.mockResolvedValue(mockAggregate);
  });

  it("fetches aggregate data and returns it", async () => {
    const { result } = renderHook(
      () => useWorkflowRunAggregate("ground-control"),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(mockAggregate);
    expect(mockApiFetch).toHaveBeenCalledWith("/workflow-runs/aggregate", {
      params: { project: "ground-control" },
    });
  });

  it("passes all filter params when provided", async () => {
    const filters = {
      repo: "autarchy-ai/Ground-Control",
      runtime: "codex",
      requirement: "GC-T001",
      workflowType: "CODEX_JOB",
      outcome: "MERGED",
      from: "2026-06-01",
      to: "2026-06-24",
    };

    const { result } = renderHook(
      () => useWorkflowRunAggregate("ground-control", filters),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockApiFetch).toHaveBeenCalledWith("/workflow-runs/aggregate", {
      params: {
        project: "ground-control",
        repo: "autarchy-ai/Ground-Control",
        runtime: "codex",
        requirement: "GC-T001",
        workflowType: "CODEX_JOB",
        outcome: "MERGED",
        from: "2026-06-01",
        to: "2026-06-24",
      },
    });
  });

  it("omits undefined filter params", async () => {
    const { result } = renderHook(
      () => useWorkflowRunAggregate("ground-control", { repo: "some-repo" }),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockApiFetch).toHaveBeenCalledWith("/workflow-runs/aggregate", {
      params: { project: "ground-control", repo: "some-repo" },
    });
  });

  it("is disabled when projectIdentifier is empty", () => {
    const { result } = renderHook(() => useWorkflowRunAggregate(""), {
      wrapper: createWrapper(),
    });

    expect(result.current.isPending).toBe(true);
    expect(mockApiFetch).not.toHaveBeenCalled();
  });
});

describe("live refresh", () => {
  // A run now advances while the page is open (issue #1435), so a snapshot taken at mount goes
  // stale within a phase. These assert the polling actually happens: dropping refetchInterval
  // reverts the page to static data, which no other test in this suite would notice.
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("refetches the run list on an interval", async () => {
    mockApiFetch.mockResolvedValue([mockRun]);
    renderHook(() => useWorkflowRuns("ground-control"), {
      wrapper: createWrapper(),
    });

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(31_000);
    await vi.waitFor(() =>
      expect(mockApiFetch.mock.calls.length).toBeGreaterThan(1),
    );
  });

  it("refetches the aggregate on an interval", async () => {
    mockApiFetch.mockResolvedValue(mockAggregate);
    renderHook(() => useWorkflowRunAggregate("ground-control"), {
      wrapper: createWrapper(),
    });

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(31_000);
    await vi.waitFor(() =>
      expect(mockApiFetch.mock.calls.length).toBeGreaterThan(1),
    );
  });

  it("refetches activity on an interval when the stream is degraded", async () => {
    mockApiFetch.mockResolvedValue(mockActivity);
    renderHook(() => useWorkflowActivity("ground-control"), {
      wrapper: createWrapper(),
    });

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(31_000);
    await vi.waitFor(() =>
      expect(mockApiFetch.mock.calls.length).toBeGreaterThan(1),
    );
  });

  // Polling is the *fallback* (issue #1436). While the stream is live it must be off, or the page
  // keeps hammering the API for data the transport is already pushing; the moment the stream drops
  // it must come back, or the page silently stops updating at all.
  it("stops polling the run list while the live stream is connected", async () => {
    mockApiFetch.mockResolvedValue([mockRun]);
    renderHook(() => useWorkflowRuns("ground-control", { live: true }), {
      wrapper: createWrapper(),
    });

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(120_000);

    expect(mockApiFetch).toHaveBeenCalledTimes(1);
  });

  it("stops polling the aggregate while the live stream is connected", async () => {
    mockApiFetch.mockResolvedValue(mockAggregate);
    renderHook(
      () => useWorkflowRunAggregate("ground-control", {}, { live: true }),
      {
        wrapper: createWrapper(),
      },
    );

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(120_000);

    expect(mockApiFetch).toHaveBeenCalledTimes(1);
  });

  it("stops polling activity while the live stream is connected", async () => {
    mockApiFetch.mockResolvedValue(mockActivity);
    renderHook(() => useWorkflowActivity("ground-control", { live: true }), {
      wrapper: createWrapper(),
    });

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(120_000);

    expect(mockApiFetch).toHaveBeenCalledTimes(1);
  });

  it("resumes polling when the stream degrades", async () => {
    mockApiFetch.mockResolvedValue([mockRun]);
    const { rerender } = renderHook(
      ({ live }: { live: boolean }) =>
        useWorkflowRuns("ground-control", { live }),
      { wrapper: createWrapper(), initialProps: { live: true } },
    );

    await vi.waitFor(() => expect(mockApiFetch).toHaveBeenCalledTimes(1));
    rerender({ live: false });
    await vi.advanceTimersByTimeAsync(31_000);

    await vi.waitFor(() =>
      expect(mockApiFetch.mock.calls.length).toBeGreaterThan(1),
    );
  });
});
