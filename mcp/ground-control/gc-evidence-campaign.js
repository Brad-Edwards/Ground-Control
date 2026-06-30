// gc_evidence_campaign: action-discriminated MCP adapter for the backend
// scheduled-evidence-collection campaign surface (GC-S005). Mirrors
// gc-evidence.js — handler logic stays testable in isolation, while index.js
// registers the tool and wraps the return value in the MCP `ok()` envelope.
// Writes only (create / update / pause / resume / trigger); reads (list, get,
// runs) route through gc_query.

import { z } from "zod";
import {
  EVIDENCE_CAMPAIGN_FREQUENCIES,
  createEvidenceCampaign,
  pauseEvidenceCampaign,
  pick,
  reqArg,
  resumeEvidenceCampaign,
  triggerEvidenceCampaign,
  updateEvidenceCampaign,
} from "./lib.js";

export const GC_EVIDENCE_CAMPAIGN_ACTIONS = ["create", "update", "pause", "resume", "trigger"];

// camelCase body fields accepted by gc_evidence_campaign.create / update —
// mirrors the backend EvidenceCampaignRequest. credentialRef is a reference
// string only; the raw secret is never carried here.
export const GC_EVIDENCE_CAMPAIGN_BODY_FIELDS = [
  "uid",
  "name",
  "frequency",
  "adapterName",
  "scopeType",
  "schemaId",
  "connectionProfileId",
  "connectionEndpoint",
  "credentialRef",
  "scopeCriteria",
  "targetControlIds",
  "retentionDays",
  "firstRunAt",
];

export const GC_EVIDENCE_CAMPAIGN_CREATE_REQUIRED_FIELDS = [
  "uid",
  "name",
  "frequency",
  "adapterName",
  "scopeType",
  "connectionProfileId",
  "connectionEndpoint",
  "credentialRef",
];

export const gcEvidenceCampaignZodShape = {
  action: z.enum(GC_EVIDENCE_CAMPAIGN_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  uid: z.string().max(50).optional(),
  name: z.string().max(200).optional(),
  frequency: z.enum(EVIDENCE_CAMPAIGN_FREQUENCIES).optional(),
  adapterName: z.string().max(100).optional(),
  scopeType: z.string().max(120).optional(),
  schemaId: z.string().max(120).optional(),
  connectionProfileId: z.string().max(200).optional(),
  connectionEndpoint: z.string().max(500).optional(),
  credentialRef: z.string().max(200).optional(),
  scopeCriteria: z.record(z.any()).optional(),
  targetControlIds: z.array(z.string().uuid()).optional(),
  retentionDays: z.number().int().positive().optional(),
  firstRunAt: z.string().optional(),
};

export const GC_EVIDENCE_CAMPAIGN_DESCRIPTION =
  `Scheduled evidence-collection campaign (GC-S005). Actions: ` +
  `${GC_EVIDENCE_CAMPAIGN_ACTIONS.join(", ")}. create schedules a recurring campaign that, at the ` +
  `configured frequency (${EVIDENCE_CAMPAIGN_FREQUENCIES.join("/")}), invokes a named evidence ` +
  `collection adapter over a scope and stores results as evidence artifacts linked to the ` +
  `target controls. create requires: ${GC_EVIDENCE_CAMPAIGN_CREATE_REQUIRED_FIELDS.join(", ")}. ` +
  `update applies a partial change by id. pause/resume toggle the schedule. trigger forces an ` +
  `immediate run and returns the resulting campaign run (admin-only). credentialRef is an ` +
  `indirection key only — never pass a raw secret. Reads (list, get, runs) route through gc_query.`;

/**
 * Pure adapter handler for gc_evidence_campaign. Validates required fields,
 * picks action-scoped body fields, and dispatches to lib.js. Returns the raw
 * value the lib call produces; the index.js registration wraps the return in
 * the MCP `ok()` envelope.
 */
export async function gcEvidenceCampaignToolHandler(args) {
  switch (args.action) {
    case "create": {
      for (const key of GC_EVIDENCE_CAMPAIGN_CREATE_REQUIRED_FIELDS) reqArg(args, key, "create");
      return createEvidenceCampaign(pick(args, GC_EVIDENCE_CAMPAIGN_BODY_FIELDS), args.project);
    }
    case "update": {
      reqArg(args, "id", "update");
      return updateEvidenceCampaign(args.id, pick(args, GC_EVIDENCE_CAMPAIGN_BODY_FIELDS), args.project);
    }
    case "pause": {
      reqArg(args, "id", "pause");
      return pauseEvidenceCampaign(args.id, args.project);
    }
    case "resume": {
      reqArg(args, "id", "resume");
      return resumeEvidenceCampaign(args.id, args.project);
    }
    case "trigger": {
      reqArg(args, "id", "trigger");
      return triggerEvidenceCampaign(args.id, args.project);
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}
