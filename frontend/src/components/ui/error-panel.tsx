import { ApiError } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import { AlertTriangle, Lock } from "lucide-react";

interface ErrorPanelProps {
  error: unknown;
  /** Overrides the derived title (e.g. a workspace-specific heading). */
  title?: string;
  action?: React.ReactNode;
  className?: string;
}

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    return error.detail;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "An unexpected error occurred.";
}

/**
 * API/authorization error surface (GC-Q015 clause (b)/(e), interaction patterns § Error states).
 * A 403 is rendered in place as an authenticated-but-unauthorized notice — never as route hiding
 * or a claim that the resource does not exist. All other errors show the canonical
 * {@link ApiError} detail without a second per-component error envelope.
 */
export function ErrorPanel({
  error,
  title,
  action,
  className,
}: ErrorPanelProps) {
  const forbidden = error instanceof ApiError && error.status === 403;
  const Icon = forbidden ? Lock : AlertTriangle;
  const heading =
    title ?? (forbidden ? "You don't have access" : "Something went wrong");

  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center justify-center gap-3 rounded-lg border border-danger/30 bg-danger/5 py-12 text-center",
        className,
      )}
    >
      <Icon className="h-8 w-8 text-danger" aria-hidden />
      <h2 className="text-base font-medium text-foreground">{heading}</h2>
      <p className="max-w-md text-sm text-muted-foreground">
        {forbidden
          ? "Your account is signed in but is not authorized for this resource."
          : messageFor(error)}
      </p>
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}
