import {
  type ColorScheme,
  type LayoutId,
  RELATION_STYLES,
  getNodeColor,
} from "@/lib/graph-constants";
import type cytoscape from "cytoscape";
import {
  type CytoscapeInstance,
  type GraphNodeData,
  type RelationData,
  WAVE_SPACING,
  getNodeDescription,
  getNodeDisplayLabel,
  getNodeEntityType,
  getNodePriority,
  getNodeRequirementType,
  getNodeSeries,
  getNodeStatement,
  getNodeStatus,
  getNodeTitle,
  getNodeWave,
  getStringProperty,
} from "./graph-node-data";

export interface GraphCanvasContext {
  colorScheme: ColorScheme;
  containerRef: React.RefObject<HTMLDivElement | null>;
  cyRef: React.RefObject<CytoscapeInstance | null>;
  filteredNodes: GraphNodeData[];
  filteredRelations: RelationData[];
  layoutId: LayoutId;
  loading: boolean;
  populateTooltip: (
    container: HTMLDivElement,
    d: Record<string, unknown>,
  ) => void;
  setSelectedNodeId: (id: string) => void;
  tooltipRef: React.RefObject<HTMLDivElement | null>;
}

export function renderGraphCanvas(
  deps: GraphCanvasContext,
): (() => void) | undefined {
  const {
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
  } = deps;

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
        name: getStringProperty(node, "name"),
        version: getStringProperty(node, "version"),
        threat: getStringProperty(node, "threat"),
        threatSource: getStringProperty(node, "threatSource"),
        threatEvent: getStringProperty(node, "threatEvent"),
        method: getStringProperty(node, "method"),
        effect: getStringProperty(node, "effect"),
        observationValue: getStringProperty(node, "observationValue"),
        // CONTROL / CONTROL_TEST
        controlFunction: getStringProperty(node, "controlFunction"),
        methodology: getStringProperty(node, "methodology"),
        conclusion: getStringProperty(node, "conclusion"),
        testerIdentity: getStringProperty(node, "testerIdentity"),
        controlUid: getStringProperty(node, "controlUid"),
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
        // RESEARCH / ARTIFACT_REFERENCE
        currentStage: getStringProperty(node, "currentStage"),
        autonomyLevel: getStringProperty(node, "autonomyLevel"),
        artifactType: getStringProperty(node, "artifactType"),
        artifactIdentifier: getStringProperty(node, "artifactIdentifier"),
        stage: getStringProperty(node, "stage"),
        kind: getStringProperty(node, "kind"),
        externalIdentifier: getStringProperty(node, "externalIdentifier"),
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
    const isTopBottom = layoutId === "dagre-tb" || layoutId === "dagre-wave-tb";
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
}
