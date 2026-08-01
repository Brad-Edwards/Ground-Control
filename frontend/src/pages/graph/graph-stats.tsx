// Split from graph.tsx under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). The markup is unchanged.
//
// The counts bar, lifted out of the page's exported component. That component
// has to stay at its own path for routing, so the page could not be brought
// under the limit by moving whole declarations out.

export interface GraphStatsSummary {
  nodes: number;
  edges: number;
  entityTypes: number;
  requirementCount: number;
  series: number;
  waves: number;
  waveStr: string;
}

export interface GraphStatsProps {
  stats: GraphStatsSummary;
  hasFilters: boolean;
  filteredNodeCount: number;
  filteredRelationCount: number;
  selectedNodeId: string | null;
}

const VALUE_CLASS = "text-foreground text-[13px] mr-0.5";

export function GraphStats({
  stats,
  hasFilters,
  filteredNodeCount,
  filteredRelationCount,
  selectedNodeId,
}: GraphStatsProps) {
  return (
    <div className="flex gap-4 border-b border-border bg-card px-4 py-1.5 text-[11px]">
      <span className="text-muted-foreground">
        <strong className={VALUE_CLASS}>
          {hasFilters ? `${filteredNodeCount} of ${stats.nodes}` : stats.nodes}
        </strong>
        nodes
      </span>
      <span className="text-muted-foreground">
        <strong className={VALUE_CLASS}>
          {hasFilters
            ? `${filteredRelationCount} of ${stats.edges}`
            : stats.edges}
        </strong>
        edges
      </span>
      <span className="text-muted-foreground">
        <strong className={VALUE_CLASS}>{stats.entityTypes}</strong>
        entity types
      </span>
      {stats.requirementCount > 0 && (
        <>
          <span className="text-muted-foreground">
            <strong className={VALUE_CLASS}>{stats.series}</strong>
            series
          </span>
          <span className="text-muted-foreground">
            <strong className={VALUE_CLASS}>{stats.waves}</strong>
            waves
          </span>
          <span className="text-muted-foreground">{stats.waveStr}</span>
        </>
      )}
      {selectedNodeId && (
        <span className="truncate text-muted-foreground">
          selected:{" "}
          <strong className="text-foreground">{selectedNodeId}</strong>
        </span>
      )}
    </div>
  );
}
