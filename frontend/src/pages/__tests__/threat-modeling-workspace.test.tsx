// @vitest-environment jsdom
/**
 * GC-Q010 — Threat Modeling Workspace page tests.
 *
 * Tests cover:
 * - Page renders all three sections (Assets, Flows, Threat Entries)
 * - Boundary distinction (boundary flag on BOUNDARY-type assets)
 * - Staleness badge renders correctly per FreshnessState
 * - Empty-state messages appear when sections are empty
 * - Threat entries card shows status, STRIDE, linked controls, linked requirements
 *
 * Environment: vitest with jsdom (global: true from vitest.config.ts).
 * Uses @testing-library/react for DOM assertions.
 */

import type { ThreatModelWorkspaceResponse } from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// ── Mock dependencies ────────────────────────────────────────────────────────

// Mock the hook so we don't need a real QueryClient or API
vi.mock("@/hooks/use-threat-model-workspace", () => ({
  useThreatModelWorkspace: vi.fn(),
}));

// Mock project context
vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "test-project", name: "Test Project" },
  }),
}));

import { useThreatModelWorkspace } from "@/hooks/use-threat-model-workspace";
import { ThreatModelingWorkspace } from "../threat-modeling-workspace";

const mockUseWorkspace = vi.mocked(useThreatModelWorkspace);

// ── Test fixtures ────────────────────────────────────────────────────────────

const emptyWorkspace: ThreatModelWorkspaceResponse = {
  assets: [],
  flows: [],
  entries: [],
  assetCount: 0,
  flowCount: 0,
  entryCount: 0,
};

const composedWorkspace: ThreatModelWorkspaceResponse = {
  assets: [
    {
      id: "asset-1",
      uid: "A-001",
      name: "Auth Service",
      assetType: "SERVICE",
      boundary: false,
    },
    {
      id: "asset-2",
      uid: "B-001",
      name: "DMZ",
      assetType: "BOUNDARY",
      boundary: true,
    },
  ],
  flows: [
    {
      id: "flow-1",
      sourceAssetId: "asset-1",
      targetAssetId: "asset-2",
      relationType: "DATA_FLOW",
    },
  ],
  entries: [
    {
      id: "entry-1",
      uid: "TM-001",
      title: "Credential stuffing",
      status: "ACTIVE",
      stride: "SPOOFING",
      linkedAssetIds: ["asset-1"],
      linkedControls: [
        {
          targetEntityId: "ctl-1",
          targetIdentifier: "CTL-001",
          targetTitle: "MFA Control",
          targetUrl: "https://example.com/ctl-1",
        },
      ],
      linkedRequirements: [
        {
          targetEntityId: "req-1",
          targetIdentifier: "GC-H001",
          targetTitle: "Security Req",
          targetUrl: "https://example.com/req-1",
        },
      ],
      staleIndicator: "STALE",
    },
    {
      id: "entry-2",
      uid: "TM-002",
      title: "SQL injection",
      status: "DRAFT",
      stride: null,
      linkedAssetIds: [],
      linkedControls: [],
      linkedRequirements: [],
      staleIndicator: "NO_OBSERVATIONS",
    },
  ],
  assetCount: 2,
  flowCount: 1,
  entryCount: 2,
};

// ── Helper ───────────────────────────────────────────────────────────────────

function renderWorkspace() {
  return render(<ThreatModelingWorkspace />);
}

// ── Tests ────────────────────────────────────────────────────────────────────

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ThreatModelingWorkspace — loading state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useThreatModelWorkspace>);
  });

  it("shows loading indicator", () => {
    renderWorkspace();
    expect(screen.getByText(/loading workspace/i)).toBeTruthy();
  });
});

describe("ThreatModelingWorkspace — empty state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: emptyWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useThreatModelWorkspace>);
  });

  it("renders all three section headings", () => {
    renderWorkspace();
    expect(screen.getAllByText(/assets/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/flows/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/threat entries/i).length).toBeGreaterThan(0);
  });

  it("shows empty-state for assets", () => {
    renderWorkspace();
    expect(screen.getByText(/no assets in scope/i)).toBeTruthy();
  });

  it("shows empty-state for flows", () => {
    renderWorkspace();
    expect(screen.getByText(/no active flows/i)).toBeTruthy();
  });

  it("shows empty-state for threat entries", () => {
    renderWorkspace();
    expect(screen.getByText(/no threat entries match/i)).toBeTruthy();
  });
});

describe("ThreatModelingWorkspace — composed data", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: composedWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useThreatModelWorkspace>);
  });

  it("renders asset UIDs", () => {
    renderWorkspace();
    expect(screen.getAllByText("A-001").length).toBeGreaterThan(0);
    expect(screen.getAllByText("B-001").length).toBeGreaterThan(0);
  });

  it("shows Boundary badge for BOUNDARY-type asset", () => {
    renderWorkspace();
    const boundaryBadges = screen.getAllByText("Boundary");
    expect(boundaryBadges.length).toBeGreaterThan(0);
  });

  it("does not show Boundary badge for non-boundary asset row", () => {
    renderWorkspace();
    // The fixture has exactly one BOUNDARY asset (B-001/DMZ) and one SERVICE
    // asset (A-001/Auth Service). Exactly one Boundary badge must render — a
    // second badge would mean the non-boundary row is wrongly flagged.
    expect(screen.queryAllByText("Boundary").length).toBe(1);
  });

  it("renders flow table with DATA_FLOW relation", () => {
    renderWorkspace();
    // The relationType is displayed with underscores replaced by spaces
    expect(screen.getAllByText(/DATA FLOW/i).length).toBeGreaterThan(0);
  });

  it("renders threat entry UIDs", () => {
    renderWorkspace();
    expect(screen.getByText("TM-001")).toBeTruthy();
    expect(screen.getByText("TM-002")).toBeTruthy();
  });

  it("renders threat entry status badges", () => {
    renderWorkspace();
    expect(screen.getAllByText("ACTIVE").length).toBeGreaterThan(0);
    expect(screen.getAllByText("DRAFT").length).toBeGreaterThan(0);
  });

  it("renders STRIDE badge for entry with stride set", () => {
    renderWorkspace();
    // SPOOFING displayed as "SPOOFING" (no underscore in this value)
    expect(screen.getAllByText(/SPOOFING/i).length).toBeGreaterThan(0);
  });

  it("renders staleness badge as Stale for STALE state", () => {
    renderWorkspace();
    const staleLabels = screen.getAllByText("Stale");
    expect(staleLabels.length).toBeGreaterThan(0);
  });

  it("renders staleness badge as No evidence for NO_OBSERVATIONS state", () => {
    renderWorkspace();
    const noEvidenceLabels = screen.getAllByText("No evidence");
    expect(noEvidenceLabels.length).toBeGreaterThan(0);
  });

  it("renders linked controls inside the entry card", () => {
    renderWorkspace();
    expect(screen.getByText("MFA Control")).toBeTruthy();
  });

  it("renders linked requirements inside the entry card", () => {
    renderWorkspace();
    expect(screen.getByText("Security Req")).toBeTruthy();
  });

  it("renders counts in section headings", () => {
    renderWorkspace();
    // The count badges appear in section headings like "Assets (2)"
    const heading = screen.getByRole("heading", { name: /assets/i });
    expect(heading.textContent).toContain("2");
  });
});

describe("ThreatModelingWorkspace — error state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    } as ReturnType<typeof useThreatModelWorkspace>);
  });

  it("shows error message", () => {
    renderWorkspace();
    expect(screen.getByText(/network error/i)).toBeTruthy();
  });
});
