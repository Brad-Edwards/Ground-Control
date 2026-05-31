// gc_evidence: action-discriminated MCP adapter for the backend
// evidence-artifact REST surface (GC-M016, ADR-045). Mirrors gc-finding.js —
// handler logic stays testable in isolation, while index.js registers the tool
// and wraps the return value in the MCP `ok()` envelope.

import { z } from "zod";
import {
  ASSURANCE_LEVELS,
  EVIDENCE_SOURCE_KINDS,
  EVIDENCE_TYPES,
  createEvidenceArtifact,
  pick,
  reqArg,
  supersedeEvidenceArtifact,
} from "./lib.js";

export const GC_EVIDENCE_ACTIONS = ["create", "supersede"];

// Snake_case body fields accepted by gc_evidence.create / supersede — mirrors
// the backend EvidenceArtifactRequest. derived_by is server-populated from the
// authenticated actor and is intentionally not in this list. expires_at and
// validity_window_days are GC-I004 fields (optional; default null).
export const GC_EVIDENCE_BODY_FIELDS = [
  "uid",
  "title",
  "summary",
  "evidence_type",
  "derivation_method",
  "derived_at",
  "assurance_level",
  "confidence",
  "notes",
  "sources",
  "expires_at",
  "validity_window_days",
];

export const GC_EVIDENCE_CREATE_REQUIRED_FIELDS = [
  "uid",
  "title",
  "summary",
  "evidence_type",
  "derivation_method",
  "derived_at",
  "sources",
];

const evidenceSourceRefShape = z.object({
  sourceKind: z.enum(EVIDENCE_SOURCE_KINDS),
  sourceEntityId: z.string().uuid().nullish(),
  sourceIdentifier: z.string().min(1).max(500).nullish(),
  role: z.string().min(1).max(100).nullish(),
});

export const gcEvidenceZodShape = {
  action: z.enum(GC_EVIDENCE_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  uid: z.string().optional(),
  title: z.string().optional(),
  summary: z.string().optional(),
  evidence_type: z.enum(EVIDENCE_TYPES).optional(),
  derivation_method: z.string().optional(),
  derived_at: z.string().optional(),
  assurance_level: z.enum(ASSURANCE_LEVELS).optional(),
  confidence: z.string().optional(),
  notes: z.string().optional(),
  sources: z.array(evidenceSourceRefShape).optional(),
  expires_at: z.string().optional(),
  validity_window_days: z.number().int().positive().optional(),
};

export const GC_EVIDENCE_DESCRIPTION =
  `Summarized-evidence aggregate (GC-M016, ADR-045 + GC-I003/I004). Actions: ` +
  `${GC_EVIDENCE_ACTIONS.join(", ")}. Append-only — there is no update or ` +
  `delete; the aggregate exposes 'create' to add a new artifact and ` +
  `'supersede' to create a replacement that links the prior one. ` +
  `create requires: ${GC_EVIDENCE_CREATE_REQUIRED_FIELDS.join(", ")}. ` +
  `supersede requires id (the prior artifact) plus the same body fields as create. ` +
  `Each source carries sourceKind (one of ${EVIDENCE_SOURCE_KINDS.join("/")}); ` +
  `internal kinds (OBSERVATION / CONTROL_TEST / ` +
  `CONTROL_EFFECTIVENESS_ASSESSMENT / VERIFICATION_RESULT / ` +
  `RISK_ASSESSMENT_RESULT / FINDING) require sourceEntityId, external kinds ` +
  `(ATTESTATION / EXTERNAL / CI_PIPELINE_RESULT / SECURITY_SCAN_RESULT) ` +
  `require sourceIdentifier; the server never dereferences external identifiers. ` +
  `Optional fields expires_at (ISO instant) and validity_window_days (positive int) ` +
  `wire the GC-I004 expiration semantics — append-only is preserved, the sweep job ` +
  `derives expiry events without mutating the artifact. ` +
  `Reads (list, get, by expiry, compliance drift events) route through gc_query.`;

/**
 * Pure adapter handler for gc_evidence. Validates required fields, picks
 * action-scoped body fields, transforms snake_case sources to camelCase to
 * match the backend DTO, and dispatches to lib.js. Returns the raw value the
 * lib call produces; the index.js registration wraps the return in the MCP
 * `ok()` envelope.
 */
export async function gcEvidenceToolHandler(args) {
  switch (args.action) {
    case "create": {
      for (const key of GC_EVIDENCE_CREATE_REQUIRED_FIELDS) reqArg(args, key, "create");
      return createEvidenceArtifact(toCreateBody(args), args.project);
    }
    case "supersede": {
      reqArg(args, "id", "supersede");
      for (const key of GC_EVIDENCE_CREATE_REQUIRED_FIELDS) reqArg(args, key, "supersede");
      return supersedeEvidenceArtifact(args.id, toCreateBody(args), args.project);
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

function toCreateBody(args) {
  const body = pick(args, GC_EVIDENCE_BODY_FIELDS);
  // Re-shape snake_case → camelCase for the backend DTO. The adapter accepts
  // either form on input; the backend wire shape is camelCase. Optional
  // fields are conditionally omitted so the request body remains minimal —
  // tests assert exact shape on the happy path.
  const out = {
    uid: body.uid,
    title: body.title,
    summary: body.summary,
    evidenceType: body.evidence_type,
    derivationMethod: body.derivation_method,
    derivedAt: body.derived_at,
    sources: body.sources,
  };
  if (body.assurance_level !== undefined) out.assuranceLevel = body.assurance_level;
  if (body.confidence !== undefined) out.confidence = body.confidence;
  if (body.notes !== undefined) out.notes = body.notes;
  if (body.expires_at !== undefined) out.expiresAt = body.expires_at;
  if (body.validity_window_days !== undefined) out.validityWindowDays = body.validity_window_days;
  return out;
}
