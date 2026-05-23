import {
  FormField,
  inputClass,
  primaryButton,
} from "@/components/ui/form-field";
import { type FormEvent, useState } from "react";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

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

interface LoginProps {
  /** Test-only: replace the redirect function so tests can spy on navigations. */
  setRedirectorForTests?: Redirector;
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
      const response = await fetch("/login", {
        method: "POST",
        headers,
        body: body.toString(),
        credentials: "same-origin",
        redirect: "follow",
      });

      if (response.ok && !response.url.endsWith("?error")) {
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
          <FormField label="Username">
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              required
              className={inputClass}
            />
          </FormField>
          <FormField label="Password">
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              className={inputClass}
            />
          </FormField>
          <button
            type="submit"
            disabled={submitting}
            className={`w-full ${primaryButton}`}
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
