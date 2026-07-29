const API_BASE = "/api/v1";
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const LOGIN_PATH = "/login";
// GC-Q015 clause (a): a fixed, non-secret flag so the standalone login bundle can render a
// "your session expired" notice when re-authentication was triggered by an XHR 401. It is not a
// return URL and reflects no response text (preflight guardrail), so it carries no open-redirect
// or reflection surface.
const LOGIN_EXPIRED_PATH = `${LOGIN_PATH}?expired=1`;

/**
 * Redirect-on-401 indirection. Tests replace this via {@link setRedirectorForTests}; production
 * uses the default which delegates to {@code window.location.assign}. We do NOT mock
 * {@code window.location} directly because jsdom 25 marks it non-configurable, so
 * {@code Object.defineProperty(window, "location", …)} throws "Cannot redefine property" in
 * the unit suite.
 */
type Redirector = (url: string) => void;

const defaultRedirector: Redirector = (url) => {
  if (typeof window !== "undefined") {
    window.location.assign(url);
  }
};

let activeRedirector: Redirector = defaultRedirector;

/**
 * Test-only hook. Returns a function that restores the production redirector. Tests call this
 * in {@code beforeEach} with a vi.fn() spy and call the returned restore function in
 * {@code afterEach}.
 */
export function setRedirectorForTests(replacement: Redirector): () => void {
  const previous = activeRedirector;
  activeRedirector = replacement;
  return () => {
    activeRedirector = previous;
  };
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public detail: string,
  ) {
    super(detail);
    this.name = "ApiError";
  }
}

/**
 * Read the value of a cookie from {@code document.cookie}. ADR-037 §3: the SPA reads
 * {@code XSRF-TOKEN} from the cookie that {@code CookieCsrfTokenRepository.withHttpOnlyFalse()}
 * writes, and echoes it in the {@code X-XSRF-TOKEN} header on mutating requests. The cookie is
 * non-HttpOnly *by design* because the double-submit pattern requires the SPA to read it.
 */
function readCookie(name: string): string | undefined {
  if (typeof document === "undefined" || !document.cookie) {
    return undefined;
  }
  const prefix = `${name}=`;
  for (const part of document.cookie.split(";")) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length));
    }
  }
  return undefined;
}

function isMutation(method: string): boolean {
  const m = method.toUpperCase();
  return m === "POST" || m === "PUT" || m === "PATCH" || m === "DELETE";
}

function attachCsrfHeader(
  method: string,
  headers: Record<string, string>,
): Record<string, string> {
  if (!isMutation(method)) {
    return headers;
  }
  const token = readCookie(CSRF_COOKIE);
  if (!token) {
    return headers;
  }
  return { ...headers, [CSRF_HEADER]: token };
}

async function handleError(response: Response): Promise<never> {
  // ADR-037: a 401 on an SPA XHR means the session is missing or expired. Send the user
  // through the Spring form-login flow so they can re-authenticate. We do NOT redirect on 403
  // — that's "you're logged in but not authorized," which the calling component can render
  // as a permission-denied message without losing the user's place in the SPA.
  //
  // We do NOT pass a `?continue=` parameter: the backend uses Spring's default success
  // handling (saved-request-aware) and does not read `continue`. Tacking one on would create
  // an unused query parameter that looks meaningful (open-redirect-shaped) without actually
  // routing anywhere. Spring's request cache will restore the user's SPA route if they had
  // navigated to one directly; XHR-initiated re-auth lands back at the app default ("/").
  if (response.status === 401) {
    activeRedirector(LOGIN_EXPIRED_PATH);
  }
  const errorBody = await response.json().catch(() => ({}));
  const body = errorBody as { detail?: string; error?: { message?: string } };
  throw new ApiError(
    response.status,
    body.detail ?? body.error?.message ?? `Request failed: ${response.status}`,
  );
}

export async function apiFetch<T>(
  path: string,
  options?: {
    params?: Record<string, string | undefined>;
    method?: string;
    body?: unknown;
    headers?: Record<string, string>;
  },
): Promise<T> {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);

  if (options?.params) {
    for (const [key, value] of Object.entries(options.params)) {
      if (value !== undefined) {
        url.searchParams.set(key, value);
      }
    }
  }

  const method = options?.method ?? "GET";
  let headers: Record<string, string> = {};

  if (options?.body) {
    headers = { ...headers, "Content-Type": "application/json" };
  }

  if (options?.headers) {
    headers = { ...headers, ...options.headers };
  }

  headers = attachCsrfHeader(method, headers);

  const init: RequestInit = {
    method,
    headers,
    credentials: "same-origin",
  };

  if (options?.body) {
    init.body = JSON.stringify(options.body);
  }

  const response = await fetch(url.toString(), init);

  if (!response.ok) {
    await handleError(response);
  }

  return response.json() as Promise<T>;
}

export async function apiDelete(
  path: string,
  options?: { params?: Record<string, string | undefined> },
): Promise<void> {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);

  if (options?.params) {
    for (const [key, value] of Object.entries(options.params)) {
      if (value !== undefined) {
        url.searchParams.set(key, value);
      }
    }
  }

  const response = await fetch(url.toString(), {
    method: "DELETE",
    credentials: "same-origin",
    headers: attachCsrfHeader("DELETE", {}),
  });

  if (!response.ok) {
    await handleError(response);
  }
}

export async function apiUpload<T>(
  path: string,
  formData: FormData,
  options?: {
    params?: Record<string, string | undefined>;
    headers?: Record<string, string>;
  },
): Promise<T> {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);

  if (options?.params) {
    for (const [key, value] of Object.entries(options.params)) {
      if (value !== undefined) {
        url.searchParams.set(key, value);
      }
    }
  }

  const response = await fetch(url.toString(), {
    method: "POST",
    headers: attachCsrfHeader("POST", { ...(options?.headers ?? {}) }),
    body: formData,
    credentials: "same-origin",
  });

  if (!response.ok) {
    await handleError(response);
  }

  return response.json() as Promise<T>;
}

/**
 * Sign out through the ADR-037 browser-session chain. {@code /logout} is a top-level Spring
 * endpoint outside {@code API_BASE}, so it does not go through {@link apiFetch}, but it reuses the
 * same CSRF cookie boundary ({@link attachCsrfHeader}), same-origin credentials, and the injectable
 * redirector — the view layer must not carry its own logout cookie parser or redirect (GC-Q015
 * preflight guardrail). On a successful invalidation the user lands on {@code /login}; a non-ok
 * response leaves the user in place, since there is no destructive client state to roll back.
 */
export async function logout(): Promise<void> {
  const response = await fetch("/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: attachCsrfHeader("POST", {}),
  });
  if (response.ok) {
    activeRedirector(LOGIN_PATH);
  }
}
