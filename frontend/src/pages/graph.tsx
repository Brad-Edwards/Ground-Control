import { GraphLegend } from "@/components/graph/graph-legend";
import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import {
  type ColorScheme,
  type LayoutId,
  RELATION_STYLES,
  getColorMap,
  getEntityTypeColor,
} from "@/lib/graph-constants";
import type {
  GraphNeighborhoodResponse,
  GraphVisualizationResponse,
} from "@/types/api";
import { Loader2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GraphControls } from "./graph/graph-controls";
import { GraphFilters } from "./graph/graph-filters";
import {
  type CytoscapeInstance,
  type GraphNodeData,
  type RelationData,
  getNodeEntityType,
  getNodeLegendKey,
  getNodePriority,
  getNodeSeries,
  getNodeStatus,
  getNodeWave,
  isRequirementNode,
} from "./graph/graph-node-data";
import { GraphStats } from "./graph/graph-stats";
import {
  getTooltipTags,
  populateGraphTooltip,
} from "./graph/populate-graph-tooltip";
import { renderGraphCanvas } from "./graph/render-graph-canvas";

export function Graph() {
  const { activeProject } = useProjectContext();
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<CytoscapeInstance | null>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);

  const [colorScheme, setColorScheme] = useState<ColorScheme>("entity");
  const [layoutId, setLayoutId] = useState<LayoutId>("dagre-tb");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState("");
  const [viewMode, setViewMode] = useState<"visualization" | "traversal">(
    "visualization",
  );

  const [filterEntityType, setFilterEntityType] = useState("");
  const [filterStatus, setFilterStatus] = useState("");
  const [filterPriority, setFilterPriority] = useState("");
  const [filterSeries, setFilterSeries] = useState("");
  const [filterWave, setFilterWave] = useState("");

  const [nodes, setNodes] = useState<GraphNodeData[]>([]);
  const [relations, setRelations] = useState<RelationData[]>([]);

  const fetchData = useCallback(async () => {
    if (!activeProject) return;
    setLoading(true);
    setError(null);

    try {
      const data = await apiFetch<GraphVisualizationResponse>(
        "/graph/visualization",
        { params: { project: activeProject.identifier } },
      );
      setNodes(data.nodes);
      setRelations(data.edges);
      setViewMode("visualization");
      setLoading(false);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to load graph data",
      );
      setLoading(false);
    }
  }, [activeProject]);

  const runTraversal = useCallback(async () => {
    if (!activeProject || !selectedNodeId) return;
    setLoading(true);
    setError(null);

    try {
      const data = await apiFetch<GraphNeighborhoodResponse>(
        "/graph/traversal/query",
        {
          method: "POST",
          params: { project: activeProject.identifier },
          body: {
            rootNodeIds: [selectedNodeId],
            maxDepth: 2,
            entityTypes: filterEntityType ? [filterEntityType] : undefined,
          },
        },
      );
      setNodes(data.nodes);
      setRelations(data.edges);
      setViewMode("traversal");
      setLoading(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to traverse graph");
      setLoading(false);
    }
  }, [activeProject, filterEntityType, selectedNodeId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const stats = useMemo(() => {
    const entityTypes = new Set<string>();
    const waves: Record<number, number> = {};
    const seriesSet = new Set<string>();
    let requirementCount = 0;
    for (const node of nodes) {
      entityTypes.add(getNodeEntityType(node));
      const w = getNodeWave(node) || 0;
      if (isRequirementNode(node)) {
        requirementCount += 1;
        waves[w] = (waves[w] ?? 0) + 1;
        seriesSet.add(getNodeSeries(node));
      }
    }
    const waveKeys = Object.keys(waves)
      .map(Number)
      .sort((a, b) => a - b);
    const waveStr = waveKeys.map((w) => `W${w}:${waves[w]}`).join(" ");
    return {
      nodes: nodes.length,
      edges: relations.length,
      entityTypes: entityTypes.size,
      requirementCount,
      series: seriesSet.size,
      waves: waveKeys.length,
      waveStr,
    };
  }, [nodes, relations]);

  const filterOptions = useMemo(() => {
    const entityTypes = new Set<string>();
    const statuses = new Set<string>();
    const priorities = new Set<string>();
    const series = new Set<string>();
    const waves = new Set<number>();
    for (const node of nodes) {
      entityTypes.add(getNodeEntityType(node));
      const status = getNodeStatus(node);
      const priority = getNodePriority(node);
      if (status) statuses.add(status);
      if (priority) priorities.add(priority);
      if (isRequirementNode(node)) {
        series.add(getNodeSeries(node));
        waves.add(getNodeWave(node) || 0);
      }
    }
    return {
      entityTypes: [...entityTypes].sort(),
      statuses: [...statuses].sort(),
      priorities: [...priorities].sort(),
      series: [...series].sort(),
      waves: [...waves].sort((a, b) => a - b),
    };
  }, [nodes]);

  const hasFilters =
    filterEntityType !== "" ||
    filterStatus !== "" ||
    filterPriority !== "" ||
    filterSeries !== "" ||
    filterWave !== "";

  const filteredNodes = useMemo(() => {
    if (!hasFilters) return nodes;
    return nodes.filter((node) => {
      if (filterEntityType && getNodeEntityType(node) !== filterEntityType) {
        return false;
      }
      if (filterStatus && getNodeStatus(node) !== filterStatus) return false;
      if (filterPriority && getNodePriority(node) !== filterPriority)
        return false;
      if (filterSeries) {
        if (!isRequirementNode(node) || getNodeSeries(node) !== filterSeries) {
          return false;
        }
      }
      if (filterWave) {
        if (
          !isRequirementNode(node) ||
          String(getNodeWave(node) || 0) !== filterWave
        ) {
          return false;
        }
      }
      return true;
    });
  }, [
    nodes,
    hasFilters,
    filterEntityType,
    filterStatus,
    filterPriority,
    filterSeries,
    filterWave,
  ]);

  const filteredRelations = useMemo(() => {
    if (!hasFilters) return relations;
    const ids = new Set(filteredNodes.map((node) => node.id));
    return relations.filter((r) => ids.has(r.sourceId) && ids.has(r.targetId));
  }, [relations, filteredNodes, hasFilters]);

  // Legend counts reflect the filtered graph so they match what's visible
  const legendItems = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const node of filteredNodes) {
      const key = getNodeLegendKey(node, colorScheme);
      counts[key] = (counts[key] ?? 0) + 1;
    }
    const colorMap = getColorMap(colorScheme);
    return Object.keys(counts)
      .sort()
      .map((key) => ({
        key,
        count: counts[key] ?? 0,
        color: colorMap[key] ?? getEntityTypeColor(key),
      }));
  }, [filteredNodes, colorScheme]);

  const relationLegend = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const r of filteredRelations) {
      counts[r.edgeType] = (counts[r.edgeType] ?? 0) + 1;
    }
    return Object.entries(RELATION_STYLES)
      .filter(([type]) => (counts[type] ?? 0) > 0)
      .map(([type, style]) => ({
        type,
        ...style,
        count: counts[type] ?? 0,
      }));
  }, [filteredRelations]);

  function clearFilters() {
    setFilterEntityType("");
    setFilterStatus("");
    setFilterPriority("");
    setFilterSeries("");
    setFilterWave("");
  }

  // Build tooltip content using safe DOM methods
  const populateTooltip = useCallback(
    (container: HTMLDivElement, d: Record<string, unknown>) =>
      populateGraphTooltip(container, d),
    [],
  );

  useEffect(
    () =>
      renderGraphCanvas({
        colorScheme,
        containerRef,
        cyRef,
        filteredNodes,
        filteredRelations,
        layoutId,
        loading,
        populateTooltip,
        setSelectedNodeId,
        tooltipRef,
      }),
    [
      loading,
      filteredNodes,
      filteredRelations,
      colorScheme,
      layoutId,
      populateTooltip,
    ],
  );

  function handleFit() {
    cyRef.current?.fit(undefined, 30);
  }

  function handleReset() {
    setSelectedNodeId("");
    cyRef.current?.elements().removeClass("highlighted dimmed");
    cyRef.current?.fit(undefined, 30);
  }

  function handleLegendClick(key: string) {
    const cy = cyRef.current;
    if (!cy) return;

    cy.elements().removeClass("highlighted dimmed");
    const matchNodes = cy.nodes().filter((n) => {
      if (n.data("entityType") !== "REQUIREMENT") {
        return n.data("entityType") === key;
      }
      return (
        getNodeLegendKey(
          {
            id: String(n.id()),
            domainId: String(n.data("domainId") ?? ""),
            entityType: n.data("entityType") as GraphNodeData["entityType"],
            projectIdentifier: "",
            uid: String(n.data("uid") ?? ""),
            label: String(n.data("label") ?? ""),
            properties: {
              priority: n.data("priority"),
              status: n.data("status"),
              requirementType: n.data("type"),
              wave: n.data("wave"),
            },
          },
          colorScheme,
        ) === key
      );
    });
    if (matchNodes.length === 0) return;
    const neighborhood = matchNodes.union(matchNodes.connectedEdges());
    cy.elements().not(neighborhood).addClass("dimmed");
    neighborhood.addClass("highlighted");
  }

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <h1 className="text-2xl font-semibold">Graph View</h1>
        <p className="text-muted-foreground">
          Select a project to view the graph.
        </p>
      </div>
    );
  }

  const selectedNode = selectedNodeId
    ? (nodes.find((node) => node.id === selectedNodeId) ?? null)
    : null;

  return (
    <div className="flex h-[calc(100vh-3.5rem)] flex-col">
      {/* Controls */}
      <GraphControls
        colorScheme={colorScheme}
        setColorScheme={setColorScheme}
        layoutId={layoutId}
        setLayoutId={setLayoutId}
        handleFit={handleFit}
        handleReset={handleReset}
        runTraversal={runTraversal}
        fetchData={fetchData}
        selectedNodeId={selectedNodeId}
        loading={loading}
        viewMode={viewMode}
      />

      {/* Filters */}
      {!loading && (
        <GraphFilters
          filterOptions={filterOptions}
          filterEntityType={filterEntityType}
          setFilterEntityType={setFilterEntityType}
          filterStatus={filterStatus}
          setFilterStatus={setFilterStatus}
          filterPriority={filterPriority}
          setFilterPriority={setFilterPriority}
          filterSeries={filterSeries}
          setFilterSeries={setFilterSeries}
          filterWave={filterWave}
          setFilterWave={setFilterWave}
          hasFilters={hasFilters}
          clearFilters={clearFilters}
        />
      )}

      {/* Stats */}
      {!loading && (
        <GraphStats
          stats={stats}
          hasFilters={hasFilters}
          filteredNodeCount={filteredNodes.length}
          filteredRelationCount={filteredRelations.length}
          selectedNodeId={selectedNode ? selectedNode.id : null}
        />
      )}

      {/* Legend */}
      {!loading && (
        <GraphLegend
          legendItems={legendItems}
          relationLegend={relationLegend}
          onLegendClick={handleLegendClick}
        />
      )}

      {/* Graph canvas */}
      <div className="relative flex-1">
        {loading && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 bg-background">
            <Loader2 className="h-8 w-8 animate-spin text-primary" />
            <span className="text-sm text-muted-foreground">
              Loading graph...
            </span>
          </div>
        )}
        {error && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 bg-background">
            <span className="text-sm text-destructive">{error}</span>
            <span className="text-xs text-muted-foreground">
              Is the backend running?
            </span>
          </div>
        )}
        {!loading &&
          !error &&
          nodes.length > 0 &&
          filteredNodes.length === 0 && (
            <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 bg-background">
              <span className="text-sm text-muted-foreground">
                No graph nodes match the current filters.
              </span>
              <button
                type="button"
                onClick={clearFilters}
                className="rounded border border-input bg-card px-3 py-1.5 text-xs hover:border-primary"
              >
                Clear filters
              </button>
            </div>
          )}
        <div ref={containerRef} className="h-full w-full" />
      </div>

      {/* Tooltip */}
      <div
        ref={tooltipRef}
        className="pointer-events-none fixed z-[100] hidden max-w-[360px] rounded-md border border-border bg-card p-3 text-xs shadow-lg"
      />
    </div>
  );
}

export { getTooltipTags };
