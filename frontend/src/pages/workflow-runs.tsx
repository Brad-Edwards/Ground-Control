import { useWorkflowRunStream } from "@/hooks/use-workflow-run-stream";
import {
  type WorkflowRunFilters,
  useWorkflowRunAggregate,
  useWorkflowRuns,
} from "@/hooks/use-workflow-runs";
import { useState } from "react";
import { useParams } from "react-router";
import {
  CycleTimeSection,
  FiltersPanel,
  ThroughputSection,
} from "./workflow-runs/format-enum";
import {
  CostProxiesSection,
  PhaseHotspotsSection,
  RunRecordsSection,
  StreamStatusBadge,
} from "./workflow-runs/phase-hotspots-section";

// ---------------------------------------------------------------------------
// Root page component
// ---------------------------------------------------------------------------

export function WorkflowRuns() {
  const { projectId = "" } = useParams<{ projectId: string }>();
  const [filters, setFilters] = useState<WorkflowRunFilters>({});

  // The stream drives the fetch hooks rather than the reverse: while push is connected they stop
  // polling, and they re-arm the moment it drops (issue #1436).
  const { status: streamStatus } = useWorkflowRunStream(projectId);
  const live = streamStatus === "live";

  const {
    data: aggregate,
    isLoading: aggLoading,
    isError: aggError,
    error: aggErr,
  } = useWorkflowRunAggregate(projectId, filters, { live });

  const {
    data: runs = [],
    isLoading: runsLoading,
    isError: runsError,
  } = useWorkflowRuns(projectId, { live });

  const isLoading = aggLoading || runsLoading;
  const isError = aggError || runsError;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Workflow Runs</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Throughput, cycle time, cost economics, and historical records for
            AI-driven workflow executions.
          </p>
        </div>
        <StreamStatusBadge status={streamStatus} />
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

          <RunRecordsSection runs={runs} />
        </>
      )}
    </div>
  );
}
