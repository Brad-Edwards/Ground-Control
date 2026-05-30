// @vitest-environment jsdom
/**
 * GC-Q011 — Control and Assurance Workspace page tests.
 *
 * Tests cover loading, empty, composed data (control cards, owner queues, test
 * summary, assessment, exceptions, freshness badge, attention flag), and error.
 */

import type { ControlWorkspaceResponse } from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-control-workspace", () => ({
  useControlWorkspace: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "test-project", name: "Test Project" },
  }),
}));

import { useControlWorkspace } from "@/hooks/use-control-workspace";
import { ControlWorkspace } from "../control-workspace";

const mockUseWorkspace = vi.mocked(useControlWorkspace);

const emptyWorkspace: ControlWorkspaceResponse = {
  controls: [],
  ownerQueues: [],
  assets: [],
  controlCount: 0,
  ownerQueueCount: 0,
  assetCount: 0,
};

const composedWorkspace: ControlWorkspaceResponse = {
  controls: [
    {
      id: "ctl-1",
      uid: "CTL-001",
      title: "MFA on admin portal",
      controlFunction: "PREVENTIVE",
      status: "OPERATIONAL",
      owner: "Alice",
      category: "Access Control",
      scopedImplementations: [
        {
          id: "sci-1",
          uid: "SCI-001",
          name: "Prod MFA",
          operationalAssetId: "asset-1",
        },
      ],
      tests: [
        {
          id: "ct-1",
          uid: "CT-001",
          methodology: "INSPECTION",
          conclusion: "EFFECTIVE",
          testDate: "2026-05-01",
          testerIdentity: "Auditor",
        },
      ],
      testSummary: {
        total: 1,
        effective: 1,
        ineffective: 0,
        notTested: 0,
        latestTestDate: "2026-05-01",
        latestConclusion: "EFFECTIVE",
      },
      latestAssessment: {
        id: "cea-1",
        uid: "CEA-001",
        designEffectiveness: "EFFECTIVE",
        operatingEffectiveness: "PARTIALLY_EFFECTIVE",
        assessedAt: "2026-05-02",
        assessor: "Assessor",
      },
      mappingCount: 2,
      exceptions: [
        {
          id: "find-1",
          uid: "FIND-001",
          title: "Control deficiency",
          findingType: "CONTROL_DEFICIENCY",
          severity: "HIGH",
          status: "OPEN",
        },
      ],
      linkedAssetIds: ["asset-1"],
      staleIndicator: "STALE",
      needsAttention: true,
    },
    {
      id: "ctl-2",
      uid: "CTL-002",
      title: "Backups",
      controlFunction: "CORRECTIVE",
      status: "DRAFT",
      owner: "",
      category: "",
      scopedImplementations: [],
      tests: [],
      testSummary: {
        total: 0,
        effective: 0,
        ineffective: 0,
        notTested: 0,
        latestTestDate: null,
        latestConclusion: null,
      },
      latestAssessment: null,
      mappingCount: 0,
      exceptions: [],
      linkedAssetIds: [],
      staleIndicator: "NO_OBSERVATIONS",
      needsAttention: false,
    },
  ],
  ownerQueues: [
    {
      owner: "Alice",
      totalControls: 1,
      attentionControls: 1,
      attentionControlUids: ["CTL-001"],
    },
    {
      owner: "Unassigned",
      totalControls: 1,
      attentionControls: 0,
      attentionControlUids: [],
    },
  ],
  assets: [],
  controlCount: 2,
  ownerQueueCount: 2,
  assetCount: 0,
};

function renderPage() {
  return render(<ControlWorkspace />);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ControlWorkspace — loading state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useControlWorkspace>);
  });

  it("shows loading indicator", () => {
    renderPage();
    expect(screen.getByText(/loading workspace/i)).toBeTruthy();
  });
});

describe("ControlWorkspace — empty state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: emptyWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useControlWorkspace>);
  });

  it("shows empty-state for controls", () => {
    renderPage();
    expect(screen.getByText(/no controls match/i)).toBeTruthy();
  });
});

describe("ControlWorkspace — composed data", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: composedWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useControlWorkspace>);
  });

  it("renders control UIDs", () => {
    renderPage();
    // CTL-001 also appears in the owner-queue attention list, so it is not unique.
    expect(screen.getAllByText("CTL-001").length).toBeGreaterThan(0);
    expect(screen.getByText("CTL-002")).toBeTruthy();
  });

  it("renders the owner work queues with attention summary", () => {
    renderPage();
    expect(screen.getByText("Alice")).toBeTruthy();
    expect(screen.getByText(/1 need attention/i)).toBeTruthy();
    expect(screen.getByText("Unassigned")).toBeTruthy();
  });

  it("renders the freshness badge for the stale control", () => {
    renderPage();
    // FreshnessBadge labels STALE as "Stale"; a regression dropping the badge fails this.
    expect(screen.getByLabelText("Evidence freshness: STALE")).toBeTruthy();
  });

  it("flags the operational control needing attention", () => {
    renderPage();
    expect(screen.getAllByLabelText("Needs attention").length).toBe(1);
  });

  it("renders the exception linked to the control", () => {
    renderPage();
    expect(screen.getByText(/control deficiency/i)).toBeTruthy();
  });

  it("renders the latest assessment ratings", () => {
    renderPage();
    expect(screen.getByText(/design EFFECTIVE/i)).toBeTruthy();
  });
});

describe("ControlWorkspace — error state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    } as ReturnType<typeof useControlWorkspace>);
  });

  it("shows error message", () => {
    renderPage();
    expect(screen.getByText(/network error/i)).toBeTruthy();
  });
});
