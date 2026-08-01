import { cn } from "@/lib/utils";
import type { Priority, RequirementType, Status } from "@/types/api";

/**
 * Semantic badge variants (GC-Q015 clause (b)). Each variant renders through the semantic state
 * tokens in {@code main.css} rather than raw Tailwind colours, so state colour is consistent and
 * WCAG AA across the console. Callers always render a text label alongside the colour, so meaning
 * never rests on colour alone (clause (e)).
 */
export type BadgeVariant =
  | "neutral"
  | "info"
  | "success"
  | "warning"
  | "danger"
  | "evidence";

const variantClasses: Record<BadgeVariant, string> = {
  neutral: "bg-muted text-muted-foreground",
  info: "bg-info/15 text-info",
  success: "bg-success/15 text-success",
  warning: "bg-warning/15 text-warning",
  danger: "bg-danger/15 text-danger",
  evidence: "bg-evidence/15 text-evidence",
};

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
}

export function Badge({
  children,
  variant = "neutral",
  className,
  ...rest
}: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
        variantClasses[variant],
        className,
      )}
      {...rest}
    >
      {children}
    </span>
  );
}

export const statusVariants: Record<Status, BadgeVariant> = {
  DRAFT: "neutral",
  ACTIVE: "success",
  DEPRECATED: "warning",
  ARCHIVED: "neutral",
};

const priorityVariants: Record<Priority, BadgeVariant> = {
  MUST: "danger",
  SHOULD: "warning",
  COULD: "info",
  WONT: "neutral",
};

const typeVariants: Record<RequirementType, BadgeVariant> = {
  FUNCTIONAL: "info",
  NON_FUNCTIONAL: "evidence",
  CONSTRAINT: "warning",
  INTERFACE: "success",
};

export function StatusBadge({ status }: { status: Status }) {
  return <Badge variant={statusVariants[status]}>{status}</Badge>;
}

export function PriorityBadge({ priority }: { priority: Priority }) {
  return <Badge variant={priorityVariants[priority]}>{priority}</Badge>;
}

export function TypeBadge({ type }: { type: RequirementType }) {
  return <Badge variant={typeVariants[type]}>{type.replace("_", " ")}</Badge>;
}
