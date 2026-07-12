// gc_risk_governance: entity- + action-discriminated MCP adapter for the
// verification result REST surface (ADR-035). Extracted from index.js so the
// handler logic is testable in isolation — see gc-risk-governance.test.js for
// adapter-level tests that drive the full path raw args → handler dispatch →
// backend HTTP call (mocked fetch). Issues #878/#879/#880 are the regression
// locks: per-entity allowlists must mirror the backend Request records, and
// the handler's `pick(args, ...)` is the gate that drops stale fields — not
// test-side pre-filtering.
//
// ADR-089 §1/§3: this tool previously also carried methodology_profile,
// risk_register_record, risk_assessment_result, treatment_plan, and
// risk_appetite_profile — composed GRC product entities retired by ADR-089.
// verification_result is an independently owned aggregate and is retained.

import { z } from "zod";
import {
  ASSURANCE_LEVELS,
  VERIFICATION_STATUSES,
  GOVERNANCE_FIELDS,
  pick, reqArg, validateGovernanceStatus,
  createVerificationResult, updateVerificationResult, deleteVerificationResult,
} from "./lib.js";

export const GC_RISK_GOVERNANCE_ENTITIES = ["verification_result"];
export const GC_RISK_GOVERNANCE_ACTIONS = ["create", "update", "delete"];

export const gcRiskGovernanceZodShape = {
  entity: z.enum(GC_RISK_GOVERNANCE_ENTITIES),
  action: z.enum(GC_RISK_GOVERNANCE_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  // Status vocabulary is per-entity; the handler validates it against
  // GOVERNANCE_STATUS_ENUMS[args.entity] before any backend call.
  status: z.string().optional(),
  // verification_result fields (#1106 — mirrors VerificationResultRequest).
  // target_id / requirement_id are optional UUIDs; prover is the required
  // principal name; property is the JML / contract property string; result
  // is the VerificationStatus enum; evidence is a free-form Map<String,Object>.
  target_id: z.string().uuid().optional(),
  requirement_id: z.string().uuid().optional(),
  prover: z.string().optional(),
  property: z.string().optional(),
  result: z.enum(VERIFICATION_STATUSES).optional(),
  assurance_level: z.enum(ASSURANCE_LEVELS).optional(),
  evidence: z.record(z.any()).optional(),
  verified_at: z.string().optional(),
  expires_at: z.string().optional(),
};

export const GC_RISK_GOVERNANCE_DESCRIPTION =
  `Verification results (ADR-089 retired the composed methodology_profile / risk_register_record / ` +
  `risk_assessment_result / treatment_plan / risk_appetite_profile entities this tool used to also carry). ` +
  `Entity: ${GC_RISK_GOVERNANCE_ENTITIES.join(", ")}. Actions: ${GC_RISK_GOVERNANCE_ACTIONS.join(", ")}. ` +
  `Reads (list, get) route through gc_query. ` +
  `Create fields (snake_case; round-trip to backend camelCase): ` +
  `verification_result={target_id,requirement_id,prover,property,result,assurance_level,evidence,verified_at,expires_at}. ` +
  `Required on create: prover, result, assurance_level, verified_at. ` +
  `Unknown fields are dropped — never tunneled through metadata. ` +
  `Required fields per action: create→{prover,result,assurance_level,verified_at}; update/delete→{id}.`;

/**
 * Pure adapter handler for gc_risk_governance. Validates per-entity status,
 * picks action-scoped body fields, and dispatches to the corresponding lib.js
 * call. Returns the raw value the lib call produces (or null for delete /
 * 204 responses); the index.js registration wraps the return in the MCP `ok()`
 * envelope. Throws Error on unknown action/entity combinations and on missing
 * required args — same semantics as the previous inline handler.
 */
export async function gcRiskGovernanceToolHandler(args) {
  validateGovernanceStatus(args.entity, args.status);
  const fieldsForAction = GOVERNANCE_FIELDS[args.entity]?.[args.action] ?? [];
  const data = pick(args, fieldsForAction);
  switch (args.entity) {
    case "verification_result": {
      switch (args.action) {
        case "create": reqArg(args, "prover", "create"); reqArg(args, "result", "create"); reqArg(args, "assurance_level", "create"); reqArg(args, "verified_at", "create"); return createVerificationResult(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateVerificationResult(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteVerificationResult(args.id, args.project); return null;
        default: throw new Error(`Action '${args.action}' not valid for verification_result`);
      }
    }
    default: throw new Error(`Unknown entity: ${args.entity}`);
  }
}
