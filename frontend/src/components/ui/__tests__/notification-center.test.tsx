// @vitest-environment jsdom
import { ToastProvider, useNotifications } from "@/components/ui/toast";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { NotificationCenter } from "../notification-center";

function Harness() {
  const { notify } = useNotifications();
  return (
    <div>
      <button
        type="button"
        onClick={() =>
          notify({ title: "Requirement created", variant: "success" })
        }
      >
        fire
      </button>
      <NotificationCenter />
    </div>
  );
}

function renderCenter() {
  return render(
    <ToastProvider>
      <Harness />
    </ToastProvider>,
  );
}

describe("NotificationCenter", () => {
  it("starts empty with a plain Notifications label", () => {
    renderCenter();
    expect(screen.getByLabelText("Notifications")).toBeDefined();
  });

  it("reflects the recent-notice count on the trigger after a notice", async () => {
    const user = userEvent.setup();
    renderCenter();
    await user.click(screen.getByRole("button", { name: "fire" }));
    expect(screen.getByLabelText("Notifications, 1 recent")).toBeDefined();
  });

  it("lists a notice and clears the history", async () => {
    const user = userEvent.setup();
    renderCenter();
    await user.click(screen.getByRole("button", { name: "fire" }));
    await user.click(screen.getByLabelText("Notifications, 1 recent"));
    // The notice shows in the dropdown (a lingering toast may also carry the text).
    expect(screen.getAllByText("Requirement created").length).toBeGreaterThan(
      0,
    );
    await user.click(screen.getByRole("button", { name: "Clear all" }));
    // History emptied → the trigger returns to its count-free label.
    expect(screen.getByLabelText("Notifications")).toBeDefined();
  });
});
