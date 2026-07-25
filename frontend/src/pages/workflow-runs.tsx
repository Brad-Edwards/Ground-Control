import {
  type WorkflowRunFilters,
  useWorkflowRunAggregate,
  useWorkflowRuns,
} from "@/hooks/use-workflow-runs";
import type {
  WorkflowRunFinalState,
  WorkflowRunOutcome,
  WorkflowRunPhaseHotspot,
  WorkflowRunResponse,
} from "@/types/api";
import { useState } from "react";
import { useParams } from "react-router";

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

function formatMinutes(minutes: number | null | undefined): string {
  if (minutes == null) return "—";
  if (minutes < 60) return `${Math.round(minutes)}m`;
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

function formatMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

function formatCost(value: number | null | undefined): string {
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

function MetricCard({
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

function SectionHeading({
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
};

const OUTCOME_STYLE: Record<WorkflowRunOutcome, string> = {
  MERGED: "bg-green-100 text-green-800",
  CLOSED_WITHOUT_MERGE: "bg-gray-100 text-gray-700",
  NONE: "bg-muted text-muted-foreground",
};

function StateBadge({ state }: Readonly<{ state: WorkflowRunFinalState }>) {
  return (
    <span
      className={`rounded px-1.5 py-0.5 text-xs font-medium ${FINAL_STATE_STYLE[state]}`}
      aria-label={`Final state: ${state}`}
    >
      {formatEnum(state)}
    </span>
  );
}

function OutcomeBadge({ outcome }: Readonly<{ outcome: WorkflowRunOutcome }>) {
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

function FiltersPanel({
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

function ThroughputSection({
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

function CycleTimeSection({
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

// ---------------------------------------------------------------------------
// Phase hotspots section
// ---------------------------------------------------------------------------

function PhaseHotspotsSection({
  hotspots,
}: Readonly<{ hotspots: WorkflowRunPhaseHotspot[] }>) {
  if (hotspots.length === 0) {
    return (
      <section className="space-y-3" aria-labelledby="hotspots-heading">
        <SectionHeading
          id="hotspots-heading"
          title="Review / gate hot spots"
          detail="Phase event volume, failure rates, and cycle timing."
        />
        <div className="rounded-lg border border-border bg-card p-4 text-sm text-muted-foreground">
          No phase data available for the selected filters.
        </div>
      </section>
    );
  }

  const maxEventCount = Math.max(...hotspots.map((h) => h.eventCount), 1);

  return (
    <section className="space-y-3" aria-labelledby="hotspots-heading">
      <SectionHeading
        id="hotspots-heading"
        title="Review / gate hot spots"
        detail="Phase event volume, failure rates, and cycle timing."
      />
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-xs font-medium uppercase text-muted-foreground">
              <th className="px-4 py-2">Phase</th>
              <th className="px-4 py-2">Volume</th>
              <th className="px-4 py-2 text-right">Events</th>
              <th className="px-4 py-2 text-right">Failed</th>
              <th className="px-4 py-2 text-right">Escalated</th>
              <th className="px-4 py-2 text-right">P50</th>
              <th className="px-4 py-2 text-right">P95</th>
              <th className="px-4 py-2 text-right">Max cycle</th>
            </tr>
          </thead>
          <tbody>
            {hotspots.map((hotspot) => (
              <tr
                key={hotspot.phase}
                className="border-b border-border last:border-0 hover:bg-muted/30"
              >
                <td className="px-4 py-2 font-mono text-xs">{hotspot.phase}</td>
                <td className="px-4 py-2">
                  <div className="flex items-center gap-2">
                    <div className="h-2 w-24 overflow-hidden rounded-full bg-muted">
                      <div
                        className="h-full rounded-full bg-primary"
                        style={{
                          width: `${(hotspot.eventCount / maxEventCount) * 100}%`,
                        }}
                        aria-label={`${hotspot.phase} relative volume`}
                      />
                    </div>
                  </div>
                </td>
                <td className="px-4 py-2 text-right font-medium">
                  {hotspot.eventCount}
                </td>
                <td className="px-4 py-2 text-right">
                  <span
                    className={hotspot.failedCount > 0 ? "text-red-400" : ""}
                  >
                    {hotspot.failedCount}
                  </span>
                </td>
                <td className="px-4 py-2 text-right">
                  <span
                    className={
                      hotspot.escalatedCount > 0 ? "text-yellow-400" : ""
                    }
                  >
                    {hotspot.escalatedCount}
                  </span>
                </td>
                <td className="px-4 py-2 text-right text-muted-foreground">
                  {formatMs(hotspot.p50Ms)}
                </td>
                <td className="px-4 py-2 text-right text-muted-foreground">
                  {formatMs(hotspot.p95Ms)}
                </td>
                <td className="px-4 py-2 text-right text-muted-foreground">
                  {hotspot.maxCycleIndex ?? "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Cost proxies section
// ---------------------------------------------------------------------------

function CostProxiesSection({
  totalCostProxy,
  mergedCostProxy,
  closedCostProxy,
  costProxyPerMergedRun,
  costProxyPerClosedRun,
  totalModelInvocations,
  totalWallClockMinutes,
  totalTokenUsage,
}: Readonly<{
  totalCostProxy: number;
  mergedCostProxy: number;
  closedCostProxy: number;
  costProxyPerMergedRun: number | null;
  costProxyPerClosedRun: number | null;
  totalModelInvocations: number;
  totalWallClockMinutes: number;
  totalTokenUsage: number;
}>) {
  return (
    <section className="space-y-3" aria-labelledby="cost-heading">
      <SectionHeading
        id="cost-heading"
        title="Cost proxies"
        detail="Aggregate economics across merged PRs and closed issues."
      />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <MetricCard
          label="Total cost proxy"
          value={formatCost(totalCostProxy)}
          tone="info"
        />
        <MetricCard
          label="Cost / merged run"
          value={formatCost(costProxyPerMergedRun)}
          detail={`${formatCost(mergedCostProxy)} total`}
          tone={costProxyPerMergedRun != null ? "info" : "neutral"}
        />
        <MetricCard
          label="Cost / closed run"
          value={formatCost(costProxyPerClosedRun)}
          detail={`${formatCost(closedCostProxy)} total`}
          tone={costProxyPerClosedRun != null ? "info" : "neutral"}
        />
        <MetricCard
          label="Model invocations"
          value={totalModelInvocations.toLocaleString()}
          tone="neutral"
        />
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <MetricCard
          label="Wall-clock minutes"
          value={formatMinutes(totalWallClockMinutes)}
          tone="neutral"
        />
        <MetricCard
          label="Token usage"
          value={totalTokenUsage.toLocaleString()}
          tone="neutral"
        />
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Active workflow status section
// ---------------------------------------------------------------------------

const ACTIVE_STATES = new Set<WorkflowRunFinalState>([
  "RUNNING",
  "READY_FOR_REVIEW",
]);

function ActiveRunsSection({
  runs,
}: Readonly<{ runs: WorkflowRunResponse[] }>) {
  const activeRuns = runs.filter((r) => ACTIVE_STATES.has(r.finalState));

  return (
    <section className="space-y-3" aria-labelledby="active-runs-heading">
      <SectionHeading
        id="active-runs-heading"
        title="Active workflow status"
        detail={`${activeRuns.length} run${activeRuns.length === 1 ? "" : "s"} currently in-flight or awaiting review.`}
      />
      {activeRuns.length === 0 ? (
        <div className="rounded-lg border border-border bg-card p-4 text-sm text-muted-foreground">
          No active runs at this time.
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs font-medium uppercase text-muted-foreground">
                <th className="px-4 py-2">State</th>
                <th className="px-4 py-2">Type</th>
                <th className="px-4 py-2">Repo</th>
                <th className="px-4 py-2">Branch</th>
                <th className="px-4 py-2">Runtime</th>
                <th className="px-4 py-2">Outcome</th>
                <th className="px-4 py-2 text-right">Started</th>
              </tr>
            </thead>
            <tbody>
              {activeRuns.map((run) => (
                <tr
                  key={run.id}
                  className="border-b border-border last:border-0 hover:bg-muted/30"
                >
                  <td className="px-4 py-2">
                    <StateBadge state={run.finalState} />
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">
                    {run.workflowType}
                  </td>
                  <td className="px-4 py-2 text-muted-foreground">
                    {run.repo ?? "—"}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-muted-foreground">
                    {run.branch ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-muted-foreground">
                    {run.runtimeDriver ?? "—"}
                  </td>
                  <td className="px-4 py-2">
                    <OutcomeBadge outcome={run.outcome} />
                  </td>
                  <td className="px-4 py-2 text-right text-xs text-muted-foreground">
                    {run.startedAt
                      ? new Date(run.startedAt).toLocaleString()
                      : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Root page component
// ---------------------------------------------------------------------------

export function WorkflowRuns() {
  const { projectId = "" } = useParams<{ projectId: string }>();
  const [filters, setFilters] = useState<WorkflowRunFilters>({});

  const {
    data: aggregate,
    isLoading: aggLoading,
    isError: aggError,
    error: aggErr,
  } = useWorkflowRunAggregate(projectId, filters);

  const {
    data: runs = [],
    isLoading: runsLoading,
    isError: runsError,
  } = useWorkflowRuns(projectId);

  const isLoading = aggLoading || runsLoading;
  const isError = aggError || runsError;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Workflow Runs</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Throughput, cycle time, cost economics, and live run status for
            AI-driven workflow executions.
          </p>
        </div>
      </div>

      <FiltersPanel filters={filters} onChange={setFilters} />

      {isLoading && (
        <div className="rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground">
          Loading workflow run data...
        </div>
      )}

      {!isLoading && isError && (
        <div className="rounded-lg border border-destructive bg-card p-6 text-sm text-destructive">
          {aggErr instanceof Error
            ? aggErr.message
            : "Unable to load workflow run data."}
        </div>
      )}

      {!isLoading && !isError && !aggregate && (
        <div className="rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground">
          No workflow run data available for this project.
        </div>
      )}

      {!isLoading && !isError && aggregate && (
        <>
          <ThroughputSection
            totalRuns={aggregate.totalRuns}
            mergedRuns={aggregate.mergedRuns}
            closedRuns={aggregate.closedRuns}
            activeRuns={aggregate.activeRuns}
            escalatedRuns={aggregate.escalatedRuns}
            abandonedRuns={aggregate.abandonedRuns}
            supersededRuns={aggregate.supersededRuns}
          />

          <CycleTimeSection
            p50={aggregate.cycleTimeP50Min}
            p95={aggregate.cycleTimeP95Min}
            p99={aggregate.cycleTimeP99Min}
          />

          <PhaseHotspotsSection hotspots={aggregate.phaseHotspots} />

          <CostProxiesSection
            totalCostProxy={aggregate.totalCostProxy}
            mergedCostProxy={aggregate.mergedCostProxy}
            closedCostProxy={aggregate.closedCostProxy}
            costProxyPerMergedRun={aggregate.costProxyPerMergedRun}
            costProxyPerClosedRun={aggregate.costProxyPerClosedRun}
            totalModelInvocations={aggregate.totalModelInvocations}
            totalWallClockMinutes={aggregate.totalWallClockMinutes}
            totalTokenUsage={aggregate.totalTokenUsage}
          />

          <ActiveRunsSection runs={runs} />
        </>
      )}
    </div>
  );
}
