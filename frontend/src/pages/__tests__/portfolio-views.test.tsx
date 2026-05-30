// @vitest-environment jsdom
/**
 * GC-Q013 — GRC Portfolio Reporting Views page tests.
 *
 * Tests cover loading, composed data (risk posture, control health, evidence
 * freshness, finding trends, asset criticality, methodology table), and error.
 */

import type { PortfolioSummaryResponse } from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-portfolio-summary", () => ({
  usePortfolioSummary: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "test-project", name: "Test Project" },
  }),
}));

import { usePortfolioSummary } from "@/hooks/use-portfolio-summary";
import { PortfolioViews } from "../portfolio-views";

const mockUsePortfolio = vi.mocked(usePortfolioSummary);

const composedPortfolio: PortfolioSummaryResponse = {
  project: "ground-control",
  asOf: "2026-05-18T00:00:00Z",
  derivationMethod: "portfolio-projection-v1",
  riskPosture: {
    totalScenarios: 2,
    scenariosByStatus: { ACTIVE: 2 },
    totalAssessments: 1,
    assessmentsByApprovalState: { APPROVED: 1 },
    totalTreatments: 1,
    treatmentsByStatus: { PLANNED: 1 },
    treatmentsByStrategy: { MITIGATE: 1 },
    totalRegisterRecords: 1,
    registerByStatus: { IDENTIFIED: 1 },
    reassessmentSignals: 1,
    overdueReviews: 1,
    overdueRegisterRecordUids: ["RRR-009"],
  },
  controlHealth: {
    totalControls: 3,
    controlsByStatus: { OPERATIONAL: 3 },
    designEffectivenessDistribution: { EFFECTIVE: 2 },
    operatingEffectivenessDistribution: { PARTIALLY_EFFECTIVE: 1 },
    unassessedControls: 1,
    unmappedControls: 1,
    unassessedControlUids: ["CTL-008"],
    unmappedControlUids: ["CTL-009"],
  },
  evidenceFreshness: {
    fresh: 2,
    stale: 1,
    expired: 0,
    superseded: 0,
    currentlyValid: 3,
  },
  findingTrends: {
    totalFindings: 4,
    bySeverity: { HIGH: 2, LOW: 2 },
    byStatus: { OPEN: 3, VERIFIED_CLOSED: 1 },
    byType: { CONTROL_DEFICIENCY: 4 },
    openCount: 3,
    overdueCount: 1,
    openFindingUids: ["FIND-001", "FIND-002", "FIND-003"],
    overdueFindingUids: ["FIND-004"],
  },
  assetCriticality: {
    totalAssets: 5,
    byCriticality: { CRITICAL: 2, LOW: 3 },
    byEnvironment: { PRODUCTION: 5 },
    byScope: { IN_SCOPE: 5 },
    criticalAssetUids: ["A-001", "A-002"],
  },
  methodologySummaries: [
    {
      family: "FAIR",
      profileCount: 1,
      assessmentCount: 1,
      approvedAssessmentCount: 1,
      assessmentsWithComputedOutputs: 1,
    },
  ],
  limitations: [],
};

function renderPage() {
  return render(<PortfolioViews />);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("PortfolioViews — loading state", () => {
  beforeEach(() => {
    mockUsePortfolio.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof usePortfolioSummary>);
  });

  it("shows loading indicator", () => {
    renderPage();
    expect(screen.getByText(/loading portfolio/i)).toBeTruthy();
  });
});

describe("PortfolioViews — composed data", () => {
  beforeEach(() => {
    mockUsePortfolio.mockReturnValue({
      data: composedPortfolio,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof usePortfolioSummary>);
  });

  it("renders all portfolio section headings", () => {
    renderPage();
    expect(screen.getByRole("heading", { name: /risk posture/i })).toBeTruthy();
    expect(
      screen.getByRole("heading", { name: /control health/i }),
    ).toBeTruthy();
    expect(
      screen.getByRole("heading", { name: /evidence freshness/i }),
    ).toBeTruthy();
    expect(
      screen.getByRole("heading", { name: /finding trends/i }),
    ).toBeTruthy();
    expect(
      screen.getByRole("heading", { name: /asset criticality/i }),
    ).toBeTruthy();
    expect(
      screen.getByRole("heading", { name: /methodology summaries/i }),
    ).toBeTruthy();
  });

  it("renders a control-health distribution bucket", () => {
    renderPage();
    expect(screen.getByText("OPERATIONAL")).toBeTruthy();
  });

  it("renders the methodology family row", () => {
    renderPage();
    expect(screen.getByText("FAIR")).toBeTruthy();
  });

  it("renders the asset criticality buckets", () => {
    renderPage();
    expect(screen.getByText("CRITICAL")).toBeTruthy();
  });

  it("renders actionable drill-down uid lists", () => {
    renderPage();
    expect(screen.getByText("CTL-008")).toBeTruthy(); // unassessed control
    expect(screen.getByText("RRR-009")).toBeTruthy(); // overdue review
    expect(screen.getByText("FIND-004")).toBeTruthy(); // overdue finding
  });
});

describe("PortfolioViews — error state", () => {
  beforeEach(() => {
    mockUsePortfolio.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    } as ReturnType<typeof usePortfolioSummary>);
  });

  it("shows error message", () => {
    renderPage();
    expect(screen.getByText(/network error/i)).toBeTruthy();
  });
});
