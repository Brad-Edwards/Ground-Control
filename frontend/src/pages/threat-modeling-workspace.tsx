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

function StaleBadge({ state }: { state: FreshnessState }) {
  const styleMap: Record<FreshnessState, string> = {
    FRESH: "bg-green-100 text-green-800",
    STALE: "bg-yellow-100 text-yellow-800",
    EXPIRED: "bg-red-100 text-red-800",
    SUPERSEDED: "bg-gray-100 text-gray-700",
    NO_OBSERVATIONS: "bg-slate-100 text-slate-600",
  };
  const labelMap: Record<FreshnessState, string> = {
    FRESH: "Fresh",
    STALE: "Stale",
    EXPIRED: "Expired",
    SUPERSEDED: "Superseded",
    NO_OBSERVATIONS: "No evidence",
  };
  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${styleMap[state]}`}
      aria-label={`Evidence freshness: ${state}`}
    >
      {labelMap[state]}
    </span>
  );
}

// ── Asset row ────────────────────────────────────────────────────────────────

function AssetRow({ asset }: { asset: WorkspaceAsset }) {
  return (
    <tr className="border-b border-border last:border-0">
      <td className="py-2 pr-4 font-mono text-sm">{asset.uid}</td>
      <td className="py-2 pr-4 text-sm">{asset.name}</td>
      <td className="py-2 pr-4 text-xs text-muted-foreground">
        {asset.assetType}
      </td>
      <td className="py-2 text-xs">
        {asset.boundary && (
          <span className="rounded bg-blue-100 px-1.5 py-0.5 text-blue-800">
            Boundary
          </span>
        )}
      </td>
    </tr>
  );
}

// ── Flow row ─────────────────────────────────────────────────────────────────

function FlowRow({
  flow,
  assetMap,
}: {
  flow: WorkspaceFlow;
  assetMap: Map<string, WorkspaceAsset>;
}) {
  const src = assetMap.get(flow.sourceAssetId);
  const tgt = assetMap.get(flow.targetAssetId);
  return (
    <tr className="border-b border-border last:border-0">
      <td className="py-2 pr-4 font-mono text-sm">
        {src?.uid ?? flow.sourceAssetId}
      </td>
      <td className="py-2 pr-4 text-xs text-muted-foreground">
        {flow.relationType.replace(/_/g, " ")}
      </td>
      <td className="py-2 font-mono text-sm">
        {tgt?.uid ?? flow.targetAssetId}
      </td>
    </tr>
  );
}

// ── Threat entry card ────────────────────────────────────────────────────────

function ThreatEntryCard({ entry }: { entry: WorkspaceThreatEntry }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">{entry.uid}</span>
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
              {entry.stride.replace(/_/g, " ")}
            </span>
          )}
          <StaleBadge state={entry.staleIndicator} />
        </div>
      </div>

      {entry.linkedControls.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Linked controls
          </p>
          <ul className="space-y-0.5">
            {entry.linkedControls.map((link, i) => (
              <li key={link.targetEntityId ?? i} className="text-xs">
                {link.targetUrl ? (
                  <a
                    href={link.targetUrl}
                    className="text-primary underline"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {link.targetTitle ??
                      link.targetIdentifier ??
                      link.targetEntityId}
                  </a>
                ) : (
                  <span>
                    {link.targetTitle ??
                      link.targetIdentifier ??
                      link.targetEntityId}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {entry.linkedRequirements.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Linked requirements
          </p>
          <ul className="space-y-0.5">
            {entry.linkedRequirements.map((link, i) => (
              <li key={link.targetEntityId ?? i} className="text-xs">
                {link.targetUrl ? (
                  <a
                    href={link.targetUrl}
                    className="text-primary underline"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {link.targetTitle ??
                      link.targetIdentifier ??
                      link.targetEntityId}
                  </a>
                ) : (
                  <span>
                    {link.targetTitle ??
                      link.targetIdentifier ??
                      link.targetEntityId}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

// ── Scope controls ───────────────────────────────────────────────────────────

interface ScopeControlsProps {
  filters: ThreatModelWorkspaceFilters;
  onChange: (f: ThreatModelWorkspaceFilters) => void;
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

function withOptionalThreatFilter<K extends keyof ThreatModelWorkspaceFilters>(
  filters: ThreatModelWorkspaceFilters,
  key: K,
  value: ThreatModelWorkspaceFilters[K] | undefined,
): ThreatModelWorkspaceFilters {
  const next = { ...filters };
  if (value === undefined) {
    delete next[key];
  } else {
    Object.assign(next, { [key]: value });
  }
  return next;
}

function ScopeControls({ filters, onChange }: ScopeControlsProps) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      <div>
        <label className="mb-1 block text-xs font-medium">STRIDE</label>
        <select
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.stride ?? ""}
          onChange={(e) =>
            onChange(
              withOptionalThreatFilter(
                filters,
                "stride",
                (e.target.value as StrideCategory) || undefined,
              ),
            )
          }
        >
          <option value="">All</option>
          {STRIDE_VALUES.map((s) => (
            <option key={s} value={s}>
              {s.replace(/_/g, " ")}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="mb-1 block text-xs font-medium">Status</label>
        <select
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.status ?? ""}
          onChange={(e) =>
            onChange(
              withOptionalThreatFilter(
                filters,
                "status",
                (e.target.value as ThreatModelStatus) || undefined,
              ),
            )
          }
        >
          <option value="">All</option>
          {STATUS_VALUES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="mb-1 block text-xs font-medium">
          As of (ISO date)
        </label>
        <input
          type="datetime-local"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.asOf?.slice(0, 16) ?? ""}
          onChange={(e) =>
            onChange(
              withOptionalThreatFilter(
                filters,
                "asOf",
                e.target.value ? `${e.target.value}:00Z` : undefined,
              ),
            )
          }
        />
      </div>
    </div>
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
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Threat Modeling Workspace</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Scoped operational assets, trust boundaries, data flows, and threat
          entries with staleness indicators.
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
          {/* Assets */}
          <section aria-labelledby="assets-heading">
            <h2 id="assets-heading" className="mb-2 text-lg font-medium">
              Assets
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.assetCount})
              </span>
            </h2>
            {data.assets.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No assets in scope.
              </p>
            ) : (
              <div className="overflow-auto rounded-lg border border-border">
                <table className="w-full text-left">
                  <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2">UID</th>
                      <th className="px-3 py-2">Name</th>
                      <th className="px-3 py-2">Type</th>
                      <th className="px-3 py-2">Flags</th>
                    </tr>
                  </thead>
                  <tbody className="px-3">
                    {data.assets.map((asset) => (
                      <AssetRow key={asset.id} asset={asset} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Flows */}
          <section aria-labelledby="flows-heading">
            <h2 id="flows-heading" className="mb-2 text-lg font-medium">
              Flows
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
              Threat Entries
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
    </div>
  );
}
