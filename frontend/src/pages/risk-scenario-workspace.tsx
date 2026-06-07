import {
  type RiskScenarioWorkspaceFilters,
  useRiskScenarioWorkspace,
} from "@/hooks/use-risk-scenario-workspace";
import type {
  RiskScenarioStatus,
  ScenarioReviewState,
  WorkspaceAsset,
  WorkspaceScenario,
} from "@/types/api";
import { useState } from "react";

// ── Review badge ─────────────────────────────────────────────────────────────

function ReviewBadge({ state }: { state: ScenarioReviewState }) {
  const styleMap: Record<ScenarioReviewState, string> = {
    REASSESSMENT_REQUIRED: "bg-red-100 text-red-800",
    REVIEW_DUE: "bg-orange-100 text-orange-800",
    EVIDENCE_STALE: "bg-yellow-100 text-yellow-800",
    CURRENT: "bg-green-100 text-green-800",
    NO_SIGNAL: "bg-slate-100 text-slate-600",
  };
  const labelMap: Record<ScenarioReviewState, string> = {
    REASSESSMENT_REQUIRED: "Reassessment required",
    REVIEW_DUE: "Review due",
    EVIDENCE_STALE: "Evidence stale",
    CURRENT: "Current",
    NO_SIGNAL: "No signal",
  };
  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${styleMap[state]}`}
      aria-label={`Review indicator: ${state}`}
    >
      {labelMap[state]}
    </span>
  );
}

// ── Asset row ─────────────────────────────────────────────────────────────────

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

// ── Scenario card ─────────────────────────────────────────────────────────────

function LinkList({
  heading,
  links,
}: {
  heading: string;
  links: {
    targetEntityId: string | null;
    targetIdentifier: string | null;
    targetTitle: string | null;
    targetUrl: string | null;
  }[];
}) {
  if (links.length === 0) return null;
  return (
    <div className="mt-2">
      <p className="mb-1 text-xs font-medium text-muted-foreground">
        {heading}
      </p>
      <ul className="space-y-0.5">
        {links.map((link, i) => (
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
  );
}

function ScenarioCard({
  scenario,
  assetMap,
  selected,
  onToggleSelect,
}: {
  scenario: WorkspaceScenario;
  assetMap: Map<string, WorkspaceAsset>;
  selected?: boolean;
  onToggleSelect?: (id: string) => void;
}) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div className="flex items-start gap-2">
          {onToggleSelect && (
            <input
              type="checkbox"
              className="mt-0.5"
              checked={selected ?? false}
              onChange={() => onToggleSelect(scenario.id)}
              aria-label={`Select ${scenario.uid} for comparison`}
            />
          )}
          <div>
            <span className="font-mono text-sm font-semibold">
              {scenario.uid}
            </span>
            <span className="ml-2 text-sm text-muted-foreground">
              {scenario.title}
            </span>
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {scenario.status}
          </span>
          <ReviewBadge state={scenario.reviewIndicator} />
        </div>
      </div>

      <p className="mb-2 text-xs italic text-muted-foreground">
        {scenario.fairSentence}
      </p>

      {scenario.linkedAssetIds.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Linked assets
          </p>
          <ul className="space-y-0.5">
            {scenario.linkedAssetIds.map((id) => {
              const a = assetMap.get(id);
              return (
                <li key={id} className="font-mono text-xs">
                  {a ? `${a.uid} — ${a.name}` : id}
                </li>
              );
            })}
          </ul>
        </div>
      )}

      <LinkList heading="Linked controls" links={scenario.linkedControls} />
      <LinkList heading="Linked findings" links={scenario.linkedFindings} />
      <LinkList heading="Linked evidence" links={scenario.linkedEvidence} />
      <LinkList
        heading="Linked requirements"
        links={scenario.linkedRequirements}
      />

      {scenario.assessments.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Assessments
          </p>
          <ul className="space-y-0.5">
            {scenario.assessments.map((a) => (
              <li key={a.id} className="text-xs">
                <span className="font-medium">
                  {a.methodologyProfileName ?? "Unknown"}
                </span>
                {" — "}
                <span className="text-muted-foreground">{a.approvalState}</span>
                {a.reassessmentRequiredAt && (
                  <span className="ml-1 rounded bg-red-100 px-1 text-red-700">
                    reassessment required
                  </span>
                )}
                {a.hasComputedOutputs && (
                  <span className="ml-1 rounded bg-blue-100 px-1 text-blue-700">
                    has outputs
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {scenario.treatments.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Treatments
          </p>
          <ul className="space-y-0.5">
            {scenario.treatments.map((t) => (
              <li key={t.id} className="text-xs">
                <span className="font-mono">{t.uid}</span>
                {" — "}
                <span>{t.title}</span>{" "}
                <span className="text-muted-foreground">
                  ({t.strategy} / {t.status})
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {scenario.registerRecords.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Risk register
          </p>
          <ul className="space-y-0.5">
            {scenario.registerRecords.map((r) => (
              <li key={r.id} className="text-xs">
                <span className="font-mono">{r.uid}</span>
                {" — "}
                <span>{r.title}</span>{" "}
                <span className="text-muted-foreground">({r.status})</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

// ── Comparison view ───────────────────────────────────────────────────────────

function ComparisonView({
  scenarios,
  assetMap,
}: {
  scenarios: WorkspaceScenario[];
  assetMap: Map<string, WorkspaceAsset>;
}) {
  return (
    <div
      data-testid="comparison-view"
      className="grid gap-4"
      style={{
        gridTemplateColumns: `repeat(${Math.min(scenarios.length, 3)}, 1fr)`,
      }}
    >
      {scenarios.map((s) => (
        <ScenarioCard key={s.id} scenario={s} assetMap={assetMap} />
      ))}
    </div>
  );
}

// ── Scope controls ────────────────────────────────────────────────────────────

const STATUS_VALUES: RiskScenarioStatus[] = ["DRAFT", "ACTIVE", "ARCHIVED"];

interface ScopeControlsProps {
  filters: RiskScenarioWorkspaceFilters;
  onChange: (f: RiskScenarioWorkspaceFilters) => void;
}

function ScopeControls({ filters, onChange }: ScopeControlsProps) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      <div>
        <label className="mb-1 block text-xs font-medium">Status</label>
        <select
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.status ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              status: (e.target.value as RiskScenarioStatus) || undefined,
            })
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
            onChange({
              ...filters,
              asOf: e.target.value ? `${e.target.value}:00Z` : undefined,
            })
          }
        />
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function RiskScenarioWorkspace() {
  const [filters, setFilters] = useState<RiskScenarioWorkspaceFilters>({});
  // Selection is staged locally and only promoted to the `compare` query param on
  // an explicit action. Sending `compare` for a single id would make the backend
  // collapse the list to that one scenario, making a second selection impossible.
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const { data, isLoading, isError, error } = useRiskScenarioWorkspace(filters);

  const assetMap = new Map<string, WorkspaceAsset>(
    (data?.assets ?? []).map((a) => [a.id, a]),
  );

  const compareMode = (filters.compare?.length ?? 0) >= 2;

  function toggleSelect(id: string) {
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }

  function startCompare() {
    setFilters((f) => ({ ...f, compare: selectedIds }));
  }

  function exitCompare() {
    setFilters((f) => ({ ...f, compare: undefined }));
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Risk Scenario Workspace</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Scoped risk scenarios with linked assets, assessments, controls,
          findings, treatments, and supporting evidence. Select two or more
          scenarios to compare them side-by-side.
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
          {/* Scoped assets */}
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

          {/* Risk scenarios */}
          <section aria-labelledby="scenarios-heading">
            <div className="mb-2 flex items-center justify-between gap-2">
              <h2 id="scenarios-heading" className="text-lg font-medium">
                Risk Scenarios
                <span className="ml-2 text-sm font-normal text-muted-foreground">
                  ({data.scenarioCount})
                </span>
              </h2>
              {compareMode ? (
                <button
                  type="button"
                  onClick={exitCompare}
                  className="rounded border border-border px-2 py-1 text-xs hover:bg-muted"
                >
                  Exit comparison
                </button>
              ) : (
                <button
                  type="button"
                  onClick={startCompare}
                  disabled={selectedIds.length < 2}
                  className="rounded border border-border px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
                >
                  Compare selected ({selectedIds.length})
                </button>
              )}
            </div>
            {data.scenarios.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No risk scenarios match the current filters.
              </p>
            ) : compareMode ? (
              <ComparisonView scenarios={data.scenarios} assetMap={assetMap} />
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.scenarios.map((scenario) => (
                  <ScenarioCard
                    key={scenario.id}
                    scenario={scenario}
                    assetMap={assetMap}
                    selected={selectedIds.includes(scenario.id)}
                    onToggleSelect={toggleSelect}
                  />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
