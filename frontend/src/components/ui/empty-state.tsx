import { cn } from "@/lib/utils";
import type { LucideIcon } from "lucide-react";

interface EmptyStateProps {
  icon?: LucideIcon;
  title: string;
  description?: string;
  /** Optional next action, shown only when the caller has an authorized action to offer. */
  action?: React.ReactNode;
  className?: string;
}

/**
 * Meaningful-absence primitive (GC-Q015 clause (b), interaction patterns § Empty states).
 * Explains what is absent and, when supplied, exposes the next action.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 py-16 text-center",
        className,
      )}
    >
      {Icon && <Icon className="h-10 w-10 text-muted-foreground" aria-hidden />}
      <h2 className="text-lg font-medium text-foreground">{title}</h2>
      {description && (
        <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}
