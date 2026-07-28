// Split from openapi-contract.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve as resolvePath } from "node:path";
import {
  GATE_RECOMMENDATION_PROVENANCES,
  OPAQUE_VALUE_KEYS,
  RESEARCH_GATE_DECISION_OUTCOMES,
  RESEARCH_GATE_POINTS,
  RESEARCH_RUN_STAGES,
  REVIEW_COMMENT_PROVENANCES,
  REVIEW_COMMENT_TARGETS,
  TO_CAMEL,
} from "./lib.js";
import { LINK_CREATE_BODY_FIELDS } from "./link-create.js";

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
});
