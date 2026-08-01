import { cn } from "@/lib/utils";

interface PageHeaderProps {
  title: string;
  description?: string;
  /** Optional short count/summary rendered next to the title (e.g. "42 total"). */
  count?: React.ReactNode;
  /** Trailing action controls (buttons, filters entry point). */
  actions?: React.ReactNode;
  className?: string;
}

/**
 * Predictable page header (GC-Q015 clause (b), shell layout § page headers). Renders the single
 * visible H1 for the route so every workspace has consistent heading hierarchy and one landmark
 * title (clause (e)).
 */
export function PageHeader({
  title,
  description,
  count,
  actions,
  className,
}: PageHeaderProps) {
  return (
    <div
      className={cn(
        "flex flex-wrap items-start justify-between gap-3",
        className,
      )}
    >
      <div className="min-w-0">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold text-foreground">{title}</h1>
          {count != null && (
            <span className="text-sm text-muted-foreground">{count}</span>
          )}
        </div>
        {description && (
          <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
