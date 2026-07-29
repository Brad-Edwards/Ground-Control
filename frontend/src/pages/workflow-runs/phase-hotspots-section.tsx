import { MetricCard } from "@/components/ui/metric-card";
import type { StreamStatus } from "@/hooks/use-workflow-run-stream";
import type {
  WorkflowRunFinalState,
  WorkflowRunPhaseHotspot,
  WorkflowRunResponse,
} from "@/types/api";
import {
  OutcomeBadge,
  SectionHeading,
  StateBadge,
  formatCost,
  formatMinutes,
  formatMs,
} from "./format-enum";

// ---------------------------------------------------------------------------
// Phase hotspots section
// ---------------------------------------------------------------------------

export function PhaseHotspotsSection({
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

export function CostProxiesSection({
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
          tone={costProxyPerMergedRun != null ? "info" : "default"}
        />
        <MetricCard
          label="Cost / closed run"
          value={formatCost(costProxyPerClosedRun)}
          detail={`${formatCost(closedCostProxy)} total`}
          tone={costProxyPerClosedRun != null ? "info" : "default"}
        />
        <MetricCard
          label="Model invocations"
          value={totalModelInvocations.toLocaleString()}
          tone="default"
        />
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <MetricCard
          label="Wall-clock minutes"
          value={formatMinutes(totalWallClockMinutes)}
          tone="default"
        />
        <MetricCard
          label="Token usage"
          value={totalTokenUsage.toLocaleString()}
          tone="default"
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

export function ActiveRunsSection({
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
// Transport health indicator
// ---------------------------------------------------------------------------

/**
 * Reports how updates are arriving, not whether a workflow is alive. `Polling` is a truthful
 * statement that the page is on the 30-second fallback — a poll succeeding is not evidence that
 * push is working, and telemetry never proves a process is currently running.
 */
export function StreamStatusBadge({
  status,
}: Readonly<{ status: StreamStatus }>) {
  const { label, detail, dot } = {
    connecting: {
      label: "Connecting",
      detail: "Opening the live update stream.",
      dot: "bg-yellow-400",
    },
    live: {
      label: "Live",
      detail: "Updates are pushed as they are recorded.",
      dot: "bg-green-400",
    },
    degraded: {
      label: "Polling",
      detail: "Live stream unavailable — refreshing every 30 seconds instead.",
      dot: "bg-orange-400",
    },
  }[status];

  return (
    // <output> carries role="status" natively, so the live-region announcement works without
    // hand-applying an ARIA role to a generic container.
    <output
      className="flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2"
      aria-label={`Update transport: ${label}`}
    >
      <span className={`h-2 w-2 rounded-full ${dot}`} aria-hidden="true" />
      <div>
        <p className="text-sm font-medium">{label}</p>
        <p className="text-xs text-muted-foreground">{detail}</p>
      </div>
    </output>
  );
}
