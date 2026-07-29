// @vitest-environment jsdom
import { ApiError } from "@/lib/api-client";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ErrorPanel } from "../error-panel";

describe("ErrorPanel", () => {
  it("renders a 403 as an authenticated-but-unauthorized notice, not route hiding", () => {
    render(<ErrorPanel error={new ApiError(403, "Forbidden")} />);
    expect(screen.getByRole("alert")).toBeDefined();
    expect(screen.getByText("You don't have access")).toBeDefined();
    expect(screen.getByText(/signed in but is not authorized/i)).toBeDefined();
  });

  it("shows the canonical ApiError detail for other errors", () => {
    render(<ErrorPanel error={new ApiError(500, "Upstream exploded")} />);
    expect(screen.getByText("Upstream exploded")).toBeDefined();
    expect(screen.getByText("Something went wrong")).toBeDefined();
  });

  it("falls back to a generic message for a non-Error value", () => {
    render(<ErrorPanel error={"weird"} />);
    expect(screen.getByText("An unexpected error occurred.")).toBeDefined();
  });
});
