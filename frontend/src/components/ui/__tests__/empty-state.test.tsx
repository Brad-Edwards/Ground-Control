// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import { FileText } from "lucide-react";
import { describe, expect, it } from "vitest";
import { EmptyState } from "../empty-state";

describe("EmptyState", () => {
  it("renders the title as a heading and the description", () => {
    render(
      <EmptyState
        icon={FileText}
        title="No requirements"
        description="Select a project to view requirements."
      />,
    );
    expect(
      screen.getByRole("heading", { name: "No requirements" }),
    ).toBeDefined();
    expect(
      screen.getByText("Select a project to view requirements."),
    ).toBeDefined();
  });

  it("renders the optional next action when provided", () => {
    render(
      <EmptyState
        title="Nothing here"
        action={<button type="button">Create</button>}
      />,
    );
    expect(screen.getByRole("button", { name: "Create" })).toBeDefined();
  });
});
