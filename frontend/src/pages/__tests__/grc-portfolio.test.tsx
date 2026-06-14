// @vitest-environment jsdom

import type { GrcPortfolioData } from "@/hooks/use-grc-portfolio";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-grc-portfolio", () => ({
  useGrcPortfolio: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
  }),
}));

vi.mock("react-router-dom", async () => {
  const actual =
    await vi.importActual<typeof import("react-router-dom")>(
      "react-router-dom",
    );
  return {
    ...actual,
    useParams: () => ({ projectId: "ground-control" }),
  };
});

import { useGrcPortfolio } from "@/hooks/use-grc-portfolio";
import { MemoryRouter } from "react-router-dom";
import { GrcPortfolio } from "../grc-portfolio";

const mockUsePortfolio = vi.mocked(useGrcPortfolio);

function renderPortfolio() {
  return render(
    <MemoryRouter>
      <GrcPortfolio />
    </MemoryRouter>,
  );
}

const emptyPortfolio: GrcPortfolioData = {
  risk: { scenarios: [], assets: [], scenarioCount: 0, assetCount: 0 },
  controls: { controls: [], controlCount: 0 },
  evidence: {
    assets: [],
    evidenceArtifacts: [],
    observations: [],
    counts: {
      fresh: 0,
      stale: 0,
      expired: 0,
      superseded: 0,
      currentlyValid: 0,
    },
    limitations: [],
    assetCount: 0,
    artifactCount: 0,
    observationCount: 0,
  },
  findings: [],
  assets: [],
};

const composedPortfolio: GrcPortfolioData = {
  risk: {
    assets: [
      {
        id: "asset-1",
        uid: "ASSET-001",
        name: "Payments API",
        assetType: "SERVICE",
        boundary: false,
      },
    ],
    scenarios: [
      {
        id: "scenario-1",
        uid: "RS-001",
        title: "Payment credential stuffing",
        status: "ACTIVE",
        threat: "External actor",
        method: "Credential stuffing",
        asset: "Payments API",
        effect: "Account takeover",
        timeHorizon: "P12M",
        fairSentence:
          "External actor impacts Payments API via Credential stuffing, causing Account takeover",
        linkedAssetIds: ["asset-1"],
        linkedControls: [],
        linkedFindings: [],
        linkedEvidence: [],
        linkedRequirements: [],
        assessments: [
          {
            id: "assessment-1",
            methodologyProfileName: "FAIR v3.0",
            approvalState: "APPROVED",
            assessmentAt: "2026-06-01T12:00:00Z",
            confidence: "HIGH",
            reassessmentRequiredAt: null,
            hasComputedOutputs: true,
          },
          {
            id: "assessment-2",
            methodologyProfileName: "NIST SP 800-30 Rev. 1",
            approvalState: "SUBMITTED",
            assessmentAt: "2026-06-02T12:00:00Z",
            confidence: "MEDIUM",
            reassessmentRequiredAt: "2026-06-20T12:00:00Z",
            hasComputedOutputs: true,
          },
        ],
        treatments: [
          {
            id: "treatment-1",
            uid: "TP-001",
            title: "Strengthen adaptive MFA",
            strategy: "MITIGATE",
            status: "IN_PROGRESS",
            owner: "Rita",
            dueDate: "2026-06-30",
          },
        ],
        registerRecords: [],
        reviewIndicator: "REASSESSMENT_REQUIRED",
      },
      {
        id: "scenario-2",
        uid: "RS-002",
        title: "Vendor outage",
        status: "DRAFT",
        threat: "Provider",
        method: "Service disruption",
        asset: "Vendor API",
        effect: "Order delay",
        timeHorizon: null,
        fairSentence:
          "Provider impacts Vendor API via Service disruption, causing Order delay",
        linkedAssetIds: [],
        linkedControls: [],
        linkedFindings: [],
        linkedEvidence: [],
        linkedRequirements: [],
        assessments: [
          {
            id: "assessment-3",
            methodologyProfileName: "ISO 27005",
            approvalState: "DRAFT",
            assessmentAt: null,
            confidence: null,
            reassessmentRequiredAt: null,
            hasComputedOutputs: false,
          },
        ],
        treatments: [],
        registerRecords: [],
        reviewIndicator: "CURRENT",
      },
    ],
    scenarioCount: 2,
    assetCount: 1,
  },
  controls: {
    controlCount: 2,
    controls: [
      {
        id: "control-1",
        uid: "CTL-001",
        title: "MFA enforcement",
        descriptionPreview: "Adaptive authentication for payments.",
        objectivePreview: "Prevent account takeover.",
        controlFunction: "PREVENTIVE",
        status: "OPERATIONAL",
        owner: "Alice",
        implementationScopePreview: "Payments production",
        category: "identity",
        source: "internal",
        scopedImplementations: [],
        tests: [],
        assessments: [
          {
            id: "control-assessment-1",
            uid: "CEA-001",
            designEffectiveness: "EFFECTIVE",
            operatingEffectiveness: "EFFECTIVE",
            assessedAt: "2026-06-01T12:00:00Z",
            assessor: "assessor",
            supportingTestIds: [],
          },
        ],
        evidence: [],
        findings: [],
        riskMappings: [],
        queueReasons: ["CURRENT"],
      },
      {
        id: "control-2",
        uid: "CTL-002",
        title: "Vendor exit plan",
        descriptionPreview: null,
        objectivePreview: null,
        controlFunction: "CORRECTIVE",
        status: "IMPLEMENTED",
        owner: null,
        implementationScopePreview: null,
        category: "resilience",
        source: "internal",
        scopedImplementations: [],
        tests: [],
        assessments: [
          {
            id: "control-assessment-2",
            uid: "CEA-002",
            designEffectiveness: "PARTIALLY_EFFECTIVE",
            operatingEffectiveness: "INEFFECTIVE",
            assessedAt: "2026-05-20T12:00:00Z",
            assessor: "assessor",
            supportingTestIds: [],
          },
        ],
        evidence: [],
        findings: [
          {
            id: "finding-2",
            uid: "FIND-002",
            title: "Exit runbook stale",
            findingType: "CONTROL_DEFICIENCY",
            severity: "HIGH",
            status: "OPEN",
            owner: "Bob",
            dueDate: "2026-06-01",
          },
        ],
        riskMappings: [],
        queueReasons: ["OWNER_MISSING", "OPEN_EXCEPTION"],
      },
    ],
  },
  evidence: {
    assets: [],
    evidenceArtifacts: [
      {
        id: "evidence-1",
        uid: "EV-001",
        title: "MFA evidence",
        summaryPreview: "Recent MFA control evidence",
        evidenceType: "CONTROL_TEST_SUMMARY",
        derivedAt: "2026-06-01T12:00:00Z",
        ageDays: 13,
        freshnessState: "FRESH",
        supersededByArtifactId: null,
        derivedBy: "agent",
        assuranceLevel: "L2",
        confidence: "HIGH",
        sources: [],
        affectedAssets: [],
        linkedControls: [],
        downstreamAssessments: [],
        linkedFindings: [],
      },
      {
        id: "evidence-2",
        uid: "EV-002",
        title: "Vendor exercise",
        summaryPreview: "Old continuity exercise evidence",
        evidenceType: "ASSURANCE_CONCLUSION",
        derivedAt: "2026-04-01T12:00:00Z",
        ageDays: 74,
        freshnessState: "STALE",
        supersededByArtifactId: null,
        derivedBy: "auditor",
        assuranceLevel: "L1",
        confidence: "MEDIUM",
        sources: [],
        affectedAssets: [],
        linkedControls: [],
        downstreamAssessments: [],
        linkedFindings: [],
      },
    ],
    observations: [],
    counts: {
      fresh: 1,
      stale: 1,
      expired: 0,
      superseded: 0,
      currentlyValid: 1,
    },
    limitations: [],
    assetCount: 1,
    artifactCount: 2,
    observationCount: 0,
  },
  findings: [
    {
      id: "finding-1",
      graphNodeId: "FINDING:finding-1",
      projectIdentifier: "ground-control",
      uid: "FIND-001",
      title: "MFA bypass exception",
      findingType: "CONTROL_DEFICIENCY",
      severity: "CRITICAL",
      status: "OPEN",
      description: "Exception permits bypass.",
      rootCauseAnalysis: null,
      owner: "Alice",
      dueDate: "2026-06-20",
      createdAt: "2026-06-05T12:00:00Z",
      updatedAt: "2026-06-10T12:00:00Z",
      createdBy: "auditor",
    },
    {
      id: "finding-2",
      graphNodeId: "FINDING:finding-2",
      projectIdentifier: "ground-control",
      uid: "FIND-002",
      title: "Exit runbook stale",
      findingType: "CONTROL_DEFICIENCY",
      severity: "HIGH",
      status: "REMEDIATION_IN_PROGRESS",
      description: "Runbook needs refresh.",
      rootCauseAnalysis: null,
      owner: null,
      dueDate: "2026-06-01",
      createdAt: "2026-05-01T12:00:00Z",
      updatedAt: "2026-06-01T12:00:00Z",
      createdBy: "auditor",
    },
  ],
  assets: [
    {
      id: "asset-1",
      graphNodeId: "OPERATIONAL_ASSET:asset-1",
      projectIdentifier: "ground-control",
      uid: "ASSET-001",
      name: "Payments API",
      description: "Payments service",
      assetType: "SERVICE",
      owner: "Payments",
      steward: "Security",
      environment: "PRODUCTION",
      criticality: "CRITICAL",
      businessContext: "Revenue path",
      scopeDesignation: "IN_SCOPE",
      subtype: "api",
      metadata: {},
      knowledgeState: "CONFIRMED",
      archivedAt: null,
      createdAt: "2026-05-01T12:00:00Z",
      updatedAt: "2026-06-01T12:00:00Z",
    },
    {
      id: "asset-2",
      graphNodeId: "OPERATIONAL_ASSET:asset-2",
      projectIdentifier: "ground-control",
      uid: "ASSET-002",
      name: "Vendor API",
      description: "Fulfillment provider",
      assetType: "THIRD_PARTY",
      owner: null,
      steward: null,
      environment: "PRODUCTION",
      criticality: "HIGH",
      businessContext: "Order fulfillment",
      scopeDesignation: "IN_SCOPE",
      subtype: "vendor",
      metadata: {},
      knowledgeState: "PROVISIONAL",
      archivedAt: null,
      createdAt: "2026-05-03T12:00:00Z",
      updatedAt: "2026-06-02T12:00:00Z",
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("GrcPortfolio", () => {
  beforeEach(() => {
    mockUsePortfolio.mockReturnValue({
      data: composedPortfolio,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useGrcPortfolio>);
  });

  it("renders portfolio summaries for every GC-Q013 reporting dimension", () => {
    renderPortfolio();

    expect(screen.getByText("GRC Portfolio")).toBeTruthy();
    expect(screen.getByText("Risk posture")).toBeTruthy();
    expect(screen.getByText("Control health")).toBeTruthy();
    expect(screen.getByText("Evidence freshness")).toBeTruthy();
    expect(screen.getByText("Finding trends")).toBeTruthy();
    expect(screen.getByText("Asset criticality")).toBeTruthy();
    expect(screen.getByText("Methodology summaries")).toBeTruthy();
    expect(screen.getAllByText("Reassessment required").length).toBeGreaterThan(
      0,
    );
    expect(screen.getAllByText("Open exception").length).toBeGreaterThan(0);
    expect(screen.getByText("Critical / high production")).toBeTruthy();
  });

  it("renders drill-down rows with graph entity identifiers and workspace links", () => {
    renderPortfolio();

    expect(screen.getByText("RS-001")).toBeTruthy();
    expect(screen.getByText("Payment credential stuffing")).toBeTruthy();
    expect(screen.getByText("FIND-001")).toBeTruthy();
    expect(screen.getByText("FINDING:finding-1")).toBeTruthy();
    expect(screen.getByText("ASSET-001")).toBeTruthy();
    expect(screen.getByText("OPERATIONAL_ASSET:asset-1")).toBeTruthy();
    expect(
      screen.getAllByRole("link", { name: /open graph/i }).length,
    ).toBeGreaterThan(0);
  });

  it("passes scope controls to the portfolio hook", () => {
    renderPortfolio();

    fireEvent.change(screen.getByLabelText("Freshness window"), {
      target: { value: "30" },
    });

    expect(
      (screen.getByLabelText("Freshness window") as HTMLInputElement).value,
    ).toBe("30");
    expect(mockUsePortfolio).toHaveBeenLastCalledWith(
      expect.objectContaining({ freshnessWindowDays: 30 }),
    );
  });
});

describe("GrcPortfolio empty state", () => {
  beforeEach(() => {
    mockUsePortfolio.mockReturnValue({
      data: emptyPortfolio,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useGrcPortfolio>);
  });

  it("shows an empty portfolio message", () => {
    renderPortfolio();

    expect(screen.getByText(/no portfolio data matches/i)).toBeTruthy();
  });
});

describe("GrcPortfolio loading and error states", () => {
  it("shows a loading message", () => {
    mockUsePortfolio.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useGrcPortfolio>);

    renderPortfolio();

    expect(screen.getByText(/loading portfolio/i)).toBeTruthy();
  });

  it("shows an error message", () => {
    mockUsePortfolio.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("portfolio failed"),
    } as ReturnType<typeof useGrcPortfolio>);

    renderPortfolio();

    expect(screen.getByText("portfolio failed")).toBeTruthy();
  });
});
