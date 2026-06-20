// @vitest-environment jsdom

import type { ControlAssuranceWorkspaceResponse } from "@/types/api";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-control-assurance-workspace", () => ({
  useControlAssuranceWorkspace: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
  }),
}));

import { useControlAssuranceWorkspace } from "@/hooks/use-control-assurance-workspace";
import { ControlAssuranceWorkspace } from "../control-assurance-workspace";

const mockUseWorkspace = vi.mocked(useControlAssuranceWorkspace);

const emptyWorkspace: ControlAssuranceWorkspaceResponse = {
  controls: [],
  controlCount: 0,
};

const composedWorkspace: ControlAssuranceWorkspaceResponse = {
  controlCount: 1,
  controls: [
    {
      id: "control-1",
      uid: "CTL-001",
      title: "Payment approval",
      descriptionPreview: "Approves high-value payments",
      objectivePreview: "Prevent unapproved payments",
      controlFunction: "PREVENTIVE",
      status: "OPERATIONAL",
      owner: "Alice",
      implementationScopePreview: "Payments production",
      category: "finance",
      source: "internal",
      scopedImplementations: [
        {
          id: "sci-1",
          uid: "SCI-001",
          name: "Payments deployment",
          implementationScope: "Payments production only",
          operationalAssetId: "asset-1",
          operationalAssetUid: "ASSET-001",
          operationalAssetName: "Payments API",
        },
      ],
      tests: [
        {
          id: "test-1",
          uid: "CTEST-001",
          methodology: "INSPECTION",
          conclusion: "EFFECTIVE",
          testerIdentity: "auditor",
          testDate: "2026-05-31",
          notesPreview: "All approvals present",
        },
      ],
      assessments: [
        {
          id: "assessment-1",
          uid: "CEA-001",
          designEffectiveness: "EFFECTIVE",
          operatingEffectiveness: "EFFECTIVE",
          assessedAt: "2026-05-31",
          assessor: "assessor",
          supportingTestIds: ["test-1"],
        },
      ],
      evidence: [
        {
          id: "evidence-1",
          uid: "EV-001",
          title: "Approval evidence",
          summaryPreview: "Control evidence summary",
          evidenceType: "CONTROL_TEST_SUMMARY",
          derivedAt: "2026-06-01T12:00:00Z",
        },
      ],
      findings: [
        {
          id: "finding-1",
          uid: "FIND-001",
          title: "Approval exception",
          findingType: "CONTROL_DEFICIENCY",
          severity: "HIGH",
          status: "OPEN",
          owner: "Bob",
          dueDate: "2026-06-30",
        },
      ],
      riskMappings: [
        {
          id: "mapping-1",
          controlRole: "PREVENTIVE",
          targetIdentifier: "RS-001",
          targetTitle: "Approval bypass",
          mappingObjective: "Prevent unapproved payments",
          evidenceRefs: [
            {
              evidenceRef: "EVD-REF-001",
              evidenceNotePreview: "Approval packet",
              evidenceArtifactId: "evidence-1",
            },
          ],
        },
      ],
      queueReasons: ["CURRENT"],
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ControlAssuranceWorkspace", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: composedWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useControlAssuranceWorkspace>);
  });

  it("renders controls, assurance evidence, exceptions, and mappings", () => {
    render(<ControlAssuranceWorkspace />);

    expect(screen.getByText("Control and Assurance Workspace")).toBeTruthy();
    expect(screen.getByText("CTL-001")).toBeTruthy();
    expect(screen.getByText("Payment approval")).toBeTruthy();
    expect(screen.getByLabelText("Control queue: CURRENT")).toBeTruthy();
    expect(screen.getByText("SCI-001")).toBeTruthy();
    expect(screen.getByText("CTEST-001")).toBeTruthy();
    expect(screen.getByText("CEA-001")).toBeTruthy();
    expect(screen.getByText("EV-001")).toBeTruthy();
    expect(screen.getByText("FIND-001")).toBeTruthy();
    expect(screen.getByText(/RS-001/)).toBeTruthy();
    expect(screen.getByText("EVD-REF-001")).toBeTruthy();
  });

  it("passes queue filter changes to the workspace hook", () => {
    render(<ControlAssuranceWorkspace />);

    fireEvent.change(screen.getByLabelText("Queue"), {
      target: { value: "OPEN_EXCEPTION" },
    });

    expect(mockUseWorkspace).toHaveBeenLastCalledWith(
      expect.objectContaining({ queue: "OPEN_EXCEPTION" }),
    );
  });
});

describe("ControlAssuranceWorkspace empty state", () => {
  beforeEach(() => {
    mockUseWorkspace.mockReturnValue({
      data: emptyWorkspace,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useControlAssuranceWorkspace>);
  });

  it("shows the no-controls message", () => {
    render(<ControlAssuranceWorkspace />);

    expect(screen.getByText(/no controls match/i)).toBeTruthy();
  });
});

describe("ControlAssuranceWorkspace loading state", () => {
  it("shows the shared loading indicator", () => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useControlAssuranceWorkspace>);

    render(<ControlAssuranceWorkspace />);

    expect(screen.getByText(/loading workspace/i)).toBeTruthy();
  });
});

describe("ControlAssuranceWorkspace error state", () => {
  it("shows the shared error message", () => {
    mockUseWorkspace.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("workspace failed"),
    } as ReturnType<typeof useControlAssuranceWorkspace>);

    render(<ControlAssuranceWorkspace />);

    expect(screen.getByText("workspace failed")).toBeTruthy();
  });
});
