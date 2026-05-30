import {
  type TraceabilityMatrixFilters,
  useTraceabilityMatrix,
} from "@/hooks/use-traceability-matrix";
import {
  LINK_TYPES,
  type LinkType,
  type MatrixCell,
  type MatrixRow,
  STATUSES,
  type Status,
} from "@/types/api";
import { useState } from "react";

// ── Coverage + gap indicators ────────────────────────────────────────────────

function coveragePercent(covered: number, total: number): number {
  return total === 0 ? 0 : Math.round((covered / total) * 100);
}

function GapBadge() {
  return (
    <span
      className="inline-flex items-center rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-800"
      aria-label="Coverage gap"
    >
      Gap
    </span>
  );
}

function ArtifactChip({ cell }: { cell: MatrixCell }) {
  const label = cell.artifactTitle || cell.artifactIdentifier;
  const stale = cell.syncStatus !== "SYNCED";
  const className = `inline-flex max-w-full items-center gap-1 truncate rounded px-1.5 py-0.5 text-xs ${
    stale ? "bg-yellow-100 text-yellow-800" : "bg-muted text-foreground"
  }`;
  return (
    <li>
      {cell.artifactUrl ? (
        <a
          href={cell.artifactUrl}
          className={`${className} underline`}
          target="_blank"
          rel="noreferrer"
          title={`${cell.artifactType}: ${cell.artifactIdentifier}`}
        >
          {label}
        </a>
      ) : (
        <span
          className={className}
          title={`${cell.artifactType}: ${cell.artifactIdentifier}`}
        >
          {label}
        </span>
      )}
    </li>
  );
}

function MatrixCellGroup({ cells }: { cells: MatrixCell[] }) {
  if (cells.length === 0) {
    return <span className="text-xs text-muted-foreground">—</span>;
  }
  return (
    <ul className="space-y-0.5">
      {cells.map((cell) => (
        <ArtifactChip key={cell.linkId} cell={cell} />
      ))}
    </ul>
  );
}

// ── Matrix row ────────────────────────────────────────────────────────────────

function MatrixTableRow({
  row,
  columns,
}: {
  row: MatrixRow;
  columns: LinkType[];
}) {
  const cellsByType = new Map<LinkType, MatrixCell[]>();
  for (const cell of row.cells) {
    const bucket = cellsByType.get(cell.linkType) ?? [];
    bucket.push(cell);
    cellsByType.set(cell.linkType, bucket);
  }
  return (
    <tr
      className={`border-b border-border last:border-0 ${
        row.hasGap ? "bg-red-50/40" : ""
      }`}
    >
      <td className="px-3 py-2 align-top">
        <div className="flex items-center gap-2">
          <span className="font-mono text-sm font-semibold">{row.uid}</span>
          {row.hasGap && <GapBadge />}
        </div>
        <div className="text-xs text-muted-foreground">{row.title}</div>
        <div className="mt-0.5 text-[10px] uppercase text-muted-foreground">
          {row.status}
          {row.wave != null && ` · wave ${row.wave}`} · {row.priority}
        </div>
      </td>
      {columns.map((linkType) => (
        <td key={linkType} className="px-3 py-2 align-top">
          <MatrixCellGroup cells={cellsByType.get(linkType) ?? []} />
        </td>
      ))}
    </tr>
  );
}

// ── Scope controls ────────────────────────────────────────────────────────────

interface ScopeControlsProps {
  filters: TraceabilityMatrixFilters;
  onChange: (f: TraceabilityMatrixFilters) => void;
}

function ScopeControls({ filters, onChange }: ScopeControlsProps) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      <div>
        <label className="mb-1 block text-xs font-medium" htmlFor="matrix-wave">
          Wave
        </label>
        <input
          id="matrix-wave"
          type="number"
          min={0}
          className="w-24 rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.wave ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              wave: e.target.value === "" ? undefined : Number(e.target.value),
            })
          }
        />
      </div>
      <div>
        <label
          className="mb-1 block text-xs font-medium"
          htmlFor="matrix-status"
        >
          Status
        </label>
        <select
          id="matrix-status"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.status ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              status: (e.target.value as Status) || undefined,
            })
          }
        >
          <option value="">All</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label
          className="mb-1 block text-xs font-medium"
          htmlFor="matrix-link-type"
        >
          Link type
        </label>
        <select
          id="matrix-link-type"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.linkType ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              linkType: (e.target.value as LinkType) || undefined,
            })
          }
        >
          <option value="">All</option>
          {LINK_TYPES.map((lt) => (
            <option key={lt} value={lt}>
              {lt}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function TraceabilityMatrix() {
  const [filters, setFilters] = useState<TraceabilityMatrixFilters>({});
  const { data, isLoading, isError, error } = useTraceabilityMatrix(filters);

  const columns: LinkType[] = data?.columns.map((c) => c.linkType) ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Traceability Matrix</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Requirements against their linked artifacts (code, tests, ADRs,
          specifications) by link type, with coverage and gap indicators. Filter
          by wave, status, and link type. A gap flags an active requirement that
          is not yet implemented and tested.
        </p>
      </div>

      <ScopeControls filters={filters} onChange={setFilters} />

      {isLoading && (
        <div className="flex min-h-[20vh] items-center justify-center text-muted-foreground">
          Loading matrix&hellip;
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to load matrix."}
        </div>
      )}

      {data && (
        <>
          {/* Coverage summary */}
          <section aria-labelledby="coverage-heading">
            <h2 id="coverage-heading" className="mb-2 text-lg font-medium">
              Coverage
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.linkedRequirementCount}/{data.requirementCount} linked ·{" "}
                {data.gapCount} gap{data.gapCount === 1 ? "" : "s"})
              </span>
            </h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {data.columns.map((col) => {
                const pct = coveragePercent(
                  col.coveredRequirements,
                  col.totalRequirements,
                );
                return (
                  <div
                    key={col.linkType}
                    className="rounded-lg border border-border bg-card p-3"
                  >
                    <div className="mb-1 flex items-center justify-between text-sm">
                      <span className="font-medium">{col.linkType}</span>
                      <span className="text-muted-foreground">
                        {col.coveredRequirements}/{col.totalRequirements} ({pct}
                        %)
                      </span>
                    </div>
                    <div className="h-2 w-full overflow-hidden rounded bg-muted">
                      <div
                        className="h-full bg-primary"
                        style={{ width: `${pct}%` }}
                        aria-label={`${col.linkType} coverage ${pct}%`}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          {/* Matrix */}
          <section aria-labelledby="matrix-heading">
            <h2 id="matrix-heading" className="mb-2 text-lg font-medium">
              Requirements
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.requirementCount})
              </span>
            </h2>
            {data.rows.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No requirements match the current filters.
              </p>
            ) : (
              <div className="overflow-auto rounded-lg border border-border">
                <table className="w-full text-left">
                  <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2">Requirement</th>
                      {columns.map((linkType) => (
                        <th key={linkType} className="px-3 py-2">
                          {linkType}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((row) => (
                      <MatrixTableRow
                        key={row.requirementId}
                        row={row}
                        columns={columns}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
