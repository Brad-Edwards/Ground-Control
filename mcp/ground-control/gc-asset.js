// gc_asset: action-discriminated MCP adapter for the backend operational-asset
// REST surface (GC-L008). Mirrors gc-finding.js — handler logic stays testable
// in isolation, while index.js registers the tool and wraps the return value in
// the MCP `ok()` envelope.
//
// Defect-2 fix: relation_create body allowlist now includes all AssetRelationRequest
// fields — description, source_system, external_source_id, collected_at, confidence
// — not just source_id/target_id/relation_type/knowledge_state.
//
// Defect-3 fix: relation_update action added (PUT /api/v1/assets/{assetId}/relations/{relationId}).

import { z } from "zod";
import {
  ASSET_TYPES, ASSET_RELATION_TYPES, ASSET_LINK_TARGET_TYPES, ASSET_LINK_TYPES,
  ASSET_CRITICALITIES, ASSET_ENVIRONMENTS, ASSET_SCOPES, KNOWLEDGE_STATES,
  createAsset, updateAsset, deleteAsset, archiveAsset,
  createAssetRelation, updateAssetRelation, deleteAssetRelation,
  detectAssetCycles, assetImpactAnalysis, extractAssetSubgraph,
  createAssetLink, deleteAssetLink,
  createAssetExternalId, updateAssetExternalId, deleteAssetExternalId,
  registerAssetSubtypeSchema, listAssetSubtypeSchemas, getAssetSubtypeSchema,
  getActiveAssetSubtypeSchema, updateAssetSubtypeSchema, deprecateAssetSubtypeSchema,
  pick, reqArg,
} from "./lib.js";
import {
  linkCreateOptionalSharedZodFields,
  performLinkCreate,
} from "./link-create.js";

export const GC_ASSET_ACTIONS = [
  "create", "update", "delete", "archive",
  "relation_create", "relation_update", "relation_delete",
  "detect_cycles", "impact_analysis", "extract_subgraph",
  "link_create", "link_delete",
  "external_id_create", "external_id_update", "external_id_delete",
  // GC-M011 subtype-schema registry actions.
  "subtype_schema_create", "subtype_schema_update", "subtype_schema_deprecate",
  "subtype_schema_get", "subtype_schema_get_active", "subtype_schema_list",
];

// Body fields for the asset create / update actions (mirrors AssetRequest /
// UpdateAssetRequest). Used by both create and update — the backend ignores
// unknown fields in the PUT body.
export const GC_ASSET_FIELDS = [
  "uid",
  "name",
  "description",
  "asset_type",
  "parent_id",
  // GC-M012 ownership/criticality/scope metadata + clear flags.
  "owner",
  "steward",
  "environment",
  "criticality",
  "business_context",
  "scope_designation",
  "clear_owner",
  "clear_steward",
  "clear_environment",
  "clear_criticality",
  "clear_business_context",
  "clear_scope_designation",
  // GC-M011 subtype + metadata + clear flags.
  "subtype",
  "metadata",
  "clear_subtype",
  "clear_metadata",
  // GC-M018 knowledge / completeness state.
  "knowledge_state",
];

// Body fields for relation_create — mirrors AssetRelationRequest.
// Defect-2: source_id is the path param, NOT a body field. All remaining
// AssetRelationRequest fields are forwarded.
export const GC_RELATION_CREATE_FIELDS = [
  "target_id",
  "relation_type",
  "description",
  "source_system",
  "external_source_id",
  "collected_at",
  "confidence",
  "knowledge_state",
];

// Body fields for relation_update — mirrors UpdateAssetRelationRequest.
// Defect-3: intentionally excludes target_id and relation_type (not updatable).
export const GC_RELATION_UPDATE_FIELDS = [
  "description",
  "source_system",
  "external_source_id",
  "collected_at",
  "confidence",
  "knowledge_state",
];

// External-id body fields.
const EXT_ID_FIELDS = ["namespace", "external_id"];

export const gcAssetZodShape = {
  action: z.enum(GC_ASSET_ACTIONS),
  id: z.string().uuid().optional(),
  uid: z.string().optional(),
  project: z.string().optional(),
  name: z.string().optional(),
  description: z.string().optional(),
  asset_type: z.enum(ASSET_TYPES).optional(),
  // GC-M012 metadata: ownership, stewardship, environment, criticality,
  // business/mission context, and assurance scope.
  owner: z.string().optional(),
  steward: z.string().optional(),
  environment: z.enum(ASSET_ENVIRONMENTS).optional(),
  criticality: z.enum(ASSET_CRITICALITIES).optional(),
  business_context: z.string().optional(),
  scope_designation: z.enum(ASSET_SCOPES).optional(),
  // GC-M012 clear flags.
  clear_owner: z.boolean().optional(),
  clear_steward: z.boolean().optional(),
  clear_environment: z.boolean().optional(),
  clear_criticality: z.boolean().optional(),
  clear_business_context: z.boolean().optional(),
  clear_scope_designation: z.boolean().optional(),
  // GC-M011: subtype discriminator + extensible metadata bag.
  subtype: z.string().optional(),
  metadata: z.record(z.any()).optional(),
  clear_subtype: z.boolean().optional(),
  clear_metadata: z.boolean().optional(),
  // GC-M018: knowledge / completeness dimension on asset AND relation.
  knowledge_state: z.enum(KNOWLEDGE_STATES).optional(),
  // GC-M011: subtype-schema registry parameters.
  schema_id: z.string().uuid().optional(),
  schema_version: z.string().optional(),
  schema_body: z.record(z.any()).optional(),
  schema_description: z.string().optional(),
  clear_schema_description: z.boolean().optional(),
  clear_schema_body: z.boolean().optional(),
  parent_id: z.string().uuid().nullable().optional(),
  // relations — Defect-2/3: added source_system, external_source_id, collected_at, confidence
  source_id: z.string().uuid().optional(),
  target_id: z.string().uuid().optional(),
  relation_type: z.enum(ASSET_RELATION_TYPES).optional(),
  relation_id: z.string().uuid().optional(),
  source_system: z.string().optional(),
  external_source_id: z.string().optional(),
  collected_at: z.string().optional(),
  confidence: z.string().optional(),
  // links
  asset_id: z.string().uuid().optional(),
  target_type: z.enum(ASSET_LINK_TARGET_TYPES).optional(),
  link_type: z.enum(ASSET_LINK_TYPES).optional(),
  ...linkCreateOptionalSharedZodFields,
  link_id: z.string().uuid().optional(),
  // external IDs
  namespace: z.string().optional(),
  external_id: z.string().optional(),
  external_id_record_id: z.string().uuid().optional(),
  roots: z.array(z.string()).optional(),
  max_depth: z.number().int().optional(),
};

export const GC_ASSET_DESCRIPTION =
  `Operational asset operations incl. relations, links, external IDs (GC-L008). ` +
  `Actions: ${GC_ASSET_ACTIONS.join(", ")}. ` +
  `link_create requires target_type + link_type; pass target_entity_id for internal ` +
  `target types or target_identifier for external types. target_url / target_title are optional. ` +
  `Reads (list, get, get_by_uid, find_by_external_id, links, external_ids) route through gc_query. ` +
  `Required fields per action: create→{uid,name,asset_type}; update/delete/archive/impact_analysis→{id}; relation_create→{source_id,target_id,relation_type}; relation_update/relation_delete→{asset_id,relation_id}; extract_subgraph→{roots}; link_create→{asset_id,target_type,link_type}; link_delete→{asset_id,link_id}; external_id_create→{asset_id,namespace,external_id}; external_id_update/external_id_delete→{asset_id,external_id_record_id}; subtype_schema_create→{asset_type,subtype,schema_version,schema_body}; subtype_schema_update/subtype_schema_deprecate/subtype_schema_get→{schema_id}; subtype_schema_get_active→{asset_type,subtype}; detect_cycles→{} and subtype_schema_list→{} (no required fields).`;

/**
 * Pure adapter handler for gc_asset. Validates required fields, picks
 * action-scoped body fields, and dispatches to the corresponding lib.js call.
 * Returns the raw value the lib call produces (or null for delete-style 204s);
 * the index.js registration wraps the return in the MCP `ok()` envelope.
 */
export async function gcAssetToolHandler(args) {
  switch (args.action) {
    case "create": {
      reqArg(args, "uid", "create");
      reqArg(args, "name", "create");
      reqArg(args, "asset_type", "create");
      return createAsset(pick(args, GC_ASSET_FIELDS), args.project);
    }
    case "update": {
      reqArg(args, "id", "update");
      return updateAsset(args.id, pick(args, GC_ASSET_FIELDS), args.project);
    }
    case "delete": {
      reqArg(args, "id", "delete");
      await deleteAsset(args.id, args.project);
      return null;
    }
    case "archive": {
      reqArg(args, "id", "archive");
      return archiveAsset(args.id, args.project);
    }
    case "relation_create": {
      // lib.js: createAssetRelation(assetId, data, project)
      // source_id is the path arg; body uses GC_RELATION_CREATE_FIELDS (Defect-2 fix).
      reqArg(args, "source_id", "relation_create");
      reqArg(args, "target_id", "relation_create");
      reqArg(args, "relation_type", "relation_create");
      return createAssetRelation(args.source_id, pick(args, GC_RELATION_CREATE_FIELDS), args.project);
    }
    case "relation_update": {
      // lib.js: updateAssetRelation(assetId, relationId, data, project) — Defect-3 fix.
      reqArg(args, "asset_id", "relation_update");
      reqArg(args, "relation_id", "relation_update");
      return updateAssetRelation(args.asset_id, args.relation_id, pick(args, GC_RELATION_UPDATE_FIELDS), args.project);
    }
    case "relation_delete": {
      // lib.js: deleteAssetRelation(assetId, relationId, project)
      reqArg(args, "asset_id", "relation_delete");
      reqArg(args, "relation_id", "relation_delete");
      await deleteAssetRelation(args.asset_id, args.relation_id, args.project);
      return null;
    }
    case "detect_cycles":
      return detectAssetCycles(args.project);
    case "impact_analysis": {
      reqArg(args, "id", "impact_analysis");
      return assetImpactAnalysis(args.id, args.project);
    }
    case "extract_subgraph": {
      reqArg(args, "roots", "extract_subgraph");
      return extractAssetSubgraph({ roots: args.roots, maxDepth: args.max_depth }, args.project);
    }
    case "link_create": {
      // lib.js: createAssetLink(assetId, data, project). Body shape +
      // target_type/link_type preconditions live in link-create.js so
      // every consolidated link_create surface stays in sync with the
      // backend link DTO.
      return performLinkCreate(args, "asset_id", createAssetLink);
    }
    case "link_delete": {
      // lib.js: deleteAssetLink(assetId, linkId, project)
      reqArg(args, "asset_id", "link_delete");
      reqArg(args, "link_id", "link_delete");
      await deleteAssetLink(args.asset_id, args.link_id, args.project);
      return null;
    }
    case "external_id_create": {
      // lib.js: createAssetExternalId(assetId, data, project)
      reqArg(args, "asset_id", "external_id_create");
      reqArg(args, "namespace", "external_id_create");
      reqArg(args, "external_id", "external_id_create");
      return createAssetExternalId(args.asset_id, pick(args, EXT_ID_FIELDS), args.project);
    }
    case "external_id_update": {
      // lib.js: updateAssetExternalId(assetId, extIdId, data, project)
      reqArg(args, "asset_id", "external_id_update");
      reqArg(args, "external_id_record_id", "external_id_update");
      return updateAssetExternalId(args.asset_id, args.external_id_record_id, pick(args, EXT_ID_FIELDS), args.project);
    }
    case "external_id_delete": {
      // lib.js: deleteAssetExternalId(assetId, extIdId, project)
      reqArg(args, "asset_id", "external_id_delete");
      reqArg(args, "external_id_record_id", "external_id_delete");
      await deleteAssetExternalId(args.asset_id, args.external_id_record_id, args.project);
      return null;
    }
    case "subtype_schema_create": {
      reqArg(args, "asset_type", "subtype_schema_create");
      reqArg(args, "subtype", "subtype_schema_create");
      reqArg(args, "schema_version", "subtype_schema_create");
      // schema_body is required at the MCP boundary too — the backend rejects
      // ACTIVE registry rows without a non-empty `fields` map.
      reqArg(args, "schema_body", "subtype_schema_create");
      const body = {
        assetType: args.asset_type,
        subtype: args.subtype,
        schemaVersion: args.schema_version,
        description: args.schema_description,
        schemaBody: args.schema_body,
      };
      return registerAssetSubtypeSchema(body, args.project);
    }
    case "subtype_schema_update": {
      reqArg(args, "schema_id", "subtype_schema_update");
      const body = {
        description: args.schema_description,
        schemaBody: args.schema_body,
        clearDescription: args.clear_schema_description,
        clearSchemaBody: args.clear_schema_body,
      };
      return updateAssetSubtypeSchema(args.schema_id, body, args.project);
    }
    case "subtype_schema_deprecate": {
      reqArg(args, "schema_id", "subtype_schema_deprecate");
      return deprecateAssetSubtypeSchema(args.schema_id, args.project);
    }
    case "subtype_schema_get": {
      reqArg(args, "schema_id", "subtype_schema_get");
      return getAssetSubtypeSchema(args.schema_id, args.project);
    }
    case "subtype_schema_get_active": {
      reqArg(args, "asset_type", "subtype_schema_get_active");
      reqArg(args, "subtype", "subtype_schema_get_active");
      return getActiveAssetSubtypeSchema(args.asset_type, args.subtype, args.project);
    }
    case "subtype_schema_list": {
      return listAssetSubtypeSchemas({
        project: args.project,
        assetType: args.asset_type,
        subtype: args.subtype,
      });
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}
