// @vitest-environment jsdom
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MetricCard } from "../metric-card";

describe("MetricCard", () => {
  it("renders label, value, and optional detail", () => {
    render(<MetricCard label="Merged" value={42} detail="75%" />);
    expect(screen.getByText("Merged")).toBeDefined();
    expect(screen.getByText("42")).toBeDefined();
    expect(screen.getByText("75%")).toBeDefined();
  });

  it("is a static figure (not a button) without onClick", () => {
    render(<MetricCard label="Total" value={10} />);
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("becomes a keyboard-activatable button when onClick is provided", () => {
    const onClick = vi.fn();
    render(<MetricCard label="Active" value={3} onClick={onClick} />);
    const button = screen.getByRole("button");
    expect(button.className).toContain("focus-visible:ring-ring");
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("applies a semantic tone token to the value", () => {
    render(<MetricCard label="Failed" value={1} tone="danger" />);
    expect(screen.getByText("1").className).toContain("text-danger");
  });
});
