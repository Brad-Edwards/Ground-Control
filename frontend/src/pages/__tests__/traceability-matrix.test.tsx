// @vitest-environment jsdom
/**
 * GC-Q003 — Traceability Matrix page tests.
 *
 * Tests cover:
 * - Loading state
 * - Empty state (no requirements)
 * - Composed data (rows, link-type columns, artifact cells, coverage bars)
 * - Gap highlighting for ACTIVE requirements missing a coverage axis
 * - Error state
 *
 * Environment: vitest with jsdom.
 */

import type { TraceabilityMatrixResponse } from "@/types/api";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-traceability-matrix", () => ({
  useTraceabilityMatrix: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "test-project", name: "Test Project" },
  }),
}));

import { useTraceabilityMatrix } from "@/hooks/use-traceability-matrix";
import { TraceabilityMatrix } from "../traceability-matrix";

const mockUseMatrix = vi.mocked(useTraceabilityMatrix);

const emptyMatrix: TraceabilityMatrixResponse = {
  rows: [],
  columns: [
    {
      linkType: "IMPLEMENTS",
      coveredRequirements: 0,
      totalRequirements: 0,
      artifactCount: 0,
    },
  ],
  requirementCount: 0,
  linkedRequirementCount: 0,
  gapCount: 0,
};

const composedMatrix: TraceabilityMatrixResponse = {
  columns: [
    {
      linkType: "IMPLEMENTS",
      coveredRequirements: 1,
      totalRequirements: 2,
      artifactCount: 1,
    },
    {
      linkType: "TESTS",
      coveredRequirements: 1,
      totalRequirements: 2,
      artifactCount: 1,
    },
  ],
  rows: [
    {
      requirementId: "req-1",
      uid: "GC-001",
      title: "Implemented and tested requirement",
      status: "ACTIVE",
      wave: 1,
      priority: "MUST",
      cells: [
        {
          linkId: "link-1",
          linkType: "IMPLEMENTS",
          artifactType: "CODE_FILE",
          artifactIdentifier: "backend/Foo.java",
          artifactTitle: "Foo",
          artifactUrl: "https://example.com/Foo.java",
          syncStatus: "SYNCED",
        },
        {
          linkId: "link-2",
          linkType: "TESTS",
          artifactType: "TEST",
          artifactIdentifier: "backend/FooTest.java",
          artifactTitle: "FooTest",
          artifactUrl: "",
          syncStatus: "SYNCED",
        },
      ],
      coveredLinkTypes: ["IMPLEMENTS", "TESTS"],
      hasGap: false,
    },
    {
      requirementId: "req-2",
      uid: "GC-002",
      title: "Active requirement missing tests",
      status: "ACTIVE",
      wave: 2,
      priority: "SHOULD",
      cells: [],
      coveredLinkTypes: [],
      hasGap: true,
    },
  ],
  requirementCount: 2,
  linkedRequirementCount: 1,
  gapCount: 1,
};

function renderPage() {
  return render(<TraceabilityMatrix />);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("TraceabilityMatrix — loading state", () => {
  beforeEach(() => {
    mockUseMatrix.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useTraceabilityMatrix>);
  });

  it("shows loading indicator", () => {
    renderPage();
    expect(screen.getByText(/loading matrix/i)).toBeTruthy();
  });
});

describe("TraceabilityMatrix — empty state", () => {
  beforeEach(() => {
    mockUseMatrix.mockReturnValue({
      data: emptyMatrix,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useTraceabilityMatrix>);
  });

  it("shows empty-state message for requirements", () => {
    renderPage();
    expect(screen.getByText(/no requirements match/i)).toBeTruthy();
  });
});

describe("TraceabilityMatrix — composed data", () => {
  beforeEach(() => {
    mockUseMatrix.mockReturnValue({
      data: composedMatrix,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useTraceabilityMatrix>);
  });

  it("renders requirement UIDs as rows", () => {
    renderPage();
    expect(screen.getByText("GC-001")).toBeTruthy();
    expect(screen.getByText("GC-002")).toBeTruthy();
  });

  it("renders link-type column headers", () => {
    renderPage();
    expect(
      screen.getByRole("columnheader", { name: "IMPLEMENTS" }),
    ).toBeTruthy();
    expect(screen.getByRole("columnheader", { name: "TESTS" })).toBeTruthy();
  });

  it("renders an artifact cell as a link when a URL is present", () => {
    renderPage();
    expect(screen.getByRole("link", { name: "Foo" })).toBeTruthy();
  });

  it("renders an artifact cell as plain text when no URL is present", () => {
    renderPage();
    const fooTest = screen.getByText("FooTest");
    expect(fooTest).toBeTruthy();
    expect(fooTest.tagName).toBe("SPAN");
  });

  it("flags a gap on the active requirement missing tests", () => {
    renderPage();
    // GapBadge labelled for accessibility; a regression dropping gap detection fails this.
    expect(screen.getAllByLabelText("Coverage gap").length).toBe(1);
  });

  it("renders a coverage bar per link type with percentage", () => {
    renderPage();
    expect(screen.getByLabelText("IMPLEMENTS coverage 50%")).toBeTruthy();
    expect(screen.getByLabelText("TESTS coverage 50%")).toBeTruthy();
  });

  it("summarizes linked and gap counts in the coverage heading", () => {
    renderPage();
    const heading = screen.getByRole("heading", { name: /coverage/i });
    expect(heading.textContent).toContain("1/2 linked");
    expect(heading.textContent).toContain("1 gap");
  });
});

describe("TraceabilityMatrix — error state", () => {
  beforeEach(() => {
    mockUseMatrix.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    } as ReturnType<typeof useTraceabilityMatrix>);
  });

  it("shows error message", () => {
    renderPage();
    expect(screen.getByText(/network error/i)).toBeTruthy();
  });
});
