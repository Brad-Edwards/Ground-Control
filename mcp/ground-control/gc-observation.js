// gc_observation: action-discriminated MCP adapter for the backend observation
// REST surface (GC-L008). Mirrors gc-finding.js — handler logic stays testable
// in isolation, while index.js registers the tool and wraps the return value in
// the MCP `ok()` envelope.
//
// Defect-1 fix: the inline gc_observation used title/statement/valid_until/
// metadata. The backend ObservationRequest uses category/observationKey/
// observationValue/source/observedAt/expiresAt/confidence/evidenceRef. All old
// field names are dropped and replaced here.

import { z } from "zod";
import {
  OBSERVATION_CATEGORIES,
  createObservation,
  updateObservation,
  deleteObservation,
  listLatestObservations,
  pick,
  reqArg,
} from "./lib.js";

export const GC_OBSERVATION_ACTIONS = ["create", "update", "delete", "latest"];

// Snake_case body fields accepted by gc_observation.create — mirrors backend
// ObservationRequest.
export const GC_OBSERVATION_CREATE_FIELDS = [
  "category",
  "observation_key",
  "observation_value",
  "source",
  "observed_at",
  "expires_at",
  "confidence",
  "evidence_ref",
];

// Required fields for the create action (maps to @NotBlank / @NotNull on
// ObservationRequest).
export const GC_OBSERVATION_CREATE_REQUIRED_FIELDS = [
  "asset_id",
  "category",
  "observation_key",
  "observation_value",
  "source",
  "observed_at",
];

// Snake_case body fields accepted by gc_observation.update — mirrors backend
// UpdateObservationRequest. Intentionally separate from create to enforce that
// create-only fields (category, observation_key, source, observed_at) are never
// forwarded on update.
export const GC_OBSERVATION_UPDATE_FIELDS = [
  "observation_value",
  "expires_at",
  "confidence",
  "evidence_ref",
];

export const gcObservationZodShape = {
  action: z.enum(GC_OBSERVATION_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  asset_id: z.string().uuid().optional(),
  category: z.enum(OBSERVATION_CATEGORIES).optional(),
  observation_key: z.string().optional(),
  observation_value: z.string().optional(),
  source: z.string().optional(),
  observed_at: z.string().optional(),
  expires_at: z.string().optional(),
  confidence: z.string().optional(),
  evidence_ref: z.string().optional(),
};

export const GC_OBSERVATION_DESCRIPTION =
  `Time-bounded state observations about an asset (GC-L008). ` +
  `Actions: ${GC_OBSERVATION_ACTIONS.join(", ")}. ` +
  `create requires: ${GC_OBSERVATION_CREATE_REQUIRED_FIELDS.join(", ")}. ` +
  `update requires asset_id + id; accepts observation_value, expires_at, confidence, evidence_ref. ` +
  `delete requires asset_id + id. ` +
  `latest requires asset_id. ` +
  `Reads (list, get) route through gc_query.`;

/**
 * Pure adapter handler for gc_observation. Validates required fields, picks
 * action-scoped body fields, and dispatches to the corresponding lib.js call.
 * Returns the raw value the lib call produces (or null for delete-style 204s);
 * the index.js registration wraps the return in the MCP `ok()` envelope.
 */
export async function gcObservationToolHandler(args) {
  switch (args.action) {
    case "create": {
      for (const key of GC_OBSERVATION_CREATE_REQUIRED_FIELDS) reqArg(args, key, "create");
      return createObservation(args.asset_id, pick(args, GC_OBSERVATION_CREATE_FIELDS), args.project);
    }
    case "update": {
      reqArg(args, "asset_id", "update");
      reqArg(args, "id", "update");
      return updateObservation(args.asset_id, args.id, pick(args, GC_OBSERVATION_UPDATE_FIELDS), args.project);
    }
    case "delete": {
      reqArg(args, "asset_id", "delete");
      reqArg(args, "id", "delete");
      await deleteObservation(args.asset_id, args.id, args.project);
      return null;
    }
    case "latest": {
      reqArg(args, "asset_id", "latest");
      return listLatestObservations(args.asset_id, args.project);
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}
