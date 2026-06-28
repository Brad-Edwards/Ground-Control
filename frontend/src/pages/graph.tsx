import { GraphLegend } from "@/components/graph/graph-legend";
import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import {
  type ColorScheme,
  type LayoutId,
  PRIORITY_COLORS,
  RELATION_STYLES,
  STATUS_COLORS,
  getColorMap,
  getEntityTypeColor,
  getNodeColor,
  getSeries,
} from "@/lib/graph-constants";
import type {
  GraphEdgeResponse,
  GraphNeighborhoodResponse,
  GraphVisualizationNodeResponse,
  GraphVisualizationResponse,
} from "@/types/api";
import type cytoscape from "cytoscape";
import { Filter, Loader2, Maximize, RotateCcw, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

type GraphNodeData = GraphVisualizationNodeResponse;
type RelationData = GraphEdgeResponse;
type CytoscapeInstance = cytoscape.Core;

const WAVE_SPACING = 120;

function isRequirementNode(node: GraphNodeData): boolean {
  return node.entityType === "REQUIREMENT";
}

function getNodeEntityType(node: GraphNodeData): string {
  return String(node.entityType ?? "UNKNOWN");
}

function getStringProperty(node: GraphNodeData, key: string): string {
  const value = node.properties[key];
  return typeof value === "string" ? value : "";
}

function getNumberProperty(node: GraphNodeData, key: string): number {
  const value = node.properties[key];
  return typeof value === "number" ? value : 0;
}

function getNodePriority(node: GraphNodeData): string {
  return getStringProperty(node, "priority");
}

function getNodeStatus(node: GraphNodeData): string {
  return getStringProperty(node, "status");
}

function getNodeRequirementType(node: GraphNodeData): string {
  return getStringProperty(node, "requirementType");
}

function getNodeStatement(node: GraphNodeData): string {
  return getStringProperty(node, "statement");
}

function getNodeTitle(node: GraphNodeData): string {
  const title = getStringProperty(node, "title");
  return title || node.label || node.uid || getNodeEntityType(node);
}

function getNodeWave(node: GraphNodeData): number {
  return getNumberProperty(node, "wave");
}

function getNodeSeries(node: GraphNodeData): string {
  if (!isRequirementNode(node)) {
    return getNodeEntityType(node);
  }
  return getSeries(node.uid || node.label);
}

function getNodeDisplayLabel(node: GraphNodeData): string {
  if (isRequirementNode(node)) {
    return (node.label || node.uid || "REQ").replace("GC-", "");
  }
  if (getNodeEntityType(node) === "OBSERVATION") {
    return getStringProperty(node, "observationKey") || node.label || "OBS";
  }
  return node.uid || node.label || getNodeTitle(node);
}

function getNodeLegendKey(
  node: GraphNodeData,
  colorScheme: ColorScheme,
): string {
  if (!isRequirementNode(node)) {
    return getNodeEntityType(node);
  }
  switch (colorScheme) {
    case "priority":
      return getNodePriority(node) || "Unknown";
    case "status":
      return getNodeStatus(node) || "Unknown";
    case "wave":
      return `Wave ${getNodeWave(node) || 0}`;
    case "entity":
      return getNodeEntityType(node);
    default:
      return getNodeSeries(node);
  }
}

function getNodeDescription(node: GraphNodeData): string {
  const entityType = getNodeEntityType(node);
  if (entityType === "REQUIREMENT") {
    return getNodeStatement(node);
  }
  if (entityType === "OPERATIONAL_ASSET") {
    return getStringProperty(node, "description");
  }
  if (entityType === "OBSERVATION") {
    return getStringProperty(node, "observationValue");
  }
  if (entityType === "RISK_SCENARIO") {
    return getStringProperty(node, "effect");
  }
  if (entityType === "RISK_REGISTER_RECORD") {
    return getStringProperty(node, "assetScopeSummary");
  }
  return "";
}

function getTooltipValue(data: Record<string, unknown>, key: string): string {
  const value = data[key];
  return typeof value === "string" ? value : "";
}

function firstTooltipString(
  data: Record<string, unknown>,
  ...keys: string[]
): string {
  for (const key of keys) {
    const value = data[key];
    if (typeof value === "string" && value) {
      return value;
    }
  }
  return "";
}

const TOOLTIP_FIELDS_BY_ENTITY_TYPE: Record<
  string,
  Array<{ label: string; key: string }>
> = {
  OPERATIONAL_ASSET: [
    { label: "Asset Type", key: "assetType" },
    { label: "Name", key: "assetName" },
    { label: "Knowledge", key: "knowledgeState" },
  ],
  OBSERVATION: [
    { label: "Category", key: "category" },
    { label: "Source", key: "source" },
    { label: "Confidence", key: "confidence" },
  ],
  RISK_SCENARIO: [
    { label: "Status", key: "status" },
    { label: "Threat", key: "threat" },
    { label: "Method", key: "method" },
  ],
  RISK_REGISTER_RECORD: [
    { label: "Status", key: "status" },
    { label: "Owner", key: "owner" },
    { label: "Cadence", key: "reviewCadence" },
  ],
  RISK_ASSESSMENT_RESULT: [
    { label: "Approval", key: "approvalState" },
    { label: "Confidence", key: "confidence" },
    { label: "Analyst", key: "analystIdentity" },
  ],
  TREATMENT_PLAN: [
    { label: "Strategy", key: "strategy" },
    { label: "Status", key: "status" },
    { label: "Owner", key: "owner" },
  ],
  METHODOLOGY_PROFILE: [
    { label: "Family", key: "family" },
    { label: "Version", key: "version" },
    { label: "Status", key: "status" },
  ],
  CONTROL: [
    { label: "Status", key: "status" },
    { label: "Owner", key: "owner" },
    { label: "Category", key: "category" },
    { label: "Source", key: "source" },
  ],
  CONTROL_TEST: [
    { label: "Methodology", key: "methodology" },
    { label: "Conclusion", key: "conclusion" },
    { label: "Tester", key: "testerIdentity" },
  ],
  CONTROL_EFFECTIVENESS_ASSESSMENT: [
    { label: "Design", key: "designEffectiveness" },
    { label: "Operating", key: "operatingEffectiveness" },
    { label: "Assessor", key: "assessor" },
  ],
  VERIFICATION_RESULT: [
    { label: "Prover", key: "prover" },
    { label: "Result", key: "result" },
    { label: "Assurance", key: "assuranceLevel" },
  ],
  THREAT_MODEL: [
    { label: "Status", key: "status" },
    { label: "Threat", key: "threatSource" },
    { label: "Stride", key: "stride" },
  ],
  FINDING: [
    { label: "Status", key: "status" },
    { label: "Type", key: "findingType" },
    { label: "Severity", key: "severity" },
    { label: "Owner", key: "owner" },
  ],
  EVIDENCE_ARTIFACT: [
    { label: "Type", key: "evidenceType" },
    { label: "Assurance", key: "assuranceLevel" },
    { label: "Derived by", key: "derivedBy" },
  ],
  AUDIT: [
    { label: "Type", key: "auditType" },
    { label: "Status", key: "status" },
    { label: "Created by", key: "createdBy" },
  ],
  RISK_CONTROL_MAPPING: [
    { label: "Role", key: "controlRole" },
    { label: "Objective", key: "mappingObjective" },
  ],
  SCOPED_CONTROL_IMPLEMENTATION: [
    { label: "Name", key: "name" },
    { label: "Control", key: "controlUid" },
  ],
  DOCUMENT: [
    { label: "Version", key: "version" },
    { label: "Created by", key: "createdBy" },
    { label: "Updated", key: "updatedAt" },
  ],
  RESEARCH_RUN: [
    { label: "Status", key: "status" },
    { label: "Stage", key: "currentStage" },
    { label: "Autonomy", key: "autonomyLevel" },
  ],
  RESEARCH_ARTIFACT: [
    { label: "Type", key: "artifactType" },
    { label: "Stage", key: "stage" },
    { label: "Status", key: "status" },
  ],
  RESEARCH_PROVENANCE_NODE: [
    { label: "Kind", key: "kind" },
    { label: "Status", key: "status" },
    { label: "External ID", key: "externalIdentifier" },
  ],
};

export function getTooltipTags(
  data: Record<string, unknown>,
): Array<{ text: string; bg: string }> {
  const entityType = String(data.entityType ?? "UNKNOWN");
  const entityColor = getEntityTypeColor(entityType);

  if (entityType === "REQUIREMENT") {
    return [
      {
        text: String(data.priority ?? ""),
        bg: PRIORITY_COLORS[String(data.priority ?? "")] ?? "#555",
      },
      {
        text: String(data.status ?? ""),
        bg: STATUS_COLORS[String(data.status ?? "")] ?? "#555",
      },
      { text: `Wave ${Number(data.wave ?? 0)}`, bg: "#6c7ee1" },
      { text: String(data.type ?? ""), bg: "#4ecdc4" },
    ].filter((tag) => tag.text);
  }

  return (TOOLTIP_FIELDS_BY_ENTITY_TYPE[entityType] ?? [])
    .map((field) => {
      const value = getTooltipValue(data, field.key);
      return value
        ? { text: `${field.label}: ${value}`, bg: entityColor }
        : null;
    })
    .filter((tag): tag is { text: string; bg: string } => tag !== null);
}

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
    (container: HTMLDivElement, d: Record<string, unknown>) => {
      container.replaceChildren();

      const uidDiv = document.createElement("div");
      uidDiv.style.cssText =
        "font-size:11px;color:#6c7ee1;font-weight:600;margin-bottom:4px";
      uidDiv.textContent = String(d.uid ?? d.entityType ?? d.id ?? "");
      container.appendChild(uidDiv);

      const titleDiv = document.createElement("div");
      titleDiv.style.cssText = "font-weight:600;margin-bottom:6px";
      titleDiv.textContent = String(d.title ?? d.label ?? "");
      container.appendChild(titleDiv);

      const metaDiv = document.createElement("div");
      metaDiv.style.cssText =
        "display:flex;gap:8px;margin-bottom:6px;flex-wrap:wrap";

      const typeTag = {
        text: String(d.entityType ?? "UNKNOWN"),
        bg: getEntityTypeColor(String(d.entityType ?? "")),
      };
      const detailTags = getTooltipTags(d);

      for (const t of [typeTag, ...detailTags].filter((tag) => tag.text)) {
        const span = document.createElement("span");
        span.style.cssText = `display:inline-block;padding:1px 6px;border-radius:3px;font-size:10px;font-weight:600;background:${t.bg}33;color:${t.bg}`;
        span.textContent = t.text;
        metaDiv.appendChild(span);
      }
      container.appendChild(metaDiv);

      const statement =
        String(d.entityType ?? "") === "REQUIREMENT"
          ? firstTooltipString(d, "statement")
          : firstTooltipString(d, "description", "observationValue", "effect");
      if (statement) {
        const stmtDiv = document.createElement("div");
        stmtDiv.style.cssText =
          "color:#a1a1aa;font-size:11px;line-height:1.4;max-height:80px;overflow:hidden";
        stmtDiv.textContent = statement;
        container.appendChild(stmtDiv);
      }
    },
    [],
  );

  useEffect(() => {
    if (loading || !containerRef.current || filteredNodes.length === 0) {
      // Destroy stale graph when filters exclude all nodes
      if (filteredNodes.length === 0 && cyRef.current) {
        cyRef.current.destroy();
        cyRef.current = null;
      }
      return;
    }

    let cancelled = false;

    async function initCytoscape() {
      const cytoscapeModule = await import("cytoscape");
      const cytoscape = cytoscapeModule.default;
      const dagreModule = await import("cytoscape-dagre");
      // cytoscape-dagre exports differ between ESM/CJS
      const cytoscapeDagre =
        "default" in dagreModule
          ? (dagreModule.default as (cy: typeof cytoscape) => void)
          : (dagreModule as unknown as (cy: typeof cytoscape) => void);
      cytoscapeDagre(cytoscape);

      if (cancelled) return;

      const elements = filteredNodes.map((node) => ({
        data: {
          id: node.id,
          domainId: node.domainId,
          uid: node.uid,
          label: getNodeDisplayLabel(node),
          entityType: node.entityType,
          title: getNodeTitle(node),
          statement: getNodeStatement(node),
          description: getNodeDescription(node),
          priority: getNodePriority(node),
          status: getNodeStatus(node),
          type: getNodeRequirementType(node),
          wave: getNodeWave(node) || 0,
          series: getNodeSeries(node),
          category: getStringProperty(node, "category"),
          assetType: getStringProperty(node, "assetType"),
          assetName: getStringProperty(node, "name"),
          knowledgeState: getStringProperty(node, "knowledgeState"),
          owner: getStringProperty(node, "owner"),
          source: getStringProperty(node, "source"),
          confidence: getStringProperty(node, "confidence"),
          reviewCadence: getStringProperty(node, "reviewCadence"),
          strategy: getStringProperty(node, "strategy"),
          approvalState: getStringProperty(node, "approvalState"),
          analystIdentity: getStringProperty(node, "analystIdentity"),
          family: getStringProperty(node, "family"),
          version: getStringProperty(node, "version"),
          threat: getStringProperty(node, "threat"),
          threatSource: getStringProperty(node, "threatSource"),
          threatEvent: getStringProperty(node, "threatEvent"),
          method: getStringProperty(node, "method"),
          effect: getStringProperty(node, "effect"),
          observationValue: getStringProperty(node, "observationValue"),
          // CONTROL / CONTROL_TEST / CONTROL_EFFECTIVENESS_ASSESSMENT
          controlFunction: getStringProperty(node, "controlFunction"),
          methodology: getStringProperty(node, "methodology"),
          conclusion: getStringProperty(node, "conclusion"),
          testerIdentity: getStringProperty(node, "testerIdentity"),
          controlUid: getStringProperty(node, "controlUid"),
          designEffectiveness: getStringProperty(node, "designEffectiveness"),
          operatingEffectiveness: getStringProperty(
            node,
            "operatingEffectiveness",
          ),
          assessor: getStringProperty(node, "assessor"),
          // VERIFICATION_RESULT
          prover: getStringProperty(node, "prover"),
          result: getStringProperty(node, "result"),
          assuranceLevel: getStringProperty(node, "assuranceLevel"),
          // THREAT_MODEL (shares the `effect` property bag with RiskScenario above)
          stride: getStringProperty(node, "stride"),
          // FINDING
          findingType: getStringProperty(node, "findingType"),
          severity: getStringProperty(node, "severity"),
          // EVIDENCE_ARTIFACT
          evidenceType: getStringProperty(node, "evidenceType"),
          derivedBy: getStringProperty(node, "derivedBy"),
          // AUDIT
          auditType: getStringProperty(node, "auditType"),
          createdBy: getStringProperty(node, "createdBy"),
          // RISK_CONTROL_MAPPING
          controlRole: getStringProperty(node, "controlRole"),
          mappingObjective: getStringProperty(node, "mappingObjective"),
          // DOCUMENT
          updatedAt: getStringProperty(node, "updatedAt"),
          color: getNodeColor(
            {
              entityType: getNodeEntityType(node),
              uid: node.uid,
              priority: getNodePriority(node),
              status: getNodeStatus(node),
              wave: getNodeWave(node),
            },
            colorScheme,
          ),
        },
      }));

      const edges = filteredRelations.map((rel) => {
        const style = RELATION_STYLES[rel.edgeType] ?? RELATION_STYLES.RELATED;
        return {
          data: {
            id: `e-${rel.id}`,
            source: rel.sourceId,
            target: rel.targetId,
            relType: rel.edgeType,
            color: style?.color ?? "#95a5a6",
            lineStyle: style?.style ?? "dotted",
          },
        };
      });

      if (cyRef.current) {
        cyRef.current.destroy();
      }

      const isWaveOrdered = layoutId.startsWith("dagre-wave");
      const isTopBottom =
        layoutId === "dagre-tb" || layoutId === "dagre-wave-tb";
      const rankDir = isTopBottom ? "BT" : "RL";

      // cytoscape-dagre layout options extend base LayoutOptions
      const layoutConfig = {
        name: "dagre" as const,
        rankDir,
        nodeSep: 30,
        rankSep: isWaveOrdered ? 80 : 60,
        edgeSep: 10,
        ...(isWaveOrdered && {
          transform: (
            node: cytoscape.NodeSingular,
            pos: { x: number; y: number },
          ) => {
            const wave = (node.data("wave") as number) || 0;
            if (isTopBottom) {
              return { x: pos.x, y: -wave * WAVE_SPACING };
            }
            return { x: -wave * WAVE_SPACING, y: pos.y };
          },
        }),
      };

      const cy = cytoscape({
        container: containerRef.current,
        elements: [...elements, ...edges],
        style: [
          {
            selector: "node",
            style: {
              label: "data(label)",
              "background-color": "data(color)",
              color: "#e1e4ed",
              "text-valign": "center",
              "text-halign": "center",
              "font-size": "9px",
              "font-weight": 600,
              width: 50,
              height: 26,
              shape: "round-rectangle",
              "border-width": 1,
              "border-color": "data(color)",
              "text-outline-width": 0,
              "overlay-padding": 3,
            },
          },
          {
            selector: "node:selected",
            style: { "border-width": 2, "border-color": "#fff" },
          },
          {
            selector: "node.highlighted",
            style: {
              "border-width": 2,
              "border-color": "#fff",
              "z-index": 10,
            },
          },
          {
            selector: "node.dimmed",
            style: { opacity: 0.15 },
          },
          {
            selector: "edge",
            style: {
              width: 1.2,
              "line-color": "data(color)",
              "target-arrow-color": "data(color)",
              "target-arrow-shape": "triangle",
              "arrow-scale": 0.7,
              "curve-style": "bezier",
              "line-style": "data(lineStyle)" as unknown as
                | "solid"
                | "dashed"
                | "dotted",
              opacity: 0.6,
            },
          },
          {
            selector: "edge.highlighted",
            style: { width: 2.5, opacity: 1, "z-index": 10 },
          },
          {
            selector: "edge.dimmed",
            style: { opacity: 0.06 },
          },
        ],
        layout: layoutConfig,
        minZoom: 0.15,
        maxZoom: 4,
        wheelSensitivity: 1,
      });

      cyRef.current = cy;

      const tooltip = tooltipRef.current;
      if (!tooltip) return;

      cy.on("mouseover", "node", (evt) => {
        populateTooltip(tooltip, evt.target.data());
        tooltip.style.display = "block";
      });

      cy.on("mousemove", "node", (evt) => {
        const x = evt.originalEvent.clientX;
        const y = evt.originalEvent.clientY;
        const pad = 12;
        let left = x + pad;
        let top = y + pad;
        if (left + 360 > window.innerWidth) left = x - 360 - pad;
        if (top + 200 > window.innerHeight) top = y - 200 - pad;
        tooltip.style.left = `${left}px`;
        tooltip.style.top = `${top}px`;
      });

      cy.on("mouseout", "node", () => {
        tooltip.style.display = "none";
      });

      cy.on("tap", "node", (evt) => {
        const node = evt.target;
        setSelectedNodeId(String(node.id()));
        if (node.hasClass("highlighted")) {
          cy.elements().removeClass("highlighted dimmed");
          return;
        }
        const neighborhood = node.closedNeighborhood();
        cy.elements().removeClass("highlighted dimmed");
        cy.elements().not(neighborhood).addClass("dimmed");
        neighborhood.addClass("highlighted");
      });

      cy.on("tap", (evt) => {
        if (evt.target === cy) {
          setSelectedNodeId("");
          cy.elements().removeClass("highlighted dimmed");
        }
      });
    }

    initCytoscape();

    return () => {
      cancelled = true;
      if (cyRef.current) {
        cyRef.current.destroy();
        cyRef.current = null;
      }
    };
  }, [
    loading,
    filteredNodes,
    filteredRelations,
    colorScheme,
    layoutId,
    populateTooltip,
  ]);

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
            entityType: String(n.data("entityType") ?? ""),
            projectIdentifier: "",
            uid: n.data("uid") ? String(n.data("uid")) : null,
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
      <div className="flex items-center gap-4 border-b border-border bg-card px-4 py-2">
        <div className="flex items-center gap-2">
          <label
            htmlFor="graph-color-by"
            className="text-xs text-muted-foreground"
          >
            Color by
          </label>
          <select
            id="graph-color-by"
            value={colorScheme}
            onChange={(e) => setColorScheme(e.target.value as ColorScheme)}
            className="rounded border border-input bg-background px-2 py-1 text-xs text-foreground"
          >
            <option value="entity">Entity type</option>
            <option value="series">Series</option>
            <option value="priority">Priority</option>
            <option value="status">Status</option>
            <option value="wave">Wave</option>
          </select>
        </div>
        <div className="flex items-center gap-2">
          <label
            htmlFor="graph-layout"
            className="text-xs text-muted-foreground"
          >
            Layout
          </label>
          <select
            id="graph-layout"
            value={layoutId}
            onChange={(e) => setLayoutId(e.target.value as LayoutId)}
            className="rounded border border-input bg-background px-2 py-1 text-xs text-foreground"
          >
            <option value="dagre-lr">DAG (left to right)</option>
            <option value="dagre-tb">DAG (top to bottom)</option>
            <option value="dagre-wave-lr">Wave-ordered (L-R)</option>
            <option value="dagre-wave-tb">Wave-ordered (T-B)</option>
          </select>
        </div>
        <button
          type="button"
          onClick={handleFit}
          className="flex items-center gap-1 rounded border border-input bg-background px-2 py-1 text-xs hover:border-primary"
          title="Fit to screen"
        >
          <Maximize className="h-3 w-3" /> Fit
        </button>
        <button
          type="button"
          onClick={handleReset}
          className="flex items-center gap-1 rounded border border-input bg-background px-2 py-1 text-xs hover:border-primary"
          title="Reset filters"
        >
          <RotateCcw className="h-3 w-3" /> Reset
        </button>
        <button
          type="button"
          onClick={runTraversal}
          disabled={!selectedNodeId || loading}
          className="flex items-center gap-1 rounded border border-input bg-background px-2 py-1 text-xs hover:border-primary disabled:cursor-not-allowed disabled:opacity-50"
          title="Traverse two hops from the selected node"
        >
          <Maximize className="h-3 w-3" /> Focus selection
        </button>
        {viewMode === "traversal" && (
          <button
            type="button"
            onClick={fetchData}
            className="flex items-center gap-1 rounded border border-input bg-background px-2 py-1 text-xs hover:border-primary"
            title="Restore the full mixed-entity graph"
          >
            <RotateCcw className="h-3 w-3" /> Full graph
          </button>
        )}
      </div>

      {/* Filters */}
      {!loading && (
        <div className="flex items-center gap-4 border-b border-border bg-card px-4 py-1.5">
          <Filter className="h-3.5 w-3.5 text-muted-foreground" />
          <div className="flex items-center gap-2">
            <label
              htmlFor="graph-filter-entity-type"
              className="text-xs text-muted-foreground"
            >
              Entity
            </label>
            <select
              id="graph-filter-entity-type"
              value={filterEntityType}
              onChange={(e) => setFilterEntityType(e.target.value)}
              className="rounded border border-input bg-background px-2 py-0.5 text-xs text-foreground"
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
            <label
              htmlFor="graph-filter-status"
              className="text-xs text-muted-foreground"
            >
              Status
            </label>
            <select
              id="graph-filter-status"
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              className="rounded border border-input bg-background px-2 py-0.5 text-xs text-foreground"
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
            <label
              htmlFor="graph-filter-priority"
              className="text-xs text-muted-foreground"
            >
              Priority
            </label>
            <select
              id="graph-filter-priority"
              value={filterPriority}
              onChange={(e) => setFilterPriority(e.target.value)}
              className="rounded border border-input bg-background px-2 py-0.5 text-xs text-foreground"
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
              <label
                htmlFor="graph-filter-series"
                className="text-xs text-muted-foreground"
              >
                Series
              </label>
              <select
                id="graph-filter-series"
                value={filterSeries}
                onChange={(e) => setFilterSeries(e.target.value)}
                className="rounded border border-input bg-background px-2 py-0.5 text-xs text-foreground"
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
              <label
                htmlFor="graph-filter-wave"
                className="text-xs text-muted-foreground"
              >
                Wave
              </label>
              <select
                id="graph-filter-wave"
                value={filterWave}
                onChange={(e) => setFilterWave(e.target.value)}
                className="rounded border border-input bg-background px-2 py-0.5 text-xs text-foreground"
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
      )}

      {/* Stats */}
      {!loading && (
        <div className="flex gap-4 border-b border-border bg-card px-4 py-1.5 text-[11px]">
          <span className="text-muted-foreground">
            <strong className="text-foreground text-[13px] mr-0.5">
              {hasFilters
                ? `${filteredNodes.length} of ${stats.nodes}`
                : stats.nodes}
            </strong>
            nodes
          </span>
          <span className="text-muted-foreground">
            <strong className="text-foreground text-[13px] mr-0.5">
              {hasFilters
                ? `${filteredRelations.length} of ${stats.edges}`
                : stats.edges}
            </strong>
            edges
          </span>
          <span className="text-muted-foreground">
            <strong className="text-foreground text-[13px] mr-0.5">
              {stats.entityTypes}
            </strong>
            entity types
          </span>
          {stats.requirementCount > 0 && (
            <>
              <span className="text-muted-foreground">
                <strong className="text-foreground text-[13px] mr-0.5">
                  {stats.series}
                </strong>
                series
              </span>
              <span className="text-muted-foreground">
                <strong className="text-foreground text-[13px] mr-0.5">
                  {stats.waves}
                </strong>
                waves
              </span>
              <span className="text-muted-foreground">{stats.waveStr}</span>
            </>
          )}
          {selectedNode && (
            <span className="truncate text-muted-foreground">
              selected:{" "}
              <strong className="text-foreground">{selectedNode.id}</strong>
            </span>
          )}
        </div>
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
