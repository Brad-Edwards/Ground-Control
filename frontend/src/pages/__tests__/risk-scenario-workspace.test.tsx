// @vitest-environment jsdom
/**
 * GC-Q009 — Risk Scenario Workspace page tests.
 *
 * Tests cover:
 * - Loading state
 * - Empty state (no scenarios, no assets)
 * - Composed data (scenario cards, asset table, review badge, FAIR sentence)
 * - Comparison view renders when ≥2 compare IDs in filters
 * - Error state
 *
 * Environment: vitest with jsdom.
 * Uses @testing-library/react for DOM assertions.
 */

import type { RiskScenarioWorkspaceResponse } from "@/types/api";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// ── Mock dependencies ────────────────────────────────────────────────────────

vi.mock("@/hooks/use-risk-scenario-workspace", () => ({
  useRiskScenarioWorkspace: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "test-project", name: "Test Project" },
  }),
}));

import { useRiskScenarioWorkspace } from "@/hooks/use-risk-scenario-workspace";
import { RiskScenarioWorkspace } from "../risk-scenario-workspace";

const mockUseWorkspace = vi.mocked(useRiskScenarioWorkspace);

// Shared describe-title prefix, extracted to avoid duplicating the literal.
const SUITE = "RiskScenarioWorkspace —";

// ── Test fixtures ─────────────────────────────────────────────────────────────

const emptyWorkspace: RiskScenarioWorkspaceResponse = {
  scenarios: [],
  assets: [],
  scenarioCount: 0,
  assetCount: 0,
};

const composedWorkspace: RiskScenarioWorkspaceResponse = {
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
  scenarios: [
    {
      id: "rs-1",
      uid: "RS-001",
      title: "Credential stuffing on portal",
      status: "ACTIVE",
      threat: "External actor",
      method: "Credential stuffing",
      asset: "Auth portal",
      effect: "Data breach",
      timeHorizon: "12 months",
      fairSentence:
        "External actor impacts Auth portal via Credential stuffing, causing Data breach",
      linkedAssetIds: ["asset-1"],
      linkedControls: [
        {
          targetEntityId: "ctl-1",
          targetIdentifier: "CTL-001",
          targetTitle: "MFA Control",
          targetUrl: "https://example.com/ctl-1",
        },
      ],
      linkedFindings: [
        {
          targetEntityId: "find-1",
          targetIdentifier: "FIND-001",
          targetTitle: "Finding One",
          targetUrl: null,
        },
      ],
      linkedEvidence: [],
      linkedRequirements: [
        {
          targetEntityId: "req-1",
          targetIdentifier: "GC-Q009",
          targetTitle: "Risk Scenario Workspace",
          targetUrl: "https://example.com/req-1",
        },
      ],
      assessments: [
        {
          id: "assess-1",
          methodologyProfileName: "FAIR-CRST",
          approvalState: "APPROVED",
          assessmentAt: "2026-05-01T12:00:00Z",
          confidence: "HIGH",
          reassessmentRequiredAt: null,
          hasComputedOutputs: true,
        },
      ],
      treatments: [
        {
          id: "tp-1",
          uid: "TP-001",
          title: "Deploy MFA",
          strategy: "MITIGATE",
          status: "IN_PROGRESS",
          owner: "Alice",
          dueDate: null,
        },
      ],
      registerRecords: [
        {
          id: "rrr-1",
          uid: "RRR-001",
          title: "Credential Risk Register",
          status: "TREATING",
        },
      ],
      reviewIndicator: "CURRENT",
    },
    {
      id: "rs-2",
      uid: "RS-002",
      title: "SQL injection on API",
      status: "DRAFT",
      threat: "Internal actor",
      method: "SQL injection",
      asset: "API endpoint",
      effect: "Data exfiltration",
      timeHorizon: null,
      fairSentence:
        "Internal actor impacts API endpoint via SQL injection, causing Data exfiltration",
      linkedAssetIds: [],
      linkedControls: [],
      linkedFindings: [],
      linkedEvidence: [],
      linkedRequirements: [],
      assessments: [],
      treatments: [],
      registerRecords: [],
      reviewIndicator: "NO_SIGNAL",
    },
  ],
  scenarioCount: 2,
  assetCount: 2,
};

// ── Helper ────────────────────────────────────────────────────────────────────

function renderPage() {
  return render(<RiskScenarioWorkspace />);
}

/**
 * Select both seeded scenarios via their per-card checkboxes and click the
 * (now-enabled) Compare button — the real user path into comparison mode,
 * shared by the comparison-view test cases.
 */
function selectBothAndCompare() {
  fireEvent.click(screen.getByLabelText("Select RS-001 for comparison"));
  fireEvent.click(screen.getByLabelText("Select RS-002 for comparison"));
  fireEvent.click(
    screen.getByRole("button", { name: /compare selected \(2\)/i }),
  );
}

// ── Tests ─────────────────────────────────────────────────────────────────────

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe(`${SUITE} loading state`, () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useRiskScenarioWorkspace>);
  });

  it("shows loading indicator", () => {
    renderPage();
    expect(screen.getByText(/loading workspace/i)).toBeTruthy();
  });
});

describe(`${SUITE} empty state`, () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: emptyWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useRiskScenarioWorkspace>);
  });

  it("renders Assets and Risk Scenarios section headings", () => {
    renderPage();
    expect(screen.getAllByText(/assets/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/risk scenarios/i).length).toBeGreaterThan(0);
  });

  it("shows empty-state for assets", () => {
    renderPage();
    expect(screen.getByText(/no assets in scope/i)).toBeTruthy();
  });

  it("shows empty-state for scenarios", () => {
    renderPage();
    expect(screen.getByText(/no risk scenarios match/i)).toBeTruthy();
  });
});

describe(`${SUITE} composed data`, () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: composedWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useRiskScenarioWorkspace>);
  });

  it("renders asset UIDs in the assets table", () => {
    renderPage();
    expect(screen.getAllByText("A-001").length).toBeGreaterThan(0);
    expect(screen.getAllByText("B-001").length).toBeGreaterThan(0);
  });

  it("shows Boundary badge for BOUNDARY-type asset", () => {
    renderPage();
    expect(screen.getAllByText("Boundary").length).toBeGreaterThan(0);
  });

  it("does not show Boundary badge for non-boundary asset", () => {
    renderPage();
    expect(screen.queryAllByText("Boundary").length).toBe(1);
  });

  it("renders scenario UIDs", () => {
    renderPage();
    expect(screen.getByText("RS-001")).toBeTruthy();
    expect(screen.getByText("RS-002")).toBeTruthy();
  });

  it("renders FAIR sentence for scenario", () => {
    renderPage();
    expect(
      screen.getByText(
        "External actor impacts Auth portal via Credential stuffing, causing Data breach",
      ),
    ).toBeTruthy();
  });

  it("renders review badge for CURRENT state", () => {
    renderPage();
    expect(screen.getByText("Current")).toBeTruthy();
  });

  it("renders review badge for NO_SIGNAL state", () => {
    renderPage();
    expect(screen.getByText("No signal")).toBeTruthy();
  });

  it("renders linked controls inside scenario card", () => {
    renderPage();
    expect(screen.getByText("MFA Control")).toBeTruthy();
  });

  it("renders linked findings inside scenario card", () => {
    renderPage();
    expect(screen.getByText("Finding One")).toBeTruthy();
  });

  it("renders linked requirements inside scenario card", () => {
    renderPage();
    // The page <h1> also contains the text "Risk Scenario Workspace", so a bare
    // text query would pass even if the requirement link were never rendered.
    // Match the anchor by role so a regression that drops the linked-requirements
    // list actually fails this test (test-quality finding, cycle 2).
    expect(
      screen.getByRole("link", { name: "Risk Scenario Workspace" }),
    ).toBeTruthy();
  });

  it("renders assessment with methodology profile name", () => {
    renderPage();
    expect(screen.getByText("FAIR-CRST")).toBeTruthy();
  });

  it("renders treatment UID and title", () => {
    renderPage();
    expect(screen.getByText("TP-001")).toBeTruthy();
    expect(screen.getByText("Deploy MFA")).toBeTruthy();
  });

  it("renders register record UID", () => {
    renderPage();
    expect(screen.getByText("RRR-001")).toBeTruthy();
  });

  it("renders counts in section headings", () => {
    renderPage();
    const heading = screen.getByRole("heading", { name: /assets/i });
    expect(heading.textContent).toContain("2");
  });
});

describe(`${SUITE} comparison view`, () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: composedWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useRiskScenarioWorkspace>);
  });

  it("does not render the comparison view before two scenarios are selected", () => {
    renderPage();
    expect(screen.queryByTestId("comparison-view")).toBeNull();
    // The Compare button is disabled until ≥2 are selected.
    const compareButton = screen.getByRole("button", {
      name: /compare selected/i,
    });
    expect((compareButton as HTMLButtonElement).disabled).toBe(true);
  });

  it("renders ComparisonView after selecting ≥2 scenarios and clicking Compare", () => {
    renderPage();

    // Select both scenarios via their per-card checkboxes (the real user path).
    fireEvent.click(screen.getByLabelText("Select RS-001 for comparison"));
    fireEvent.click(screen.getByLabelText("Select RS-002 for comparison"));

    const compareButton = screen.getByRole("button", {
      name: /compare selected \(2\)/i,
    });
    expect((compareButton as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(compareButton);

    // Compare mode is now active: ComparisonView (not the standard grid) renders,
    // and an Exit control replaces the Compare button. A regression that broke or
    // removed ComparisonView would fail this assertion.
    const view = screen.getByTestId("comparison-view");
    expect(view).toBeTruthy();
    expect(view.style.gridTemplateColumns).toBe("repeat(2, 1fr)");
    expect(screen.getByText("RS-001")).toBeTruthy();
    expect(screen.getByText("RS-002")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: /exit comparison/i }),
    ).toBeTruthy();
  });

  it("exits comparison mode when Exit comparison is clicked", () => {
    renderPage();

    selectBothAndCompare();
    expect(screen.getByTestId("comparison-view")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /exit comparison/i }));
    expect(screen.queryByTestId("comparison-view")).toBeNull();
  });
});

describe(`${SUITE} error state`, () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    } as ReturnType<typeof useRiskScenarioWorkspace>);
  });

  it("shows error message", () => {
    renderPage();
    expect(screen.getByText(/network error/i)).toBeTruthy();
  });
});
