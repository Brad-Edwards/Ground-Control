import {
  AsOfDateControl,
  AssetsSection,
  IndicatorBadge,
  ScopeControlsShell,
  WorkspaceLinkList,
  WorkspaceShell,
} from "@/components/workspace-shared";
import {
  type EvidenceStateWorkspaceFilters,
  useEvidenceStateWorkspace,
} from "@/hooks/use-evidence-state-workspace";
import type {
  EvidenceStateArtifact,
  EvidenceStateObservation,
  FreshnessState,
} from "@/types/api";
import { useState } from "react";

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
  NO_OBSERVATIONS: "No observations",
};

function FreshnessBadge({ state }: Readonly<{ state: FreshnessState }>) {
  return (
    <IndicatorBadge
      state={state}
      styleMap={FRESHNESS_STYLE_MAP}
      labelMap={FRESHNESS_LABEL_MAP}
      ariaPrefix="Evidence freshness"
    />
  );
}

function MetadataRow({
  label,
  value,
}: Readonly<{ label: string; value: string | number | null | undefined }>) {
  if (value == null || value === "") {
    return null;
  }
  return (
    <div>
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="break-words text-sm">{value}</dd>
    </div>
  );
}

function ArtifactCard({
  artifact,
}: Readonly<{ artifact: EvidenceStateArtifact }>) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">
            {artifact.uid}
          </span>
          <span className="ml-2 text-sm text-muted-foreground">
            {artifact.title}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {artifact.evidenceType}
          </span>
          <FreshnessBadge state={artifact.freshnessState} />
        </div>
      </div>

      <p className="mb-3 text-sm text-muted-foreground">
        {artifact.summaryPreview}
      </p>

      <dl className="grid gap-2 md:grid-cols-3">
        <MetadataRow label="Derived at" value={artifact.derivedAt} />
        <MetadataRow label="Derived by" value={artifact.derivedBy} />
        <MetadataRow label="Confidence" value={artifact.confidence} />
        <MetadataRow label="Assurance" value={artifact.assuranceLevel} />
        <MetadataRow label="Age" value={`${artifact.ageDays} days`} />
      </dl>

      {artifact.sources.length > 0 && (
        <div className="mt-3">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Provenance
          </p>
          <ul className="space-y-0.5">
            {artifact.sources.map((source, index) => (
              <li
                key={`${source.sourceKind}-${source.sourceEntityId ?? source.sourceIdentifier ?? index}`}
                className="text-xs"
              >
                <span className="rounded bg-muted px-1.5 py-0.5 font-mono">
                  {source.sourceKind}
                </span>
                <span className="ml-2">
                  {source.label ?? source.sourceIdentifier ?? "Unlabelled"}
                </span>
                {source.role && (
                  <span className="ml-1 text-muted-foreground">
                    ({source.role})
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      <WorkspaceLinkList
        heading="Affected assets"
        links={artifact.affectedAssets}
      />
      <WorkspaceLinkList
        heading="Linked controls"
        links={artifact.linkedControls}
      />
      <WorkspaceLinkList
        heading="Downstream assessments"
        links={artifact.downstreamAssessments}
      />
      <WorkspaceLinkList
        heading="Linked findings"
        links={artifact.linkedFindings}
      />
    </div>
  );
}

function ObservationCard({
  observation,
}: Readonly<{ observation: EvidenceStateObservation }>) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">
            {observation.assetUid}
          </span>
          <span className="ml-2 text-sm text-muted-foreground">
            {observation.observationKey}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {observation.category}
          </span>
          <FreshnessBadge state={observation.freshnessState} />
        </div>
      </div>

      <p className="mb-3 text-sm text-muted-foreground">
        {observation.valuePreview}
      </p>

      <dl className="grid gap-2 md:grid-cols-3">
        <MetadataRow label="Observed at" value={observation.observedAt} />
        <MetadataRow label="Expires at" value={observation.expiresAt} />
        <MetadataRow label="Source" value={observation.source} />
        <MetadataRow label="Evidence ref" value={observation.evidenceRef} />
        <MetadataRow label="Confidence" value={observation.confidence} />
        <MetadataRow label="Age" value={`${observation.ageDays} days`} />
      </dl>

      <WorkspaceLinkList
        heading="Evidence artifacts"
        links={observation.evidenceArtifacts}
      />
      <WorkspaceLinkList
        heading="Downstream assessments"
        links={observation.downstreamAssessments}
      />
      <WorkspaceLinkList
        heading="Linked findings"
        links={observation.linkedFindings}
      />
    </div>
  );
}

function ScopeControls({
  filters,
  onChange,
}: Readonly<{
  filters: EvidenceStateWorkspaceFilters;
  onChange: (next: EvidenceStateWorkspaceFilters) => void;
}>) {
  return (
    <ScopeControlsShell>
      <AsOfDateControl
        value={filters.asOf}
        onChange={(asOf) => onChange({ ...filters, asOf })}
      />
      <div>
        <label
          htmlFor="freshness-window"
          className="mb-1 block text-xs font-medium"
        >
          Freshness window
        </label>
        <input
          id="freshness-window"
          type="number"
          min={1}
          className="w-28 rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.freshnessWindowDays ?? 90}
          onChange={(event) =>
            onChange({
              ...filters,
              freshnessWindowDays: Number(event.target.value) || undefined,
            })
          }
        />
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={filters.includeSuperseded ?? false}
          onChange={(event) =>
            onChange({ ...filters, includeSuperseded: event.target.checked })
          }
        />
        Include superseded
      </label>
    </ScopeControlsShell>
  );
}

export function EvidenceStateExplorer() {
  const [filters, setFilters] = useState<EvidenceStateWorkspaceFilters>({});
  const { data, isLoading, isError, error } =
    useEvidenceStateWorkspace(filters);

  return (
    <WorkspaceShell
      title="Evidence and State Explorer"
      description="Project-scoped observations, evidence artifacts, freshness, provenance, and downstream impact."
      controls={<ScopeControls filters={filters} onChange={setFilters} />}
      isLoading={isLoading}
      isError={isError}
      error={error}
      hasData={!!data}
    >
      {data && (
        <>
          <div className="grid gap-3 md:grid-cols-5">
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Fresh</p>
              <p className="text-xl font-semibold">{data.counts.fresh}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Stale</p>
              <p className="text-xl font-semibold">{data.counts.stale}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Expired</p>
              <p className="text-xl font-semibold">{data.counts.expired}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Superseded</p>
              <p className="text-xl font-semibold">{data.counts.superseded}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Currently valid</p>
              <p className="text-xl font-semibold">
                {data.counts.currentlyValid}
              </p>
            </div>
          </div>

          <AssetsSection assets={data.assets} count={data.assetCount} />

          <section aria-labelledby="evidence-heading">
            <h2 id="evidence-heading" className="mb-2 text-lg font-medium">
              Evidence Artifacts
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.artifactCount})
              </span>
            </h2>
            {data.evidenceArtifacts.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No evidence artifacts match the current filters.
              </p>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.evidenceArtifacts.map((artifact) => (
                  <ArtifactCard key={artifact.id} artifact={artifact} />
                ))}
              </div>
            )}
          </section>

          <section aria-labelledby="observations-heading">
            <h2 id="observations-heading" className="mb-2 text-lg font-medium">
              Observations
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.observationCount})
              </span>
            </h2>
            {data.observations.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No observations match the current filters.
              </p>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.observations.map((observation) => (
                  <ObservationCard
                    key={observation.id}
                    observation={observation}
                  />
                ))}
              </div>
            )}
          </section>

          {data.limitations.length > 0 && (
            <section aria-labelledby="limitations-heading">
              <h2 id="limitations-heading" className="mb-2 text-lg font-medium">
                Limitations
              </h2>
              <ul className="space-y-1 text-sm text-muted-foreground">
                {data.limitations.map((limitation) => (
                  <li key={limitation}>{limitation}</li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </WorkspaceShell>
  );
}
