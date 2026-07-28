// Split from graph.tsx under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). The markup is unchanged.
//
// graph.tsx's exported component must stay at its own path for routing, so the
// page could not be brought under the limit by moving whole declarations out.
// This is the filter bar, lifted with the values it renders passed in.

import { Filter, X } from "lucide-react";

export interface GraphFilterOptions {
  entityTypes: string[];
  statuses: string[];
  priorities: string[];
  series: string[];
  waves: number[];
}

export interface GraphFiltersProps {
  filterOptions: GraphFilterOptions;
  filterEntityType: string;
  setFilterEntityType: (value: string) => void;
  filterStatus: string;
  setFilterStatus: (value: string) => void;
  filterPriority: string;
  setFilterPriority: (value: string) => void;
  filterSeries: string;
  setFilterSeries: (value: string) => void;
  filterWave: string;
  setFilterWave: (value: string) => void;
  hasFilters: boolean;
  clearFilters: () => void;
}

const SELECT_CLASS =
  "rounded border border-input bg-background px-2 py-0.5 text-xs text-foreground";
const LABEL_CLASS = "text-xs text-muted-foreground";

export function GraphFilters({
  filterOptions,
  filterEntityType,
  setFilterEntityType,
  filterStatus,
  setFilterStatus,
  filterPriority,
  setFilterPriority,
  filterSeries,
  setFilterSeries,
  filterWave,
  setFilterWave,
  hasFilters,
  clearFilters,
}: GraphFiltersProps) {
  return (
    <div className="flex items-center gap-4 border-b border-border bg-card px-4 py-1.5">
      <Filter className="h-3.5 w-3.5 text-muted-foreground" />
      <div className="flex items-center gap-2">
        <label htmlFor="graph-filter-entity-type" className={LABEL_CLASS}>
          Entity
        </label>
        <select
          id="graph-filter-entity-type"
          value={filterEntityType}
          onChange={(e) => setFilterEntityType(e.target.value)}
          className={SELECT_CLASS}
        >
          <option value="">All</option>
          {filterOptions.entityTypes.map((entityType) => (
            <option key={entityType} value={entityType}>
              {entityType}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-center gap-2">
        <label htmlFor="graph-filter-status" className={LABEL_CLASS}>
          Status
        </label>
        <select
          id="graph-filter-status"
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className={SELECT_CLASS}
        >
          <option value="">All</option>
          {filterOptions.statuses.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-center gap-2">
        <label htmlFor="graph-filter-priority" className={LABEL_CLASS}>
          Priority
        </label>
        <select
          id="graph-filter-priority"
          value={filterPriority}
          onChange={(e) => setFilterPriority(e.target.value)}
          className={SELECT_CLASS}
        >
          <option value="">All</option>
          {filterOptions.priorities.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
      </div>
      {filterOptions.series.length > 0 && (
        <div className="flex items-center gap-2">
          <label htmlFor="graph-filter-series" className={LABEL_CLASS}>
            Series
          </label>
          <select
            id="graph-filter-series"
            value={filterSeries}
            onChange={(e) => setFilterSeries(e.target.value)}
            className={SELECT_CLASS}
          >
            <option value="">All</option>
            {filterOptions.series.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      )}
      {filterOptions.waves.length > 0 && (
        <div className="flex items-center gap-2">
          <label htmlFor="graph-filter-wave" className={LABEL_CLASS}>
            Wave
          </label>
          <select
            id="graph-filter-wave"
            value={filterWave}
            onChange={(e) => setFilterWave(e.target.value)}
            className={SELECT_CLASS}
          >
            <option value="">All</option>
            {filterOptions.waves.map((w) => (
              <option key={w} value={String(w)}>
                {w}
              </option>
            ))}
          </select>
        </div>
      )}
      {hasFilters && (
        <button
          type="button"
          onClick={clearFilters}
          className="flex items-center gap-1 rounded border border-input bg-background px-2 py-0.5 text-xs text-muted-foreground hover:border-primary hover:text-foreground"
        >
          <X className="h-3 w-3" /> Clear
        </button>
      )}
    </div>
  );
}
