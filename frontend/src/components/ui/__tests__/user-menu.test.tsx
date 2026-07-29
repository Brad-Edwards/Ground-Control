// @vitest-environment jsdom
import type { SessionResponse } from "@/hooks/use-session";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UserMenu } from "../user-menu";

function renderMenu(session: SessionResponse) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  qc.setQueryData(["session"], session);
  return render(
    <QueryClientProvider client={qc}>
      <UserMenu />
    </QueryClientProvider>,
  );
}

describe("UserMenu", () => {
  const originalFetch = globalThis.fetch;
  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });
  beforeEach(() => {
    Object.defineProperty(document, "cookie", {
      configurable: true,
      writable: true,
      value: "XSRF-TOKEN=tok",
    });
  });

  it("shows the signed-in principal display name", () => {
    renderMenu({
      displayName: "alice",
      roles: ["ROLE_USER"],
      canAdminister: false,
    });
    expect(screen.getByText("alice")).toBeDefined();
  });

  it("shows the admin cue and role projection when canAdminister is true", async () => {
    const user = userEvent.setup();
    renderMenu({
      displayName: "alice",
      roles: ["ROLE_ADMIN", "ROLE_USER"],
      canAdminister: true,
    });
    await user.click(screen.getByLabelText("Account menu"));
    expect(screen.getByText("Admin · User")).toBeDefined();
    // The ShieldCheck admin cue (aria-hidden, portaled to body) must actually render.
    expect(document.querySelector(".lucide-shield-check")).not.toBeNull();
  });

  it("hides the admin cue when canAdminister is false despite the same roles", async () => {
    const user = userEvent.setup();
    renderMenu({
      displayName: "bob",
      roles: ["ROLE_ADMIN", "ROLE_USER"],
      canAdminister: false,
    });
    await user.click(screen.getByLabelText("Account menu"));
    // Same roles-join renders, but the cue is gated strictly on canAdminister — deleting,
    // hard-coding, or inverting that gate must fail this test.
    expect(screen.getByText("Admin · User")).toBeDefined();
    expect(document.querySelector(".lucide-shield-check")).toBeNull();
  });

  it("signs out through the CSRF-aware /logout POST", async () => {
    const user = userEvent.setup();
    const fetchSpy = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    renderMenu({
      displayName: "alice",
      roles: ["ROLE_USER"],
      canAdminister: false,
    });
    await user.click(screen.getByLabelText("Account menu"));
    await user.click(screen.getByText("Sign out"));

    expect(fetchSpy).toHaveBeenCalledOnce();
    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const [url, init] = call;
    expect(url).toBe("/logout");
    expect(init.method).toBe("POST");
    expect(init.headers["X-XSRF-TOKEN"]).toBe("tok");
  });
});
