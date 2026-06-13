// @vitest-environment jsdom

import type { EvidenceStateWorkspaceResponse } from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-evidence-state-workspace", () => ({
  useEvidenceStateWorkspace: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
  }),
}));

import { useEvidenceStateWorkspace } from "@/hooks/use-evidence-state-workspace";
import { EvidenceStateExplorer } from "../evidence-state-explorer";

const mockUseWorkspace = vi.mocked(useEvidenceStateWorkspace);

const emptyWorkspace: EvidenceStateWorkspaceResponse = {
  assets: [],
  evidenceArtifacts: [],
  observations: [],
  counts: { fresh: 0, stale: 0, expired: 0, superseded: 0, currentlyValid: 0 },
  limitations: [],
  assetCount: 0,
  artifactCount: 0,
  observationCount: 0,
};

const composedWorkspace: EvidenceStateWorkspaceResponse = {
  assets: [
    {
      id: "asset-1",
      uid: "ASSET-001",
      name: "Payments API",
      assetType: "SERVICE",
      boundary: false,
    },
  ],
  evidenceArtifacts: [
    {
      id: "ev-1",
      uid: "EV-001",
      title: "Patch assurance",
      summaryPreview: "Patch evidence summary",
      evidenceType: "OBSERVATION_SUMMARY",
      derivedAt: "2026-06-01T12:00:00Z",
      ageDays: 0,
      freshnessState: "FRESH",
      supersededByArtifactId: null,
      derivedBy: "agent",
      assuranceLevel: "L2",
      confidence: "HIGH",
      sources: [
        {
          sourceKind: "OBSERVATION",
          sourceEntityId: "obs-1",
          sourceIdentifier: null,
          role: "source",
          label: "ASSET-001 patch_level",
        },
      ],
      affectedAssets: [
        {
          targetEntityId: "asset-1",
          targetIdentifier: "ASSET-001",
          targetTitle: "Payments API",
          targetUrl: null,
        },
      ],
      linkedControls: [
        {
          targetEntityId: "ctl-1",
          targetIdentifier: "CTL-001",
          targetTitle: "Patch control",
          targetUrl: null,
        },
      ],
      downstreamAssessments: [
        {
          targetEntityId: "assess-1",
          targetIdentifier: "RS-001",
          targetTitle: "FAIR",
          targetUrl: null,
        },
      ],
      linkedFindings: [
        {
          targetEntityId: "find-1",
          targetIdentifier: "FIND-001",
          targetTitle: "Patch drift",
          targetUrl: null,
        },
      ],
    },
  ],
  observations: [
    {
      id: "obs-1",
      assetId: "asset-1",
      assetUid: "ASSET-001",
      category: "CONFIGURATION",
      observationKey: "patch_level",
      valuePreview: "2026-05 cumulative update",
      source: "collector",
      evidenceRef: "collector://patch",
      observedAt: "2026-06-01T11:00:00Z",
      expiresAt: null,
      ageDays: 0,
      freshnessState: "FRESH",
      confidence: "HIGH",
      evidenceArtifacts: [
        {
          targetEntityId: "ev-1",
          targetIdentifier: "EV-001",
          targetTitle: "Patch assurance",
          targetUrl: null,
        },
      ],
      downstreamAssessments: [],
      linkedFindings: [],
    },
  ],
  counts: { fresh: 2, stale: 0, expired: 0, superseded: 0, currentlyValid: 2 },
  limitations: ["superseded artifacts excluded"],
  assetCount: 1,
  artifactCount: 1,
  observationCount: 1,
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("EvidenceStateExplorer", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: composedWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useEvidenceStateWorkspace>);
  });

  it("renders evidence artifacts, provenance, freshness, and impact links", () => {
    render(<EvidenceStateExplorer />);

    expect(screen.getByText("Evidence and State Explorer")).toBeTruthy();
    expect(screen.getByText("EV-001")).toBeTruthy();
    expect(screen.getAllByText("Patch assurance").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Fresh").length).toBeGreaterThan(0);
    expect(screen.getByText("ASSET-001 patch_level")).toBeTruthy();
    expect(screen.getAllByText("Payments API").length).toBeGreaterThan(0);
    expect(screen.getByText("Patch control")).toBeTruthy();
    expect(screen.getByText("Patch drift")).toBeTruthy();
  });

  it("renders observation state and linked artifact provenance", () => {
    render(<EvidenceStateExplorer />);

    expect(screen.getByText("patch_level")).toBeTruthy();
    expect(screen.getByText("2026-05 cumulative update")).toBeTruthy();
    expect(screen.getByText("collector://patch")).toBeTruthy();
  });

  it("renders limitations", () => {
    render(<EvidenceStateExplorer />);

    expect(screen.getByText("superseded artifacts excluded")).toBeTruthy();
  });
});

describe("EvidenceStateExplorer empty state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: emptyWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useEvidenceStateWorkspace>);
  });

  it("shows empty states for evidence and observations", () => {
    render(<EvidenceStateExplorer />);

    expect(screen.getByText(/no evidence artifacts match/i)).toBeTruthy();
    expect(screen.getByText(/no observations match/i)).toBeTruthy();
  });
});
