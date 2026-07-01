// MCP–backend write-contract drift gate (issue #1106, ADR-034).
//
// Each inventory row describes one MCP write surface (tool + action) and the
// backend OpenAPI schema it must agree with. The test resolves the OpenAPI
// schema properties, converts each MCP snake_case field to camelCase, and
// asserts that every MCP field lands in the OpenAPI schema — and that every
// OpenAPI required field has a corresponding MCP field. Narrow exclusions with
// rationale handle fields that can't appear in the MCP allowlist (server-
// populated, path/query params, MCP-only control args, opaque maps, etc.).
//
// Run locally:
//   make mcp-openapi-contract
// or:
//   cd backend && JAVA_HOME=... ./gradlew generateContractOpenApi --no-daemon
//   node --test mcp/ground-control/openapi-contract.test.js

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve as resolvePath } from "node:path";

import {
  TO_CAMEL,
  OPAQUE_VALUE_KEYS,
  AUDIT_TYPES,
  CONTROL_FUNCTIONS,
  CONTROL_TEST_METHODOLOGIES,
  CONTROL_TEST_CONCLUSIONS,
  CONTROL_EFFECTIVENESS_RATINGS,
  METHODOLOGY_FAMILIES,
  METHODOLOGY_PROFILE_STATUSES,
  RISK_APPETITE_PROFILE_STATUSES,
  TREATMENT_PLAN_STATUSES,
  TREATMENT_STRATEGIES,
  VERIFICATION_STATUSES,
  ASSURANCE_LEVELS,
  FINDING_TYPES,
  FINDING_SEVERITIES,
  STRIDE_CATEGORIES,
  EVIDENCE_TYPES,
  OBSERVATION_CATEGORIES,
  ASSET_TYPES,
  ASSET_ENVIRONMENTS,
  ASSET_CRITICALITIES,
  ASSET_SCOPES,
  KNOWLEDGE_STATES,
  ARCHITECTURE_FLOW_DIRECTIONS,
  ARCHITECTURE_MODEL_ELEMENT_KINDS,
  ARCHITECTURE_MODEL_PROVENANCE_SOURCES,
  // Research run decision surfaces (GC-RSCH-F004/F034/N012/N013, ADR-066/067/068)
  RESEARCH_GATE_POINTS,
  RESEARCH_GATE_DECISION_OUTCOMES,
  GATE_RECOMMENDATION_PROVENANCES,
  REVIEW_COMMENT_TARGETS,
  REVIEW_COMMENT_PROVENANCES,
  RATIONALE_ENTRY_KINDS,
  RATIONALE_EVIDENCE_BASES,
  RATIONALE_PROVENANCES,
  RESEARCH_RUN_STAGES,
  RESEARCH_ARTIFACT_TYPES,
  DISCLOSURE_ENTRY_FAMILIES,
  DISCLOSURE_UNCERTAINTY_CATEGORIES,
  PROVENANCE_NODE_KINDS,
  PROVENANCE_EDGE_RELATIONS,
  // Methodology source coverage gate (GC-RSCH-F006)
  METHODOLOGY_SOURCE_STATES,
} from "./lib.js";

import {
  GC_AUDIT_CREATE_BODY_FIELDS,
  GC_AUDIT_UPDATE_BODY_FIELDS,
} from "./gc-audit.js";

import {
  GC_THREAT_MODEL_CREATE_BODY_FIELDS,
  GC_THREAT_MODEL_UPDATE_BODY_FIELDS,
} from "./gc-threat-model.js";

import {
  GC_RISK_SCENARIO_CREATE_BODY_FIELDS,
  GC_RISK_SCENARIO_UPDATE_BODY_FIELDS,
} from "./gc-risk-scenario.js";

import { CONTROL_FIELDS } from "./gc-control.js";

import { GC_EVIDENCE_BODY_FIELDS } from "./gc-evidence.js";

import {
  GC_FINDING_CREATE_BODY_FIELDS,
  GC_FINDING_UPDATE_BODY_FIELDS,
} from "./gc-finding.js";

import {
  GC_OBSERVATION_CREATE_FIELDS,
  GC_OBSERVATION_UPDATE_FIELDS,
} from "./gc-observation.js";

import {
  GC_ASSET_CREATE_FIELDS,
  GC_ASSET_UPDATE_FIELDS,
} from "./gc-asset.js";

import { LINK_CREATE_BODY_FIELDS } from "./link-create.js";

import {
  GC_ARCHITECTURE_MODEL_CREATE_SNAPSHOT_FIELDS,
  GC_ARCHITECTURE_MODEL_ELEMENT_FIELDS,
} from "./gc-architecture-model.js";

// GOVERNANCE_FIELDS lives in lib.js and is now exported.
// Import it directly to stay consistent with the contract test's reliance on
// the same field lists that the live tool uses.
import { GOVERNANCE_FIELDS } from "./lib.js";

// ---------------------------------------------------------------------------
// Load spec
// ---------------------------------------------------------------------------

const specPath =
  process.env.GC_OPENAPI_SPEC ||
  resolvePath(import.meta.dirname, "../../backend/build/contract/openapi.json");

let spec;
try {
  spec = JSON.parse(readFileSync(specPath, "utf-8"));
} catch {
  throw new Error(
    `OpenAPI spec not found at ${specPath}. ` +
      "Run 'make mcp-openapi-contract' to generate the OpenAPI spec.",
  );
}

// ---------------------------------------------------------------------------
// Schema resolution helpers
// ---------------------------------------------------------------------------

/**
 * Resolve a $ref chain within the spec components/schemas section.
 * Returns the resolved schema object.
 */
function resolveSchema(nameOrRef) {
  if (typeof nameOrRef !== "string") return nameOrRef;
  const name = nameOrRef.startsWith("#/components/schemas/")
    ? nameOrRef.slice("#/components/schemas/".length)
    : nameOrRef;
  const schema = spec.components?.schemas?.[name];
  if (!schema) throw new Error(`Schema not found: ${name}`);
  if (schema.$ref) return resolveSchema(schema.$ref);
  return schema;
}

/**
 * Collect all property names from a schema, following allOf/anyOf/oneOf and
 * $ref chains one level deep (sufficient for flat record DTOs).
 */
function schemaProps(nameOrRef) {
  const schema = resolveSchema(nameOrRef);
  const props = new Set(Object.keys(schema.properties ?? {}));
  for (const sub of [
    ...(schema.allOf ?? []),
    ...(schema.anyOf ?? []),
    ...(schema.oneOf ?? []),
  ]) {
    for (const p of Object.keys(resolveSchema(sub).properties ?? {})) {
      props.add(p);
    }
  }
  return props;
}

/**
 * Collect required fields from a schema, same composition as schemaProps.
 */
function schemaRequired(nameOrRef) {
  const schema = resolveSchema(nameOrRef);
  const req = new Set(schema.required ?? []);
  for (const sub of [
    ...(schema.allOf ?? []),
    ...(schema.anyOf ?? []),
    ...(schema.oneOf ?? []),
  ]) {
    for (const r of resolveSchema(sub).required ?? []) req.add(r);
  }
  return req;
}

/**
 * Extract the enum value array from a single property descriptor, following
 * $ref chains and checking items.$ref for array-of-enum properties.
 * Returns an array of strings, or null if the property has no enum.
 */
function getPropertyEnum(prop) {
  if (!prop) return null;
  if (prop.enum) return prop.enum;
  if (prop.items?.enum) return prop.items.enum;
  // Resolve $ref on the property itself
  if (prop.$ref) {
    const resolved = resolveSchema(prop.$ref);
    if (resolved?.enum) return resolved.enum;
  }
  // Resolve $ref on items (array-of-enum)
  if (prop.items?.$ref) {
    const resolved = resolveSchema(prop.items.$ref);
    if (resolved?.enum) return resolved.enum;
  }
  return null;
}

// ---------------------------------------------------------------------------
// camelCase conversion — matches lib.js logic
// ---------------------------------------------------------------------------

/**
 * Convert a snake_case field name to camelCase using the same TO_CAMEL table
 * that lib.js uses at runtime, with a simple underscore fallback for fields
 * not in the table.
 */
function toCamelCaseFallback(snake) {
  return snake.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
}

function snakeToCamel(snake) {
  return TO_CAMEL[snake] || toCamelCaseFallback(snake);
}

// ---------------------------------------------------------------------------
// Opaque-map detection helper
// ---------------------------------------------------------------------------

/**
 * Return true if the resolved OpenAPI property descriptor describes a
 * free-form map (Map<String,Object> in Java): i.e., the schema has
 * `additionalProperties` set OR is `type: "object"` with no declared
 * `properties` key.
 */
function isFreeFormMap(propDef) {
  if (!propDef || typeof propDef !== "object") return false;
  const resolved = propDef.$ref ? resolveSchema(propDef.$ref) : propDef;
  if (resolved.additionalProperties !== undefined) return true;
  if (resolved.type === "object" && !resolved.properties) return true;
  return false;
}

// ---------------------------------------------------------------------------
// Core assertion engine
// ---------------------------------------------------------------------------

/**
 * Run one contract row with STRICT BIDIRECTIONAL parity.
 *
 * @param {object}   opts
 * @param {string}   opts.label           Human-readable label (tool + action)
 * @param {string[]} opts.mcpFields       snake_case MCP allowlist
 * @param {string}   opts.openapiSchema   Schema name in components/schemas
 *
 * Directional exclusions (BOTH require a rationale string):
 * @param {object}   [opts.mcpOnly]       {[camelCaseField]: rationale}
 *   MCP forwards this field but it is intentionally NOT an OpenAPI request
 *   body property (path param, query param, MCP control/routing arg, etc.).
 * @param {object}   [opts.backendOnly]   {[camelCaseField]: rationale}
 *   The OpenAPI request schema has this property but the MCP intentionally
 *   does NOT expose it (server-populated, immutable via separate endpoint,
 *   etc.).
 *
 * @param {object}   [opts.manualFieldMap] {[snake]: camelOverride}
 *   Override the TO_CAMEL/fallback conversion for specific fields (needed
 *   when the adapter does manual conversion, e.g. gc-evidence.js).
 * @param {object}   [opts.enums]         {[camelCaseField]: string[]}
 *   MCP enum constant arrays keyed by camelCase field name. Every OpenAPI
 *   enum-typed property that is present in the MCP allowlist (and not in
 *   mcpOnly or enumExclusions) MUST have a matching entry here — the
 *   auto-detect enforces this so no enum field can silently drift.
 * @param {object}   [opts.enumExclusions] {[camelCaseField]: rationale}
 *   Fields whose OpenAPI property carries an enum but whose enum values
 *   cannot be compared at this level (e.g. nested within a complex array
 *   item). Must provide a rationale string.
 * @param {object}   [opts.opaqueExclusions] {[camelCaseField]: rationale}
 *   MCP body fields that map to a free-form OpenAPI map property but whose
 *   opaque-key protection is handled outside OPAQUE_VALUE_KEYS (e.g. via a
 *   rawBody path in the lib.js function, to avoid name-collision with a
 *   response field of the same name). Must provide a rationale string.
 */
function assertContractRow({
  label,
  mcpFields,
  openapiSchema,
  mcpOnly = {},
  backendOnly = {},
  manualFieldMap = {},
  enums = {},
  enumExclusions = {},
  opaqueExclusions = {},
}) {
  // Collect all properties from schema (including allOf/anyOf/oneOf) for
  // per-property inspection.
  function allSchemaProperties(nameOrRef) {
    const s = resolveSchema(nameOrRef);
    const result = { ...(s.properties ?? {}) };
    for (const sub of [
      ...(s.allOf ?? []),
      ...(s.anyOf ?? []),
      ...(s.oneOf ?? []),
    ]) {
      Object.assign(result, resolveSchema(sub).properties ?? {});
    }
    return result;
  }

  const schemaProperties = allSchemaProperties(openapiSchema);
  const props = schemaProps(openapiSchema); // Set<string> of all prop names

  // Build a set of MCP camelCase fields.
  const mcpCamelFields = new Set(
    mcpFields.map((s) => manualFieldMap[s] ?? snakeToCamel(s)),
  );

  // -------------------------------------------------------------------------
  // FORWARD assertion: every MCP field must be in OpenAPI props OR in mcpOnly.
  // -------------------------------------------------------------------------
  for (const snakeField of mcpFields) {
    const camel = manualFieldMap[snakeField] ?? snakeToCamel(snakeField);
    if (!props.has(camel) && !mcpOnly[camel]) {
      assert.fail(
        `[${label}] MCP field '${snakeField}' → '${camel}' not found in ` +
          `OpenAPI schema '${openapiSchema}' and has no mcpOnly exclusion. ` +
          `Available OpenAPI properties: ${[...props].join(", ")}. ` +
          `If this is a path/query/routing param, add it to mcpOnly with rationale.`,
      );
    }
  }

  // -------------------------------------------------------------------------
  // REVERSE assertion: every OpenAPI property (required AND optional) must be
  // in the MCP camelCase field set OR in backendOnly.
  // This is the strict strengthening — catches MCP missing a backend field.
  // -------------------------------------------------------------------------
  for (const apiField of props) {
    if (!mcpCamelFields.has(apiField) && !backendOnly[apiField]) {
      assert.fail(
        `[${label}] OpenAPI schema '${openapiSchema}' has property '${apiField}' ` +
          `(required=${schemaRequired(openapiSchema).has(apiField)}) ` +
          `that has no matching MCP allowlist entry. ` +
          `MCP camelCase fields: ${[...mcpCamelFields].join(", ")}. ` +
          `Either ADD '${apiField}' to the MCP allowlist (if MCP should expose it) ` +
          `OR add it to backendOnly with rationale (if it is server-populated, ` +
          `immutable, or otherwise not exposable via MCP).`,
      );
    }
  }

  // -------------------------------------------------------------------------
  // STRUCTURAL GATE: every MCP body field that maps to a free-form map in the
  // OpenAPI schema MUST be registered in OPAQUE_VALUE_KEYS (both snake_case
  // and camelCase) OR have an opaqueExclusions entry explaining why protection
  // is handled via another mechanism. This prevents user-provided inner keys
  // from being silently snake→camel mangled by toCamelCase().
  // -------------------------------------------------------------------------
  for (const snakeField of mcpFields) {
    const camel = manualFieldMap[snakeField] ?? snakeToCamel(snakeField);
    // Skip MCP-only fields that have no OpenAPI property to inspect.
    if (mcpOnly[camel]) continue;
    const propDef = schemaProperties[camel];
    if (!propDef) continue;
    if (isFreeFormMap(propDef)) {
      // If this field has a documented opaqueExclusion, the protection is
      // handled via an alternative mechanism (e.g. rawBody in the lib function).
      if (opaqueExclusions[camel]) continue;

      // Both the snake_case MCP arg name and the camelCase backend name must
      // be guarded. The snake form is what toCamelCase() sees on the way in;
      // the camel form is the renamed key. Both must be in OPAQUE_VALUE_KEYS.
      const snakeGuarded = OPAQUE_VALUE_KEYS.has(snakeField);
      const camelGuarded = OPAQUE_VALUE_KEYS.has(camel);
      if (!snakeGuarded || !camelGuarded) {
        const missing = [];
        if (!snakeGuarded) missing.push(`'${snakeField}' (snake_case form)`);
        if (!camelGuarded) missing.push(`'${camel}' (camelCase form)`);
        assert.fail(
          `[${label}] OpenAPI schema '${openapiSchema}' property '${camel}' ` +
            `is a free-form map (additionalProperties) but its MCP field is ` +
            `not registered in OPAQUE_VALUE_KEYS in lib.js. ` +
            `Missing: ${missing.join(", ")}. ` +
            `Add both forms to OPAQUE_VALUE_KEYS to prevent toCamelCase() ` +
            `from mangling user-provided inner keys, OR add an opaqueExclusions ` +
            `entry if protection is handled via another mechanism (e.g. rawBody).`,
        );
      }
    }
  }

  // -------------------------------------------------------------------------
  // Enum value-set comparison (GC-O013 clause a).
  //
  // For every MCP allowlist field that maps to an OpenAPI property with an
  // enum value set:
  //   1. The row MUST supply a matching entry in `enums` (or `enumExclusions`).
  //      Missing entries are caught here so no enum field can silently drift.
  //   2. The supplied MCP constant array MUST equal the OpenAPI enum set
  //      (order-independent sorted comparison).
  // -------------------------------------------------------------------------

  for (const camelField of mcpCamelFields) {
    // Skip MCP-only fields — those have no OpenAPI property to check.
    if (mcpOnly[camelField]) continue;

    const propDef = schemaProperties[camelField];
    if (!propDef) continue; // already caught by forward check above

    const openApiEnum = getPropertyEnum(propDef);
    if (!openApiEnum) continue; // property carries no enum — nothing to compare

    if (enumExclusions[camelField]) continue; // documented nested/complex exclusion

    if (!Object.prototype.hasOwnProperty.call(enums, camelField)) {
      assert.fail(
        `[${label}] OpenAPI schema '${openapiSchema}' property '${camelField}' ` +
          `has enum values ${JSON.stringify(openApiEnum)} but no 'enums.${camelField}' ` +
          `entry was provided for this contract row. ` +
          `Add the MCP enum constant array to the 'enums' option, or add a ` +
          `rationale entry to 'enumExclusions' if comparison is not feasible at this level.`,
      );
    }

    // Order-independent set equality.
    const mcpEnum = enums[camelField];
    const sortedMcp = [...mcpEnum].sort();
    const sortedApi = [...openApiEnum].sort();
    const mcpOnlyVals = sortedMcp.filter((v) => !openApiEnum.includes(v));
    const apiOnlyVals = sortedApi.filter((v) => !mcpEnum.includes(v));

    if (mcpOnlyVals.length > 0 || apiOnlyVals.length > 0) {
      assert.fail(
        `[${label}] Enum drift on '${openapiSchema}.${camelField}': ` +
          (mcpOnlyVals.length > 0
            ? `MCP-only values: ${JSON.stringify(mcpOnlyVals)}. `
            : "") +
          (apiOnlyVals.length > 0
            ? `OpenAPI-only values: ${JSON.stringify(apiOnlyVals)}. `
            : "") +
          `Fix the MCP constant in lib.js to match the backend, ` +
          `or record a narrow enumExclusion with rationale if the MCP ` +
          `intentionally exposes a subset.`,
      );
    }
  }
}

// Overload: shorter alias used in describe blocks.
function assertRow(opts) {
  it(`${opts.label}`, () => assertContractRow(opts));
}

// ---------------------------------------------------------------------------
// Inventory
// ---------------------------------------------------------------------------

// Common MCP-only routing/control args that appear in every MCP tool's Zod
// shape but are never request-body properties. Applied to mcpOnly on every row.
const MCP_CONTROL_ARGS = {
  action: "MCP-only routing arg — never in the request body",
  entity: "MCP-only routing arg — never in the request body",
  id: "UUID of the entity being operated on — path param, not body",
  project: "MCP project routing arg — query param, not body",
};

describe("MCP–OpenAPI write-contract", () => {
  // -------------------------------------------------------------------------
  // gc_audit
  // -------------------------------------------------------------------------

  describe("gc_audit create → AuditRequest", () => {
    assertRow({
      label: "gc_audit/create",
      mcpFields: GC_AUDIT_CREATE_BODY_FIELDS,
      openapiSchema: "AuditRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        auditType: AUDIT_TYPES,
      },
    });
  });

  describe("gc_audit update → UpdateAuditRequest", () => {
    assertRow({
      label: "gc_audit/update",
      mcpFields: GC_AUDIT_UPDATE_BODY_FIELDS,
      openapiSchema: "UpdateAuditRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        auditType: AUDIT_TYPES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_threat_model
  // -------------------------------------------------------------------------

  describe("gc_threat_model create → ThreatModelRequest", () => {
    assertRow({
      label: "gc_threat_model/create",
      mcpFields: GC_THREAT_MODEL_CREATE_BODY_FIELDS,
      openapiSchema: "ThreatModelRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        // stride_category → stride via TO_CAMEL; backend field is ThreatModelRequest.stride
        stride: STRIDE_CATEGORIES,
      },
    });
  });

  describe("gc_threat_model update → UpdateThreatModelRequest", () => {
    assertRow({
      label: "gc_threat_model/update",
      mcpFields: GC_THREAT_MODEL_UPDATE_BODY_FIELDS,
      openapiSchema: "UpdateThreatModelRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        stride: STRIDE_CATEGORIES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_scenario
  // -------------------------------------------------------------------------

  describe("gc_risk_scenario create → RiskScenarioRequest", () => {
    assertRow({
      label: "gc_risk_scenario/create",
      mcpFields: GC_RISK_SCENARIO_CREATE_BODY_FIELDS,
      openapiSchema: "RiskScenarioRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  describe("gc_risk_scenario update → UpdateRiskScenarioRequest", () => {
    assertRow({
      label: "gc_risk_scenario/update",
      mcpFields: GC_RISK_SCENARIO_UPDATE_BODY_FIELDS,
      openapiSchema: "UpdateRiskScenarioRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_control — control entity
  // -------------------------------------------------------------------------

  describe("gc_control/control create → ControlRequest", () => {
    assertRow({
      label: "gc_control/control/create",
      mcpFields: CONTROL_FIELDS.control.create,
      openapiSchema: "ControlRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        controlFunction: CONTROL_FUNCTIONS,
      },
    });
  });

  describe("gc_control/control update → UpdateControlRequest", () => {
    assertRow({
      label: "gc_control/control/update",
      mcpFields: CONTROL_FIELDS.control.update,
      openapiSchema: "UpdateControlRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        controlFunction: CONTROL_FUNCTIONS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_control — control_test entity
  // -------------------------------------------------------------------------

  describe("gc_control/control_test create → ControlTestRequest", () => {
    assertRow({
      label: "gc_control/control_test/create",
      mcpFields: CONTROL_FIELDS.control_test.create,
      openapiSchema: "ControlTestRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        methodology: CONTROL_TEST_METHODOLOGIES,
        conclusion: CONTROL_TEST_CONCLUSIONS,
      },
    });
  });

  describe("gc_control/control_test update → UpdateControlTestRequest", () => {
    assertRow({
      label: "gc_control/control_test/update",
      mcpFields: CONTROL_FIELDS.control_test.update,
      openapiSchema: "UpdateControlTestRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        methodology: CONTROL_TEST_METHODOLOGIES,
        conclusion: CONTROL_TEST_CONCLUSIONS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_control — control_effectiveness_assessment entity
  // -------------------------------------------------------------------------

  describe("gc_control/control_effectiveness_assessment create → ControlEffectivenessAssessmentRequest", () => {
    assertRow({
      label: "gc_control/control_effectiveness_assessment/create",
      mcpFields: CONTROL_FIELDS.control_effectiveness_assessment.create,
      openapiSchema: "ControlEffectivenessAssessmentRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        designEffectiveness: CONTROL_EFFECTIVENESS_RATINGS,
        operatingEffectiveness: CONTROL_EFFECTIVENESS_RATINGS,
      },
    });
  });

  describe("gc_control/control_effectiveness_assessment update → UpdateControlEffectivenessAssessmentRequest", () => {
    assertRow({
      label: "gc_control/control_effectiveness_assessment/update",
      mcpFields: CONTROL_FIELDS.control_effectiveness_assessment.update,
      openapiSchema: "UpdateControlEffectivenessAssessmentRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        designEffectiveness: CONTROL_EFFECTIVENESS_RATINGS,
        operatingEffectiveness: CONTROL_EFFECTIVENESS_RATINGS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_evidence — create / supersede share the same body fields
  // -------------------------------------------------------------------------

  describe("gc_evidence create/supersede → EvidenceArtifactRequest", () => {
    assertRow({
      label: "gc_evidence/create",
      mcpFields: GC_EVIDENCE_BODY_FIELDS,
      openapiSchema: "EvidenceArtifactRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      // gc-evidence.js does manual camelCase conversion in toCreateBody()
      // instead of using toCamelCase(). Map those explicitly.
      manualFieldMap: {
        evidence_type: "evidenceType",
        derivation_method: "derivationMethod",
        derived_at: "derivedAt",
        assurance_level: "assuranceLevel",
      },
      enums: {
        evidenceType: EVIDENCE_TYPES,
        assuranceLevel: ASSURANCE_LEVELS,
      },
      enumExclusions: {
        // EvidenceArtifactRequest.sources is an array of EvidenceSourceRefDto
        // objects, each carrying a sourceKind enum. The enum lives inside the
        // items.$ref schema (EvidenceSourceRefDto.sourceKind), not at the
        // top-level property. Flat enum comparison is not applicable here;
        // drift on EVIDENCE_SOURCE_KINDS is covered by the Zod schema in
        // gc-evidence.js and the adapter's runtime validation.
        sources:
          "Array of EvidenceSourceRefDto — sourceKind enum is nested inside items.$ref; not comparable at top-level property.",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_finding
  // -------------------------------------------------------------------------

  describe("gc_finding create → FindingRequest", () => {
    assertRow({
      label: "gc_finding/create",
      mcpFields: GC_FINDING_CREATE_BODY_FIELDS,
      openapiSchema: "FindingRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        findingType: FINDING_TYPES,
        severity: FINDING_SEVERITIES,
      },
    });
  });

  describe("gc_finding update → UpdateFindingRequest", () => {
    assertRow({
      label: "gc_finding/update",
      mcpFields: GC_FINDING_UPDATE_BODY_FIELDS,
      openapiSchema: "UpdateFindingRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        findingType: FINDING_TYPES,
        severity: FINDING_SEVERITIES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_observation
  // -------------------------------------------------------------------------

  describe("gc_observation create → ObservationRequest", () => {
    assertRow({
      label: "gc_observation/create",
      mcpFields: GC_OBSERVATION_CREATE_FIELDS,
      openapiSchema: "ObservationRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        // asset_id is the path param /assets/{assetId}/observations — the
        // backend resolves it from the URL, not the JSON body.
        assetId: "path parameter /assets/{assetId} — not a body field",
      },
      enums: {
        category: OBSERVATION_CATEGORIES,
      },
    });
  });

  describe("gc_observation update → UpdateObservationRequest", () => {
    assertRow({
      label: "gc_observation/update",
      mcpFields: GC_OBSERVATION_UPDATE_FIELDS,
      openapiSchema: "UpdateObservationRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        assetId: "path parameter /assets/{assetId} — not a body field",
      },
      // UpdateObservationRequest carries no enum-typed properties;
      // category is create-only and absent from UpdateObservationRequest.
    });
  });

  // -------------------------------------------------------------------------
  // gc_asset — split field lists: GC_ASSET_CREATE_FIELDS and GC_ASSET_UPDATE_FIELDS
  // AssetRequest (create) has uid + no clear_* flags.
  // UpdateAssetRequest (update) has clear_* flags + no uid.
  // -------------------------------------------------------------------------

  describe("gc_asset create → AssetRequest", () => {
    assertRow({
      label: "gc_asset/create",
      mcpFields: GC_ASSET_CREATE_FIELDS,
      openapiSchema: "AssetRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        assetType: ASSET_TYPES,
        environment: ASSET_ENVIRONMENTS,
        criticality: ASSET_CRITICALITIES,
        scopeDesignation: ASSET_SCOPES,
        knowledgeState: KNOWLEDGE_STATES,
      },
    });
  });

  describe("gc_asset update → UpdateAssetRequest", () => {
    assertRow({
      label: "gc_asset/update",
      mcpFields: GC_ASSET_UPDATE_FIELDS,
      openapiSchema: "UpdateAssetRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        assetType: ASSET_TYPES,
        environment: ASSET_ENVIRONMENTS,
        criticality: ASSET_CRITICALITIES,
        scopeDesignation: ASSET_SCOPES,
        knowledgeState: KNOWLEDGE_STATES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_architecture_model — snapshot aggregate with nested element requests
  // -------------------------------------------------------------------------

  describe("gc_architecture_model create_snapshot → ArchitectureModelSnapshotRequest", () => {
    assertRow({
      label: "gc_architecture_model/create_snapshot",
      mcpFields: GC_ARCHITECTURE_MODEL_CREATE_SNAPSHOT_FIELDS,
      openapiSchema: "ArchitectureModelSnapshotRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  describe("gc_architecture_model create_snapshot elements → ArchitectureModelElementRequest", () => {
    assertRow({
      label: "gc_architecture_model/create_snapshot/elements",
      mcpFields: GC_ARCHITECTURE_MODEL_ELEMENT_FIELDS,
      openapiSchema: "ArchitectureModelElementRequest",
      enums: {
        elementKind: ARCHITECTURE_MODEL_ELEMENT_KINDS,
        flowDirection: ARCHITECTURE_FLOW_DIRECTIONS,
        provenanceSource: ARCHITECTURE_MODEL_PROVENANCE_SOURCES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_governance — methodology_profile
  // -------------------------------------------------------------------------

  describe("gc_risk_governance/methodology_profile create → MethodologyProfileRequest", () => {
    assertRow({
      label: "gc_risk_governance/methodology_profile/create",
      mcpFields: GOVERNANCE_FIELDS.methodology_profile.create,
      openapiSchema: "MethodologyProfileRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        family: METHODOLOGY_FAMILIES,
        status: METHODOLOGY_PROFILE_STATUSES,
      },
    });
  });

  describe("gc_risk_governance/methodology_profile update → UpdateMethodologyProfileRequest", () => {
    assertRow({
      label: "gc_risk_governance/methodology_profile/update",
      mcpFields: GOVERNANCE_FIELDS.methodology_profile.update,
      openapiSchema: "UpdateMethodologyProfileRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        family: METHODOLOGY_FAMILIES,
        status: METHODOLOGY_PROFILE_STATUSES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_governance — risk_register_record
  // -------------------------------------------------------------------------

  describe("gc_risk_governance/risk_register_record create → RiskRegisterRecordRequest", () => {
    assertRow({
      label: "gc_risk_governance/risk_register_record/create",
      mcpFields: GOVERNANCE_FIELDS.risk_register_record.create,
      openapiSchema: "RiskRegisterRecordRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  describe("gc_risk_governance/risk_register_record update → UpdateRiskRegisterRecordRequest", () => {
    assertRow({
      label: "gc_risk_governance/risk_register_record/update",
      mcpFields: GOVERNANCE_FIELDS.risk_register_record.update,
      openapiSchema: "UpdateRiskRegisterRecordRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_governance — risk_assessment_result
  // -------------------------------------------------------------------------

  describe("gc_risk_governance/risk_assessment_result create → RiskAssessmentResultRequest", () => {
    assertRow({
      label: "gc_risk_governance/risk_assessment_result/create",
      mcpFields: GOVERNANCE_FIELDS.risk_assessment_result.create,
      openapiSchema: "RiskAssessmentResultRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  describe("gc_risk_governance/risk_assessment_result update → UpdateRiskAssessmentResultRequest", () => {
    assertRow({
      label: "gc_risk_governance/risk_assessment_result/update",
      mcpFields: GOVERNANCE_FIELDS.risk_assessment_result.update,
      openapiSchema: "UpdateRiskAssessmentResultRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        // risk_scenario_id is in the update MCP allowlist but is absent from
        // UpdateRiskAssessmentResultRequest (create-only FK). It is forwarded
        // on update but the backend ignores it.
        riskScenarioId:
          "create-only FK in RiskAssessmentResultRequest — UpdateRiskAssessmentResultRequest has no riskScenarioId; MCP update allowlist retains it for symmetry but backend ignores it",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_governance — treatment_plan
  // -------------------------------------------------------------------------

  describe("gc_risk_governance/treatment_plan create → TreatmentPlanRequest", () => {
    assertRow({
      label: "gc_risk_governance/treatment_plan/create",
      mcpFields: GOVERNANCE_FIELDS.treatment_plan.create,
      openapiSchema: "TreatmentPlanRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        strategy: TREATMENT_STRATEGIES,
        status: TREATMENT_PLAN_STATUSES,
      },
    });
  });

  describe("gc_risk_governance/treatment_plan update → UpdateTreatmentPlanRequest", () => {
    assertRow({
      label: "gc_risk_governance/treatment_plan/update",
      mcpFields: GOVERNANCE_FIELDS.treatment_plan.update,
      openapiSchema: "UpdateTreatmentPlanRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        // riskRegisterRecordId is in the update MCP allowlist (from create) but
        // is absent from UpdateTreatmentPlanRequest — treatment plans are owned
        // by their register record for life; the FK is create-only.
        riskRegisterRecordId:
          "create-only required FK — UpdateTreatmentPlanRequest has no riskRegisterRecordId; MCP update allowlist retains it but backend ignores it",
      },
      enums: {
        strategy: TREATMENT_STRATEGIES,
        // status is absent from UpdateTreatmentPlanRequest; no enum check needed.
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_governance — verification_result
  // -------------------------------------------------------------------------

  describe("gc_risk_governance/verification_result create → VerificationResultRequest", () => {
    assertRow({
      label: "gc_risk_governance/verification_result/create",
      mcpFields: GOVERNANCE_FIELDS.verification_result.create,
      openapiSchema: "VerificationResultRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        result: VERIFICATION_STATUSES,
        assuranceLevel: ASSURANCE_LEVELS,
      },
      opaqueExclusions: {
        // VerificationResultRequest.evidence is a Map<String,Object> whose
        // inner keys are tool/verifier identifiers supplied by the caller.
        // The field name "evidence" is also used as a structured array field
        // in analysis responses (toSnakeCase path), so adding it to the global
        // OPAQUE_VALUE_KEYS would block recursive snake-casing of those
        // response arrays. Instead, createVerificationResult() and
        // updateVerificationResult() in lib.js build the camelCase body
        // explicitly via rawBody, preserving evidence inner keys without
        // touching the global OPAQUE_VALUE_KEYS registry.
        evidence:
          "Map<String,Object> protected via rawBody path in createVerificationResult/updateVerificationResult " +
          "to avoid OPAQUE_VALUE_KEYS name collision with analysis-response evidence arrays.",
      },
    });
  });

  describe("gc_risk_governance/verification_result update → UpdateVerificationResultRequest", () => {
    assertRow({
      label: "gc_risk_governance/verification_result/update",
      mcpFields: GOVERNANCE_FIELDS.verification_result.update,
      openapiSchema: "UpdateVerificationResultRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        result: VERIFICATION_STATUSES,
        assuranceLevel: ASSURANCE_LEVELS,
      },
      opaqueExclusions: {
        evidence:
          "Map<String,Object> protected via rawBody path in createVerificationResult/updateVerificationResult " +
          "to avoid OPAQUE_VALUE_KEYS name collision with analysis-response evidence arrays.",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_risk_governance — risk_appetite_profile (GC-T005)
  // -------------------------------------------------------------------------

  describe("gc_risk_governance/risk_appetite_profile create → RiskAppetiteProfileRequest", () => {
    assertRow({
      label: "gc_risk_governance/risk_appetite_profile/create",
      mcpFields: GOVERNANCE_FIELDS.risk_appetite_profile.create,
      openapiSchema: "RiskAppetiteProfileRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        methodologyFamily: METHODOLOGY_FAMILIES,
        status: RISK_APPETITE_PROFILE_STATUSES,
      },
    });
  });

  describe("gc_risk_governance/risk_appetite_profile update → UpdateRiskAppetiteProfileRequest", () => {
    assertRow({
      label: "gc_risk_governance/risk_appetite_profile/update",
      mcpFields: GOVERNANCE_FIELDS.risk_appetite_profile.update,
      openapiSchema: "UpdateRiskAppetiteProfileRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        methodologyFamily: METHODOLOGY_FAMILIES,
        status: RISK_APPETITE_PROFILE_STATUSES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // link_create (shared across asset / threat_model / risk_scenario / control)
  // -------------------------------------------------------------------------
  // LINK_CREATE_BODY_FIELDS is used by every consolidated tool's link_create
  // action. The backend link DTOs all share the same shape (AssetLinkRequest,
  // ThreatModelLinkRequest, etc. are structurally identical). We validate
  // against AssetLinkRequest as the canonical representative.
  // TraceabilityLinkRequest is a separate endpoint (GC traceability links)
  // with a different shape (artifactType/artifactIdentifier) — not this surface.

  describe("link_create → AssetLinkRequest (canonical representative)", () => {
    assertRow({
      label: "shared/link_create",
      mcpFields: LINK_CREATE_BODY_FIELDS,
      openapiSchema: "AssetLinkRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enumExclusions: {
        // targetType: each consolidated tool (gc_asset, gc_control, gc_risk_scenario,
        // gc_threat_model, gc_finding, gc_audit) restricts target_type in its own Zod
        // schema to a per-entity subset (ASSET_LINK_TARGET_TYPES, CONTROL_LINK_TARGET_TYPES,
        // etc.). The OpenAPI representative (AssetLinkRequest) carries the full backend
        // TargetType enum, which differs schema-by-schema. A single flat comparison here
        // would either false-fail (MCP subset ≠ full enum) or require merging all
        // per-entity sets. Enum correctness is enforced by each tool's Zod validation.
        targetType:
          "Per-entity subset — each tool's Zod schema constrains target_type independently; " +
          "AssetLinkRequest.targetType carries the full TargetType union, not the asset subset.",
        // linkType: same pattern — each tool exposes its own valid link type vocabulary.
        // AssetLinkRequest.linkType (IMPLEMENTS/MITIGATES/SUBJECT_OF/…) differs from
        // other entities' schemas, and the MCP enforces correctness per-tool via Zod.
        linkType:
          "Per-entity subset — link type vocabulary differs across entity schemas; " +
          "enforced per-tool by Zod validation rather than at this shared row level.",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — gate decision (extended with recommendation fields, ADR-066)
  // -------------------------------------------------------------------------

  describe("gc_research_run/gate_decision → GateDecisionRequest", () => {
    assertRow({
      label: "gc_research_run/gate_decision",
      mcpFields: [
        "gate_point", "outcome", "selected_option_id", "rationale_summary",
        "recommendation_option_id", "recommendation_summary",
        "recommendation_provenance", "question_key", "source_action_id",
      ],
      openapiSchema: "GateDecisionRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        gatePoint: RESEARCH_GATE_POINTS,
        outcome: RESEARCH_GATE_DECISION_OUTCOMES,
        recommendationProvenance: GATE_RECOMMENDATION_PROVENANCES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — add_review_comment (ADR-067)
  // -------------------------------------------------------------------------

  describe("gc_research_run/add_review_comment → AddReviewCommentRequest", () => {
    assertRow({
      label: "gc_research_run/add_review_comment",
      mcpFields: [
        "target_type", "target_gate_point", "target_stage",
        "target_artifact_id", "target_decision_log_id", "body", "provenance",
      ],
      openapiSchema: "AddReviewCommentRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      enums: {
        targetType: REVIEW_COMMENT_TARGETS,
        targetGatePoint: RESEARCH_GATE_POINTS,
        targetStage: RESEARCH_RUN_STAGES,
        provenance: REVIEW_COMMENT_PROVENANCES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — resolve_review_comment (ADR-067)
  // -------------------------------------------------------------------------

  describe("gc_research_run/resolve_review_comment → ResolveReviewCommentRequest", () => {
    assertRow({
      label: "gc_research_run/resolve_review_comment",
      mcpFields: ["resolution_summary"],
      openapiSchema: "ResolveReviewCommentRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        commentId: "path param /review-comments/{commentId} — not a body field",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — add_rationale (ADR-068)
  // -------------------------------------------------------------------------

  describe("gc_research_run/add_rationale → AddRationaleEntryRequest", () => {
    assertRow({
      label: "gc_research_run/add_rationale",
      mcpFields: [
        "stage", "artifact_type", "target_artifact_id", "attempt_no",
        "gate_point", "kind", "evidence_basis", "rationale_provenance",
        "subject_key", "rationale_summary", "evidence_locator", "confidence_summary",
      ],
      openapiSchema: "AddRationaleEntryRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      // target_artifact_id → artifactId and rationale_provenance → provenance
      // don't follow standard snake→camel conversion
      manualFieldMap: {
        target_artifact_id: "artifactId",
        rationale_provenance: "provenance",
        evidence_basis: "evidenceBasis",
        evidence_locator: "evidenceLocator",
        confidence_summary: "confidenceSummary",
        subject_key: "subjectKey",
        rationale_summary: "rationaleSummary",
        artifact_type: "artifactType",
        gate_point: "gatePoint",
        attempt_no: "attemptNo",
      },
      enums: {
        stage: RESEARCH_RUN_STAGES,
        artifactType: RESEARCH_ARTIFACT_TYPES,
        gatePoint: RESEARCH_GATE_POINTS,
        kind: RATIONALE_ENTRY_KINDS,
        evidenceBasis: RATIONALE_EVIDENCE_BASES,
        provenance: RATIONALE_PROVENANCES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — create_disclosure (ADR-068 §4)
  // -------------------------------------------------------------------------

  describe("gc_research_run/create_disclosure → CreateDisclosureRequest", () => {
    assertRow({
      label: "gc_research_run/create_disclosure",
      mcpFields: [
        "final_artifact_id", "final_attempt_no",
        "ai_parts_declared_none", "uncertainty_declared_none",
        "human_approvals_declared_none",
      ],
      openapiSchema: "CreateDisclosureRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — add_disclosure_entry (ADR-068 §4)
  // -------------------------------------------------------------------------

  describe("gc_research_run/add_disclosure_entry → AddDisclosureEntryRequest", () => {
    assertRow({
      label: "gc_research_run/add_disclosure_entry",
      mcpFields: [
        "family", "uncertainty_category", "section_key", "locator",
        "model_label", "summary", "rationale_entry_id", "decision_log_id", "review_comment_id",
      ],
      openapiSchema: "AddDisclosureEntryRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        disclosureId: "path param /disclosure/{disclosureId} — not a body field",
      },
      enums: {
        family: DISCLOSURE_ENTRY_FAMILIES,
        uncertaintyCategory: DISCLOSURE_UNCERTAINTY_CATEGORIES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_provenance — record_node (ADR-069 §2)
  // -------------------------------------------------------------------------

  describe("gc_research_provenance/record_node → ProvenanceNodeRequest", () => {
    assertRow({
      label: "gc_research_provenance/record_node",
      mcpFields: [
        "kind", "subject_key", "stage", "artifact_type", "artifact_id", "attempt_no",
        "locator", "content_hash", "external_identifier", "summary",
        "tool_name", "tool_version", "source_action_id", "idempotency_key",
      ],
      openapiSchema: "ProvenanceNodeRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        runId: "path param /research-runs/{runId} — not a body field",
      },
      enums: {
        kind: PROVENANCE_NODE_KINDS,
        stage: RESEARCH_RUN_STAGES,
        artifactType: RESEARCH_ARTIFACT_TYPES,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_provenance — record_edge (ADR-069 §2)
  // -------------------------------------------------------------------------

  describe("gc_research_provenance/record_edge → ProvenanceEdgeRequest", () => {
    assertRow({
      label: "gc_research_provenance/record_edge",
      mcpFields: ["from_node_id", "to_node_id", "relation", "role", "summary", "idempotency_key"],
      openapiSchema: "ProvenanceEdgeRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        runId: "path param /research-runs/{runId} — not a body field",
      },
      enums: {
        relation: PROVENANCE_EDGE_RELATIONS,
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — select_methodology (GC-RSCH-F006)
  // -------------------------------------------------------------------------

  describe("gc_research_run/select_methodology → SelectMethodologyRequest", () => {
    assertRow({
      label: "gc_research_run/select_methodology",
      // ADR-078: select_methodology now takes only method_key; the label,
      // profile/catalog version, and required-source set are derived server-side
      // from the backend methodology catalog.
      mcpFields: ["method_key"],
      openapiSchema: "SelectMethodologyRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      manualFieldMap: {
        method_key: "methodKey",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — list_methodology_catalog (GC-RSCH-F006 / ADR-078)
  // -------------------------------------------------------------------------

  describe("gc_research_run/list_methodology_catalog", () => {
    it("is a body-less read of GET /research-runs/methodology/catalog", () => {
      const path = spec.paths?.["/api/v1/research-runs/methodology/catalog"];
      assert.ok(path, "GET /api/v1/research-runs/methodology/catalog must exist in the OpenAPI spec");
      assert.ok(path.get, "methodology/catalog must expose a GET operation");
      // No request body: the action is global reference data, no fields to mirror.
      assert.ok(!path.get.requestBody, "list_methodology_catalog GET must not declare a request body");
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — record_methodology_source (GC-RSCH-F006)
  // -------------------------------------------------------------------------

  describe("gc_research_run/record_methodology_source → RecordMethodologySourceRequest", () => {
    assertRow({
      label: "gc_research_run/record_methodology_source",
      mcpFields: ["source_ref", "source_label"],
      openapiSchema: "RecordMethodologySourceRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
      },
      manualFieldMap: {
        source_ref: "sourceRef",
        source_label: "sourceLabel",
      },
    });
  });

  // -------------------------------------------------------------------------
  // gc_research_run — update_methodology_source_state (GC-RSCH-F006)
  // -------------------------------------------------------------------------

  describe("gc_research_run/update_methodology_source_state → UpdateMethodologySourceStateRequest", () => {
    assertRow({
      label: "gc_research_run/update_methodology_source_state",
      mcpFields: ["source_state"],
      openapiSchema: "UpdateMethodologySourceStateRequest",
      mcpOnly: {
        ...MCP_CONTROL_ARGS,
        sourceId: "path param /sources/{sourceId} — not a body field",
      },
      manualFieldMap: {
        source_state: "state",
      },
      enums: {
        state: METHODOLOGY_SOURCE_STATES,
      },
    });
  });
});
