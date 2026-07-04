#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const repoRoot = resolve(import.meta.dirname, "../..");
const buildSpecPath = resolve(repoRoot, "backend/build/contract/openapi.json");
const committedSpecPath = resolve(repoRoot, "contracts/openapi/openapi.json");
const generatedTypesPath = resolve(repoRoot, "contracts/gen/typescript/api.ts");
const frontendShimPath = resolve(repoRoot, "frontend/src/types/api.ts");

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sortObject(value) {
  if (Array.isArray(value)) {
    return value.map(sortObject);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, child]) => [key, sortObject(child)]),
    );
  }
  return value;
}

function writeGenerated(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text.endsWith("\n") ? text : `${text}\n`, "utf8");
}

function refName(ref) {
  const prefix = "#/components/schemas/";
  if (!ref?.startsWith(prefix)) {
    throw new Error(`Unsupported $ref: ${ref}`);
  }
  return ref.slice(prefix.length);
}

function stringLiteral(value) {
  return JSON.stringify(value);
}

function unionFromValues(values) {
  if (!Array.isArray(values) || values.length === 0) {
    return "string";
  }
  return values.map(stringLiteral).join(" | ");
}

function schemaType(schema) {
  if (!schema || typeof schema !== "object") {
    return "unknown";
  }
  if (schema.$ref) {
    return refName(schema.$ref);
  }
  if (schema.enum) {
    return unionFromValues(schema.enum);
  }
  if (schema.allOf) {
    return schema.allOf.map(schemaType).join(" & ");
  }
  if (schema.oneOf || schema.anyOf) {
    const variants = schema.oneOf ?? schema.anyOf;
    return variants.map(schemaType).join(" | ");
  }

  if (schema.type === "array") {
    return `${schemaType(schema.items)}[]`;
  }
  if (schema.type === "integer" || schema.type === "number") {
    return "number";
  }
  if (schema.type === "boolean") {
    return "boolean";
  }
  if (schema.type === "string") {
    return "string";
  }
  if (schema.type === "object" || schema.properties) {
    if (schema.additionalProperties) {
      const valueType =
        typeof schema.additionalProperties === "object"
          ? schemaType(schema.additionalProperties)
          : "unknown";
      return `Record<string, ${valueType}>`;
    }
    if (!schema.properties) {
      return "Record<string, unknown>";
    }
    const props = Object.entries(schema.properties)
      .map(([name, prop]) => `${JSON.stringify(name)}: ${schemaType(prop)};`)
      .join(" ");
    return `{ ${props} }`;
  }
  return "unknown";
}

function isRequestLike(name) {
  return (
    name.endsWith("Request") ||
    name.startsWith("Update") ||
    name.includes("TransitionRequest") ||
    name.endsWith("QueryRequest")
  );
}

const optionalResponseProps = {
  TimelineEntryResponse: new Set(["reason"]),
};

const schemaDeclarationOverrides = {
  ImportError: "export type ImportError = string;",
  SyncError: "export type SyncError = string;",
  WorkspaceAssessmentDto: "export type WorkspaceAssessmentDto = Record<string, any>;",
};

const exactPropertyTypes = {
  "TimelineEntryResponse.changeCategory": "ChangeCategory",
  "WorkspaceControlDto.queueReasons": "ControlWorkspaceQueueReason[]",
  "WorkspaceScenarioDto.reviewIndicator": "ScenarioReviewState",
};

function propertyType(schemaName, propName, propSchema) {
  const exact = exactPropertyTypes[`${schemaName}.${propName}`];
  if (exact) return exact;
  if (propSchema?.type === "array") {
    return `${schemaType(propSchema.items)}[]`;
  }
  if (propSchema?.additionalProperties) {
    return "Record<string, any>";
  }
  if (propSchema?.type === "object") {
    return "Record<string, any>";
  }
  if (propSchema?.$ref) {
    return refName(propSchema.$ref);
  }
  return "any";
}

function emitSchemaDeclaration(name, schema) {
  if (schemaDeclarationOverrides[name]) {
    return schemaDeclarationOverrides[name];
  }
  if (schema?.type === "object" || schema?.properties) {
    const required = new Set(schema.required ?? []);
    const requestLike = isRequestLike(name);
    const lines = [`export interface ${name} {`];
    lines.push("  [key: string]: any;");
    if (!schema.properties || Object.keys(schema.properties).length === 0) {
      lines.push("  _?: never;");
    } else {
      for (const [propName, propSchema] of Object.entries(schema.properties)) {
        const optional =
          (requestLike && !required.has(propName)) || optionalResponseProps[name]?.has(propName) ? "?" : "";
        lines.push(`  ${JSON.stringify(propName)}${optional}: ${propertyType(name, propName, propSchema)};`);
      }
    }
    lines.push("}");
    return lines.join("\n");
  }
  return `export type ${name} = ${schemaType(schema)};`;
}

function schemaProperty(spec, schemaName, propertyName) {
  const schema = spec.components?.schemas?.[schemaName];
  const property = schema?.properties?.[propertyName];
  if (!property) {
    throw new Error(`OpenAPI schema ${schemaName}.${propertyName} not found`);
  }
  return property;
}

function enumValues(spec, schemaName, propertyName) {
  const property = schemaProperty(spec, schemaName, propertyName);
  if (!property.enum) {
    throw new Error(`OpenAPI schema ${schemaName}.${propertyName} is not an enum`);
  }
  return property.enum;
}

const enumExports = [
  ["Status", "STATUSES", "RequirementResponse", "status"],
  ["RiskScenarioStatus", null, "RiskScenarioResponse", "status"],
  ["Priority", "PRIORITIES", "RequirementResponse", "priority"],
  ["ControlFunction", "CONTROL_FUNCTIONS", "WorkspaceControlDto", "controlFunction"],
  ["ControlStatus", "CONTROL_STATUSES", "WorkspaceControlDto", "status"],
  ["RequirementType", "REQUIREMENT_TYPES", "RequirementResponse", "requirementType"],
  ["RelationType", "RELATION_TYPES", "RelationResponse", "relationType"],
  ["ArtifactType", "ARTIFACT_TYPES", "TraceabilityLinkResponse", "artifactType"],
  ["LinkType", "LINK_TYPES", "TraceabilityLinkResponse", "linkType"],
  ["TestCaseStatus", "TEST_CASE_STATUSES", "TestCaseResponse", "status"],
  ["TestCaseType", "TEST_CASE_TYPES", "TestCaseResponse", "type"],
  ["TestCasePriority", "TEST_CASE_PRIORITIES", "TestCaseResponse", "priority"],
  ["TestCaseFormat", "TEST_CASE_FORMATS", "TestCaseResponse", "format"],
  ["TestPlanStatus", "TEST_PLAN_STATUSES", "TestPlanResponse", "status"],
  ["TestSuitePopulationMode", "TEST_SUITE_POPULATION_MODES", "TestSuiteResponse", "populationMode"],
  ["TestRunStatus", "TEST_RUN_STATUSES", "TestRunResponse", "status"],
  ["TestRunCaseResultStatus", "TEST_RUN_CASE_RESULT_STATUSES", "TestRunCaseResultResponse", "status"],
  ["AssetType", null, "WorkspaceAssetDto", "assetType"],
  ["ChangeCategory", "CHANGE_CATEGORIES", "TimelineEntryResponse", "changeCategory"],
  ["StrideCategory", null, "ThreatModelResponse", "stride"],
  ["ThreatModelStatus", null, "ThreatModelResponse", "status"],
  ["FindingType", null, "FindingResponse", "findingType"],
  ["FindingSeverity", null, "FindingResponse", "severity"],
  ["FindingStatus", null, "FindingResponse", "status"],
  ["VerificationStatus", "VERIFICATION_STATUSES", "VerificationResultResponse", "result"],
  ["AssuranceLevel", "ASSURANCE_LEVELS", "VerificationResultResponse", "assuranceLevel"],
  ["WorkflowRunFinalState", null, "WorkflowRunResponse", "finalState"],
  ["WorkflowRunOutcome", null, "WorkflowRunResponse", "outcome"],
  ["WorkflowRunProvenance", null, "WorkflowRunResponse", "provenance"],
];

const legacyAliases = [
  ["GraphNeighborhoodResponse", "GraphVisualizationResponse"],
  ["PackDependencyResponse", "PackDependency"],
  ["RegisteredControlPackEntryResponse", "RegisteredControlPackEntry"],
  ["TestCaseTreeNode", "TestCaseTreeNodeResponse"],
  ["TestCaseTreeLeaf", "TestCaseLeaf"],
  ["WorkspaceAsset", "WorkspaceAssetDto"],
  ["WorkspaceFlow", "WorkspaceFlowDto"],
  ["WorkspaceLink", "WorkspaceLinkDto"],
  ["WorkspaceThreatEntry", "WorkspaceThreatEntryDto"],
  ["EvidenceStateProvenanceSource", "ProvenanceSourceDto"],
  ["EvidenceStateArtifact", "EvidenceArtifactDto"],
  ["EvidenceStateObservation", "ObservationDto"],
  ["ControlWorkspaceScopedImplementation", "WorkspaceScopedImplementationDto"],
  ["ControlWorkspaceControlTest", "WorkspaceControlTestDto"],
  ["ControlWorkspaceAssessment", "WorkspaceAssessmentDto"],
  ["ControlWorkspaceEvidence", "WorkspaceEvidenceDto"],
  ["ControlWorkspaceFinding", "WorkspaceFindingDto"],
  ["ControlWorkspaceMappingEvidenceRef", "WorkspaceMappingEvidenceRefDto"],
  ["ControlWorkspaceRiskMapping", "WorkspaceRiskMappingDto"],
  ["ControlWorkspaceControl", "WorkspaceControlDto"],
  ["ControlAssuranceWorkspaceResponse", "ControlWorkspaceResponse"],
  ["MethodologyProfile", "MethodologyProfileResponse"],
  ["WorkspaceAssessment", "WorkspaceAssessmentDto"],
  ["WorkspaceTreatment", "WorkspaceTreatmentDto"],
  ["WorkspaceRegisterRef", "WorkspaceRegisterRefDto"],
  ["WorkspaceScenario", "WorkspaceScenarioDto"],
  ["WorkflowRunPhaseHotspot", "PhaseHotspotResponse"],
];

const broadLegacyTypes = [
  "SyncStatus",
  "RevisionType",
  "PackType",
  "CatalogStatus",
  "PackRegistryImportFormat",
  "GraphEntityType",
  "AssetCriticality",
  "AssetEnvironment",
  "AssetScope",
  "KnowledgeState",
  "NormalizedConcept",
  "CrosswalkVocabularySurface",
  "ThreatEventKind",
  "ThreatSourceRelevance",
  "NistLikelihoodBand",
  "NistImpactBand",
  "AuditType",
  "AuditStatus",
  "AuditPhaseKind",
  "AuditLinkTargetType",
  "AuditLinkType",
  "AssetRelationType",
  "FreshnessState",
  "EvidenceType",
  "EvidenceSourceKind",
  "ControlTestConclusion",
  "ControlEffectivenessRating",
  "ControlWorkspaceQueueReason",
  "MethodologyFamily",
  "RiskAssessmentApprovalStatus",
  "TreatmentPlanStatus",
  "TreatmentStrategy",
  "RiskRegisterStatus",
  "ScenarioReviewState",
  "TestCaseTreeNodeKind",
  "MappingControlRole",
];

const broadLegacyTypeValues = {
  SyncStatus: ["SYNCED", "STALE", "BROKEN"],
  FreshnessState: ["FRESH", "STALE", "EXPIRED", "SUPERSEDED", "NO_OBSERVATIONS"],
  EvidenceType: [
    "OBSERVATION_SUMMARY",
    "CONTROL_TEST_SUMMARY",
    "ASSURANCE_CONCLUSION",
    "VERIFICATION_SUMMARY",
    "ATTESTATION",
    "MIXED",
  ],
  EvidenceSourceKind: [
    "OBSERVATION",
    "CONTROL_TEST",
    "CONTROL_EFFECTIVENESS_ASSESSMENT",
    "VERIFICATION_RESULT",
    "RISK_ASSESSMENT_RESULT",
    "FINDING",
    "ATTESTATION",
    "EXTERNAL",
  ],
  ControlTestConclusion: ["EFFECTIVE", "INEFFECTIVE", "NOT_TESTED"],
  ControlEffectivenessRating: ["EFFECTIVE", "PARTIALLY_EFFECTIVE", "INEFFECTIVE"],
  ControlWorkspaceQueueReason: [
    "OWNER_MISSING",
    "STATUS_DRAFT",
    "TEST_EVIDENCE_MISSING",
    "ASSESSMENT_MISSING",
    "OPEN_EXCEPTION",
    "EFFECTIVENESS_WEAK",
    "CURRENT",
  ],
  RiskAssessmentApprovalStatus: ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED"],
  TreatmentPlanStatus: ["PLANNED", "IN_PROGRESS", "BLOCKED", "COMPLETED", "CANCELED"],
  TreatmentStrategy: ["MITIGATE", "ACCEPT", "TRANSFER", "SHARE", "AVOID", "OTHER"],
  RiskRegisterStatus: ["IDENTIFIED", "ANALYZING", "ASSESSED", "TREATING", "MONITORING", "ACCEPTED", "CLOSED"],
  ScenarioReviewState: ["REASSESSMENT_REQUIRED", "REVIEW_DUE", "EVIDENCE_STALE", "CURRENT", "NO_SIGNAL"],
};

const broadLegacyConstants = {
  PACK_REGISTRY_IMPORT_FORMATS: {
    type: "PackRegistryImportFormat",
    values: ["AUTO", "OSCAL_JSON", "GC_MANIFEST"],
  },
  MAPPING_CONTROL_ROLES: {
    type: "MappingControlRole",
    values: ["PREVENTIVE", "DETECTIVE", "CORRECTIVE", "DETERRENT", "COMPENSATING", "RECOVERY", "DIRECTIVE"],
  },
  ASSET_CRITICALITIES: {
    type: "AssetCriticality",
    values: ["CRITICAL", "HIGH", "MEDIUM", "LOW"],
  },
  ASSET_ENVIRONMENTS: {
    type: "AssetEnvironment",
    values: ["PRODUCTION", "STAGING", "DEVELOPMENT", "TEST", "NON_PRODUCTION", "OTHER"],
  },
  ASSET_SCOPES: {
    type: "AssetScope",
    values: ["IN_SCOPE", "OUT_OF_SCOPE"],
  },
  KNOWLEDGE_STATES: {
    type: "KnowledgeState",
    values: ["CONFIRMED", "PROVISIONAL", "UNKNOWN"],
  },
  NORMALIZED_CONCEPTS: {
    type: "NormalizedConcept",
    values: [
      "THREAT_SOURCE",
      "THREAT_EVENT",
      "VULNERABILITY_OR_EXPOSURE",
      "ASSET",
      "PROCESS_OR_OBJECTIVE",
      "CONSEQUENCE_OR_EFFECT",
      "CONTROL",
      "LIKELIHOOD_OR_FREQUENCY",
      "IMPACT_OR_LOSS_MAGNITUDE",
      "TREATMENT",
    ],
  },
  CROSSWALK_VOCABULARY_SURFACES: {
    type: "CrosswalkVocabularySurface",
    values: ["INPUT_SCHEMA", "OUTPUT_SCHEMA", "TREATMENT_STRATEGY_VOCABULARY"],
  },
  THREAT_EVENT_KINDS: {
    type: "ThreatEventKind",
    values: ["ADVERSARIAL", "NON_ADVERSARIAL"],
  },
  THREAT_SOURCE_RELEVANCES: {
    type: "ThreatSourceRelevance",
    values: ["CONFIRMED", "EXPECTED", "ANTICIPATED", "PREDICTED", "POSSIBLE", "NOT_APPLICABLE"],
  },
  NIST_LIKELIHOOD_BANDS: {
    type: "NistLikelihoodBand",
    values: ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"],
  },
  NIST_IMPACT_BANDS: {
    type: "NistImpactBand",
    values: ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"],
  },
  AUDIT_TYPES: {
    type: "AuditType",
    values: ["INTERNAL", "EXTERNAL", "REGULATORY", "SPECIAL"],
  },
  AUDIT_STATUSES: {
    type: "AuditStatus",
    values: ["PLANNED", "IN_PROGRESS", "DRAFT_REPORT", "FINAL_REPORT", "CLOSED"],
  },
  AUDIT_PHASE_KINDS: {
    type: "AuditPhaseKind",
    values: ["PLANNING", "FIELDWORK", "REPORTING", "FOLLOWUP"],
  },
  AUDIT_LINK_TARGET_TYPES: {
    type: "AuditLinkTargetType",
    values: ["FRAMEWORK", "ASSET", "CONTROL", "RISK_SCENARIO", "RISK_REGISTER_RECORD", "EVIDENCE", "FINDING", "EXTERNAL"],
  },
  AUDIT_LINK_TYPES: {
    type: "AuditLinkType",
    values: ["SCOPES", "ASSESSES", "EVIDENCED_BY", "FOLLOWS_UP_ON", "ASSOCIATED"],
  },
  METHODOLOGY_FAMILIES: {
    type: "MethodologyFamily",
    values: ["FAIR", "NIST_SP800_30_R1", "ISO_27005", "CUSTOM"],
  },
};

function emitGeneratedTypes(spec) {
  const schemas = spec.components?.schemas ?? {};
  const lines = [
    "/* eslint-disable */",
    "// @ts-nocheck",
    "// Generated by tools/contracts/generate-contracts.mjs from contracts/openapi/openapi.json.",
    "// Do not edit by hand; run `make contracts`.",
    "",
  ];

  for (const name of Object.keys(schemas).sort()) {
    lines.push(emitSchemaDeclaration(name, schemas[name]), "");
  }

  lines.push("export interface PagedResponse<T> {");
  lines.push("  totalPages: number;");
  lines.push("  totalElements: number;");
  lines.push("  size: number;");
  lines.push("  content: T[];");
  lines.push("  number: number;");
  lines.push("  sort?: unknown;");
  lines.push("  pageable?: unknown;");
  lines.push("  first?: boolean;");
  lines.push("  last?: boolean;");
  lines.push("  numberOfElements?: number;");
  lines.push("  empty?: boolean;");
  lines.push("}");
  lines.push("");

  for (const [alias, target] of legacyAliases) {
    lines.push(`export type ${alias} = ${target};`);
  }
  lines.push("");

  for (const typeName of broadLegacyTypes) {
    if (schemas[typeName]) continue;
    if (broadLegacyTypeValues[typeName]) continue;
    if (Object.values(broadLegacyConstants).some(({ type }) => type === typeName)) continue;
    lines.push(`export type ${typeName} = string;`);
  }
  lines.push("");

  for (const [typeName, values] of Object.entries(broadLegacyTypeValues)) {
    lines.push(`export type ${typeName} = ${unionFromValues(values)};`);
  }
  lines.push("");

  for (const [typeName, constName, schemaName, propertyName] of enumExports) {
    if (schemas[typeName]) continue;
    const values = enumValues(spec, schemaName, propertyName);
    lines.push(`export type ${typeName} = ${unionFromValues(values)};`);
    if (constName) {
      lines.push(`export const ${constName}: ${typeName}[] = ${JSON.stringify(values)};`);
    }
  }
  lines.push("");

  for (const [constName, { type: typeName, values }] of Object.entries(broadLegacyConstants)) {
    if (!lines.some((line) => line.startsWith(`export type ${typeName} `))) {
      lines.push(`export type ${typeName} = ${unionFromValues(values)};`);
    }
    if (lines.some((line) => line.startsWith(`export const ${constName}:`))) {
      continue;
    }
    lines.push(`export const ${constName}: ${typeName}[] = ${JSON.stringify(values)};`);
  }

  return `${lines.join("\n")}\n`;
}

function emitFrontendShim() {
  return `// Compatibility import surface for the web console.
// The API contract types are generated under contracts/gen/typescript/.
// Do not add hand-mirrored DTOs or enum vocabularies here.
export * from "../../../contracts/gen/typescript/api";
`;
}

const spec = readJson(buildSpecPath);
const canonicalSpec = sortObject(spec);
writeGenerated(committedSpecPath, `${JSON.stringify(canonicalSpec, null, 2)}\n`);
writeGenerated(generatedTypesPath, emitGeneratedTypes(canonicalSpec));
writeGenerated(frontendShimPath, emitFrontendShim());
