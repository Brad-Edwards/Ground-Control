// gc_threat_model: action-discriminated MCP adapter for the backend threat-model
// REST surface (ADR-024, ADR-035). Extracted from index.js so the handler logic
// is testable in isolation — see lib.test.js and the gc-threat-model.test.js
// adapter-level tests covering create/update body shapes, required-field
// enforcement, and snake_case <-> camelCase mapping (issue #875).

import { z } from "zod";
import {
  THREAT_MODEL_STATUSES, STRIDE_CATEGORIES,
  THREAT_MODEL_LINK_TARGET_TYPES, THREAT_MODEL_LINK_TYPES,
  createThreatModel, updateThreatModel, deleteThreatModel,
  transitionThreatModelStatus, getThreatModelLinkedRequirements, getThreatModelTrace,
  createThreatModelLink, deleteThreatModelLink,
  pick, reqArg,
} from "./lib.js";
import {
  linkCreateOptionalSharedZodFields,
  performLinkCreate,
} from "./link-create.js";

export const GC_THREAT_MODEL_ACTIONS = [
  "create", "update", "delete", "transition", "requirements", "trace", "link_create", "link_delete",
];

// Snake_case body fields accepted by gc_threat_model.create — mirrors
// backend ThreatModelRequest. uid is create-only; the update DTO has no uid.
export const GC_THREAT_MODEL_CREATE_BODY_FIELDS = [
  "uid", "title", "threat_source", "threat_event", "effect",
  "stride_category", "narrative",
];

// Snake_case body fields accepted by gc_threat_model.update — mirrors
// backend UpdateThreatModelRequest. clear_stride / clear_narrative are
// update-only flags; the create DTO has no such fields.
export const GC_THREAT_MODEL_UPDATE_BODY_FIELDS = [
  "title", "threat_source", "threat_event", "effect",
  "stride_category", "narrative", "clear_stride", "clear_narrative",
];

// @NotBlank fields on backend ThreatModelRequest. Enforced at the adapter
// boundary so callers get a clear `'X' is required` error instead of waiting
// for a backend 422.
export const GC_THREAT_MODEL_CREATE_REQUIRED_FIELDS = [
  "uid", "title", "threat_source", "threat_event", "effect",
];

export const gcThreatModelZodShape = {
  action: z.enum(GC_THREAT_MODEL_ACTIONS),
  id: z.string().uuid().optional(),
  uid: z.string().optional(),
  project: z.string().optional(),
  title: z.string().optional(),
  threat_source: z.string().optional(),
  threat_event: z.string().optional(),
  effect: z.string().optional(),
  stride_category: z.enum(STRIDE_CATEGORIES).optional(),
  narrative: z.string().optional(),
  clear_stride: z.boolean().optional(),
  clear_narrative: z.boolean().optional(),
  status: z.enum(THREAT_MODEL_STATUSES).optional(),
  threat_model_id: z.string().uuid().optional(),
  target_type: z.enum(THREAT_MODEL_LINK_TARGET_TYPES).optional(),
  link_type: z.enum(THREAT_MODEL_LINK_TYPES).optional(),
  ...linkCreateOptionalSharedZodFields,
  link_id: z.string().uuid().optional(),
};

export const GC_THREAT_MODEL_DESCRIPTION =
  `Threat models + their links. Actions: ${GC_THREAT_MODEL_ACTIONS.join(", ")}. ` +
  `create requires: ${GC_THREAT_MODEL_CREATE_REQUIRED_FIELDS.join(", ")} (and accepts ` +
  `optional stride_category, narrative). update accepts the same fields minus uid plus ` +
  `clear_stride / clear_narrative to explicitly null optional fields. ` +
  `status is consumed only by the transition action (creation defaults to DRAFT). ` +
  `requirements returns all requirements linked to the threat model (requires id). ` +
  `trace returns the full end-to-end trace: assets, controls, requirements, and per-requirement ` +
  `implementing artifacts (requires id). ` +
  `link_create requires target_type + link_type; for internal target types ` +
  `(ASSET / REQUIREMENT / CONTROL / RISK_SCENARIO / OBSERVATION / RISK_ASSESSMENT_RESULT / ` +
  `VERIFICATION_RESULT / FINDING / EVIDENCE) pass target_entity_id, ` +
  `for external types (EXTERNAL / ISSUE / CODE / ARCHITECTURE_MODEL) pass target_identifier. ` +
  `target_url and target_title are optional. ` +
  `Reads (list, get, links_list) route through gc_query.`;

/**
 * Pure adapter handler for gc_threat_model. Validates required fields, picks
 * action-scoped body fields, and dispatches to the corresponding lib.js call.
 * Returns the raw value the lib call produces (or null for delete-style 204s);
 * the index.js registration wraps the return in the MCP `ok()` envelope.
 */
export async function gcThreatModelToolHandler(args) {
  switch (args.action) {
    case "create": {
      for (const key of GC_THREAT_MODEL_CREATE_REQUIRED_FIELDS) reqArg(args, key, "create");
      return createThreatModel(pick(args, GC_THREAT_MODEL_CREATE_BODY_FIELDS), args.project);
    }
    case "update": {
      reqArg(args, "id", "update");
      return updateThreatModel(args.id, pick(args, GC_THREAT_MODEL_UPDATE_BODY_FIELDS), args.project);
    }
    case "delete": {
      reqArg(args, "id", "delete");
      await deleteThreatModel(args.id, args.project);
      return null;
    }
    case "transition": {
      reqArg(args, "id", "transition");
      reqArg(args, "status", "transition");
      return transitionThreatModelStatus(args.id, args.status, args.project);
    }
    case "requirements": {
      reqArg(args, "id", "requirements");
      return getThreatModelLinkedRequirements(args.id, args.project);
    }
    case "trace": {
      reqArg(args, "id", "trace");
      return getThreatModelTrace(args.id, args.project);
    }
    case "link_create": {
      return performLinkCreate(args, "threat_model_id", createThreatModelLink);
    }
    case "link_delete": {
      reqArg(args, "threat_model_id", "link_delete");
      reqArg(args, "link_id", "link_delete");
      await deleteThreatModelLink(args.threat_model_id, args.link_id, args.project);
      return null;
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}
