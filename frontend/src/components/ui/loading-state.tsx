import { cn } from "@/lib/utils";

/**
 * Container-sized spinner (GC-Q015 clause (b), interaction patterns § Loading states). Dimensions
 * are stable so content does not shift when it resolves.
 */
export function LoadingState({
  label = "Loading…",
  className,
}: {
  label?: string;
  className?: string;
}) {
  return (
    <output
      className={cn("flex min-h-[40vh] items-center justify-center", className)}
      aria-live="polite"
    >
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary" />
      <span className="sr-only">{label}</span>
    </output>
  );
}

/** Fixed-height skeleton block for list/table placeholders. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      className={cn("animate-pulse rounded-md bg-muted", className)}
      aria-hidden
    />
  );
}
