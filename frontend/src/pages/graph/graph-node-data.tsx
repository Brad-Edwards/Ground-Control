import { type ColorScheme, getSeries } from "@/lib/graph-constants";
import type {
  GraphEdgeResponse,
  GraphVisualizationNodeResponse,
} from "@/types/api";
import type cytoscape from "cytoscape";

export type GraphNodeData = GraphVisualizationNodeResponse;

export type RelationData = GraphEdgeResponse;

export type CytoscapeInstance = cytoscape.Core;

export const WAVE_SPACING = 120;

export function isRequirementNode(node: GraphNodeData): boolean {
  return node.entityType === "REQUIREMENT";
}

export function getNodeEntityType(node: GraphNodeData): string {
  return String(node.entityType ?? "UNKNOWN");
}

export function getStringProperty(node: GraphNodeData, key: string): string {
  const value = node.properties[key];
  return typeof value === "string" ? value : "";
}

function getNumberProperty(node: GraphNodeData, key: string): number {
  const value = node.properties[key];
  return typeof value === "number" ? value : 0;
}

export function getNodePriority(node: GraphNodeData): string {
  return getStringProperty(node, "priority");
}

export function getNodeStatus(node: GraphNodeData): string {
  return getStringProperty(node, "status");
}

export function getNodeRequirementType(node: GraphNodeData): string {
  return getStringProperty(node, "requirementType");
}

export function getNodeStatement(node: GraphNodeData): string {
  return getStringProperty(node, "statement");
}

export function getNodeTitle(node: GraphNodeData): string {
  const title = getStringProperty(node, "title");
  return title || node.label || node.uid || getNodeEntityType(node);
}

export function getNodeWave(node: GraphNodeData): number {
  return getNumberProperty(node, "wave");
}

export function getNodeSeries(node: GraphNodeData): string {
  if (!isRequirementNode(node)) {
    return getNodeEntityType(node);
  }
  return getSeries(node.uid || node.label);
}

export function getNodeDisplayLabel(node: GraphNodeData): string {
  if (isRequirementNode(node)) {
    return (node.label || node.uid || "REQ").replace("GC-", "");
  }
  if (getNodeEntityType(node) === "OBSERVATION") {
    return getStringProperty(node, "observationKey") || node.label || "OBS";
  }
  return node.uid || node.label || getNodeTitle(node);
}

export function getNodeLegendKey(
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

export function getNodeDescription(node: GraphNodeData): string {
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
  return "";
}

export function getTooltipValue(
  data: Record<string, unknown>,
  key: string,
): string {
  const value = data[key];
  return typeof value === "string" || typeof value === "number"
    ? String(value)
    : "";
}

export function firstTooltipString(
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

export const TOOLTIP_FIELDS_BY_ENTITY_TYPE: Record<
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
  WORKFLOW_RUN: [
    { label: "Workflow", key: "workflowType" },
    { label: "State", key: "finalState" },
    { label: "Outcome", key: "outcome" },
  ],
  WORK_ITEM_REFERENCE: [
    { label: "Repository", key: "repo" },
    { label: "Issue", key: "issueNumber" },
  ],
  ARTIFACT_REFERENCE: [
    { label: "Type", key: "artifactType" },
    { label: "Identifier", key: "artifactIdentifier" },
  ],
};
