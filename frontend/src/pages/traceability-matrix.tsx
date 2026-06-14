import { FilterSelect } from "@/components/requirements/filter-select";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { useProjectContext } from "@/contexts/project-context";
import {
  type TraceabilityMatrixFilters,
  useTraceabilityMatrix,
} from "@/hooks/use-traceability-matrix";
import type {
  LinkType,
  RequirementWithLinksResponse,
  TraceabilityLinkResponse,
} from "@/types/api";
import { LINK_TYPES, STATUSES } from "@/types/api";
import { ExternalLink, FileText } from "lucide-react";
import { useState } from "react";

// Column order is fixed by the GC-Q003 spec, independent of the LINK_TYPES
// enum declaration order.
const MATRIX_LINK_TYPES: LinkType[] = [
  "IMPLEMENTS",
  "TESTS",
  "DOCUMENTS",
  "CONSTRAINS",
  "VERIFIES",
];

const SKELETON_ROW_KEYS = [
  "skeleton-1",
  "skeleton-2",
  "skeleton-3",
  "skeleton-4",
  "skeleton-5",
];

type Coverage = "COVERED" | "PARTIAL" | "NONE";

const coverageStyles: Record<Coverage, string> = {
  COVERED: "bg-green-500/15 text-green-400",
  PARTIAL: "bg-amber-500/15 text-amber-400",
  NONE: "bg-red-500/15 text-red-400",
};

const coverageLabels: Record<Coverage, string> = {
  COVERED: "Covered",
  PARTIAL: "Partial",
  NONE: "Gap",
};

/**
 * Aggregate coverage for a requirement, computed from the full link set. Only
 * meaningful when no linkType filter is active — a filtered row carries a single
 * link type and cannot satisfy the IMPLEMENTS + TESTS "Covered" definition.
 */
function computeCoverage(links: TraceabilityLinkResponse[]): Coverage {
  if (links.length === 0) return "NONE";
  const hasImplements = links.some((l) => l.linkType === "IMPLEMENTS");
  const hasTests = links.some((l) => l.linkType === "TESTS");
  if (hasImplements && hasTests) return "COVERED";
  return "PARTIAL";
}

function LinkChip({ link }: { link: TraceabilityLinkResponse }) {
  const label = `${link.artifactType.replace(/_/g, " ")}: ${link.artifactTitle || link.artifactIdentifier}`;
  if (link.artifactUrl) {
    return (
      <a
        href={link.artifactUrl}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs text-primary hover:bg-primary/20"
      >
        {label}
        <ExternalLink className="h-3 w-3" />
      </a>
    );
  }
  return (
    <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs text-foreground">
      {label}
    </span>
  );
}

function MatrixCell({ links }: { links: TraceabilityLinkResponse[] }) {
  if (links.length === 0) {
    return (
      <span className="text-sm text-muted-foreground" aria-label="No link">
        —
      </span>
    );
  }
  return (
    <div className="flex flex-wrap gap-1">
      {links.map((link) => (
        <LinkChip key={link.id} link={link} />
      ))}
    </div>
  );
}

function MatrixRow({
  row,
  columns,
  filtered,
}: {
  row: RequirementWithLinksResponse;
  columns: LinkType[];
  filtered: boolean;
}) {
  const { requirement, links } = row;
  return (
    <tr className="hover:bg-accent/30 align-top">
      <td className="px-3 py-2 font-mono text-xs">{requirement.uid}</td>
      <td className="px-3 py-2 text-sm">{requirement.title}</td>
      <td className="px-3 py-2">
        <StatusBadge status={requirement.status} />
      </td>
      <td className="px-3 py-2 text-sm text-muted-foreground">
        {requirement.wave}
      </td>
      {columns.map((linkType) => (
        <td key={linkType} className="px-3 py-2">
          <MatrixCell links={links.filter((l) => l.linkType === linkType)} />
        </td>
      ))}
      <td className="px-3 py-2">
        {filtered ? (
          links.length > 0 ? (
            <Badge className={coverageStyles.COVERED}>Present</Badge>
          ) : (
            <Badge className={coverageStyles.NONE}>Gap</Badge>
          )
        ) : (
          <Badge className={coverageStyles[computeCoverage(links)]}>
            {coverageLabels[computeCoverage(links)]}
          </Badge>
        )}
      </td>
    </tr>
  );
}

export function TraceabilityMatrix() {
  const { activeProject, isLoading: projectLoading } = useProjectContext();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [statusFilter, setStatusFilter] = useState("");
  const [waveFilter, setWaveFilter] = useState("");
  const [linkTypeFilter, setLinkTypeFilter] = useState("");

  const waveValue = waveFilter !== "" ? Number(waveFilter) : undefined;

  const filters: TraceabilityMatrixFilters = {
    status: (statusFilter || undefined) as TraceabilityMatrixFilters["status"],
    wave: Number.isNaN(waveValue) ? undefined : waveValue,
    linkType: (linkTypeFilter ||
      undefined) as TraceabilityMatrixFilters["linkType"],
    page,
    size,
  };

  const { data, isLoading, isError, error } = useTraceabilityMatrix(filters);

  const filtered = linkTypeFilter !== "";
  const columns: LinkType[] = filtered
    ? [linkTypeFilter as LinkType]
    : MATRIX_LINK_TYPES;
  const columnCount = 4 + columns.length + 1;

  if (projectLoading) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-64 animate-pulse rounded bg-muted" />
        <div className="h-64 animate-pulse rounded-lg bg-muted" />
      </div>
    );
  }

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <FileText className="h-12 w-12 text-muted-foreground" />
        <h1 className="text-2xl font-semibold">Traceability Matrix</h1>
        <p className="text-muted-foreground">
          Select a project to view the traceability matrix.
        </p>
      </div>
    );
  }

  const content = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Traceability Matrix</h1>
          <p className="text-sm text-muted-foreground">
            Requirements crossed against their linked artifacts, with coverage
            gaps highlighted.
          </p>
        </div>
        <span className="text-sm text-muted-foreground">
          {totalElements} total
        </span>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card p-3">
        <FilterSelect
          value={statusFilter}
          onChange={(v) => {
            setStatusFilter(v);
            setPage(0);
          }}
          placeholder="Status"
          options={STATUSES}
        />
        <input
          type="number"
          className="w-20 rounded-md border border-input bg-background px-3 py-1.5 text-sm placeholder:text-muted-foreground"
          placeholder="Wave"
          value={waveFilter}
          onChange={(e) => {
            setWaveFilter(e.target.value);
            setPage(0);
          }}
          min={0}
        />
        <FilterSelect
          value={linkTypeFilter}
          onChange={(v) => {
            setLinkTypeFilter(v);
            setPage(0);
          }}
          placeholder="Link Type"
          options={LINK_TYPES}
        />
        {(statusFilter || waveFilter || linkTypeFilter) && (
          <button
            type="button"
            className="text-xs text-muted-foreground hover:text-foreground"
            onClick={() => {
              setStatusFilter("");
              setWaveFilter("");
              setLinkTypeFilter("");
              setPage(0);
            }}
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Table */}
      {isError ? (
        <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-6 text-center text-sm text-destructive">
          {error instanceof Error
            ? error.message
            : "Failed to load traceability matrix."}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="border-b border-border bg-card">
              <tr>
                <th className="px-3 py-3 text-left text-xs font-medium text-muted-foreground">
                  UID
                </th>
                <th className="px-3 py-3 text-left text-xs font-medium text-muted-foreground">
                  Title
                </th>
                <th className="px-3 py-3 text-left text-xs font-medium text-muted-foreground">
                  Status
                </th>
                <th className="px-3 py-3 text-left text-xs font-medium text-muted-foreground">
                  Wave
                </th>
                {columns.map((linkType) => (
                  <th
                    key={linkType}
                    className="px-3 py-3 text-left text-xs font-medium text-muted-foreground"
                  >
                    {linkType}
                  </th>
                ))}
                <th className="px-3 py-3 text-left text-xs font-medium text-muted-foreground">
                  Coverage
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {isLoading ? (
                SKELETON_ROW_KEYS.map((key) => (
                  <tr key={key}>
                    <td colSpan={columnCount} className="px-3 py-3">
                      <div className="h-5 w-full animate-pulse rounded bg-muted" />
                    </td>
                  </tr>
                ))
              ) : content.length === 0 ? (
                <tr>
                  <td
                    colSpan={columnCount}
                    className="px-4 py-8 text-center text-muted-foreground"
                  >
                    No requirements match these filters.
                  </td>
                </tr>
              ) : (
                content.map((row) => (
                  <MatrixRow
                    key={row.requirement.id}
                    row={row}
                    columns={columns}
                    filtered={filtered}
                  />
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Pagination */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground">Rows per page:</span>
          <select
            className="rounded-md border border-input bg-background px-2 py-1 text-sm"
            value={size}
            onChange={(e) => {
              setSize(Number(e.target.value));
              setPage(0);
            }}
          >
            <option value="10">10</option>
            <option value="25">25</option>
            <option value="50">50</option>
          </select>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {Math.max(totalPages, 1)}
          </span>
          <button
            type="button"
            className="rounded-md border border-input bg-background px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </button>
          <button
            type="button"
            className="rounded-md border border-input bg-background px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
            disabled={page >= totalPages - 1}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
