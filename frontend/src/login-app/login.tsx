import { type FormEvent, type ReactNode, useState } from "react";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const LOGIN_PATH = "/login";

type Redirector = (url: string) => void;

const defaultRedirector: Redirector = (url) => {
  window.location.assign(url);
};

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

interface FieldProps {
  label: string;
  children: ReactNode;
}

function Field({ label, children }: FieldProps) {
  return (
    <label className="block space-y-1.5">
      <span className="text-sm font-medium text-foreground">{label}</span>
      {children}
    </label>
  );
}

const inputClass =
  "w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1 focus:ring-offset-background";

const submitButtonClass =
  "w-full inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1 focus:ring-offset-background disabled:pointer-events-none disabled:opacity-50 bg-primary text-primary-foreground hover:bg-primary/90";

interface LoginProps {
  /** Test-only: replace the redirect function so tests can spy on navigations. */
  setRedirectorForTests?: Redirector;
}

// Spring's `formLogin().failureUrl(/login?error)` returns the user to /login with
// an `error` query param. `response.url` after fetch with `redirect: "follow"`
// reflects the final URL the browser landed on. Parse rather than string-match —
// a successful login may legitimately redirect to a saved request whose own URL
// happens to contain "error" in its query.
function isFailureRedirect(finalUrl: string): boolean {
  try {
    const parsed = new URL(finalUrl);
    return parsed.pathname === LOGIN_PATH && parsed.searchParams.has("error");
  } catch {
    return false;
  }
}

export function Login({ setRedirectorForTests }: LoginProps) {
  const redirector = setRedirectorForTests ?? defaultRedirector;

  const searchParams =
    typeof window !== "undefined"
      ? new URLSearchParams(window.location.search)
      : new URLSearchParams();

  const [hasError, setHasError] = useState(searchParams.has("error"));
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitting(true);
    setHasError(false);

    const form = e.currentTarget;
    const username = (form.elements.namedItem("username") as HTMLInputElement)
      .value;
    const password = (form.elements.namedItem("password") as HTMLInputElement)
      .value;

    const csrfToken = readCookie(CSRF_COOKIE);
    const headers: Record<string, string> = {
      "Content-Type": "application/x-www-form-urlencoded",
    };
    if (csrfToken) {
      headers[CSRF_HEADER] = csrfToken;
    }

    try {
      const body = new URLSearchParams({ username, password });
      const response = await fetch(LOGIN_PATH, {
        method: "POST",
        headers,
        body: body.toString(),
        credentials: "same-origin",
        redirect: "follow",
      });

      if (response.ok && !isFailureRedirect(response.url)) {
        redirector(response.url);
        return;
      }
      setHasError(true);
    } catch {
      setHasError(true);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="w-full max-w-sm space-y-6 rounded-lg border border-border bg-card p-8 shadow-sm">
        <h1 className="text-2xl font-semibold text-foreground">
          Ground Control
        </h1>
        {hasError && (
          <div
            role="alert"
            className="rounded-md border border-destructive bg-destructive/10 px-4 py-3 text-sm text-destructive"
          >
            Invalid credentials. Please try again.
          </div>
        )}
        <form onSubmit={handleSubmit} className="space-y-4">
          <Field label="Username">
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              required
              className={inputClass}
            />
          </Field>
          <Field label="Password">
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              className={inputClass}
            />
          </Field>
          <button
            type="submit"
            disabled={submitting}
            className={submitButtonClass}
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
