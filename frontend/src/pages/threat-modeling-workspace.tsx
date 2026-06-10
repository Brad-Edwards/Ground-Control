import {
  AsOfDateControl,
  AssetsSection,
  IndicatorBadge,
  ScopeControlsShell,
  WorkspaceLinkList,
  WorkspaceShell,
  WorkspaceStatusSelect,
} from "@/components/workspace-shared";
import {
  type ThreatModelWorkspaceFilters,
  useThreatModelWorkspace,
} from "@/hooks/use-threat-model-workspace";
import type {
  FreshnessState,
  StrideCategory,
  ThreatModelStatus,
  WorkspaceAsset,
  WorkspaceFlow,
  WorkspaceThreatEntry,
} from "@/types/api";
import { useState } from "react";

// ── Staleness badge ──────────────────────────────────────────────────────────

const FRESHNESS_STYLE_MAP: Record<FreshnessState, string> = {
  FRESH: "bg-green-100 text-green-800",
  STALE: "bg-yellow-100 text-yellow-800",
  EXPIRED: "bg-red-100 text-red-800",
  SUPERSEDED: "bg-gray-100 text-gray-700",
  NO_OBSERVATIONS: "bg-slate-100 text-slate-600",
};

const FRESHNESS_LABEL_MAP: Record<FreshnessState, string> = {
  FRESH: "Fresh",
  STALE: "Stale",
  EXPIRED: "Expired",
  SUPERSEDED: "Superseded",
  NO_OBSERVATIONS: "No evidence",
};

function StaleBadge({ state }: Readonly<{ state: FreshnessState }>) {
  return (
    <IndicatorBadge
      state={state}
      styleMap={FRESHNESS_STYLE_MAP}
      labelMap={FRESHNESS_LABEL_MAP}
      ariaPrefix="Evidence freshness"
    />
  );
}

// ── Flow row ─────────────────────────────────────────────────────────────────

function FlowRow({
  flow,
  assetMap,
}: Readonly<{
  flow: WorkspaceFlow;
  assetMap: Map<string, WorkspaceAsset>;
}>) {
  const src = assetMap.get(flow.sourceAssetId);
  const tgt = assetMap.get(flow.targetAssetId);
  return (
    <tr className="border-b border-border last:border-0">
      <td className="py-2 pr-4 font-mono text-sm">
        {src?.uid ?? flow.sourceAssetId}
      </td>
      <td className="py-2 pr-4 text-xs text-muted-foreground">
        {flow.relationType.replaceAll("_", " ")}
      </td>
      <td className="py-2 font-mono text-sm">
        {tgt?.uid ?? flow.targetAssetId}
      </td>
    </tr>
  );
}

// ── Threat entry card ────────────────────────────────────────────────────────

function ThreatEntryCard({ entry }: Readonly<{ entry: WorkspaceThreatEntry }>) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">{entry.uid}</span>
          {/* no rendered space: visual gap is provided by ml-2 */}
          <span className="ml-2 text-sm text-muted-foreground">
            {entry.title}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {entry.status}
          </span>
          {entry.stride && (
            <span className="rounded bg-purple-100 px-1.5 py-0.5 text-xs text-purple-800">
              {entry.stride.replaceAll("_", " ")}
            </span>
          )}
          <StaleBadge state={entry.staleIndicator} />
        </div>
      </div>

      <WorkspaceLinkList
        heading="Linked controls"
        links={entry.linkedControls}
      />
      <WorkspaceLinkList
        heading="Linked requirements"
        links={entry.linkedRequirements}
      />
    </div>
  );
}

// ── Scope controls ───────────────────────────────────────────────────────────

interface ScopeControlsProps {
  readonly filters: ThreatModelWorkspaceFilters;
  readonly onChange: (f: ThreatModelWorkspaceFilters) => void;
}

const STRIDE_VALUES: StrideCategory[] = [
  "SPOOFING",
  "TAMPERING",
  "REPUDIATION",
  "INFORMATION_DISCLOSURE",
  "DENIAL_OF_SERVICE",
  "ELEVATION_OF_PRIVILEGE",
];

const STATUS_VALUES: ThreatModelStatus[] = ["DRAFT", "ACTIVE", "ARCHIVED"];

function ScopeControls({ filters, onChange }: ScopeControlsProps) {
  return (
    <ScopeControlsShell>
      <div>
        <label
          htmlFor="stride-filter"
          className="mb-1 block text-xs font-medium"
        >
          STRIDE
        </label>
        <select
          id="stride-filter"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.stride ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              stride: (e.target.value as StrideCategory) || undefined,
            })
          }
        >
          <option value="">All</option>
          {STRIDE_VALUES.map((s) => (
            <option key={s} value={s}>
              {s.replaceAll("_", " ")}
            </option>
          ))}
        </select>
      </div>

      <WorkspaceStatusSelect
        value={filters.status}
        options={STATUS_VALUES}
        onChange={(status) => onChange({ ...filters, status })}
      />

      <AsOfDateControl
        value={filters.asOf}
        onChange={(asOf) => onChange({ ...filters, asOf })}
      />
    </ScopeControlsShell>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export function ThreatModelingWorkspace() {
  const [filters, setFilters] = useState<ThreatModelWorkspaceFilters>({});
  const { data, isLoading, isError, error } = useThreatModelWorkspace(filters);

  const assetMap = new Map<string, WorkspaceAsset>(
    (data?.assets ?? []).map((a) => [a.id, a]),
  );

  return (
    <WorkspaceShell
      title="Threat Modeling Workspace"
      description="Scoped operational assets, trust boundaries, data flows, and threat entries with staleness indicators."
      controls={<ScopeControls filters={filters} onChange={setFilters} />}
      isLoading={isLoading}
      isError={isError}
      error={error}
      hasData={!!data}
    >
      {data && (
        <>
          {/* Assets */}
          <AssetsSection assets={data.assets} count={data.assetCount} />

          {/* Flows */}
          <section aria-labelledby="flows-heading">
            <h2 id="flows-heading" className="mb-2 text-lg font-medium">
              <span>Flows</span>
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.flowCount})
              </span>
            </h2>
            {data.flows.length === 0 ? (
              <p className="text-sm text-muted-foreground">No active flows.</p>
            ) : (
              <div className="overflow-auto rounded-lg border border-border">
                <table className="w-full text-left">
                  <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2">Source</th>
                      <th className="px-3 py-2">Relation</th>
                      <th className="px-3 py-2">Target</th>
                    </tr>
                  </thead>
                  <tbody className="px-3">
                    {data.flows.map((flow) => (
                      <FlowRow key={flow.id} flow={flow} assetMap={assetMap} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Threat entries */}
          <section aria-labelledby="entries-heading">
            <h2 id="entries-heading" className="mb-2 text-lg font-medium">
              <span>Threat Entries</span>
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.entryCount})
              </span>
            </h2>
            {data.entries.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No threat entries match the current filters.
              </p>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.entries.map((entry) => (
                  <ThreatEntryCard key={entry.id} entry={entry} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </WorkspaceShell>
  );
}
