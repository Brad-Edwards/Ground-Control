// @vitest-environment jsdom
/**
 * Vitest coverage for the standalone Login bundle.
 *
 * Contract under test:
 * - The login form renders username and password fields plus a submit button.
 * - On submit the form posts application/x-www-form-urlencoded to /login.
 * - The XSRF-TOKEN cookie value is echoed as X-XSRF-TOKEN on the POST.
 * - On a successful login (response.ok, final URL pathname/query not the failure
 *   marker) the page navigates to response.url.
 * - When the server redirects back to /login?error the page shows a generic
 *   error message that does NOT echo the submitted username.
 * - When the page is loaded at /login?error the error message is shown immediately.
 * - Failure detection is URL-pathname-aware: a saved-request redirect whose URL
 *   has its own `error=` query but a different pathname is treated as success.
 */

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Login } from "./login";

// ---- helpers ----------------------------------------------------------------

function setCookie(value: string) {
  Object.defineProperty(document, "cookie", {
    configurable: true,
    writable: true,
    value,
  });
}

const originalCookie = document.cookie;

// ---- suite ------------------------------------------------------------------

afterEach(() => {
  cleanup();
  setCookie(originalCookie);
  vi.restoreAllMocks();
});

describe("Login page — form rendering", () => {
  it("renders a username input, a password input, and a submit button", () => {
    render(<Login />);
    expect(screen.getByLabelText(/username/i)).toBeTruthy();
    expect(screen.getByLabelText(/password/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /sign in/i })).toBeTruthy();
  });
});

describe("Login page — form submission", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // Default: successful login — response.ok true, response.url points at the
    // SPA root so the page navigates away from /login?error.
    // Response.ok and Response.url are prototype getters on the built-in Response
    // class, so Object.assign cannot override them. Return a plain object that
    // quacks like a Response instead.
    fetchSpy = vi.fn(() =>
      Promise.resolve({ ok: true, url: "http://localhost/" } as Response),
    );
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
  });

  it("posts application/x-www-form-urlencoded to /login on submit", async () => {
    render(<Login />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "secret");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledOnce());

    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/login");
    expect((init.headers as Record<string, string>)["Content-Type"]).toContain(
      "application/x-www-form-urlencoded",
    );
    expect(init.method?.toUpperCase()).toBe("POST");
    const body = new URLSearchParams(init.body as string);
    expect(body.get("username")).toBe("alice");
    expect(body.get("password")).toBe("secret");
  });

  it("reads XSRF-TOKEN cookie and sends it as X-XSRF-TOKEN header", async () => {
    setCookie("XSRF-TOKEN=csrf-abc123; OTHER=x");
    render(<Login />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "secret");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledOnce());

    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe(
      "csrf-abc123",
    );
  });

  it("navigates to response.url on a successful login", async () => {
    const redirectSpy = vi.fn();
    render(<Login setRedirectorForTests={redirectSpy} />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "secret");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() =>
      expect(redirectSpy).toHaveBeenCalledWith("http://localhost/"),
    );
  });

  it("treats a redirect to a non-/login path with ?error in the query as success", async () => {
    // Saved-request redirect to /projects?error=stale — this is NOT the Spring
    // failure URL (which is exactly /login?error). The earlier endsWith("?error")
    // heuristic would misclassify this. Pathname-aware detection treats it as
    // success and navigates.
    const fetchSpy = vi.fn(() =>
      Promise.resolve({
        ok: true,
        url: "http://localhost/projects?error=stale",
      } as Response),
    );
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    const redirectSpy = vi.fn();
    render(<Login setRedirectorForTests={redirectSpy} />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "secret");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() =>
      expect(redirectSpy).toHaveBeenCalledWith(
        "http://localhost/projects?error=stale",
      ),
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("Login page — error handling", () => {
  it("shows a generic error message when the server redirects to /login?error", async () => {
    const fetchSpy = vi.fn(() =>
      Promise.resolve({
        ok: true,
        url: "http://localhost/login?error",
      } as Response),
    );
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    render(<Login />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "wrong");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() =>
      expect(
        screen.getByRole("alert") ?? screen.queryByText(/invalid credentials/i),
      ).toBeTruthy(),
    );
    // The error must NOT echo the submitted username.
    expect(screen.queryByText(/alice/)).toBeNull();
  });

  it("shows error immediately when the page URL already contains ?error", () => {
    // Simulate arriving at /login?error after a failed login redirect.
    // window.location is unforgeable in jsdom (non-configurable), so use
    // history.pushState to set the search string the component reads.
    const originalUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    window.history.pushState({}, "", "/login?error");
    try {
      render(<Login />);
      expect(screen.getByRole("alert")).toBeTruthy();
      expect(screen.queryByText(/alice/)).toBeNull();
    } finally {
      window.history.pushState({}, "", originalUrl);
    }
  });
});
