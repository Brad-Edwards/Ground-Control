import { cn } from "@/lib/utils";

export type MetricTone = "default" | "info" | "success" | "warning" | "danger";

interface MetricCardProps {
  label: string;
  value: React.ReactNode;
  /** Optional semantic emphasis for the value (uses state tokens, not raw colours). */
  tone?: MetricTone;
  /** Optional secondary line under the value (e.g. a percentage or total). */
  detail?: React.ReactNode;
  /** When provided, the card becomes an activating control (e.g. drill-down navigation). */
  onClick?: () => void;
  className?: string;
}

const toneClasses = {
  default: "text-foreground",
  info: "text-info",
  success: "text-success",
  warning: "text-warning",
  danger: "text-danger",
} as const;

/**
 * Small numeric summary card (GC-Q015 clause (b)). Promotes the dashboard/workflow stat tiles into
 * one primitive. When {@code onClick} is set it renders as a real button so the drill-down is
 * keyboard-activated (clause (e)); otherwise it is a static figure.
 */
export function MetricCard({
  label,
  value,
  tone = "default",
  detail,
  onClick,
  className,
}: MetricCardProps) {
  const body = (
    <>
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className={cn("mt-1 text-2xl font-semibold", toneClasses[tone])}>
        {value}
      </p>
      {detail != null && (
        <p className="mt-1 text-xs text-muted-foreground">{detail}</p>
      )}
    </>
  );

  const shared = "rounded-lg border border-border bg-card p-4 text-left";

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        className={cn(
          shared,
          "transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          className,
        )}
      >
        {body}
      </button>
    );
  }

  return <div className={cn(shared, className)}>{body}</div>;
}
