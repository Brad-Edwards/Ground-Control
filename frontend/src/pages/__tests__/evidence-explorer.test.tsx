// @vitest-environment jsdom
/**
 * GC-Q012 — Evidence and State Explorer page tests.
 *
 * Tests cover loading, empty, composed data (freshness counts, artifact cards
 * with provenance + downstream findings, observation cards), and error.
 */

import type { EvidenceExplorerResponse } from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-evidence-explorer", () => ({
  useEvidenceExplorer: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "test-project", name: "Test Project" },
  }),
}));

import { useEvidenceExplorer } from "@/hooks/use-evidence-explorer";
import { EvidenceExplorer } from "../evidence-explorer";

const mockUseExplorer = vi.mocked(useEvidenceExplorer);

const emptyExplorer: EvidenceExplorerResponse = {
  evidenceArtifacts: [],
  observations: [],
  counts: { fresh: 0, stale: 0, expired: 0, superseded: 0, currentlyValid: 0 },
  limitations: [],
  artifactCount: 0,
  observationCount: 0,
};

const composedExplorer: EvidenceExplorerResponse = {
  evidenceArtifacts: [
    {
      id: "ev-1",
      uid: "EV-001",
      title: "Rollup evidence",
      evidenceType: "OBSERVATION_SUMMARY",
      derivationMethod: "ROLLUP",
      derivedAt: "2026-05-01T12:00:00Z",
      derivedBy: "collector",
      assuranceLevel: "L1",
      confidence: "HIGH",
      supersededByArtifactId: null,
      freshnessState: "FRESH",
      ageDays: 3,
      sources: [
        {
          sourceKind: "OBSERVATION",
          sourceEntityId: "obs-1",
          sourceIdentifier: null,
          role: "primary",
        },
      ],
      downstreamFindings: [
        {
          id: "find-1",
          uid: "FIND-001",
          title: "Downstream finding",
          severity: "HIGH",
          status: "OPEN",
        },
      ],
    },
  ],
  observations: [
    {
      id: "obs-1",
      assetId: "asset-1",
      assetUid: "A-001",
      category: "CONFIGURATION",
      observationKey: "os_version",
      observationValue: "1.2.3",
      source: "scanner",
      confidence: "HIGH",
      evidenceRef: "https://example.com/proof",
      observedAt: "2026-01-01T12:00:00Z",
      expiresAt: null,
      freshnessState: "STALE",
      ageDays: 120,
      downstreamFindings: [],
    },
  ],
  counts: { fresh: 1, stale: 1, expired: 0, superseded: 0, currentlyValid: 2 },
  limitations: [],
  artifactCount: 1,
  observationCount: 1,
};

function renderPage() {
  return render(<EvidenceExplorer />);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("EvidenceExplorer — loading state", () => {
  beforeEach(() => {
    mockUseExplorer.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useEvidenceExplorer>);
  });

  it("shows loading indicator", () => {
    renderPage();
    expect(screen.getByText(/loading explorer/i)).toBeTruthy();
  });
});

describe("EvidenceExplorer — empty state", () => {
  beforeEach(() => {
    mockUseExplorer.mockReturnValue({
      data: emptyExplorer,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useEvidenceExplorer>);
  });

  it("shows empty-state for artifacts", () => {
    renderPage();
    expect(screen.getByText(/no evidence artifacts match/i)).toBeTruthy();
  });
});

describe("EvidenceExplorer — composed data", () => {
  beforeEach(() => {
    mockUseExplorer.mockReturnValue({
      data: composedExplorer,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useEvidenceExplorer>);
  });

  it("renders the evidence artifact with its type", () => {
    renderPage();
    expect(screen.getByText("EV-001")).toBeTruthy();
    // The type appears both as a filter <option> and as the card badge, so it is
    // not unique — assert the card badge raises the count above the lone option.
    expect(screen.getAllByText("OBSERVATION_SUMMARY").length).toBeGreaterThan(
      1,
    );
  });

  it("renders artifact provenance source kind", () => {
    renderPage();
    expect(screen.getByText("OBSERVATION")).toBeTruthy();
  });

  it("renders the freshness badge for a fresh artifact", () => {
    renderPage();
    expect(screen.getByLabelText("Evidence freshness: FRESH")).toBeTruthy();
  });

  it("renders downstream finding impact", () => {
    renderPage();
    expect(screen.getAllByText(/downstream findings/i).length).toBeGreaterThan(
      0,
    );
    expect(screen.getByText("FIND-001")).toBeTruthy();
  });

  it("renders an observation with its value and stale badge", () => {
    renderPage();
    expect(screen.getByText("os_version")).toBeTruthy();
    expect(screen.getByText(/= 1.2.3/)).toBeTruthy();
    expect(screen.getByLabelText("Evidence freshness: STALE")).toBeTruthy();
  });

  it("renders the freshness counts", () => {
    renderPage();
    expect(screen.getByText("Currently valid")).toBeTruthy();
  });
});

describe("EvidenceExplorer — error state", () => {
  beforeEach(() => {
    mockUseExplorer.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    } as ReturnType<typeof useEvidenceExplorer>);
  });

  it("shows error message", () => {
    renderPage();
    expect(screen.getByText(/network error/i)).toBeTruthy();
  });
});
