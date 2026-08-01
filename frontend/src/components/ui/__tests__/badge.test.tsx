// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Badge, PriorityBadge, StatusBadge, TypeBadge } from "../badge";

describe("Badge", () => {
  it("applies the semantic variant token class and keeps the text label", () => {
    render(<Badge variant="success">Merged</Badge>);
    const badge = screen.getByText("Merged");
    // meaning is carried by the label, colour by the semantic token (never colour alone)
    expect(badge.className).toContain("text-success");
    expect(badge.className).toContain("bg-success/15");
  });

  it("forwards standard span attributes such as aria-label", () => {
    render(
      <Badge variant="danger" aria-label="Final state: FAILED">
        Failed
      </Badge>,
    );
    expect(screen.getByLabelText("Final state: FAILED")).toBeDefined();
  });

  it("maps requirement status ACTIVE to the success variant", () => {
    render(<StatusBadge status="ACTIVE" />);
    const badge = screen.getByText("ACTIVE");
    expect(badge.className).toContain("text-success");
  });

  it("maps MUST priority to danger and COULD to info", () => {
    const { rerender } = render(<PriorityBadge priority="MUST" />);
    expect(screen.getByText("MUST").className).toContain("text-danger");
    rerender(<PriorityBadge priority="COULD" />);
    expect(screen.getByText("COULD").className).toContain("text-info");
  });

  it("renders a requirement type label with the underscore replaced", () => {
    render(<TypeBadge type="NON_FUNCTIONAL" />);
    expect(screen.getByText("NON FUNCTIONAL")).toBeDefined();
  });
});
