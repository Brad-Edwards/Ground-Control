// @vitest-environment jsdom

import type { TimelineEntryResponse } from "@/types/api";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/hooks/use-history", () => ({
  useRequirementTimeline: vi.fn(),
}));

import { useRequirementTimeline } from "@/hooks/use-history";
import { HistoryTab } from "../history-tab";

const mockUseTimeline = vi.mocked(useRequirementTimeline);

function makeEntry(
  overrides: Partial<TimelineEntryResponse> & {
    changes?: TimelineEntryResponse["changes"];
    truncated?: boolean;
  } = {},
): TimelineEntryResponse {
  return {
    revisionNumber: 1,
    revisionType: "ADD",
    timestamp: "2026-01-01T00:00:00Z",
    actor: "test-user",
    changeCategory: "REQUIREMENT",
    entityId: "entity-1",
    snapshot: { title: "My Req", uid: "REQ-001" },
    changes: {},
    truncated: false,
    ...overrides,
  };
}

function mockReturn(
  entries: TimelineEntryResponse[],
  extra: Partial<ReturnType<typeof useRequirementTimeline>> = {},
): ReturnType<typeof useRequirementTimeline> {
  return {
    data: entries,
    isLoading: false,
    isError: false,
    error: null,
    ...extra,
  } as ReturnType<typeof useRequirementTimeline>;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("HistoryTab", () => {
  describe("truncated field rendering", () => {
    it("renders preview value and Show full button when change is truncated", () => {
      const preview = "A".repeat(200);
      const entry = makeEntry({
        revisionType: "MOD",
        changes: {
          statement: {
            oldValue: "short",
            newValue: preview,
            truncated: true,
          },
        },
        truncated: true,
      });

      mockUseTimeline.mockReturnValue(mockReturn([entry]));
      render(<HistoryTab requirementId="req-1" />);

      // Expand the diff section first
      const expandButton = screen.getByText(/field.*changed/i);
      fireEvent.click(expandButton);

      // The preview text should be visible
      expect(screen.getByText(preview)).toBeTruthy();

      // "Show full" button should appear
      expect(screen.getByText("Show full")).toBeTruthy();
    });

    it("does not render Show full button when change is not truncated", () => {
      const entry = makeEntry({
        revisionType: "MOD",
        changes: {
          title: {
            oldValue: "Old",
            newValue: "New",
            truncated: false,
          },
        },
        truncated: false,
      });

      mockUseTimeline.mockReturnValue(mockReturn([entry]));
      render(<HistoryTab requirementId="req-1" />);

      // Expand the diff section
      const expandButton = screen.getByText(/field.*changed/i);
      fireEvent.click(expandButton);

      expect(screen.queryByText("Show full")).toBeNull();
    });

    it("clicking Show full issues expand=true request and renders full value", () => {
      // First call returns truncated data
      const preview = "B".repeat(200);
      const truncatedEntry = makeEntry({
        revisionType: "MOD",
        changes: {
          statement: {
            oldValue: "short",
            newValue: preview,
            truncated: true,
          },
        },
        truncated: true,
      });

      const fullValue = "B".repeat(300);
      const fullEntry = makeEntry({
        revisionType: "MOD",
        changes: {
          statement: {
            oldValue: "short",
            newValue: fullValue,
            truncated: false,
          },
        },
        truncated: false,
      });

      mockUseTimeline
        .mockReturnValueOnce(mockReturn([truncatedEntry]))
        .mockReturnValueOnce(mockReturn([fullEntry]));

      render(<HistoryTab requirementId="req-1" />);

      // Expand diff
      const expandButton = screen.getByText(/field.*changed/i);
      fireEvent.click(expandButton);

      // Click "Show full"
      const showFullBtn = screen.getByText("Show full");
      fireEvent.click(showFullBtn);

      // Verify useRequirementTimeline was called with expand=true
      const calls = mockUseTimeline.mock.calls;
      const expandCall = calls.find((call) => call[2] === true);
      expect(expandCall).toBeTruthy();

      // The full (untruncated) value must actually render, and "Show full"
      // disappears now that the change is no longer truncated.
      expect(screen.getByText(fullValue)).toBeTruthy();
      expect(screen.queryByText("Show full")).toBeNull();
    });
  });

  describe("loading state", () => {
    it("shows loading skeleton while fetching", () => {
      mockUseTimeline.mockReturnValue(mockReturn([], { isLoading: true }));

      render(<HistoryTab requirementId="req-1" />);

      expect(screen.queryByText(/field.*changed/i)).toBeNull();
    });
  });

  describe("empty state", () => {
    it("shows no history entries message when empty", () => {
      mockUseTimeline.mockReturnValue(mockReturn([]));

      render(<HistoryTab requirementId="req-1" />);

      expect(screen.getByText(/no history entries/i)).toBeTruthy();
    });
  });
});
