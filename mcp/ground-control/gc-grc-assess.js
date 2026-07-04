// gc_grc_assess: bounded MCP adapter for the on-demand GRC assessment lane
// (GC-GRC-016). The backend owns durable run records, partition/merge
// semantics, review state, and graph-effect execution. This adapter only
// validates the fixed command shape and dispatches to the fixed REST surface.

import { z } from "zod";
import {
  createGrcAssessmentRun,
  getGrcAssessmentRun,
  listGrcAssessmentRuns,
  pick,
  reqArg,
  reviewGrcAssessmentRun,
} from "./lib.js";

export const GC_GRC_ASSESS_ACTIONS = ["run", "review", "get", "list"];
export const GC_GRC_ASSESS_MODES = ["model", "reassess", "re_screen"];
export const GC_GRC_ASSESS_SCOPE_TYPES = [
  "whole_project",
  "package_path_set",
  "boundary",
  "asset",
  "named_threat_set",
  "named_risk_set",
  "stale_drift_set",
];

const REVIEW_POLICIES = ["required", "optional", "disabled"];
const REVIEW_DECISIONS = ["request_review", "approved", "rejected"];

const MODE_MAP = {
  model: "MODEL",
  reassess: "REASSESS",
  re_screen: "RE_SCREEN",
};

const SCOPE_TYPE_MAP = {
  whole_project: "WHOLE_PROJECT",
  package_path_set: "PACKAGE_PATH_SET",
  boundary: "BOUNDARY",
  asset: "ASSET",
  named_threat_set: "NAMED_THREAT_SET",
  named_risk_set: "NAMED_RISK_SET",
  stale_drift_set: "STALE_DRIFT_SET",
};

const REVIEW_POLICY_MAP = {
  required: "REQUIRED",
  optional: "OPTIONAL",
  disabled: "DISABLED",
};

const REVIEW_DECISION_MAP = {
  request_review: "REQUEST_REVIEW",
  approved: "APPROVED",
  rejected: "REJECTED",
};

export const GC_GRC_ASSESS_RUN_FIELDS = [
  "mode",
  "scope_type",
  "scope_values",
  "commit_sha",
  "base_commit_sha",
  "languages",
  "surfaces",
  "declared_boundaries",
  "threat_pack_id",
  "threat_pack_version",
  "review_policy",
  "review_decision",
  "idempotency_key",
  "partition_limit",
];

export const GC_GRC_ASSESS_REVIEW_FIELDS = [
  "review_decision",
  "reviewed_by",
  "review_rationale",
];

const declaredBoundarySchema = z.object({
  key: z.string().min(1).max(120),
  name: z.string().min(1).max(200),
  description: z.string().max(2000).optional(),
  path_selectors: z.array(z.string().min(1).max(500)).optional(),
  paths: z.array(z.string().min(1).max(500)).optional(),
  surfaces: z.array(z.string().min(1).max(80)).optional(),
});

export const gcGrcAssessZodShape = {
  action: z.enum(GC_GRC_ASSESS_ACTIONS),
  project: z.string().optional(),
  id: z.string().uuid().optional(),
  mode: z.enum(GC_GRC_ASSESS_MODES).optional(),
  scope_type: z.enum(GC_GRC_ASSESS_SCOPE_TYPES).optional(),
  scope_values: z.array(z.string().min(1).max(500)).max(500).optional(),
  commit_sha: z.string().regex(/^[0-9a-fA-F]{7,64}$/).optional(),
  base_commit_sha: z.string().regex(/^[0-9a-fA-F]{7,64}$/).optional(),
  languages: z.array(z.string().min(1).max(80)).max(50).optional(),
  surfaces: z.array(z.string().min(1).max(80)).max(50).optional(),
  declared_boundaries: z.array(declaredBoundarySchema).max(100).optional(),
  threat_pack_id: z.string().min(1).max(200).optional(),
  threat_pack_version: z.string().max(100).optional(),
  review_policy: z.enum(REVIEW_POLICIES).optional(),
  review_decision: z.enum(REVIEW_DECISIONS).optional(),
  reviewed_by: z.string().max(200).optional(),
  review_rationale: z.string().max(2000).optional(),
  idempotency_key: z.string().max(200).optional(),
  partition_limit: z.number().int().positive().max(500).optional(),
  limit: z.number().int().positive().max(100).optional(),
};

export const GC_GRC_ASSESS_DESCRIPTION =
  `On-demand GRC assessment lane (GC-GRC-016). Actions: ${GC_GRC_ASSESS_ACTIONS.join(", ")}. ` +
  `run creates a durable assessment run record over mode={model,reassess,re_screen} and ` +
  `scope_type={${GC_GRC_ASSESS_SCOPE_TYPES.join(",")}}; it partitions and deduplicates server-side. ` +
  `review approves or rejects a READY_FOR_REVIEW run; approval commits graph effects through the shared ` +
  `derivation-backed engine. get/list read durable lane-run records. ` +
  `Required fields: run→{mode,scope_type}; review/get→{id}; review→{review_decision}. ` +
  `For model/reassess commits, pass commit_sha,languages,surfaces; boundary scopes should pass ` +
  `declared_boundaries with path_selectors so approved partition commits can use gc_derivation semantics.`;

export async function gcGrcAssessToolHandler(args) {
  switch (args.action) {
    case "run": {
      reqArg(args, "mode", "run");
      reqArg(args, "scope_type", "run");
      return createGrcAssessmentRun(toRunBody(args), args.project);
    }
    case "review": {
      reqArg(args, "id", "review");
      reqArg(args, "review_decision", "review");
      return reviewGrcAssessmentRun(args.id, toReviewBody(args), args.project);
    }
    case "get": {
      reqArg(args, "id", "get");
      return getGrcAssessmentRun(args.id, args.project);
    }
    case "list":
      return listGrcAssessmentRuns({ project: args.project, limit: args.limit });
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

function toRunBody(args) {
  const body = pick(args, GC_GRC_ASSESS_RUN_FIELDS);
  body.mode = MODE_MAP[body.mode];
  body.scope_type = SCOPE_TYPE_MAP[body.scope_type];
  if (body.review_policy != null) {
    body.review_policy = REVIEW_POLICY_MAP[body.review_policy];
  }
  if (body.review_decision != null) {
    body.review_decision = REVIEW_DECISION_MAP[body.review_decision];
  }
  if (Array.isArray(body.declared_boundaries)) {
    body.declared_boundaries = body.declared_boundaries.map((boundary) => ({
      ...boundary,
      path_selectors: boundary.path_selectors ?? boundary.paths ?? [],
    }));
  }
  return body;
}

function toReviewBody(args) {
  const body = pick(args, GC_GRC_ASSESS_REVIEW_FIELDS);
  body.review_decision = REVIEW_DECISION_MAP[body.review_decision];
  return body;
}
