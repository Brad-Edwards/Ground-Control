import { FreshnessBadge } from "@/components/ui/freshness-badge";
import {
  type ControlWorkspaceFilters,
  useControlWorkspace,
} from "@/hooks/use-control-workspace";
import {
  CONTROL_FUNCTIONS,
  type ControlFunction,
  type ControlStatus,
  type OwnerQueue,
  type WorkspaceControl,
} from "@/types/api";
import { useState } from "react";

const CONTROL_STATUSES: ControlStatus[] = [
  "DRAFT",
  "PROPOSED",
  "IMPLEMENTED",
  "OPERATIONAL",
  "DEPRECATED",
  "RETIRED",
];

// ── Owner work queues ─────────────────────────────────────────────────────────

function OwnerQueues({ queues }: { queues: OwnerQueue[] }) {
  if (queues.length === 0) {
    return <p className="text-sm text-muted-foreground">No owners in scope.</p>;
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {queues.map((q) => (
        <div
          key={q.owner}
          className="rounded-lg border border-border bg-card p-3"
        >
          <div className="flex items-center justify-between">
            <span className="font-medium">{q.owner}</span>
            <span className="text-sm text-muted-foreground">
              {q.totalControls} control{q.totalControls === 1 ? "" : "s"}
            </span>
          </div>
          {q.attentionControls > 0 ? (
            <div className="mt-1 text-xs text-red-700">
              {q.attentionControls} need attention:{" "}
              <span className="font-mono">
                {q.attentionControlUids.join(", ")}
              </span>
            </div>
          ) : (
            <div className="mt-1 text-xs text-green-700">All current</div>
          )}
        </div>
      ))}
    </div>
  );
}

// ── Control card ──────────────────────────────────────────────────────────────

function ControlCard({ control }: { control: WorkspaceControl }) {
  return (
    <div
      className={`rounded-lg border bg-card p-4 ${
        control.needsAttention ? "border-red-300" : "border-border"
      }`}
    >
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">{control.uid}</span>
          <span className="ml-2 text-sm text-muted-foreground">
            {control.title}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {control.status}
          </span>
          <FreshnessBadge state={control.staleIndicator} />
          {control.needsAttention && (
            <span
              className="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-800"
              aria-label="Needs attention"
            >
              Attention
            </span>
          )}
        </div>
      </div>

      <div className="mb-2 text-xs text-muted-foreground">
        {control.controlFunction}
        {control.category && ` · ${control.category}`} · Owner:{" "}
        {control.owner || "Unassigned"} · {control.mappingCount} mapping
        {control.mappingCount === 1 ? "" : "s"}
      </div>

      {control.scopedImplementations.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Scoped implementations
          </p>
          <ul className="space-y-0.5">
            {control.scopedImplementations.map((s) => (
              <li key={s.id} className="text-xs">
                <span className="font-mono">{s.uid}</span> — {s.name}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="mt-2 text-xs">
        <span className="font-medium text-muted-foreground">Tests: </span>
        {control.testSummary.total === 0 ? (
          <span className="text-muted-foreground">none</span>
        ) : (
          <span>
            {control.testSummary.total} total · {control.testSummary.effective}{" "}
            effective · {control.testSummary.ineffective} ineffective
            {control.testSummary.latestConclusion &&
              ` · latest ${control.testSummary.latestConclusion}`}
          </span>
        )}
      </div>

      <div className="mt-1 text-xs">
        <span className="font-medium text-muted-foreground">Assessment: </span>
        {control.latestAssessment ? (
          <span>
            design {control.latestAssessment.designEffectiveness} · operating{" "}
            {control.latestAssessment.operatingEffectiveness}
          </span>
        ) : (
          <span className="text-muted-foreground">not assessed</span>
        )}
      </div>

      {control.exceptions.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Exceptions
          </p>
          <ul className="space-y-0.5">
            {control.exceptions.map((e) => (
              <li key={e.id} className="text-xs">
                <span className="font-mono">{e.uid}</span> — {e.title}{" "}
                <span className="text-muted-foreground">
                  ({e.severity} / {e.status})
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

// ── Scope controls ────────────────────────────────────────────────────────────

interface ScopeControlsProps {
  filters: ControlWorkspaceFilters;
  onChange: (f: ControlWorkspaceFilters) => void;
}

function ScopeControls({ filters, onChange }: ScopeControlsProps) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      <div>
        <label className="mb-1 block text-xs font-medium" htmlFor="cw-status">
          Status
        </label>
        <select
          id="cw-status"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.status ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              status: (e.target.value as ControlStatus) || undefined,
            })
          }
        >
          <option value="">All</option>
          {CONTROL_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium" htmlFor="cw-function">
          Function
        </label>
        <select
          id="cw-function"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.controlFunction ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              controlFunction: (e.target.value as ControlFunction) || undefined,
            })
          }
        >
          <option value="">All</option>
          {CONTROL_FUNCTIONS.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium" htmlFor="cw-owner">
          Owner
        </label>
        <input
          id="cw-owner"
          type="text"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.owner ?? ""}
          onChange={(e) =>
            onChange({ ...filters, owner: e.target.value || undefined })
          }
        />
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function ControlWorkspace() {
  const [filters, setFilters] = useState<ControlWorkspaceFilters>({});
  const { data, isLoading, isError, error } = useControlWorkspace(filters);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">
          Control &amp; Assurance Workspace
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Controls with their scoped implementations, control tests,
          effectiveness assessments, observation-backed evidence freshness,
          exceptions, and owner work queues. Filter by status, function, and
          owner.
        </p>
      </div>

      <ScopeControls filters={filters} onChange={setFilters} />

      {isLoading && (
        <div className="flex min-h-[20vh] items-center justify-center text-muted-foreground">
          Loading workspace&hellip;
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to load workspace."}
        </div>
      )}

      {data && (
        <>
          <section aria-labelledby="owner-queues-heading">
            <h2 id="owner-queues-heading" className="mb-2 text-lg font-medium">
              Owner Work Queues
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.ownerQueueCount})
              </span>
            </h2>
            <OwnerQueues queues={data.ownerQueues} />
          </section>

          <section aria-labelledby="controls-heading">
            <h2 id="controls-heading" className="mb-2 text-lg font-medium">
              Controls
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.controlCount})
              </span>
            </h2>
            {data.controls.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No controls match the current filters.
              </p>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.controls.map((control) => (
                  <ControlCard key={control.id} control={control} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
