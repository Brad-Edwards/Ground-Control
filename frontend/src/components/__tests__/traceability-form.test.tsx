// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TraceabilityForm } from "../traceability-form";

describe("TraceabilityForm", () => {
  it("shows an identifier example that matches the selected artifact type", async () => {
    const user = userEvent.setup();
    render(<TraceabilityForm onSubmit={vi.fn()} onCancel={vi.fn()} />);

    expect(screen.getByPlaceholderText("e.g. 42")).toBeTruthy();

    await user.selectOptions(
      screen.getByRole("combobox", { name: "Artifact Type" }),
      "ADR",
    );

    expect(screen.getByPlaceholderText("e.g. ADR-011")).toBeTruthy();
  });
});
