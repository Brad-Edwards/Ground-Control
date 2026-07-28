import type { WorkflowRunFilters } from "@/hooks/use-workflow-runs";
import type { WorkflowRunFinalState, WorkflowRunOutcome } from "@/types/api";

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

function formatEnum(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function formatMinutes(minutes: number | null | undefined): string {
  if (minutes == null) return "—";
  if (minutes < 60) return `${Math.round(minutes)}m`;
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

export function formatCost(value: number | null | undefined): string {
  if (value == null) return "—";
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  });
}

function percentage(value: number, total: number): string {
  if (total === 0) return "0%";
  return `${Math.round((value / total) * 100)}%`;
}

// ---------------------------------------------------------------------------
// Shared UI primitives
// ---------------------------------------------------------------------------

export function MetricCard({
  label,
  value,
  detail,
  tone = "neutral",
}: Readonly<{
  label: string;
  value: string | number;
  detail?: string;
  tone?: "neutral" | "good" | "warn" | "bad" | "info";
}>) {
  const toneClass = {
    neutral: "text-foreground",
    good: "text-green-300",
    warn: "text-yellow-300",
    bad: "text-red-300",
    info: "text-blue-300",
  }[tone];

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs font-medium uppercase text-muted-foreground">
        {label}
      </p>
      <p className={`mt-2 text-2xl font-semibold ${toneClass}`}>{value}</p>
      {detail && <p className="mt-1 text-xs text-muted-foreground">{detail}</p>}
    </div>
  );
}

export function SectionHeading({
  id,
  title,
  detail,
}: Readonly<{ id?: string; title: string; detail?: string }>) {
  return (
    <div>
      <h2 id={id} className="text-lg font-semibold">
        {title}
      </h2>
      {detail && <p className="mt-1 text-sm text-muted-foreground">{detail}</p>}
    </div>
  );
}

function BarRow({
  label,
  count,
  total,
}: Readonly<{ label: string; count: number; total: number }>) {
  return (
    <div className="grid grid-cols-[9rem_1fr_3rem] items-center gap-3 text-sm">
      <span className="truncate text-muted-foreground">{label}</span>
      <div className="h-2 overflow-hidden rounded-full bg-muted">
        <div
          className="h-full rounded-full bg-primary"
          style={{ width: percentage(count, total) }}
        />
      </div>
      <span className="text-right font-medium">{count}</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Final-state / outcome badge
// ---------------------------------------------------------------------------

const FINAL_STATE_STYLE: Record<WorkflowRunFinalState, string> = {
  RUNNING: "bg-blue-100 text-blue-800",
  READY_FOR_REVIEW: "bg-yellow-100 text-yellow-800",
  MERGED: "bg-green-100 text-green-800",
  CLOSED: "bg-gray-100 text-gray-700",
  ESCALATED: "bg-orange-100 text-orange-800",
  ABANDONED: "bg-red-100 text-red-800",
  SUPERSEDED: "bg-slate-100 text-slate-600",
  FAILED: "bg-red-100 text-red-900",
};

const OUTCOME_STYLE: Record<WorkflowRunOutcome, string> = {
  MERGED: "bg-green-100 text-green-800",
  CLOSED_WITHOUT_MERGE: "bg-gray-100 text-gray-700",
  NONE: "bg-muted text-muted-foreground",
};

export function StateBadge({
  state,
}: Readonly<{ state: WorkflowRunFinalState }>) {
  return (
    <span
      className={`rounded px-1.5 py-0.5 text-xs font-medium ${FINAL_STATE_STYLE[state]}`}
      aria-label={`Final state: ${state}`}
    >
      {formatEnum(state)}
    </span>
  );
}

export function OutcomeBadge({
  outcome,
}: Readonly<{ outcome: WorkflowRunOutcome }>) {
  return (
    <span
      className={`rounded px-1.5 py-0.5 text-xs font-medium ${OUTCOME_STYLE[outcome]}`}
      aria-label={`Outcome: ${outcome}`}
    >
      {formatEnum(outcome)}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Filters panel
// ---------------------------------------------------------------------------

export function FiltersPanel({
  filters,
  onChange,
}: Readonly<{
  filters: WorkflowRunFilters;
  onChange: (next: WorkflowRunFilters) => void;
}>) {
  function field(
    id: string,
    label: string,
    key: keyof WorkflowRunFilters,
    type: "text" | "date" = "text",
  ) {
    return (
      <div>
        <label
          htmlFor={id}
          className="mb-1 block text-xs font-medium text-muted-foreground"
        >
          {label}
        </label>
        <input
          id={id}
          type={type}
          className="w-full rounded border border-border bg-background px-2 py-1 text-sm"
          value={(filters[key] as string | undefined) ?? ""}
          onChange={(e) =>
            onChange({ ...filters, [key]: e.target.value || undefined })
          }
        />
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="mb-3 text-sm font-medium">Filters</p>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
        {field("wr-from", "From", "from", "date")}
        {field("wr-to", "To", "to", "date")}
        {field("wr-repo", "Repo", "repo")}
        {field("wr-runtime", "Runtime / agent", "runtime")}
        {field("wr-requirement", "Requirement UID", "requirement")}
        {field("wr-workflow-type", "Workflow type", "workflowType")}
        {field("wr-outcome", "Outcome", "outcome")}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Throughput section
// ---------------------------------------------------------------------------

export function ThroughputSection({
  totalRuns,
  mergedRuns,
  closedRuns,
  activeRuns,
  escalatedRuns,
  abandonedRuns,
  supersededRuns,
}: Readonly<{
  totalRuns: number;
  mergedRuns: number;
  closedRuns: number;
  activeRuns: number;
  escalatedRuns: number;
  abandonedRuns: number;
  supersededRuns: number;
}>) {
  return (
    <section className="space-y-3" aria-labelledby="throughput-heading">
      <SectionHeading
        id="throughput-heading"
        title="Throughput"
        detail={`${totalRuns} total workflow runs across all outcome buckets.`}
      />
      <div className="grid gap-3 sm:grid-cols-4 lg:grid-cols-7">
        <MetricCard label="Total runs" value={totalRuns} tone="info" />
        <MetricCard
          label="Merged"
          value={mergedRuns}
          detail={percentage(mergedRuns, totalRuns)}
          tone="good"
        />
        <MetricCard
          label="Closed"
          value={closedRuns}
          detail={percentage(closedRuns, totalRuns)}
        />
        <MetricCard
          label="Active"
          value={activeRuns}
          detail={percentage(activeRuns, totalRuns)}
          tone="info"
        />
        <MetricCard
          label="Escalated"
          value={escalatedRuns}
          tone={escalatedRuns > 0 ? "warn" : "neutral"}
        />
        <MetricCard
          label="Abandoned"
          value={abandonedRuns}
          tone={abandonedRuns > 0 ? "bad" : "neutral"}
        />
        <MetricCard label="Superseded" value={supersededRuns} />
      </div>
      <div className="space-y-2 rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-medium">Outcome breakdown</p>
        <BarRow label="Merged" count={mergedRuns} total={totalRuns} />
        <BarRow label="Closed" count={closedRuns} total={totalRuns} />
        <BarRow label="Active" count={activeRuns} total={totalRuns} />
        <BarRow label="Escalated" count={escalatedRuns} total={totalRuns} />
        <BarRow label="Abandoned" count={abandonedRuns} total={totalRuns} />
        <BarRow label="Superseded" count={supersededRuns} total={totalRuns} />
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Cycle-time section — labelled bars
// ---------------------------------------------------------------------------

export function CycleTimeSection({
  p50,
  p95,
  p99,
}: Readonly<{
  p50: number | null;
  p95: number | null;
  p99: number | null;
}>) {
  const max = Math.max(p50 ?? 0, p95 ?? 0, p99 ?? 0);

  function CycleBar({
    label,
    minutes,
  }: Readonly<{ label: string; minutes: number | null }>) {
    const pct = max > 0 && minutes != null ? (minutes / max) * 100 : 0;
    return (
      <div className="grid grid-cols-[5rem_1fr_5rem] items-center gap-3 text-sm">
        <span className="text-muted-foreground">{label}</span>
        <div className="h-3 overflow-hidden rounded-full bg-muted">
          <div
            className="h-full rounded-full bg-primary"
            style={{ width: `${pct}%` }}
            aria-label={`${label} cycle time bar`}
          />
        </div>
        <span className="text-right font-medium">{formatMinutes(minutes)}</span>
      </div>
    );
  }

  return (
    <section className="space-y-3" aria-labelledby="cycle-time-heading">
      <SectionHeading
        id="cycle-time-heading"
        title="Cycle-time distribution"
        detail="Wall-clock minutes from run start to final state."
      />
      <div className="rounded-lg border border-border bg-card p-4">
        {p50 == null && p95 == null && p99 == null ? (
          <p className="text-sm text-muted-foreground">
            No cycle-time data available for the selected filters.
          </p>
        ) : (
          <div className="space-y-3">
            <CycleBar label="P50" minutes={p50} />
            <CycleBar label="P95" minutes={p95} />
            <CycleBar label="P99" minutes={p99} />
          </div>
        )}
      </div>
    </section>
  );
}
