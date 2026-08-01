// @vitest-environment jsdom
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Button } from "../button";

describe("Button", () => {
  it("defaults to type=button so it never accidentally submits a form", () => {
    render(<Button>Save</Button>);
    expect(
      screen.getByRole("button", { name: "Save" }).getAttribute("type"),
    ).toBe("button");
  });

  it("respects an explicit type", () => {
    render(<Button type="submit">Go</Button>);
    expect(
      screen.getByRole("button", { name: "Go" }).getAttribute("type"),
    ).toBe("submit");
  });

  it("applies the danger variant token classes", () => {
    render(<Button variant="danger">Delete</Button>);
    expect(screen.getByRole("button", { name: "Delete" }).className).toContain(
      "bg-destructive",
    );
  });

  it("carries a visible focus ring for keyboard navigation", () => {
    render(<Button>Focus</Button>);
    expect(screen.getByRole("button", { name: "Focus" }).className).toContain(
      "focus-visible:ring-ring",
    );
  });

  it("does not fire onClick while disabled", () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Nope
      </Button>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Nope" }));
    expect(onClick).not.toHaveBeenCalled();
  });
});
