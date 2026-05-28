// gc_risk_scenario: action-discriminated MCP adapter for the backend risk-scenario
// REST surface (ADR-035). Extracted from index.js so the handler logic is
// testable in isolation — see gc-risk-scenario.test.js for adapter-level
// tests covering create/update body shapes, required-field enforcement, and
// snake_case <-> camelCase mapping (issue #876, mirrors the issue #875 fix
// for gc_threat_model).

import { z } from "zod";
import {
  RISK_SCENARIO_STATUSES,
  RISK_SCENARIO_LINK_TARGET_TYPES, RISK_SCENARIO_LINK_TYPES,
  createRiskScenario, updateRiskScenario, deleteRiskScenario,
  transitionRiskScenarioStatus, getRiskScenarioRequirements, getRiskScenarioTrace,
  createRiskScenarioLink, deleteRiskScenarioLink,
  pick, reqArg,
} from "./lib.js";
import {
  linkCreateOptionalSharedZodFields,
  performLinkCreate,
} from "./link-create.js";

export const GC_RISK_SCENARIO_ACTIONS = [
  "create", "update", "delete", "transition", "requirements", "trace",
  "link_create", "link_delete",
];

// Snake_case body fields accepted by gc_risk_scenario.create — mirrors
// backend RiskScenarioRequest. uid is create-only; the update DTO has no uid.
export const GC_RISK_SCENARIO_CREATE_BODY_FIELDS = [
  "uid", "title", "threat", "method", "asset", "effect", "time_horizon",
];

// Snake_case body fields accepted by gc_risk_scenario.update — mirrors
// backend UpdateRiskScenarioRequest. All fields are optional (partial
// update); title and time_horizon retain their @Size caps server-side.
export const GC_RISK_SCENARIO_UPDATE_BODY_FIELDS = [
  "title", "threat", "method", "asset", "effect", "time_horizon",
];

// @NotBlank fields on backend RiskScenarioRequest. Enforced at the adapter
// boundary so callers get a clear `'X' is required` error instead of waiting
// for a backend 422.
export const GC_RISK_SCENARIO_CREATE_REQUIRED_FIELDS = [
  "uid", "title", "threat", "method", "asset", "effect", "time_horizon",
];

export const gcRiskScenarioZodShape = {
  action: z.enum(GC_RISK_SCENARIO_ACTIONS),
  id: z.string().uuid().optional(),
  uid: z.string().optional(),
  project: z.string().optional(),
  title: z.string().optional(),
  threat: z.string().optional(),
  method: z.string().optional(),
  asset: z.string().optional(),
  effect: z.string().optional(),
  time_horizon: z.string().optional(),
  status: z.enum(RISK_SCENARIO_STATUSES).optional(),
  scenario_id: z.string().uuid().optional(),
  target_type: z.enum(RISK_SCENARIO_LINK_TARGET_TYPES).optional(),
  link_type: z.enum(RISK_SCENARIO_LINK_TYPES).optional(),
  ...linkCreateOptionalSharedZodFields,
  link_id: z.string().uuid().optional(),
};

export const GC_RISK_SCENARIO_DESCRIPTION =
  `Risk scenarios + their links. Actions: ${GC_RISK_SCENARIO_ACTIONS.join(", ")}. ` +
  `create requires: ${GC_RISK_SCENARIO_CREATE_REQUIRED_FIELDS.join(", ")}. ` +
  `update accepts the same fields minus uid, all optional. ` +
  `status is consumed only by the transition action (creation defaults to DRAFT). ` +
  `requirements returns all requirements linked to the risk scenario (requires id). ` +
  `trace returns the full end-to-end trace: assets, controls, requirements, and per-requirement ` +
  `implementing artifacts (requires id). ` +
  `link_create requires target_type + link_type; for internal target types ` +
  `(OBSERVATION / ASSET / REQUIREMENT / RISK_REGISTER_RECORD / RISK_ASSESSMENT_RESULT / ` +
  `TREATMENT_PLAN / METHODOLOGY_PROFILE / CONTROL / THREAT_MODEL / FINDING / AUDIT_RECORD / ` +
  `EVIDENCE) pass target_entity_id; for external types (VULNERABILITY / EXTERNAL) ` +
  `pass target_identifier. target_url and target_title are optional. ` +
  `Reads (list, get, links_list) route through gc_query.`;

/**
 * Pure adapter handler for gc_risk_scenario. Validates required fields, picks
 * action-scoped body fields, and dispatches to the corresponding lib.js call.
 * Returns the raw value the lib call produces (or null for delete-style 204s);
 * the index.js registration wraps the return in the MCP `ok()` envelope.
 */
export async function gcRiskScenarioToolHandler(args) {
  switch (args.action) {
    case "create": {
      for (const key of GC_RISK_SCENARIO_CREATE_REQUIRED_FIELDS) reqArg(args, key, "create");
      return createRiskScenario(pick(args, GC_RISK_SCENARIO_CREATE_BODY_FIELDS), args.project);
    }
    case "update": {
      reqArg(args, "id", "update");
      return updateRiskScenario(args.id, pick(args, GC_RISK_SCENARIO_UPDATE_BODY_FIELDS), args.project);
    }
    case "delete": {
      reqArg(args, "id", "delete");
      await deleteRiskScenario(args.id, args.project);
      return null;
    }
    case "transition": {
      reqArg(args, "id", "transition");
      reqArg(args, "status", "transition");
      return transitionRiskScenarioStatus(args.id, args.status, args.project);
    }
    case "requirements": {
      reqArg(args, "id", "requirements");
      return getRiskScenarioRequirements(args.id, args.project);
    }
    case "trace": {
      reqArg(args, "id", "trace");
      return getRiskScenarioTrace(args.id, args.project);
    }
    case "link_create": {
      return performLinkCreate(args, "scenario_id", createRiskScenarioLink);
    }
    case "link_delete": {
      reqArg(args, "scenario_id", "link_delete");
      reqArg(args, "link_id", "link_delete");
      await deleteRiskScenarioLink(args.scenario_id, args.link_id, args.project);
      return null;
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}
