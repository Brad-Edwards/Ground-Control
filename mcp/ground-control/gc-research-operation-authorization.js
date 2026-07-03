// gc_research_operation_authorization: action-discriminated MCP adapter for the
// backend research high-risk operation authorization REST surface (GC-RSCH-R005 /
// GC-RSCH-N005 / GC-RSCH-N006, ADR-085). Run-scoped, default-deny authorization
// records for generated-code execution, browser activity, lab/hardware actions,
// and external writes. Curated writes (request / decide / consume) mirror REST
// because gc_query is read-only; reads (list / get) are exposed here and also
// remain reachable via gc_query under the /api/v1/research-runs allow-list. The
// proposing/deciding actor is server-populated from the authenticated context and
// is intentionally never a body field (ADR-026 / ADR-033); the decision route is
// admin-gated server-side (an AUTONOMOUS run may propose but never approve).

import {
  RESEARCH_DATA_CLASSES,
  RESEARCH_DATA_FORMS,
  RESEARCH_DESTINATION_CLASSES,
  RESEARCH_HIGH_RISK_OPERATION_KINDS,
  consumeResearchOperationAuthorization,
  decideResearchOperationAuthorization,
  getResearchOperationAuthorization,
  listResearchOperationAuthorizations,
  pick,
  reqArg,
  requestResearchOperationAuthorization,
} from "./lib.js";
import { z } from "zod";

export const GC_RESEARCH_OPERATION_AUTHORIZATION_ACTIONS = ["request", "decide", "consume", "list", "get"];

// Snake_case body fields accepted by request — mirrors OperationAuthorizationRequest.
export const GC_RESEARCH_OPERATION_AUTHORIZATION_REQUEST_BODY_FIELDS = [
  "operation_kind",
  "data_class",
  "destination_class",
  "requested_form",
  "tool_id",
  "sandbox_profile",
  "target_class",
  "expires_at",
  "summary",
  "source_action_id",
];

export const GC_RESEARCH_OPERATION_AUTHORIZATION_REQUEST_REQUIRED_FIELDS = [
  "operation_kind",
  "data_class",
  "destination_class",
  "requested_form",
  // ADR-085 §1: an authorization must bind a concrete effect request.
  "tool_id",
  "sandbox_profile",
  "summary",
  "source_action_id",
];

export const gcResearchOperationAuthorizationZodShape = {
  action: z.enum(GC_RESEARCH_OPERATION_AUTHORIZATION_ACTIONS),
  project: z.string().optional(),
  run_id: z.string().uuid(),
  authorization_id: z.string().uuid().optional(),
  // request
  operation_kind: z.enum(RESEARCH_HIGH_RISK_OPERATION_KINDS).optional(),
  data_class: z.enum(RESEARCH_DATA_CLASSES).optional(),
  destination_class: z.enum(RESEARCH_DESTINATION_CLASSES).optional(),
  requested_form: z.enum(RESEARCH_DATA_FORMS).optional(),
  tool_id: z.string().max(200).optional(),
  sandbox_profile: z.string().max(120).optional(),
  target_class: z.string().max(120).optional(),
  expires_at: z.string().optional(),
  summary: z.string().max(2000).optional(),
  source_action_id: z.string().max(200).optional(),
  // decide
  approve: z.boolean().optional(),
  note: z.string().max(500).optional(),
};

export const GC_RESEARCH_OPERATION_AUTHORIZATION_DESCRIPTION =
  `Research high-risk operation authorization (GC-RSCH-R005 / ADR-085). Run-scoped, default-deny ` +
  `records for generated-code execution, browser activity, lab/hardware actions, and external writes. ` +
  `Actions: ${GC_RESEARCH_OPERATION_AUTHORIZATION_ACTIONS.join(", ")}. All actions require run_id. ` +
  `request requires ${GC_RESEARCH_OPERATION_AUTHORIZATION_REQUEST_REQUIRED_FIELDS.join(", ")} ` +
  `(operation_kind one of ${RESEARCH_HIGH_RISK_OPERATION_KINDS.join("/")}); it lands PROPOSED with a ` +
  `default-deny policy basis and is never auto-approved. decide requires authorization_id + approve ` +
  `(approval is admin-gated server-side and requires the run's snapshotted egress policy to permit the ` +
  `(data_class, destination_class, requested_form) tuple; an AUTONOMOUS run may propose but never approve). ` +
  `consume/get require authorization_id. The proposing/deciding actor is server-populated from the ` +
  `authenticated context and is never a body field. Reads also route through gc_query under the ` +
  `/api/v1/research-runs allow-list.`;

/**
 * Pure adapter handler for gc_research_operation_authorization. Validates
 * required fields, re-shapes snake_case body fields to the backend camelCase DTO
 * wire shape, and dispatches to lib.js. index.js wraps the return in ok().
 */
export async function gcResearchOperationAuthorizationToolHandler(args) {
  reqArg(args, "run_id", args.action);
  switch (args.action) {
    case "request": {
      for (const key of GC_RESEARCH_OPERATION_AUTHORIZATION_REQUEST_REQUIRED_FIELDS) reqArg(args, key, "request");
      return requestResearchOperationAuthorization(args.run_id, toRequestBody(args), args.project);
    }
    case "decide": {
      reqArg(args, "authorization_id", "decide");
      reqArg(args, "approve", "decide");
      return decideResearchOperationAuthorization(
        args.run_id,
        args.authorization_id,
        { approve: args.approve, note: args.note },
        args.project,
      );
    }
    case "consume": {
      reqArg(args, "authorization_id", "consume");
      return consumeResearchOperationAuthorization(args.run_id, args.authorization_id, args.project);
    }
    case "list":
      return listResearchOperationAuthorizations(args.run_id, args.project);
    case "get": {
      reqArg(args, "authorization_id", "get");
      return getResearchOperationAuthorization(args.run_id, args.authorization_id, args.project);
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

function toRequestBody(args) {
  const body = pick(args, GC_RESEARCH_OPERATION_AUTHORIZATION_REQUEST_BODY_FIELDS);
  return {
    operationKind: body.operation_kind,
    dataClass: body.data_class,
    destinationClass: body.destination_class,
    requestedForm: body.requested_form,
    toolId: body.tool_id,
    sandboxProfile: body.sandbox_profile,
    targetClass: body.target_class,
    expiresAt: body.expires_at,
    summary: body.summary,
    sourceActionId: body.source_action_id,
  };
}
