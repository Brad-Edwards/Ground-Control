// @vitest-environment jsdom

import type {
  PagedResponse,
  RequirementResponse,
  RequirementWithLinksResponse,
  TraceabilityLinkResponse,
} from "@/types/api";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-traceability-matrix", () => ({
  useTraceabilityMatrix: vi.fn(),
}));

vi.mock("@/contexts/project-context", () => ({
  useProjectContext: () => ({
    activeProject: { identifier: "ground-control", name: "Ground Control" },
    isLoading: false,
  }),
}));

import { useTraceabilityMatrix } from "@/hooks/use-traceability-matrix";
import { TraceabilityMatrix } from "../traceability-matrix";

const mockUseMatrix = vi.mocked(useTraceabilityMatrix);

function requirement(
  overrides: Partial<RequirementResponse> & { id: string; uid: string },
): RequirementResponse {
  return {
    graphNodeId: `graph-${overrides.id}`,
    projectIdentifier: "ground-control",
    title: "Untitled",
    statement: "A statement",
    rationale: "A rationale",
    requirementType: "FUNCTIONAL",
    priority: "MUST",
    status: "ACTIVE",
    wave: 1,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    archivedAt: null,
    ...overrides,
  };
}

function link(
  overrides: Partial<TraceabilityLinkResponse> & {
    id: string;
    requirementId: string;
    linkType: TraceabilityLinkResponse["linkType"];
    artifactType: TraceabilityLinkResponse["artifactType"];
    artifactIdentifier: string;
  },
): TraceabilityLinkResponse {
  return {
    artifactUrl: "https://example.com/artifact",
    artifactTitle: "",
    syncStatus: "SYNCED",
    lastSyncedAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

const coveredReq = requirement({
  id: "req-1",
  uid: "REQ-001",
  title: "Login flow",
});
const gapReq = requirement({
  id: "req-2",
  uid: "REQ-002",
  title: "Password reset",
  status: "DRAFT",
  wave: 2,
});

const composed: PagedResponse<RequirementWithLinksResponse> = {
  content: [
    {
      requirement: coveredReq,
      links: [
        link({
          id: "link-1",
          requirementId: "req-1",
          linkType: "IMPLEMENTS",
          artifactType: "PULL_REQUEST",
          artifactIdentifier: "42",
          artifactTitle: "Implement login",
        }),
        link({
          id: "link-2",
          requirementId: "req-1",
          linkType: "TESTS",
          artifactType: "TEST",
          artifactIdentifier: "LoginTest",
        }),
      ],
    },
    {
      requirement: gapReq,
      links: [],
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 25,
};

function mockReturn(
  value: Partial<ReturnType<typeof useTraceabilityMatrix>>,
): ReturnType<typeof useTraceabilityMatrix> {
  return {
    data: undefined,
    isLoading: false,
    isError: false,
    error: null,
    ...value,
  } as ReturnType<typeof useTraceabilityMatrix>;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("TraceabilityMatrix", () => {
  it("shows the loading skeleton while fetching", () => {
    mockUseMatrix.mockReturnValue(mockReturn({ isLoading: true }));

    render(<TraceabilityMatrix />);

    // Header still renders; rows are replaced with animated skeletons.
    expect(screen.getByText("Traceability Matrix")).toBeTruthy();
    expect(screen.queryByText("REQ-001")).toBeNull();
  });

  it("shows the empty state when no requirements match", () => {
    mockUseMatrix.mockReturnValue(
      mockReturn({
        data: {
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 25,
        },
      }),
    );

    render(<TraceabilityMatrix />);

    expect(
      screen.getByText(/no requirements match these filters/i),
    ).toBeTruthy();
  });

  it("shows Partial for a requirement with an IMPLEMENTS link but no TESTS link", () => {
    const partialReq = requirement({
      id: "req-3",
      uid: "REQ-003",
      title: "Partial coverage",
    });
    mockUseMatrix.mockReturnValue(
      mockReturn({
        data: {
          content: [
            {
              requirement: partialReq,
              links: [
                link({
                  id: "link-3",
                  requirementId: "req-3",
                  linkType: "IMPLEMENTS",
                  artifactType: "PULL_REQUEST",
                  artifactIdentifier: "7",
                }),
              ],
            },
          ],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 25,
        },
      }),
    );

    render(<TraceabilityMatrix />);

    // Only IMPLEMENTS, no TESTS -> Partial, not Covered (and not a Gap).
    expect(screen.getByText("Partial")).toBeTruthy();
    expect(screen.queryByText("Covered")).toBeNull();
    expect(screen.queryByText("Gap")).toBeNull();
  });

  describe("with composed data", () => {
    beforeEach(() => {
      mockUseMatrix.mockReturnValue(mockReturn({ data: composed }));
    });

    it("renders requirement rows with their UID, title, and link chips", () => {
      render(<TraceabilityMatrix />);

      expect(screen.getByText("REQ-001")).toBeTruthy();
      expect(screen.getByText("Login flow")).toBeTruthy();
      expect(screen.getByText("REQ-002")).toBeTruthy();
      expect(screen.getByText("Password reset")).toBeTruthy();
      // Chips render artifact type + identifier (title when present).
      expect(screen.getByText(/PULL REQUEST: Implement login/)).toBeTruthy();
      expect(screen.getByText(/TEST: LoginTest/)).toBeTruthy();
    });

    it("shows Covered for IMPLEMENTS+TESTS and Gap for a requirement with no links", () => {
      render(<TraceabilityMatrix />);

      expect(screen.getByText("Covered")).toBeTruthy();
      expect(screen.getByText("Gap")).toBeTruthy();
    });

    it("renders one column per link type when unfiltered", () => {
      render(<TraceabilityMatrix />);

      const headers = screen
        .getAllByRole("columnheader")
        .map((h) => h.textContent);
      expect(headers).toContain("IMPLEMENTS");
      expect(headers).toContain("TESTS");
      expect(headers).toContain("DOCUMENTS");
      expect(headers).toContain("CONSTRAINS");
      expect(headers).toContain("VERIFIES");
    });

    it("narrows to a single link-type column when the Link Type filter is set", () => {
      render(<TraceabilityMatrix />);

      const linkTypeSelect = screen.getByDisplayValue("Link Type");
      fireEvent.change(linkTypeSelect, { target: { value: "IMPLEMENTS" } });

      const headers = screen
        .getAllByRole("columnheader")
        .map((h) => h.textContent);
      expect(headers).toContain("IMPLEMENTS");
      expect(headers).not.toContain("TESTS");
      expect(headers).not.toContain("DOCUMENTS");

      // Changing the filter resets to page 0 and re-queries with the link type.
      expect(mockUseMatrix).toHaveBeenLastCalledWith(
        expect.objectContaining({ linkType: "IMPLEMENTS", page: 0 }),
      );
    });
  });

  it("renders the error state when the query fails", () => {
    mockUseMatrix.mockReturnValue(
      mockReturn({ isError: true, error: new Error("matrix failed") }),
    );

    render(<TraceabilityMatrix />);

    expect(screen.getByText("matrix failed")).toBeTruthy();
  });
});
