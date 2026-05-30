import { FreshnessBadge } from "@/components/ui/freshness-badge";
import {
  type EvidenceExplorerFilters,
  useEvidenceExplorer,
} from "@/hooks/use-evidence-explorer";
import type {
  EvidenceFreshnessCounts,
  EvidenceType,
  ExplorerArtifact,
  ExplorerFindingRef,
  ExplorerObservation,
} from "@/types/api";
import { useState } from "react";

const EVIDENCE_TYPES: EvidenceType[] = [
  "OBSERVATION_SUMMARY",
  "CONTROL_TEST_SUMMARY",
  "ASSURANCE_CONCLUSION",
  "VERIFICATION_SUMMARY",
  "ATTESTATION",
  "MIXED",
];

// ── Freshness counts ──────────────────────────────────────────────────────────

function CountsBar({ counts }: { counts: EvidenceFreshnessCounts }) {
  const cells: { label: string; value: number }[] = [
    { label: "Fresh", value: counts.fresh },
    { label: "Stale", value: counts.stale },
    { label: "Expired", value: counts.expired },
    { label: "Superseded", value: counts.superseded },
    { label: "Currently valid", value: counts.currentlyValid },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
      {cells.map((c) => (
        <div
          key={c.label}
          className="rounded-lg border border-border bg-card p-3 text-center"
        >
          <div className="text-2xl font-semibold">{c.value}</div>
          <div className="text-xs text-muted-foreground">{c.label}</div>
        </div>
      ))}
    </div>
  );
}

// ── Downstream findings ─────────────────────────────────────────────────────────

function DownstreamFindings({ findings }: { findings: ExplorerFindingRef[] }) {
  if (findings.length === 0) return null;
  return (
    <div className="mt-1">
      <span className="text-xs font-medium text-muted-foreground">
        Downstream findings:{" "}
      </span>
      {findings.map((f, i) => (
        <span key={f.id} className="text-xs">
          {i > 0 && ", "}
          <span className="font-mono">{f.uid}</span> ({f.severity}/{f.status})
        </span>
      ))}
    </div>
  );
}

// ── Artifact card ───────────────────────────────────────────────────────────────

function ArtifactCard({ artifact }: { artifact: ExplorerArtifact }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-1 flex items-start justify-between gap-2">
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
      <div className="text-xs text-muted-foreground">
        {artifact.derivationMethod} · {artifact.assuranceLevel} · derived by{" "}
        {artifact.derivedBy || "unknown"} · {artifact.ageDays}d old
        {artifact.supersededByArtifactId && " · superseded"}
      </div>
      {artifact.sources.length > 0 && (
        <div className="mt-2">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Provenance
          </p>
          <ul className="space-y-0.5">
            {artifact.sources.map((s, i) => (
              <li
                key={s.sourceEntityId ?? s.sourceIdentifier ?? i}
                className="text-xs"
              >
                <span className="font-mono">{s.sourceKind}</span>
                {s.role && ` (${s.role})`}
                {s.sourceIdentifier && ` — ${s.sourceIdentifier}`}
              </li>
            ))}
          </ul>
        </div>
      )}
      <DownstreamFindings findings={artifact.downstreamFindings} />
    </div>
  );
}

// ── Observation row ──────────────────────────────────────────────────────────────

function ObservationCard({
  observation,
}: {
  observation: ExplorerObservation;
}) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-1 flex items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">
            {observation.observationKey}
          </span>
          {observation.observationValue && (
            <span className="ml-2 text-sm">
              = {observation.observationValue}
            </span>
          )}
        </div>
        <FreshnessBadge state={observation.freshnessState} />
      </div>
      <div className="text-xs text-muted-foreground">
        Asset <span className="font-mono">{observation.assetUid}</span>
        {observation.category && ` · ${observation.category}`}
        {observation.source && ` · source ${observation.source}`} ·{" "}
        {observation.ageDays}d old
      </div>
      {observation.evidenceRef && (
        <div className="mt-1 text-xs">
          <a
            href={observation.evidenceRef}
            className="text-primary underline"
            target="_blank"
            rel="noreferrer"
          >
            Evidence reference
          </a>
        </div>
      )}
      <DownstreamFindings findings={observation.downstreamFindings} />
    </div>
  );
}

// ── Scope controls ────────────────────────────────────────────────────────────

interface ScopeControlsProps {
  filters: EvidenceExplorerFilters;
  onChange: (f: EvidenceExplorerFilters) => void;
}

function ScopeControls({ filters, onChange }: ScopeControlsProps) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      <div>
        <label className="mb-1 block text-xs font-medium" htmlFor="ee-type">
          Evidence type
        </label>
        <select
          id="ee-type"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.evidenceType ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              evidenceType: (e.target.value as EvidenceType) || undefined,
            })
          }
        >
          <option value="">All</option>
          {EVIDENCE_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className="mb-1 flex items-center gap-1 text-xs font-medium">
          <input
            type="checkbox"
            checked={filters.includeSuperseded ?? true}
            onChange={(e) =>
              onChange({ ...filters, includeSuperseded: e.target.checked })
            }
          />
          Include superseded
        </label>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function EvidenceExplorer() {
  const [filters, setFilters] = useState<EvidenceExplorerFilters>({});
  const { data, isLoading, isError, error } = useEvidenceExplorer(filters);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">
          Evidence &amp; State Explorer
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Evidence artifacts and observations with freshness, provenance,
          affected assets, and downstream finding impact. Filter by evidence
          type and supersession.
        </p>
      </div>

      <ScopeControls filters={filters} onChange={setFilters} />

      {isLoading && (
        <div className="flex min-h-[20vh] items-center justify-center text-muted-foreground">
          Loading explorer&hellip;
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to load explorer."}
        </div>
      )}

      {data && (
        <>
          <section aria-labelledby="freshness-heading">
            <h2 id="freshness-heading" className="mb-2 text-lg font-medium">
              Evidence Freshness
            </h2>
            <CountsBar counts={data.counts} />
          </section>

          <section aria-labelledby="artifacts-heading">
            <h2 id="artifacts-heading" className="mb-2 text-lg font-medium">
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
                {data.evidenceArtifacts.map((a) => (
                  <ArtifactCard key={a.id} artifact={a} />
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
                No observations in scope.
              </p>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.observations.map((o) => (
                  <ObservationCard key={o.id} observation={o} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
