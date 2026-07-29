// @vitest-environment jsdom
/**
 * Vitest coverage for the ADR-037 browser-session additions to {@link apiFetch} /
 * {@link apiUpload} / {@link apiDelete}. The contract under test:
 *
 *  - Every fetch carries {@code credentials: "same-origin"} so the {@code GC_SESSION} cookie
 *    rides along on SPA XHR calls.
 *  - On non-GET requests, the {@code XSRF-TOKEN} cookie value (if present) is echoed via the
 *    {@code X-XSRF-TOKEN} header (Spring's CookieCsrfTokenRepository contract).
 *  - A 401 response triggers a redirect to {@code /login} so the user is sent through the
 *    Spring form-login flow.
 *
 * The redirect path is exercised via {@link setRedirectorForTests} rather than by
 * monkey-patching {@code window.location.assign}: jsdom 25 marks {@code window.location} as
 * non-configurable, so a {@code defineProperty(window, "location", …)} stub throws "Cannot
 * redefine property" and the entire test file fails to run. An injectable redirector keeps
 * the production path identical while letting the test see exactly what URL the wrapper
 * tried to navigate to.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  apiDelete,
  apiFetch,
  apiUpload,
  logout,
  setRedirectorForTests,
} from "./api-client";

const originalFetch = globalThis.fetch;
const originalCookie = document.cookie;

function setCookie(value: string) {
  Object.defineProperty(document, "cookie", {
    configurable: true,
    writable: true,
    value,
  });
}

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

describe("apiFetch", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchSpy = vi.fn(() => Promise.resolve(jsonResponse({ ok: true })));
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    setCookie("");
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    setCookie(originalCookie);
  });

  it("passes credentials: 'same-origin' so the session cookie is sent", async () => {
    await apiFetch("/requirements");

    expect(fetchSpy).toHaveBeenCalledOnce();
    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const init = call[1] as RequestInit;
    expect(init.credentials).toBe("same-origin");
  });

  it("does not attach X-XSRF-TOKEN on GET (CSRF only protects mutations)", async () => {
    setCookie("XSRF-TOKEN=t-123; OTHER=x");

    await apiFetch("/requirements");

    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const init = call[1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("attaches X-XSRF-TOKEN from cookie on POST", async () => {
    setCookie("XSRF-TOKEN=t-csrf-abc; UNRELATED=other");

    await apiFetch("/requirements", { method: "POST", body: { x: 1 } });

    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const init = call[1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBe("t-csrf-abc");
  });

  it("attaches X-XSRF-TOKEN on PATCH and DELETE too", async () => {
    setCookie("XSRF-TOKEN=patch-tok");
    await apiFetch("/requirements/1", { method: "PATCH", body: { x: 1 } });
    const patchCall = fetchSpy.mock.calls[0];
    if (!patchCall) throw new Error("expected fetch to be called");
    let init = patchCall[1] as RequestInit;
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe(
      "patch-tok",
    );

    fetchSpy.mockClear();
    setCookie("XSRF-TOKEN=delete-tok");
    await apiFetch("/requirements/1", { method: "DELETE" });
    const deleteCall = fetchSpy.mock.calls[0];
    if (!deleteCall) throw new Error("expected fetch to be called");
    init = deleteCall[1] as RequestInit;
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe(
      "delete-tok",
    );
  });

  it("omits X-XSRF-TOKEN when no cookie is present", async () => {
    setCookie("");

    await apiFetch("/requirements", { method: "POST", body: { x: 1 } });

    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const init = call[1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("redirects to /login on 401 and rethrows ApiError", async () => {
    const redirectSpy = vi.fn();
    const restore = setRedirectorForTests(redirectSpy);
    try {
      fetchSpy.mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            error: { code: "authentication_required", message: "auth" },
          }),
          {
            status: 401,
            headers: { "Content-Type": "application/json" },
          },
        ),
      );

      await expect(apiFetch("/requirements")).rejects.toBeInstanceOf(ApiError);
      expect(redirectSpy).toHaveBeenCalledOnce();
      // GC-Q015: we redirect to /login with a fixed, non-secret `expired=1` flag so the login
      // bundle can show a session-expired notice. It is not a return URL and reflects no response
      // text — the backend still does not consume a `?continue=`, so there is no open-redirect
      // surface.
      const redirectCall = redirectSpy.mock.calls[0];
      if (!redirectCall) throw new Error("expected redirector to be called");
      expect(redirectCall[0]).toBe("/login?expired=1");
    } finally {
      restore();
    }
  });

  it("does not redirect on 403", async () => {
    const redirectSpy = vi.fn();
    const restore = setRedirectorForTests(redirectSpy);
    try {
      fetchSpy.mockResolvedValueOnce(
        new Response(JSON.stringify({ error: { code: "access_denied" } }), {
          status: 403,
          headers: { "Content-Type": "application/json" },
        }),
      );

      await expect(apiFetch("/admin/users")).rejects.toBeInstanceOf(ApiError);
      expect(redirectSpy).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it("logout POSTs /logout with the CSRF header and redirects to /login", async () => {
    const redirectSpy = vi.fn();
    const restore = setRedirectorForTests(redirectSpy);
    setCookie("XSRF-TOKEN=tok");
    try {
      fetchSpy.mockResolvedValueOnce(new Response(null, { status: 204 }));
      await logout();
      expect(fetchSpy).toHaveBeenCalledOnce();
      const call = fetchSpy.mock.calls[0];
      if (!call) throw new Error("expected fetch to be called");
      const [url, init] = call;
      expect(url).toBe("/logout");
      expect(init.method).toBe("POST");
      expect(init.credentials).toBe("same-origin");
      expect(init.headers["X-XSRF-TOKEN"]).toBe("tok");
      expect(redirectSpy).toHaveBeenCalledWith("/login");
    } finally {
      restore();
    }
  });
});

describe("apiDelete and apiUpload", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchSpy = vi.fn(() => Promise.resolve(jsonResponse({ ok: true })));
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    setCookie("XSRF-TOKEN=cross-tok");
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    setCookie(originalCookie);
  });

  it("apiDelete sends credentials and X-XSRF-TOKEN", async () => {
    await apiDelete("/admin/users/alice");

    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const init = call[1] as RequestInit;
    expect(init.credentials).toBe("same-origin");
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe(
      "cross-tok",
    );
  });

  it("apiUpload sends credentials and X-XSRF-TOKEN on multipart POST", async () => {
    const formData = new FormData();
    formData.append("file", new Blob(["x"]), "test.sdoc");

    await apiUpload("/admin/import", formData);

    const call = fetchSpy.mock.calls[0];
    if (!call) throw new Error("expected fetch to be called");
    const init = call[1] as RequestInit;
    expect(init.credentials).toBe("same-origin");
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe(
      "cross-tok",
    );
  });
});
