import { Badge } from "@/components/ui/badge";
import { useWorkflowRunStream } from "@/hooks/use-workflow-run-stream";
import { useWorkflowActivity } from "@/hooks/use-workflow-runs";
import type { WorkflowActivityResponse } from "@/types/api";
import { Link, useParams } from "react-router";
import {
  type GateAttempt,
  type OpenActivityRun,
  attentionLabel,
  elapsedMs,
  formatElapsed,
  gateLabel,
  gateVariant,
  useSnapshotClock,
} from "./workflow-activity/activity-format";
import {
  OutcomeBadge,
  SectionHeading,
  StateBadge,
  formatCost,
} from "./workflow-runs/format-enum";
import { StreamStatusBadge } from "./workflow-runs/phase-hotspots-section";

function RunIdentity({ row }: Readonly<{ row: OpenActivityRun }>) {
  const issue = row.run.issueNumber ? `#${row.run.issueNumber}` : "No issue";
  return (
    <div>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold">{issue}</h2>
        <StateBadge state={row.run.finalState} />
        <Badge variant="neutral">{row.run.workflowType}</Badge>
      </div>
      <p className="mt-1 break-all font-mono text-xs text-muted-foreground">
        {row.run.branch ?? "Branch unobserved"}
      </p>
    </div>
  );
}

function GateStrip({ gates }: Readonly<{ gates: GateAttempt[] }>) {
  if (gates.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No station attempts observed yet.
      </p>
    );
  }
  return (
    <ol className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
      {gates.map((gate) => (
        <li
          key={gate.stationId}
          className="rounded-md border border-border bg-background p-3"
        >
          <div className="flex items-start justify-between gap-2">
            <span className="text-sm font-medium">
              {gate.stationTitle ?? gate.stationId}
            </span>
            <Badge variant={gateVariant(gate)}>{gateLabel(gate)}</Badge>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            Cycle {gate.cycleIndex ?? "unobserved"}
            {gate.durationMs != null
              ? ` · ${formatElapsed(gate.durationMs)}`
              : ""}
          </p>
          {(gate.findingCount > 0 || gate.findingsDropped > 0) && (
            <p className="mt-1 text-xs text-muted-foreground">
              {gate.findingCount} persisted finding
              {gate.findingCount === 1 ? "" : "s"}
              {gate.findingsDropped > 0
                ? ` · ${gate.findingsDropped} dropped by emitter cap`
                : ""}
            </p>
          )}
        </li>
      ))}
    </ol>
  );
}

function OpenRunCard({
  row,
  now,
}: Readonly<{ row: OpenActivityRun; now: number }>) {
  const attention = attentionLabel(row, now);
  const phaseElapsed = elapsedMs(now, row.currentPhaseSince);
  const totalElapsed = elapsedMs(now, row.run.startedAt);
  return (
    <article
      className={`rounded-lg border bg-card p-4 ${attention ? "border-warning" : "border-border"}`}
    >
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <RunIdentity row={row} />
        {attention && <Badge variant="warning">{attention}</Badge>}
      </div>

      <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-5">
        <div>
          <dt className="text-xs text-muted-foreground">Current phase</dt>
          <dd className="mt-1 font-medium">
            {row.currentPhaseTitle ?? row.currentPhase ?? "Unobserved"}
          </dd>
          <dd className="mt-1 text-xs text-muted-foreground">
            {row.currentPhaseSince
              ? `Observed ${new Date(row.currentPhaseSince).toLocaleString()}`
              : "Transition time unobserved"}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Time in phase</dt>
          <dd className="mt-1 font-medium">{formatElapsed(phaseElapsed)}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Elapsed</dt>
          <dd className="mt-1 font-medium">{formatElapsed(totalElapsed)}</dd>
          <dd className="mt-1 text-xs text-muted-foreground">
            {row.run.startedAt
              ? `Started ${new Date(row.run.startedAt).toLocaleString()}`
              : "Start time unobserved"}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Cycle</dt>
          <dd className="mt-1 font-medium">
            {row.currentCycle ?? "Unobserved"}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Model / tier</dt>
          <dd className="mt-1 font-medium">
            {row.routing?.model ?? "Unobserved"}
            {row.routing?.tier ? ` · ${row.routing.tier}` : ""}
          </dd>
        </div>
      </dl>

      <div className="mt-4 border-t border-border pt-4">
        <p className="mb-2 text-xs font-medium uppercase text-muted-foreground">
          Gate progress
        </p>
        <GateStrip gates={row.gates} />
      </div>
    </article>
  );
}

function RecentlyFinished({
  runs,
  projectId,
}: Readonly<{
  runs: WorkflowActivityResponse["recentlyFinished"];
  projectId: string;
}>) {
  return (
    <section className="space-y-3" aria-labelledby="recent-finished-heading">
      <div className="flex items-end justify-between gap-3">
        <SectionHeading
          id="recent-finished-heading"
          title="Recently finished"
          detail="Terminal runs leave the live band and remain available in run history."
        />
        <Link
          to={`/p/${projectId}/workflow-runs`}
          className="text-sm font-medium text-primary hover:underline"
        >
          View run history
        </Link>
      </div>
      {runs.length === 0 ? (
        <div className="rounded-lg border border-border bg-card p-4 text-sm text-muted-foreground">
          No terminal runs recorded.
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs font-medium uppercase text-muted-foreground">
                <th className="px-4 py-2">Run</th>
                <th className="px-4 py-2">State</th>
                <th className="px-4 py-2">Outcome</th>
                <th className="px-4 py-2">Branch</th>
                <th className="px-4 py-2 text-right">Cost</th>
                <th className="px-4 py-2 text-right">Ended</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr
                  key={run.id}
                  className="border-b border-border last:border-0"
                >
                  <td className="px-4 py-2 font-medium">
                    {run.issueNumber ? `#${run.issueNumber}` : run.workflowType}
                  </td>
                  <td className="px-4 py-2">
                    <StateBadge state={run.finalState} />
                  </td>
                  <td className="px-4 py-2">
                    <OutcomeBadge outcome={run.outcome} />
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-muted-foreground">
                    {run.branch ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {formatCost(run.costProxy)}
                  </td>
                  <td className="px-4 py-2 text-right text-xs text-muted-foreground">
                    {run.endedAt
                      ? new Date(run.endedAt).toLocaleString()
                      : "Unobserved"}
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

export function WorkflowActivity() {
  const { projectId = "" } = useParams<{ projectId: string }>();
  const { status } = useWorkflowRunStream(projectId);
  const { data, isLoading, isError, error } = useWorkflowActivity(projectId, {
    live: status === "live",
  });
  const now = useSnapshotClock(data?.observedAt);
  const orderedOpenRuns = [...(data?.openRuns ?? [])].sort(
    (left, right) =>
      Number(Boolean(attentionLabel(right, now))) -
      Number(Boolean(attentionLabel(left, now))),
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Live Activity</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Current workflow observations and gate progress. Attention flags
            indicate missing lifecycle transitions, not process liveness.
          </p>
        </div>
        <StreamStatusBadge status={status} />
      </div>

      {isLoading && (
        <div className="rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground">
          Loading live activity...
        </div>
      )}
      {!isLoading && isError && (
        <div className="rounded-lg border border-destructive bg-card p-6 text-sm text-destructive">
          {error instanceof Error ? error.message : "Unable to load activity."}
        </div>
      )}
      {!isLoading && !isError && data && (
        <>
          <section className="space-y-3" aria-labelledby="open-runs-heading">
            <SectionHeading
              id="open-runs-heading"
              title="Open runs"
              detail={`${data.openRunTotal} open run${data.openRunTotal === 1 ? "" : "s"} observed${data.openRunsTruncated ? `; showing the newest ${data.openRuns.length}` : ""}.`}
            />
            {orderedOpenRuns.length === 0 ? (
              <div className="rounded-lg border border-border bg-card p-4 text-sm text-muted-foreground">
                No open workflow runs at this time.
              </div>
            ) : (
              <div className="space-y-3">
                {orderedOpenRuns.map((row) => (
                  <OpenRunCard key={row.run.id} row={row} now={now} />
                ))}
              </div>
            )}
          </section>
          <RecentlyFinished
            runs={data.recentlyFinished}
            projectId={projectId}
          />
        </>
      )}
    </div>
  );
}
